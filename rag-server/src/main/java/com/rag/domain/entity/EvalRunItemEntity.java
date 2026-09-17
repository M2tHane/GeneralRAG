package com.rag.domain.entity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.rag.answerability.AnswerabilityDecisionType;
import com.rag.domain.enums.ReviewTag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * 评测运行明细，每样本一行的执行快照（V1__init.sql 表 11：eval_run_item）。
 *
 * <p>retrieved JSON 与契约 EvalHit[]{rank,chunkId,docName,titlePath,page,score}
 * 同构（不可变快照）；citations 与契约 Citation[] 同构。hit 可空
 * （NULL=未判定）；review_tag/review_note 可空=未人工标注。</p>
 */
@Entity
@Table(name = "eval_run_item")
public class EvalRunItemEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @Column(name = "run_id", nullable = false, updatable = false, columnDefinition = "char(36)")
    private String runId;

    /** 样本在本次运行内的序号（对应 eval_dataset_item.seq）。 */
    @Column(name = "seq", nullable = false)
    private int seq;

    /** 评测问题（运行时从样本快照复制，运行记录自包含）。 */
    @Column(name = "question", nullable = false, length = 2000)
    private String question;

    /** 本次运行检索命中快照（EvalHit[]），至少为 []。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retrieved", nullable = false, columnDefinition = "json")
    private List<Map<String, Object>> retrieved;

    /** 模型实际回答文本；运行中断/该题未执行时为 NULL。 */
    @Column(name = "generated_answer", columnDefinition = "text")
    private String generatedAnswer;

    /** 回答引用（Citation[]）；未生成回答时为 NULL。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citations", columnDefinition = "json")
    private List<Map<String, Object>> citations;

    /** 样本级 Hit@K（K=该次运行 topK）；NULL=尚未判定。 */
    @Column(name = "hit")
    private Boolean hit;

    /** 该题端到端耗时（毫秒）；未执行为 NULL。 */
    @Column(name = "latency_ms")
    private Integer latencyMs;

    /** R2-L1：该题检索耗时（毫秒，含向量化/通道/融合/重排）；未执行为 NULL。 */
    @Column(name = "retrieval_ms")
    private Integer retrievalMs;

    /** R2-L1：该题生成耗时（毫秒）；拒答未调用模型时为约 0。 */
    @Column(name = "generation_ms")
    private Integer generationMs;

    /** R4：系统是否拒答（判定落库，此前只能靠回答文案反推）。NULL=未执行或旧数据。 */
    @Column(name = "refused")
    private Boolean refused;

    /** R4：Answerability 决策类型（枚举名）；NULL=未执行或旧数据。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "answerability_decision_type", length = 40)
    private AnswerabilityDecisionType answerabilityDecisionType;

    /** R4：Judge 置信度；未调 Judge 为 NULL。 */
    @Column(name = "answerability_confidence")
    private Double answerabilityConfidence;

    /** R4：决策原因简述（截断 500）。 */
    @Column(name = "answerability_reason", length = 500)
    private String answerabilityReason;

    /** R4：Judge 是否失败降级；未调 Judge 为 false。 */
    @Column(name = "answerability_degraded")
    private Boolean answerabilityDegraded;

    /** R4：判定耗时（毫秒）。 */
    @Column(name = "answerability_latency_ms")
    private Integer answerabilityLatencyMs;

    /** R6-C：本次实际使用的检索查询（rewrite 后）；未执行/旧行为为 NULL。 */
    @Column(name = "retrieval_query", length = 2000)
    private String retrievalQuery;

    /** R6-C：检索查询是否发生 history-aware 改写；未执行/旧行为为 NULL。 */
    @Column(name = "query_rewritten")
    private Boolean queryRewritten;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_tag", length = 20)
    private ReviewTag reviewTag;

    @Column(name = "review_note", length = 1000)
    private String reviewNote;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(3)")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(3)")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public List<Map<String, Object>> getRetrieved() {
        return retrieved;
    }

    public void setRetrieved(List<Map<String, Object>> retrieved) {
        this.retrieved = retrieved;
    }

    public String getGeneratedAnswer() {
        return generatedAnswer;
    }

    public void setGeneratedAnswer(String generatedAnswer) {
        this.generatedAnswer = generatedAnswer;
    }

    public List<Map<String, Object>> getCitations() {
        return citations;
    }

    public void setCitations(List<Map<String, Object>> citations) {
        this.citations = citations;
    }

    public Boolean getHit() {
        return hit;
    }

    public void setHit(Boolean hit) {
        this.hit = hit;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Integer getRetrievalMs() {
        return retrievalMs;
    }

    public void setRetrievalMs(Integer retrievalMs) {
        this.retrievalMs = retrievalMs;
    }

    public Integer getGenerationMs() {
        return generationMs;
    }

    public void setGenerationMs(Integer generationMs) {
        this.generationMs = generationMs;
    }

    public Boolean getRefused() {
        return refused;
    }

    public void setRefused(Boolean refused) {
        this.refused = refused;
    }

    public AnswerabilityDecisionType getAnswerabilityDecisionType() {
        return answerabilityDecisionType;
    }

    public void setAnswerabilityDecisionType(AnswerabilityDecisionType answerabilityDecisionType) {
        this.answerabilityDecisionType = answerabilityDecisionType;
    }

    public Double getAnswerabilityConfidence() {
        return answerabilityConfidence;
    }

    public void setAnswerabilityConfidence(Double answerabilityConfidence) {
        this.answerabilityConfidence = answerabilityConfidence;
    }

    public String getAnswerabilityReason() {
        return answerabilityReason;
    }

    public void setAnswerabilityReason(String answerabilityReason) {
        this.answerabilityReason = answerabilityReason;
    }

    public Boolean getAnswerabilityDegraded() {
        return answerabilityDegraded;
    }

    public void setAnswerabilityDegraded(Boolean answerabilityDegraded) {
        this.answerabilityDegraded = answerabilityDegraded;
    }

    public Integer getAnswerabilityLatencyMs() {
        return answerabilityLatencyMs;
    }

    public void setAnswerabilityLatencyMs(Integer answerabilityLatencyMs) {
        this.answerabilityLatencyMs = answerabilityLatencyMs;
    }

    public String getRetrievalQuery() {
        return retrievalQuery;
    }

    public void setRetrievalQuery(String retrievalQuery) {
        this.retrievalQuery = retrievalQuery;
    }

    public Boolean getQueryRewritten() {
        return queryRewritten;
    }

    public void setQueryRewritten(Boolean queryRewritten) {
        this.queryRewritten = queryRewritten;
    }

    public ReviewTag getReviewTag() {
        return reviewTag;
    }

    public void setReviewTag(ReviewTag reviewTag) {
        this.reviewTag = reviewTag;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public void setReviewNote(String reviewNote) {
        this.reviewNote = reviewNote;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
