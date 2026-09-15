-- =====================================================================
-- Flyway 迁移 V2__round2_retrieval_eval.sql
-- 对应：第二轮「检索与效果优化」需求（docs/round2/01-需求理解.md）
-- 创建日期：2026-09-14
-- 方言：MySQL 8 / InnoDB / utf8mb4
--
-- 变更范围（纯增量，不改 V1 既有列语义，旧数据保持可读）：
--   1. eval_run_item 新增检索/生成耗时拆分两列（R2-L1）：
--      第一轮只有端到端 latency_ms，无法回答"慢在检索还是生成"。
--      两列均可空 = 未执行/旧数据。
--
-- 说明：
--   * retrieved / citations 等 JSON 列无需迁移：R2 新增的分阶段位次字段
--     （vectorRank/bm25Rank/fusedRank/rerankRank 等）是 JSON 内的可选键，
--     V1 的 retrieved 快照继续可读（缺键即为 null）。
--   * metrics JSON 同理新增 recallAt5/mrr/refusalAccuracy/rankDistribution/
--     latencyP50Ms/latencyP95Ms/latencyMaxMs 等键，无需 DDL。
--   * review_tag 枚举扩展（BadCase 归因）本轮未做：归因先由前端按检索阶段
--     与排名变化推导展示，人工仍用既有 5 值枚举标注；若后续需要入库归因类型，
--     届时新增迁移扩展 CHECK 约束（避免本轮引入未被使用的枚举值）。
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- eval_run_item：新增检索耗时与生成耗时（毫秒）
--   retrieval_ms  = 向量化 + 通道检索 + 融合 + 重排 + 有效性过滤
--   generation_ms = 模型生成耗时（拒答路径不调用模型，约为 0）
--   二者之和约等于既有 latency_ms（存在少量计时段落差异）
-- ---------------------------------------------------------------------
ALTER TABLE eval_run_item
    ADD COLUMN retrieval_ms INT NULL
        COMMENT '该题检索耗时（毫秒）：向量化+双通道+融合+重排；NULL=未执行或旧数据'
        AFTER latency_ms,
    ADD COLUMN generation_ms INT NULL
        COMMENT '该题生成耗时（毫秒）；拒答路径未调用模型，约为 0；NULL=未执行或旧数据'
        AFTER retrieval_ms;
