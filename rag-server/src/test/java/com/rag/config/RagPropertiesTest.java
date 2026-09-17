package com.rag.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * RagProperties 启动期校验行为（docs/03-技术路线.md §5）：
 * 全默认+合法值可加载且默认值正确；base-url 缺失、top-k&lt;1、min-score 越界均拒绝启动。
 */
class RagPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestApp.class);

    @Configuration
    @EnableConfigurationProperties(RagProperties.class)
    static class TestApp {
    }

    private static final String CHAT_BASE_URL = "rag.models.chat.base-url=http://localhost:8000/v1";
    private static final String EMBEDDING_BASE_URL = "rag.models.embedding.base-url=http://localhost:8001/v1";

    @Test
    void legalMinimalConfigBindsWithRouteDefaults() {
        runner.withPropertyValues(CHAT_BASE_URL, EMBEDDING_BASE_URL).run(context -> {
            assertThat(context).hasNotFailed();
            RagProperties props = context.getBean(RagProperties.class);

            assertThat(props.getModels().getChat().getBaseUrl()).isEqualTo("http://localhost:8000/v1");
            assertThat(props.getModels().getChat().getApiKey()).isEmpty();
            assertThat(props.getModels().getChat().getModelName()).isEqualTo("qwen2.5-7b-instruct");
            assertThat(props.getModels().getChat().getTemperature()).isEqualTo(0.2);
            assertThat(props.getModels().getChat().getTimeoutSeconds()).isEqualTo(120);
            assertThat(props.getModels().getChat().getMaxHistoryMessages()).isEqualTo(8);

            assertThat(props.getModels().isStartupCheck()).isTrue();
            assertThat(props.getModels().getEmbedding().getBaseUrl()).isEqualTo("http://localhost:8001/v1");
            assertThat(props.getModels().getEmbedding().getModelName()).isEqualTo("bge-m3");
            assertThat(props.getModels().getEmbedding().getDimensions()).isEqualTo(1024);
            assertThat(props.getModels().getEmbedding().getTimeoutSeconds()).isEqualTo(30);

            assertThat(props.getRetrieval().getTopK()).isEqualTo(6);
            assertThat(props.getRetrieval().getMinScore()).isEqualTo(0.30);
            assertThat(props.getRetrieval().getMaxContextChars()).isEqualTo(6000);

            // R6-C：Query Rewrite 默认值
            assertThat(props.getRetrieval().getQueryRewrite().isEnabled()).isTrue();
            assertThat(props.getRetrieval().getQueryRewrite().getTimeoutSeconds()).isEqualTo(5);
            assertThat(props.getRetrieval().getQueryRewrite().getMaxQueryLength()).isEqualTo(200);
            assertThat(props.getRetrieval().getQueryRewrite().getMaxHistoryTurns()).isEqualTo(4);

            assertThat(props.getIngestion().getWorkerThreads()).isEqualTo(2);
            assertThat(props.getIngestion().getMaxUploadSizeMb()).isEqualTo(50);

            assertThat(props.getElasticsearch().getContentAnalyzer()).isEqualTo("ik_max_word");
        });
    }

    @Test
    void missingChatBaseUrlFailsStartup() {
        runner.withPropertyValues(EMBEDDING_BASE_URL).run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureMessages(context)).contains("rag.models.chat.base-url");
        });
    }

    @Test
    void zeroTopKFailsStartup() {
        runner.withPropertyValues(CHAT_BASE_URL, EMBEDDING_BASE_URL,
                "rag.retrieval.top-k=0"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureMessages(context)).contains("rag.retrieval.top-k");
        });
    }

    @Test
    void minScoreOutOfRangeFailsStartup() {
        runner.withPropertyValues(CHAT_BASE_URL, EMBEDDING_BASE_URL,
                "rag.retrieval.min-score=1.5"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureMessages(context)).contains("rag.retrieval.min-score");
        });
    }

    @Test
    void zeroDimensionsFailsStartup() {
        runner.withPropertyValues(CHAT_BASE_URL, EMBEDDING_BASE_URL,
                "rag.models.embedding.dimensions=0"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureMessages(context)).contains("dimensions");
        });
    }

    @Test
    void zeroQueryRewriteTimeoutFailsStartup() {
        runner.withPropertyValues(CHAT_BASE_URL, EMBEDDING_BASE_URL,
                "rag.retrieval.query-rewrite.timeout-seconds=0"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureMessages(context)).contains("rag.retrieval.query-rewrite.timeout-seconds");
        });
    }

    /** 失败原因链拼接：Boot 将属性校验失败包在 BeanCreationException 里，需逐层下钻。 */
    private static String failureMessages(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
        StringBuilder sb = new StringBuilder();
        Throwable t = context.getStartupFailure();
        while (t != null) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append('\n');
            }
            t = t.getCause();
        }
        return sb.toString();
    }
}
