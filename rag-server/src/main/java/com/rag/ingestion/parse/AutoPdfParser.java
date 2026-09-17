package com.rag.ingestion.parse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import com.rag.domain.enums.FileType;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * PDF AUTO 解析器（R6-D）：probe → 确定性路由 → 委托所选 parser 正式解析。
 *
 * <p><b>AUTO ≠ fallback</b>（R6-D 核心原则）：{@link PdfAutoRouter} 一旦定论
 * （selectedParser=PDFBOX 或 MINERU），本次解析的 ownership 即归属该 parser；
 * 所选 parser 的正式解析失败就是明确的入库失败，<b>禁止</b>换另一个 parser 静默回退。
 * 否则同一 PDF 今天 MinerU 明天 PDFBox，chunk 结构漂移而 metadata 无从察觉，
 * 破坏版本比较/Eval/一致性/debug。</p>
 *
 * <p><b>与手动模式的关系</b>：{@code rag.ingestion.pdf-parser=pdfbox|mineru} 时本 Bean
 * 不注册，PdfBoxParser/MineruParser 按原有条件互斥注册，行为零变化。仅
 * {@code =auto} 时本类成为 PDF 的唯一注册 parser（ParserRouter 按 FileType 唯一，
 * 三者仍互斥，重复注册启动即失败——原有防护不变）。</p>
 *
 * <p><b>路由元数据</b>：决策（selected/reason/metrics/latency）写入
 * {@link ParseReport}——成功路径随 {@link ParsedDocument} 上浮、失败路径经
 * {@link PdfAutoParseException} 上浮——由流水线持久化到 document.parse_metadata
 * （探针指标只存数据库不上抛用户——路由可复盘即可）。</p>
 *
 * <p>失败语义（R6-D §21）：错误文案明确区分三个层次——探针/路由/正式解析。
 * 探针失败不构成错误（路由到 MinerU 是合法决策）；正式解析失败时错误信息
 * 前缀 {@code AUTO selected X because REASON}，绝不伪装成普通 PDF 解析错误。
 * 失败以 {@link PdfAutoParseException} 结构化抛出（携带 ParseReport，
 * 错误码保留 delegate 原值，cause 保留原异常）。</p>
 */
@Component
@Conditional(AutoPdfParserEnabled.class)
public class AutoPdfParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(AutoPdfParser.class);

    private final PdfAutoRouter router;
    private final PdfBoxParser pdfBoxParser;
    private final MineruParser mineruParser;

    /** AUTO 下两个候选都由配置显式给出；缺一不可（构造期即暴露装配错误）。 */
    public AutoPdfParser(PdfAutoRouter router, PdfBoxParser pdfBoxParser, MineruParser mineruParser) {
        this.router = router;
        this.pdfBoxParser = pdfBoxParser;
        this.mineruParser = mineruParser;
    }

    @Override
    public FileType supportedType() {
        return FileType.PDF;
    }

    @Override
    public ParsedDocument parse(InputStream in, FileType type) {
        // AUTO 决策需要字节流可重读：MineruParser 内部 readAllBytes 会耗尽流，
        // 而探针与正式解析各需一次读取——先整体读入内存（≤ max-upload-size-mb=50MB，
        // 上传层已有大小上限），再用 ByteArrayInputStream 分发。
        byte[] bytes;
        try {
            bytes = in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "读取 PDF 流失败：" + e.getMessage());
        }

        PdfRoutingDecision decision = router.route(new ByteArrayInputStream(bytes));
        log.info("AUTO_PROBE 完成：selected={}, reason={}, pages={}, chars={}, charsPerPage={}, "
                        + "emptyPageRatio={}, printableRatio={}, replacementCharRatio={}, probeLatencyMs={}",
                decision.selectedParser(), decision.reason(), decision.metrics().pageCount(),
                decision.metrics().charCount(), decision.metrics().charsPerPage(),
                decision.metrics().emptyPageRatio(), decision.metrics().printableRatio(),
                decision.metrics().replacementCharRatio(), decision.metrics().probeLatencyMs());

        ParseReport report = ParseReport.auto(decision);
        ParsedDocument parsed;
        try {
            parsed = switch (decision.selectedParser()) {
                case PDFBOX -> pdfBoxParser.parse(new ByteArrayInputStream(bytes), type);
                case MINERU -> mineruParser.parse(new ByteArrayInputStream(bytes), type);
            };
        } catch (RuntimeException e) {
            // 所选 parser 的正式解析失败 = 明确的入库失败。不换 parser（AUTO ≠ fallback）。
            // R6-D.1：抛结构化异常携带已产生的 routing report，失败路径同样持久化
            // parse_metadata（成功路径 report 随 ParsedDocument 上浮，两条路径同源）。
            // 错误码保留 delegate 原值；cause 保留原异常堆栈。
            throw new PdfAutoParseException(
                    e instanceof DomainException de ? de.getCode() : ErrorCode.INTERNAL_ERROR,
                    "AUTO selected " + decision.selectedParser() + " because " + decision.reason()
                            + "；正式解析失败：" + e.getMessage(),
                    report, e);
        }
        return parsed.withParseReport(report);
    }
}
