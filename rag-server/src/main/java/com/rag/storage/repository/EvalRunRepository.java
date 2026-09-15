package com.rag.storage.repository;

import com.rag.domain.entity.EvalRunEntity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评测运行仓储。运行列表按创建时间倒序分页；可按知识库过滤。
 */
public interface EvalRunRepository extends JpaRepository<EvalRunEntity, String> {

    Page<EvalRunEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<EvalRunEntity> findByKbIdOrderByCreatedAtDesc(String kbId, Pageable pageable);

    /** 按数据集版本列运行历史（idx_eval_run_version）。 */
    Page<EvalRunEntity> findByDatasetVersionIdOrderByCreatedAtDesc(String datasetVersionId, Pageable pageable);

    /** M6-①：RUNNING 行幂等认领（status 条件更新；1=认领成功，0=已被他人收尾）。 */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update EvalRunEntity r set r.finishedAt = CURRENT_TIMESTAMP where r.id = :id and r.status = 'RUNNING' and r.finishedAt is null")
    int claimIfRunning(@Param("id") String id);

    /** M6-①：启动恢复——重启后仍 RUNNING 的运行复位 FAILED。 */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update EvalRunEntity r set r.status = 'FAILED', r.failureReason = '服务重启中断', r.finishedAt = CURRENT_TIMESTAMP where r.status = 'RUNNING'")
    int failAllRunningOnStartup();
}