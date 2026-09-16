package com.rag.eval;

import com.rag.retrieval.RetrievalTrace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage-level retrieval 评测计算（R5-B）：基于真实 pipeline 产出的
 * {@link RetrievalTrace} 与既有 {@link EvidenceMatcher}，计算各阶段召回与
 * 重排升降级统计。<b>不复制 retrieval pipeline</b>——所有候选与位次都来自
 * 生产执行路径顺带收集的 trace；证据锚点判定复用 {@code EvidenceMatcher}。
 *
 * <p>口径：</p>
 * <ul>
 *   <li>Vector/BM25/RRF Recall@30：≥1 个证据 chunk 出现在该阶段前 30 名的题数比例；</li>
 *   <li>Union Recall：≥1 个证据 chunk 被任一通道召回（无 K 截断）；</li>
 *   <li>Rerank Recall@6：重排后前 6（= 默认 topK）；未重排（VECTOR/降级）→ null；</li>
 *   <li>promoted/degraded/stable：对融合序与重排序均有名次的证据 chunk 比较——
 *       变小 = promoted，变大或掉出候选 = degraded，不变 = stable。
 *       分母 = 融合序可见的证据 chunk 数（如实标注，不掺 N/A）。</li>
 * </ul>
 */
public final class StageMetrics {

    private StageMetrics() {
    }

    private static final int STAGE_K = 30;
    private static final int RERANK_K = 6;

    /** 聚合累加器（分子/分母分离，避免小数累加失真）。 */
    public static final class Aggregate {
        private int vectorHit, vectorCount;
        private int bm25Hit, bm25Count;
        private int unionHit, unionCount;
        private int rrfHit, rrfCount;
        private int rerankHit, rerankCount;
        private int promoted, degraded, stable;

        /** 累加单题结果（null 字段 = 本题 N/A，不计入分母）。 */
        public void add(StageMetrics.ItemStageResult r) {
            if (r == null || r.evidenceCount() == 0) {
                return;
            }
            if (r.vectorRecallHit() != null) {
                vectorHit += r.vectorRecallHit();
                vectorCount++;
            }
            if (r.bm25RecallHit() != null) {
                bm25Hit += r.bm25RecallHit();
                bm25Count++;
            }
            if (r.unionHit() != null) {
                unionHit += r.unionHit();
                unionCount++;
            }
            if (r.rrfRecallHit() != null) {
                rrfHit += r.rrfRecallHit();
                rrfCount++;
            }
            if (r.rerankRecallHit() != null) {
                rerankHit += r.rerankRecallHit();
                rerankCount++;
                promoted += r.rerankPromoted();
                degraded += r.rerankDegraded();
                stable += r.rerankStable();
            }
        }

        /** 转 metrics map（分母 0 → null）。 */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("vectorRecall30", ratio(vectorHit, vectorCount));
            m.put("bm25Recall30", ratio(bm25Hit, bm25Count));
            m.put("unionRecall", ratio(unionHit, unionCount));
            m.put("rrfRecall30", ratio(rrfHit, rrfCount));
            m.put("rerankRecall6", ratio(rerankHit, rerankCount));
            m.put("vectorHit", vectorHit);
            m.put("vectorCount", vectorCount);
            m.put("bm25Hit", bm25Hit);
            m.put("bm25Count", bm25Count);
            m.put("unionHit", unionHit);
            m.put("unionCount", unionCount);
            m.put("rrfHit", rrfHit);
            m.put("rrfCount", rrfCount);
            m.put("rerankHit", rerankHit);
            m.put("rerankCount", rerankCount);
            m.put("rerankPromoted", promoted);
            m.put("rerankDegraded", degraded);
            m.put("rerankStable", stable);
            return m;
        }

        private static Double ratio(int hit, int count) {
            return count == 0 ? null : Math.round((double) hit / count * 10000.0) / 10000.0;
        }
    }

    /** 单题判定结果（null = 该口径本题 N/A）。 */
    public record ItemStageResult(Integer vectorRecallHit, Integer bm25RecallHit,
                                  Integer unionHit, Integer rrfRecallHit, Integer rerankRecallHit,
                                  Integer rerankPromoted, Integer rerankDegraded, Integer rerankStable,
                                  int evidenceCount) {
    }

    /**
     * 单题判定。
     *
     * @param evidence 数据集证据（docName/titlePath/chunkId/contentHash 锚点）
     * @param trace    真实执行收集的阶段轨迹
     * @param hitsById 聚合各阶段出现过的 chunkId → EsHit（供 EvidenceMatcher 锚点匹配）
     */
    public static ItemStageResult evaluateItem(List<Map<String, Object>> evidence,
                                               RetrievalTrace trace,
                                               Map<String, com.rag.storage.es.EsHit> hitsById) {
        int evidenceCount = evidence == null ? 0 : evidence.size();
        if (evidenceCount == 0 || trace == null || evidenceChunkCandidates(trace, hitsById).isEmpty()) {
            return new ItemStageResult(null, null, null, null, null, null, null, null, evidenceCount);
        }
        // 证据 → chunkId：把每个候选阶段的 chunkId 序转成 EsHit 序，复用 firstMatchRank
        Set<String> evidenceChunkIds = new java.util.HashSet<>();
        for (Map<String, Object> ev : evidence) {
            // 各阶段独立匹配：同一证据可能在 vector 有 hash 命中而 BM25 序里也出现
            for (List<RetrievalTrace.StageCandidate> stage : List.of(
                    trace.vectorCandidates(), trace.bm25Candidates(),
                    trace.fusedCandidates(), trace.rerankedCandidates())) {
                List<com.rag.storage.es.EsHit> ordered = new ArrayList<>();
                for (RetrievalTrace.StageCandidate c : stage) {
                    com.rag.storage.es.EsHit hit = hitsById.get(c.chunkId());
                    if (hit != null) {
                        ordered.add(hit);
                    }
                }
                if (EvidenceMatcher.firstMatchRank(ev, ordered) > 0) {
                    evidenceChunkIds.add(firstMatchedChunkId(ev, ordered));
                }
            }
        }
        return evaluateByIds(evidenceChunkIds, evidenceCount, trace);
    }

    /** 证据 chunkId 已知时的纯 trace 计算（单测直接构造 trace 使用）。 */
    public static ItemStageResult evaluateByIds(Set<String> evidenceChunkIds,
                                                int evidenceCount, RetrievalTrace trace) {
        if (evidenceCount == 0 || trace == null || evidenceChunkIds.isEmpty()) {
            return new ItemStageResult(null, null, null, null, null, null, null, null, evidenceCount);
        }
        int vectorHit = countHits(evidenceChunkIds, trace.vectorCandidates(), STAGE_K);
        int bm25Hit = countHits(evidenceChunkIds, trace.bm25Candidates(), STAGE_K);
        int unionHit = (int) trace.unionCandidates().stream()
                .filter(c -> evidenceChunkIds.contains(c.chunkId())).count();
        int rrfHit = countHits(evidenceChunkIds, trace.fusedCandidates(), STAGE_K);
        int rerankHit = countHits(evidenceChunkIds, trace.rerankedCandidates(), RERANK_K);
        boolean reranked = !trace.rerankedCandidates().isEmpty();
        int promoted = 0, degraded = 0, stable = 0;
        if (reranked) {
            Map<String, RetrievalTrace.StageRanks> ranks = trace.ranksByChunk();
            for (String id : evidenceChunkIds) {
                RetrievalTrace.StageRanks r = ranks.get(id);
                if (r == null || r.rrfRank() == null) {
                    continue; // 融合序不可见的证据不参与 rerank 前后比较
                }
                Integer before = r.rrfRank();
                Integer after = r.rerankRank();
                if (after == null) {
                    degraded++; // 重排后掉出候选
                } else if (after < before) {
                    promoted++;
                } else if (after > before) {
                    degraded++;
                } else {
                    stable++;
                }
            }
        }
        return new ItemStageResult(
                vectorHit > 0 ? 1 : 0, bm25Hit > 0 ? 1 : 0,
                unionHit > 0 ? 1 : 0, rrfHit > 0 ? 1 : 0,
                reranked ? (rerankHit > 0 ? 1 : 0) : null,
                reranked ? promoted : null, reranked ? degraded : null,
                reranked ? stable : null, evidenceCount);
    }

    private static String firstMatchedChunkId(Map<String, Object> evidence,
                                              List<com.rag.storage.es.EsHit> ordered) {
        for (com.rag.storage.es.EsHit hit : ordered) {
            if (EvidenceMatcher.firstMatchRank(evidence, List.of(hit)) > 0) {
                return hit.chunkId();
            }
        }
        return null;
    }

    private static int countHits(Set<String> evidenceIds,
                                 List<RetrievalTrace.StageCandidate> stage, int k) {
        return (int) stage.stream().limit(k)
                .filter(c -> evidenceIds.contains(c.chunkId())).count();
    }

    private static Set<String> evidenceChunkCandidates(RetrievalTrace trace,
                                                       Map<String, com.rag.storage.es.EsHit> hitsById) {
        Set<String> ids = new java.util.HashSet<>();
        for (RetrievalTrace.StageCandidate c : trace.unionCandidates()) {
            if (hitsById.containsKey(c.chunkId())) {
                ids.add(c.chunkId());
            }
        }
        return ids;
    }
}
