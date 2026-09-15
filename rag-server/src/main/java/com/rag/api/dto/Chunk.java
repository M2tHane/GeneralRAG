package com.rag.api.dto;

/**
 * 分块预览（契约 Chunk）。id 为确定性 chunkId（{docId}-c%04d）；
 * page 仅 PDF 可得，MD/TXT 为 null。
 */
public record Chunk(
        String id,
        String docId,
        int seq,
        String titlePath,
        Integer page,
        int charCount,
        String text) {
}
