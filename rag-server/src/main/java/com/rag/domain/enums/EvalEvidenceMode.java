package com.rag.domain.enums;

/**
 * Hard Eval 正例证据形态（R6-A，contracts/openapi.yaml EvalEvidenceMode）。
 *
 * <p>仅用于 {@code answerable=true} 的样本，声明「多少块证据才能完整回答」，
 * 驱动不同的指标口径：
 * {@link #SINGLE_CHUNK} 一块证据足够（常规 Hit@K / MRR）；
 * {@link #MULTI_CHUNK} 多块 required evidence 必须共同覆盖（附加 Evidence Coverage）；
 * {@link #FOLLOW_UP} 当前问题依赖 history 做指代解析（history 只用于解析指代，
 * 不构成证据）。{@code answerable=false} 的样本该值必须为 null。</p>
 */
public enum EvalEvidenceMode {
    SINGLE_CHUNK,
    MULTI_CHUNK,
    FOLLOW_UP
}
