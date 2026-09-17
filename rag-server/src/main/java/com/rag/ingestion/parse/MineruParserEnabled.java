package com.rag.ingestion.parse;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * MineruParser 注册条件（R6-D）：pdf-parser=mineru（手动直连），或 auto
 * （AUTO 路由的委托候选；base-url 为空时 Bean 仍注册但解析时显式失败，
 * 与手动 mineru 模式的既有语义一致）。
 */
public class MineruParserEnabled implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String value = context.getEnvironment().getProperty("rag.ingestion.pdf-parser", "pdfbox");
        return value.equals("mineru") || value.equals("auto");
    }
}
