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
     * R6-B：Judge ACCEPT 后生成不得自行软拒答——"证据充分已由前置判定确认，
     * 有直接依据就必须作答"的一致性约束必须钉进系统提示词（防漂移）。
     */
    @Test
    void systemInstructionContainsR6BSufficiencyConsistencyRule() {
        List<ChatMessage> messages = assembler.build(
                "磁盘告警怎么处理？", List.of(), new Context("【文档 1】指南\n磁盘处理", 20, List.of("d-c0001"), false));

        String system = ((SystemMessage) messages.get(0)).text();
        assertThat(system).contains("已由前置判定确认");
        assertThat(system).contains("必须依据证据作答");
        assertThat(system).contains("不要因为证据显得简短或零散而拒绝回答");
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
