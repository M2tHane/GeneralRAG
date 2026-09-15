package com.rag.storage.repository;

import com.rag.domain.entity.EvalDatasetEntity;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 评测数据集仓储。name 唯一：重复导入同名数据集 → 409 DUPLICATE_EVAL_DATASET。
 */
public interface EvalDatasetRepository extends JpaRepository<EvalDatasetEntity, String> {

    boolean existsByName(String name);
}
