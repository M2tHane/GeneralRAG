package com.rag.eval;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rag.config.RagProperties;
import com.rag.domain.entity.DocumentEntity;
import com.rag.domain.entity.EvalDatasetVersionEntity;
import com.rag.domain.entity.EvalRunEntity;
import com.rag.domain.entity.EvalRunItemEntity;
import com.rag.domain.entity.KnowledgeBaseEntity;
import com.rag.domain.enums.DatasetType;
import com.rag.domain.enums.DocumentStatus;
import com.rag.domain.enums.EvalRunStatus;
import com.rag.domain.enums.FileType;
import com.rag.domain.enums.PipelineStage;
import com.rag.storage.es.ChunkDoc;
import com.rag.storage.es.EsChunkIndex;
import com.rag.storage.repository.DocumentRepository;
import com.rag.storage.repository.EvalRunItemRepository;
import com.rag.storage.repository.EvalRunRepository;
import com.rag.storage.repository.KnowledgeBaseRepository;
import com.rag.support.FakeOpenAiServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测全链路集成测试（Task 6，Testcontainers + FakeOpenAiServer）。
 *
 * <p>不依赖 Task 4 控制器（T4 并行中）：直接用 repository 组装知识库与已入库文档
 * （与 QaStreamIT 相同的 ES 直写方式）；经 EvalService 导入 JSON 数据集 → runSync
 * 同步执行 → 断言 configSnapshot / hit / metrics / 人工标注。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {com.rag.RagApplication.class})
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EvalFlowIT {

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

    @Autowired EvalService evalService;
    @Autowired EvalRunRepository runRepository;
    @Autowired EvalRunItemRepository runItemRepository;
    @Autowired EvalRunViewService runViewService;
    @Autowired KnowledgeBaseRepository kbRepository;
    @Autowired DocumentRepository documentRepository;
    @Autowired EsChunkIndex esChunkIndex;
    @Autowired RagProperties ragProperties;

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
        registry.add("minio.bucket", () -> "eval-it");
        registry.add("rag.models.chat.base-url", () -> fakeModel.baseUrl());
        registry.add("rag.models.embedding.base-url", () -> fakeModel.baseUrl());
        registry.add("rag.models.startup-check", () -> "false");
    }

    @AfterAll
    static void tearDownAll() {
        if (fakeModel != null) {
            fakeModel.stop();
        }
    }

    // ------------------------------------------------------------------
    // 场景数据
    // ------------------------------------------------------------------

    static final String DOC_NAME = "支付接口文档.md";
    static final String DOC_TITLE_PATH = "支付接口文档 > 回调配置";

    static String kbId;
    static EvalService.EvalDatasetView datasetV1;
    static EvalService.EvalDatasetView datasetV2;
    static UUID runId;

    @Test
    @Order(1)
    void prepareKbAndIndexedDoc() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setName("eval-it-" + UUID.randomUUID());
        kbId = kbRepository.save(kb).getId();

        // 直写 ES 组装已入库文档（不依赖 T4/T3 控制器与流水线）
        String docId = UUID.randomUUID().toString();
        DocumentEntity doc = new DocumentEntity();
        doc.setId(docId);
        doc.setKbId(kbId);
        doc.setName(DOC_NAME);
        doc.setFileType(FileType.MD);
        doc.setSizeBytes(100);
        doc.setContentSha256("e".repeat(64));
        doc.setStatus(DocumentStatus.COMPLETED);
        doc.setCurrentStage(PipelineStage.COMPLETED);
        doc.setChunkCount(2);
        documentRepository.save(doc);

        esChunkIndex.ensureIndex();
        esChunkIndex.rebuildChunks(docId, kbId,
                List.of(new ChunkDoc(docId + "-c0000", DOC_TITLE_PATH, null, 0, 20,
                                "支付回调确认超时为 5 秒。"),
                        new ChunkDoc(docId + "-c0001", "支付接口文档 > 签名算法", null, 1, 20,
                                "请求签名使用 HMAC-SHA256。")),
                List.of(new float[] {1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f},
                        new float[] {0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f}));
    }

    @Test
    @Order(2)
    void importDatasetCreatesVersion1And2() {
        String json1 = """
                [
                  {"question":"支付回调确认超时是多少？","referenceAnswer":"5 秒",
                   "answerable":true,"category":"DIRECT",
                   "evidence":[{"docName":"%s","titlePath":"%s"}]},
                  {"question":"知识库里完全没有的话题问题？","answerable":false,"category":"OUT_OF_KB","evidence":[]}
                ]
                """.formatted(DOC_NAME, DOC_TITLE_PATH);
        datasetV1 = evalService.importDataset(multipartFile(json1, "评测集.json"), "eval-it-集", DatasetType.TUNING);
        assertThat(datasetV1.latestVersion().versionNo()).isEqualTo(1);
        assertThat(datasetV1.latestVersion().itemCount()).isEqualTo(2);
        assertThat(datasetV1.datasetType()).isEqualTo(DatasetType.TUNING);

        // 同名再导入 → 版本 2（不可变追加，EV-4）
        String json2 = """
                [
                  {"question":"签名算法是什么？","answerable":true,"category":"TERM_VARIATION",
                   "evidence":[{"docName":"%s","titlePath":"%s"}]}
                ]
                """.formatted(DOC_NAME, "支付接口文档 > 签名算法");
        datasetV2 = evalService.importDataset(multipartFile(json2, "评测集.json"), "eval-it-集", DatasetType.TUNING);
        assertThat(datasetV2.id()).isEqualTo(datasetV1.id());
        assertThat(datasetV2.latestVersion().versionNo()).isEqualTo(2);
        assertThat(datasetV2.latestVersion().versionId()).isNotEqualTo(datasetV1.latestVersion().versionId());

        List<EvalService.EvalDatasetView> datasets = evalService.listDatasets();
        assertThat(datasets).extracting(EvalService.EvalDatasetView::name).contains("eval-it-集");
    }

    @Test
    @Order(3)
    void runCompletesWithMetricsAndConfigSnapshot() {
        fakeModel.setChatAnswer("依据文档，支付回调确认超时为 5 秒。");
        // 显式指定版本 1（2 个样本；若不传 versionId，契约语义是"最新版本"=v2）
        EvalRunEntity run = evalService.createRun(UUID.fromString(datasetV1.id()),
                UUID.fromString(datasetV1.latestVersion().versionId()),
                UUID.fromString(kbId), null, null);
        runId = UUID.fromString(run.getId());
        assertThat(run.getStatus()).isEqualTo(EvalRunStatus.RUNNING);

        // 同步驱动（等价异步 submitAsync 的执行体）
        evalService.runSync(runId);

        EvalRunEntity finished = evalService.getRun(runId);
        assertThat(finished.getStatus()).isEqualTo(EvalRunStatus.COMPLETED);
        assertThat(finished.getFailureReason()).isNull();
        assertThat(finished.getFinishedAt()).isNotNull();

        // configSnapshot 字段齐全（EV-4）
        Map<String, Object> snapshot = finished.getConfigSnapshot();
        assertThat(snapshot).containsKeys("chatModel", "embeddingModel", "embeddingDimensions",
                "topK", "minScore");
        assertThat(snapshot.get("embeddingDimensions")).isEqualTo(8);
        assertThat(snapshot.get("topK")).isEqualTo(ragProperties.getRetrieval().getTopK());

        // metrics 结构（EV-3：answerable=1 题，命中 → hitAt*=1.0；OUT_OF_KB 不计分母）
        Map<String, Object> metrics = finished.getMetrics();
        assertThat(metrics).containsKeys("hitAt1", "hitAt3", "hitAt5", "avgLatencyMs", "itemCount");
        assertThat(metrics.get("itemCount")).isEqualTo(2);
        assertThat(((Number) metrics.get("hitAt1")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) metrics.get("hitAt3")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) metrics.get("hitAt5")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) metrics.get("avgLatencyMs")).doubleValue()).isGreaterThanOrEqualTo(0);

        // 明细：可回答题 hit=true + retrieved 快照 + 回答与引用；OUT_OF_KB 题 hit=null
        EvalRunViewService.ItemPage items = runViewService.listItems(finished, 1, 20);
        assertThat(items.total()).isEqualTo(2);
        Map<String, Object> answerableItem = items.items().stream()
                .filter(i -> Boolean.TRUE.equals(i.get("answerable"))).findFirst().orElseThrow();
        assertThat(answerableItem.get("hit")).isEqualTo(Boolean.TRUE);
        assertThat((List<?>) answerableItem.get("retrieved")).isNotEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> firstHit = (Map<String, Object>) ((List<?>) answerableItem.get("retrieved")).get(0);
        assertThat(firstHit).containsKeys("rank", "chunkId", "docName", "titlePath", "score");
        assertThat(firstHit.get("docName")).isEqualTo(DOC_NAME);
        assertThat((String) answerableItem.get("generatedAnswer")).isNotBlank();
        assertThat((List<?>) answerableItem.get("citations")).isNotEmpty();

        Map<String, Object> outOfKbItem = items.items().stream()
                .filter(i -> Boolean.FALSE.equals(i.get("answerable"))).findFirst().orElseThrow();
        assertThat(outOfKbItem.get("hit")).isNull(); // OUT_OF_KB 不参与 hit 判定
        assertThat((String) outOfKbItem.get("generatedAnswer")).isNotBlank(); // 仍生成回答以验证拒答行为
    }

    @Test
    @Order(4)
    void runListAndDetailPaging() {
        org.springframework.data.domain.Page<com.rag.domain.entity.EvalRunEntity> page =
                evalService.listRuns(UUID.fromString(datasetV1.id()), 1, 10);
        assertThat(page.getContent()).isNotEmpty();
        assertThat(page.getContent()).allSatisfy(r ->
                assertThat(r.getDatasetVersionId()).isIn(List.of(
                        datasetV1.latestVersion().versionId(), datasetV2.latestVersion().versionId())));

        // 契约语义：versionId 缺省 → 取该数据集最新版本（v2）
        EvalRunEntity latestRun = evalService.createRun(UUID.fromString(datasetV1.id()), null,
                UUID.fromString(kbId), null, null);
        assertThat(latestRun.getDatasetVersionId()).isEqualTo(datasetV2.latestVersion().versionId());
        evalService.runSync(UUID.fromString(latestRun.getId()));
        assertThat(evalService.getRun(UUID.fromString(latestRun.getId())).getStatus())
                .isEqualTo(EvalRunStatus.COMPLETED);

        // 明细分页 pageSize=1：total=2、两页
        EvalRunViewService.ItemPage p1 = runViewService.listItems(evalService.getRun(runId), 1, 1);
        EvalRunViewService.ItemPage p2 = runViewService.listItems(evalService.getRun(runId), 2, 1);
        assertThat(p1.total()).isEqualTo(2);
        assertThat(p1.items()).hasSize(1);
        assertThat(p2.items()).hasSize(1);
        assertThat(p2.items().get(0).get("seq"))
                .isNotEqualTo(p1.items().get(0).get("seq"));
    }

    @Test
    @Order(5)
    void reviewTagPersists() {
        EvalRunItemEntity item = runItemRepository.findAll().stream()
                .filter(i -> i.getRunId().equals(runId.toString()))
                .findFirst().orElseThrow();
        Map<String, Object> reviewed = runViewService.review(evalService.getRun(runId),
                UUID.fromString(item.getId()), com.rag.domain.enums.ReviewTag.MISSING_EVIDENCE,
                "证据在库但未召回");
        assertThat(reviewed.get("reviewTag")).isEqualTo("MISSING_EVIDENCE");
        assertThat(reviewed.get("reviewNote")).isEqualTo("证据在库但未召回");

        // 落库复核
        EvalRunItemEntity reloaded = runItemRepository.findById(item.getId()).orElseThrow();
        assertThat(reloaded.getReviewTag()).isEqualTo(com.rag.domain.enums.ReviewTag.MISSING_EVIDENCE);
        assertThat(reloaded.getReviewNote()).isEqualTo("证据在库但未召回");
    }

    private static MockMultipartFile multipartFile(String content, String fileName) {
        return new MockMultipartFile("file", fileName, "application/json",
                content.getBytes(StandardCharsets.UTF_8));
    }
}
