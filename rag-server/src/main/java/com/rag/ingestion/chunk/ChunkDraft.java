package com.rag.ingestion.chunk;

/**
 * 分块产物（Chunker 输出；INDEXING 阶段映射为 storage 层 ChunkDoc 后写入 ES）。
 *
 * @param chunkId   确定性 id：{@code {docId}-c%04d}，从 0 起；重试重建不变（可重放）
 * @param seq       块序号，从 0 起
 * @param titlePath 标题路径（如「部署指南.md > 1. 概述 > 1.1 背景」）；无结构时为文档名
 * @param page      分块起始页；PDF 有、MD/TXT 为 null
 * @param charCount 块正文字符数
 * @param text      块正文
 */
public record ChunkDraft(String chunkId, int seq, String titlePath, Integer page, int charCount, String text) {
}
