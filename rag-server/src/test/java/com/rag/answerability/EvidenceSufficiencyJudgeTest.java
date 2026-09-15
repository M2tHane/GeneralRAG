package com.rag.answerability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.RagProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * EvidenceSufficiencyJudge 单元测试（R4）——失败矩阵全覆盖：
 * timeout / 模型异常 / 空输出 / 非 JSON / 缺 answerable / confidence 非法 /
 * markdown 围栏容错 / 正常 true 与 false。全部失败形态必须抛
 * JudgeUnavailableException（不猜默认值），由 AnswerabilityPolicy 统一降级。
 */
@ExtendWith(MockitoExtension.class)
class EvidenceSufficiencyJudgeTest {

    @Mock
    private ChatModel chatModel;

    private RagProperties props;
    private EvidenceSufficiencyJudge judge;

    @BeforeEach
    void setUp() {
        props = new RagProperties();
        props.getRetrieval().getAnswerability().setJudgeTimeoutSeconds(1);
        props.getRetrieval().getAnswerability().setMaxEvidenceChars(4000);
        judge = new EvidenceSufficiencyJudge(chatModel, props, new ObjectMapper());
    }

    private void stubModel(String output) {
        when(chatModel.chat(any(List.class))).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<ChatMessage> messages = (List<ChatMessage>) inv.getArgument(0);
            return ChatResponse.builder().aiMessage(AiMessage.from(output)).build();
        });
    }

    // ---------- 正常路径 ----------

    @Test
    void parsesAcceptResult() {
        stubModel("{\"answerable\": true, \"confidence\": 0.92, \"reason\": \"证据明确给出默认值\"}");
        EvidenceSufficiencyJudge.JudgeResult r = judge.judge("问题", "证据");
        assertThat(r.answerable()).isTrue();
        assertThat(r.confidence()).isEqualTo(0.92);
        assertThat(r.reason()).isEqualTo("证据明确给出默认值");
    }

    @Test
    void parsesRefuseResult() {
        stubModel("{\"answerable\": false, \"confidence\": 0.96, \"reason\": \"证据没有主从复制内容\"}");
        assertThat(judge.judge("问题", "证据").answerable()).isFalse();
    }

    /** 容错：markdown 围栏 + JSON 前后说明文字。 */
    @Test
    void toleratesMarkdownFenceAndSurroundingText() {
        stubModel("好的，我的判断如下：\n```json\n{\"answerable\": false, \"confidence\": 0.9, \"reason\": \"x\"}\n```\n以上。");
        assertThat(judge.judge("问题", "证据").answerable()).isFalse();
    }

    @Test
    void toleratesMissingReason() {
        stubModel("{\"answerable\": true, \"confidence\": 0.8}");
        assertThat(judge.judge("问题", "证据").reason()).isEmpty();
    }

    // ---------- 失败矩阵 ----------

    @Test
    void timeoutThrowsJudgeUnavailable() {
        when(chatModel.chat(any(List.class))).thenAnswer(inv -> {
            Thread.sleep(3000); // 超过 1s 阈值
            return ChatResponse.builder().aiMessage(AiMessage.from("{}")).build();
        });
        assertThatThrownBy(() -> judge.judge("问题", "证据"))
                .isInstanceOf(EvidenceSufficiencyJudge.JudgeUnavailableException.class)
                .hasMessageContaining("超时");
    }

    @Test
    void modelRuntimeErrorThrowsJudgeUnavailable() {
        when(chatModel.chat(any(List.class))).thenThrow(new RuntimeException("connection refused"));
        assertThatThrownBy(() -> judge.judge("问题", "证据"))
                .isInstanceOf(EvidenceSufficiencyJudge.JudgeUnavailableException.class)
                .hasMessageContaining("调用失败");
    }

    @Test
    void blankOutputThrows() {
        stubModel("   ");
        assertThatThrownBy(() -> judge.judge("问题", "证据"))
                .isInstanceOf(EvidenceSufficiencyJudge.JudgeUnavailableException.class)
                .hasMessageContaining("空内容");
    }

    @Test
    void nonJsonOutputThrows() {
        stubModel("这些证据不足以回答该问题。");
        assertThatThrownBy(() -> judge.judge("问题", "证据"))
                .isInstanceOf(EvidenceSufficiencyJudge.JudgeUnavailableException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void missingAnswerableThrows() {
        stubModel("{\"confidence\": 0.9, \"reason\": \"x\"}");
        assertThatThrownBy(() -> judge.judge("问题", "证据"))
                .isInstanceOf(EvidenceSufficiencyJudge.JudgeUnavailableException.class)
                .hasMessageContaining("answerable");
    }

    @Test
    void invalidAnswerableThrows() {
        stubModel("{\"answerable\": \"yes\", \"confidence\": 0.9}");
        assertThatThrownBy(() -> judge.judge("问题", "证据"))
                .isInstanceOf(EvidenceSufficiencyJudge.JudgeUnavailableException.class)
                .hasMessageContaining("answerable");
    }

    @Test
    void invalidConfidenceThrows() {
        stubModel("{\"answerable\": true, \"confidence\": 1.5}");
        assertThatThrownBy(() -> judge.judge("问题", "证据"))
                .isInstanceOf(EvidenceSufficiencyJudge.JudgeUnavailableException.class)
                .hasMessageContaining("confidence");
    }

    // ---------- 输入截断与提示词 ----------

    @Test
    void evidenceTruncatedToMaxChars() {
        props.getRetrieval().getAnswerability().setMaxEvidenceChars(200);
        AtomicLong capturedLen = new AtomicLong();
        when(chatModel.chat(any(List.class))).thenAnswer(inv -> {
            List<ChatMessage> messages = (List<ChatMessage>) inv.getArgument(0);
            String userText = messages.get(messages.size() - 1).toString();
            capturedLen.set(userText.length());
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("{\"answerable\": true, \"confidence\": 0.5, \"reason\": \"x\"}"))
                    .build();
        });
        judge.judge("问题", "长".repeat(10_000));
        assertThat(capturedLen.get()).isLessThan(600); // 200 证据 + 模板/包装
    }

    /** 判定规则 6 条语义已写进系统提示词（防提示词漂移）。 */
    @Test
    void systemPromptContainsSixRules() {
        assertThatCode(() -> {
            java.lang.reflect.Field f = EvidenceSufficiencyJudge.class.getDeclaredField("SYSTEM_INSTRUCTION");
            f.setAccessible(true);
            String prompt = (String) f.get(null);
            assertThat(prompt).contains("不是在判断内容是否与问题相关");
            assertThat(prompt).contains("只回答了问题的一部分");
            assertThat(prompt).contains("同一产品的不同参数");
            assertThat(prompt).contains("同一技术的不同功能");
            assertThat(prompt).contains("证据之外的知识");
            assertThat(prompt).contains("完整、明确地回答");
        }).doesNotThrowAnyException();
    }
}
