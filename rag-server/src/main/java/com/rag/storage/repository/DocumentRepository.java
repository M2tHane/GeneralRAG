package com.rag.storage.repository;

import java.util.List;
import java.util.Optional;

import com.rag.domain.entity.DocumentEntity;
import com.rag.domain.enums.DocumentStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 文档仓储。
 *
 * <p>去重（KB-8）：UNIQUE(kb_id, content_sha256) 由 V1__init.sql 建立，
 * 上传同步阶段先以 {@link #existsByKbIdAndContentSha256} 预判 → 409
 * DUPLICATE_DOCUMENT；并发窗口内仍可能命中约束，由 service 层按
 * DataIntegrityViolationException 兜底转换。</p>
 */
public interface DocumentRepository extends JpaRepository<DocumentEntity, String> {

    boolean existsByKbIdAndContentSha256(String kbId, String contentSha256);

    /** 文档列表：状态筛选（可空）+ 分页。 */
    Page<DocumentEntity> findByKbId(String kbId, Pageable pageable);

    Page<DocumentEntity> findByKbIdAndStatus(String kbId, DocumentStatus status, Pageable pageable);

    /** DeletionSummary.documentsDeleted 统计。 */
    long countByKbId(String kbId);

    Optional<DocumentEntity> findByKbIdAndContentSha256(String kbId, String contentSha256);

    // ---- R3-P3 多版本 ----

    /** 版本组全量（按版本号升序）。 */
    List<DocumentEntity> findByRootIdOrderByVersionNoAsc(String rootId);

    /** 组内激活版本（至多一个）。 */
    Optional<DocumentEntity> findByRootIdAndActiveTrue(String rootId);

    /** 组内最新版本号（建新版本时 +1）。 */
    Optional<DocumentEntity> findFirstByRootIdOrderByVersionNoDesc(String rootId);

    /** KB 内同名文档（判断「同名再上传」是否落入既有版本组；同名可能跨组仅当历史数据手工改过名，实现上取第一个）。 */
    List<DocumentEntity> findByKbIdAndName(String kbId, String name);

    /**
     * 定向更新状态字段（R3-P3）：流水线线程持有的实体快照可能滞后于并发发生的
     * 版本激活切换（active 位被其它事务改过），全字段 merge 会把旧 active 位写回并
     * 撞 uk_document_active（真机踩中）——状态推进必须用定向 UPDATE，不回写 active。
     */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "update DocumentEntity d set d.status = :status, d.currentStage = :stage, "
                    + "d.chunkCount = :chunkCount where d.id = :id")
    int updateStatusFields(@org.springframework.data.repository.query.Param("id") String id,
                           com.rag.domain.enums.DocumentStatus status,
                           com.rag.domain.enums.PipelineStage stage,
                           @org.springframework.data.repository.query.Param("chunkCount") int chunkCount);

    /**
     * 定向写入解析路由元数据（R6-D）：与 {@link #updateStatusFields} 同理，
     * 避免全字段 merge 把 worker 快照里的旧 active 位写回撞唯一键。
     * 仅 AUTO 解析路径调用；手动模式传 null 不调用、不清除历史值。
     */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "update DocumentEntity d set d.parseMetadata = :parseMetadata where d.id = :id")
    int updateParseMetadata(@org.springframework.data.repository.query.Param("id") String id,
                            @org.springframework.data.repository.query.Param("parseMetadata")
                            java.util.Map<String, Object> parseMetadata);
}
