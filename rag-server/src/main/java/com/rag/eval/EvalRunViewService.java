package com.rag.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rag.domain.entity.EvalDatasetItemEntity;
import com.rag.domain.entity.EvalRunEntity;
import com.rag.domain.entity.EvalRunItemEntity;
import com.rag.domain.enums.ReviewTag;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import com.rag.storage.repository.EvalDatasetItemRepository;
import com.rag.storage.repository.EvalDatasetVersionRepository;
import com.rag.storage.repository.EvalRunItemRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评测运行明细读取/标注服务（Task 6）：EvalRunSummary / EvalRunDetail / EvalRunItem
 * 视图组装（contracts/openapi.yaml）。
 *
 * <p>eval_run_item 表不冗余存 answerable/category/referenceAnswer（V1__init.sql），
 * 这些字段随 eval_dataset_item 按版本不可变，读取时按 (versionId, seq) 关联补齐——
 * 数据集版本不可变（EV-4）保证该关联在运行生命周期内稳定。</p>
 */
@Service
public class EvalRunViewService {

    private final EvalRunItemRepository runItemRepository;
    private final EvalDatasetItemRepository datasetItemRepository;
    private final EvalDatasetVersionRepository versionRepository;

    public EvalRunViewService(EvalRunItemRepository runItemRepository,
                              EvalDatasetItemRepository datasetItemRepository,
                              EvalDatasetVersionRepository versionRepository) {
        this.runItemRepository = runItemRepository;
        this.datasetItemRepository = datasetItemRepository;
        this.versionRepository = versionRepository;
    }

    /** 运行明细分页（已关联样本元数据 → 契约 EvalRunItem 视图）。 */
    @Transactional(readOnly = true)
    public ItemPage listItems(EvalRunEntity run, int page, int pageSize) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(pageSize, 1), 100));
        Page<EvalRunItemEntity> result = runItemRepository.findByRunIdOrderBySeqAsc(
                run.getId(), pageable);
        Map<Integer, EvalDatasetItemEntity> datasetItems = datasetItemsByVersion(
                run.getDatasetVersionId());
        List<Map<String, Object>> views = result.getContent().stream()
                .map(item -> toItem(item, datasetItems.get(item.getSeq())))
                .toList();
        return new ItemPage(views, result.getNumber() + 1, result.getSize(),
                result.getTotalElements());
    }

    /** 人工标注（PATCH）：run 下不存在该 item → EVAL_RUN_NOT_FOUND（契约口径）。 */
    @Transactional
    public Map<String, Object> review(EvalRunEntity run, UUID itemId, ReviewTag reviewTag,
                                      String reviewNote) {
        EvalRunItemEntity item = runItemRepository.findById(itemId.toString())
                .filter(i -> i.getRunId().equals(run.getId()))
                .orElseThrow(() -> new DomainException(ErrorCode.EVAL_RUN_NOT_FOUND,
                        "该运行下不存在此明细样本"));
        item.setReviewTag(reviewTag);
        item.setReviewNote(reviewNote);
        item = runItemRepository.saveAndFlush(item);
        return toItem(item, datasetItemsByVersion(run.getDatasetVersionId()).get(item.getSeq()));
    }

    // ------------------------------------------------------------------

    private Map<Integer, EvalDatasetItemEntity> datasetItemsByVersion(String versionId) {
        Map<Integer, EvalDatasetItemEntity> map = new LinkedHashMap<>();
        for (EvalDatasetItemEntity item : datasetItemRepository
                .findByVersionIdOrderBySeqAsc(versionId)) {
            map.put(item.getSeq(), item);
        }
        return map;
    }

    /** 契约 EvalRunItem 结构（datasetItem 提供表未冗余的 answerable/category/referenceAnswer）。 */
    static Map<String, Object> toItem(EvalRunItemEntity item, EvalDatasetItemEntity datasetItem) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", item.getId());
        view.put("seq", item.getSeq());
        view.put("question", item.getQuestion());
        view.put("referenceAnswer", datasetItem == null ? null : datasetItem.getReferenceAnswer());
        view.put("answerable", datasetItem != null && datasetItem.isAnswerable());
        view.put("category", datasetItem == null ? null : datasetItem.getCategory().name());
        view.put("retrieved", item.getRetrieved());
        view.put("generatedAnswer", item.getGeneratedAnswer());
        view.put("citations", item.getCitations());
        view.put("hit", item.getHit());
        view.put("latencyMs", item.getLatencyMs());
        // R2-L1：检索/生成耗时拆分（旧数据为 null，契约声明 nullable）
        view.put("retrievalMs", item.getRetrievalMs());
        view.put("generationMs", item.getGenerationMs());
        // R2-E2：正确答案分块的首个命中位次（对已落库 retrieved 快照复算，与指标口径同源）
        view.put("evidenceRank", evidenceRank(item, datasetItem));
        view.put("reviewTag", item.getReviewTag() == null ? null : item.getReviewTag().name());
        view.put("reviewNote", item.getReviewNote());
        return view;
    }

    /**
     * 参考证据在本次运行 retrieved 快照中的首个命中位次（R2-E2）。
     *
     * <p>复用 {@link EvidenceMatcher}，与指标判定同一口径——避免"详情页显示的名次"
     * 与"指标分子"口径不一致。answerable=false（资料外题）无参考证据，返回 null。</p>
     */
    private static Integer evidenceRank(EvalRunItemEntity item, EvalDatasetItemEntity datasetItem) {
        if (datasetItem == null || !datasetItem.isAnswerable()) {
            return null;
        }
        List<Map<String, Object>> evidence = datasetItem.getEvidence();
        if (evidence == null || evidence.isEmpty() || item.getRetrieved() == null) {
            return null;
        }
        List<EvidenceMatcher.CandidateRef> candidates = new ArrayList<>(item.getRetrieved().size());
        for (Map<String, Object> hit : item.getRetrieved()) {
            candidates.add(new EvidenceMatcher.CandidateRef(
                    hit.get("contentHash") == null ? null : String.valueOf(hit.get("contentHash")),
                    hit.get("chunkId") == null ? null : String.valueOf(hit.get("chunkId")),
                    hit.get("titlePath") == null ? null : String.valueOf(hit.get("titlePath"))));
        }
        int rank = EvidenceMatcher.firstMatchRankOfAllInSnapshot(evidence, candidates);
        return rank > 0 ? rank : null;
    }

    /** 契约 EvalRunItemPage。 */
    public record ItemPage(List<Map<String, Object>> items, int page, int pageSize, long total) {
    }
}
