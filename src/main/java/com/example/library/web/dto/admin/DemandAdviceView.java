package com.example.library.web.dto.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 热门书需求建议：系统根据等待人数、复本数量、续借限制、跨馆调拨成本
 * 给出“新增采购 / 暂停续借”的处置建议。
 */
public record DemandAdviceView(
        Long bibId,
        String title,
        long waitingCount,          // 有效预约总数（排队 + 待取）
        long queuedUnassigned,      // 尚未锁定副本的排队人数
        long circulatingCopies,    // 在流转复本数（排除丢失/污损/注销）
        long availableCopies,       // 当前在架可借数
        long activeLoans,           // 在借册数
        double waitCopyRatio,       // 等待人数 / 在流转复本
        long renewableLoans,        // 按次数规则仍可续借的在借数
        boolean renewalFrozen,      // 是否已暂停续借
        boolean suggestPurchase,    // 建议新增采购
        long suggestedCopies,       // 建议采购册数
        boolean suggestFreezeRenewal, // 建议暂停续借
        String rationale,           // 文字化规则依据（可向馆员解释）
        Map<String, LibraryDemandRow> byLibrary, // 各取书馆等待情况
        Instant evaluatedAt
) {}
