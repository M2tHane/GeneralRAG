package com.rag.config;

import java.time.Duration;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j 模型客户端装配（docs/03-技术路线.md §2 规则 4 / §5）。
 *
 * <p>业务代码只依赖 {@link StreamingChatModel} / {@link EmbeddingModel} 接口，
 * 模型切换由 RagProperties 驱动。startup-check=true 时启动即向 embedding 服务
 * 发一次真实探活请求并校验返回维度与配置一致（维度不一致是最隐蔽的线上事故源，
 * 必须启动期拦截，失败信息直接指出需要核对的环境变量）。</p>
 */
@Configuration
public class ModelClients {

    private static final Logger log = LoggerFactory.getLogger(ModelClients.class);

    @Bean
    public StreamingChatModel streamingChatModel(RagProperties ragProperties) {
        RagProperties.Chat chat = ragProperties.getModels().getChat();
        return OpenAiStreamingChatModel.builder()
                .baseUrl(chat.getBaseUrl())
                .apiKey(chat.getApiKey())
                .modelName(chat.getModelName())
                .temperature(chat.getTemperature())
                .timeout(Duration.ofSeconds(chat.getTimeoutSeconds()))
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel(RagProperties ragProperties) {
        RagProperties.Embedding embedding = ragProperties.getModels().getEmbedding();
        return OpenAiEmbeddingModel.builder()
                .baseUrl(embedding.getBaseUrl())
                .apiKey(embedding.getApiKey())
                .modelName(embedding.getModelName())
                .timeout(Duration.ofSeconds(embedding.getTimeoutSeconds()))
                .build();
    }

    /** 启动探活：连通性 + 维度一致性校验（rag.models.startup-check 控制）。 */
    @Bean
    public ApplicationRunner modelStartupCheckRunner(EmbeddingModel embeddingModel, RagProperties ragProperties) {
        return (ApplicationArguments args) -> {
            RagProperties.Embedding cfg = ragProperties.getModels().getEmbedding();
            if (!ragProperties.getModels().isStartupCheck()) {
                log.info("rag.models.startup-check=false，跳过 embedding 启动探活");
                return;
            }
            int expected = cfg.getDimensions();
            int actual;
            try {
                float[] vector = embeddingModel.embed("startup check").content().vector();
                actual = vector == null ? -1 : vector.length;
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "embedding 服务启动探活失败：" + e.getMessage()
                                + "。请核对环境变量 EMBEDDING_BASE_URL（当前 " + cfg.getBaseUrl()
                                + "）、EMBEDDING_API_KEY、EMBEDDING_MODEL_NAME（当前 " + cfg.getModelName()
                                + "）指向的 OpenAI 兼容端点是否可达；模型服务晚于应用启动的本地场景可设"
                                + " RAG_MODELS_STARTUP_CHECK=false 跳过。", e);
            }
            if (actual != expected) {
                throw new IllegalStateException(
                        "embedding 返回维度与配置不一致：配置 dimensions=" + expected
                                + "（环境变量 EMBEDDING_DIMENSIONS/rag.models.embedding.dimensions），"
                                + "实际返回 " + actual + "（模型 " + cfg.getModelName() + "）。"
                                + "请修正配置，或更换与配置维度一致的 embedding 模型；"
                                + "维度变更需清库重建（docs/03-技术路线.md §12 决策 5）。");
            }
            log.info("embedding 启动探活通过：模型={}，维度={}", cfg.getModelName(), actual);
        };
    }
}
