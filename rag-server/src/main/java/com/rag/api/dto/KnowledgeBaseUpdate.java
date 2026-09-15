package com.rag.api.dto;

import jakarta.validation.constraints.Size;

/**
 * 更新知识库请求（契约 KnowledgeBaseUpdate：部分更新，minProperties 1，
 * 缺省字段保持不变；name ≤100，description ≤500）。
 *
 * <p>注：@Size(null) 通过，未提交字段自然保持不变。</p>
 */
public record KnowledgeBaseUpdate(
        @Size(min = 1, max = 100, message = "name 长度必须在 1..100")
        String name,
        @Size(max = 500, message = "description 长度不能超过 500")
        String description) {
}
