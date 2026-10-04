package com.example.library.web.dto;

/** 书目续借暂停状态 */
public record BibRenewalView(
        Long bibId,
        String title,
        boolean renewalSuspended,
        String renewalSuspendReason,
        String staff
) {}
