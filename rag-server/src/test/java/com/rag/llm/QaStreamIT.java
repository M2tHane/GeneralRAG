package com.rag.llm;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.rag.config.RagProperties;
import com.rag.support.FakeOpenAiServer;
import com.rag.support.SharedInfraSupport;
import com.rag.domain.entity.ChatMessageEntity;
import com.rag.domain.entity.ChatSessionEntity;
import com.rag.domain.entity.DocumentEntity;
import com.rag.domain.entity.KnowledgeBaseEntity;
import com.rag.domain.enums.DocumentStatus;
import com.rag.domain.enums.FileType;
import com.rag.domain.enums.MessageStatus;
import com.rag.domain.enums.PipelineStage;
import com.rag.storage.es.ChunkDoc;
import com.rag.storage.es.EsChunkIndex;
import com.rag.storage.repository.ChatMessageRepository;
import com.rag.storage.repository.ChatSessionRepository;
import com.rag.storage.repository.DocumentRepository;
import com.rag.storage.repository.KnowledgeBaseRepository;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * 问答链路集成测试（随机端口真实 HTTP + OkHttp SSE 客户端 + Testcontainers）。
 * 模型服务为 MockWebServer 式替身：本 IT 通过 @DynamicPropertySource 把
 * chat/embedding base-url 指向一个内嵌的 OpenAI 兼容假服务（FakeOpenAiServer）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QaStreamIT extends SharedInfraSupport {

    /** OpenAI 兼容假模型服务：/v1/embeddings 返回 8 维向量；/v1/chat/completions 流式返回。 */
    private static FakeOpenAiServer fakeModel;

    @LocalServerPort
    int port;

    @Autowired KnowledgeBaseRepository kbRepository;
    @Autowired ChatSessionRepository sessionRepository;
    @Autowired ChatMessageRepository messageRepository;
    @Autowired DocumentRepository documentRepository;
    @Autowired EsChunkIndex esChunkIndex;
    @Autowired RagProperties ragProperties;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        // 先启动假模型与容器再注册属性（容器为共享单例，见 SharedInfraSupport）
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
        registry.add("minio.bucket", () -> "qa-it");
        registry.add("rag.models.chat.base-url", () -> fakeModel.baseUrl());
        registry.add("rag.models.embedding.base-url", () -> fakeModel.baseUrl());
        registry.add("rag.models.startup-check", () -> "false");
        // 本套件验证的是 SSE 传输语义（事件序列/终态/断连/并发），而非拒答策略。
        // R2-A1 之后，与语料无关的问题会走拒答短路（不调用模型），从而掩盖传输路径的
        // 真实行为；故此处显式关闭拒答，拒答策略由 RefusalPolicyTest 专门覆盖。
        registry.add("rag.retrieval.refusal.enabled", () -> "false");
        // R4：Answerability 判定同样会短路（NO_HITS/低分直接拒答），本套件一并关闭，
        // 判定策略由 AnswerabilityPolicyTest 覆盖。
        registry.add("rag.retrieval.answerability.enabled", () -> "false");
        // 固定 VECTOR 模式：本套件用 8 维假向量，只关心传输语义，不涉及融合/重排
        registry.add("rag.retrieval.mode", () -> "VECTOR");
    }

    @AfterAll
    static void tearDownAll() {
        if (fakeModel != null) {
            fakeModel.stop();
        }
        release();
    }

    // ------------------------------------------------------------------
    // 场景数据（@Order 保证：先建库入库，再跑流式用例）
    // ------------------------------------------------------------------

    private static String kbId;
    private static String sessionId;
    private static String docId;

    @Test
    @Order(1)
    void prepareKbSessionAndDoc() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setName("qa-it-" + UUID.randomUUID());
        kbId = kbRepository.save(kb).getId();

        ChatSessionEntity session = new ChatSessionEntity();
        session.setKbId(kbId);
        session.setTitle("IT 会话");
        sessionId = sessionRepository.save(session).getId();

        // 直接经 ES 组装一篇已入库文档（绕过 API 层，T4 尚未实现）
        docId = UUID.randomUUID().toString();
        DocumentEntity doc = new DocumentEntity();
        doc.setId(docId);
        doc.setKbId(kbId);
        doc.setName("qa-it 文档.md");
        doc.setFileType(FileType.MD);
        doc.setSizeBytes(100);
        doc.setContentSha256("c".repeat(64));
        doc.setStatus(DocumentStatus.COMPLETED);
        doc.setCurrentStage(PipelineStage.COMPLETED);
        doc.setChunkCount(1);
        documentRepository.save(doc);

        esChunkIndex.ensureIndex();
        esChunkIndex.rebuildChunks(docId, kbId,
                List.of(new ChunkDoc(docId + "-c0000", "qa-it 文档 > 回调配置", null, 0, 12,
                        "支付回调确认超时为 5 秒。")),
                List.of(new float[] {1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f}));
    }

    @Test
    @Order(2)
    void happyStreamSequence() throws Exception {
        FakeModelFixture.setChatAnswer("依据文档，支付回调确认超时为 5 秒。");

        SseCollector collector = stream(kbId, sessionId, "支付回调确认超时是多少？", UUID.randomUUID().toString());
        assertThat(collector.awaitTerminal(20)).isTrue();

        // 事件序列：stage* → token* → citations → done（终态），error/canceled 不出现
        assertThat(collector.eventNames).containsSubsequence("stage", "stage", "stage",
                "token", "citations", "done");
        assertThat(collector.errorEvents).isEmpty();
        assertThat(collector.canceledEvents).isEmpty();
        // citations 内容 = 本次实际检索命中（含 chunkId 与分数）
        assertThat(collector.citationsPayload).isNotEmpty();
        assertThat(collector.citationsPayload).allSatisfy(c -> {
            assertThat(c).containsKeys("chunkId", "docId", "docName", "titlePath", "score");
            assertThat((String) c.get("chunkId")).startsWith(docId);
        });
        // done 载荷含 messageId
        assertThat(collector.donePayload).containsKey("messageId");
        // 助手消息落库
        boolean assistantPersisted = waitFor(15, () -> messageRepository.findAll().stream()
                .anyMatch(m -> m.getSessionId().equals(sessionId)
                        && m.getRole() == com.rag.domain.enums.SessionRole.ASSISTANT
                        && m.getStatus() == MessageStatus.COMPLETED));
        assertThat(assistantPersisted).as("助手消息应以 COMPLETED 落库").isTrue();
    }

    @Test
    @Order(3)
    void duplicateClientRequestIdRejectedWith409() throws Exception {
        FakeModelFixture.setChatAnswer("重复提交场景。");
        String clientRequestId = UUID.randomUUID().toString();

        // 占住活跃流：假模型延迟响应，确保第一笔仍进行中
        fakeModel.setChatDelayMs(3000);
        Thread first = new Thread(() -> streamQuietly(kbId, sessionId, "第一笔问题", clientRequestId));
        first.start();
        Thread.sleep(800); // 等第一笔完成同步受理

        // 同 clientRequestId 第二笔 → 409 STREAM_ALREADY_ACTIVE（HTTP 层直接断言）
        Request request = streamRequest(kbId, sessionId, "第二笔问题", clientRequestId);
        try (Response response = client.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(409);
            assertThat(response.body().string()).contains("STREAM_ALREADY_ACTIVE");
        }
        fakeModel.setChatDelayMs(0);
        first.join(10_000);
    }

    @Test
    @Order(4)
    void clientDisconnectPersistsCanceledMessage() throws Exception {
        // 必须让服务端"感知到"断连才能验证 canceled 落库。
        // 仅发少量 token 时，整个响应体（几百字节）会被 Tomcat 输出缓冲/Socket 缓冲吸收，
        // 客户端 cancel 后服务端仍能顺利完成写入 → 落库 COMPLETED，测试随机失败。
        // 因此这里产生足够大的输出（远超 Socket 缓冲），迫使服务端在写 token 时收到 broken pipe。
        FakeModelFixture.setChatAnswer("断连场景回答内容比较长，用于让流持续一段时间。".repeat(5000));
        fakeModel.setChatTokenDelayMs(0);

        String clientRequestId = UUID.randomUUID().toString();
        SseCollector collector = new SseCollector();
        EventSource[] sourceHolder = new EventSource[1];
        Request request = streamRequest(kbId, sessionId, "断连测试问题", clientRequestId);
        EventSources.createFactory(client).newEventSource(request, new EventSourceListener() {
            @Override public void onOpen(EventSource es, Response response) { sourceHolder[0] = es; }
            @Override public void onEvent(EventSource es, String id, String type, String data) {
                collector.onEvent(type, data);
                if ("token".equals(type)) {
                    // 收到部分 token 后立即断开
                    es.cancel();
                    collector.disconnect.countDown();
                }
            }
        });
        assertThat(collector.disconnect.await(15, TimeUnit.SECONDS)).isTrue();
        if (sourceHolder[0] != null) sourceHolder[0].cancel();

        // 等服务端检测断连：写 token 失败 → handleDisconnect → 落库 CANCELED
        boolean canceledPersisted = waitFor(20, () -> messageRepository.findAll().stream()
                .anyMatch(m -> m.getStatus() == MessageStatus.CANCELED));
        assertThat(canceledPersisted).as("断连后应落库 CANCELED 助手消息").isTrue();
        fakeModel.setChatTokenDelayMs(0);
    }

    @Test
    @Order(5)
    void deletedDocumentNeverHitsAfterDeletion() throws Exception {
        // KB-9 删除收口：主档删除 → 立即检索不再命中（ES cleanup 尚未执行的窗口期）
        FakeModelFixture.setChatAnswer("任何回答。");
        esChunkIndex.rebuildChunks(docId, kbId,
                List.of(new ChunkDoc(docId + "-c0000", "qa-it 文档 > 回调配置", null, 0, 12,
                        "支付回调确认超时为 5 秒。")),
                List.of(new float[] {1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f}));

        documentRepository.deleteById(docId);
        documentRepository.flush();

        SseCollector collector = stream(kbId, sessionId, "支付回调确认超时是多少？", UUID.randomUUID().toString());
        assertThat(collector.awaitTerminal(20)).isTrue();
        // 删除后该 doc 的分块不得出现在 citations（引用只来自本次实际检索且过滤已删主档）
        boolean deletedDocCited = collector.citationsPayload.stream()
                .anyMatch(c -> ((String) c.get("chunkId")).startsWith(docId));
        assertThat(deletedDocCited).isFalse();
    }

    @Test
    @Order(6)
    void modelFailureProducesErrorEvent() throws Exception {
        FakeModelFixture.setChatFailure(true);
        SseCollector collector = stream(kbId, sessionId, "模型失败问题", UUID.randomUUID().toString());
        assertThat(collector.awaitTerminal(20)).isTrue();
        assertThat(collector.errorEvents).isNotEmpty();
        assertThat(collector.doneEvents).isEmpty();
        assertThat(collector.canceledEvents).isEmpty();
        FakeModelFixture.setChatFailure(false);
    }

    // ------------------------------------------------------------------
    // HTTP/SSE 基础设施
    // ------------------------------------------------------------------

    private final OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(Duration.ofSeconds(30)).build();

    private Request streamRequest(String kbId, String sessionId, String question, String clientRequestId) {
        String body = """
                {"kbId":"%s","sessionId":"%s","question":"%s","clientRequestId":"%s"}
                """.formatted(kbId, sessionId, question, clientRequestId);
        return new Request.Builder()
                .url("http://localhost:" + port + "/api/v1/qa/stream")
                .post(RequestBody.create(body, okhttp3.MediaType.parse("application/json")))
                .build();
    }

    private SseCollector stream(String kbId, String sessionId, String question, String clientRequestId)
            throws Exception {
        SseCollector collector = new SseCollector();
        EventSources.createFactory(client).newEventSource(streamRequest(kbId, sessionId, question, clientRequestId),
                collector);
        return collector;
    }

    private void streamQuietly(String kbId, String sessionId, String question, String clientRequestId) {
        try {
            stream(kbId, sessionId, question, clientRequestId).awaitTerminal(30);
        } catch (Exception ignored) {
            // 后台场景
        }
    }

    /** SSE 事件收集器：按 event/data 记录，含终态门闩。 */
    static class SseCollector extends EventSourceListener {
        final List<String> eventNames = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> citationsPayload = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> doneEvents = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> errorEvents = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> canceledEvents = new CopyOnWriteArrayList<>();
        volatile Map<String, Object> donePayload;
        final CountDownLatch terminal = new CountDownLatch(1);
        final CountDownLatch disconnect = new CountDownLatch(1);
        volatile boolean seenToken;

        void onEvent(String type, String data) {
            Map<String, Object> payload = FakeOpenAiServer.parseJson(data);
            eventNames.add(type);
            switch (type) {
                case "citations" -> citationsPayload.addAll(
                        (List<Map<String, Object>>) payload.getOrDefault("citations", List.of()));
                case "done" -> { doneEvents.add(payload); donePayload = payload; terminal.countDown(); }
                case "error" -> { errorEvents.add(payload); terminal.countDown(); }
                case "canceled" -> { canceledEvents.add(payload); terminal.countDown(); }
                case "token" -> seenToken = true;
                default -> { }
            }
        }

        boolean awaitTerminal(int seconds) throws InterruptedException {
            return terminal.await(seconds, TimeUnit.SECONDS);
        }

        @Override public void onEvent(EventSource es, String id, String type, String data) {
            onEvent(type, data);
        }
    }

    private static boolean waitFor(int seconds, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(200);
        }
        return condition.getAsBoolean();
    }

    /** 假模型行为开关（静态，供各用例调整）。 */
    static final class FakeModelFixture {
        static void setChatAnswer(String answer) { fakeModel.setChatAnswer(answer); }
        static void setChatFailure(boolean fail) { fakeModel.setChatFailure(fail); }
    }
}
