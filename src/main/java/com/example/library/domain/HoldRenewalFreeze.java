package com.example.library.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 热门书暂停续借冻结（按书目）：
 * 需求建议可触发，馆员也可手动设置；冻结期间该书所有在借不可续借，
 * 让复本尽快回流满足预约队列。队列消化后馆员可解除。
 */
@Entity
@Table(name = "hold_renewal_freezes")
@Getter
@Setter
public class HoldRenewalFreeze {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bib_id")
    private Bib bib;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, length = 64)
    private String staff;

    @Column(nullable = false, length = 512)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "release_staff", length = 64)
    private String releaseStaff;

    @Column(name = "release_reason", length = 512)
    private String releaseReason;
}
