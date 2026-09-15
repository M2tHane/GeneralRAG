package com.rag.domain.entity;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.rag.domain.enums.EvalRunStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * 评测运行（V1__init.sql 表 10：eval_run）。
 *
 * <p>config_snapshot JSON（EV-4）：chatModel/embeddingModel/embeddingDimensions/
 * topK/minScore/chunkStrategy/maxChunkChars 等按实际执行值记录，保证两次运行
 * 可区分、可复现。kb_id 弱引用刻意无外键（KB 删除后历史运行保留）；
 * dataset_version_id 外键 RESTRICT 保护评测留痕。</p>
 */
@Entity
@Table(name = "eval_run")
public class EvalRunEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @Column(name = "dataset_version_id", nullable = false, updatable = false, columnDefinition = "char(36)")
    private String datasetVersionId;

    /** 评测目标知识库 ID；弱引用，KB 删除后历史运行保留。 */
    @Column(name = "kb_id", nullable = false, updatable = false, columnDefinition = "char(36)")
    private String kbId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private EvalRunStatus status;

    /** 运行配置快照（EV-4）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_snapshot", nullable = false, columnDefinition = "json")
    private Map<String, Object> configSnapshot;

    /** 汇总指标 {hitAt1,hitAt3,hitAt5,avgLatencyMs,itemCount}；仅 COMPLETED 后非空。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics", columnDefinition = "json")
    private Map<String, Object> metrics;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    /** 运行开始时间（= 受理时间）。 */
    @CreationTimestamp
    @Column(name = "started_at", nullable = false, updatable = false, columnDefinition = "datetime(3)")
    private LocalDateTime startedAt;

    @Column(name = "finished_at", columnDefinition = "datetime(3)")
    private LocalDateTime finishedAt;

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

    public String getDatasetVersionId() {
        return datasetVersionId;
    }

    public void setDatasetVersionId(String datasetVersionId) {
        this.datasetVersionId = datasetVersionId;
    }

    public String getKbId() {
        return kbId;
    }

    public void setKbId(String kbId) {
        this.kbId = kbId;
    }

    public EvalRunStatus getStatus() {
        return status;
    }

    public void setStatus(EvalRunStatus status) {
        this.status = status;
    }

    public Map<String, Object> getConfigSnapshot() {
        return configSnapshot;
    }

    public void setConfigSnapshot(Map<String, Object> configSnapshot) {
        this.configSnapshot = configSnapshot;
    }

    public Map<String, Object> getMetrics() {
        return metrics;
    }

    public void setMetrics(Map<String, Object> metrics) {
        this.metrics = metrics;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
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
