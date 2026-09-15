package com.rag.api.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.rag.api.dto.Chunk;
import com.rag.api.dto.ChunkingConfig;
import com.rag.api.dto.Document;
import com.rag.api.dto.DocumentDeletionResult;
import com.rag.api.dto.DocumentDetail;
import com.rag.api.dto.IngestionTask;
import com.rag.api.dto.ParsedText;
import com.rag.api.dto.UploadAccepted;
import com.rag.config.RagProperties;
import com.rag.domain.entity.CleanupTaskEntity;
import com.rag.domain.entity.DocumentEntity;
import com.rag.domain.entity.IngestionTaskEntity;
import com.rag.domain.entity.KnowledgeBaseEntity;
import com.rag.domain.enums.CleanupScope;
import com.rag.domain.enums.CleanupStatus;
import com.rag.domain.enums.DocumentStatus;
import com.rag.domain.enums.FileType;
import com.rag.domain.enums.PipelineStage;
import com.rag.domain.enums.StoreType;
import com.rag.domain.enums.TaskStatus;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import com.rag.ingestion.CleanupService;
import com.rag.ingestion.IngestionTaskManager;
import com.rag.storage.es.EsChunkIndex;
import com.rag.storage.minio.ObjectStore;
import com.rag.storage.repository.CleanupTaskRepository;
import com.rag.storage.repository.DocumentRepository;
import com.rag.storage.repository.IngestionTaskRepository;
import com.rag.storage.repository.KnowledgeBaseRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档与入库流水线应用服务（API 层，路线 §3.1/§3.3）。
 *
 * <p>上传同步校验链（KB-2/KB-3/KB-8）：KB 存在 → 扩展名白名单（415）→
 * 大小上限（413）→ magic bytes 粗校验（%PDF / UTF-8 可解码）→ sha256 去重
 * （409，details 含 existingDocumentId）→ MinIO putSource → 建 document +
 * ingestion_task(QUEUED) → enqueue → 202。上传受理不等待流水线。</p>
 *
 * <p>删除语义（路线 §3.3）：MySQL 事务内删 document 行（ingestion_task 外键级联），
 * 写 ES/MinIO 两条 cleanup_task，事务提交后立即触发一轮清理（加速收敛；
 * 失败由 CleanupService @Scheduled 每 30s 兜底重扫）。</p>
 *
 * <p>分块查询说明：EsChunkIndex 面向检索场景（kNN）无分页列表方法且属共享 storage 层
 * （本任务写边界禁止修改），此处直接注入共享 {@link ElasticsearchClient} 做只读查询
 * （index 名复用 {@link EsChunkIndex#INDEX_NAME} 常量），不改变 storage 层行为。</p>
 */
@Service
public class DocumentService {

    /** 上传扩展名白名单（契约：.pdf/.md/.txt），复用 ObjectStore 常量。 */
    private static final Set<String> ALLOWED_EXTENSIONS = ObjectStore.SOURCE_EXTENSIONS;

    private final KnowledgeBaseRepository kbRepository;
    private final DocumentRepository documentRepository;
    private final IngestionTaskRepository taskRepository;
    private final CleanupTaskRepository cleanupTaskRepository;
    private final ObjectStore objectStore;
    private final IngestionTaskManager taskManager;
    private final CleanupService cleanupService;
    private final ElasticsearchClient elasticsearchClient;
    private final RagProperties ragProperties;

    public DocumentService(KnowledgeBaseRepository kbRepository,
                           DocumentRepository documentRepository,
                           IngestionTaskRepository taskRepository,
                           CleanupTaskRepository cleanupTaskRepository,
                           ObjectStore objectStore,
                           IngestionTaskManager taskManager,
                           CleanupService cleanupService,
                           ElasticsearchClient elasticsearchClient,
                           RagProperties ragProperties) {
        this.kbRepository = kbRepository;
        this.documentRepository = documentRepository;
        this.taskRepository = taskRepository;
        this.cleanupTaskRepository = cleanupTaskRepository;
        this.objectStore = objectStore;
        this.taskManager = taskManager;
        this.cleanupService = cleanupService;
        this.elasticsearchClient = elasticsearchClient;
        this.ragProperties = ragProperties;
    }

    // ------------------------------------------------------------------
    // 上传（同步校验 + 受理）
    // ------------------------------------------------------------------

    public UploadAccepted upload(UUID kbId, MultipartFile file, String chunkStrategy,
                                 Integer maxLength, Integer overlap) {
        requireKb(kbId);

        // 1. 扩展名白名单（415）
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String extension = extractExtension(fileName);
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension)) {
            throw new DomainException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }

        // 2. 大小上限（413）：以 rag.ingestion.max-upload-size-mb 配置为准
        long maxBytes = ragProperties.getIngestion().getMaxUploadSizeMb() * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new DomainException(ErrorCode.FILE_TOO_LARGE,
                    "文件超过大小上限（" + ragProperties.getIngestion().getMaxUploadSizeMb() + "MB）");
        }

        // 3. 读取全部字节：magic bytes 校验与 sha256 计算共用（≤50MB 开销可接受，路线 §3.1）
        byte[] content;
        try (InputStream in = file.getInputStream()) {
            content = in.readAllBytes();
        } catch (IOException e) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "读取上传文件失败：" + e.getMessage());
        }
        verifyMagicBytes(extension, content);

        // 4. sha256 去重（409，details=existingDocumentId）——R3-P3 后语义：
        //    同 KB 同内容（不论版本组）仍 409，内容相同的「新版本」无信息量
        String sha256 = sha256Hex(content);
        String kbIdStr = kbId.toString();
        Optional<DocumentEntity> duplicate = documentRepository.findByKbIdAndContentSha256(kbIdStr, sha256);
        if (duplicate.isPresent()) {
            throw duplicateDocument(duplicate.get());
        }

        // 4.5 版本组判定（R3-P3）：KB 内已有同名且同类型文档 → 作为其版本组的新版本
        //     （自动激活，见 persistUpload）；否则为新文档组 v1
        DocumentEntity versionRoot = null;
        for (DocumentEntity sameName : documentRepository.findByKbIdAndName(kbIdStr, fileName)) {
            if (sameName.getFileType() == FileType.valueOf(extension.toUpperCase(Locale.ROOT))) {
                versionRoot = sameName;
                break;
            }
        }

        // 5. MinIO putSource（M4：移出事务——50MB 上传期间不再占用 DB 连接，
        //    且后续 saveAndFlush 竞态回滚不会留下孤儿对象问题无法补偿；
        //    反向孤儿——putSource 成功但落库失败——由下方 catch 写 cleanup_task 补偿）
        String docId = UUID.randomUUID().toString();
        DocumentEntity doc = new DocumentEntity();
        doc.setId(docId);
        doc.setKbId(kbIdStr);
        doc.setName(fileName);
        doc.setFileType(FileType.valueOf(extension.toUpperCase(Locale.ROOT)));
        if (versionRoot != null) {
            String rootId = versionRoot.getRootId() == null || versionRoot.getRootId().isBlank()
                    ? versionRoot.getId() : versionRoot.getRootId();
            int nextVersion = documentRepository.findFirstByRootIdOrderByVersionNoDesc(rootId)
                    .map(prev -> prev.getVersionNo() + 1).orElse(1);
            doc.setRootId(rootId);
            doc.setVersionNo(nextVersion);
            doc.setActive(true);
        } else {
            doc.setRootId(docId);
            doc.setVersionNo(1);
            doc.setActive(true);
        }
        doc.setSizeBytes(content.length);
        doc.setContentSha256(sha256);
        doc.setStatus(DocumentStatus.QUEUED);
        doc.setCurrentStage(PipelineStage.QUEUED);
        Map<String, Object> chunkConfig = new LinkedHashMap<>();
        chunkConfig.put("strategy", chunkStrategy == null ? "LENGTH_OVERLAP" : chunkStrategy);
        chunkConfig.put("maxLength", maxLength == null ? 800 : maxLength);
        chunkConfig.put("overlap", overlap == null ? 100 : overlap);
        doc.setChunkConfig(chunkConfig);
        doc.setSourceObjectKey(ObjectStore.sourceKey(kbIdStr, docId, extension));

        objectStore.putSource(kbIdStr, docId, fileName,
                new java.io.ByteArrayInputStream(content), content.length);

        try {
            return persistUpload(doc, sha256, kbIdStr);
        } catch (RuntimeException e) {
            // M4：落库失败（含并发去重兜底 409）时补偿删除刚写入的 MinIO 对象，
            // 避免无 document 行的孤儿对象永久残留
            try {
                objectStore.removeDoc(kbIdStr, docId);
            } catch (RuntimeException cleanupError) {
                org.slf4j.LoggerFactory.getLogger(DocumentService.class)
                        .error("上传落库失败后 MinIO 补偿删除也失败（孤儿对象）kbId={} docId={}", kbIdStr, docId, cleanupError);
            }
            throw e;
        }
    }

    /**
     * 落库短事务（M4）：只做 DB 写，MinIO 与校验均已在事务外完成。
     * R3-P3：新版本受理即切换激活（不等流水线完成）——换取「入库期间旧版本仍可检索」，
     * 避免更新窗口内检索空窗；新版本失败时可经 activate 手动回旧版。
     */
    @Transactional
    protected UploadAccepted persistUpload(DocumentEntity doc, String sha256, String kbIdStr) {
        if (doc.isActive() && doc.getVersionNo() > 1) {
            // 先 flush 旧版本失活：uk_document_active（active_key=root_id）唯一键在
            // 新版本 INSERT 时校验，若失活 UPDATE 尚未落库会与之冲突（IT 实测踩中），
            // 且冲突被下方 catch 误转成 DUPLICATE_DOCUMENT
            documentRepository.findByRootIdAndActiveTrue(doc.getRootId())
                    .filter(prev -> !prev.getId().equals(doc.getId()))
                    .ifPresent(prev -> {
                        prev.setActive(false);
                        documentRepository.saveAndFlush(prev);
                    });
        }
        DocumentEntity saved;
        try {
            saved = documentRepository.saveAndFlush(doc);
        } catch (DataIntegrityViolationException e) {
            // 并发窗口兜底：预判未命中但 UNIQUE(kb_id, content_sha256) 命中；
            // 或组内版本号唯一键（uk_document_root_version）竞态——后者按内容去重不存在，
            // 属并发同名上传，转 409 语义最接近的 DUPLICATE_DOCUMENT 并提示重试
            Optional<DocumentEntity> raced = documentRepository.findByKbIdAndContentSha256(kbIdStr, sha256);
            throw raced.map(DocumentService::duplicateDocument)
                    .orElseGet(() -> new DomainException(ErrorCode.DUPLICATE_DOCUMENT));
        }

        IngestionTaskEntity task = new IngestionTaskEntity();
        task.setDocumentId(saved.getId());
        task.setStatus(TaskStatus.QUEUED);
        task.setStage(PipelineStage.QUEUED);
        task.setAttempt(1);
        IngestionTaskEntity savedTask = taskRepository.saveAndFlush(task);

        // 入队必须在事务提交后执行：worker 线程会读 document/task 行，
        // 事务内提前入队会被 worker 以未提交数据读空（DOCUMENT_NOT_FOUND）。
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            enqueueAfterCommit(saved.getId());
        } else {
            // 无活跃事务（内部调用未过代理等场景）：事务已由 saveAndFlush 提交，直接入队
            taskManager.enqueue(saved.getId());
        }

        return new UploadAccepted(toDocument(saved), toTask(savedTask));
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageBundle listDocuments(UUID kbId, DocumentStatus status, int page, int pageSize) {
        requireKb(kbId);
        Pageable pageable = PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<DocumentEntity> result = status == null
                ? documentRepository.findByKbId(kbId.toString(), pageable)
                : documentRepository.findByKbIdAndStatus(kbId.toString(), status, pageable);
        List<Document> items = result.getContent().stream().map(this::toDocument).toList();
        return new PageBundle(items, page, pageSize, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public DocumentDetail getDocument(UUID docId) {
        DocumentEntity doc = requireDoc(docId);
        IngestionTaskEntity task = taskRepository.findByDocumentId(doc.getId()).orElse(null);
        return new DocumentDetail(
                doc.getId(), doc.getKbId(), doc.getName(), doc.getFileType().name(),
                doc.getSizeBytes(), doc.getStatus().name(), doc.getCurrentStage().name(),
                doc.getChunkCount(), doc.getRootId(), doc.getVersionNo(), doc.isActive(),
                doc.getCreatedAt(), doc.getUpdatedAt(),
                toChunkingConfig(doc), doc.getContentSha256(),
                doc.getFailureStage(), doc.getFailureReason(), task == null ? null : toTask(task));
    }

    /** 解析文本预览：parsed.txt 不存在 → 409 PARSED_TEXT_NOT_READY（解析阶段未完成）。 */
    @Transactional(readOnly = true)
    public ParsedText getParsedText(UUID docId, Integer maxChars) {
        DocumentEntity doc = requireDoc(docId);
        if (!objectStore.existsParsed(doc.getKbId(), doc.getId())) {
            throw new DomainException(ErrorCode.PARSED_TEXT_NOT_READY);
        }
        String content = objectStore.getParsed(doc.getKbId(), doc.getId());
        boolean truncated = false;
        if (maxChars != null && content.length() > maxChars) {
            content = content.substring(0, maxChars);
            truncated = true;
        }
        return new ParsedText(doc.getId(), content, content.length(), truncated);
    }

    /**
     * 分块预览（分页）：ES 按 doc_id 过滤、seq 升序。尚未分块完成时索引无该 doc
     * 的文档 → 自然返回空页（契约：200 空页不报错）。
     */
    public PageBundle listChunks(UUID docId, int page, int pageSize) {
        DocumentEntity doc = requireDoc(docId);
        try {
            SearchResponse<Map> response = elasticsearchClient.search(s -> s
                            .index(EsChunkIndex.INDEX_NAME)
                            .query(q -> q.term(t -> t.field("doc_id").value(doc.getId())))
                            .from((page - 1) * pageSize)
                            .size(pageSize)
                            .sort(so -> so.field(f -> f.field("seq").order(SortOrder.Asc))),
                    Map.class);
            long total = response.hits().total() == null ? 0 : response.hits().total().value();
            List<Chunk> items = new ArrayList<>(response.hits().hits().size());
            for (Hit<Map> hit : response.hits().hits()) {
                items.add(toChunk(hit));
            }
            return new PageBundle(items, page, pageSize, total);
        } catch (IOException e) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "分块查询失败：" + e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public IngestionTask getTask(UUID docId) {
        requireDoc(docId);
        IngestionTaskEntity task = taskRepository.findByDocumentId(docId.toString())
                .orElseThrow(() -> new DomainException(ErrorCode.DOCUMENT_NOT_FOUND));
        return toTask(task);
    }

    // ------------------------------------------------------------------
    // 重试
    // ------------------------------------------------------------------

    /**
     * 重试失败任务（API 层最小职责，与 IngestionTaskManager 的续跑契约一致）：
     * 仅 FAILED 可重试（非 FAILED → 409 TASK_NOT_RETRYABLE）。置任务回 QUEUED
     * （stage 保持 failureStage——manager 按 task.stage 续跑并跳过已完成阶段）、
     * attempt+1、document.status=QUEUED、清空 finishedAt 后重新入队。
     * failureStage/failureReason 由 manager 终态时清理，此处保留（续跑期间失败信息仍可见）。
     */
    @Transactional
    public IngestionTask retry(UUID docId) {
        DocumentEntity doc = requireDoc(docId);
        IngestionTaskEntity task = taskRepository.findByDocumentId(docId.toString())
                .orElseThrow(() -> new DomainException(ErrorCode.DOCUMENT_NOT_FOUND));
        if (task.getStatus() != TaskStatus.FAILED) {
            throw new DomainException(ErrorCode.TASK_NOT_RETRYABLE,
                    "任务当前状态为 " + task.getStatus() + "，仅失败的任务可以重试");
        }
        task.setStatus(TaskStatus.QUEUED);
        task.setAttempt(task.getAttempt() + 1);
        task.setFinishedAt(null);
        taskRepository.saveAndFlush(task);
        doc.setStatus(DocumentStatus.QUEUED);
        documentRepository.saveAndFlush(doc);
        // 与 upload 相同：提交后再入队，避免 worker 读到未提交状态
        enqueueAfterCommit(docId.toString());
        return toTask(task);
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    /**
     * 删除文档：MySQL 行删除（ingestion_task 外键级联）+ ES/MinIO 两条 cleanup_task
     * 同事务写入，提交后立即触发一轮补偿清理（失败由 @Scheduled 兜底）。
     * chunksDeleted 为 ES 侧最近已知分块数（document.chunk_count，可近似）。
     */
    @Transactional
    public DocumentDeletionResult delete(UUID docId) {
        DocumentEntity doc = requireDoc(docId);
        int chunksDeleted = doc.getChunkCount();
        documentRepository.delete(doc);
        fallbackActivationAfterDelete(doc);

        CleanupTaskEntity esTask = newCleanupTask(doc, StoreType.ELASTICSEARCH);
        CleanupTaskEntity minioTask = newCleanupTask(doc, StoreType.MINIO);
        cleanupTaskRepository.saveAll(List.of(esTask, minioTask));
        List<CleanupTaskEntity> accepted = List.of(esTask, minioTask);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    for (CleanupTaskEntity task : accepted) {
                        cleanupService.processOne(task);
                    }
                } catch (RuntimeException e) {
                    // M7：补偿任务已持久化，@Scheduled 30s 兜底；失败不应把已成功的删除变成 500
                    org.slf4j.LoggerFactory.getLogger(DocumentService.class)
                            .error("afterCommit 清理加速执行失败（@Scheduled 兜底）", e);
                }
            }
        });

        return new DocumentDeletionResult(docId.toString(), chunksDeleted, accepted.size());
    }

    // ------------------------------------------------------------------
    // 版本管理（R3-P3）
    // ------------------------------------------------------------------

    /** 版本组列表（版本号升序）。docId 不存在 → 404。 */
    @Transactional(readOnly = true)
    public List<Document> listVersions(UUID docId) {
        DocumentEntity doc = requireDoc(docId);
        return documentRepository.findByRootIdOrderByVersionNoAsc(doc.getRootId()).stream()
                .map(this::toDocument)
                .toList();
    }

    /**
     * 激活指定版本：目标版本置 TRUE，组内其它版本置 FALSE。
     * 仅 COMPLETED 可激活（QUEUED/PROCESSING 中间态检索无内容，激活等于自造空窗；
     * FAILED 版本可激活——用户从失败的新版本回退旧版的路径，语义为「恢复到可检索状态」）。
     */
    @Transactional
    public Document activate(UUID docId) {
        DocumentEntity target = requireDoc(docId);
        if (target.getStatus() != DocumentStatus.COMPLETED
                && target.getStatus() != DocumentStatus.FAILED) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT,
                    "版本当前状态为 " + target.getStatus() + "，仅 COMPLETED/FAILED 版本可激活");
        }
        // 顺序敏感：uk_document_active（active_key=root_id）下，必须先失活其它版本并 flush，
        // 再激活目标——同批混合 UPDATE 时 Hibernate 执行顺序不定，激活先行会撞唯一键（真机踩中）
        List<DocumentEntity> versions = documentRepository.findByRootIdOrderByVersionNoAsc(target.getRootId());
        for (DocumentEntity v : versions) {
            if (!v.getId().equals(target.getId())) {
                v.setActive(false);
            }
        }
        documentRepository.saveAllAndFlush(versions);
        target.setActive(true);
        documentRepository.saveAndFlush(target);
        return toDocument(target);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 事务提交后再入队（上传/重试共用）：worker 读库必须见到已提交的 document/task 行。 */
    private void enqueueAfterCommit(String documentId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    taskManager.enqueue(documentId);
                } catch (RuntimeException e) {
                    // M7：事务已提交——失败时吞掉并记日志（启动恢复会重新入队），
                    // 否则异常沿同步器传播把已成功的 202 变成 500，客户端重试得 409
                    org.slf4j.LoggerFactory.getLogger(DocumentService.class)
                            .error("afterCommit 入队失败（启动恢复兜底）documentId={}", documentId, e);
                }
            }
        });
    }

    /**
     * 删除激活版本后的回落（R3-P3）：自动激活组内最新的 COMPLETED 旧版；
     * 删除非激活版本或组内无其它 COMPLETED 版本时不动作。
     * 同事务内执行（delete 的 @Transactional），回落与删除原子生效。
     */
    private void fallbackActivationAfterDelete(DocumentEntity deleted) {
        if (!deleted.isActive()) {
            return;
        }
        documentRepository.findByRootIdOrderByVersionNoAsc(deleted.getRootId()).stream()
                .filter(v -> !v.getId().equals(deleted.getId()))
                .filter(v -> v.getStatus() == DocumentStatus.COMPLETED)
                .reduce((first, second) -> second) // 版本号升序的最后一个 = 最新 COMPLETED
                .ifPresent(latest -> latest.setActive(true));
    }

    private KnowledgeBaseEntity requireKb(UUID kbId) {
        return kbRepository.findById(kbId.toString())
                .orElseThrow(() -> new DomainException(ErrorCode.KB_NOT_FOUND));
    }

    private DocumentEntity requireDoc(UUID docId) {
        return documentRepository.findById(docId.toString())
                .orElseThrow(() -> new DomainException(ErrorCode.DOCUMENT_NOT_FOUND));
    }

    @SuppressWarnings("unchecked")
    private static Chunk toChunk(Hit<Map> hit) {
        Map<String, Object> source = hit.source() == null ? Map.of() : hit.source();
        Integer page = (Integer) source.get("page");
        Integer seq = (Integer) source.get("seq");
        Integer charCount = (Integer) source.get("char_count");
        return new Chunk(
                hit.id(),
                (String) source.get("doc_id"),
                seq == null ? 0 : seq,
                (String) source.get("title_path"),
                page,
                charCount == null ? 0 : charCount,
                (String) source.get("content"));
    }

    private static CleanupTaskEntity newCleanupTask(DocumentEntity doc, StoreType store) {
        CleanupTaskEntity task = new CleanupTaskEntity();
        task.setScope(CleanupScope.DOCUMENT);
        task.setRefId(doc.getId());
        task.setStore(store);
        Map<String, Object> payload = new HashMap<>();
        payload.put("docId", doc.getId());
        payload.put("kbId", doc.getKbId());
        task.setPayload(payload);
        task.setStatus(CleanupStatus.PENDING);
        return task;
    }

    private Document toDocument(DocumentEntity entity) {
        return new Document(entity.getId(), entity.getKbId(), entity.getName(),
                entity.getFileType().name(), entity.getSizeBytes(), entity.getStatus(),
                entity.getCurrentStage(), entity.getChunkCount(),
                entity.getRootId(), entity.getVersionNo(), entity.isActive(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private IngestionTask toTask(IngestionTaskEntity entity) {
        return new IngestionTask(entity.getId(), entity.getDocumentId(), entity.getStatus().name(),
                entity.getStage(), entity.getAttempt(), entity.getFailureStage(),
                entity.getFailureReason(), entity.getCreatedAt(), entity.getStartedAt(),
                entity.getFinishedAt());
    }

    private static ChunkingConfig toChunkingConfig(DocumentEntity doc) {
        Map<String, Object> map = doc.getChunkConfig();
        if (map == null || map.isEmpty()) {
            return null;
        }
        Object strategy = map.get("strategy");
        Object maxLength = map.get("maxLength");
        Object overlap = map.get("overlap");
        return new ChunkingConfig(
                strategy == null ? null : com.rag.domain.enums.ChunkStrategy.valueOf(strategy.toString()),
                maxLength instanceof Number n ? n.intValue() : 0,
                overlap instanceof Number n ? n.intValue() : 0);
    }

    private static DomainException duplicateDocument(DocumentEntity existing) {
        return new DomainException(ErrorCode.DUPLICATE_DOCUMENT,
                "内容相同的文档已存在于该知识库",
                List.of(new DomainException.Detail("file",
                        "existingDocumentId=" + existing.getId())));
    }

    // ------------------------------------------------------------------
    // 上传校验工具
    // ------------------------------------------------------------------

    /** 提取小写扩展名（无扩展名 → null）。文件名仅此处解析，不参与对象键。 */
    private static String extractExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * magic bytes 粗校验（路线 §7）：PDF 必须 %PDF- 前缀；docx/xlsx 为 ZIP 容器（OOXML），
     * 必须 PK\x03\x04 前缀；md/txt/csv 必须可严格按 UTF-8 解码（REPORT 模式，
     * 二进制冒充文本 → 415）。
     */
    private static void verifyMagicBytes(String extension, byte[] content) {
        if ("pdf".equals(extension)) {
            if (content.length < 5 || content[0] != '%' || content[1] != 'P'
                    || content[2] != 'D' || content[3] != 'F' || content[4] != '-') {
                throw new DomainException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                        "文件内容不是合法的 PDF（缺少 %PDF- 魔数）");
            }
            return;
        }
        if ("docx".equals(extension) || "xlsx".equals(extension)) {
            // OOXML = ZIP 容器：本地文件头固定 PK\x03\x04
            if (content.length < 4 || content[0] != 'P' || content[1] != 'K'
                    || content[2] != 0x03 || content[3] != 0x04) {
                throw new DomainException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                        "文件内容不是合法的 " + extension.toUpperCase(Locale.ROOT)
                                + "（缺少 ZIP 魔数，可能不是真正的 Office OOXML 文件，"
                                + "旧版 .doc/.xls 二进制格式不支持）");
            }
            return;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
        } catch (CharacterCodingException e) {
            throw new DomainException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "文件内容不是合法的 UTF-8 文本");
        }
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    /** 内部分页结果包（controller 层转契约 PageResult，避免暴露 Spring Data Page）。 */
    public record PageBundle(List<?> items, int page, int pageSize, long total) {
    }
}
