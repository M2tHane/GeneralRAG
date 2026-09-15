package com.rag.ingestion.chunk;

import java.util.List;

import com.rag.domain.enums.ChunkStrategy;
import com.rag.ingestion.parse.ParsedDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SpreadsheetChunker 单测（R4-Excel）：Sheet Summary + Row Group 语义、
 * 表头重复注入、行号 titlePath、预算聚合、单行超预算原子性、CSV 无 sheet 名。
 */
class SpreadsheetChunkerTest {

    private final SpreadsheetChunker chunker = new SpreadsheetChunker();

    private static ParsedDocument xlsxDoc(String text) {
        return ParsedDocument.plain(text, com.rag.ingestion.parse.MarkdownParser.extractHeadings(text))
                .withDocumentName("连接池参数.xlsx");
    }

    /** 与 XlsxParser 产物同构：## 标题 + 管道表（表头、分隔行、数据行）。 */
    private static final String TWO_SHEET_TEXT = """
            ## HikariCP

            | 参数名 | 默认值 | 描述 |
            | --- | --- | --- |
            | maxLifetime | 1800000 | 连接最大生命周期 |
            | idleTimeout | 600000 | 空闲连接超时 |
            | connectionTimeout | 30000 | 获取连接超时 |

            ## Druid

            | 参数名 | 默认值 |
            | --- | --- |
            | initialSize | 5 |
            """;

    @Test
    void producesSummaryPerSheetAndRowGroups() {
        List<ChunkDraft> drafts = chunker.chunk(xlsxDoc(TWO_SHEET_TEXT), cfg(400), "doc1");

        // 2 个 sheet：各 1 Summary + 1 RowGroup（3 行 / 1 行都未超预算）
        assertThat(drafts).hasSize(4);
        assertThat(drafts).allSatisfy(d -> assertThat(d.chunkId()).startsWith("doc1-c"));
        assertThat(drafts.get(0).seq()).isEqualTo(0);
        assertThat(drafts.get(3).seq()).isEqualTo(3);
    }

    @Test
    void summaryContainsFieldListRowCountAndSample() {
        List<ChunkDraft> drafts = chunker.chunk(xlsxDoc(TWO_SHEET_TEXT), cfg(400), "doc1");
        String summary = drafts.get(0).text();

        assertThat(drafts.get(0).titlePath()).isEqualTo("连接池参数.xlsx > HikariCP > 概览");
        assertThat(summary)
                .contains("## HikariCP")
                .contains("共 3 行数据")
                .contains("参数名、默认值、描述")
                .contains("| maxLifetime | 1800000 | 连接最大生命周期 |")
                // 示例只带前 3 行
                .doesNotContain("initialSize");
        assertThat(drafts.get(0).charCount()).isEqualTo(summary.length());
    }

    @Test
    void rowGroupRepeatsContextAndHeaderWithTitlePathRange() {
        List<ChunkDraft> drafts = chunker.chunk(xlsxDoc(TWO_SHEET_TEXT), cfg(400), "doc1");
        ChunkDraft group = drafts.get(1);

        assertThat(group.titlePath()).isEqualTo("连接池参数.xlsx > HikariCP > 数据行 1-3");
        assertThat(group.text())
                // 上下文行 + 表头 + 分隔行逐块重复
                .startsWith("数据表：连接池参数.xlsx > HikariCP\n")
                .contains("| 参数名 | 默认值 | 描述 |\n| --- | --- | --- |")
                .contains("| maxLifetime | 1800000 | 连接最大生命周期 |")
                .contains("| connectionTimeout | 30000 | 获取连接超时 |");
    }

    @Test
    void rowGroupsSplitByBudgetNotByFixedCount() {
        // maxLength=150：固定开销（上下文+表头+分隔 ≈68）后 1+2 行超限 → 切成 1-2 / 3-3 两块
        List<ChunkDraft> drafts = chunker.chunk(xlsxDoc(TWO_SHEET_TEXT), cfg(150), "doc1");

        List<ChunkDraft> hikariGroups = drafts.stream()
                .filter(d -> d.titlePath().startsWith("连接池参数.xlsx > HikariCP > 数据行"))
                .toList();
        // 3 行数据被预算切成多块，且每块独立携带表头
        assertThat(hikariGroups.size()).isGreaterThan(1);
        assertThat(hikariGroups).allSatisfy(d ->
                assertThat(d.text()).contains("| 参数名 | 默认值 | 描述 |"));
        // titlePath 行号连续不重不漏
        assertThat(hikariGroups.get(0).titlePath()).endsWith("数据行 1-2");
        assertThat(hikariGroups.get(1).titlePath()).endsWith("数据行 3-3");
    }

    @Test
    void overlongRowStaysAtomicWithoutTruncation() {
        String longDesc = "很长的描述".repeat(60); // 单行 360 字符
        String text = """
                ## 表

                | 名称 | 说明 |
                | --- | --- |
                | a | %s |
                | b | 短 |
                """.formatted(longDesc);
        List<ChunkDraft> drafts = chunker.chunk(xlsxDoc(text), cfg(200), "doc1");

        ChunkDraft overlong = drafts.stream()
                .filter(d -> d.titlePath().endsWith("数据行 1-1")).findFirst().orElseThrow();
        // 不截断、不丢行：允许超上限独立成块
        assertThat(overlong.text()).contains(longDesc);
        assertThat(overlong.titlePath()).endsWith("数据行 1-1");
        // 后续行正常成块
        assertThat(drafts.stream().filter(d -> d.titlePath().endsWith("数据行 2-2")).findFirst())
                .as("短行应正常成块")
                .isPresent();
    }

    @Test
    void csvTextWithoutSheetHeadingUsesDocumentNameOnly() {
        String csvText = """
                | 参数名 | 默认值 |
                | --- | --- |
                | maxLifetime | 1800000 |
                """;
        List<ChunkDraft> drafts = chunker.chunk(xlsxDoc(csvText), cfg(400), "doc1");

        assertThat(drafts).hasSize(2);
        assertThat(drafts.get(0).titlePath()).isEqualTo("连接池参数.xlsx > 概览");
        assertThat(drafts.get(1).titlePath()).isEqualTo("连接池参数.xlsx > 数据行 1-1");
        assertThat(drafts.get(1).text()).startsWith("数据表：连接池参数.xlsx\n");
    }

    @Test
    void blankTextYieldsZeroChunks() {
        assertThat(chunker.chunk(xlsxDoc("   \n  "), cfg(400), "doc1")).isEmpty();
    }

    @Test
    void chunkIdsAreDeterministicAcrossRuns() {
        List<ChunkDraft> first = chunker.chunk(xlsxDoc(TWO_SHEET_TEXT), cfg(400), "doc9");
        List<ChunkDraft> second = chunker.chunk(xlsxDoc(TWO_SHEET_TEXT), cfg(400), "doc9");
        assertThat(first).extracting(ChunkDraft::chunkId)
                .containsExactlyElementsOf(second.stream().map(ChunkDraft::chunkId).toList());
    }

    @Test
    void parseTablesHandlesHeadingAndPipeLinePositions() {
        List<SpreadsheetChunker.SheetTable> tables = SpreadsheetChunker.parseTables(TWO_SHEET_TEXT);
        assertThat(tables).hasSize(2);
        assertThat(tables.get(0).sheetName()).isEqualTo("HikariCP");
        assertThat(tables.get(0).header()).containsExactly("参数名", "默认值", "描述");
        assertThat(tables.get(0).rows()).hasSize(3);
        assertThat(tables.get(0).rows().get(0)).containsExactly("maxLifetime", "1800000", "连接最大生命周期");
        assertThat(tables.get(1).sheetName()).isEqualTo("Druid");
    }

    private static ChunkingConfig cfg(int maxLength) {
        return new ChunkingConfig(ChunkStrategy.STRUCTURE, maxLength, 50);
    }
}
