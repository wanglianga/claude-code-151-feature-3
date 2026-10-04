package com.example.library.web.dto.admin;

import java.time.Instant;

/**
 * 单册新复本的分配建议视图：
 * pickupWindowStart/End 为通知读者的预计取书窗口；
 * fulfillMode=TRANSFER 时窗口含调拨 ETA。
 */
public record CopyAllocationView(
        Long id,
        Long acquisitionId,
        String itemBarcode,
        Long holdId,
        String readerCardNo,
        String readerName,
        Integer queuePosition,
        String targetLibraryCode,
        String targetLibraryName,
        String pickupLibraryCode,
        String pickupLibraryName,
        String status,              // SUGGESTED / CONFIRMED / MANUAL / SKIPPED
        String decidedBy,           // SYSTEM / STAFF
        String staff,
        String fulfillMode,         // LOCAL / TRANSFER
        long etaHours,
        Instant pickupWindowStart,
        Instant pickupWindowEnd,
        String reason,
        Instant holdCreatedAt,
        Instant createdAt,
        Instant decidedAt
) {}
