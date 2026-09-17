package com.rag.ingestion.parse;

/**
 * PDFBox 质量探针指标（R6-D，AUTO 路由的唯一输入）。
 *
 * <p>全部为确定性统计量，无 LLM/字体分析/词典参与。有效字符 = 非 Unicode 空白字符
 * （{@link Character#isWhitespace}）；可打印字符按 Unicode 类别判定（中文/英文/数字/
 * 常见标点均算可打印），不以 ASCII 为界；replacement 指 U+FFFD。</p>
 *
 * <p>指标定义：</p>
 * <ul>
 *   <li>{@code charCount}：全文有效字符总数（去空白）</li>
 *   <li>{@code charsPerPage}：charCount / pageCount（pageCount=0 时为 0）</li>
 *   <li>{@code emptyPageRatio}：有效字符 &lt; emptyPageCharThreshold 的页数 / 总页数</li>
 *   <li>{@code printableRatio}：可打印字符数 / 非空白字符数（非空白=0 时为 1）</li>
 *   <li>{@code replacementCharRatio}：U+FFFD 字符数 / 非空白字符数（非空白=0 时为 0）</li>
 * </ul>
 */
public record PdfQualityMetrics(
        int pageCount,
        long charCount,
        double charsPerPage,
        double emptyPageRatio,
        double printableRatio,
        double replacementCharRatio,
        long probeLatencyMs) {

    /** 探针失败时的占位指标（无任何统计意义，仅满足记录完整性）。 */
    public static PdfQualityMetrics failed(long probeLatencyMs) {
        return new PdfQualityMetrics(0, 0, 0, 1, 0, 0, probeLatencyMs);
    }
}
