package com.rag.api;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.rag.api.document.DocumentController;
import com.rag.api.document.DocumentService;
import com.rag.api.dto.Chunk;
import com.rag.api.dto.Document;
import com.rag.api.dto.DocumentDeletionResult;
import com.rag.api.dto.DocumentDetail;
import com.rag.api.dto.IngestionTask;
import com.rag.api.dto.ParsedText;
import com.rag.api.dto.UploadAccepted;
import com.rag.domain.enums.DocumentStatus;
import com.rag.domain.enums.PipelineStage;
import com.rag.domain.enums.TaskStatus;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文档控制器切片测试（契约语义：202 受理、415/413/409 映射、parsed-text 409、
 * retry 409、分页结构 {items,page,pageSize,total}）。
 */
@ActiveProfiles("test")
@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

    private static final String KB = UUID.randomUUID().toString();
    private static final String DOC = UUID.randomUUID().toString();

    @Autowired MockMvc mockMvc;
    @MockBean DocumentService service;

    private static Document doc() {
        return new Document(DOC, KB, "部署手册.md", "MD", 1024,
                DocumentStatus.QUEUED, PipelineStage.QUEUED, 0,
                DOC, 1, true,
                LocalDateTime.now(), LocalDateTime.now());
    }

    private static IngestionTask task() {
        return new IngestionTask(UUID.randomUUID().toString(), DOC,
                TaskStatus.QUEUED.name(), PipelineStage.QUEUED, 1,
                null, null, LocalDateTime.now(), null, null);
    }

    // ------------------------------------------------------------------
    // 上传
    // ------------------------------------------------------------------

    @Test
    void uploadReturns202WithDocumentAndTask() throws Exception {
        when(service.upload(any(UUID.class), any(), isNull(), isNull(), isNull()))
                .thenReturn(new UploadAccepted(doc(), task()));

        mockMvc.perform(multipart("/api/v1/knowledge-bases/" + KB + "/documents")
                        .file(new MockMultipartFile("file", "部署手册.md",
                                MediaType.TEXT_PLAIN_VALUE, "# 标题\n内容".getBytes())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.document.id").value(DOC))
                .andExpect(jsonPath("$.document.kbId").value(KB))
                .andExpect(jsonPath("$.document.status").value("QUEUED"))
                .andExpect(jsonPath("$.task.documentId").value(DOC))
                .andExpect(jsonPath("$.task.status").value("QUEUED"))
                .andExpect(jsonPath("$.task.attempt").value(1));
    }

    @Test
    void uploadUnsupportedExtensionMapsTo415() throws Exception {
        when(service.upload(any(UUID.class), any(), isNull(), isNull(), isNull()))
                .thenThrow(new DomainException(ErrorCode.UNSUPPORTED_FILE_TYPE));

        mockMvc.perform(multipart("/api/v1/knowledge-bases/" + KB + "/documents")
                        .file(new MockMultipartFile("file", "malware.exe",
                                MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[]{1})))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
    }

    @Test
    void uploadTooLargeMapsTo413() throws Exception {
        when(service.upload(any(UUID.class), any(), isNull(), isNull(), isNull()))
                .thenThrow(new DomainException(ErrorCode.FILE_TOO_LARGE));

        mockMvc.perform(multipart("/api/v1/knowledge-bases/" + KB + "/documents")
                        .file(new MockMultipartFile("file", "big.pdf",
                                MediaType.APPLICATION_PDF_VALUE, new byte[]{1})))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void uploadDuplicateMapsTo409WithExistingDocumentIdDetail() throws Exception {
        when(service.upload(any(UUID.class), any(), isNull(), isNull(), isNull()))
                .thenThrow(new DomainException(ErrorCode.DUPLICATE_DOCUMENT,
                        "内容相同的文档已存在于该知识库",
                        List.of(new DomainException.Detail("file",
                                "existingDocumentId=" + DOC))));

        mockMvc.perform(multipart("/api/v1/knowledge-bases/" + KB + "/documents")
                        .file(new MockMultipartFile("file", "部署手册.md",
                                MediaType.TEXT_PLAIN_VALUE, "# 标题\n内容".getBytes())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DOCUMENT"))
                .andExpect(jsonPath("$.details[0].field").value("file"))
                .andExpect(jsonPath("$.details[0].issue")
                        .value("existingDocumentId=" + DOC));
    }

    @Test
    void uploadToUnknownKbMapsTo404() throws Exception {
        when(service.upload(any(UUID.class), any(), isNull(), isNull(), isNull()))
                .thenThrow(new DomainException(ErrorCode.KB_NOT_FOUND));

        mockMvc.perform(multipart("/api/v1/knowledge-bases/" + UUID.randomUUID() + "/documents")
                        .file(new MockMultipartFile("file", "a.txt",
                                MediaType.TEXT_PLAIN_VALUE, "内容".getBytes())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KB_NOT_FOUND"));
    }

    // ------------------------------------------------------------------
    // 列表 / 详情
    // ------------------------------------------------------------------

    @Test
    void listDocumentsReturnsPagedEnvelope() throws Exception {
        when(service.listDocuments(any(UUID.class), isNull(), eq(1), eq(20)))
                .thenReturn(new DocumentService.PageBundle(List.of(doc()), 1, 20, 1));

        mockMvc.perform(get("/api/v1/knowledge-bases/" + KB + "/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(DOC))
                .andExpect(jsonPath("$.items[0].fileType").value("MD"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void listDocumentsWithStatusFilterPassesStatus() throws Exception {
        when(service.listDocuments(any(UUID.class), eq(DocumentStatus.FAILED), eq(1), eq(20)))
                .thenReturn(new DocumentService.PageBundle(List.of(), 1, 20, 0));

        mockMvc.perform(get("/api/v1/knowledge-bases/" + KB + "/documents")
                        .param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void listDocumentsWithIllegalStatusMapsTo400() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge-bases/" + KB + "/documents")
                        .param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void getDocumentReturnsDetailWithTaskSummary() throws Exception {
        when(service.getDocument(any(UUID.class)))
                .thenReturn(new DocumentDetail(DOC, KB, "部署手册.md", "MD", 1024,
                        "COMPLETED", "COMPLETED", 6,
                        DOC, 1, true,
                        LocalDateTime.now(), LocalDateTime.now(),
                        new com.rag.api.dto.ChunkingConfig(
                                com.rag.domain.enums.ChunkStrategy.STRUCTURE, 800, 100),
                        "a".repeat(64), null, null, task()));

        mockMvc.perform(get("/api/v1/documents/" + DOC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(DOC))
                .andExpect(jsonPath("$.contentSha256").value("a".repeat(64)))
                .andExpect(jsonPath("$.chunkConfig.strategy").value("STRUCTURE"))
                .andExpect(jsonPath("$.chunkConfig.maxLength").value(800))
                .andExpect(jsonPath("$.task.id").isNotEmpty());
    }

    @Test
    void getUnknownDocumentReturns404DocumentNotFound() throws Exception {
        when(service.getDocument(any(UUID.class)))
                .thenThrow(new DomainException(ErrorCode.DOCUMENT_NOT_FOUND));

        mockMvc.perform(get("/api/v1/documents/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    // ------------------------------------------------------------------
    // 解析文本 / 分块 / 任务
    // ------------------------------------------------------------------

    @Test
    void parsedTextReturns200WithTruncationFlag() throws Exception {
        when(service.getParsedText(any(UUID.class), eq(100)))
                .thenReturn(new ParsedText(DOC, "截断后的内容", 6, true));

        mockMvc.perform(get("/api/v1/documents/" + DOC + "/parsed-text")
                        .param("maxChars", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docId").value(DOC))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.charCount").value(6));
    }

    @Test
    void parsedTextNotReadyMapsTo409() throws Exception {
        when(service.getParsedText(any(UUID.class), any()))
                .thenThrow(new DomainException(ErrorCode.PARSED_TEXT_NOT_READY));

        mockMvc.perform(get("/api/v1/documents/" + DOC + "/parsed-text"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARSED_TEXT_NOT_READY"));
    }

    @Test
    void chunksReturnsPagedEnvelope() throws Exception {
        when(service.listChunks(any(UUID.class), eq(1), eq(20)))
                .thenReturn(new DocumentService.PageBundle(
                        List.of(new Chunk(DOC + "-c0000", DOC, 0, "部署手册", null, 42, "正文")), 1, 20, 1));

        mockMvc.perform(get("/api/v1/documents/" + DOC + "/chunks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(DOC + "-c0000"))
                .andExpect(jsonPath("$.items[0].seq").value(0))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void getTaskReturns200() throws Exception {
        when(service.getTask(any(UUID.class))).thenReturn(task());

        mockMvc.perform(get("/api/v1/documents/" + DOC + "/task"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(DOC))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    // ------------------------------------------------------------------
    // 重试 / 删除
    // ------------------------------------------------------------------

    @Test
    void retryReturns202() throws Exception {
        when(service.retry(any(UUID.class)))
                .thenReturn(new IngestionTask(UUID.randomUUID().toString(), DOC,
                        TaskStatus.QUEUED.name(), PipelineStage.PARSING, 2,
                        PipelineStage.EMBEDDING, "旧失败原因",
                        LocalDateTime.now(), LocalDateTime.now(), null));

        mockMvc.perform(post("/api/v1/documents/" + DOC + "/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attempt").value(2))
                .andExpect(jsonPath("$.failureStage").value("EMBEDDING"));
    }

    @Test
    void retryNonFailedTaskMapsTo409() throws Exception {
        when(service.retry(any(UUID.class)))
                .thenThrow(new DomainException(ErrorCode.TASK_NOT_RETRYABLE));

        mockMvc.perform(post("/api/v1/documents/" + DOC + "/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_RETRYABLE"));
    }

    @Test
    void deleteDocumentReturns200WithDeletionResult() throws Exception {
        when(service.delete(any(UUID.class)))
                .thenReturn(new DocumentDeletionResult(DOC, 6, 2));

        mockMvc.perform(delete("/api/v1/documents/" + DOC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docId").value(DOC))
                .andExpect(jsonPath("$.chunksDeleted").value(6))
                .andExpect(jsonPath("$.cleanupTasksAccepted").value(2));

        verify(service).delete(any(UUID.class));
    }

    /** 非 UUID docId → 404（资源不存在语义）。 */
    @Test
    void getWithNonUuidDocIdReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/documents/not-a-uuid"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }
}
