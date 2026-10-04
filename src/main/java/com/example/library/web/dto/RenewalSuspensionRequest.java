package com.example.library.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 设置/解除书目续借暂停（热门书需求调控） */
public record RenewalSuspensionRequest(
        @NotNull Boolean suspended,
        @NotBlank(message = "操作馆员必填") String staff,
        /** 暂停时必填，向读者解释 */
        String reason
) {}
