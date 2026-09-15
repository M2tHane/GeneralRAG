package com.rag.eval;

import java.util.List;

import com.rag.domain.entity.EvalDatasetVersionEntity;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 评测数据集版本查询补充（Task 6 新增接口，与 storage/repository/EvalDatasetVersionRepository
 * 共享同一实体/表）：补充按 datasetId 列全部版本的能力（运行列表按数据集过滤时
 * 需要 versionId 集合）。原仓储为既有共享文件（Task 2 写边界），本任务不改。
 */
public interface EvalVersionQueryRepository extends JpaRepository<EvalDatasetVersionEntity, String> {

    List<EvalDatasetVersionEntity> findByDatasetId(String datasetId);
}
