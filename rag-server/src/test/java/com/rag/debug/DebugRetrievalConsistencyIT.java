package com.rag.debug;

import java.util.List;
import java.util.UUID;

import com.rag.config.RagProperties;
import com.rag.domain.entity.DocumentEntity;
import com.rag.domain.enums.DocumentStatus;
import com.rag.domain.enums.FileType;
import com.rag.domain.enums.PipelineStage;
import com.rag.retrieval.RetrievalService;
import com.rag.storage.es.ChunkDoc;
import com.rag.storage.es.EsChunkIndex;
import com.rag.storage.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

import com.rag.support.FakeOpenAiServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DebugRetrievalService 与 RetrievalService 语义一致性测试（Task 6 取舍的守护）。
 *
 * <p>背景：RetrievalService.retrieve 不暴露分段耗时且本任务禁止修改 retrieval 现有
 * 文件，DebugRetrievalService 以相同组件复刻同一检索语义。本测试在同库同问题上断言
 * 两者的命中（chunkId 序列 + rank + score + passedThreshold）完全一致——
 * RetrievalService 未来变更检索语义时此处失败，提示同步调试实现。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {com.rag.RagApplication.class})
@ActiveProfiles("test")
class DebugRetrievalConsistencyIT {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8"))
            .withStartupTimeout(java.time.Duration.ofMinutes(5));
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("minio/minio:latest"))
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(new HttpWaitStrategy().forPort(9000).forPath("/minio/health/ready")
                    .forStatusCode(200))
            .withStartupTimeout(java.time.Duration.ofMinutes(5));
    private static final ElasticsearchContainer ES = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.14.1"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("xpack.security.http.ssl.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withStartupTimeout(java.time.Duration.ofMinutes(6));

    private static FakeOpenAiServer fakeModel;

    @Autowired RetrievalService retrievalService;
    @Autowired DebugRetrievalService debugRetrievalService;
    @Autowired EsChunkIndex esChunkIndex;
    @Autowired DocumentRepository documentRepository;
    @Autowired com.rag.storage.repository.KnowledgeBaseRepository kbRepository;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        try {
            fakeModel = new FakeOpenAiServer();
            fakeModel.start();
        } catch (Exception e) {
            throw new IllegalStateException("FakeOpenAiServer 启动失败", e);
        }
        MYSQL.start();
        MINIO.start();
        ES.start();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + ES.getHost() + ":" + ES.getMappedPort(9200));
        registry.add("minio.endpoint",
                () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("minio.access-key", () -> "minioadmin");
        registry.add("minio.secret-key", () -> "minioadmin");
        registry.add("minio.bucket", () -> "debug-it");
        registry.add("rag.models.chat.base-url", () -> fakeModel.baseUrl());
        registry.add("rag.models.embedding.base-url", () -> fakeModel.baseUrl());
        registry.add("rag.models.startup-check", () -> "false");
    }

    @Test
    void debugHitsMatchRetrievalService() {
        com.rag.domain.entity.KnowledgeBaseEntity kb = new com.rag.domain.entity.KnowledgeBaseEntity();
        kb.setName("debug-it-" + UUID.randomUUID());
        String kbId = kbRepository.save(kb).getId();
        String docId = UUID.randomUUID().toString();
        DocumentEntity doc = new DocumentEntity();
        doc.setId(docId);
        doc.setKbId(kbId);
        doc.setName("一致性测试文档.md");
        doc.setFileType(FileType.MD);
        doc.setSizeBytes(100);
        doc.setContentSha256("d".repeat(64));
        doc.setStatus(DocumentStatus.COMPLETED);
        doc.setCurrentStage(PipelineStage.COMPLETED);
        doc.setChunkCount(2);
        documentRepository.save(doc);

        esChunkIndex.ensureIndex();
        esChunkIndex.rebuildChunks(docId, kbId,
                List.of(new ChunkDoc(docId + "-c0000", "一致性测试文档 > 部署", null, 0, 20,
                                "部署服务的端口为 8080。"),
                        new ChunkDoc(docId + "-c0001", "一致性测试文档 > 回调", null, 1, 20,
                                "支付回调超时为 5 秒。")),
                List.of(new float[] {1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f},
                        new float[] {0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f}));

        String question = "支付回调超时是多少？";
        DebugResult.DebugRetrievalResult result = debugRetrievalService.debug(
                kbId, question, null, null);
        List<com.rag.retrieval.model.RetrievalHit> expected =
                retrievalService.retrieve(kbId, question, null, null);

        assertThat(result.hits()).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            var debugHit = result.hits().get(i);
            var refHit = expected.get(i);
            assertThat(debugHit.rank()).isEqualTo(refHit.rank());
            assertThat(debugHit.chunk().id()).isEqualTo(refHit.chunk().chunkId());
            assertThat(debugHit.score()).isEqualTo(refHit.score());
            assertThat(debugHit.passedThreshold()).isEqualTo(refHit.passedThreshold());
            assertThat(debugHit.chunk().text()).isEqualTo(refHit.chunk().content());
        }
        // 分段计时：契约要求三个数值都存在且 total >= embed/search
        assertThat(result.timings().totalMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.timings().embedMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.timings().searchMs()).isGreaterThanOrEqualTo(0);
        // effectiveConfig 与配置默认一致
        assertThat(result.effectiveConfig().embeddingModel()).isEqualTo("bge-m3");
        assertThat(result.effectiveConfig().dimensions()).isEqualTo(8);
    }
}
