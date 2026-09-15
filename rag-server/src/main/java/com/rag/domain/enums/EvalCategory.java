package com.rag.domain.enums;

/**
 * 评测问题类别（contracts/openapi.yaml EvalCategory；EV-1/EV-5 五类覆盖）。
 *
 * <p>R4（Answerability 轮）新增 {@link #PARTIAL_EVIDENCE}：语料只覆盖问题要求
 * 的<b>部分</b>答案（如问 A/B/C 三种方案优缺点而语料只有 A/B），检索可以高度
 * 相关但证据不足以完整回答——expected answerable=false。它是"高相关但不可回答"
 * Bad Case 的核心正样本类别之一（另一类是 answerable=false 的 CONFUSABLE）。</p>
 */
public enum EvalCategory {
    DIRECT,
    TERM_VARIATION,
    FOLLOW_UP,
    OUT_OF_KB,
    CONFUSABLE,
    PARTIAL_EVIDENCE
}
