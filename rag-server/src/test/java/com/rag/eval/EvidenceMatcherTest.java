package com.rag.eval;

import java.util.List;
import java.util.Map;

import com.rag.storage.es.EsHit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分块级证据判定测试（R2-E1）。
 *
 * <p>这是第二轮最关键的度量修复：第一轮用 {@code docName} 或 {@code titlePath} 相等判定，
 * 而数据集 titlePath 从未等于真实分块路径，命中全部由 docName 承担 → 退化为文档级，
 * 4 篇文档下指标饱和。本测试把"必须命中分块、不得回退文档级"钉死。</p>
 */
class EvidenceMatcherTest {

    private static EsHit hit(String chunkId, String titlePath) {
        return new EsHit(chunkId, "doc-1", titlePath, null, 1, 10, "内容", 0.9);
    }

    private static final List<EsHit> HITS = List.of(
            hit("doc-1-c0000", "redis.md > 1. Redis 持久化"),
            hit("doc-1-c0005", "redis.md > 1.3. AOF 的优缺点与刷盘策略"),
            hit("doc-1-c0008", "redis.md > 1.6. RDB 与 AOF 的相互作用"));

    @Test
    void matchesByChunkIdExactly() {
        Map<String, Object> ev = Map.of("chunkId", "doc-1-c0005");
        assertThat(EvidenceMatcher.firstMatchRank(ev, HITS)).isEqualTo(2);
    }

    @Test
    void matchesByTitlePathExactly() {
        Map<String, Object> ev = Map.of("titlePath", "redis.md > 1.6. RDB 与 AOF 的相互作用");
        assertThat(EvidenceMatcher.firstMatchRank(ev, HITS)).isEqualTo(3);
    }

    /**
     * 回归守护：不得回退到 docName 相等。
     *
     * <p>证据只给了 docName（无任何分块级锚点）时，第一轮会判为命中（文档级），
     * 本轮必须判为未命中——否则指标再次退化为文档级。</p>
     */
    @Test
    void doesNotFallBackToDocNameOnly() {
        Map<String, Object> ev = Map.of("docName", "redis.md");
        assertThat(EvidenceMatcher.firstMatchRank(ev, HITS)).isZero();
        assertThat(EvidenceMatcher.hasChunkLevelAnchor(ev)).isFalse();
    }

    /** 第一轮数据集里 titlePath 缺 docName 前缀的形态，不再被误判为命中。 */
    @Test
    void doesNotMatchTitlePathWithoutDocNamePrefix() {
        Map<String, Object> ev = Map.of("titlePath", "Redis 持久化 > 3. AOF 的优缺点与刷盘策略");
        assertThat(EvidenceMatcher.firstMatchRank(ev, HITS)).isZero();
        assertThat(EvidenceMatcher.hasChunkLevelAnchor(ev)).isTrue();
    }

    @Test
    void anchorPathIsAcceptedAsChunkLevelAnchor() {
        Map<String, Object> ev = Map.of("anchorPath", "redis.md > 1. Redis 持久化");
        assertThat(EvidenceMatcher.firstMatchRank(ev, HITS)).isEqualTo(1);
    }

    @Test
    void firstMatchRankOfAllReturnsBestRank() {
        List<Map<String, Object>> evidence = List.of(
                Map.of("chunkId", "doc-1-c0008"),
                Map.of("chunkId", "doc-1-c0005"));
        assertThat(EvidenceMatcher.firstMatchRankOfAllAsList(evidence, HITS)).isEqualTo(2);
    }

    @Test
    void emptyEvidenceOrHitsYieldZero() {
        assertThat(EvidenceMatcher.firstMatchRank(Map.of(), HITS)).isZero();
        assertThat(EvidenceMatcher.firstMatchRank(Map.of("chunkId", "x"), List.of())).isZero();
        assertThat(EvidenceMatcher.firstMatchRankOfAllAsList(List.of(), HITS)).isZero();
        assertThat(EvidenceMatcher.isHit(List.of(), HITS)).isFalse();
    }

    /** 已落库快照的复算路径（视图服务用）必须与检索路径同口径。 */
    @Test
    void snapshotMatcherAgreesWithHitMatcher() {
        List<EvidenceMatcher.CandidateRef> snapshot = HITS.stream()
                .map(h -> new EvidenceMatcher.CandidateRef(EvidenceMatcher.contentHashOf(h.content()), h.chunkId(), h.titlePath()))
                .toList();
        Map<String, Object> byChunk = Map.of("chunkId", "doc-1-c0005");
        Map<String, Object> byTitle = Map.of("titlePath", "redis.md > 1.6. RDB 与 AOF 的相互作用");
        Map<String, Object> byDocOnly = Map.of("docName", "redis.md");

        assertThat(EvidenceMatcher.firstMatchRankInSnapshot(byChunk, snapshot))
                .isEqualTo(EvidenceMatcher.firstMatchRank(byChunk, HITS));
        assertThat(EvidenceMatcher.firstMatchRankInSnapshot(byTitle, snapshot))
                .isEqualTo(EvidenceMatcher.firstMatchRank(byTitle, HITS));
        assertThat(EvidenceMatcher.firstMatchRankInSnapshot(byDocOnly, snapshot)).isZero();
    }
}
