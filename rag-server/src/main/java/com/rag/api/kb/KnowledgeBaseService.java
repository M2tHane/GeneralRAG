package com.rag.api.kb;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rag.api.dto.DeletionSummary;
import com.rag.api.dto.KnowledgeBaseCreate;
import com.rag.api.dto.KnowledgeBase;
import com.rag.api.dto.KnowledgeBaseUpdate;
import com.rag.domain.entity.CleanupTaskEntity;
import com.rag.domain.entity.ChatMessageEntity;
import com.rag.domain.entity.ChatSessionEntity;
import com.rag.domain.entity.DocumentEntity;
import com.rag.domain.entity.IngestionTaskEntity;
import com.rag.domain.entity.KnowledgeBaseEntity;
import com.rag.domain.enums.CleanupScope;
import com.rag.domain.enums.CleanupStatus;
import com.rag.domain.enums.StoreType;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import com.rag.ingestion.CleanupService;
import com.rag.storage.repository.ChatMessageRepository;
import com.rag.storage.repository.ChatSessionRepository;
import com.rag.storage.repository.CleanupTaskRepository;
import com.rag.storage.repository.DocumentRepository;
import com.rag.storage.repository.IngestionTaskRepository;
import com.rag.storage.repository.KnowledgeBaseRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库应用服务（API 层）：CRUD 与删除编排（docs/03-技术路线.md §3.3）。
 *
 * <p>删除语义：MySQL 事务内删除 KB 行，document/ingestion_task/chat_session/
 * chat_message 依靠外键 ON DELETE CASCADE 级联移除（V1__init.sql，检索边界立即收口）；
 * 同时在同一事务内写 2 条 cleanup_task（ES delete_by_query(kb_id)、MinIO removePrefix）
 * ——补偿任务刻意无外键、独立于主档存活。事务提交后触发 CleanupService 立即处理一轮，
 * 加速 ES/MinIO 收敛；失败也不影响已受理的删除（@Scheduled 30s 兜底重扫）。</p>
 */
@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository kbRepository;
    private final DocumentRepository documentRepository;
    private final IngestionTaskRepository taskRepository;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final CleanupTaskRepository cleanupTaskRepository;
    private final CleanupService cleanupService;

    public KnowledgeBaseService(KnowledgeBaseRepository kbRepository,
                                DocumentRepository documentRepository,
                                IngestionTaskRepository taskRepository,
                                ChatSessionRepository sessionRepository,
                                ChatMessageRepository messageRepository,
                                CleanupTaskRepository cleanupTaskRepository,
                                CleanupService cleanupService) {
        this.kbRepository = kbRepository;
        this.documentRepository = documentRepository;
        this.taskRepository = taskRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.cleanupTaskRepository = cleanupTaskRepository;
        this.cleanupService = cleanupService;
    }

    /** 全量列表（第一版知识库数量小，不分页）。统计含 documentCount 与 chunkCount 聚合。 */
    @Transactional(readOnly = true)
    public List<KnowledgeBase> list() {
        return kbRepository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public KnowledgeBase get(UUID kbId) {
        return toDto(requireKb(kbId));
    }

    @Transactional
    public KnowledgeBase create(KnowledgeBaseCreate request) {
        if (kbRepository.existsByName(request.name())) {
            throw nameDuplicated(request.name());
        }
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setName(request.name());
        entity.setDescription(request.description());
        // 并发窗口兜底：唯一约束命中 → 统一转为 KB_NAME_DUPLICATED
        try {
            return toDto(kbRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException e) {
            throw nameDuplicated(request.name());
        }
    }

    /** 部分更新：null 字段保持不变（契约 KnowledgeBaseUpdate.minProperties=1）。 */
    @Transactional
    public KnowledgeBase update(UUID kbId, KnowledgeBaseUpdate request) {
        KnowledgeBaseEntity entity = requireKb(kbId);
        if (request.name() != null && !request.name().equals(entity.getName())
                && kbRepository.existsByName(request.name())) {
            throw nameDuplicated(request.name());
        }
        if (request.name() != null) {
            entity.setName(request.name());
        }
        if (request.description() != null) {
            entity.setDescription(request.description());
        }
        try {
            return toDto(kbRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException e) {
            throw nameDuplicated(entity.getName());
        }
    }

    /**
     * 删除（confirm 语义由 controller 把关）：级联删除文档/任务/会话/消息（DB 级联），
     * 写 ES + MinIO 两条补偿任务后提交，随即触发一轮清理处理。返回 MySQL 侧即时统计。
     */
    @Transactional
    public DeletionSummary delete(UUID kbId) {
        KnowledgeBaseEntity kb = requireKb(kbId);
        long documentCount = documentRepository.countByKbId(kbId.toString());
        long sessionCount = sessionRepository.countByKbId(kbId.toString());

        // ES 侧最近已知分块数：主档删除前聚合（可近似，契约 chunksDeleted 语义）
        long chunksDeleted = 0;
        for (DocumentEntity doc : documentRepository.findByKbId(kbId.toString(),
                org.springframework.data.domain.Pageable.unpaged())) {
            chunksDeleted += doc.getChunkCount();
        }

        kbRepository.delete(kb);

        // 补偿任务与主档删除同事务写入；cleanup_task 无外键，独立于 KB 行存活
        CleanupTaskEntity esTask = newCleanupTask(kbId, CleanupScope.KNOWLEDGE_BASE,
                StoreType.ELASTICSEARCH, Map.of("kbId", kbId.toString()));
        CleanupTaskEntity minioTask = newCleanupTask(kbId, CleanupScope.KNOWLEDGE_BASE,
                StoreType.MINIO, Map.of("kbId", kbId.toString()));
        cleanupTaskRepository.saveAll(List.of(esTask, minioTask));

        // 事务提交后再触发清理（避免未提交行被扫描到）；失败由 @Scheduled 兜底
        List<CleanupTaskEntity> accepted = List.of(esTask, minioTask);
        org.springframework.transaction.support.TransactionSynchronizationManager
                .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            cleanupService.processPending();
                        } catch (RuntimeException e) {
                            // M7：补偿任务已持久化，@Scheduled 兜底
                            org.slf4j.LoggerFactory.getLogger(KnowledgeBaseService.class)
                                    .error("afterCommit 清理加速执行失败（@Scheduled 兜底）", e);
                        }
                    }
                });

        long messageCount = countMessages(kbId.toString());
        return new DeletionSummary(kbId.toString(), documentCount, chunksDeleted,
                sessionCount, messageCount, accepted.size());
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private KnowledgeBaseEntity requireKb(UUID kbId) {
        return kbRepository.findById(kbId.toString())
                .orElseThrow(() -> new DomainException(ErrorCode.KB_NOT_FOUND));
    }

    private KnowledgeBase toDto(KnowledgeBaseEntity entity) {
        String id = entity.getId();
        long documentCount = documentRepository.countByKbId(id);
        long chunkCount = 0;
        for (DocumentEntity doc : documentRepository.findByKbId(id,
                org.springframework.data.domain.Pageable.unpaged())) {
            chunkCount += doc.getChunkCount();
        }
        return new KnowledgeBase(id, entity.getName(), entity.getDescription(),
                documentCount, chunkCount, entity.getCreatedAt(), entity.getUpdatedAt());
    }
    /** 会话消息总数：消息从属于会话（session_id FK），需按 KB 下全部会话聚合。 */
    private long countMessages(String kbId) {
        long total = 0;
        for (ChatSessionEntity session : sessionRepository.findByKbIdOrderByUpdatedAtDesc(
                kbId, org.springframework.data.domain.Pageable.unpaged())) {
            total += messageRepository.countBySessionId(session.getId());
        }
        return total;
    }

    private static DomainException nameDuplicated(String name) {
        return new DomainException(ErrorCode.KB_NAME_DUPLICATED,
                "同名知识库已存在：" + name);
    }

    private static CleanupTaskEntity newCleanupTask(UUID kbId, CleanupScope scope,
                                                    StoreType store, Map<String, Object> payload) {
        CleanupTaskEntity task = new CleanupTaskEntity();
        task.setScope(scope);
        task.setRefId(kbId.toString());
        task.setStore(store);
        task.setPayload(payload);
        task.setStatus(CleanupStatus.PENDING);
        return task;
    }
}
