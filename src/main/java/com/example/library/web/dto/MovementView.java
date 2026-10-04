package com.example.library.web.dto;

import java.time.Instant;

/** 馆藏流转记录视图（采购到馆 / 自动分配 / 人工调整） */
public record MovementView(
        Long id,
        String itemBarcode,
        String type,
        String fromLibraryCode,
        String toLibraryCode,
        Long holdId,
        String staff,
        String reason,
        String detail,
        Instant createdAt
) {}
