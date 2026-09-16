package com.rag.storage.es;

/**
 * kNN 检索命中（chunk 字段值 + 相关性分数）。
 *
 * <p>score 为 ES kNN 相似度得分（cosine 下同向量 ≈ 1.0，越大越相关）；
 * minScore 过滤与 rank 排序由上层（retrieval/eval）负责。</p>
 *
 * <p>R5-A：{@code content} = answerContent（忠实证据）；{@code retrievalContent}
 * = 检索增强表示（旧文档为 null，此时各检索通道回退 content）。</p>
 */
public record EsHit(String chunkId, String docId, String titlePath, Integer page,
                    int seq, int charCount, String content, String retrievalContent,
                    double score) {

    /** 兼容旧调用点（无双层内容时代）的便捷构造。 */
    public EsHit(String chunkId, String docId, String titlePath, Integer page,
                 int seq, int charCount, String content, double score) {
        this(chunkId, docId, titlePath, page, seq, charCount, content, null, score);
    }
}
