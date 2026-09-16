package com.rag.answerability;

import java.util.List;

import com.rag.config.RagProperties;
import com.rag.llm.RefusalPolicy;
import com.rag.retrieval.model.Context;
import com.rag.retrieval.model.RetrievalHit;
import com.rag.retrieval.model.RetrievalMode;
import com.rag.storage.es.EsHit;
import com.rag.answerability.JudgeFailureType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AnswerabilityPolicy 单元测试（R4 / R4.1 稳定化）——钉死门控语义与降级语义：
 *
 * <ol>
 *   <li>无 hits / 低分：不调 Judge 直接拒答（门控第一步）；</li>
 *   <li>灰区与高分：必须调 Judge（Baseline 数据证明高分不可答真实存在，
 *       不存在安全 highThreshold——不允许"高分跳过 Judge"的实现漂移）；</li>
 *   <li>Judge 失败：degraded=true，按 failClosed 拒答或退回旧阈值放行；
 *       降级 reason 保留原因分类（TIMEOUT/OVERLOADED/…）供 Debug/Eval 归因；</li>
 *   <li>关闭：ANSWERABILITY_DISABLED 走旧 RefusalPolicy 行为；</li>
 *   <li>VECTOR / HYBRID / HYBRID_RERANK / 重排降级四种口径的阈值选择；</li>
 *   <li>R4.1：Judge 收到的证据 = 输入 Context 的全文（Evidence 一致性，
 *       历史透传），mid-chunk 截断不再存在。</li>
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

    private static Context context(String text) {
        return new Context(text, text.length(), List.of("c1"), false);
    }

    private AnswerabilityInput input(double score, RetrievalMode mode, boolean rerankApplied) {
        return AnswerabilityInput.of("问题", context("证据全文"), List.of(hit(score)), mode, rerankApplied);
    }

    // ---------- 门控第一步：低分 / 无命中不调 Judge ----------

    @Test
    void noHitsRefusesWithoutJudge() {
        AnswerabilityInput in = AnswerabilityInput.of(
                "问题", Context.empty(), List.of(), RetrievalMode.HYBRID_RERANK, true);
        AnswerabilityDecision d = policy().evaluate(in);
        assertThat(d.answerable()).isFalse();
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.NO_HITS);
        assertThat(d.judgeInvoked()).isFalse();
        verify(judge, never()).judge(anyString(), anyString());
        verify(judge, never()).judge(anyString(), anyList(), anyString());
    }

    @Test
    void lowScoreRefusesWithoutJudge() {
        // rerank 生效口径阈值 0.65：0.40 < 0.65 → LOW_SCORE_REFUSAL
        AnswerabilityDecision d = policy().evaluate(input(0.40, RetrievalMode.HYBRID_RERANK, true));
        assertThat(d.answerable()).isFalse();
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.LOW_SCORE_REFUSAL);
        assertThat(d.judgeInvoked()).isFalse();
        assertThat(d.confidence()).isNull();
        assertThat(d.reason()).contains("0.400").contains("低于阈值");
        verify(judge, never()).judge(anyString(), anyString());
    }

    /** 边界值：score == 阈值时不过阈值（RefusalPolicy 语义是 top < threshold 才"不足"，恰好相等 → 进 Judge）。 */
    @Test
    void scoreExactlyAtThresholdGoesToJudge() {
        when(judge.judge(anyString(), anyList(), anyString()))
                .thenReturn(new EvidenceSufficiencyJudge.JudgeResult(true, 0.9, "证据完整"));
        AnswerabilityDecision d = policy().evaluate(input(0.65, RetrievalMode.HYBRID_RERANK, true));
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.JUDGE_ACCEPT);
        verify(judge).judge(eq("问题"), anyList(), eq("证据全文"));
    }

    // ---------- 灰区与高分：全部走 Judge（不允许高分直答） ----------

    @Test
    void midScoreGoesToJudge() {
        when(judge.judge(anyString(), anyList(), anyString()))
                .thenReturn(new EvidenceSufficiencyJudge.JudgeResult(false, 0.95, "证据仅涉及连接池参数"));
        AnswerabilityDecision d = policy().evaluate(input(0.75, RetrievalMode.HYBRID_RERANK, true));
        assertThat(d.answerable()).isFalse();
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.JUDGE_REFUSE);
        assertThat(d.judgeInvoked()).isTrue();
        assertThat(d.degraded()).isFalse();
        assertThat(d.confidence()).isEqualTo(0.95);
        assertThat(d.reason()).isEqualTo("证据仅涉及连接池参数");
    }

    /** CONFUSABLE 形态：rerank=1.000 高分也必须过 Judge——这是本轮 Bad Case 的核心回归守护。 */
    @Test
    void perfectScoreStillRequiresJudge() {
        when(judge.judge(anyString(), anyList(), anyString()))
                .thenReturn(new EvidenceSufficiencyJudge.JudgeResult(false, 0.93, "证据只有默认值，无最小值"));
        AnswerabilityDecision d = policy().evaluate(input(1.000, RetrievalMode.HYBRID_RERANK, true));
        assertThat(d.answerable()).isFalse();
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.JUDGE_REFUSE);
        verify(judge).judge(anyString(), anyList(), anyString());
    }

    @Test
    void judgeAcceptCarriesConfidenceAndReason() {
        when(judge.judge(anyString(), anyList(), anyString()))
                .thenReturn(new EvidenceSufficiencyJudge.JudgeResult(true, 0.92, "证据明确给出默认值"));
        AnswerabilityDecision d = policy().evaluate(input(0.90, RetrievalMode.HYBRID_RERANK, true));
        assertThat(d.answerable()).isTrue();
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.JUDGE_ACCEPT);
        assertThat(d.confidence()).isEqualTo(0.92);
        assertThat(d.judgeInvoked()).isTrue();
        assertThat(d.degraded()).isFalse();
        assertThat(d.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    /** R4.1 §十二：history 透传给 Judge（仅解析指代），conversation 上下文不丢。 */
    @Test
    void historyIsForwardedToJudge() {
        when(judge.judge(anyString(), anyList(), anyString()))
                .thenReturn(new EvidenceSufficiencyJudge.JudgeResult(true, 0.9, "ok"));
        com.rag.llm.PromptAssembler.HistoryTurn turn =
                new com.rag.llm.PromptAssembler.HistoryTurn(com.rag.domain.enums.SessionRole.USER, "上一问");
        AnswerabilityInput in = new AnswerabilityInput(
                "那怎么解除只读块？", List.of(turn), context("证据全文"),
                List.of(hit(0.90)), RetrievalMode.HYBRID_RERANK, true);
        policy().evaluate(in);
        verify(judge).judge(eq("那怎么解除只读块？"), eq(List.of(turn)), eq("证据全文"));
    }

    // ---------- Judge 失败降级 ----------

    @Test
    void judgeFailureFailOpenFallsBackToOldPolicy() {
        // failClosed=false（默认）：灰区（0.75 ≥ 0.65）退回旧阈值行为 = 放行；
        // 降级 reason 保留原因分类前缀（如 TIMEOUT:）供归因；failureType 结构化透出
        when(judge.judge(anyString(), anyList(), anyString()))
                .thenThrow(new EvidenceSufficiencyJudge.JudgeUnavailableException(
                        JudgeFailureType.TIMEOUT, "TIMEOUT: Judge 15 秒超时"));
        AnswerabilityDecision d = policy().evaluate(input(0.75, RetrievalMode.HYBRID_RERANK, true));
        assertThat(d.answerable()).isTrue();
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.JUDGE_DEGRADED);
        assertThat(d.degraded()).isTrue();
        assertThat(d.confidence()).isNull();
        assertThat(d.reason()).contains("退回旧阈值行为").contains("TIMEOUT");
        assertThat(d.failureType()).isEqualTo(JudgeFailureType.TIMEOUT);
    }

    @Test
    void judgeFailureFailClosedRefuses() {
        props.getRetrieval().getAnswerability().setFailClosed(true);
        when(judge.judge(anyString(), anyList(), anyString()))
                .thenThrow(new EvidenceSufficiencyJudge.JudgeUnavailableException(
                        JudgeFailureType.MODEL_ERROR, "MODEL_ERROR: 服务不可用"));
        AnswerabilityDecision d = policy().evaluate(input(0.90, RetrievalMode.HYBRID_RERANK, true));
        assertThat(d.answerable()).isFalse();
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.JUDGE_DEGRADED);
        assertThat(d.degraded()).isTrue();
        assertThat(d.reason()).contains("failClosed");
        assertThat(d.failureType()).isEqualTo(JudgeFailureType.MODEL_ERROR);
    }

    // ---------- 关闭（对照模式） ----------

    @Test
    void disabledFallsBackToRefusalPolicySemantics() {
        props.getRetrieval().getAnswerability().setEnabled(false);
        // 0.40 < 0.65 → 旧阈值拒答
        AnswerabilityDecision refuse = policy().evaluate(input(0.40, RetrievalMode.HYBRID_RERANK, true));
        assertThat(refuse.answerable()).isFalse();
        assertThat(refuse.decisionType()).isEqualTo(AnswerabilityDecisionType.ANSWERABILITY_DISABLED);
        assertThat(refuse.judgeInvoked()).isFalse();
        verify(judge, never()).judge(anyString(), anyString());
        // 0.80 ≥ 0.65 → 旧阈值放行
        AnswerabilityDecision accept = policy().evaluate(input(0.80, RetrievalMode.HYBRID_RERANK, true));
        assertThat(accept.answerable()).isTrue();
        assertThat(accept.decisionType()).isEqualTo(AnswerabilityDecisionType.ANSWERABILITY_DISABLED);
    }

    // ---------- 检索模式 / 降级口径 ----------

    @Test
    void vectorModeUsesCosineThreshold() {
        // VECTOR 口径阈值 0.30：0.40 ≥ 0.30 → 进 Judge（余弦阈值不能拒它）
        when(judge.judge(anyString(), anyList(), anyString()))
                .thenReturn(new EvidenceSufficiencyJudge.JudgeResult(true, 0.8, "ok"));
        AnswerabilityDecision d = policy().evaluate(input(0.40, RetrievalMode.VECTOR, false));
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.JUDGE_ACCEPT);
        // 0.20 < 0.30 → 低分直拒
        AnswerabilityDecision low = policy().evaluate(input(0.20, RetrievalMode.VECTOR, false));
        assertThat(low.decisionType()).isEqualTo(AnswerabilityDecisionType.LOW_SCORE_REFUSAL);
    }

    @Test
    void hybridRerankDegradedUsesCosineThreshold() {
        // 重排降级：口径切回 cosine 0.30——0.50 的 rerank 语义分不能套 0.65 阈值
        when(judge.judge(anyString(), anyList(), anyString()))
                .thenReturn(new EvidenceSufficiencyJudge.JudgeResult(true, 0.8, "ok"));
        AnswerabilityDecision d = policy().evaluate(input(0.50, RetrievalMode.HYBRID_RERANK, false));
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.JUDGE_ACCEPT);
        verify(judge).judge(contains("问题"), anyList(), contains("证据全文"));
    }

    @Test
    void hybridModeUsesCosineThreshold() {
        // HYBRID 模式没有重排：0.50 的余弦分 ≥ 0.30 → 必须进 Judge（cosine 口径不拒它）
        when(judge.judge(anyString(), anyList(), anyString()))
                .thenReturn(new EvidenceSufficiencyJudge.JudgeResult(true, 0.8, "ok"));
        AnswerabilityDecision d = policy().evaluate(input(0.50, RetrievalMode.HYBRID, false));
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.JUDGE_ACCEPT);
        verify(judge).judge(eq("问题"), anyList(), eq("证据全文"));
    }

    // ---------- AnswerabilityInput 结构 ----------

    @Test
    void inputNormalizesNullHistoryAndHits() {
        AnswerabilityInput in = new AnswerabilityInput(
                "问题", null, context("证据"), null, RetrievalMode.VECTOR, false);
        assertThat(in.history()).isEmpty();
        assertThat(in.hits()).isEmpty();
        // null hits + disabled → 走旧阈值语义（insufficient(hits=null) 无命中 → 拒）
        props.getRetrieval().getAnswerability().setEnabled(false);
        AnswerabilityDecision d = policy().evaluate(in);
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.ANSWERABILITY_DISABLED);
        assertThat(d.answerable()).isFalse();
        verify(judge, never()).judge(any(String.class), anyList(), any(String.class));
    }
}
