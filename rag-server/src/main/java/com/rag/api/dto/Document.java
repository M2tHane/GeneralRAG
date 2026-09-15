package com.rag.api.dto;

import com.rag.domain.enums.DocumentStatus;
import com.rag.domain.enums.PipelineStage;

import java.time.LocalDateTime;

/**
 * 文档 DTO（契约 Document）。列表行与 UploadAccepted.document 共用。
 * rootId/versionNo/isActive 为多版本字段（R3-P3；单版本文档 rootId=id、versionNo=1、isActive=true）。
 */
public record Document(
        String id,
        String kbId,
        String name,
        String fileType,
        long sizeBytes,
        DocumentStatus status,
        PipelineStage currentStage,
        int chunkCount,
        String rootId,
        Integer versionNo,
        Boolean isActive,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
