package com.rag.domain.enums;

/**
 * 跨存储清理补偿任务状态（V1__init.sql cleanup_task.status）。
 * FAILED 由 @Scheduled 调度器持续重试直至 DONE（无重试上限，attempts/last_error 留痕）。
 */
public enum CleanupStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED
}
