package com.rag.api.dto;

import com.rag.domain.enums.PipelineStage;

import java.time.LocalDateTime;

/**
 * 入库任务 DTO（契约 IngestionTask）。failed 的 stage/failureReason 仅在
 * status=FAILED 时非空；startedAt/finishedAt 未发生时为 null。
 */
public record IngestionTask(
        String id,
        String documentId,
        String status,
        PipelineStage stage,
        int attempt,
        PipelineStage failureStage,
        String failureReason,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {
}
