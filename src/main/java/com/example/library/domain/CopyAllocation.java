package com.example.library.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 采购批次中一册新复本的分配记录：
 * 系统先按“预约时间 + 分馆等待时长均衡”给出 SUGGESTED 建议，
 * 馆员可确认（CONFIRMED）或改派（MANUAL，强制填写原因，进入馆藏流转记录），
 * 暂无合适对象时可跳过（SKIPPED，复本在目标馆上架可借）。
 */
@Entity
@Table(name = "copy_allocations")
@Getter
@Setter
public class CopyAllocation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "acquisition_id")
    private ItemAcquisition acquisition;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id")
    private Item item;

    /** 建议/改派指向的预约；跳过（直接上架）时为空 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hold_id")
    private Hold hold;

    /** 建议初始落点馆：分配给跨馆读者时为复本先送达的取书馆，跳过时为上架馆 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_library_id")
    private Library targetLibrary;

    /** SUGGESTED / CONFIRMED / MANUAL / SKIPPED */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AllocationStatus status;

    /** SYSTEM 系统建议 / STAFF 馆员改派 */
    @Enumerated(EnumType.STRING)
    @Column(name = "decided_by", nullable = false, length = 16)
    private AllocationDecidedBy decidedBy;

    @Column(length = 64)
    private String staff;

    /** LOCAL 取书馆即落点馆（到馆即上架待取）/ TRANSFER 需继续跨馆调拨 */
    @Column(name = "fulfill_mode", nullable = false, length = 16)
    private String fulfillMode;

    @Column(name = "eta_hours", nullable = false)
    private long etaHours;

    /** 通知读者的预计取书窗口截止时间（落点馆 + 取书时限 / 调拨 ETA 推算） */
    @Column(name = "pickup_window_end")
    private Instant pickupWindowEnd;

    /** 馆员手动改派/跳过的强制留痕原因 */
    @Column(length = 512)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;
}
