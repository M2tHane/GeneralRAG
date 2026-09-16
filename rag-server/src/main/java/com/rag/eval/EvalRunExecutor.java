package com.rag.eval;

import java.util.ArrayList;
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
import com.rag.domain.enums.SessionRole;
import com.rag.llm.ChatStreamService;
import com.rag.llm.PromptAssembler;
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
        // R2-E3：拒答正确率 = (资料外题正确拒答 + 资料内题未误拒) / 参与判定题数
        // R4.1.1 统计口径拆分：
        //   executedCount  = 成功执行完成的题（含 Judge degraded，不含执行异常）
        //   evaluatedCount = 可进入 TP/FP/FN/TN 的题 = executedCount - degradedCount
        // 质量指标（混淆矩阵/FAR/FRR/refusalAccuracy）只用 evaluatedCount 样本；
        // 成本指标（judgeInvocationRate）用 executedCount——degraded 也真实消耗了
        // 一次 Judge 调用尝试，不能从分母里消失。
        int executedCount = 0;
        int evaluatedCount = 0;
        int refusalCorrect = 0;
        int judgeInvokedCount = 0;
        int judgeDegradedCount = 0;
        Map<String, Integer> degradedByType = new java.util.LinkedHashMap<>();
        long judgeLatencySum = 0;
        int tpCount = 0;
        int fpCount = 0;
        int fnCount = 0;
        int tnCount = 0;
        // R4.1.1：数据集事实统计——OUT_OF_KB 题数只看数据集标签，不受执行/降级影响
        int outOfKbCount = 0;
        // R4：按类别拆分（定位哪类题出错）
        Map<String, int[]> categoryStats = new java.util.LinkedHashMap<>();
        List<Integer> latencies = new java.util.ArrayList<>();

        for (EvalDatasetItemEntity item : datasetItems) {
            if (!item.isAnswerable()) {
                outOfKbCount++;
            }
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
                        topK, minScore, toHistoryTurns(item.getHistory()));
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
            // R4.1/R4.1.1：执行失败与 degraded 题（Judge 系统故障）都不计入混淆矩阵、
            // 拒答正确率与类别拆分——故障既不是"正确拒答"也不是"误拒"，混入会
            // 系统性污染质量指标；degraded 只体现在 executedCount/degraded 明细中。
            boolean degraded = answer != null && answer.answerability() != null
                    && answer.answerability().degraded();
            if (!executionFailed) {
                executedCount++;
                if (degraded) {
                    // 系统故障：计入 executed（Judge 调用已消耗），不计入 evaluated
                } else {
                    evaluatedCount++;
                    boolean refused = answer != null && answer.refusal();
                    if (item.isAnswerable() == !refused) {
                        refusalCorrect++;
                    }
                    if (refused) {
                        if (item.isAnswerable()) {
                            fnCount++;
                        } else {
                            tnCount++;
                        }
                    } else if (item.isAnswerable()) {
                        tpCount++;
                    } else {
                        fpCount++;
                    }
                    // R4：类别拆分统计（expectedAnswerable / refused / misjudged）
                    String catKey = item.getCategory() == null ? "UNKNOWN" : item.getCategory().name();
                    int[] stat = categoryStats.computeIfAbsent(catKey, k -> new int[3]);
                    stat[0]++; // 样本数
                    if (refused) {
                        stat[1]++; // 拒答数
                    }
                    if (item.isAnswerable() == refused) {
                        stat[2]++; // 判错数（应答被拒 + 不应答被答）
                    }
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
                // R4：系统实际决策落库（混淆矩阵的数据源，杜绝"靠回答文案反推"）
                runItem.setRefused(answer.refusal());
                com.rag.answerability.AnswerabilityDecision dec = answer.answerability();
                if (dec != null) {
                    runItem.setAnswerabilityDecisionType(dec.decisionType());
                    runItem.setAnswerabilityConfidence(dec.confidence());
                    runItem.setAnswerabilityReason(truncate(dec.reason() == null ? "" : dec.reason(), 500));
                    runItem.setAnswerabilityDegraded(dec.degraded());
                    runItem.setAnswerabilityLatencyMs(toIntMs(dec.latencyMs()));
                    if (dec.degraded()) {
                        judgeDegradedCount++;
                        // R4.1.1：结构化 failureType 计数（不再依赖 reason 文本前缀）
                        String type = dec.failureType() == null ? "UNKNOWN" : dec.failureType().name();
                        degradedByType.merge(type, 1, Integer::sum);
                    }
                    if (dec.judgeInvoked()) {
                        judgeInvokedCount++;
                        judgeLatencySum += dec.latencyMs();
                    }
                }
            }
            runItemRepository.save(runItem);

            totalLatency += latencyMs;
            latencies.add(latencyMs);
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
        // R2-E3：拒答正确率独立成指标（分母 = evaluatedCount，仅参与判定的题）
        metrics.put("refusalAccuracy", evaluatedCount == 0 ? null
                : round4((double) refusalCorrect / evaluatedCount));
        metrics.put("answerableCount", answerableCount);
        // R4.1.1：数据集事实统计——不受执行/降级影响（此前 = refusalTotal - answerableCount，
        // 在 degraded 出现时会与数据集标签集合不一致）
        metrics.put("outOfKbCount", outOfKbCount);
        // R4.1.1：执行/判定分母分离（口径见 executeItems 注释）
        metrics.put("executedCount", executedCount);
        metrics.put("evaluatedCount", evaluatedCount);
        // R4：Answerability 混淆矩阵（逐题累计：TP=应答且答 FP=不应答却答 FN=应答却拒 TN=不应答且拒；
        // degraded 题 = 系统故障，不计入四格，单独透出 failureType 细分）
        metrics.put("answerabilityConfusion", confusionMetrics(executedCount, evaluatedCount,
                judgeInvokedCount, judgeDegradedCount, judgeLatencySum, categoryStats,
                tpCount, fpCount, fnCount, tnCount, degradedByType));
        // R2-L1：耗时拆分与分位数
        metrics.put("avgLatencyMs", executedCount == 0 ? null : round4((double) totalLatency / executedCount));
        metrics.put("retrievalMsAvg", executedCount == 0 ? null : round4((double) totalRetrieval / executedCount));
        metrics.put("generationMsAvg", executedCount == 0 ? null : round4((double) totalGeneration / executedCount));
        metrics.put("latencyP50Ms", percentile(latencies, 0.50));
        metrics.put("latencyP95Ms", percentile(latencies, 0.95));
        metrics.put("latencyMaxMs", latencies.isEmpty() ? null : latencies.get(latencies.size() - 1));
        metrics.put("itemCount", executedCount);

        run.setStatus(EvalRunStatus.COMPLETED);
        run.setMetrics(metrics);
        run.setFinishedAt(java.time.LocalDateTime.now());
        runRepository.save(run);
        log.info("评测运行完成 runId={} itemCount={} executed={} evaluated={} answerable={} "
                        + "hitAt1={} hitAt3={} hitAt5={} recallAt5={} mrr={} refusalAcc={}",
                run.getId(), executedCount, executedCount, evaluatedCount, answerableCount,
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

    /**
     * R4 Answerability 混淆矩阵与派生指标。
     *
     * <p>定义（False Answer Rate 是核心指标——企业知识库中"没答案却自信回答"
     * 比偶尔误拒风险更高）：</p>
     * <ul>
     *   <li>falseAnswerRate = FP/(FP+TN)：实际不可回答的问题中被错误回答的比例</li>
     *   <li>falseRefusalRate = FN/(FN+TP)：实际可回答的问题中被错误拒答的比例</li>
     *   <li>answerablePrecision = TP/(TP+FP)；answerableRecall = TP/(TP+FN)</li>
     *   <li>refusalPrecision = TN/(TN+FN)；refusalRecall = TN/(TN+FP)</li>
     *   <li>judgeInvocationRate = judgeInvoked / executedCount（成本口径，R4.1.1）——
     *       degraded（TIMEOUT/OVERLOADED 等）也真实消耗了一次 Judge 调用尝试，
     *       分母必须是 executedCount 而非 evaluatedCount</li>
     *   <li>judgeDegradedRate = judgeDegraded / judgeInvoked（可用性）</li>
     * </ul>
     *
     * <p>R4.1 语义修正：{@code degraded} 题（Judge TIMEOUT/OVERLOADED/MODEL_ERROR/
     * INVALID_RESPONSE）是<b>系统故障</b>而非判定能力——不进 TP/FP/FN/TN，
     * 也不进 falseAnswerRate / falseRefusalRate / refusalAccuracy；否则
     * failClosed=true 时每次故障都被记成 Correct Refusal，指标被系统性高估。
     * R4.1.1：降级明细按结构化 {@link com.rag.answerability.JudgeFailureType} 计数，
     * 在 {@code degraded} 字段透出，不再依赖 reason 文本前缀。</p>
     */
    private static Map<String, Object> confusionMetrics(int executedCount, int evaluatedCount,
                                                        int judgeInvoked, int judgeDegraded,
                                                        long judgeLatencySum,
                                                        Map<String, int[]> categoryStats,
                                                        int tp, int fp, int fn, int tn,
                                                        Map<String, Integer> degradedByType) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tp", tp);
        m.put("fp", fp);
        m.put("fn", fn);
        m.put("tn", tn);
        m.put("executedCount", executedCount);
        m.put("evaluatedCount", evaluatedCount);
        m.put("falseAnswerRate", (fp + tn) == 0 ? null : round4((double) fp / (fp + tn)));
        m.put("falseRefusalRate", (fn + tp) == 0 ? null : round4((double) fn / (fn + tp)));
        m.put("answerablePrecision", (tp + fp) == 0 ? null : round4((double) tp / (tp + fp)));
        m.put("answerableRecall", (tp + fn) == 0 ? null : round4((double) tp / (tp + fn)));
        m.put("refusalPrecision", (tn + fn) == 0 ? null : round4((double) tn / (tn + fn)));
        m.put("refusalRecall", (tn + fp) == 0 ? null : round4((double) tn / (tn + fp)));
        m.put("judgeInvocationRate", executedCount == 0 ? null : round4((double) judgeInvoked / executedCount));
        m.put("judgeDegradedRate", judgeInvoked == 0 ? null : round4((double) judgeDegraded / judgeInvoked));
        m.put("judgeLatencyMsAvg", judgeInvoked == 0 ? null : round4((double) judgeLatencySum / judgeInvoked));
        m.put("degraded", Map.copyOf(degradedByType));
        Map<String, Object> breakdown = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : categoryStats.entrySet()) {
            int[] stat = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("count", stat[0]);
            row.put("refused", stat[1]);
            row.put("misjudged", stat[2]);
            breakdown.put(e.getKey(), row);
        }
        m.put("categoryBreakdown", breakdown);
        return m;
    }

    /**
     * 数据集样本 history → 会话历史轻量结构（R4.1 FOLLOW_UP 口径修正）。
     * 结构 = [{role: user|assistant, content}]（导入时已校验，此处防御性兜底）。
     * 检索仍只使用当前问题（本系统无 query rewrite——评测测的是当前真实系统）；
     * 历史仅进入生成 prompt 的历史区与 Judge 的指代解析区，均不构成证据。
     */
    private static List<PromptAssembler.HistoryTurn> toHistoryTurns(List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<PromptAssembler.HistoryTurn> turns = new ArrayList<>(history.size());
        for (Map<String, Object> turn : history) {
            Object role = turn.get("role");
            Object content = turn.get("content");
            if (!(role instanceof String r) || !(content instanceof String c) || c.isBlank()) {
                continue; // 防御：导入已校验，异常形态跳过而非失败整轮
            }
            turns.add(new PromptAssembler.HistoryTurn(
                    "assistant".equalsIgnoreCase(r) ? SessionRole.ASSISTANT : SessionRole.USER,
                    c));
        }
        return turns;
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
