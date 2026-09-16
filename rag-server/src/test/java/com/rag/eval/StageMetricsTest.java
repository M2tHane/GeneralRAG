package com.rag.eval;

import com.rag.retrieval.RetrievalTrace;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StageMetrics 单测（R5-B）：各阶段召回口径与 promote/degrade/stable 判定。
 * 直接构造 RetrievalTrace（trace 由真实 pipeline 产出，本测试只验证计算规则）。
 */
class StageMetricsTest {

    private static RetrievalTrace.StageCandidate c(String id, int rank, double score) {
        return new RetrievalTrace.StageCandidate(id, rank, score);
    }

    /** 常规轨迹：vector [e1,e2,x]，bm25 [x,e1]，fused [e1,x,e2]，preRerank/rerank [x,e1,e2]。 */
    private static RetrievalTrace trace() {
        return new RetrievalTrace(
                List.of(c("e1", 1, 0.9), c("e2", 2, 0.8), c("x1", 3, 0.7)),
                List.of(c("x1", 1, 12.0), c("e1", 2, 8.0)),
                List.of(c("e1", 1, 0), c("e2", 2, 0), c("x1", 3, 0)),
                List.of(c("e1", 1, 0.03), c("x1", 2, 0.02), c("e2", 3, 0.016)),
                List.of(c("e1", 1, 0.03), c("x1", 2, 0.02), c("e2", 3, 0.016)),
                List.of(c("x1", 1, 0.95), c("e1", 2, 0.9), c("e2", 3, 0.8)),
                List.of("x1", "e1", "e2"),
                new RetrievalTrace.StageTiming(10, 20, 1, 30, 61));
    }

    @Test
    void allStageRecallsHitWhenEvidenceVisible() {
        StageMetrics.ItemStageResult r = StageMetrics.evaluateByIds(
                Set.of("e1", "e2"), 2, trace());
        assertThat(r.vectorRecallHit()).isEqualTo(1); // e1,e2 都在 vector 前 30
        assertThat(r.bm25RecallHit()).isEqualTo(1);   // e1 在 bm25（e2 不在，但 ≥1 即命中）
        assertThat(r.unionHit()).isEqualTo(1);
        assertThat(r.rrfRecallHit()).isEqualTo(1);
        assertThat(r.rerankRecallHit()).isEqualTo(1); // e1,e2 都在 rerank 前 6
    }

    @Test
    void recallMissesWhenEvidenceAbsent() {
        StageMetrics.ItemStageResult r = StageMetrics.evaluateByIds(
                Set.of("e9"), 1, trace());
        assertThat(r.vectorRecallHit()).isZero();
        assertThat(r.bm25RecallHit()).isZero();
        assertThat(r.unionHit()).isZero();
        assertThat(r.rrfRecallHit()).isZero();
        assertThat(r.rerankRecallHit()).isZero();
    }

    @Test
    void promoteDegradeStableClassification() {
        // e1: rrf 1 → rerank 2 = degraded；e2: rrf 3 → rerank 3 = stable
        StageMetrics.ItemStageResult r = StageMetrics.evaluateByIds(
                Set.of("e1", "e2"), 2, trace());
        assertThat(r.rerankPromoted()).isZero();
        assertThat(r.rerankDegraded()).isEqualTo(1);
        assertThat(r.rerankStable()).isEqualTo(1);
    }

    @Test
    void promotedWhenRerankImprovesRank() {
        // e1: preRerank 2 → rerank 1 = promoted；e2: preRerank 1 → 掉出 rerank = degraded
        RetrievalTrace t = new RetrievalTrace(
                List.of(c("e1", 1, 0.9)),
                List.of(),
                List.of(c("e1", 1, 0), c("e2", 2, 0)),
                List.of(c("e2", 1, 0.02), c("e1", 2, 0.016)),
                List.of(c("e2", 1, 0.02), c("e1", 2, 0.016)),
                List.of(c("e1", 1, 0.95)),
                List.of("e1"),
                new RetrievalTrace.StageTiming(1, 1, 1, 1, 4));
        StageMetrics.ItemStageResult r = StageMetrics.evaluateByIds(
                Set.of("e1", "e2"), 2, t);
        assertThat(r.rerankPromoted()).isEqualTo(1);
        assertThat(r.rerankDegraded()).isEqualTo(1);
        assertThat(r.rerankStable()).isZero();
    }

    @Test
    void noRerankMeansNullRerankMetrics() {
        // VECTOR 模式：无 bm25/fused/rerank 候选
        RetrievalTrace t = new RetrievalTrace(
                List.of(c("e1", 1, 0.9)),
                List.of(), List.of(c("e1", 1, 0)),
                List.of(), List.of(), List.of(), List.of("e1"),
                new RetrievalTrace.StageTiming(1, 2, 0, 0, 3));
        StageMetrics.ItemStageResult r = StageMetrics.evaluateByIds(
                Set.of("e1"), 1, t);
        assertThat(r.vectorRecallHit()).isEqualTo(1);
        assertThat(r.bm25RecallHit()).isZero();
        assertThat(r.rrfRecallHit()).isZero();
        assertThat(r.rerankRecallHit()).isNull();
        assertThat(r.rerankPromoted()).isNull();
    }

    @Test
    void filterRemovedCandidateIsNotRerankerDegraded() {
        // R5.2 回归：证据在 RRF 中存在（rrf=2），但被 deleted/inactive filter 淘汰
        // （preRerank 无名次）→ 没有进入重排器，不得记为 reranker degraded。
        // RRF 阶段召回仍基于真正 RRF 候选：rrfRecallHit = 1 不受影响。
        RetrievalTrace t = new RetrievalTrace(
                List.of(c("e1", 1, 0.9), c("e2", 2, 0.8)),
                List.of(c("e2", 1, 12.0), c("e1", 2, 8.0)),
                List.of(c("e1", 1, 0), c("e2", 2, 0)),
                List.of(c("e1", 1, 0.03), c("e2", 2, 0.02)),   // RRF 融合序：两者都在
                List.of(c("e1", 1, 0.03)),                      // filter 后：e2 被淘汰
                List.of(c("e1", 1, 0.95)),                      // 重排只见过 e1
                List.of("e1"),
                new RetrievalTrace.StageTiming(1, 2, 1, 3, 7));
        StageMetrics.ItemStageResult r = StageMetrics.evaluateByIds(
                Set.of("e1", "e2"), 2, t);
        assertThat(r.rrfRecallHit()).isEqualTo(1);        // RRF 阶段召回：e2 在 RRF 前 30 → HIT
        assertThat(r.rerankRecallHit()).isEqualTo(1);     // e1 重排 rank1 → HIT
        assertThat(r.rerankDegraded()).isZero();          // e2 未进重排器 → NOT degraded
        assertThat(r.rerankPromoted()).isZero();
        assertThat(r.rerankStable()).isEqualTo(1);        // e1: preRerank 1 → rerank 1
    }

    @Test
    void aggregateSeparatesNumeratorsAndDenominators() {
        StageMetrics.Aggregate agg = new StageMetrics.Aggregate();
        agg.add(StageMetrics.evaluateByIds(Set.of("e1", "e2"), 2, trace())); // 全命中
        agg.add(StageMetrics.evaluateByIds(Set.of("e9"), 1, trace()));       // 全未命中
        Map<String, Object> m = agg.toMap();
        assertThat(m.get("vectorRecall30")).isEqualTo(0.5);   // 1/2 题
        assertThat(m.get("unionRecall")).isEqualTo(0.5);
        assertThat(m.get("vectorCount")).isEqualTo(2);
        assertThat(m.get("rerankPromoted")).isEqualTo(0);     // 两题均无 promoted
        // 第二题（e9）：promoted=0 degraded=0 stable=0 —— 不污染升降级统计
        assertThat(m.get("rerankStable")).isEqualTo(1);
        assertThat(m.get("rerankDegraded")).isEqualTo(1);
    }

    @Test
    void rankOfNullMeansStageMissed() {
        RetrievalTrace t = trace();
        assertThat(t.rankOf("e2", t.bm25Candidates())).isNull();
        assertThat(t.rankOf("e1", t.bm25Candidates())).isEqualTo(2);
        Map<String, RetrievalTrace.StageRanks> ranks = t.ranksByChunk();
        assertThat(ranks.get("e2").bm25Rank()).isNull();  // BM25 未召回 e2
        assertThat(ranks.get("e1").finalRank()).isEqualTo(2);
    }
}
