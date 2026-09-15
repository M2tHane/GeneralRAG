package com.rag.retrieval;

import java.util.List;

import com.rag.storage.es.EsHit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RRF 融合单元测试（R2-H2）：公式、确定性、单通道未命中仍进候选。
 *
 * <p>融合在应用侧实现（ES 原生 RRF 因 license=basic 返回 403，见需求 E4），
 * 因此可以用纯内存测试把公式钉死——这正是选择应用侧融合的收益之一。</p>
 */
class RrfFusionTest {

    private final RrfFusion fusion = new RrfFusion();

    private static EsHit hit(String id, double score) {
        return new EsHit(id, "doc-1", "doc-1 > 标题", null, 1, 10, "内容 " + id, score);
    }

    @Test
    void fusesTwoChannelsByReciprocalRank() {
        // k=60：向量 #1 → 1/61，BM25 #1 → 1/61
        List<EsHit> vector = List.of(hit("A", 0.9), hit("B", 0.8));
        List<EsHit> bm25 = List.of(hit("B", 12.0), hit("C", 8.0));

        List<RrfFusion.FusedHit> fused = fusion.fuse(60, vector, bm25);

        // 手算：A = 1/61 ≈ 0.016393；B = 1/62 + 1/61 ≈ 0.032522；C = 1/62 ≈ 0.016129
        // 期望序：B（双通道）> A ≈ C
        assertThat(fused).extracting(f -> f.chunk().chunkId()).containsExactly("B", "A", "C");
        double expectedB = 1.0 / 61 + 1.0 / 62;
        double expectedA = 1.0 / 61;
        double expectedC = 1.0 / 62;
        assertThat(fused.get(0).fusedScore()).isCloseTo(expectedB, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(fused.get(1).fusedScore()).isCloseTo(expectedA, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(fused.get(2).fusedScore()).isCloseTo(expectedC, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void recordsPerChannelRanksAndLeavesMissingChannelNull() {
        List<EsHit> vector = List.of(hit("A", 0.9), hit("B", 0.8));
        List<EsHit> bm25 = List.of(hit("B", 12.0));

        List<RrfFusion.FusedHit> fused = fusion.fuse(60, vector, bm25);

        RrfFusion.FusedHit b = fused.stream().filter(f -> f.chunk().chunkId().equals("B"))
                .findFirst().orElseThrow();
        assertThat(b.vectorRank()).isEqualTo(2);
        assertThat(b.bm25Rank()).isEqualTo(1);

        // A 只出现在向量通道：bm25Rank 为 null —— 这本身是有效信息（BM25 未召回）
        RrfFusion.FusedHit a = fused.stream().filter(f -> f.chunk().chunkId().equals("A"))
                .findFirst().orElseThrow();
        assertThat(a.vectorRank()).isEqualTo(1);
        assertThat(a.bm25Rank()).isNull();
    }

    /** 单通道召回的候选必须仍进候选集（不能因另一通道缺失而被丢弃）。 */
    @Test
    void keepsSingleChannelOnlyCandidates() {
        List<EsHit> vector = List.of(hit("V-only", 0.9));
        List<EsHit> bm25 = List.of(hit("B-only", 5.0));

        List<RrfFusion.FusedHit> fused = fusion.fuse(60, vector, bm25);

        assertThat(fused).extracting(f -> f.chunk().chunkId())
                .containsExactlyInAnyOrder("V-only", "B-only");
    }

    /** 排序必须确定：同分时按首现顺序、再按 chunkId，避免两次运行抖动。 */
    @Test
    void tiesAreBrokenDeterministically() {
        List<EsHit> vector = List.of(hit("B", 0.9), hit("A", 0.8));
        List<RrfFusion.FusedHit> first = fusion.fuse(60, vector, List.of());
        List<RrfFusion.FusedHit> second = fusion.fuse(60, vector, List.of());
        assertThat(first).extracting(f -> f.chunk().chunkId()).containsExactly("B", "A");
        assertThat(second).extracting(f -> f.chunk().chunkId()).containsExactly("B", "A");
    }

    @Test
    void emptyChannelsYieldEmptyResult() {
        assertThat(fusion.fuse(60, List.of(), List.of())).isEmpty();
        assertThat(fusion.fuse(60, null, null)).isEmpty();
    }

    /**
     * 归一化：k=60 时两通道 rank1 得 1.0、单通道 rank1 得 0.5。
     * 该性质说明为何 score 不能直接用融合分（单通道相关命中会被降到 0.5）。
     */
    @Test
    void normalizeCapsSingleChannelAtHalf() {
        double bothTop = RrfFusion.normalize(2.0 / 61, 60);
        double singleTop = RrfFusion.normalize(1.0 / 61, 60);
        assertThat(bothTop).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(singleTop).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-12));
    }
}
