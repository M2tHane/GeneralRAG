package com.rag.api.dto;

import java.time.LocalDateTime;

/**
 * 会话 DTO（契约 Session）。messageCount 由 service 层聚合。
 */
public record Session(
        String id,
        String kbId,
        String title,
        long messageCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
