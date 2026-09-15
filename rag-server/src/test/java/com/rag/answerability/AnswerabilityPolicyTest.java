package com.rag.answerability;

import java.util.List;

import com.rag.config.RagProperties;
import com.rag.llm.RefusalPolicy;
import com.rag.retrieval.model.RetrievalHit;
import com.rag.retrieval.model.RetrievalMode;
import com.rag.storage.es.EsHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AnswerabilityPolicy 单元测试（R4）——钉死门控语义与降级语义：
 *
 * <ol>
 *   <li>无 hits / 低分：不调 Judge 直接拒答（门控第一步）；</li>
 *   <li>灰区与高分：必须调 Judge（Baseline 数据证明高分不可答真实存在，
 *       不存在安全 highThreshold——不允许"高分跳过 Judge"的实现漂移）；</li>
 *   <li>Judge 失败：degraded=true，按 failClosed 拒答或退回旧阈值放行；</li>
 *   <li>关闭：ANSWERABILITY_DISABLED 走旧 RefusalPolicy 行为；</li>
 *   <li>VECTOR / HYBRID / HYBRID_RERANK / 重排降级四种口径的阈值选择。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnswerabilityPolicyTest {

    @Mock
    private EvidenceSufficiencyJudge judge;

    private RagProperties props;

    @BeforeEach
    void setUp() {
        props = new RagProperties();
        props.getRetrieval().getRefusal().setEnabled(true);
        props.getRetrieval().getRefusal().setRerankThreshold(0.65);
        props.getRetrieval().getRefusal().setCosineThreshold(0.30);
        props.getRetrieval().getAnswerability().setEnabled(true);
        props.getRetrieval().getAnswerability().setFailClosed(false);
    }

    private AnswerabilityPolicy policy() {
        return new AnswerabilityPolicy(props, new RefusalPolicy(props), judge);
    }

    private static RetrievalHit hit(double score) {
        return new RetrievalHit(1, new EsHit("c1", "d1", "t", null, 1, 5, "内容", score), score, true);
    }

    // ---------- 门控第一步：低分 / 无命中不调 Judge ----------

    @Test
    void noHitsRefusesWithoutJudge() {
        AnswerabilityDecision d = policy().evaluate(List.of(), RetrievalMode.HYBRID_RERANK, true, "问题", "证据");
        assertThat(d.answerable()).isFalse();
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.NO_HITS);
        assertThat(d.judgeInvoked()).isFalse();
        verify(judge, never()).judge(anyString(), anyString());
    }

    @Test
    void lowScoreRefusesWithoutJudge() {
        // rerank 生效口径阈值 0.65：0.40 < 0.65 → LOW_SCORE_REFUSAL
        AnswerabilityDecision d = policy().evaluate(
                List.of(hit(0.40)), RetrievalMode.HYBRID_RERANK, true, "问题", "证据");
        assertThat(d.answerable()).isFalse();
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.LOW_SCORE_REFUSAL);
        assertThat(d.judgeInvoked()).isFalse();
        assertThat(d.confidence()).isNull();
        assertThat(d.reason()).contains("0.400").contains("低于阈值");
        verify(judge, never()).judge(anyString(), anyString());
    }

    /** 边界值：score == 阈值时不过阈值（RefusalPolicy 语义是 top < threshold 才"不足"，恰好相等 → 进 Judge）。 */
    @Test
    void scoreExactlyAtThresholdGoesToJudge() throws Exception {
        when(judge.judge(anyString(), anyString()))
                .thenReturn(new EvidenceSufficiencyJudge.JudgeResult(true, 0.9, "证据完整"));
        AnswerabilityDecision d = policy().evaluate(
                List.of(hit(0.65)), RetrievalMode.HYBRID_RERANK, true, "问题", "证据");
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.JUDGE_ACCEPT);
        verify(judge).judge(eq("问题"), eq("证据"));
    }

    // ---------- 灰区与高分：全部走 Judge（不允许高分直答） ----------

    @Test
    void midScoreGoesToJudge() throws Exception {
        when(judge.judge(anyString(), anyString()))
                .thenReturn(new EvidenceSufficiencyJudge.JudgeResult(false, 0.95, "证据仅涉及连接池参数"));
        AnswerabilityDecision d = policy().evaluate(
                List.of(hit(0.75)), RetrievalMode.HYBRID_RERANK, true, "问题", "证据");
        assertThat(d.answerable()).isFalse();
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.JUDGE_REFUSE);
        assertThat(d.judgeInvoked()).isTrue();
        assertThat(d.degraded()).isFalse();
        assertThat(d.confidence()).isEqualTo(0.95);
        assertThat(d.reason()).isEqualTo("证据仅涉及连接池参数");
    }

    /** CONFUSABLE 形态：rerank=1.000 高分也必须过 Judge——这是本轮 Bad Case 的核心回归守护。 */
    @Test
    void perfectScoreStillRequiresJudge() throws Exception {
        when(judge.judge(anyString(), anyString()))
                .thenReturn(new EvidenceSufficiencyJudge.JudgeResult(false, 0.93, "证据只有默认值，无最小值"));
        AnswerabilityDecision d = policy().evaluate(
                List.of(hit(1.000)), RetrievalMode.HYBRID_RERANK, true, "问题", "证据");
        assertThat(d.answerable()).isFalse();
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.JUDGE_REFUSE);
        verify(judge).judge(anyString(), anyString());
    }

    @Test
    void judgeAcceptCarriesConfidenceAndReason() throws Exception {
        when(judge.judge(anyString(), anyString()))
                .thenReturn(new EvidenceSufficiencyJudge.JudgeResult(true, 0.92, "证据明确给出默认值"));
        AnswerabilityDecision d = policy().evaluate(
                List.of(hit(0.90)), RetrievalMode.HYBRID_RERANK, true, "问题", "证据");
        assertThat(d.answerable()).isTrue();
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.JUDGE_ACCEPT);
        assertThat(d.confidence()).isEqualTo(0.92);
        assertThat(d.judgeInvoked()).isTrue();
        assertThat(d.degraded()).isFalse();
        assertThat(d.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    // ---------- Judge 失败降级 ----------

    @Test
    void judgeFailureFailOpenFallsBackToOldPolicy() {
        // failClosed=false（默认）：灰区（0.75 ≥ 0.65）退回旧阈值行为 = 放行
        when(judge.judge(anyString(), anyString()))
                .thenThrow(new EvidenceSufficiencyJudge.JudgeUnavailableException("超时"));
        AnswerabilityDecision d = policy().evaluate(
                List.of(hit(0.75)), RetrievalMode.HYBRID_RERANK, true, "问题", "证据");
        assertThat(d.answerable()).isTrue();
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.JUDGE_DEGRADED);
        assertThat(d.degraded()).isTrue();
        assertThat(d.confidence()).isNull();
        assertThat(d.reason()).contains("退回旧阈值行为");
    }

    @Test
    void judgeFailureFailClosedRefuses() {
        props.getRetrieval().getAnswerability().setFailClosed(true);
        when(judge.judge(anyString(), anyString()))
                .thenThrow(new EvidenceSufficiencyJudge.JudgeUnavailableException("服务不可用"));
        AnswerabilityDecision d = policy().evaluate(
                List.of(hit(0.90)), RetrievalMode.HYBRID_RERANK, true, "问题", "证据");
        assertThat(d.answerable()).isFalse();
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.JUDGE_DEGRADED);
        assertThat(d.degraded()).isTrue();
        assertThat(d.reason()).contains("failClosed");
    }

    // ---------- 关闭（对照模式） ----------

    @Test
    void disabledFallsBackToRefusalPolicySemantics() {
        props.getRetrieval().getAnswerability().setEnabled(false);
        // 0.40 < 0.65 → 旧阈值拒答
        AnswerabilityDecision refuse = policy().evaluate(
                List.of(hit(0.40)), RetrievalMode.HYBRID_RERANK, true, "问题", "证据");
        assertThat(refuse.answerable()).isFalse();
        assertThat(refuse.decisionType()).isEqualTo(AnswerabilityDecisionType.ANSWERABILITY_DISABLED);
        assertThat(refuse.judgeInvoked()).isFalse();
        verify(judge, never()).judge(anyString(), anyString());
        // 0.80 ≥ 0.65 → 旧阈值放行
        AnswerabilityDecision accept = policy().evaluate(
                List.of(hit(0.80)), RetrievalMode.HYBRID_RERANK, true, "问题", "证据");
        assertThat(accept.answerable()).isTrue();
        assertThat(accept.decisionType()).isEqualTo(AnswerabilityDecisionType.ANSWERABILITY_DISABLED);
    }

    // ---------- 检索模式 / 降级口径 ----------

    @Test
    void vectorModeUsesCosineThreshold() throws Exception {
        // VECTOR 口径阈值 0.30：0.40 ≥ 0.30 → 进 Judge（余弦阈值不能拒它）
        when(judge.judge(anyString(), anyString()))
                .thenReturn(new EvidenceSufficiencyJudge.JudgeResult(true, 0.8, "ok"));
        AnswerabilityDecision d = policy().evaluate(
                List.of(hit(0.40)), RetrievalMode.VECTOR, false, "问题", "证据");
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.JUDGE_ACCEPT);
        // 0.20 < 0.30 → 低分直拒
        AnswerabilityDecision low = policy().evaluate(
                List.of(hit(0.20)), RetrievalMode.VECTOR, false, "问题", "证据");
        assertThat(low.decisionType()).isEqualTo(AnswerabilityDecisionType.LOW_SCORE_REFUSAL);
    }

    @Test
    void hybridRerankDegradedUsesCosineThreshold() throws Exception {
        // 重排降级：口径切回 cosine 0.30——0.50 的 rerank 语义分不能套 0.65 阈值
        when(judge.judge(anyString(), anyString()))
                .thenReturn(new EvidenceSufficiencyJudge.JudgeResult(true, 0.8, "ok"));
        AnswerabilityDecision d = policy().evaluate(
                List.of(hit(0.50)), RetrievalMode.HYBRID_RERANK, false, "问题", "证据");
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.JUDGE_ACCEPT);
        verify(judge).judge(contains("问题"), anyString());
    }

    @Test
    void hybridModeUsesCosineThreshold() throws Exception {
        // HYBRID 模式没有重排：0.50 的余弦分 ≥ 0.30 → 必须进 Judge（cosine 口径不拒它）
        when(judge.judge(anyString(), anyString()))
                .thenReturn(new EvidenceSufficiencyJudge.JudgeResult(true, 0.8, "ok"));
        AnswerabilityDecision d = policy().evaluate(
                List.of(hit(0.50)), RetrievalMode.HYBRID, false, "问题", "证据");
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.JUDGE_ACCEPT);
        verify(judge).judge(eq("问题"), eq("证据"));
    }
}
