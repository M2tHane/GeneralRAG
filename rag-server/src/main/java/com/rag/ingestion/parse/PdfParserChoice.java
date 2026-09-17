package com.rag.ingestion.parse;

/**
 * AUTO 路由可选择的实际解析器（R6-D）。
 *
 * <p>只有两个成员：AUTO 本身不是选择对象（AUTO 是路由器，路由的产物必是其中之一）。
 * 独立于全局配置字符串 {@code rag.ingestion.pdf-parser}（那个还有 auto 取值）。</p>
 */
public enum PdfParserChoice {
    PDFBOX,
    MINERU
}
