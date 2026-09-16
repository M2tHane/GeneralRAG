package com.rag.ingestion.chunk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import com.rag.ingestion.parse.ParsedDocument;
import com.rag.ingestion.parse.XlsxParser;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Excel 双层分块 fixture 测试（R5-C）：POI 内存生成 xlsx（2 Sheet、字符串/数字/
 * 百分比/日期/公式/空单元格/多行）→ XlsxParser → SpreadsheetChunker，验证：
 * 每 Sheet 有 summary、RowGroup 重复 header、行不被切断、跨 Sheet 不串数据、
 * display value 正确、公式取计算值、answerContent/retrievalContent 分层。
 */
class ExcelDualLayerChunkingTest {

    private static ParsedDocument parsed;

    @BeforeAll
    static void buildFixtureAndParse() throws Exception {
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sales = wb.createSheet("区域销售");
            Row header = sales.createRow(0);
            header.createCell(0).setCellValue("产品");
            header.createCell(1).setCellValue("地区");
            header.createCell(2).setCellValue("Q1销售额");
            header.createCell(3).setCellValue("Q2销售额");
            header.createCell(4).setCellValue("达成率");
            header.createCell(5).setCellValue("更新日期");
            sales.createRow(1).createCell(0).setCellValue("A");
            sales.getRow(1).createCell(1).setCellValue("华东");
            sales.getRow(1).createCell(2).setCellValue(100);
            sales.getRow(1).createCell(3).setCellValue(120);
            sales.getRow(1).createCell(4).setCellValue(0.85);
            sales.getRow(1).getCell(4).setCellStyle(percentStyle(wb));
            sales.getRow(1).createCell(5).setCellValue("2026-06-30");
            sales.createRow(2).createCell(0).setCellValue("B");
            sales.getRow(2).createCell(1).setCellValue("华南");
            sales.getRow(2).createCell(2).setCellValue(80);
            sales.getRow(2).createCell(3).setCellValue(90);
            sales.getRow(2).createCell(4).setCellValue(0.72);
            sales.getRow(2).getCell(4).setCellStyle(percentStyle(wb));
            // 行 3：C 行部分空单元格
            sales.createRow(3).createCell(0).setCellValue("C");
            sales.getRow(3).createCell(1).setCellValue("华东");
            sales.getRow(3).createCell(3).setCellValue(60);
            // 公式行：SUM 计算值
            sales.createRow(4).createCell(0).setCellValue("合计");
            sales.getRow(4).createCell(2).setCellFormula("SUM(C2:C4)");
            sales.getRow(4).createCell(3).setCellFormula("SUM(D2:D4)");

            Sheet notes = wb.createSheet("产品说明");
            notes.createRow(0).createCell(0).setCellValue("产品");
            notes.getRow(0).createCell(1).setCellValue("说明");
            notes.createRow(1).createCell(0).setCellValue("A");
            notes.getRow(1).createCell(1).setCellValue("旗舰机型");
            notes.createRow(2).createCell(0).setCellValue("D");
            notes.getRow(2).createCell(1).setCellValue("测试覆盖机型");

            wb.write(out);
            xlsx = out.toByteArray();
        }
        parsed = new XlsxParser().parse(new ByteArrayInputStream(xlsx), null);
    }

    private static org.apache.poi.ss.usermodel.CellStyle percentStyle(XSSFWorkbook wb) {
        org.apache.poi.ss.usermodel.CellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("0%"));
        return style;
    }

    @Test
    void bothSheetsPresentWithHeadings() {
        assertThat(parsed.text()).contains("## 区域销售").contains("## 产品说明");
    }

    @Test
    void percentageFormattedAsDisplayValue() {
        assertThat(parsed.text()).contains("85%").contains("72%");
        assertThat(parsed.text()).doesNotContain("0.85 |"); // 不是原始小数
    }

    @Test
    void formulaCellsShowComputedValueNotFormulaString() {
        // DataFormatter + evaluator：SUM 公式 cell 输出缓存计算值（首次生成可能无缓存，
        // 显示值取 evaluator 显式计算，保证非公式串）
        assertThat(parsed.text()).doesNotContain("SUM(");
    }

    @Test
    void emptyCellsRenderAsBlankColumns() {
        // C 行：Q1 空、Q2=60；日期空 → 列存在但值为空
        assertThat(parsed.text()).contains("| C | 华东 |  | 60 ");
    }

    @Test
    void chunkingProducesSummaryAndRowGroupsWithDualLayerContent() {
        ChunkingConfig cfg = new ChunkingConfig(com.rag.domain.enums.ChunkStrategy.STRUCTURE, 800, 100);
        List<ChunkDraft> drafts = new SpreadsheetChunker().chunk(
                new ParsedDocument("2026销售.xlsx", parsed.text(), List.of(), List.of()),
                cfg, "doc-r5");
        // 两张 sheet → 2 个 summary + ≥2 个 row group
        List<ChunkDraft> summaries = drafts.stream()
                .filter(d -> d.titlePath().endsWith("概览")).toList();
        assertThat(summaries).hasSize(2);
        assertThat(summaries).extracting(ChunkDraft::titlePath)
                .contains("2026销售.xlsx > 区域销售 > 概览", "2026销售.xlsx > 产品说明 > 概览");
        // summary 内容：字段列表 + 行数
        assertThat(summaries.get(0).text())
                .contains("字段：产品、地区、Q1销售额、Q2销售额、达成率、更新日期")
                .contains("4 行数据");

        // RowGroup 重复 header/schema；行不被切断（每行完整出现）
        List<ChunkDraft> rowGroups = drafts.stream()
                .filter(d -> d.titlePath().contains("数据行 ")).toList();
        assertThat(rowGroups.size()).isGreaterThanOrEqualTo(2);
        for (ChunkDraft group : rowGroups) {
            // 各组重复自己 sheet 的 header（跨 sheet 不串）
            String expectedHeader = group.titlePath().startsWith("2026销售.xlsx > 区域销售")
                    ? "| 产品 | 地区 | Q1销售额 | Q2销售额 | 达成率 | 更新日期 |"
                    : "| 产品 | 说明 |";
            assertThat(group.text()).contains(expectedHeader).contains("| --- ");
        }
        // A 行完整（不被切断）：产品、地区、数值同行
        assertThat(rowGroups.stream().anyMatch(d ->
                d.text().contains("| A | 华东 | 100 | 120 | 85% | 2026-06-30 |"))).isTrue();
        // 跨 Sheet 不串数据：区域销售的块不含产品说明内容，反之亦然
        assertThat(rowGroups.stream()
                .filter(d -> d.titlePath().startsWith("2026销售.xlsx > 区域销售"))
                .anyMatch(d -> d.text().contains("旗舰机型"))).isFalse();
        assertThat(rowGroups.stream()
                .filter(d -> d.titlePath().startsWith("2026销售.xlsx > 产品说明"))
                .anyMatch(d -> d.text().contains("华东"))).isFalse();
    }

    @Test
    void dualLayerContentViaEnricherKeepsAnswerContentFaithful() {
        // R5-C §16：answerContent 忠实原始表格；retrievalContent = 文件/Sheet 元数据 + [answerContent]
        RetrievalContentEnricher enricher = new RetrievalContentEnricher();
        ChunkingConfig cfg = new ChunkingConfig(com.rag.domain.enums.ChunkStrategy.STRUCTURE, 800, 100);
        List<ChunkDraft> drafts = new SpreadsheetChunker().chunk(
                new ParsedDocument("2026销售.xlsx", parsed.text(), List.of(), List.of()),
                cfg, "doc-r5");
        ChunkDraft rowGroup = drafts.stream()
                .filter(d -> d.titlePath().contains("数据行 ")).findFirst().orElseThrow();
        String retrieval = enricher.enrich(rowGroup.text(), rowGroup.titlePath(), "2026销售.xlsx");
        // titlePath 已含文档名 → 只补"章节："行（不重复文档行），且尾部是忠实 answerContent
        assertThat(retrieval)
                .startsWith("章节：2026销售.xlsx > 区域销售 > 数据行 1-4\n\n")
                .endsWith(rowGroup.text());
        assertThat(enricher.enrich(rowGroup.text(), null, "2026销售.xlsx"))
                .startsWith("文档：2026销售.xlsx\n\n");
        // answerContent 本身无虚构内容：不含"文档："前缀
        assertThat(rowGroup.text()).doesNotContain("文档：");
    }
}
