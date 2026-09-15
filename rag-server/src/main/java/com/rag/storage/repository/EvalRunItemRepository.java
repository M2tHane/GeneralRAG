package com.rag.storage.repository;

import java.util.List;

import com.rag.domain.entity.EvalRunItemEntity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 评测运行明细仓储。idx_eval_run_item_run_seq(run_id, seq) 支撑分页明细与全量执行读取。
 */
public interface EvalRunItemRepository extends JpaRepository<EvalRunItemEntity, String> {

    /** 运行明细分页（按 seq 正序，逐题对照）。 */
    Page<EvalRunItemEntity> findByRunIdOrderBySeqAsc(String runId, Pageable pageable);

    /** 全量明细（EvalRunExecutor 执行与指标聚合）。 */
    List<EvalRunItemEntity> findAllByRunIdOrderBySeqAsc(String runId);
}
