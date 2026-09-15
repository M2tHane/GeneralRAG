package com.rag.domain.enums;

/**
 * 分块策略（contracts/openapi.yaml ChunkStrategy）。
 */
public enum ChunkStrategy {
    /** 按长度 + 相邻重叠切分。 */
    LENGTH_OVERLAP,
    /** 按标题/段落结构切分，单块超上限再按长度封顶。 */
    STRUCTURE
}
