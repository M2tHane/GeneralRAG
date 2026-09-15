package com.rag.ingestion.parse;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.rag.domain.enums.FileType;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * PDF 解析器（PDFBox 2.x，经 langchain4j-document-parser-apache-pdfbox 传递依赖）：
 * 用 {@link PDFTextStripper#setStartPage(int)/#setEndPage(int)} 逐页抽取文本，
 * 保留每页文本供 StructureChunker 记录分块起始页（路线 §3.1）。
 *
 * <p>扫描件检测（异步，路线 §12 决策 1）：首页无文本层 →
 * {@link ErrorCode#SCANNED_PDF_NOT_SUPPORTED}，任务 FAILED 并在前端行内提示。</p>
 *
 * <p>注册条件（R3-P2）：{@code rag.ingestion.pdf-parser} 缺省或为 pdfbox 时注册；
 * 配置为 mineru 时由 {@link MineruParser} 接管 PDF（两者按 FileType 互斥，
 * ParserRouter 重复注册会启动失败）。</p>
 */
@Component
@ConditionalOnProperty(name = "rag.ingestion.pdf-parser", havingValue = "pdfbox", matchIfMissing = true)
public class PdfBoxParser implements DocumentParser {

    @Override
    public FileType supportedType() {
        return FileType.PDF;
    }

    @Override
    public ParsedDocument parse(InputStream in, FileType type) {
        List<String> pageTexts = new ArrayList<>();
        try (PDDocument document = PDDocument.load(in)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            int pageCount = document.getNumberOfPages();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pageTexts.add(stripper.getText(document));
            }
        } catch (Exception e) {
            if (e instanceof DomainException de) {
                throw de;
            }
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "PDF 解析失败：" + e.getMessage());
        }
        if (pageTexts.isEmpty() || pageTexts.get(0).isBlank()) {
            throw new DomainException(ErrorCode.SCANNED_PDF_NOT_SUPPORTED,
                    "扫描版 PDF 暂不支持：首页未检测到文本层（可能是纯图片扫描件）。"
                            + "请上传含文本层的 PDF，或使用 MD/TXT 格式。");
        }
        return ParsedDocument.paged(pageTexts);
    }
}
