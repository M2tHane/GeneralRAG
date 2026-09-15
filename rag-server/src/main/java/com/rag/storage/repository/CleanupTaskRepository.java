package com.rag.storage.repository;

import java.util.Collection;
import java.util.List;

import com.rag.domain.entity.CleanupTaskEntity;
import com.rag.domain.enums.CleanupStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 跨存储清理补偿任务仓储（路线 §3.3）。
 * @Scheduled 调度器按 status ∈ {PENDING, FAILED} 扫描，按 updated_at 陈旧度排序。
 */
public interface CleanupTaskRepository extends JpaRepository<CleanupTaskEntity, String> {

    List<CleanupTaskEntity> findByStatusInOrderByUpdatedAtAsc(Collection<CleanupStatus> statuses);

    /** 每次尝试 +1（无上限，仅记录）。bulk update 不经持久化上下文。 */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update CleanupTaskEntity c set c.attempts = c.attempts + 1 where c.id = :id")
    int incrementAttempts(@Param("id") String id);
}
