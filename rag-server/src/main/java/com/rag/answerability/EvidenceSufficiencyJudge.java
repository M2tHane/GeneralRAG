package com.rag.answerability;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.RagProperties;
import com.rag.domain.exception.DomainException;
import com.rag.llm.PromptAssembler;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 证据充分性判定器（R4 / R4.1 稳定化）：只回答一个问题——
 *
 * <blockquote>仅根据提供的证据，是否已经有足够的信息<b>完整、明确地</b>回答用户问题？</blockquote>
 *
 * <p><b>职责边界</b>：本类不做拒答决策（那是 {@link AnswerabilityPolicy} 的事），
 * 不拼上下文（调用方传入与生成同源的 Context 文本），不碰生成 Prompt
 * （Judge 的 reason/confidence 绝不进入生成模型上下文，避免自我强化）。
 * 输出 JSON 结构化结果，解析失败/超时/模型异常/容量耗尽统一抛
 * {@link JudgeUnavailableException}（message 带原因分类 TIMEOUT / OVERLOADED /
 * MODEL_ERROR / INVALID_RESPONSE），由调用方决定降级语义。</p>
 *
 * <p><b>R4.1 并发模型（取代单线程 executor + Future）</b>：</p>
 *
 * <pre>
 * Chat 请求线程 → AnswerabilityPolicy → Semaphore bulkhead → 同步 ChatModel 调用
 * </pre>
 *
 * <ul>
 *   <li><b>同步调用</b>：超时由模型 HTTP 客户端硬性执行（read timeout =
 *       {@code judge-timeout-seconds}），到点抛 TimeoutException——不存在
 *       "上层 Future 已放弃、底层请求仍在跑"的假取消，调用线程阻塞上界
 *       = 超时值本身；</li>
 *   <li><b>bulkhead 闸门</b>：并发判定数上限 {@code max-concurrent-judges}，
 *       容量耗尽时最多等待 {@code bulkhead-wait-ms}，仍拿不到名额按
 *       OVERLOADED 快速失败——一个慢 Judge 不会把其他请求拖进无限排队；
 *       多个请求在信号量许可内<b>真实并发执行</b>（单线程 executor 的
 *       串行瓶颈已移除）。</li>
 * </ul>
 *
 * <p><b>为什么用阻塞 ChatModel 而不是 Streaming</b>：Judge 只需要短 JSON 输出，
 * 无流式需求；且判定发生在 SSE 流开启后的同步段落，直接阻塞等待即可。</p>
 */
@Component
public class EvidenceSufficiencyJudge {

    private static final Logger log = LoggerFactory.getLogger(EvidenceSufficiencyJudge.class);

    /**
     * Judge 提示词核心语义（R4 需求 §七）：
     * 不是判断"相关"，而是判断"证据是否足以完整、明确地回答"。
     * 六条细则逐字对应 CONFUSABLE / PARTIAL_EVIDENCE 的 Bad Case 形态。
     */
    static final String SYSTEM_INSTRUCTION = """
            你是知识库问答系统的证据充分性判定器。你不是在判断内容是否与问题相关，
            而是要判断：仅根据提供的证据，是否已经有足够的信息完整、明确地回答用户问题。

            判定规则：
            1. 内容与问题相关，但证据中没有问题的答案 → answerable=false
            2. 证据只回答了问题的一部分（问题要求多项，证据只覆盖部分）→ answerable=false
            3. 同一产品的不同参数（如问 maxAge 而证据只有 maxLifetime）→ answerable=false
            4. 同一技术的不同功能（如问集群迁移而证据只有持久化配置）→ answerable=false
            5. 需要依赖证据之外的知识（经验、常识、外部资料）才能补全答案 → answerable=false
            6. 可以从证据直接得出问题的完整答案 → answerable=true

            对话历史（如提供）仅用于理解问题中的指代（它/这个/该情况），不能作为知识事实依据。

            只输出一个 JSON 对象，不要输出其他任何文字：
            {"answerable": true 或 false, "confidence": 0到1的小数, "reason": "一句话说明证据给了什么或缺什么"}""";

    static final String USER_TEMPLATE = """
            【候选证据】
            %s

            【当前问题】
            %s

            这些证据是否足以完整、明确地回答上述当前问题？""";

    /** 有对话历史时的用户消息模板：历史独立成段且声明用途（仅解析指代，不是证据）。 */
    static final String USER_TEMPLATE_WITH_HISTORY = """
            【对话历史】（仅用于理解当前问题中的指代，不构成回答依据）
            %s

            【候选证据】
            %s

            【当前问题】
            %s

            这些证据是否足以完整、明确地回答上述当前问题？""";

    /** Judge 失败的统一异常（message 前缀为原因分类；调用方决定降级语义）。 */
    public static final class JudgeUnavailableException extends RuntimeException {
        public JudgeUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public JudgeUnavailableException(String message) {
            super(message);
        }
    }

    /** Judge 结构化输出。 */
    public record JudgeResult(boolean answerable, double confidence, String reason) {
    }

    private final ChatModel judgeModel;
    private final RagProperties.Answerability cfg;
    private final ObjectMapper objectMapper;
    /** 并发闸门：permits = max-concurrent-judges（R4.1 bulkhead，取代单线程 executor）。 */
    private final Semaphore bulkhead;

    public EvidenceSufficiencyJudge(@Qualifier("judgeChatModel") ChatModel judgeModel,
                                    RagProperties ragProperties,
                                    ObjectMapper objectMapper) {
        this.judgeModel = judgeModel;
        this.cfg = ragProperties.getRetrieval().getAnswerability();
        this.objectMapper = objectMapper;
        this.bulkhead = new Semaphore(Math.max(1, cfg.getMaxConcurrentJudges()), false);
    }

    /**
     * 执行判定（无历史版本：判定输入只有问题与证据）。
     *
     * @param question     用户当前问题
     * @param evidenceText 与生成同源的上下文全文（{@link com.rag.retrieval.model.Context#text()}，不截断）
     * @return 结构化判定结果
     * @throws JudgeUnavailableException 容量耗尽(OVERLOADED) / 超时(TIMEOUT) /
     *                                   模型异常(MODEL_ERROR) / 响应非法(INVALID_RESPONSE)
     */
    public JudgeResult judge(String question, String evidenceText) {
        return judge(question, List.of(), evidenceText);
    }

    /**
     * 执行判定（R4.1：支持对话历史，用于解析 FOLLOW_UP 问题的指代）。
     *
     * <p>历史<b>只进提示词、不是证据</b>：提示词明确"对话历史仅用于理解当前问题中的
     * 指代，不能作为知识事实依据"；判定的答案仍只能来自 evidenceText。</p>
     *
     * @param question     用户当前问题
     * @param history      会话历史（时间正序；仅用于解析指代；可为空）
     * @param evidenceText 与生成同源的上下文全文（Context.text，不截断）
     * @return 结构化判定结果
     * @throws JudgeUnavailableException 容量耗尽(OVERLOADED) / 超时(TIMEOUT) /
     *                                   模型异常(MODEL_ERROR) / 响应非法(INVALID_RESPONSE)
     */
    public JudgeResult judge(String question, List<PromptAssembler.HistoryTurn> history, String evidenceText) {
        List<ChatMessage> messages = List.of(
                SystemMessage.from(SYSTEM_INSTRUCTION),
                UserMessage.from(buildUserPrompt(question, history, evidenceText)));
        long start = System.currentTimeMillis();
        // 1. bulkhead：拿不到名额不无限排队，OVERLOADED 快速降级
        boolean acquired;
        try {
            acquired = bulkhead.tryAcquire(cfg.getBulkheadWaitMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JudgeUnavailableException("OVERLOADED: Judge 并发闸门等待被中断", e);
        }
        if (!acquired) {
            log.debug("Judge 容量耗尽 maxConcurrentJudges={} waitMs={}",
                    cfg.getMaxConcurrentJudges(), cfg.getBulkheadWaitMs());
            throw new JudgeUnavailableException(
                    "OVERLOADED: Judge 并发已达上限（" + cfg.getMaxConcurrentJudges()
                            + "），等待 " + cfg.getBulkheadWaitMs() + "ms 未获得名额");
        }
        // 2. 同步调用：HTTP read timeout = judge-timeout-seconds，硬超时在模型客户端内部发生
        try {
            String raw = judgeModel.chat(messages).aiMessage().text();
            JudgeResult result = parse(raw);
            log.debug("Judge 完成 latencyMs={} answerable={} confidence={}",
                    System.currentTimeMillis() - start, result.answerable(), result.confidence());
            return result;
        } catch (JudgeUnavailableException e) {
            throw e; // parse 阶段已归类 INVALID_RESPONSE，不再二次包装
        } catch (DomainException e) {
            // langchain4j 抛出的 HttpTimeoutException 在上层被包装为模型异常；
            // 按异常链里的超时类型归类为 TIMEOUT（其余保持 MODEL_ERROR）
            if (isTimeout(e)) {
                throw new JudgeUnavailableException(
                        "TIMEOUT: Judge " + cfg.getJudgeTimeoutSeconds() + " 秒超时", e);
            }
            throw new JudgeUnavailableException(
                    "MODEL_ERROR: Judge 模型调用失败：" + e.getMessage(), e);
        } catch (RuntimeException e) {
            if (isTimeout(e)) {
                throw new JudgeUnavailableException(
                        "TIMEOUT: Judge " + cfg.getJudgeTimeoutSeconds() + " 秒超时", e);
            }
            throw new JudgeUnavailableException(
                    "MODEL_ERROR: Judge 模型调用失败：" + e.getMessage(), e);
        } finally {
            // 无论成功/失败/中断都必须释放许可，否则一次调用泄漏就永久缩减容量
            bulkhead.release();
        }
    }

    /** 组装用户提示词（含历史时走独立模板，历史区有明确的非证据声明）。 */
    static String buildUserPrompt(String question, List<PromptAssembler.HistoryTurn> history, String evidenceText) {
        List<PromptAssembler.HistoryTurn> turns = history == null ? List.of() : history;
        if (turns.isEmpty()) {
            return String.format(USER_TEMPLATE,
                    evidenceText == null ? "" : evidenceText,
                    question == null ? "" : question);
        }
        StringBuilder historyText = new StringBuilder();
        for (PromptAssembler.HistoryTurn turn : turns) {
            historyText.append(turn.role() == com.rag.domain.enums.SessionRole.USER ? "用户：" : "助手：")
                    .append(turn.content() == null ? "" : turn.content())
                    .append('\n');
        }
        return String.format(USER_TEMPLATE_WITH_HISTORY,
                historyText.toString().stripTrailing(),
                evidenceText == null ? "" : evidenceText,
                question == null ? "" : question);
    }

    /** 判定异常链中是否为超时（langchain4j TimeoutException / JDK HttpTimeoutException）。 */
    private static boolean isTimeout(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof dev.langchain4j.exception.TimeoutException
                    || cur instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 解析 Judge 输出。宽容处理 markdown 围栏（部分模型爱包 ```json），
     * 但 answerable/confidence 缺失或非法一律视为解析失败——不猜默认值，
     * 否则一次解析歧义就会变成静默的错误拒答/错误回答。
     */
    JudgeResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new JudgeUnavailableException("INVALID_RESPONSE: Judge 返回空内容");
        }
        String text = raw.strip();
        // 剥离 ```json ... ``` / ``` ... ``` 围栏
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "").strip();
        }
        // 容忍 JSON 前后混入的说明文字：截取首个 { 到末个 }
        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart < 0 || braceEnd <= braceStart) {
            throw new JudgeUnavailableException("INVALID_RESPONSE: Judge 返回不含 JSON 对象：" + preview(text));
        }
        try {
            JsonNode node = objectMapper.readTree(text.substring(braceStart, braceEnd + 1));
            JsonNode answerable = node.path("answerable");
            JsonNode confidence = node.path("confidence");
            if (!answerable.isBoolean()) {
                throw new JudgeUnavailableException(
                        "INVALID_RESPONSE: Judge 输出缺少合法 answerable 字段：" + preview(text));
            }
            if (!confidence.isNumber() || confidence.asDouble() < 0.0 || confidence.asDouble() > 1.0) {
                throw new JudgeUnavailableException(
                        "INVALID_RESPONSE: Judge 输出缺少合法 confidence 字段：" + preview(text));
            }
            String reason = node.path("reason").isTextual()
                    ? node.path("reason").asText() : "";
            return new JudgeResult(answerable.asBoolean(), confidence.asDouble(), reason);
        } catch (JudgeUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new JudgeUnavailableException("INVALID_RESPONSE: Judge 输出 JSON 解析失败：" + e.getMessage(), e);
        }
    }

    private static String preview(String text) {
        String stripped = text.replaceAll("\\s+", " ");
        return stripped.length() <= 120 ? stripped : stripped.substring(0, 120) + "…";
    }
}
