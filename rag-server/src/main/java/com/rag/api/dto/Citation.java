package com.rag.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 引用（契约 Citation）：assistant 消息 citations JSON 列元素结构。
 * chunkId/docId/docName/titlePath/score 必有，page 可缺省（MD/TXT）。
 * JsonIgnoreProperties 兼容 QA 链路未来追加的展示字段。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Citation(
        String chunkId,
        String docId,
        String docName,
        String titlePath,
        Integer page,
        float score) {
}
