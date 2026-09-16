package com.rag.answerability;

/**
 * Answerability 判定结果（不可变；Chat / Debug / Eval 共享同一结构）。
 *
 * @param answerable   判定结果：证据是否足以完整回答（true=进入生成）
 * @param decisionType 决策类型（为什么回答/拒答）
 * @param confidence   Judge 置信度 [0,1]；未调 Judge 为 null
 * @param reason       决策原因（Judge 简述或门控说明）；未判定为 null——
 *                     未调 Judge 时绝不伪造 Judge reason
 * @param judgeInvoked 本次是否实际调用了 Judge（门控直拒为 false）
 * @param degraded     Judge 是否失败降级（超时/不可用/解析失败）
 * @param failureType  降级二级原因（R4.1.1 结构化）：仅 degraded=true 时非 null；
 *                     指标统计的事实源（reason 文本前缀仅供人类阅读）
 * @param latencyMs    判定耗时（毫秒，含 Judge 调用）；门控路径为门控本身耗时（≈0）
 */
public record AnswerabilityDecision(boolean answerable,
                                    AnswerabilityDecisionType decisionType,
                                    Double confidence,
                                    String reason,
                                    boolean judgeInvoked,
                                    boolean degraded,
                                    JudgeFailureType failureType,
                                    long latencyMs) {

    public AnswerabilityDecision {
        if (!degraded) {
            failureType = null; // 非 degraded 一律 null，杜绝脏数据
        }
    }

    public static AnswerabilityDecision lowScoreRefusal(double topScore, double threshold, String scale) {
        return new AnswerabilityDecision(false, AnswerabilityDecisionType.LOW_SCORE_REFUSAL,
                null, "最高分 " + String.format(java.util.Locale.ROOT, "%.3f", topScore)
                + "（" + scale + " 口径）低于阈值 " + String.format(java.util.Locale.ROOT, "%.2f", threshold),
                false, false, null, 0);
    }

    public static AnswerabilityDecision noHits(long latencyMs) {
        return new AnswerabilityDecision(false, AnswerabilityDecisionType.NO_HITS,
                null, "检索零命中，无证据可判定", false, false, null, latencyMs);
    }

    public static AnswerabilityDecision judgeAccept(double confidence, String reason, long latencyMs) {
        return new AnswerabilityDecision(true, AnswerabilityDecisionType.JUDGE_ACCEPT,
                confidence, reason, true, false, null, latencyMs);
    }

    public static AnswerabilityDecision judgeRefuse(double confidence, String reason, long latencyMs) {
        return new AnswerabilityDecision(false, AnswerabilityDecisionType.JUDGE_REFUSE,
                confidence, reason, true, false, null, latencyMs);
    }

    public static AnswerabilityDecision judgeDegraded(boolean fallbackAnswerable, JudgeFailureType failureType,
                                                      String reason, long latencyMs) {
        return new AnswerabilityDecision(fallbackAnswerable, AnswerabilityDecisionType.JUDGE_DEGRADED,
                null, reason, true, true, failureType, latencyMs);
    }
}
