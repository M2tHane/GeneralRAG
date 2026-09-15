package com.rag.ingestion.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

import com.rag.domain.enums.ChunkStrategy;
import com.rag.ingestion.parse.MarkdownParser;
import com.rag.ingestion.parse.ParsedDocument;
import org.springframework.stereotype.Component;

/**
 * 结构化分块：优先按标题/段落边界切分，正文段落聚合直到 maxLength，
 * 单段超长再按长度截断（路线 Task 3）。
 *
 * <ul>
 *   <li>ATX 标题行（#/##/###，来自 MD 解析产物，也兼容文本中出现的标题行）开启新块，
 *       并作为块的 titlePath 前缀，编号形如「1. 标题 > 1.1 子标题」；
 *       完整 titlePath = 「文档名 > 1. 标题 > 1.1 子标题」，无标题时为文档名；
 *   <li>标题行作为其所在块正文的第一行保留（标题后无正文时不丢信息）；
 *   <li>超长段落按 maxLength 硬切（不重叠），各片段按片段起始偏移记页；
 *   <li>PDF 页码：块首字符所在页——依据 ParsedDocument「text = join(pages, \f)」
 *       不变式推算每页起始偏移（见 {@link ParsedDocument#pageStartOffsets()}），
 *       MD/TXT page = null。</li>
 * </ul>
 */
@Component
public class StructureChunker implements Chunker {

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.STRUCTURE;
    }

    @Override
    public List<ChunkDraft> chunk(ParsedDocument doc, ChunkingConfig cfg, String docId) {
        String text = doc.text() == null ? "" : doc.text();
        if (text.isBlank()) {
            return List.of();
        }
        String documentName = doc.documentName() == null ? "" : doc.documentName();
        int[] pageStarts = doc.isPaged() ? doc.pageStartOffsets() : null;

        List<ChunkDraft> drafts = new ArrayList<>();
        int[] levelCounters = new int[7]; // 1-6 级标题计数
        String currentTitlePath = documentName;

        int seq = 0;
        int cursor = 0;
        // \f（PDF 页分隔符）与 \n 同为行边界，且可能出现在行中（页间无换行直接拼接）。
        // 扫描前把 \f 统一替换为 \n：两者都是 1 字符，替换后各页起始偏移不变，
        // pageStartOffsets 在替换后的文本上依然成立，页码归属不受影响。
        if (text.indexOf('\f') >= 0) {
            text = text.replace('\f', '\n');
        }
        String[] lines = text.split("\n", -1);
        StringBuilder body = null;
        String blockTitlePath = documentName;
        Integer blockPage = null;
        boolean pendingBlank = false; // 段落间空行：下一段落前补「\n\n」
        for (String line : lines) {
            int lineStart = cursor;
            cursor += line.length() + 1;
            if (line.isBlank()) {
                if (body != null) {
                    pendingBlank = true;
                }
                continue;
            }
            Matcher heading = MarkdownParser.ATX_HEADING.matcher(line);
            if (heading.matches()) {
                // 标题行：flush 当前块，更新标题路径，标题行作为新块正文第一行
                if (body != null) {
                    seq = flush(drafts, body, blockTitlePath, blockPage, docId, seq);
                }
                int level = heading.group(1).length();
                levelCounters[level]++;
                for (int i = level + 1; i < levelCounters.length; i++) {
                    levelCounters[i] = 0;
                }
                currentTitlePath = documentName + " > " + numberedHeading(levelCounters, level, heading.group(2));
                body = new StringBuilder(line);
                blockTitlePath = currentTitlePath;
                blockPage = pageAt(pageStarts, lineStart);
                pendingBlank = false;
                continue;
            }
            // 正文行：聚合到当前块直到 maxLength（分隔符计入长度，保证块不超上限）
            String separator = body == null ? "" : (pendingBlank ? "\n\n" : "\n");
            if (body == null) {
                body = new StringBuilder();
                blockTitlePath = currentTitlePath;
                blockPage = pageAt(pageStarts, lineStart);
            } else if (body.length() + separator.length() + line.length() > cfg.maxLength()) {
                seq = flush(drafts, body, blockTitlePath, blockPage, docId, seq);
                body = new StringBuilder();
                separator = "";
                blockTitlePath = currentTitlePath;
                blockPage = pageAt(pageStarts, lineStart);
            }
            body.append(separator).append(line);
            pendingBlank = false;
            // 单行超长（如无换行的长段落）：按长度硬切
            while (body.length() > cfg.maxLength()) {
                String overlong = body.toString();
                int keep = cfg.maxLength();
                seq = flush(drafts, new StringBuilder(overlong.substring(0, keep)),
                        blockTitlePath, blockPage, docId, seq);
                String rest = overlong.substring(keep);
                body = new StringBuilder(rest);
                // 剩余部分起始偏移前移，保证 PDF 页码随片段推进
                blockPage = pageAt(pageStarts, lineStart + keep);
            }
        }
        if (body != null) {
            flush(drafts, body, blockTitlePath, blockPage, docId, seq);
        }
        return drafts;
    }

    private static int flush(List<ChunkDraft> drafts, StringBuilder body, String titlePath,
                             Integer page, String docId, int seq) {
        String text = body.toString();
        if (!text.isBlank()) {
            drafts.add(new ChunkDraft(LengthOverlapChunker.chunkId(docId, seq), seq,
                    titlePath, page, text.length(), text));
            seq++;
        }
        body.setLength(0);
        return seq;
    }

    /**
     * 标题编号：level1「1.」，level2「1.1」，level3「1.1.1」……
     *
     * <p>R2-T1：原文标题常自带编号（如「6. RDB 与 AOF 的相互作用」），直接拼接会得到
     * 「1.6. 6. RDB 与 AOF 的相互作用」这类重复编号。<b>先剥掉标题自带的编号前缀</b>，
     * 只保留本方法生成的结构编号。</p>
     */
    private static String numberedHeading(int[] counters, int level, String text) {
        StringBuilder number = new StringBuilder();
        for (int i = 1; i <= level; i++) {
            if (number.length() > 0) {
                number.append('.');
            }
            number.append(counters[i]);
        }
        return number.append(". ").append(stripHeadingNumber(text)).toString();
    }

    /** 正则：标题开头的自带编号前缀，如「6. 」「1.1 」「3、」「2.3.4.」（可含空白）。 */
    private static final java.util.regex.Pattern HEADING_NUMBER_PREFIX =
            java.util.regex.Pattern.compile("^\\s*\\d+(?:\\.\\d+)*\\.?\\s*");

    /**
     * 剥掉标题文本自带的编号前缀；剥完为空（标题只有编号）时原样返回，避免产生空标题。
     */
    private static String stripHeadingNumber(String text) {
        if (text == null) {
            return "";
        }
        String stripped = HEADING_NUMBER_PREFIX.matcher(text).replaceFirst("");
        return stripped.isBlank() ? text : stripped;
    }

    /** 偏移所在页（1 起）：最大的 pageStart ≤ offset；无页结构返回 null。 */
    private static Integer pageAt(int[] pageStarts, int offset) {
        if (pageStarts == null || pageStarts.length == 0) {
            return null;
        }
        int page = 1;
        for (int i = 0; i < pageStarts.length; i++) {
            if (pageStarts[i] <= offset) {
                page = i + 1;
            } else {
                break;
            }
        }
        return page;
    }
}
