package com.example.library.web.dto.admin;

import jakarta.validation.constraints.NotBlank;

/**
 * 馆员手动调整某册新复本的系统分配建议：
 * 传 targetHoldId 表示改派给指定排队预约；skip=true 表示跳过分配、在 shelfLibraryCode 上架。
 * staff + reason 必填，原因进入馆藏流转记录。
 */
public record AdjustAllocationRequest(
        Long targetHoldId,
        boolean skip,
        String shelfLibraryCode,
        @NotBlank String staff,
        @NotBlank String reason
) {}
