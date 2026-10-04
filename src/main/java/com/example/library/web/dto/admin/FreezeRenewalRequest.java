package com.example.library.web.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 设置热门书暂停续借 */
public record FreezeRenewalRequest(
        @NotNull Long bibId,
        @NotBlank String staff,
        @NotBlank String reason
) {}
