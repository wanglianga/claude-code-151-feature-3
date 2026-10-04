package com.example.library.service;

import com.example.library.domain.*;
import com.example.library.repo.*;
import com.example.library.web.ApiException;
import com.example.library.web.Views;
import com.example.library.web.dto.BatchView;
import com.example.library.web.dto.TaskView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 跨馆调拨物流：出库打包（批次 + 包裹编号 + ETA）、到馆签收、丢包登记。
 */
@Service
public class TransferService {

    private final TransferTaskRepository tasks;
    private final TransferBatchRepository batches;
    private final ItemRepository items;
    private final HoldRepository holds;
    private final PolicyService policy;
    private final HoldService holdService;
    private final FineService fines;
    private final NotificationService notifications;
    private final AppClock clock;

    public TransferService(TransferTaskRepository tasks, TransferBatchRepository batches,
                           ItemRepository items, HoldRepository holds, PolicyService policy,
                           HoldService holdService, FineService fines,
                           NotificationService notifications, AppClock clock) {
        this.tasks = tasks;
        this.batches = batches;
        this.items = items;
        this.holds = holds;
        this.policy = policy;
        this.holdService = holdService;
        this.fines = fines;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TransferTask> pendingPickup() {
        return tasks.findByStatusOrderByCreatedAt(TaskStatus.CREATED);
    }

    /** 物流员取书：逐册核验条码与出库馆，生成批次与包裹编号，记录 ETA */
    @Transactional
    public TransferBatch pickup(List<Long> taskIds, String staffName) {
        if (taskIds == null || taskIds.isEmpty()) {
            throw ApiException.unprocessable("至少选择一个调拨任务出库");
        }
        TransferBatch batch = new TransferBatch();
        batch.setBatchNo("B" + clock.now().toEpochMilli()
                + "-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        batch.setStaffName(staffName);
        batch.setStatus("OPEN");
        batch.setCreatedAt(clock.now());
        batches.save(batch);

        int seq = 1;
        for (Long id : taskIds) {
            TransferTask t = tasks.findById(id)
                    .orElseThrow(() -> ApiException.notFound("调拨任务不存在: " + id));
            if (t.getStatus() != TaskStatus.CREATED) {
                throw ApiException.conflict("任务 " + id + " 状态为 " + t.getStatus() + "，不能出库");
            }
            Item item = items.lockById(t.getItem().getId()).orElseThrow();
            if (item.getStatus() != ItemStatus.LOCKED) {
                throw ApiException.conflict("条码 " + item.getBarcode() + " 状态为 "
                        + item.getStatus() + "，与调拨任务不一致");
            }
            if (!item.getCurrentLibrary().getId().equals(t.getFromLibrary().getId())) {
                throw ApiException.conflict("条码 " + item.getBarcode()
                        + " 实际所在馆与出库馆记录不一致，已中止出库");
            }
            boolean sameRegion = t.getFromLibrary().getRegion().equals(t.getToLibrary().getRegion());
            long etaHours = policy.etaHours(sameRegion);

            t.setStatus(TaskStatus.PICKED_UP);
            t.setBatch(batch);
            t.setPackageNo(batch.getBatchNo() + "-" + String.format("%03d", seq++));
            t.setPickedAt(clock.now());
            t.setEta(clock.now().plus(etaHours, ChronoUnit.HOURS));

            item.setStatus(ItemStatus.IN_TRANSIT);
        }
        return batch;
    }

    /** 图书到馆签收：入预约架通知读者；若预约已取消/失效则直接再分配给下一位 */
    @Transactional
    public TransferTask arrive(String packageNo) {
        TransferTask t = tasks.findByPackageNo(packageNo)
                .orElseThrow(() -> ApiException.notFound("包裹编号不存在: " + packageNo));
        if (t.getStatus() != TaskStatus.PICKED_UP) {
            throw ApiException.conflict("包裹 " + packageNo + " 状态为 " + t.getStatus() + "，不能签收");
        }
        // 全局加锁顺序：预约集 → 馆藏行（与预约/还书路径一致，防死锁）
        if (t.getHold() != null) {
            holdService.lockActiveSet(t.getHold().getBib().getId());
        }
        Item item = items.lockById(t.getItem().getId()).orElseThrow();
        t.setStatus(TaskStatus.ARRIVED);
        t.setArrivedAt(clock.now());
        item.setCurrentLibrary(t.getToLibrary());

        Hold h = t.getHold();
        boolean releasedEarly = t.isReleaseOnArrival()
                || h == null
                || h.getStatus() == HoldStatus.CANCELLED
                || h.getStatus() == HoldStatus.EXPIRED;

        if (releasedEarly) {
            // 原预约已失效：到馆即进入再分配（同馆首位排队者上架，否则在目的馆变可借）
            holdService.reallocate(item.getId(), t.getToLibrary());
        } else {
            item.setStatus(ItemStatus.ON_HOLD);
            h.setItem(item);
            h.setStatus(HoldStatus.READY);
            h.setQueuePosition(null);
            Instant readyAt = clock.now();
            h.setReadyAt(readyAt);
            Instant expiry = readyAt.plus(policy.pickupDays(), ChronoUnit.DAYS);
            if (t.getToLibrary().isTemporaryClosed()) {
                expiry = expiry.plus(policy.closureGraceHours(), ChronoUnit.HOURS);
            }
            h.setExpiresAt(expiry);
            notifications.notify(h.getReader(), "READY", "取书通知",
                    "您跨馆预约的《" + h.getBib().getTitle() + "》已到" + t.getToLibrary().getName()
                            + "，包裹号 " + packageNo + "，取书窗口 " + readyAt + " 至 " + expiry
                            + "，请于 " + expiry + " 前取书。");
        }
        return t;
    }

    /**
     * 物流丢包：图书置 LOST、原预约终止并通知读者（读者免责，由队列后续等待）、
     * 生成物流赔偿单（chargedTo=LOGISTICS）。队列重排在 HoldService 侧由预约终止触发。
     */
    @Transactional
    public TransferTask reportLost(Long taskId, String note) {
        TransferTask t = tasks.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("调拨任务不存在: " + taskId));
        if (t.getStatus() != TaskStatus.PICKED_UP && t.getStatus() != TaskStatus.CREATED) {
            throw ApiException.conflict("任务状态为 " + t.getStatus() + "，不能登记丢包");
        }
        Hold h0 = t.getHold();
        if (h0 != null) {
            holdService.lockActiveSet(h0.getBib().getId());
        }
        Item item = items.lockById(t.getItem().getId()).orElseThrow();
        t.setStatus(TaskStatus.LOST);
        item.setStatus(ItemStatus.LOST);

        Hold h = t.getHold();
        if (h != null && (h.getStatus() == HoldStatus.QUEUED || h.getStatus() == HoldStatus.READY)) {
            holdService.onItemLost(h);
        }

        String basis = "物流丢包按 fee.logisticsLoss=" + FineService.money(policy.feeLogisticsLoss())
                + " 元/册由物流方赔付馆方，读者免责";
        fines.create(h == null ? null : h.getReader(), FineType.LOGISTICS_LOSS, ChargedTo.LOGISTICS,
                policy.feeLogisticsLoss(),
                "调拨丢包：条码 " + item.getBarcode() + "，" + t.getFromLibrary().getCode()
                        + "→" + t.getToLibrary().getCode() + (note == null ? "" : "，备注：" + note),
                basis, null, t);
        return t;
    }

    @Transactional(readOnly = true)
    public List<TaskView> pendingPickupViews() {
        return pendingPickup().stream().map(Views::task).toList();
    }

    @Transactional(readOnly = true)
    public List<TaskView> batchTaskViews(Long batchId) {
        return byBatch(batchId).stream().map(Views::task).toList();
    }

    @Transactional(readOnly = true)
    public List<BatchView> openBatchViews() {
        return openBatches().stream().map(Views::batch).toList();
    }

    @Transactional(readOnly = true)
    public TaskView taskView(Long id) {
        return Views.task(tasks.findById(id)
                .orElseThrow(() -> ApiException.notFound("调拨任务不存在: " + id)));
    }

    @Transactional
    public BatchView pickupView(List<Long> taskIds, String staffName) {
        return Views.batch(pickup(taskIds, staffName));
    }

    @Transactional
    public TaskView arriveView(String packageNo) {
        return Views.task(arrive(packageNo));
    }

    @Transactional
    public TaskView reportLostView(Long taskId, String note) {
        return Views.task(reportLost(taskId, note));
    }

    @Transactional
    public BatchView closeBatchView(Long batchId) {
        return Views.batch(closeBatch(batchId));
    }

    @Transactional(readOnly = true)
    public List<TransferTask> byBatch(Long batchId) {
        return tasks.findByBatchId(batchId);
    }

    @Transactional(readOnly = true)
    public List<TransferBatch> openBatches() {
        return batches.findByStatusOrderByCreatedAtDesc("OPEN");
    }

    @Transactional
    public TransferBatch closeBatch(Long batchId) {
        TransferBatch b = batches.findById(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
        boolean stillOpen = tasks.findByBatchId(batchId).stream()
                .anyMatch(t -> t.getStatus() == TaskStatus.CREATED || t.getStatus() == TaskStatus.PICKED_UP);
        if (stillOpen) {
            throw ApiException.conflict("批次中仍有未完成（未到馆/未丢包结案）的包裹，不能关闭");
        }
        b.setStatus("CLOSED");
        b.setClosedAt(clock.now());
        return b;
    }
}
