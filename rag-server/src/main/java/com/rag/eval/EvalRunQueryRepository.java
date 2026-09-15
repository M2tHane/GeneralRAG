package com.rag.eval;

import java.util.Collection;

import com.rag.domain.entity.EvalRunEntity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 评测运行查询补充（Task 6 新增接口，与 storage/repository/EvalRunRepository 共享
 * 同一实体/表）：按数据集版本集合过滤运行分页。原仓储为既有共享文件（Task 2 写
 * 边界），本任务不改。
 */
public interface EvalRunQueryRepository extends JpaRepository<EvalRunEntity, String> {

    Page<EvalRunEntity> findByDatasetVersionIdInOrderByCreatedAtDesc(Collection<String> versionIds,
                                                                     Pageable pageable);
}
