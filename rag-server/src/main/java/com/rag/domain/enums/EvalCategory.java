package com.rag.domain.enums;

/**
 * 评测问题类别（contracts/openapi.yaml EvalCategory；EV-1/EV-5 五类覆盖）。
 */
public enum EvalCategory {
    DIRECT,
    TERM_VARIATION,
    FOLLOW_UP,
    OUT_OF_KB,
    CONFUSABLE
}
