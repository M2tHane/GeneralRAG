package com.rag.api.session;

import java.util.List;
import java.util.UUID;

import com.rag.api.dto.Message;
import com.rag.api.dto.PageResult;
import com.rag.api.dto.Session;
import com.rag.api.dto.SessionCreate;
import com.rag.api.kb.KnowledgeBaseController;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话管理接口（契约 paths：/api/v1/sessions）。
 * 路径参数 sessionId 非 UUID → 404（与 kbId/docId 同语义）。
 */
@RestController
@RequestMapping("/api/v1/sessions")
@Tag(name = "sessions")
public class SessionController {

    private final SessionService service;

    public SessionController(SessionService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "listSessions", summary = "会话列表（可按知识库过滤）")
    public List<Session> list(@RequestParam(name = "kbId", required = false) String kbId) {
        return service.list(kbId == null ? null : KnowledgeBaseController.parseKbId(kbId));
    }

    @PostMapping
    @Operation(operationId = "createSession", summary = "新建会话")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "成功")
    public ResponseEntity<Session> create(@Valid @RequestBody SessionCreate request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @GetMapping("/{sessionId}")
    @Operation(operationId = "getSession", summary = "会话详情")
    public Session get(@PathVariable String sessionId) {
        return service.get(parseSessionId(sessionId));
    }

    @DeleteMapping("/{sessionId}")
    @Operation(operationId = "deleteSession", summary = "删除会话（级联删除消息）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "成功")
    public ResponseEntity<Void> delete(@PathVariable String sessionId) {
        service.delete(parseSessionId(sessionId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{sessionId}/messages")
    @Operation(operationId = "listMessages", summary = "会话消息（按时间正序，分页）")
    public PageResult<Message> listMessages(
            @PathVariable String sessionId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(100, Math.max(1, pageSize));
        return service.listMessages(parseSessionId(sessionId), safePage, safePageSize);
    }

    /** 非 UUID 路径参数 → 404（资源不存在；与 kbId/docId 同语义）。 */
    static UUID parseSessionId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new DomainException(ErrorCode.SESSION_NOT_FOUND);
        }
    }
}
