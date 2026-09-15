package com.rag.retrieval.model;

import com.rag.storage.es.EsHit;

/**
 * 检索命中（内部模型，调试接口与评测复用）。
 *
 * @param rank            名次（1 起，按最终排序分降序）
 * @param chunk           命中分块（含 chunkId/docId/titlePath/page/seq/content）
 * @param score           最终排序分（决定 rank 与 passedThreshold 的分数）
 * @param passedThreshold 是否通过 minScore 阈值（进入上下文/引用；仅作标记，不过滤）
 * @param stages          分阶段证据（向量/BM25/融合/重排位次与分数；R2-D1）
 */
public record RetrievalHit(int rank, EsHit chunk, double score, boolean passedThreshold,
                           RetrievalStages stages) {

    /** 无分阶段证据的命中（单通道旧路径 / 测试构造）。 */
    public RetrievalHit(int rank, EsHit chunk, double score, boolean passedThreshold) {
        this(rank, chunk, score, passedThreshold, RetrievalStages.empty());
    }

    /** 替换名次与阈值标记，保留分块与阶段证据。 */
    public RetrievalHit withRankAndThreshold(int newRank, boolean newPassed) {
        return new RetrievalHit(newRank, chunk, score, newPassed, stages);
    }
}
