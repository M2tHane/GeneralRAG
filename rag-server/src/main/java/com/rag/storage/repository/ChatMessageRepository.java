package com.rag.storage.repository;

import java.util.List;

import com.rag.domain.entity.ChatMessageEntity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 消息仓储。idx_chat_message_session_created(session_id, created_at) 支撑两类读：
 * 正序分页（会话详情）与倒序取最近 N 条（QA-3 对话历史注入 prompt，仅连贯性非证据）。
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, String> {

    /** 消息正序分页（created_at 毫秒精度保证顺序）。 */
    List<ChatMessageEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId, Pageable pageable);

    /** 倒序取最近 N 条历史（页大小即 N），调用方自行 reverse。 */
    List<ChatMessageEntity> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);

    /** DeletionSummary.messagesDeleted 统计。 */
    long countBySessionId(String sessionId);
}
