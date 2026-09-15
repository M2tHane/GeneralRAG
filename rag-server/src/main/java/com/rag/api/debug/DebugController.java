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
        return debugRetrievalService.debug(request.kbId(), request.question(),
                request.topK(), request.minScore(), mode);
    }

    /**
     * 契约 DebugRetrievalRequest。
     *
     * @param mode 检索模式覆盖（R2-D1：VECTOR/HYBRID/HYBRID_RERANK，缺省取服务端配置）
     */
    public record DebugRetrievalRequest(
            @NotNull String kbId,
            @NotBlank @Size(min = 1, max = 2000) String question,
            @Min(1) @Max(50) Integer topK,
            @Min(0) @Max(1) Double minScore,
            String mode) {
    }
}
