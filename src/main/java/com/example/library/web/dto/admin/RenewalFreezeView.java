package com.example.library.web.dto.admin;

import java.time.Instant;

/** 热门书暂停续借冻结记录视图 */
public record RenewalFreezeView(
        Long id,
        Long bibId,
        String bibTitle,
        boolean active,
        String staff,
        String reason,
        Instant createdAt,
        Instant releasedAt,
        String releaseStaff,
        String releaseReason
) {}
