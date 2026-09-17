package com.rag.ingestion.parse;

/**
 * 解析路由报告（R6-D）：AUTO 模式的决策与探针指标，随 ParsedDocument 上浮，
 * 由流水线持久化到 document.parse_metadata。
 *
 * <p>手动模式（pdfbox/mineru）无报告——requestedParser=selectedParser，
 * 无路由发生（R6-D §16：routingReason=null 而非造 MANUAL_SELECTION 值，保持简单）。</p>
 *
 * @param selectedParser 实际执行解析的 parser（AUTO 模式必填；手动模式为 null）
 * @param routingReason  AUTO 路由原因（手动模式为 null）
 * @param probeFailed    探针是否失败（PROBE_FAILED 时 true）
 * @param probe          探针指标（AUTO 模式必填，探针失败时为占位值）
 */
public record ParseReport(String selectedParser, String routingReason, boolean probeFailed,
                          PdfQualityMetrics probe) {

    /** AUTO 决策报告。 */
    public static ParseReport auto(PdfRoutingDecision decision) {
        return new ParseReport(decision.selectedParser().name(), decision.reason().name(),
                decision.probeFailed(), decision.metrics());
    }
}
