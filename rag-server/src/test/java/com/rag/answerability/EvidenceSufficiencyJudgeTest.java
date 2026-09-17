package com.rag.answerability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.RagProperties;
import com.rag.domain.enums.SessionRole;
import com.rag.llm.PromptAssembler;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * EvidenceSufficiencyJudge 单元测试（R4 / R4.1 稳定化）——
 *
 * <p>R4.1 并发模型：同步 ChatModel 调用 + Semaphore bulkhead。测试覆盖：</p>
 * <ol>
 *   <li>失败矩阵全覆盖：timeout / 模型异常 / 空输出 / 非 JSON / 缺 answerable /
 *       confidence 非法 / markdown 围栏容错。全部失败形态抛 JudgeUnavailableException
 *       且 message 带原因分类前缀（TIMEOUT / MODEL_ERROR / INVALID_RESPONSE）——
 *       Debug/Eval/日志据此归因；</li>
 *   <li>bulkhead 并发：maxConcurrentJudges 路并发真实执行（不串行）；
 *       超过容量时按 OVERLOADED 快速失败；</li>
 *   <li>对话历史：历史进入提示词且带"仅解析指代、不是证据"声明；空历史走无历史模板；</li>
 *   <li>证据不再截断：maxEvidenceChars 已删除，全文直达提示词；</li>
 *   <li>六条判定规则提示词防漂移。</li>
 * </ol>
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
        props.getRetrieval().getAnswerability().setMaxConcurrentJudges(2);
        props.getRetrieval().getAnswerability().setBulkheadWaitMs(50);
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

    // ---------- 失败矩阵（message 带原因分类） ----------

    @Test
    void langchain4jTimeoutExceptionClassifiedAsTimeout() {
        // 真实链路：langchain4j 同步调用在 read timeout 处抛
        // dev.langchain4j.exception.TimeoutException（底层 JDK HttpTimeoutException 被包装）
        // ——必须归类 TIMEOUT 而非 MODEL_ERROR；HTTP 层真实超时由 AnswerabilityFlowIT 覆盖
        when(chatModel.chat(any(List.class))).thenThrow(
                new dev.langchain4j.exception.TimeoutException("http read timeout"));
        assertThatThrownBy(() -> judge.judge("问题", "证据"))
                .isInstanceOf(EvidenceSufficiencyJudge.JudgeUnavailableException.class)
                .hasMessageStartingWith("TIMEOUT");
    }

    @Test
    void modelRuntimeErrorThrowsJudgeUnavailable() {
        when(chatModel.chat(any(List.class))).thenThrow(new RuntimeException("connection refused"));
        assertThatThrownBy(() -> judge.judge("问题", "证据"))
                .isInstanceOf(EvidenceSufficiencyJudge.JudgeUnavailableException.class)
                .hasMessageStartingWith("MODEL_ERROR")
                .hasMessageContaining("connection refused");
    }

    @Test
    void blankOutputThrows() {
        stubModel("   ");
        assertThatThrownBy(() -> judge.judge("问题", "证据"))
                .isInstanceOf(EvidenceSufficiencyJudge.JudgeUnavailableException.class)
                .hasMessageContaining("INVALID_RESPONSE")
                .hasMessageContaining("空内容");
    }

    @Test
    void nonJsonOutputThrows() {
        stubModel("这些证据不足以回答该问题。");
        assertThatThrownBy(() -> judge.judge("问题", "证据"))
                .isInstanceOf(EvidenceSufficiencyJudge.JudgeUnavailableException.class)
                .hasMessageContaining("INVALID_RESPONSE");
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

    // ---------- bulkhead 并发（R4.1 核心：不再单线程串行） ----------

    /** 并发请求数 ≤ maxConcurrentJudges 时真实并行：总耗时 ≈ 单次耗时而非 N 倍。 */
    @Test
    void concurrentJudgesRunInParallel() throws Exception {
        AtomicLong active = new AtomicLong();
        AtomicLong maxObserved = new AtomicLong();
        when(chatModel.chat(any(List.class))).thenAnswer(inv -> {
            long now = active.incrementAndGet();
            maxObserved.accumulateAndGet(now, Math::max);
            Thread.sleep(300); // 模拟模型耗时：若串行 4 次 = 1.2s，并行 ≈ 0.3s
            active.decrementAndGet();
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("{\"answerable\": true, \"confidence\": 0.5, \"reason\": \"x\"}"))
                    .build();
        });
        props.getRetrieval().getAnswerability().setMaxConcurrentJudges(4);
        EvidenceSufficiencyJudge parallelJudge = new EvidenceSufficiencyJudge(chatModel, props, new ObjectMapper());

        ExecutorService pool = Executors.newFixedThreadPool(4);
        long start = System.currentTimeMillis();
        for (int i = 0; i < 4; i++) {
            pool.submit(() -> parallelJudge.judge("问题", "证据"));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        long elapsed = System.currentTimeMillis() - start;

        // 真实并发观测：同时活跃数 ≥ 2（串行实现恒为 1）
        assertThat(maxObserved.get()).as("应存在并发执行（同时活跃 ≥ 2）").isGreaterThanOrEqualTo(2);
        // 耗时护栏：并行 ≈ 300ms+调度；串行会 ≥ 1200ms。给 900ms 上限防 CI 抖动误报
        assertThat(elapsed).as("4 路并发不应串行排队（elapsed=%dms）", elapsed).isLessThan(900);
    }

    /** 超过容量：快速失败 OVERLOADED，且不打扰模型（不发请求）。 */
    @Test
    void bulkheadExhaustionFailsFastWithOverloaded() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong calls = new AtomicLong();
        when(chatModel.chat(any(List.class))).thenAnswer(inv -> {
            calls.incrementAndGet();
            release.await(); // 占住全部许可
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("{\"answerable\": true, \"confidence\": 0.5, \"reason\": \"x\"}"))
                    .build();
        });
        props.getRetrieval().getAnswerability().setMaxConcurrentJudges(2);
        props.getRetrieval().getAnswerability().setBulkheadWaitMs(50);
        EvidenceSufficiencyJudge fullJudge = new EvidenceSufficiencyJudge(chatModel, props, new ObjectMapper());

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch occupied = new CountDownLatch(2);
        List<java.util.concurrent.Future<?>> jobs = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 2; i++) {
            jobs.add(pool.submit(() -> {
                occupied.countDown();
                fullJudge.judge("问题", "证据");
            }));
        }
        assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100); // 确保前两个请求已进入模型调用（占满许可）

        // 第 3 个请求：容量耗尽 → OVERLOADED 快速失败，且未发起模型调用
        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> fullJudge.judge("问题", "证据"))
                .isInstanceOf(EvidenceSufficiencyJudge.JudgeUnavailableException.class)
                .hasMessageStartingWith("OVERLOADED");
        assertThat(System.currentTimeMillis() - start).isLessThan(1000);
        assertThat(calls.get()).as("被拒请求不应发起模型调用").isEqualTo(2);

        release.countDown();
        for (java.util.concurrent.Future<?> job : jobs) {
            job.get(5, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
    }

    // ---------- 对话历史（R4.1 FOLLOW_UP 指代解析） ----------

    @Test
    void historyGoesIntoPromptWithNonEvidenceDisclaimer() {
        AtomicReference<String> userText = new AtomicReference<>();
        when(chatModel.chat(any(List.class))).thenAnswer(inv -> {
            List<ChatMessage> messages = (List<ChatMessage>) inv.getArgument(0);
            userText.set(((UserMessage) messages.get(messages.size() - 1)).singleText());
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("{\"answerable\": true, \"confidence\": 0.5, \"reason\": \"x\"}"))
                    .build();
        });
        List<PromptAssembler.HistoryTurn> history = List.of(
                new PromptAssembler.HistoryTurn(SessionRole.USER, "ES 洪泛水位是多少？"),
                new PromptAssembler.HistoryTurn(SessionRole.ASSISTANT, "97% 磁盘水位。"));
        judge.judge("那怎么解除只读块？", history, "证据全文");

        String prompt = userText.get();
        assertThat(prompt).contains("对话历史");
        assertThat(prompt).contains("仅用于理解当前问题中的指代");
        assertThat(prompt).contains("不构成回答依据");
        assertThat(prompt).contains("ES 洪泛水位是多少？");
        assertThat(prompt).contains("97% 磁盘水位。");
        assertThat(prompt).contains("【候选证据】").contains("证据全文");
        assertThat(prompt).contains("【当前问题】").contains("那怎么解除只读块？");
    }

    @Test
    void emptyHistoryUsesPlainTemplate() {
        AtomicReference<String> userText = new AtomicReference<>();
        when(chatModel.chat(any(List.class))).thenAnswer(inv -> {
            List<ChatMessage> messages = (List<ChatMessage>) inv.getArgument(0);
            userText.set(((UserMessage) messages.get(messages.size() - 1)).singleText());
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("{\"answerable\": true, \"confidence\": 0.5, \"reason\": \"x\"}"))
                    .build();
        });
        judge.judge("问题", List.of(), "证据");
        assertThat(userText.get()).doesNotContain("对话历史");
        assertThat(userText.get()).contains("【候选证据】").contains("【当前问题】");
    }

    /** R4.1 §六/§七：证据不再按字符截断——Context 全文直达 Judge（截断交给 ContextAssembler 的分块级策略）。 */
    @Test
    void evidencePassedInFullWithoutMidChunkTruncation() {
        AtomicReference<String> userText = new AtomicReference<>();
        when(chatModel.chat(any(List.class))).thenAnswer(inv -> {
            List<ChatMessage> messages = (List<ChatMessage>) inv.getArgument(0);
            userText.set(((UserMessage) messages.get(messages.size() - 1)).singleText());
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("{\"answerable\": true, \"confidence\": 0.5, \"reason\": \"x\"}"))
                    .build();
        });
        String fullEvidence = "块A：" + "长".repeat(3000) + "\n块B：" + "尾".repeat(3000);
        judge.judge("问题", fullEvidence);
        // 旧实现 maxEvidenceChars=4000 会把"块B"从中间切掉；新实现全文可见
        assertThat(userText.get()).contains(fullEvidence);
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
            // R6-B：核心定义由"完整、明确地回答"升级为"完整、可靠、不过度推断"
            assertThat(prompt).contains("完整、可靠、不过度推断");
            // R4.1：历史非证据声明钉进系统提示词
            assertThat(prompt).contains("仅用于理解问题中的指代");
        }).doesNotThrowAnyException();
    }

    /**
     * R6-B：判定核心定义收紧——"相关 ≠ 充分"、充分性三补充规则（A 派生推理
     * 禁止 / B yes-no 反驳即充分 / C 组合≠推断）必须钉进系统提示词（防漂移）。
     */
    @Test
    void systemPromptContainsR6BSufficiencySemantics() {
        assertThatCode(() -> {
            java.lang.reflect.Field f = EvidenceSufficiencyJudge.class.getDeclaredField("SYSTEM_INSTRUCTION");
            f.setAccessible(true);
            String prompt = (String) f.get(null);
            // 核心定义：RELEVANT ≠ SUFFICIENT
            assertThat(prompt).contains("相关不等于充分");
            assertThat(prompt).contains("完整、可靠、不过度推断");
            // A. 派生推理禁止（算术/聚合/占比/补全/外部常识/因果）
            assertThat(prompt).contains("禁止派生推理");
            assertThat(prompt).contains("算术运算").contains("聚合").contains("占比")
                    .contains("补全").contains("常识");
            // B. yes/no 修正型：证据明确反驳命题即足以回答"不是"
            assertThat(prompt).contains("是/否型问题的反驳即充分");
            assertThat(prompt).contains("相反的事实");
            // C. 多块组合允许，组合外的未陈述新结论禁止
            assertThat(prompt).contains("证据组合与推断的边界");
            assertThat(prompt).contains("多个证据分块可以组合使用");
        }).doesNotThrowAnyException();
    }
}
