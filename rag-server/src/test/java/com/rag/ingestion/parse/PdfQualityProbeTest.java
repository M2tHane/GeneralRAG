package com.rag.ingestion.parse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R6-D PDF 质量探针单测：
 * 1. 纯统计逻辑直接喂文本（与 PDFBox 版本行为解耦，锁定指标定义）；
 * 2. 真实 PDF（测试内 PDFBox 现场生成）验证 normal-text vs image-only 方向正确。
 */
class PdfQualityProbeTest {

    private static final PdfQualityProbe PROBE = new PdfQualityProbe();

    // ------------------------------------------------------------- 纯统计

    @Test
    void metricsCountNonWhitespaceAndIgnoreWhitespace() {
        // 3 页：第1页 "Hello 世界 123"（10 有效字符），第2页 " \n\t"（0），第3页 "abc"（3）
        PdfQualityMetrics m = PdfQualityProbe.metrics(
                List.of("Hello 世界 123", " \n\t", "abc"), 20, 5);
        assertThat(m.pageCount()).isEqualTo(3);
        assertThat(m.charCount()).isEqualTo(13);
        assertThat(m.charsPerPage()).isBetween(4.3, 4.4);
        // 第1页 10 ≥ 20? 否（10 < 20）→ 空；第2页 → 空；第3页 → 空。全部低于阈值 20
        assertThat(m.emptyPageRatio()).isEqualTo(1.0);
        assertThat(m.printableRatio()).isEqualTo(1.0);
        assertThat(m.replacementCharRatio()).isZero();
    }

    @Test
    void printableRatioTreatsCjkAsPrintable() {
        // 中文必须算可打印（不以 ASCII 为界）
        PdfQualityMetrics m = PdfQualityProbe.metrics(List.of("数据库配置说明"), 20, 0);
        assertThat(m.printableRatio()).isEqualTo(1.0);
        assertThat(m.charCount()).isEqualTo(7);
    }

    @Test
    void replacementCharRatioCountsUxfffd() {
        // 10 非空白字符中 2 个 U+FFFD → 0.2；U+FFFD 不算可打印 → printable 8/10 = 0.8
        PdfQualityMetrics m = PdfQualityProbe.metrics(List.of("a\uFFFDb\uFFFDcdefgh"), 20, 0);
        assertThat(m.replacementCharRatio()).isEqualTo(0.2);
        assertThat(m.printableRatio()).isEqualTo(0.8);
    }

    @Test
    void controlCharsAreNotPrintableButCountAsChars() {
        // \u0001（C0 控制字符）非空白、不可打印
        PdfQualityMetrics m = PdfQualityProbe.metrics(List.of("ab\u0001cd"), 20, 0);
        assertThat(m.charCount()).isEqualTo(5);
        assertThat(m.printableRatio()).isEqualTo(0.8);
    }

    @Test
    void emptyPageThresholdIsInclusive() {
        // 页有效字符 == 阈值 → 不算空页（< 判空）
        PdfQualityMetrics m = PdfQualityProbe.metrics(List.of("abcdefghij"), 10, 0);
        assertThat(m.emptyPageRatio()).isZero();
        PdfQualityMetrics m2 = PdfQualityProbe.metrics(List.of("abcde"), 10, 0);
        assertThat(m2.emptyPageRatio()).isEqualTo(1.0);
    }

    // ------------------------------------------------------------- 真实 PDF

    /** 测试内现场生成 PDF：每页写入给定文本（空串 = 无内容流 = 无文本层，模拟扫描页）。 */
    private static byte[] buildPdf(List<String> pageTexts) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
                doc.addPage(page);
                if (text != null && !text.isEmpty()) {
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(PDType1Font.HELVETICA, 12);
                        cs.newLineAtOffset(50, 700);
                        cs.showText(text);
                        cs.endText();
                    }
                }
            }
            doc.save(out);
        }
        return out.toByteArray();
    }

    @Test
    void normalTextPdfHasHighDensityAndLowEmptyRatio() throws IOException {
        // 每页约 400 字符正文 → 密度高、无空页
        String body = "The HikariCP connection pool provides configuration parameters "
                + "for maximum pool size and idle timeout behavior in production. ";
        byte[] pdf = buildPdf(List.of(body, body, body));
        PdfQualityMetrics m = PROBE.probe(new ByteArrayInputStream(pdf), 20);

        assertThat(m.pageCount()).isEqualTo(3);
        // 方向断言而非钉死字符数（PDFBox 版本差异容忍，R6-D §28）：文本页应有足量可提取字符
        assertThat(m.charCount()).isGreaterThan(100);
        assertThat(m.emptyPageRatio()).isLessThan(0.1);
        assertThat(m.printableRatio()).isGreaterThan(0.9);
        assertThat(m.replacementCharRatio()).isZero();
        assertThat(m.probeLatencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void imageOnlyPdfHasZeroCharsAndFullEmptyRatio() throws IOException {
        // 纯图片页模拟（无内容流的页 = 无文本层）：charCount 0、emptyPageRatio 1
        byte[] pdf = buildPdf(List.of("", "", ""));
        PdfQualityMetrics m = PROBE.probe(new ByteArrayInputStream(pdf), 20);

        assertThat(m.pageCount()).isEqualTo(3);
        assertThat(m.charCount()).isZero();
        assertThat(m.charsPerPage()).isZero();
        assertThat(m.emptyPageRatio()).isEqualTo(1.0);
    }

    @Test
    void mixedPdfHalfTextHalfEmptyShowsEmptyPageRatio() throws IOException {
        String body = "Mixed pages: this page has a real text layer with enough characters "
                + "to pass the per-page threshold in routing rules. ";
        byte[] pdf = buildPdf(List.of(body, "", body, ""));
        PdfQualityMetrics m = PROBE.probe(new ByteArrayInputStream(pdf), 20);

        assertThat(m.pageCount()).isEqualTo(4);
        assertThat(m.charCount()).isGreaterThan(100);
        // 2/4 页为空文本页 → 0.5（这正是"总量达标但空页多"的混合件特征）
        assertThat(m.emptyPageRatio()).isEqualTo(0.5);
    }

    @Test
    void corruptedPdfFailsProbeExplicitly() {
        byte[] garbage = "this is not a pdf at all just bytes".getBytes();
        assertThatThrownBy(() -> PROBE.probe(new ByteArrayInputStream(garbage), 20))
                .isInstanceOf(PdfQualityProbe.ProbeFailedException.class)
                .hasMessageContaining("探针失败");
    }
}
