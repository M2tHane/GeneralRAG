package com.rag.api.dto;

import com.rag.domain.enums.ChunkStrategy;

/**
 * 分块配置（契约 ChunkingConfig{strategy, maxLength, overlap}），
 * 与 document.chunk_config JSON 列结构一致。
 */
public record ChunkingConfig(
        ChunkStrategy strategy,
        int maxLength,
        int overlap) {
}
