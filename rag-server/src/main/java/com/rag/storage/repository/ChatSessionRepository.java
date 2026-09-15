package com.rag.storage.repository;

import com.rag.domain.entity.ChatSessionEntity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 会话仓储。会话列表按知识库过滤、按最近更新排序。
 */
public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, String> {

    /** DeletionSummary.sessionsDeleted 统计。 */
    long countByKbId(String kbId);

    /** 会话列表页（Master–Detail 左栏）。 */
    Page<ChatSessionEntity> findByKbIdOrderByUpdatedAtDesc(String kbId, Pageable pageable);
}
