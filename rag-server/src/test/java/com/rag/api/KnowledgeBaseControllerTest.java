package com.rag.api;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.rag.api.dto.DeletionSummary;
import com.rag.api.dto.KnowledgeBase;
import com.rag.api.dto.KnowledgeBaseCreate;
import com.rag.api.dto.KnowledgeBaseUpdate;
import com.rag.api.kb.KnowledgeBaseController;
import com.rag.api.kb.KnowledgeBaseService;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识库控制器切片测试（契约语义：confirm 语义、404/409 映射、字段校验）。
 */
@ActiveProfiles("test")
@WebMvcTest(KnowledgeBaseController.class)
class KnowledgeBaseControllerTest {

    private static final String BASE = "/api/v1/knowledge-bases";

    @Autowired MockMvc mockMvc;
    @MockBean KnowledgeBaseService service;

    private static KnowledgeBase kb(String name) {
        return new KnowledgeBase(UUID.randomUUID().toString(), name, "描述",
                0, 0, LocalDateTime.now(), LocalDateTime.now());
    }

    // ------------------------------------------------------------------
    // POST 创建
    // ------------------------------------------------------------------

    @Test
    void createReturns201WithBody() throws Exception {
        KnowledgeBase created = kb("产品手册");
        when(service.create(any(KnowledgeBaseCreate.class))).thenReturn(created);

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"产品手册\",\"description\":\"描述\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(created.id()))
                .andExpect(jsonPath("$.name").value("产品手册"))
                .andExpect(jsonPath("$.documentCount").value(0))
                .andExpect(jsonPath("$.chunkCount").value(0));
    }

    @Test
    void createWithBlankNameReturns400InvalidArgument() throws Exception {
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void createWithNameOver100Returns400() throws Exception {
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "x".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.details[0].field").value("name"));
    }

    @Test
    void createWithDuplicatedNameMapsTo409() throws Exception {
        when(service.create(any(KnowledgeBaseCreate.class)))
                .thenThrow(new DomainException(ErrorCode.KB_NAME_DUPLICATED));

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"重复名\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KB_NAME_DUPLICATED"));
    }

    // ------------------------------------------------------------------
    // GET 列表 / 详情
    // ------------------------------------------------------------------

    @Test
    void listReturns200Array() throws Exception {
        when(service.list()).thenReturn(List.of(kb("A"), kb("B")));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void getWithUnknownKbIdReturns404KbNotFound() throws Exception {
        when(service.get(any(UUID.class)))
                .thenThrow(new DomainException(ErrorCode.KB_NOT_FOUND));

        mockMvc.perform(get(BASE + "/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KB_NOT_FOUND"));
    }

    /** 非 UUID 路径参数 → 404（资源不存在语义，见 KnowledgeBaseController 注释）。 */
    @Test
    void getWithNonUuidKbIdReturns404() throws Exception {
        mockMvc.perform(get(BASE + "/not-a-uuid"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KB_NOT_FOUND"));
    }

    // ------------------------------------------------------------------
    // PATCH 部分更新
    // ------------------------------------------------------------------

    @Test
    void patchReturns200() throws Exception {
        KnowledgeBase updated = kb("新名称");
        when(service.update(any(UUID.class), any(KnowledgeBaseUpdate.class))).thenReturn(updated);

        mockMvc.perform(patch(BASE + "/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新名称\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("新名称"));
    }

    @Test
    void patchWithDuplicatedNameMapsTo409() throws Exception {
        when(service.update(any(UUID.class), any(KnowledgeBaseUpdate.class)))
                .thenThrow(new DomainException(ErrorCode.KB_NAME_DUPLICATED));

        mockMvc.perform(patch(BASE + "/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"重复名\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KB_NAME_DUPLICATED"));
    }

    @Test
    void patchWithEmptyNameReturns400() throws Exception {
        // @Size(min=1)：空串即非法（未提交字段为 null 合法）
        mockMvc.perform(patch(BASE + "/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    // ------------------------------------------------------------------
    // DELETE confirm 语义（契约：confirm enum [true]，缺失/非 true → 400）
    // ------------------------------------------------------------------

    @Test
    void deleteWithoutConfirmReturns400ConfirmationRequired() throws Exception {
        mockMvc.perform(delete(BASE + "/" + UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONFIRMATION_REQUIRED"));

        verify(service, never()).delete(any(UUID.class));
    }

    @Test
    void deleteWithConfirmFalseReturns400() throws Exception {
        mockMvc.perform(delete(BASE + "/" + UUID.randomUUID()).param("confirm", "false"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONFIRMATION_REQUIRED"));

        verify(service, never()).delete(any(UUID.class));
    }

    @Test
    void deleteWithConfirmTrueReturns200DeletionSummary() throws Exception {
        UUID kbId = UUID.randomUUID();
        DeletionSummary summary =
                new DeletionSummary(kbId.toString(), 3, 42, 2, 9, 2);
        when(service.delete(kbId)).thenReturn(summary);

        mockMvc.perform(delete(BASE + "/" + kbId).param("confirm", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kbId").value(kbId.toString()))
                .andExpect(jsonPath("$.documentsDeleted").value(3))
                .andExpect(jsonPath("$.chunksDeleted").value(42))
                .andExpect(jsonPath("$.sessionsDeleted").value(2))
                .andExpect(jsonPath("$.messagesDeleted").value(9))
                .andExpect(jsonPath("$.cleanupTasksAccepted").value(2));
    }

    @Test
    void deleteUnknownKbReturns404() throws Exception {
        when(service.delete(any(UUID.class)))
                .thenThrow(new DomainException(ErrorCode.KB_NOT_FOUND));

        mockMvc.perform(delete(BASE + "/" + UUID.randomUUID()).param("confirm", "true"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KB_NOT_FOUND"));
    }
}
