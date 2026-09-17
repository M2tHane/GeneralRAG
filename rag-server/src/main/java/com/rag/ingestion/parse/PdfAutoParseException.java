package com.rag.ingestion.parse;

import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;

/**
 * AUTO 正式解析失败的结构化异常（R6-D.1）。
 *
 * <p>AUTO 路由决策一旦产生（selected/reason/probe）即是确定性事实，即使所选 parser
 * 正式解析失败，这些 routing 信息仍应可复盘。成功路径上报告随
 * {@link ParsedDocument#parseReport()} 上浮；失败路径没有 ParsedDocument，
 * 由本异常携带同一份 {@link ParseReport}——两条路径同源同一对象，
 * <b>不靠解析异常字符串反推 metadata</b>。</p>
 *
 * <p>职责边界（R6-D §29）：本异常只附加 ParseReport 上下文；错误码保留 delegate
 * 原值（如 PARSER_UNAVAILABLE / SCANNED_PDF_NOT_SUPPORTED / INTERNAL_ERROR），
 * 不统一改写；cause 保留原异常堆栈；failure 信息本身不入 ParseReport
 * （由任务失败体系负责）。</p>
 */
public class PdfAutoParseException extends DomainException {

    private final ParseReport parseReport;

    public PdfAutoParseException(ErrorCode code, String message, ParseReport parseReport, Throwable cause) {
        super(code, message);
        if (cause != null) {
            initCause(cause);
        }
        this.parseReport = parseReport;
    }

    /** 已产生的 AUTO 路由报告（selectedParser/routingReason/probeFailed/probe）。 */
    public ParseReport parseReport() {
        return parseReport;
    }
}
