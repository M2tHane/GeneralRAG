package com.rag.api.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rag.domain.entity.EvalRunEntity;
import com.rag.domain.enums.DatasetType;
import com.rag.domain.enums.ReviewTag;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import com.rag.eval.EvalRunViewService;
import com.rag.eval.EvalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 评测接口（contracts/openapi.yaml eval 域：listEvalDatasets / importEvalDataset /
 * createEvalRun / listEvalRuns / getEvalRun / reviewEvalRunItem）。
 *
 * <p>HTTP 状态按契约：导入 201、发起运行 202、其余 200；404 用 EVAL_DATASET_NOT_FOUND /
 * EVAL_RUN_NOT_FOUND（含 run 下无此 item 的情况）；文件解析失败 422 INVALID_DATASET_FILE
 * 由 DatasetFileParser 抛出、GlobalExceptionHandler 统一转换。</p>
 */
@RestController
@RequestMapping("/api/v1/eval")
public class EvalController {

    private final EvalService evalService;
    private final EvalRunViewService runViewService;

    public EvalController(EvalService evalService, EvalRunViewService runViewService) {
        this.evalService = evalService;
        this.runViewService = runViewService;
    }

    // ------------------------------------------------------------------
    // 数据集
    // ------------------------------------------------------------------

    @GetMapping("/datasets")
    @Operation(operationId = "listEvalDatasets", summary = "数据集列表")
    public List<EvalService.EvalDatasetView> listDatasets() {
        return evalService.listDatasets();
    }

    @PostMapping(value = "/datasets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(operationId = "importEvalDataset", summary = "导入评测数据集（JSON 或 CSV 文件，产生新版本）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "成功")
    @ResponseStatus(HttpStatus.CREATED)
    public EvalService.EvalDatasetView importDataset(
            @RequestPart("file") MultipartFile file,
            @RequestPart("name") String name,
            @RequestPart("datasetType") String datasetType) {
        DatasetType type;
        try {
            type = DatasetType.valueOf(datasetType);
        } catch (IllegalArgumentException e) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT,
                    "datasetType 非法（合法值：TUNING/TEST）：" + datasetType);
        }
        return evalService.importDataset(file, name, type);
    }

    // ------------------------------------------------------------------
    // 运行
    // ------------------------------------------------------------------

    @PostMapping("/runs")
    @Operation(operationId = "createEvalRun", summary = "发起评测运行（异步）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "成功")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> createRun(@Valid @RequestBody EvalRunCreateRequest request) {
        UUID datasetId = parseUuid(request.datasetId(), "datasetId");
        UUID kbId = parseUuid(request.kbId(), "kbId");
        UUID versionId = request.versionId() == null ? null : parseUuid(request.versionId(), "versionId");
        EvalRunEntity run = evalService.createRun(datasetId, versionId, kbId,
                request.topK(), request.minScore());
        evalService.submitAsync(UUID.fromString(run.getId()));
        return Map.of("runId", run.getId(), "status", run.getStatus().name());
    }

    @GetMapping("/runs")
    @Operation(operationId = "listEvalRuns", summary = "评测运行列表")
    public Map<String, Object> listRuns(
            @RequestParam(required = false) String datasetId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(name = "pageSize", defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        UUID datasetUuid = datasetId == null ? null : parseUuid(datasetId, "datasetId");
        Page<EvalRunEntity> result = evalService.listRuns(datasetUuid, page, pageSize);
        return Map.of(
                "items", result.getContent().stream().map(this::toSummary).toList(),
                "page", result.getNumber() + 1,
                "pageSize", result.getSize(),
                "total", result.getTotalElements());
    }

    @GetMapping("/runs/{runId}")
    @Operation(operationId = "getEvalRun", summary = "评测运行详情（指标快照 + 样本结果分页）")
    public Map<String, Object> getRun(@PathVariable UUID runId,
                                      @RequestParam(defaultValue = "1") @Min(1) int page,
                                      @RequestParam(name = "pageSize", defaultValue = "20")
                                      @Min(1) @Max(100) int pageSize) {
        EvalRunEntity run = evalService.getRun(runId);
        EvalRunViewService.ItemPage items = runViewService.listItems(run, page, pageSize);
        Map<String, Object> detail = new LinkedHashMap<>(toSummary(run));
        detail.put("configSnapshot", run.getConfigSnapshot());
        detail.put("failureReason", run.getFailureReason());
        detail.put("items", Map.of(
                "items", items.items(),
                "page", items.page(),
                "pageSize", items.pageSize(),
                "total", items.total()));
        return detail;
    }

    @PatchMapping("/runs/{runId}/items/{itemId}")
    @Operation(operationId = "reviewEvalRunItem", summary = "人工标记样本失败原因")
    public Map<String, Object> reviewRunItem(@PathVariable UUID runId, @PathVariable UUID itemId,
                                             @Valid @RequestBody EvalReviewUpdateRequest request) {
        EvalRunEntity run = evalService.getRun(runId);
        return runViewService.review(run, itemId, request.reviewTag(), request.reviewNote());
    }

    // ------------------------------------------------------------------
    // DTO（与 contracts/openapi.yaml 对应 schema 字段一字不差）
    // ------------------------------------------------------------------

    /** 契约 EvalRunCreate。 */
    public record EvalRunCreateRequest(@NotNull String datasetId, String versionId,
                                       @NotNull String kbId,
                                       @Min(1) @Max(50) Integer topK,
                                       @Min(0) @Max(1) Double minScore) {
    }

    /** 契约 EvalReviewUpdate。 */
    public record EvalReviewUpdateRequest(@NotNull ReviewTag reviewTag, String reviewNote) {
    }

    /**
     * 契约 EvalRunSummary：既有指标展开为顶层字段（兼容第一轮消费方），
     * 并附 configSnapshot 与 metrics 原始对象——第二轮列表需要展示检索模式，
     * 对比页需要全部 R2 指标（Recall@K/MRR/拒答正确率/耗时分位），
     * 逐字段展开既易漏又易与 metrics 漂移。
     */
    private Map<String, Object> toSummary(EvalRunEntity run) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", run.getId());
        summary.put("datasetId", evalService.resolveDatasetId(run));
        summary.put("datasetVersionId", run.getDatasetVersionId());
        summary.put("kbId", run.getKbId());
        summary.put("status", run.getStatus().name());
        Map<String, Object> metrics = run.getMetrics();
        summary.put("itemCount", metrics == null ? null : metrics.get("itemCount"));
        summary.put("hitAt1", metrics == null ? null : metrics.get("hitAt1"));
        summary.put("hitAt3", metrics == null ? null : metrics.get("hitAt3"));
        summary.put("hitAt5", metrics == null ? null : metrics.get("hitAt5"));
        summary.put("avgLatencyMs", metrics == null ? null : metrics.get("avgLatencyMs"));
        // R2：新指标随 metrics 透出（召回/排名/拒答/耗时拆分与分位），避免逐字段展开遗漏
        summary.put("metrics", metrics);
        // R2-C3 可比性守卫需要配置快照（检索模式/融合参数/分块配置）
        summary.put("configSnapshot", run.getConfigSnapshot());
        summary.put("failureReason", run.getFailureReason());
        summary.put("createdAt", run.getCreatedAt());
        summary.put("finishedAt", run.getFinishedAt());
        return summary;
    }

    private static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, field + " 不是合法 UUID：" + value);
        }
    }
}
