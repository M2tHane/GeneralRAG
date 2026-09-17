package com.rag.ingestion.parse;

/**
 * PDF AUTO 路由原因（R6-D）。固定枚举而非自由字符串，保证同一 PDF 的
 * 路由决策可复盘、可比较。判定优先级（先命中先定论，见 PdfAutoRouter）：
 *
 * <pre>
 * PROBE_FAILED → LOW_TEXT_DENSITY → TOO_MANY_EMPTY_TEXT_PAGES
 *   → LOW_TEXT_QUALITY → GARBLED_TEXT → TEXT_PDF
 * </pre>
 */
public enum PdfRoutingReason {
    /** 文本量/密度正常，路由到 PDFBox。 */
    TEXT_PDF,
    /** 全文字符数或单页均字符低于下限（典型：扫描件无文本层）。 */
    LOW_TEXT_DENSITY,
    /** 空文本页占比超上限（典型：部分页扫描/部分页文本的混合件）。 */
    TOO_MANY_EMPTY_TEXT_PAGES,
    /** 可打印字符占比过低（提取出的"文本"不像正常文本）。 */
    LOW_TEXT_QUALITY,
    /** U+FFFD 替换字符占比过高（典型：字体映射损坏的乱码文本）。 */
    GARBLED_TEXT,
    /** PDFBox 探针无法打开/读取 PDF（损坏或加密）。这只是 AUTO 的路由决策，非解析回退。 */
    PROBE_FAILED
}
