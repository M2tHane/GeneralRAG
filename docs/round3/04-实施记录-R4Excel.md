# R4-Excel：表格感知分块（Sheet Summary + Row Group）实施记录

日期：2026-09-15
状态：完成（单元测试 + 全量回归 + 真机验证均通过）

## 1. 一句话结论

几千行的 Excel 不再被当作"一篇普通长文档"切块：**Sheet 概览块负责粗定位，Row Group（按 token 预算动态聚合、逐块重复表头与上下文）负责精确召回**，行级定位通过 titlePath 后缀 `数据行 a-b` 免费贯通引用/调试/评测三条链路。解析器、ES mapping、API 契约、前端**零改动**。

## 2. 设计决策（与讨论稿的对应）

| 讨论稿条目 | 本轮落地情况 |
|---|---|
| §1 Sheet Summary（行数/字段/示例） | ✅ 每 sheet 1 块，前 3 行示例；CSV 为无 sheet 名的单表 |
| §2 Row Group + 表头重复 | ✅ 每块注入「数据表：文档名 > Sheet名」上下文行 + 表头 + 分隔行 |
| §3 按 token/字符预算聚合（非固定行数） | ✅ 复用 chunkConfig.maxLength 作为块总预算；行原子不截断 |
| §4 一行一 Document（结构化字段+filter） | ⏸ 有意推迟：等真实 BadCase 归因证明需要范围/筛选查询再做 |
| §8 Row Metadata（rowStart/rowEnd） | ✅ 以 titlePath 后缀「数据行 a-b」承载，零 schema 改动 |
| §10 第一版最小实现 | ✅ 严格按此边界执行；检索链路（RRF/rerank）未动 |

### 关键技术决策

1. **切块器从 parsed.txt 重建而非解析器直传结构**。`IngestionTaskManager.chunk()` 总是从
   `ParsedDocument.fromPersisted(...)` 重建（失败重试续跑的既有约定），SpreadsheetChunker 直接
   消费 Markdown 管道表文本（XlsxParser/CsvParser 的确定性产物），首次入库与重试天然同路径，
   不新增持久化格式。管道表文本的表格信息无损（表头/分隔行/单元格），足以重建逻辑表。
2. **路由按 FileType 而非新增 ChunkStrategy**：`STRUCTURE + (XLSX|CSV)` → SpreadsheetChunker，
   其他组合完全不变。不新增契约枚举值，chunkConfig 与数据集兼容性不受影响。
3. **行号 = sheet 内数据行序号（1 起）**，而非原始 Excel 行号：解析时已剔除空行，原始行号
   不可恢复；序号由文本决定，跨重试确定性不变。
4. **单行超预算原子成块**（允许超上限），不截断不丢弃——截断会丢字段值，丢行会丢记录。

## 3. 改动清单

| 文件 | 改动 |
|---|---|
| `ingestion/chunk/SpreadsheetChunker.java` | **新增**：管道表文本 → SheetTable → Summary/RowGroup 两类块 |
| `ingestion/IngestionTaskManager.java` | `chunk()` 增加 `isSpreadsheet(doc)` 路由（XLSX/CSV + STRUCTURE） |
| `test/.../chunk/SpreadsheetChunkerTest.java` | **新增** 9 个用例（见 §4） |
| `docs/eval/eval-formats-r4-v1-array.json` | 新增数据集（r3 版的 xlsx 锚点重绑到 R4 新分块） |

明确**不改**：XlsxParser/CsvParser（解析产物格式不变）、ChunkDraft/ChunkDoc/EsHit（无新字段）、
ES mapping、Chunk/Citation DTO、contracts/openapi.yaml、前端类型（`rowStart/rowEnd` 由
titlePath 承载后无需契约变更）。

## 4. 验证证据

### 4.1 单元测试（9 用例）

`SpreadsheetChunkerTest`：多 sheet 概览+行组结构、Summary 内容（字段列表/行数/示例不越界）、
行组重复表头+行号 titlePath、按预算切分（150 字符预算 → 数据行 1-2 / 3-3 两块，每块带表头）、
超长行原子性、CSV 无 sheet 名回退文档名、空文本、chunkId 确定性、表格文本解析。

### 4.2 全量回归

```text
mvn test → Tests run: 157, Failures: 0, Errors: 0  BUILD SUCCESS
```

（148 → 157：+9 SpreadsheetChunkerTest；路由变更未破坏任何既有 IT）

### 4.3 真机验证（R4 表格分块验证库 kb=263cc088…，doc=hikari-cp-reference.xlsx）

**chunk 结构**（GET /documents/{id}/chunks，total=6）：

```text
seq0 概览   hikari-cp-reference.xlsx > 参数说明 > 概览     （字段/行数/前3行示例）
seq1 行组   hikari-cp-reference.xlsx > 参数说明 > 数据行 1-8（上下文行+表头+8行数据）
seq2 概览   hikari-cp-reference.xlsx > 常见问题 > 概览
seq3 行组   hikari-cp-reference.xlsx > 常见问题 > 数据行 1-4
seq4 概览   hikari-cp-reference.xlsx > 容量估算 > 概览
seq5 行组   hikari-cp-reference.xlsx > 容量估算 > 数据行 1-3
```

**检索（/debug/retrieval，HYBRID_RERANK，未降级）**：

| 问题 | rank1 块 | vector | bm25 | rerank |
|---|---|---|---|---|
| maxLifetime 默认值是多少（精确标识符） | 参数说明 > 数据行 1-8 | 0.814 | 8.6 | **0.975** |
| 连接生命周期由哪些参数控制（语义） | 参数说明 > 数据行 1-8 | 0.812 | 6.1 | 0.725 |

概念验证成立：概览块 rerank 仅 0.520（Q1），行组块显著胜出——"行组负责精确检索、
概览负责导航"的分工符合设计预期。

**QA SSE（提问 maxLifetime 默认值）**：回答「maxLifetime 的默认值是 **1800000 ms**」，
引用首位 = `参数说明 > 数据行 1-8`（score 0.82），行级定位直接可读。

**评测（新数据集 r4-excel-v1，run 9f9560b0…）**：R4 知识库只入库了 xlsx 一个文档，
10 条中 3 条 xlsx 条目全部命中（idleTimeout rank1、容量估算 rank1、link failure rank2），
xlsx 域内 Hit@1=2/3；2 条 OUT_OF_KB 全部正确拒答（引用空数组，如 Q9「Nginx 主动健康检查」
→「当前资料不足以回答该问题」，citations=0）。nginx/kafka 条目锚点绑定 docx/csv 文档、
该文档不在本库，notFound 属预期（库不完整，非检索缺陷）。

**首轮 eval 全 0 的说明**（如实记录）：先用原 r3 数据集跑了一次，Hit@1=0.0/notFound=8。
原因：数据集锚点（contentHash/chunkId/titlePath）绑定旧切块结构，新切块逐块注入了表头
与上下文行 → 内容变化 → 旧锚点全部失配。这是锚点跨结构变化失效的预期行为（contentHash
设计目标只是"改名/重入库不变"，不是"切块策略变化不变"），不是检索退化——debug 接口
同库同问题 rank1 命中行组块可证。处置：重绑 3 条 xlsx 证据生成 r4-excel-v1 后重跑通过。

## 5. 已知边界

1. **xlsx/csv 与 STRUCTURE 组合才走新分块**；LENGTH_OVERLAP 仍为滑窗（保持既有语义）。
2. 行号是 sheet 内数据行序号，与原始 Excel 行号可能不同（空行剔除后）；titlePath 语义以
   chunk 预览为准。
3. 数值范围/筛选/统计类问题（如"价格<5000 且 32GB"）仍依赖语义检索，未做结构化
   filter——按讨论稿 §6 的演进路线，等真实 BadCase 归因再立项。
4. 讨论稿的"一行一 Document + ES 结构化字段"（§4/§5）未实现，属有意推迟。
5. 评测数据集锚点绑定切块策略：切换切块策略后需重绑证据（r3→r4 已示范做法）。

## 6. 教训

- **重启 dev 服务的环境变量要以 docs/round2/02-实施记录.md 的启动清单为准**（本轮漏了
  CHAT/EMBEDDING_BASE_URL、模型名与 MYSQL_PASSWORD，多试错一轮）；RERANK_MODEL_BASE_URL
  必须是裸域名 `https://dashscope.aliyuncs.com`（客户端自行追加 text-rerank path，传完整
  path 会拼出双重路径 → 400 降级）。
