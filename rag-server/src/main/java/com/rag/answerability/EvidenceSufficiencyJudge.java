package com.rag.answerability;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.RagProperties;
import com.rag.domain.exception.DomainException;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 证据充分性判定器（R4）：只回答一个问题——
 *
 * <blockquote>仅根据提供的证据，是否已经有足够的信息<b>完整、明确地</b>回答用户问题？</blockquote>
 *
 * <p><b>职责边界</b>：本类不做拒答决策（那是 {@link AnswerabilityPolicy} 的事），
 * 不拼上下文（调用方传入与生成同源的 Context 文本），不碰生成 Prompt
 * （Judge 的 reason/confidence 绝不进入生成模型上下文，避免自我强化）。
 * 输出 JSON 结构化结果，解析失败/超时/模型异常统一抛
 * {@link JudgeUnavailableException}，由调用方决定降级语义。</p>
 *
 * <p><b>为什么用阻塞 ChatModel 而不是 Streaming</b>：Judge 只需要短 JSON 输出，
 * 无流式需求；且判定发生在 SSE 流开启后的同步段落，直接阻塞等待即可。
 * 超时用独立线程池 + Future 实现（不占用 SSE 线程做长等待的中断控制）。</p>
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

            只输出一个 JSON 对象，不要输出其他任何文字：
            {"answerable": true 或 false, "confidence": 0到1的小数, "reason": "一句话说明证据给了什么或缺什么"}""";

    static final String USER_TEMPLATE = """
            【问题】
            %s

            【候选证据】
            %s

            这些证据是否足以完整、明确地回答上述问题？""";

    /** Judge 失败的统一异常（调用方决定降级语义；本类不做策略）。 */
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
    private final ExecutorService judgeExecutor;

    public EvidenceSufficiencyJudge(@Qualifier("judgeChatModel") ChatModel judgeModel,
                                    RagProperties ragProperties,
                                    ObjectMapper objectMapper) {
        this.judgeModel = judgeModel;
        this.cfg = ragProperties.getRetrieval().getAnswerability();
        this.objectMapper = objectMapper;
        // 单例线程池足够：判定在问答/评测路径上串行发生，并发由上层 SSE/eval 闸门约束
        this.judgeExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "evidence-judge");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 执行判定。
     *
     * @param question     用户问题
     * @param evidenceText 与生成同源的上下文全文（{@link com.rag.retrieval.model.Context#text()}）
     * @return 结构化判定结果
     * @throws JudgeUnavailableException 超时 / 模型异常 / 响应非 JSON / 字段缺失或非法
     */
    public JudgeResult judge(String question, String evidenceText) {
        List<ChatMessage> messages = List.of(
                SystemMessage.from(SYSTEM_INSTRUCTION),
                UserMessage.from(String.format(USER_TEMPLATE,
                        question == null ? "" : question,
                        truncate(evidenceText))));
        long start = System.currentTimeMillis();
        try {
            Future<String> future = judgeExecutor.submit(() -> judgeModel.chat(messages).aiMessage().text());
            String raw = future.get(cfg.getJudgeTimeoutSeconds(), TimeUnit.SECONDS);
            JudgeResult result = parse(raw);
            log.debug("Judge 完成 latencyMs={} answerable={} confidence={}",
                    System.currentTimeMillis() - start, result.answerable(), result.confidence());
            return result;
        } catch (java.util.concurrent.TimeoutException e) {
            throw new JudgeUnavailableException(
                    "Judge " + cfg.getJudgeTimeoutSeconds() + " 秒超时", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() instanceof DomainException ? e.getCause() : e.getCause();
            throw new JudgeUnavailableException(
                    "Judge 模型调用失败：" + (cause == null ? "未知" : cause.getMessage()), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JudgeUnavailableException("Judge 调用被中断", e);
        }
    }

    /**
     * 解析 Judge 输出。宽容处理 markdown 围栏（部分模型爱包 ```json），
     * 但 answerable/confidence 缺失或非法一律视为解析失败——不猜默认值，
     * 否则一次解析歧义就会变成静默的错误拒答/错误回答。
     */
    JudgeResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new JudgeUnavailableException("Judge 返回空内容");
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
            throw new JudgeUnavailableException("Judge 返回不含 JSON 对象：" + preview(text));
        }
        try {
            JsonNode node = objectMapper.readTree(text.substring(braceStart, braceEnd + 1));
            JsonNode answerable = node.path("answerable");
            JsonNode confidence = node.path("confidence");
            if (!answerable.isBoolean()) {
                throw new JudgeUnavailableException("Judge 输出缺少合法 answerable 字段：" + preview(text));
            }
            if (!confidence.isNumber() || confidence.asDouble() < 0.0 || confidence.asDouble() > 1.0) {
                throw new JudgeUnavailableException("Judge 输出缺少合法 confidence 字段：" + preview(text));
            }
            String reason = node.path("reason").isTextual()
                    ? node.path("reason").asText() : "";
            return new JudgeResult(answerable.asBoolean(), confidence.asDouble(), reason);
        } catch (JudgeUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new JudgeUnavailableException("Judge 输出 JSON 解析失败：" + e.getMessage(), e);
        }
    }

    private String truncate(String evidence) {
        if (evidence == null) {
            return "";
        }
        int max = cfg.getMaxEvidenceChars();
        return evidence.length() <= max ? evidence : evidence.substring(0, max);
    }

    private static String preview(String text) {
        String stripped = text.replaceAll("\\s+", " ");
        return stripped.length() <= 120 ? stripped : stripped.substring(0, 120) + "…";
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        judgeExecutor.shutdownNow();
    }
}
