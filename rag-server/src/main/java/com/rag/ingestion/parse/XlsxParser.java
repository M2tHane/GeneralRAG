package com.rag.ingestion.parse;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.rag.domain.enums.FileType;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Excel（.xlsx）解析器（R3-P1，Apache POI XSSF）。
 *
 * <p>产物策略：每个工作表转为一个 Markdown 表格段落，产出 Markdown 风格文本——</p>
 * <ul>
 *   <li>非空工作表按顺序输出「## {sheetName}」+ Markdown 管道表；
 *       sheet 名成为标题层级，titlePath 可锚定到具体工作表；</li>
 *   <li>单元格经 {@link DataFormatter} 取「显示值」（日期/数字按格式化文本输出，
 *       避免 Excel 内部序列值泄漏进语料）；</li>
 *   <li>跳过全空行/列；行内越界（稀疏）单元格补空占位，保证表格对齐；</li>
 *   <li>全空工作表跳过；所有工作表均空 → 解析失败（不产生空语料）。</li>
 * </ul>
 */
@Component
public class XlsxParser implements DocumentParser {

    private final DataFormatter formatter = new DataFormatter();

    @Override
    public FileType supportedType() {
        return FileType.XLSX;
    }

    @Override
    public ParsedDocument parse(InputStream in, FileType type) {
        StringBuilder text = new StringBuilder();
        int tableCount = 0;
        try (XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                String table = sheetToMarkdownTable(sheet);
                if (table.isEmpty()) {
                    continue;
                }
                tableCount++;
                text.append("## ").append(sheet.getSheetName()).append("\n\n").append(table);
            }
        } catch (Exception e) {
            if (e instanceof DomainException de) {
                throw de;
            }
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Excel 解析失败：" + e.getMessage());
        }
        if (tableCount == 0) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                    "Excel 文档解析结果为空：所有工作表均无数据");
        }
        String body = text.toString();
        return ParsedDocument.plain(body, MarkdownParser.extractHeadings(body));
    }

    /** 单个 sheet → Markdown 表（含表头分隔行）；全空 sheet 返回空串。 */
    private String sheetToMarkdownTable(Sheet sheet) {
        int firstRow = sheet.getFirstRowNum();
        int lastRow = sheet.getLastRowNum();
        if (firstRow < 0 || (firstRow == 0 && lastRow == 0 && sheet.getRow(0) == null)) {
            return "";
        }
        // 预扫描：最大列数 + 每列是否出现过非空值（跳过全空列）
        int maxColumns = 0;
        boolean[] columnHasValue = null;
        List<List<String>> cellGrid = new ArrayList<>(lastRow - firstRow + 1);
        for (int r = firstRow; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            List<String> cells = new ArrayList<>();
            if (row != null) {
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    cells.add(cellText(row, c));
                }
            }
            maxColumns = Math.max(maxColumns, cells.size());
            cellGrid.add(cells);
        }
        if (maxColumns == 0) {
            return "";
        }
        columnHasValue = new boolean[maxColumns];
        for (List<String> cells : cellGrid) {
            for (int c = 0; c < cells.size(); c++) {
                if (!cells.get(c).isEmpty()) {
                    columnHasValue[c] = true;
                }
            }
        }
        // 组装：行内按 maxColumns 补齐；全空行跳过
        StringBuilder sb = new StringBuilder();
        boolean headerDone = false;
        for (List<String> cells : cellGrid) {
            StringBuilder line = new StringBuilder("|");
            boolean rowHasValue = false;
            for (int c = 0; c < maxColumns; c++) {
                String value = c < cells.size() ? cells.get(c) : "";
                if (columnHasValue[c]) {
                    line.append(' ').append(value).append(" |");
                    if (!value.isEmpty()) {
                        rowHasValue = true;
                    }
                }
            }
            if (!rowHasValue) {
                continue;
            }
            sb.append(line).append('\n');
            if (!headerDone) {
                sb.append('|');
                for (int c = 0; c < maxColumns; c++) {
                    if (columnHasValue[c]) {
                        sb.append(" --- |");
                    }
                }
                sb.append('\n');
                headerDone = true;
            }
        }
        return sb.isEmpty() ? "" : sb.append('\n').toString();
    }

    private String cellText(Row row, int column) {
        var cell = row.getCell(column);
        if (cell == null) {
            return "";
        }
        String text = formatter.formatCellValue(cell);
        return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').strip();
    }
}
