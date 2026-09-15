package com.rag.retrieval;

import java.util.List;

import com.rag.retrieval.model.RetrievalHit;
import com.rag.retrieval.model.RetrievalMode;

/**
 * 一次检索执行的完整请求（R2-D2：问答/调试/评测共用同一条代码路径）。
 *
 * @param kbId           检索范围知识库（ES filter，QA-1）
 * @param question       用户问题
 * @param topK           topK 覆盖（null = 取配置）
 * @param minScore       minScore 覆盖（null = 取配置）
 * @param mode           检索模式覆盖（null = 取配置）
 * @param candidateLimit 各通道候选数覆盖（null = 取配置）
 */
public record RetrievalRequest(String kbId, String question, Integer topK, Double minScore,
                               RetrievalMode mode, Integer candidateLimit) {

    /** 默认请求：全部取服务端配置。 */
    public static RetrievalRequest of(String kbId, String question) {
        return new RetrievalRequest(kbId, question, null, null, null, null);
    }

    /** 调试/评测用：可覆盖 topK/minScore。 */
    public static RetrievalRequest of(String kbId, String question, Integer topK, Double minScore) {
        return new RetrievalRequest(kbId, question, topK, minScore, null, null);
    }
}
