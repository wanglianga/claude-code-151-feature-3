package com.example.library.web.dto.admin;

import java.time.Instant;

/**
 * 单个分馆对某热门书的需求画像：
 * 新复本分配时参考“最早预约时间 / 总等待时长”，避免全部流向总馆。
 */
public record LibraryDemandRow(
        String libraryCode,
        String libraryName,
        long waitingCount,          // 取书馆在本馆的有效预约数
        long unassignedCount,       // 其中尚未锁定副本的排队数
        Instant oldestHoldAt,       // 最早一笔预约时间（等待起点）
        long waitedHours,           // 最早预约至今等待小时数
        long totalWaitedHours       // 该馆全部未满足预约的累计等待小时
) {}
