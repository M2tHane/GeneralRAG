package com.rag.domain.enums;

/**
 * 评测数据集类型（contracts/openapi.yaml DatasetType）。
 * EV-6：调优集与独立测试集严格分离。
 */
public enum DatasetType {
    TUNING,
    TEST
}
