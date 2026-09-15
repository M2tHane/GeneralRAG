package com.rag.api.dto;

/**
 * 删除知识库结果（契约 DeletionSummary）。计数为 MySQL 侧即时结果；
 * ES/MinIO 清理由 cleanup_task 补偿异步完成（docs/03-技术路线.md §3.3）。
 */
public record DeletionSummary(
        String kbId,
        long documentsDeleted,
        long chunksDeleted,
        long sessionsDeleted,
        long messagesDeleted,
        long cleanupTasksAccepted) {
}
