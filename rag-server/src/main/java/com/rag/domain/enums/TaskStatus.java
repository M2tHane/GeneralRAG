package com.rag.domain.enums;

/**
 * 入库任务状态（contracts/openapi.yaml IngestionTask.status；V1__init.sql ingestion_task.status）。
 * QUEUED→RUNNING 经 CAS 认领（IngestionTaskRepository#claimIfQueued）防双跑。
 */
public enum TaskStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED
}
