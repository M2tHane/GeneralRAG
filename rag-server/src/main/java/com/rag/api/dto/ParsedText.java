package com.rag.api.dto;

/**
 * 解析文本预览（契约 ParsedText）。content 为解析+清洗后文本；
 * 受 maxChars 截断时 truncated=true 且 charCount 为返回内容的字符数。
 */
public record ParsedText(
        String docId,
        String content,
        int charCount,
        boolean truncated) {
}
