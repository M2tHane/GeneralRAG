package com.rag.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.rag.domain.entity.DocumentEntity;
import com.rag.domain.entity.EvalDatasetItemEntity;
import com.rag.domain.entity.EvalRunEntity;
import com.rag.domain.entity.EvalRunItemEntity;
import com.rag.domain.enums.EvalRunStatus;
import com.rag.llm.ChatStreamService;
import com.rag.retrieval.RetrievalService;
import com.rag.retrieval.model.RetrievalHit;
import com.rag.retrieval.model.RetrievalStages;
import com.rag.storage.es.EsHit;
import com.rag.storage.repository.DocumentRepository;
import com.rag.storage.repository.EvalDatasetItemRepository;
import com.rag.storage.repository.EvalRunRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 评测运行执行器（Task 6）：单线程池串行执行，逐题检索→回答→落明细→聚合指标。
 *
 * <p>单题流程（EV-3 口径）：</p>
 * <ol>
 *   <li>{@code answerOnce(kbId, question, topK, minScore, history=[])} 一次调用同时
 *       得到 hits（全量，含未过阈值）与回答+引用——与问答链路同一代码路径；</li>
 *   <li>retrieved 快照 = 契约 EvalHit[]（rank/chunkId/docName/titlePath/page/score）；</li>
 *   <li>hit 判定：参考 evidence 的 docName 或 titlePath 与任一命中（K = 本次运行
 *       topK）的 docName/titlePath 相等即 true；<b>answerable=false 的题（OUT_OF_KB）
 *       hit 记 NULL 且不计入任何 Hit@K 分母</b>——它们考察的是"应拒答"行为，
 *       不参与召回率统计；</li>
 *   <li>answerable=false 的题同样生成回答（用于人工检查"无法回答"行为），
 *       citations 照常记录；</li>
 *   <li>latencyMs = 该题端到端（检索+生成）毫秒。</li>
 * </ol>
 *
 * <p>Hit@K 口径：K=1/3/5 固定输出。分子 = answerable 题中"首个 evidence 匹配出现
 * 在前 N 个命中内"的数量；分母 = answerable 题总数。若运行 topK &lt; 5，Hit@5 的
 * 分母不变、分子按现有命中计算（K 超过 topK 时等价于按 topK 计），注释声明以避免
 * 与其他系统口径混淆。</p>
 *
 * <p>失败语义：单题异常只影响该题（generatedAnswer=NULL 继续执行）；
 * 运行级异常 → status=FAILED + failureReason（截断 512）。</p>
 */
@Component
public class EvalRunExecutor {

    private static final Logger log = LoggerFactory.getLogger(EvalRunExecutor.class);
    private static final int[] HIT_KS = {1, 3, 5};

    private final EvalRunRepository runRepository;
    private final EvalRunItemQueryRepository runItemRepository;
    private final EvalDatasetItemRepository datasetItemRepository;
    private final DocumentRepository documentRepository;
    private final ChatStreamService chatStreamService;
    private final ExecutorService executor;

    public EvalRunExecutor(EvalRunRepository runRepository,
                           EvalRunItemQueryRepository runItemRepository,
                           EvalDatasetItemRepository datasetItemRepository,
                           DocumentRepository documentRepository,
                           ChatStreamService chatStreamService) {
        this.runRepository = runRepository;
        this.runItemRepository = runItemRepository;
        this.datasetItemRepository = datasetItemRepository;
        this.documentRepository = documentRepository;
        this.chatStreamService = chatStreamService;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "eval-run-executor");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 异步受理（EvalService.submitAsync → 202 返回后由线程池执行）。 */
    public void submit(String runId) {
        executor.submit(() -> {
            try {
                runRepository.findById(runId).ifPresent(this::executeRun);
            } catch (RuntimeException e) {
                log.error("评测运行执行异常（runId={}）", runId, e);
            }
        });
    }

    /**
     * 同步执行一次运行（runSync：测试与运维入口）。幂等防护：仅 RUNNING 状态执行。
     */
    public void executeRun(EvalRunEntity run) {
        if (run.getStatus() != EvalRunStatus.RUNNING) {
            log.info("评测运行非 RUNNING，跳过（runId={}, status={}）", run.getId(), run.getStatus());
            return;
        }
        if (!casClaimRun(run.getId())) {
            log.info("评测运行已被其他执行者认领，跳过（runId={}）", run.getId());
            return;
        }
        Map<String, Object> config = run.getConfigSnapshot() == null
                ? Map.of() : run.getConfigSnapshot();
        int topK = ((Number) config.getOrDefault("topK", 6)).intValue();
        double minScore = ((Number) config.getOrDefault("minScore", 0.30)).doubleValue();
        log.info("评测运行开始 runId={} kbId={} topK={} minScore={}",
                run.getId(), run.getKbId(), topK, minScore);
        try {
            List<EvalDatasetItemEntity> datasetItems =
                    datasetItemRepository.findByVersionIdOrderBySeqAsc(run.getDatasetVersionId());
            long itemCount = runItemRepository.countByRunId(run.getId());
            if (itemCount == 0) {
                seedRunItems(run, datasetItems);
            }
            executeItems(run, datasetItems, topK, minScore);
        } catch (Exception e) {
            log.error("评测运行失败（runId={}）", run.getId(), e);
            run.setStatus(EvalRunStatus.FAILED);
            run.setFailureReason(truncate(e.getClass().getSimpleName() + ": " + e.getMessage(), 512));
            run.setFinishedAt(java.time.LocalDateTime.now());
            runRepository.save(run);
        }
    }

    /** M6-①：启动恢复——重启后仍 RUNNING 的运行复位 FAILED（对称 ingestion.recoverOnStartup）。 */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        int reset = runRepository.failAllRunningOnStartup();
        if (reset > 0) {
            log.warn("启动恢复：{} 个 RUNNING 评测运行已复位 FAILED（服务重启中断）", reset);
        }
    }

    /** M6-①：RUNNING 行 CAS 认领（status 条件更新），防 submit/runSync 双跑与重启后双执行。 */
    private boolean casClaimRun(String runId) {
        return runRepository.claimIfRunning(runId) == 1;
    }

    /** 首次执行前建明细骨架行（seq 与样本序号一致；retrieved=[] 占位）。 */
    private void seedRunItems(EvalRunEntity run, List<EvalDatasetItemEntity> datasetItems) {
        List<EvalRunItemEntity> seeds = new java.util.ArrayList<>(datasetItems.size());
        for (EvalDatasetItemEntity item : datasetItems) {
            EvalRunItemEntity entity = new EvalRunItemEntity();
            entity.setRunId(run.getId());
            entity.setSeq(item.getSeq());
            entity.setQuestion(item.getQuestion());
            entity.setRetrieved(List.of());
            seeds.add(entity);
        }
        runItemRepository.saveAll(seeds);
        runItemRepository.flush();
    }

    private void executeItems(EvalRunEntity run, List<EvalDatasetItemEntity> datasetItems,
                              int topK, double minScore) {
        // docName 查询缓存（Hit 判定与 EvalHit 快照共用）
        Map<String, String> docNameCache = new java.util.concurrent.ConcurrentHashMap<>();

        int answerableCount = 0;
        int[] hitCounts = new int[HIT_KS.length];
        long totalLatency = 0;
        long totalRetrieval = 0;
        long totalGeneration = 0;
        int executed = 0;
        // R2-E2：正确答案排名分布（rank1/2/3/…/未召回）
        Map<Integer, Integer> rankDistribution = new java.util.TreeMap<>();
        int notFound = 0;
        double mrrSum = 0.0;
        int mrrCount = 0;
        // R2-E2：Recall@5 = 命中的证据分块数 / 参考证据分块总数
        int evidenceTotal = 0;
        int evidenceCovered = 0;
        // R2-E3：拒答正确率 = (资料外题正确拒答 + 资料内题未误拒) / 全部题
        int refusalCorrect = 0;
        int refusalTotal = 0;
        List<Integer> latencies = new java.util.ArrayList<>();

        for (EvalDatasetItemEntity item : datasetItems) {
            EvalRunItemEntity runItem = runItemRepository
                    .findByRunIdAndSeq(run.getId(), item.getSeq()).orElse(null);
            if (runItem == null) {
                log.warn("评测明细行缺失，跳过（runId={}, seq={}）", run.getId(), item.getSeq());
                continue;
            }
            long start = System.currentTimeMillis();
            List<RetrievalHit> hits;
            ChatStreamService.AnswerResult answer = null;
            boolean executionFailed = false;
            try {
                answer = chatStreamService.answerOnce(run.getKbId(), item.getQuestion(),
                        topK, minScore, List.of());
                hits = answer.hits();
            } catch (RuntimeException e) {
                // M6-②：单题执行失败 ≠ 未召回——hit 记 NULL 并退出 Hit@K 分母，
                // 否则外部服务抖动会系统性低估指标、污染评测结论
                log.warn("评测单题执行失败（runId={}, seq={}）：{}", run.getId(), item.getSeq(), e.getMessage());
                hits = List.of();
                executionFailed = true;
            }
            int latencyMs = (int) (System.currentTimeMillis() - start);

            List<EsHit> orderedHits = new java.util.ArrayList<>(hits.size());
            for (RetrievalHit hit : hits) {
                orderedHits.add(hit.chunk());
            }
            List<Map<String, Object>> retrieved = buildRetrieved(hits, docNameCache, documentRepository);

            // hit 判定（R2-E1 分块级）：answerable=false 或执行失败一律 NULL
            Boolean hit = null;
            int firstEvidenceRank = Integer.MAX_VALUE;
            if (item.isAnswerable() && !executionFailed) {
                answerableCount++;
                List<Map<String, Object>> evidence = item.getEvidence() == null
                        ? List.of() : item.getEvidence();
                int coveredForItem = 0;
                for (Map<String, Object> ev : evidence) {
                    // 分块级判定：共享 EvidenceMatcher，保证与展示口径一致（R2-E1）
                    int matchRank = EvidenceMatcher.firstMatchRank(ev, orderedHits);
                    if (matchRank > 0) {
                        coveredForItem++;
                        if (matchRank < firstEvidenceRank) {
                            firstEvidenceRank = matchRank;
                        }
                    }
                }
                evidenceTotal += evidence.size();
                evidenceCovered += coveredForItem;
                hit = firstEvidenceRank != Integer.MAX_VALUE;
                for (int k = 0; k < HIT_KS.length; k++) {
                    // K 超过 topK 时分子按现有命中（≤topK）计，分母不变（见类注释）
                    if (firstEvidenceRank <= Math.min(HIT_KS[k], topK)) {
                        hitCounts[k]++;
                    }
                }
                if (hit) {
                    rankDistribution.merge(firstEvidenceRank, 1, Integer::sum);
                    mrrSum += 1.0 / firstEvidenceRank;
                    mrrCount++;
                } else {
                    notFound++;
                }
            }

            // R2-E3：拒答正确率（answerable=false 应拒；answerable=true 不应拒）
            if (!executionFailed) {
                refusalTotal++;
                boolean refused = answer != null && answer.refusal();
                if (item.isAnswerable() == !refused) {
                    refusalCorrect++;
                }
            }

            runItem.setRetrieved(retrieved);
            runItem.setHit(hit);
            runItem.setLatencyMs(latencyMs);
            if (executionFailed) {
                runItem.setReviewNote("执行失败（不计入 Hit@K 分母）");
            }
            if (answer != null) {
                runItem.setGeneratedAnswer(answer.answer());
                runItem.setCitations(answer.citations() == null ? List.of()
                        : new java.util.ArrayList<>(answer.citations()));
                runItem.setRetrievalMs(toIntMs(answer.retrievalMs()));
                runItem.setGenerationMs(toIntMs(answer.generationMs()));
                totalRetrieval += answer.retrievalMs();
                totalGeneration += answer.generationMs();
            }
            runItemRepository.save(runItem);

            totalLatency += latencyMs;
            latencies.add(latencyMs);
            executed++;
        }

        latencies.sort(null);
        Map<String, Object> metrics = new LinkedHashMap<>();
        for (int k = 0; k < HIT_KS.length; k++) {
            metrics.put("hitAt" + HIT_KS[k], answerableCount == 0 ? null
                    : round4((double) hitCounts[k] / answerableCount));
        }
        // R2-E2：Recall@5 / MRR / 排名分布
        metrics.put("recallAt5", evidenceTotal == 0 ? null
                : round4((double) evidenceCovered / evidenceTotal));
        metrics.put("mrr", mrrCount == 0 ? null : round4(mrrSum / answerableCount));
        Map<String, Object> rankDist = new LinkedHashMap<>();
        for (int k = 1; k <= 5; k++) {
            rankDist.put(String.valueOf(k), rankDistribution.getOrDefault(k, 0));
        }
        rankDist.put("notFound", notFound);
        metrics.put("rankDistribution", rankDist);
        // R2-E3：拒答正确率独立成指标
        metrics.put("refusalAccuracy", refusalTotal == 0 ? null
                : round4((double) refusalCorrect / refusalTotal));
        metrics.put("answerableCount", answerableCount);
        metrics.put("outOfKbCount", refusalTotal - answerableCount);
        // R2-L1：耗时拆分与分位数
        metrics.put("avgLatencyMs", executed == 0 ? null : round4((double) totalLatency / executed));
        metrics.put("retrievalMsAvg", executed == 0 ? null : round4((double) totalRetrieval / executed));
        metrics.put("generationMsAvg", executed == 0 ? null : round4((double) totalGeneration / executed));
        metrics.put("latencyP50Ms", percentile(latencies, 0.50));
        metrics.put("latencyP95Ms", percentile(latencies, 0.95));
        metrics.put("latencyMaxMs", latencies.isEmpty() ? null : latencies.get(latencies.size() - 1));
        metrics.put("itemCount", executed);

        run.setStatus(EvalRunStatus.COMPLETED);
        run.setMetrics(metrics);
        run.setFinishedAt(java.time.LocalDateTime.now());
        runRepository.save(run);
        log.info("评测运行完成 runId={} itemCount={} answerable={} hitAt1={} hitAt3={} hitAt5={} "
                        + "recallAt5={} mrr={} refusalAcc={}",
                run.getId(), executed, answerableCount,
                metrics.get("hitAt1"), metrics.get("hitAt3"), metrics.get("hitAt5"),
                metrics.get("recallAt5"), metrics.get("mrr"), metrics.get("refusalAccuracy"));
    }

    /**
     * retrieved 快照：契约 EvalHit[] + 第二轮分阶段位次（R2-D1/E2）。
     * 分阶段字段可空（该模式未参与该通道时为 null）。
     */
    private static List<Map<String, Object>> buildRetrieved(List<RetrievalHit> hits,
                                                            Map<String, String> docNameCache,
                                                            DocumentRepository documentRepository) {
        List<Map<String, Object>> retrieved = new java.util.ArrayList<>(hits.size());
        for (RetrievalHit hit : hits) {
            EsHit chunk = hit.chunk();
            Map<String, Object> evalHit = new LinkedHashMap<>();
            evalHit.put("rank", hit.rank());
            evalHit.put("chunkId", chunk.chunkId());
            evalHit.put("contentHash", EvidenceMatcher.contentHashOf(chunk.content()));
            evalHit.put("docName", docName(chunk, docNameCache, documentRepository));
            evalHit.put("titlePath", chunk.titlePath());
            evalHit.put("page", chunk.page());
            evalHit.put("score", hit.score());
            evalHit.put("passedThreshold", hit.passedThreshold());
            RetrievalStages stages = hit.stages();
            evalHit.put("vectorRank", stages.vectorRank());
            evalHit.put("vectorScore", stages.vectorScore());
            evalHit.put("bm25Rank", stages.bm25Rank());
            evalHit.put("bm25Score", stages.bm25Score());
            evalHit.put("fusedRank", stages.fusedRank());
            evalHit.put("fusedScore", stages.fusedScore());
            evalHit.put("rerankRank", stages.rerankRank());
            evalHit.put("rerankScore", stages.rerankScore());
            retrieved.add(evalHit);
        }
        return retrieved;
    }

    /** 分位数（最近秩法；样本为空返回 null）。 */
    private static Integer percentile(List<Integer> sorted, double p) {
        if (sorted.isEmpty()) {
            return null;
        }
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(sorted.size() - 1, idx));
        return sorted.get(idx);
    }

    private static int toIntMs(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    private static String docName(EsHit chunk, Map<String, String> cache,
                                  DocumentRepository documentRepository) {
        return cache.computeIfAbsent(chunk.docId(), docId ->
                documentRepository.findById(docId)
                        .map(DocumentEntity::getName).orElse(""));
    }

    private static String str(Object value) {
        return value == null || String.valueOf(value).isBlank()
                ? null : String.valueOf(value);
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
