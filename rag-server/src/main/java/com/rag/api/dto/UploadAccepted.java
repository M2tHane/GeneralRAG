package com.rag.api.dto;

/**
 * 上传受理结果（契约 UploadAccepted）：202，document 为已建文档元数据，
 * task 为已入队任务句柄（KB-3 上传不等待流水线）。
 */
public record UploadAccepted(Document document, IngestionTask task) {
}
