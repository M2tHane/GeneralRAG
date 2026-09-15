package com.rag.domain.enums;

/**
 * 文档状态（contracts/openapi.yaml DocumentStatus；V1__init.sql document.status）。
 */
public enum DocumentStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED
}
