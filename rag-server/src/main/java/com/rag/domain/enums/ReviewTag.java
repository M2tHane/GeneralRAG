package com.rag.domain.enums;

/**
 * 评测明细人工标注标签（contracts/openapi.yaml EvalRunItem.reviewTag；
 * V1__init.sql eval_run_item.review_tag）。NULL 表示未标注。
 */
public enum ReviewTag {
    OK,
    WRONG_ANSWER,
    MISSING_EVIDENCE,
    WRONG_SOURCE,
    OTHER
}
