package com.example.library.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 新增复本采购批次：某热门书一批新到馆复本，到馆后进入队列分配。
 * RECEIVED 已到馆待分配 → ALLOCATED 全部分配完成；异常可 CANCELLED。
 */
@Entity
@Table(name = "item_acquisitions")
@Getter
@Setter
public class ItemAcquisition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bib_id")
    private Bib bib;

    @Column(name = "copy_count", nullable = false)
    private int copyCount;

    /** RECEIVED / ALLOCATED / CANCELLED */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AcquisitionStatus status;

    @Column(length = 64)
    private String staff;

    @Column(length = 512)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "allocated_at")
    private Instant allocatedAt;
}
