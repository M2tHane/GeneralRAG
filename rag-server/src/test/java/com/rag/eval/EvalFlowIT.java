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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 评测全链路集成测试（Task 6，Testcontainers + FakeOpenAiServer）。
 *
 * <p>不依赖 Task 4 控制器（T4 并行中）：直接用 repository 组装知识库与已入库文档
 * （与 QaStreamIT 相同的 ES 直写方式）；经 EvalService 导入 JSON 数据集 → runSync
 * 同步执行 → 断言 configSnapshot / hit / metrics / 人工标注。</p>
 *
 * <p>R4.1.2 后：MySQL/ES/MinIO 直连开发环境（配置统一在 application-test.yaml），
 * 套件隔离靠随机 UUID 数据、独立 bucket 名与随机数据集名；FakeOpenAiServer 套件私有。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {com.rag.RagApplication.class})
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EvalFlowIT {

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
        registry.add("minio.bucket", () -> "eval-it");
        registry.add("rag.models.chat.base-url", () -> fakeModel.baseUrl());
        registry.add("rag.models.embedding.base-url", () -> fakeModel.baseUrl());
        registry.add("rag.models.startup-check", () -> "false");
        // R6-C：本套件非流式请求 = Judge（顺序响应 stub 精确对位）；rewrite 也是非流式
        // 请求，会错位 stub 序列。rewrite 由 QueryRewriteFlowIT 专门覆盖，此处关闭
        registry.add("rag.retrieval.query-rewrite.enabled", () -> "false");
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
    /** 数据集名带运行级随机后缀：共享开发库，同名历史数据不得影响断言（R4.1.3 隔离） */
    static String datasetName;
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
                List.of(FakeOpenAiServer.unitVector(0), FakeOpenAiServer.unitVector(1)));
        datasetName = "eval-it-集-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @Order(2)
    void importDatasetCreatesVersion1And2() {
        String json1 = """
                [
                  {"question":"支付回调确认超时是多少？","referenceAnswer":"5 秒",
                   "answerable":true,"category":"DIRECT","evidenceMode":"SINGLE_CHUNK",
                   "evidence":[{"docName":"%s","titlePath":"%s"}]},
                  {"question":"知识库里完全没有的话题问题？","answerable":false,"category":"OUT_OF_KB","failureMode":"OUT_OF_KB","evidence":[]}
                ]
                """.formatted(DOC_NAME, DOC_TITLE_PATH);
        datasetV1 = evalService.importDataset(multipartFile(json1, "评测集.json"), datasetName, DatasetType.TUNING);
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
        datasetV2 = evalService.importDataset(multipartFile(json2, "评测集.json"), datasetName, DatasetType.TUNING);
        assertThat(datasetV2.id()).isEqualTo(datasetV1.id());
        assertThat(datasetV2.latestVersion().versionNo()).isEqualTo(2);
        assertThat(datasetV2.latestVersion().versionId()).isNotEqualTo(datasetV1.latestVersion().versionId());

        List<EvalService.EvalDatasetView> datasets = evalService.listDatasets();
        assertThat(datasets).extracting(EvalService.EvalDatasetView::name).contains(datasetName);
    }

    @Test
    @Order(3)
    void runCompletesWithMetricsAndConfigSnapshot() {
        fakeModel.setChatAnswer("依据文档，支付回调确认超时为 5 秒。");
        // R6-A：显式 stub Judge 合法 JSON——此前 Judge 收到非 JSON 文案必然
        // JUDGE_DEGRADED，两题全部退出混淆矩阵/分组指标（evaluated=0），
        // failureMode/evidenceMode 分组无从验证
        fakeModel.setNonStreamAnswer("{\"answerable\": true, \"confidence\": 0.9, \"reason\": \"ok\"}");
        fakeModel.setNonStreamFailure(false);
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
        assertThat(snapshot.get("embeddingDimensions"))
                .isEqualTo(FakeOpenAiServer.DIMENSIONS);
        assertThat(snapshot.get("topK")).isEqualTo(ragProperties.getRetrieval().getTopK());

        // metrics 结构（EV-3：answerable=1 题，命中 → hitAt*=1.0；OUT_OF_KB 不计分母）
        Map<String, Object> metrics = finished.getMetrics();
        assertThat(metrics).containsKeys("hitAt1", "hitAt3", "hitAt5", "avgLatencyMs", "itemCount");
        assertThat(metrics.get("itemCount")).isEqualTo(2);
        assertThat(((Number) metrics.get("hitAt1")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) metrics.get("hitAt3")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) metrics.get("hitAt5")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) metrics.get("avgLatencyMs")).doubleValue()).isGreaterThanOrEqualTo(0);

        // R6-A：新增指标键存在；SINGLE_CHUNK 正例进 evidenceMode 分组，coverage=1.0
        assertThat(metrics).containsKeys("evidenceCoverageAvg", "fullEvidenceCoverageRate",
                "failureModeBreakdown", "evidenceModeBreakdown");
        assertThat(((Number) metrics.get("evidenceCoverageAvg")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) metrics.get("fullEvidenceCoverageRate")).doubleValue()).isEqualTo(1.0);
        @SuppressWarnings("unchecked")
        Map<String, Object> fmb = (Map<String, Object>) metrics.get("failureModeBreakdown");
        @SuppressWarnings("unchecked")
        Map<String, Object> outOfKbRow = (Map<String, Object>) fmb.get("OUT_OF_KB");
        assertThat(outOfKbRow).isNotNull();
        assertThat(((Number) outOfKbRow.get("count")).intValue()).isEqualTo(1);
        // FakeOpenAiServer 生成的回答内容为固定文案，Answerability 判决走 fake 决策——
        // 此处不断言 far 方向（由混淆矩阵测试覆盖），只断言组内计数自洽
        int answered = ((Number) outOfKbRow.get("answered")).intValue();
        int refusedCnt = ((Number) outOfKbRow.get("refused")).intValue();
        assertThat(answered + refusedCnt).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> emb = (Map<String, Object>) metrics.get("evidenceModeBreakdown");
        @SuppressWarnings("unchecked")
        Map<String, Object> singleRow = (Map<String, Object>) emb.get("SINGLE_CHUNK");
        assertThat(singleRow).isNotNull();
        assertThat(((Number) singleRow.get("count")).intValue()).isEqualTo(1);
        assertThat(((Number) singleRow.get("hitAt1")).doubleValue()).isEqualTo(1.0);
        // R6-A.1 §11/§12：组内 macro coverage 与 micro recall 双口径输出且此处等值
        //（单题 1/1：macro = micro = 1.0，键存在即口径已接线；多题差异由 Hard 集验证）
        assertThat(singleRow).containsKeys("evidenceCoverageAvg", "evidenceRecall",
                "fullEvidenceCoverageRate");
        assertThat(((Number) singleRow.get("evidenceCoverageAvg")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) singleRow.get("evidenceRecall")).doubleValue()).isEqualTo(1.0);

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
        // 共享容器（R4.1.2）：按 runId 过滤本套件数据，不依赖全表为空
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

    /**
     * R4.1 语义守卫：Judge 系统故障（TIMEOUT/OVERLOADED/MODEL_ERROR/INVALID_RESPONSE）
     * 是 SYSTEM_ERROR 而非拒答能力——degraded 题不得进入 TP/FP/FN/TN 与
     * refusalAccuracy，否则 failClosed=true 时每次故障都会被记成 Correct Refusal，
     * 指标被系统性高估。
     *
     * <p>构造：nonStreamFailure=true → Judge 请求 500 → MODEL_ERROR → JUDGE_DEGRADED。
     * 本套件 failClosed=false（默认）→ 降级放行生成。两题数据集（1 answerable +
     * 1 OUT_OF_KB）若按旧逻辑会分别记 TP/TN；修复后必须全部出格，
     * 只出现在 confusion.degraded 明细中。</p>
     */
    @Test
    @Order(6)
    void judgeSystemErrorExcludedFromConfusionMatrix() {
        String json = """
                [
                  {"question":"支付回调确认超时是多少？","referenceAnswer":"5 秒",
                   "answerable":true,"category":"DIRECT","evidenceMode":"SINGLE_CHUNK",
                   "evidence":[{"docName":"%s","titlePath":"%s"}]},
                  {"question":"知识库里完全没有的话题问题？","answerable":false,"category":"OUT_OF_KB","failureMode":"OUT_OF_KB","evidence":[]}
                ]
                """.formatted(DOC_NAME, DOC_TITLE_PATH);
        EvalService.EvalDatasetView ds = evalService.importDataset(
                multipartFile(json, "降级语义集.json"), "eval-it-降级集", DatasetType.TUNING);

        fakeModel.resetNonStreamCounters();
        fakeModel.setNonStreamFailure(true); // Judge 请求 500 → MODEL_ERROR → JUDGE_DEGRADED
        fakeModel.setNonStreamAnswer(null);
        fakeModel.setChatAnswer("降级放行后的回答。");

        EvalRunEntity run = evalService.createRun(UUID.fromString(ds.id()),
                UUID.fromString(ds.latestVersion().versionId()),
                UUID.fromString(kbId), null, null);
        evalService.runSync(UUID.fromString(run.getId()));

        EvalRunEntity finished = evalService.getRun(UUID.fromString(run.getId()));
        assertThat(finished.getStatus()).isEqualTo(EvalRunStatus.COMPLETED);
        Map<String, Object> metrics = finished.getMetrics();

        // 两题都调了 Judge 且全部降级（MODEL_ERROR）
        @SuppressWarnings("unchecked")
        Map<String, Object> confusion = (Map<String, Object>) metrics.get("answerabilityConfusion");
        assertThat(confusion).isNotNull();
        // degraded 题不进四格：修复前这里是 tp=1, tn=1（系统故障被算成正确判定）
        assertThat(confusion).containsEntry("tp", 0).containsEntry("fp", 0)
                .containsEntry("fn", 0).containsEntry("tn", 0);
        assertThat(((Number) confusion.get("judgeDegradedRate")).doubleValue()).isEqualTo(1.0);
        // 降级明细按结构化 failureType 透出（2 题均 MODEL_ERROR → 500 故障）
        @SuppressWarnings("unchecked")
        Map<String, Object> degraded = (Map<String, Object>) confusion.get("degraded");
        assertThat(degraded).containsEntry("MODEL_ERROR", 2);
        // R4.1.1：executedCount（含 degraded）/ evaluatedCount（排除 degraded）分离
        assertThat(confusion).containsEntry("executedCount", 2).containsEntry("evaluatedCount", 0);

        // refusalAccuracy 分母同样排除 degraded 题（0 题参与 → null）
        assertThat(metrics.get("refusalAccuracy")).isNull();

        // 逐题决策落库可复盘：两题 decisionType=JUDGE_DEGRADED、degraded=true
        EvalRunViewService.ItemPage items = runViewService.listItems(finished, 1, 10);
        assertThat(items.total()).isEqualTo(2);
        assertThat(items.items()).allSatisfy(i ->
                assertThat(i.get("answerabilityDecisionType")).isEqualTo("JUDGE_DEGRADED"));

        fakeModel.setNonStreamFailure(false);
    }

    /**
     * R4.1.1 调用链测试：FOLLOW_UP 数据集 history 必须贯穿
     * Eval → answerOnce → AnswerabilityInput → AnswerabilityPolicy →
     * EvidenceSufficiencyJudge。R4.1 曾在 answerOnce 用无 history 的便捷构造，
     * 导致 history 只到生成端、Judge 永远看不到（本用例对该回归点敏感）。
     *
     * <p>样本设计：当前问题「那这个触发以后怎么解除？」离开历史无法理解（"这个"
     * 指代第一轮对话里的洪泛水位）；证据单独提供 ES 只读块解除内容。断言：
     * ① Judge prompt 含历史区（用户+助手轮）与"仅用于理解指代"声明；
     * ② Judge prompt 的【候选证据】区不含历史内容（history 不是证据）；
     * ③ 判定走 JUDGE 路径且决策/回答正常。</p>
     */
    @Test
    @Order(7)
    void followUpHistoryReachesJudgeThroughEvalChain() {
        String historyJson = """
                [
                  {"question":"那这个触发以后怎么解除？","referenceAnswer":"调整水位后等待自动解除，或手动放开只读块",
                   "answerable":true,"category":"FOLLOW_UP","evidenceMode":"FOLLOW_UP",
                   "history":[
                     {"role":"user","content":"ES 洪泛水位是什么？"},
                     {"role":"assistant","content":"洪泛水位是磁盘使用率达到 90%% 时把索引标记为只读的机制。"}
                   ],
                   "evidence":[{"docName":"%s","titlePath":"%s"}]}
                ]
                """.formatted(DOC_NAME, DOC_TITLE_PATH).replace("%%", "%");
        EvalService.EvalDatasetView ds = evalService.importDataset(
                multipartFile(historyJson, "followup链路集.json"), "eval-it-followup链路", DatasetType.TUNING);

        fakeModel.resetNonStreamCounters();
        // Judge 判可回答（合法 JSON）+ 生成正常回答
        fakeModel.setNonStreamAnswer("{\"answerable\": true, \"confidence\": 0.88, \"reason\": \"证据含解除只读块的方法\"}");
        fakeModel.setNonStreamFailure(false);
        fakeModel.setChatAnswer("依据文档，调整水位设置后等待自动解除或手动放开只读块。");

        EvalRunEntity run = evalService.createRun(UUID.fromString(ds.id()),
                UUID.fromString(ds.latestVersion().versionId()),
                UUID.fromString(kbId), null, null);
        evalService.runSync(UUID.fromString(run.getId()));
        EvalRunEntity finished = evalService.getRun(UUID.fromString(run.getId()));
        assertThat(finished.getStatus()).isEqualTo(EvalRunStatus.COMPLETED);

        // 1) Judge 真实被调用且 prompt 同时含历史区与证据区
        assertThat(fakeModel.nonStreamRequestCount()).isEqualTo(1);
        List<String> prompts = fakeModel.lastChatPrompts();
        String judgePrompt = prompts.stream()
                .filter(p -> p.contains("【对话历史】")).findFirst().orElse(null);
        assertThat(judgePrompt)
                .as("history 应贯穿 Eval→answerOnce→AnswerabilityInput→Judge（R4.1.1 修复点）")
                .isNotNull();
        assertThat(judgePrompt).contains("ES 洪泛水位是什么？")
                .contains("洪泛水位是磁盘使用率达到 90% 时把索引标记为只读的机制")
                .contains("仅用于理解当前问题中的指代")
                .contains("那这个触发以后怎么解除？");

        // 2) 历史内容不得混入证据区：【候选证据】与【对话历史】之间不含历史助手指代内容
        String evidenceZone = judgePrompt.substring(judgePrompt.indexOf("【候选证据】"),
                judgePrompt.indexOf("【当前问题】"));
        assertThat(evidenceZone).doesNotContain("洪泛水位是磁盘使用率达到 90%");

        // 3) 判定落库可复盘：走 JUDGE 路径（非门控直拒）
        EvalRunViewService.ItemPage items = runViewService.listItems(finished, 1, 10);
        Map<String, Object> item = items.items().get(0);
        assertThat(String.valueOf(item.get("answerabilityDecisionType")))
                .isIn("JUDGE_ACCEPT", "JUDGE_REFUSE");
        assertThat(item.get("refused")).isEqualTo(Boolean.FALSE);
    }

    /**
     * R4.1.2 统计口径：executionFailed 题（此处注入 chat 500 → answerOnce 抛异常）
     * 计入 attemptedCount 但不计入 executedCount，其耗时不得进入延迟统计集合
     * （avg/P50/P95 的分母与样本都必须只来自成功执行的题）。
     *
     * <p>构造：2 题，item1 正常、item2 执行失败。item1 的 latency 为真实成功耗时
     * （≫0）；item2 失败耗时通常极小——若失败耗时混入，样本数会变成 2 而非 1。
     * 用 latencies 集合大小 = executedCount 语义验证：P50 与 avg 都应来自 item1，
     * 且 P50 ≈ avg（单样本集合），绝不出现"两样本均值"形态。</p>
     */
    @Test
    @Order(8)
    void executionFailedItemsExcludedFromLatencyMetrics() {
        String json = """
                [
                  {"question":"支付回调确认超时是多少？","referenceAnswer":"5 秒",
                   "answerable":true,"category":"DIRECT","evidenceMode":"SINGLE_CHUNK",
                   "evidence":[{"docName":"%s","titlePath":"%s"}]},
                  {"question":"签名算法是什么？","referenceAnswer":"HMAC-SHA256",
                   "answerable":true,"category":"TERM_VARIATION",
                   "evidence":[{"docName":"%s","titlePath":"支付接口文档 > 签名算法"}]}
                ]
                """.formatted(DOC_NAME, DOC_TITLE_PATH, DOC_NAME);
        EvalService.EvalDatasetView ds = evalService.importDataset(
                multipartFile(json, "延迟口径集.json"), "eval-it-延迟口径", DatasetType.TUNING);

        fakeModel.resetNonStreamCounters();
        // Judge 放行（合法 JSON）；生成端先正常后 500 —— chatFailure 对所有 chat 请求生效，
        // 但 eval 逐题串行执行，本题集只有 seq2 的生成请求会落在 failure=true 窗口后
        fakeModel.setNonStreamAnswer("{\"answerable\": true, \"confidence\": 0.9, \"reason\": \"ok\"}");
        fakeModel.setNonStreamFailure(false);
        fakeModel.setChatAnswer("依据文档，支付回调确认超时为 5 秒。");
        fakeModel.setChatFailureAfter(1); // 第 1 次流式生成后，后续 chat 请求全部 500

        try {
            EvalRunEntity run = evalService.createRun(UUID.fromString(ds.id()),
                    UUID.fromString(ds.latestVersion().versionId()),
                    UUID.fromString(kbId), null, null);
            evalService.runSync(UUID.fromString(run.getId()));
            EvalRunEntity finished = evalService.getRun(UUID.fromString(run.getId()));
            assertThat(finished.getStatus()).isEqualTo(EvalRunStatus.COMPLETED);

            Map<String, Object> metrics = finished.getMetrics();
            assertThat(metrics.get("attemptedCount")).isEqualTo(2);
            assertThat(metrics.get("executedCount")).isEqualTo(1);
            assertThat(metrics.get("evaluatedCount")).isEqualTo(1);

            // 失败题不得进入延迟集合：单成功样本下 P50 = max = avg（同一值的整数毫秒）
            Number p50 = (Number) metrics.get("latencyP50Ms");
            Number max = (Number) metrics.get("latencyMaxMs");
            Number avg = (Number) metrics.get("avgLatencyMs");
            assertThat(max.doubleValue()).isEqualTo(p50.doubleValue());
            // avg 与 P50 来自同一个样本（round4 只影响小数位）
            assertThat(avg.doubleValue()).isCloseTo(p50.doubleValue(), within(0.5));

            // 失败题落库可辨识
            EvalRunViewService.ItemPage items = runViewService.listItems(finished, 1, 10);
            assertThat(items.total()).isEqualTo(2);
            Map<String, Object> failedItem = items.items().stream()
                    .filter(i -> i.get("generatedAnswer") == null)
                    .findFirst().orElseThrow();
            assertThat(String.valueOf(failedItem.get("reviewNote"))).contains("执行失败");
        } finally {
            fakeModel.setChatFailureAfter(0); // 解除故障注入，避免污染后续用例
            fakeModel.setChatFailure(false);
        }
    }

    /**
     * R6-B.1：Eval denominator invariant——attempted ≥ executed ≥ evaluated，
     * 且 evaluatedCount = TP+FP+FN+TN（degraded 系统故障不进四格），
     * judgeInvokedCount/judgeDegradedCount 只在 answerability 明细（degraded 字段）
     * 与 confusion.judgeDegradedRate 体现，绝不把 degraded 记成 TP/TN。
     *
     * <p>构造 3 题：题1 Judge ACCEPT → TP；题2 Judge REFUSE → TN；
     * 题3 Judge 500 → MODEL_ERROR → JUDGE_DEGRADED。此前无混合场景测试，
     * 单一故障场景（Order 6）与单一成功场景（Order 3）都无法证明
     * "TP/TN 计数与 evaluatedCount 分母同步收缩"。</p>
     */
    @Test
    @Order(9)
    void confusionDenominatorInvariantWithMixedJudgeOutcomes() {
        String json = """
                [
                  {"question":"支付回调确认超时是多少？","referenceAnswer":"5 秒",
                   "answerable":true,"category":"DIRECT","evidenceMode":"SINGLE_CHUNK",
                   "evidence":[{"docName":"%s","titlePath":"%s"}]},
                  {"question":"知识库里完全没有的话题问题？","answerable":false,"category":"OUT_OF_KB","failureMode":"OUT_OF_KB","evidence":[]},
                  {"question":"签名算法是什么？","referenceAnswer":"HMAC-SHA256",
                   "answerable":true,"category":"TERM_VARIATION",
                   "evidence":[{"docName":"%s","titlePath":"支付接口文档 > 签名算法"}]}
                ]
                """.formatted(DOC_NAME, DOC_TITLE_PATH, DOC_NAME);
        EvalService.EvalDatasetView ds = evalService.importDataset(
                multipartFile(json, "分母不变式集.json"), "eval-it-分母不变式", DatasetType.TUNING);

        // Judge 决策按请求次序：seq1 ACCEPT → TP；seq2 REFUSE → TN；
        // seq3 注入 500 → MODEL_ERROR → degraded（failOpen 放行，但已不计入矩阵）
        fakeModel.resetNonStreamCounters();
        fakeModel.setNonStreamFailure(false);
        fakeModel.setNonStreamAnswer(null); // 恢复默认（= chatAnswer）
        fakeModel.setNonStreamAnswers(List.of(
                "{\"answerable\": true, \"confidence\": 0.9, \"reason\": \"证据明确 5 秒\"}",
                "{\"answerable\": false, \"confidence\": 0.9, \"reason\": \"证据无该话题\"}"));
        fakeModel.setNonStreamFailureAfter(2); // 第 3 次非流式请求起 500
        fakeModel.setChatAnswer("依据文档，支付回调确认超时为 5 秒。");

        try {
            EvalRunEntity run = evalService.createRun(UUID.fromString(ds.id()),
                    UUID.fromString(ds.latestVersion().versionId()),
                    UUID.fromString(kbId), null, null);
            evalService.runSync(UUID.fromString(run.getId()));
            EvalRunEntity finished = evalService.getRun(UUID.fromString(run.getId()));
            assertThat(finished.getStatus()).isEqualTo(EvalRunStatus.COMPLETED);

            Map<String, Object> metrics = finished.getMetrics();
            // 分母链：attempted ≥ executed ≥ evaluated
            assertThat(metrics.get("attemptedCount")).isEqualTo(3);
            assertThat(metrics.get("executedCount")).isEqualTo(3);
            assertThat(metrics.get("evaluatedCount")).isEqualTo(2);

            // 核心不变式：evaluatedCount == TP+FP+FN+TN
            @SuppressWarnings("unchecked")
            Map<String, Object> confusion = (Map<String, Object>) metrics.get("answerabilityConfusion");
            int tp = ((Number) confusion.get("tp")).intValue();
            int fp = ((Number) confusion.get("fp")).intValue();
            int fn = ((Number) confusion.get("fn")).intValue();
            int tn = ((Number) confusion.get("tn")).intValue();
            assertThat(tp).isEqualTo(1);  // seq1 应答且答
            assertThat(fp).isZero();
            assertThat(fn).isZero();
            assertThat(tn).isEqualTo(1);  // seq2 不应答且拒
            assertThat(((Number) confusion.get("evaluatedCount")).intValue())
                    .as("evaluatedCount 必须等于 TP+FP+FN+TN（degraded 不进四格）")
                    .isEqualTo(tp + fp + fn + tn);

            // degraded 只体现在明细与成本口径，不污染质量指标
            @SuppressWarnings("unchecked")
            Map<String, Object> degraded = (Map<String, Object>) confusion.get("degraded");
            assertThat(degraded).containsEntry("MODEL_ERROR", 1);
            assertThat(((Number) confusion.get("judgeDegradedRate")).doubleValue()).isGreaterThan(0.0);
            assertThat(((Number) confusion.get("judgeInvocationRate")).doubleValue()).isEqualTo(1.0);

            // 逐题决策落库可复盘：seq3 = JUDGE_DEGRADED
            EvalRunViewService.ItemPage items = runViewService.listItems(finished, 1, 10);
            assertThat(items.items()).anySatisfy(i -> {
                assertThat(i.get("answerabilityDecisionType")).isEqualTo("JUDGE_DEGRADED");
                assertThat(i.get("seq")).isEqualTo(3);
            });
        } finally {
            fakeModel.setNonStreamFailureAfter(0);
            fakeModel.setNonStreamAnswers(null);
        }
    }

    private static MockMultipartFile multipartFile(String content, String fileName) {
        return new MockMultipartFile("file", fileName, "application/json",
                content.getBytes(StandardCharsets.UTF_8));
    }
}
