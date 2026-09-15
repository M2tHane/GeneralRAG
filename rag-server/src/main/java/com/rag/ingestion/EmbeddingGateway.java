package com.rag.ingestion;

import java.util.List;

/**
 * 嵌入网关（ingestion 包内接口，隔离流水线对具体模型客户端的依赖）。
 *
 * <p>装配约定（Task 3 ↔ Task 5 对接点）：langchain4j {@code EmbeddingModel} bean 由
 * Task 5 的 {@code config/ModelClients} 提供；本包的 {@link DefaultEmbeddingGateway}
 * 通过 {@code ObjectProvider<EmbeddingModel>} 惰性适配——应用启动时无该 bean 不会失败，
 * 直到 EMBEDDING 阶段真正调用时才报错。检索/评测侧（Task 5/6）直接使用
 * EmbeddingModel bean，不经本网关。</p>
 */
public interface EmbeddingGateway {

    /**
     * 批量向量化。
     *
     * @param texts 非空文本列表（调用方按 ≤16 条分批）
     * @return 与输入一一对应的向量列表
     */
    List<float[]> embed(List<String> texts);
}
