package com.rag.domain.entity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.rag.domain.enums.MessageStatus;
import com.rag.domain.enums.SessionRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * 问答消息（V1__init.sql 表 6：chat_message）。
 *
 * <p>用户消息先落库（QA-8 输入不丢），助手消息终态时落库。
 * citations JSON 与契约 Citation[] 同构；仅来自该回答实际检索结果，
 * 资料不足时为空数组（QA-6 无伪造引用），故 NOT NULL。
 * 消息不可编辑，无 updated_at。</p>
 */
@Entity
@Table(name = "chat_message")
public class ChatMessageEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @Column(name = "session_id", nullable = false, updatable = false, columnDefinition = "char(36)")
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private SessionRole role;

    /** 消息正文（用户提问上限 2000 字，回答按模型输出）。 */
    @Column(name = "content", nullable = false, columnDefinition = "mediumtext")
    private String content;

    /** 引用列表，结构同契约 Citation[]；USER 消息与资料不足时为 []。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citations", nullable = false, columnDefinition = "json")
    private List<Map<String, Object>> citations;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MessageStatus status;

    /** 失败错误码（契约 Error.code 枚举，如 MODEL_TIMEOUT）；仅 status=ERROR 非空。 */
    @Column(name = "error_code", length = 64)
    private String errorCode;

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

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public SessionRole getRole() {
        return role;
    }

    public void setRole(SessionRole role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<Map<String, Object>> getCitations() {
        return citations;
    }

    public void setCitations(List<Map<String, Object>> citations) {
        this.citations = citations;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public void setStatus(MessageStatus status) {
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
