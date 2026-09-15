package com.rag.ingestion;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.rag.domain.entity.DocumentEntity;
import com.rag.domain.entity.IngestionTaskEntity;
import com.rag.domain.enums.DocumentStatus;
import com.rag.domain.enums.FileType;
import com.rag.domain.enums.PipelineStage;
import com.rag.domain.enums.TaskStatus;
import com.rag.ingestion.chunk.Chunker;
import com.rag.ingestion.chunk.LengthOverlapChunker;
import com.rag.ingestion.chunk.StructureChunker;
import com.rag.ingestion.parse.ParserRouter;
import com.rag.ingestion.pipeline.StageFlow;
import com.rag.config.RagProperties;
import com.rag.storage.es.EsChunkIndex;
import com.rag.storage.minio.ObjectStore;
import com.rag.storage.repository.DocumentRepository;
import com.rag.storage.repository.IngestionTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入库任务状态机单元测试（Mockito，不起 Spring）：
 * 合法迁移表、终态拒绝、失败落库、重试续跑不重复解析（PARSING spy 验证）。
 */
class IngestionTaskManagerTest {

    private IngestionTaskRepository taskRepository;
    private DocumentRepository documentRepository;
    private ObjectStore objectStore;
    private EsChunkIndex esChunkIndex;
    private EmbeddingGateway embeddingGateway;
    private IngestionTaskManager manager;

    private static final String DOC_ID = "22222222-2222-2222-2222-222222222222";
    private static final String KB_ID = "33333333-3333-3333-3333-333333333333";

    @BeforeEach
    void setUp() {
        taskRepository = mock(IngestionTaskRepository.class);
        documentRepository = mock(DocumentRepository.class);
        objectStore = mock(ObjectStore.class);
        esChunkIndex = mock(EsChunkIndex.class);
        embeddingGateway = mock(EmbeddingGateway.class);

        RagProperties properties = new RagProperties();
        ParserRouter parserRouter = new ParserRouter(List.of(
                new com.rag.ingestion.parse.TextParser(),
                new com.rag.ingestion.parse.MarkdownParser(),
                new com.rag.ingestion.parse.PdfBoxParser()));

        manager = new IngestionTaskManager(taskRepository, documentRepository, objectStore,
                esChunkIndex, parserRouter,
                List.of(new LengthOverlapChunker(), new StructureChunker()),
                embeddingGateway, properties);
    }

    // ------------------------------------------------------------------
    // 状态机迁移表
    // ------------------------------------------------------------------

    @Test
    void legalStageProgressionsAccepted() {
        assertThat(StageFlow.stagesFrom(PipelineStage.QUEUED)).hasSize(5); // PARSING..INDEXING
        StageFlow.assertLegal(PipelineStage.PARSING, PipelineStage.CLEANING);
        StageFlow.assertLegal(PipelineStage.INDEXING, PipelineStage.COMPLETED);
        StageFlow.assertLegal(PipelineStage.CHUNKING, PipelineStage.CHUNKING); // 续跑同阶段重入合法
    }

    @Test
    void completedIsTerminal() {
        assertThatThrownBy(() -> StageFlow.stagesFrom(PipelineStage.COMPLETED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> StageFlow.assertLegal(PipelineStage.COMPLETED, PipelineStage.PARSING))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void skippingStagesRejected() {
        assertThatThrownBy(() -> StageFlow.assertLegal(PipelineStage.PARSING, PipelineStage.CHUNKING))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> StageFlow.assertLegal(PipelineStage.CLEANING, PipelineStage.EMBEDDING))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> StageFlow.assertLegal(PipelineStage.QUEUED, PipelineStage.COMPLETED))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------
    // 失败落库（failureStage/failureReason 记录）
    // ------------------------------------------------------------------

    @Test
    void failureRecordsStageAndReason() {
        stubHappyPathEntities();
        // PARSING 阶段抛异常（源文件读取失败）
        when(objectStore.existsParsed(KB_ID, DOC_ID)).thenReturn(false);
        when(objectStore.getSource(eq(KB_ID), eq(DOC_ID), anyString()))
                .thenThrow(new RuntimeException("MinIO 连接失败"));

        manager.processOne(DOC_ID);

        IngestionTaskEntity saved = capturedTask();
        assertThat(saved.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(saved.getFailureStage()).isEqualTo(PipelineStage.PARSING);
        assertThat(saved.getFailureReason()).contains("MinIO 连接失败");
    }

    // ------------------------------------------------------------------
    // 重试续跑：PARSING 产物已存在时不重跑解析（spy 判据）
    // ------------------------------------------------------------------

    @Test
    void retryFromChunkingSkipsParsing() {
        // 文档上次在 EMBEDDING 失败 → task.stage=EMBEDDING，续跑从 EMBEDDING 起
        IngestionTaskEntity task = task(TaskStatus.QUEUED, PipelineStage.EMBEDDING, 2);
        DocumentEntity doc = doc(DocumentStatus.PROCESSING);
        when(taskRepository.findByDocumentId(DOC_ID)).thenReturn(Optional.of(task));
        when(taskRepository.claimIfQueued(task.getId())).thenReturn(true);
        when(taskRepository.updateStage(anyString(), any(com.rag.domain.enums.PipelineStage.class))).thenReturn(1);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        when(documentRepository.existsById(DOC_ID)).thenReturn(true);
        // 注意：不 stub existsParsed —— 续跑从 EMBEDDING 起，PARSING 根本不应被调用
        List<com.rag.ingestion.chunk.ChunkDraft> drafts = List.of(
                new com.rag.ingestion.chunk.ChunkDraft(DOC_ID + "-c0000", 0, "t", null, 5, "hello"));
        stubChunkingReuse(doc, drafts);
        when(embeddingGateway.embed(any())).thenReturn(List.of(new float[]{0.1f, 0.2f}));

        manager.processOne(DOC_ID);

        // PARSING 阶段执行逻辑未被触发：既没读源文件也没写 parsed.txt
        verify(objectStore, never()).getSource(any(), any(), any());
        verify(objectStore, never()).putParsed(any(), any(), any());
        // 直接从 EMBEDDING 续跑至完成
        IngestionTaskEntity saved = capturedTask();
        assertThat(saved.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        verify(esChunkIndex).rebuildChunks(eq(DOC_ID), eq(KB_ID), any(), any());
    }

    @Test
    void retryAfterParsingCompletionReusesParsedTextWithoutRerunningParser() {
        // task.stage=CHUNKING（PARSING/CLEANING 已完成）→ 依赖 parsed.txt 证据复用，不再解析
        IngestionTaskEntity task = task(TaskStatus.QUEUED, PipelineStage.CHUNKING, 2);
        DocumentEntity doc = doc(DocumentStatus.PROCESSING);
        when(taskRepository.findByDocumentId(DOC_ID)).thenReturn(Optional.of(task));
        when(taskRepository.claimIfQueued(task.getId())).thenReturn(true);
        when(taskRepository.updateStage(anyString(), any(com.rag.domain.enums.PipelineStage.class))).thenReturn(1);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        when(documentRepository.existsById(DOC_ID)).thenReturn(true);
        // 注意：CHUNKING 直接从 parsed.txt 续跑——manager.chunk() 走 getParsed 分支
        String persisted = "# 标题\n第一段内容。\n\n第二段内容。";
        stubForReuse(doc, persisted);
        List<com.rag.ingestion.chunk.ChunkDraft> drafts = List.of(
                new com.rag.ingestion.chunk.ChunkDraft(DOC_ID + "-c0000", 0, "t", null, 5, "hello"));
        when(embeddingGateway.embed(any())).thenReturn(List.of(new float[]{0.3f}));

        manager.processOne(DOC_ID);

        // 解析器不重跑：不读源文件、不写 parsed.txt（清洗产物已存在）
        verify(objectStore, never()).getSource(any(), any(), any());
        verify(objectStore, never()).putParsed(any(), any(), any());
        assertThat(capturedTask().getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    // ------------------------------------------------------------------
    // CAS 认领失败 → 静默跳过
    // ------------------------------------------------------------------

    @Test
    void alreadyClaimedTaskSkippedSilently() {
        IngestionTaskEntity task = task(TaskStatus.QUEUED, PipelineStage.QUEUED, 1);
        when(taskRepository.findByDocumentId(DOC_ID)).thenReturn(Optional.of(task));
        when(taskRepository.claimIfQueued(task.getId())).thenReturn(false);

        manager.processOne(DOC_ID);

        verify(documentRepository, never()).findById(any());
        verify(esChunkIndex, never()).rebuildChunks(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void stubHappyPathEntities() {
        IngestionTaskEntity task = task(TaskStatus.QUEUED, PipelineStage.QUEUED, 1);
        DocumentEntity doc = doc(DocumentStatus.QUEUED);
        when(taskRepository.findByDocumentId(DOC_ID)).thenReturn(Optional.of(task));
        when(taskRepository.claimIfQueued(task.getId())).thenReturn(true);
        // B1 修复后：阶段循环依赖 updateStage 返回值与 existsById 二次确认
        when(taskRepository.updateStage(anyString(), any(com.rag.domain.enums.PipelineStage.class))).thenReturn(1);
        when(documentRepository.existsById(DOC_ID)).thenReturn(true);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        lenient().when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(taskRepository.findById(any())).thenReturn(Optional.of(task));
    }

    /** 续跑（CHUNKING 起）复用 parsed.txt 的公共 stub。 */
    private void stubForReuse(DocumentEntity doc, String persistedText) {
        doc.setChunkConfig(Map.of("strategy", "STRUCTURE", "maxLength", 1000, "overlap", 0));
        lenient().when(objectStore.getParsed(KB_ID, DOC_ID)).thenReturn(persistedText);
        lenient().when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(taskRepository.findById(any())).thenReturn(Optional.of(new IngestionTaskEntity()));
    }

    /** 续跑（EMBEDDING 起）直接复用上下文 drafts 的 stub（模拟 CHUNKING 产物在场）。 */
    private void stubChunkingReuse(DocumentEntity doc,
                                   List<com.rag.ingestion.chunk.ChunkDraft> drafts) {
        doc.setChunkConfig(Map.of("strategy", "STRUCTURE", "maxLength", 1000, "overlap", 0));
        lenient().when(objectStore.getParsed(eq(KB_ID), eq(DOC_ID))).thenReturn("# t\nbody");
        lenient().when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(taskRepository.findById(any())).thenReturn(Optional.of(new IngestionTaskEntity()));
        lenient().when(esChunkIndex.rebuildChunks(any(), any(), any(), any())).thenReturn(drafts.size());
        // 供 capturedTask() 校验 EMBEDDING 起步的续跑终态
        lenient().when(embeddingGateway.embed(any()))
                .thenReturn(List.of(new float[]{0.1f, 0.2f}));
    }

    private IngestionTaskEntity capturedTask() {
        org.mockito.ArgumentCaptor<IngestionTaskEntity> captor =
                org.mockito.ArgumentCaptor.forClass(IngestionTaskEntity.class);
        verify(taskRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private static org.mockito.verification.VerificationMode atLeastOnce() {
        return org.mockito.Mockito.atLeastOnce();
    }

    private static IngestionTaskEntity task(TaskStatus status, PipelineStage stage, int attempt) {
        IngestionTaskEntity task = new IngestionTaskEntity();
        task.setId("task-1");
        task.setDocumentId(DOC_ID);
        task.setStatus(status);
        task.setStage(stage);
        task.setAttempt(attempt);
        return task;
    }

    private static DocumentEntity doc(DocumentStatus status) {
        DocumentEntity doc = new DocumentEntity();
        doc.setId(DOC_ID);
        doc.setKbId(KB_ID);
        doc.setName("示例文档.txt");
        doc.setFileType(FileType.TXT);
        doc.setStatus(status);
        doc.setCurrentStage(PipelineStage.QUEUED);
        doc.setChunkConfig(Map.of("strategy", "LENGTH_OVERLAP", "maxLength", 1000, "overlap", 100));
        return doc;
    }
}
