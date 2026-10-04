package com.example.library.web.dto.admin;

import java.time.Instant;

/** 馆藏流转记录视图（新增采购/系统分配/馆员改派/暂停续借等全部可审计） */
public record FlowLogView(
        Long id,
        Long bibId,
        Long itemId,
        String itemBarcode,
        Long holdId,
        String action,
        String detail,
        String staff,
        Instant createdAt
) {}
