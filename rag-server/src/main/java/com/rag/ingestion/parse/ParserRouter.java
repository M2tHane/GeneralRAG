package com.rag.ingestion.parse;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.rag.domain.enums.FileType;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 解析器路由表（路线 §2 边界规则 2：第一版按文件类型路由；
 * MinerU 等未来解析器作为新 DocumentParser 实现注册进本表即可）。
 */
@Component
public class ParserRouter {

    private final Map<FileType, DocumentParser> parsers = new EnumMap<>(FileType.class);

    public ParserRouter(List<DocumentParser> candidates) {
        for (DocumentParser parser : candidates) {
            // R6-D：AUTO 委托候选（PdfBoxParser/MineruParser in auto 模式）不占路由表条目；
            // PDF 入口由 AutoPdfParser 独占。routeable() 默认 true，其它 parser 不受影响。
            if (!parser.routeable()) {
                continue;
            }
            DocumentParser existing = parsers.put(parser.supportedType(), parser);
            if (existing != null) {
                throw new IllegalStateException(
                        "FileType " + parser.supportedType() + " 注册了多个解析器："
                                + existing.getClass().getName() + " / " + parser.getClass().getName());
            }
        }
    }

    public DocumentParser route(FileType fileType) {
        DocumentParser parser = parsers.get(fileType);
        if (parser == null) {
            throw new DomainException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "不支持的文件类型（支持 pdf/md/txt/docx/xlsx/csv）：" + fileType);
        }
        return parser;
    }
}
