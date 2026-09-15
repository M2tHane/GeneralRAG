package com.rag.storage.repository;

import java.util.Optional;

import com.rag.domain.entity.EvalDatasetVersionEntity;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 评测数据集版本仓储。UNIQUE(dataset_id, version_no) 由 V1__init.sql 建立；
 * 导入时取当前最大版本号 +1 产生新版本（并发导入靠唯一约束兜底）。
 */
public interface EvalDatasetVersionRepository extends JpaRepository<EvalDatasetVersionEntity, String> {

    /** 最新版本（EvalRunCreate.versionId 缺省时使用；EvalDataset.latestVersion 展示）。 */
    Optional<EvalDatasetVersionEntity> findFirstByDatasetIdOrderByVersionNoDesc(String datasetId);
}
