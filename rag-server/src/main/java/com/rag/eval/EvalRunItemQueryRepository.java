package com.rag.eval;

import java.util.Optional;

import com.rag.domain.entity.EvalRunItemEntity;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 评测运行明细查询补充（Task 6 新增接口，与 storage/repository/EvalRunItemRepository
 * 共享同一实体/表）：按 (runId, seq) 定位明细行（执行器逐题更新）。原仓储为既有
 * 共享文件（Task 2 写边界），本任务不改。
 */
public interface EvalRunItemQueryRepository extends JpaRepository<EvalRunItemEntity, String> {

    Optional<EvalRunItemEntity> findByRunIdAndSeq(String runId, int seq);

    long countByRunId(String runId);
}
