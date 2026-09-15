package com.rag.domain.entity;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.rag.domain.enums.CleanupScope;
import com.rag.domain.enums.CleanupStatus;
import com.rag.domain.enums.StoreType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * 跨存储清理补偿任务（V1__init.sql 表 4：cleanup_task）。
 *
 * <p>ref_id 为多态引用（document.id 或 knowledge_base.id，由 scope 区分），
 * 刻意无外键——主档删除先于本表 DONE，清理任务必须独立于主档存活，
 * 由 @Scheduled 每 30s 扫 PENDING/FAILED 重试直至 DONE（路线 §3.3）。</p>
 */
@Entity
@Table(name = "cleanup_task")
public class CleanupTaskEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 16)
    private CleanupScope scope;

    @Column(name = "ref_id", nullable = false, updatable = false, columnDefinition = "char(36)")
    private String refId;

    @Enumerated(EnumType.STRING)
    @Column(name = "store", nullable = false, length = 16)
    private StoreType store;

    /** 清理参数（对象键前缀/doc_id/kb_id/chunk 估计数等）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "json")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CleanupStatus status;

    /** 已执行次数，每次尝试 +1（无上限，仅记录）。 */
    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    /** 最近一次失败原因；FAILED 后非空，重试成功即清空。 */
    @Column(name = "last_error", length = 512)
    private String lastError;

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

    public CleanupScope getScope() {
        return scope;
    }

    public void setScope(CleanupScope scope) {
        this.scope = scope;
    }

    public String getRefId() {
        return refId;
    }

    public void setRefId(String refId) {
        this.refId = refId;
    }

    public StoreType getStore() {
        return store;
    }

    public void setStore(StoreType store) {
        this.store = store;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    public CleanupStatus getStatus() {
        return status;
    }

    public void setStatus(CleanupStatus status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
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
