package com.rag.ingestion.parse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.rag.config.RagProperties;
import com.rag.domain.enums.FileType;
import com.rag.domain.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R6-D AutoPdfParser 单测（Mockito）：AUTO ownership 语义——
 * 决策一旦做出，所选 parser 正式解析失败就是入库失败，禁止换 parser 静默回退；
 * 探针失败 → MINERU 决策（这是路由，不是回退）；手动模式无报告。
 */
class AutoPdfParserTest {

    private PdfAutoRouter router;
    private PdfBoxParser pdfBoxParser;
    private MineruParser mineruParser;
    private AutoPdfParser parser;

    private static final byte[] PDF_BYTES = "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        router = mock(PdfAutoRouter.class);
        pdfBoxParser = mock(PdfBoxParser.class);
        mineruParser = mock(MineruParser.class);
        parser = new AutoPdfParser(router, pdfBoxParser, mineruParser);
    }

    private static PdfRoutingDecision decision(PdfParserChoice choice, PdfRoutingReason reason) {
        return PdfRoutingDecision.of(choice, reason,
                new PdfQualityMetrics(2, 400, 200, 0, 0.99, 0, 5));
    }

    @Test
    void routesToPdfBoxAndDelegates() {
        when(router.route(any(InputStream.class))).thenReturn(decision(PdfParserChoice.PDFBOX, PdfRoutingReason.TEXT_PDF));
        when(pdfBoxParser.parse(any(InputStream.class), any(FileType.class)))
                .thenReturn(ParsedDocument.paged(java.util.List.of("页面一", "页面二")));

        ParsedDocument parsed = parser.parse(new ByteArrayInputStream(PDF_BYTES), FileType.PDF);

        assertThat(parsed.parseReport()).isNotNull();
        assertThat(parsed.parseReport().selectedParser()).isEqualTo("PDFBOX");
        assertThat(parsed.parseReport().routingReason()).isEqualTo("TEXT_PDF");
        assertThat(parsed.parseReport().probeFailed()).isFalse();
        assertThat(parsed.parseReport().probe().pageCount()).isEqualTo(2);
        verify(mineruParser, never()).parse(any(InputStream.class), any(FileType.class));
    }

    @Test
    void routesToMinerUAndDelegates() {
        when(router.route(any(InputStream.class))).thenReturn(decision(PdfParserChoice.MINERU, PdfRoutingReason.LOW_TEXT_DENSITY));
        when(mineruParser.parse(any(InputStream.class), any(FileType.class)))
                .thenReturn(ParsedDocument.plain("# OCR 结果", java.util.List.of()));

        ParsedDocument parsed = parser.parse(new ByteArrayInputStream(PDF_BYTES), FileType.PDF);

        assertThat(parsed.parseReport().selectedParser()).isEqualTo("MINERU");
        assertThat(parsed.parseReport().routingReason()).isEqualTo("LOW_TEXT_DENSITY");
        verify(pdfBoxParser, never()).parse(any(InputStream.class), any(FileType.class));
    }

    /** R6-D §31 核心语义：AUTO→PDFBOX→解析失败 = 失败，绝不切 MinerU。
     *  R6-D.1：失败以结构化 PdfAutoParseException 抛出，携带已产生的 ParseReport
     *  （selected/routingReason 可复盘），错误码保留 delegate 原值。 */
    @Test
    void pdfBoxFailureAfterPdfBoxDecisionNeverFallsBackToMinerU() {
        when(router.route(any(InputStream.class))).thenReturn(decision(PdfParserChoice.PDFBOX, PdfRoutingReason.TEXT_PDF));
        when(pdfBoxParser.parse(any(InputStream.class), any(FileType.class)))
                .thenThrow(new DomainException(com.rag.domain.exception.ErrorCode.SCANNED_PDF_NOT_SUPPORTED));

        org.assertj.core.api.ThrowableAssert.ThrowingCallable call =
                () -> parser.parse(new ByteArrayInputStream(PDF_BYTES), FileType.PDF);
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("AUTO selected PDFBOX because TEXT_PDF");
        verify(mineruParser, never()).parse(any(InputStream.class), any(FileType.class));

        // 结构化上下文：report 来自异常对象而非异常字符串反推
        PdfAutoParseException structured = catchThrowableOfType(call, PdfAutoParseException.class);
        assertThat(structured.parseReport().selectedParser()).isEqualTo("PDFBOX");
        assertThat(structured.parseReport().routingReason()).isEqualTo("TEXT_PDF");
        assertThat(structured.parseReport().probeFailed()).isFalse();
        assertThat(structured.getCode()).isEqualTo(com.rag.domain.exception.ErrorCode.SCANNED_PDF_NOT_SUPPORTED);
        assertThat(structured.getCause()).isInstanceOf(DomainException.class);
    }

    /** R6-D §14 核心语义：AUTO→MINERU→失败 = 失败，绝不切 PDFBox。
     *  R6-D.1：结构化 report 记录 selected=MINERU + routingReason。 */
    @Test
    void mineruFailureAfterMineruDecisionNeverFallsBackToPdfBox() {
        when(router.route(any(InputStream.class))).thenReturn(decision(PdfParserChoice.MINERU, PdfRoutingReason.LOW_TEXT_DENSITY));
        when(mineruParser.parse(any(InputStream.class), any(FileType.class)))
                .thenThrow(new DomainException(com.rag.domain.exception.ErrorCode.PARSER_UNAVAILABLE, "MinerU 服务不可达"));

        org.assertj.core.api.ThrowableAssert.ThrowingCallable call =
                () -> parser.parse(new ByteArrayInputStream(PDF_BYTES), FileType.PDF);
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("AUTO selected MINERU because LOW_TEXT_DENSITY")
                .hasMessageContaining("MinerU 服务不可达");
        verify(pdfBoxParser, never()).parse(any(InputStream.class), any(FileType.class));

        PdfAutoParseException structured = catchThrowableOfType(call, PdfAutoParseException.class);
        assertThat(structured.parseReport().selectedParser()).isEqualTo("MINERU");
        assertThat(structured.parseReport().routingReason()).isEqualTo("LOW_TEXT_DENSITY");
        assertThat(structured.getCode()).isEqualTo(com.rag.domain.exception.ErrorCode.PARSER_UNAVAILABLE);
    }

    /** R6-D.1：探针失败（PROBE_FAILED→MINERU）后 MinerU 也失败——routing 事实
     *  （probeFailed=true + 占位 metrics）仍随结构化异常保留。 */
    @Test
    void mineruFailureAfterProbeFailedDecisionKeepsProbeFailedReport() {
        when(router.route(any(InputStream.class)))
                .thenReturn(PdfRoutingDecision.probeFailed(PdfQualityMetrics.failed(3)));
        when(mineruParser.parse(any(InputStream.class), any(FileType.class)))
                .thenThrow(new DomainException(com.rag.domain.exception.ErrorCode.PARSER_UNAVAILABLE, "MinerU 500"));

        org.assertj.core.api.ThrowableAssert.ThrowingCallable call =
                () -> parser.parse(new ByteArrayInputStream(PDF_BYTES), FileType.PDF);
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("AUTO selected MINERU because PROBE_FAILED");
        verify(pdfBoxParser, never()).parse(any(InputStream.class), any(FileType.class));

        PdfAutoParseException structured = catchThrowableOfType(call, PdfAutoParseException.class);
        assertThat(structured.parseReport().selectedParser()).isEqualTo("MINERU");
        assertThat(structured.parseReport().routingReason()).isEqualTo("PROBE_FAILED");
        assertThat(structured.parseReport().probeFailed()).isTrue();
        assertThat(structured.parseReport().probe().pageCount()).isZero();
    }

    /** 探针失败是合法路由决策（→ MINERU），不是异常。 */
    @Test
    void probeFailedDecisionRoutesToMinerUWithoutError() {
        when(router.route(any(InputStream.class)))
                .thenReturn(PdfRoutingDecision.probeFailed(PdfQualityMetrics.failed(3)));
        when(mineruParser.parse(any(InputStream.class), any(FileType.class)))
                .thenReturn(ParsedDocument.plain("OCR 文本", java.util.List.of()));

        ParsedDocument parsed = parser.parse(new ByteArrayInputStream(PDF_BYTES), FileType.PDF);

        assertThat(parsed.parseReport().probeFailed()).isTrue();
        assertThat(parsed.parseReport().routingReason()).isEqualTo("PROBE_FAILED");
        verify(pdfBoxParser, never()).parse(any(InputStream.class), any(FileType.class));
    }

    /** 失败文案区分探针/路由/正式解析（R6-D §21）：错误信息带 selected+reason 前缀。 */
    @Test
    void failureMessageKeepsRoutingContext() {
        when(router.route(any(InputStream.class))).thenReturn(decision(PdfParserChoice.MINERU, PdfRoutingReason.GARBLED_TEXT));
        when(mineruParser.parse(any(InputStream.class), any(FileType.class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(PDF_BYTES), FileType.PDF))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("AUTO selected MINERU because GARBLED_TEXT")
                .hasMessageContaining("connection refused");
    }

    /** 流可重读：探针与正式解析各读一次（AutoPdfParser 先整体读入内存再分发）。 */
    @Test
    void streamIsReusableAcrossProbeAndParse() {
        AtomicInteger reads = new AtomicInteger();
        when(router.route(any(InputStream.class))).thenAnswer(inv -> {
            InputStream in = inv.getArgument(0);
            reads.incrementAndGet();
            in.readAllBytes();
            return decision(PdfParserChoice.PDFBOX, PdfRoutingReason.TEXT_PDF);
        });
        when(pdfBoxParser.parse(any(InputStream.class), any(FileType.class))).thenAnswer(inv -> {
            InputStream in = inv.getArgument(0);
            reads.incrementAndGet();
            byte[] all = in.readAllBytes();
            assertThat(all).isEqualTo(PDF_BYTES);
            return ParsedDocument.paged(java.util.List.of("x"));
        });

        parser.parse(new ByteArrayInputStream(PDF_BYTES), FileType.PDF);
        assertThat(reads.get()).isEqualTo(2);
    }
}
