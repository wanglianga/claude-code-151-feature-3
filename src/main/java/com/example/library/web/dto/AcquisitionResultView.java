package com.example.library.web.dto;

import java.time.Instant;
import java.util.List;

/** 采购到馆执行结果：每册条码、去向与预计取书窗口 */
public record AcquisitionResultView(
        Long bibId,
        String title,
        String arrivalLibraryCode,
        int createdCopies,
        List<CreatedCopy> copies,
        /** 与系统建议不一致、被人工调整的册数 */
        int manualAdjustments,
        String adjustReason
) {
    public record CreatedCopy(
            int copyIndex,
            String barcode,
            String status,
            Long holdId,
            String readerCardNo,
            String pickupLibraryCode,
            /** LOCAL_READY 本馆上架待取 / TRANSFER 调拨中 / SHELF 无排队上架可借 */
            String assignmentType,
            /** 是否被人工调整（与系统建议不一致） */
            boolean manuallyAdjusted,
            Instant pickupWindowFrom,
            Instant pickupWindowTo
    ) {}
}
