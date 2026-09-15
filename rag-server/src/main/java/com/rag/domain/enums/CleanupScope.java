package com.rag.domain.enums;

/**
 * 清理补偿任务范围（V1__init.sql cleanup_task.scope），
 * 决定 cleanup_task.ref_id 指向的对象与清理键（doc_id / kb_id）。
 */
public enum CleanupScope {
    DOCUMENT,
    KNOWLEDGE_BASE
}
