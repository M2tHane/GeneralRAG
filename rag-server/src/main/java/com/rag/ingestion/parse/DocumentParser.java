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
