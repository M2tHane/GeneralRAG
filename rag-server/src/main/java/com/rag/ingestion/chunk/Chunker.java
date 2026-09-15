package com.rag.ingestion.chunk;

import java.util.List;

import com.rag.domain.enums.ChunkStrategy;
import com.rag.ingestion.parse.ParsedDocument;

/**
 * 分块器接口（路线 §2：chunk 包，两个实现）。
 * 共同约束：chunkId 确定性 {@code {docId}-c%04d} 从 0 起；空文档 → 0 块不报错。
 */
public interface Chunker {

    /** 本分块器对应的策略（路由依据）。 */
    ChunkStrategy strategy();

    /**
     * 分块。
     *
     * @param doc   解析产物（含文档名，作 titlePath 前缀）
     * @param cfg   分块参数
     * @param docId 文档 id（chunkId 前缀）
     */
    List<ChunkDraft> chunk(ParsedDocument doc, ChunkingConfig cfg, String docId);
}
