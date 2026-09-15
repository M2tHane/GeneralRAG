package com.rag.ingestion.parse;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.rag.domain.enums.FileType;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

/**
 * Word（.docx）解析器（R3-P1，Apache POI XWPF）。
 *
 * <p>产物策略：转为 <b>Markdown 风格文本</b>，复用 StructureChunker 的既有能力——</p>
 * <ul>
 *   <li>Word 标题样式（Heading 1-6 / 标题 1-6）映射为 ATX 标题行 {@code ### text}，
 *       MarkdownParser.extractHeadings 可重建标题结构，titlePath 语义与 MD 一致；</li>
 *   <li>表格转为 Markdown 管道表（首行为表头 + 分隔行），单元格内换行替换为空格；</li>
 *   <li>普通段落逐行保留，空段落丢弃（与清洗阶段行为一致）。</li>
 * </ul>
 *
 * <p>不支持旧版二进制 .doc（HWPF）：上传侧 magic bytes 已拦截非 ZIP 容器。
 * 仅读文本层，图片/文本框内容不提取（与 PDF 文本层口径一致）。</p>
 */
@Component
public class DocxParser implements DocumentParser {

    @Override
    public FileType supportedType() {
        return FileType.DOCX;
    }

    @Override
    public ParsedDocument parse(InputStream in, FileType type) {
        StringBuilder text = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(in)) {
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String line = toLine(paragraph);
                    if (!line.isEmpty()) {
                        text.append(line).append('\n');
                    }
                } else if (element instanceof XWPFTable table) {
                    text.append(toMarkdownTable(table));
                }
            }
        } catch (Exception e) {
            if (e instanceof DomainException de) {
                throw de;
            }
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Word 解析失败：" + e.getMessage());
        }
        String body = text.toString();
        if (body.isBlank()) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                    "Word 文档解析结果为空：无文本段落（可能是纯图片文档）");
        }
        return ParsedDocument.plain(body, MarkdownParser.extractHeadings(body));
    }

    /** 段落 → 行：标题样式映射 ATX；空段落丢弃；段内换行归一为空格。 */
    private static String toLine(XWPFParagraph paragraph) {
        String text = paragraph.getText();
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ').strip();
        int level = headingLevel(paragraph);
        return level > 0 ? "#".repeat(level) + " " + normalized : normalized;
    }

    /** Word 标题样式 → ATX 级别（1-6）；非标题返回 0。兼容中英文样式名。 */
    private static int headingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle() == null ? "" : paragraph.getStyle();
        java.util.regex.Matcher en = java.util.regex.Pattern
                .compile("(?i)heading\\s*([1-6])").matcher(style);
        if (en.matches()) {
            return Integer.parseInt(en.group(1));
        }
        java.util.regex.Matcher zh = java.util.regex.Pattern
                .compile("标题\\s*([1-6])").matcher(style);
        if (zh.matches()) {
            return Integer.parseInt(zh.group(1));
        }
        return 0;
    }

    /** 表格 → Markdown 管道表：首行为表头 + 分隔行；越界单元格留空占位。 */
    static String toMarkdownTable(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return "";
        }
        int columns = rows.stream().mapToInt(r -> r.getTableCells().size()).max().orElse(0);
        if (columns == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow row = rows.get(i);
            sb.append('|');
            for (int c = 0; c < columns; c++) {
                sb.append(' ').append(cellText(row, c)).append(" |");
            }
            sb.append('\n');
            if (i == 0) {
                sb.append('|');
                sb.append(" --- |".repeat(columns));
                sb.append('\n');
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String cellText(XWPFTableRow row, int column) {
        List<XWPFTableCell> cells = row.getTableCells();
        if (column >= cells.size()) {
            return "";
        }
        String text = cells.get(column).getText();
        return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').strip();
    }
}
