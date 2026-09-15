-- =====================================================================
-- Flyway 迁移 V1__init.sql
-- 对应：docs/03-技术路线.md 第 4.1 节「MySQL 核心表」
-- 创建日期：2026-09-13
-- 方言：MySQL 8 / InnoDB / utf8mb4（utf8mb4_0900_ai_ci）
-- 说明：
--   * 主键统一为 CHAR(36) UUID，由应用侧生成（与 OpenAPI 契约 format: uuid 一致）。
--   * 状态列一律使用 VARCHAR + 本注释枚举取值，不使用 MySQL ENUM 类型，
--     避免后续增删枚举值带来的 ALTER 迁移痛苦。
--   * 时间列统一 DATETIME(3)（毫秒精度，支撑消息排序与延迟统计）；
--     created_at DEFAULT CURRENT_TIMESTAMP，updated_at 额外 ON UPDATE CURRENT_TIMESTAMP。
--   * 结构化扩展字段（chunk_config/payload/citations/evidence/config_snapshot/
--     metrics/retrieved）使用原生 JSON 类型，结构与 contracts/openapi.yaml
--     对应 schema（ChunkingConfig/Citation/EvidenceRef/EvalHit 等）同构。
--   * 分块正文不进 MySQL：chunk 全文唯一存于 Elasticsearch，MySQL 仅存
--     chunk_count 等聚合（见路线文档 4.1 末尾说明）。
--   * 本脚本不含任何种子数据（第一版无预置数据）。
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. knowledge_base 知识库
--    名称唯一，对应原型「重名创建被拒」（契约错误码 KB_NAME_DUPLICATED）。
-- ---------------------------------------------------------------------
CREATE TABLE knowledge_base (
    id          CHAR(36)     NOT NULL COMMENT '主键 UUID',
    name        VARCHAR(100) NOT NULL COMMENT '知识库名称，用户可见，全局唯一（maxLength 100，同契约）',
    description VARCHAR(500) NULL     COMMENT '知识库描述（maxLength 500，同契约），可空',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_base_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='知识库。name 唯一以落实「重名被拒」业务规则（KB_NAME_DUPLICATED）。';

-- ---------------------------------------------------------------------
-- 2. document 文档
--    去重约束 UNIQUE(kb_id, content_sha256)：去重维度 = 知识库范围 +
--    文件内容哈希（KB-8），sha256 于上传同步阶段计算，重复上传在
--    建行前即命中约束 → 409 DUPLICATE_DOCUMENT。
--    status 枚举：QUEUED / PROCESSING / COMPLETED / FAILED（契约 DocumentStatus）
--    current_stage / failure_stage 枚举：
--      QUEUED / PARSING / CLEANING / CHUNKING / EMBEDDING / INDEXING / COMPLETED
--      （契约 PipelineStage，禁止虚构阶段）
--    file_type 枚举：PDF / MD / TXT
-- ---------------------------------------------------------------------
CREATE TABLE document (
    id               CHAR(36)     NOT NULL COMMENT '主键 UUID',
    kb_id            CHAR(36)     NOT NULL COMMENT '所属知识库 ID',
    name             VARCHAR(500) NOT NULL COMMENT '用户上传的原始文件名，仅作展示，不参与对象键/路径拼接（安全约束见路线文档 §7）',
    file_type        VARCHAR(8)   NOT NULL COMMENT '文件类型，枚举：PDF / MD / TXT',
    size_bytes       BIGINT       NOT NULL COMMENT '文件字节数（上传上限 50MB）',
    content_sha256   CHAR(64)     NOT NULL COMMENT '文件内容 SHA-256 十六进制（小写），KB 范围去重依据（KB-8）',
    status           VARCHAR(16)  NOT NULL COMMENT '文档状态，枚举：QUEUED / PROCESSING / COMPLETED / FAILED',
    current_stage    VARCHAR(16)  NOT NULL COMMENT '当前流水线阶段，枚举：QUEUED / PARSING / CLEANING / CHUNKING / EMBEDDING / INDEXING / COMPLETED',
    chunk_count      INT          NOT NULL DEFAULT 0 COMMENT '已完成分块数量（聚合值；分块正文仅存 ES，见路线文档 §4.1）',
    chunk_config     JSON         NULL     COMMENT '生效的分块策略与参数，结构同契约 ChunkingConfig{strategy,maxLength,overlap}，KB-6 可解释',
    source_object_key VARCHAR(255) NULL    COMMENT 'MinIO 源对象键（ragsource/{kbId}/{docId}/source.*），仅 UUID 组成',
    parsed_object_key VARCHAR(255) NULL    COMMENT 'MinIO 解析+清洗产物对象键（parsed.txt）；存在即 PARSING 阶段完成标志，重试据此跳过（幂等）',
    failure_stage    VARCHAR(16)  NULL     COMMENT '失败发生阶段，枚举同 current_stage；仅 status=FAILED 时非空（如扫描件 → PARSING + SCANNED_PDF_NOT_SUPPORTED）',
    failure_reason   VARCHAR(512) NULL     COMMENT '失败原因（稳定错误码 + 人读文案）；仅 status=FAILED 时非空',
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（上传受理时间）',
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_document_kb_sha256 (kb_id, content_sha256) COMMENT 'KB 范围内容哈希去重（KB-8），重复上传 409 DUPLICATE_DOCUMENT',
    KEY idx_document_kb_status (kb_id, status) COMMENT '文档列表按知识库过滤 + 状态筛选',
    CONSTRAINT fk_document_kb FOREIGN KEY (kb_id) REFERENCES knowledge_base (id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='文档元数据。UNIQUE(kb_id, content_sha256) 落实 KB 范围内容去重；删 KB 级联删文档（跨存储清理由 cleanup_task 补偿）。';

-- ---------------------------------------------------------------------
-- 3. ingestion_task 入库任务
--    状态机唯一载体（路线文档 §3.1）。每文档仅保留一个当前任务，
--    无任务历史列表（第一版）。status 枚举：QUEUED / RUNNING / COMPLETED / FAILED；
--    stage 枚举同 document.current_stage；attempt 从 1 起。
--    认领用 CAS：UPDATE ... SET status='RUNNING' WHERE id=? AND status='QUEUED'，
--    天然防双跑；启动恢复将 QUEUED/RUNNING 复位为 QUEUED（RUNNING 记 attempt+1）。
-- ---------------------------------------------------------------------
CREATE TABLE ingestion_task (
    id             CHAR(36)     NOT NULL COMMENT '主键 UUID',
    document_id    CHAR(36)     NOT NULL COMMENT '所属文档 ID，唯一（每文档仅一个当前任务，任务表不做历史）',
    status         VARCHAR(16)  NOT NULL COMMENT '任务状态，枚举：QUEUED / RUNNING / COMPLETED / FAILED；QUEUED→RUNNING 经 CAS 认领防双跑',
    stage          VARCHAR(16)  NOT NULL COMMENT '当前阶段，枚举：QUEUED / PARSING / CLEANING / CHUNKING / EMBEDDING / INDEXING / COMPLETED；每阶段完成即推进',
    attempt        INT          NOT NULL DEFAULT 1 COMMENT '执行尝试次数，从 1 起；启动恢复对 RUNNING 任务 attempt+1 后复位 QUEUED',
    failure_stage  VARCHAR(16)  NULL     COMMENT '失败发生阶段，枚举同 stage；仅 status=FAILED 时非空，重试从该阶段按产物证据续跑',
    failure_reason VARCHAR(512) NULL     COMMENT '失败原因（稳定错误码 + 人读文案）；仅 status=FAILED 时非空',
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '任务创建时间（上传受理即建，status=QUEUED）',
    started_at     DATETIME(3)  NULL     COMMENT '首次进入 RUNNING 的时间；未开始为 NULL',
    finished_at    DATETIME(3)  NULL     COMMENT '终态（COMPLETED/FAILED）时间；未终态为 NULL',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ingestion_task_document (document_id) COMMENT '每文档至多一个当前任务',
    KEY idx_ingestion_task_status (status) COMMENT '启动恢复扫描 QUEUED/RUNNING 任务复位（路线文档 §3.1）',
    CONSTRAINT fk_ingestion_task_document FOREIGN KEY (document_id) REFERENCES document (id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='入库流水线任务（状态机唯一载体）。ON DELETE CASCADE：文档删除后任务失去意义，随文档级联删除。';

-- ---------------------------------------------------------------------
-- 4. cleanup_task 跨存储清理补偿任务
--    删除（文档/知识库）在 MySQL 事务内生效后，为每个受影响对象写本表
--    （ES 分块 + MinIO 对象），由 @Scheduled 每 30s 扫 PENDING/FAILED 重试
--    直至 DONE（无重试上限，attempts/last_error 留痕）——跨存储非原子的
--    补偿兜底（路线文档 §3.3）。
--    status 枚举：PENDING / RUNNING / DONE / FAILED；
--    scope 枚举：DOCUMENT / KNOWLEDGE_BASE；
--    store 枚举：ELASTICSEARCH / MINIO。
--    刻意不建外键：ref_id 为多态引用（document.id 或 knowledge_base.id，
--    由 scope 区分），且主档删除先于本表 DONE，外键 CASCADE/RESTRICT 均
--    不符合补偿语义——清理任务必须独立于主档存活，直到清理完成。
-- ---------------------------------------------------------------------
CREATE TABLE cleanup_task (
    id         CHAR(36)     NOT NULL COMMENT '主键 UUID',
    scope      VARCHAR(16)  NOT NULL COMMENT '清理范围，枚举：DOCUMENT / KNOWLEDGE_BASE，决定 ref_id 指向对象与清理键（doc_id / kb_id）',
    ref_id     CHAR(36)     NOT NULL COMMENT '被清理对象 ID（scope=DOCUMENT → document.id；scope=KNOWLEDGE_BASE → knowledge_base.id）；多态引用，刻意无外键（见表注释）',
    store      VARCHAR(16)  NOT NULL COMMENT '目标存储，枚举：ELASTICSEARCH / MINIO；同一删除会产生两条（每存储各一）',
    payload    JSON         NOT NULL COMMENT '清理参数（对象键前缀/doc_id/kb_id/chunk 估计数等），结构与各 store 的清理执行器约定一致',
    status     VARCHAR(16)  NOT NULL COMMENT '任务状态，枚举：PENDING / RUNNING / DONE / FAILED；FAILED 由调度器持续重试',
    attempts   INT          NOT NULL DEFAULT 0 COMMENT '已执行次数，每次尝试 +1（无上限，仅记录）',
    last_error VARCHAR(512) NULL     COMMENT '最近一次失败原因；仅 FAILED 后非空，下轮重试成功即清空',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '任务创建时间（删除事务内写入）',
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间（重试调度按陈旧度排序）',
    PRIMARY KEY (id),
    KEY idx_cleanup_task_status_updated (status, updated_at) COMMENT '调度器扫描 PENDING/FAILED 任务（按 updated_at 排序）',
    KEY idx_cleanup_task_ref (scope, ref_id) COMMENT '按被删对象反查清理任务（排障与 DeletionSummary 核对）',
    CONSTRAINT chk_cleanup_task_scope CHECK (scope IN ('DOCUMENT', 'KNOWLEDGE_BASE')),
    CONSTRAINT chk_cleanup_task_store CHECK (store IN ('ELASTICSEARCH', 'MINIO')),
    CONSTRAINT chk_cleanup_task_status CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='跨存储（ES/MinIO）删除补偿任务。无外键为刻意设计：主档删除后任务必须仍可独立重试直至 DONE。';

-- ---------------------------------------------------------------------
-- 5. chat_session 会话
--    删 KB 级联删会话（路线文档 §12 决策 2，DeletionSummary 给出计数）。
-- ---------------------------------------------------------------------
CREATE TABLE chat_session (
    id         CHAR(36)     NOT NULL COMMENT '主键 UUID',
    kb_id      CHAR(36)     NOT NULL COMMENT '所属知识库 ID，检索隔离边界（QA-1）',
    title      VARCHAR(100) NOT NULL COMMENT '会话标题（maxLength 100，同契约 SessionCreate.title；缺省取首条提问前 N 字）',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间',
    PRIMARY KEY (id),
    KEY idx_chat_session_kb (kb_id) COMMENT '按知识库列会话（会话列表页）',
    CONSTRAINT fk_chat_session_kb FOREIGN KEY (kb_id) REFERENCES knowledge_base (id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='问答会话。ON DELETE CASCADE：删知识库级联删会话（契约 DeletionSummary.sessionsDeleted）。';

-- ---------------------------------------------------------------------
-- 6. chat_message 消息
--    用户消息先落库（QA-8：输入不丢），助手消息终态时落库。
--    role 枚举：USER / ASSISTANT（契约 Message.role）；
--    status 枚举：COMPLETED / ERROR / CANCELED（契约 Message.status，
--    对应 SSE 终态 done / error / canceled，互斥）。
--    citations JSON 与契约 Citation[] 同构；仅来自该回答实际检索结果，
--    资料不足时为空数组（QA-6：无伪造引用），故 NOT NULL。
--    error_code 存稳定错误码（契约 Error.code 枚举），仅 status=ERROR 非空。
-- ---------------------------------------------------------------------
CREATE TABLE chat_message (
    id         CHAR(36)     NOT NULL COMMENT '主键 UUID',
    session_id CHAR(36)     NOT NULL COMMENT '所属会话 ID',
    role       VARCHAR(16)  NOT NULL COMMENT '消息角色，枚举：USER / ASSISTANT',
    content    MEDIUMTEXT   NOT NULL COMMENT '消息正文（用户提问或助手回答；问题上限 2000 字，回答按模型输出）',
    citations  JSON         NOT NULL COMMENT '引用列表，结构同契约 Citation[]（chunkId/docId/docName/titlePath/page/score）；仅 USER 消息与资料不足时为 []，绝不伪造引用（QA-6）',
    status     VARCHAR(16)  NOT NULL COMMENT '消息状态，枚举：COMPLETED / ERROR / CANCELED；与 SSE 终态 done/error/canceled 一一对应',
    error_code VARCHAR(64)  NULL     COMMENT '失败错误码（契约 Error.code 枚举，如 MODEL_TIMEOUT）；仅 status=ERROR 非空',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（用户消息于流开始前落库，QA-8）',
    PRIMARY KEY (id),
    KEY idx_chat_message_session_created (session_id, created_at) COMMENT '按会话取消息分页（含最近 N 条历史注入 prompt，QA-3）',
    CONSTRAINT fk_chat_message_session FOREIGN KEY (session_id) REFERENCES chat_session (id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT chk_chat_message_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT chk_chat_message_status CHECK (status IN ('COMPLETED', 'ERROR', 'CANCELED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='问答消息。ON DELETE CASCADE：删会话级联删消息（DeletionSummary.messagesDeleted）；消息正文不设 updated_at（消息不可编辑）。';

-- ---------------------------------------------------------------------
-- 7. eval_dataset 评测数据集
--    name 唯一（重复导入同名数据集 → DUPLICATE_EVAL_DATASET）。
--    dataset_type 枚举：TUNING / TEST（EV-6：调优集与独立测试集严格分离）。
-- ---------------------------------------------------------------------
CREATE TABLE eval_dataset (
    id           CHAR(36)     NOT NULL COMMENT '主键 UUID',
    name         VARCHAR(100) NOT NULL COMMENT '数据集名称（maxLength 100），全局唯一',
    dataset_type VARCHAR(16)  NOT NULL COMMENT '数据集类型，枚举：TUNING / TEST（EV-6 调优集/测试集分离标记）',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_eval_dataset_name (name),
    CONSTRAINT chk_eval_dataset_type CHECK (dataset_type IN ('TUNING', 'TEST'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='评测数据集（仅元数据）。样本内容在版本表中，导入产生新版本（EV-4）。';

-- ---------------------------------------------------------------------
-- 8. eval_dataset_version 评测数据集版本
--    导入产生新版本，旧版本不可变（EV-4 版本留痕），因此不提供
--    updated_at。UNIQUE(dataset_id, version_no) 保证版本号单调不重复。
--    ON DELETE CASCADE：删除数据集连带删除全部版本与样本（第一版无
--    单独删版本入口，级联即语义）。
-- ---------------------------------------------------------------------
CREATE TABLE eval_dataset_version (
    id          CHAR(36)    NOT NULL COMMENT '主键 UUID',
    dataset_id  CHAR(36)    NOT NULL COMMENT '所属数据集 ID',
    version_no  INT         NOT NULL COMMENT '版本号，同一数据集内从 1 递增；导入即产生新版本，旧版本只读',
    item_count  INT         NOT NULL DEFAULT 0 COMMENT '该版本样本条数（导入时统计写入）',
    source_name VARCHAR(255) NOT NULL COMMENT '导入来源文件名（留痕，仅展示用途）',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '版本创建时间（=导入时间）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_eval_dataset_version_no (dataset_id, version_no) COMMENT '同一数据集版本号唯一且递增',
    CONSTRAINT fk_eval_dataset_version_dataset FOREIGN KEY (dataset_id) REFERENCES eval_dataset (id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='评测数据集版本（不可变留痕，EV-4）。删数据集级联删版本。';

-- ---------------------------------------------------------------------
-- 9. eval_dataset_item 评测样本
--    EV-1/EV-5 字段完整。evidence JSON 与契约 EvidenceRef[]
--    {docName,titlePath,page,snippet} 同构；OUT_OF_KB 样本 evidence 为 []。
--    category 枚举：DIRECT / TERM_VARIATION / FOLLOW_UP / OUT_OF_KB / CONFUSABLE。
--    answerable=0 的样本预期「答不出/答不知道」，参与命中率统计时按语义处理。
-- ---------------------------------------------------------------------
CREATE TABLE eval_dataset_item (
    id               CHAR(36)      NOT NULL COMMENT '主键 UUID',
    version_id       CHAR(36)      NOT NULL COMMENT '所属数据集版本 ID',
    seq              INT           NOT NULL COMMENT '样本在版本内的序号（导入文件顺序，从 0 或 1 起，与导入记录一致）',
    question         VARCHAR(2000) NOT NULL COMMENT '评测问题（与问答输入上限一致，≤2000 字）',
    reference_answer TEXT          NULL     COMMENT '参考答案（人工编写，可为空；仅用于人工对照，不参与自动评分）',
    evidence         JSON          NULL     COMMENT '参考证据，结构同契约 EvidenceRef[]{docName,titlePath,page,snippet}；Hit@K 判定依据（EV-3）',
    answerable       TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否应可回答：1=是（默认），0=知识库外问题（OUT_OF_KB，预期拒答）',
    category         VARCHAR(20)   NOT NULL COMMENT '问题类别，枚举：DIRECT / TERM_VARIATION / FOLLOW_UP / OUT_OF_KB / CONFUSABLE',
    created_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（随版本导入写入，之后不可变）',
    PRIMARY KEY (id),
    KEY idx_eval_dataset_item_version_seq (version_id, seq) COMMENT '按版本取样本（顺序展示与评测执行读取）',
    CONSTRAINT fk_eval_dataset_item_version FOREIGN KEY (version_id) REFERENCES eval_dataset_version (id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT chk_eval_dataset_item_category CHECK (category IN ('DIRECT', 'TERM_VARIATION', 'FOLLOW_UP', 'OUT_OF_KB', 'CONFUSABLE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='评测样本（随版本不可变）。evidence 是 Hit@K 判定与人工标注的对照依据。';

-- ---------------------------------------------------------------------
-- 10. eval_run 评测运行
--     config_snapshot JSON（EV-4）：chatModel/embeddingModel/
--     embeddingDimensions/topK/minScore/chunkStrategy/maxChunkChars 等，
--     按实际执行值记录，保证两次运行可区分、可复现。
--     metrics JSON：hitAt1/hitAt3/hitAt5/avgLatencyMs/itemCount
--     （Hit@K 的 K = 该次运行 topK，见路线文档 §12 决策 6）。
--     status 枚举：RUNNING / COMPLETED / FAILED（契约 EvalRunStatus）。
--     kb_id 刻意不建外键：评测运行是对某次配置的度量留痕，知识库删除后
--     历史运行与 metrics 仍应保留可查（与 cleanup_task 同理，弱引用）。
--     dataset_version_id 用 RESTRICT：版本是评测结论的解释依据，存在
--     运行记录的版本不允许连带删除（第一版无删版本入口，防患于未然）。
-- ---------------------------------------------------------------------
CREATE TABLE eval_run (
    id                  CHAR(36)     NOT NULL COMMENT '主键 UUID',
    dataset_version_id  CHAR(36)     NOT NULL COMMENT '评测所用的数据集版本 ID（EV-4：指明跑的是哪个版本）',
    kb_id               CHAR(36)     NOT NULL COMMENT '评测目标知识库 ID；弱引用，刻意无外键——KB 删除后历史运行保留（见表注释）',
    status              VARCHAR(16)  NOT NULL COMMENT '运行状态，枚举：RUNNING / COMPLETED / FAILED',
    config_snapshot     JSON         NOT NULL COMMENT '运行配置快照（EV-4）：模型名/参数/topK/minScore/分块策略/向量维度等实际执行值',
    metrics             JSON         NULL     COMMENT '汇总指标 {hitAt1,hitAt3,hitAt5,avgLatencyMs,itemCount}；仅 COMPLETED 后非空',
    failure_reason      VARCHAR(512) NULL     COMMENT '失败原因（稳定错误码 + 人读文案）；仅 status=FAILED 非空',
    started_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '运行开始时间',
    finished_at         DATETIME(3)  NULL     COMMENT '运行结束时间；RUNNING 时为 NULL',
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间（=受理时间）',
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间',
    PRIMARY KEY (id),
    KEY idx_eval_run_version (dataset_version_id) COMMENT '按数据集版本列运行历史（运行列表页）',
    KEY idx_eval_run_kb (kb_id) COMMENT '按知识库过滤运行记录',
    CONSTRAINT fk_eval_run_version FOREIGN KEY (dataset_version_id) REFERENCES eval_dataset_version (id)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT chk_eval_run_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='评测运行。config_snapshot 使两次运行可区分（EV-4）；kb_id 弱引用保留历史，版本引用 RESTRICT 保护留痕。';

-- ---------------------------------------------------------------------
-- 11. eval_run_item 评测运行明细
--     每个样本一行的执行快照（EV-3）。retrieved JSON 与契约 EvalHit[]
--     {rank,chunkId,docName,titlePath,page,score} 同构；citations 与契约
--     Citation[] 同构。hit 可空（NULL=未判定/样本不可判定）；review_tag /
--     review_note 可空 = 未人工标注（契约 EvalRunItem.reviewTag nullable）。
--     review_tag 枚举：OK / WRONG_ANSWER / MISSING_EVIDENCE / WRONG_SOURCE / OTHER。
--     ON DELETE CASCADE：明细从属于运行，删运行（如未来做清理）连带删除。
-- ---------------------------------------------------------------------
CREATE TABLE eval_run_item (
    id               CHAR(36)      NOT NULL COMMENT '主键 UUID',
    run_id           CHAR(36)      NOT NULL COMMENT '所属评测运行 ID',
    seq              INT           NOT NULL COMMENT '样本在本次运行内的序号（对应 eval_dataset_item.seq，便于逐题对照）',
    question         VARCHAR(2000) NOT NULL COMMENT '评测问题（运行时从样本快照复制，运行记录自包含）',
    retrieved        JSON          NOT NULL COMMENT '本次运行检索命中快照，结构同契约 EvalHit[]{rank,chunkId,docName,titlePath,page,score}；至少为 []',
    generated_answer TEXT          NULL     COMMENT '模型实际回答文本；运行中断/该题未执行时为 NULL',
    citations        JSON          NULL     COMMENT '回答引用，结构同契约 Citation[]；未生成回答时为 NULL',
    hit              TINYINT(1)    NULL     COMMENT '参考证据是否出现在命中集中（样本级 Hit@K，K=该次运行 topK）；NULL=尚未判定',
    latency_ms       INT           NULL     COMMENT '该题端到端耗时（毫秒）；未执行为 NULL',
    review_tag       VARCHAR(20)   NULL     COMMENT '人工标注的失败原因，枚举：OK / WRONG_ANSWER / MISSING_EVIDENCE / WRONG_SOURCE / OTHER；NULL=未标注',
    review_note      VARCHAR(1000) NULL     COMMENT '人工标注备注（maxLength 1000，同契约 EvalReviewUpdate.reviewNote）；NULL=未标注',
    created_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（随运行写入）',
    updated_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新时间（人工标注会更新此列）',
    PRIMARY KEY (id),
    KEY idx_eval_run_item_run_seq (run_id, seq) COMMENT '按运行取明细（分页展示按 seq 排序，逐题对照）',
    CONSTRAINT fk_eval_run_item_run FOREIGN KEY (run_id) REFERENCES eval_run (id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT chk_eval_run_item_review_tag CHECK (review_tag IN ('OK', 'WRONG_ANSWER', 'MISSING_EVIDENCE', 'WRONG_SOURCE', 'OTHER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='评测运行明细（每样本一行的执行快照，EV-3）。review 两列可空=未标注；retrieved 为不可变快照。';
