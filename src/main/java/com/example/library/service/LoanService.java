package com.example.library.service;

import com.example.library.domain.*;
import com.example.library.repo.*;
import com.example.library.web.ApiException;
import com.example.library.web.Views;
import com.example.library.web.dto.ClaimView;
import com.example.library.web.dto.LoanView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 借阅生命周期：借出、应还日期、续借资格（次数上限 + 排队冲突）、
 * 还书核验（条码/馆藏状态/污损/缺页/附件）、逾期与赔书罚款、
 * “声称已还”争议处理。
 */
@Service
public class LoanService {

    private final LoanRepository loans;
    private final ItemRepository items;
    private final ReaderRepository readers;
    private final LibraryRepository libraries;
    private final HoldRepository holds;
    private final ClaimRepository claims;
    private final HoldRenewalFreezeRepository renewalFreezes;
    private final PolicyService policy;
    private final FineService fines;
    private final HoldService holdService;
    private final NotificationService notifications;
    private final AppClock clock;

    public LoanService(LoanRepository loans, ItemRepository items, ReaderRepository readers,
                       LibraryRepository libraries, HoldRepository holds, ClaimRepository claims,
                       HoldRenewalFreezeRepository renewalFreezes,
                       PolicyService policy, FineService fines, HoldService holdService,
                       NotificationService notifications, AppClock clock) {
        this.loans = loans;
        this.items = items;
        this.readers = readers;
        this.libraries = libraries;
        this.holds = holds;
        this.claims = claims;
        this.renewalFreezes = renewalFreezes;
        this.policy = policy;
        this.fines = fines;
        this.holdService = holdService;
        this.notifications = notifications;
        this.clock = clock;
    }

    public record RenewCheck(boolean renewable, String reason, String ruleBasis) {}

    /** 供 HTTP 层按 id 查询：实体加载与懒访问在同一只读事务内完成 */
    @Transactional(readOnly = true)
    public RenewCheck renewCheck(Long loanId) {
        return renewEligibility(get(loanId));
    }

    @Transactional(readOnly = true)
    public RenewCheck renewEligibility(Loan loan) {
        if (loan.getStatus() != LoanStatus.ACTIVE && loan.getStatus() != LoanStatus.CLAIMED_RETURNED) {
            return new RenewCheck(false, "借阅单状态为 " + loan.getStatus(), null);
        }
        if (loan.getStatus() == LoanStatus.CLAIMED_RETURNED) {
            return new RenewCheck(false, "该借阅单存在“声称已还”争议，待馆方核查", null);
        }
        // 热门书暂停续借（馆员按需求建议设置）：冻结期间在借复本一律不得续借，加速回流
        if (!renewalFreezes.findActiveByBib(loan.getItem().getBib().getId()).isEmpty()) {
            return new RenewCheck(false,
                    "《" + loan.getItem().getBib().getTitle() + "》为热门预约书，馆方已暂停续借，"
                            + "请到期归还，复本将优先分配给排队读者",
                    "热门书暂停续借：该书目存在生效中的 hold_renewal_freezes 记录");
        }
        if (loan.getRenewCount() >= policy.renewLimit()) {
            return new RenewCheck(false,
                    "已达续借次数上限 " + policy.renewLimit() + "（热门书不得被长期独占）",
                    "loan.renewLimit=" + policy.renewLimit());
        }
        long waiting = holds.findActiveByBib(loan.getItem().getBib().getId(),
                CatalogService.ACTIVE_HOLD_STATUSES).size();
        if (waiting > 0) {
            return new RenewCheck(false,
                    "该书当前有 " + waiting + " 位读者在预约排队，热门书有排队时不可续借",
                    "排队冲突优先：等候中的 " + waiting + " 个预约优先获得该书");
        }
        return new RenewCheck(true, null,
                "续借后应还日期顺延 " + policy.loanPeriodDays() + " 天；剩余续借次数 "
                        + (policy.renewLimit() - loan.getRenewCount() - 1));
    }

    @Transactional
    public Loan checkout(String cardNo, String barcode, String libraryCode) {
        Reader reader = readers.findByCardNo(cardNo)
                .orElseThrow(() -> ApiException.notFound("读者证不存在: " + cardNo));
        if (!"ACTIVE".equals(reader.getStatus())) {
            throw ApiException.unprocessable("读者证已停用");
        }
        Item item = items.findByBarcode(barcode)
                .orElseThrow(() -> ApiException.notFound("馆藏条码不存在: " + barcode));
        holdService.lockActiveSet(item.getBib().getId());
        items.lockById(item.getId());
        Library lib = libraries.findByCode(libraryCode)
                .orElseThrow(() -> ApiException.notFound("分馆不存在: " + libraryCode));

        long overdueCount = loans.countOverdueForReader(reader.getId(), LoanStatus.ACTIVE, clock.now());
        if (overdueCount > 0) {
            throw ApiException.unprocessable("您有 " + overdueCount + " 册逾期未还图书，请先归还后再借书");
        }

        Instant due = clock.now().plus(policy.loanPeriodDays(), ChronoUnit.DAYS);
        if (lib.isTemporaryClosed()) {
            due = due.plus(policy.closureGraceHours(), ChronoUnit.HOURS);
        }

        if (item.getStatus() == ItemStatus.ON_HOLD) {
            Hold mine = holds.findActiveByBib(item.getBib().getId(), CatalogService.ACTIVE_HOLD_STATUSES).stream()
                    .filter(h -> h.getStatus() == HoldStatus.READY
                            && h.getItem() != null && h.getItem().getId().equals(item.getId())
                            && h.getReader().getId().equals(reader.getId()))
                    .findFirst()
                    .orElseThrow(() -> ApiException.conflict(
                            "条码 " + barcode + " 在预约架上，属于其他读者的预约，不能直接借出"));
            mine.setStatus(HoldStatus.BORROWED);
            mine.setQueuePosition(null);
        } else if (item.getStatus() != ItemStatus.AVAILABLE) {
            throw ApiException.conflict("条码 " + barcode + " 当前状态为 " + item.getStatus() + "，不可借出");
        }

        Loan loan = new Loan();
        loan.setReader(reader);
        loan.setItem(item);
        loan.setCheckoutLibrary(lib);
        loan.setCheckedOutAt(clock.now());
        loan.setDueDate(due);
        loan.setStatus(LoanStatus.ACTIVE);
        loans.save(loan);

        item.setStatus(ItemStatus.CHECKED_OUT);
        item.setCurrentLibrary(lib);
        return loan;
    }

    @Transactional
    public Loan renew(Long loanId) {
        Loan loan = loans.findById(loanId)
                .orElseThrow(() -> ApiException.notFound("借阅单不存在: " + loanId));
        RenewCheck check = renewEligibility(loan);
        if (!check.renewable()) {
            throw ApiException.unprocessable("不能续借：" + check.reason());
        }
        loan.setRenewCount(loan.getRenewCount() + 1);
        loan.setDueDate(loan.getDueDate().plus(policy.loanPeriodDays(), ChronoUnit.DAYS));
        return loan;
    }

    /** 还书核验结果（工作人员录入） */
    public record ReturnAssessment(boolean damaged, boolean missingPages, boolean accessoryMissing,
                                   String note) {}

    @Transactional
    public Loan returnBook(String barcode, String returnLibraryCode, ReturnAssessment assessment) {
        Item item = items.findByBarcode(barcode)
                .orElseThrow(() -> ApiException.notFound("馆藏条码不存在: " + barcode));
        // 全局加锁顺序：预约集 → 馆藏行
        holdService.lockActiveSet(item.getBib().getId());
        items.lockById(item.getId());
        Loan loan = loans.findFirstByItemIdAndStatusOrderByCheckedOutAtDesc(item.getId(), LoanStatus.ACTIVE)
                .or(() -> loans.findFirstByItemIdAndStatusOrderByCheckedOutAtDesc(
                        item.getId(), LoanStatus.CLAIMED_RETURNED))
                .orElseThrow(() -> ApiException.conflict("条码 " + barcode + " 没有进行中的借阅记录"));
        Library lib = libraries.findByCode(returnLibraryCode)
                .orElseThrow(() -> ApiException.notFound("还书分馆不存在: " + returnLibraryCode));

        Instant now = clock.now();
        loan.setStatus(LoanStatus.RETURNED);
        loan.setReturnedAt(now);
        loan.setReturnedLibrary(lib);
        loan.setConditionNote(assessment.note());
        item.setCurrentLibrary(lib);

        // 逾期罚款结算（金额封顶，保留 OPEN 供读者缴纳/馆员调整）
        if (now.isAfter(loan.getDueDate())) {
            fines.refreshOverdueFine(loan);
        }

        boolean unfit = assessment.damaged() || assessment.missingPages() || assessment.accessoryMissing();
        if (assessment.damaged()) {
            item.setCondition("DAMAGED");
            applyDamageFine(loan, FineType.DAMAGE, policy.feeDamage(),
                    "污损", "fee.damage=" + FineService.money(policy.feeDamage()) + " 元/册");
        }
        if (assessment.missingPages()) {
            item.setMissingPages(true);
            item.setCondition("DAMAGED");
            applyDamageFine(loan, FineType.MISSING_PAGES, policy.feeMissingPages(),
                    "缺页", "fee.missingPages=" + FineService.money(policy.feeMissingPages()) + " 元/册");
        }
        if (assessment.accessoryMissing()) {
            applyDamageFine(loan, FineType.ACCESSORY, policy.feeAccessory(),
                    "缺附件", "fee.accessory=" + FineService.money(policy.feeAccessory()) + " 元/件");
        }

        // 关联的待处理“声称已还”工单自动结案
        claims.findFirstByLoanIdAndStatus(loan.getId(), ClaimStatus.OPEN).ifPresent(c -> {
            c.setStatus(ClaimStatus.FOUND);
            c.setResolvedAt(now);
            c.setNote((c.getNote() == null ? "" : c.getNote() + "；") + "图书实际归还入库");
        });

        if (unfit) {
            // 污损/缺页图书不参与再分配，转维修/注销评估
            item.setStatus(ItemStatus.DAMAGED);
        } else {
            // 完好图书：优先满足该书目排队中的预约（可能继续跨馆调拨），否则在还书馆上架
            holdService.reallocate(item.getId(), lib);
        }
        return loan;
    }

    private void applyDamageFine(Loan loan, FineType type, double amount, String label, String basis) {
        fines.create(loan.getReader(), type, ChargedTo.READER, amount,
                "《" + loan.getItem().getBib().getTitle() + "》条码 " + loan.getItem().getBarcode()
                        + " 还书核验发现" + label,
                basis, loan, null);
        notifications.notify(loan.getReader(), "FINE_OPEN", "赔偿通知",
                "您归还的《" + loan.getItem().getBib().getTitle() + "》核验发现" + label
                        + "，按规则需赔偿 " + FineService.money(amount) + " 元。如有异议可到馆申诉。");
    }

    // ---------------- “声称已还”争议 ----------------

    @Transactional
    public Claim claimReturned(Long loanId, String note) {
        Loan loan = loans.findById(loanId)
                .orElseThrow(() -> ApiException.notFound("借阅单不存在: " + loanId));
        if (loan.getStatus() != LoanStatus.ACTIVE) {
            throw ApiException.conflict("借阅单状态为 " + loan.getStatus() + "，不能提交声称已还");
        }
        claims.findFirstByLoanIdAndStatus(loanId, ClaimStatus.OPEN).ifPresent(c -> {
            throw ApiException.conflict("该借阅单已有进行中的核查工单 #" + c.getId());
        });
        Claim c = new Claim();
        c.setLoan(loan);
        c.setReader(loan.getReader());
        c.setStatus(ClaimStatus.OPEN);
        c.setNote(note);
        c.setCreatedAt(clock.now());
        claims.save(c);

        loan.setStatus(LoanStatus.CLAIMED_RETURNED);
        notifications.notify(loan.getReader(), "CLAIM", "已还声明已受理",
                "我们已受理您关于《" + loan.getItem().getBib().getTitle() + "》的已还声明，"
                        + "馆员将核查上架记录；核查期间暂停累计逾期罚款。");
        return c;
    }

    /** 馆员在书架找到：图书正常入库，借阅结案；逾期罚款视为馆方流程原因予以豁免 */
    @Transactional
    public Claim resolveClaimFound(Long claimId, String foundLibraryCode, String staff) {
        Claim c = mustOpenClaim(claimId);
        Library lib = libraries.findByCode(foundLibraryCode)
                .orElseThrow(() -> ApiException.notFound("分馆不存在: " + foundLibraryCode));
        Loan loan = c.getLoan();
        Item item = items.lockById(loan.getItem().getId()).orElseThrow();
        Instant now = clock.now();

        c.setStatus(ClaimStatus.FOUND);
        c.setStaff(staff);
        c.setResolvedAt(now);
        c.setNote((c.getNote() == null ? "" : c.getNote() + "；") + "馆员在" + lib.getName() + "找到并入库");

        loan.setStatus(LoanStatus.RETURNED);
        loan.setReturnedAt(now);
        loan.setReturnedLibrary(lib);
        item.setCurrentLibrary(lib);

        fines.forLoan(loan.getId()).stream()
                .filter(f -> f.getType() == FineType.OVERDUE && f.getStatus() == FineStatus.OPEN)
                .forEach(f -> fines.adjust(f.getId(), staff, "WAIVE", null,
                        "读者声称已还且馆员在馆内找到，逾期为上架流程延误，豁免读者罚款"));

        notifications.notify(loan.getReader(), "CLAIM", "核查结果：书已找到",
                "您声明已还的《" + item.getBib().getTitle() + "》已在" + lib.getName()
                        + "找到，逾期罚款已豁免，感谢配合。");
        holdService.reallocate(item.getId(), lib);
        return c;
    }

    /** 逾期未找到：按丢书赔书处理（人工可再调减/豁免），该书从预约流转中退出 */
    @Transactional
    public Claim resolveClaimNotFound(Long claimId, String staff) {
        Claim c = mustOpenClaim(claimId);
        Loan loan = c.getLoan();
        Item item = items.lockById(loan.getItem().getId()).orElseThrow();
        Instant now = clock.now();

        c.setStatus(ClaimStatus.NOT_FOUND);
        c.setStaff(staff);
        c.setResolvedAt(now);
        loan.setStatus(LoanStatus.LOST);
        item.setStatus(ItemStatus.LOST);

        fines.create(loan.getReader(), FineType.LOST, ChargedTo.READER, policy.feeReplacement(),
                "《" + item.getBib().getTitle() + "》条码 " + item.getBarcode()
                        + " 声称已还核查期满未找到，按丢书赔书",
                "fee.replacement=" + FineService.money(policy.feeReplacement())
                        + " 元/册（如事后找到可凭记录申诉退还）",
                loan, null);
        notifications.notify(loan.getReader(), "FINE_OPEN", "核查结果：图书未找到",
                "您声明已还的《" + item.getBib().getTitle() + "》核查期满未找到，"
                        + "按规则需赔书 " + FineService.money(policy.feeReplacement())
                        + " 元。如日后图书找到，可凭工单号 #" + c.getId() + " 申诉。");
        return c;
    }

    /** 馆方豁免结案（如监控证实已投入还书箱但处置失误），图书按馆藏正常流转 */
    @Transactional
    public Claim waiveClaim(Long claimId, String staff, String reason, String foundLibraryCode) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("豁免必须填写理由");
        }
        Claim c = mustOpenClaim(claimId);
        Library lib = libraries.findByCode(foundLibraryCode)
                .orElseThrow(() -> ApiException.notFound("分馆不存在: " + foundLibraryCode));
        Loan loan = c.getLoan();
        Item item = items.lockById(loan.getItem().getId()).orElseThrow();
        Instant now = clock.now();

        c.setStatus(ClaimStatus.WAIVED);
        c.setStaff(staff);
        c.setResolvedAt(now);
        c.setNote((c.getNote() == null ? "" : c.getNote() + "；") + "馆方豁免：" + reason);
        loan.setStatus(LoanStatus.RETURNED);
        loan.setReturnedAt(now);
        loan.setReturnedLibrary(lib);
        item.setCurrentLibrary(lib);

        fines.forLoan(loan.getId()).stream()
                .filter(f -> f.getStatus() == FineStatus.OPEN)
                .forEach(f -> fines.adjust(f.getId(), staff, "WAIVE", null,
                        "声称已还争议馆方豁免：" + reason));
        holdService.reallocate(item.getId(), lib);
        return c;
    }

    private Claim mustOpenClaim(Long claimId) {
        Claim c = claims.findById(claimId)
                .orElseThrow(() -> ApiException.notFound("核查工单不存在: " + claimId));
        if (c.getStatus() != ClaimStatus.OPEN) {
            throw ApiException.conflict("工单已结案：" + c.getStatus());
        }
        return c;
    }

    @Transactional(readOnly = true)
    public List<LoanView> activeLoanViews(String cardNo) {
        return activeForReader(cardNo).stream().map(Views::loan).toList();
    }

    @Transactional
    public LoanView checkoutView(String cardNo, String barcode, String libraryCode) {
        return Views.loan(checkout(cardNo, barcode, libraryCode));
    }

    @Transactional
    public LoanView renewView(Long loanId) {
        return Views.loan(renew(loanId));
    }

    @Transactional
    public LoanView returnView(String barcode, String libraryCode, ReturnAssessment assessment) {
        return Views.loan(returnBook(barcode, libraryCode, assessment));
    }

    @Transactional
    public ClaimView claimView(Long loanId, String note) {
        return Views.claim(claimReturned(loanId, note));
    }

    @Transactional
    public ClaimView resolveFoundView(Long claimId, String code, String staff) {
        return Views.claim(resolveClaimFound(claimId, code, staff));
    }

    @Transactional
    public ClaimView resolveNotFoundView(Long claimId, String staff) {
        return Views.claim(resolveClaimNotFound(claimId, staff));
    }

    @Transactional
    public ClaimView waiveView(Long claimId, String staff, String reason, String code) {
        return Views.claim(waiveClaim(claimId, staff, reason, code));
    }

    @Transactional(readOnly = true)
    public List<Loan> activeForReader(String cardNo) {
        return loans.findByReaderCardNoAndStatusOrderByCheckedOutAtDesc(cardNo, LoanStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public LoanView loanView(Long id) {
        return Views.loan(get(id));
    }

    @Transactional(readOnly = true)
    public Loan get(Long id) {
        return loans.findById(id).orElseThrow(() -> ApiException.notFound("借阅单不存在: " + id));
    }

    @Transactional(readOnly = true)
    public List<Claim> openClaims() {
        return claims.findByStatusOrderByCreatedAt(ClaimStatus.OPEN);
    }

    @Transactional(readOnly = true)
    public List<ClaimView> openClaimViews() {
        return openClaims().stream().map(Views::claim).toList();
    }

    public Instant dueDate(Loan loan) {
        return loan.getDueDate();
    }
}
