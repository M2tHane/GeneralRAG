package com.rag.answerability;

/**
 * Answerability 决策类型（contracts/openapi.yaml AnswerabilityDecisionType）。
 *
 * <p>回答/拒答的<b>原因</b>，供调试与评测归因——同一"拒答"结果可能是低分直拒、
 * Judge 拒绝或降级路径，归因不同则优化动作不同。</p>
 */
public enum AnswerabilityDecisionType {

    /** 低于低分阈值直接拒答（未调 Judge）。 */
    LOW_SCORE_REFUSAL,

    /** 检索零命中，直接拒答。 */
    NO_HITS,

    /** 灰区，Judge 判定证据足以完整回答。 */
    JUDGE_ACCEPT,

    /** 灰区，Judge 判定证据不足以回答。 */
    JUDGE_REFUSE,

    /** Judge 失败（超时/不可用/解析失败），按降级策略处理（退回旧阈值行为）。 */
    JUDGE_DEGRADED,

    /** Answerability 关闭（仅对照），行为同旧 RefusalPolicy 单阈值判定。 */
    ANSWERABILITY_DISABLED
}
