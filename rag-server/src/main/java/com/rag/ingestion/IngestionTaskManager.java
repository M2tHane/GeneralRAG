package com.rag.ingestion;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rag.config.RagProperties;
import com.rag.domain.entity.DocumentEntity;
import com.rag.domain.entity.IngestionTaskEntity;
import com.rag.domain.enums.DocumentStatus;
import com.rag.domain.enums.FileType;
import com.rag.domain.enums.PipelineStage;
import com.rag.domain.enums.TaskStatus;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import com.rag.ingestion.chunk.ChunkDraft;
import com.rag.ingestion.chunk.Chunker;
import com.rag.ingestion.chunk.ChunkingConfig;
import com.rag.ingestion.parse.DocumentParser;
import com.rag.ingestion.parse.ParsedDocument;
import com.rag.ingestion.parse.ParserRouter;
import com.rag.ingestion.pipeline.StageContext;
import com.rag.ingestion.pipeline.StageFlow;
import com.rag.storage.es.ChunkDoc;
import com.rag.storage.es.EsChunkIndex;
import com.rag.storage.minio.ObjectStore;
import com.rag.storage.repository.DocumentRepository;
import com.rag.storage.repository.IngestionTaskRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 入库流水线调度器（路线 §3.1）：固定线程池认领 QUEUED 任务，按
 * PARSING→CLEANING→CHUNKING→EMBEDDING→INDEXING→COMPLETED 推进，每阶段
 * 开始前先落 task.stage（Document.currentStage 同步），失败记
 * FAILED + failureStage + failureReason（错误码 + 人读文案）。
 *
 * <p><b>阶段复用（幂等证据）</b>：重试时任务已被 API 层置回 QUEUED，task.stage
 * 即 failureStage，续跑从该阶段开始：</p>
 * <ul>
 *   <li>PARSING 完成判据 = parsed.txt 存在（{@link ObjectStore#existsParsed}）——
 *       存在则 PARSING 跳过，且 CLEANING 随之跳过（clean 产物与解析文本一同落盘）；</li>
 *   <li>CHUNKING 起直接重建：优先复用本次运行的内存产物，续跑场景从 parsed.txt
 *       重建 {@link ParsedDocument#fromPersisted}；ES 侧
 *       {@link EsChunkIndex#rebuildChunks} 先删后写，天然幂等不产生重复分块；</li>
 *   <li>CLEANING 若在解析产物未落盘时失败，重试会重新解析（产物不存在 ⇒ 解析+清洗需重做）。</li>
 * </ul>
 *
 * <p>启动恢复：{@code ApplicationReadyEvent} 把 QUEUED/RUNNING 任务复位 QUEUED
 * （RUNNING 记 attempt+1）并重新入队。COMPLETED 为终态、FAILED 需显式 retry，
 * 状态机由 {@link StageFlow#assertLegal} 守护。</p>
 */
@Component
public class IngestionTaskManager {

    private static final Logger log = LoggerFactory.getLogger(IngestionTaskManager.class);

    /** EMBEDDING 阶段批量向量化大小（路线 Task 3：每批 ≤16 条）。 */
    static final int EMBED_BATCH_SIZE = 16;

    private static final java.util.concurrent.atomic.AtomicInteger POOL_SEQUENCE =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private final IngestionTaskRepository taskRepository;
    private final DocumentRepository documentRepository;
    private final ObjectStore objectStore;
    private final EsChunkIndex esChunkIndex;
    private final ParserRouter parserRouter;
    private final Map<FileType, String> sourceExtensions = new EnumMap<>(Map.of(
            FileType.PDF, "pdf", FileType.MD, "md", FileType.TXT, "txt",
            FileType.DOCX, "docx", FileType.XLSX, "xlsx", FileType.CSV, "csv"));
    private final Map<com.rag.domain.enums.ChunkStrategy, Chunker> chunkers = new EnumMap<>(com.rag.domain.enums.ChunkStrategy.class);
    /** 表格类文件的 STRUCTURE 分块器（R4-Excel：Sheet Summary + Row Group）。 */
    private final com.rag.ingestion.chunk.SpreadsheetChunker spreadsheetChunker =
            new com.rag.ingestion.chunk.SpreadsheetChunker();
    private final EmbeddingGateway embeddingGateway;
    private final com.rag.ingestion.chunk.RetrievalContentEnricher retrievalContentEnricher;
    private final RagProperties ragProperties;
    private final java.util.concurrent.ExecutorService executor;

    public IngestionTaskManager(IngestionTaskRepository taskRepository,
                                DocumentRepository documentRepository,
                                ObjectStore objectStore,
                                EsChunkIndex esChunkIndex,
                                ParserRouter parserRouter,
                                List<Chunker> chunkerList,
                                EmbeddingGateway embeddingGateway,
                                com.rag.ingestion.chunk.RetrievalContentEnricher retrievalContentEnricher,
                                RagProperties ragProperties) {
        this.taskRepository = taskRepository;
        this.documentRepository = documentRepository;
        this.objectStore = objectStore;
        this.esChunkIndex = esChunkIndex;
        this.parserRouter = parserRouter;
        this.ragProperties = ragProperties;
        for (Chunker chunker : chunkerList) {
            Chunker existing = chunkers.put(chunker.strategy(), chunker);
            if (existing != null) {
                throw new IllegalStateException("ChunkStrategy " + chunker.strategy()
                        + " 注册了多个分块器：" + existing.getClass().getName()
                        + " / " + chunker.getClass().getName());
            }
        }
        this.embeddingGateway = embeddingGateway;
        this.retrievalContentEnricher = retrievalContentEnricher;
        this.executor = java.util.concurrent.Executors.newFixedThreadPool(
                ragProperties.getIngestion().getWorkerThreads(), runnable -> {
                    Thread thread = new Thread(runnable, "rag-ingestion-" + POOL_SEQUENCE.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** 提交任务异步执行（上传受理后由 API 层调用，KB-3 不等待）。 */
    public void enqueue(String documentId) {
        executor.submit(() -> {
            try {
                processOne(documentId);
            } catch (RuntimeException e) {
                // processOne 内部已落 FAILED；此处仅兜底日志（如 DOCUMENT_NOT_FOUND 等前置异常）
                log.error("入库任务执行异常（docId={}）", documentId, e);
            }
        });
    }

    /**
     * 认领并执行一个任务（同步）。仅当任务处于 QUEUED 时执行（CAS 认领防双跑）；
     * 已被他处认领/执行则静默跳过。
     *
     * <p>注意：整体一个事务边界会放大失败回滚面（阶段产物已外部化到 ES/MinIO，
     * 回滚反而造成状态错乱），因此阶段落库依赖各 @Modifying/CRUD 的小事务。
     * @Modifying 查询必须运行在事务内——由 Spring Data 的事务性代理（SimpleJpaRepository
     * 自带事务）覆盖不到自定义 @Query 方法，故这里将逐条更新收敛到 claimAndAdvance 等小事务。
     * 实现上采用「repository 方法自带 @Transactional」策略。</p>
     */
    public void processOne(String documentId) {
        IngestionTaskEntity task = taskRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new DomainException(ErrorCode.DOCUMENT_NOT_FOUND,
                        "文档不存在或任务缺失：" + documentId));
        if (!taskRepository.claimIfQueued(task.getId())) {
            log.info("任务已被认领，跳过（docId={}, taskId={}, status={}）",
                    documentId, task.getId(), task.getStatus());
            return;
        }
        // claimIfQueued 是 bulk update（clearAutomatically），重新加载认领后的状态
        task = taskRepository.findByDocumentId(documentId).orElseThrow();
        DocumentEntity doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new DomainException(ErrorCode.DOCUMENT_NOT_FOUND,
                        "文档不存在：" + documentId));

        StageContext ctx = new StageContext(doc, task);
        PipelineStage cursor = task.getStage();
        PipelineStage executing = cursor;
        try {
            // CHUNKING 产物（drafts）只在单次执行的上下文中传递；重试若从 EMBEDDING/INDEXING
            // 起步（CHUNKING 已完成但产物不在场），必须先按 parsed.txt 重建分块——
            // chunkId 确定性 + ES 先删后写保证幂等（路线 §3.1"重建分块"语义）。
            if (cursor == PipelineStage.EMBEDDING || cursor == PipelineStage.INDEXING) {
                chunk(ctx);
            }
            for (PipelineStage stage : StageFlow.stagesFrom(cursor)) {
                StageFlow.assertLegal(cursor, stage);
                // 每阶段开始前先落库（路线 §3.1：先落 task.stage 再执行）。
                // updateStage 返回 0 行 = 任务行已被删除（用户在流水线运行中删除了文档，
                // B1 竞态）：必须立即静默退出，禁止继续执行任何阶段（否则 merge 会把
                // document 行"复活"、ES 分块在 cleanup 之后写回造成残留命中）。
                if (taskRepository.updateStage(task.getId(), stage) == 0) {
                    log.info("任务行已不存在（文档在处理中被删除），流水线中止（docId={}）", doc.getId());
                    return;
                }
                // 二次确认主档仍在（delete 事务先删 task 再删 doc 的窗口内 updateStage 可能仍命中）
                if (!documentRepository.existsById(doc.getId())) {
                    log.info("文档已删除，流水线中止（docId={}）", doc.getId());
                    return;
                }
                // 定向更新而非 merge 全字段：worker 持有的快照可能滞后于并发版本切换
                // （R3-P3 active 位），全字段写回会把旧 active 位写回撞唯一键
                documentRepository.updateStatusFields(doc.getId(), DocumentStatus.PROCESSING, stage, doc.getChunkCount());
                executing = stage;
                execute(stage, ctx);
                cursor = stage;
            }
            StageFlow.assertLegal(cursor, PipelineStage.COMPLETED);
            if (taskRepository.updateStage(task.getId(), PipelineStage.COMPLETED) == 0) {
                log.info("任务行已不存在（文档在处理中被删除），放弃收官（docId={}）", doc.getId());
                return;
            }
            task.setStatus(TaskStatus.COMPLETED);
            task.setStage(PipelineStage.COMPLETED);
            task.setFinishedAt(LocalDateTime.now());
            task.setFailureStage(null);
            task.setFailureReason(null);
            taskRepository.save(task);
            documentRepository.updateStatusFields(doc.getId(),
                    DocumentStatus.COMPLETED, PipelineStage.COMPLETED, doc.getChunkCount());
            log.info("入库任务完成（docId={}, chunks={}, attempt={}）",
                    doc.getId(), doc.getChunkCount(), task.getAttempt());
        } catch (Exception e) {
            fail(task, doc, executing, e);
        }
    }

    /** 启动恢复：QUEUED/RUNNING 复位 QUEUED（RUNNING 记 attempt+1）并重新入队。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        List<IngestionTaskEntity> interrupted = taskRepository.findByStatusIn(
                List.of(TaskStatus.QUEUED, TaskStatus.RUNNING));
        for (IngestionTaskEntity task : interrupted) {
            boolean wasRunning = task.getStatus() == TaskStatus.RUNNING;
            task.setStatus(TaskStatus.QUEUED);
            if (wasRunning) {
                task.setAttempt(task.getAttempt() + 1);
            }
            taskRepository.save(task);
            log.info("启动恢复：重新入队（docId={}, taskId={}, 原状态={}, stage={}, attempt={}）",
                    task.getDocumentId(), task.getId(), wasRunning ? "RUNNING" : "QUEUED",
                    task.getStage(), task.getAttempt());
            enqueue(task.getDocumentId());
        }
        if (!interrupted.isEmpty()) {
            log.info("启动恢复完成：共恢复 {} 个入库任务", interrupted.size());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------
    // 阶段实现
    // ------------------------------------------------------------------

    private void execute(PipelineStage stage, StageContext ctx) {
        switch (stage) {
            case PARSING -> parse(ctx);
            case CLEANING -> clean(ctx);
            case CHUNKING -> chunk(ctx);
            case EMBEDDING -> embed(ctx);
            case INDEXING -> index(ctx);
            default -> throw new IllegalStateException("不可执行的阶段：" + stage);
        }
    }

    private void parse(StageContext ctx) {
        DocumentEntity doc = ctx.document;
        if (objectStore.existsParsed(doc.getKbId(), doc.getId())) {
            // PARSING 完成判据（路线 §3.1）：parsed.txt 存在 ⇒ 解析+清洗均已完成
            ctx.parsingAlreadyDone = true;
            log.info("PARSING 产物已存在，跳过（docId={}）", doc.getId());
            return;
        }
        DocumentParser parser = parserRouter.route(doc.getFileType());
        try (InputStream in = objectStore.getSource(doc.getKbId(), doc.getId(),
                sourceExtensions.get(doc.getFileType()))) {
            ParsedDocument parsed;
            try {
                parsed = parser.parse(in, doc.getFileType());
            } catch (com.rag.ingestion.parse.PdfAutoParseException e) {
                // R6-D.1：AUTO 路由决策已产生（selected/reason/probe 是确定事实），
                // 所选 parser 正式解析失败时 routing metadata 同样持久化——
                // 不依赖解析成功，也不从异常字符串反推（report 由结构化异常携带）。
                // 持久化后再 rethrow（fail() 落任务失败态）。
                persistPdfParseMetadata(doc, e.parseReport());
                throw e;
            }
            // R6-D：解析路由元数据持久化到 document.parse_metadata，保证
            // "这个 PDF 为什么被送到 MinerU / 实际谁解析的"可复盘。
            // AUTO：report 携带 selected/routingReason/probe；手动：requested=selected，
            // routingReason=null（§16 语义）。定向 UPDATE（理由同 updateStatusFields）；
            // 非 PDF 文件类型每种格式只有一个 parser，无路由信息可记，不写。
            if (doc.getFileType() == FileType.PDF) {
                persistPdfParseMetadata(doc, parsed.parseReport());
            }
            ctx.parsed = parsed.withDocumentName(doc.getName());
        } catch (java.io.IOException e) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "关闭源文件流失败：" + e.getMessage());
        }
    }

    /**
     * R6-D.1：ParseReport → document.parse_metadata 统一持久化（成功/失败两条路径
     * 同源同一 serializer，禁止复制两份 Map 拼装逻辑）。
     *
     * <p>定向下 UPDATE 写库（理由同 updateStatusFields：避免全字段 merge 把 worker
     * 快照里的旧 active 位写回撞唯一键），并同步内存快照 doc.parseMetadata——
     * 失败路径后续 fail() 会对 doc 做全字段 save，若不同步，快照里的 null
     * parseMetadata 会把刚写入的 metadata 抹掉。</p>
     *
     * <p>report=null（手动模式/无路由发生）仍写 identity metadata：
     * requested=selected=全局配置、routingReason=null（§16 语义），记录
     * "文档实际由哪个 parser 解析"；仅非 PDF 文件不调用本方法。</p>
     */
    private void persistPdfParseMetadata(DocumentEntity doc, com.rag.ingestion.parse.ParseReport report) {
        Map<String, Object> metadata = parseMetadataOf(doc, report);
        documentRepository.updateParseMetadata(doc.getId(), metadata);
        doc.setParseMetadata(metadata);
    }

    /** R6-D：ParseReport → document.parse_metadata JSON 结构（结构示例见 V8 迁移注释）。
     *  成功/失败两条路径共用本 serializer；失败时 report 来自 PdfAutoParseException。 */
    private Map<String, Object> parseMetadataOf(DocumentEntity doc, com.rag.ingestion.parse.ParseReport report) {
        String requested = ragProperties.getIngestion().getPdfParser().toUpperCase(java.util.Locale.ROOT);
        Map<String, Object> parser = new LinkedHashMap<>();
        parser.put("requested", requested);
        parser.put("selected", report != null ? report.selectedParser() : requested);
        parser.put("routingReason", report != null ? report.routingReason() : null);
        if (report != null) {
            com.rag.ingestion.parse.PdfQualityMetrics m = report.probe();
            Map<String, Object> probe = new LinkedHashMap<>();
            probe.put("pageCount", m.pageCount());
            probe.put("charCount", m.charCount());
            probe.put("charsPerPage", m.charsPerPage());
            probe.put("emptyPageRatio", m.emptyPageRatio());
            probe.put("printableRatio", m.printableRatio());
            probe.put("replacementCharRatio", m.replacementCharRatio());
            probe.put("probeLatencyMs", m.probeLatencyMs());
            parser.put("probe", probe);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("docId", doc.getId());
        metadata.put("parser", parser);
        return metadata;
    }

    private void clean(StageContext ctx) {
        if (ctx.parsingAlreadyDone) {
            // parsed.txt 已存在 ⇒ 清洗产物已随解析落盘
            return;
        }
        ParsedDocument parsed = ctx.parsed;
        if (parsed.isPaged()) {
            // PDF：按页清洗后仍以 \f 拼接，保持「text = join(pages, \f)」不变式（页码可重建）
            List<String> cleanedPages = new ArrayList<>(parsed.pages().size());
            parsed.pages().forEach(page -> cleanedPages.add(cleanText(page.text())));
            String cleaned = String.join(String.valueOf(ParsedDocument.PAGE_SEPARATOR), cleanedPages);
            objectStore.putParsed(ctx.document.getKbId(), ctx.document.getId(), cleaned);
            ctx.cleanedText = cleaned;
        } else {
            String cleaned = cleanText(parsed.text());
            objectStore.putParsed(ctx.document.getKbId(), ctx.document.getId(), cleaned);
            ctx.cleanedText = cleaned;
        }
    }

    private void chunk(StageContext ctx) {
        DocumentEntity doc = ctx.document;
        String persisted = ctx.cleanedText != null
                ? ctx.cleanedText
                : objectStore.getParsed(doc.getKbId(), doc.getId());
        ParsedDocument parsed = ParsedDocument.fromPersisted(doc.getFileType(), doc.getName(), persisted);
        ChunkingConfig cfg = ChunkingConfig.fromMap(doc.getChunkConfig());
        // 表格类文件（R4-Excel）在 STRUCTURE 下走专用分块：Sheet Summary + Row Group
        // （表头重复注入 + 数据行 titlePath）；其余格式/策略不变。
        List<ChunkDraft> drafts = isSpreadsheet(doc)
                ? spreadsheetChunker.chunk(parsed, cfg, doc.getId())
                : chunkerFor(cfg.strategy()).chunk(parsed, cfg, doc.getId());
        doc.setChunkCount(drafts.size());
        // R5-A：为每个 chunk 生成检索增强表示（确定性元数据前缀）。
        // answerContent（draft.text）不变——Judge/Generation/Citation 只见原文。
        List<ChunkDraft> enriched = new ArrayList<>(drafts.size());
        for (ChunkDraft draft : drafts) {
            enriched.add(new ChunkDraft(draft.chunkId(), draft.seq(), draft.titlePath(),
                    draft.page(), draft.charCount(), draft.text(),
                    retrievalContentEnricher.enrich(draft.text(), draft.titlePath(), parsed.documentName())));
        }
        ctx.drafts = enriched;
        log.info("CHUNKING 完成（docId={}, chunks={}）", doc.getId(), enriched.size());
    }

    private void embed(StageContext ctx) {
        List<ChunkDraft> drafts = ctx.drafts;
        List<float[]> vectors = new ArrayList<>(drafts.size());
        for (int from = 0; from < drafts.size(); from += EMBED_BATCH_SIZE) {
            int to = Math.min(from + EMBED_BATCH_SIZE, drafts.size());
            // R5-A：embedding 输入 = retrievalContent（检索增强表示）；无则回退 answerContent
            List<String> batch = drafts.subList(from, to).stream()
                    .map(d -> d.retrievalContent() != null ? d.retrievalContent() : d.text()).toList();
            vectors.addAll(embeddingGateway.embed(batch));
        }
        ctx.vectors = vectors;
    }

    private void index(StageContext ctx) {
        DocumentEntity doc = ctx.document;
        List<ChunkDoc> chunkDocs = new ArrayList<>(ctx.drafts.size());
        for (ChunkDraft draft : ctx.drafts) {
            chunkDocs.add(new ChunkDoc(draft.chunkId(), draft.titlePath(), draft.page(),
                    draft.seq(), draft.charCount(), draft.text(), draft.retrievalContent()));
        }
        esChunkIndex.rebuildChunks(doc.getId(), doc.getKbId(), chunkDocs, ctx.vectors);
    }

    private Chunker chunkerFor(com.rag.domain.enums.ChunkStrategy strategy) {
        Chunker chunker = chunkers.get(strategy);
        if (chunker == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "分块策略未注册：" + strategy);
        }
        return chunker;
    }

    /** 表格类文件：解析产物同为 Markdown 管道表（XlsxParser/CsvParser），共用表格分块。 */
    private static boolean isSpreadsheet(DocumentEntity doc) {
        return doc.getFileType() == FileType.XLSX || doc.getFileType() == FileType.CSV;
    }

    // ------------------------------------------------------------------
    // 终态
    // ------------------------------------------------------------------

    private void fail(IngestionTaskEntity task, DocumentEntity doc, PipelineStage stage, Exception e) {
        ErrorCode code = e instanceof DomainException de ? de.getCode() : ErrorCode.INTERNAL_ERROR;
        String reason = truncate(code.name() + ": " + e.getMessage(), 512);
        log.warn("入库任务失败（docId={}, taskId={}, stage={}, code={}）：{}",
                doc.getId(), task.getId(), stage, code, reason);
        task.setStatus(TaskStatus.FAILED);
        task.setStage(stage);
        task.setFailureStage(stage);
        task.setFailureReason(reason);
        task.setFinishedAt(LocalDateTime.now());
        taskRepository.save(task);
        doc.setStatus(DocumentStatus.FAILED);
        doc.setCurrentStage(stage);
        doc.setFailureStage(stage);
        doc.setFailureReason(reason);
        documentRepository.save(doc);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 清洗：去 BOM、统一换行、去行尾空白、折叠 3+ 连续空行、去首尾空白。 */
    static String cleanText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String cleaned = text.replace("\uFEFF", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        StringBuilder sb = new StringBuilder(cleaned.length());
        for (String line : cleaned.split("\n", -1)) {
            int end = line.length();
            while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
                end--;
            }
            sb.append(line, 0, end).append('\n');
        }
        cleaned = sb.toString();
        cleaned = cleaned.replaceAll("\n{3,}", "\n\n");
        return cleaned.strip();
    }
}
