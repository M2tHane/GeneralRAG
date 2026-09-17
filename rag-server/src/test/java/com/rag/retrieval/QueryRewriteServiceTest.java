package com.rag.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.RagProperties;
import com.rag.domain.enums.SessionRole;
import com.rag.llm.PromptAssembler;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * QueryRewriteService 单元测试（R6-C）。
 *
 * <p>覆盖：无历史跳过（零成本路径）/ 指代解析改写 / 前者解析 / 完整问题保持不变 /
 * 主题切换不泄漏历史 / 结构化输出非法回退 / 模型异常回退 / 超时归类 /
 * markdown 围栏容错 / rewritten=false 的 query 不被信任 / 历史窗口截断。</p>
 */
@ExtendWith(MockitoExtension.class)
class QueryRewriteServiceTest {

    @Mock
    private ChatModel chatModel;

    private RagProperties props;
    private QueryRewriteService service;

    @BeforeEach
    void setUp() {
        props = new RagProperties();
        props.getRetrieval().getQueryRewrite().setTimeoutSeconds(1);
        props.getRetrieval().getQueryRewrite().setMaxQueryLength(200);
        service = new QueryRewriteService(chatModel, props, new ObjectMapper());
    }

    private void stubModel(String output) {
        lenient().when(chatModel.chat(any(List.class))).thenAnswer(inv ->
                ChatResponse.builder().aiMessage(AiMessage.from(output)).build());
    }

    private static PromptAssembler.HistoryTurn user(String content) {
        return new PromptAssembler.HistoryTurn(SessionRole.USER, content);
    }

    private static PromptAssembler.HistoryTurn assistant(String content) {
        return new PromptAssembler.HistoryTurn(SessionRole.ASSISTANT, content);
    }

    private static final List<PromptAssembler.HistoryTurn> HIKARI_HISTORY = List.of(
            user("HikariCP 里 idleTimeout 和 cachePrepStmts 分别是什么默认值？"),
            assistant("idleTimeout 是 600000 ms，cachePrepStmts 默认 true。"));

    // ---------- 触发条件 ----------

    @Test
    void noHistorySkipsRewrite() {
        QueryRewriteService.QueryRewriteResult r = service.rewrite("Redis AOF 是什么？", List.of());
        assertThat(r.rewritten()).isFalse();
        assertThat(r.retrievalQuery()).isEqualTo("Redis AOF 是什么？");
        assertThat(r.degraded()).isFalse();
        assertThat(r.latencyMs()).isEqualTo(0);
        verify(chatModel, never()).chat(any(List.class));
    }

    @Test
    void disabledConfigSkipsRewrite() {
        props.getRetrieval().getQueryRewrite().setEnabled(false);
        QueryRewriteService.QueryRewriteResult r = service.rewrite("它默认开启吗？", HIKARI_HISTORY);
        assertThat(r.rewritten()).isFalse();
        assertThat(r.retrievalQuery()).isEqualTo("它默认开启吗？");
        verify(chatModel, never()).chat(any(List.class));
    }

    // ---------- 指代/省略解析 ----------

    @Test
    void pronounResolved() {
        stubModel("{\"rewritten\": true, \"query\": \"HikariCP cachePrepStmts 默认开启吗\", \"reason\": \"解析“它”指代\"}");
        QueryRewriteService.QueryRewriteResult r = service.rewrite("它默认开启吗？", HIKARI_HISTORY);
        assertThat(r.rewritten()).isTrue();
        assertThat(r.retrievalQuery()).contains("HikariCP").contains("cachePrepStmts");
        assertThat(r.retrievalQuery()).doesNotContain("它默认");
        assertThat(r.reason()).contains("指代");
    }

    @Test
    void formerResolved() {
        stubModel("{\"rewritten\": true, \"query\": \"HikariCP idleTimeout 默认是多少\"}");
        QueryRewriteService.QueryRewriteResult r = service.rewrite("前者是多少？", HIKARI_HISTORY);
        assertThat(r.rewritten()).isTrue();
        assertThat(r.retrievalQuery()).contains("HikariCP").contains("idleTimeout");
    }

    // ---------- 完整问题 / 主题切换 ----------

    @Test
    void completeQuestionKeptUnchanged() {
        stubModel("{\"rewritten\": false, \"query\": \"Kafka enable.idempotence 开启后 acks 应设置为什么？\"}");
        List<PromptAssembler.HistoryTurn> history = List.of(
                user("Redis AOF 和 RDB 有什么区别？"), assistant("RDB 是快照，AOF 是日志。"));
        QueryRewriteService.QueryRewriteResult r = service.rewrite(
                "Kafka enable.idempotence 开启后 acks 应设置为什么？", history);
        assertThat(r.rewritten()).isFalse();
        // Rewriter 判定保持原问题 → retrievalQuery 必须是原问题（query 字段不被信任）
        assertThat(r.retrievalQuery()).isEqualTo("Kafka enable.idempotence 开启后 acks 应设置为什么？");
        assertThat(r.degraded()).isFalse();
    }

    @Test
    void rewrittenFalseQueryFieldNotTrusted() {
        // 模型违规：rewritten=false 却改了 query——防御性回退原问题，不采信
        stubModel("{\"rewritten\": false, \"query\": \"HikariCP idleTimeout 默认是多少\"}");
        QueryRewriteService.QueryRewriteResult r = service.rewrite("Kafka acks 设置为什么？", HIKARI_HISTORY);
        assertThat(r.rewritten()).isFalse();
        assertThat(r.retrievalQuery()).isEqualTo("Kafka acks 设置为什么？");
    }

    @Test
    void topicSwitchDoesNotLeakHistory() {
        stubModel("{\"rewritten\": false, \"query\": \"Redis AOF rewrite 是什么？\"}");
        List<PromptAssembler.HistoryTurn> history = List.of(
                user("HikariCP maximumPoolSize 是什么？"), assistant("是连接池最大连接数。"));
        QueryRewriteService.QueryRewriteResult r = service.rewrite("Redis AOF rewrite 是什么？", history);
        assertThat(r.rewritten()).isFalse();
        assertThat(r.retrievalQuery()).doesNotContain("HikariCP");
        assertThat(r.retrievalQuery()).isEqualTo("Redis AOF rewrite 是什么？");
    }

    // ---------- 失败回退 ----------

    @Test
    void invalidStructuredOutputFallsBackToOriginal() {
        stubModel("这不是 JSON。");
        QueryRewriteService.QueryRewriteResult r = service.rewrite("前者是多少？", HIKARI_HISTORY);
        assertThat(r.rewritten()).isFalse();
        assertThat(r.retrievalQuery()).isEqualTo("前者是多少？");
        assertThat(r.degraded()).isTrue();
        assertThat(r.failureType()).isEqualTo(QueryRewriteService.RewriteFailureType.INVALID_RESPONSE);
    }

    @Test
    void missingQueryFieldFallsBackToOriginal() {
        stubModel("{\"rewritten\": true}");
        QueryRewriteService.QueryRewriteResult r = service.rewrite("前者是多少？", HIKARI_HISTORY);
        assertThat(r.rewritten()).isFalse();
        assertThat(r.retrievalQuery()).isEqualTo("前者是多少？");
        assertThat(r.failureType()).isEqualTo(QueryRewriteService.RewriteFailureType.INVALID_RESPONSE);
    }

    @Test
    void oversizeQueryFallsBackToOriginal() {
        props.getRetrieval().getQueryRewrite().setMaxQueryLength(20);
        stubModel("{\"rewritten\": true, \"query\": \"一个远远超过二十个字符限制的改写查询内容，用于触发超长回退保护逻辑\"}");
        QueryRewriteService.QueryRewriteResult r = service.rewrite("前者是多少？", HIKARI_HISTORY);
        assertThat(r.rewritten()).isFalse();
        assertThat(r.retrievalQuery()).isEqualTo("前者是多少？");
        assertThat(r.failureType()).isEqualTo(QueryRewriteService.RewriteFailureType.INVALID_RESPONSE);
    }

    @Test
    void modelErrorFallsBackToOriginal() {
        lenient().when(chatModel.chat(any(List.class))).thenThrow(new RuntimeException("connection refused"));
        QueryRewriteService.QueryRewriteResult r = service.rewrite("前者是多少？", HIKARI_HISTORY);
        assertThat(r.rewritten()).isFalse();
        assertThat(r.retrievalQuery()).isEqualTo("前者是多少？");
        assertThat(r.degraded()).isTrue();
        assertThat(r.failureType()).isEqualTo(QueryRewriteService.RewriteFailureType.MODEL_ERROR);
    }

    @Test
    void timeoutClassifiedAsTimeout() {
        lenient().when(chatModel.chat(any(List.class))).thenThrow(
                new RuntimeException(new dev.langchain4j.exception.TimeoutException("read timeout")));
        QueryRewriteService.QueryRewriteResult r = service.rewrite("前者是多少？", HIKARI_HISTORY);
        assertThat(r.rewritten()).isFalse();
        assertThat(r.retrievalQuery()).isEqualTo("前者是多少？");
        assertThat(r.failureType()).isEqualTo(QueryRewriteService.RewriteFailureType.TIMEOUT);
    }

    /** 容错：markdown 围栏 + JSON 前后说明文字（同 Judge parse 语义）。 */
    @Test
    void toleratesMarkdownFenceAndSurroundingText() {
        stubModel("好的：\n```json\n{\"rewritten\": true, \"query\": \"HikariCP idleTimeout 默认是多少\"}\n```\n以上。");
        QueryRewriteService.QueryRewriteResult r = service.rewrite("前者是多少？", HIKARI_HISTORY);
        assertThat(r.rewritten()).isTrue();
        assertThat(r.retrievalQuery()).contains("idleTimeout");
    }

    // ---------- Prompt 防漂移 + 历史窗口 ----------

    @Test
    void systemPromptLocksRewriteDiscipline() {
        List<ChatMessage> messages = service.buildMessages("前者是多少？", HIKARI_HISTORY);
        String system = ((dev.langchain4j.data.message.SystemMessage) messages.get(0)).text();
        assertThat(system).contains("任务不是回答问题");
        assertThat(system).contains("保留用户原始意图");
        assertThat(system).contains("只补充历史中明确出现的实体");
        assertThat(system).contains("不回答问题，不使用外部知识");
        assertThat(system).contains("不做算术运算");
        assertThat(system).contains("不增加新的过滤条件，不改变问题的语义");
        assertThat(system).contains("保持原问题不变");
    }

    @Test
    void historyWindowTruncatedToMaxHistoryTurns() {
        // maxHistoryTurns 语义 = 最近 N 条 HistoryTurn（一条 user/assistant 各算一条），
        // 与 PromptAssembler/ChatStreamService 的 maxHistoryMessages 收敛口径一致
        props.getRetrieval().getQueryRewrite().setMaxHistoryTurns(2);
        List<PromptAssembler.HistoryTurn> longHistory = List.of(
                user("第一轮问题"), assistant("第一轮回答"),
                user("第二轮问题"), assistant("第二轮回答"),
                user("第三轮问题"), assistant("第三轮回答"));
        List<ChatMessage> messages = service.buildMessages("它默认开启吗？", longHistory);
        // UserMessage 无 text() 访问器（单文本构造存于 contents），直接用 toString 断言
        String userMsg = messages.get(1).toString();
        assertThat(userMsg).contains("第三轮问题").contains("第三轮回答");
        assertThat(userMsg).doesNotContain("第一轮问题").doesNotContain("第二轮问题");
    }
}
