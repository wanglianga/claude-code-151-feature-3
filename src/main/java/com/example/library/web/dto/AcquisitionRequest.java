package com.example.library.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 新增复本采购到馆执行请求。
 * overrides 用于馆员人工调整某册的分配去向；只要最终分配与系统建议不一致，
 * 就必须填写 adjustReason（进入馆藏流转记录）。
 */
public record AcquisitionRequest(
        @NotBlank(message = "到馆分馆代码必填") String arrivalLibraryCode,
        @Min(value = 1, message = "采购册数至少为 1") @Max(value = 50, message = "单次采购册数不超过 50") int copies,
        @NotBlank(message = "操作馆员必填") String staff,
        @Valid List<OverrideAssignment> overrides,
        String adjustReason
) {
    public record OverrideAssignment(
            @Min(0) int copyIndex,
            @NotNull(message = "调整目标预约必填") Long holdId
    ) {}
}
