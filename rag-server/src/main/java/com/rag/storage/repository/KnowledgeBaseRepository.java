package com.rag.storage.repository;

import com.rag.domain.entity.KnowledgeBaseEntity;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 知识库仓储。name 唯一约束在 DB 层，重名检测由 service 层配合 existsByName 完成。
 */
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, String> {

    boolean existsByName(String name);
}
