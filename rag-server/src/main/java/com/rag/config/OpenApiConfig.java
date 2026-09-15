package com.rag.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc 配置。运行时导出 /v3/api-docs，供 Task 7 的契约漂移测试与
 * contracts/openapi.yaml 比对。控制器由后续任务（T4/T5/T6）按契约逐域实现。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ragOpenApi() {
        return new OpenAPI().info(new Info()
                .title("通用知识问答 RAG API")
                .description("文档入库（解析→清洗→分块→向量化→入库）→ 向量检索 → SSE 流式回答。"
                        + "权威契约：contracts/openapi.yaml")
                .version("1.0.0"));
    }
}
