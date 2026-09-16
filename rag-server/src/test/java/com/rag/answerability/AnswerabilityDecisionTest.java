package com.rag.answerability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AnswerabilityDecision 不变量测试（R4.1.2）：
 * degraded=false → failureType=null；degraded=true → failureType 非 null。
 * 该不变量保证 Eval 的降级明细统计永远不会在新代码路径产生 UNKNOWN。
 */
class AnswerabilityDecisionTest {

    @Test
    void nonDegradedDecisionNormalizesFailureTypeToNull() {
        AnswerabilityDecision d = new AnswerabilityDecision(
                true, AnswerabilityDecisionType.JUDGE_ACCEPT, 0.9, "ok",
                true, false, JudgeFailureType.MODEL_ERROR, 10);
        assertThat(d.degraded()).isFalse();
        assertThat(d.failureType()).isNull();
    }

    @Test
    void degradedWithNullFailureTypeIsRejected() {
        assertThatThrownBy(() -> new AnswerabilityDecision(
                false, AnswerabilityDecisionType.JUDGE_DEGRADED, null,
                "TIMEOUT: Judge 15 秒超时", true, true, null, 15000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("degraded decision requires failureType");
    }

    @Test
    void degradedWithFailureTypeIsAccepted() {
        AnswerabilityDecision d = AnswerabilityDecision.judgeDegraded(
                true, JudgeFailureType.OVERLOADED, "OVERLOADED: ...", 500);
        assertThat(d.degraded()).isTrue();
        assertThat(d.failureType()).isEqualTo(JudgeFailureType.OVERLOADED);
        assertThat(d.decisionType()).isEqualTo(AnswerabilityDecisionType.JUDGE_DEGRADED);
    }

    @Test
    void gateDecisionsCarryNullFailureType() {
        assertThat(AnswerabilityDecision.noHits(0).failureType()).isNull();
        assertThat(AnswerabilityDecision.lowScoreRefusal(0.1, 0.75, "rerank").failureType()).isNull();
        assertThat(AnswerabilityDecision.judgeAccept(0.9, "ok", 10).failureType()).isNull();
        assertThat(AnswerabilityDecision.judgeRefuse(0.9, "no", 10).failureType()).isNull();
    }
}
