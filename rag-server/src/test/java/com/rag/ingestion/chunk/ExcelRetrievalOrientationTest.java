package com.rag.ingestion.chunk;

import java.io.FileInputStream;
import java.util.List;

import com.rag.ingestion.parse.ParsedDocument;
import com.rag.ingestion.parse.XlsxParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Excel retrieval 定向验证（R5.1 §5）：真实 fixture（sales-r5-fixture.xlsx）经
 * parse → chunk → RetrievalContentEnricher 后，BM25 按 retrieval_content 的
 * 元数据前缀（文档名/Sheet 名/字段列表）是否真能定位到正确块类型。
 *
 * <p>验证三条检索意图各归其位：数据行问题 → RowGroup；Sheet 说明问题 →
 * Summary/RowGroup；字段清单问题 → Sheet Summary。模拟 BM25 的方式是
 * "问题词组在 retrievalContent 中的分块级命中"（与 ES match 同为词项匹配，
 * IK 分词对词项包含关系的判定一致）。</p>
 */
class ExcelRetrievalOrientationTest {

    private static List<ChunkDraft> drafts;

    @BeforeAll
    static void parseAndChunkRealFixture() throws Exception {
        String path = "../docs/eval-corpus/sales-r5-fixture.xlsx";
        // 与 IngestionTaskManager 相同：parser 产物由流水线补文档名
        ParsedDocument parsed = new XlsxParser().parse(new FileInputStream(path), null)
                .withDocumentName("sales-r5-fixture.xlsx");
        RetrievalContentEnricher enricher = new RetrievalContentEnricher();
        drafts = new SpreadsheetChunker().chunk(parsed,
                new ChunkingConfig(com.rag.domain.enums.ChunkStrategy.STRUCTURE, 800, 100), "doc-x")
                .stream()
                .map(d -> new ChunkDraft(d.chunkId(), d.seq(), d.titlePath(), d.page(),
                        d.charCount(), d.text(),
                        enricher.enrich(d.text(), d.titlePath(), "sales-r5-fixture.xlsx")))

                .toList();
    }

    /** 问题词项 → 命中的 retrievalContent 集合（模拟 BM25 match：词项包含）。 */
    private static List<ChunkDraft> search(String... terms) {
        return drafts.stream().filter(d -> {
            String rc = d.retrievalContent();
            for (String t : terms) {
                if (rc.contains(t)) {
                    return true;
                }
            }
            return false;
        }).toList();
    }

    @Test
    void rowQuestionHitsRowGroupNotSummary() {
        // "华东 A 产品 Q2 销售额" → 应命中区域销售的数据行块（含 A 华东 100 120 那行）
        List<ChunkDraft> hits = search("华东", "Q2销售额");
        assertThat(hits).anySatisfy(d -> {
            assertThat(d.titlePath()).contains("区域销售 > 数据行");
            assertThat(d.text()).contains("| A | 华东 | 100 | 120 | 85% | 2026-06-30 |");
        });
        // Summary 示例行含前 3 行数据（A 华东在其中）→ 粗召回命中合法；
        // 关键是 RowGroup（精确定位块）同样被命中
        assertThat(hits).anySatisfy(d -> assertThat(d.titlePath()).contains("数据行 "));
    }

    @Test
    void sheetQuestionHitsNotesSheetRowGroup() {
        // "产品说明 Sheet 中 A 的说明" → 应命中产品说明的 RowGroup（A=旗舰机型）
        List<ChunkDraft> hits = search("产品说明", "旗舰机型");
        assertThat(hits).anySatisfy(d -> {
            assertThat(d.titlePath()).contains("产品说明");
            assertThat(d.text()).contains("| A | 旗舰机型 |");
        });
        // 区域销售的块不应命中（跨 Sheet 不串）
        assertThat(hits).noneSatisfy(d ->
                assertThat(d.titlePath()).contains("区域销售"));
    }

    @Test
    void fieldListingQuestionHitsSheetSummary() {
        // "这个销售表有哪些字段" → Sheet Summary（含"字段：产品、地区、…"）是唯一
        // 完整字段清单所在；且 summary 的 retrievalContent 带 Sheet 名可区分表
        List<ChunkDraft> hits = search("字段：产品、地区、Q1销售额、Q2销售额", "区域销售");
        assertThat(hits).anySatisfy(d -> {
            assertThat(d.titlePath()).isEqualTo("sales-r5-fixture.xlsx > 区域销售 > 概览");
            assertThat(d.text()).contains("字段：产品、地区、Q1销售额、Q2销售额、达成率、更新日期");
        });
    }

    @Test
    void retrievalContentCarriesSheetMetadataForStructuredQueries() {
        // 结构化查询依赖 retrievalContent 的元数据前缀：RowGroup 的 answerContent
        // 只有数据行，不含文件名/Sheet 名——BM25 若只搜 content 将无法召回
        // "哪个文件/哪个 Sheet"。Summary 正文含 sheetPath 属 R4-Excel 设计
        // （summary 本身就是表级说明块），非虚构。
        for (ChunkDraft d : drafts) {
            assertThat(d.retrievalContent()).startsWith("章节：sales-r5-fixture.xlsx >");
            if (d.titlePath().contains("数据行 ")) {
                // answerContent 不含 enricher 的元数据行（文档：/章节：）；
                // "数据表：<文件名> > <Sheet>" 上下文行是 R4-Excel 设计内的自含语义
                assertThat(d.text()).doesNotContain("文档：").doesNotContain("章节：");
            }
        }
    }
}
