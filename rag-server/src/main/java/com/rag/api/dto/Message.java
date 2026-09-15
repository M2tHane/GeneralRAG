package com.rag.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息 DTO（契约 Message）。citations 为契约 Citation[]（chat_message.citations
 * JSON 列反序列化）；error 仅 status=ERROR 时以 {code,message} 摘要出现。
 */
public record Message(
        String id,
        String sessionId,
        String role,
        String content,
        List<Citation> citations,
        String status,
        ErrorSummary error,
        LocalDateTime createdAt) {

    /**
     * 错误摘要（契约 Message.error 引用 Error 结构）。chat_message 仅持久化 error_code，
     * message 取该错误码的稳定默认文案、requestId 无存档返回 null——均为真实可得信息，
     * 不虚构请求级细节。
     */
    public record ErrorSummary(String code, String message, String requestId) {

        public static ErrorSummary of(String code) {
            String message = null;
            try {
                message = com.rag.domain.exception.ErrorCode.valueOf(code).getDefaultMessage();
            } catch (IllegalArgumentException ignored) {
                // 未知历史错误码：message 置 null（契约 message 必填——回退为 code 本身）
                message = code;
            }
            return new ErrorSummary(code, message, null);
        }
    }
}
