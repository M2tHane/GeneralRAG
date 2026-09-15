package com.rag.api.session;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.dto.Citation;
import com.rag.api.dto.Message;
import com.rag.api.dto.PageResult;
import com.rag.api.dto.Session;
import com.rag.api.dto.SessionCreate;
import com.rag.api.kb.KnowledgeBaseController;
import com.rag.domain.entity.ChatMessageEntity;
import com.rag.domain.entity.ChatSessionEntity;
import com.rag.domain.entity.KnowledgeBaseEntity;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import com.rag.storage.repository.ChatMessageRepository;
import com.rag.storage.repository.ChatSessionRepository;
import com.rag.storage.repository.KnowledgeBaseRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话应用服务（API 层）：会话 CRUD 与消息分页读取。
 * 消息创建由 QA 链路（Task 5）负责；本服务只读消息（citations JSON 反序列化为 Citation[]）。
 */
@Service
public class SessionService {

    private final KnowledgeBaseRepository kbRepository;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    public SessionService(KnowledgeBaseRepository kbRepository,
                          ChatSessionRepository sessionRepository,
                          ChatMessageRepository messageRepository,
                          ObjectMapper objectMapper) {
        this.kbRepository = kbRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
    }

    /** 会话列表（可按 kbId 过滤），按最近更新排序（Master–Detail 左栏）。 */
    @Transactional(readOnly = true)
    public List<Session> list(UUID kbId) {
        Iterable<ChatSessionEntity> entities = kbId == null
                ? sessionRepository.findAll()
                : sessionRepository.findByKbIdOrderByUpdatedAtDesc(kbId.toString(),
                        Pageable.unpaged());
        List<Session> sessions = new ArrayList<>();
        for (ChatSessionEntity entity : entities) {
            sessions.add(toSession(entity));
        }
        return sessions;
    }

    @Transactional
    public Session create(SessionCreate request) {
        UUID kbId = request.kbId();
        KnowledgeBaseEntity kb = kbRepository.findById(kbId.toString())
                .orElseThrow(() -> new DomainException(ErrorCode.KB_NOT_FOUND));
        ChatSessionEntity entity = new ChatSessionEntity();
        entity.setKbId(kb.getId());
        entity.setTitle(request.title() == null || request.title().isBlank()
                ? "新会话" : request.title());
        return toSession(sessionRepository.saveAndFlush(entity));
    }

    @Transactional(readOnly = true)
    public Session get(UUID sessionId) {
        return toSession(requireSession(sessionId));
    }

    /** 删除会话：消息经外键 ON DELETE CASCADE 级联移除。 */
    @Transactional
    public void delete(UUID sessionId) {
        sessionRepository.delete(requireSession(sessionId));
    }

    /** 消息正序分页（契约：按时间正序）。 */
    @Transactional(readOnly = true)
    public PageResult<Message> listMessages(UUID sessionId, int page, int pageSize) {
        requireSession(sessionId);
        List<ChatMessageEntity> content = messageRepository.findBySessionIdOrderByCreatedAtAsc(
                sessionId.toString(), PageRequest.of(page - 1, pageSize));
        List<Message> items = new ArrayList<>(content.size());
        for (ChatMessageEntity entity : content) {
            items.add(toMessage(entity));
        }
        // total：该会话全部消息数（与分页窗口解耦）
        long total = messageRepository.countBySessionId(sessionId.toString());
        return new PageResult<>(items, page, pageSize, total);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private ChatSessionEntity requireSession(UUID sessionId) {
        return sessionRepository.findById(sessionId.toString())
                .orElseThrow(() -> new DomainException(ErrorCode.SESSION_NOT_FOUND));
    }

    private Session toSession(ChatSessionEntity entity) {
        return new Session(entity.getId(), entity.getKbId(), entity.getTitle(),
                messageRepository.countBySessionId(entity.getId()),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    /** citations JSON 列 → 契约 Citation[]；反序列化失败按数据错误处理（不静默吞掉）。 */
    private Message toMessage(ChatMessageEntity entity) {
        List<Citation> citations = objectMapper.convertValue(
                entity.getCitations() == null ? List.of() : entity.getCitations(),
                new TypeReference<List<Citation>>() {
                });
        Message.ErrorSummary error = entity.getErrorCode() == null
                ? null
                : Message.ErrorSummary.of(entity.getErrorCode());
        return new Message(entity.getId(), entity.getSessionId(), entity.getRole().name(),
                entity.getContent(), citations, entity.getStatus().name(), error,
                entity.getCreatedAt());
    }
}
