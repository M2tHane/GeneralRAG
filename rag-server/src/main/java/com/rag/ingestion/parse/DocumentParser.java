package com.rag.ingestion.parse;

import java.io.InputStream;

import com.rag.domain.enums.FileType;

/**
 * 文档解析器接口（路线 §2 边界规则 2：MinerU 等未来解析器在此新增实现即可，流水线阶段不变）。
 *
 * <p>输入为源文件字节流与文件类型，输出统一的 {@link ParsedDocument}
 * （全文 + 逐页文本[仅 PDF] + 标题序列[仅 MD]）。</p>
 */
public interface DocumentParser {

    /** 本解析器负责的文件类型（ParserRouter 据此路由）。 */
    FileType supportedType();

    /**
     * 是否进入 ParserRouter 路由表（R6-D）：AUTO 模式下 PdfBoxParser/MineruParser
     * 仍作为 Bean 注册（供 {@link AutoPdfParser} 委托），但不占用路由表条目——
     * PDF 入口由 AutoPdfParser 独占，避免同 FileType 多实现注册冲突。
     * 默认 true（全部既有 parser 不受影响）。
     */
    default boolean routeable() {
        return true;
    }

    /**
     * 解析源文件。
     *
     * @param in   源文件字节流（调用方负责关闭；实现方内部不关闭）
     * @param type 文件类型（与 {@link #supportedType()} 一致）
     * @return 统一解析产物
     * @throws com.rag.domain.exception.DomainException 业务性失败
     *         （如 PDF 首页无文本层 → SCANNED_PDF_NOT_SUPPORTED）
     */
    ParsedDocument parse(InputStream in, FileType type);
}
