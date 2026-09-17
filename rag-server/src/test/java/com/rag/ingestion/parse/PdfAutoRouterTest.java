package com.rag.ingestion.parse;

import com.rag.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R6-D PDF AUTO 路由器纯单测：全部决策通过 decide() 直接喂 metrics，
 * 锁死规则优先级（§11 同一 PDF 永远得到同一个 reason），不被 PDF fixture 细节绑死。
 */
class PdfAutoRouterTest {

    private RagProperties props;
    private PdfAutoRouter router;

    @BeforeEach
    void setUp() {
        props = new RagProperties();
        router = new PdfAutoRouter(new PdfQualityProbe(), props);
    }

    private static PdfQualityMetrics metrics(int pageCount, long charCount, double charsPerPage,
                                             double emptyPageRatio, double printableRatio,
                                             double replacementCharRatio) {
        return new PdfQualityMetrics(pageCount, charCount, charsPerPage, emptyPageRatio,
                printableRatio, replacementCharRatio, 12);
    }

    @Test
    void textPdfRoutesToPdfBox() {
        PdfRoutingDecision d = router.decide(metrics(3, 900, 300, 0, 0.99, 0));
        assertThat(d.selectedParser()).isEqualTo(PdfParserChoice.PDFBOX);
        assertThat(d.reason()).isEqualTo(PdfRoutingReason.TEXT_PDF);
        assertThat(d.probeFailed()).isFalse();
    }

    @Test
    void lowCharCountRoutesToMinerU() {
        PdfRoutingDecision d = router.decide(metrics(2, 99, 49.5, 0, 0.99, 0));
        assertThat(d.selectedParser()).isEqualTo(PdfParserChoice.MINERU);
        assertThat(d.reason()).isEqualTo(PdfRoutingReason.LOW_TEXT_DENSITY);
    }

    @Test
    void lowCharsPerPageRoutesToMinerU() {
        // charCount 达标（≥100）但单页均字符 29.5 < 30：仍 LOW_TEXT_DENSITY
        PdfRoutingDecision d = router.decide(metrics(4, 118, 29.5, 0, 0.99, 0));
        assertThat(d.selectedParser()).isEqualTo(PdfParserChoice.MINERU);
        assertThat(d.reason()).isEqualTo(PdfRoutingReason.LOW_TEXT_DENSITY);
    }

    @Test
    void tooManyEmptyPagesRoutesToMinerU() {
        // 密度达标（躲过第一条规则），空页占比 0.75 < 0.8 默认？不——0.85 > 0.8
        PdfRoutingDecision d = router.decide(metrics(4, 800, 200, 0.85, 0.99, 0));
        assertThat(d.selectedParser()).isEqualTo(PdfParserChoice.MINERU);
        assertThat(d.reason()).isEqualTo(PdfRoutingReason.TOO_MANY_EMPTY_TEXT_PAGES);
    }

    @Test
    void lowPrintableRatioRoutesToMinerU() {
        PdfRoutingDecision d = router.decide(metrics(3, 900, 300, 0.1, 0.80, 0));
        assertThat(d.selectedParser()).isEqualTo(PdfParserChoice.MINERU);
        assertThat(d.reason()).isEqualTo(PdfRoutingReason.LOW_TEXT_QUALITY);
    }

    @Test
    void highReplacementRatioRoutesToMinerU() {
        PdfRoutingDecision d = router.decide(metrics(3, 900, 300, 0.1, 0.99, 0.10));
        assertThat(d.selectedParser()).isEqualTo(PdfParserChoice.MINERU);
        assertThat(d.reason()).isEqualTo(PdfRoutingReason.GARBLED_TEXT);
    }

    @Test
    void routingReasonPriorityIsDeterministic() {
        // 全部条件同时命中：按 PROBE_FAILED 之外规则声明顺序取第一个 = LOW_TEXT_DENSITY
        PdfRoutingDecision d = router.decide(metrics(2, 10, 5, 0.9, 0.1, 0.5));
        assertThat(d.reason()).isEqualTo(PdfRoutingReason.LOW_TEXT_DENSITY);

        // 命中后三条但躲过密度：TOO_MANY_EMPTY_TEXT_PAGES 优先于 LOW_TEXT_QUALITY/GARBLED
        PdfRoutingDecision d2 = router.decide(metrics(2, 200, 100, 0.9, 0.1, 0.5));
        assertThat(d2.reason()).isEqualTo(PdfRoutingReason.TOO_MANY_EMPTY_TEXT_PAGES);

        // 命中后两条但躲过空页：LOW_TEXT_QUALITY 优先于 GARBLED_TEXT
        PdfRoutingDecision d3 = router.decide(metrics(2, 200, 100, 0.5, 0.1, 0.5));
        assertThat(d3.reason()).isEqualTo(PdfRoutingReason.LOW_TEXT_QUALITY);
    }

    @Test
    void sameMetricsAlwaysProduceSameDecision() {
        PdfQualityMetrics m = metrics(4, 500, 125, 0.25, 0.95, 0.01);
        for (int i = 0; i < 5; i++) {
            PdfRoutingDecision d = router.decide(m);
            assertThat(d.selectedParser()).isEqualTo(PdfParserChoice.PDFBOX);
            assertThat(d.reason()).isEqualTo(PdfRoutingReason.TEXT_PDF);
        }
    }

    @Test
    void probeFailureRoutesToMinerUWithFailedMetrics() {
        PdfRoutingDecision d = PdfRoutingDecision.probeFailed(PdfQualityMetrics.failed(8));
        assertThat(d.selectedParser()).isEqualTo(PdfParserChoice.MINERU);
        assertThat(d.reason()).isEqualTo(PdfRoutingReason.PROBE_FAILED);
        assertThat(d.probeFailed()).isTrue();
        assertThat(d.metrics().pageCount()).isZero();
    }

    @Test
    void thresholdsAreConfigurable() {
        props.getIngestion().getPdfAuto().setMinChars(1000);
        // 900 字符在默认阈值下是 TEXT_PDF，提高 minChars 后变 LOW_TEXT_DENSITY
        PdfRoutingDecision d = router.decide(metrics(3, 900, 300, 0, 0.99, 0));
        assertThat(d.reason()).isEqualTo(PdfRoutingReason.LOW_TEXT_DENSITY);
        props.getIngestion().getPdfAuto().setMinChars(100);
        assertThat(router.decide(metrics(3, 900, 300, 0, 0.99, 0)).reason())
                .isEqualTo(PdfRoutingReason.TEXT_PDF);
    }

    @Test
    void boundaryValuesUseInclusiveMinAndExclusiveMax() {
        // 恰好等于下限：不算低密度（min 语义为 < 判低）；恰好等于上限：不算超限（max 语义为 > 判超）
        PdfRoutingDecision d = router.decide(metrics(1, 100, 30, 0.8, 0.90, 0.05));
        assertThat(d.reason()).isEqualTo(PdfRoutingReason.TEXT_PDF);
    }

    @Test
    void routeOnProbeFailureReturnsProbeFailedDecision() {
        PdfQualityProbe failingProbe = new PdfQualityProbe() {
            @Override
            public PdfQualityMetrics probe(java.io.InputStream in, int emptyPageCharThreshold) {
                throw new PdfQualityProbe.ProbeFailedException("模拟损坏 PDF", null);
            }
        };
        PdfAutoRouter r = new PdfAutoRouter(failingProbe, props);
        PdfRoutingDecision d = r.route(new java.io.ByteArrayInputStream(new byte[0]));
        assertThat(d.selectedParser()).isEqualTo(PdfParserChoice.MINERU);
        assertThat(d.reason()).isEqualTo(PdfRoutingReason.PROBE_FAILED);
        assertThat(d.probeFailed()).isTrue();
    }
}
