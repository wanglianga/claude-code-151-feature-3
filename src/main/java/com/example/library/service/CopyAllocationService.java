package com.example.library.service;

import com.example.library.domain.*;
import com.example.library.repo.*;
import com.example.library.web.ApiException;
import com.example.library.web.dto.admin.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 新增复本分配与热门书队列调整。
 *
 * 流程：馆员登记到馆新复本（采购批次）→ 系统按“预约时间 + 各分馆读者等待时长”
 * 均衡给出每册分配建议（避免新复本全部流向总馆）→ 馆员可采纳或手动改派
 * （改派原因强制写入馆藏流转记录）→ 确认后执行：本馆即上架待取、跨馆生成调拨，
 * 通知中写明预计取书窗口（跨馆含物流 ETA）。
 *
 * 一致性：所有执行入口沿用全局锁顺序——先锁该书目活跃预约行，再锁具体馆藏行。
 */
@Service
public class CopyAllocationService {

    private final ItemAcquisitionRepository acquisitions;
    private final CopyAllocationRepository allocations;
    private final HoldRenewalFreezeRepository freezes;
    private final CollectionFlowLogRepository flowLogs;
    private final BibRepository bibs;
    private final ItemRepository items;
    private final HoldRepository holds;
    private final LibraryRepository libraries;
    private final TransferTaskRepository tasks;
    private final HoldService holdService;
    private final PolicyService policy;
    private final NotificationService notifications;
    private final AppClock clock;

    public CopyAllocationService(ItemAcquisitionRepository acquisitions,
                                 CopyAllocationRepository allocations,
                                 HoldRenewalFreezeRepository freezes,
                                 CollectionFlowLogRepository flowLogs,
                                 BibRepository bibs, ItemRepository items, HoldRepository holds,
                                 LibraryRepository libraries, TransferTaskRepository tasks,
                                 HoldService holdService,
                                 PolicyService policy, NotificationService notifications, AppClock clock) {
        this.acquisitions = acquisitions;
        this.allocations = allocations;
        this.freezes = freezes;
        this.flowLogs = flowLogs;
        this.bibs = bibs;
        this.items = items;
        this.holds = holds;
        this.libraries = libraries;
        this.tasks = tasks;
        this.holdService = holdService;
        this.policy = policy;
        this.notifications = notifications;
        this.clock = clock;
    }

    // ---------------- 采购批次到馆 + 系统分配建议 ----------------

    /**
     * 登记一批新采购复本到馆（落在收货馆，默认中心馆）。
     * 复本以 RECEIVED 状态暂存（不计入在架可借、不会被普通预约抢占），
     * 同事务内系统生成逐册均衡分配建议，等待馆员确认。
     */
    @Transactional
    public ItemAcquisition registerAcquisition(Long bibId, int copyCount, String receivingLibraryCode,
                                               String staff, String note) {
        if (copyCount <= 0 || copyCount > 100) {
            throw ApiException.unprocessable("采购册数须在 1~100 之间");
        }
        Bib bib = bibs.findById(bibId).orElseThrow(() -> ApiException.notFound("书目不存在: " + bibId));
        String code = receivingLibraryCode == null || receivingLibraryCode.isBlank()
                ? "CENTRAL" : receivingLibraryCode;
        Library receiving = libraries.findByCode(code)
                .orElseThrow(() -> ApiException.notFound("收货分馆不存在: " + code));
        if (!receiving.isActive()) {
            throw ApiException.unprocessable("收货分馆已停止服务");
        }
        // 与其他“预约↔馆藏”入口一致：先锁该书目活跃预约行，避免并发采购/预约产生重复分配建议
        holds.lockActiveByBib(bib.getId(), CatalogService.ACTIVE_HOLD_STATUSES);
        Instant now = clock.now();

        ItemAcquisition acq = new ItemAcquisition();
        acq.setBib(bib);
        acq.setCopyCount(copyCount);
        acq.setStatus(AcquisitionStatus.RECEIVED);
        acq.setStaff(staff);
        acq.setNote(note);
        acq.setCreatedAt(now);
        acquisitions.save(acq);

        List<Item> newItems = new ArrayList<>();
        for (int i = 1; i <= copyCount; i++) {
            Item item = new Item();
            item.setBarcode(String.format("N-%d-%03d", acq.getId(), i));
            item.setBib(bib);
            item.setHomeLibrary(receiving);
            item.setCurrentLibrary(receiving);
            item.setStatus(ItemStatus.RECEIVED);
            item.setCondition("GOOD");
            items.save(item);
            newItems.add(item);
        }

        log(bib, null, null, "ACQUISITION",
                "新增采购到馆 " + copyCount + " 册《" + bib.getTitle() + "》，收货馆 "
                        + receiving.getName() + "，条码 " + newItems.get(0).getBarcode()
                        + " 起；登记人：" + staff + (note == null || note.isBlank() ? "" : "；备注：" + note),
                staff);

        generateSuggestions(acq, newItems, receiving, now);
        return acq;
    }

    /**
     * 均衡分配建议：逐册贪心。为每位未满足排队读者计算两个序号——
     * 全局预约时间序号（FIFO，越早越小）与“分馆轮次序号”
     * （各分馆先服务本馆第 1 位、再服务第 2 位……轮内按预约时间，保证不会连续给同一分馆），
     * 按 score = (1-w)·FIFO序号 + w·轮次序号 取最小者，且每轮只在各分馆队首之间挑选。
     * w 由规则 allocation.balanceWeight 控制：0=完全按预约时间，1=完全按分馆轮次均衡。
     */
    private void generateSuggestions(ItemAcquisition acq, List<Item> newItems, Library receiving, Instant now) {
        List<Hold> all = holds.findActiveByBib(
                        acq.getBib().getId(), List.of(HoldStatus.QUEUED)).stream()
                .filter(h -> h.getItem() == null)
                .sorted(Comparator.comparing(Hold::getCreatedAt).thenComparing(Hold::getId))
                .toList();

        Map<Long, Integer> fifoIndex = new HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            fifoIndex.put(all.get(i).getId(), i);
        }
        Map<Long, Integer> roundRobinIndex = roundRobinOrder(all);

        // 每个分馆一条按预约时间有序的待服务队列；逐册只在各队首之间挑选
        Map<Long, Deque<Hold>> byLibrary = new LinkedHashMap<>();
        for (Hold h : all) {
            byLibrary.computeIfAbsent(h.getPickupLibrary().getId(), k -> new ArrayDeque<>()).addLast(h);
        }

        double w = Math.max(0.0, Math.min(1.0, policy.allocationBalanceWeight()));
        for (Item item : newItems) {
            CopyAllocation a = new CopyAllocation();
            a.setAcquisition(acq);
            a.setItem(item);
            a.setStatus(AllocationStatus.SUGGESTED);
            a.setDecidedBy(AllocationDecidedBy.SYSTEM);
            a.setCreatedAt(now);

            Hold picked = null;
            if (!byLibrary.isEmpty()) {
                picked = pickBalanced(byLibrary, fifoIndex, roundRobinIndex, w);
                Deque<Hold> q = byLibrary.get(picked.getPickupLibrary().getId());
                q.pollFirst();
                if (q.isEmpty()) {
                    byLibrary.remove(picked.getPickupLibrary().getId());
                }
            }

            if (picked == null) {
                // 复本多于等待人数：建议在收货馆直接上架可借
                a.setHold(null);
                a.setTargetLibrary(receiving);
                a.setFulfillMode("SHELF");
                a.setEtaHours(0);
            } else {
                Library target = picked.getPickupLibrary();
                boolean local = target.getId().equals(receiving.getId());
                long eta = local ? 0
                        : policy.etaHours(target.getRegion().equals(receiving.getRegion()));
                a.setHold(picked);
                a.setTargetLibrary(target);
                a.setFulfillMode(local ? "LOCAL" : "TRANSFER");
                a.setEtaHours(eta);
                a.setPickupWindowEnd(pickupWindowEnd(target, now.plus(eta, ChronoUnit.HOURS)));
            }
            allocations.save(a);
            log(acq.getBib(), item, picked, "ALLOCATION_SYSTEM",
                    "系统建议：新购复本 " + item.getBarcode()
                            + (picked == null ? " 在 " + receiving.getName() + "上架可借（当前无未满足排队）"
                            : " 分配给预约 #" + picked.getId() + "（读者 " + picked.getReader().getCardNo()
                            + "，取书馆 " + targetLibName(a) + "，方式 " + modeText(a.getFulfillMode())
                            + (a.getEtaHours() > 0 ? "，ETA " + a.getEtaHours() + " 小时" : "")
                            + "）；综合预约时间(FIFO#" + (picked == null ? "-" : fifoIndex.get(picked.getId()))
                            + ")与分馆轮次(轮#" + (picked == null ? "-" : roundRobinIndex.get(picked.getId()))
                            + ")，均衡权重 " + w),
                    null);
        }
    }

    /**
     * 分馆轮次序号：第 0 轮各馆本馆最早的一位先上（按预约时间排序），
     * 第 1 轮再各馆第二位……保证同一分馆不会在其他分馆的首位之前连续得到第二册。
     */
    private Map<Long, Integer> roundRobinOrder(List<Hold> allSorted) {
        Map<Long, List<Hold>> byLib = new LinkedHashMap<>();
        for (Hold h : allSorted) {
            byLib.computeIfAbsent(h.getPickupLibrary().getId(), k -> new ArrayList<>()).add(h);
        }
        Map<Long, Integer> order = new HashMap<>();
        int depth = 0;
        int index = 0;
        while (true) {
            final int d = depth;
            List<Hold> round = byLib.values().stream()
                    .filter(list -> list.size() > d)
                    .map(list -> list.get(d))
                    .sorted(Comparator.comparing(Hold::getCreatedAt).thenComparing(Hold::getId))
                    .toList();
            if (round.isEmpty()) {
                break;
            }
            for (Hold h : round) {
                order.put(h.getId(), index++);
            }
            depth++;
        }
        return order;
    }

    /** 在各分馆队首中选 FIFO/轮次综合序号最小者；平分时先保分馆均衡，再看预约时间 */
    private Hold pickBalanced(Map<Long, Deque<Hold>> byLibrary,
                              Map<Long, Integer> fifoIndex, Map<Long, Integer> roundRobinIndex, double w) {
        Hold best = null;
        double bestScore = Double.MAX_VALUE;
        for (Deque<Hold> q : byLibrary.values()) {
            Hold h = q.peekFirst();
            double score = (1 - w) * fifoIndex.get(h.getId()) + w * roundRobinIndex.get(h.getId());
            if (best == null || score < bestScore
                    || (score == bestScore && beforeBalanced(h, best, fifoIndex, roundRobinIndex))) {
                bestScore = score;
                best = h;
            }
        }
        return best;
    }

    private boolean beforeBalanced(Hold a, Hold b, Map<Long, Integer> fifoIndex,
                                   Map<Long, Integer> roundRobinIndex) {
        int rr = Integer.compare(roundRobinIndex.get(a.getId()), roundRobinIndex.get(b.getId()));
        if (rr != 0) {
            return rr < 0; // 平分时优先分馆等待更久的一方，避免新复本集中到总馆
        }
        int time = a.getCreatedAt().compareTo(b.getCreatedAt());
        return time < 0 || (time == 0 && a.getId() < b.getId());
    }

    // ---------------- 馆员手动改派（原因强制留痕） ----------------

    /**
     * 馆员调整某册的系统建议：改派给其他排队预约，或跳过分配直接上架。
     * 调整动作与原因立即写入馆藏流转记录；调整后仍需馆员确认才执行。
     */
    @Transactional
    public CopyAllocation adjustSuggestion(Long allocationId, Long targetHoldId, boolean skip,
                                           String shelfLibraryCode, String staff, String reason) {
        if (staff == null || staff.isBlank()) {
            throw ApiException.unprocessable("手动调整必须填写操作馆员姓名");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("手动调整必须填写调整原因（将记入馆藏流转记录）");
        }
        CopyAllocation a = mustSuggestion(allocationId);
        // 改派会重新指定预约归属：先锁该书目预约集（全局锁顺序），串行化同书目的并发手动调整
        holds.lockActiveByBib(a.getAcquisition().getBib().getId(), CatalogService.ACTIVE_HOLD_STATUSES);
        Instant now = clock.now();

        String original = describeTarget(a);
        if (skip) {
            Library shelf = (shelfLibraryCode == null || shelfLibraryCode.isBlank())
                    ? a.getItem().getHomeLibrary()
                    : libraries.findByCode(shelfLibraryCode)
                        .orElseThrow(() -> ApiException.notFound("上架分馆不存在: " + shelfLibraryCode));
            a.setHold(null);
            a.setTargetLibrary(shelf);
            a.setFulfillMode("SHELF");
            a.setEtaHours(0);
            a.setPickupWindowEnd(null);
        } else {
            if (targetHoldId == null) {
                throw ApiException.unprocessable("请指定改派目标预约，或明确跳过分配");
            }
            Hold target = holds.lockById(targetHoldId)
                    .orElseThrow(() -> ApiException.notFound("预约不存在: " + targetHoldId));
            if (!target.getBib().getId().equals(a.getAcquisition().getBib().getId())) {
                throw ApiException.unprocessable("目标预约不属于本书目");
            }
            if (target.getStatus() != HoldStatus.QUEUED || target.getItem() != null) {
                throw ApiException.conflict("预约 #" + targetHoldId + " 当前状态为 "
                        + target.getStatus() + "，已不处于待分配排队");
            }
            boolean duplicate = allocations.findByAcquisitionIdOrderById(a.getAcquisition().getId()).stream()
                    .anyMatch(o -> !o.getId().equals(a.getId())
                            && o.getStatus() == AllocationStatus.SUGGESTED
                            && o.getHold() != null && o.getHold().getId().equals(targetHoldId));
            if (duplicate) {
                throw ApiException.conflict("预约 #" + targetHoldId + " 已被本批次另一册的分配建议占用");
            }
            Library receiving = a.getItem().getHomeLibrary();
            Library tLib = target.getPickupLibrary();
            boolean local = tLib.getId().equals(receiving.getId());
            long eta = local ? 0 : policy.etaHours(tLib.getRegion().equals(receiving.getRegion()));
            a.setHold(target);
            a.setTargetLibrary(tLib);
            a.setFulfillMode(local ? "LOCAL" : "TRANSFER");
            a.setEtaHours(eta);
            a.setPickupWindowEnd(pickupWindowEnd(tLib, now.plus(eta, ChronoUnit.HOURS)));
        }
        a.setDecidedBy(AllocationDecidedBy.STAFF);
        a.setStaff(staff);
        a.setReason(reason);

        log(a.getAcquisition().getBib(), a.getItem(), a.getHold(), "ALLOCATION_MANUAL",
                "馆员 " + staff + " 调整新购复本 " + a.getItem().getBarcode() + " 的分配建议："
                        + original + " → " + describeTarget(a) + "；调整原因：" + reason,
                staff);
        return a;
    }

    // ---------------- 馆员确认并执行分配 ----------------

    @Transactional
    public CopyAllocation confirmAllocation(Long allocationId, String staff) {
        CopyAllocation a = mustSuggestion(allocationId);
        // 全局锁顺序：该书目活跃预约行 → 该馆藏行
        holds.lockActiveByBib(a.getAcquisition().getBib().getId(), CatalogService.ACTIVE_HOLD_STATUSES);
        executeAllocation(a, staff);
        refreshBatchStatus(a.getAcquisition());
        return a;
    }

    @Transactional
    public ItemAcquisition confirmAll(Long acquisitionId, String staff) {
        if (staff == null || staff.isBlank()) {
            throw ApiException.unprocessable("确认执行必须填写操作馆员姓名");
        }
        ItemAcquisition acq = mustAcquisition(acquisitionId);
        holds.lockActiveByBib(acq.getBib().getId(), CatalogService.ACTIVE_HOLD_STATUSES);
        for (CopyAllocation a : allocations.findByAcquisitionIdOrderById(acq.getId())) {
            if (a.getStatus() == AllocationStatus.SUGGESTED) {
                executeAllocation(a, staff);
            }
        }
        refreshBatchStatus(acq);
        return acq;
    }

    private void executeAllocation(CopyAllocation a, String staff) {
        Item item = items.lockById(a.getItem().getId()).orElseThrow();
        if (item.getStatus() != ItemStatus.RECEIVED) {
            throw ApiException.conflict("复本 " + item.getBarcode() + " 状态为 " + item.getStatus()
                    + "，不在待分配到馆状态");
        }
        Instant now = clock.now();
        Bib bib = a.getAcquisition().getBib();
        boolean manual = a.getDecidedBy() == AllocationDecidedBy.STAFF;

        if (a.getHold() == null || "SHELF".equals(a.getFulfillMode())) {
            // 暂无等待者 / 馆员手动跳过：在目标馆上架可借（馆员“本馆留存”决定，不主动喂队列；
            // 读者新预约或其他副本释放时仍会按外馆保护等既有规则使用它）
            item.setStatus(ItemStatus.AVAILABLE);
            item.setCurrentLibrary(a.getTargetLibrary());
            a.setStatus(AllocationStatus.SKIPPED);
            log(bib, item, null, "ALLOCATION_EXECUTE",
                    "新购复本 " + item.getBarcode() + " 在 " + a.getTargetLibrary().getName()
                            + " 上架可借（本次不直配排队预约）" + (manual ? "；馆员决定：" + a.getReason() : ""),
                    staff);
        } else {
            Hold h = holds.lockById(a.getHold().getId()).orElseThrow();
            if (h.getStatus() != HoldStatus.QUEUED || h.getItem() != null) {
                throw ApiException.conflict("预约 #" + h.getId() + " 已失效或已分配其他副本，请馆员重新调整后再确认");
            }
            h.setItem(item);
            a.setHold(h);
            if ("LOCAL".equals(a.getFulfillMode())) {
                item.setStatus(ItemStatus.ON_HOLD);
                item.setCurrentLibrary(a.getTargetLibrary());
                h.setStatus(HoldStatus.READY);
                h.setQueuePosition(null);
                h.setReadyAt(now);
                Instant expiry = pickupWindowEnd(a.getTargetLibrary(), now);
                h.setExpiresAt(expiry);
                a.setPickupWindowEnd(expiry);
                notifications.notify(h.getReader(), "READY", "取书通知（新购复本）",
                        "您排队的《" + bib.getTitle() + "》已由新购复本满足，现已在"
                                + a.getTargetLibrary().getName() + "预约架。"
                                + "预计取书窗口：即日至 " + expiry + "，请按期取书。");
            } else {
                Library source = item.getHomeLibrary();
                item.setStatus(ItemStatus.LOCKED);
                item.setCurrentLibrary(source);
                TransferTask t = new TransferTask();
                t.setHold(h);
                t.setItem(item);
                t.setFromLibrary(source);
                t.setToLibrary(a.getTargetLibrary());
                t.setStatus(TaskStatus.CREATED);
                t.setCreatedAt(now);
                tasks.save(t);
                Instant etaAt = now.plus(a.getEtaHours(), ChronoUnit.HOURS);
                Instant windowEnd = pickupWindowEnd(a.getTargetLibrary(), etaAt);
                a.setPickupWindowEnd(windowEnd);
                notifications.notify(h.getReader(), "ALLOCATION", "新购复本分配通知",
                        "您排队的《" + bib.getTitle() + "》已分配一册新购复本，将由"
                                + source.getName() + "配送到" + a.getTargetLibrary().getName()
                                + "，预计在途约 " + a.getEtaHours() + " 小时；预计取书窗口："
                                + etaAt + " 至 " + windowEnd
                                + "。到馆后我们会再次通知您取书。");
            }
            a.setStatus(manual ? AllocationStatus.MANUAL : AllocationStatus.CONFIRMED);
            log(bib, item, h, "ALLOCATION_EXECUTE",
                    "新购复本 " + item.getBarcode() + " 分配给预约 #" + h.getId() + "（读者 "
                            + h.getReader().getCardNo() + "，取书馆 " + a.getTargetLibrary().getName()
                            + "，方式 " + modeText(a.getFulfillMode()) + "）"
                            + (manual ? "；馆员手动调整：" + a.getReason() : "；采纳系统均衡建议"),
                    staff);
            renumber(bib);
        }
        a.setStaff(staff);
        a.setDecidedAt(now);
    }

    /** 取消采购批次：仅允许在没有任何复本已执行前取消；复本统一在收货馆上架可借 */
    @Transactional
    public ItemAcquisition cancelAcquisition(Long acquisitionId, String staff, String reason) {
        if (staff == null || staff.isBlank()) {
            throw ApiException.unprocessable("取消批次必须填写操作馆员姓名");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("取消批次必须填写原因");
        }
        ItemAcquisition acq = mustAcquisition(acquisitionId);
        List<CopyAllocation> rows = allocations.findByAcquisitionIdOrderById(acq.getId());
        if (rows.stream().anyMatch(a -> a.getStatus() == AllocationStatus.CONFIRMED
                || a.getStatus() == AllocationStatus.MANUAL)) {
            throw ApiException.conflict("批次中已有复本完成分配，不能整体取消");
        }
        // 全局锁顺序：先锁该书目活跃预约行，再逐册锁馆藏；取消后复本走常规再分配，不破坏队列公平性
        holds.lockActiveByBib(acq.getBib().getId(), CatalogService.ACTIVE_HOLD_STATUSES);
        Instant now = clock.now();
        List<Item> shelved = new ArrayList<>();
        for (CopyAllocation a : rows) {
            Item item = items.lockById(a.getItem().getId()).orElseThrow();
            if (item.getStatus() == ItemStatus.RECEIVED) {
                item.setStatus(ItemStatus.AVAILABLE);
                item.setCurrentLibrary(item.getHomeLibrary());
                shelved.add(item);
            }
            a.setStatus(AllocationStatus.SKIPPED);
            // 建议不再执行：清空建议指向并标记收货馆上架；原系统建议已由 ALLOCATION_SYSTEM 流水留痕
            a.setHold(null);
            a.setTargetLibrary(item.getHomeLibrary());
            a.setFulfillMode("SHELF");
            a.setEtaHours(0);
            a.setPickupWindowEnd(null);
            a.setDecidedBy(AllocationDecidedBy.STAFF);
            a.setStaff(staff);
            a.setReason("批次取消：" + reason);
            a.setDecidedAt(now);
        }
        acq.setStatus(AcquisitionStatus.CANCELLED);
        acq.setAllocatedAt(now);
        log(acq.getBib(), null, null, "ACQUISITION_CANCEL",
                "取消采购批次 #" + acq.getId() + "（" + acq.getCopyCount() + " 册《"
                        + acq.getBib().getTitle() + "》），复本改在收货馆上架；原因：" + reason, staff);
        // 上架复本按常规单点再分配依次服务队列首位（同馆优先，否则生成跨馆调拨）
        for (Item item : shelved) {
            holdService.reallocate(item.getId(), item.getHomeLibrary());
        }
        renumber(acq.getBib());
        return acq;
    }

    private void refreshBatchStatus(ItemAcquisition acq) {
        boolean pending = allocations.findByAcquisitionIdOrderById(acq.getId()).stream()
                .anyMatch(a -> a.getStatus() == AllocationStatus.SUGGESTED);
        if (!pending && acq.getStatus() == AcquisitionStatus.RECEIVED) {
            acq.setStatus(AcquisitionStatus.ALLOCATED);
            acq.setAllocatedAt(clock.now());
        }
    }

    // ---------------- 暂停 / 解除续借 ----------------

    @Transactional
    public HoldRenewalFreeze freezeRenewal(Long bibId, String staff, String reason) {
        if (staff == null || staff.isBlank()) {
            throw ApiException.unprocessable("设置暂停续借必须填写操作馆员姓名");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("设置暂停续借必须填写原因");
        }
        Bib bib = bibs.findById(bibId).orElseThrow(() -> ApiException.notFound("书目不存在: " + bibId));
        if (!freezes.findActiveByBib(bibId).isEmpty()) {
            throw ApiException.conflict("《" + bib.getTitle() + "》已处于暂停续借状态");
        }
        HoldRenewalFreeze f = new HoldRenewalFreeze();
        f.setBib(bib);
        f.setActive(true);
        f.setStaff(staff);
        f.setReason(reason);
        f.setCreatedAt(clock.now());
        freezes.save(f);
        log(bib, null, null, "RENEW_FREEZE",
                "《" + bib.getTitle() + "》暂停续借：排队期间在借复本不得续借，加速回流满足预约；原因："
                        + reason + "；操作人：" + staff, staff);
        return f;
    }

    @Transactional
    public HoldRenewalFreeze releaseRenewal(Long bibId, String staff, String reason) {
        if (staff == null || staff.isBlank()) {
            throw ApiException.unprocessable("解除暂停续借必须填写操作馆员姓名");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("解除暂停续借必须填写原因");
        }
        Bib bib = bibs.findById(bibId).orElseThrow(() -> ApiException.notFound("书目不存在: " + bibId));
        HoldRenewalFreeze f = freezes.findActiveByBib(bibId).stream().findFirst()
                .orElseThrow(() -> ApiException.conflict("《" + bib.getTitle() + "》当前没有生效中的暂停续借"));
        f.setActive(false);
        f.setReleasedAt(clock.now());
        f.setReleaseStaff(staff);
        f.setReleaseReason(reason);
        log(bib, null, null, "RENEW_RELEASE",
                "《" + bib.getTitle() + "》解除暂停续借；原因：" + reason + "；操作人：" + staff, staff);
        return f;
    }

    @Transactional(readOnly = true)
    public boolean isRenewalFrozen(Long bibId) {
        return !freezes.findActiveByBib(bibId).isEmpty();
    }

    // ---------------- 视图查询 ----------------

    @Transactional
    public AcquisitionView registerView(Long bibId, int copyCount, String receivingLibraryCode,
                                        String staff, String note) {
        return toView(registerAcquisition(bibId, copyCount, receivingLibraryCode, staff, note));
    }

    @Transactional
    public CopyAllocationView adjustSuggestionView(Long allocationId, Long targetHoldId, boolean skip,
                                                   String shelfLibraryCode, String staff, String reason) {
        return toView(adjustSuggestion(allocationId, targetHoldId, skip, shelfLibraryCode, staff, reason));
    }

    @Transactional
    public CopyAllocationView confirmAllocationView(Long allocationId, String staff) {
        return toView(confirmAllocation(allocationId, staff));
    }

    @Transactional
    public AcquisitionView confirmAllView(Long acquisitionId, String staff) {
        return toView(confirmAll(acquisitionId, staff));
    }

    @Transactional
    public AcquisitionView cancelView(Long acquisitionId, String staff, String reason) {
        return toView(cancelAcquisition(acquisitionId, staff, reason));
    }

    @Transactional
    public RenewalFreezeView freezeView(Long bibId, String staff, String reason) {
        return toView(freezeRenewal(bibId, staff, reason));
    }

    @Transactional
    public RenewalFreezeView releaseView(Long bibId, String staff, String reason) {
        return toView(releaseRenewal(bibId, staff, reason));
    }

    @Transactional(readOnly = true)
    public List<AcquisitionView> acquisitionViews(Long bibId) {
        List<ItemAcquisition> list = bibId == null
                ? acquisitions.findAllByOrderByCreatedAtDesc()
                : acquisitions.findByBibIdOrderByCreatedAtDesc(bibId);
        return list.stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public AcquisitionView acquisitionView(Long id) {
        return toView(mustAcquisition(id));
    }

    @Transactional(readOnly = true)
    public List<RenewalFreezeView> freezeViews(boolean activeOnly) {
        List<HoldRenewalFreeze> list = activeOnly ? freezes.findAllActive() : freezes.findAll();
        return list.stream()
                .sorted(Comparator.comparing(HoldRenewalFreeze::getCreatedAt).reversed())
                .map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<FlowLogView> flowLogViews(Long bibId, int limit) {
        List<CollectionFlowLog> list = bibId == null
                ? flowLogs.findAllByOrderByCreatedAtDesc()
                : flowLogs.findByBibIdOrderByCreatedAtDesc(bibId);
        return list.stream().limit(limit).map(this::toView).toList();
    }

    // ---------------- 内部工具 ----------------

    private ItemAcquisition mustAcquisition(Long id) {
        return acquisitions.findById(id)
                .orElseThrow(() -> ApiException.notFound("采购批次不存在: " + id));
    }

    private CopyAllocation mustSuggestion(Long id) {
        CopyAllocation a = allocations.findById(id)
                .orElseThrow(() -> ApiException.notFound("分配建议不存在: " + id));
        if (a.getStatus() != AllocationStatus.SUGGESTED) {
            throw ApiException.conflict("该分配建议已执行（状态 " + a.getStatus() + "），不能再调整");
        }
        return a;
    }

    /** 预计取书窗口截止 = 预计可到馆时间 + 取书时限；取书馆临时闭馆自动顺延 */
    private Instant pickupWindowEnd(Library pickup, Instant readyAt) {
        Instant end = readyAt.plus(policy.pickupDays(), ChronoUnit.DAYS);
        if (pickup.isTemporaryClosed()) {
            end = end.plus(policy.closureGraceHours(), ChronoUnit.HOURS);
        }
        return end;
    }

    private void renumber(Bib bib) {
        List<Hold> queued = holds.findActiveByBib(bib.getId(), List.of(HoldStatus.QUEUED));
        int pos = 1;
        for (Hold h : queued) {
            h.setQueuePosition(pos++);
        }
    }

    private void log(Bib bib, Item item, Hold hold, String action, String detail, String staff) {
        CollectionFlowLog l = new CollectionFlowLog();
        l.setBib(bib);
        l.setItem(item);
        l.setHold(hold);
        l.setAction(action);
        l.setDetail(detail);
        l.setStaff(staff);
        l.setCreatedAt(clock.now());
        flowLogs.save(l);
    }

    private String modeText(String mode) {
        return switch (mode) {
            case "LOCAL" -> "收货馆本地直架";
            case "TRANSFER" -> "跨馆调拨";
            default -> "直接上架";
        };
    }

    private String targetLibName(CopyAllocation a) {
        return a.getTargetLibrary() == null ? "-" : a.getTargetLibrary().getName();
    }

    private String describeTarget(CopyAllocation a) {
        if (a.getHold() == null || "SHELF".equals(a.getFulfillMode())) {
            return "在" + targetLibName(a) + "上架可借";
        }
        return "预约 #" + a.getHold().getId() + "（" + a.getHold().getReader().getCardNo()
                + "，" + targetLibName(a) + "，" + modeText(a.getFulfillMode()) + "）";
    }

    private AcquisitionView toView(ItemAcquisition acq) {
        List<CopyAllocationView> rows = allocations.findByAcquisitionIdOrderById(acq.getId()).stream()
                .map(this::toView).toList();
        return new AcquisitionView(acq.getId(), acq.getBib().getId(), acq.getBib().getTitle(),
                acq.getCopyCount(), acq.getStatus().name(), acq.getStaff(), acq.getNote(),
                acq.getCreatedAt(), acq.getAllocatedAt(), rows);
    }

    private CopyAllocationView toView(CopyAllocation a) {
        Hold h = a.getHold();
        Instant windowStart = null;
        if (h != null && !"SHELF".equals(a.getFulfillMode())) {
            windowStart = a.getCreatedAt() == null ? null
                    : a.getCreatedAt().plus(a.getEtaHours(), ChronoUnit.HOURS);
            if (a.getDecidedAt() != null
                    && (a.getStatus() == AllocationStatus.CONFIRMED || a.getStatus() == AllocationStatus.MANUAL)) {
                windowStart = a.getDecidedAt().plus(a.getEtaHours(), ChronoUnit.HOURS);
            }
        }
        return new CopyAllocationView(a.getId(), a.getAcquisition().getId(),
                a.getItem().getBarcode(),
                h == null ? null : h.getId(),
                h == null ? null : h.getReader().getCardNo(),
                h == null ? null : h.getReader().getName(),
                h == null ? null : h.getQueuePosition(),
                a.getTargetLibrary().getCode(), a.getTargetLibrary().getName(),
                h == null ? a.getTargetLibrary().getCode() : h.getPickupLibrary().getCode(),
                h == null ? a.getTargetLibrary().getName() : h.getPickupLibrary().getName(),
                a.getStatus().name(), a.getDecidedBy().name(), a.getStaff(),
                a.getFulfillMode(), a.getEtaHours(), windowStart, a.getPickupWindowEnd(),
                a.getReason(), h == null ? null : h.getCreatedAt(), a.getCreatedAt(), a.getDecidedAt());
    }

    private RenewalFreezeView toView(HoldRenewalFreeze f) {
        return new RenewalFreezeView(f.getId(), f.getBib().getId(), f.getBib().getTitle(),
                f.isActive(), f.getStaff(), f.getReason(), f.getCreatedAt(),
                f.getReleasedAt(), f.getReleaseStaff(), f.getReleaseReason());
    }

    private FlowLogView toView(CollectionFlowLog l) {
        return new FlowLogView(l.getId(),
                l.getBib() == null ? null : l.getBib().getId(),
                l.getItem() == null ? null : l.getItem().getId(),
                l.getItem() == null ? null : l.getItem().getBarcode(),
                l.getHold() == null ? null : l.getHold().getId(),
                l.getAction(), l.getDetail(), l.getStaff(), l.getCreatedAt());
    }
}
