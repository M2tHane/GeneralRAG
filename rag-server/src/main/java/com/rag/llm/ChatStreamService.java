package com.rag.llm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rag.api.RequestIdFilter;
import com.rag.api.dto.ApiError;
import com.rag.config.AsyncConfig;
import com.rag.config.RagProperties;
import com.rag.domain.entity.ChatMessageEntity;
import com.rag.domain.entity.ChatSessionEntity;
import com.rag.domain.entity.DocumentEntity;
import com.rag.domain.enums.MessageStatus;
import com.rag.domain.enums.SessionRole;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import com.rag.retrieval.ContextAssembler;
import com.rag.retrieval.RetrievalService;
import com.rag.retrieval.RetrievalPipeline;
import com.rag.retrieval.RetrievalRequest;
import com.rag.retrieval.model.RetrievalMode;
import com.rag.retrieval.model.Context;
import com.rag.retrieval.model.RetrievalHit;
import com.rag.storage.es.EsHit;
import com.rag.storage.repository.ChatMessageRepository;
import com.rag.storage.repository.ChatSessionRepository;
import com.rag.storage.repository.DocumentRepository;
import com.rag.storage.repository.KnowledgeBaseRepository;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 问答流式服务（docs/03-技术路线.md §3.2 / §6，contracts/openapi.yaml
 * QAStreamRequest / QAStreamEvent 契约）。Task 4 的 QaController 仅做薄封装，
 * SSE 全部逻辑（事件发射、终态、超时、取消、活跃流登记、消息落库）收敛在本类。
 *
 * <p>事件序列：stage(RETRIEVAL_STARTED → RETRIEVAL_COMPLETED → GENERATION_STARTED)
 * → token* → citations（恰一次，恒在 done 前）→ done/error/canceled（互斥终态）。
 * 心跳为注释行 :ping 每 15s。</p>
 *
 * <p>终态归一：正常完成、模型异常/超时、用户取消、客户端断连四条路径
 * 都经由 terminal CAS 幂等收尾——终态事件只发一次、助手消息只落一条、
 * 活跃流登记与并发信号量只释放一次。</p>
 */
@Service
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    private final RetrievalService retrievalService;
    private final ContextAssembler contextAssembler;
    private final PromptAssembler promptAssembler;
    private final RefusalPolicy refusalPolicy;
    private final RetrievalPipeline retrievalPipeline;
    private final com.rag.answerability.AnswerabilityPolicy answerabilityPolicy;
    private final StreamingChatModel chatModel;
    private final RagProperties ragProperties;
    private final KnowledgeBaseRepository kbRepository;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final DocumentRepository documentRepository;
    private final Executor streamExecutor;
    private final ScheduledExecutorService scheduler;

    /** 活跃流登记：clientRequestId → 流状态（防重复提交 → 409 STREAM_ALREADY_ACTIVE）。 */
    private final ConcurrentHashMap<String, StreamState> activeStreams = new ConcurrentHashMap<>();

    /** SSE 并发流闸门（同步受理阶段把关，超出 → 503 SERVICE_UNAVAILABLE）。 */
    private final Semaphore streamSlots = new Semaphore(AsyncConfig.SSE_STREAM_CONCURRENCY);

    public ChatStreamService(RetrievalService retrievalService,
                             ContextAssembler contextAssembler,
                             PromptAssembler promptAssembler,
                             RefusalPolicy refusalPolicy,
                             RetrievalPipeline retrievalPipeline,
                             com.rag.answerability.AnswerabilityPolicy answerabilityPolicy,
                             StreamingChatModel chatModel,
                             RagProperties ragProperties,
                             KnowledgeBaseRepository kbRepository,
                             ChatSessionRepository sessionRepository,
                             ChatMessageRepository messageRepository,
                             DocumentRepository documentRepository,
                             @Qualifier("sseStreamExecutor") Executor streamExecutor,
                             @Qualifier("sseScheduler") ScheduledExecutorService scheduler) {
        this.retrievalService = retrievalService;
        this.contextAssembler = contextAssembler;
        this.promptAssembler = promptAssembler;
        this.refusalPolicy = refusalPolicy;
        this.retrievalPipeline = retrievalPipeline;
        this.answerabilityPolicy = answerabilityPolicy;
        this.chatModel = chatModel;
        this.ragProperties = ragProperties;
        this.kbRepository = kbRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.documentRepository = documentRepository;
        this.streamExecutor = streamExecutor;
        this.scheduler = scheduler;
    }

    // ==================================================================
    // 对 Task 4 的公开 API
    // ==================================================================

    /**
     * 发起问答流（Task 4 controller：POST /api/v1/qa/stream，
     * 方法上加 produces = text/event-stream，返回值直接作为响应体）。
     *
     * <p>同步阶段完成：KB/会话校验（404）、活跃流登记（409）、并发闸门（503）、
     * 用户消息落库（QA-8）；全部失败路径抛 {@link DomainException}，由
     * GlobalExceptionHandler 转换为契约 Error JSON 响应。</p>
     *
     * @param command kbId / sessionId / question / clientRequestId（可空，服务端补 UUID）
     * @return 已提交生成的 SseEmitter（controller 原样返回，不要再次设置回调）
     */
    public SseEmitter startStream(QaCommand command) {
        validateKbAndSession(command.kbId(), command.sessionId());
        String clientRequestId = command.clientRequestId() != null && !command.clientRequestId().isBlank()
                ? command.clientRequestId()
                : UUID.randomUUID().toString();

        // 历史取自本次提问之前（用户消息尚未落库，天然排除当前问题）
        List<PromptAssembler.HistoryTurn> history = loadHistory(command.sessionId());
        SseEmitter emitter = new SseEmitter(0L); // 0 = 不设容器级超时；生命周期由终态/断连驱动
        StreamState state = new StreamState(clientRequestId, command.kbId(), command.sessionId(),
                command.question(), MDC.get(RequestIdFilter.MDC_KEY), emitter, history);

        if (activeStreams.putIfAbsent(clientRequestId, state) != null) {
            throw new DomainException(ErrorCode.STREAM_ALREADY_ACTIVE);
        }
        try {
            if (!streamSlots.tryAcquire()) {
                throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE,
                        "问答并发已达上限（" + AsyncConfig.SSE_STREAM_CONCURRENCY + " 个进行中的流），请稍后重试");
            }
        } catch (RuntimeException e) {
            activeStreams.remove(clientRequestId, state);
            throw e;
        }
        try {
            state.userMessageId = persistUserMessage(command.sessionId(), command.question());
        } catch (RuntimeException e) {
            streamSlots.release();
            activeStreams.remove(clientRequestId, state);
            throw e;
        }

        registerDisconnectCallbacks(state);
        startHeartbeat(state);
        try {
            streamExecutor.execute(() -> runStream(state));
        } catch (RuntimeException e) { // RejectedExecutionException 等受理失败
            log.warn("SSE executor 受理失败 clientRequestId={}", clientRequestId, e);
            state.terminal.set(true);
            stopTimers(state);
            streamSlots.release();
            activeStreams.remove(clientRequestId, state);
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE,
                    "问答并发已达上限（" + AsyncConfig.SSE_STREAM_CONCURRENCY + " 个进行中的流），请稍后重试");
        }
        log.info("问答流开始 sessionId={} clientRequestId={} kbId={}", command.sessionId(), clientRequestId, command.kbId());
        return emitter;
    }

    /**
     * 取消进行中的流（Task 4 controller：POST /api/v1/qa/cancel，成功后返回
     * 202 {clientRequestId, canceled:true}）。已结束/未知 → 404 CANCEL_TARGET_NOT_FOUND。
     */
    public void cancel(String clientRequestId) {
        StreamState state = activeStreams.get(clientRequestId);
        if (state == null || state.terminal.get()
                || !state.terminal.compareAndSet(false, true)) {
            throw new DomainException(ErrorCode.CANCEL_TARGET_NOT_FOUND);
        }
        state.canceled = true;
        stopTimers(state);
        bindMdc(state);
        try {
            log.info("用户取消问答流 clientRequestId={}", clientRequestId);
            persistAssistant(state, MessageStatus.CANCELED, null);
            sendQuietly(state, "canceled", new CanceledEvent("USER_CANCELED"));
        } catch (RuntimeException e) {
            log.error("取消落库异常（继续收尾）clientRequestId={}", clientRequestId, e);
        } finally {
            finish(state);
        }
    }

    /**
     * 非流式问答（评测复用，同一检索/上下文/prompt/Answerability 路径；不写会话与消息）。
     *
     * <p>R4：判定走 {@link AnswerabilityPolicy}（低分直拒 → Judge），
     * 拒答语义与流式路径一致（拒答 + 空引用），并把判定结果带回给评测落库。</p>
     *
     * @return 全文 + 引用（本次实际命中）+ 全量 hits + 耗时 + 是否拒答 + 分段耗时 + 判定
     */
    public AnswerResult answerOnce(String kbId, String question,
                                   Integer topKOverride, Double minScoreOverride,
                                   List<PromptAssembler.HistoryTurn> history) {
        long start = System.currentTimeMillis();
        RetrievalPipeline.RetrievalOutcome outcome = retrievalPipeline.execute(
                RetrievalRequest.of(kbId, question, topKOverride, minScoreOverride));
        List<RetrievalHit> hits = outcome.hits();
        long retrievalMs = System.currentTimeMillis() - start;
        List<RetrievalHit> passed = hits.stream().filter(RetrievalHit::passedThreshold).toList();

        // R4/R4.1：证据充分性判定（与流式同一路径）。assemble 一次，同一 Context
        // 实例既喂 Judge 也喂生成 Prompt——不存在"Judge 看前 N 字、生成看全文"的漂移
        RetrievalPipeline.RetrievalDiagnostics diag = outcome.diagnostics();
        boolean rerankApplied = diag.rerankApplied();
        Context context = contextAssembler.assemble(passed);
        long judgeStart = System.currentTimeMillis();
        // R4.1.1：history 必须传给 Judge（解析 FOLLOW_UP 指代），而非只给生成——
        // 历史仅进 Judge 提示词的指代解析区，不构成证据（evidence 仍只来自 context）
        com.rag.answerability.AnswerabilityDecision decision = answerabilityPolicy.evaluate(
                new com.rag.answerability.AnswerabilityInput(
                        question, history, context, hits, diag.mode(), rerankApplied));
        long answerabilityMs = System.currentTimeMillis() - judgeStart;
        if (!decision.answerable()) {
            return new AnswerResult(refusalPolicy.refusalAnswer(), List.of(), hits,
                    System.currentTimeMillis() - start, retrievalMs,
                    System.currentTimeMillis() - start - retrievalMs - answerabilityMs, true,
                    decision, answerabilityMs);
        }

        List<ChatMessage> messages = promptAssembler.build(question, history, context);

        StringBuffer full = new StringBuffer(); // M2
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        chatModel.chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                full.append(token);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                done.countDown();
            }

            @Override
            public void onError(Throwable error) {
                failure.set(error);
                done.countDown();
            }
        });
        long idleMs = TimeUnit.SECONDS.toMillis(ragProperties.getModels().getChat().getTimeoutSeconds());
        try {
            if (!done.await(idleMs, TimeUnit.MILLISECONDS)) {
                throw new DomainException(ErrorCode.MODEL_TIMEOUT,
                        "模型 " + ragProperties.getModels().getChat().getTimeoutSeconds() + " 秒内未返回完整回答");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainException(ErrorCode.MODEL_UNAVAILABLE, "等待模型回答被中断");
        }
        if (failure.get() != null) {
            throw new DomainException(ErrorCode.MODEL_UNAVAILABLE,
                    "模型服务暂时不可用：" + failure.get().getMessage());
        }
        long totalMs = System.currentTimeMillis() - start;
        return new AnswerResult(full.toString(), buildCitations(passed), hits, totalMs,
                retrievalMs, totalMs - retrievalMs - answerabilityMs, false,
                decision, answerabilityMs);
    }

    /** 非流式回答聚合结果（eval 复用）。 */
    public record AnswerResult(String answer, List<Map<String, Object>> citations,
                               List<RetrievalHit> hits, long elapsedMs,
                               long retrievalMs, long generationMs, boolean refusal,
                               com.rag.answerability.AnswerabilityDecision answerability,
                               long answerabilityMs) {
    }

    /** 问答流命令（controller 请求体映射；约束对齐契约 QAStreamRequest）。 */
    public record QaCommand(
            @jakarta.validation.constraints.NotBlank(message = "kbId 不能为空") String kbId,
            @jakarta.validation.constraints.NotBlank(message = "sessionId 不能为空") String sessionId,
            @jakarta.validation.constraints.NotBlank(message = "问题不能为空")
            @jakarta.validation.constraints.Size(max = 2000, message = "问题长度不能超过 2000 字") String question,
            String clientRequestId) {
    }

    // ==================================================================
    // SSE 事件载荷（与 contracts/openapi.yaml StreamEvent* 一字不差）
    // ==================================================================

    /**
     * SSE 事件载荷（与 contracts/openapi.yaml StreamEvent* 一字不差）。
     *
     * <p>R4.1.1：ANSWERABILITY_CHECKED stage 携带机器可读的判定枚举
     * （decisionType 必有；judgeFailureType 仅 degraded 时非 null）——
     * 冒烟/监控可结构化统计，不必解析 detail 文案。故意<b>不</b>暴露
     * confidence / reason / prompt / evidence（自由文本不进用户侧 SSE）。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StageEvent(String stage, String detail, Long elapsedMs,
                      String decisionType, String judgeFailureType) {

        /** 常规 stage（无判定枚举）。 */
        StageEvent(String stage, String detail, Long elapsedMs) {
            this(stage, detail, elapsedMs, null, null);
        }
    }

    record TokenEvent(String delta) {
    }

    record CitationsEvent(List<Map<String, Object>> citations) {
    }

    record DoneEvent(String messageId, Long elapsedMs) {
    }

    record ErrorEvent(ApiError error) {
    }

    record CanceledEvent(String reason) {
    }

    // ==================================================================
    // 流执行
    // ==================================================================

    private void runStream(StreamState state) {
        bindMdc(state);
        try {
            if (state.terminal.get()) {
                return; // 断连早于任务启动：断连回调已收尾
            }
            send(state, "stage", new StageEvent("RETRIEVAL_STARTED", null, null));

            long retrievalStart = System.currentTimeMillis();
            RetrievalPipeline.RetrievalOutcome outcome = retrievalPipeline.execute(
                    RetrievalRequest.of(state.kbId, state.question));
            List<RetrievalHit> hits = outcome.hits();
            List<RetrievalHit> passed = hits.stream().filter(RetrievalHit::passedThreshold).toList();
            // R4/R4.1：证据充分性判定（低分直拒 → 其余 Judge，共享 AnswerabilityPolicy）。
            // assemble 一次：同一 Context 实例既喂 Judge 也喂生成 Prompt（§五 一致性）
            RetrievalPipeline.RetrievalDiagnostics diag = outcome.diagnostics();
            boolean rerankApplied = diag.rerankApplied();
            Context context = contextAssembler.assemble(passed);
            // R4.1.1：流式路径同样把 session history 传给 Judge（指代解析，非证据）
            com.rag.answerability.AnswerabilityDecision decision = answerabilityPolicy.evaluate(
                    new com.rag.answerability.AnswerabilityInput(
                            state.question, state.history, context, hits, diag.mode(), rerankApplied));
            boolean insufficient = !decision.answerable();
            state.refusal = insufficient;
            state.citations = insufficient ? List.of() : buildCitations(passed);
            state.answerability = decision;
            long elapsedMs = System.currentTimeMillis() - retrievalStart;
            double topScore = hits.stream().mapToDouble(RetrievalHit::score).max().orElse(0.0);
            // stage 行如实反映检索真相与判定原因：按 decisionType 给出说明，不把 Judge 拒答谎报成低分
            String retrievalDetail = switch (decision.decisionType()) {
                case LOW_SCORE_REFUSAL -> "命中 " + hits.size() + " 块 · 最高分 " + fmt3(topScore)
                        + "（" + refusalPolicy.scaleName(diag.mode(), rerankApplied) + " 口径）"
                        + " 低于证据阈值 " + fmt2(refusalPolicy.thresholdFor(diag.mode(), rerankApplied))
                        + " · " + elapsedMs + "ms";
                case NO_HITS -> "未命中任何分块 · " + elapsedMs + "ms";
                case JUDGE_REFUSE -> "命中 " + hits.size() + " 块（最高分 " + fmt3(topScore) + "）"
                        + " · 判定证据不足以完整回答 · " + elapsedMs + "ms";
                case JUDGE_DEGRADED -> "命中 " + hits.size() + " 块（最高分 " + fmt3(topScore) + "）"
                        + " · Judge 降级（" + (decision.answerable() ? "放行" : "拒答") + "）· " + elapsedMs + "ms";
                case ANSWERABILITY_DISABLED -> refusalPolicy.insufficient(hits, diag.mode(), rerankApplied)
                        ? "命中 " + hits.size() + " 块 · 最高分 " + fmt3(topScore)
                                + "（" + refusalPolicy.scaleName(diag.mode(), rerankApplied) + " 口径）"
                                + " 低于证据阈值 " + fmt2(refusalPolicy.thresholdFor(diag.mode(), rerankApplied))
                                + " · " + elapsedMs + "ms"
                        : "命中 " + passed.size() + " 块 · " + elapsedMs + "ms";
                case JUDGE_ACCEPT -> "命中 " + passed.size() + " 块 · " + elapsedMs + "ms";
            };
            send(state, "stage", new StageEvent("RETRIEVAL_COMPLETED", retrievalDetail, elapsedMs));
            if (state.terminal.get()) {
                return;
            }

            // R4/R4.1：Answerability 判定完成的进度表达（confidence/reason 不进 SSE 用户内容；
            // R4.1.1 额外携带机器可读枚举供冒烟/监控统计）
            send(state, "stage", new StageEvent("ANSWERABILITY_CHECKED",
                    decisionLabel(decision), decision.latencyMs(),
                    decision.decisionType().name(),
                    decision.failureType() == null ? null : decision.failureType().name()));
            if (state.terminal.get()) {
                return;
            }

            send(state, "stage", new StageEvent("GENERATION_STARTED", null, null));
            if (state.terminal.get()) {
                return;
            }

            // R2-A1/R4：证据不足不调用模型，直接流式输出拒答文案（避免模型在低相关证据上编造）
            if (insufficient) {
                log.info("证据不足，拒答并清空引用 clientRequestId={} decisionType={} reason={}",
                        state.clientRequestId, decision.decisionType(), decision.reason());
                streamRefusal(state);
                return;
            }

            List<ChatMessage> messages = promptAssembler.build(state.question, state.history, context);
            state.lastModelActivity = System.currentTimeMillis();
            startIdleWatchdog(state);
            chatModel.chat(messages, new StreamingHandler(state));
        } catch (DomainException e) {
            // 检索链路失败（embedding/ES 不可用等）→ 契约错误码 RETRIEVAL_FAILED
            log.warn("问答流检索失败 clientRequestId={}", state.clientRequestId, e);
            failTerminal(state, ErrorCode.RETRIEVAL_FAILED, "检索服务暂时不可用：" + e.getMessage());
        } catch (Exception e) {
            log.error("问答流未预期异常 clientRequestId={}", state.clientRequestId, e);
            failTerminal(state, ErrorCode.INTERNAL_ERROR, "服务内部错误，请稍后重试");
        }
    }

    /** ANSWERABILITY_CHECKED stage 的简述（不含 confidence/reason——那是 Debug/Eval 信息）。 */
    private static String decisionLabel(com.rag.answerability.AnswerabilityDecision decision) {
        return switch (decision.decisionType()) {
            case LOW_SCORE_REFUSAL -> "证据充分性判定：低分拒答";
            case NO_HITS -> "证据充分性判定：无命中";
            case JUDGE_ACCEPT -> "证据充分性判定：可回答";
            case JUDGE_REFUSE -> "证据充分性判定：证据不足";
            case JUDGE_DEGRADED -> decision.answerable()
                    ? "证据充分性判定：Judge 降级放行" : "证据充分性判定：Judge 降级拒答";
            case ANSWERABILITY_DISABLED -> "证据充分性判定：关闭（旧阈值行为）";
        };
    }

    /**
     * 证据不足的拒答流（R2-A1）：按与正常回答相同的 token 事件形态输出固定文案，
     * 随后走统一成功收尾（citations 为空数组 → done），保持 SSE 事件序列不变。
     */
    private void streamRefusal(StreamState state) {
        String answer = refusalPolicy.refusalAnswer();
        for (String token : answer.split("")) {
            if (state.terminal.get() || state.canceled) {
                return;
            }
            state.content.append(token);
            try {
                send(state, "token", new TokenEvent(token));
            } catch (IOException | IllegalStateException e) {
                log.info("拒答 token 发送失败（客户端断连）clientRequestId={}", state.clientRequestId);
                handleDisconnect(state);
                return;
            }
        }
        completeSuccess(state, state.content.toString());
    }

    /** 模型流式回调：token 透传、完成/失败归一到终态路径。 */
    private class StreamingHandler implements StreamingChatResponseHandler {

        private final StreamState state;

        StreamingHandler(StreamState state) {
            this.state = state;
        }

        @Override
        public void onPartialResponse(String token) {
            state.lastModelActivity = System.currentTimeMillis();
            if (state.terminal.get() || state.canceled) {
                return; // 已取消/断连：停止消费后续增量
            }
            state.content.append(token);
            try {
                send(state, "token", new TokenEvent(token));
            } catch (IOException | IllegalStateException e) {
                log.info("token 发送失败（客户端断连）clientRequestId={}", state.clientRequestId);
                handleDisconnect(state);
            }
        }

        @Override
        public void onCompleteResponse(ChatResponse response) {
            if (state.terminal.get()) {
                return;
            }
            String full = state.content.toString();
            if (full.isEmpty() && response != null && response.aiMessage() != null
                    && response.aiMessage().text() != null) {
                full = response.aiMessage().text();
            }
            completeSuccess(state, full);
        }

        @Override
        public void onError(Throwable error) {
            if (state.terminal.get()) {
                return;
            }
            log.warn("模型流式生成失败 clientRequestId={}", state.clientRequestId, error);
            failTerminal(state, ErrorCode.MODEL_UNAVAILABLE, "模型服务暂时不可用：" + error.getMessage());
        }
    }

    // ==================================================================
    // 终态路径（terminal CAS 幂等）
    // ==================================================================

    private void completeSuccess(StreamState state, String full) {
        if (!state.terminal.compareAndSet(false, true)) {
            return;
        }
        stopTimers(state);
        bindMdc(state);
        // 本地写缓冲可能把 broken pipe 推迟到终态事件上才暴露：先发一个探测事件。
        // 若客户端已断开，按 canceled 落库（QA-8：取消语义），不再伪造"完成"。
        if (!sendProbe(state, "citations", new CitationsEvent(state.citations))) {
            // citations 恰好一次的语义在断连场景无法满足（对端已消失），按断连收尾
            handleDisconnectAfterTerminal(state);
            return;
        }
        String messageId;
        long elapsedMs = System.currentTimeMillis() - state.startedAt;
        try {
            messageId = persistAssistant(state, MessageStatus.COMPLETED, null);
        } catch (RuntimeException e) {
            // B2：落库失败不得跳过收尾——否则并发槽位/活跃流登记永久泄漏（整个接口 503 到重启）
            log.error("助手消息落库失败（按完成收尾继续）clientRequestId={}", state.clientRequestId, e);
            messageId = UUID.randomUUID().toString();
        }
        try {
            send(state, "done", new DoneEvent(messageId, elapsedMs));
        } catch (IOException | IllegalStateException e) {
            log.info("done 事件发送失败（客户端断连）clientRequestId={}", state.clientRequestId);
            finish(state);
            return;
        }
        log.info("问答流完成 clientRequestId={} messageId={} elapsedMs={}", state.clientRequestId, messageId, elapsedMs);
        finish(state);
    }

    /** completeSuccess 已占用终态后的断连收尾：按 CANCELED 补落助手消息并清理。 */
    private void handleDisconnectAfterTerminal(StreamState state) {
        try {
            persistAssistant(state, MessageStatus.CANCELED, null);
        } finally {
            finish(state);
        }
    }

    /** 发送并返回是否成功；失败时仅记日志（调用方决定降级路径）。 */
    private boolean sendProbe(StreamState state, String event, Object payload) {
        try {
            send(state, event, payload);
            return true;
        } catch (IOException | IllegalStateException e) {
            log.info("终态事件发送失败（客户端断连）event={} clientRequestId={}", event, state.clientRequestId);
            return false;
        }
    }

    private void failTerminal(StreamState state, ErrorCode code, String message) {
        if (!state.terminal.compareAndSet(false, true)) {
            return;
        }
        stopTimers(state);
        bindMdc(state);
        try {
            persistAssistant(state, MessageStatus.ERROR, code.name());
        } catch (RuntimeException e) {
            log.error("失败终态落库异常（继续收尾，保证槽位释放）clientRequestId={}", state.clientRequestId, e);
        } finally {
            sendQuietly(state, "error", new ErrorEvent(new ApiError(code.name(), message, state.requestId, null)));
            finish(state);
        }
    }

    /** emitter onError/onTimeout/onCompletion 或写失败 → 视为断连，canceled 收尾。 */
    private void handleDisconnect(StreamState state) {
        if (!state.terminal.compareAndSet(false, true)) {
            return; // 正常终态路径触发的 onCompletion，幂等空操作
        }
        state.canceled = true;
        stopTimers(state);
        bindMdc(state);
        try {
            log.info("客户端断连，问答流取消 clientRequestId={}", state.clientRequestId);
            persistAssistant(state, MessageStatus.CANCELED, null);
            sendQuietly(state, "canceled", new CanceledEvent("USER_CANCELED"));
        } finally {
            finish(state);
        }
    }

    /** 收尾：complete emitter、释放并发闸门、移除活跃流登记。 */
    private void finish(StreamState state) {
        try {
            state.emitter.complete();
        } catch (Exception e) {
            log.debug("emitter complete 忽略异常 clientRequestId={}", state.clientRequestId);
        }
        streamSlots.release();
        activeStreams.remove(state.clientRequestId, state);
        MDC.remove(RequestIdFilter.MDC_KEY);
    }

    private void stopTimers(StreamState state) {
        Future<?> heartbeat = state.heartbeat;
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        Future<?> watchdog = state.watchdog;
        if (watchdog != null) {
            watchdog.cancel(false);
        }
    }

    private void registerDisconnectCallbacks(StreamState state) {
        state.emitter.onTimeout(() -> handleDisconnect(state));
        state.emitter.onError(t -> handleDisconnect(state));
        state.emitter.onCompletion(() -> handleDisconnect(state));
    }

    private void startIdleWatchdog(StreamState state) {
        long idleMs = TimeUnit.SECONDS.toMillis(ragProperties.getModels().getChat().getTimeoutSeconds());
        state.watchdog = scheduler.scheduleAtFixedRate(() -> {
            if (state.terminal.get()) {
                return;
            }
            long idle = System.currentTimeMillis() - state.lastModelActivity;
            if (idle >= idleMs) {
                log.warn("模型空闲超时 clientRequestId={} idleMs={}", state.clientRequestId, idle);
                failTerminal(state, ErrorCode.MODEL_TIMEOUT,
                        "模型 " + ragProperties.getModels().getChat().getTimeoutSeconds()
                                + " 秒内未返回任何增量，连接已终止");
            }
        }, idleMs, Math.min(idleMs, 5_000L), TimeUnit.MILLISECONDS);
    }

    private void startHeartbeat(StreamState state) {
        state.heartbeat = scheduler.scheduleAtFixedRate(() -> {
            if (state.terminal.get()) {
                return;
            }
            try {
                state.emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                log.info("心跳发送失败（客户端断连）clientRequestId={}", state.clientRequestId);
                handleDisconnect(state);
            }
        }, AsyncConfig.SSE_HEARTBEAT_SECONDS, AsyncConfig.SSE_HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    // ==================================================================
    // 校验 / 持久化 / 组装
    // ==================================================================

    private void validateKbAndSession(String kbId, String sessionId) {
        if (!kbRepository.existsById(kbId)) {
            throw new DomainException(ErrorCode.KB_NOT_FOUND);
        }
        ChatSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new DomainException(ErrorCode.SESSION_NOT_FOUND));
        if (!session.getKbId().equals(kbId)) {
            throw new DomainException(ErrorCode.SESSION_NOT_FOUND);
        }
    }

    private List<PromptAssembler.HistoryTurn> loadHistory(String sessionId) {
        int max = ragProperties.getModels().getChat().getMaxHistoryMessages();
        List<ChatMessageEntity> recent =
                messageRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, PageRequest.of(0, max));
        List<PromptAssembler.HistoryTurn> history = new ArrayList<>(recent.size());
        for (int i = recent.size() - 1; i >= 0; i--) { // 倒序取回 → 转正序
            ChatMessageEntity m = recent.get(i);
            history.add(new PromptAssembler.HistoryTurn(m.getRole(), m.getContent()));
        }
        return history;
    }

    private String persistUserMessage(String sessionId, String question) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setSessionId(sessionId);
        message.setRole(SessionRole.USER);
        message.setContent(question);
        message.setCitations(List.of());
        message.setStatus(MessageStatus.COMPLETED); // 用户消息即落即完成（QA-8 输入不丢）
        messageRepository.saveAndFlush(message);
        return message.getId();
    }

    /** 助手消息落库：content=已生成全文、citations JSON、status、error_code。 */
    private String persistAssistant(StreamState state, MessageStatus status, String errorCode) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setSessionId(state.sessionId);
        message.setRole(SessionRole.ASSISTANT);
        message.setContent(state.content.toString());
        message.setCitations(new ArrayList<>(state.citations));
        message.setStatus(status);
        message.setErrorCode(errorCode);
        messageRepository.saveAndFlush(message);
        return message.getId();
    }

    /** Citation 内容 = 本次实际检索命中（QA-6：无命中为空数组，绝不伪造）。 */
    private List<Map<String, Object>> buildCitations(List<RetrievalHit> passed) {
        if (passed.isEmpty()) {
            return List.of();
        }
        Set<String> docIds = passed.stream().map(h -> h.chunk().docId()).collect(Collectors.toSet());
        Map<String, DocumentEntity> docs = documentRepository.findAllById(docIds).stream()
                .collect(Collectors.toMap(DocumentEntity::getId, d -> d));
        List<Map<String, Object>> citations = new ArrayList<>(passed.size());
        for (RetrievalHit hit : passed) {
            EsHit chunk = hit.chunk();
            DocumentEntity doc = docs.get(chunk.docId());
            Map<String, Object> citation = new java.util.LinkedHashMap<>();
            citation.put("chunkId", chunk.chunkId());
            citation.put("docId", chunk.docId());
            citation.put("docName", doc == null ? "" : doc.getName());
            citation.put("titlePath", chunk.titlePath());
            citation.put("page", chunk.page()); // MD/TXT 为 null（JSON null，契约 nullable）
            citation.put("score", chunk.score());
            citations.add(citation);
        }
        return citations;
    }


    private static String fmt3(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private static String fmt2(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private void bindMdc(StreamState state) {
        if (state.requestId != null) {
            MDC.put(RequestIdFilter.MDC_KEY, state.requestId);
        }
    }

    private void send(StreamState state, String event, Object payload) throws IOException {
        state.emitter.send(SseEmitter.event().name(event).data(payload));
    }

    private void sendQuietly(StreamState state, String event, Object payload) {
        try {
            send(state, event, payload);
        } catch (Exception e) {
            log.debug("SSE 事件发送失败（连接可能已断开）event={} clientRequestId={}", event, state.clientRequestId);
        }
    }

    /** 单条活跃流的全部可变状态（终态布尔是唯一的收尾裁决点）。 */
    static final class StreamState {

        final String clientRequestId;
        final String kbId;
        final String sessionId;
        final String question;
        final String requestId;
        final SseEmitter emitter;
        final List<PromptAssembler.HistoryTurn> history;
        final long startedAt = System.currentTimeMillis();

        /** 终态裁决：done/error/canceled 与断连四路径经由 CAS 只允许一方收尾。 */
        final AtomicBoolean terminal = new AtomicBoolean(false);

        /** 生成停止标志：取消/断连后 token 回调直接丢弃后续增量。 */
        volatile boolean canceled;

        final StringBuffer content = new StringBuffer(); // M2
        volatile long lastModelActivity;
        volatile List<Map<String, Object>> citations = List.of();

        /** R2-A1：本次是否因证据不足而拒答（拒答时 citations 恒为空数组）。 */
        volatile boolean refusal;
        /** R4：本次 Answerability 判定（含决策类型/置信度/原因，落库到消息与调试可查）。 */
        volatile com.rag.answerability.AnswerabilityDecision answerability;
        volatile String userMessageId;
        volatile Future<?> heartbeat;
        volatile Future<?> watchdog;

        StreamState(String clientRequestId, String kbId, String sessionId, String question,
                    String requestId, SseEmitter emitter, List<PromptAssembler.HistoryTurn> history) {
            this.clientRequestId = clientRequestId;
            this.kbId = kbId;
            this.sessionId = sessionId;
            this.question = question;
            this.requestId = requestId;
            this.emitter = emitter;
            this.history = history;
        }
    }
}
