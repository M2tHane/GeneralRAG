package com.rag.domain.entity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.rag.domain.enums.EvalCategory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * 评测样本，随版本不可变（V1__init.sql 表 9：eval_dataset_item）。
 *
 * <p>evidence JSON 与契约 EvidenceRef[]{docName,titlePath,page,snippet} 同构，
 * 是 Hit@K 判定（EV-3）与人工标注对照依据；OUT_OF_KB 样本 evidence 为 []。
 * answerable=false 的样本预期拒答（OUT_OF_KB）。</p>
 */
@Entity
@Table(name = "eval_dataset_item")
public class EvalDatasetItemEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @Column(name = "version_id", nullable = false, updatable = false, columnDefinition = "char(36)")
    private String versionId;

    /** 样本在版本内的序号（与导入记录一致）。 */
    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "question", nullable = false, length = 2000)
    private String question;

    /** 参考答案（人工编写，可为空；仅人工对照，不参与自动评分）。 */
    @Column(name = "reference_answer", columnDefinition = "text")
    private String referenceAnswer;

    /** 参考证据，结构同契约 EvidenceRef[]；OUT_OF_KB 为 []，可为 NULL。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", columnDefinition = "json")
    private List<Map<String, Object>> evidence;

    /**
     * 可选对话历史（R4.1：FOLLOW_UP 口径修正），结构 = [{role,content}] 时间正序。
     * 仅用于解析当前问题的指代（生成 prompt 历史区），不构成回答证据；NULL=无历史。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "history", columnDefinition = "json")
    private List<Map<String, Object>> history;

    /** 是否应可回答：true=是（默认），false=知识库外问题（预期拒答）。 */
    @Column(name = "answerable", nullable = false)
    private boolean answerable = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private EvalCategory category;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(3)")
    private LocalDateTime createdAt;

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

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = versionId;
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

    public String getReferenceAnswer() {
        return referenceAnswer;
    }

    public void setReferenceAnswer(String referenceAnswer) {
        this.referenceAnswer = referenceAnswer;
    }

    public List<Map<String, Object>> getEvidence() {
        return evidence;
    }

    public void setEvidence(List<Map<String, Object>> evidence) {
        this.evidence = evidence;
    }

    public List<Map<String, Object>> getHistory() {
        return history;
    }

    public void setHistory(List<Map<String, Object>> history) {
        this.history = history;
    }

    public boolean isAnswerable() {
        return answerable;
    }

    public void setAnswerable(boolean answerable) {
        this.answerable = answerable;
    }

    public EvalCategory getCategory() {
        return category;
    }

    public void setCategory(EvalCategory category) {
        this.category = category;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
