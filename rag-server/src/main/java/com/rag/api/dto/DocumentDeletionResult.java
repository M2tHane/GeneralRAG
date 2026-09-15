package com.rag.api.dto;

/**
 * 文档删除结果（契约 DocumentDeletionResult）。chunksDeleted 为 ES 侧最近已知
 * 分块数（document.chunk_count），实际清理由 cleanup_task 补偿异步完成。
 */
public record DocumentDeletionResult(
        String docId,
        int chunksDeleted,
        long cleanupTasksAccepted) {
}
