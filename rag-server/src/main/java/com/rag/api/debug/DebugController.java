package com.rag.api.debug;

import com.rag.debug.DebugResult;
import com.rag.debug.DebugRetrievalService;
import com.rag.retrieval.model.RetrievalMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索调试接口（contracts/openapi.yaml POST /api/v1/debug/retrieval）。
 * 纯只读：不落任何会话/消息数据；默认配置与问答链路同源（RagProperties）。
 */
@RestController
@RequestMapping("/api/v1/debug")
public class DebugController {

    private final DebugRetrievalService debugRetrievalService;

    public DebugController(DebugRetrievalService debugRetrievalService) {
        this.debugRetrievalService = debugRetrievalService;
    }

    @PostMapping("/retrieval")
    @Operation(operationId = "debugRetrieval", summary = "检索调试（与问答共用同一检索代码路径与默认配置）")
    public DebugResult.DebugRetrievalResult debugRetrieval(@Valid @RequestBody DebugRetrievalRequest request) {
        RetrievalMode mode = null;
        if (request.mode() != null && !request.mode().isBlank()) {
            try {
                mode = RetrievalMode.valueOf(request.mode().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new com.rag.domain.exception.DomainException(
                        com.rag.domain.exception.ErrorCode.INVALID_ARGUMENT,
                        "检索模式不合法：" + request.mode() + "（可选 VECTOR / HYBRID / HYBRID_RERANK）");
            }
        }
        // R6-C：history 非空时触发 history-aware query rewrite（仅检索查询；判定仍用原问题）
        List<com.rag.llm.PromptAssembler.HistoryTurn> history = new ArrayList<>();
        if (request.history() != null) {
            for (HistoryMessage msg : request.history()) {
                if (msg == null || msg.role() == null || msg.content() == null
                        || msg.content().isBlank()) {
                    continue; // 防御：异常形态跳过而非失败
                }
                history.add(new com.rag.llm.PromptAssembler.HistoryTurn(
                        "assistant".equalsIgnoreCase(msg.role())
                                ? com.rag.domain.enums.SessionRole.ASSISTANT
                                : com.rag.domain.enums.SessionRole.USER,
                        msg.content()));
            }
        }
        return debugRetrievalService.debug(request.kbId(), request.question(),
                request.topK(), request.minScore(), mode, history);
    }

    /**
     * 契约 DebugRetrievalRequest。
     *
     * @param mode 检索模式覆盖（R2-D1：VECTOR/HYBRID/HYBRID_RERANK，缺省取服务端配置）
     * @param history 会话历史（R6-C：可选；时间正序 [{role: user|assistant, content}]，
     *                仅用于 history-aware query rewrite 触发，不进入判定/生成上下文）
     */
    public record DebugRetrievalRequest(
            @NotNull String kbId,
            @NotBlank @Size(min = 1, max = 2000) String question,
            @Min(1) @Max(50) Integer topK,
            @Min(0) @Max(1) Double minScore,
            String mode,
            List<HistoryMessage> history) {
    }

    /** 契约 HistoryMessage（R6-C，与 eval 数据集 history 元素同构）。 */
    public record HistoryMessage(String role, String content) {
    }
}
