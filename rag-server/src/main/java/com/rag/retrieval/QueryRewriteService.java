package com.rag.retrieval;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.RagProperties;
import com.rag.llm.PromptAssembler;
import com.rag.domain.enums.SessionRole;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * History-aware Query Rewriter（R6-C）：在检索前把「依赖对话历史的当前问题」
 * 改写为可脱离历史独立理解的检索查询，再进入 {@link RetrievalPipeline}。
 *
 * <p><b>职责边界</b>：只改检索查询。不做检索、不做判定、不碰生成 Prompt；
 * Judge 与 Generation 看到的用户问题仍是原始 current question + history
 * （rewritten query 不是用户实际提出的问题，绝不能覆盖它）。</p>
 *
 * <p><b>失败即回退（fail-open to original）</b>：rewrite 是检索增强而非链路必需，
 * 超时 / 模型异常 / 响应非法 / 改写为空 一律回退原始 current question，
 * 整个 QA 请求不因 rewrite 失败而失败。它与 Answerability Judge 是两个独立层：
 * rewrite 失败不产生任何 Judge degraded 语义。</p>
 *
 * <p><b>超时语义（同 Judge R4.1 设计）</b>：HTTP connect/read timeout =
 * {@code rag.retrieval.query-rewrite.timeout-seconds}，同步调用在模型侧硬超时，
 * 不存在"上层已放弃、底层仍在跑"的假取消；调用线程阻塞上界 = 超时值本身。</p>
 *
 * <p><b>结构化输出</b>：{@code {"rewritten": bool, "query": str[, "reason": str]}}。
 * rewritten=false 时 query 必须等于原始问题（防御：不等也回退原问题，不信任自由文本）。
 * reason 仅用于 debug/可观测，绝不进入检索或生成链路。</p>
 */
@Component
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    /**
     * Rewriter 系统指令（R6-C §5/§6）：任务是补全上下文，不是回答问题；
     * 禁止外部知识/新事实/新过滤条件/语义扩张。逐字锁定由
     * {@code QueryRewriteServiceTest} 防漂移断言守护。
     */
    static final String SYSTEM_INSTRUCTION = """
            你是 Retrieval Query Rewriter。你的任务不是回答问题，而是根据对话历史，
            把当前问题改写成一个可以脱离历史独立理解、适合用于知识库检索的查询。

            规则：
            1. 保留用户原始意图，不新增问题、不扩大用户意图；
            2. 只补充历史中明确出现的实体、参数名、产品名和指代对象（如"它/这个/前者/后者/该配置"）；
            3. 不回答问题，不使用外部知识，不推测历史中没有明确出现的信息；
            4. 不做算术运算，不引入任何历史中没有的新事实；
            5. 不增加新的过滤条件，不改变问题的语义；
            6. 如果当前问题本身已经完整、可以脱离历史独立理解，保持原问题不变（rewritten=false）；
            7. 输出尽量简洁：query 只包含改写后的查询本身，不写任何解释。""";

    static final String USER_TEMPLATE = """
            【对话历史】（时间正序，仅用于解析当前问题的指代）
            %s

            【当前问题】
            %s

            请按系统规则改写当前问题，只输出一个 JSON 对象，不要输出其他任何文字：
            {"rewritten": true 或 false, "query": "改写后的检索查询", "reason": "一句话说明补了什么指代（可省略）"}""";

    /** 改写失败统一异常（内部使用；对外永远表现为"回退原始查询"）。 */
    public enum RewriteFailureType { TIMEOUT, MODEL_ERROR, INVALID_RESPONSE }

    /** 改写结果（不可变；调用方据此决定检索用什么查询 + debug/trace 透出）。 */
    public record QueryRewriteResult(String originalQuery, String retrievalQuery,
                                     boolean rewritten, String reason,
                                     boolean degraded, RewriteFailureType failureType,
                                     long latencyMs) {

        /** 未改写（含失败回退）：retrievalQuery = originalQuery。 */
        public static QueryRewriteResult unchanged(String originalQuery, long latencyMs) {
            return new QueryRewriteResult(originalQuery, originalQuery, false, null, false, null, latencyMs);
        }

        /** 失败回退：retrievalQuery = originalQuery，degraded=true 供可观测。 */
        public static QueryRewriteResult fallback(String originalQuery, RewriteFailureType type,
                                                  String detail, long latencyMs) {
            return new QueryRewriteResult(originalQuery, originalQuery, false, detail, true, type, latencyMs);
        }
    }

    private final ChatModel rewriteModel;
    private final RagProperties.QueryRewrite cfg;
    private final ObjectMapper objectMapper;

    public QueryRewriteService(@Qualifier("queryRewriteChatModel") ChatModel rewriteModel,
                               RagProperties ragProperties,
                               ObjectMapper objectMapper) {
        this.rewriteModel = rewriteModel;
        this.cfg = ragProperties.getRetrieval().getQueryRewrite();
        this.objectMapper = objectMapper;
    }

    /**
     * 产出本次检索使用的查询。
     *
     * <p>触发条件（R6-C §3/§4）：history 为空 → 无指代可解析，直接跳过（零成本）；
     * history 非空 → 调 Rewriter，由模型判定当前问题是否已完整（保持原问题）。
     * 不在调用方堆中文指代词规则——误判"需要改写"的代价只是一次模型调用，
     * Rewriter 自身会返回 rewritten=false。</p>
     *
     * <p>任何失败都回退 originalQuery，绝不抛出——调用方（问答流/评测）无需感知。</p>
     */
    public QueryRewriteResult rewrite(String currentQuestion, List<PromptAssembler.HistoryTurn> history) {
        List<PromptAssembler.HistoryTurn> turns = history == null ? List.of() : history;
        if (!cfg.isEnabled() || turns.isEmpty()
                || currentQuestion == null || currentQuestion.isBlank()) {
            return QueryRewriteResult.unchanged(
                    currentQuestion == null ? "" : currentQuestion, 0);
        }
        long start = System.currentTimeMillis();
        try {
            String raw = rewriteModel.chat(buildMessages(currentQuestion, turns))
                    .aiMessage().text();
            long latency = System.currentTimeMillis() - start;
            QueryRewriteResult parsed = parse(currentQuestion, raw, latency);
            if (parsed.rewritten()) {
                log.info("QueryRewrite 改写完成 latencyMs={} query={}", latency, parsed.retrievalQuery());
            } else {
                log.debug("QueryRewrite 保持原问题 latencyMs={}", latency);
            }
            return parsed;
        } catch (RuntimeException e) {
            long latency = System.currentTimeMillis() - start;
            if (isTimeout(e)) {
                log.warn("QueryRewrite 超时（{}s），回退原始查询", cfg.getTimeoutSeconds());
                return QueryRewriteResult.fallback(currentQuestion, RewriteFailureType.TIMEOUT,
                        "rewrite timeout", latency);
            }
            log.warn("QueryRewrite 模型调用失败，回退原始查询：{}", e.getMessage());
            return QueryRewriteResult.fallback(currentQuestion, RewriteFailureType.MODEL_ERROR,
                    "rewrite model error: " + e.getMessage(), latency);
        }
    }

    /** 异常原因链中是否为超时（langchain4j TimeoutException / JDK HttpTimeoutException）。 */
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

    /** 组装消息（含历史时截取最近 maxHistoryTurns 轮；沿用项目 history window 约定）。 */
    List<ChatMessage> buildMessages(String currentQuestion, List<PromptAssembler.HistoryTurn> turns) {
        int from = Math.max(0, turns.size() - cfg.getMaxHistoryTurns());
        StringBuilder historyText = new StringBuilder();
        for (PromptAssembler.HistoryTurn turn : turns.subList(from, turns.size())) {
            historyText.append(turn.role() == SessionRole.USER ? "用户：" : "助手：")
                    .append(turn.content() == null ? "" : turn.content())
                    .append('\n');
        }
        return List.of(
                SystemMessage.from(SYSTEM_INSTRUCTION),
                UserMessage.from(String.format(USER_TEMPLATE,
                        historyText.toString().stripTrailing(),
                        currentQuestion)));
    }

    /**
     * 解析 Rewriter 输出（宽容 markdown 围栏，同 Judge parse 语义）：
     * rewritten 非布尔 / query 缺失或空白 / 超长 → INVALID_RESPONSE 回退原问题；
     * rewritten=false 时无论 query 写什么都不采用（防御性：以原始问题为准）。
     */
    QueryRewriteResult parse(String originalQuery, String raw, long latencyMs) {
        if (raw == null || raw.isBlank()) {
            return QueryRewriteResult.fallback(originalQuery, RewriteFailureType.INVALID_RESPONSE,
                    "rewrite empty response", latencyMs);
        }
        String text = raw.strip();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "").strip();
        }
        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart < 0 || braceEnd <= braceStart) {
            return QueryRewriteResult.fallback(originalQuery, RewriteFailureType.INVALID_RESPONSE,
                    "rewrite response has no JSON object", latencyMs);
        }
        try {
            JsonNode node = objectMapper.readTree(text.substring(braceStart, braceEnd + 1));
            JsonNode rewritten = node.path("rewritten");
            if (!rewritten.isBoolean()) {
                return QueryRewriteResult.fallback(originalQuery, RewriteFailureType.INVALID_RESPONSE,
                        "rewrite output missing boolean rewritten", latencyMs);
            }
            String reason = node.path("reason").isTextual() ? node.path("reason").asText() : null;
            if (!rewritten.asBoolean()) {
                return new QueryRewriteResult(originalQuery, originalQuery, false, reason, false, null, latencyMs);
            }
            JsonNode query = node.path("query");
            if (!query.isTextual() || query.asText().isBlank()) {
                return QueryRewriteResult.fallback(originalQuery, RewriteFailureType.INVALID_RESPONSE,
                        "rewritten=true but query missing/blank", latencyMs);
            }
            String rewrittenQuery = query.asText().strip();
            if (rewrittenQuery.length() > cfg.getMaxQueryLength()) {
                return QueryRewriteResult.fallback(originalQuery, RewriteFailureType.INVALID_RESPONSE,
                        "rewritten query exceeds max length", latencyMs);
            }
            return new QueryRewriteResult(originalQuery, rewrittenQuery, true, reason, false, null, latencyMs);
        } catch (Exception e) {
            return QueryRewriteResult.fallback(originalQuery, RewriteFailureType.INVALID_RESPONSE,
                    "rewrite JSON parse failed: " + e.getMessage(), latencyMs);
        }
    }
}
