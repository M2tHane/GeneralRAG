package com.rag.retrieval;

import java.util.List;

import com.rag.llm.PromptAssembler;
import com.rag.retrieval.model.RetrievalHit;
import com.rag.retrieval.model.RetrievalMode;

/**
 * 一次检索执行的完整请求（R2-D2：问答/调试/评测共用同一条代码路径）。
 *
 * <p>R6-C：新增 {@code history}（可为空）——仅供链路上游（ChatStreamService 等）
 * 做 history-aware query rewrite 使用；{@link RetrievalPipeline} 本身不读取它，
 * 检索算法（Vector/BM25/RRF/Reranker）与各参数完全不变。</p>
 *
 * @param kbId           检索范围知识库（ES filter，QA-1）
 * @param question       用户问题（启用 rewrite 时为"当前用户问题"，pipeline 实际检索
 *                       用的是由上游改写后的 retrievalQuery——见 ChatStreamService）
 * @param topK           topK 覆盖（null = 取配置）
 * @param minScore       minScore 覆盖（null = 取配置）
 * @param mode           检索模式覆盖（null = 取配置）
 * @param candidateLimit 各通道候选数覆盖（null = 取配置）
 * @param history        会话历史（时间正序；仅 rewrite 触发判断用，可为 null）
 */
public record RetrievalRequest(String kbId, String question, Integer topK, Double minScore,
                               RetrievalMode mode, Integer candidateLimit,
                               List<PromptAssembler.HistoryTurn> history) {

    public RetrievalRequest {
        history = history == null ? List.of() : history;
    }

    /** 默认请求：全部取服务端配置。 */
    public static RetrievalRequest of(String kbId, String question) {
        return new RetrievalRequest(kbId, question, null, null, null, null, null);
    }

    /** 调试/评测用：可覆盖 topK/minScore。 */
    public static RetrievalRequest of(String kbId, String question, Integer topK, Double minScore) {
        return new RetrievalRequest(kbId, question, topK, minScore, null, null, null);
    }

    /** 兼容旧六参构造（无 history）。 */
    public RetrievalRequest(String kbId, String question, Integer topK, Double minScore,
                            RetrievalMode mode, Integer candidateLimit) {
        this(kbId, question, topK, minScore, mode, candidateLimit, null);
    }
}
