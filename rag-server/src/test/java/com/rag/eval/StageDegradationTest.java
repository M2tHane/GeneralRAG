package com.rag.eval;

import com.rag.retrieval.RetrievalTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R5.1 stage degradation 回归：证据在 vector/bm25/rrf 全部可见、被 rerank
 * 淘汰出 final topK 时，StageMetrics 必须仍能通过 evidence-anchor 快照
 * （titlePath + contentHash，不携带正文）定位其前序阶段名次——
 * 这是"reranker degradation"类 BadCase 的核心诊断场景。
 *
 * <p>走 {@link StageMetrics#evaluateItem(List, RetrievalTrace)} 真实路径
 * （dataset evidence map → firstMatchRankInSnapshot），不绕过匹配逻辑。</p>
 */
class StageDegradationTest {

    private static final String EVIDENCE_CONTENT = "HikariCP 连接池 maximumPoolSize 默认为 10。";
    private static final String EVIDENCE_HASH = EvidenceMatcher.contentHashOf(EVIDENCE_CONTENT);
    private static final String EVIDENCE_TITLE_PATH = "数据库运维手册.md > MySQL > HikariCP > 参数配置";

    /** 证据数据集条目（只带 contentHash + titlePath 锚点，与真实 v2 数据集同形）。 */
    private static List<Map<String, Object>> evidence() {
        return List.of(Map.of(
                "titlePath", EVIDENCE_TITLE_PATH,
                "contentHash", EVIDENCE_HASH));
    }

    @Test
    void evidenceSurvivesRerankButDroppedFromFinalTopK() {
        // vector rank=5, bm25 rank=3, rrf rank=8, rerank rank=20（掉出 rerank@6），
        // final topK 不含证据 chunk（rerank 只保留 20 个候选）
        RetrievalTrace trace = buildTrace(true, true);

        StageMetrics.ItemStageResult r = StageMetrics.evaluateItem(evidence(), trace);

        assertThat(r.vectorRecallHit()).isEqualTo(1);   // Vector Recall@30 = HIT
        assertThat(r.bm25RecallHit()).isEqualTo(1);     // BM25 Recall@30 = HIT
        assertThat(r.rrfRecallHit()).isEqualTo(1);      // RRF Recall@30 = HIT
        assertThat(r.rerankRecallHit()).isZero();       // Rerank Recall@6 = MISS（rank 20 > 6）
        // promote/degrade：preRerank 8 → rerank 20 = degraded
        assertThat(r.rerankDegraded()).isEqualTo(1);
        assertThat(r.rerankPromoted()).isZero();
        assertThat(r.rerankStable()).isZero();

        // BadCase 下钻：finalRank = null（掉出 topK），前序阶段名次可见
        RetrievalTrace.StageRanks ranks = trace.ranksByChunk().get("ev-1");
        assertThat(ranks.vectorRank()).isEqualTo(5);
        assertThat(ranks.bm25Rank()).isEqualTo(3);
        assertThat(ranks.rrfRank()).isEqualTo(8);
        assertThat(ranks.preRerankRank()).isEqualTo(8);
        assertThat(ranks.rerankRank()).isEqualTo(20);
        assertThat(ranks.finalRank()).isNull();
    }

    @Test
    void evidenceFoundPurelyByContentHashWhenChunkIdUnknown() {
        // 数据集生成于旧 chunk 结构（chunkId 已变）：只剩 contentHash 锚点可用
        RetrievalTrace trace = buildTrace(true, true);
        List<Map<String, Object>> hashOnly = List.of(Map.of("contentHash", EVIDENCE_HASH));
        StageMetrics.ItemStageResult r = StageMetrics.evaluateItem(hashOnly, trace);
        assertThat(r.vectorRecallHit()).isEqualTo(1);
        assertThat(r.rerankRecallHit()).isZero();
    }

    @Test
    void degradedStillCountedWhenRerankerDropsCandidateEntirely() {
        // 更极端：重排器（真见过该 chunk）输出里彻底没有它（rerankRank = null）→ degraded
        RetrievalTrace trace = buildTrace(true, false);
        StageMetrics.ItemStageResult r = StageMetrics.evaluateItem(evidence(), trace);
        assertThat(r.vectorRecallHit()).isEqualTo(1);
        assertThat(r.rerankRecallHit()).isZero();
        assertThat(r.rerankDegraded()).isEqualTo(1); // preRerank 可见 → 重排后消失 = degraded
        RetrievalTrace.StageRanks ranks = trace.ranksByChunk().get("ev-1");
        assertThat(ranks.preRerankRank()).isEqualTo(8);
        assertThat(ranks.rerankRank()).isNull();
    }

    @Test
    void filterRemovedCandidateIsNotRerankerDegraded() {
        // R5.2 回归：证据在 RRF 中存在（rrf=8），但被 deleted/inactive filter 淘汰
        // （preRerank 无名次）→ 未进入重排器，不计为 reranker degraded。
        // RRF 阶段召回基于真正的 RRF 候选：rrfRecallHit = 1 不受影响。
        RetrievalTrace trace = buildTrace(false, false);
        StageMetrics.ItemStageResult r = StageMetrics.evaluateItem(evidence(), trace);
        assertThat(r.rrfRecallHit()).isEqualTo(1);
        assertThat(r.rerankRecallHit()).isZero();
        assertThat(r.rerankDegraded()).isZero();   // 未进重排器 → NOT reranker degraded
        assertThat(r.rerankPromoted()).isZero();
        assertThat(r.rerankStable()).isZero();
        RetrievalTrace.StageRanks ranks = trace.ranksByChunk().get("ev-1");
        assertThat(ranks.rrfRank()).isEqualTo(8);
        assertThat(ranks.preRerankRank()).isNull(); // filter 淘汰的直接证据
        assertThat(ranks.rerankRank()).isNull();
    }

    /**
     * @param passedFilter  证据是否通过 deleted/inactive filter（false = preRerank 淘汰）
     * @param includedInRerank 重排器输出是否包含证据（false = 彻底掉出）
     */
    private static List<RetrievalTrace.StageCandidate> filler(int startRank, int count, double score) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new RetrievalTrace.StageCandidate(
                        "filler-" + startRank + "-" + i, startRank + i, score,
                        "filler.md > 行 " + (startRank + i),
                        EvidenceMatcher.contentHashOf("无关填充 " + startRank + "-" + i)))
                .toList();
    }

    private static RetrievalTrace buildTrace(boolean passedFilter, boolean includedInRerank) {
        // vector rank=5（前 4 个 filler）
        List<RetrievalTrace.StageCandidate> vector = new java.util.ArrayList<>(filler(1, 4, 0.9));
        vector.add(new RetrievalTrace.StageCandidate("ev-1", 5, 0.85,
                EVIDENCE_TITLE_PATH, EVIDENCE_HASH));
        // bm25 rank=3
        List<RetrievalTrace.StageCandidate> bm25 = new java.util.ArrayList<>(filler(1, 2, 12.0));
        bm25.add(new RetrievalTrace.StageCandidate("ev-1", 3, 8.5,
                EVIDENCE_TITLE_PATH, EVIDENCE_HASH));
        // rrf rank=8
        List<RetrievalTrace.StageCandidate> fused = new java.util.ArrayList<>(filler(1, 7, 0.02));
        fused.add(new RetrievalTrace.StageCandidate("ev-1", 8, 0.016,
                EVIDENCE_TITLE_PATH, EVIDENCE_HASH));
        // preRerank：通过 filter 则位次不变（8），否则淘汰
        List<RetrievalTrace.StageCandidate> preRerank = new java.util.ArrayList<>();
        if (passedFilter) {
            preRerank.addAll(fused);
        } else {
            preRerank.addAll(filler(1, 7, 0.02));
        }
        // rerank rank=20（19 个 filler 挤在前面）或彻底掉出
        List<RetrievalTrace.StageCandidate> reranked = new java.util.ArrayList<>(filler(1, 19, 0.95));
        if (includedInRerank) {
            reranked.add(new RetrievalTrace.StageCandidate("ev-1", 20, 0.55,
                    EVIDENCE_TITLE_PATH, EVIDENCE_HASH));
        }
        // final topK=6 全是 filler：证据掉出（rerank 后 topK 内无证据）
        List<String> finalTopK = reranked.stream().limit(6)
                .map(RetrievalTrace.StageCandidate::chunkId).toList();
        return new RetrievalTrace(vector, bm25, List.of(
                        new RetrievalTrace.StageCandidate("ev-1", 1, 0),
                        new RetrievalTrace.StageCandidate("filler-union", 2, 0)),
                fused, preRerank, reranked, finalTopK,
                new RetrievalTrace.StageTiming(10, 30, 1, 40, 81));
    }
}
