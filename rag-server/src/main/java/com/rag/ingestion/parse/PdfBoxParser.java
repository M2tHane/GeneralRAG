package com.rag.ingestion.parse;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.rag.domain.enums.FileType;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * PDF 解析器（PDFBox 2.x，经 langchain4j-document-parser-apache-pdfbox 传递依赖）：
 * 用 {@link PDFTextStripper#setStartPage(int)/#setEndPage(int)} 逐页抽取文本，
 * 保留每页文本供 StructureChunker 记录分块起始页（路线 §3.1）。
 *
 * <p>扫描件检测（异步，路线 §12 决策 1）：首页无文本层 →
 * {@link ErrorCode#SCANNED_PDF_NOT_SUPPORTED}，任务 FAILED 并在前端行内提示。</p>
 *
 * <p>注册条件（R3-P2；R6-D 扩展）：{@code rag.ingestion.pdf-parser} 缺省或为 pdfbox 时
 * 手动注册（ParserRouter 直接路由）；配置为 auto 时仍注册，但仅作为 {@link AutoPdfParser}
 * 的委托候选（PDF 的 ParserRouter 路由表入口由 AutoPdfParser 独占——否则 AUTO 下
 * PDF 有两个 FileType.PDF 实现，ParserRouter 重复注册会启动失败）；配置为 mineru 时
 * 由 {@link MineruParser} 接管。</p>
 */
@Component
@Conditional(PdfBoxParserEnabled.class)
public class PdfBoxParser implements DocumentParser {

    @Override
    public FileType supportedType() {
        return FileType.PDF;
    }

    /**
     * AUTO 模式下不占 ParserRouter 路由表条目（仅作为 AutoPdfParser 委托候选）。
     * 直连 Spring Environment 判定：与 PdfBoxParserEnabled 的注册条件保持一致。
     */
    @Override
    public boolean routeable() {
        String mode = environment.getProperty("rag.ingestion.pdf-parser", "pdfbox");
        return !"auto".equals(mode);
    }

    private final org.springframework.core.env.Environment environment;

    public PdfBoxParser(org.springframework.core.env.Environment environment) {
        this.environment = environment;
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
