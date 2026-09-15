package com.rag.answerability;

import java.util.List;

import com.rag.config.RagProperties;
import com.rag.llm.PromptAssembler;
import com.rag.llm.RefusalPolicy;
import com.rag.retrieval.model.Context;
import com.rag.retrieval.model.RetrievalHit;
import com.rag.retrieval.model.RetrievalMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Answerability 判定输入（R4.1 §二十）：把判定所需上下文收敛为一个结构，
 * 避免 7 个散落参数的调用漂移——尤其保证 <b>question / history / context 三者
 * 永远一起出现</b>，不会出现"传了 history 忘了换新 context"的组合错误。
 *
 * @param question      用户当前问题（Judge 提示词独立成段使用）
 * @param history       会话历史（时间正序；仅用于 Judge 解析指代，不是证据；可为空）
 * @param context       上下文（ContextAssembler 产物；<b>与生成用同一实例</b>）
 * @param hits          检索命中（含未过阈值项，与 RefusalPolicy 输入一致）
 * @param mode          生效检索模式（决定阈值口径）
 * @param rerankApplied 重排是否真正生效
 */
public record AnswerabilityInput(String question,
                                 List<PromptAssembler.HistoryTurn> history,
                                 Context context,
                                 List<RetrievalHit> hits,
                                 RetrievalMode mode,
                                 boolean rerankApplied) {

    public AnswerabilityInput {
        history = history == null ? List.of() : history;
        hits = hits == null ? List.of() : hits;
    }

    /** 无历史的便捷构造（调试页等场景）。 */
    public static AnswerabilityInput of(String question, Context context,
                                        List<RetrievalHit> hits, RetrievalMode mode, boolean rerankApplied) {
        return new AnswerabilityInput(question, List.of(), context, hits, mode, rerankApplied);
    }
}
