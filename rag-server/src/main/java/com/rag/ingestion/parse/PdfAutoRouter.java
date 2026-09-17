package com.rag.ingestion.parse;

import java.io.InputStream;

import com.rag.config.RagProperties;
import org.springframework.stereotype.Component;

/**
 * PDF AUTO 路由器（R6-D）：probe + decision，仅此而已。
 *
 * <p>不做正式解析、不 chunk、不上传 MinerU、不写数据库（R6-D §3）。规则顺序固定
 * （R6-D §11：同一 PDF 永远得到同一个 routing reason，多条件同时命中时按序取第一个）：</p>
 *
 * <pre>
 * 探针失败
 *   → MINERU / PROBE_FAILED
 * charCount &lt; minChars 或 charsPerPage &lt; minCharsPerPage
 *   → MINERU / LOW_TEXT_DENSITY
 * emptyPageRatio &gt; maxEmptyPageRatio
 *   → MINERU / TOO_MANY_EMPTY_TEXT_PAGES
 * printableRatio &lt; minPrintableRatio
 *   → MINERU / LOW_TEXT_QUALITY
 * replacementCharRatio &gt; maxReplacementCharRatio
 *   → MINERU / GARBLED_TEXT
 * 其余
 *   → PDFBOX / TEXT_PDF
 * </pre>
 *
 * <p>阈值全部来自 {@link RagProperties.Ingestion#getPdfAuto()}，不散落在代码里。
 * 确定性规则，无任何 LLM 参与。</p>
 */
@Component
public class PdfAutoRouter {

    private final PdfQualityProbe probe;
    private final RagProperties.Ingestion.PdfAuto cfg;

    public PdfAutoRouter(PdfQualityProbe probe, RagProperties properties) {
        this.probe = probe;
        this.cfg = properties.getIngestion().getPdfAuto();
    }

    /**
     * 探针 + 路由。探针失败不抛出（PROBE_FAILED 是合法决策），其余异常照常上抛。
     */
    public PdfRoutingDecision route(InputStream in) {
        try {
            return decide(probe.probe(in, cfg.getEmptyPageCharThreshold()));
        } catch (PdfQualityProbe.ProbeFailedException e) {
            // PROBE_FAILED → MINERU：这只是 AUTO 的路由决策（加密/损坏件交给 MinerU 定夺），
            // 不是「PDFBox 解析失败 → 换 MinerU」的回退
            return PdfRoutingDecision.probeFailed(PdfQualityMetrics.failed(0));
        }
    }

    /** 纯决策（探针结果 → 结论），单测直接喂 metrics 锁优先级，不被 fixture 细节绑死。 */
    public PdfRoutingDecision decide(PdfQualityMetrics m) {
        if (m.charCount() < cfg.getMinChars() || m.charsPerPage() < cfg.getMinCharsPerPage()) {
            return PdfRoutingDecision.of(PdfParserChoice.MINERU, PdfRoutingReason.LOW_TEXT_DENSITY, m);
        }
        if (m.emptyPageRatio() > cfg.getMaxEmptyPageRatio()) {
            return PdfRoutingDecision.of(PdfParserChoice.MINERU, PdfRoutingReason.TOO_MANY_EMPTY_TEXT_PAGES, m);
        }
        if (m.printableRatio() < cfg.getMinPrintableRatio()) {
            return PdfRoutingDecision.of(PdfParserChoice.MINERU, PdfRoutingReason.LOW_TEXT_QUALITY, m);
        }
        if (m.replacementCharRatio() > cfg.getMaxReplacementCharRatio()) {
            return PdfRoutingDecision.of(PdfParserChoice.MINERU, PdfRoutingReason.GARBLED_TEXT, m);
        }
        return PdfRoutingDecision.of(PdfParserChoice.PDFBOX, PdfRoutingReason.TEXT_PDF, m);
    }
}
