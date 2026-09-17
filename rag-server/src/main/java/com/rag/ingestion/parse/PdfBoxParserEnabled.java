package com.rag.ingestion.parse;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * PdfBoxParser 注册条件（R6-D）：pdf-parser 缺省或 pdfbox（手动直连），或 auto
 * （AUTO 路由的委托候选——此时 PdfBoxParser 仍注册为 Bean，但不进入 ParserRouter
 * 路由表，PDF 的路由表入口由 AutoPdfParser 独占）。
 */
public class PdfBoxParserEnabled implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String value = context.getEnvironment().getProperty("rag.ingestion.pdf-parser", "pdfbox");
        return value.equals("pdfbox") || value.equals("auto");
    }
}
