package com.rag.debug;

import java.util.List;

/**
 * 检索调试响应 DTO 集合（contracts/openapi.yaml DebugRetrievalRequest/Result/
 * RetrievalHit/DebugTimings/DebugIssue 一字不差；阶段位次为第二轮新增）。
 */
public final class DebugResult {

    private DebugResult() {
    }

    /**
     * effectiveConfig：override 后的实际生效值 + 第二轮检索模式与融合参数。
     *
     * @param mode 本次生效检索模式（VECTOR/HYBRID/HYBRID_RERANK）
     * @param candidateLimit 各通道候选数上限
     * @param rrfK RRF 常数
     * @param rerankModel 重排模型名（未配置时为空串）
     * @param rerankEnabled 本次是否会调用重排
     * @param rerankDegraded 本次是否发生重排降级（重排未生效）
     * @param rerankDegradeReason 降级原因（未降级为 null）
     * @param answerabilityEnabled Answerability 判定是否启用（R4）
     * @param judgeModel Evidence Sufficiency Judge 使用的模型名（未启用时为空串）
     */
    public record EffectiveConfig(int topK, double minScore, String embeddingModel,
                                  int dimensions, String chatModel, String mode,
                                  int candidateLimit, int rrfK, String rerankModel,
                                  boolean rerankEnabled, boolean rerankDegraded,
                                  String rerankDegradeReason,
                                  boolean answerabilityEnabled, String judgeModel) {
    }

    /** 契约 Chunk 结构（EsHit 字段名 → 契约名映射：chunkId→id、content→text）。 */
    public record ChunkPayload(String id, String docId, int seq, String titlePath,
                               Integer page, int charCount, String text) {
    }

    /**
     * 候选在某一检索阶段的位次与分数。
     *
     * @param stage 阶段名（vector/bm25/fused/rerank）
     * @param rank 该阶段名次（1 起）
     * @param score 该阶段分数（量纲随阶段不同）
     */
    public record StageRank(String stage, int rank, double score) {
    }

    /** 契约 RetrievalHit（第二轮新增 stages：分阶段位次与分数，R2-D1）。 */
    public record HitPayload(int rank, ChunkPayload chunk, double score, boolean passedThreshold,
                             List<StageRank> stages, String rankChangedReason) {
    }

    /** 契约 DebugTimings（毫秒）。 */
    public record Timings(long embedMs, long searchMs, long totalMs) {
    }

    /** 契约 context：实际送入模型的上下文全文。 */
    public record ContextPayload(String text, int charCount, List<String> chunkIds) {
    }

    /**
     * 契约 DebugIssue。
     * type 取值：NO_HITS / ALL_BELOW_THRESHOLD / CONTEXT_TRUNCATED / RERANK_DEGRADED。
     */
    public record Issue(String type, String message) {
    }

    /**
     * 契约 AnswerabilityDecision（R4）：证据充分性判定结果。
     *
     * @param answerable   证据是否足以完整回答
     * @param decisionType 决策类型（AnswerabilityDecisionType 枚举名）
     * @param confidence   Judge 置信度；未调 Judge 为 null
     * @param reason       决策原因；未调 Judge 时是门控说明（不伪造 Judge reason）
     * @param judgeInvoked 是否实际调用了 Judge
     * @param degraded     Judge 是否失败降级
     * @param latencyMs    判定耗时（毫秒）
     */
    public record AnswerabilityDecisionPayload(boolean answerable, String decisionType,
                                               Double confidence, String reason,
                                               boolean judgeInvoked, boolean degraded,
                                               long latencyMs) {
    }

    public record DebugRetrievalResult(EffectiveConfig effectiveConfig, List<HitPayload> hits,
                                       Timings timings, ContextPayload context,
                                       AnswerabilityDecisionPayload answerability,
                                       List<Issue> issues) {
    }
}
