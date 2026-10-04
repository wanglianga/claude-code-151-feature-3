package com.example.library.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 热门书需求调控建议：综合等待人数、复本数量、续借限制与跨馆调拨成本，
 * 给出“新增采购”与“暂停续借”两项建议及依据。
 */
public record DemandAdviceView(
        Long bibId,
        String title,
        /** 未分到复本、正在等书的排队人数 */
        long waitingCount,
        /** 全部活跃预约（含已到馆待取） */
        long activeHoldCount,
        /** 在流转复本数（不含丢失/注销/污损待修） */
        long copiesTotal,
        /** 当前在架可借复本数 */
        long copiesAvailable,
        /** 在借册数 */
        long activeLoans,
        /** 需求压力 = waitingCount / copiesTotal */
        double demandRatio,
        /** 当前续借次数上限（loan.renewLimit） */
        int renewLimit,
        /** 该书是否已被暂停续借 */
        boolean renewalSuspended,
        String renewalSuspendReason,
        /** 等待者平均可调拨在途小时；null 表示无人可调 */
        Double avgTransferEtaHours,
        /** 无任何可调拨来源的等待人数（跨馆调拨无法缓解） */
        long infeasibleHoldCount,
        boolean suggestPurchase,
        int suggestedPurchaseCopies,
        boolean suggestSuspendRenewal,
        /** 逐项依据（引用规则参数，便于向读者/采购解释） */
        List<String> reasons,
        Instant generatedAt
) {}
