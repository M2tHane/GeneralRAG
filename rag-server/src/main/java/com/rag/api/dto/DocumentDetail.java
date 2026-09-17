package com.rag.api.dto;

import com.rag.domain.enums.PipelineStage;

import java.time.LocalDateTime;

/**
 * 文档详情（契约 DocumentDetail = Document allOf {chunkConfig, contentSha256,
 * parseMetadata?, failureStage?, failureReason?, task?}）。task 为内嵌当前任务摘要，
 * 详情以 GET /documents/{docId}/task 为准（契约该端点 description）。
 * parseMetadata 为 PDF 解析路由元数据（R6-D/R6-D.1）：AUTO 模式含
 * selected/routingReason/probe（正式解析失败时同样写入）；手动模式为
 * identity metadata（requested=selected，routingReason=null，无 probe）；非 PDF 恒 null。
 */
public record DocumentDetail(
        String id,
        String kbId,
        String name,
        String fileType,
        long sizeBytes,
        String status,
        String currentStage,
        int chunkCount,
        String rootId,
        Integer versionNo,
        Boolean isActive,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        ChunkingConfig chunkConfig,
        String contentSha256,
        java.util.Map<String, Object> parseMetadata,
        PipelineStage failureStage,
        String failureReason,
        IngestionTask task) {
}
