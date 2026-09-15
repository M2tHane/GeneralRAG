package com.rag.api.document;

import java.util.List;
import java.util.UUID;

import com.rag.api.dto.Chunk;
import com.rag.api.dto.Document;
import com.rag.api.dto.DocumentDeletionResult;
import com.rag.api.dto.DocumentDetail;
import com.rag.api.dto.IngestionTask;
import com.rag.api.dto.PageResult;
import com.rag.api.dto.ParsedText;
import com.rag.api.dto.UploadAccepted;
import com.rag.api.kb.KnowledgeBaseController;
import com.rag.domain.enums.DocumentStatus;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档与入库流水线接口（契约 paths：/api/v1/knowledge-bases/{kbId}/documents
 * 与 /api/v1/documents/{docId}...）。
 *
 * <p>路径参数 docId 解析失败（非 UUID）按 404 处理（与 KnowledgeBaseController 同语义）；
 * page/pageSize 违约（非数字）由 Spring 类型绑定 → 400 INVALID_ARGUMENT。</p>
 */
@RestController
@Tag(name = "documents")
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------
    // /api/v1/knowledge-bases/{kbId}/documents
    // ------------------------------------------------------------------

    @GetMapping("/api/v1/knowledge-bases/{kbId}/documents")
    @Operation(operationId = "listDocuments", summary = "文档列表（状态筛选 + 分页）")
    public PageResult<Document> listDocuments(
            @PathVariable String kbId,
            @RequestParam(name = "status", required = false) DocumentStatus status,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        PageBounds bounds = PageBounds.of(page, pageSize);
        DocumentService.PageBundle bundle =
                service.listDocuments(KnowledgeBaseController.parseKbId(kbId), status, bounds.page(), bounds.pageSize());
        return new PageResult<>(castItems(bundle), bundle.page(), bundle.pageSize(), bundle.total());
    }

    /**
     * 上传文档（multipart）：202 受理并进入异步流水线。chunkStrategy/maxLength/overlap
     * 缺省按契约默认值（LENGTH_OVERLAP/800/100）；数值越界（maxLength∈[200,4000]、
     * overlap∈[0,500]）由 service 前的 @ModelAttribute 校验或人工校验拦截。
     */
    @PostMapping(value = "/api/v1/knowledge-bases/{kbId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(operationId = "uploadDocument", summary = "上传文档（multipart，立即返回任务受理）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "已受理")
    public ResponseEntity<UploadAccepted> upload(
            @PathVariable String kbId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "chunkStrategy", required = false) String chunkStrategy,
            @RequestParam(name = "maxLength", required = false) Integer maxLength,
            @RequestParam(name = "overlap", required = false) Integer overlap) {
        validateChunkParams(maxLength, overlap);
        // M3：chunkStrategy 白名单同步校验——非法值 202 受理后会在 worker CHUNKING 阶段
        // 以 INTERNAL_ERROR 失败，用户视角是"上传成功但永远失败且原因莫名"
        if (chunkStrategy != null && java.util.Set.of("LENGTH_OVERLAP", "STRUCTURE").contains(chunkStrategy) == false) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT,
                    "chunkStrategy 仅支持 LENGTH_OVERLAP 或 STRUCTURE：" + chunkStrategy);
        }
        UploadAccepted accepted = service.upload(
                KnowledgeBaseController.parseKbId(kbId), file, chunkStrategy, maxLength, overlap);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    }

    // ------------------------------------------------------------------
    // /api/v1/documents/{docId}...
    // ------------------------------------------------------------------

    @GetMapping("/api/v1/documents/{docId}")
    @Operation(operationId = "getDocument", summary = "文档详情")
    public DocumentDetail getDocument(@PathVariable String docId) {
        return service.getDocument(parseDocId(docId));
    }

    @DeleteMapping("/api/v1/documents/{docId}")
    @Operation(operationId = "deleteDocument", summary = "删除文档")
    public DocumentDeletionResult deleteDocument(@PathVariable String docId) {
        return service.delete(parseDocId(docId));
    }

    @GetMapping("/api/v1/documents/{docId}/parsed-text")
    @Operation(operationId = "getDocumentParsedText", summary = "解析文本预览")
    public ParsedText getParsedText(@PathVariable String docId,
                                    @RequestParam(name = "maxChars", required = false) Integer maxChars) {
        return service.getParsedText(parseDocId(docId), maxChars);
    }

    @GetMapping("/api/v1/documents/{docId}/chunks")
    @Operation(operationId = "listDocumentChunks", summary = "分块预览（分页）")
    public PageResult<Chunk> listChunks(
            @PathVariable String docId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        PageBounds bounds = PageBounds.of(page, pageSize);
        DocumentService.PageBundle bundle = service.listChunks(parseDocId(docId), bounds.page(), bounds.pageSize());
        return new PageResult<>(castItems(bundle), bundle.page(), bundle.pageSize(), bundle.total());
    }

    @GetMapping("/api/v1/documents/{docId}/task")
    @Operation(operationId = "getDocumentTask", summary = "当前入库任务")
    public IngestionTask getTask(@PathVariable String docId) {
        return service.getTask(parseDocId(docId));
    }

    @PostMapping("/api/v1/documents/{docId}/retry")
    @Operation(operationId = "retryDocumentIngestion", summary = "重试失败的入库任务")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "已受理")
    public ResponseEntity<IngestionTask> retry(@PathVariable String docId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.retry(parseDocId(docId)));
    }

    // ------------------------------------------------------------------
    // 版本管理（R3-P3）
    // ------------------------------------------------------------------

    @GetMapping("/api/v1/documents/{docId}/versions")
    @Operation(operationId = "listDocumentVersions", summary = "版本组列表（版本号升序）")
    public java.util.List<Document> listVersions(@PathVariable String docId) {
        return service.listVersions(parseDocId(docId));
    }

    @PostMapping("/api/v1/documents/{docId}/activate")
    @Operation(operationId = "activateDocumentVersion", summary = "激活指定版本（组内其它版本自动失活）")
    public Document activate(@PathVariable String docId) {
        return service.activate(parseDocId(docId));
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static void validateChunkParams(Integer maxLength, Integer overlap) {
        if (maxLength != null && (maxLength < 200 || maxLength > 4000)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT,
                    "maxLength 必须在 [200,4000] 内",
                    List.of(new DomainException.Detail("maxLength",
                            "实际值 " + maxLength + "，契约范围 [200,4000]")));
        }
        if (overlap != null && (overlap < 0 || overlap > 500)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT,
                    "overlap 必须在 [0,500] 内",
                    List.of(new DomainException.Detail("overlap",
                            "实际值 " + overlap + "，契约范围 [0,500]")));
        }
    }

    /** 非 UUID 路径参数 → 404（资源不存在；与 kbId 同语义）。 */
    static UUID parseDocId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new DomainException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
    }

    /** 分页参数归一：越界值夹取回契约范围（page≥1，pageSize∈[1,100]）。 */
    private record PageBounds(int page, int pageSize) {
        static PageBounds of(int page, int pageSize) {
            return new PageBounds(Math.max(1, page), Math.min(100, Math.max(1, pageSize)));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> castItems(DocumentService.PageBundle bundle) {
        return (List<T>) bundle.items();
    }
}
