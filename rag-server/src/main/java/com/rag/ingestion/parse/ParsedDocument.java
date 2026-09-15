package com.rag.ingestion.parse;

import java.util.ArrayList;
import java.util.List;

import com.rag.domain.enums.FileType;

/**
 * 统一解析产物（路线 §2：ParsedDocument{text, pages[{no, text}], structure hints}）。
 *
 * <p><b>页分隔不变式</b>：当 {@code pages} 非空（PDF）时，{@link #text()} 恒等于
 * 各页文本以 {@link #PAGE_SEPARATOR}（\f，PDF 惯例分页符）顺序拼接的结果。
 * 该不变式是 StructureChunker 由字符偏移推算分块起始页、以及
 * {@link #fromPersisted} 从 parsed.txt 重建逐页结构的依据。
 * 清洗阶段按页清洗后仍以 \f 拼接落盘，不变式贯穿 PARSING → CLEANING → CHUNKING。</p>
 *
 * <p>{@code documentName} 由流水线在解析后补充（仅作 titlePath 前缀展示，
 * 不参与对象键拼接，路线 §7）。</p>
 */
public record ParsedDocument(String documentName, String text,
                             List<ParsedPage> pages, List<Heading> headings) {

    /** PDF 逐页文本在 parsed.txt 中的分隔符（form feed）。 */
    public static final char PAGE_SEPARATOR = '\f';

    public ParsedDocument {
        pages = pages == null ? List.of() : List.copyOf(pages);
        headings = headings == null ? List.of() : List.copyOf(headings);
    }

    /** 无页结构（MD/TXT）：全文 + 可空标题序列。 */
    public static ParsedDocument plain(String text, List<Heading> headings) {
        return new ParsedDocument(null, text, List.of(), headings);
    }

    /** 有页结构（PDF）：逐页文本，text 由页文本以 \f 拼接生成。 */
    public static ParsedDocument paged(List<String> pageTexts) {
        List<ParsedPage> pages = new ArrayList<>(pageTexts.size());
        for (int i = 0; i < pageTexts.size(); i++) {
            pages.add(new ParsedPage(i + 1, pageTexts.get(i)));
        }
        return new ParsedDocument(null, String.join(String.valueOf(PAGE_SEPARATOR), pageTexts), pages, List.of());
    }

    /** 流水线在解析后补充文档名（titlePath 前缀）。 */
    public ParsedDocument withDocumentName(String documentName) {
        return new ParsedDocument(documentName, text, pages, headings);
    }

    /** 是否带逐页结构（PDF）。 */
    public boolean isPaged() {
        return !pages.isEmpty();
    }

    /**
     * 各页文本在 {@link #text()} 中的起始偏移（页分隔符计入前一页之后）。
     * 仅当 {@link #isPaged()} 时有意义；依赖「text = join(pages, \f)」不变式。
     */
    public int[] pageStartOffsets() {
        int[] starts = new int[pages.size()];
        int cursor = 0;
        for (int i = 0; i < pages.size(); i++) {
            starts[i] = cursor;
            cursor += pages.get(i).text().length() + 1; // +1 为 \f 分隔符
        }
        return starts;
    }

    /**
     * 从 parsed.txt（PARSING+CLEANING 的确定性落盘产物）重建解析产物，
     * 供重试从 CHUNKING 续跑时分块使用（阶段复用证据，路线 §3.1）：
     * PDF 按 \f 还原逐页结构；MD 重新提取 ATX 标题；TXT/CSV 直接使用全文
     * （CSV 解析产物已展平为文本）；DOCX/XLSX 解析产物即 Markdown 风格文本，
     * 同样按 ATX 标题重建。
     */
    public static ParsedDocument fromPersisted(FileType fileType, String documentName, String persisted) {
        return switch (fileType) {
            case PDF -> paged(splitPages(persisted)).withDocumentName(documentName);
            case MD, DOCX, XLSX -> plain(persisted, MarkdownParser.extractHeadings(persisted))
                    .withDocumentName(documentName);
            case TXT, CSV -> plain(persisted, List.of()).withDocumentName(documentName);
        };
    }

    private static List<String> splitPages(String persisted) {
        if (persisted.isEmpty()) {
            return List.of("");
        }
        // -1 保留末尾空页段，保证 join(split) == 原文（不变式）
        String[] parts = persisted.split(String.valueOf(PAGE_SEPARATOR), -1);
        return List.of(parts);
    }

    /** 逐页文本（仅 PDF）。no 从 1 起。 */
    public record ParsedPage(int no, String text) {
    }

    /** ATX 标题（仅 MD）。level 为 # 个数（1-6）。 */
    public record Heading(int level, String text) {
    }
}
