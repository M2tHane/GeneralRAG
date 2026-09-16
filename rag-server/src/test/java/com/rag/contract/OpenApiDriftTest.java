package com.rag.contract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约漂移测试（计划 Task 7）：拉取运行时 /v3/api-docs，与 contracts/openapi.yaml
 * 逐路径比对（方法集合、operationId、响应状态码、请求体存在性）。
 *
 * <p>结构级比对而非 openapi-diff 全量 diff：springdoc 2.x 导出 OpenAPI 3.0.2 且泛型
 * 信封 schema 命名与手写契约不同（如 PageResultDocument vs DocumentPage），逐字段 diff
 * 会淹没在格式差异里。本测试守护的是「不丢路径、不改方法、不丢 operationId、
 * 不删响应状态码、不删请求体」这些 breaking 变化；schema 字段级漂移由
 * openapi-typescript 生成的前端类型在编译期兜底。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OpenApiDriftTest {

    @LocalServerPort
    int port;

    @Autowired TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {


        registry.add("minio.access-key", () -> "minioadmin");
        registry.add("minio.secret-key", () -> "minioadmin");
        registry.add("minio.bucket", () -> "drift");
        registry.add("rag.models.chat.base-url", () -> "http://localhost:1");
        registry.add("rag.models.embedding.base-url", () -> "http://localhost:1");
        registry.add("rag.models.startup-check", () -> "false");
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> METHODS = List.of("get", "post", "put", "patch", "delete");

    @Test
    @SuppressWarnings("unchecked")
    void runtimeApiMatchesContract() throws Exception {
        String docsJson = restTemplate.getForObject("http://localhost:" + port + "/v3/api-docs", String.class);
        assertThat(docsJson).isNotNull();
        JsonNode runtime = JSON.readTree(docsJson);

        Path contractPath = Path.of("..", "contracts", "openapi.yaml");
        if (!Files.exists(contractPath)) {
            contractPath = Path.of("contracts", "openapi.yaml");
        }
        assertThat(Files.exists(contractPath)).as("契约文件存在：" + contractPath.toAbsolutePath()).isTrue();
        // 手写契约是 YAML——用 snakeyaml 解析（spring-boot 已传递依赖）
        org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
        Map<String, Object> contract = yaml.load(Files.readString(contractPath));

        Map<String, Object> contractPaths = (Map<String, Object>) contract.get("paths");
        JsonNode runtimePaths = runtime.path("paths");

        List<String> problems = new ArrayList<>();

        // 1. 契约里的每个 path+method 必须在运行时存在，且 operationId 一致
        for (Map.Entry<String, Object> pathEntry : contractPaths.entrySet()) {
            String path = pathEntry.getKey();
            JsonNode runtimeOps = runtimePaths.path(path);
            if (runtimeOps.isMissingNode()) {
                problems.add("运行时缺少路径：" + path);
                continue;
            }
            Map<String, Object> contractOps = (Map<String, Object>) pathEntry.getValue();
            for (Map.Entry<String, Object> opEntry : contractOps.entrySet()) {
                String method = opEntry.getKey();
                if (!METHODS.contains(method) || !(opEntry.getValue() instanceof Map)) {
                    continue;
                }
                JsonNode runtimeOp = runtimeOps.path(method);
                if (runtimeOp.isMissingNode()) {
                    problems.add("运行时缺少操作：" + method.toUpperCase() + " " + path);
                    continue;
                }
                Map<String, Object> contractOp = (Map<String, Object>) opEntry.getValue();
                String expectedId = String.valueOf(contractOp.get("operationId"));
                String actualId = runtimeOp.path("operationId").asText();
                if (!expectedId.equals(actualId)) {
                    problems.add("operationId 不一致：" + method.toUpperCase() + " " + path
                            + " 契约=" + expectedId + " 运行时=" + actualId);
                }
                // 响应状态码：契约的 2xx 成功状态码必须在运行时出现。
                // 契约里的 4xx/5xx 由 GlobalExceptionHandler 统一产生（行为已被控制器测试覆盖），
                // springdoc 不会从异常处理器推导这些声明——属文档生成限制而非行为缺失，不在此比较。
                Map<String, Object> contractResponses = (Map<String, Object>) contractOp.get("responses");
                if (contractResponses != null) {
                    for (String statusCode : contractResponses.keySet()) {
                        boolean isSuccess = statusCode.startsWith("2");
                        if (isSuccess && !runtimeOp.path("responses").has(statusCode)) {
                            problems.add("运行时缺少成功响应状态码：" + method.toUpperCase() + " " + path
                                    + " → " + statusCode);
                        }
                    }
                }
                // 请求体：契约要求 requestBody 时运行时必须有
                if (contractOp.containsKey("requestBody") && runtimeOp.path("requestBody").isMissingNode()) {
                    problems.add("运行时缺少请求体：" + method.toUpperCase() + " " + path);
                }
            }
        }

        // 2. 运行时新增路径允许（契约之外不报错），但契约方法在运行时被换成别的动词不行——已由上面覆盖
        assertThat(problems)
                .as("契约漂移（%d 处）", problems.size())
                .isEmpty();
    }
}
