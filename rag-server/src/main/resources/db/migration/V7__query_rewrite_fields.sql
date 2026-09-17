-- =====================================================================
-- Flyway 迁移 V7__query_rewrite_fields.sql
-- 对应：R6-C「History-aware Query Rewrite」——检索查询可观测性
-- 创建日期：2026-09-14
-- 方言：MySQL 8 / InnoDB / utf8mb4
--
-- 背景：R6-C 在检索前新增 history-aware Query Rewrite（仅改检索查询，
--       Judge/Generation 仍用原始问题）。BadCase 下钻需要知道每题
--       「实际用哪个查询检索、是否发生改写」，否则 FOLLOW_UP 类失败
--       无法归因到 rewrite 层还是召回层。
--
-- 变更范围（纯增量，不改既有列语义，旧数据保持 NULL 可读）：
--   eval_run_item 新增 2 个可空列：
--     retrieval_query  本次实际使用的检索查询（rewrite 后；未改写 = 原问题）
--     query_rewritten  检索查询是否发生改写
--   （rewrite reason/latency 不落库：reason 属 debug 文本、latency 进 run.metrics）
-- =====================================================================

SET NAMES utf8mb4;

ALTER TABLE eval_run_item
    ADD COLUMN retrieval_query VARCHAR(2000) NULL
        COMMENT 'R6-C：本次实际使用的检索查询（history-aware rewrite 后；未改写=原问题）；旧数据为 NULL'
        AFTER answerability_latency_ms,
    ADD COLUMN query_rewritten TINYINT(1) NULL
        COMMENT 'R6-C：检索查询是否发生 history-aware 改写；旧数据为 NULL'
        AFTER retrieval_query;
