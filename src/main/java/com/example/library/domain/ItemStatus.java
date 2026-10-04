package com.example.library.domain;

public enum ItemStatus {
    AVAILABLE, LOCKED, IN_TRANSIT, ON_HOLD, CHECKED_OUT, LOST, DAMAGED, WITHDRAWN,
    /** 新采购到馆、等待馆员确认分配建议（不计入在架可借，不会被普通预约抢占） */
    RECEIVED
}
