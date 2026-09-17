package com.rag.retrieval;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 一次检索的分阶段候选轨迹（R5-B）。
 *
 * <p><b>来源唯一</b>：由 {@link RetrievalPipeline#execute} 在真实执行过程中顺带
 * 收集——<b>不存在第二套 retrieval pipeline</b>；Debug 接口与 Eval 共享同一结构，
 * 保证"评测看到的阶段位次"与"生产发生的阶段位次"一致。</p>
 *
 * <p><b>轻量</b>：每个阶段只记录 chunkId/rank/score（ranks 为该阶段 1 起的名次），
 * 不保存正文。VECTOR 模式无 BM25/RRF 通道（空列表）；rerank 降级时
 * {@code rerankedCandidates} 为空。</p>
 *
 * @param vectorCandidates   向量通道候选（kNN 召回，rank = 余弦序）
 * @param bm25Candidates     BM25 通道候选（rank = BM25 序）
 * @param unionCandidates    两通道并集（chunkId 去重后的候选总量）
 * @param fusedCandidates    RRF 融合后候选（rank = 融合序；RRF 阶段召回口径以此为准）
 * @param preRerankCandidates 进入重排阶段的候选（R5.2：在 deleted/inactive filter
 *                            <b>之后</b>记录，rank = 过滤后序）。rerank 升降级归因
 *                            以它为比较基线——被 filter 淘汰的 chunk 没有进入重排器，
 *                            不得记为 reranker degraded
 * @param rerankedCandidates 重排后候选（rank = 重排序；未重排/降级为空）
 * @param finalTopK          最终返回的 topK chunkId（有序）
 * @param timing             各阶段耗时（毫秒）
 * @param queryRewrite       R6-C：本次检索的查询改写信息（未启用 rewrite 时为 null）——
 *                           originalQuery=用户原始问题、retrievalQuery=实际检索查询、
 *                           rewritten=是否发生改写。不携带 history 内容。
 */
public record RetrievalTrace(
        List<StageCandidate> vectorCandidates,
        List<StageCandidate> bm25Candidates,
        List<StageCandidate> unionCandidates,
        List<StageCandidate> fusedCandidates,
        List<StageCandidate> preRerankCandidates,
        List<StageCandidate> rerankedCandidates,
        List<String> finalTopK,
        StageTiming timing,
        QueryRewriteInfo queryRewrite) {

    /**
     * R6-C：查询改写信息（轻量字段；与 ChatStreamService.AnswerQueryInfo 同语义，
     * 独立定义避免 retrieval 包反向依赖 llm 包）。
     *
     * @param originalQuery  用户原始问题
     * @param retrievalQuery 实际检索查询
     * @param rewritten      是否改写生效
     * @param latencyMs      rewrite 耗时（毫秒；未触发 rewrite 为 0）
     * @param fallback       rewrite 是否失败回退（超时/模型异常/响应非法）
     */
    public record QueryRewriteInfo(String originalQuery, String retrievalQuery, boolean rewritten,
                                   long latencyMs, boolean fallback) {
    }

    /** 兼容旧构造（无 rewrite 信息；测试直构用）。 */
    public RetrievalTrace(List<StageCandidate> vectorCandidates,
                          List<StageCandidate> bm25Candidates,
                          List<StageCandidate> unionCandidates,
                          List<StageCandidate> fusedCandidates,
                          List<StageCandidate> preRerankCandidates,
                          List<StageCandidate> rerankedCandidates,
                          List<String> finalTopK,
                          StageTiming timing) {
        this(vectorCandidates, bm25Candidates, unionCandidates, fusedCandidates,
                preRerankCandidates, rerankedCandidates, finalTopK, timing, null);
    }

    /**
     * 阶段候选：chunkId + 该阶段名次（1 起）+ 该阶段分数 + 证据锚点
     * （R5.1：titlePath 与 contentHash=answerContent 哈希，在 trace 收集点
     * 由 EsHit 透传/现算——<b>不携带正文</b>，供 StageMetrics 在证据掉出
     * final topK 时仍能用 EvidenceMatcher 锚点定位前序阶段名次）。
     */
    public record StageCandidate(String chunkId, int rank, double score,
                                 String titlePath, String contentHash) {

        /** 兼容旧构造（无锚点：单测直接构造用）。 */
        public StageCandidate(String chunkId, int rank, double score) {
            this(chunkId, rank, score, null, null);
        }
    }

    /**
     * 阶段耗时。{@code vectorMs}/{@code bm25Ms} 在当前实现中共享一次
     * {@code searchMs}（两通道并发于一次 ES 搜索窗口），拆分需要两次独立计时，
     * 现按实际可测边界记录：embedMs / searchMs（两通道合计）/ fusionMs /
     * rerankMs / totalMs。
     */
    public record StageTiming(long embedMs, long searchMs, long fusionMs,
                              long rerankMs, long totalMs) {
    }

    /** 空轨迹（构建失败前的占位）。 */
    public static RetrievalTrace empty(StageTiming timing) {
        return new RetrievalTrace(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), timing, null);
    }

    /** 指定 chunk 在某阶段的名次（未出现返回 null——"该阶段未召回"是有效信息）。 */
    public Integer rankOf(String chunkId, List<StageCandidate> stage) {
        for (StageCandidate c : stage) {
            if (c.chunkId().equals(chunkId)) {
                return c.rank();
            }
        }
        return null;
    }

    /** chunkId → 各阶段名次视图（BadCase 下钻用）。 */
    public Map<String, StageRanks> ranksByChunk() {
        return unionCandidates.stream().collect(Collectors.toMap(
                StageCandidate::chunkId,
                c -> new StageRanks(
                        rankOf(c.chunkId(), vectorCandidates),
                        rankOf(c.chunkId(), bm25Candidates),
                        rankOf(c.chunkId(), fusedCandidates),
                        rankOf(c.chunkId(), preRerankCandidates),
                        rankOf(c.chunkId(), rerankedCandidates),
                        finalTopK.indexOf(c.chunkId()) >= 0
                                ? finalTopK.indexOf(c.chunkId()) + 1 : null),
                (a, b) -> a));
    }

    /**
     * BadCase 下钻视图：同一 chunk 在各阶段的位次（null = 该阶段未召回/未重排）。
     *
     * @param vectorRank    向量名次；bm25Rank BM25 名次；rrfRank 融合名次；
     *                      preRerankRank 过滤后进入重排的名次（R5.2）；
     *                      rerankRank 重排名次；finalRank 最终 topK 名次（1 起）
     */
    public record StageRanks(Integer vectorRank, Integer bm25Rank, Integer rrfRank,
                             Integer preRerankRank, Integer rerankRank, Integer finalRank) {
    }
}
