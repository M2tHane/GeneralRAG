package com.rag.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rag.config.RagProperties;
import com.rag.domain.entity.EvalDatasetEntity;
import com.rag.domain.entity.EvalDatasetItemEntity;
import com.rag.domain.entity.EvalDatasetVersionEntity;
import com.rag.domain.entity.EvalRunEntity;
import com.rag.domain.enums.DatasetType;
import com.rag.domain.enums.EvalRunStatus;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import com.rag.eval.support.DatasetFileParser;
import com.rag.storage.repository.EvalDatasetItemRepository;
import com.rag.storage.repository.EvalDatasetRepository;
import com.rag.storage.repository.EvalDatasetVersionRepository;
import com.rag.storage.repository.EvalRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 评测数据集与运行服务（Task 6，contracts/openapi.yaml eval 域）。
 *
 * <p>导入语义（EV-4）：同名数据集已存在 → 在该数据集下产生新版本
 * （versionNo = 当前最大 + 1，旧版本不可变）；名称不存在 → 新建数据集 + 版本 1。
 * 契约 409 DUPLICATE_EVAL_DATASET 为「显式新建重名」等未来语义预留，V1 导入
 * 同名即追加版本，不抛 409（实现计划 Task 6 确认口径）。</p>
 *
 * <p>运行受理：建 eval_run(RUNNING) + configSnapshot（EV-4：按服务端当前实际值
 * 快照 chatModel/embeddingModel/embeddingDimensions/topK/minScore）后返回 202，
 * 执行交给 {@link EvalRunExecutor} 单线程池异步；{@link #runSync} 供测试/运维
 * 同步驱动。</p>
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    private final EvalDatasetRepository datasetRepository;
    private final EvalDatasetQueryRepository datasetQueryRepository;
    private final EvalDatasetVersionRepository versionRepository;
    private final EvalDatasetItemRepository itemRepository;
    private final EvalRunRepository runRepository;
    private final EvalVersionQueryRepository versionQueryRepository;
    private final EvalRunQueryRepository runQueryRepository;
    private final DatasetFileParser parser;
    private final EvalRunExecutor executor;
    private final RagProperties ragProperties;
    private final com.rag.storage.repository.DocumentRepository documentRepository;

    public EvalService(EvalDatasetRepository datasetRepository,
                       EvalDatasetQueryRepository datasetQueryRepository,
                       EvalDatasetVersionRepository versionRepository,
                       EvalDatasetItemRepository itemRepository,
                       EvalRunRepository runRepository,
                       EvalVersionQueryRepository versionQueryRepository,
                       EvalRunQueryRepository runQueryRepository,
                       DatasetFileParser parser,
                       EvalRunExecutor executor,
                       RagProperties ragProperties,
                       com.rag.storage.repository.DocumentRepository documentRepository) {
        this.datasetRepository = datasetRepository;
        this.datasetQueryRepository = datasetQueryRepository;
        this.versionRepository = versionRepository;
        this.itemRepository = itemRepository;
        this.runRepository = runRepository;
        this.versionQueryRepository = versionQueryRepository;
        this.runQueryRepository = runQueryRepository;
        this.parser = parser;
        this.executor = executor;
        this.documentRepository = documentRepository;
        this.ragProperties = ragProperties;
    }

    // ==================================================================
    // 数据集
    // ==================================================================

    /**
     * 导入数据集文件（POST /api/v1/eval/datasets）：
     * 解析 → 同名建新版本 / 新名建数据集+版本1 → 落样本行（seq 与文件顺序一致）。
     */
    @Transactional
    public EvalDatasetView importDataset(MultipartFile file, String name, DatasetType datasetType) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT,
                    "数据集名称必填且不超过 100 字符");
        }
        List<DatasetFileParser.EvalItem> items = parser.parse(file);

        EvalDatasetEntity dataset = datasetQueryRepository.findByName(name).orElse(null);
        if (dataset == null) {
            dataset = new EvalDatasetEntity();
            dataset.setName(name);
            dataset.setDatasetType(datasetType);
            dataset = datasetRepository.saveAndFlush(dataset);
        } else if (dataset.getDatasetType() != datasetType) {
            // spec-M3（EV-6）：同名数据集类型不一致必须拒绝，否则 TEST 样本会静默混入 TUNING 集
            throw new DomainException(ErrorCode.DUPLICATE_EVAL_DATASET,
                    "数据集「" + name + "」已存在且类型为 " + dataset.getDatasetType()
                            + "，与请求类型 " + datasetType + " 不一致（调优集与独立测试集不得混用）");
        }

        int nextVersionNo = versionRepository.findFirstByDatasetIdOrderByVersionNoDesc(dataset.getId())
                .map(EvalDatasetVersionEntity::getVersionNo).orElse(0) + 1;
        EvalDatasetVersionEntity version = new EvalDatasetVersionEntity();
        version.setDatasetId(dataset.getId());
        version.setVersionNo(nextVersionNo);
        version.setItemCount(items.size());
        version.setSourceName(file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename());
        version = versionRepository.saveAndFlush(version);

        List<EvalDatasetItemEntity> entities = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            DatasetFileParser.EvalItem item = items.get(i);
            EvalDatasetItemEntity entity = new EvalDatasetItemEntity();
            entity.setVersionId(version.getId());
            entity.setSeq(i + 1);
            entity.setQuestion(item.question());
            entity.setReferenceAnswer(item.referenceAnswer());
            entity.setEvidence(item.evidence());
            entity.setAnswerable(item.answerable());
            entity.setCategory(item.category());
            entities.add(entity);
        }
        itemRepository.saveAll(entities);
        itemRepository.flush();
        log.info("评测数据集导入完成 name={} versionNo={} itemCount={}", name, nextVersionNo, items.size());
        return toView(dataset, version);
    }

    /** 数据集列表（GET /api/v1/eval/datasets），含各自最新版本摘要。 */
    @Transactional(readOnly = true)
    public List<EvalDatasetView> listDatasets() {
        List<EvalDatasetView> views = new ArrayList<>();
        for (EvalDatasetEntity dataset : datasetRepository
                .findAll(Sort.by(Sort.Direction.DESC, "createdAt"))) {
            EvalDatasetVersionEntity latest = versionRepository
                    .findFirstByDatasetIdOrderByVersionNoDesc(dataset.getId()).orElse(null);
            views.add(toView(dataset, latest));
        }
        return views;
    }

    // ==================================================================
    // 运行
    // ==================================================================

    /**
     * 受理评测运行（POST /api/v1/eval/runs）：缺省版本取最新；缺省检索参数取
     * 服务端配置默认值。configSnapshot 按实际执行值记录（EV-4）。返回 202 前即
     * 落 RUNNING 行，执行异步。
     */
    @Transactional
    public EvalRunEntity createRun(UUID datasetId, UUID versionId, UUID kbId,
                                   Integer topKOverride, Double minScoreOverride) {
        EvalDatasetEntity dataset = datasetRepository.findById(datasetId.toString())
                .orElseThrow(() -> new DomainException(ErrorCode.EVAL_DATASET_NOT_FOUND));
        EvalDatasetVersionEntity version = versionId != null
                ? versionRepository.findById(versionId.toString())
                        .filter(v -> v.getDatasetId().equals(dataset.getId()))
                        .orElseThrow(() -> new DomainException(ErrorCode.EVAL_DATASET_NOT_FOUND,
                                "指定版本不存在或不属于该数据集"))
                : versionRepository.findFirstByDatasetIdOrderByVersionNoDesc(dataset.getId())
                        .orElseThrow(() -> new DomainException(ErrorCode.EVAL_DATASET_NOT_FOUND,
                                "数据集没有可用版本"));

        RagProperties.Retrieval cfg = ragProperties.getRetrieval();
        int topK = topKOverride != null ? topKOverride : cfg.getTopK();
        double minScore = minScoreOverride != null ? minScoreOverride : cfg.getMinScore();
        Map<String, Object> configSnapshot = new LinkedHashMap<>();
        configSnapshot.put("chatModel", ragProperties.getModels().getChat().getModelName());
        configSnapshot.put("embeddingModel", ragProperties.getModels().getEmbedding().getModelName());
        configSnapshot.put("embeddingDimensions", ragProperties.getModels().getEmbedding().getDimensions());
        configSnapshot.put("topK", topK);
        configSnapshot.put("minScore", minScore);
        // R2-H3/EV-4：检索配置必须进快照，否则两次运行无法区分（改融合参数=改配置）
        configSnapshot.put("retrievalMode", cfg.getMode().name());
        configSnapshot.put("rrfK", cfg.getRrf().getK());
        configSnapshot.put("candidateLimit", cfg.getRrf().getCandidateLimit());
        boolean rerankEnabled = cfg.getMode() == com.rag.retrieval.model.RetrievalMode.HYBRID_RERANK;
        configSnapshot.put("rerankEnabled", rerankEnabled);
        configSnapshot.put("rerankModel", rerankEnabled ? cfg.getRerank().getModelName() : "");
        configSnapshot.put("rerankDegradeOnFailure", cfg.getRerank().isDegradeOnFailure());
        // R2-A2：拒答规则也是评测口径的一部分（关掉会显著改变拒答正确率）
        configSnapshot.put("refusalEnabled", cfg.getRefusal().isEnabled());
        configSnapshot.put("refusalRerankThreshold", cfg.getRefusal().getRerankThreshold());
        configSnapshot.put("refusalCosineThreshold", cfg.getRefusal().getCosineThreshold());
        // EV-4 分块配置：评测对象知识库内各文档实际使用的策略/参数（同名键集合，多文档混合可见差异）
        Map<String, Object> chunkStrategies = new LinkedHashMap<>();
        Map<String, Object> chunkMaxLengths = new LinkedHashMap<>();
        documentRepository.findByKbId(kbId.toString(), org.springframework.data.domain.Pageable.unpaged())
                .forEach(d -> {
                    Object strategy = d.getChunkConfig() == null ? null : d.getChunkConfig().get("strategy");
                    Object maxLen = d.getChunkConfig() == null ? null : d.getChunkConfig().get("maxLength");
                    if (strategy != null) {
                        chunkStrategies.putIfAbsent(String.valueOf(strategy), Boolean.TRUE);
                    }
                    if (maxLen instanceof Number n) {
                        chunkMaxLengths.putIfAbsent(String.valueOf(n.intValue()), Boolean.TRUE);
                    }
                });
        configSnapshot.put("chunkStrategy", chunkStrategies.keySet());
        configSnapshot.put("maxChunkChars", chunkMaxLengths.keySet());

        EvalRunEntity run = new EvalRunEntity();
        run.setDatasetVersionId(version.getId());
        run.setKbId(kbId.toString());
        run.setStatus(EvalRunStatus.RUNNING);
        run.setConfigSnapshot(configSnapshot);
        run = runRepository.saveAndFlush(run);
        log.info("评测运行受理 runId={} datasetId={} versionNo={} kbId={} topK={} minScore={}",
                run.getId(), datasetId, version.getVersionNo(), kbId, topK, minScore);
        return run;
    }

    /** 运行列表分页（GET /api/v1/eval/runs；datasetId 过滤按其全部版本）。 */
    @Transactional(readOnly = true)
    public Page<EvalRunEntity> listRuns(UUID datasetId, int page, int pageSize) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(pageSize, 1), 100));
        if (datasetId != null) {
            List<String> versionIds = versionQueryRepository.findByDatasetId(datasetId.toString())
                    .stream().map(EvalDatasetVersionEntity::getId).toList();
            if (versionIds.isEmpty()) {
                return Page.empty(pageable);
            }
            return runQueryRepository.findByDatasetVersionIdInOrderByCreatedAtDesc(versionIds, pageable);
        }
        return runRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    /** 运行详情（GET /api/v1/eval/runs/{runId}），404 走 EVAL_RUN_NOT_FOUND。 */
    @Transactional(readOnly = true)
    public EvalRunEntity getRun(UUID runId) {
        return runRepository.findById(runId.toString())
                .orElseThrow(() -> new DomainException(ErrorCode.EVAL_RUN_NOT_FOUND));
    }

    /** 同步驱动一次运行（测试/运维入口；HTTP 路径用 {@link #submitAsync}）。 */
    public void runSync(UUID runId) {
        executor.executeRun(runRepository.findById(runId.toString())
                .orElseThrow(() -> new DomainException(ErrorCode.EVAL_RUN_NOT_FOUND)));
    }

    /** 运行所属数据集 ID（EvalRunSummary.datasetId；运行表只存 version 弱关联）。 */
    @Transactional(readOnly = true)
    public String resolveDatasetId(EvalRunEntity run) {
        return versionRepository.findById(run.getDatasetVersionId())
                .map(EvalDatasetVersionEntity::getDatasetId).orElse(null);
    }

    /** 异步提交（单线程池；受理失败不影响已落库的 RUNNING 行）。 */
    public void submitAsync(UUID runId) {
        executor.submit(runId.toString());
    }

    // ------------------------------------------------------------------

    private static EvalDatasetView toView(EvalDatasetEntity dataset,
                                          EvalDatasetVersionEntity latest) {
        return new EvalDatasetView(dataset.getId(), dataset.getName(), dataset.getDatasetType(),
                latest == null ? null
                        : new LatestVersion(latest.getId(), latest.getVersionNo(),
                                latest.getItemCount(), latest.getCreatedAt()),
                dataset.getCreatedAt());
    }

    /** 契约 EvalDataset（latestVersion 结构内联）。 */
    public record EvalDatasetView(String id, String name, DatasetType datasetType,
                                  LatestVersion latestVersion,
                                  java.time.LocalDateTime createdAt) {
    }

    public record LatestVersion(String versionId, int versionNo, int itemCount,
                                java.time.LocalDateTime createdAt) {
    }
}
