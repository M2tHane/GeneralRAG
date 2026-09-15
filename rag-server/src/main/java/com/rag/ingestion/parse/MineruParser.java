package com.rag.ingestion.parse;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.rag.config.RagProperties;
import com.rag.domain.enums.FileType;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MinerU 远端解析器（R3-P2）：调用 <a href="https://github.com/opendatalab/MinerU">MinerU</a>
 * 的 {@code mineru-api} HTTP 服务解析 PDF，支持复杂版面与扫描件 OCR。
 *
 * <p>协议（MinerU 3.4.x，实测核实）：</p>
 * <ul>
 *   <li>{@code POST /file_parse}（multipart）：{@code files}（必填，实测字段名不带方括号）、
 *       {@code lang_list=["ch"]}、{@code backend=pipeline}（CPU 兼容）、
 *       {@code parse_method=auto}、{@code return_md=true}；</li>
 *   <li>响应 JSON：{@code {backend, version, results: {"<文件名去扩展名>": {md_content}}}}；
 *       失败返回 409/503；</li>
 *   <li>{@code GET /health} 健康检查。</li>
 * </ul>
 *
 * <p><b>产物映射</b>：md_content 即 Markdown 文本，与 MD 解析产物同构——
 * 标题层级（# / ##）由 StructureChunker 提取为 titlePath，页码信息不支持
 * （{@code page=null}）。</p>
 *
 * <p><b>失败语义</b>：刻意<b>不静默回退 pdfbox</b>——扫描件回退只会得到
 * 「无文本层」错误，两者都必须诚实暴露。服务不可达/超时 → PARSER_UNAVAILABLE(502)；
 * 远端解析失败 → 解析失败语义（422 SCANNED_PDF_NOT_SUPPORTED 不适用于此，统一
 * PARSER_FAILED 语义并携带远端信息）。</p>
 *
 * <p>启用条件：{@code rag.ingestion.pdf-parser=mineru}（Bean 注册开关）且
 * mineru-base-url 非空（调用目标）。未选 mineru 时本 Bean 不注册，PDF 路由到
 * PdfBoxParser（ParserRouter 按 FileType 唯一注册，两个 PDF 解析器互斥）。
 * 本机部署见 docs/round3/02-实施记录-P2.md。</p>
 */
@Component
@ConditionalOnProperty(name = "rag.ingestion.pdf-parser", havingValue = "mineru")
public class MineruParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(MineruParser.class);

    /** mineru-api 健康检查路径。 */
    static final String HEALTH_PATH = "/health";
    /** mineru-api 同步解析路径。 */
    static final String PARSE_PATH = "/file_parse";

    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient http;
    private final boolean boundarySet;

    /**
     * @param enabled    rag.ingestion.pdf-parser == mineru
     * @param baseUrl    rag.ingestion.mineru-base-url
     * @param timeout    rag.ingestion.mineru-timeout-seconds
     */
    public MineruParser(RagProperties properties) {
        RagProperties.Ingestion cfg = properties.getIngestion();
        this.baseUrl = stripTrailingSlash(cfg.getMineruBaseUrl());
        this.timeout = Duration.ofSeconds(cfg.getMineruTimeoutSeconds());
        this.boundarySet = !baseUrl.isEmpty();
        if (boundarySet) {
            log.info("MinerU 解析器启用：base-url={}，timeout={}s", baseUrl, timeout.toSeconds());
        } else {
            log.warn("MinerU 解析器已选择但 mineru-base-url 为空：解析时将显式失败");
        }
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** ParserRouter 注册条件：配置选择 mineru 且 base-url 已给。 */
    public boolean isEnabled() {
        return boundarySet;
    }

    @Override
    public FileType supportedType() {
        return FileType.PDF;
    }

    @Override
    public ParsedDocument parse(InputStream in, FileType type) {
        if (!boundarySet) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                    "MinerU 解析器未启用：需要 rag.ingestion.pdf-parser=mineru 且配置"
                            + " rag.ingestion.mineru-base-url（环境变量 MINERU_BASE_URL）");
        }
        byte[] pdf;
        try {
            pdf = in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "读取 PDF 流失败：" + e.getMessage());
        }

        String boundary = "rag-mineru-" + Long.toHexString(System.nanoTime());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + PARSE_PATH))
                .timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipartBody(pdf, boundary))
                .build();

        String body;
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR,
                        "MinerU 解析失败（HTTP " + response.statusCode() + "）："
                                + snippet(response.body()));
            }
            body = response.body();
        } catch (java.io.IOException e) {
            throw new DomainException(ErrorCode.PARSER_UNAVAILABLE,
                    "MinerU 服务不可达（" + baseUrl + "）：" + e.getMessage()
                            + "。请确认 mineru-api 已启动，或将 rag.ingestion.pdf-parser 改回 pdfbox。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "MinerU 调用被中断");
        }

        return toParsedDocument(body);
    }

    /** 健康检查（部署验证用）：可达返回 true。 */
    public boolean healthy() {
        if (!boundarySet) {
            return false;
        }
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder().uri(URI.create(baseUrl + HEALTH_PATH))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (java.io.IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 响应 → ParsedDocument：取 results 中第一个条目的 md_content。
     * results 为空 / md_content 缺失 / 全空白 → 解析失败（不产生空语料）。
     */
    static ParsedDocument toParsedDocument(String responseBody) {
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseBody);
        } catch (java.io.IOException e) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                    "MinerU 响应不是合法 JSON：" + snippet(responseBody));
        }
        com.fasterxml.jackson.databind.JsonNode results = root.path("results");
        if (!results.isObject() || results.isEmpty()) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                    "MinerU 响应缺少 results：" + snippet(responseBody));
        }
        List<String> mdContents = new ArrayList<>();
        results.forEach(entry -> {
            String md = entry.path("md_content").asText(null);
            if (md != null && !md.isBlank()) {
                mdContents.add(md);
            }
        });
        if (mdContents.isEmpty()) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                    "MinerU 解析结果为空（md_content 缺失或空白）：可能是纯图片且 OCR 未产出文本，"
                            + "或远端解析失败");
        }
        // 单文件上传只有一个条目；多文件按序拼接（不产生跨文件边界混淆——调用方只传 1 个）
        String md = String.join("\n\n", mdContents);
        return ParsedDocument.plain(md, MarkdownParser.extractHeadings(md));
    }

    /** 手工构造 multipart（JDK HttpClient 无内建 multipart）：files + lang_list + backend + parse_method + return_md。 */
    static HttpRequest.BodyPublisher multipartBody(byte[] pdf, String boundary) {
        List<byte[]> parts = new ArrayList<>();
        parts.add(bytes("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"files\"; filename=\"source.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n"));
        parts.add(pdf);
        parts.add(bytes("\r\n"));
        parts.add(formField(boundary, "lang_list", "ch"));
        parts.add(formField(boundary, "backend", "pipeline"));
        parts.add(formField(boundary, "parse_method", "auto"));
        parts.add(formField(boundary, "return_md", "true"));
        parts.add(bytes("--" + boundary + "--\r\n"));
        return HttpRequest.BodyPublishers.ofByteArrays(parts);
    }

    private static byte[] formField(String boundary, String name, String value) {
        return bytes("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replace('\n', ' ');
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }

}
