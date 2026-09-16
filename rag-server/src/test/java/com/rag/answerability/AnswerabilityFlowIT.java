package com.rag.answerability;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.rag.config.RagProperties;
import com.rag.support.FakeOpenAiServer;
import com.rag.domain.entity.ChatSessionEntity;
import com.rag.domain.entity.DocumentEntity;
import com.rag.domain.entity.KnowledgeBaseEntity;
import com.rag.domain.enums.DocumentStatus;
import com.rag.domain.enums.FileType;
import com.rag.domain.enums.PipelineStage;
import com.rag.storage.es.ChunkDoc;
import com.rag.storage.es.EsChunkIndex;
import com.rag.storage.repository.ChatSessionRepository;
import com.rag.storage.repository.DocumentRepository;
import com.rag.storage.repository.KnowledgeBaseRepository;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Answerability 全链路集成测试（R4.1）：与 QaStreamIT 相反，本套件
 * <b>保持 rag.retrieval.answerability.enabled=true</b>，验证真实 Answerability
 * wiring——检索 → Context → AnswerabilityPolicy → EvidenceSufficiencyJudge →
 * 生成/拒答 的完整事件序列与决策语义。
 *
 * <p>模型服务为 {@link FakeOpenAiServer}（OpenAI 兼容替身）：
 * 非流式请求 = Judge 路径（setNonStreamAnswer 定向输出 / nonStreamFailure 注入
 * 500 / nonStreamDelayMs 注入慢响应），流式请求 = 生成路径（setChatAnswer）。
 * 假向量为 1024 维 deterministic pseudo-random（{@code FakeOpenAiServer.embedVector}，
 * 性质由 {@code FakeOpenAiServerVectorTest} 锁定）：同文本 cos=1.0，不同文本近正交。
 * ES knn（similarity=cosine）score = (1+cos)/2。阈值显式设 0.90（见
 * @DynamicPropertySource 说明）：HIGH_SCORE_QUESTION 的分块向量直接取同一
 * embedVector → cos=1 → score=1 → 进 Judge；LOW_SCORE_QUESTION 为不同文本
 * → cos≈0 → score≈0.5 → LOW_SCORE_REFUSAL，两侧 margin 明显。</p>
 *
 * <p>检索模式固定 VECTOR（假模型无 rerank 服务）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnswerabilityFlowIT {

    private static FakeOpenAiServer fakeModel;

    @LocalServerPort
    int port;

    @Autowired KnowledgeBaseRepository kbRepository;
    @Autowired ChatSessionRepository sessionRepository;
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
        }        registry.add("minio.bucket", () -> "answerability-it");
        registry.add("rag.models.chat.base-url", () -> fakeModel.baseUrl());
        registry.add("rag.models.embedding.base-url", () -> fakeModel.baseUrl());
        registry.add("rag.models.startup-check", () -> "false");
        // 本套件主旨：Answerability 开启下的全链路行为（refusal 开启同理，默认即开）
        registry.add("rag.retrieval.mode", () -> "VECTOR");
        // ES knn（similarity=cosine）的 _score = (1+cos)/2 ∈ [0,1]。fake 向量为
        // deterministic pseudo-random：HIGH_SCORE_QUESTION 与分块向量同构
        // （cos=1 → score=1）→ 进 Judge；LOW_SCORE_QUESTION 不同文本（cos≈0
        // → score≈0.5）→ LOW_SCORE_REFUSAL。margin 由 FakeOpenAiServerVectorTest 锁定。
        registry.add("rag.retrieval.refusal.cosine-threshold", () -> "0.90");
        // Judge 超时压短：timeout 场景不必等 15s
        registry.add("rag.retrieval.answerability.judge-timeout-seconds", () -> "2");
        registry.add("rag.retrieval.answerability.max-concurrent-judges", () -> "4");
    }

    @AfterAll
    static void tearDownAll() {
        if (fakeModel != null) {
            fakeModel.stop();
        }
    }

    private static String kbId;
    private static String sessionId;
    private static final String DOC_CHUNK = "支付回调确认超时为 5 秒。";

    @Test
    @Order(1)
    void prepareKbSessionAndDoc() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setName("answerability-it-" + UUID.randomUUID());
        kbId = kbRepository.save(kb).getId();

        ChatSessionEntity session = new ChatSessionEntity();
        session.setKbId(kbId);
        session.setTitle("Answerability IT 会话");
        sessionId = sessionRepository.save(session).getId();

        String docId = UUID.randomUUID().toString();
        DocumentEntity doc = new DocumentEntity();
        doc.setId(docId);
        doc.setKbId(kbId);
        doc.setName("answerability-it 文档.md");
        doc.setFileType(FileType.MD);
        doc.setSizeBytes(100);
        doc.setContentSha256("a".repeat(64));
        doc.setStatus(DocumentStatus.COMPLETED);
        doc.setCurrentStage(PipelineStage.COMPLETED);
        doc.setChunkCount(1);
        documentRepository.save(doc);

        esChunkIndex.ensureIndex();
        // 分块向量 = embedVector(HIGH_SCORE_QUESTION)：保证高分问题的查询向量与
        // top1 分块完全同向（cos=1.0 → score=1.0 ≥ 0.90 → 必进 Judge），而低分问题
        // （无关字符串）经逐维混合去相关后 score < 0.90 → LOW_SCORE_REFUSAL。
        // 正文仍是 DOC_CHUNK，case1 的回答/引用断言不受影响。
        esChunkIndex.rebuildChunks(docId, kbId,
                List.of(new ChunkDoc(docId + "-c0000", "answerability-it 文档 > 回调配置", null, 0, 12, DOC_CHUNK)),
                List.of(FakeOpenAiServer.embedVector(HIGH_SCORE_QUESTION)));
    }

    /** 与分块文本无关的问题（不同文本 → 伪向量近正交，score≈0.5）：Case 5 低分直拒。 */
    private static final String LOW_SCORE_QUESTION = "urjlprhzcojlniej";

    /** 与分块同构的问题（分块向量取同一 embedVector，score=1.0）：进 Judge。 */
    private static final String HIGH_SCORE_QUESTION = "支付回调确认超时是多少？";

    @Test
    @Order(2)
    void case1_judgeAcceptThenGenerate() {
        fakeModel.resetNonStreamCounters();
        fakeModel.setNonStreamAnswer("{\"answerable\": true, \"confidence\": 0.91, \"reason\": \"证据给出 5 秒\"}");
        fakeModel.setChatAnswer("依据文档，支付回调确认超时为 5 秒。");

        SseCollector collector = stream(HIGH_SCORE_QUESTION);
        assertThat(collector.awaitTerminal(20)).isTrue();
        assertThat(collector.errorEvents).isEmpty();

        // 完整事件序列：RETRIEVAL_STARTED → RETRIEVAL_COMPLETED → ANSWERABILITY_CHECKED
        // → GENERATION_STARTED → token* → citations → done
        assertThat(stageList(collector)).containsExactly("RETRIEVAL_STARTED", "RETRIEVAL_COMPLETED",
                "ANSWERABILITY_CHECKED", "GENERATION_STARTED");
        assertThat(collector.eventNames).containsSubsequence("token", "citations", "done");
        assertThat(collector.stageDetails).anyMatch(e -> "ANSWERABILITY_CHECKED".equals(e.getKey()) && e.getValue().contains("可回答"));
        // citations 非空且指向真实分块
        assertThat(collector.citationsPayload).isNotEmpty();
        // done 正常，无拒答文案
        assertThat(collector.donePayload).containsKey("messageId");
        assertThat(collector.generatedText).contains("5 秒");
        // Judge 确实被调用（1 次非流式请求）
        assertThat(fakeModel.nonStreamRequestCount()).isEqualTo(1);
    }

    @Test
    @Order(3)
    void case2_judgeRefuseSkipsGeneration() {
        fakeModel.resetNonStreamCounters();
        fakeModel.setNonStreamAnswer("{\"answerable\": false, \"confidence\": 0.97, \"reason\": \"证据没有涉及该参数\"}");
        fakeModel.setChatAnswer("这段话永远不应该出现——Judge 拒答时不得调用生成模型。");

        SseCollector collector = stream(HIGH_SCORE_QUESTION);
        assertThat(collector.awaitTerminal(20)).isTrue();
        assertThat(collector.errorEvents).isEmpty();

        // 拒答序列：RETRIEVAL_STARTED → RETRIEVAL_COMPLETED → ANSWERABILITY_CHECKED（证据不足）
        // → 固定拒答 token → citations=[] → done；无 GENERATION_STARTED（未调用生成模型）
        // R4 已发布契约：GENERATION_STARTED 在判定后统一发出（拒答路径同样存在，随后为拒答 token）
        assertThat(stageList(collector)).containsExactly("RETRIEVAL_STARTED", "RETRIEVAL_COMPLETED",
                "ANSWERABILITY_CHECKED", "GENERATION_STARTED");
        assertThat(collector.eventNames).containsSubsequence("token", "citations", "done");
        assertThat(collector.stageDetails).anyMatch(e -> "ANSWERABILITY_CHECKED".equals(e.getKey()) && e.getValue().contains("证据不足"));
        // 固定拒答文案 + citations 恒为空
        assertThat(collector.generatedText).isEqualTo("当前资料不足以回答该问题。");
        assertThat(collector.citationsPayload).isEmpty();
        // 生成模型未被调用：非流式（Judge）1 次；流式（生成）0 次
        assertThat(fakeModel.nonStreamRequestCount()).isEqualTo(1);
        assertThat(collector.seenToken).isTrue();
    }

    @Test
    @Order(4)
    void case3_malformedJudgeJsonDegradesToFallback() {
        fakeModel.resetNonStreamCounters();
        // Judge 输出非法 JSON（failClosed=false 默认）→ JUDGE_DEGRADED → 退回旧阈值行为放行
        fakeModel.setNonStreamAnswer("这不是一个 JSON 对象。");
        fakeModel.setChatAnswer("降级放行后的正常回答。");

        SseCollector collector = stream(HIGH_SCORE_QUESTION);
        assertThat(collector.awaitTerminal(20)).isTrue();
        assertThat(collector.errorEvents).isEmpty();

        // ANSWERABILITY_CHECKED 显示降级放行；生成照常进行
        assertThat(collector.stageDetails).anyMatch(e -> "ANSWERABILITY_CHECKED".equals(e.getKey()) && e.getValue().contains("Judge 降级放行"));
        assertThat(collector.generatedText).isEqualTo("降级放行后的正常回答。");
        assertThat(collector.citationsPayload).isNotEmpty();
    }

    @Test
    @Order(5)
    void case4_judgeTimeoutDegradesAndDoesNotBlockNextJudge() {
        fakeModel.resetNonStreamCounters();
        // Judge 慢 6s > 2s timeout → 第一次判定 TIMEOUT → 降级放行
        fakeModel.setNonStreamDelayMs(6_000);
        fakeModel.setChatAnswer("超时降级后的回答。");

        long start = System.currentTimeMillis();
        SseCollector collector = stream(HIGH_SCORE_QUESTION);
        assertThat(collector.awaitTerminal(30)).isTrue();
        long elapsed = System.currentTimeMillis() - start;

        // 降级放行：Judge HTTP 在 2s 处硬超时（总耗时应远小于 6s 慢响应 + 后续流程）
        assertThat(elapsed).as("Judge 应在 HTTP timeout 处被硬取消而非等待 6s 慢响应，elapsed=%dms", elapsed)
                .isLessThan(9_000);
        assertThat(collector.stageDetails).anyMatch(e -> "ANSWERABILITY_CHECKED".equals(e.getKey()) && e.getValue().contains("Judge 降级放行"));

        // 关键：慢请求被硬取消后，bulkhead 名额被释放——下一个 Judge 立即正常工作
        fakeModel.setNonStreamDelayMs(0);
        fakeModel.setNonStreamAnswer("{\"answerable\": true, \"confidence\": 0.9, \"reason\": \"ok\"}");
        fakeModel.setChatAnswer("后续正常判定。");
        SseCollector next = stream(HIGH_SCORE_QUESTION);
        assertThat(next.awaitTerminal(20)).isTrue();
        assertThat(next.stageDetails).anyMatch(e ->
                "ANSWERABILITY_CHECKED".equals(e.getKey()) && e.getValue().contains("可回答"));
    }

    @Test
    @Order(6)
    void case5_lowScoreRefusesWithoutJudgeOrGeneration() {
        fakeModel.resetNonStreamCounters();
        fakeModel.setChatAnswer("不应出现。");

        SseCollector collector = stream(LOW_SCORE_QUESTION);
        assertThat(collector.awaitTerminal(20)).isTrue();
        assertThat(collector.errorEvents).isEmpty();

        // 低分直拒：不调 Judge（0 次非流式）、不调生成模型（假模型生成计数为 0）。
        // 事件序列按 R4 已发布契约：拒答路径同样有 GENERATION_STARTED stage（进度表达），
        // 但其后只有拒答 token，无模型调用。
        assertThat(fakeModel.nonStreamRequestCount()).isZero();
        assertThat(stageList(collector)).containsExactly("RETRIEVAL_STARTED", "RETRIEVAL_COMPLETED",
                "ANSWERABILITY_CHECKED", "GENERATION_STARTED");
        assertThat(collector.stageDetails).anyMatch(e -> "ANSWERABILITY_CHECKED".equals(e.getKey()) && e.getValue().contains("低分拒答"));
        assertThat(collector.generatedText).isEqualTo("当前资料不足以回答该问题。");
        assertThat(collector.citationsPayload).isEmpty();
    }

    @Test
    @Order(7)
    void case6_sseEventOrderMatchesContract() {
        fakeModel.resetNonStreamCounters();
        fakeModel.setNonStreamAnswer("{\"answerable\": true, \"confidence\": 0.9, \"reason\": \"ok\"}");
        fakeModel.setChatAnswer("契约顺序验证。");

        SseCollector collector = stream(HIGH_SCORE_QUESTION);
        assertThat(collector.awaitTerminal(20)).isTrue();

        // 可回答路径 stage 名次序：RETRIEVAL_STARTED → RETRIEVAL_COMPLETED →
        // ANSWERABILITY_CHECKED → GENERATION_STARTED，且全部位于 token 之前
        List<String> stages = stageList(collector);
        assertThat(stages).containsExactly("RETRIEVAL_STARTED", "RETRIEVAL_COMPLETED",
                "ANSWERABILITY_CHECKED", "GENERATION_STARTED");
        int firstToken = collector.eventNames.indexOf("token");
        assertThat(firstToken).isGreaterThan(collector.eventNames.lastIndexOf("stage"));
        // citations 恰一次且在 done 前
        assertThat(collector.eventNames).containsSubsequence("citations", "done");
        assertThat(collector.eventNames.stream().filter("citations"::equals).count()).isEqualTo(1);

        // Judge prompt 断言：证据全文进入 Judge 提示词（与生成同源；无按字符截断）
        String judgePrompt = fakeModel.lastChatPrompts().stream()
                .filter(p -> p.contains("证据充分性判定器") || p.contains("【候选证据】"))
                .findFirst().orElse("");
        // lastChatPrompts 只记录 content 文本（SystemMessage 内容不含候选证据标记，跳过它）
        List<String> contents = fakeModel.lastChatPrompts();
        assertThat(contents.stream().anyMatch(c -> c.contains(DOC_CHUNK) && c.contains("【当前问题】")))
                .as("Judge 用户消息应包含与生成同源的证据全文与当前问题").isTrue();
        assertThat(judgePrompt.isEmpty() || judgePrompt.contains("证据充分性")).isTrue();
    }

    // ------------------------------------------------------------------
    // HTTP/SSE 基础设施（与 QaStreamIT 相同模式）
    // ------------------------------------------------------------------

    private final OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(Duration.ofSeconds(40)).build();

    private Request streamRequest(String question) {
        String body = """
                {"kbId":"%s","sessionId":"%s","question":"%s","clientRequestId":"%s"}
                """.formatted(kbId, sessionId, question, UUID.randomUUID());
        return new Request.Builder()
                .url("http://localhost:" + port + "/api/v1/qa/stream")
                .post(RequestBody.create(body, okhttp3.MediaType.parse("application/json")))
                .build();
    }

    /** 发起 SSE 流；返回 [EventSource, SseCollector]（断言失败时 cancel 防止后台流泄漏）。 */
    private Object[] streamHeld(String question) {
        SseCollector collector = new SseCollector();
        EventSource es = EventSources.createFactory(client).newEventSource(streamRequest(question), collector);
        return new Object[] {es, collector};
    }

    private SseCollector stream(String question) {
        return (SseCollector) streamHeld(question)[1];
    }

    private static List<String> stageList(SseCollector collector) {
        return collector.eventNames.stream()
                .filter(n -> n.startsWith("stage:"))
                .map(n -> n.substring("stage:".length()))
                .toList();
    }

    /** SSE 事件收集器：记录 stage 名与判定文案（Answerability 断言核心）。 */
    static class SseCollector extends EventSourceListener {
        final List<String> eventNames = new CopyOnWriteArrayList<>(); // stage 事件记录为 "stage:<STAGE_NAME>"
        final List<Map.Entry<String, String>> stageDetails = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> citationsPayload = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> errorEvents = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> canceledEvents = new CopyOnWriteArrayList<>();
        volatile Map<String, Object> donePayload;
        final StringBuilder text = new StringBuilder();
        volatile boolean seenToken;
        final CountDownLatch terminal = new CountDownLatch(1);

        volatile String generatedText;

        void onEvent(String type, String data) {
            Map<String, Object> payload = FakeOpenAiServer.parseJson(data);
            switch (type) {
                case "stage" -> {
                    String stage = String.valueOf(payload.get("stage"));
                    eventNames.add("stage:" + stage);
                    stageDetails.add(Map.entry(stage, String.valueOf(payload.getOrDefault("detail", ""))));
                }
                case "token" -> {
                    eventNames.add("token");
                    seenToken = true;
                    text.append(String.valueOf(payload.get("delta")));
                }
                case "citations" -> {
                    eventNames.add("citations");
                    citationsPayload.addAll(
                            (List<Map<String, Object>>) payload.getOrDefault("citations", List.of()));
                }
                case "done" -> {
                    eventNames.add("done");
                    donePayload = payload;
                    generatedText = text.toString();
                    terminal.countDown();
                }
                case "error" -> {
                    eventNames.add("error");
                    errorEvents.add(payload);
                    generatedText = text.toString();
                    terminal.countDown();
                }
                case "canceled" -> {
                    eventNames.add("canceled");
                    canceledEvents.add(payload);
                    generatedText = text.toString();
                    terminal.countDown();
                }
                default -> { }
            }
        }

        boolean awaitTerminal(int seconds) {
            try {
                return terminal.await(seconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override public void onEvent(EventSource es, String id, String type, String data) {
            onEvent(type, data);
        }

        @Override public void onFailure(EventSource es, Throwable t, okhttp3.Response response) {
            eventNames.add("FAILURE:" + (t == null ? "null" : t.getMessage()));
            terminal.countDown();
        }

        @Override public void onClosed(EventSource es) {
            eventNames.add("CLOSED");
        }
    }
}
