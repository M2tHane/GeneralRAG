-- =====================================================================
-- Flyway 迁移 V4__answerability_eval.sql
-- 对应：第四轮「Evidence Answerability」需求（Bad Case 驱动：
--       高相关但不可回答的问题穿透阈值被错误回答）
-- 创建日期：2026-09-15
-- 方言：MySQL 8 / InnoDB / utf8mb4
--
-- 变更范围（纯增量，不改既有列语义，旧数据保持可读）：
--   1. eval_category 扩展 PARTIAL_EVIDENCE（数据集样本类别 CHECK 约束）：
--      语料只覆盖问题要求的部分答案（如"三种方案优缺点"只有两种），
--      即使检索高度相关也应判为不可完整回答。旧枚举值不变。
--   2. eval_run_item 新增系统实际决策列（复盘混淆矩阵用）：
--      refused / decision_type / confidence / reason / degraded / latency。
--      此前拒答与否只能靠回答文案反推（脆弱且会漂移）。
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. eval_dataset_item.category 允许 PARTIAL_EVIDENCE
--    重建 CHECK：MySQL 8 会自动替换同名列级 CHECK（原约束随表定义，
--    通过 ALTER TABLE ... DROP CHECK + ADD 显式处理，兼容性强）。
-- ---------------------------------------------------------------------
ALTER TABLE eval_dataset_item
    DROP CHECK chk_eval_dataset_item_category,
    ADD CONSTRAINT chk_eval_dataset_item_category
        CHECK (category IN ('DIRECT', 'TERM_VARIATION', 'FOLLOW_UP',
                            'OUT_OF_KB', 'CONFUSABLE', 'PARTIAL_EVIDENCE'));

-- ---------------------------------------------------------------------
-- 2. eval_run_item：系统实际决策快照
--    decision_type 枚举与后端 AnswerabilityDecisionType 一致：
--      LOW_SCORE_REFUSAL      低于低分阈值，未调 Judge 直接拒答
--      NO_HITS                检索零命中，直接拒答
--      HIGH_CONFIDENCE_ACCEPT 高于高分阈值，未调 Judge 直接生成
--      JUDGE_ACCEPT           灰区，Judge 判可回答
--      JUDGE_REFUSE           灰区，Judge 判不可回答
--      JUDGE_DEGRADED         Judge 失败，按降级策略处理
--      ANSWERABILITY_DISABLED 拒答/Answerability 关闭（仅对照）
-- ---------------------------------------------------------------------
ALTER TABLE eval_run_item
    ADD COLUMN refused TINYINT(1) NULL
        COMMENT '系统是否拒答（含证据不足与 Answerability 判定）；NULL=未执行或旧数据'
        AFTER generation_ms,
    ADD COLUMN answerability_decision_type VARCHAR(40) NULL
        COMMENT 'Answerability 决策类型：LOW_SCORE_REFUSAL/NO_HITS/HIGH_CONFIDENCE_ACCEPT/JUDGE_ACCEPT/JUDGE_REFUSE/JUDGE_DEGRADED/ANSWERABILITY_DISABLED；NULL=未执行或旧数据'
        AFTER refused,
    ADD COLUMN answerability_confidence DOUBLE NULL
        COMMENT 'Judge 置信度 [0,1]；未调 Judge 为 NULL'
        AFTER answerability_decision_type,
    ADD COLUMN answerability_reason VARCHAR(500) NULL
        COMMENT '决策原因（Judge reason 或门控原因简述）；NULL=无'
        AFTER answerability_confidence,
    ADD COLUMN answerability_degraded TINYINT(1) NULL
        COMMENT 'Judge 是否降级（超时/解析失败/不可用）；未调 Judge 为 0'
        AFTER answerability_reason,
    ADD COLUMN answerability_latency_ms INT NULL
        COMMENT 'Answerability 判定耗时（毫秒，含 Judge 调用）；NULL=未执行或旧数据'
        AFTER answerability_degraded;
