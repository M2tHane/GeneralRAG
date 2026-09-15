package com.rag.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 新建会话请求（契约 SessionCreate：kbId 必填，title ≤100 可选，
 * 缺省取首条提问前 N 字——由 QA 链路写入，此处缺省为「新会话」）。
 */
public record SessionCreate(
        @NotNull(message = "kbId 不能为空")
        UUID kbId,
        @Size(max = 100, message = "title 长度不能超过 100")
        String title) {
}
