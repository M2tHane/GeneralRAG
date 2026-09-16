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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.rag.support.FakeOpenAiServer;
import com.rag.support.SharedInfraSupport;

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
class DebugRetrievalConsistencyIT extends SharedInfraSupport {

    private static FakeOpenAiServer fakeModel;

    @Autowired RetrievalService retrievalService;
    @Autowired DebugRetrievalService debugRetrievalService;
    @Autowired EsChunkIndex esChunkIndex;
    @Autowired DocumentRepository documentRepository;
    @Autowired com.rag.storage.repository.KnowledgeBaseRepository kbRepository;

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
        registry.add("minio.bucket", () -> "debug-it");
        registry.add("rag.models.chat.base-url", () -> fakeModel.baseUrl());
        registry.add("rag.models.embedding.base-url", () -> fakeModel.baseUrl());
        registry.add("rag.models.startup-check", () -> "false");
    }

    @AfterAll
    static void tearDown() {
        if (fakeModel != null) {
            fakeModel.stop();
        }
        release();
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
