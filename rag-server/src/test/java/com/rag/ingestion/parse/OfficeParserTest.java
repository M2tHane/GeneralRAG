package com.rag.ingestion.parse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.rag.domain.enums.FileType;
import com.rag.eval.EvidenceMatcher;
import com.rag.domain.exception.DomainException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R3-P1 新格式解析器测试：docx / xlsx / csv。
 *
 * <p>测试文件在测试内即席生成（POI 构造），不依赖外部 fixture：
 * docx 标题样式 → ATX 映射、表格 → 管道表；xlsx 多 sheet、显示值、空列剔除；
 * csv 引号/转义/空行。产物必须能被 MarkdownParser.extractHeadings 重建标题结构
 * （保证 fromPersisted 续跑路径与首次解析等价）。</p>
 */
class OfficeParserTest {

    // ------------------------------------------------------------------ docx

    private static byte[] sampleDocx() throws Exception {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph h1 = doc.createParagraph();
            h1.setStyle("Heading1");
            h1.createRun().setText("部署指南");
            doc.createParagraph().createRun().setText("第一段正文。");
            XWPFParagraph h2 = doc.createParagraph();
            h2.setStyle("Heading2");
            h2.createRun().setText("配置说明");
            doc.createParagraph().createRun().setText("第二段正文。");
            XWPFTable table = doc.createTable(2, 2);
            XWPFTableRow header = table.getRow(0);
            header.getCell(0).setText("参数");
            header.getCell(1).setText("值");
            XWPFTableRow data = table.getRow(1);
            data.getCell(0).setText("mode");
            data.getCell(1).setText("fast");
            doc.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void docxMapsHeadingsAndTablesToMarkdown() throws Exception {
        DocxParser parser = new DocxParser();
        assertThat(parser.supportedType()).isEqualTo(FileType.DOCX);
        ParsedDocument parsed = parser.parse(
                new ByteArrayInputStream(sampleDocx()), FileType.DOCX);

        assertThat(parsed.headings()).extracting(ParsedDocument.Heading::level)
                .containsExactly(1, 2);
        assertThat(parsed.text())
                .contains("# 部署指南")
                .contains("## 配置说明")
                .contains("| 参数 | 值 |")
                .contains("| mode | fast |");
        // 产物标题结构可被 MarkdownParser 重建（fromPersisted 等价的前提）
        assertThat(MarkdownParser.extractHeadings(parsed.text()))
                .hasSameSizeAs(parsed.headings());
    }

    @Test
    void docxEmptyDocumentFails() throws Exception {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.write(out);
            assertThatThrownBy(() -> new DocxParser().parse(
                    new ByteArrayInputStream(out.toByteArray()), FileType.DOCX))
                    .isInstanceOf(DomainException.class);
        }
    }

    // ------------------------------------------------------------------ xlsx

    private static byte[] sampleXlsx() throws Exception {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("参数");
            sheet.createRow(0).createCell(0).setCellValue("参数");
            sheet.getRow(0).createCell(1).setCellValue("值");
            sheet.createRow(1).createCell(0).setCellValue("timeout");
            sheet.getRow(1).createCell(1).setCellValue(3000);
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void xlsxProducesSheetHeadingsAndPipeTable() throws Exception {
        XlsxParser parser = new XlsxParser();
        assertThat(parser.supportedType()).isEqualTo(FileType.XLSX);
        ParsedDocument parsed = parser.parse(
                new ByteArrayInputStream(sampleXlsx()), FileType.XLSX);

        assertThat(parsed.text())
                .contains("## 参数")
                .contains("| 参数 | 值 |")
                .contains("| timeout | 3000 |");
        assertThat(parsed.headings()).hasSize(1);
        assertThat(parsed.headings().get(0).level()).isEqualTo(2);
    }

    @Test
    void xlsxAllEmptySheetsFail() throws Exception {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet("空表");
            wb.write(out);
            assertThatThrownBy(() -> new XlsxParser().parse(
                    new ByteArrayInputStream(out.toByteArray()), FileType.XLSX))
                    .isInstanceOf(DomainException.class);
        }
    }

    // ------------------------------------------------------------------ csv

    @Test
    void csvConvertsToPipeTableAndParsesQuotedFields() {
        CsvParser parser = new CsvParser();
        assertThat(parser.supportedType()).isEqualTo(FileType.CSV);
        String csv = "code,desc\n\"A,1\",\"say \"\"hi\"\"\"\n\nB,2";
        ParsedDocument parsed = parser.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), FileType.CSV);

        assertThat(parsed.text())
                .contains("| code | desc |")
                .contains("| A,1 | say \"hi\" |")
                .contains("| B | 2 |");
        assertThat(parsed.headings()).isEmpty();
    }

    @Test
    void csvSingleRowFails() {
        assertThatThrownBy(() -> new CsvParser().parse(
                new ByteArrayInputStream("only,header\n".getBytes(StandardCharsets.UTF_8)),
                FileType.CSV))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void csvEmptyFails() {
        assertThatThrownBy(() -> new CsvParser().parse(
                new ByteArrayInputStream(new byte[0]), FileType.CSV))
                .isInstanceOf(DomainException.class);
    }

    // ---------------------------------------------------------- 内容哈希锚点

    @Test
    void contentHashIsStableUnderWhitespaceVariation() {
        String a = "第一行\n\n第二行  内容\n";
        String b = "  第一行\n 第二行 内容  ";
        assertThat(EvidenceMatcher.contentHashOf(a))
                .isEqualTo(EvidenceMatcher.contentHashOf(b))
                .hasSize(16);
        assertThat(EvidenceMatcher.contentHashOf(" ")).isNull();
        assertThat(EvidenceMatcher.contentHashOf(null)).isNull();
    }

    @Test
    void contentHashDiffersForDifferentContent() {
        assertThat(EvidenceMatcher.contentHashOf("内容甲"))
                .isNotEqualTo(EvidenceMatcher.contentHashOf("内容乙"));
    }

    // --------------------------------------------------------- 重试续跑重建

    @Test
    void fromPersistedRebuildsNewFormats() {
        String persisted = "# 标题\n正文。\n\n| a | b |\n| --- | --- |\n| 1 | 2 |";
        for (FileType type : List.of(FileType.DOCX, FileType.XLSX, FileType.CSV)) {
            ParsedDocument rebuilt = ParsedDocument.fromPersisted(type, "d." + type, persisted);
            assertThat(rebuilt.text()).isEqualTo(persisted);
            assertThat(rebuilt.documentName()).isEqualTo("d." + type);
        }
        assertThat(ParsedDocument.fromPersisted(FileType.DOCX, "d", persisted).headings())
                .hasSize(1);
        assertThat(ParsedDocument.fromPersisted(FileType.CSV, "d", persisted).headings())
                .isEmpty();
    }

    // ------------------------------------------------------- ParserRouter 路由

    @Test
    void routerRoutesNewFormats() {
        ParserRouter router = new ParserRouter(List.of(
                new MarkdownParser(), new TextParser(), new DocxParser(),
                new XlsxParser(), new CsvParser()));
        assertThat(router.route(FileType.DOCX)).isInstanceOf(DocxParser.class);
        assertThat(router.route(FileType.XLSX)).isInstanceOf(XlsxParser.class);
        assertThat(router.route(FileType.CSV)).isInstanceOf(CsvParser.class);
    }

    private static InputStream unused() {
        return null;
    }
}
