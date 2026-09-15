package com.rag.storage.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.rag.domain.entity.IngestionTaskEntity;
import com.rag.domain.enums.PipelineStage;
import com.rag.domain.enums.TaskStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 入库任务仓储（状态机唯一载体，路线 §3.1）。
 *
 * <p>CAS 认领：单实例下兜底防双跑；多实例扩容前必须换队列（路线 §10 取舍 2）。
 * bulk update 绕过持久化上下文，{@code clearAutomatically = true} 清一级缓存，
 * 避免后续读读到陈旧实体。</p>
 */
public interface IngestionTaskRepository extends JpaRepository<IngestionTaskEntity, String> {

    Optional<IngestionTaskEntity> findByDocumentId(String documentId);

    /** 启动恢复扫描：QUEUED/RUNNING → 复位 QUEUED（RUNNING 记 attempt+1）。 */
    List<IngestionTaskEntity> findByStatusIn(Collection<TaskStatus> statuses);

    /**
     * 状态 CAS 迁移：仅当当前状态等于 fromStatus 时更新为 toStatus，并补记
     * 首次 RUNNING 时间。返回受影响行数（1=认领成功，0=已被他处认领/状态已变）。
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update IngestionTaskEntity t
               set t.status = :toStatus, t.startedAt = coalesce(t.startedAt, current_timestamp)
             where t.id = :id and t.status = :fromStatus
            """)
    int casUpdateStatus(@Param("id") String id,
                        @Param("fromStatus") TaskStatus fromStatus,
                        @Param("toStatus") TaskStatus toStatus);

    /** 认领任务：仅 QUEUED→RUNNING 成功一次。 */
    default boolean claimIfQueued(String id) {
        return casUpdateStatus(id, TaskStatus.QUEUED, TaskStatus.RUNNING) == 1;
    }

    /** 阶段推进：每阶段先落 task.stage 再执行（路线 §3.1）。 */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update IngestionTaskEntity t set t.stage = :stage where t.id = :id")
    int updateStage(@Param("id") String id, @Param("stage") PipelineStage stage);
}
