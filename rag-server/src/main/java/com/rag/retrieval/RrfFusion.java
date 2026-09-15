package com.rag.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.rag.retrieval.model.RetrievalStages;
import com.rag.storage.es.EsHit;
import org.springframework.stereotype.Component;

/**
 * 应用侧 RRF（Reciprocal Rank Fusion）融合（R2-H2）。
 *
 * <p><b>为什么应用侧融合</b>：本项目的 ES 集群 license=basic，ES 原生
 * {@code retriever.rrf} 返回 403（RRF 属付费特性），故融合在应用层实现。
 * 附带好处是可单测、不依赖 ES 版本特性。</p>
 *
 * <p>公式：{@code score(d) = Σ_channels 1 / (k + rank_channel(d))}，rank 从 1 起，
 * 只对"出现在该通道候选内"的通道累加。{@code k} 默认 60（ES 惯例），可配置。
 * 结果按分数降序；分数相同时按首个出现通道的位次、再按 chunkId 稳定排序
 * （避免并列时顺序抖动导致两次运行不可比）。</p>
 */
@Component
public class RrfFusion {

    /** 融合一个候选在各通道的排名信息（rank 从 1 起；未出现在该通道则不含该键）。 */
    public record ChannelRanks(int vectorRank, int bm25Rank) {
    }

    /**
     * 融合向量与 BM25 两路候选。
     *
     * @param k            RRF 常数（≥1）
     * @param vectorHits   向量通道候选（按相似度降序）
     * @param bm25Hits     BM25 通道候选（按 BM25 分降序）
     * @return 融合结果（按融合分降序，含各通道位次与融合分）
     */
    public List<FusedHit> fuse(int k, List<EsHit> vectorHits, List<EsHit> bm25Hits) {
        int safeK = Math.max(1, k);
        Map<String, Integer> vectorRank = new HashMap<>();
        Map<String, Integer> bm25Rank = new HashMap<>();
        Map<String, EsHit> byId = new HashMap<>();
        // LinkedHashSet 保留"向量优先、再 BM25"的稳定出现顺序，用于并列时的确定性排序
        Set<String> order = new LinkedHashSet<>();
        collect(vectorHits, vectorRank, byId, order);
        collect(bm25Hits, bm25Rank, byId, order);

        List<FusedHit> fused = new ArrayList<>(order.size());
        for (String chunkId : order) {
            Integer vr = vectorRank.get(chunkId);
            Integer br = bm25Rank.get(chunkId);
            double score = 0.0;
            if (vr != null) {
                score += 1.0 / (safeK + vr);
            }
            if (br != null) {
                score += 1.0 / (safeK + br);
            }
            fused.add(new FusedHit(byId.get(chunkId), vr, br, score));
        }
        // 确定性排序：融合分降序 → 首现通道位次 → chunkId
        Map<String, Integer> appearance = new HashMap<>();
        int idx = 0;
        for (String chunkId : order) {
            appearance.put(chunkId, idx++);
        }
        fused.sort(Comparator
                .comparingDouble(FusedHit::fusedScore).reversed()
                .thenComparingInt(h -> appearance.get(h.chunk().chunkId()))
                .thenComparing(h -> h.chunk().chunkId()));
        return fused;
    }

    private static void collect(List<EsHit> hits, Map<String, Integer> rankMap,
                                Map<String, EsHit> byId, Set<String> order) {
        if (hits == null) {
            return;
        }
        int rank = 1;
        for (EsHit hit : hits) {
            if (hit == null || hit.chunkId() == null) {
                continue;
            }
            // 同一分块在单通道内只记首个位次（ES 不应重复返回，防御性处理）
            if (rankMap.putIfAbsent(hit.chunkId(), rank) == null) {
                order.add(hit.chunkId());
            }
            byId.putIfAbsent(hit.chunkId(), hit);
            rank++;
        }
    }

    /** 融合结果：分块 + 各通道位次 + 融合分。 */
    public record FusedHit(EsHit chunk, Integer vectorRank, Integer bm25Rank, double fusedScore) {

        /** 融合后阶段证据（重排阶段由调用方补齐）。 */
        public RetrievalStages toStages(Double vectorScore, Double bm25Score, int fusedRank) {
            return new RetrievalStages(vectorRank, vectorScore, bm25Rank, bm25Score,
                    fusedRank, fusedScore, null, null, false);
        }
    }

    /**
     * 融合分归一化到 (0,1]，供 minScore 阈值与拒答判定使用。
     *
     * <p>原始 RRF 分约为 {@code 1/(k+rank)} 量级（k=60 时 ≈0.016），与 minScore 的
     * [0,1] 语义完全不在同一量纲——若直接拿原始分比阈值，阈值会形同虚设。归一化基准
     * 取"两个通道都排第 1"的最大可能分 {@code 2/(k+1)}，故两通道均 rank1 的候选得 1.0，
     * 单通道 rank1 的候选得 0.5。</p>
     *
     * <p><b>口径说明</b>：这是"融合相对分"，不是余弦相似度，与 VECTOR 模式的
     * cosine 分不可直接比较（各模式阈值标定不同，属已知限制，报告如实记录）。</p>
     */
    public static double normalize(double fusedScore, int k) {
        double maxPossible = 2.0 / (Math.max(1, k) + 1);
        if (maxPossible <= 0) {
            return 0.0;
        }
        return Math.min(1.0, fusedScore / maxPossible);
    }

    /** 便于调试与测试：把融合结果映射为 chunkId → 融合分（按融合序）。 */
    public Map<String, Double> scoreMap(List<FusedHit> fused) {
        Map<String, Double> map = new TreeMap<>();
        for (FusedHit hit : fused) {
            map.put(hit.chunk().chunkId(), hit.fusedScore());
        }
        return map;
    }
}
