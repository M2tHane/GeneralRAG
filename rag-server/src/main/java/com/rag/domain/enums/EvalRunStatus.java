package com.rag.domain.enums;

/**
 * 评测运行状态（contracts/openapi.yaml EvalRunStatus；V1__init.sql eval_run.status）。
 */
public enum EvalRunStatus {
    RUNNING,
    COMPLETED,
    FAILED
}
