package com.rag.retrieval.model;

import java.util.List;

/**
 * 送入模型的上下文（ContextAssembler 产物）。
 *
 * @param text      拼接后的上下文全文
 * @param charCount 文本字符数
 * @param chunkIds  组成上下文的分块 ID（按拼接顺序）
 * @param truncated 是否因超过 max-context-chars 被截断
 */
public record Context(String text, int charCount, List<String> chunkIds, boolean truncated) {

    public static final String EMPTY_EVIDENCE_TEXT = "（无相关资料）";

    public static Context empty() {
        return new Context(EMPTY_EVIDENCE_TEXT, EMPTY_EVIDENCE_TEXT.length(), List.of(), false);
    }
}
