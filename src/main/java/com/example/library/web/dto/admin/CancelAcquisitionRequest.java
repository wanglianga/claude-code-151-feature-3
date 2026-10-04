package com.example.library.web.dto.admin;

import jakarta.validation.constraints.NotBlank;

/** 取消采购批次（须在所有复本执行前） */
public record CancelAcquisitionRequest(
        @NotBlank String staff,
        @NotBlank String reason
) {}
