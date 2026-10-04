package com.example.library.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 新增复本分配建议（预览）：按“预约时间 + 读者所在馆 + 各分馆等待均衡”计算，
 * 馆员可据此执行或在执行时人工调整。
 */
public record AllocationPlanView(
        Long bibId,
        String title,
        String arrivalLibraryCode,
        String arrivalLibraryName,
        int copies,
        /** 各分馆当前等待情况（为什么这样分的依据） */
        List<BranchWaitRow> branchWaits,
        /** 每一册的建议去向 */
        List<PlannedAssignment> assignments,
        /** 分配规则说明 */
        List<String> rules,
        Instant generatedAt
) {
    public record BranchWaitRow(
            String libraryCode,
            String libraryName,
            long waitingCount,
            /** 该馆最早等待者已等待小时数 */
            double oldestWaitHours
    ) {}

    public record PlannedAssignment(
            int copyIndex,
            /** null 表示该册无排队者可分，到馆后上架可借 */
            Long holdId,
            String readerCardNo,
            String readerName,
            String pickupLibraryCode,
            String pickupLibraryName,
            /** 该读者已等待小时数 */
            double waitedHours,
            /** 复本到馆后调拨在途小时（本馆为 0） */
            long transferEtaHours,
            /** 预计取书窗口 */
            Instant pickupWindowFrom,
            Instant pickupWindowTo,
            String reason
    ) {}
}
