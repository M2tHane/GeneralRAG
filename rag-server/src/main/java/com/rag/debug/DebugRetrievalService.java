package com.rag.debug;

import java.util.ArrayList;
import java.util.List;

import com.rag.answerability.AnswerabilityDecision;
import com.rag.answerability.AnswerabilityInput;
import com.rag.answerability.AnswerabilityPolicy;
import com.rag.config.RagProperties;
import com.rag.retrieval.ContextAssembler;
import com.rag.retrieval.RetrievalPipeline;
import com.rag.retrieval.RetrievalRequest;
import com.rag.retrieval.RetrievalService;
import com.rag.retrieval.model.Context;
import com.rag.retrieval.model.RetrievalHit;
import com.rag.retrieval.model.RetrievalMode;
import com.rag.retrieval.model.RetrievalStages;
import com.rag.storage.es.EsHit;
import org.springframework.stereotype.Service;

/**
 * 检索调试服务（Task 6 → R2-D2 重构 → R4 Answerability 扩展）。
 *
 * <p>第一轮本类为拿到分段耗时，逐行复刻了 {@code RetrievalService} 的检索语义，
 * 只靠一致性测试守护。第二轮改造后检索语义唯一收敛在
 * {@link RetrievalPipeline}，本类退化为薄封装：<b>调用同一条流水线</b>，把
 * {@link RetrievalPipeline.RetrievalDiagnostics} 映射为调试响应 DTO。</p>
 *
 * <p>因此"调试页与问答/评测结果一致"不再依赖两个实现保持同步，而是同一实现。</p>
 *
 * <p>R4：调试接口补齐<b>证据充分性判定</b>——与问答/评测共用
 * {@link AnswerabilityPolicy}，判定输入就是本响应中 context.text 的分块集合
 * （与生成上下文同源）。由此一条 Bad Case 的完整归因链可观测：
 * 召回（hits）→ 排序（stages）→ 证据（context）→ 判定（answerability）→ 生成。</p>
 */
@Service
public class DebugRetrievalService {

    private final RetrievalService retrievalService;
    private final ContextAssembler contextAssembler;
    private final RagProperties ragProperties;
    private final AnswerabilityPolicy answerabilityPolicy;

    public DebugRetrievalService(RetrievalService retrievalService,
                                 ContextAssembler contextAssembler,
                                 RagProperties ragProperties,
                                 AnswerabilityPolicy answerabilityPolicy) {
        this.retrievalService = retrievalService;
        this.contextAssembler = contextAssembler;
        this.ragProperties = ragProperties;
        this.answerabilityPolicy = answerabilityPolicy;
    }

    /**
     * 调试检索：复用统一流水线，输出分段耗时 + 分阶段位次 + 上下文 + Answerability 判定。
     *
     * @param modeOverride 检索模式覆盖（null = 取配置）
     */
    public DebugResult.DebugRetrievalResult debug(String kbId, String question,
                                                  Integer topKOverride, Double minScoreOverride,
                                                  RetrievalMode modeOverride) {
        RetrievalPipeline.RetrievalOutcome outcome = retrievalService.retrieveOutcome(
                new RetrievalRequest(kbId, question, topKOverride, minScoreOverride,
                        modeOverride, null));
        List<RetrievalHit> ranked = outcome.hits();
        RetrievalPipeline.RetrievalDiagnostics diag = outcome.diagnostics();

        List<RetrievalHit> passed = ranked.stream().filter(RetrievalHit::passedThreshold).toList();
        Context context = contextAssembler.assemble(passed);

        // R4/R4.1：与问答同一条判定路径——低分直拒 → Judge；判定输入=同一 Context 实例
        AnswerabilityDecision decision = answerabilityPolicy.evaluate(
                AnswerabilityInput.of(question, context, ranked, diag.mode(), diag.rerankApplied()));

        RagProperties.Rerank rerankCfg = ragProperties.getRetrieval().getRerank();
        boolean rerankEnabled = diag.mode() == RetrievalMode.HYBRID_RERANK;
        String rerankModel = rerankEnabled ? rerankCfg.getModelName() : "";
        boolean answerabilityEnabled = ragProperties.getRetrieval().getAnswerability().isEnabled();
        String judgeModel = answerabilityEnabled
                ? ragProperties.getModels().getChat().getModelName() : "";

        return new DebugResult.DebugRetrievalResult(
                new DebugResult.EffectiveConfig(diag.topK(), diag.minScore(),
                        ragProperties.getModels().getEmbedding().getModelName(),
                        ragProperties.getModels().getEmbedding().getDimensions(),
                        ragProperties.getModels().getChat().getModelName(),
                        diag.mode().name(), diag.candidateLimit(), diag.rrfK(),
                        rerankModel, rerankEnabled, diag.rerankDegraded(),
                        diag.rerankDegradeReason(),
                        answerabilityEnabled, judgeModel),
                ranked.stream().map(DebugRetrievalService::toPayload).toList(),
                new DebugResult.Timings(diag.embedMs(), diag.searchMs(), diag.totalMs()),
                new DebugResult.ContextPayload(context.text(), context.charCount(),
                        context.chunkIds()),
                toDebugDecision(decision),
                buildIssues(ranked, context, diag),
                toTracePayload(outcome.trace()));
    }

    /** R5-B：RetrievalTrace → Debug 载荷（同一份 trace 结构，Eval/Debug 不两套）。 */
    private static DebugResult.RetrievalTracePayload toTracePayload(com.rag.retrieval.RetrievalTrace trace) {
        if (trace == null) {
            return null;
        }
        var map = (java.util.function.Function<com.rag.retrieval.RetrievalTrace.StageCandidate,
                DebugResult.RetrievalTracePayload.StageCandidatePayload>)
                c -> new DebugResult.RetrievalTracePayload.StageCandidatePayload(
                        c.chunkId(), c.rank(), c.score());
        java.util.List<DebugResult.RetrievalTracePayload.StageRanksPayload> ranks =
                trace.ranksByChunk().entrySet().stream()
                        .map(e -> new DebugResult.RetrievalTracePayload.StageRanksPayload(
                                e.getKey(), e.getValue().vectorRank(), e.getValue().bm25Rank(),
                                e.getValue().rrfRank(), e.getValue().preRerankRank(),
                                e.getValue().rerankRank(), e.getValue().finalRank()))
                        .toList();
        return new DebugResult.RetrievalTracePayload(
                trace.vectorCandidates().stream().map(map).toList(),
                trace.bm25Candidates().stream().map(map).toList(),
                trace.unionCandidates().stream().map(map).toList(),
                trace.fusedCandidates().stream().map(map).toList(),
                trace.preRerankCandidates().stream().map(map).toList(),
                trace.rerankedCandidates().stream().map(map).toList(),
                trace.finalTopK(), ranks);
    }

    /** 契约 AnswerabilityDecision 映射（决策关闭时传 null，不虚构判定）。 */
    private static DebugResult.AnswerabilityDecisionPayload toDebugDecision(AnswerabilityDecision d) {
        return new DebugResult.AnswerabilityDecisionPayload(
                d.answerable(), d.decisionType().name(), d.confidence(), d.reason(),
                d.judgeInvoked(), d.degraded(),
                d.failureType() == null ? null : d.failureType().name(), d.latencyMs());
    }

    /** 兼容旧签名（第一轮调用点/测试）：默认模式。 */
    public DebugResult.DebugRetrievalResult debug(String kbId, String question,
                                                  Integer topKOverride, Double minScoreOverride) {
        return debug(kbId, question, topKOverride, minScoreOverride, null);
    }

    private static DebugResult.HitPayload toPayload(RetrievalHit hit) {
        EsHit chunk = hit.chunk();
        DebugResult.ChunkPayload chunkPayload = new DebugResult.ChunkPayload(
                chunk.chunkId(), chunk.docId(), chunk.seq(), chunk.titlePath(),
                chunk.page(), chunk.charCount(), chunk.content());
        return new DebugResult.HitPayload(hit.rank(), chunkPayload, hit.score(),
                hit.passedThreshold(), toStageRanks(hit.stages(), hit.rank()),
                rankChangedReason(hit.stages()));
    }

    /** 把阶段证据映射为有序的阶段位次列表（只含实际参与且该候选出现的阶段）。 */
    private static List<DebugResult.StageRank> toStageRanks(RetrievalStages stages, int finalRank) {
        List<DebugResult.StageRank> list = new ArrayList<>(4);
        if (stages == null) {
            return list;
        }
        if (stages.vectorRank() != null) {
            list.add(new DebugResult.StageRank("vector", stages.vectorRank(), stages.vectorScore()));
        }
        if (stages.bm25Rank() != null) {
            list.add(new DebugResult.StageRank("bm25", stages.bm25Rank(), stages.bm25Score()));
        }
        if (stages.fusedRank() != null) {
            list.add(new DebugResult.StageRank("fused", stages.fusedRank(), stages.fusedScore()));
        }
        if (stages.rerankRank() != null) {
            list.add(new DebugResult.StageRank("rerank", stages.rerankRank(), stages.rerankScore()));
        }
        return list;
    }

    /**
     * 解释"这条为什么排在这里"：只有当某阶段位次与最终名次不同才给出说明，
     * 全部一致则返回 null（不制造噪音）。
     */
    private static String rankChangedReason(RetrievalStages stages) {
        if (stages == null || !stages.hasAny()) {
            return null;
        }
        if (stages.rerankRank() != null && stages.fusedRank() != null
                && !stages.rerankRank().equals(stages.fusedRank())) {
            return "重排调整：融合 #" + stages.fusedRank() + " → 重排 #" + stages.rerankRank();
        }
        if (stages.fusedRank() != null && stages.vectorRank() != null
                && !stages.fusedRank().equals(stages.vectorRank())) {
            return "RRF 融合调整：向量 #" + stages.vectorRank()
                    + (stages.bm25Rank() != null ? " · BM25 #" + stages.bm25Rank() : "")
                    + " → 融合 #" + stages.fusedRank();
        }
        if (stages.bm25Rank() != null && stages.vectorRank() == null) {
            return "仅 BM25 通道召回：BM25 #" + stages.bm25Rank();
        }
        if (stages.vectorRank() != null && stages.bm25Rank() == null
                && stages.fusedRank() != null) {
            return "仅向量通道召回：向量 #" + stages.vectorRank();
        }
        return null;
    }

    private static List<DebugResult.Issue> buildIssues(List<RetrievalHit> ranked, Context context,
                                                       RetrievalPipeline.RetrievalDiagnostics diag) {
        List<DebugResult.Issue> issues = new ArrayList<>();
        if (diag.rerankDegraded()) {
            issues.add(new DebugResult.Issue("RERANK_DEGRADED",
                    "重排未生效，本次使用融合顺序："
                            + (diag.rerankDegradeReason() == null ? "原因未知" : diag.rerankDegradeReason())
                            + "。降级结果与正常重排结果不可直接比较。"));
        }
        if (ranked.isEmpty()) {
            issues.add(new DebugResult.Issue("NO_HITS",
                    "本次检索未命中任何分块：检查问题表述是否与文档内容措辞相近，"
                            + "或确认相关文档已完成入库。"));
            return issues;
        }
        boolean anyPassed = ranked.stream().anyMatch(RetrievalHit::passedThreshold);
        if (!anyPassed) {
            issues.add(new DebugResult.Issue("ALL_BELOW_THRESHOLD",
                    "有 " + ranked.size() + " 个命中但全部低于 minScore 阈值：可尝试降低"
                            + " minScore 或更换问题表述。"));
        }
        if (context.truncated()) {
            issues.add(new DebugResult.Issue("CONTEXT_TRUNCATED",
                    "上下文因超过 max-context-chars 被截断，仅前 "
                            + context.chunkIds().size() + " 个分块参与回答。"));
        }
        return issues;
    }
}
