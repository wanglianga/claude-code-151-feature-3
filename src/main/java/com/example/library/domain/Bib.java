package com.example.library.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "bibs")
@Getter
@Setter
public class Bib {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String isbn;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String author;

    private String subject;

    @Column(name = "ebook_available", nullable = false)
    private boolean ebookAvailable = false;

    @Column(name = "ebook_url")
    private String ebookUrl;

    /** 热门书需求调控：暂停该书续借（等待人数过多时由馆员设置，加快复本回转） */
    @Column(name = "renew_suspended", nullable = false)
    private boolean renewSuspended = false;

    /** 暂停续借的理由（向读者解释用） */
    @Column(name = "renew_suspend_reason")
    private String renewSuspendReason;
}
