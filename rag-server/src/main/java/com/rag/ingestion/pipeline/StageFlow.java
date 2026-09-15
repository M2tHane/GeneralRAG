package com.rag.ingestion.pipeline;

import java.util.List;

import com.rag.domain.enums.PipelineStage;

/**
 * 入库流水线阶段状态机（路线 §3.1）。
 *
 * <p>合法迁移：QUEUED→PARSING→CLEANING→CHUNKING→EMBEDDING→INDEXING→COMPLETED。
 * COMPLETED 为终态；FAILED 不是状态机节点（任务由 API 层显式 retry 置回 QUEUED，
 * 从 failureStage 对应的 task.stage 续跑）。非法迁移抛 {@link IllegalStateException}。</p>
 */
public final class StageFlow {

    /** 固定执行顺序。 */
    public static final List<PipelineStage> EXECUTION_ORDER =
            List.of(PipelineStage.PARSING, PipelineStage.CLEANING, PipelineStage.CHUNKING,
                    PipelineStage.EMBEDDING, PipelineStage.INDEXING);

    private StageFlow() {
    }

    /** 阶段开始前校验迁移合法性，非法即抛（状态机守护，禁止跳阶段/回退/终态后再推进）。 */
    public static void assertLegal(PipelineStage from, PipelineStage to) {
        if (from == to) {
            // 同阶段重入仅可能出现在重试续跑的第一步（task.stage 已是该阶段），视为合法空操作
            if (EXECUTION_ORDER.contains(from)) {
                return;
            }
            throw illegal(from, to);
        }
        // QUEUED 是未开始标记（不在执行序列内）：启动迁移 QUEUED→PARSING 合法
        if (from == PipelineStage.QUEUED && to == PipelineStage.PARSING) {
            return;
        }
        int fromIdx = EXECUTION_ORDER.indexOf(from);
        int toIdx = EXECUTION_ORDER.indexOf(to);
        // 仅允许相邻推进（INDEXING→COMPLETED 为收官迁移）
        boolean legal = (fromIdx >= 0 && toIdx == fromIdx + 1)
                || (from == PipelineStage.INDEXING && to == PipelineStage.COMPLETED);
        if (!legal) {
            throw illegal(from, to);
        }
    }

    /**
     * 从某阶段开始的执行序列（含该阶段）。task.stage=QUEUED 表示从未开始，从 PARSING 起；
     * 重试续跑从 failureStage（即 task.stage）起；COMPLETED 为终态，拒绝再推进。
     */
    public static List<PipelineStage> stagesFrom(PipelineStage start) {
        if (start == PipelineStage.QUEUED) {
            return EXECUTION_ORDER;
        }
        int idx = EXECUTION_ORDER.indexOf(start);
        if (idx < 0) {
            throw new IllegalStateException("COMPLETED 为终态，不能再推进（stage=" + start + "）");
        }
        return EXECUTION_ORDER.subList(idx, EXECUTION_ORDER.size());
    }

    private static IllegalStateException illegal(PipelineStage from, PipelineStage to) {
        return new IllegalStateException("非法的阶段迁移：" + from + " → " + to);
    }
}
