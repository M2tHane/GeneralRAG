package com.rag.storage.repository;

import java.util.List;

import com.rag.domain.entity.EvalDatasetItemEntity;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 评测样本仓储（随版本不可变）。
 */
public interface EvalDatasetItemRepository extends JpaRepository<EvalDatasetItemEntity, String> {

    /** 按版本顺序取样本（展示与评测执行读取共用，EV-1）。 */
    List<EvalDatasetItemEntity> findByVersionIdOrderBySeqAsc(String versionId);

    /** 版本样本条数（导入统计 / EvalRunSummary.itemCount）。 */
    long countByVersionId(String versionId);
}
