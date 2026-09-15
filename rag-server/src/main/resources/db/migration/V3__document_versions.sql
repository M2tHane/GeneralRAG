-- =====================================================================
-- V3 (R3-P3): 文档多版本管理
-- 方案见 docs/round3/03-实施记录-P3.md §2：
--   * document 加 root_id / version_no / is_active 三列（同表自引用，不新建表）
--   * root_id = 组内第一版 document.id；第一版 root_id = id（存量回填）
--   * UNIQUE(kb_id, content_sha256) 保留：同 KB 同内容仍 409（版本语义只针对
--     「同名且内容不同」的再上传）
--   * 组内至多一个 is_active=TRUE：应用层保证 + 联合唯一索引兜底
-- =====================================================================

ALTER TABLE document
    ADD COLUMN root_id    CHAR(36)    NOT NULL DEFAULT '' COMMENT '同源文档组标识 = 组内第一版 document.id；版本组查询入口' AFTER content_sha256,
    ADD COLUMN version_no INT         NOT NULL DEFAULT 1 COMMENT '组内版本号，从 1 递增（同 root_id 内唯一）' AFTER root_id,
    ADD COLUMN is_active  BOOLEAN     NOT NULL DEFAULT TRUE COMMENT '组内激活版本（检索只命中激活版本）；组内至多一个 TRUE' AFTER version_no;

-- 存量回填：全部既有文档视为 v1 激活版（root_id = id）
UPDATE document SET root_id = id, version_no = 1, is_active = TRUE;

-- 组内版本号唯一；激活唯一性用生成列 + 唯一索引兜底：
-- active_key = 激活时取 root_id，未激活取 NULL——MySQL 唯一索引对 NULL 不去重，
-- 天然允许多个未激活版本（第一版 root_id=id 时 CASE…ELSE id 会与激活行撞键，实测踩中）
ALTER TABLE document
    ADD COLUMN active_key CHAR(36)
        GENERATED ALWAYS AS (CASE WHEN is_active THEN root_id ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_document_root_version (root_id, version_no),
    ADD UNIQUE KEY uk_document_active (active_key),
    ADD KEY idx_document_root (root_id, version_no),
    ADD KEY idx_document_kb_active (kb_id, is_active);

