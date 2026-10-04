package com.example.library.web.dto.admin;

import java.time.Instant;
import java.util.List;

/** 新增复本采购批次视图（含每册分配建议） */
public record AcquisitionView(
        Long id,
        Long bibId,
        String bibTitle,
        int copyCount,
        String status,
        String staff,
        String note,
        Instant createdAt,
        Instant allocatedAt,
        List<CopyAllocationView> allocations
) {}
