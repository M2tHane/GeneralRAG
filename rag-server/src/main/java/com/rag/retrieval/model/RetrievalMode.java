package com.rag.retrieval.model;

/**
 * 检索模式（R2-H）：向量单通道基线 / BM25+向量融合 / 融合后再重排。
 * 决定运行时执行哪些阶段，并进入评测 config_snapshot 供两次运行区分（EV-4）。
 */
public enum RetrievalMode {

    /** 仅向量 kNN（第一轮行为，作为对照基线）。 */
    VECTOR,

    /** BM25 与向量双通道候选，应用侧 RRF 融合定序。 */
    HYBRID,

    /** 融合后再调用重排模型重新定序。 */
    HYBRID_RERANK
}
