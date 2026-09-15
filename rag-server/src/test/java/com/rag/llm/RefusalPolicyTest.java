package com.rag.llm;

import java.util.List;

import com.rag.config.RagProperties;
import com.rag.retrieval.model.RetrievalHit;
import com.rag.retrieval.model.RetrievalMode;
import com.rag.storage.es.EsHit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拒答策略单元测试（R2-A2）。
 *
 * <p>钉死两件易错的事：</p>
 * <ol>
 *   <li>两套阈值按"分数来源"选择——重排相关度用 rerank 阈值、余弦用 cosine 阈值；
 *       若混用（例如未配重排却套 0.65 的 rerank 阈值），会把相关命中系统性误拒；</li>
 *   <li>空命中必然判为证据不足。</li>
 * </ol>
 */
class RefusalPolicyTest {

    private static RetrievalHit hit(double score) {
        return new RetrievalHit(1, new EsHit("c1", "d1", "t", null, 1, 5, "x", score), score, true);
    }

    private static RefusalPolicy policy(double rerankThreshold, double cosineThreshold) {
        RagProperties props = new RagProperties();
        props.getRetrieval().getRefusal().setEnabled(true);
        props.getRetrieval().getRefusal().setRerankThreshold(rerankThreshold);
        props.getRetrieval().getRefusal().setCosineThreshold(cosineThreshold);
        return new RefusalPolicy(props);
    }

    @Test
    void emptyHitsAreAlwaysInsufficient() {
        RefusalPolicy p = policy(0.65, 0.30);
        assertThat(p.insufficient(List.of(), RetrievalMode.VECTOR, false)).isTrue();
        assertThat(p.insufficient(null, RetrievalMode.VECTOR, false)).isTrue();
    }

    /** 重排生效：用 rerank 阈值（0.65）。0.60 的相关命中应判不足。 */
    @Test
    void usesRerankThresholdWhenRerankApplied() {
        RefusalPolicy p = policy(0.65, 0.30);
        assertThat(p.insufficient(List.of(hit(0.60)), RetrievalMode.HYBRID_RERANK, true)).isTrue();
        assertThat(p.insufficient(List.of(hit(0.80)), RetrievalMode.HYBRID_RERANK, true)).isFalse();
        assertThat(p.scaleName(RetrievalMode.HYBRID_RERANK, true)).isEqualTo("rerank");
    }

    /**
     * 重排未生效（未配置重排服务或已降级）：必须回落到 cosine 阈值。
     * 这是回归守护——若误用 rerank 阈值，0.60 的余弦命中会被错误拒答。
     */
    @Test
    void fallsBackToCosineThresholdWhenRerankNotApplied() {
        RefusalPolicy p = policy(0.65, 0.30);
        assertThat(p.insufficient(List.of(hit(0.60)), RetrievalMode.HYBRID_RERANK, false)).isFalse();
        assertThat(p.insufficient(List.of(hit(0.20)), RetrievalMode.HYBRID_RERANK, false)).isTrue();
        assertThat(p.scaleName(RetrievalMode.HYBRID_RERANK, false)).isEqualTo("cosine");
    }

    @Test
    void vectorAndHybridUseCosineThreshold() {
        RefusalPolicy p = policy(0.65, 0.30);
        assertThat(p.insufficient(List.of(hit(0.40)), RetrievalMode.VECTOR, false)).isFalse();
        assertThat(p.insufficient(List.of(hit(0.40)), RetrievalMode.HYBRID, false)).isFalse();
        assertThat(p.insufficient(List.of(hit(0.10)), RetrievalMode.HYBRID, false)).isTrue();
    }

    @Test
    void disabledPolicyNeverRefuses() {
        RagProperties props = new RagProperties();
        props.getRetrieval().getRefusal().setEnabled(false);
        RefusalPolicy p = new RefusalPolicy(props);
        assertThat(p.insufficient(List.of(hit(0.01)), RetrievalMode.HYBRID_RERANK, true)).isFalse();
        assertThat(p.insufficient(List.of(), RetrievalMode.VECTOR, false)).isFalse();
    }

    /** 取最高分判定（不能被低分候选拉低结论）。 */
    @Test
    void usesTopScoreAcrossHits() {
        RefusalPolicy p = policy(0.65, 0.30);
        assertThat(p.insufficient(List.of(hit(0.10), hit(0.90)), RetrievalMode.HYBRID_RERANK, true))
                .isFalse();
    }
}
