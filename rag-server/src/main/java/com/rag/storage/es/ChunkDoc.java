package com.rag.storage.es;

/**
 * 待索引的分块（storage 层自有结构，不依赖 Task 3 的 ingestion.chunk 产物类型）。
 * Task 3 的 Chunker 产出映射为本记录后再交给 {@link EsChunkIndex#rebuildChunks}。
 *
 * <p><b>R5-A 双层内容</b>：{@code content} = answerContent（忠实证据，供
 * Judge/Generation/Citation/EvidenceMatcher）；{@code retrievalContent} =
 * 检索增强表示（元数据前缀，供 Embedding/BM25/Reranker），只帮助"找到证据"，
 * 绝不能成为回答证据。</p>
 *
 * @param id               chunkId，确定性 {@code {docId}-c%04d}（可重放，ES _id 同值）
 * @param titlePath        标题路径（展示用，keyword 不参与检索）
 * @param page             起始页码；PDF 有、MD/TXT 为 null
 * @param seq              块序号（从 0 起）
 * @param charCount        块字符数（answerContent 长度）
 * @param content          块正文（answerContent）
 * @param retrievalContent 检索增强表示（answerContent + 确定性元数据前缀；可为 null = 旧数据）
 */
public record ChunkDoc(String id, String titlePath, Integer page, int seq, int charCount,
                       String content, String retrievalContent) {

    /** 兼容旧调用点的便捷构造（retrievalContent = null，读取时回退 content）。 */
    public ChunkDoc(String id, String titlePath, Integer page, int seq, int charCount, String content) {
        this(id, titlePath, page, seq, charCount, content, null);
    }
}
