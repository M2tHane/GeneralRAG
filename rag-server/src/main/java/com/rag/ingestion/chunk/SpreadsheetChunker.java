package com.rag.ingestion.chunk;

import java.util.ArrayList;
import java.util.List;

import com.rag.ingestion.parse.MarkdownParser;
import com.rag.ingestion.parse.ParsedDocument;
import org.springframework.stereotype.Component;

/**
 * 电子表格分块（R4-Excel，Sheet 粗定位 + Row Group 精检索）：
 * 输入为解析/清洗后的 Markdown 管道表文本（XlsxParser/CsvParser 的确定性产物，
 * 从 parsed.txt 重建，重试续跑与首次入库同一路径），产出两类块：
 *
 * <ul>
 *   <li><b>Sheet Summary（每表 1 块）</b>：表名 + 行数 + 字段列表 + 前 3 行示例，
 *       负责整表级问题（"有哪些参数""哪个表存连接池配置"）的粗召回，
 *       titlePath = {@code 文档名 > Sheet名 > 概览}（无 Sheet 名即 CSV 为
 *       {@code 文档名 > 概览}）；</li>
 *   <li><b>Row Group（数据块）</b>：按 maxLength 预算把若干数据行动态聚合为一块，
 *       每块重复注入「数据表：文档名 > Sheet名」上下文行 + 表头行 + 分隔行，
 *       保证块离开原文后仍自含语义；titlePath = {@code ... > 数据行 a-b}
 *       （数据行序号为 1 起的 sheet 内数据行序号，跨重试确定性不变），
 *       行定位随 titlePath 贯通引用/调试/评测，无需新增 ES 字段。</li>
 * </ul>
 *
 * <p>切块路由：仅 STRUCTURE 策略 + XLSX/CSV 走本分块器（IngestionTaskManager 路由）；
 * LENGTH_OVERLAP 维持滑窗行为。表行是原子记录：单行超预算时不截断、不丢弃，
 * 独立成块（允许超上限）；overlap 不适用于整行聚合（行间不重叠）。</p>
 *
 * <p>解析约定（与解析器产物严格对应）：ATX 标题行开启新 sheet 段；段内第 1 条管道行
 * 为表头、第 2 条为分隔行（解析器恒定产出，按位置而非形状识别）、其余为数据行；
 * 单元格内的竖线已被解析器替换为斜杠，可安全按竖线拆列。</p>
 */
@Component
public class SpreadsheetChunker {

    /** Summary 块携带的示例数据行数上限。 */
    private static final int SUMMARY_SAMPLE_ROWS = 3;

    private static final String SUMMARY_SUFFIX = "概览";
    private static final String ROW_RANGE_PREFIX = "数据行 ";
    private static final String CONTEXT_PREFIX = "数据表：";

    /**
     * @param doc 解析产物（text 为管道表文本；documentName 作 titlePath 前缀）
     * @param cfg 分块参数；maxLength 为 Row Group 总长度（含上下文行+表头）预算
     * @param docId chunkId 前缀（确定性 {docId}-c%04d）
     */
    public List<ChunkDraft> chunk(ParsedDocument doc, ChunkingConfig cfg, String docId) {
        String text = doc.text() == null ? "" : doc.text();
        if (text.isBlank()) {
            return List.of();
        }
        String documentName = doc.documentName() == null ? "" : doc.documentName();
        List<ChunkDraft> drafts = new ArrayList<>();
        int seq = 0;
        for (SheetTable table : parseTables(text)) {
            String sheetPath = table.sheetName().isEmpty()
                    ? documentName
                    : documentName + " > " + table.sheetName();

            String summaryText = buildSummary(table, sheetPath);
            String summaryTitlePath = sheetPath + " > " + SUMMARY_SUFFIX;
            drafts.add(new ChunkDraft(LengthOverlapChunker.chunkId(docId, seq), seq,
                    summaryTitlePath, null, summaryText.length(), summaryText));
            seq++;

            seq = chunkRowGroups(drafts, table, sheetPath, cfg.maxLength(), docId, seq);
        }
        return drafts;
    }

    // ------------------------------------------------------------------
    // Row Group：按预算聚合数据行，每块重复上下文行 + 表头
    // ------------------------------------------------------------------

    private int chunkRowGroups(List<ChunkDraft> drafts, SheetTable table, String sheetPath,
                               int maxLength, String docId, int seq) {
        if (table.rows().isEmpty()) {
            return seq;
        }
        String contextLine = CONTEXT_PREFIX + sheetPath;
        String headerLine = pipeLine(table.header());
        String separatorLine = separatorLine(table.header().size());
        // 块固定开销：上下文行 + 表头行 + 分隔行 + 各自换行
        int fixedLength = contextLine.length() + headerLine.length() + separatorLine.length() + 3;

        List<String> group = new ArrayList<>();
        int groupLength = 0;
        int rowNo = 1; // 数据行序号，1 起（sheet 内）
        int groupStartNo = 1;
        for (List<String> row : table.rows()) {
            String rowLine = pipeLine(row);
            int extra = (group.isEmpty() ? 0 : 1) + rowLine.length();
            if (!group.isEmpty() && fixedLength + groupLength + extra > maxLength) {
                seq = flushGroup(drafts, contextLine, headerLine, separatorLine, group,
                        sheetPath, groupStartNo, docId, seq);
                group = new ArrayList<>();
                groupLength = 0;
                groupStartNo = rowNo;
                extra = rowLine.length();
            }
            group.add(rowLine);
            groupLength += extra;
            rowNo++;
        }
        return flushGroup(drafts, contextLine, headerLine, separatorLine, group,
                sheetPath, groupStartNo, docId, seq);
    }

    private int flushGroup(List<ChunkDraft> drafts, String contextLine, String headerLine,
                           String separatorLine, List<String> rows, String sheetPath,
                           int startNo, String docId, int seq) {
        if (rows.isEmpty()) {
            return seq;
        }
        StringBuilder body = new StringBuilder(contextLine.length() + headerLine.length()
                + separatorLine.length() + 64 * rows.size());
        body.append(contextLine).append('\n')
                .append(headerLine).append('\n')
                .append(separatorLine);
        for (String rowLine : rows) {
            body.append('\n').append(rowLine);
        }
        String titlePath = sheetPath + " > " + ROW_RANGE_PREFIX + startNo
                + "-" + (startNo + rows.size() - 1);
        drafts.add(new ChunkDraft(LengthOverlapChunker.chunkId(docId, seq), seq,
                titlePath, null, body.length(), body.toString()));
        return seq + 1;
    }

    // ------------------------------------------------------------------
    // Summary：表名 + 行数 + 字段 + 示例行
    // ------------------------------------------------------------------

    private String buildSummary(SheetTable table, String sheetPath) {
        StringBuilder sb = new StringBuilder();
        if (!table.sheetName().isEmpty()) {
            sb.append("## ").append(table.sheetName()).append("\n\n");
        }
        sb.append(CONTEXT_PREFIX).append(sheetPath)
                .append("，共 ").append(table.rows().size()).append(" 行数据。\n");
        sb.append("字段：");
        for (int i = 0; i < table.header().size(); i++) {
            if (i > 0) {
                sb.append('、');
            }
            sb.append(table.header().get(i));
        }
        sb.append('\n');
        if (!table.rows().isEmpty()) {
            sb.append("示例数据（前 ").append(Math.min(SUMMARY_SAMPLE_ROWS, table.rows().size()))
                    .append(" 行）：\n");
            sb.append(pipeLine(table.header())).append('\n').append(separatorLine(table.header().size()));
            for (int i = 0; i < Math.min(SUMMARY_SAMPLE_ROWS, table.rows().size()); i++) {
                sb.append('\n').append(pipeLine(table.rows().get(i)));
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 管道表文本解析：ATX 标题分段 → 每段一张表
    // ------------------------------------------------------------------

    /**
     * 把管道表文本拆为一张张 SheetTable。非管道行的 ATX 标题行开启新段（sheet 名）；
     * 标题前的管道行归入无 sheet 名段（CSV 产物）。段内：第 1 条管道行 = 表头，
     * 第 2 条 = 分隔行（跳过），其余 = 数据行。
     */
    static List<SheetTable> parseTables(String text) {
        List<SheetTable> tables = new ArrayList<>();
        String currentSheet = null;
        List<String> pipeLines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            var heading = MarkdownParser.ATX_HEADING.matcher(line);
            if (heading.matches()) {
                collectTable(tables, currentSheet, pipeLines);
                currentSheet = heading.group(2).strip();
                pipeLines = new ArrayList<>();
            } else if (line.strip().startsWith("|")) {
                pipeLines.add(line.strip());
            }
        }
        collectTable(tables, currentSheet, pipeLines);
        return tables;
    }

    private static void collectTable(List<SheetTable> tables, String sheetName, List<String> pipeLines) {
        if (pipeLines.isEmpty()) {
            return;
        }
        List<String> header = cells(pipeLines.get(0));
        List<List<String>> rows = new ArrayList<>();
        // 按位置识别：解析器恒定「表头、分隔行、数据行…」产出，第 2 条为分隔行
        for (int i = 2; i < pipeLines.size(); i++) {
            List<String> cells = cells(pipeLines.get(i));
            // 列数对齐表头（解析器已按列补空，此处仅防御稀疏尾列）
            while (cells.size() < header.size()) {
                cells.add("");
            }
            rows.add(cells);
        }
        tables.add(new SheetTable(sheetName == null ? "" : sheetName, header, rows));
    }

    /** 拆一条管道行为单元格：去首尾竖线后按竖线切分并 strip（单元格内竖线已被解析器转义）。 */
    static List<String> cells(String pipeLine) {
        String inner = pipeLine.strip();
        if (inner.startsWith("|")) {
            inner = inner.substring(1);
        }
        if (inner.endsWith("|")) {
            inner = inner.substring(0, inner.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : inner.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    private static String pipeLine(List<String> cells) {
        StringBuilder sb = new StringBuilder(32 * cells.size());
        for (String cell : cells) {
            sb.append("| ").append(cell).append(' ');
        }
        return sb.append('|').toString();
    }

    private static String separatorLine(int columns) {
        return "| --- ".repeat(columns) + "|";
    }

    /** 一张逻辑表：sheet 名（CSV 为空串）、表头单元格、数据行单元格。 */
    record SheetTable(String sheetName, List<String> header, List<List<String>> rows) {
    }
}
