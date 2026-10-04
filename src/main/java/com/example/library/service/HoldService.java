package com.example.library.service;

import com.example.library.domain.*;
import com.example.library.repo.*;
import com.example.library.web.ApiException;
import com.example.library.web.Views;
import com.example.library.web.dto.HoldView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 预约与排队核心。
 *
 * 一致性约定：
 * - 所有涉及“某书目预约 ↔ 馆藏”分配的入口，均按固定顺序加锁：
 *   先锁该书目活跃预约行（holds FOR UPDATE），再锁在架可借副本行（items FOR UPDATE），
 *   避免两位读者/两条定时任务同时抢到同一条码（全局统一锁顺序，杜绝死锁）。
 * - 任何释放出来的在馆副本（取消、超时未取、到馆后原预约失效、还书）都走
 *   {@link #reallocate(Long, Library)} 单点再分配，保证队列首位优先且状态一致。
 */
@Service
public class HoldService {

    private final HoldRepository holds;
    private final ItemRepository items;
    private final BibRepository bibs;
    private final ReaderRepository readers;
    private final LibraryRepository libraries;
    private final TransferTaskRepository tasks;
    private final PolicyService policy;
    private final CatalogService catalog;
    private final NotificationService notifications;
    private final AppClock clock;

    public HoldService(HoldRepository holds, ItemRepository items, BibRepository bibs,
                       ReaderRepository readers, LibraryRepository libraries, TransferTaskRepository tasks,
                       PolicyService policy, CatalogService catalog,
                       NotificationService notifications, AppClock clock) {
        this.holds = holds;
        this.items = items;
        this.bibs = bibs;
        this.readers = readers;
        this.libraries = libraries;
        this.tasks = tasks;
        this.policy = policy;
        this.catalog = catalog;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional
    public HoldView placeHoldView(Long bibId, String cardNo, String pickupCode) {
        return Views.hold(placeHold(bibId, cardNo, pickupCode));
    }

    @Transactional
    public HoldView cancelHoldView(Long holdId) {
        return Views.hold(cancelHold(holdId));
    }

    /** 先取预约集行锁，再在其中定位目标预约（与其他入口保持统一的全局加锁顺序） */
    @Transactional
    public Hold cancelHold(Long holdId) {
        Hold ref = holds.findById(holdId)
                .orElseThrow(() -> ApiException.notFound("预约不存在: " + holdId));
        List<Hold> lockedSet = holds.lockActiveByBib(ref.getBib().getId(), CatalogService.ACTIVE_HOLD_STATUSES);
        Hold h = lockedSet.stream().filter(x -> x.getId().equals(holdId)).findFirst()
                .orElseThrow(() -> ApiException.conflict("预约当前状态 " + ref.getStatus() + "，无需取消"));
        if (h.getStatus() == HoldStatus.BORROWED) {
            throw ApiException.unprocessable("已借出的预约不能取消，请办理还书");
        }
        cancelInternal(h, "读者主动取消预约");
        return h;
    }

    /** 外部服务在要变更某书目“预约↔馆藏”状态前，统一先获取该书目预约集行锁 */
    public List<Hold> lockActiveSet(Long bibId) {
        return holds.lockActiveByBib(bibId, CatalogService.ACTIVE_HOLD_STATUSES);
    }

    @Transactional
    public Hold placeHold(Long bibId, String cardNo, String pickupCode) {
        Bib bib = bibs.findById(bibId).orElseThrow(() -> ApiException.notFound("书目不存在: " + bibId));
        Reader reader = readers.findByCardNo(cardNo)
                .orElseThrow(() -> ApiException.notFound("读者证不存在: " + cardNo));
        if (!"ACTIVE".equals(reader.getStatus())) {
            throw ApiException.unprocessable("读者证已停用，暂不能预约");
        }
        Library pickup = libraries.findByCode(pickupCode)
                .orElseThrow(() -> ApiException.notFound("取书馆不存在: " + pickupCode));
        if (!pickup.isActive()) {
            throw ApiException.unprocessable("该分馆已停止服务");
        }

        // 固定锁顺序：预约行 → 在架副本行
        List<Hold> active = holds.lockActiveByBib(bibId, CatalogService.ACTIVE_HOLD_STATUSES);
        items.lockByBibAndStatus(bibId, ItemStatus.AVAILABLE);

        boolean duplicate = active.stream().anyMatch(h -> h.getReader().getId().equals(reader.getId()));
        if (duplicate) {
            throw ApiException.conflict("您已预约《" + bib.getTitle() + "》，请勿重复排队");
        }
        long readerActive = holds.countActiveForReader(reader.getId(), CatalogService.ACTIVE_HOLD_STATUSES);
        if (readerActive >= policy.maxHoldsPerReader()) {
            throw ApiException.unprocessable("同时有效预约数已达上限 " + policy.maxHoldsPerReader()
                    + "（公平性规则 hold.maxPerReader）");
        }

        Hold h = new Hold();
        h.setBib(bib);
        h.setReader(reader);
        h.setPickupLibrary(pickup);
        h.setStatus(HoldStatus.QUEUED);
        h.setCreatedAt(clock.now());
        h.setQueuePosition(active.size() + 1);
        holds.save(h);

        // 公平性 FIFO：前面还有未分到副本的排队者时，新预约不能插队拿书
        boolean earlierUnassigned = active.stream()
                .anyMatch(q -> q.getStatus() == HoldStatus.QUEUED && q.getItem() == null);
        Allocation allocation = earlierUnassigned ? null : catalog.chooseItem(bib, pickup);
        if (allocation != null) {
            assignToHold(h, allocation);
        }
        renumber(bib);
        return h;
    }

    /** 把具体副本分配给队首预约：本馆直接上架待取，跨馆锁定并生成调拨任务 */
    public void assignToHold(Hold h, Allocation a) {
        Item item = items.lockById(a.item().getId()).orElseThrow();
        h.setItem(item);
        if ("LOCAL".equals(a.mode())) {
            item.setStatus(ItemStatus.ON_HOLD);
            h.setStatus(HoldStatus.READY);
            h.setQueuePosition(null);
            Instant readyAt = clock.now();
            h.setReadyAt(readyAt);
            h.setExpiresAt(pickupExpiry(h.getPickupLibrary(), readyAt));
            notifications.notify(h.getReader(), "READY", "取书通知",
                    "您预约的《" + h.getBib().getTitle() + "》已在" + h.getPickupLibrary().getName()
                            + "预约架，取书窗口 " + readyAt + " 至 " + h.getExpiresAt()
                            + "，请于 " + h.getExpiresAt() + " 前取书。");
        } else {
            item.setStatus(ItemStatus.LOCKED);
            TransferTask t = new TransferTask();
            t.setHold(h);
            t.setItem(item);
            t.setFromLibrary(a.source());
            t.setToLibrary(h.getPickupLibrary());
            t.setStatus(TaskStatus.CREATED);
            t.setCreatedAt(clock.now());
            tasks.save(t);
            // 已锁条码但仍在等物流：保持队列可见（位置 1），到馆后转 READY
        }
    }

    /** 取书时限：取书馆临时闭馆时自动顺延 closure.graceHours */
    private Instant pickupExpiry(Library pickup, Instant readyAt) {
        Instant expiry = readyAt.plus(policy.pickupDays(), ChronoUnit.DAYS);
        if (pickup.isTemporaryClosed()) {
            expiry = expiry.plus(policy.closureGraceHours(), ChronoUnit.HOURS);
        }
        return expiry;
    }

    private void cancelInternal(Hold h, String reason) {
        Item item = h.getItem();
        boolean wasReady = h.getStatus() == HoldStatus.READY;
        TransferTask openTask = item == null ? null : tasks.findActiveForItem(
                item.getId(), List.of(TaskStatus.CREATED, TaskStatus.PICKED_UP)).stream()
                .filter(t -> t.getHold() != null && t.getHold().getId().equals(h.getId()))
                .findFirst().orElse(null);

        h.setStatus(HoldStatus.CANCELLED);
        h.setCancelledAt(clock.now());
        notifications.notify(h.getReader(), "CANCEL", "预约已取消",
                "您预约的《" + h.getBib().getTitle() + "》已取消。" + reason);

        if (item == null) {
            renumber(h.getBib());
            return;
        }
        if (openTask != null) {
            if (openTask.getStatus() == TaskStatus.CREATED) {
                // 尚未出库：直接解锁，任务取消，副本在出库馆进入再分配
                openTask.setStatus(TaskStatus.CANCELLED);
                Library source = openTask.getFromLibrary();
                renumber(h.getBib());
                reallocate(item.getId(), source);
            } else {
                // 已出库在途：包裹继续送达，到馆后释放给下一位
                openTask.setReleaseOnArrival(true);
                h.setReleaseOnArrival(true);
                renumber(h.getBib());
            }
        } else if (wasReady && item.getStatus() == ItemStatus.ON_HOLD) {
            // 已到预约架：释放给队列下一位
            Library at = item.getCurrentLibrary();
            renumber(h.getBib());
            reallocate(item.getId(), at);
        } else {
            renumber(h.getBib());
        }
    }

    /** 定时任务：取书超时 → 释放给下一位 */
    @Transactional
    public int expireOverdueHolds() {
        List<Hold> due = holds.findExpired(HoldStatus.READY, clock.now());
        int processed = 0;
        for (Hold ref : due) {
            // 行锁复查，防止多实例定时任务并发重复释放同一条预约
            Hold h = holds.lockById(ref.getId()).orElse(null);
            if (h == null || h.getStatus() != HoldStatus.READY || h.getExpiresAt() == null
                    || !h.getExpiresAt().isBefore(clock.now())) {
                continue;
            }
            // 取书馆闭馆已在 expiresAt 中顺延；此处再次兜底
            if (h.getPickupLibrary().isTemporaryClosed()) {
                h.setExpiresAt(pickupExpiry(h.getPickupLibrary(), h.getReadyAt()));
                continue;
            }
            expireHold(h);
            processed++;
        }
        return processed;
    }

    private void expireHold(Hold h) {
        Item item = h.getItem();
        h.setStatus(HoldStatus.EXPIRED);
        notifications.notify(h.getReader(), "HOLD_EXPIRED", "预约已超时释放",
                "您预约的《" + h.getBib().getTitle() + "》超过取书时限未取，已释放给下一位预约者。");
        if (item != null && item.getStatus() == ItemStatus.ON_HOLD) {
            Library at = item.getCurrentLibrary();
            renumber(h.getBib());
            reallocate(item.getId(), at);
        } else {
            renumber(h.getBib());
        }
    }

    /**
     * 单点再分配：把位于 sourceLibrary 的某副本交给队列首位；
     * 无人排队则在该馆上架可借。调用前不得持有该 item 行锁（本方法自行按全局顺序加锁）。
     */
    public void reallocate(Long itemId, Library sourceLibrary) {
        Item item = items.findById(itemId).orElseThrow();
        List<Hold> waiting = holds.lockActiveByBib(item.getBib().getId(), List.of(HoldStatus.QUEUED));
        Item locked = items.lockById(itemId).orElseThrow();

        // 未分配副本的排队者中，优先满足取书点就在当前馆的（可立即上架，省一趟物流）；
        // 没有则按队列位置把书继续跨馆调拨给最早的等待者。
        List<Hold> unassigned = waiting.stream().filter(h -> h.getItem() == null).toList();
        java.util.Comparator<Hold> byPosition = java.util.Comparator.comparing(
                Hold::getQueuePosition, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()));
        Hold next = unassigned.stream()
                .filter(h -> h.getPickupLibrary().getId().equals(sourceLibrary.getId()))
                .min(byPosition)
                .orElseGet(() -> unassigned.stream().min(byPosition).orElse(null));

        if (next == null) {
            locked.setStatus(ItemStatus.AVAILABLE);
            locked.setCurrentLibrary(sourceLibrary);
            return;
        }

        next.setItem(locked);
        if (next.getPickupLibrary().getId().equals(sourceLibrary.getId())) {
            locked.setStatus(ItemStatus.ON_HOLD);
            locked.setCurrentLibrary(sourceLibrary);
            next.setStatus(HoldStatus.READY);
            next.setQueuePosition(null);
            Instant readyAt = clock.now();
            next.setReadyAt(readyAt);
            next.setExpiresAt(pickupExpiry(next.getPickupLibrary(), readyAt));
            notifications.notify(next.getReader(), "READY", "取书通知",
                    "您排队的《" + next.getBib().getTitle() + "》已到" + next.getPickupLibrary().getName()
                            + "预约架，取书窗口 " + readyAt + " 至 " + next.getExpiresAt()
                            + "，请于 " + next.getExpiresAt() + " 前取书。");
        } else {
            locked.setStatus(ItemStatus.LOCKED);
            locked.setCurrentLibrary(sourceLibrary);
            TransferTask t = new TransferTask();
            t.setHold(next);
            t.setItem(locked);
            t.setFromLibrary(sourceLibrary);
            t.setToLibrary(next.getPickupLibrary());
            t.setStatus(TaskStatus.CREATED);
            t.setCreatedAt(clock.now());
            tasks.save(t);
        }
        renumber(item.getBib());
    }

    /** 重新编号仍在排队中的预约（按创建先后） */
    private void renumber(Bib bib) {
        List<Hold> queued = holds.findActiveByBib(bib.getId(), List.of(HoldStatus.QUEUED));
        int pos = 1;
        for (Hold h : queued) {
            h.setQueuePosition(pos++);
        }
    }

    /** 供调拨丢包等外部异常调用的重新排队入口 */
    @Transactional
    public void renumberPublic(Long bibId) {
        bibs.findById(bibId).ifPresent(this::renumber);
    }

    /**
     * 调拨途中丢包：把原预约复位为“未分配的排队中”并保留其队列位置（读者免责、不失公平性），
     * 随后尝试从其他馆重新调拨；无可用副本时继续等待。
     */
    @Transactional
    public void onItemLost(Hold h) {
        List<Hold> active = holds.lockActiveByBib(h.getBib().getId(), CatalogService.ACTIVE_HOLD_STATUSES);
        items.lockByBibAndStatus(h.getBib().getId(), ItemStatus.AVAILABLE);
        Hold locked = active.stream().filter(x -> x.getId().equals(h.getId())).findFirst().orElse(h);
        if (locked.getStatus() != HoldStatus.QUEUED && locked.getStatus() != HoldStatus.READY) {
            return;
        }
        locked.setItem(null);
        locked.setStatus(HoldStatus.QUEUED);
        locked.setReadyAt(null);
        locked.setExpiresAt(null);
        renumber(locked.getBib());

        boolean earlierUnassigned = holds.findActiveByBib(locked.getBib().getId(), List.of(HoldStatus.QUEUED)).stream()
                .filter(q -> q.getId() < locked.getId() && q.getItem() == null)
                .findAny().isPresent();
        if (!earlierUnassigned) {
            Allocation a = catalog.chooseItem(locked.getBib(), locked.getPickupLibrary());
            if (a != null) {
                assignToHold(locked, a);
            }
        }
        notifications.notify(locked.getReader(), "CANCEL", "调拨异常通知",
                "很抱歉，您预约的《" + locked.getBib().getTitle() + "》在调拨途中丢失，您无需承担费用；"
                        + "系统已为您保留队列位置（第 " + locked.getQueuePosition() + " 位）并尝试重新调拨。");
    }

    @Transactional(readOnly = true)
    public List<HoldView> activeHoldViewsForReader(String cardNo) {
        return activeHoldsForReader(cardNo).stream().map(Views::hold).toList();
    }

    @Transactional(readOnly = true)
    public List<Hold> activeHoldsForReader(String cardNo) {
        return holds.findActiveByReaderCard(cardNo, CatalogService.ACTIVE_HOLD_STATUSES);
    }

    @Transactional(readOnly = true)
    public HoldView holdView(Long id) {
        return Views.hold(get(id));
    }

    @Transactional(readOnly = true)
    public Hold get(Long id) {
        return holds.findById(id).orElseThrow(() -> ApiException.notFound("预约不存在: " + id));
    }

    @Transactional(readOnly = true)
    public List<HoldView> allActiveHoldViews() {
        return holds.findAll().stream()
                .filter(h -> h.getStatus() == HoldStatus.QUEUED || h.getStatus() == HoldStatus.READY)
                .sorted(java.util.Comparator.comparing(h -> h.getBib().getId()))
                .map(Views::hold).toList();
    }

    public Instant readyExpiry(Hold h) {
        return h.getExpiresAt();
    }
}
