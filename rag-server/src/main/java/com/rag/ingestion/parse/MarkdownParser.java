package com.rag.ingestion.parse;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.rag.domain.enums.FileType;
import org.springframework.stereotype.Component;

/**
 * Markdown 解析器：全文 UTF-8 直读（容错 UTF-8 BOM），
 * 按行提取 ATX 标题（#/##/### … 1-6 级）为 headings 序列。
 */
@Component
public class MarkdownParser implements DocumentParser {

    /** ATX 标题：行首 1-6 个 #，后接至少一个空白，再到行尾（行尾 # 视为正文不剥离）。 */
    public static final Pattern ATX_HEADING = Pattern.compile("^(#{1,6})\\s+(.*\\S|\\S)\\s*$");

    @Override
    public FileType supportedType() {
        return FileType.MD;
    }

    @Override
    public ParsedDocument parse(InputStream in, FileType type) {
        String text = TextReader.readUtf8(in);
        return ParsedDocument.plain(text, extractHeadings(text));
    }

    /**
     * 提取 ATX 标题序列（按文档顺序）。静态方法复用：重试从 CHUNKING 续跑时
     * {@link ParsedDocument#fromPersisted} 据此从 parsed.txt 重建标题结构。
     */
    public static List<ParsedDocument.Heading> extractHeadings(String text) {
        List<ParsedDocument.Heading> headings = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            Matcher matcher = ATX_HEADING.matcher(line);
            if (matcher.matches()) {
                headings.add(new ParsedDocument.Heading(matcher.group(1).length(), matcher.group(2)));
            }
        }
        return headings;
    }
}
