package com.rag.ingestion.parse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;

/**
 * 文本读取工具：UTF-8 全文读取，容错 UTF-8 BOM（EF BB BF）。
 * 解析器（MD/TXT）与产物重建共用，保证同一文件两次读取字节级一致。
 */
final class TextReader {

    private TextReader() {
    }

    static String readUtf8(InputStream in) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                sb.append(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "读取文本流失败：" + e.getMessage());
        }
        String text = sb.toString();
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text;
    }
}
