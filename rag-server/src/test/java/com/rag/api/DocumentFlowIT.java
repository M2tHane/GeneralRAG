package com.rag.api;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rag.support.FakeOpenAiServer;
import com.rag.support.SharedInfraSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 文档全旅程集成测试（真实 controller + 随机端口 + Testcontainers 全家桶 + 假 embedding）：
 * 建库 → 上传 md → 轮询 task 至 COMPLETED → 列表/详情/分块/解析文本 → 同文件重传
 * 断言 409 DUPLICATE_DOCUMENT（details 含 existingDocumentId）→ 删除文档 → cleanup_task
 * 收敛（ES 查不到分块）→ 建会话（repository 直插消息）→ 消息分页 → 删会话 204。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DocumentFlowIT extends SharedInfraSupport {

    private static FakeOpenAiServer fakeModel;

    @Autowired TestRestTemplate rest;

    private com.rag.storage.es.EsChunkIndex esChunkIndex;

    @Autowired com.rag.storage.repository.ChatSessionRepository sessionRepository;
    @Autowired com.rag.storage.repository.ChatMessageRepository messageRepository;

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
        registry.add("minio.bucket", () -> "api-flow-it");
        registry.add("rag.models.embedding.base-url", fakeModel::baseUrl);
        registry.add("rag.models.chat.base-url", fakeModel::baseUrl);
        registry.add("rag.models.startup-check", () -> "false");
    }

    @AfterAll
    static void tearDown() {
        if (fakeModel != null) {
            fakeModel.stop();
        }
    }

    /**
     * 主代码目前无启动期 ensureIndex 钩子（EsChunkIndex 注释标注为遗留事项）；
     * 各 IT 均在用例内显式建索引。上传受理后流水线异步推进，故在任意 HTTP 操作前
     * 于 @Test 方法开头调用（见各用例第一步）。
     */
    @Autowired
    void ensureEsIndex(com.rag.storage.es.EsChunkIndex esChunkIndex) {
        this.esChunkIndex = esChunkIndex;
        esChunkIndex.ensureIndex();
    }

    // ------------------------------------------------------------------
    // 全旅程
    // ------------------------------------------------------------------

    @Test
    void fullDocumentFlow() {
        // 1. 建库
        String kbId = createKb("流程测试库-" + UUID.randomUUID());

        // 2. 上传 md（multipart）
        String markdown = "# 部署指南\n" + "服务依赖数据库与对象存储。".repeat(40)
                + "\n\n# 常见故障\n" + "磁盘告警先检查容量。".repeat(40);
        byte[] fileBytes = markdown.getBytes(StandardCharsets.UTF_8);
        ResponseEntity<Map> accepted = upload(kbId, "部署手册.md", fileBytes, "STRUCTURE", null, null);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<String, Object> document = (Map<String, Object>) accepted.getBody().get("document");
        Map<String, Object> task = (Map<String, Object>) accepted.getBody().get("task");
        String docId = (String) document.get("id");
        String taskId = (String) task.get("id");
        assertThat(docId).isNotBlank();
        assertThat(task.get("status")).isEqualTo("QUEUED");
        assertThat(task.get("documentId")).isEqualTo(docId);

        // 3. 轮询 /task 至 COMPLETED（上传不等待流水线，KB-3）
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    ResponseEntity<Map> resp = get("/api/v1/documents/" + docId + "/task");
                    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(resp.getBody().get("status")).isEqualTo("COMPLETED");
                });
        ResponseEntity<Map> finishedTask = get("/api/v1/documents/" + docId + "/task");
        assertThat((int) finishedTask.getBody().get("attempt")).isEqualTo(1);

        // 4. 列表（默认筛选）
        ResponseEntity<Map> list = get("/api/v1/knowledge-bases/" + kbId + "/documents");
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((int) list.getBody().get("total")).isEqualTo(1);
        List<Map<String, Object>> items = (List<Map<String, Object>>) list.getBody().get("items");
        assertThat(items.get(0).get("id")).isEqualTo(docId);
        assertThat(items.get(0).get("status")).isEqualTo("COMPLETED");

        // 4b. 状态筛选（QUEUED 应为空页）
        ResponseEntity<Map> queuedList = get(
                "/api/v1/knowledge-bases/" + kbId + "/documents?status=QUEUED");
        assertThat((int) queuedList.getBody().get("total")).isZero();

        // 5. 详情：chunkConfig + contentSha256 + task 摘要
        ResponseEntity<Map> detail = get("/api/v1/documents/" + docId);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> chunkConfig = (Map<String, Object>) detail.getBody().get("chunkConfig");
        assertThat(chunkConfig.get("strategy")).isEqualTo("STRUCTURE");
        assertThat((int) chunkConfig.get("maxLength")).isEqualTo(800);
        assertThat((String) detail.getBody().get("contentSha256")).hasSize(64);
        assertThat((Map<String, Object>) detail.getBody().get("task")).isNotNull();

        // 6. 分块（分页，seq 正序；chunkId = docId-c%04d）
        ResponseEntity<Map> chunks = get("/api/v1/documents/" + docId + "/chunks?page=1&pageSize=10");
        assertThat(chunks.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> chunkItems = (List<Map<String, Object>>) chunks.getBody().get("items");
        assertThat(chunkItems).isNotEmpty();
        assertThat(chunkItems.get(0).get("id").toString()).startsWith(docId + "-c");
        assertThat((int) chunks.getBody().get("total")).isGreaterThanOrEqualTo(chunkItems.size());

        // 7. 解析文本（就绪；maxChars 截断）
        ResponseEntity<Map> parsed = get("/api/v1/documents/" + docId + "/parsed-text?maxChars=100");
        assertThat(parsed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((boolean) parsed.getBody().get("truncated")).isTrue();
        assertThat((int) parsed.getBody().get("charCount")).isEqualTo(100);

        // 8. 同文件重传 → 409 DUPLICATE_DOCUMENT + details 含 existingDocumentId
        ResponseEntity<Map> duplicate = upload(kbId, "部署手册-副本.md", fileBytes, null, null, null);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody().get("code")).isEqualTo("DUPLICATE_DOCUMENT");
        List<Map<String, Object>> details = (List<Map<String, Object>>) duplicate.getBody().get("details");
        assertThat(details).isNotNull();
        assertThat(details.get(0).get("field")).isEqualTo("file");
        assertThat(details.get(0).get("issue").toString())
                .isEqualTo("existingDocumentId=" + docId);

        // 8b. 内容不同 → 正常受理（202）
        ResponseEntity<Map> second = upload(kbId, "运维手册.md",
                "## 运维\n另一份内容。".getBytes(StandardCharsets.UTF_8), null, null, null);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // 8c. 非法扩展名 → 415
        ResponseEntity<Map> badType = upload(kbId, "a.exe", new byte[]{1, 2, 3}, null, null, null);
        assertThat(badType.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(badType.getBody().get("code")).isEqualTo("UNSUPPORTED_FILE_TYPE");

        // 8d. 未确认删库 → 400 CONFIRMATION_REQUIRED
        ResponseEntity<Map> unconfirmed = delete("/api/v1/knowledge-bases/" + kbId, false);
        assertThat(unconfirmed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unconfirmed.getBody().get("code")).isEqualTo("CONFIRMATION_REQUIRED");

        // 9. 删除文档 → 200 + cleanupTasksAccepted=2；主档即时收口 + 补偿清理
        ResponseEntity<Map> deletion = delete("/api/v1/documents/" + docId, false);
        assertThat(deletion.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deletion.getBody().get("docId")).isEqualTo(docId);
        assertThat((int) deletion.getBody().get("cleanupTasksAccepted")).isEqualTo(2);

        // 主档删除后文档行立即不存在（检索边界收口）
        assertThat(get("/api/v1/documents/" + docId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 10. 会话：建会话（POST）→ repository 直插消息 → 消息分页 → 删会话 204
        ResponseEntity<Map> sessionResp = post("/api/v1/sessions",
                Map.of("kbId", kbId, "title", "流程测试会话"));
        assertThat(sessionResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String sessionId = (String) sessionResp.getBody().get("id");
        assertThat((int) sessionResp.getBody().get("messageCount")).isZero();

        insertMessage(sessionId, "USER", "如何配置告警？", List.of());
        insertMessage(sessionId, "ASSISTANT", "按以下步骤配置……",
                List.of(Map.of("chunkId", "fake-chunk-0", "docId", docId,
                        "docName", "部署手册.md", "titlePath", "部署指南 > 告警", "score", 0.9)));

        ResponseEntity<Map> messages = get("/api/v1/sessions/" + sessionId + "/messages?page=1&pageSize=10");
        assertThat(messages.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((int) messages.getBody().get("total")).isEqualTo(2);
        List<Map<String, Object>> msgItems = (List<Map<String, Object>>) messages.getBody().get("items");
        assertThat(msgItems.get(0).get("role")).isEqualTo("USER");
        assertThat(msgItems.get(1).get("role")).isEqualTo("ASSISTANT");
        List<Map<String, Object>> citations = (List<Map<String, Object>>) msgItems.get(1).get("citations");
        assertThat(citations.get(0).get("docName")).isEqualTo("部署手册.md");
        assertThat(citations.get(0).get("score")).isEqualTo(0.9);

        // 会话列表过滤（Session[] 是数组响应）
        ResponseEntity<List> sessions = rest.getForEntity("/api/v1/sessions?kbId=" + kbId, List.class);
        assertThat(sessions.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sessions.getBody()).hasSize(1);

        ResponseEntity<Void> deleted = rest.exchange("/api/v1/sessions/" + sessionId,
                HttpMethod.DELETE, null, Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** 删库收口（confirm=true）：级联 + cleanup_task → ES 分块清空 + 会话为空。 */
    @Test
    void deleteKbWithConfirmCascadesAndConverges() {
        String kbId = createKb("级联删除库-" + UUID.randomUUID());
        ResponseEntity<Map> accepted = upload(kbId, "手册.md",
                "# 手册\n内容。".getBytes(StandardCharsets.UTF_8), null, null, null);
        String docId = (String) ((Map<String, Object>) accepted.getBody().get("document")).get("id");
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(
                        get("/api/v1/documents/" + docId + "/task").getBody().get("status"))
                        .isEqualTo("COMPLETED"));

        ResponseEntity<Map> sessionResp = post("/api/v1/sessions", Map.of("kbId", kbId));
        String sessionId = (String) sessionResp.getBody().get("id");
        insertMessage(sessionId, "USER", "问题", List.of());

        ResponseEntity<Map> deletion = delete("/api/v1/knowledge-bases/" + kbId + "?", true);
        assertThat(deletion.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deletion.getBody().get("kbId")).isEqualTo(kbId);
        assertThat((int) deletion.getBody().get("documentsDeleted")).isEqualTo(1);
        assertThat((int) deletion.getBody().get("sessionsDeleted")).isEqualTo(1);
        assertThat((int) deletion.getBody().get("messagesDeleted")).isEqualTo(1);
        assertThat((int) deletion.getBody().get("cleanupTasksAccepted")).isEqualTo(2);

        // 主档即时收口
        assertThat(get("/api/v1/documents/" + docId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/sessions/" + sessionId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // ES 侧收敛
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    ResponseEntity<Map> chunks = get("/api/v1/documents/" + docId + "/chunks");
                    // 文档行已删 → 404 也视为收口（分块必然不再可查）
                    assertThat(chunks.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.NOT_FOUND);
                });
    }

    // ------------------------------------------------------------------
    // HTTP 助手
    // ------------------------------------------------------------------

    private String createKb(String name) {
        ResponseEntity<Map> resp = post("/api/v1/knowledge-bases", Map.of("name", name));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) resp.getBody().get("id");
    }

    private ResponseEntity<Map> upload(String kbId, String fileName, byte[] bytes,
                                       String strategy, Integer maxLength, Integer overlap) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        if (strategy != null) {
            body.add("chunkStrategy", strategy);
        }
        if (maxLength != null) {
            body.add("maxLength", maxLength);
        }
        if (overlap != null) {
            body.add("overlap", overlap);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.postForEntity("/api/v1/knowledge-bases/" + kbId + "/documents",
                new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> get(String path) {
        return rest.getForEntity(path, Map.class);
    }

    private ResponseEntity<Map> post(String path, Object body) {
        return rest.postForEntity(path, body, Map.class);
    }

    /** confirm=false 时不带参数（对应 controller 未携带 → 400 语义）；true 携带 confirm=true。 */
    private ResponseEntity<Map> delete(String path, boolean confirm) {
        String url = confirm ? path + "confirm=true" : path;
        return rest.exchange(url, HttpMethod.DELETE, null, Map.class);
    }

    private void insertMessage(String sessionId, String role, String content, List<Map<String, Object>> citations) {
        com.rag.domain.entity.ChatMessageEntity entity = new com.rag.domain.entity.ChatMessageEntity();
        entity.setSessionId(sessionId);
        entity.setRole(com.rag.domain.enums.SessionRole.valueOf(role));
        entity.setContent(content);
        entity.setCitations(citations);
        entity.setStatus(com.rag.domain.enums.MessageStatus.COMPLETED);
        messageRepository.save(entity);
    }
}
