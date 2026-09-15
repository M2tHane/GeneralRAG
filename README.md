# 通用知识问答 RAG

可本地运行、可演示、可评测的知识问答系统：文档入库（解析 → 清洗 → 分块 → 向量化 → 入库）→ **混合检索（BM25 + 向量 RRF 融合 + 重排）** → SSE 流式回答与来源引用 → 检索调试（分阶段位次）→ 效果评测（分块级指标 + 两次运行对比）。

- 需求基线：`docs/01-需求理解.md`（第一版）、`docs/round2/01-需求理解.md`（第二轮）
- 技术路线：`docs/03-技术路线.md`
- API 契约（唯一事实源）：`contracts/openapi.yaml`
- 已批准 UX 决策：`prototype/DESIGN.md`（原型代码在 `prototype/`，为模拟数据演示）
- 第一版实现计划与验证报告：`docs/06-实现计划.md`、`docs/06-测试报告.html`
- 第二轮实施记录：`docs/round2/02-实施记录.md`

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
export RAG_REFUSAL_THRESHOLD=0.65         # 重排口径的决定性阈值；切模式需重校准

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

### 无模型服务时的体验

`RAG_MODELS_STARTUP_CHECK=false` 可启动用于界面/管理链路验证；此时上传会在 EMBEDDING 阶段失败（原因可读、可重试），问答/调试返回 `RETRIEVAL_FAILED`——这是设计内的降级行为，不是缺陷。重排服务不可达时不会导致问答失败，而是显式降级为融合顺序并在调试页/评测运行中标注「已降级、未重排」。

## 3. 启动前端（rag-web）

```bash
cd rag-web
pnpm install
pnpm build && pnpm start   # http://localhost:3000（/api 由 Next.js rewrites 代理到 8080）
```

## 4. 评测

1. 建知识库并上传 `docs/eval-corpus/` 下的 4 篇语料（来源与说明见 `docs/eval/README.md`）；
2. 导入数据集：`POST /api/v1/eval/datasets`（multipart：file / name / datasetType）——
   - 第一轮格式：`docs/eval/eval-tuning-v1.json`（TUNING）/ `eval-test-v1.json`（TEST），evidence 用 docName 锚定；
   - 第二轮格式：`docs/eval/eval-tuning-v2-array.json` / `eval-test-v2-array.json`，evidence 用**真实 chunkId + 分块级 titlePath** 锚定（推荐）；
   - 调优集与独立测试集严格分离，测试集不参与调参。
3. 发起运行：`POST /api/v1/eval/runs`（datasetId / kbId / 可选 topK、minScore）；
4. 查看结果：`GET /api/v1/eval/runs/{runId}`（Hit@1/3/5、Recall@5、MRR、拒答正确率、耗时拆分与 p50/p95/max、逐题回答与来源），`PATCH .../items/{itemId}` 人工标注；
5. 前端「效果评测」页可浏览运行列表、查看单次运行、**勾选两次运行做对比**（可比性守卫 + 指标变化 + 逐题计数 + 逐题下钻到分阶段位次）。

## 5. 测试

```bash
cd rag-server && mvn test        # 130 个测试，含 Testcontainers 集成测试套件
cd rag-web    && pnpm test       # vitest；pnpm e2e 需前后端同时在线
```

## 已知局限

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
| 后端 | `rag-server/`（Maven 单模块，包边界见技术路线 §2） |
| 前端 | `rag-web/`（Next.js App Router） |
| 数据库迁移 | `rag-server/src/main/resources/db/migration/V1__init.sql`（11 表）、`V2__round2_retrieval_eval.sql`（耗时拆分两列） |
| 中间件编排 | `docker-compose.yml` |
| 评测材料 | `docs/eval-corpus/`、`docs/eval/` |
| 工作流状态 | `.supie/state/current.yaml`（运行态，不入库） |
