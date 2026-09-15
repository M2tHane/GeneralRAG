package com.rag.eval;

import java.util.Optional;

import com.rag.domain.entity.EvalDatasetEntity;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 评测数据集查询补充（Task 6 新增接口，与 storage/repository/EvalDatasetRepository
 * 共享同一实体/表）：补充按 name 读取（同名导入追加版本需要读实体，原仓储只有
 * existsByName 且为既有共享文件，本任务不改）。
 */
public interface EvalDatasetQueryRepository extends JpaRepository<EvalDatasetEntity, String> {

    Optional<EvalDatasetEntity> findByName(String name);
}
