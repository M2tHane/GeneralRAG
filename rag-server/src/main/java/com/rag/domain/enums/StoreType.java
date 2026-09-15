package com.rag.domain.enums;

/**
 * 清理补偿任务的目标存储（V1__init.sql cleanup_task.store）。
 * 同一次删除会为每个受影响对象各写两条任务（每存储各一）。
 */
public enum StoreType {
    ELASTICSEARCH,
    MINIO
}
