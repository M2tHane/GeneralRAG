package com.rag.ingestion;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rag.domain.entity.DocumentEntity;
import com.rag.domain.entity.IngestionTaskEntity;
import com.rag.domain.entity.KnowledgeBaseEntity;
import com.rag.domain.enums.ChunkStrategy;
import com.rag.domain.enums.DocumentStatus;
import com.rag.domain.enums.FileType;
import com.rag.domain.enums.PipelineStage;
import com.rag.domain.enums.TaskStatus;
import com.rag.storage.es.ChunkDoc;
import com.rag.storage.es.EsChunkIndex;
import com.rag.storage.es.EsHit;
import com.rag.storage.minio.ObjectStore;
import com.rag.storage.repository.DocumentRepository;
import com.rag.storage.repository.IngestionTaskRepository;
import com.rag.storage.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入库流水线集成测试（真实 MySQL/ES/MinIO + 假 embedding 服务）：
 * happy path 全阶段、失败→重试复用已完成阶段、重建幂等（分块不翻倍）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class IngestionPipelineIT extends com.rag.support.SharedInfraSupport {

    private static com.rag.support.FakeOpenAiServer fakeModel;

    @Autowired IngestionTaskManager taskManager;
    @Autowired KnowledgeBaseRepository kbRepository;
    @Autowired DocumentRepository documentRepository;
    @Autowired IngestionTaskRepository taskRepository;
    @Autowired ObjectStore objectStore;
    @Autowired EsChunkIndex esChunkIndex;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        fakeModel = newFakeModel();
        acquire();
        registry.add("spring.datasource.url", mysql()::getJdbcUrl);
        registry.add("spring.datasource.username", mysql()::getUsername);
        registry.add("spring.datasource.password", mysql()::getPassword);
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + es().getHost() + ":" + es().getMappedPort(9200));
        registry.add("minio.endpoint",
                () -> "http://" + minio().getHost() + ":" + minio().getMappedPort(9000));
        registry.add("minio.access-key", () -> "minioadmin");
        registry.add("minio.secret-key", () -> "minioadmin");
        registry.add("minio.bucket", () -> "ing-it");
        registry.add("rag.models.embedding.base-url", () -> fakeModel.baseUrl());
        registry.add("rag.models.chat.base-url", () -> fakeModel.baseUrl());
        registry.add("rag.models.startup-check", () -> "false");
    }

    @AfterAll
    static void tearDown() {
        if (fakeModel != null) {
            fakeModel.stop();
        }
    }

    private String newKb() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setName("ing-it-" + UUID.randomUUID());
        return kbRepository.save(kb).getId();
    }

    /** 组装一篇“已上传”文档：源文件已进 MinIO + QUEUED 任务行（上传接口的受理产物）。 */
    private String uploadDoc(String kbId, String markdown, ChunkStrategy strategy) {
        String docId = UUID.randomUUID().toString();
        DocumentEntity doc = new DocumentEntity();
        doc.setId(docId);
        doc.setKbId(kbId);
        doc.setName("部署手册.md");
        doc.setFileType(FileType.MD);
        doc.setSizeBytes(markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8).length); // 字节数而非字符数
        doc.setContentSha256("d".repeat(64));
        doc.setStatus(DocumentStatus.QUEUED);
        doc.setCurrentStage(PipelineStage.QUEUED);
        doc.setChunkConfig(Map.of("strategy", strategy.name(), "maxLength", 400, "overlap", 50));
        documentRepository.save(doc);

        byte[] sourceBytes = markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        objectStore.putSource(kbId, docId, "部署手册.md",
                new java.io.ByteArrayInputStream(sourceBytes), sourceBytes.length);

        IngestionTaskEntity task = new IngestionTaskEntity();
        task.setDocumentId(docId);
        task.setStatus(TaskStatus.QUEUED);
        task.setStage(PipelineStage.QUEUED);
        task.setAttempt(1);
        taskRepository.save(task);
        return docId;
    }

    @Test
    void happyPathCompletesAllStagesAndIndexesChunks() {
        String kbId = newKb();
        // maxLength=400：第一节约 470 字符即触发切块，确保切成 ≥2 块
        String markdown = "# 架构\n" + "网关负责渠道路由与签名验签。".repeat(30)
                + "\n\n# 回调\n" + "回调先落库再分发，超时进入重试队列。".repeat(30);
        String docId = uploadDoc(kbId, markdown, ChunkStrategy.STRUCTURE);
        esChunkIndex.ensureIndex();

        taskManager.processOne(docId);

        IngestionTaskEntity task = taskRepository.findByDocumentId(docId).orElseThrow();
        DocumentEntity doc = documentRepository.findById(docId).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(doc.getChunkCount()).isGreaterThanOrEqualTo(2);

        // 解析文本已落 MinIO（PARSING 完成判据）
        assertThat(objectStore.existsParsed(kbId, docId)).isTrue();

        // ES 中可检索到分块（向量来自假 embedding 服务）
        List<EsHit> hits = esChunkIndex.knnSearch(kbId, fakeVector("渠道路由"), 5, 50);
        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(h -> assertThat(h.chunkId()).startsWith(docId));
    }

    @Test
    void failureThenRetryReusesCompletedStagesAndDoesNotDuplicateChunks() {
        String kbId = newKb();
        String markdown = "# 标题一\n第一段内容。\n\n# 标题二\n第二段内容。";
        String docId = uploadDoc(kbId, markdown, ChunkStrategy.STRUCTURE);
        esChunkIndex.ensureIndex();

        // 第一轮：embedding 失败
        fakeModel.setEmbedFailure(true);
        taskManager.processOne(docId);

        IngestionTaskEntity task = taskRepository.findByDocumentId(docId).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getFailureStage()).isEqualTo(PipelineStage.EMBEDDING);
        DocumentEntity doc = documentRepository.findById(docId).orElseThrow();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.FAILED);
        // 解析文本已落盘（已完成阶段证据）
        assertThat(objectStore.existsParsed(kbId, docId)).isTrue();

        // 重试（API 层会把任务置回 QUEUED）：embedding 服务恢复
        fakeModel.setEmbedFailure(false);
        task.setStatus(TaskStatus.QUEUED);
        task.setAttempt(task.getAttempt() + 1);
        taskRepository.save(task);
        taskManager.processOne(docId);

        IngestionTaskEntity after = taskRepository.findByDocumentId(docId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(after.getAttempt()).isEqualTo(2);

        // 幂等重建：ES 中该 doc 分块数与 doc.chunkCount 一致，不翻倍
        List<EsHit> hits = esChunkIndex.knnSearch(kbId, fakeVector("标题"), 50, 500);
        long docChunks = hits.stream().filter(h -> h.docId().equals(docId)).count();
        assertThat(docChunks).isEqualTo(documentRepository.findById(docId).orElseThrow().getChunkCount());
    }

    /** 与 FakeOpenAiServer.embedVector 相同规则的向量（保证维度与可检索性）。 */
    private static float[] fakeVector(String text) {
        return com.rag.support.FakeOpenAiServer.embedVector(text);
    }
}
