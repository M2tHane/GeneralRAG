package com.rag.ingestion;

import java.util.List;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link EmbeddingGateway} 默认实现：把 Task 5（config/ModelClients）将提供的
 * langchain4j {@link EmbeddingModel} bean 适配为 ingestion 包内接口。
 *
 * <p><b>装配约定</b>：通过 {@link ObjectProvider} 惰性解析——
 * 若容器中尚无 EmbeddingModel bean（Task 5 未完成、或本地未配置模型服务），
 * 构造与启动不受影响，EMBEDDING 阶段首次调用时抛
 * {@link ErrorCode#SERVICE_UNAVAILABLE} 并指出缺什么。Task 5 提供
 * {@code OpenAiEmbeddingModel} bean 后自动生效，无需改本类。</p>
 */
@Component
public class DefaultEmbeddingGateway implements EmbeddingGateway {

    private final ObjectProvider<EmbeddingModel> modelProvider;

    public DefaultEmbeddingGateway(ObjectProvider<EmbeddingModel> modelProvider) {
        this.modelProvider = modelProvider;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        EmbeddingModel model = modelProvider.getIfAvailable();
        if (model == null) {
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Embedding 服务未装配：容器中不存在 EmbeddingModel bean"
                            + "（检查 config.ModelClients 与 EMBEDDING_BASE_URL 配置）");
        }
        List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
        Response<List<Embedding>> response = model.embedAll(segments);
        return response.content().stream()
                .map(Embedding::vector)
                .toList();
    }
}
