package com.rag.retrieval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.rag.config.RagProperties;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import com.rag.eval.EvidenceMatcher;
import com.rag.retrieval.model.RetrievalHit;
import com.rag.retrieval.model.RetrievalMode;
import com.rag.retrieval.model.RetrievalStages;
import com.rag.storage.es.EsChunkIndex;
import com.rag.storage.es.EsHit;
import com.rag.storage.repository.DocumentRepository;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 检索流水线（R2-D2）：问答、检索调试、评测共用<b>唯一</b>一条代码路径。
 *
 * <p>第一轮的 {@code DebugRetrievalService} 为拿到分段耗时逐行复刻了检索语义，
 * 只靠一个一致性测试守护；第二轮改造若继续复制，每处改动都要落两遍。故本轮把
 * 流水线收敛到本类，由它同时产出命中结果与分阶段诊断（耗时、模式、降级信息），
 * 调试服务退化为对它的薄封装。</p>
 *
 * <p>执行顺序：向量化 → 通道检索（向量 kNN / BM25）→ 应用侧 RRF 融合 →
 * 已删文档有效性过滤（KB-9）→ 重排（可降级）→ 阈值标记（不过滤）。</p>
 *
 * <p>模式语义：{@link RetrievalMode#VECTOR} 仅向量通道；{@link RetrievalMode#HYBRID}
 * 双通道 + 融合；{@link RetrievalMode#HYBRID_RERANK} 融合后再重排。</p>
 */
@Component
public class RetrievalPipeline {

    private static final Logger log = LoggerFactory.getLogger(RetrievalPipeline.class);

    private final EmbeddingModel embeddingModel;
    private final EsChunkIndex esChunkIndex;
    private final RrfFusion rrfFusion;
    private final Reranker reranker;
    private final DocumentRepository documentRepository;
    private final RagProperties ragProperties;
    /** R6-C：history-aware query rewrite（可空——单测构造时允许不装配）。 */
    private final QueryRewriteService queryRewriteService;

    /**
     * 生产构造（Spring 唯一入口）：注入统一的 QueryRewriteService。
     * 单测可用 {@link #RetrievalPipeline(EmbeddingModel, EsChunkIndex, RrfFusion,
     * Reranker, DocumentRepository, RagProperties, QueryRewriteService)} 传 null。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public RetrievalPipeline(EmbeddingModel embeddingModel,
                             EsChunkIndex esChunkIndex,
                             RrfFusion rrfFusion,
                             Reranker reranker,
                             DocumentRepository documentRepository,
                             RagProperties ragProperties,
                             QueryRewriteService queryRewriteService) {
        this.embeddingModel = embeddingModel;
        this.esChunkIndex = esChunkIndex;
        this.rrfFusion = rrfFusion;
        this.reranker = reranker;
        this.documentRepository = documentRepository;
        this.ragProperties = ragProperties;
        this.queryRewriteService = queryRewriteService;
    }

    /**
     * 执行一次检索并返回结果与分阶段诊断。
     *
     * <p>R6-C：{@code request.history()} 非空且 rewrite 启用时，先用
     * {@link QueryRewriteService} 把依赖历史的当前问题改写为独立可理解的检索查询
     * 再进入流水线——Vector/BM25/RRF/Reranker/阈值全部不变，只换输入查询；
     * rewrite 信息透传到 trace（Debug/Eval 可观测）。rewrite 失败由 Rewriter
     * 内部回退原始查询，本方法不会因此失败。</p>
     *
     * @return 检索结果（hits 含未过阈值项，以 passedThreshold 区分）+ 阶段耗时/模式/降级
     */
    public RetrievalOutcome execute(RetrievalRequest request) {
        RagProperties.Retrieval cfg = ragProperties.getRetrieval();
        int topK = request.topK() != null ? request.topK() : cfg.getTopK();
        double minScore = request.minScore() != null ? request.minScore() : cfg.getMinScore();
        RetrievalMode mode = request.mode() != null ? request.mode() : cfg.getMode();
        int candidateLimit = request.candidateLimit() != null
                ? request.candidateLimit() : cfg.getRrf().getCandidateLimit();

        // R6-C：history-aware query rewrite（仅改检索查询；失败回退原始查询）
        String retrievalQuery = request.question();
        RetrievalTrace.QueryRewriteInfo rewriteInfo = null;
        if (queryRewriteService != null && request.history() != null && !request.history().isEmpty()
                && cfg.getQueryRewrite().isEnabled()) {
            QueryRewriteService.QueryRewriteResult rewrite =
                    queryRewriteService.rewrite(request.question(), request.history());
            retrievalQuery = rewrite.retrievalQuery();
            rewriteInfo = new RetrievalTrace.QueryRewriteInfo(
                    rewrite.originalQuery(), rewrite.retrievalQuery(), rewrite.rewritten(),
                    rewrite.latencyMs(), rewrite.degraded());
        }

        long totalStart = System.currentTimeMillis();

        // 阶段 1：向量化（VECTOR/HYBRID/HYBRID_RERANK 都需要问题向量）
        long embedStart = System.currentTimeMillis();
        float[] queryVector = embed(retrievalQuery);
        long embedMs = System.currentTimeMillis() - embedStart;

        // 阶段 2：通道检索
        long searchStart = System.currentTimeMillis();
        List<EsHit> vectorHits = esChunkIndex.knnSearch(request.kbId(), queryVector,
                Math.max(topK, candidateLimit), Math.max(100, 10 * Math.max(topK, candidateLimit)));
        List<EsHit> bm25Hits = mode == RetrievalMode.VECTOR
                ? List.of()
                : esChunkIndex.bm25Search(request.kbId(), retrievalQuery,
                        Math.max(topK, candidateLimit));
        long searchMs = System.currentTimeMillis() - searchStart;

        // R5-B：阶段候选轨迹（真实执行路径顺带收集，非第二套 pipeline）。
        // R5.1：候选携带证据锚点（titlePath + contentHash(answerContent)），
        // 使证据掉出 final topK 时仍可被 StageMetrics 定位前序阶段名次
        List<RetrievalTrace.StageCandidate> vectorCandidates = toCandidates(vectorHits);
        List<RetrievalTrace.StageCandidate> bm25Candidates = toCandidates(bm25Hits);

        // 阶段 3：排序（单通道直接用通道分；混合用 RRF 融合）
        long fusionStart = System.currentTimeMillis();
        List<RetrievalHit> ordered;
        if (mode == RetrievalMode.VECTOR) {
            ordered = toVectorHits(vectorHits);
        } else {
            ordered = toFusedHits(bm25Hits, vectorHits, topK, candidateLimit);
        }
        long fusionMs = System.currentTimeMillis() - fusionStart;
        List<RetrievalTrace.StageCandidate> fusedCandidates = new ArrayList<>(ordered.size());
        int fusedRank = 1;
        for (RetrievalHit h : ordered) {
            fusedCandidates.add(new RetrievalTrace.StageCandidate(
                    h.chunk().chunkId(), fusedRank++, h.stages().fusedScore() != null
                            ? h.stages().fusedScore() : h.score(),
                    h.chunk().titlePath(), com.rag.eval.EvidenceMatcher.contentHashOf(h.chunk().content())));
        }

        // 阶段 4：KB-9 已删文档有效性过滤（ES 删除为补偿任务异步执行，主档删除即边界收口）
        ordered = filterDeletedDocs(ordered);

        // 阶段 4.5（R3-P3）：只命中激活版本——同名文档再上传产生新版本并自动激活，
        // 旧版本分块在 ES 中保留（可切回），但检索不再命中
        ordered = filterInactiveVersions(ordered);

        // R5.2：preRerank 候选在 filter 之后记录——被 deleted/inactive 过滤淘汰的
        // chunk 没有进入重排器，rerank 升降级归因不得把它算成 degraded。
        // 注意与 fusedCandidates（RRF 阶段召回口径）区分，两个概念不混用。
        List<RetrievalTrace.StageCandidate> preRerankCandidates = new ArrayList<>(ordered.size());
        int preRank = 1;
        for (RetrievalHit h : ordered) {
            preRerankCandidates.add(new RetrievalTrace.StageCandidate(
                    h.chunk().chunkId(), preRank++, h.stages().fusedScore() != null
                            ? h.stages().fusedScore() : h.score(),
                    h.chunk().titlePath(), com.rag.eval.EvidenceMatcher.contentHashOf(h.chunk().content())));
        }

        // 阶段 5：重排（仅 HYBRID_RERANK；失败按配置降级并留痕）
        boolean rerankDegraded = false;
        String rerankDegradeReason = null;
        long rerankStart = System.currentTimeMillis();
        List<RetrievalTrace.StageCandidate> rerankedCandidates = List.of();
        if (mode == RetrievalMode.HYBRID_RERANK) {
            Reranker.RerankOutcome outcome = reranker.rerank(request.kbId(), request.question(), ordered);
            ordered = outcome.hits();
            rerankDegraded = outcome.degraded();
            rerankDegradeReason = outcome.reason();
            if (rerankDegraded && !cfg.getRerank().isDegradeOnFailure()) {
                throw new DomainException(ErrorCode.RETRIEVAL_FAILED,
                        "重排服务不可用且未启用降级：" + rerankDegradeReason);
            }
            if (!rerankDegraded) {
                rerankedCandidates = new ArrayList<>(ordered.size());
                int rr = 1;
                for (RetrievalHit h : ordered) {
                    rerankedCandidates.add(new RetrievalTrace.StageCandidate(
                            h.chunk().chunkId(), rr++, h.stages().rerankScore() != null
                                    ? h.stages().rerankScore() : h.score(),
                            h.chunk().titlePath(), com.rag.eval.EvidenceMatcher.contentHashOf(h.chunk().content())));
                }
            }
        }
        long rerankMs = System.currentTimeMillis() - rerankStart;

        // 重排是否真正参与了打分：以"命中是否带重排位次"为准，而非看谁被装配。
        // 未配置重排服务时装配的是恒等实现，它不改变分数（分数仍是余弦），
        // 此时若按重排口径套阈值会造成系统性误拒——must derive from evidence, not config.
        boolean rerankApplied = mode == RetrievalMode.HYBRID_RERANK && !rerankDegraded
                && ordered.stream().anyMatch(h -> h.stages().rerankRank() != null);

        // 阶段 6：截断到 topK 并标记阈值（标记而非剔除：调试页需要看"命中但低于阈值"）
        List<RetrievalHit> ranked = new ArrayList<>(Math.min(topK, ordered.size()));
        int rank = 1;
        for (RetrievalHit hit : ordered) {
            if (rank > topK) {
                break;
            }
            ranked.add(hit.withRankAndThreshold(rank, hit.score() >= minScore));
            rank++;
        }

        long totalMs = System.currentTimeMillis() - totalStart;
        // R5-B：并集候选（chunkId 去重）+ 最终 topK
        java.util.LinkedHashSet<String> unionIds = new java.util.LinkedHashSet<>();
        for (RetrievalTrace.StageCandidate c : vectorCandidates) unionIds.add(c.chunkId());
        for (RetrievalTrace.StageCandidate c : bm25Candidates) unionIds.add(c.chunkId());
        List<RetrievalTrace.StageCandidate> unionCandidates = new ArrayList<>(unionIds.size());
        int ur = 1;
        for (String id : unionIds) {
            unionCandidates.add(new RetrievalTrace.StageCandidate(id, ur++, 0.0));
        }
        List<String> finalTopK = ranked.stream().map(h -> h.chunk().chunkId()).toList();
        RetrievalTrace trace = new RetrievalTrace(vectorCandidates, bm25Candidates,
                unionCandidates, fusedCandidates, preRerankCandidates, rerankedCandidates,
                finalTopK, new RetrievalTrace.StageTiming(embedMs, searchMs, fusionMs, rerankMs, totalMs),
                rewriteInfo);
        return new RetrievalOutcome(ranked, new RetrievalDiagnostics(
                mode, topK, minScore, candidateLimit, cfg.getRrf().getK(),
                embedMs, searchMs, totalMs, rerankDegraded, rerankDegradeReason, rerankApplied),
                trace);
    }

    /** EsHit 序（即该通道 rank 序）→ 轻量阶段候选（含证据锚点；不携带正文）。 */
    private static List<RetrievalTrace.StageCandidate> toCandidates(List<EsHit> hits) {
        List<RetrievalTrace.StageCandidate> candidates = new ArrayList<>(hits.size());
        int rank = 1;
        for (EsHit hit : hits) {
            candidates.add(new RetrievalTrace.StageCandidate(hit.chunkId(), rank++, hit.score(),
                    hit.titlePath(), EvidenceMatcher.contentHashOf(hit.content())));
        }
        return candidates;
    }

    /** VECTOR 模式：直接按 cosine 降序，记录向量位次。 */
    private List<RetrievalHit> toVectorHits(List<EsHit> vectorHits) {
        List<RetrievalHit> hits = new ArrayList<>(vectorHits.size());
        int rank = 1;
        for (EsHit hit : vectorHits) {
            RetrievalStages stages = new RetrievalStages(rank, hit.score(),
                    null, null, null, null, null, null, false);
            hits.add(new RetrievalHit(rank, hit, hit.score(), false, stages));
            rank++;
        }
        return hits;
    }

    /**
     * HYBRID / HYBRID_RERANK：应用侧 RRF 融合决定<b>排序</b>；
     * {@code score}（阈值与拒答判定用）= 向量余弦相似度。
     *
     * <p><b>为什么 score 不用融合分</b>：原始 RRF 分只有 ~0.016 量级，且只对"出现在
     * 该通道候选内"的通道累加——单通道召回的候选其分天然只有双通道的一半，
     * 与它是否真的相关无关。若把它归一化后直接比阈值，任何"只被一个通道召回"的
     * 相关命中都会被判为低相关而拒绝（实测：单通道 rank1 归一化后恰好 0.5）。
     * 因此融合分只用于<b>排序</b>（排序对单调变换不敏感），而阈值/拒答这类需要
     * <b>绝对</b>相关度的判断使用余弦相似度——它是唯一跨模式可比的绝对量。</p>
     *
     * <p>限制（如实记录）：仅被 BM25 召回、向量未召回的候选拿不到余弦分，其
     * score 记 0.0——排序上仍可能靠前，但不会通过 minScore 进入引用。BM25 在本设计
     * 中的贡献是"把正确分块提升进 topK"，而不是独立提供相关度。
     * 重排启用时，score 会被重排相关度覆盖（见 DashScopeReranker）。</p>
     */
    private List<RetrievalHit> toFusedHits(List<EsHit> bm25Hits, List<EsHit> vectorHits,
                                           int topK, int candidateLimit) {
        int rrfK = ragProperties.getRetrieval().getRrf().getK();
        List<RrfFusion.FusedHit> fused = rrfFusion.fuse(rrfK, vectorHits, bm25Hits);
        Map<String, Double> vectorScores = new LinkedHashMap<>();
        for (EsHit hit : vectorHits) {
            vectorScores.putIfAbsent(hit.chunkId(), hit.score());
        }
        Map<String, Double> bm25Scores = new LinkedHashMap<>();
        for (EsHit hit : bm25Hits) {
            bm25Scores.putIfAbsent(hit.chunkId(), hit.score());
        }
        List<RetrievalHit> hits = new ArrayList<>(fused.size());
        int rank = 1;
        for (RrfFusion.FusedHit item : fused) {
            EsHit chunk = item.chunk();
            Double cosine = vectorScores.get(chunk.chunkId());
            double evidenceScore = cosine == null ? 0.0 : cosine;
            RetrievalStages stages = item.toStages(cosine, bm25Scores.get(chunk.chunkId()), rank);
            hits.add(new RetrievalHit(rank, chunk, evidenceScore, false, stages));
            rank++;
        }
        return hits;
    }

    /** 批量查 document 主档，剔除已删除（行不存在）文档的命中。 */
    private List<RetrievalHit> filterDeletedDocs(List<RetrievalHit> hits) {
        if (hits.isEmpty()) {
            return List.of();
        }
        Set<String> docIds = hits.stream().map(h -> h.chunk().docId())
                .collect(Collectors.toSet());
        Set<String> existingDocIds = documentRepository.findAllById(docIds).stream()
                .map(d -> d.getId())
                .collect(Collectors.toCollection(HashSet::new));
        return hits.stream()
                .filter(hit -> existingDocIds.contains(hit.chunk().docId()))
                .toList();
    }

    /**
     * 阶段 4.5（R3-P3）：过滤非激活版本的命中。与阶段 4 同模式：一次 findAllById
     * 批量取 active 位，内存过滤；ES mapping 不动（active 是 MySQL 侧元数据）。
     */
    private List<RetrievalHit> filterInactiveVersions(List<RetrievalHit> hits) {
        if (hits.isEmpty()) {
            return List.of();
        }
        Set<String> docIds = hits.stream().map(h -> h.chunk().docId())
                .collect(Collectors.toSet());
        Set<String> activeDocIds = documentRepository.findAllById(docIds).stream()
                .filter(com.rag.domain.entity.DocumentEntity::isActive)
                .map(com.rag.domain.entity.DocumentEntity::getId)
                .collect(Collectors.toCollection(HashSet::new));
        if (activeDocIds.size() == docIds.size()) {
            return hits; // 全部激活：零开销快路径
        }
        return hits.stream()
                .filter(hit -> activeDocIds.contains(hit.chunk().docId()))
                .toList();
    }

    private float[] embed(String question) {
        try {
            float[] vector = embeddingModel.embed(question).content().vector();
            int expected = ragProperties.getModels().getEmbedding().getDimensions();
            if (vector == null || vector.length != expected) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR,
                        "问题向量化维度与配置不一致：期望 " + expected + "，实际 "
                                + (vector == null ? "null" : vector.length)
                                + "。请核对 EMBEDDING_MODEL_NAME/EMBEDDING_DIMENSIONS 配置。");
            }
            return vector;
        } catch (DomainException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("问题向量化失败：{}", e.getMessage());
            throw new DomainException(ErrorCode.RETRIEVAL_FAILED,
                    "问题向量化失败，embedding 服务暂时不可用：" + e.getMessage());
        }
    }

    /**
     * 检索结果 + 分阶段诊断。
     *
     * @param hits        命中（分数序、rank 从 1；含未过阈值项）
     * @param diagnostics 生效配置、模式、耗时与降级信息
     */
    public record RetrievalOutcome(List<RetrievalHit> hits, RetrievalDiagnostics diagnostics,
                                   RetrievalTrace trace) {

        /** 通过阈值的命中（进入上下文/引用）。 */
        public List<RetrievalHit> passed() {
            return hits.stream().filter(RetrievalHit::passedThreshold).toList();
        }

        /** 兼容旧调用点（无 trace 需求）。 */
        public RetrievalOutcome(List<RetrievalHit> hits, RetrievalDiagnostics diagnostics) {
            this(hits, diagnostics, null);
        }
    }

    /**
     * 分阶段诊断（调试页展示；评测 config_snapshot 记录）。
     *
     * @param mode 本次生效检索模式
     * @param topK 生效 topK
     * @param minScore 生效 minScore
     * @param candidateLimit 各通道候选数上限
     * @param rrfK RRF 常数
     * @param embedMs 向量化耗时
     * @param searchMs 通道检索耗时
     * @param totalMs 端到端检索耗时
     * @param rerankDegraded 是否发生重排降级
     * @param rerankDegradeReason 降级原因（未降级为 null）
     * @param rerankApplied 重排是否真正参与打分（未配置重排服务时为 false）
     */
    public record RetrievalDiagnostics(RetrievalMode mode, int topK, double minScore,
                                       int candidateLimit, int rrfK, long embedMs, long searchMs,
                                       long totalMs, boolean rerankDegraded,
                                       String rerankDegradeReason, boolean rerankApplied) {
    }
}
