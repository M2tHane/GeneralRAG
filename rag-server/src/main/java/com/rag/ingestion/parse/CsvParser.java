package com.rag.ingestion.parse;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.rag.domain.enums.FileType;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * CSV 解析器（R3-P1）：UTF-8 读取，转为 Markdown 管道表（首行为表头）。
 *
 * <p>解析规则（简化 RFC 4180，与 DatasetFileParser 的数据集 CSV 口径一致）：</p>
 * <ul>
 *   <li>字段内不含换行；引号包裹字段支持逗号与转义双引号（""）；</li>
 *   <li>空行跳过；行内列数不足按表头列数补空、超出丢弃多余列；</li>
 *   <li>仅一行数据（无表头语义）时也按表头+空表处理不成立——单行文件直接
 *       以该行作表头输出（无数据行）不产生语料 → 解析失败。</li>
 * </ul>
 */
@Component
public class CsvParser implements DocumentParser {

    @Override
    public FileType supportedType() {
        return FileType.CSV;
    }

    @Override
    public ParsedDocument parse(InputStream in, FileType type) {
        List<String[]> rows = readRows(TextReader.readUtf8(in));
        if (rows.isEmpty()) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "CSV 文件为空：无任何数据行");
        }
        int columns = rows.stream().mapToInt(r -> r.length).max().orElse(0);
        if (rows.size() < 2) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                    "CSV 文件只有一行：无法区分表头与数据，请补充表头行与数据行");
        }
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            body.append('|');
            for (int c = 0; c < columns; c++) {
                body.append(' ').append(c < row.length ? escapeCell(row[c]) : "").append(" |");
            }
            body.append('\n');
            if (i == 0) {
                body.append('|');
                body.append(" --- |".repeat(columns));
                body.append('\n');
            }
        }
        String text = body.toString();
        return ParsedDocument.plain(text, List.of());
    }

    /** 简化 RFC 4180 逐行解析：引号包裹 + "" 转义；空行跳过。 */
    static List<String[]> readRows(String text) {
        List<String[]> rows = new ArrayList<>();
        for (String rawLine : text.split("\r?\n", -1)) {
            String line = rawLine.stripTrailing();
            if (line.isBlank()) {
                continue;
            }
            rows.add(splitLine(line));
        }
        return rows;
    }

    private static String[] splitLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"' && field.isEmpty()) {
                inQuotes = true;
            } else if (ch == ',') {
                fields.add(field.toString().strip());
                field.setLength(0);
            } else {
                field.append(ch);
            }
        }
        fields.add(field.toString().strip());
        return fields.toArray(String[]::new);
    }

    /** Markdown 单元格转义：竖线破坏表格结构，换为斜杠。 */
    private static String escapeCell(String value) {
        return value.replace('|', '/').strip();
    }
}
