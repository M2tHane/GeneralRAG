package com.rag.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import com.rag.storage.es.ChunkDoc;
import com.rag.storage.es.EsChunkIndex;
import com.rag.storage.es.EsHit;
import com.rag.storage.minio.ObjectStore;
import com.rag.storage.repository.CleanupTaskRepository;
import com.rag.storage.repository.DocumentRepository;
import com.rag.storage.repository.IngestionTaskRepository;
import com.rag.support.FakeOpenAiServer;
import com.rag.storage.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 存储层集成测试（真实依赖，不用嵌入式替身）：
 * MySQL 8（Flyway 真实迁移 + JPA validate）、MinIO、Elasticsearch 8.14.1（官方镜像，
 * 无 IK → rag.elasticsearch.content-analyzer=standard，见 application-test.yaml）。
 */
@SpringBootTest
@ActiveProfiles("test")
class StorageIT {

    private static final List<String> ALL_TABLES = List.of(
            "knowledge_base", "document", "ingestion_task", "cleanup_task",
            "chat_session", "chat_message", "eval_dataset", "eval_dataset_version",
            "eval_dataset_item", "eval_run", "eval_run_item");

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {        registry.add("minio.bucket", () -> "rag-it");
    }

    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    KnowledgeBaseRepository kbRepository;
    @Autowired
    DocumentRepository documentRepository;
    @Autowired
    IngestionTaskRepository ingestionTaskRepository;
    @Autowired
    CleanupTaskRepository cleanupTaskRepository;
    @Autowired
    ObjectStore objectStore;
    @Autowired
    EsChunkIndex esChunkIndex;

    // ------------------------------------------------------------------
    // 1. Flyway 迁移：11 表齐全（且 JPA ddl-auto=validate 在上下文启动时已通过）
    // ------------------------------------------------------------------
    @Test
    void flywayMigratedAllElevenTables() {
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = database() and table_type = 'BASE TABLE'",
                String.class);
        assertThat(tables).containsAll(ALL_TABLES);
    }

    // ------------------------------------------------------------------
    // 2. document 唯一约束 UNIQUE(kb_id, content_sha256) 生效（KB-8）
    // ------------------------------------------------------------------
    @Test
    void duplicateKbAndSha256RejectedByUniqueConstraint() {
        String kbId = newKb();
        String sha = "a".repeat(64);
        DocumentEntity first = newDocument(kbId, sha);
        documentRepository.saveAndFlush(first);

        assertThat(documentRepository.existsByKbIdAndContentSha256(kbId, sha)).isTrue();

        DocumentEntity duplicate = newDocument(kbId, sha);
        assertThatThrownBy(() -> documentRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // 3. ingestion_task CAS 认领：仅 QUEUED→RUNNING 成功一次
    // ------------------------------------------------------------------
    @Test
    @Transactional
    void ingestionTaskCasClaimSucceedsExactlyOnce() {
        String kbId = newKb();
        DocumentEntity doc = newDocument(kbId, "b".repeat(64));
        documentRepository.saveAndFlush(doc);

        IngestionTaskEntity task = new IngestionTaskEntity();
        task.setDocumentId(doc.getId());
        task.setStatus(TaskStatus.QUEUED);
        task.setStage(PipelineStage.QUEUED);
        task.setAttempt(1);
        ingestionTaskRepository.saveAndFlush(task);

        assertThat(ingestionTaskRepository.findByDocumentId(doc.getId())).isPresent();

        // 第一次认领成功，并补记 startedAt
        assertThat(ingestionTaskRepository.claimIfQueued(task.getId())).isTrue();
        IngestionTaskEntity claimed = ingestionTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(claimed.getStartedAt()).isNotNull();

        // 第二次认领失败（已非 QUEUED）
        assertThat(ingestionTaskRepository.claimIfQueued(task.getId())).isFalse();

        // RUNNING→RUNNING 是幂等空操作（仓储 CAS 只做"状态匹配才更新"，状态机合法性由
        // IngestionTaskManager 负责）——返回 1 但语义等价于无变化
        assertThat(ingestionTaskRepository.casUpdateStatus(task.getId(), TaskStatus.RUNNING, TaskStatus.RUNNING))
                .isEqualTo(1);

        // 启动恢复查询能扫到 RUNNING 任务
        assertThat(ingestionTaskRepository.findByStatusIn(List.of(TaskStatus.QUEUED, TaskStatus.RUNNING)))
                .extracting(IngestionTaskEntity::getId)
                .contains(task.getId());

        // 阶段推进落库
        ingestionTaskRepository.updateStage(task.getId(), PipelineStage.PARSING);
        assertThat(ingestionTaskRepository.findById(task.getId()).orElseThrow().getStage())
                .isEqualTo(PipelineStage.PARSING);
    }

    // ------------------------------------------------------------------
    // 4. cleanup_task 扫描查询 + attempts 递增
    // ------------------------------------------------------------------
    @Test
    @Transactional
    void cleanupTaskScanAndAttemptsIncrement() {
        CleanupTaskEntity task = new CleanupTaskEntity();
        task.setScope(CleanupScope.DOCUMENT);
        task.setRefId(UUID.randomUUID().toString());
        task.setStore(StoreType.MINIO);
        task.setPayload(Map.of("kbId", UUID.randomUUID().toString(), "docId", task.getRefId()));
        task.setStatus(CleanupStatus.PENDING);
        task.setAttempts(0);
        cleanupTaskRepository.saveAndFlush(task);

        List<CleanupTaskEntity> pending = cleanupTaskRepository
                .findByStatusInOrderByUpdatedAtAsc(List.of(CleanupStatus.PENDING, CleanupStatus.FAILED));
        assertThat(pending).extracting(CleanupTaskEntity::getId).contains(task.getId());

        cleanupTaskRepository.incrementAttempts(task.getId());
        assertThat(cleanupTaskRepository.findById(task.getId()).orElseThrow().getAttempts()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 5. MinIO：put/get 往返、parsed 存在性、前缀删除
    // ------------------------------------------------------------------
    @Test
    void minioRoundTripAndPrefixRemoval() throws Exception {
        String kbId = UUID.randomUUID().toString();
        String docId = UUID.randomUUID().toString();
        byte[] sourceBytes = "fake-pdf-bytes-%PDF-1.4".getBytes(StandardCharsets.UTF_8);

        String key = objectStore.putSource(kbId, docId, "研发规范.pdf",
                new ByteArrayInputStream(sourceBytes), sourceBytes.length);
        assertThat(key).isEqualTo("ragsource/" + kbId + "/" + docId + "/source.pdf");

        try (InputStream in = objectStore.getSource(kbId, docId, "pdf")) {
            assertThat(in.readAllBytes()).isEqualTo(sourceBytes);
        }

        assertThat(objectStore.existsParsed(kbId, docId)).isFalse();
        objectStore.putParsed(kbId, docId, "解析+清洗后的文本内容");
        assertThat(objectStore.existsParsed(kbId, docId)).isTrue();
        assertThat(objectStore.getParsed(kbId, docId)).isEqualTo("解析+清洗后的文本内容");

        // 非白名单扩展名被拒（文件名不进键，仅提扩展名）
        assertThatThrownBy(() -> objectStore.putSource(kbId, docId, "恶意.exe",
                new ByteArrayInputStream(new byte[1]), 1))
                .isInstanceOf(DomainException.class);

        // 前缀删除：doc 前缀清掉 parsed 与 source，不影响同 KB 其他文档
        String siblingDoc = UUID.randomUUID().toString();
        objectStore.putParsed(kbId, siblingDoc, "兄弟文档");
        objectStore.removeDoc(kbId, docId);
        assertThat(objectStore.existsParsed(kbId, docId)).isFalse();
        assertThat(objectStore.existsParsed(kbId, siblingDoc)).isTrue();

        objectStore.removeKb(kbId);
        assertThat(objectStore.existsParsed(kbId, siblingDoc)).isFalse();
    }

    // ------------------------------------------------------------------
    // 6. ES：ensureIndex → rebuildChunks（幂等）→ knnSearch（过滤/排序）
    //    → deleteByDoc / deleteByKb。分析器 standard（官方镜像无 IK）。
    // ------------------------------------------------------------------
    @Test
    void esChunkIndexLifecycleAndKnn() {
        esChunkIndex.ensureIndex();
        esChunkIndex.ensureIndex(); // 幂等：已存在时不重建、分析器探测通过

        String kbA = UUID.randomUUID().toString();
        String kbB = UUID.randomUUID().toString();
        String docA = UUID.randomUUID().toString();
        String docB = UUID.randomUUID().toString();

        ChunkDoc chunkA1 = new ChunkDoc(docA + "-c0001", "研发规范>架构", 3, 1, 12, "微服务架构设计原则");
        ChunkDoc chunkA2 = new ChunkDoc(docA + "-c0002", "研发规范>测试", null, 2, 12, "接口测试覆盖要求");
        // 向量维度必须匹配实际索引 mapping（开发环境索引为真实 embedding 维度），
        // 不能假设 test profile 的 8 维——共享开发索引是本套件的前提
        float[] vectorA1 = FakeOpenAiServer.unitVector(0);
        float[] vectorA2 = FakeOpenAiServer.unitVector(1);

        assertThat(esChunkIndex.rebuildChunks(docA, kbA, List.of(chunkA1, chunkA2),
                List.of(vectorA1, vectorA2))).isEqualTo(2);

        ChunkDoc chunkB1 = new ChunkDoc(docB + "-c0001", "其他知识库", 1, 1, 4, "无关内容");
        float[] vectorB1 = FakeOpenAiServer.unitVector(2);
        esChunkIndex.rebuildChunks(docB, kbB, List.of(chunkB1), List.of(vectorB1));

        // 幂等重建：重复执行不产生重复分块
        esChunkIndex.rebuildChunks(docA, kbA, List.of(chunkA1, chunkA2), List.of(vectorA1, vectorA2));

        // kb 过滤 + 分数排序 + 字段映射
        List<EsHit> hitsA = esChunkIndex.knnSearch(kbA, vectorA1, 5, 20);
        assertThat(hitsA).hasSize(2);
        assertThat(hitsA.get(0).chunkId()).isEqualTo(docA + "-c0001");
        assertThat(hitsA.get(0).docId()).isEqualTo(docA);
        assertThat(hitsA.get(0).titlePath()).isEqualTo("研发规范>架构");
        assertThat(hitsA.get(0).page()).isEqualTo(3);
        assertThat(hitsA.get(0).seq()).isEqualTo(1);
        assertThat(hitsA.get(0).charCount()).isEqualTo(12);
        assertThat(hitsA.get(0).content()).isEqualTo("微服务架构设计原则");
        assertThat(hitsA.get(0).score()).isGreaterThanOrEqualTo(hitsA.get(1).score());
        assertThat(hitsA).extracting(EsHit::docId).containsExactlyInAnyOrder(docA, docA);

        // 跨库隔离：kbB 查不到 kbA 的块
        List<EsHit> hitsB = esChunkIndex.knnSearch(kbB, vectorA1, 5, 20);
        assertThat(hitsB).hasSize(1);
        assertThat(hitsB.get(0).docId()).isEqualTo(docB);

        // deleteByDoc 后立即不再命中
        assertThat(esChunkIndex.deleteByDoc(docA)).isEqualTo(2);
        assertThat(esChunkIndex.knnSearch(kbA, vectorA1, 5, 20)).isEmpty();
        assertThat(esChunkIndex.knnSearch(kbB, vectorA1, 5, 20)).hasSize(1);

        // deleteByKb 后立即不再命中
        assertThat(esChunkIndex.deleteByKb(kbB)).isEqualTo(1);
        assertThat(esChunkIndex.knnSearch(kbB, vectorB1, 5, 20)).isEmpty();
    }

    // ------------------------------------------------------------------
    // helper
    // ------------------------------------------------------------------

    private String newKb() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setName("it-kb-" + UUID.randomUUID());
        kb.setDescription("StorageIT");
        kbRepository.saveAndFlush(kb);
        return kb.getId();
    }

    private static DocumentEntity newDocument(String kbId, String sha256) {
        DocumentEntity doc = new DocumentEntity();
        doc.setKbId(kbId);
        doc.setName("设计文档.md");
        doc.setFileType(FileType.MD);
        doc.setSizeBytes(1024);
        doc.setContentSha256(sha256);
        doc.setStatus(DocumentStatus.QUEUED);
        doc.setCurrentStage(PipelineStage.QUEUED);
        doc.setChunkConfig(Map.of("strategy", "LENGTH_OVERLAP", "maxLength", 1000, "overlap", 150));
        return doc;
    }
}
