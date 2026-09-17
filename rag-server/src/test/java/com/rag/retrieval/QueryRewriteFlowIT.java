package com.rag.retrieval;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rag.config.RagProperties;
import com.rag.retrieval.model.RetrievalHit;
import com.rag.domain.entity.DocumentEntity;
import com.rag.domain.entity.EvalRunEntity;
import com.rag.domain.entity.KnowledgeBaseEntity;
import com.rag.domain.enums.DatasetType;
import com.rag.domain.enums.DocumentStatus;
import com.rag.domain.enums.PipelineStage;
import com.rag.storage.es.ChunkDoc;
import com.rag.storage.es.EsChunkIndex;
import com.rag.storage.repository.DocumentRepository;
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

/**
 * History-aware Query Rewrite 全链路集成测试（R6-C）。
 *
 * <p><b>核心命题</b>：rewrite 真正参与了 production retrieval path——不是字符串
 * 转换函数的单测，而是「answerOnce / debug / eval」实际发生的检索使用了改写查询，
 * 证据因此进入 topK。</p>
 *
 * <p><b>锚定原理（假向量）</b>：FakeOpenAiServer.embedVector 是确定性纯函数——
 * 同文本 cos=1.0（ES knn score=(1+cos)/2≈1.0），不同文本近正交（≈0.5）。
 * 分块向量直接取 {@code embedVector(REWRITTEN_QUERY)}，因此：
 * 原问题「前者是多少？」的向量近正交 → <b>Before 必然 miss</b>（score≈0.5）；
 * rewrite 后的查询与分块同构 → <b>After 命中 top1</b>。检索阈值 0.90 使该差异
 * 两侧 margin 明显，断言不依赖任何真实语义相似度。</p>
 *
 * <p><b>非流式请求序列（rewrite 与 Judge 同通道，逐次消费 stub）</b>：
 * history 非空的 answerOnce = [rewrite → Judge → …]；本套件用
 * {@code setNonStreamAnswers} 按序提供。searchOnly 辅助只做一次
 * answerOnce 且拦截在低分直拒（不进 Judge），使非流式恰好只消耗 rewrite 一次。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {com.rag.RagApplication.class})
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QueryRewriteFlowIT {

    private static FakeOpenAiServer fakeModel;

    @Autowired com.rag.llm.ChatStreamService chatStreamService;
    @Autowired com.rag.debug.DebugRetrievalService debugRetrievalService;
    @Autowired com.rag.eval.EvalService evalService;
    @Autowired com.rag.eval.EvalRunViewService runViewService;
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
        registry.add("minio.bucket", () -> "rewrite-it");
        registry.add("rag.models.chat.base-url", () -> fakeModel.baseUrl());
        registry.add("rag.models.embedding.base-url", () -> fakeModel.baseUrl());
        registry.add("rag.models.startup-check", () -> "false");
        // 本套件主旨：rewrite 开启下的真实检索行为。VECTOR 模式 + cosine 阈值 0.90
        //（与 AnswerabilityFlowIT 相同的 margin 设计：同文 1.0 / 异文 0.5）。
        // min-score 同步抬高到 0.90：近正交查询（≈0.5）不得进入引用/上下文，
        // 改写后同构查询（1.0）必须进入——阈值口径与 Answerability 低分直拒一致
        registry.add("rag.retrieval.mode", () -> "VECTOR");
        registry.add("rag.retrieval.min-score", () -> "0.90");
        registry.add("rag.retrieval.refusal.cosine-threshold", () -> "0.90");
        registry.add("rag.retrieval.query-rewrite.enabled", () -> "true");
        registry.add("rag.retrieval.query-rewrite.timeout-seconds", () -> "2");
        // Answerability 低分直拒后不进 Judge，rewrite-only 序列更可控
        registry.add("rag.retrieval.answerability.judge-timeout-seconds", () -> "2");
    }

    @AfterAll
    static void tearDownAll() {
        if (fakeModel != null) {
            fakeModel.stop();
        }
    }

    /** rewrite 后的检索查询（分块向量锚定到它——Case A 的 evidence）。 */
    static final String REWRITTEN_QUERY = "HikariCP maximumPoolSize 默认是多少";
    /** 原始 follow-up 问题（向量与分块近正交 → Before 必然 miss）。 */
    static final String FORMER_QUESTION = "前者是多少？";
    static final String CHUNK_TEXT = "HikariCP 的 maximumPoolSize 默认是 10。";

    static String kbId;

    @Test
    @Order(1)
    void prepareKbAndAnchoredChunk() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setName("rewrite-it-" + UUID.randomUUID());
        kbId = kbRepository.save(kb).getId();

        String docId = UUID.randomUUID().toString();
        DocumentEntity doc = new DocumentEntity();
        doc.setId(docId);
        doc.setKbId(kbId);
        doc.setName("rewrite-it-hikari.md");
        doc.setFileType(com.rag.domain.enums.FileType.MD);
        doc.setSizeBytes(100);
        doc.setContentSha256("b".repeat(64));
        doc.setStatus(DocumentStatus.COMPLETED);
        doc.setCurrentStage(PipelineStage.COMPLETED);
        doc.setChunkCount(1);
        documentRepository.save(doc);

        esChunkIndex.ensureIndex();
        esChunkIndex.rebuildChunks(docId, kbId,
                List.of(new ChunkDoc(docId + "-c0000", "rewrite-it-hikari.md > 参数说明",
                        null, 0, 12, CHUNK_TEXT)),
                List.of(FakeOpenAiServer.embedVector(REWRITTEN_QUERY)));
    }

    /** Case A history（Hard Eval seq「前者是多少？」的真实形态）。 */
    static List<com.rag.llm.PromptAssembler.HistoryTurn> hikariHistory() {
        return List.of(
                new com.rag.llm.PromptAssembler.HistoryTurn(
                        com.rag.domain.enums.SessionRole.USER,
                        "HikariCP 里 idleTimeout 和 cachePrepStmts 分别是什么默认值？"),
                new com.rag.llm.PromptAssembler.HistoryTurn(
                        com.rag.domain.enums.SessionRole.ASSISTANT,
                        "idleTimeout 是 600000 ms，cachePrepStmts 默认 true。"));
    }

    // ------------------------------------------------------------------
    // Before/After：rewrite 必须真实进入 production retrieval path
    // ------------------------------------------------------------------

    /**
     * Before 基线：同一问题、同一分块，Rewriter 输出 rewritten=false（保持原问题）
     * → 检索用「前者是多少？」→ 近正交 → 低分直拒（无命中进入阈值）。
     * 证明 miss 是原查询造成的，而非数据问题。
     */
    @Test
    @Order(2)
    void beforeRewrite_originalQueryMissesEvidence() {
        fakeModel.resetNonStreamCounters();
        // 第 1 次非流式 = rewrite：判定保持原问题（此时原问题必然 miss）
        fakeModel.setNonStreamAnswers(List.of(
                "{\"rewritten\": false, \"query\": \"" + FORMER_QUESTION + "\"}"));
        fakeModel.setNonStreamFailure(false);

        com.rag.llm.ChatStreamService.AnswerResult r = chatStreamService.answerOnce(
                kbId, FORMER_QUESTION, null, null, hikariHistory());

        // 原问题向量与分块近正交（score≈0.5 < 0.90）→ 未过阈值 → 低分直拒
        assertThat(r.hits().stream().noneMatch(RetrievalHit::passedThreshold)).isTrue();
        assertThat(r.refusal()).isTrue();
        assertThat(r.trace().queryRewrite()).isNotNull();
        assertThat(r.trace().queryRewrite().rewritten()).isFalse();
        assertThat(r.trace().queryRewrite().retrievalQuery()).isEqualTo(FORMER_QUESTION);
        // rewrite 确实发生了一次模型调用（非流式 1 次；低分直拒未进 Judge）
        assertThat(fakeModel.nonStreamRequestCount()).isEqualTo(1);
    }

    /**
     * After：Rewriter 改写 → 检索用 REWRITTEN_QUERY → 与分块同构 → score=1.0
     * → 证据进 topK（本设计 top1）→ Judge ACCEPT → 正常生成。
     * 逐题检索查询快照（AnswerResult.queryInfo）与 trace 双向可证。
     */
    @Test
    @Order(3)
    void afterRewrite_rewrittenQueryHitsEvidenceTopK() {
        fakeModel.resetNonStreamCounters();
        // 第 1 次 = rewrite（改写）；第 2 次 = Judge（accept）
        fakeModel.setNonStreamAnswers(List.of(
                "{\"rewritten\": true, \"query\": \"" + REWRITTEN_QUERY + "\", \"reason\": \"解析前者指代\"}",
                "{\"answerable\": true, \"confidence\": 0.9, \"reason\": \"证据给出默认值\"}"));
        fakeModel.setChatAnswer("HikariCP 的 maximumPoolSize 默认是 10。");
        fakeModel.setNonStreamFailure(false);

        com.rag.llm.ChatStreamService.AnswerResult r = chatStreamService.answerOnce(
                kbId, FORMER_QUESTION, null, null, hikariHistory());

        // 检索层：改写查询真实进入 pipeline——证据进入 topK 且过阈值
        assertThat(r.trace().queryRewrite().rewritten()).isTrue();
        assertThat(r.trace().queryRewrite().retrievalQuery()).isEqualTo(REWRITTEN_QUERY);
        assertThat(r.hits()).isNotEmpty();
        assertThat(r.hits().get(0).chunk().content()).isEqualTo(CHUNK_TEXT);
        assertThat(r.hits().get(0).passedThreshold()).isTrue();
        // 判定层：Judge 用的是原始问题（prompt 含「前者是多少？」），不是改写查询
        List<String> prompts = fakeModel.lastChatPrompts();
        String judgePrompt = prompts.stream()
                .filter(p -> p.contains("【候选证据】")).findFirst().orElse("");
        assertThat(judgePrompt).contains("【当前问题】").contains(FORMER_QUESTION);
        assertThat(judgePrompt).doesNotContain(REWRITTEN_QUERY);
        // 生成层：正常回答（未拒答），AnswerQueryInfo 三元组正确
        assertThat(r.refusal()).isFalse();
        assertThat(r.answer()).contains("10");
        assertThat(r.queryInfo()).isNotNull();
        assertThat(r.queryInfo().originalQuery()).isEqualTo(FORMER_QUESTION);
        assertThat(r.queryInfo().retrievalQuery()).isEqualTo(REWRITTEN_QUERY);
        assertThat(r.queryInfo().queryRewritten()).isTrue();
        // 非流式序列：rewrite 1 次 + Judge 1 次
        assertThat(fakeModel.nonStreamRequestCount()).isEqualTo(2);
    }

    /**
     * 失败回退：rewrite 模型 500 → MODEL_ERROR 回退原查询 → 请求不失败，
     * 检索用原问题（miss → 低分直拒）。rewrite 失败不产生 Answerability degraded。
     */
    @Test
    @Order(4)
    void rewriteFailureFallsBackToOriginalAndNeverFailsRequest() {
        fakeModel.resetNonStreamCounters();
        fakeModel.setNonStreamAnswers(null);
        fakeModel.setNonStreamFailure(true); // rewrite 请求 500

        com.rag.llm.ChatStreamService.AnswerResult r = chatStreamService.answerOnce(
                kbId, FORMER_QUESTION, null, null, hikariHistory());

        // 请求整体不失败：回退原查询 → miss → 低分直拒（与 Before 相同检索结果）
        assertThat(r.refusal()).isTrue();
        assertThat(r.trace().queryRewrite().retrievalQuery()).isEqualTo(FORMER_QUESTION);
        assertThat(r.trace().queryRewrite().rewritten()).isFalse();
        // rewrite 失败 ≠ Judge degraded：本次根本没走到 Judge（低分直拒），
        // 判定类型是 LOW_SCORE_REFUSAL 而非 JUDGE_DEGRADED
        assertThat(r.answerability().decisionType())
                .isEqualTo(com.rag.answerability.AnswerabilityDecisionType.LOW_SCORE_REFUSAL);
        assertThat(r.answerability().degraded()).isFalse();
        fakeModel.setNonStreamFailure(false);
    }

    /**
     * Topic switch：完整问题 + Rewriter 判定 rewritten=false → 检索查询不被历史污染
     * （不出现 HikariCP），完整查询独立检索。
     */
    @Test
    @Order(5)
    void completeQuestionNotPollutedByHistory() {
        fakeModel.resetNonStreamCounters();
        fakeModel.setNonStreamAnswers(List.of(
                "{\"rewritten\": false, \"query\": \"Redis AOF rewrite 是什么？\"}"));
        fakeModel.setNonStreamFailure(false);

        // 新增一个 Redis 分块（向量锚定到完整问题）
        String docId = UUID.randomUUID().toString();
        DocumentEntity doc = new DocumentEntity();
        doc.setId(docId);
        doc.setKbId(kbId);
        doc.setName("rewrite-it-redis.md");
        doc.setFileType(com.rag.domain.enums.FileType.MD);
        doc.setSizeBytes(100);
        doc.setContentSha256("c".repeat(64));
        doc.setStatus(DocumentStatus.COMPLETED);
        doc.setCurrentStage(PipelineStage.COMPLETED);
        doc.setChunkCount(1);
        documentRepository.save(doc);
        String redisQuestion = "Redis AOF rewrite 是什么？";
        esChunkIndex.rebuildChunks(docId, kbId,
                List.of(new ChunkDoc(docId + "-c0000", "rewrite-it-redis.md > 持久化",
                        null, 0, 12, "AOF rewrite 把日志重写为最小集合。")),
                List.of(FakeOpenAiServer.embedVector(redisQuestion)));

        com.rag.llm.ChatStreamService.AnswerResult r = chatStreamService.answerOnce(
                kbId, redisQuestion, null, null, hikariHistory());

        // 完整问题直接命中自己的分块——历史里的 HikariCP 不会污染查询
        assertThat(r.trace().queryRewrite().rewritten()).isFalse();
        assertThat(r.trace().queryRewrite().retrievalQuery()).isEqualTo(redisQuestion);
        assertThat(r.hits().get(0).chunk().content()).contains("AOF rewrite");
        // 过阈值的引用集合只有 Redis 分块（HikariCP 分块近正交 ≈0.5 分，不过 0.90 阈值）
        assertThat(r.hits().stream().filter(RetrievalHit::passedThreshold)
                .map(h -> h.chunk().content())
                .anyMatch(c -> c.contains("maximumPoolSize"))).isFalse();
    }

    // ------------------------------------------------------------------
    // Debug 接口：history 触发 rewrite + 三元组透出
    // ------------------------------------------------------------------

    @Test
    @Order(6)
    void debugWithHistoryRewritesAndExposesTriple() {
        fakeModel.resetNonStreamCounters();
        fakeModel.setNonStreamAnswers(List.of(
                "{\"rewritten\": true, \"query\": \"" + REWRITTEN_QUERY + "\"}"));
        fakeModel.setNonStreamFailure(false);

        var result = debugRetrievalService.debug(kbId, FORMER_QUESTION, null, null, null, hikariHistory());

        assertThat(result.queryRewrite()).isNotNull();
        assertThat(result.queryRewrite().originalQuery()).isEqualTo(FORMER_QUESTION);
        assertThat(result.queryRewrite().retrievalQuery()).isEqualTo(REWRITTEN_QUERY);
        assertThat(result.queryRewrite().queryRewritten()).isTrue();
        // 改写使证据进入 hits
        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits().get(0).chunk().text()).isEqualTo(CHUNK_TEXT);
    }

    @Test
    @Order(7)
    void debugWithoutHistoryKeepsQueryUnchanged() {
        fakeModel.resetNonStreamCounters();
        fakeModel.setNonStreamFailure(false);

        var result = debugRetrievalService.debug(kbId, FORMER_QUESTION, null, null, null, null);

        // 无 history：rewrite 未触发，queryRewrite=null（不虚构改写）
        assertThat(result.queryRewrite()).isNull();
        assertThat(fakeModel.nonStreamRequestCount()).isZero();
    }

    // ------------------------------------------------------------------
    // Eval：rewrite 落库 + metrics 透出
    // ------------------------------------------------------------------

    @Test
    @Order(8)
    void evalRunPersistsRetrievalQueryAndRewriteMetrics() {
        fakeModel.resetNonStreamCounters();
        // 数据集 2 题：FOLLOW_UP（history，会 rewrite）+ DIRECT（无 history，不 rewrite）。
        // DIRECT 问题与分块向量锚定同文（无 history → 检索直接用原问题）
        String json = """
                [
                  {"question":"前者是多少？","referenceAnswer":"idleTimeout 默认 600000 ms",
                   "answerable":true,"category":"FOLLOW_UP","evidenceMode":"FOLLOW_UP",
                   "history":[
                     {"role":"user","content":"HikariCP 里 idleTimeout 和 cachePrepStmts 分别是什么默认值？"},
                     {"role":"assistant","content":"idleTimeout 是 600000 ms，cachePrepStmts 默认 true。"}
                   ],
                   "evidence":[{"docName":"rewrite-it-hikari.md","titlePath":"rewrite-it-hikari.md > 参数说明"}]},
                  {"question":"%s","referenceAnswer":"HikariCP 的 maximumPoolSize 默认是 10。",
                   "answerable":true,"category":"DIRECT","evidenceMode":"SINGLE_CHUNK",
                   "evidence":[{"docName":"rewrite-it-hikari.md","titlePath":"rewrite-it-hikari.md > 参数说明"}]}
                ]
                """.formatted(REWRITTEN_QUERY);

        com.rag.eval.EvalService.EvalDatasetView ds = evalService.importDataset(
                new MockMultipartFile("file", "rewrite-eval.json", "application/json",
                        json.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "rewrite-it-集-" + UUID.randomUUID().toString().substring(0, 8),
                DatasetType.TUNING);

        // 非流式序列：seq1 = [rewrite(改写), Judge(accept)]；seq2 无 history，
        // rewrite 不触发 → 直接 [Judge(accept)]（共 3 次非流式请求）
        fakeModel.setNonStreamAnswers(List.of(
                "{\"rewritten\": true, \"query\": \"" + REWRITTEN_QUERY + "\"}",
                "{\"answerable\": true, \"confidence\": 0.9, \"reason\": \"ok\"}",
                "{\"answerable\": true, \"confidence\": 0.9, \"reason\": \"ok\"}"));
        fakeModel.setChatAnswer("依据文档，默认值如上。");
        fakeModel.setNonStreamFailure(false);

        EvalRunEntity run = evalService.createRun(java.util.UUID.fromString(ds.id()),
                java.util.UUID.fromString(ds.latestVersion().versionId()),
                java.util.UUID.fromString(kbId), null, null);
        evalService.runSync(java.util.UUID.fromString(run.getId()));
        EvalRunEntity finished = evalService.getRun(java.util.UUID.fromString(run.getId()));
        assertThat(finished.getStatus()).isEqualTo(com.rag.domain.enums.EvalRunStatus.COMPLETED);

        Map<String, Object> metrics = finished.getMetrics();
        @SuppressWarnings("unchecked")
        Map<String, Object> rewriteMetrics = (Map<String, Object>) metrics.get("queryRewrite");
        assertThat(rewriteMetrics).isNotNull();
        // invoked = rewriteInfo 非空的题数（pipeline 实际执行了 rewrite 判定的题）：
        // seq1（history 非空，改写生效）+ seq2（无 history → 未触发 rewrite，无 rewriteInfo）
        assertThat(((Number) rewriteMetrics.get("invoked")).intValue()).isEqualTo(1);
        assertThat(((Number) rewriteMetrics.get("rewritten")).intValue()).isEqualTo(1);
        assertThat(((Number) rewriteMetrics.get("unchanged")).intValue()).isEqualTo(0);
        assertThat(((Number) rewriteMetrics.get("fallback")).intValue()).isEqualTo(0);

        // 逐题快照：FOLLOW_UP 题记录改写后的检索查询；DIRECT 题无 rewrite 信息
        var items = runViewService.listItems(finished, 1, 10);
        Map<String, Object> followUp = items.items().stream()
                .filter(i -> "FOLLOW_UP".equals(i.get("evidenceMode"))).findFirst().orElseThrow();
        assertThat(followUp.get("queryRewritten")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(followUp.get("retrievalQuery"))).isEqualTo(REWRITTEN_QUERY);
        assertThat(followUp.get("hit")).isEqualTo(Boolean.TRUE);

        Map<String, Object> direct = items.items().stream()
                .filter(i -> "SINGLE_CHUNK".equals(i.get("evidenceMode"))).findFirst().orElseThrow();
        // DIRECT 题走的是 rewrite 未触发路径（trace.queryRewrite=null）→ 逐题快照不虚构
        assertThat(direct.get("queryRewritten")).isNull();
        assertThat(direct.get("retrievalQuery")).isNull();
        assertThat(direct.get("hit")).isEqualTo(Boolean.TRUE);
    }
}
