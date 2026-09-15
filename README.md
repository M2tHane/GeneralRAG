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

## 环境要求

- JDK 17+、Maven 3.9+
- Docker（docker compose 提供全部中间件）
- Node 20+ / pnpm（前端）
- 模型服务：OpenAI 兼容端点的 Chat 与 Embedding 服务（本地 vLLM/Ollama 或远端 API 均可，密钥走环境变量，不入仓库）
- 重排服务（可选）：DashScope 原生重排端点。**注意它不是 OpenAI 兼容路径**（`/compatible-mode/v1/rerank` 返回 404），因此单独配置

## 1. 启动中间件

```bash
docker compose up -d
# rag-mysql  3306（root-pass / rag / rag-pass，库 rag）
# rag-es     9200（首启自动安装 analysis-ik 插件，约 1-2 分钟；日志见 "try load config ... IKAnalyzer"）
# rag-minio  9000 API / 9001 控制台（minioadmin/minioadmin，本地开发默认，生产必须替换）
```

## 2. 启动后端（rag-server）

```bash
cd rag-server
export MYSQL_PWD=rag-pass
export SPRING_DATASOURCE_USERNAME=rag
export SPRING_DATASOURCE_PASSWORD=rag-pass

# 模型服务（必填；startup-check 会真实调用 embedding 校验连通性与维度）
export CHAT_MODEL_BASE_URL=http://your-llm-endpoint/v1     # OpenAI 兼容
export CHAT_MODEL_API_KEY=...                              # 本地推理服务可留空
export CHAT_MODEL_NAME=your-chat-model
export EMBEDDING_BASE_URL=http://your-embed-endpoint/v1
export EMBEDDING_API_KEY=...
export EMBEDDING_MODEL_NAME=your-embedding-model
export EMBEDDING_DIMENSIONS=1024                           # 必须与模型实际输出一致，否则启动失败

# 重排服务（可选；不配置则重排相关能力自动降级为融合顺序，问答仍可用）
export RERANK_MODEL_BASE_URL=https://dashscope.aliyuncs.com  # 原生端点，非 OpenAI 兼容路径
export RERANK_MODEL_API_KEY=...
export RERANK_MODEL_NAME=qwen3.7-text-rerank

# 检索模式与拒答阈值（可选覆盖）
export RAG_RETRIEVAL_MODE=HYBRID_RERANK   # VECTOR | HYBRID | HYBRID_RERANK
export RAG_REFUSAL_RERANK_THRESHOLD=0.65  # 重排口径阈值（默认 0.65）；切模式需重校准
export RAG_REFUSAL_COSINE_THRESHOLD=0.30  # 余弦口径阈值（默认 0.30，重排降级时生效）
# Answerability 证据充分性判定（默认开启；低分直拒，其余交给 LLM Judge）
export RAG_ANSWERABILITY_ENABLED=true
export RAG_ANSWERABILITY_FAIL_CLOSED=false # Judge 失败时 false=退回旧阈值放行 / true=保守拒答

mvn spring-boot:run   # http://localhost:8080
```

配置项全览见 `rag-server/src/main/resources/application-example.yaml`；`rag.retrieval.*`（top-k / min-score / mode / rrf / rerank / refusal）、`rag.ingestion.max-upload-size-mb` 等均可按需覆盖。

### 检索模式与阈值口径（重要）

三种模式的 `score` 语义不同，**不可直接互相比较，切换模式后需重新校准 minScore 与拒答阈值**：

| 模式 | score 语义 | 说明 |
| --- | --- | --- |
| `VECTOR` | 余弦相似度 | 第一轮基线 |
| `HYBRID` | 余弦相似度（排序由 RRF 融合决定） | 融合分只用于排序；阈值判断用绝对余弦分 |
| `HYBRID_RERANK` | 重排相关度（请求内相对值） | 实测区分度最好 |

拒答阈值分两套口径：重排生效时用 `rerank-threshold`（默认 0.65，实测可分：可答题 ≥0.79、资料外 ≤0.63）；降级/未启用重排时用 `cosine-threshold`（默认 0.30，**区分度弱**，实测资料外题余弦分与可答题区间重叠，无法可靠识别资料外问题）。

### 证据充分性判定（Answerability，R4）

相关不等于可回答——关键词高度重合的问题（同产品不同参数、部分证据、同技术不同功能）会拿到高重排分但证据不含答案。R4 起在检索与生成之间加入 `AnswerabilityPolicy`（问答/调试/评测共享同一路径）：

```text
score < lowThreshold（双口径阈值，同上）        → 直接拒答（LOW_SCORE_REFUSAL，不调 Judge）
其余 → LLM Evidence Sufficiency Judge          → 可回答（JUDGE_ACCEPT）/ 证据不足（JUDGE_REFUSE）
Judge 超时/不可用/解析失败                      → JUDGE_DEGRADED（默认退回旧阈值行为并显式标注）
```

- Judge 不看"是否相关"，只判断"**仅凭证据能否完整、明确回答**"：部分证据、同产品不同参数、需要外部知识补全 → 判不可回答。
- **不存在"高分直答"**：实测部分证据题与可答题的重排分分布完全重叠（两类都有 1.000），任何高分阈值都会漏掉假阳性（数据见 `docs/round4/01-Baseline分析.md`）。
- 实测效果（72 题专项集 A/B）：**False Answer Rate 47.4% → 10.5%**，False Refusal Rate 0% → 5.9%，检索指标零变化；代价是 Judge 调用率 72.2%、平均 +2.6s。
- 判定关闭：`RAG_ANSWERABILITY_ENABLED=false`（退回旧单阈值行为，仅对照用）；Judge 失败策略 `RAG_ANSWERABILITY_FAIL_CLOSED=true` 切换为保守拒答。

**R4.1 工程稳定化**（`docs/round4/03-R4.1稳定化.md`）：
- Judge 并发模型从单线程 executor + Future 改为 **Semaphore bulkhead + 同步调用**（`RAG_ANSWERABILITY_MAX_CONCURRENT_JUDGES`，默认 4；`RAG_ANSWERABILITY_BULKHEAD_WAIT_MS`，默认 500）——多请求真实并发，容量耗尽按 OVERLOADED 快速降级；
- Judge 超时语义单一化：`judge-timeout-seconds` 同时是 HTTP connect/read timeout（模型侧硬取消，无后台孤儿请求；重试关闭）；
- Judge 与生成使用**同一个 Context 实例**全文（`max-evidence-chars` 已删除，mid-chunk 截断不复存在）；
- 失败原因四分类可见于调试/评测/日志：TIMEOUT / OVERLOADED / MODEL_ERROR / INVALID_RESPONSE；
- 新增 `AnswerabilityFlowIT`（accept/refuse/malformed/timeout/低分直拒/SSE 契约顺序全覆盖）；
- 评测数据集支持可选 `history`（V5 迁移，FOLLOW_UP 指代解析；仅用于理解问题，不构成证据）；
- 标注指南：`docs/eval/answerability-labeling-guide.md`；answerability 数据集 v2（含标签修订与 FOLLOW_UP history）：`docs/eval/eval-answerability-v2-array.json`。

### 无模型服务时的体验

`RAG_MODELS_STARTUP_CHECK=false` 可启动用于界面/管理链路验证；此时上传会在 EMBEDDING 阶段失败（原因可读、可重试），问答/调试返回 `RETRIEVAL_FAILED`——这是设计内的降级行为，不是缺陷。重排服务不可达时不会导致问答失败，而是显式降级为融合顺序并在调试页/评测运行中标注「已降级、未重排」。

## 3. 启动前端（rag-web）

```bash
cd rag-web
pnpm install
pnpm build && pnpm start   # http://localhost:3000（/api 由 Next.js rewrites 代理到 8080）
```

## 4. 评测

1. 建知识库并上传 `docs/eval-corpus/` 下的语料（来源与说明见 `docs/eval/README.md`）；
2. 导入数据集：`POST /api/v1/eval/datasets`（multipart：file / name / datasetType）——
   - 第一轮格式：`docs/eval/eval-tuning-v1.json`（TUNING）/ `eval-test-v1.json`（TEST），evidence 用 docName 锚定（**已退役**）；
   - 第二轮格式：`docs/eval/eval-tuning-v2-array.json` / `eval-test-v2-array.json`，evidence 用**真实 chunkId + 分块级 titlePath** 锚定；
   - 第四轮 Answerability 专项集：`docs/eval/eval-answerability-v1-array.json`（72 题，contentHash 锚点，CONFUSABLE/PARTIAL_EVIDENCE/OUT_OF_KB 占 2/3）；
   - R4.1 修订版：`docs/eval/eval-answerability-v2-array.json`（3 处标签按标注指南修订 + 6 道 FOLLOW_UP 补 history）；
   - 调优集与独立测试集严格分离，测试集不参与调参；
3. 发起运行：`POST /api/v1/eval/runs`（datasetId / kbId / 可选 topK、minScore）；
4. 查看结果：`GET /api/v1/eval/runs/{runId}`（Hit@1/3/5、Recall@5、MRR、拒答正确率、**Answerability 混淆矩阵（TP/FP/FN/TN、False Answer Rate、False Refusal Rate、Judge 调用率/降级率/耗时）、耗时拆分与 p50/p95/max、逐题回答与来源与判定记录**），`PATCH .../items/{itemId}` 人工标注；
5. 前端「效果评测」页可浏览运行列表、查看单次运行、**勾选两次运行做对比**（可比性守卫 + 指标变化 + 逐题计数 + 逐题下钻到分阶段位次）。

## 5. 测试

```bash
cd rag-server && mvn test        # 200 个测试，含 Testcontainers 集成测试套件（含 AnswerabilityFlowIT）
cd rag-web    && pnpm test       # vitest；pnpm e2e 需前后端同时在线
```

## 已知局限

**第四轮（Answerability）新增口径限制**

- **Judge 不是真值**：Evidence Judge 会以 ≥0.95 的置信度误判——A/B 后残留 4 个 FP 中 3 个是数据集预期过严、1 个是口径边界，但置信度本身不可作为正确性信号。每次调整后都要人工复核数据集预期标签。
- **PARTIAL_EVIDENCE 的"完整回答"边界存在口径争议**："证据给出通用措施是否算覆盖逐项问题"没有客观答案；专项集中此类边界题约占 1/6，扩数据集时需先统一口径。
- **Judge 与生成共用同一模型**（本地单模型约束），判定与生成可能同源偏差；换独立小模型是后续优化点。
- **Answerability 使 E2E 延迟上升约 1/3**（Judge 平均 2.57s）；对延迟敏感场景可 `RAG_ANSWERABILITY_ENABLED=false` 回退到旧阈值行为。
- **PARTIAL_EVIDENCE 类别（R4）**：`EvalCategory` 新增枚举值，需 V4 迁移；旧客户端若硬编码枚举需同步。

**R4.1 期间发现的外部依赖限制**

- **rerank/embedding 分数语义绑定供应商模型**：DashScope 账号欠费导致 qwen3.7-text-rerank/qwen3.7-text-embedding 不可用，切换 gte-rerank-v2/text-embedding-v4 后重排分分布完全不同——**低分阈值必须随 reranker 模型重新校准**，不能跨模型沿用；更换 embedding 模型必须重建全部 KB 索引（向量空间不一致）。

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
| 数据库迁移 | `rag-server/src/main/resources/db/migration/V1__init.sql`（11 表）、`V2__round2_retrieval_eval.sql`（耗时拆分两列）、`V3__document_versions.sql`（文档多版本）、`V4__answerability_eval.sql`（PARTIAL_EVIDENCE + 逐题决策列） |
| 中间件编排 | `docker-compose.yml` |
| 评测材料 | `docs/eval-corpus/`、`docs/eval/`（answerability-v1 生成器：`scripts/gen-answerability-dataset.py`） |
| 轮次记录 | `docs/round2/`、`docs/round3/`、`docs/round4/` |
| 工作流状态 | `.supie/state/current.yaml`（运行态，不入库） |
