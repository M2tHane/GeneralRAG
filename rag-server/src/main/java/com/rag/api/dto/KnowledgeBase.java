package com.rag.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库 DTO（contracts/openapi.yaml components.schemas.KnowledgeBase）。
 * 统计字段由 service 层聚合（documentCount/document 表、chunkCount/chunk_count 列之和）。
 */
public record KnowledgeBase(
        String id,
        String name,
        String description,
        long documentCount,
        long chunkCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
