package com.rag.storage.es;

/**
 * 待索引的分块（storage 层自有结构，不依赖 Task 3 的 ingestion.chunk 产物类型）。
 * Task 3 的 Chunker 产出映射为本记录后再交给 {@link EsChunkIndex#rebuildChunks}。
 *
 * @param id        chunkId，确定性 {@code {docId}-c%04d}（可重放，ES _id 同值）
 * @param titlePath 标题路径（展示用，keyword 不参与检索）
 * @param page      起始页码；PDF 有、MD/TXT 为 null
 * @param seq       块序号（从 0 起）
 * @param charCount 块字符数
 * @param content   块正文
 */
public record ChunkDoc(String id, String titlePath, Integer page, int seq, int charCount, String content) {
}
