package com.rag.ingestion.chunk;

/**
 * 分块产物（Chunker 输出；INDEXING 阶段映射为 storage 层 ChunkDoc 后写入 ES）。
 *
 * <p><b>R5-A 双层内容</b>：{@code text} = answerContent（忠实证据）；
 * {@code retrievalContent} = 检索增强表示（确定性元数据前缀，见
 * {@link RetrievalContentEnricher}），只供检索通道。</p>
 *
 * @param chunkId          确定性 id：{@code {docId}-c%04d}，从 0 起；重试重建不变（可重放）
 * @param seq              块序号，从 0 起
 * @param titlePath        标题路径（如「部署指南.md > 1. 概述 > 1.1 背景」）；无结构时为文档名
 * @param page             分块起始页；PDF 有、MD/TXT 为 null
 * @param charCount        块正文字符数（answerContent）
 * @param text             块正文（answerContent）
 * @param retrievalContent 检索增强表示（null = 未启用双层内容）
 */
public record ChunkDraft(String chunkId, int seq, String titlePath, Integer page, int charCount,
                         String text, String retrievalContent) {

    /** 兼容旧构造（未启用双层内容）。 */
    public ChunkDraft(String chunkId, int seq, String titlePath, Integer page, int charCount, String text) {
        this(chunkId, seq, titlePath, page, charCount, text, null);
    }
}
