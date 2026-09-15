package com.rag.retrieval.model;

/**
 * 一次检索中各通道的分阶段证据（R2-D1/R2-H3）。
 *
 * <p>第一轮 {@link RetrievalHit} 只有一个 score，无法表达"这一条在向量通道排第几、
 * 在 BM25 通道排第几、融合后第几、重排后第几"，因此调试页与评测对比都说不清
 * "排序为什么变了"。本记录承载这份证据。</p>
 *
 * <p>各字段可空：某通道未参与（如 VECTOR 模式没有 BM25）或该命中未出现在该通道
 * 候选内时为 null——这本身就是有效信息（"BM25 没召回到这条"）。</p>
 *
 * @param vectorRank 向量通道名次（1 起）
 * @param vectorScore 向量余弦相似度
 * @param bm25Rank BM25 通道名次（1 起）
 * @param bm25Score BM25 原始相关性分（无上界，不可跨查询比较）
 * @param fusedRank RRF 融合后名次（1 起）
 * @param fusedScore RRF 融合原始分（Σ 1/(k+rank)，量纲很小，不是 0..1 相关度）
 * @param rerankRank 重排名次（1 起）
 * @param rerankScore 重排相关度（0..1，但按厂商语义为请求内相对值，不可跨请求比较）
 * @param rerankDegraded 本次是否因重排失败而降级为融合顺序
 */
public record RetrievalStages(
        Integer vectorRank,
        Double vectorScore,
        Integer bm25Rank,
        Double bm25Score,
        Integer fusedRank,
        Double fusedScore,
        Integer rerankRank,
        Double rerankScore,
        boolean rerankDegraded) {

    /** 空证据（单通道且未记录时使用）。 */
    public static RetrievalStages empty() {
        return new RetrievalStages(null, null, null, null, null, null, null, null, false);
    }

    /** 是否记录了任何分阶段证据（调试页据此决定是否展示位次区块）。 */
    public boolean hasAny() {
        return vectorRank != null || bm25Rank != null || fusedRank != null || rerankRank != null;
    }
}
