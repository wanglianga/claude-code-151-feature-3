package com.example.library.service;

import com.example.library.domain.*;
import com.example.library.repo.*;
import com.example.library.web.ApiException;
import com.example.library.web.dto.admin.DemandAdviceView;
import com.example.library.web.dto.admin.LibraryDemandRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 热门书需求建议：根据等待人数、复本数量、续借限制、跨馆调拨成本，
 * 为每部有预约压力的书目生成“新增采购 / 暂停续借”处置建议。
 *
 * 建议只作决策支持，不直接改变库存/借阅；馆员据此登记采购批次或设置续借冻结。
 */
@Service
public class DemandService {

    private final BibRepository bibs;
    private final HoldRepository holds;
    private final ItemRepository items;
    private final LoanRepository loans;
    private final LibraryRepository libraries;
    private final HoldRenewalFreezeRepository freezes;
    private final PolicyService policy;
    private final CatalogService catalog;
    private final AppClock clock;

    public DemandService(BibRepository bibs, HoldRepository holds, ItemRepository items,
                         LoanRepository loans, LibraryRepository libraries,
                         HoldRenewalFreezeRepository freezes, PolicyService policy,
                         CatalogService catalog, AppClock clock) {
        this.bibs = bibs;
        this.holds = holds;
        this.items = items;
        this.loans = loans;
        this.libraries = libraries;
        this.freezes = freezes;
        this.policy = policy;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<DemandAdviceView> adviceList() {
        return bibs.findAll().stream()
                .map(this::adviceFor)
                .sorted(Comparator.comparingDouble(DemandAdviceView::waitCopyRatio).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public DemandAdviceView advice(Long bibId) {
        Bib bib = bibs.findById(bibId).orElseThrow(() -> ApiException.notFound("书目不存在: " + bibId));
        return adviceFor(bib);
    }

    private DemandAdviceView adviceFor(Bib bib) {
        Instant now = clock.now();
        List<Hold> active = holds.findActiveByBib(bib.getId(), CatalogService.ACTIVE_HOLD_STATUSES);
        List<Hold> unassigned = active.stream()
                .filter(h -> h.getStatus() == HoldStatus.QUEUED && h.getItem() == null)
                .toList();

        long circulating = items.countCirculatingByBib(bib.getId(),
                List.of(ItemStatus.LOST, ItemStatus.WITHDRAWN, ItemStatus.RECEIVED));
        long available = items.countByBibIdAndStatus(bib.getId(), ItemStatus.AVAILABLE);
        List<Loan> bibLoans = loans.findByStatusIn(List.of(LoanStatus.ACTIVE)).stream()
                .filter(l -> l.getItem().getBib().getId().equals(bib.getId()))
                .toList();
        long renewableLoans = bibLoans.stream()
                .filter(l -> l.getRenewCount() < policy.renewLimit()).count();
        boolean frozen = !freezes.findActiveByBib(bib.getId()).isEmpty();

        // 各取书馆等待画像
        Map<String, LibraryDemandRow> byLibrary = new LinkedHashMap<>();
        Map<String, List<Hold>> byLibHolds = new HashMap<>();
        for (Hold h : active) {
            byLibHolds.computeIfAbsent(h.getPickupLibrary().getCode(), k -> new ArrayList<>()).add(h);
        }
        // 全网跨馆可调剂源馆（受外馆保护比例约束仍允许调出至少 1 册），按源馆去重
        Set<Long> shippableSources = new HashSet<>();
        Long minEta = null;
        for (Library lib : libraries.findAll()) {
            if (!lib.isActive()) continue;
            List<Hold> libHolds = byLibHolds.getOrDefault(lib.getCode(), List.of());
            if (libHolds.isEmpty()) continue;
            List<Hold> libUnassigned = libHolds.stream()
                    .filter(h -> h.getStatus() == HoldStatus.QUEUED && h.getItem() == null)
                    .sorted(Comparator.comparing(Hold::getCreatedAt))
                    .toList();
            Instant oldest = libHolds.stream().map(Hold::getCreatedAt).min(Instant::compareTo).orElse(now);
            long totalWaited = libUnassigned.stream()
                    .mapToLong(h -> Duration.between(h.getCreatedAt(), now).toHours()).sum();
            // 该馆能否靠跨馆调剂消化：本馆无架时，其他馆受保护比例约束仍可调出
            long localAvailable = items.countByBibIdAndCurrentLibraryIdAndStatus(
                    bib.getId(), lib.getId(), ItemStatus.AVAILABLE);
            if (localAvailable == 0) {
                for (Library src : libraries.findAll()) {
                    if (src.getId().equals(lib.getId()) || !src.isActive() || src.isTemporaryClosed()) continue;
                    long srcAvail = items.countByBibIdAndCurrentLibraryIdAndStatus(
                            bib.getId(), src.getId(), ItemStatus.AVAILABLE);
                    if (srcAvail > 0 && catalog.protectionAllows(bib.getId(), src, srcAvail)) {
                        shippableSources.add(src.getId());
                        long eta = policy.etaHours(src.getRegion().equals(lib.getRegion()));
                        minEta = minEta == null ? eta : Math.min(minEta, eta);
                    }
                }
            }
            byLibrary.put(lib.getCode(), new LibraryDemandRow(
                    lib.getCode(), lib.getName(), libHolds.size(), libUnassigned.size(),
                    oldest, Duration.between(oldest, now).toHours(), totalWaited));
        }

        long waiting = active.size();
        double ratio = circulating == 0 ? (waiting == 0 ? 0 : Double.POSITIVE_INFINITY)
                : (double) waiting / circulating;
        double waitRatio = policy.acquisitionWaitRatio();
        int minQueue = policy.acquisitionMinQueue();

        long targetCirculating = (long) Math.ceil(waiting / waitRatio);
        long needByRatio = Math.max(0, targetCirculating - circulating);
        long needByQueue = Math.max(0, unassigned.size() - available);
        long suggestedCopies = Math.max(needByRatio, needByQueue);
        boolean suggestPurchase = unassigned.size() >= minQueue && suggestedCopies > 0;
        boolean suggestFreeze = !frozen && unassigned.size() > 0 && renewableLoans > 0;

        String rationale = buildRationale(bib, waiting, unassigned.size(), circulating, available,
                bibLoans.size(), renewableLoans, ratio, waitRatio, minQueue,
                shippableSources.size(), minEta, frozen);

        return new DemandAdviceView(bib.getId(), bib.getTitle(), waiting, unassigned.size(),
                circulating, available, bibLoans.size(),
                ratio == Double.POSITIVE_INFINITY ? -1 : Math.round(ratio * 100.0) / 100.0,
                renewableLoans, frozen, suggestPurchase, suggestedCopies, suggestFreeze,
                rationale, byLibrary, now);
    }

    private String buildRationale(Bib bib, long waiting, long unassigned, long circulating, long available,
                                  long activeLoans, long renewableLoans, double ratio, double waitRatio,
                                  int minQueue, long shippableSourceCount, Long minEta, boolean frozen) {
        StringBuilder sb = new StringBuilder();
        sb.append("《").append(bib.getTitle()).append("》当前有效预约 ").append(waiting)
                .append(" 人（其中未锁定副本排队 ").append(unassigned).append(" 人），在流转复本 ")
                .append(circulating).append(" 册、在架可借 ").append(available).append(" 册");
        sb.append("，等待/复本比 ").append(ratio == Double.POSITIVE_INFINITY ? "∞" : String.format("%.2f", ratio))
                .append("（采购阈值 ").append(waitRatio).append("、最少排队 ").append(minQueue).append(" 人）。");
        if (shippableSourceCount > 0) {
            sb.append("跨馆尚有 ").append(shippableSourceCount)
                    .append(" 个分馆受保护比例允许调剂，最快 ETA ").append(minEta)
                    .append(" 小时，但跨片区调拨成本较高；");
        } else if (waiting > 0) {
            sb.append("各分馆受外馆保护比例约束已无可调剂库存，跨馆调拨无法消化队列；");
        }
        sb.append("在借 ").append(activeLoans).append(" 册中按次数规则仍可续借约 ").append(renewableLoans)
                .append(" 册，");
        if (frozen) {
            sb.append("已暂停续借以加速复本回流。");
        } else if (renewableLoans > 0 && unassigned > 0) {
            sb.append("暂停续借可让这些复本按期回流，优先于继续跨馆调拨。");
        } else {
            sb.append("暂无需暂停续借。");
        }
        return sb.toString();
    }
}
