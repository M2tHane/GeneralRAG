package com.rag.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * 假 mineru-api 服务（R6-D 测试替身，协议面与 MineruParser 对齐）：
 *
 * <ul>
 *   <li>GET /health → 200</li>
 *   <li>POST /file_parse（multipart）→ {"results": {"source": {"md_content": …}}}</li>
 * </ul>
 *
 * <p>可注入故障：{@link #setFailure(boolean)}（返回 500）。
 * 记录收到的解析请求数（断言 MinerU 确实被调用 / PDFBox 未被当最终 parser 使用）。</p>
 */
public class FakeMineruServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private int port;
    private final AtomicBoolean failure = new AtomicBoolean(false);
    private final AtomicInteger parseRequests = new AtomicInteger(0);
    private volatile String mdContent = "# MinerU OCR\n扫描页正文，OCR 提取的文本内容。";

    public void start() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/file_parse", exchange -> {
            parseRequests.incrementAndGet();
            exchange.getRequestBody().readAllBytes(); // 消费 multipart
            if (failure.get()) {
                byte[] err = "simulated mineru crash".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, err.length);
                exchange.getResponseBody().write(err);
                exchange.close();
                return;
            }
            String body = JSON.writeValueAsString(java.util.Map.of(
                    "backend", "pipeline", "version", "3.4.5-test",
                    "results", java.util.Map.of("source", java.util.Map.of("md_content", mdContent))));
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public String baseUrl() {
        return "http://localhost:" + port;
    }

    public void setFailure(boolean value) {
        failure.set(value);
    }

    public void setMdContent(String value) {
        mdContent = value;
    }

    /** 已收到的 /file_parse 请求数。 */
    public int parseRequests() {
        return parseRequests.get();
    }
}
