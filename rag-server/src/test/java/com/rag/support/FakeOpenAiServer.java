package com.rag.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * OpenAI 兼容假模型服务（测试替身，供 SSE/流水线集成测试使用）。
 *
 * <p>行为：</p>
 * <ul>
 *   <li>GET  /v1/models → 模型列表</li>
 *   <li>POST /v1/embeddings → input 逐条返回 8 维伪向量（按文本哈希扰动，相似文本相近）</li>
 *   <li>POST /v1/chat/completions（stream=true）→ SSE delta 逐 token 输出 {@code chatAnswer}</li>
 *   <li>POST /v1/chat/completions（非流式）→ 完整 JSON</li>
 * </ul>
 *
 * <p>可注入故障：{@link #setChatFailure(boolean)}（chat 返回 500）、
 * {@link #setEmbedFailure(boolean)}（embeddings 返回 500）、
 * {@link #setChatDelayMs(long)}/{@link #setChatTokenDelayMs(long)}（延迟）。</p>
 */
public class FakeOpenAiServer {

    private static final ObjectMapper JSON = new ObjectMapper();
    public static final int DIMENSIONS = 8;

    private HttpServer server;
    private int port;

    private volatile String chatAnswer = "默认回答内容。";
    private volatile long chatDelayMs = 0;
    private volatile long chatTokenDelayMs = 0;
    private final AtomicBoolean chatFailure = new AtomicBoolean(false);
    /** R4.1.2：前 N 次流式 chat 请求正常、之后全部 500（0 = 关闭，等效 setChatFailure(false)）。 */
    private final java.util.concurrent.atomic.AtomicInteger chatFailAfter =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** R4.1.2：已收到的流式 chat 请求计数（chatFailAfter 的判定基准）。 */
    private final java.util.concurrent.atomic.AtomicInteger chatStreamCount =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private final AtomicBoolean embedFailure = new AtomicBoolean(false);
    /** 供用例读取模型收到了什么 prompt（断言历史/证据分区）。 */
    private final List<String> lastChatPrompts = new ArrayList<>();

    // ---- R4.1：非流式（Judge）请求的定向控制（与流式生成互相独立） ----
    /** 非流式响应内容（Judge 输出）；null = 走默认 chatAnswer。 */
    private volatile String nonStreamAnswer;
    /** 非流式请求额外延迟（毫秒）：模拟 Judge 模型慢响应/超时。 */
    private volatile long nonStreamDelayMs = 0;
    /** 非流式请求失败（500）：模拟 Judge 模型不可用。 */
    private final AtomicBoolean nonStreamFailure = new AtomicBoolean(false);
    /** 已收到的非流式请求计数（断言"Judge 被调/未被调"）。 */
    private final java.util.concurrent.atomic.AtomicInteger nonStreamCount =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 并发非流式请求的活跃数峰值（断言 bulkhead 并发语义）。 */
    private final java.util.concurrent.atomic.AtomicInteger nonStreamActive =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger nonStreamActiveMax =
            new java.util.concurrent.atomic.AtomicInteger();

    public void start() throws IOException {
        server = HttpServer.create(new java.net.InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/v1/models", exchange -> respond(exchange, 200, Map.of(
                "object", "list",
                "data", List.of(Map.of("id", "fake-model", "object", "model")))));
        server.createContext("/v1/embeddings", exchange -> {
            if (embedFailure.get()) {
                respond(exchange, 500, Map.of("error", Map.of("message", "embed failure (test)")));
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            Map<?, ?> req = JSON.readValue(body, Map.class);
            List<?> inputs = req.get("input") instanceof List<?> list
                    ? list : List.of(String.valueOf(req.get("input")));
            List<Map<String, Object>> data = new ArrayList<>();
            int idx = 0;
            for (Object input : inputs) {
                data.add(Map.of(
                        "object", "embedding",
                        "index", idx++,
                        "embedding", embedVector(String.valueOf(input))));
            }
            respond(exchange, 200, Map.of("object", "list", "data", data,
                    "model", "fake-embedding", "usage", Map.of("prompt_tokens", 1, "total_tokens", 1)));
        });
        server.createContext("/v1/chat/completions", exchange -> handleChat(exchange));
        server.start();
    }

    @SuppressWarnings("unchecked")
    private void handleChat(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        try {
            doHandleChat(exchange);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void doHandleChat(com.sun.net.httpserver.HttpExchange exchange)
            throws IOException, InterruptedException {
        try {
            doHandleChatInner(exchange);
        } catch (Exception e) {
            System.err.println("[FakeOpenAiServer] chat handler 异常：" + e);
            exchange.close();
        }
    }

    private void doHandleChatInner(com.sun.net.httpserver.HttpExchange exchange)
            throws IOException, InterruptedException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        Map<?, ?> req = JSON.readValue(body, Map.class);
        recordChatPrompt(req);
        if (chatFailure.get()) {
            respond(exchange, 500, Map.of("error", Map.of("message", "chat failure (test)")));
            return;
        }
        boolean stream = Boolean.TRUE.equals(req.get("stream"));
        // R4.1.2：前 N 次流式请求放行、之后 500（仅流式计数；Judge 走非流式不受影响）
        if (stream && chatFailAfter.get() > 0
                && chatStreamCount.incrementAndGet() > chatFailAfter.get()) {
            respond(exchange, 500, Map.of("error", Map.of("message", "chat failure after N (test)")));
            return;
        }
        if (chatDelayMs > 0) {
            Thread.sleep(chatDelayMs);
        }
        if (!stream) {
            // R4.1：非流式请求（Judge 路径）定向控制
            nonStreamCount.incrementAndGet();
            int active = nonStreamActive.incrementAndGet();
            nonStreamActiveMax.accumulateAndGet(active, Math::max);
            try {
                if (nonStreamFailure.get()) {
                    respond(exchange, 500, Map.of("error", Map.of("message", "judge failure (test)")));
                    return;
                }
                if (nonStreamDelayMs > 0) {
                    Thread.sleep(nonStreamDelayMs);
                }
                String answer = nonStreamAnswer != null ? nonStreamAnswer : chatAnswer;
                respond(exchange, 200, Map.of(
                        "id", "chatcmpl-fake", "object", "chat.completion",
                        "choices", List.of(Map.of(
                                "index", 0,
                                "message", Map.of("role", "assistant", "content", answer),
                                "finish_reason", "stop")),
                        "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
                return;
            } finally {
                nonStreamActive.decrementAndGet();
            }
        }
        // SSE 流式
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        String answer = chatAnswer;
        try (var out = exchange.getResponseBody()) {
            for (int i = 0; i < answer.length(); i += 4) {
                String delta = answer.substring(i, Math.min(i + 4, answer.length()));
                // 注意：Map.of 不允许 null 值（会 NPE），含 null 的字段一律用 HashMap
                out.write(("data: " + JSON.writeValueAsString(chunk(Map.of("content", delta), null)) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (chatTokenDelayMs > 0) {
                    Thread.sleep(chatTokenDelayMs);
                }
            }
            out.write(("data: " + JSON.writeValueAsString(chunk(new java.util.HashMap<>(), "stop")) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /** OpenAI chat.completion.chunk 结构（finishReason 可为 null）。 */
    private static Map<String, Object> chunk(Map<String, Object> delta, String finishReason) {
        Map<String, Object> choice = new java.util.HashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);
        Map<String, Object> chunk = new java.util.HashMap<>();
        chunk.put("id", "chatcmpl-fake");
        chunk.put("object", "chat.completion.chunk");
        chunk.put("choices", List.of(choice));
        return chunk;
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public String baseUrl() {
        return "http://localhost:" + port + "/v1";
    }

    public void setChatAnswer(String answer) {
        this.chatAnswer = answer;
    }

    public void setChatDelayMs(long ms) {
        this.chatDelayMs = ms;
    }

    public void setChatTokenDelayMs(long ms) {
        this.chatTokenDelayMs = ms;
    }

    public void setChatFailure(boolean fail) {
        this.chatFailure.set(fail);
    }

    /** R4.1.2：前 N 次流式生成正常、之后 500（0 = 关闭）；同时重置流式计数。 */
    public void setChatFailureAfter(int n) {
        this.chatStreamCount.set(0);
        this.chatFailAfter.set(n);
        if (n <= 0) {
            this.chatFailure.set(false);
        }
    }

    public void setEmbedFailure(boolean fail) {
        this.embedFailure.set(fail);
    }

    // ---- R4.1：非流式（Judge）定向控制 ----

    /** 设置非流式响应内容（Judge 输出）；传 null 恢复默认（同 chatAnswer）。 */
    public void setNonStreamAnswer(String answer) {
        this.nonStreamAnswer = answer;
    }

    public void setNonStreamDelayMs(long ms) {
        this.nonStreamDelayMs = ms;
    }

    public void setNonStreamFailure(boolean fail) {
        this.nonStreamFailure.set(fail);
    }

    public int nonStreamRequestCount() {
        return nonStreamCount.get();
    }

    public void resetNonStreamCounters() {
        nonStreamCount.set(0);
        nonStreamActive.set(0);
        nonStreamActiveMax.set(0);
    }

    /** 并发非流式请求活跃数峰值（bulkhead 并发断言用）。 */
    public int nonStreamActiveMax() {
        return nonStreamActiveMax.get();
    }

    public List<String> lastChatPrompts() {
        return List.copyOf(lastChatPrompts);
    }

    // ------------------------------------------------------------------

    private void recordChatPrompt(Map<?, ?> req) {
        Object messages = req.get("messages");
        if (messages instanceof List<?> list) {
            list.forEach(m -> {
                if (m instanceof Map<?, ?> msg && msg.get("content") != null) {
                    lastChatPrompts.add(String.valueOf(msg.get("content")));
                }
            });
        }
    }

    /**
     * 8 维伪向量：以文本哈希做确定性扰动，含少量公共分量，
     * 使「文本相近 → 向量相近」的断言可成立（仅测试用途，非真实语义向量）。
     */
    public static float[] embedVector(String text) {
        float[] v = new float[DIMENSIONS];
        v[0] = 0.5f; // 公共基分量：任意两文本余弦相似度都不至于为 0
        if (text != null && !text.isEmpty()) {
            int h = text.hashCode();
            for (int i = 1; i < DIMENSIONS; i++) {
                v[i] = ((h >> i) & 0xFF) / 255.0f;
            }
        }
        float norm = 0;
        for (float x : v) norm += x * x;
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < DIMENSIONS; i++) {
            v[i] /= norm;
        }
        return v;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, Object payload)
            throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 供测试侧解析 SSE data 载荷。 */
    public static Map<String, Object> parseJson(String json) {
        try {
            return JSON.readValue(json, Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
