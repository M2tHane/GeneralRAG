# 通用知识问答 RAG

可本地运行、可演示、可评测的知识问答系统：文档入库（解析 → 清洗 → 分块 → 向量化 → 入库）→ **混合检索（BM25 + 向量 RRF 融合 + 重排）** → **证据充分性判定（低分直拒 + LLM Evidence Judge，两阶段门控）** → SSE 流式回答与来源引用 → 检索调试（分阶段位次 + Answerability 决策）→ 效果评测（分块级指标 + Answerability 混淆矩阵 + 两次运行对比）。

- 需求基线：`docs/01-需求理解.md`（第一版）、`docs/round2/01-需求理解.md`（第二轮）
- 技术路线：`docs/03-技术路线.md`
- API 契约（唯一事实源）：`contracts/openapi.yaml`
- 已批准 UX 决策：`prototype/DESIGN.md`（原型代码在 `prototype/`，为模拟数据演示）
- 第一版实现计划与验证报告：`docs/06-实现计划.md`、`docs/06-测试报告.html`
- 第二轮实施记录：`docs/round2/02-实施记录.md`
- 第三轮（格式扩展/Excel 分块/文档多版本）：`docs/round3/01-实施记录-P1.md` ~ `04-实施记录-R4Excel.md`
- 第四轮（Answerability）：`docs/round4/01-Baseline分析.md`、`docs/round4/02-实施记录.md`
- 第五轮（检索质量/Excel 分块）：`docs/round5/01-Round5实施记录.md`
- 项目范围冻结：`docs/FEATURE-FREEZE.md`（Current Scope / Completed / Known Limitations / Future Work）

## Quick Start

前置条件（版本来自 `pom.xml` / `package.json` 与实际运行验证）：

- JDK 17+、Maven 3.9+
- Docker + Docker Compose（全部中间件由 compose 提供）
- Node 20+ 与 pnpm（前端 Next.js 14）
- 模型服务：OpenAI 兼容端点的 Chat 与 Embedding 服务（本地 vLLM/Ollama 或远端 API 均可，密钥走环境变量不入仓库；默认 `startup-check=true` 时 Embedding 必须在启动时可达）
- 重排服务（**可选**）：DashScope 原生重排端点，**不是 OpenAI 兼容路径**（`/compatible-mode/v1/rerank` 返回 404）；不配置时重排自动降级为融合顺序，问答仍可用

```bash
# 1. Clone
git clone https://github.com/M2tHane/GeneralRAG && cd GeneralRAG

# 2. 配置环境变量（主路径：根目录 .env；应用不会自动读取 .env，需在启动后端时 source）
cp .env.example .env
# 编辑 .env，必填：CHAT_MODEL_BASE_URL/NAME、EMBEDDING_BASE_URL/NAME、
#                  CHAT_MODEL_API_KEY/EMBEDDING_API_KEY（本地推理服务可留空）、
#                  MYSQL_PASSWORD（与 docker-compose.yml 一致：rag-pass）
# 可选：RERANK_MODEL_*（不填则重排降级）、RAG_* 行为开关

# 3. 启动中间件（MySQL 3306 / ES 9200 / MinIO 9000+9001 控制台）
docker compose up -d
# rag-es 首次启动自动安装 analysis-ik 插件，约 1-2 分钟

# 4. 启动后端（http://localhost:8080）
cd rag-server
set -a && source ../.env && set +a
mvn spring-boot:run

# 5. 启动前端（http://localhost:3000；/api 由 next.config.mjs rewrites 代理到 8080）
cd ../rag-web
pnpm install
pnpm dev          # 生产模式可改用 pnpm build && pnpm start

# 6. 验证
curl http://localhost:8080/actuator/health   # {"status":"UP"}，含 db / elasticsearch 分项
# 浏览器打开 http://localhost:3000 → 自动跳转 /kb 知识库页
```

### 7. 上传第一个文档

前端「知识库」页创建知识库 → 进入详情上传 `md / txt / pdf / docx / xlsx / csv` → 文档列表轮询任务状态至 `COMPLETED`。命令行等价：

```bash
curl -X POST http://localhost:8080/api/v1/knowledge-bases \
  -H 'Content-Type: application/json' -d '{"name":"my-kb"}'
curl -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/documents -F file=@./doc.md
curl http://localhost:8080/api/v1/documents/{docId}/task   # status: QUEUED→RUNNING→COMPLETED
```

### 8. 问第一个问题

前端会话页选择知识库提问；命令行等价（SSE 流）：

```bash
curl -N -X POST http://localhost:8080/api/v1/qa/stream -H 'Content-Type: application/json' \
  -d '{"kbId":"...","sessionId":"...","question":"..."}'
```

`stage` 事件透出 RETRIEVAL → ANSWERABILITY → GENERATION 真实阶段；回答仅依据检索证据并带 citations 来源引用，证据不足时拒答且 citations 为空数组。

## Optional Services（与最小启动解耦）

| 服务 | 什么时候需要 | 如何启用 |
| --- | --- | --- |
| 重排服务 | 默认 `HYBRID_RERANK` 模式想要重排精度 | `.env` 填 `RERANK_MODEL_BASE_URL`（裸域名）/`RERANK_MODEL_API_KEY`/`RERANK_MODEL_NAME`；不可达时自动降级为融合顺序并在调试/评测中标注 |
| MinerU（OCR） | 扫描件/复杂版面 PDF | 部署 mineru-api 后在 `.env` 填 `MINERU_BASE_URL`，并设 `RAG_INGESTION_PDF_PARSER=mineru`（强制远端解析）或 `auto`（R6-D 质量探针路由） |

默认 `pdf-parser=pdfbox`，**最小启动不需要 MinerU**，普通文本 PDF 直接入库。

## Demo

仓库提供一套独立于 Eval Dataset 的 Golden Demo（`demo/`，Aurora Cloud 主题，6 份短文档 + 12 个演示问题），
覆盖 Direct QA、Hybrid Retrieval、Multi-source 组合、Follow-up Query Rewrite、Corrective 纠错、
Out-of-KB Refusal 和 Excel 表格检索，10~15 分钟可完整演示。

详见 `demo/README.md`（验证记录见 `demo/VALIDATION.md`）。

## Troubleshooting（本轮实际启动验证遇到的问题）

- **后端启动失败 `Access denied for user 'rag' ... (using password: NO)`**：`MYSQL_PASSWORD` 未生效——application.yaml 默认密码为空。确认根目录已 `cp .env.example .env`，并按上方 `set -a && source ../.env && set +a` 方式加载（compose 内 MySQL 密码固定 `rag-pass`）。
- **后端启动失败「embedding 启动探活失败 / 维度不一致」**：`EMBEDDING_BASE_URL/API_KEY/MODEL_NAME` 指向的端点不可达，或模型输出维度 ≠ `rag.models.embedding.dimensions`（固定 1024；改维度直接改该配置项，且需清空 ES 分块重新入库）。模型服务晚于应用启动的本地场景可临时 `RAG_MODELS_STARTUP_CHECK=false`。
- **前端页面请求 500**：`/api/*` 代理到 8080，后端未启动即 500——先确认后端 health。
- **rag-es 启动慢**：首启安装 analysis-ik 插件约 1-2 分钟（装进镜像层后重启不重复下载）。

## 检索模式与阈值口径（重要）

三种模式的 `score` 语义不同，**不可直接互相比较，切换模式后需重新校准 minScore 与拒答阈值**：

| 模式 | score 语义 | 说明 |
| --- | --- | --- |
| `VECTOR` | 余弦相似度 | 第一轮基线 |
| `HYBRID` | 余弦相似度（排序由 RRF 融合决定） | 融合分只用于排序；阈值判断用绝对余弦分 |
| `HYBRID_RERANK` | 重排相关度（请求内相对值） | 实测区分度最好 |

拒答阈值分两套口径：重排生效时用 `rerank-threshold`（默认 0.75，R4.1 重校准值，**模型绑定**，更换 rerank 模型或语料分布后必须重新校准）；降级/未启用重排时用 `cosine-threshold`（默认 0.30，**区分度弱**，实测资料外题余弦分与可答题区间重叠，无法可靠识别资料外问题）。

### 证据充分性判定（Answerability，R4）

相关不等于可回答——关键词高度重合的问题（同产品不同参数、部分证据、同技术不同功能）会拿到高重排分但证据不含答案。R4 起在检索与生成之间加入 `AnswerabilityPolicy`（问答/调试/评测共享同一路径）：

```text
score < lowThreshold（双口径阈值，同上）        → 直接拒答（LOW_SCORE_REFUSAL，不调 Judge）
其余 → LLM Evidence Sufficiency Judge          → 可回答（JUDGE_ACCEPT）/ 证据不足（JUDGE_REFUSE）
Judge 超时/不可用/解析失败                      → JUDGE_DEGRADED（默认退回旧阈值行为并显式标注）
```

- Judge 不看"是否相关"，只判断"**仅凭证据能否完整、明确回答**"：部分证据、同产品不同参数、需要外部知识补全 → 判不可回答。
- **不存在"高分直答"**：实测部分证据题与可答题的重排分分布完全重叠（两类都有 1.000），任何高分阈值都会漏掉假阳性（数据见 `docs/round4/01-Baseline分析.md`）。
- **重排阈值是模型绑定的，不是常量**：R4.1 用 72 题分数分布扫描重校准——`qwen3.7-text-rerank` 可答题 P25≈0.99，旧 0.65 阈值下 baseline 仍有 17 个 FP；`0.75` 在不新增 FRR 的前提下把确定无答案题挡在 Judge 之前（依据与完整扫描表见 `docs/round4/04-R4.1评测与校准.md` §B）。**更换 rerank 模型必须重新扫描**。
- **同模型 / 同 KB / 同 ground truth（v2 标签）实测**：False Answer Rate **45.95% → 5.41%**（FP 17→2，其中阈值直拒 3 + Judge 拦截 12），FRR 0% → 8.57%（3 题），检索指标零变化；Judge 降级率 0%（P95 4.65s）；E2E P50 +1.8s（Judge 代价）、Overall P95 -2.2s（拒答题免生成长尾）。BadCase 逐题归因与并发冒烟（1/4/8）见 `docs/round4/04-R4.1评测与校准.md`。
- 判定关闭：`RAG_ANSWERABILITY_ENABLED=false`（退回旧单阈值行为，仅对照用）；Judge 失败策略 `RAG_ANSWERABILITY_FAIL_CLOSED=true` 切换为保守拒答。

**R4.1 工程稳定化**（`docs/round4/03-R4.1稳定化.md`）：
- Judge 并发模型从单线程 executor + Future 改为 **Semaphore bulkhead + 同步调用**（`RAG_ANSWERABILITY_MAX_CONCURRENT_JUDGES`，默认 4；`RAG_ANSWERABILITY_BULKHEAD_WAIT_MS`，默认 500）——多请求真实并发，容量耗尽按 OVERLOADED 快速降级；
- Judge 超时语义单一化：`judge-timeout-seconds` 同时是 HTTP connect/read timeout（模型侧硬取消，无后台孤儿请求；重试关闭）；
- Judge 与生成使用**同一个 Context 实例**全文（`max-evidence-chars` 已删除，mid-chunk 截断不复存在）；
- 失败原因四分类可见于调试/评测/日志：TIMEOUT / OVERLOADED / MODEL_ERROR / INVALID_RESPONSE；
- **Eval 口径修正：Judge 降级（SYSTEM_ERROR）不计入混淆矩阵与拒答正确率**，在 `answerabilityConfusion.degraded` 单独透出——系统故障不得虚假提升 refusal 指标（`EvalFlowIT` 红绿用例守护）；
- 新增 `AnswerabilityFlowIT`（accept/refuse/malformed/timeout/低分直拒/SSE 契约顺序全覆盖）；
- 评测数据集支持可选 `history`（V5 迁移，FOLLOW_UP 指代解析；仅用于理解问题，不构成证据）；
- 标注指南：`docs/eval/answerability-labeling-guide.md`；answerability 数据集 v2（含标签修订与 FOLLOW_UP history）：`docs/eval/eval-answerability-v2-array.json`；
- 真实模型并发冒烟脚本：`scripts/smoke_concurrency.py`（SSE 全路径，1/4/8 并发实测：4 内零 OVERLOADED，8 时 6/16 快速降级为预期反压）。

### 无模型服务时的体验

`RAG_MODELS_STARTUP_CHECK=false` 可启动用于界面/管理链路验证；此时上传会在 EMBEDDING 阶段失败（原因可读、可重试），问答/调试返回 `RETRIEVAL_FAILED`——这是设计内的降级行为，不是缺陷。重排服务不可达时不会导致问答失败，而是显式降级为融合顺序并在调试页/评测运行中标注「已降级、未重排」。

配置项全览见 `rag-server/src/main/resources/application-example.yaml`（Advanced Configuration Reference；Quick Start 只需要 `.env`，两者不要同时作为主配置入口使用）；`rag.retrieval.*`（top-k / min-score / mode / rrf / rerank / refusal）、`rag.ingestion.max-upload-size-mb` 等均可按需覆盖。

## 评测（4）

1. 建知识库并上传 `docs/eval-corpus/` 下的语料（来源与说明见 `docs/eval/README.md`）；
2. 导入数据集：`POST /api/v1/eval/datasets`（multipart：file / name / datasetType）——
   - 第二轮格式：`docs/eval/eval-tuning-v2-array.json` / `eval-test-v2-array.json`，evidence 用**真实 chunkId + 分块级 titlePath** 锚定；
   - R3 格式扩展专项：`docs/eval/eval-formats-r3-v1-array.json`（docx/xlsx/csv 语料，contentHash 锚点）；
   - 第四轮 Answerability 专项集：`docs/eval/eval-answerability-v1-array.json`（72 题，contentHash 锚点，CONFUSABLE/PARTIAL_EVIDENCE/OUT_OF_KB 占 2/3）；
   - R4.1 修订版：`docs/eval/eval-answerability-v2-array.json`（3 处标签按标注指南修订 + 6 道 FOLLOW_UP 补 history）；
   - R6-A Hard Eval：`docs/eval/eval-v3-hard-v2-array.json`（80 题，7 类 failureMode 标签）；
   - 调优集与独立测试集严格分离，测试集不参与调参；
3. 发起运行：`POST /api/v1/eval/runs`（datasetId / kbId / 可选 topK、minScore）；
4. 查看结果：`GET /api/v1/eval/runs/{runId}`（Hit@1/3/5、Recall@5、MRR、拒答正确率、**Answerability 混淆矩阵（TP/FP/FN/TN、False Answer Rate、False Refusal Rate、Judge 调用率/降级率/耗时）、耗时拆分与 p50/p95/max、逐题回答与来源与判定记录**），`PATCH .../items/{itemId}` 人工标注；
5. 前端「效果评测」页可浏览运行列表、查看单次运行、**勾选两次运行做对比**（可比性守卫 + 指标变化 + 逐题计数 + 逐题下钻到分阶段位次）。

## 测试（5）

```bash
cd rag-server && mvn test        # 43 个测试类（含集成测试，直连开发 Docker 的 MySQL/ES/MinIO）
cd rag-web    && pnpm test       # vitest；pnpm e2e 需前后端同时在线
```

> ⚠ 集成测试需要先 `docker compose up -d` 启动开发基础设施；MySQL/ES/MinIO 不可用时测试直接失败（无 Testcontainers，R4.1.2 后移除）。测试只写自己创建的数据（随机 UUID + 独立 bucket），不做全库/全索引清理。test profile 与开发 ES mapping 完全一致（1024 维 + ik_max_word）；MinIO endpoint/凭据统一由环境配置提供，各 IT 仅覆盖自己的 bucket；Fake embedding 为 deterministic pseudo-random normalized vector，用于测试分数控制，不代表真实语义相似度。

## 已知局限

**第四轮（Answerability）新增口径限制**

- **Judge 不是真值**：Evidence Judge 会以 ≥0.95 的置信度误判——R4.1 同 ground truth 评测后仍残留 2 个 FP（都是 PARTIAL_EVIDENCE 语义边界题）与 3 个 FN（2 题 Judge 低置信/严格口径误拒 + 1 题阈值直拒），且 FP 的 Judge 置信度高达 0.90–0.98。置信度本身不可作为正确性信号。每次调整后都要人工复核数据集预期标签。
- **PARTIAL_EVIDENCE 的"完整回答"边界存在口径争议**："证据给出通用措施是否算覆盖逐项问题"没有客观答案；R4.1 残留 FP 全部属于此类，且 unanswerable 侧有 6 题重排分落在 0.95–1.0——**任何阈值都拦不住，纯指标层面 FAR 无法降到 0**，扩数据集时需先统一口径。
- **Judge 与生成共用同一模型**（本地单模型约束），判定与生成可能同源偏差；换独立小模型是后续优化点。
- **Answerability 使 E2E P50 上升约 1.8s**（Judge 平均 2.8s）；对延迟敏感场景可 `RAG_ANSWERABILITY_ENABLED=false` 回退到旧阈值行为（P95 反而因拒答题免生成长尾而下降）。
- **FRR 样本量小**：R4.1 的 FRR 8.57% 仅基于 3/35 题，置信区间宽，不能据此断言恶化程度。
- **PARTIAL_EVIDENCE 类别（R4）**：`EvalCategory` 新增枚举值，需 V4 迁移；旧客户端若硬编码枚举需同步。

**R4.1 期间发现的外部依赖限制**

- **rerank/embedding 分数语义绑定供应商模型**：DashScope 账号欠费导致 qwen3.7-text-rerank/qwen3.7-text-embedding 不可用，切换 gte-rerank-v2/text-embedding-v4 后重排分分布完全不同（可答题 top1 ≈0.45 vs 原模型 ≈0.99）——**低分阈值必须随 reranker 模型重新校准**（账号恢复后已重校准为 0.75，见 `docs/round4/04-R4.1评测与校准.md` §B）；更换 embedding 模型必须重建全部 KB 索引（向量空间不一致）。

**第二轮引入/变更的口径限制（必读）**

- **Hit@K 已下沉到分块级**：判定按 evidence 的 `chunkId` / `titlePath` / `anchorPath` 精确匹配，**不再回退到 docName 相等**。若数据集 evidence 缺少分块级锚点，该题记为未命中——这会在指标上如实体现为「数据集锚点不足」，而不是给出虚高的文档级满分。第一轮 Hit@K=1.00 是文档级饱和基线，**已退役，不可再作为对比基线**。
- **重排分不可跨请求比较**（厂商语义）：重排相关度是请求内相对值，不能跨查询/跨运行直接比数值。
- **两种 score 口径不可比**：见 §2 表格；跨模式的指标差异不能只归因于"检索能力"。
- **余弦阈值无法可靠区分资料外问题**：实测资料外题余弦分与可答题区间重叠，故 `VECTOR`/降级场景的拒答正确率不能代表系统能力；只有重排生效时的拒答数字有效。
- **两次运行是「可比」而非「逐位可复现」**：embedding 有浮点微差、HNSW 为近似检索、候选集可能变动。
- **重排使检索侧耗时上升**：实测从 ~340ms 升到 ~550ms（含重排调用）；生成耗时通常占主导。
- **ES 原生 RRF 不可用**：集群 license=basic 时 `retriever.rrf` 返回 403，融合在应用侧实现（也便于单测）。

**第一轮既有局限（仍有效）**

- 多轮指代追问（"那第二个方案呢"）检索仅用当前问题向量化，**本轮未做查询改写**：第二轮全部样本中仅 1 道 FOLLOW_UP 题证据偏弱，缺少 BadCase 群体支持无条件引入模型调用；待真实 BadCase 集形成后再决定。
- 更换 Embedding 模型/维度后需清空 ES 分块并重新入库（无向量索引迁移工具）。
- 扫描件 PDF 在解析阶段异步检出并以任务失败提示（`SCANNED_PDF_NOT_SUPPORTED`）。
- 单实例部署；入库任务依赖进程内线程池 + DB 任务表，多实例化前需替换为消息队列（见技术路线 §10.2）。
- `Message.error` 的 `requestId` 无存档，返回 null；会话标题缺省为「新会话」。
- 标题路径编号重复（`1.6. 6.`）已在第二轮修复；**但修复只对修复后重新入库的语料生效**，存量分块仍是旧路径，需重新入库（见 `docs/round2/02-实施记录.md`）。

## 交付物索引

| 项 | 路径 |
| --- | --- |
| 后端 | `rag-server/`（Maven 单模块，包边界见技术路线 §2；`com.rag.answerability` 为 R4 新增包） |
| 前端 | `rag-web/`（Next.js App Router） |
| 数据库迁移 | `rag-server/src/main/resources/db/migration/`（V1 初始化 11 表 → V8 parse_metadata，共 8 个，Flyway 管理） |
| 中间件编排 | `docker-compose.yml` |
| 评测材料 | `docs/eval-corpus/`、`docs/eval/`（answerability-v1 生成器：`scripts/gen-answerability-dataset.py`） |
| 轮次记录 | `docs/round2/` ~ `docs/round5/`（第五轮检索质量/Excel 分块；R6 系列见 git log 与评测页） |
| 工作流状态 | `.supie/state/`（README 与 archive 入库；current.yaml 为本地运行态，不入库） |
