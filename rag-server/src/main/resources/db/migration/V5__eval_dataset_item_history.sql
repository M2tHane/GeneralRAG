-- =====================================================================
-- Flyway 迁移 V5__eval_dataset_item_history.sql
-- 对应：R4.1「Answerability 稳定化」——FOLLOW_UP 评测口径修正
-- 创建日期：2026-09-16
-- 方言：MySQL 8 / InnoDB / utf8mb4
--
-- 背景：FOLLOW_UP 类样本（"那对因此分配失败的分片，还要做什么？"）语义上
--       依赖会话历史，但此前评测执行 history=[]——结果不能解释为
--       follow-up 能力。本迁移为评测样本增加可选对话历史，评测执行时
--       作为生成上下文（与问答链路 loadHistory 等价）传入。
--
-- 变更范围（纯增量）：
--   eval_dataset_item.history JSON NULL
--     结构 = 契约 HistoryTurn[]{role: user|assistant, content}，时间正序；
--     仅用于理解当前问题的指代（生成 prompt 的历史区），不作为证据；
--     NULL = 无历史（非 FOLLOW_UP 样本缺省）。
--     样本行随版本不可变（V1 口径），已导入版本不受影响。
-- =====================================================================

SET NAMES utf8mb4;

ALTER TABLE eval_dataset_item
    ADD COLUMN history JSON NULL
        COMMENT '可选对话历史[{role,content}]时间正序，仅用于解析当前问题指代；不构成回答证据；NULL=无历史'
        AFTER evidence;
