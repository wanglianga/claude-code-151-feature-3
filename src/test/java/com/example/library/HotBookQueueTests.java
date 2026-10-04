package com.example.library;

import com.example.library.domain.*;
import com.example.library.repo.*;
import com.example.library.service.*;
import com.example.library.web.ApiException;
import com.example.library.web.dto.AcquisitionRequest;
import com.example.library.web.dto.AcquisitionResultView;
import com.example.library.web.dto.AllocationPlanView;
import com.example.library.web.dto.DemandAdviceView;
import com.example.library.web.dto.MovementView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 热门书预约队列调控端到端测试：
 * 需求建议（等待人数/复本/续借限制/调拨成本）、暂停续借、
 * 新增复本公平分配（避免全部流向总馆）、人工调整留痕、多余复本上架。
 */
@SpringBootTest
@ActiveProfiles("test")
@org.springframework.transaction.annotation.Transactional
class HotBookQueueTests {

    @Autowired DemandService demandService;
    @Autowired HoldService holdService;
    @Autowired LoanService loanService;
    @Autowired CatalogService catalog;
    @Autowired PolicyService policy;
    @Autowired AppClock clock;
    @Autowired ReaderRepository readers;
    @Autowired ItemRepository items;
    @Autowired HoldRepository holds;
    @Autowired LoanRepository loans;
    @Autowired TransferTaskRepository tasks;
    @Autowired NotificationRepository notifications;
    @Autowired ItemMovementRepository movements;

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

    private Reader newReader(String cardNo, String name) {
        Reader r = new Reader();
        r.setCardNo(cardNo);
        r.setName(name);
        r.setEmail(cardNo.toLowerCase() + "@example.com");
        r.setStatus("ACTIVE");
        return readers.save(r);
    }

    // ---------- 1. 需求建议：综合等待人数、复本数量、续借限制、跨馆调拨成本 ----------

    @Test
    void demandAdvice_considersWaitingCopiesRenewalLimitAndTransferCost() {
        // 红楼梦：中心馆 H-0001、城北 H-0002（单册受外馆保护，不可调出）
        Hold ready = holdService.placeHold(2L, "R001", "CENTRAL"); // 本馆在架 → READY，占用 H-0001
        assertEquals(HoldStatus.READY, ready.getStatus());
        holdService.placeHold(2L, "R002", "CENTRAL"); // 排队等书
        holdService.placeHold(2L, "R003", "WEST");
        newReader("R004", "赵六");
        holdService.placeHold(2L, "R004", "EAST");

        DemandAdviceView advice = demandService.demandAdvice(2L);
        assertEquals(3, advice.waitingCount(), "3 位读者未分到复本");
        assertEquals(4, advice.activeHoldCount());
        assertEquals(2, advice.copiesTotal(), "红楼梦在流转 2 册");
        assertEquals(1, advice.copiesAvailable(), "城北 H-0002 仍在架");
        assertEquals(1.5, advice.demandRatio());
        assertEquals(1, advice.renewLimit(), "续借限制进入建议上下文");
        assertFalse(advice.renewalSuspended());

        // 城北单册受保护 → 3 位等待者均无跨馆调拨来源
        assertEquals(3, advice.infeasibleHoldCount());
        assertNull(advice.avgTransferEtaHours());

        // 等待 3 ≥ 采购阈值 3 且在架不足 → 建议采购 2 册；等待 3 ≥ 暂停续借阈值 2 → 建议暂停续借
        assertTrue(advice.suggestPurchase());
        assertEquals(2, advice.suggestedPurchaseCopies());
        assertTrue(advice.suggestSuspendRenewal());
        assertTrue(advice.reasons().stream().anyMatch(r -> r.contains("demand.purchaseThreshold")));
        assertTrue(advice.reasons().stream().anyMatch(r -> r.contains("跨馆调拨无法缓解")));
        assertTrue(advice.reasons().stream().anyMatch(r -> r.contains("loan.renewLimit")));
    }

    // ---------- 2. 暂停续借：设置后该书一律不可续借，恢复后可续借 ----------

    @Test
    void renewalSuspension_blocksRenewalUntilLifted() {
        Loan loan = loanService.checkout("R001", "J-0001", "CENTRAL"); // 解忧杂货店，无人排队
        assertTrue(loanService.renewEligibility(loan).renewable());

        // 暂停必须填理由
        assertThrows(ApiException.class,
                () -> demandService.setRenewalSuspension(3L, true, "馆员老李", " "));
        demandService.setRenewalSuspension(3L, true, "馆员老李", "新书预约爆满，暂停续借加快流转");

        var check = loanService.renewEligibility(loans.findById(loan.getId()).orElseThrow());
        assertFalse(check.renewable());
        assertTrue(check.reason().contains("暂停续借"));
        assertThrows(ApiException.class, () -> loanService.renew(loan.getId()));

        // 重复暂停 → 冲突；解除后恢复续借资格
        assertThrows(ApiException.class,
                () -> demandService.setRenewalSuspension(3L, true, "馆员老李", "重复设置"));
        var view = demandService.setRenewalSuspension(3L, false, "馆员老李", null);
        assertFalse(view.renewalSuspended());
        assertTrue(loanService.renewEligibility(loans.findById(loan.getId()).orElseThrow()).renewable());
    }

    // ---------- 3. 新增复本分配：按预约时间+读者所在馆，兼顾各分馆等待，避免全部流向总馆 ----------

    /** 把 bib1 全部在架复本借出，使后续预约只能排队等书 */
    private void checkoutAllBib1Copies() {
        loanService.checkout("R001", "C-0001", "CENTRAL");
        loanService.checkout("R001", "C-0002", "NORTH");
        loanService.checkout("R002", "C-0003", "SOUTH");
        loanService.checkout("R002", "C-0004", "EAST");
        loanService.checkout("R003", "C-0005", "EAST");
        loanService.checkout("R003", "C-0006", "EAST");
    }

    @Test
    void acquisition_allocatesFairlyAcrossBranches_notAllToCentral() {
        checkoutAllBib1Copies();
        newReader("R004", "赵六");
        newReader("R005", "钱七");
        newReader("R006", "孙八");
        newReader("R007", "李九");

        // 总馆两位读者预约最早，分馆读者稍晚
        clock.setFixed(t0);
        Hold hCentral1 = holdService.placeHold(1L, "R004", "CENTRAL");
        clock.setFixed(t0.plus(1, ChronoUnit.HOURS));
        Hold hCentral2 = holdService.placeHold(1L, "R005", "CENTRAL");
        clock.setFixed(t0.plus(2, ChronoUnit.HOURS));
        Hold hWest = holdService.placeHold(1L, "R006", "WEST");
        clock.setFixed(t0.plus(3, ChronoUnit.HOURS));
        Hold hSouth = holdService.placeHold(1L, "R007", "SOUTH");
        assertNull(hCentral1.getItem());
        assertNull(hWest.getItem());

        clock.setFixed(t0.plus(10, ChronoUnit.HOURS));
        // 分配建议预览：等待 CENTRAL 10h/9h、WEST 8h(+72h 跨片区在途)、SOUTH 7h(+72h 在途)
        AllocationPlanView plan = demandService.allocationPlan(1L, "CENTRAL", 3);
        assertEquals(hWest.getId(), plan.assignments().get(0).holdId(), "预计总等待最长：城西 8h+72h");
        assertEquals(hSouth.getId(), plan.assignments().get(1).holdId(), "其次：城南 7h+72h");
        assertEquals(hCentral1.getId(), plan.assignments().get(2).holdId(), "其后才轮到总馆最早等待者");
        assertEquals(72, plan.assignments().get(0).transferEtaHours());
        assertEquals(0, plan.assignments().get(2).transferEtaHours());
        assertEquals(3, plan.branchWaits().size());
        var centralWait = plan.branchWaits().stream()
                .filter(b -> b.libraryCode().equals("CENTRAL")).findFirst().orElseThrow();
        assertEquals(2, centralWait.waitingCount());
        assertEquals(10.0, centralWait.oldestWaitHours());

        // 执行采购到馆 3 册
        AcquisitionResultView result = demandService.acquire(1L,
                new AcquisitionRequest("CENTRAL", 3, "馆员小王", null, null));
        assertEquals(3, result.createdCopies());
        assertEquals(0, result.manualAdjustments());

        // 关键公平性断言：3 册没有全部流向总馆——城西、城南各 1 册调拨，总馆仅 1 册
        long toCentral = result.copies().stream()
                .filter(c -> "CENTRAL".equals(c.pickupLibraryCode())).count();
        assertEquals(1, toCentral, "新增复本不得全部流向总馆");
        assertEquals("TRANSFER", result.copies().get(0).assignmentType());
        assertEquals("WEST", result.copies().get(0).pickupLibraryCode());
        assertEquals("LOCAL_READY", result.copies().get(2).assignmentType());
        // 预计取书窗口 = 到馆时刻 + 调拨在途 → 起 + 取书时限 3 天
        assertEquals(t0.plus(82, ChronoUnit.HOURS), result.copies().get(0).pickupWindowFrom());
        assertEquals(t0.plus(82, ChronoUnit.HOURS).plus(3, ChronoUnit.DAYS),
                result.copies().get(0).pickupWindowTo());

        // 状态联动：城西/城南复本 LOCKED 并生成调拨任务；总馆复本 ON_HOLD 待取
        Hold westRef = holds.findById(hWest.getId()).orElseThrow();
        assertEquals(ItemStatus.LOCKED, westRef.getItem().getStatus());
        var westTask = tasks.findActiveForItem(westRef.getItem().getId(),
                List.of(TaskStatus.CREATED)).get(0);
        assertEquals("CENTRAL", westTask.getFromLibrary().getCode());
        assertEquals("WEST", westTask.getToLibrary().getCode());
        assertEquals(HoldStatus.READY, holds.findById(hCentral1.getId()).orElseThrow().getStatus());
        assertEquals(ItemStatus.ON_HOLD, holds.findById(hCentral1.getId()).orElseThrow().getItem().getStatus());
        // 总馆第二位读者继续排队，位置收敛为 1
        Hold c2 = holds.findById(hCentral2.getId()).orElseThrow();
        assertEquals(HoldStatus.QUEUED, c2.getStatus());
        assertEquals(1, c2.getQueuePosition());

        // 通知含预计取书窗口：调拨读者收到调配通知，本馆读者收到取书通知
        var westNotices = notifications.findByReaderCardNoOrderByCreatedAtDesc("R006");
        assertTrue(westNotices.stream().anyMatch(n -> "ALLOCATION".equals(n.getType())
                && n.getMessage().contains("取书窗口") && n.getMessage().contains("预计")));
        var centralNotices = notifications.findByReaderCardNoOrderByCreatedAtDesc("R004");
        assertTrue(centralNotices.stream().anyMatch(n -> "READY".equals(n.getType())
                && n.getMessage().contains("取书窗口")));

        // 馆藏流转记录：每册均有采购到馆 + 自动分配两条留痕
        String barcode0 = result.copies().get(0).barcode();
        List<MovementView> mov0 = demandService.movementsForItem(barcode0);
        assertEquals(2, mov0.size());
        assertEquals("ACQUISITION", mov0.get(0).type());
        assertEquals("CENTRAL", mov0.get(0).toLibraryCode());
        assertEquals("AUTO_ASSIGN", mov0.get(1).type());
        assertEquals("WEST", mov0.get(1).toLibraryCode());
        assertEquals(hWest.getId(), mov0.get(1).holdId());
    }

    // ---------- 4. 人工调整：与建议不一致必须填原因，原因进入馆藏流转记录 ----------

    @Test
    void acquisition_manualOverride_requiresReasonAndIsAudited() {
        checkoutAllBib1Copies();
        newReader("R004", "赵六");
        newReader("R005", "钱七");
        newReader("R006", "孙八");

        clock.setFixed(t0);
        Hold h1 = holdService.placeHold(1L, "R004", "CENTRAL");
        clock.setFixed(t0.plus(1, ChronoUnit.HOURS));
        Hold h2 = holdService.placeHold(1L, "R005", "CENTRAL");
        clock.setFixed(t0.plus(2, ChronoUnit.HOURS));
        Hold h3 = holdService.placeHold(1L, "R006", "WEST");

        clock.setFixed(t0.plus(10, ChronoUnit.HOURS));
        // 系统建议：第 1 册 → 城西 R006（8h+72h），第 2 册 → 总馆 R004（10h）
        AllocationPlanView plan = demandService.allocationPlan(1L, "CENTRAL", 2);
        assertEquals(h3.getId(), plan.assignments().get(0).holdId());
        assertEquals(h1.getId(), plan.assignments().get(1).holdId());

        // 人工把第 2 册调整给 R005：不填原因 → 422
        var overrideToH2 = List.of(new AcquisitionRequest.OverrideAssignment(1, h2.getId()));
        assertThrows(ApiException.class, () -> demandService.acquire(1L,
                new AcquisitionRequest("CENTRAL", 2, "馆员小王", overrideToH2, null)));
        // 调整目标不属于该书目 → 422
        Hold otherBib = holdService.placeHold(2L, "R001", "CENTRAL");
        Long otherBibHold = otherBib.getId();
        assertThrows(ApiException.class, () -> demandService.acquire(1L,
                new AcquisitionRequest("CENTRAL", 2, "馆员小王",
                        List.of(new AcquisitionRequest.OverrideAssignment(1, otherBibHold)), "错书目")));
        // 册序号越界 → 422
        assertThrows(ApiException.class, () -> demandService.acquire(1L,
                new AcquisitionRequest("CENTRAL", 2, "馆员小王",
                        List.of(new AcquisitionRequest.OverrideAssignment(5, h2.getId())), "越界")));

        // 填写原因后执行：第 1 册仍按建议给城西 R006，第 2 册人工调整给 R005
        AcquisitionResultView result = demandService.acquire(1L,
                new AcquisitionRequest("CENTRAL", 2, "馆员小王", overrideToH2, "R005 为老年读者，优先照顾"));
        assertEquals(1, result.manualAdjustments());
        assertEquals(h3.getId(), result.copies().get(0).holdId());
        assertFalse(result.copies().get(0).manuallyAdjusted());
        assertEquals(h2.getId(), result.copies().get(1).holdId());
        assertTrue(result.copies().get(1).manuallyAdjusted());
        assertEquals(HoldStatus.READY, holds.findById(h2.getId()).orElseThrow().getStatus());
        // 被顶替的 R004 继续排队且位置收敛为 1
        Hold h1Ref = holds.findById(h1.getId()).orElseThrow();
        assertEquals(HoldStatus.QUEUED, h1Ref.getStatus());
        assertEquals(1, h1Ref.getQueuePosition());

        // 调整原因进入馆藏流转记录
        String adjustedBarcode = result.copies().get(1).barcode();
        List<MovementView> movs = demandService.movementsForItem(adjustedBarcode);
        var adjust = movs.stream().filter(m -> m.type().equals("MANUAL_ADJUST")).findFirst().orElseThrow();
        assertEquals("R005 为老年读者，优先照顾", adjust.reason());
        assertEquals("馆员小王", adjust.staff());
        assertTrue(adjust.detail().contains("系统建议"));
        assertEquals(h2.getId(), adjust.holdId());
        // 自动分配的第 1 册无人工原因
        var autoMovs = demandService.movementsForItem(result.copies().get(0).barcode());
        assertTrue(autoMovs.stream().noneMatch(m -> m.type().equals("MANUAL_ADJUST")));
        // 书目级流转记录可查
        assertFalse(demandService.movementsForBib(1L).isEmpty());
    }

    // ---------- 5. 复本多于排队：多余复本到馆上架，条码序号连续 ----------

    @Test
    void acquisition_extraCopiesShelvedAtArrivalLibrary() {
        AcquisitionResultView first = demandService.acquire(4L,
                new AcquisitionRequest("EAST", 2, "馆员小王", null, null));
        assertEquals(2, first.createdCopies());
        assertEquals("N4-0001", first.copies().get(0).barcode());
        assertEquals("SHELF", first.copies().get(0).assignmentType());
        assertEquals(ItemStatus.AVAILABLE.name(), first.copies().get(1).status());

        // 城东在架可借变为 2 册
        var view = catalog.view(4L, "EAST");
        assertEquals(2, view.localAvailable());

        // 再次采购：条码序号继续递增不冲突
        AcquisitionResultView second = demandService.acquire(4L,
                new AcquisitionRequest("EAST", 1, "馆员小王", null, null));
        assertEquals("N4-0003", second.copies().get(0).barcode());

        // 上架册只有采购到馆一条流转记录
        List<MovementView> movs = demandService.movementsForItem("N4-0001");
        assertEquals(1, movs.size());
        assertEquals("ACQUISITION", movs.get(0).type());
    }
}
