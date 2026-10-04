package com.example.library.web.dto.admin;

import jakarta.validation.constraints.NotBlank;

/** 解除热门书暂停续借 */
public record ReleaseFreezeRequest(
        @NotBlank String staff,
        @NotBlank String reason
) {}
