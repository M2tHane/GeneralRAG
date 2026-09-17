package com.rag.llm;

import java.util.List;
import java.util.stream.IntStream;

import com.rag.domain.enums.SessionRole;
import com.rag.retrieval.model.Context;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prompt 组装单测：双区隔离（历史内容不出现在证据区标记之下）、
 * max-history-messages 截断、系统指令三句核心约束逐字存在（QA-3/QA-6）。
 */
class PromptAssemblerTest {

    private final PromptAssembler assembler = new PromptAssembler(8);

    private static PromptAssembler.HistoryTurn turn(SessionRole role, String content) {
        return new PromptAssembler.HistoryTurn(role, content);
    }

    @Test
    void systemInstructionContainsThreeCoreRules() {
        List<ChatMessage> messages = assembler.build(
                "磁盘告警怎么处理？", List.of(), new Context("【文档 1】指南\n磁盘处理", 20, List.of("d-c0001"), false));

        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        String system = ((SystemMessage) messages.get(0)).text();
        assertThat(system).contains("仅依据文档证据回答");
        assertThat(system).contains("无法依据当前资料回答");
        assertThat(system).contains("不得编造引用来源");
        assertThat(system).contains("不构成回答依据"); // QA-3：历史非证据
    }

    /**
     * R6-B.1：生成系统指令必须是自洽规则——不再假设"证据充分已由前置判定确认"
     * （Judge fail-open 降级进生成时该前提不成立），同时锁住 evidence-composition
     * 与反推断语义（与 Judge 语义对齐，但独立自检）。
     */
    @Test
    void systemInstructionContainsSelfSufficientEvidenceRules() {
        List<ChatMessage> messages = assembler.build(
                "磁盘告警怎么处理？", List.of(), new Context("【文档 1】指南\n磁盘处理", 20, List.of("d-c0001"), false));

        String system = ((SystemMessage) messages.get(0)).text();
        // 错误前提必须删除：生成不得假设 Judge 一定成功确认过充分性
        assertThat(system).doesNotContain("已由前置判定确认");
        // 组合规则：多片段明确事实可组合；证据分散/简短/措辞不同 ≠ 不足
        assertThat(system).contains("分散在多个证据片段中");
        assertThat(system).contains("组合这些明确陈述的事实回答");
        assertThat(system).contains("不要仅因为证据简短、分散，或没有使用与问题完全相同的措辞而拒绝回答");
        // 反推断：禁算术/聚合/比例/补全/外部知识/因果推断
        assertThat(system).contains("只允许组合证据中明确陈述的事实");
        assertThat(system).contains("不得通过额外算术运算、跨行聚合、比例计算、缺失值补全、外部知识、未陈述的因果关系或其他推断生成证据中没有的新事实");
        // 组合 vs 推断边界：组织已陈述事实（含对照关系）不算推断（seq54 回归驱动）
        assertThat(system).contains("用\"因此\"\"所以\"等连接词组织证据中已明确陈述的事实");
        // 充分性自检保留：关键事实缺失仍允许（必须）拒答
        assertThat(system).contains("如果回答所需的关键事实确实缺失，则明确说明当前证据不足");
    }

    @Test
    void historyZoneAndEvidenceZoneAreStrictlySeparated() {
        List<PromptAssembler.HistoryTurn> history = List.of(
                turn(SessionRole.USER, "历史问题甲：服务器配置是多少"),
                turn(SessionRole.ASSISTANT, "历史回答乙：服务器配置是 8 核 16G"));

        List<ChatMessage> messages = assembler.build(
                "本次问题：磁盘告警怎么处理？", history,
                new Context("【文档 1】部署指南>磁盘\n磁盘告警处理步骤", 30, List.of("d-c0001"), false));

        // 结构：System + 2 条历史（USER/ASSISTANT 独立消息）+ 证据区 UserMessage
        assertThat(messages).hasSize(4);
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) messages.get(1)).singleText()).isEqualTo("历史问题甲：服务器配置是多少");
        assertThat(messages.get(2)).isInstanceOf(AiMessage.class);

        String evidenceMessage = ((UserMessage) messages.get(3)).singleText();
        int evidenceMarker = evidenceMessage.indexOf("【文档证据区】");
        assertThat(evidenceMarker).isGreaterThanOrEqualTo(0);
        // 历史内容绝不出现在证据区标记之下（QA-3 历史≠证据）
        String evidenceZone = evidenceMessage.substring(evidenceMarker);
        assertThat(evidenceZone).doesNotContain("服务器配置");
        assertThat(evidenceZone).contains("磁盘告警处理步骤");
        assertThat(evidenceZone).contains("本次问题：磁盘告警怎么处理？");
    }

    @Test
    void historyIsTruncatedToMaxHistoryMessages() {
        List<PromptAssembler.HistoryTurn> history = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> turn(SessionRole.USER, "旧问题" + i))
                .toList();

        List<ChatMessage> messages = assembler.build("当前问题", history, Context.empty());

        // System(1) + 最近 8 条历史 + 证据区(1)
        assertThat(messages).hasSize(1 + 8 + 1);
        String joined = messages.stream()
                .filter(m -> m instanceof UserMessage || m instanceof AiMessage)
                .map(m -> m instanceof UserMessage u ? u.singleText() : ((AiMessage) m).text())
                .reduce("", String::concat);
        assertThat(joined).contains("旧问题12").doesNotContain("旧问题1\n").doesNotContain("旧问题4");
    }

    @Test
    void emptyEvidenceUsesNoMaterialPlaceholder() {
        List<ChatMessage> messages = assembler.build("完全无关的问题", List.of(), Context.empty());
        String evidenceMessage = ((UserMessage) messages.get(messages.size() - 1)).singleText();
        assertThat(evidenceMessage).contains("【文档证据区】").contains("（无相关资料）");
    }
}
