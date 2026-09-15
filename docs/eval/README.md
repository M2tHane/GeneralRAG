# 评测准备

## 0. 第一轮与第二轮的数据集版本（重要）

| 版本 | 文件 | 锚定方式 | 状态 |
| --- | --- | --- | --- |
| v1 | `eval-tuning-v1.json` / `eval-test-v1.json`（含 `-array` 契约格式） | `docName` + 手写 titlePath | **已退役**：第一轮命中判定实际退化为文档级（evidence titlePath 从未匹配真实分块路径，命中全靠 docName 相等），Hit@K=1.00 是饱和的假满分 |
| **v2** | `eval-tuning-v2-array.json` / `eval-test-v2-array.json` | **真实 chunkId + 分块级 titlePath**（按分块器实际输出生成） | **当前版本**：第二轮分块级判定使用，evidence 逐条锚定真实 chunkId |

第二轮起命中判定按 `chunkId` / `titlePath` / `anchorPath` 精确匹配，**不再回退到 docName**——证据缺分块级锚点的题会如实判为未命中（体现"数据集锚点不足"），而不是给出文档级虚高满分。因此 **v1 数据集不可再用于第二轮口径的评测**。

第一轮冒烟结果存档：调优集 6 题 / 测试集 10 题，Hit@1/3/5=1.00（文档级口径，已退役），调优集 avg 2782ms、测试集 avg 4732ms（runId 见 `.supie/state/archive/round1-completed-20260914.yaml`）。

第二轮对照实测（同一新库、同一 v2 测试集版本、分块级锚点，真实模型）：

| 指标 | VECTOR | HYBRID_RERANK |
| --- | --- | --- |
| Hit@1 | 0.50 | 1.00 |
| MRR | 0.708 | 1.00 |
| 拒答正确率 | 0.80 | 1.00 |
| 检索耗时均值 | 344 ms | 553 ms |

详见 `docs/round2/02-实施记录.md` §3.3。

## 1. 语料来源与合规

| 语料文件 | 来源 | 抓取时间 |
| --- | --- | --- |
| elasticsearch-cluster-health.md | elastic.co 官方文档（cluster-health 与磁盘水位章节） | 2026-09-14 |
| redis-persistence.md | redis.io 官方文档（persistence） | 2026-09-14 |
| kafka-consumer-groups.md | kafka.apache.org 官方文档 + 公开技术资料通述 | 2026-09-14 |
| nginx-proxy-buffering.md | nginx.org 官方文档（ngx_http_proxy_module） | 2026-09-14 |

- 抓取方式：WebFetch 抓取官方文档页面后人工整理为 Markdown（保留标题层级，供 StructureChunker 提取标题路径）。各文件头部注明来源与「仅用于本地评测测试」。
- HikariCP 连接池资料：GitHub 在本网络不可达（raw.githubusercontent 超时），**未纳入**语料；如需要可后续补充。
- 第二轮修复了标题编号重复（`1.6. 6.`）缺陷；**v2 数据集的锚点按修复后的分块输出生成，语料必须重新入库后才可使用 v2 数据集评测**（详见 `docs/round2/02-实施记录.md` §5）。

## 2. 测试题

| 文件 | datasetType | 题数 | 类别覆盖 |
| --- | --- | --- | --- |
| docs/eval/eval-tuning-v2-array.json | TUNING（调优集） | 6 | DIRECT、TERM_VARIATION、CONFUSABLE、OUT_OF_KB（不可答） |
| docs/eval/eval-test-v2-array.json | TEST（独立测试集） | 10 | DIRECT、TERM_VARIATION、CONFUSABLE、FOLLOW_UP、OUT_OF_KB |

（v1 文件头的类别统计与实际内容有出入，以 v2 文件实际内容为准。）

- 字段与 `contracts/openapi.yaml` 的 EvalDatasetItem / EvidenceRef 对齐；v2 的 `evidence.chunkId` / `evidence.titlePath` 来自真实分块器输出。
- FOLLOW_UP 题（"那磁盘清理完之后……"）检索仅对当前问题向量化，预期表现较差，属已知局限（第二轮未做查询改写，理由见需求文档 §4）。
- 不可答题（OUT_OF_KB）用于验证"资料不足时明确说明、不伪造引用"（QA-6 / EV-5）；第二轮起拒答时引用为空数组。
- 两集严格分离：调优只允许使用调优集，独立测试集不参与任何调参迭代（EV-6）。

## 3. 评测执行前置条件

1. **模型服务**：OpenAI 兼容端点的 Embedding 与 Chat 服务；重排服务可选（DashScope **原生**端点，非 OpenAI 兼容路径，配置见 `README.md` §2）。
2. **中间件**：docker compose 已启动（rag-mysql / rag-es / rag-minio）。
3. 后端 rag-server 启动后：建评测知识库 → 上传 docs/eval-corpus/*.md（4 篇）→ 等入库完成 → `POST /api/v1/eval/datasets` 导入 v2 数据集 → `POST /api/v1/eval/runs` 发起运行 → 前端「效果评测」页查看指标与两次运行对比。
4. 对照 VECTOR 基线：以 `RAG_RETRIEVAL_MODE=VECTOR` 重启后端再跑一次（运行的 configSnapshot 记录 retrievalMode，前端对比页据此做可比性守卫）。

## 4. 中间件部署记录

- `docker compose up -d` 于 2026-09-14 启动：rag-mysql(3306)、rag-es(9200，首启自动安装 analysis-ik)、rag-minio(9000/9001)。
- ES 镜像为 docker.elastic.co 官方 8.14.1（Docker Hub 无此 tag，已修正 docker-compose.yml 与 StorageIT 的镜像引用）。

## 5. 第四轮数据集（answerability-v1）

`eval-answerability-v1-array.json`：72 题 Answerability 专项 TUNING 集（CONFUSABLE/PARTIAL_EVIDENCE/
OUT_OF_KB 合计 44 题），语料与运行留痕见 `docs/round4/02-实施记录.md`。
指标口径升级：混淆矩阵（TP/FP/FN/TN）、False Answer Rate = FP/(FP+TN)、False Refusal Rate = FN/(FN+TP)、
Judge 调用率/降级率/耗时，随 run metrics 的 `answerabilityConfusion` 键透出。
