package com.rag.ingestion.chunk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RetrievalContentEnricher 单测（R5-A）：检索增强表示的确定性拼装规则。
 */
class RetrievalContentEnricherTest {

    private final RetrievalContentEnricher enricher = new RetrievalContentEnricher();

    @Test
    void includesDocumentNameTitlePathAndAnswerContent() {
        String rc = enricher.enrich("连接池 maximumPoolSize 默认为 10。",
                "数据库运维手册.md > MySQL > HikariCP > 参数配置", "数据库运维手册.md");
        assertThat(rc)
                .contains("章节：数据库运维手册.md > MySQL > HikariCP > 参数配置")
                .contains("连接池 maximumPoolSize 默认为 10。")
                .doesNotContain("文档：数据库运维手册.md"); // titlePath 已含文档名，不重复
        // answerContent 完整保留（前缀只加在头部）
        assertThat(rc.endsWith("连接池 maximumPoolSize 默认为 10。")).isTrue();
    }

    @Test
    void documentNameAddedWhenTitlePathLacksIt() {
        String rc = enricher.enrich("正文内容", "1. 概述", "部署手册.md");
        assertThat(rc)
                .contains("文档：部署手册.md")
                .contains("章节：1. 概述")
                .contains("正文内容");
        // 顺序：文档行 → 章节行 → 正文
        assertThat(rc.indexOf("文档：")).isLessThan(rc.indexOf("章节："));
        assertThat(rc.indexOf("章节：")).isLessThan(rc.indexOf("正文内容"));
    }

    @Test
    void titlePathOnlyWhenNoDocumentName() {
        String rc = enricher.enrich("正文", "手册 > 第 1 章", null);
        assertThat(rc).contains("章节：手册 > 第 1 章").doesNotContain("文档：");
    }

    @Test
    void noTitlePathMeansNoFabrication() {
        // R5-A §1：无 titlePath 时不要虚构——只补文档名行，绝不编造章节名
        String rc = enricher.enrich("正文", null, "部署手册.md");
        assertThat(rc).isEqualTo("文档：部署手册.md\n\n正文");
        // 什么都没有 → 就是原文
        assertThat(enricher.enrich("正文", null, null)).isEqualTo("正文");
        assertThat(enricher.enrich("正文", "", "")).isEqualTo("正文");
    }

    @Test
    void deterministicForSameInputs() {
        String a = enricher.enrich("内容", "手册 > 1", "手册.md");
        String b = enricher.enrich("内容", "手册 > 1", "手册.md");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void nullAnswerContentYieldsEmpty() {
        assertThat(enricher.enrich(null, "t", "d")).isEmpty();
    }
}
