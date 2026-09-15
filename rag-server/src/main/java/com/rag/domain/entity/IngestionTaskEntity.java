package com.rag.domain.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.rag.domain.enums.PipelineStage;
import com.rag.domain.enums.TaskStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * 入库流水线任务，状态机唯一载体（V1__init.sql 表 3：ingestion_task）。
 *
 * <p>每文档仅保留一个当前任务（document_id UNIQUE，无任务历史）。
 * QUEUED→RUNNING 经 CAS 认领（IngestionTaskRepository#claimIfQueued）防双跑；
 * 启动恢复将 QUEUED/RUNNING 复位为 QUEUED（RUNNING 记 attempt+1）。</p>
 */
@Entity
@Table(name = "ingestion_task")
public class IngestionTaskEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @Column(name = "document_id", nullable = false, updatable = false, columnDefinition = "char(36)", unique = true)
    private String documentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TaskStatus status;

    /** 当前阶段，每阶段完成即推进（先落 task.stage 再执行，见路线 §3.1）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 16)
    private PipelineStage stage;

    /** 执行尝试次数，从 1 起。 */
    @Column(name = "attempt", nullable = false)
    private int attempt = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_stage", length = 16)
    private PipelineStage failureStage;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(3)")
    private LocalDateTime createdAt;

    /** 首次进入 RUNNING 的时间；未开始为 NULL。 */
    @Column(name = "started_at", columnDefinition = "datetime(3)")
    private LocalDateTime startedAt;

    /** 终态（COMPLETED/FAILED）时间。 */
    @Column(name = "finished_at", columnDefinition = "datetime(3)")
    private LocalDateTime finishedAt;

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

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public PipelineStage getStage() {
        return stage;
    }

    public void setStage(PipelineStage stage) {
        this.stage = stage;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public PipelineStage getFailureStage() {
        return failureStage;
    }

    public void setFailureStage(PipelineStage failureStage) {
        this.failureStage = failureStage;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
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
}
