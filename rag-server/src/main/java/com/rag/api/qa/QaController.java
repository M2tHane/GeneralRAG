package com.rag.api.qa;

import com.rag.api.dto.ApiError;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import com.rag.llm.ChatStreamService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

/**
 * 知识问答 SSE 端点（契约 /api/v1/qa/stream、/api/v1/qa/cancel 的正式实现）。
 * 流逻辑全部在 {@link ChatStreamService}（Task 5），此处仅薄封装。
 */
@RestController
@RequestMapping("/api/v1/qa")
public class QaController {

    private final ChatStreamService chatStreamService;

    public QaController(ChatStreamService chatStreamService) {
        this.chatStreamService = chatStreamService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(operationId = "qaStream", summary = "知识问答（SSE 流式）")
    public SseEmitter stream(@jakarta.validation.Valid @RequestBody ChatStreamService.QaCommand command) {
        return chatStreamService.startStream(command);
    }

    @PostMapping("/cancel")
    @Operation(operationId = "qaCancel", summary = "取消进行中的问答流")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "已受理取消")
    public ResponseEntity<Map<String, Object>> cancel(@RequestBody Map<String, String> body) {
        String clientRequestId = body == null ? null : body.get("clientRequestId");
        if (clientRequestId == null || clientRequestId.isBlank()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "clientRequestId 不能为空");
        }
        // 契约 format: uuid——非法格式按 400（而非静默当作自定义键）
        try {
            UUID.fromString(clientRequestId);
        } catch (IllegalArgumentException e) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "clientRequestId 必须是 UUID：" + clientRequestId);
        }
        try {
            chatStreamService.cancel(clientRequestId);
        } catch (DomainException e) {
            throw e;
        }
        // 契约：202 {clientRequestId, canceled:true}
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("clientRequestId", clientRequestId, "canceled", true));
    }
}
