package com.rag.ingestion.parse;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * AutoPdfParser 注册条件（R6-D）：rag.ingestion.pdf-parser=auto。
 * 此时 PDF 的 ParserRouter 路由表入口由 AutoPdfParser 独占
 * （PdfBoxParser/MineruParser 仍注册但 routeable()=false）。
 */
public class AutoPdfParserEnabled implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return context.getEnvironment()
                .getProperty("rag.ingestion.pdf-parser", "pdfbox").equals("auto");
    }
}
