package com.rag.domain.enums;

/**
 * 入库流水线真实阶段（contracts/openapi.yaml PipelineStage；V1__init.sql
 * document.current_stage / ingestion_task.stage / failure_stage）。
 *
 * <p>契约约束：SSE 与任务进度不得出现此枚举之外的“虚假进度”（QA-4）。</p>
 */
public enum PipelineStage {
    QUEUED,
    PARSING,
    CLEANING,
    CHUNKING,
    EMBEDDING,
    INDEXING,
    COMPLETED
}
