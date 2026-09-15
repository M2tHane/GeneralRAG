package com.rag.ingestion.parse;

import java.io.InputStream;
import java.util.List;

import com.rag.domain.enums.FileType;
import org.springframework.stereotype.Component;

/**
 * 纯文本解析器：UTF-8 全文直读（容错 BOM），无页结构、无标题结构。
 */
@Component
public class TextParser implements DocumentParser {

    @Override
    public FileType supportedType() {
        return FileType.TXT;
    }

    @Override
    public ParsedDocument parse(InputStream in, FileType type) {
        return ParsedDocument.plain(TextReader.readUtf8(in), List.of());
    }
}
