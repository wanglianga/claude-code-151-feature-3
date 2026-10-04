package com.example.library.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 馆藏流转记录：新增复本采购到馆、系统按队列自动分配、馆员人工调整分配。
 * 人工调整必须填写原因并在此留痕（操作人、原建议、调整后去向）。
 */
@Entity
@Table(name = "item_movements")
@Getter
@Setter
public class ItemMovement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id")
    private Item item;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private MovementType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_library_id")
    private Library fromLibrary;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_library_id")
    private Library toLibrary;

    /** 分配给的预约（仅 AUTO_ASSIGN / MANUAL_ADJUST 有值） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hold_id")
    private Hold hold;

    /** 操作馆员（系统动作为空） */
    private String staff;

    /** 人工调整原因（MANUAL_ADJUST 必填） */
    private String reason;

    /** 流转细节说明（如系统建议与人工调整的差异） */
    @Column(length = 1024)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
