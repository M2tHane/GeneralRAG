package com.rag.ingestion.parse;

/**
 * PDF AUTO 路由决策（R6-D）：AUTO ownership 的落点，一次探针一个结论。
 *
 * <p>selectedParser 仅取 PDFBOX / MINERU；决策一旦做出，对应 parser 的正式解析失败
 * 就是明确的入库失败——禁止换另一个 parser 静默回退（回退会让同一 PDF 在不同次
 * 上传中产生不同 chunk 结构而 metadata 无从察觉，破坏版本比较/Eval/debug）。</p>
 *
 * <p>{@code probeFailed} 单独成位（区别于 reason），供上层失败文案区分
 * 「探针都做不了」与「探针正常但判定走 mineru」。</p>
 */
public record PdfRoutingDecision(
        PdfParserChoice selectedParser,
        PdfRoutingReason reason,
        PdfQualityMetrics metrics,
        boolean probeFailed) {

    /** 探针失败 → mineru（PROBE_FAILED）。metrics 为占位值。 */
    public static PdfRoutingDecision probeFailed(PdfQualityMetrics metrics) {
        return new PdfRoutingDecision(PdfParserChoice.MINERU, PdfRoutingReason.PROBE_FAILED,
                metrics, true);
    }

    /** 探针成功 → 按规则路由。 */
    public static PdfRoutingDecision of(PdfParserChoice parser, PdfRoutingReason reason,
                                        PdfQualityMetrics metrics) {
        return new PdfRoutingDecision(parser, reason, metrics, false);
    }
}
