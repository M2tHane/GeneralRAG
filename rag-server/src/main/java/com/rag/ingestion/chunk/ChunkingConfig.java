package com.rag.ingestion.chunk;

import java.util.Map;

import com.rag.domain.enums.ChunkStrategy;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;

/**
 * 分块参数（内部模型，结构同契约 ChunkingConfig{strategy, maxLength, overlap}）。
 *
 * <p>API 层的契约约束（maxLength 200-4000、overlap 0-500）由 Task 4 的 DTO 校验负责；
 * 本类只做分块算法的数学前提校验：maxLength > 0 且 overlap < maxLength
 * （overlap ≥ maxLength 时滑窗无法推进 → INVALID_ARGUMENT）。</p>
 */
public record ChunkingConfig(ChunkStrategy strategy, int maxLength, int overlap) {

    public ChunkingConfig {
        if (strategy == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "分块策略不能为空");
        }
        if (maxLength <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "分块长度上限必须为正数：" + maxLength);
        }
        if (overlap < 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "分块重叠必须 ≥ 0：" + overlap);
        }
        if (overlap >= maxLength) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT,
                    "分块重叠（overlap=" + overlap + "）必须小于块长上限（maxLength=" + maxLength + "）");
        }
    }

    /** 从 document.chunk_config JSON 列（结构同契约 ChunkingConfig）还原。 */
    public static ChunkingConfig fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "文档缺少分块配置（chunk_config）");
        }
        Object strategy = map.get("strategy");
        Object maxLength = map.get("maxLength");
        Object overlap = map.get("overlap");
        if (!(strategy instanceof String strategyName) || !(maxLength instanceof Number maxNum)
                || !(overlap instanceof Number overlapNum)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT,
                    "分块配置不合法（需要 strategy/maxLength/overlap）：" + map);
        }
        try {
            return new ChunkingConfig(ChunkStrategy.valueOf(strategyName),
                    maxNum.intValue(), overlapNum.intValue());
        } catch (IllegalArgumentException e) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "分块策略不合法：" + strategyName);
        }
    }
}
