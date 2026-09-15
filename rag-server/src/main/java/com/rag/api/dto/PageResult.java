package com.rag.api.dto;

import java.util.List;

/**
 * 通用分页信封（契约 DocumentPage / ChunkPage / MessagePage 共同结构
 * {items, page, pageSize, total}）。单一泛型 record 避免三个同构 DTO 重复；
 * JSON 序列化后字段名与契约一致。
 */
public record PageResult<T>(List<T> items, int page, int pageSize, long total) {
}
