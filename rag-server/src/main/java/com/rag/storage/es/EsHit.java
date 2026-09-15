package com.rag.storage.es;

/**
 * kNN 检索命中（chunk 字段值 + 相关性分数）。
 *
 * <p>score 为 ES kNN 相似度得分（cosine 下同向量 ≈ 1.0，越大越相关）；
 * minScore 过滤与 rank 排序由上层（retrieval/eval）负责。</p>
 */
public record EsHit(String chunkId, String docId, String titlePath, Integer page,
                    int seq, int charCount, String content, double score) {
}
