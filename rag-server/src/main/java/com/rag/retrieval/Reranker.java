package com.rag.retrieval;

import java.util.List;

import com.rag.retrieval.model.RetrievalHit;

/**
 * 重排接入点（docs/03-技术路线.md §2 规则 3）。
 *
 * <p>调用时机：在已删文档有效性过滤、阈值标记之前（见 {@link RetrievalService}）。</p>
 *
 * <p>R2-R2：重排是外部服务，可能超时/报错/返回异常。实现<b>不得抛出中断问答链路的
 * 异常</b>，而应返回 {@link RerankOutcome#degraded()} 结果，由上层标注"已降级、未重排"。
 * 降级结果与正常重排结果不可直接比较，因此必须显式留痕（调试页与评测运行）。</p>
 */
public interface Reranker {

    /**
     * @param kbId     检索范围（供重排器做库级隔离/缓存分片）
     * @param question 本次检索使用的有效 query（R6-C.1：与 Embedding/BM25 同源——
     *                 history-aware rewrite 生效时为改写后查询，否则为原始问题；
     *                 不必然等于用户原始问题）
     * @param hits     候选命中（按融合/原分数序）
     * @return 重排结果（含是否降级；降级时顺序为传入顺序）
     */
    RerankOutcome rerank(String kbId, String question, List<RetrievalHit> hits);

    /**
     * 重排结果。
     *
     * @param hits     重排后的命中（降级时为原顺序）
     * @param degraded 是否发生降级（重排未生效）
     * @param reason   降级原因（未降级为 null）
     */
    record RerankOutcome(List<RetrievalHit> hits, boolean degraded, String reason) {

        public static RerankOutcome applied(List<RetrievalHit> hits) {
            return new RerankOutcome(hits, false, null);
        }

        public static RerankOutcome degraded(List<RetrievalHit> hits, String reason) {
            return new RerankOutcome(hits, true, reason);
        }
    }
}
