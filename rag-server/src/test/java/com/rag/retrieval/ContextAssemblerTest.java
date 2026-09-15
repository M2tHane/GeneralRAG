package com.rag.retrieval;

import java.util.List;

import com.rag.retrieval.model.Context;
import com.rag.retrieval.model.RetrievalHit;
import com.rag.storage.es.EsHit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文组装单测：拼接格式、分数排序、max-context-chars 截断 + truncated 标记、
 * chunkIds 与实际进入上下文的分块一一对应。
 */
class ContextAssemblerTest {

    private static EsHit hit(String chunkId, String titlePath, String content, double score) {
        return new EsHit(chunkId, chunkId.split("-c")[0], titlePath, null, 0,
                content.length(), content, score);
    }

    @Test
    void assemblesSectionsInScoreOrderWithContractFormat() {
        ContextAssembler assembler = new ContextAssembler(6000);
        List<RetrievalHit> hits = List.of(
                new RetrievalHit(1, hit("d1-c0001", "部署指南>磁盘告警", "磁盘告警处理步骤", 0.91), 0.91, true),
                new RetrievalHit(2, hit("d2-c0001", "研发规范>测试", "接口测试覆盖要求", 0.75), 0.75, true));

        Context context = assembler.assemble(hits);

        assertThat(context.truncated()).isFalse();
        assertThat(context.chunkIds()).containsExactly("d1-c0001", "d2-c0001");
        // 高分在前；格式「【文档 N】titlePath\ncontent」
        assertThat(context.text()).startsWith("【文档 1】部署指南>磁盘告警\n磁盘告警处理步骤");
        assertThat(context.text()).contains("【文档 2】研发规范>测试\n接口测试覆盖要求");
        assertThat(context.text()).containsSubsequence("【文档 1】", "【文档 2】");
        assertThat(context.charCount()).isEqualTo(context.text().length());
    }

    @Test
    void inputOrderDoesNotAffectOutputOrder() {
        ContextAssembler assembler = new ContextAssembler(6000);
        List<RetrievalHit> hits = List.of(
                new RetrievalHit(1, hit("low-c0001", "低分", "低分内容", 0.40), 0.40, true),
                new RetrievalHit(2, hit("high-c0001", "高分", "高分内容", 0.90), 0.90, true));

        Context context = assembler.assemble(hits);

        assertThat(context.chunkIds()).containsExactly("high-c0001", "low-c0001");
        assertThat(context.text()).startsWith("【文档 1】高分");
    }

    @Test
    void truncatesBeyondMaxContextCharsAndMarksTruncated() {
        ContextAssembler assembler = new ContextAssembler(40);
        String longContent = "长".repeat(30);
        List<RetrievalHit> hits = List.of(
                new RetrievalHit(1, hit("a-c0001", "标题甲", longContent, 0.9), 0.9, true),
                new RetrievalHit(2, hit("b-c0001", "标题乙", "乙内容", 0.8), 0.8, true));

        Context context = assembler.assemble(hits);

        assertThat(context.truncated()).isTrue();
        assertThat(context.charCount()).isLessThanOrEqualTo(40);
        // 第一块完整进入，第二块被截断（或未进入），chunkIds 至少含第一块
        assertThat(context.text()).contains("【文档 1】标题甲");
        assertThat(context.chunkIds().get(0)).isEqualTo("a-c0001");
    }

    @Test
    void emptyPassedHitsYieldEmptyEvidence() {
        ContextAssembler assembler = new ContextAssembler(6000);
        Context context = assembler.assemble(List.of());
        assertThat(context.text()).isEqualTo("（无相关资料）");
        assertThat(context.chunkIds()).isEmpty();
        assertThat(context.truncated()).isFalse();
    }
}
