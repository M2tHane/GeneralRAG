package com.rag.api.kb;

import java.util.List;
import java.util.UUID;

import com.rag.api.dto.DeletionSummary;
import com.rag.api.dto.KnowledgeBaseCreate;
import com.rag.api.dto.KnowledgeBase;
import com.rag.api.dto.KnowledgeBaseUpdate;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库管理接口（契约 paths：/api/v1/knowledge-bases）。
 *
 * <p>路径参数 kbId 解析失败（非 UUID）按 404 KB_NOT_FOUND 处理：路径标识的资源不存在，
 * 而非客户端提交了可修正的字段值，二者语义不同。</p>
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
@Tag(name = "knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "listKnowledgeBases", summary = "知识库列表")
    public List<KnowledgeBase> list() {
        return service.list();
    }

    @PostMapping
    @Operation(operationId = "createKnowledgeBase", summary = "创建知识库")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "成功")
    public ResponseEntity<KnowledgeBase> create(@Valid @RequestBody KnowledgeBaseCreate request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @GetMapping("/{kbId}")
    @Operation(operationId = "getKnowledgeBase", summary = "知识库详情")
    public KnowledgeBase get(@PathVariable String kbId) {
        return service.get(parseKbId(kbId));
    }

    @PatchMapping("/{kbId}")
    @Operation(operationId = "updateKnowledgeBase", summary = "重命名/修改描述")
    public KnowledgeBase update(@PathVariable String kbId,
                                   @Valid @RequestBody KnowledgeBaseUpdate request) {
        return service.update(parseKbId(kbId), request);
    }

    /**
     * 删除（需显式确认）：契约 confirm 参数 required=true 且 enum [true]，
     * 缺失/非 true 统一 400 CONFIRMATION_REQUIRED（明确人读文案，而非依赖
     * 参数绑定Missing参数错误，保证错误码稳定）。
     */
    @DeleteMapping("/{kbId}")
    @Operation(operationId = "deleteKnowledgeBase", summary = "删除知识库（需显式确认）")
    public DeletionSummary delete(@PathVariable String kbId,
                                  @RequestParam(name = "confirm", required = false) Boolean confirm) {
        if (!Boolean.TRUE.equals(confirm)) {
            throw new DomainException(ErrorCode.CONFIRMATION_REQUIRED,
                    "删除知识库需要显式确认：请追加查询参数 confirm=true");
        }
        return service.delete(parseKbId(kbId));
    }

    /** 非 UUID 路径参数 → 404（资源不存在；见类注释）。供 api 层其他 controller 复用。 */
    public static UUID parseKbId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new DomainException(ErrorCode.KB_NOT_FOUND);
        }
    }
}
