package com.example.library.domain;

/**
 * 新复本分配记录状态：
 * SUGGESTED 系统建议、待馆员确认；
 * CONFIRMED 馆员采纳系统建议并执行；
 * MANUAL 馆员手动改派后执行（原因进流转记录）；
 * SKIPPED 暂不分配给预约，复本在目标馆上架可借。
 */
public enum AllocationStatus {
    SUGGESTED, CONFIRMED, MANUAL, SKIPPED
}
