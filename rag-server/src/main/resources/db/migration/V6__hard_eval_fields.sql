-- =====================================================================
-- Flyway 迁移 V6__hard_eval_fields.sql
-- 对应：R6-A「Hard-case Eval Challenge Set」——评测口径升级
-- 创建日期：2026-09-17
-- 方言：MySQL 8 / InnoDB / utf8mb4
--
-- 背景：eval-v2 的负例只有 answerable=false 一种形态，无法区分
--       「证据不足」「实体混淆」「数字陷阱」「不支持推断」等失败机理，
--       诊断价值不足。本轮为 Hard Eval（eval-v3-hard）引入最小标签集。
--
-- 变更范围（纯增量，不改既有列语义，旧数据保持 NULL 可读）：
--   eval_dataset_item 新增 4 个可空列：
--     failure_mode        负例（answerable=0）失败机理枚举（8 值）；正例必须为 NULL
--     evidence_mode       正例证据形态：SINGLE_CHUNK / MULTI_CHUNK / FOLLOW_UP；负例为 NULL
--     tempting_evidence   负例的诱导性证据（最容易让系统误答的相关 chunk 锚点）
--     missing_requirement 负例缺失的关键条件说明（人工可读）
--
--   枚举值与后端 EvalFailureMode / EvalEvidenceMode 一致：
--     failure_mode IN (OUT_OF_KB, PARTIAL_EVIDENCE, MISSING_CONDITION,
--                      ENTITY_MISMATCH, SCOPE_MISMATCH, NUMERIC_MISMATCH,
--                      VERSION_CONFLICT, UNSUPPORTED_INFERENCE)
--     evidence_mode IN (SINGLE_CHUNK, MULTI_CHUNK, FOLLOW_UP)
-- =====================================================================

SET NAMES utf8mb4;

ALTER TABLE eval_dataset_item
    ADD COLUMN failure_mode VARCHAR(32) NULL
        COMMENT '负例失败机理（answerable=0 时非空）：OUT_OF_KB/PARTIAL_EVIDENCE/MISSING_CONDITION/ENTITY_MISMATCH/SCOPE_MISMATCH/NUMERIC_MISMATCH/VERSION_CONFLICT/UNSUPPORTED_INFERENCE；正例必须为 NULL'
        AFTER category,
    ADD COLUMN evidence_mode VARCHAR(20) NULL
        COMMENT '正例证据形态（answerable=1 时非空）：SINGLE_CHUNK/MULTI_CHUNK/FOLLOW_UP；负例为 NULL'
        AFTER failure_mode,
    ADD COLUMN tempting_evidence JSON NULL
        COMMENT '负例诱导性证据（EvidenceRef[] 结构，仅 answerable=0 可用）：最容易诱导系统误答的相关 chunk 锚点；正例为 NULL'
        AFTER evidence_mode,
    ADD COLUMN missing_requirement VARCHAR(500) NULL
        COMMENT '负例缺失的关键条件说明（仅 answerable=0 可用）；NULL=无'
        AFTER tempting_evidence;
