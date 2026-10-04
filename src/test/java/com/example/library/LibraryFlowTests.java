package com.example.library;

import com.example.library.domain.*;
import com.example.library.repo.*;
import com.example.library.service.*;
import com.example.library.web.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端业务流程集成测试（H2 PostgreSQL 兼容模式 + Flyway 真实迁移）。
 * 每个测试方法事务回滚；AppClock 手动固定/拨动以验证超时与逾期。
 */
@SpringBootTest
@ActiveProfiles("test")
@org.springframework.transaction.annotation.Transactional
class LibraryFlowTests {

    @Autowired HoldService holdService;
    @Autowired TransferService transferService;
    @Autowired LoanService loanService;
    @Autowired FineService fineService;
    @Autowired CatalogService catalog;
    @Autowired PolicyService policy;
    @Autowired LibraryAdminService libraryAdmin;
    @Autowired AdminStatsService stats;
    @Autowired DemandService demand;
    @Autowired CopyAllocationService copyAllocation;
    @Autowired AppClock clock;
    @Autowired ItemRepository items;
    @Autowired HoldRepository holds;
    @Autowired TransferTaskRepository tasks;
    @Autowired LoanRepository loans;
    @Autowired FineRepository fines;
    @Autowired ClaimRepository claims;
    @Autowired ReaderRepository readers;
    @Autowired NotificationRepository notifications;
    @Autowired CopyAllocationRepository copyAllocations;
    @Autowired CollectionFlowLogRepository flowLogs;
    @Autowired HoldRenewalFreezeRepository freezes;

    private Instant t0;

    @BeforeEach
    void setUp() {
        t0 = Instant.parse("2026-10-03T08:00:00Z");
        clock.setFixed(t0);
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    // ---------- 1. 检索视图：本馆库存、保护比例、ETA、电子资源 ----------

    @Test
    void catalog_showsLocalStock_transferableCopies_queueAndEbook() {
        var view = catalog.view(1L, "WEST"); // 三体，取书城西
        assertEquals("三体", view.title());
        assertEquals(0, view.localAvailable(), "城西无三体本馆库存");
        assertTrue(view.ebookAvailable(), "三体有电子资源替代");
        assertNotNull(view.ebookUrl());

        var east = view.libraries().stream().filter(l -> l.code().equals("EAST")).findFirst().orElseThrow();
        assertEquals(3, east.availableCopies());
        // EAST 共 3 册，保护比例 0.5 → 至少保留 ceil(1.5)=2 册，可调出 1 册
        assertTrue(east.transferable(), "城东 3 册可调出 1 册");
        assertEquals(72L, east.etaHours(), "跨片区 ETA 72 小时");

        var central = view.libraries().stream().filter(l -> l.code().equals("CENTRAL")).findFirst().orElseThrow();
        assertFalse(central.transferable(), "中心馆单册受保护不可调出");
    }

    // ---------- 2. 跨馆调拨全链路：预约 → 锁条码 → 出库 → 到馆通知 → 借出 ----------

    @Test
    void hold_transfer_arrive_notify_checkout() {
        // 城西唯一可行来源是城东（其余馆均为单册受保护）
        Hold h = holdService.placeHold(1L, "R001", "WEST");
        assertEquals(HoldStatus.QUEUED, h.getStatus());
        assertEquals(1, h.getQueuePosition());
        assertNotNull(h.getItem());
        assertEquals("EAST", h.getItem().getCurrentLibrary().getCode());
        assertEquals(ItemStatus.LOCKED, h.getItem().getStatus());

        var pending = transferService.pendingPickup();
        assertEquals(1, pending.size());
        Long taskId = pending.get(0).getId();

        TransferBatch batch = transferService.pickup(List.of(taskId), "物流员小赵");
        var picked = tasks.findById(taskId).orElseThrow();
        assertEquals(TaskStatus.PICKED_UP, picked.getStatus());
        assertNotNull(picked.getPackageNo());
        assertEquals(batch.getId(), picked.getBatch().getId());
        assertEquals("EAST", picked.getFromLibrary().getCode());
        assertEquals("WEST", picked.getToLibrary().getCode());
        assertEquals(t0.plus(72, ChronoUnit.HOURS), picked.getEta());
        assertEquals(ItemStatus.IN_TRANSIT, items.findByBarcode(picked.getItem().getBarcode()).orElseThrow().getStatus());

        TransferTask arrived = transferService.arrive(picked.getPackageNo());
        assertEquals(TaskStatus.ARRIVED, arrived.getStatus());
        Hold ready = holds.findById(h.getId()).orElseThrow();
        assertEquals(HoldStatus.READY, ready.getStatus());
        assertEquals(ItemStatus.ON_HOLD, ready.getItem().getStatus());
        assertEquals("WEST", ready.getItem().getCurrentLibrary().getCode());
        assertEquals(t0.plus(3, ChronoUnit.DAYS), ready.getExpiresAt());

        // 别人不能借走预约架上属于 R001 的书
        assertThrows(ApiException.class,
                () -> loanService.checkout("R002", ready.getItem().getBarcode(), "WEST"));

        Loan loan = loanService.checkout("R001", ready.getItem().getBarcode(), "WEST");
        assertEquals(t0.plus(30, ChronoUnit.DAYS), loan.getDueDate());
        assertEquals(ItemStatus.CHECKED_OUT, loan.getItem().getStatus());
        assertEquals(HoldStatus.BORROWED, holds.findById(h.getId()).orElseThrow().getStatus());
    }

    // ---------- 3. FIFO 排队 + 到馆超时释放给下一位 + 跨馆还书再调拨 ----------

    @Test
    void queueFIFO_expiryPassToNext_crossBranchReturn() {
        // 红楼梦：中心馆 1 册本地可借，城北 1 册（单册保护不可调出）
        Hold h1 = holdService.placeHold(2L, "R001", "CENTRAL");
        Hold h2 = holdService.placeHold(2L, "R002", "CENTRAL");
        Hold h3 = holdService.placeHold(2L, "R003", "CENTRAL");
        assertEquals(HoldStatus.READY, h1.getStatus());
        assertEquals(HoldStatus.QUEUED, h2.getStatus());
        assertEquals(1, h2.getQueuePosition());
        assertEquals(2, h3.getQueuePosition());

        // 取书超时（闭馆顺延逻辑之外，正常 3 天期限）
        clock.setFixed(t0.plus(4, ChronoUnit.DAYS));
        int processed = holdService.expireOverdueHolds();
        assertEquals(1, processed);
        assertEquals(HoldStatus.EXPIRED, holds.findById(h1.getId()).orElseThrow().getStatus());
        assertEquals(HoldStatus.READY, holds.findById(h2.getId()).orElseThrow().getStatus());
        assertEquals(1, holds.findById(h3.getId()).orElseThrow().getQueuePosition());
        String barcode = holds.findById(h2.getId()).orElseThrow().getItem().getBarcode();

        // 第二位取书后跨馆还到城北：应触发给第三位、城北→中心馆的新调拨
        Loan loan = loanService.checkout("R002", barcode, "CENTRAL");
        loanService.returnBook(barcode, "NORTH",
                new LoanService.ReturnAssessment(false, false, false, "完好"));
        TransferTask next = tasks.findActiveForItem(
                items.findByBarcode(barcode).orElseThrow().getId(),
                List.of(TaskStatus.CREATED, TaskStatus.PICKED_UP)).get(0);
        assertEquals("NORTH", next.getFromLibrary().getCode());
        assertEquals("CENTRAL", next.getToLibrary().getCode());
        assertEquals(h3.getId(), next.getHold().getId());
    }

    // ---------- 4. 远端分馆保护：提高保护比例后热门书无法抽空远端库存 ----------

    @Test
    void protectionRatio_blocksShippingLastCopies() {
        policy.update("transfer.protectionRatio", 1.0);
        // 城东 2 册要求留存 2 册 → 所有馆都不可调出，读者只能排队
        Hold h = holdService.placeHold(1L, "R001", "WEST");
        assertEquals(HoldStatus.QUEUED, h.getStatus());
        assertNull(h.getItem(), "无允许调出的副本，不锁条码");
        assertTrue(transferService.pendingPickup().isEmpty());
    }

    // ---------- 5. 物流丢包：读者免责保位 + 自动重新调拨 + 物流赔偿 ----------

    @Test
    void logisticsLost_readerKeepsQueueAndLogisticsCharged() {
        Hold h = holdService.placeHold(1L, "R001", "WEST");
        Long firstTaskId = transferService.pendingPickup().get(0).getId();
        String firstBarcode = h.getItem().getBarcode();
        TransferBatch batch = transferService.pickup(List.of(firstTaskId), "物流员小钱");
        TransferTask picked = tasks.findByBatchId(batch.getId()).get(0);

        transferService.reportLost(picked.getId(), "包裹破损遗失");

        assertEquals(ItemStatus.LOST, items.findByBarcode(firstBarcode).orElseThrow().getStatus());
        Hold requeued = holds.findById(h.getId()).orElseThrow();
        assertEquals(HoldStatus.QUEUED, requeued.getStatus());
        assertEquals(1, requeued.getQueuePosition(), "读者保留队列位置");
        assertNotNull(requeued.getItem(), "自动从城东第二册重新调拨");
        assertNotEquals(firstBarcode, requeued.getItem().getBarcode());

        Fine logisticsFine = fines.findAll().stream()
                .filter(f -> f.getType() == FineType.LOGISTICS_LOSS).findFirst().orElseThrow();
        assertEquals(ChargedTo.LOGISTICS, logisticsFine.getChargedTo());
        assertEquals(0, new BigDecimal("60.00").compareTo(logisticsFine.getAmount()));
        assertNotNull(logisticsFine.getRuleBasis());

        // 新包裹正常到馆，读者仍可取书
        TransferTask second = transferService.pendingPickup().get(0);
        transferService.pickup(List.of(second.getId()), "物流员小孙");
        transferService.arrive(tasks.findById(second.getId()).orElseThrow().getPackageNo());
        assertEquals(HoldStatus.READY, holds.findById(h.getId()).orElseThrow().getStatus());
    }

    // ---------- 6. 取消：出库前取消释放；在途取消到馆后给下一位 ----------

    @Test
    void cancelBeforePickupAndInTransit() {
        Hold h1 = holdService.placeHold(1L, "R001", "WEST");
        Hold h2 = holdService.placeHold(1L, "R002", "WEST");
        Long task1 = transferService.pendingPickup().stream()
                .filter(t -> t.getHold().getId().equals(h1.getId())).findFirst().orElseThrow().getId();

        // 出库前取消：任务取消，副本重新分配给 R002
        holdService.cancelHold(h1.getId());
        assertEquals(TaskStatus.CANCELLED, tasks.findById(task1).orElseThrow().getStatus());
        Hold h2ref = holds.findById(h2.getId()).orElseThrow();
        assertNotNull(h2ref.getItem());
        Long task2 = transferService.pendingPickup().get(0).getId();

        // 出库后、在途期间取消：包裹继续送达，到馆释放给队列下一位 R003
        Hold h3 = holdService.placeHold(1L, "R003", "WEST");
        transferService.pickup(List.of(task2), "物流员小周");
        holdService.cancelHold(h2.getId());
        assertTrue(tasks.findById(task2).orElseThrow().isReleaseOnArrival());

        // 城东再无可调副本（2 册：1 丢在架？不，一册在途一册…）R003 等待在途包裹到馆
        transferService.arrive(tasks.findById(task2).orElseThrow().getPackageNo());
        assertEquals(HoldStatus.READY, holds.findById(h3.getId()).orElseThrow().getStatus());
    }

    // ---------- 7. 逾期罚款累计/封顶、逾期阻止借书、排队阻止续借 ----------

    @Test
    void overdueFine_cap_blocksCheckout_andQueueBlocksRenewal() {
        Loan loan = loanService.checkout("R001", "J-0001", "CENTRAL"); // 解忧杂货店
        // 40 天后：逾期 10 天 × 0.5 = 5 元
        clock.setFixed(t0.plus(40, ChronoUnit.DAYS));
        Fine f = fineService.refreshOverdueFine(loan);
        assertEquals(0, new BigDecimal("5.00").compareTo(f.getAmount()));

        // 90 天后：封顶 20 元
        clock.setFixed(t0.plus(120, ChronoUnit.DAYS));
        fineService.refreshOverdueFine(loan);
        assertEquals(0, new BigDecimal("20.00").compareTo(f.getAmount()));
        assertNotNull(f.getRuleBasis());

        // 有逾期未还 → 拒绝新借书
        assertThrows(ApiException.class, () -> loanService.checkout("R001", "J-0002", "WEST"));

        // 归还后逾期罚款仍在，可借阅恢复；此时有人排队则不可续借
        loanService.returnBook("J-0001", "CENTRAL",
                new LoanService.ReturnAssessment(false, false, false, null));
        Hold waiting = holdService.placeHold(3L, "R002", "CENTRAL"); // 解忧在中心馆的副本刚还回，R002 立即 READY
        assertEquals(HoldStatus.READY, waiting.getStatus());

        // 用 R003 借一册没有排队的书验证续借次数上限（renewLimit=1）
        Loan loan2 = loanService.checkout("R003", "P-0001", "NORTH"); // 平凡的世界，城北唯一册无其他人预约
        var eligible = loanService.renewEligibility(loan2);
        assertTrue(eligible.renewable());
        loanService.renew(loan2.getId());
        var second = loanService.renewEligibility(loans.findById(loan2.getId()).orElseThrow());
        assertFalse(second.renewable());
        assertTrue(second.reason().contains("续借次数上限"));
    }

    // ---------- 8. 还书核验：污损/缺页/缺附件分别计赔，图书退出流转 ----------

    @Test
    void returnWithDamage_multipleFines_itemWithdrawnFromRotation() {
        loanService.checkout("R001", "H-0001", "CENTRAL");
        Loan returned = loanService.returnBook("H-0001", "CENTRAL",
                new LoanService.ReturnAssessment(true, true, true, "水渍、缺附图、光盘缺失"));
        assertEquals("水渍、缺附图、光盘缺失", returned.getConditionNote());
        assertEquals(ItemStatus.DAMAGED, items.findByBarcode("H-0001").orElseThrow().getStatus());

        var readerFines = fines.findAll().stream()
                .filter(x -> x.getReader().getCardNo().equals("R001")).toList();
        assertEquals(3, readerFines.size());
        double total = readerFines.stream().mapToDouble(x -> x.getAmount().doubleValue()).sum();
        assertEquals(35.0, total, 0.001); // 10 + 20 + 5

        // 污损图书不应出现在跨馆可调拨候选中
        var view = catalog.view(2L, "NORTH");
        var central = view.libraries().stream().filter(l -> l.code().equals("CENTRAL")).findFirst().orElseThrow();
        assertEquals(0, central.availableCopies());
    }

    // ---------- 9. “声称已还”：找到则入库并豁免；未找到按赔书；人工调整留痕 ----------

    @Test
    void claimReturned_foundWaivedOverdue_notFoundChargesReplacement_adjustAudited() {
        Loan loan = loanService.checkout("R002", "J-0001", "CENTRAL");
        clock.setFixed(t0.plus(40, ChronoUnit.DAYS));
        fineService.refreshOverdueFine(loan);
        Claim claim = loanService.claimReturned(loan.getId(), "确定还到服务台了");
        assertEquals(LoanStatus.CLAIMED_RETURNED, loans.findById(loan.getId()).orElseThrow().getStatus());

        // 馆员在架上找到：入库 + 逾期罚款豁免
        loanService.resolveClaimFound(claim.getId(), "CENTRAL", "馆员老李");
        assertEquals(LoanStatus.RETURNED, loans.findById(loan.getId()).orElseThrow().getStatus());
        Fine overdue = fines.findAll().stream()
                .filter(x -> x.getType() == FineType.OVERDUE).findFirst().orElseThrow();
        assertEquals(FineStatus.WAIVED, overdue.getStatus());

        // 另一册：核查期满未找到 → 赔书价 50，馆员再调减为 30 并留痕
        Loan loan2 = loanService.checkout("R003", "J-0002", "WEST");
        Claim claim2 = loanService.claimReturned(loan2.getId(), null);
        loanService.resolveClaimNotFound(claim2.getId(), "馆员老李");
        assertEquals(LoanStatus.LOST, loans.findById(loan2.getId()).orElseThrow().getStatus());
        assertEquals(ItemStatus.LOST, items.findByBarcode("J-0002").orElseThrow().getStatus());
        Fine replacement = fines.findAll().stream()
                .filter(x -> x.getType() == FineType.LOST).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("50.00").compareTo(replacement.getAmount()));

        Fine reduced = fineService.adjust(replacement.getId(), "馆员老李", "REDUCE", 30.0,
                "读者为残障人士且首次丢书，人文关怀调减");
        assertEquals(0, new BigDecimal("30.00").compareTo(reduced.getAmount()));
        assertEquals(FineStatus.REDUCED, reduced.getStatus());
        var history = fineService.adjustmentHistory(reduced.getId());
        assertEquals(1, history.size());
        assertEquals("馆员老李", history.get(0).getStaff());
        assertEquals(0, new BigDecimal("50.00").compareTo(history.get(0).getOldAmount()));

        // 无理由调整必须被拒绝
        assertThrows(ApiException.class,
                () -> fineService.adjust(reduced.getId(), "馆员老李", "WAIVE", null, "  "));
    }

    // ---------- 10. 临时闭馆：取书时限与临期应还日期自动顺延 ----------

    @Test
    void temporaryClosure_extendsPickupAndDueDates() {
        Hold h = holdService.placeHold(2L, "R001", "CENTRAL"); // 本地在架 READY
        Instant oldExpiry = h.getExpiresAt();

        // R003 借一本 25 小时后到期的场景用固定策略：直接借 30 天期书，闭馆顺延后应还日期增加 24h
        Loan loan = loanService.checkout("R003", "P-0001", "NORTH");
        Instant oldDue = loan.getDueDate();

        libraryAdmin.setTemporaryClosure("CENTRAL", true, "设备检修");
        assertEquals(oldExpiry.plus(24, ChronoUnit.HOURS),
                holds.findById(h.getId()).orElseThrow().getExpiresAt());

        // 中心馆闭馆不影响城北借出的书
        assertEquals(oldDue, loans.findById(loan.getId()).orElseThrow().getDueDate());

        // 临近应还日期时城北闭馆：该书在宽限窗口内到期 → 顺延 24h
        clock.setFixed(oldDue.minus(12, ChronoUnit.HOURS));
        libraryAdmin.setTemporaryClosure("NORTH", true, "台风");
        assertEquals(oldDue.plus(24, ChronoUnit.HOURS),
                loans.findById(loan.getId()).orElseThrow().getDueDate());

        // 回到闭馆后第 4 天内：取书时限已顺延，超时任务不得释放预约
        clock.setFixed(oldExpiry.plus(1, ChronoUnit.HOURS));
        assertEquals(0, holdService.expireOverdueHolds());
        assertEquals(HoldStatus.READY, holds.findById(h.getId()).orElseThrow().getStatus());
    }

    // ---------- 11. 管理端统计冒烟：总览/热门/批次/读者/单馆 ----------

    @Test
    void adminStats_areConsistentAfterFlow() {
        Hold h = holdService.placeHold(1L, "R001", "WEST");
        Long taskId = transferService.pendingPickup().get(0).getId();
        transferService.pickup(List.of(taskId), "物流员小赵");
        transferService.arrive(tasks.findById(taskId).orElseThrow().getPackageNo());
        loanService.checkout("R001", h.getItem().getBarcode(), "WEST");

        var dashboard = stats.dashboard(30);
        assertTrue(dashboard.transferTasks() >= 1);
        assertTrue(dashboard.arrivalRatePercent() >= 0);

        var popular = stats.popularBibs(30, 5);
        assertTrue(popular.stream().anyMatch(p -> p.title().equals("三体")));

        assertEquals(1, stats.batchStats().size());
        var west = stats.singleLibrary("WEST", 30);
        assertTrue(west.incomingTasks() >= 1);
        assertTrue(west.avgIncomingHours() >= 0);

        var r1 = stats.readerOverview("R001").get(0);
        assertEquals("R001", r1.cardNo());
        assertTrue(r1.activeLoans() >= 1);

        var bib = stats.bibStats(1L);
        assertEquals(6, bib.totalCopies());
        assertNotNull(bib.copiesByStatus().get("CHECKED_OUT"));
    }

    // ---------- 12. 热门书需求建议 + 暂停续借生效/解除 ----------

    @Test
    void demandAdvice_purchaseAndFreezeRenewalSuggestions_freezeBlocksRenew() {
        // R001 借走红楼梦中心馆唯一册，造成 3 人排队且跨馆无可调剂（城北单册受保护）
        Loan loan = loanService.checkout("R001", "H-0001", "CENTRAL");
        newReader("R101");
        newReader("R102");
        newReader("R103");
        holdService.placeHold(2L, "R101", "CENTRAL");
        holdService.placeHold(2L, "R102", "CENTRAL");
        holdService.placeHold(2L, "R103", "CENTRAL");

        var advice = demand.advice(2L);
        assertEquals(3, advice.waitingCount());
        assertEquals(3, advice.queuedUnassigned());
        assertEquals(2, advice.circulatingCopies());
        assertTrue(advice.suggestPurchase(), "3 人排队 2 册、在架 0，应建议新增采购");
        assertTrue(advice.suggestedCopies() >= 1);
        assertTrue(advice.suggestFreezeRenewal(), "存在可续借在借且有人排队，应建议暂停续借");
        assertTrue(advice.rationale().contains("等待/复本比"));
        assertNotNull(advice.byLibrary().get("CENTRAL"));
        assertTrue(demand.adviceList().stream().anyMatch(a -> a.bibId() == 2L));

        // 按建议暂停续借：在借复本立即不可续借
        copyAllocation.freezeRenewal(2L, "王馆长", "红楼梦预约积压，暂停续借加速回流");
        var frozen = loanService.renewEligibility(loans.findById(loan.getId()).orElseThrow());
        assertFalse(frozen.renewable());
        assertTrue(frozen.reason().contains("暂停续借"));
        assertThrows(ApiException.class, () -> loanService.renew(loan.getId()));
        assertTrue(copyAllocation.isRenewalFrozen(2L));

        // 无原因/无操作人不得设置或解除
        assertThrows(ApiException.class, () -> copyAllocation.freezeRenewal(3L, "  ", "x"));
        assertThrows(ApiException.class, () -> copyAllocation.releaseRenewal(2L, "王馆长", " "));

        // 解除后冻结记录归档（该书仍有排队，常规“排队阻续”规则继续生效）
        copyAllocation.releaseRenewal(2L, "王馆长", "采购复本已到馆，队列开始消化");
        assertFalse(copyAllocation.isRenewalFrozen(2L));
        var freezeRow = freezes.findAll().get(0);
        assertFalse(freezeRow.isActive());
        assertEquals("采购复本已到馆，队列开始消化", freezeRow.getReleaseReason());
    }

    // ---------- 13. 新购复本按预约时间+分馆等待均衡分配，不全部流向总馆，通知含取书窗口 ----------

    @Test
    void newCopies_balancedAcrossBranches_byReservationTimeAndBranchWait() {
        loanService.checkout("R001", "P-0001", "NORTH"); // 平凡的世界唯一册借出 → 全网无书可分
        Reader c1 = newReader("R201");
        Reader c2 = newReader("R202");
        Reader w1 = newReader("R203");
        Reader w2 = newReader("R204");
        Reader s1 = newReader("R205");
        clock.setFixed(t0);
        holdService.placeHold(4L, c1.getCardNo(), "CENTRAL");
        clock.setFixed(t0.plus(1, ChronoUnit.HOURS));
        holdService.placeHold(4L, c2.getCardNo(), "CENTRAL");
        clock.setFixed(t0.plus(2, ChronoUnit.HOURS));
        holdService.placeHold(4L, w1.getCardNo(), "WEST");
        clock.setFixed(t0.plus(3, ChronoUnit.HOURS));
        holdService.placeHold(4L, w2.getCardNo(), "WEST");
        clock.setFixed(t0.plus(5, ChronoUnit.HOURS));
        holdService.placeHold(4L, s1.getCardNo(), "SOUTH");

        // 纯分馆轮次均衡：3 册应各去一个馆，而不是全部补中心馆
        policy.update("allocation.balanceWeight", 1.0);
        Instant decideAt = t0.plus(2, ChronoUnit.DAYS);
        clock.setFixed(decideAt);
        var acq = copyAllocation.registerAcquisition(4L, 3, "CENTRAL", "采购馆员小冯", "热门书加购");
        var rows = copyAllocations.findByAcquisitionIdOrderById(acq.getId());
        assertEquals(3, rows.size());
        assertEquals(List.of("CENTRAL", "WEST", "SOUTH"),
                rows.stream().map(r -> r.getTargetLibrary().getCode()).toList(),
                "均衡算法按分馆轮次分配，避免全部流向总馆");
        assertTrue(rows.stream().allMatch(r -> r.getStatus() == AllocationStatus.SUGGESTED));
        assertEquals(List.of(c1.getId(), w1.getId(), s1.getId()),
                rows.stream().map(r -> r.getHold().getReader().getId()).toList());

        // 新购复本在确认前为 RECEIVED：不计入在架可借，不会被普通预约/检索抢占
        assertTrue(rows.stream().allMatch(r -> r.getItem().getStatus() == ItemStatus.RECEIVED));
        assertEquals(0, catalog.view(4L, "CENTRAL").localAvailable());

        // 预计取书窗口：本地直架 = 到馆 + 3 天；跨馆 = ETA 72h 后再 + 3 天
        assertEquals(0L, rows.get(0).getEtaHours());
        assertEquals(72L, rows.get(1).getEtaHours());
        assertEquals(72L, rows.get(2).getEtaHours());
        assertEquals(decideAt.plus(3, ChronoUnit.DAYS), rows.get(0).getPickupWindowEnd());
        assertEquals(decideAt.plus(72, ChronoUnit.HOURS).plus(3, ChronoUnit.DAYS),
                rows.get(1).getPickupWindowEnd());

        // 馆员一键确认执行
        copyAllocation.confirmAll(acq.getId(), "王馆长");
        Hold hCentral = holds.findById(rows.get(0).getHold().getId()).orElseThrow();
        assertEquals(HoldStatus.READY, hCentral.getStatus());
        assertEquals(ItemStatus.ON_HOLD, items.findById(rows.get(0).getItem().getId()).orElseThrow().getStatus());
        assertEquals("CENTRAL", hCentral.getItem().getCurrentLibrary().getCode());

        // 跨馆两册生成调拨任务，馆藏锁定在中心馆
        var pending = transferService.pendingPickup();
        assertEquals(2, pending.size());
        assertEquals(List.of("SOUTH", "WEST"), pending.stream()
                .sorted(java.util.Comparator.comparing(t -> t.getToLibrary().getCode()))
                .map(t -> t.getToLibrary().getCode()).toList());
        assertEquals(List.of("CENTRAL", "CENTRAL"),
                pending.stream().map(t -> t.getFromLibrary().getCode()).toList());

        // 通知内容包含预计取书窗口（READY 与跨馆 ALLOCATION 两种）
        var c1Notes = notifications.findByReaderCardNoOrderByCreatedAtDesc(c1.getCardNo());
        assertTrue(c1Notes.get(0).getMessage().contains("即日至"));
        var w1Notes = notifications.findByReaderCardNoOrderByCreatedAtDesc(w1.getCardNo());
        assertTrue(w1Notes.get(0).getMessage().contains("预计在途约 72 小时"));
        assertTrue(w1Notes.get(0).getMessage().contains("预计取书窗口"));

        // 跨馆包裹出库 → 到馆：读者 READY，取书时限按实际到馆时间重算
        TransferTask toWest = pending.stream().filter(t -> t.getToLibrary().getCode().equals("WEST")).findFirst().orElseThrow();
        transferService.pickup(List.of(toWest.getId()), "物流员小赵");
        TransferTask arrived = transferService.arrive(
                tasks.findById(toWest.getId()).orElseThrow().getPackageNo());
        assertEquals(TaskStatus.ARRIVED, arrived.getStatus());
        Hold hW1 = holds.findAll().stream()
                .filter(h -> h.getReader().getId().equals(w1.getId())
                        && h.getBib().getId() == 4L).findFirst().orElseThrow();
        assertEquals(HoldStatus.READY, hW1.getStatus());

        // 未被服务的剩余排队重新编号：中心馆第二=1；城西 w1 在途仍占位 2，城西第二 w2=3
        Hold hC2 = holds.findAll().stream()
                .filter(h -> h.getReader().getId().equals(c2.getId())
                        && h.getBib().getId() == 4L).findFirst().orElseThrow();
        Hold hW2 = holds.findAll().stream()
                .filter(h -> h.getReader().getId().equals(w2.getId())
                        && h.getBib().getId() == 4L).findFirst().orElseThrow();
        assertEquals(1, hC2.getQueuePosition());
        assertEquals(3, hW2.getQueuePosition());

        // 批次完结；流转记录含系统建议与执行
        assertEquals(AcquisitionStatus.ALLOCATED, acq.getStatus());
        var logs = flowLogs.findByBibIdOrderByCreatedAtDesc(4L);
        assertTrue(logs.stream().anyMatch(l -> l.getAction().equals("ALLOCATION_SYSTEM")));
        assertTrue(logs.stream().anyMatch(l -> l.getAction().equals("ALLOCATION_EXECUTE")));
    }

    // ---------- 14. 馆员手动改派建议，原因强制进入馆藏流转记录 ----------

    @Test
    void staffManualAdjust_requiresReasonAndWritesFlowLog_thenExecutesAsManual() {
        loanService.checkout("R001", "P-0001", "NORTH");
        Reader r1 = newReader("R301");
        Reader r2 = newReader("R302");
        Reader r3 = newReader("R303");
        clock.setFixed(t0);
        Hold h1 = holdService.placeHold(4L, r1.getCardNo(), "CENTRAL");
        clock.setFixed(t0.plus(1, ChronoUnit.HOURS));
        Hold h2 = holdService.placeHold(4L, r2.getCardNo(), "WEST");
        clock.setFixed(t0.plus(2, ChronoUnit.HOURS));
        Hold h3 = holdService.placeHold(4L, r3.getCardNo(), "SOUTH");

        var acq = copyAllocation.registerAcquisition(4L, 2, "CENTRAL", "采购馆员小冯", null);
        var rows = copyAllocations.findByAcquisitionIdOrderById(acq.getId());

        // 第一册建议默认给最早的 h1（中心馆本地）；馆员要改派给最晚上传的 h3
        assertEquals(h1.getId(), rows.get(0).getHold().getId());
        // 无原因、无操作人一律拒绝
        assertThrows(ApiException.class,
                () -> copyAllocation.adjustSuggestion(rows.get(0).getId(), h3.getId(), false, null, "王馆长", " "));
        assertThrows(ApiException.class,
                () -> copyAllocation.adjustSuggestion(rows.get(0).getId(), h3.getId(), false, null, "  ", "原因"));
        // 改派给已被其他册占用的预约 → 冲突
        Long occupiedBySecond = rows.get(1).getHold().getId();
        assertThrows(ApiException.class,
                () -> copyAllocation.adjustSuggestion(rows.get(0).getId(), occupiedBySecond, false, null,
                        "王馆长", "尝试重复占用"));

        copyAllocation.adjustSuggestion(rows.get(0).getId(), h3.getId(), false, null,
                "王馆长", "读者为残障人士且城南取书，优先照顾");
        // 第二册馆员选择跳过、留在城北上架
        copyAllocation.adjustSuggestion(rows.get(1).getId(), null, true, "NORTH",
                "王馆长", "城北馆下周办书展，留存一册");

        var adjusted = copyAllocations.findById(rows.get(0).getId()).orElseThrow();
        assertEquals(h3.getId(), adjusted.getHold().getId());
        assertEquals("SOUTH", adjusted.getTargetLibrary().getCode());
        assertEquals(AllocationDecidedBy.STAFF, adjusted.getDecidedBy());
        assertEquals(AllocationStatus.SUGGESTED, adjusted.getStatus(), "改派后仍需馆员确认才执行");
        var skipped = copyAllocations.findById(rows.get(1).getId()).orElseThrow();
        assertNull(skipped.getHold());
        assertEquals("SHELF", skipped.getFulfillMode());
        assertEquals("NORTH", skipped.getTargetLibrary().getCode());

        // 调整原因进入馆藏流转记录
        var manualLogs = flowLogs.findByBibIdOrderByCreatedAtDesc(4L).stream()
                .filter(l -> l.getAction().equals("ALLOCATION_MANUAL")).toList();
        assertEquals(2, manualLogs.size());
        assertTrue(manualLogs.stream().anyMatch(l -> l.getDetail().contains("残障人士")));
        assertTrue(manualLogs.stream().anyMatch(l -> l.getDetail().contains("书展")));

        copyAllocation.confirmAll(acq.getId(), "王馆长");
        var after = copyAllocations.findByAcquisitionIdOrderById(acq.getId());
        assertEquals(AllocationStatus.MANUAL, after.get(0).getStatus());
        assertEquals(AllocationStatus.SKIPPED, after.get(1).getStatus());
        // 改派给 h3（取书城南）→ 生成中心馆→城南调拨，出库到馆后读者 READY
        assertEquals(HoldStatus.QUEUED, holds.findById(h3.getId()).orElseThrow().getStatus());
        TransferTask toSouth = transferService.pendingPickup().stream()
                .filter(t -> t.getToLibrary().getCode().equals("SOUTH")).findFirst().orElseThrow();
        transferService.pickup(List.of(toSouth.getId()), "物流员小赵");
        transferService.arrive(tasks.findById(toSouth.getId()).orElseThrow().getPackageNo());
        assertEquals(HoldStatus.READY, holds.findById(h3.getId()).orElseThrow().getStatus());
        assertEquals(ItemStatus.AVAILABLE,
                items.findById(after.get(1).getItem().getId()).orElseThrow().getStatus());
        assertEquals("NORTH",
                items.findById(after.get(1).getItem().getId()).orElseThrow().getCurrentLibrary().getCode());
    }

    // ---------- 15. 采购批次在执行前取消：复本上架并走常规再分配，取消原因留痕 ----------

    @Test
    void cancelAcquisition_beforeExecution_shelvesCopiesAndServesQueueWithReason() {
        loanService.checkout("R001", "P-0001", "NORTH");
        Reader r1 = newReader("R401");
        Reader r2 = newReader("R402");
        clock.setFixed(t0);
        Hold h1 = holdService.placeHold(4L, r1.getCardNo(), "CENTRAL");
        clock.setFixed(t0.plus(1, ChronoUnit.HOURS));
        Hold h2 = holdService.placeHold(4L, r2.getCardNo(), "WEST");

        var acq = copyAllocation.registerAcquisition(4L, 1, "CENTRAL", "采购馆员小冯", null);
        assertThrows(ApiException.class,
                () -> copyAllocation.cancelAcquisition(acq.getId(), "王馆长", " "));

        copyAllocation.cancelAcquisition(acq.getId(), "王馆长", "供应商书目版本错误，本批退回重发");
        assertEquals(AcquisitionStatus.CANCELLED, acq.getStatus());
        var row = copyAllocations.findByAcquisitionIdOrderById(acq.getId()).get(0);
        assertEquals(AllocationStatus.SKIPPED, row.getStatus());
        // 复本在收货馆（中心馆）上架后经常规单点再分配，优先服务取书点同馆的队首
        assertEquals(HoldStatus.READY, holds.findById(h1.getId()).orElseThrow().getStatus());
        assertEquals(ItemStatus.ON_HOLD,
                items.findById(row.getItem().getId()).orElseThrow().getStatus());
        assertEquals(HoldStatus.QUEUED, holds.findById(h2.getId()).orElseThrow().getStatus());
        var cancelLog = flowLogs.findByBibIdOrderByCreatedAtDesc(4L).stream()
                .filter(l -> l.getAction().equals("ACQUISITION_CANCEL")).findFirst().orElseThrow();
        assertTrue(cancelLog.getDetail().contains("版本错误"));
    }

    private Reader newReader(String card) {
        Reader r = new Reader();
        r.setCardNo(card);
        r.setName(card);
        r.setEmail(card + "@example.com");
        r.setStatus("ACTIVE");
        return readers.save(r);
    }
}
