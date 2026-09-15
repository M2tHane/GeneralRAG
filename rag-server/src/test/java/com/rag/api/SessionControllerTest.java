package com.rag.api;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rag.api.dto.Message;
import com.rag.api.dto.Session;
import com.rag.api.dto.SessionCreate;
import com.rag.api.session.SessionController;
import com.rag.api.session.SessionService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话控制器切片测试（契约语义：201/204、404 映射、MessagePage 结构、
 * citations JSON → Citation[] 结构透出）。
 */
@ActiveProfiles("test")
@WebMvcTest(SessionController.class)
class SessionControllerTest {

    private static final String KB = UUID.randomUUID().toString();
    private static final String SESSION = UUID.randomUUID().toString();

    @Autowired MockMvc mockMvc;
    @MockBean SessionService service;

    private static Session session() {
        return new Session(SESSION, KB, "新会话", 0, LocalDateTime.now(), LocalDateTime.now());
    }

    // ------------------------------------------------------------------
    // 列表 / 创建
    // ------------------------------------------------------------------

    @Test
    void listSessionsReturns200Array() throws Exception {
        when(service.list(null)).thenReturn(List.of(session()));

        mockMvc.perform(get("/api/v1/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(SESSION))
                .andExpect(jsonPath("$[0].kbId").value(KB))
                .andExpect(jsonPath("$[0].messageCount").value(0));
    }

    @Test
    void createSessionReturns201() throws Exception {
        when(service.create(any(SessionCreate.class))).thenReturn(session());

        mockMvc.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kbId\":\"" + KB + "\",\"title\":\"产品问答\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(SESSION))
                .andExpect(jsonPath("$.title").value("新会话"));
    }

    @Test
    void createSessionWithoutKbIdReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"无知识库\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void createSessionWithUnknownKbMapsTo404() throws Exception {
        when(service.create(any(SessionCreate.class)))
                .thenThrow(new DomainException(ErrorCode.KB_NOT_FOUND));

        mockMvc.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kbId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KB_NOT_FOUND"));
    }

    // ------------------------------------------------------------------
    // 详情 / 删除
    // ------------------------------------------------------------------

    @Test
    void getSessionReturns200() throws Exception {
        when(service.get(any(UUID.class))).thenReturn(session());

        mockMvc.perform(get("/api/v1/sessions/" + SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SESSION));
    }

    @Test
    void getUnknownSessionReturns404() throws Exception {
        when(service.get(any(UUID.class)))
                .thenThrow(new DomainException(ErrorCode.SESSION_NOT_FOUND));

        mockMvc.perform(get("/api/v1/sessions/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    void deleteSessionReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/sessions/" + SESSION))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // 消息分页
    // ------------------------------------------------------------------

    @Test
    void listMessagesReturnsMessagePageInOrder() throws Exception {
        Message user = new Message(UUID.randomUUID().toString(), SESSION, "USER",
                "如何配置告警？", List.of(), "COMPLETED", null, LocalDateTime.now());
        Message assistant = new Message(UUID.randomUUID().toString(), SESSION, "ASSISTANT",
                "按以下步骤配置……",
                List.of(new com.rag.api.dto.Citation("chunk-1", UUID.randomUUID().toString(),
                        "部署手册.md", "部署指南 > 告警", 3, 0.87f)),
                "COMPLETED", null, LocalDateTime.now().plusSeconds(5));
        when(service.listMessages(any(UUID.class), eq(1), eq(20)))
                .thenReturn(new com.rag.api.dto.PageResult<>(List.of(user, assistant), 1, 20, 2));

        mockMvc.perform(get("/api/v1/sessions/" + SESSION + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].role").value("USER"))
                .andExpect(jsonPath("$.items[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.items[1].citations[0].chunkId").value("chunk-1"))
                .andExpect(jsonPath("$.items[1].citations[0].docName").value("部署手册.md"))
                .andExpect(jsonPath("$.items[1].citations[0].titlePath").value("部署指南 > 告警"))
                .andExpect(jsonPath("$.items[1].citations[0].score").value(0.87))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.total").value(2));
    }

    /** eq 静态导入遗漏会编译失败 —— 显式声明以保可读。 */
    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
