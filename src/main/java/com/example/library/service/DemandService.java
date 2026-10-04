package com.example.library.service;

import com.example.library.domain.*;
import com.example.library.repo.*;
import com.example.library.web.ApiException;
import com.example.library.web.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 热门书预约队列调控。
 *
 * 1) 需求建议：综合等待人数、复本数量、续借限制与跨馆调拨成本，
 *    建议“新增采购”或“暂停续借”（{@link #demandAdvice}）。
 * 2) 新增复本分配：复本到馆后按“预约时间 + 读者所在馆”把复本分给排队读者，
 *    并以“各分馆等待均衡”折减优先级，避免新增复本全部流向总馆
 *    （{@link #allocationPlan} 预览 / {@link #acquire} 执行）。
 * 3) 分配优先级 =（该馆最早等待者已等待时长 + 调拨在途时长）÷（1 + 本轮已分给该馆册数）。
 *    预计到手总等待越长的馆越优先；每分给某馆一册，该馆优先级折减。
 * 4) 馆员可查看建议并人工调整；调整原因强制填写并写入馆藏流转记录（item_movements）。
 */
@Service
public class DemandService {

    /** 分配规则说明（预览接口返回，便于馆员理解系统建议） */
    public static final List<String> ALLOCATION_RULES = List.of(
            "分配优先级 =（该馆最早等待者已等待时长 + 调拨在途时长）÷（1 + 本轮已分给该馆册数）",
            "已等待时长按预约创建时间计算（先约先得）；调拨在途时长按 transfer.sameRegionHours/otherRegionHours 估算",
            "每分给某馆一册后该馆优先级折减，兼顾各分馆等待时间，避免新增复本全部流向同一馆（如总馆）",
            "取书馆即到馆馆的读者无需调拨，直接上架待取");

    private final BibRepository bibs;
    private final ItemRepository items;
    private final HoldRepository holds;
    private final LoanRepository loans;
    private final LibraryRepository libraries;
    private final ItemMovementRepository movements;
    private final PolicyService policy;
    private final CatalogService catalog;
    private final HoldService holdService;
    private final NotificationService notifications;
    private final AppClock clock;

    public DemandService(BibRepository bibs, ItemRepository items, HoldRepository holds,
                         LoanRepository loans, LibraryRepository libraries, ItemMovementRepository movements,
                         PolicyService policy, CatalogService catalog, HoldService holdService,
                         NotificationService notifications, AppClock clock) {
        this.bibs = bibs;
        this.items = items;
        this.holds = holds;
        this.loans = loans;
        this.libraries = libraries;
        this.movements = movements;
        this.policy = policy;
        this.catalog = catalog;
        this.holdService = holdService;
        this.notifications = notifications;
        this.clock = clock;
    }

    // ---------------- 1. 需求建议：采购 / 暂停续借 ----------------

    @Transactional(readOnly = true)
    public DemandAdviceView demandAdvice(Long bibId) {
        Bib bib = bibs.findById(bibId).orElseThrow(() -> ApiException.notFound("书目不存在: " + bibId));
        List<Hold> active = holds.findActiveByBib(bibId, CatalogService.ACTIVE_HOLD_STATUSES);
        List<Hold> waiting = unassignedQueue(active);
        long waitingCount = waiting.size();

        List<Item> all = items.findAll().stream().filter(i -> i.getBib().getId().equals(bibId)).toList();
        long copiesTotal = all.stream().filter(i -> i.getStatus() != ItemStatus.LOST
                && i.getStatus() != ItemStatus.WITHDRAWN && i.getStatus() != ItemStatus.DAMAGED).count();
        long copiesAvailable = all.stream().filter(i -> i.getStatus() == ItemStatus.AVAILABLE).count();
        long activeLoans = loans.findByStatusIn(List.of(LoanStatus.ACTIVE)).stream()
                .filter(l -> l.getItem().getBib().getId().equals(bibId)).count();
        double demandRatio = round1((double) waitingCount / Math.max(1, copiesTotal));

        // 跨馆调拨成本：每位等待者最快可行来源的在途小时；无可行来源则调拨无法缓解
        List<Long> etas = new ArrayList<>();
        long infeasible = 0;
        List<Library> libs = libraries.findAll().stream().filter(Library::isActive).toList();
        for (Hold h : waiting) {
            Long best = null;
            for (Library src : libs) {
                long avail = items.countByBibIdAndCurrentLibraryIdAndStatus(bibId, src.getId(), ItemStatus.AVAILABLE);
                if (avail == 0) continue;
                boolean localSrc = src.getId().equals(h.getPickupLibrary().getId());
                if (!localSrc) {
                    if (src.isTemporaryClosed()) continue;
                    if (!catalog.protectionAllows(bibId, src, avail)) continue;
                }
                long eta = localSrc ? 0
                        : policy.etaHours(src.getRegion().equals(h.getPickupLibrary().getRegion()));
                best = best == null ? eta : Math.min(best, eta);
            }
            if (best == null) infeasible++; else etas.add(best);
        }
        Double avgEta = etas.isEmpty() ? null
                : round1(etas.stream().mapToLong(Long::longValue).average().orElse(0));

        int purchaseThreshold = policy.demandPurchaseThreshold();
        int suspendThreshold = policy.demandSuspendRenewalThreshold();
        boolean suggestPurchase = waitingCount >= purchaseThreshold && copiesAvailable < waitingCount;
        int suggestedCopies = (int) Math.max(0, waitingCount - copiesAvailable);
        boolean suggestSuspend = waitingCount >= suspendThreshold && !bib.isRenewSuspended();

        List<String> reasons = new ArrayList<>();
        reasons.add("等待人数 " + waitingCount + "（未分到复本）；在流转复本 " + copiesTotal
                + " 册、在架可借 " + copiesAvailable + " 册、在借 " + activeLoans
                + " 册；需求压力 " + demandRatio);
        if (waitingCount == 0) {
            reasons.add("当前无人等书，无需调控");
        }
        if (infeasible > 0) {
            reasons.add(infeasible + " 位等待者无任何可调拨来源（在架复本受外馆保护比例约束或来源馆闭馆），"
                    + "跨馆调拨无法缓解，只能等还书或新增采购");
        } else if (avgEta != null) {
            reasons.add("跨馆调拨可覆盖全部等待者，平均在途 " + avgEta + " 小时（调拨成本）");
        }
        if (suggestPurchase) {
            reasons.add("等待人数 " + waitingCount + " ≥ 采购阈值 demand.purchaseThreshold=" + purchaseThreshold
                    + "，且在架可借 " + copiesAvailable + " 册不足 → 建议新增采购 " + suggestedCopies + " 册");
        } else if (waitingCount > 0) {
            reasons.add("等待人数 " + waitingCount + " 未达采购阈值 demand.purchaseThreshold=" + purchaseThreshold
                    + "，暂不建议采购");
        }
        reasons.add("续借限制：loan.renewLimit=" + policy.renewLimit() + "，有排队时系统自动禁止续借"
                + (bib.isRenewSuspended() ? "；该书已暂停续借（" + bib.getRenewSuspendReason() + "）" : ""));
        if (suggestSuspend) {
            reasons.add("等待人数 " + waitingCount + " ≥ 暂停续借阈值 demand.suspendRenewalThreshold="
                    + suspendThreshold + " → 建议暂停该书续借，加快复本回转");
        }

        return new DemandAdviceView(bib.getId(), bib.getTitle(), waitingCount, active.size(),
                copiesTotal, copiesAvailable, activeLoans, demandRatio,
                policy.renewLimit(), bib.isRenewSuspended(), bib.getRenewSuspendReason(),
                avgEta, infeasible, suggestPurchase, suggestedCopies, suggestSuspend,
                reasons, clock.now());
    }

    /** 馆员设置/解除书目续借暂停；暂停必须填写理由（向读者解释） */
    @Transactional
    public BibRenewalView setRenewalSuspension(Long bibId, boolean suspended, String staff, String reason) {
        Bib bib = bibs.findById(bibId).orElseThrow(() -> ApiException.notFound("书目不存在: " + bibId));
        if (suspended && (reason == null || reason.isBlank())) {
            throw ApiException.unprocessable("暂停续借必须填写理由（将向读者展示）");
        }
        if (suspended == bib.isRenewSuspended()) {
            throw ApiException.conflict("该书续借暂停状态已是 " + suspended + "，无需重复设置");
        }
        bib.setRenewSuspended(suspended);
        bib.setRenewSuspendReason(suspended ? reason : null);
        return new BibRenewalView(bib.getId(), bib.getTitle(), suspended, bib.getRenewSuspendReason(), staff);
    }

    // ---------------- 2. 新增复本分配建议（预览） ----------------

    @Transactional(readOnly = true)
    public AllocationPlanView allocationPlan(Long bibId, String arrivalCode, int copies) {
        Bib bib = bibs.findById(bibId).orElseThrow(() -> ApiException.notFound("书目不存在: " + bibId));
        Library arrival = arrivalLibrary(arrivalCode);
        checkCopies(copies);
        List<Hold> queue = unassignedQueue(holds.findActiveByBib(bibId, List.of(HoldStatus.QUEUED)));
        Instant now = clock.now();
        List<AllocationPlanView.PlannedAssignment> plan = computePlan(arrival, copies, queue, now);
        return new AllocationPlanView(bib.getId(), bib.getTitle(), arrival.getCode(), arrival.getName(),
                copies, branchWaits(queue, now), plan, ALLOCATION_RULES, now);
    }

    // ---------------- 3. 采购到馆执行（可人工调整，留痕） ----------------

    @Transactional
    public AcquisitionResultView acquire(Long bibId, AcquisitionRequest req) {
        Bib bib = bibs.findById(bibId).orElseThrow(() -> ApiException.notFound("书目不存在: " + bibId));
        Library arrival = arrivalLibrary(req.arrivalLibraryCode());
        checkCopies(req.copies());

        // 全局加锁顺序：先锁该书目活跃预约集（与预约/还书/调拨路径一致）
        List<Hold> queue = unassignedQueue(holdService.lockActiveSet(bibId));
        Instant now = clock.now();

        // 系统建议（完整队列）
        List<AllocationPlanView.PlannedAssignment> suggested = computePlan(arrival, req.copies(), queue, now);

        // 人工调整：copyIndex → 指定预约
        Map<Integer, Hold> manual = new LinkedHashMap<>();
        if (req.overrides() != null) {
            for (AcquisitionRequest.OverrideAssignment o : req.overrides()) {
                if (o.copyIndex() >= req.copies()) {
                    throw ApiException.unprocessable("调整册序号 " + o.copyIndex() + " 超出采购册数 " + req.copies());
                }
                Hold h = holds.findById(o.holdId())
                        .orElseThrow(() -> ApiException.notFound("预约不存在: " + o.holdId()));
                if (!h.getBib().getId().equals(bibId)) {
                    throw ApiException.unprocessable("预约 #" + o.holdId() + " 不属于书目《" + bib.getTitle() + "》");
                }
                if (h.getStatus() != HoldStatus.QUEUED || h.getItem() != null) {
                    throw ApiException.conflict("预约 #" + o.holdId() + " 状态为 " + h.getStatus()
                            + "，不在可分配队列中");
                }
                if (manual.containsKey(o.copyIndex())) {
                    throw ApiException.conflict("册序号 " + o.copyIndex() + " 被重复调整");
                }
                if (manual.containsValue(h)) {
                    throw ApiException.conflict("预约 #" + o.holdId() + " 被分配到多册");
                }
                manual.put(o.copyIndex(), h);
            }
        }

        // 最终方案：手工位优先，剩余位由规划器在剩余队列上按同一规则填充
        Hold[] finalAssign = new Hold[req.copies()];
        Set<Long> used = new HashSet<>();
        manual.forEach((i, h) -> { finalAssign[i] = h; used.add(h.getId()); });
        List<Hold> remaining = queue.stream().filter(h -> !used.contains(h.getId())).toList();
        List<Hold> autoFilled = computePlan(arrival, req.copies(), remaining, now).stream()
                .filter(p -> p.holdId() != null)
                .map(p -> remaining.stream().filter(h -> h.getId().equals(p.holdId())).findFirst().orElseThrow())
                .toList();
        java.util.Iterator<Hold> fill = autoFilled.iterator();
        for (int i = 0; i < req.copies(); i++) {
            if (finalAssign[i] == null && fill.hasNext()) {
                finalAssign[i] = fill.next();
            }
        }

        // 与系统建议不一致 → 必须填写调整原因（进入馆藏流转记录）
        int adjustedCount = 0;
        for (int i = 0; i < req.copies(); i++) {
            Long suggestedHold = suggested.get(i).holdId();
            Long finalHold = finalAssign[i] == null ? null : finalAssign[i].getId();
            if (!java.util.Objects.equals(suggestedHold, finalHold)) adjustedCount++;
        }
        if (adjustedCount > 0 && (req.adjustReason() == null || req.adjustReason().isBlank())) {
            throw ApiException.unprocessable("最终分配与系统建议不一致（" + adjustedCount
                    + " 册被人工调整），必须填写 adjustReason，调整原因将进入馆藏流转记录");
        }

        // 执行：建复本 → 分配 → 通知（含预计取书窗口）→ 流转留痕
        List<AcquisitionResultView.CreatedCopy> copies = new ArrayList<>();
        int seq = nextBarcodeSeq(bib);
        for (int i = 0; i < req.copies(); i++) {
            Item item = new Item();
            String barcode = "N" + bib.getId() + "-" + String.format("%04d", seq++);
            item.setBarcode(barcode);
            item.setBib(bib);
            item.setHomeLibrary(arrival);
            item.setCurrentLibrary(arrival);
            item.setStatus(ItemStatus.AVAILABLE);
            item.setCondition("GOOD");
            items.save(item);

            Hold h = finalAssign[i];
            record(item, MovementType.ACQUISITION, null, arrival, null, req.staff(), null,
                    "新增采购到馆（第 " + (i + 1) + "/" + req.copies() + " 册）"
                            + (h == null ? "，无排队读者，上架可借" : ""));

            Long suggestedHold = suggested.get(i).holdId();
            boolean adjusted = !java.util.Objects.equals(suggestedHold, h == null ? null : h.getId());
            if (h == null) {
                copies.add(new AcquisitionResultView.CreatedCopy(i, barcode, ItemStatus.AVAILABLE.name(),
                        null, null, null, "SHELF", false, null, null));
                continue;
            }

            boolean local = h.getPickupLibrary().getId().equals(arrival.getId());
            long etaH = local ? 0 : policy.etaHours(arrival.getRegion().equals(h.getPickupLibrary().getRegion()));
            holdService.assignToHold(h, new Allocation(item, arrival, local ? "LOCAL" : "TRANSFER", etaH));

            Instant winFrom = local ? now : now.plus(etaH, ChronoUnit.HOURS);
            Instant winTo = winFrom.plus(policy.pickupDays(), ChronoUnit.DAYS);
            if (!local) {
                notifications.notify(h.getReader(), "ALLOCATION", "复本调配通知",
                        "热门书《" + bib.getTitle() + "》为您调配了新增复本（条码 " + barcode + "），正从"
                                + arrival.getName() + "调往" + h.getPickupLibrary().getName()
                                + "，预计 " + winFrom + " 到馆；预计取书窗口 " + winFrom + " 至 " + winTo
                                + "（到馆后取书时限 " + policy.pickupDays() + " 天），书到馆后我们会再次通知您。");
            }

            String detail;
            MovementType type;
            if (adjusted) {
                type = MovementType.MANUAL_ADJUST;
                detail = "系统建议：" + describeSuggestion(suggested.get(i))
                        + " → 人工调整：预约#" + h.getId() + "（" + h.getReader().getCardNo() + "/"
                        + h.getPickupLibrary().getName() + "）";
            } else {
                type = MovementType.AUTO_ASSIGN;
                double waitedH = round1(Duration.between(h.getCreatedAt(), now).toMinutes() / 60.0);
                detail = "按队列自动分配：预约#" + h.getId() + "（" + h.getReader().getCardNo() + "/"
                        + h.getPickupLibrary().getName() + "，已等待 " + waitedH
                        + " 小时，调拨在途 " + etaH + " 小时）";
            }
            record(item, type, arrival, h.getPickupLibrary(), h,
                    adjusted ? req.staff() : null, adjusted ? req.adjustReason() : null, detail);

            copies.add(new AcquisitionResultView.CreatedCopy(i, barcode, item.getStatus().name(),
                    h.getId(), h.getReader().getCardNo(), h.getPickupLibrary().getCode(),
                    local ? "LOCAL_READY" : "TRANSFER", adjusted, winFrom, winTo));
        }
        holdService.renumberPublic(bibId);
        return new AcquisitionResultView(bib.getId(), bib.getTitle(), arrival.getCode(), req.copies(),
                copies, adjustedCount, adjustedCount > 0 ? req.adjustReason() : null);
    }

    // ---------------- 4. 馆藏流转记录查询 ----------------

    @Transactional(readOnly = true)
    public List<MovementView> movementsForItem(String barcode) {
        Item item = items.findByBarcode(barcode)
                .orElseThrow(() -> ApiException.notFound("馆藏条码不存在: " + barcode));
        return movements.findByItemId(item.getId()).stream().map(DemandService::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<MovementView> movementsForBib(Long bibId) {
        bibs.findById(bibId).orElseThrow(() -> ApiException.notFound("书目不存在: " + bibId));
        return movements.findByBibId(bibId).stream().map(DemandService::toView).toList();
    }

    private static MovementView toView(ItemMovement m) {
        return new MovementView(m.getId(), m.getItem().getBarcode(), m.getType().name(),
                m.getFromLibrary() == null ? null : m.getFromLibrary().getCode(),
                m.getToLibrary() == null ? null : m.getToLibrary().getCode(),
                m.getHold() == null ? null : m.getHold().getId(),
                m.getStaff(), m.getReason(), m.getDetail(), m.getCreatedAt());
    }

    // ---------------- 内部：分配规划 ----------------

    /**
     * 逐册选择去向：在仍有未满足预约的分馆中，取
     * （该馆最早等待者已等待秒数 + 调拨在途秒数）÷（1 + 本轮已分给该馆册数）最大者；
     * 馆内按预约创建时间先到先得。复本多于排队时，多余册到馆上架。
     */
    private List<AllocationPlanView.PlannedAssignment> computePlan(
            Library arrival, int copies, List<Hold> queue, Instant now) {
        Map<Library, Deque<Hold>> byBranch = new TreeMap<>(Comparator.comparing(Library::getCode));
        for (Hold h : queue) {
            byBranch.computeIfAbsent(h.getPickupLibrary(), k -> new ArrayDeque<>()).add(h);
        }
        Map<Long, Integer> allocated = new HashMap<>();
        List<AllocationPlanView.PlannedAssignment> out = new ArrayList<>();
        for (int i = 0; i < copies; i++) {
            if (byBranch.isEmpty()) {
                out.add(new AllocationPlanView.PlannedAssignment(i, null, null, null, null, null,
                        0, 0, null, null, "无排队读者，到馆后上架可借"));
                continue;
            }
            Library best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (Map.Entry<Library, Deque<Hold>> e : byBranch.entrySet()) {
                Library b = e.getKey();
                double waitSec = Duration.between(e.getValue().peek().getCreatedAt(), now).toSeconds();
                double etaSec = b.getId().equals(arrival.getId()) ? 0
                        : policy.etaHours(arrival.getRegion().equals(b.getRegion())) * 3600.0;
                double score = (waitSec + etaSec) / (1 + allocated.getOrDefault(b.getId(), 0));
                if (score > bestScore) {
                    bestScore = score;
                    best = b;
                }
            }
            Hold h = byBranch.get(best).poll();
            if (byBranch.get(best).isEmpty()) {
                byBranch.remove(best);
            }
            allocated.merge(best.getId(), 1, Integer::sum);

            boolean local = best.getId().equals(arrival.getId());
            long etaH = local ? 0 : policy.etaHours(arrival.getRegion().equals(best.getRegion()));
            Instant winFrom = local ? now : now.plus(etaH, ChronoUnit.HOURS);
            Instant winTo = winFrom.plus(policy.pickupDays(), ChronoUnit.DAYS);
            double waitedH = round1(Duration.between(h.getCreatedAt(), now).toMinutes() / 60.0);
            out.add(new AllocationPlanView.PlannedAssignment(i, h.getId(),
                    h.getReader().getCardNo(), h.getReader().getName(),
                    best.getCode(), best.getName(), waitedH, etaH, winFrom, winTo,
                    local ? "取书馆即到馆馆，无需调拨，直接上架待取"
                            : "该馆最早等待者预计总等待（已等待 + 调拨在途）最长，优先满足"));
        }
        return out;
    }

    private List<AllocationPlanView.BranchWaitRow> branchWaits(List<Hold> queue, Instant now) {
        Map<Library, List<Hold>> byBranch = new TreeMap<>(Comparator.comparing(Library::getCode));
        for (Hold h : queue) {
            byBranch.computeIfAbsent(h.getPickupLibrary(), k -> new ArrayList<>()).add(h);
        }
        return byBranch.entrySet().stream()
                .map(e -> new AllocationPlanView.BranchWaitRow(e.getKey().getCode(), e.getKey().getName(),
                        e.getValue().size(),
                        round1(Duration.between(e.getValue().get(0).getCreatedAt(), now).toMinutes() / 60.0)))
                .toList();
    }

    private List<Hold> unassignedQueue(List<Hold> activeSet) {
        return activeSet.stream()
                .filter(h -> h.getStatus() == HoldStatus.QUEUED && h.getItem() == null)
                .sorted(Comparator.comparing(Hold::getCreatedAt).thenComparing(Hold::getId))
                .toList();
    }

    private Library arrivalLibrary(String code) {
        Library arrival = libraries.findByCode(code)
                .orElseThrow(() -> ApiException.notFound("到馆分馆不存在: " + code));
        if (!arrival.isActive()) {
            throw ApiException.unprocessable("分馆 " + code + " 已停止服务，不能作为到馆馆");
        }
        return arrival;
    }

    private static void checkCopies(int copies) {
        if (copies < 1 || copies > 50) {
            throw ApiException.unprocessable("采购册数须在 1~50 之间: " + copies);
        }
    }

    private int nextBarcodeSeq(Bib bib) {
        String prefix = "N" + bib.getId() + "-";
        return items.findByBarcodeStartingWith(prefix).stream()
                .map(i -> i.getBarcode().substring(prefix.length()))
                .filter(s -> s.matches("\\d+"))
                .mapToInt(Integer::parseInt)
                .max().orElse(0) + 1;
    }

    private String describeSuggestion(AllocationPlanView.PlannedAssignment p) {
        return p.holdId() == null ? "上架可借（无排队）"
                : "预约#" + p.holdId() + "（" + p.readerCardNo() + "/" + p.pickupLibraryName() + "）";
    }

    private ItemMovement record(Item item, MovementType type, Library from, Library to, Hold hold,
                                String staff, String reason, String detail) {
        ItemMovement m = new ItemMovement();
        m.setItem(item);
        m.setType(type);
        m.setFromLibrary(from);
        m.setToLibrary(to);
        m.setHold(hold);
        m.setStaff(staff);
        m.setReason(reason);
        m.setDetail(detail);
        m.setCreatedAt(clock.now());
        return movements.save(m);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
