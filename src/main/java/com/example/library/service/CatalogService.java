package com.example.library.service;

import com.example.library.domain.*;
import com.example.library.repo.BibRepository;
import com.example.library.repo.HoldRepository;
import com.example.library.repo.ItemRepository;
import com.example.library.repo.LibraryRepository;
import com.example.library.web.ApiException;
import com.example.library.web.dto.CatalogView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 读者检索视角：本馆库存、可跨馆调拨库存（受外馆保护比例约束）、
 * 预约队列长度、预计到馆时间、电子资源替代。
 */
@Service
public class CatalogService {

    public static final List<HoldStatus> ACTIVE_HOLD_STATUSES = List.of(HoldStatus.QUEUED, HoldStatus.READY);
    public static final List<ItemStatus> SHELF_STATUSES =
            List.of(ItemStatus.AVAILABLE, ItemStatus.ON_HOLD);

    private final BibRepository bibs;
    private final ItemRepository items;
    private final LibraryRepository libraries;
    private final HoldRepository holds;
    private final PolicyService policy;
    private final AppClock clock;

    public CatalogService(BibRepository bibs, ItemRepository items, LibraryRepository libraries,
                          HoldRepository holds, PolicyService policy, AppClock clock) {
        this.bibs = bibs;
        this.items = items;
        this.libraries = libraries;
        this.holds = holds;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CatalogView> search(String keyword, String pickupLibraryCode) {
        Library pickup = pickupLibraryCode == null ? null
                : libraries.findByCode(pickupLibraryCode)
                    .orElseThrow(() -> ApiException.notFound("分馆不存在: " + pickupLibraryCode));
        return bibs.search(keyword).stream().map(b -> buildView(b, pickup)).toList();
    }

    @Transactional(readOnly = true)
    public CatalogView view(Long bibId, String pickupLibraryCode) {
        Bib b = bibs.findById(bibId).orElseThrow(() -> ApiException.notFound("书目不存在: " + bibId));
        Library pickup = pickupLibraryCode == null ? null
                : libraries.findByCode(pickupLibraryCode)
                    .orElseThrow(() -> ApiException.notFound("分馆不存在: " + pickupLibraryCode));
        return buildView(b, pickup);
    }

    /**
     * 为取书馆选择最优可调拨副本：
     * 1) 本馆在架可借 → 直接借阅/锁定；
     * 2) 其他馆可借且通过外馆保护比例校验 → 按距离最近（物流成本）选择。
     * 调用方须自行持有所需的悲观锁。
     */
    public Allocation chooseItem(Bib bib, Library pickup) {
        List<Item> local = items.findByBibAndLibraryAndStatus(bib.getId(), pickup.getId(), ItemStatus.AVAILABLE);
        if (!local.isEmpty()) {
            return new Allocation(local.get(0), pickup, "LOCAL", 0);
        }
        record Candidate(Item item, Library src, double distance, long etaHours) {}
        List<Candidate> candidates = new ArrayList<>();
        for (Library src : libraries.findAll()) {
            if (src.getId().equals(pickup.getId()) || !src.isActive() || src.isTemporaryClosed()) {
                continue;
            }
            List<Item> avail = items.findByBibAndLibraryAndStatus(bib.getId(), src.getId(), ItemStatus.AVAILABLE);
            if (avail.isEmpty() || !protectionAllows(bib.getId(), src, avail.size())) {
                continue;
            }
            boolean sameRegion = src.getRegion().equals(pickup.getRegion());
            candidates.add(new Candidate(avail.get(0), src, distance(src, pickup), policy.etaHours(sameRegion)));
        }
        return candidates.stream()
                .min(Comparator.comparingDouble((Candidate c) -> c.etaHours)
                        .thenComparingDouble(c -> c.distance))
                .map(c -> new Allocation(c.item, c.src, "TRANSFER", c.etaHours))
                .orElse(null);
    }

    /**
     * 外馆保护：调拨走 1 册后，该馆留存可借数不得低于 ceil(protectionRatio × 该馆在流转馆藏数)，
     * 防止热门书把远端分馆库存抽空（已丢失/注销、以及新购待馆员确认分配的 RECEIVED 副本不计入分母；
     * 单副本分馆默认必须留存）。
     */
    public boolean protectionAllows(Long bibId, Library source, long availableNow) {
        long total = items.countCirculatingByBibAndLibrary(bibId, source.getId(),
                List.of(ItemStatus.LOST, ItemStatus.WITHDRAWN, ItemStatus.RECEIVED));
        long retained = availableNow - 1;
        return retained >= (long) Math.ceil(policy.protectionRatio() * total);
    }

    public double distance(Library a, Library b) {
        return Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());
    }

    private CatalogView buildView(Bib b, Library pickup) {
        long queueLength = holds.findActiveByBib(b.getId(), ACTIVE_HOLD_STATUSES).size();
        List<CatalogView.LibraryStock> stocks = new ArrayList<>();
        int localAvailable = 0;
        Long bestEtaHours = null;

        for (Library lib : libraries.findAll()) {
            if (!lib.isActive()) continue;
            long available = items.countByBibIdAndCurrentLibraryIdAndStatus(
                    b.getId(), lib.getId(), ItemStatus.AVAILABLE);
            long total = items.countByBibIdAndCurrentLibraryId(b.getId(), lib.getId());
            boolean shippable = available > 0
                    && protectionAllows(b.getId(), lib, available)
                    && !lib.isTemporaryClosed();
            Long eta = null;
            if (pickup != null && !lib.getId().equals(pickup.getId()) && shippable) {
                eta = policy.etaHours(lib.getRegion().equals(pickup.getRegion()));
            }
            stocks.add(new CatalogView.LibraryStock(lib.getCode(), lib.getName(), lib.getRegion(),
                    total, available, shippable, eta,
                    lib.isTemporaryClosed(), pickup == null ? 0 : distance(lib, pickup)));
            if (pickup != null && lib.getId().equals(pickup.getId())) {
                localAvailable = (int) available;
            }
            if (pickup != null && shippable) {
                long localEta = lib.getId().equals(pickup.getId()) ? 0L
                        : policy.etaHours(lib.getRegion().equals(pickup.getRegion()));
                bestEtaHours = bestEtaHours == null ? localEta : Math.min(bestEtaHours, localEta);
            }
        }

        String suggestion = null;
        if (localAvailable == 0 && b.isEbookAvailable()) {
            suggestion = "本馆暂无可借纸质书，可立即使用电子资源：" + b.getEbookUrl();
        } else if (localAvailable == 0 && bestEtaHours == null && queueLength == 0) {
            suggestion = "全部馆藏均在借出/调拨中，建议加入预约队列";
        }

        return new CatalogView(
                b.getId(), b.getIsbn(), b.getTitle(), b.getAuthor(), b.getSubject(),
                b.isEbookAvailable(), b.getEbookUrl(),
                pickup == null ? null : pickup.getCode(),
                localAvailable, queueLength, bestEtaHours, suggestion, stocks,
                clock.now());
    }
}
