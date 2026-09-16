package com.rag.answerability;

/**
 * Judge 系统故障的二级分类（R4.1.1）：JUDGE_DEGRADED 之上的结构化原因。
 *
 * <p>此前原因只存在于 decision reason 的文本前缀（"TIMEOUT: …"），指标统计若依赖
 * {@code reason.startsWith(...)} 就会被措辞调整悄悄破坏。此枚举是机器可读的
 * 唯一事实源——reason 前缀保留仅为人类可读日志/调试展示。</p>
 */
public enum JudgeFailureType {
    /** Judge HTTP 超时（judge-timeout-seconds 内未返回）。 */
    TIMEOUT,
    /** bulkhead 容量耗尽（max-concurrent-judges + bulkhead-wait-ms 内未获得名额）。 */
    OVERLOADED,
    /** 模型调用异常（网络错误/HTTP 5xx/连接失败等非超时异常）。 */
    MODEL_ERROR,
    /** 响应非法（空内容/无 JSON/字段缺失或越界）。 */
    INVALID_RESPONSE
}
