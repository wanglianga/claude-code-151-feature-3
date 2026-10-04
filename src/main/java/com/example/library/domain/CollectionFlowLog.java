package com.example.library.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 馆藏流转记录：新增复本到馆、系统分配建议、馆员手动改派（含原因）、
 * 暂停/解除续借冻结等馆藏与队列相关动作的审计流水。
 */
@Entity
@Table(name = "collection_flow_logs")
@Getter
@Setter
public class CollectionFlowLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bib_id")
    private Bib bib;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private Item item;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hold_id")
    private Hold hold;

    /** ACQUISITION / ALLOCATION_SYSTEM / ALLOCATION_MANUAL / ALLOCATION_EXECUTE /
     *  RENEW_FREEZE / RENEW_RELEASE / ACQUISITION_CANCEL */
    @Column(nullable = false, length = 32)
    private String action;

    @Column(nullable = false, length = 1024)
    private String detail;

    @Column(length = 64)
    private String staff;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
