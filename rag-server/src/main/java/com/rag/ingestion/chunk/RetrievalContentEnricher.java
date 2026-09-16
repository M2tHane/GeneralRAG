package com.rag.ingestion.chunk;

import org.springframework.stereotype.Component;

/**
 * 检索表示增强器（R5-A）：把 chunk 的确定性元数据（document name / titlePath）
 * 拼进<b>检索专用</b>文本，提高 Embedding / BM25 / Reranker 对"文档归属"类
 * 查询的召回。
 *
 * <p><b>原则（R5-A §1）</b>：只用已有确定性信息，禁止 LLM contextual retrieval；
 * 无 titlePath 时不虚构（为 null 时该行省略）。产物是 {@code retrievalContent}，
 * <b>只服务于检索通道</b>——Judge / Generation / Citation / EvidenceMatcher
 * 一律使用 {@code answerContent}（= 原始 chunk 文本），metadata 绝不能成为
 * 新的回答证据。</p>
 *
 * <p>输出形态（确定性、可复现）：</p>
 * <pre>
 * 文档：部署手册.md
 * 章节部署手册 &gt; 1. 概述 &gt; 1.1 背景
 *
 * （正文……）
 * </pre>
 */
@Component
public class RetrievalContentEnricher {

    /** 元数据块与正文之间的空行分隔（元数据行自带 \n 结尾，再补一个空行）。 */
    static final String SEPARATOR = "\n";

    /**
     * 生成检索表示。
     *
     * @param answerContent 原始 chunk 文本（= answerContent，只透传不修改）
     * @param titlePath     标题路径（StructureChunker 产物；无结构时 = 文档名）
     * @param documentName  文档名（ParsedDocument.documentName；可 null）
     * @return retrievalContent；answerContent 为 null 时返回空串
     */
    public String enrich(String answerContent, String titlePath, String documentName) {
        if (answerContent == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        // titlePath 通常已含文档名前缀（StructureChunker：documentName > 章节…）。
        // 仅当 titlePath 缺失或不以文档名开头时，才补"文档："行——避免重复。
        boolean titlePathHasDocName = titlePath != null && !titlePath.isBlank()
                && documentName != null && !documentName.isBlank()
                && titlePath.startsWith(documentName);
        if (documentName != null && !documentName.isBlank() && !titlePathHasDocName) {
            sb.append("文档：").append(documentName).append('\n');
        }
        if (titlePath != null && !titlePath.isBlank()) {
            sb.append("章节：").append(titlePath).append('\n');
        }
        if (sb.length() > 0) {
            sb.append(SEPARATOR);
        }
        return sb.append(answerContent).toString();
    }
}
