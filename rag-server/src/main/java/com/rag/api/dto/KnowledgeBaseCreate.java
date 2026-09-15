package com.rag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建知识库请求（契约 KnowledgeBaseCreate：name 必填 ≤100，description ≤500）。
 */
public record KnowledgeBaseCreate(
        @NotBlank(message = "name 不能为空")
        @Size(max = 100, message = "name 长度不能超过 100")
        String name,
        @Size(max = 500, message = "description 长度不能超过 500")
        String description) {
}
