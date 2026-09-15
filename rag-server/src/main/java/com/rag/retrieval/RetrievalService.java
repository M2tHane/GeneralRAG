package com.rag.retrieval;

import java.util.List;

import com.rag.retrieval.model.RetrievalHit;
import org.springframework.stereotype.Service;

/**
 * 检索服务（docs/03-技术路线.md §3.2 步骤 4）。
 *
 * <p>R2-D2：检索语义已收敛到 {@link RetrievalPipeline}，本类退化为对外门面，
 * 保证问答链路/评测的既有调用点不变，同时向调试接口暴露分阶段诊断。
 * 语义只有一处实现，不再需要"两个类保持一致"的一致性守护测试。</p>
 */
@Service
public class RetrievalService {

    private final RetrievalPipeline pipeline;

    public RetrievalService(RetrievalPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * 检索一条问题（默认模式与配置）。
     *
     * @return 命中列表（分数降序、rank 从 1 起；含未过阈值项，以 passedThreshold 区分）
     */
    public List<RetrievalHit> retrieve(String kbId, String question,
                                       Integer topKOverride, Double minScoreOverride) {
        return pipeline.execute(RetrievalRequest.of(kbId, question, topKOverride, minScoreOverride))
                .hits();
    }

    /** 带分阶段诊断的检索（调试接口与评测 config_snapshot 使用）。 */
    public RetrievalPipeline.RetrievalOutcome retrieveOutcome(RetrievalRequest request) {
        return pipeline.execute(request);
    }
}
