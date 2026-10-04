package com.example.library.web.dto.admin;

import jakarta.validation.constraints.NotBlank;

/** 馆员确认执行一条/整批分配建议 */
public record StaffDecisionRequest(@NotBlank String staff) {}
