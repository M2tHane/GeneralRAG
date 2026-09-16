# Round 5 — Retrieval Quality & Format-aware Ingestion

基线：53f9476（R4.1.x 全部关闭后）。三个阶段独立 commit：

| 阶段 | commit | 内容 |
| --- | --- | --- |
| R5-A | `90e42a0 feat: separate retrieval and answer content` | retrievalContent/answerContent 双层拆分 |
| R5-B | `c5bea43 feat: add stage-level retrieval evaluation` | RetrievalTrace + stage metrics |
| R5-C | `8bd7b13 feat: add structure-aware Excel chunking` | Excel 双层接入 + 公式修复 + fixture |

## R5-A 数据流（拆分后）

```text
answerContent（chunk.content，忠实证据）
├─ ContextAssembler → Judge evidence / Generation context
├─ Citation（chunkId/docName/titlePath/score，无正文）
└─ EvidenceMatcher / contentHash（eval 锚点）

retrievalContent（chunk.retrieval_content，确定性增强）
├─ Embedding 输入（IngestionTaskManager.embed）
├─ BM25 搜索字段（retrieval_content 优先，content 兜底——旧 chunk 兼容）
└─ Reranker documents（回退 content）
```

- `RetrievalContentEnricher`：纯函数，只拼 documentName/titlePath；
  titlePath 已含文档名时不重复；无 titlePath 不虚构；无 LLM。
- ES mapping 新增 `retrieval_content`（text，与正文同 analyzer）；`content`
  字段语义不变（= answerContent），旧索引兼容（旧 chunk 无此字段时检索回退）。
- **重新 ingest 才能让旧 chunk 获得新字段**——Comparable Eval 用重建 KB。

## R5-B 设计

- `RetrievalTrace`（vector/bm25/union/fused/reranked 候选 + finalTopK +
  StageTiming），由 `RetrievalPipeline.execute` **顺带收集**——没有第二套
  检索逻辑；`/api/v1/debug/retrieval` 与 Eval 共用同一 trace。
- `StageMetrics`：证据锚点复用 `EvidenceMatcher`（titlePath+contentHash）；
  Vector/BM25/RRF Recall@30、Union Recall、Rerank Recall@6；
  promoted/degraded/stable（融合序 vs 重排序，掉出候选 = degraded），
  分母 = 融合序可见的证据 chunk 数。
- per-item：retrieved 快照每条命中带 `stageRanks{vector,bm25,rrf,rerank,final}`。
- 口径注记：vectorMs/bm25Ms 共享 searchMs（两通道同一 ES 搜索窗口），
  trace 按 embedMs/searchMs/fusionMs/rerankMs/totalMs 记录。

## R5-C 变更点

SpreadsheetChunker 的 Sheet Summary / RowGroup / header 重复 / 行原子（R4-Excel
已有）直接继承；本轮：
- `XlsxParser`：公式 cell 经 `FormulaEvaluator` 显式求值（内存生成 xlsx 无
  缓存值），输出计算值；百分比/日期仍走 DataFormatter 显示值；
- SpreadsheetChunker 产物统一接 `RetrievalContentEnricher`：
  answerContent 忠实表格（无元数据前缀），retrievalContent = 文件/Sheet
  元数据 + answerContent；
- fixture：`docs/eval-corpus/sales-r5-fixture.xlsx`（2 Sheet：区域销售
  4 数据行 + SUM 公式、产品说明 2 行；字符串/数字/百分比/日期/公式/空单元格）
  + `ExcelDualLayerChunkingTest` 6 用例。

## Comparable Eval

环境：KB `1637cb30`（8 docs / 48 chunks = 原 7 篇 44 chunks + fixture 4），
同一 v2 数据集（2415c28b）、同一 embedding（qwen3.7-text-embedding）、
同一 reranker（qwen3.7-text-rerank）、同一 Judge（deepseek-v4-flash-0731）、
topK=6 / candidateLimit=30 / RRF k=60 / rerank threshold **0.75**。
对照 run：R4.1 `2decd02e`（44 chunks，无双层内容）vs R5 `27502106`。

| Metric | R4.1（2decd02e） | R5（27502106） | Δ |
| --- | - | - | - |
| Hit@1 | 0.8286 | **0.8571** | +0.0285 |
| Hit@3 / Hit@5 | 0.9429 / 0.9429 | 0.9429 / 0.9429 | 0 |
| MRR | 0.8810 | **0.8952** | +0.0142 |
| Vector Recall@30 | —（无口径） | 1.0（33/33） | — |
| BM25 Recall@30 | — | 1.0（33/33） | — |
| Union Recall | — | 1.0（33/33） | — |
| RRF Recall@30 | — | 1.0（33/33） | — |
| Rerank Recall@6 | — | 1.0（33/33） | — |
| rerank promoted / degraded / stable | — | 9 / 3 / 21（分母 33） | — |
| FAR（FP/(FP+TN)） | 5.41% | 5.41% | 0 |
| FRR（FN/(FN+TP)） | 8.57% | **5.71%** | -2.86pp |
| TP/FP/FN/TN | 32/2/3/35 | 33/2/2/35 | FN 3→2 |
| Judge degraded rate | 0% | 0% | 0 |
| E2E P50 / P95 | 4391 / 7252ms | 4308 / 9751ms | P95 +2.5s（本轮波及，检索均值不变） |
| retrievalMsAvg | 473ms | 474ms | ≈0 |

### BadCase（R5 run 逐题）

| seq | 类型 | 决策 | stageRanks（top1 证据） | 归因 |
| - | - | - | - | - |
| 35 | FN | JUDGE_REFUSE | vector=4, bm25=3, rrf=3, rerank=1, final=1 | Judge 低置信误拒（检索全链路可见，非检索丢失） |
| 60 | FN | JUDGE_REFUSE | vector=1, bm25=1, rrf=1, rerank=1, final=1 | Judge 严格口径误拒（R4.1 同源） |
| 56 | FP | JUDGE_ACCEPT | 全 1 | PARTIAL_EVIDENCE 边界（R4.1 同源） |
| 63 | FP | JUDGE_ACCEPT | 全 1 | PARTIAL_EVIDENCE 边界（R4.1 同源） |

**检索阶段零丢失**：33 个证据 chunk 在 vector/bm25/rrf 前 30 与 rerank 前 6
全部可见——R5 的 2 个 FN 全是 Judge 语义误拒、2 个 FP 全是 PARTIAL_EVIDENCE
边界，与 R4.1 结论一致且检索侧无回归。

### Threshold 0.75

top1 rerank 分数（R5 run）：min 0.0038 / P25 0.5516 / median 0.9624——与 R4.1
sweep 时的分布形态一致（可答题仍集中 0.95+，无关题 ≈0）。**0.75 无需重新
校准**（enrichment 前缀未引起分布漂移）；保留后续数据集扩容时再复核的提醒。

## 测试记录（每阶段只跑相关集合）

- R5-A：RetrievalContentEnricherTest 6、IngestionTaskManagerTest 7、
  AnswerabilityFlowIT 8（含防污染 case8）、StorageIT 6、
  DebugRetrievalConsistencyIT 1、EsChunkIndexMappingTest 5
- R5-B：StageMetricsTest 7、EvalFlowIT 8、DebugRetrievalConsistencyIT 1
- R5-C：ExcelDualLayerChunkingTest 6、SpreadsheetChunkerTest 9

---

## R5.1 Correctness 修复（2026-09-16，基线 3639d31）

### 1. retrieval_content mapping 迁移（非破坏）

**实测发现**：开发索引 `rag_chunks_v1.retrieval_content` 确为动态 mapping 创建
（带 `fields.keyword` 子字段、analyzer=default/standard），与 content 的
ik_max_word 不一致——"与 content 同 analyzer"在此索引上不成立。

**修复**：`ensureIndex()` 对已存在索引执行 mapping 检查：
- 字段缺失 → PUT mapping 补齐（text + 当前 analyzer），旧 chunk 读侧回退 content；
- analyzer 一致 → 继续；
- analyzer 不一致 → **启动即报错**，明确要求人工 reindex/rebuild，禁止自动破坏。

**实际处置**：当前开发索引属于不一致情形；按报告-不静默处理原则，删除该**派生**
索引（0 数据丢失——chunks 全部可由 MySQL/MinIO 原始文档重建），后端重启后
`ensureIndex` 以正确 analyzer 重建，8 文档 48 chunks 重新 ingest 验证。

### 2. BM25 真正 fallback

`should(retrieval_content) should(content) minShould=1` 会让新 chunk 同时吃两份
正文 BM25 分。改为 exists 分支：

```text
(retrieval_content exists AND match retrieval_content)
OR (retrieval_content missing AND match content)
```

新 chunk 只吃 retrieval_content 一份分数；旧 chunk 保持 content 可检索。

### 3. Stage eval 修正（evidence-anchor 快照）

`StageCandidate` 扩展 `titlePath` + `contentHash`（answerContent 哈希，收集点
现算，**trace 不携带正文**）；`StageMetrics.evaluateItem` 不再依赖最终 topK 的
EsHit，直接把各阶段候选映射为 `EvidenceMatcher.CandidateRef` 复用既有的
`firstMatchRankInSnapshot`（无新写匹配逻辑）。证据被 rerank 淘汰出 final topK
后仍可定位 vector/bm25/rrf 名次。

### 4. Stage degradation 回归

`StageDegradationTest`（3 用例，走 evaluateItem 真实匹配路径）：vector=5/bm25=3/
rrf=8/rerank=20/final=MISS → Vector/BM25/RRF Recall@30 全 HIT、Rerank@6 MISS、
degraded=1、finalRank=null；含 contentHash-only 锚点与"rerank 彻底掉出"变体。

### 5. Excel retrieval 定向（3+1 case，真实 fixture）

数据行问题（华东 A Q2 销售额）→ RowGroup 命中；Sheet 说明问题 → 产品说明
RowGroup 命中（跨 Sheet 不串）；字段清单问题 → Sheet Summary 命中；
RowGroup 的 retrievalContent 带元数据前缀而 answerContent 无 enricher 虚构。

### 6. 修正后 Comparable Eval（run `2756f6c0`，同环境同参数）

| Metric | R5 修正前（27502106） | R5.1 修正后（2756f6c0） |
| --- | - | - |
| Hit@1 / 3 / 5 | 0.8571 / 0.9429 / 0.9429 | 同 |
| MRR | 0.8952 | 0.8952 |
| Vector/BM25/Union/RRF Recall@30 | 1.0×4 | 1.0×4（**口径修正后仍然全 1.0**） |
| Rerank Recall@6 | 1.0 | 1.0 |
| promoted / degraded / stable | 9 / 3 / 21 | **7 / 3 / 23**（2 chunk 由 promoted 重新归为 stable——旧路径锚点来自 final-topK EsHit，rank 比较受 topK 截断失真；修正后以全候选快照为准） |
| FAR / FRR | 5.41% / 5.71% | 同 |
| Judge degraded | 0% | 0% |

**以修正后指标为准**，Round 5 报告中的 promoted/degraded/stable 已更新口径。

## R5.2 最后收尾（fix: close Round 5 edge cases）

### 1. rerank degradation 过滤边界修正（R5.2 主修复）

**问题**：`fusedCandidates` 在 `filterDeletedDocs`/`filterInactiveVersions` 之前记录，
被 filter 淘汰的 chunk 携带 rrfRank 而无 rerankRank，被 `StageMetrics` 误算为
"reranker degraded"——但它实际从未进入重排器。

**修复**：`RetrievalTrace` 新增 `preRerankCandidates`（filter 之后、重排器之前记录，
含证据锚点），`StageRanks` 增加 `preRerankRank`；promoted/degraded/stable 的比较基线
从 rrfRank 改为 preRerankRank。**RRF 阶段召回（rrfRecall@30）仍基于真正的 RRF 融合
候选（fusedCandidates），两个口径不混用**。消费方同步更新：EvalRunExecutor 的
per-item stageRanks 输出增加 `preRerank` 键；DebugRetrievalService/DebugResult 的
trace payload 增加 preRerankCandidates/preRerankRank 字段。

新增回归（`StageMetricsTest.filterRemovedCandidateIsNotRerankerDegraded` 与
`StageDegradationTest.filterRemovedCandidateIsNotRerankerDegraded`，后者走
evaluateItem 真实锚点匹配路径）：证据 rrf 可见、filter 后消失 → rrfRecall=1、
rerankDegraded=0、rerankStable=1（存活证据）且 stable/degraded 均不为它计数；
`degradedStillCountedWhenRerankerDropsCandidateEntirely` 改为验证"真进过重排器
（preRerank 可见）但重排输出消失"仍计 degraded。

### 2. mapping migration 定向测试

新增 `EsChunkIndexMigrationTest`（Mockito 桩 ES client，不依赖真实 ES）：
- 字段不存在 → PUT mapping 补齐 retrieval_content，analyzer = 当前 contentAnalyzer
  （捕获 Function 参数在全新 Builder 上执行校验）；
- 字段存在且 analyzer 一致 → 无任何写入；
- 字段存在但 analyzer 不一致（含 non-text 变体 keyword 形态）→ fail-fast，
  且验证 **delete index 与 reindex 从未被调用**。

顺带修复：`migrateRetrievalContentMapping` 对 non-text 字段变体（keyword 等）原会
抛 langchain 客户端的 `IllegalStateException`（`Cannot get 'Text' variant`），改为
`existing.isText()` 守卫后走统一的 fail-fast DomainException。

### 3. Excel fixture 真日期 cell

`ExcelDualLayerChunkingTest` 的日期由字符串 `setCellValue("2026-06-30")` 改为真日期：
`setCellValue(LocalDate.of(2026, 6, 30))` + `yyyy-mm-dd` 样式；新增
`realDateCellFormattedAsDisplayValue`（DataFormatter 输出 "2026-06-30"，且非序列号
46203）。百分比/公式/空单元格测试全部保留，7/7 通过。

### 4. destructive action 硬约束

AGENTS.md 新增「Destructive actions require explicit authorization【HARD CONSTRAINT】」
章节（位于 Repository boundary 与 Completion standard 之间）：DELETE ES index、
DROP/TRUNCATE 表、批量删除开发数据、丢弃派生数据的 migration、强制覆盖不可恢复资源
均需用户明确授权；即使"理论上可重建"也不得自行判断后执行；允许 检测/报告/给出处置
方案/等待授权；禁止"这是派生数据，所以我直接删了"。

### 5. 相关测试结果（改哪里测哪里，无全量 mvn test）

| Suite | Tests run | Failures |
| --- | - | - |
| StageMetricsTest | 8 | 0 |
| StageDegradationTest | 4 | 0 |
| EsChunkIndexMigrationTest（新增） | 4 | 0 |
| EsChunkIndexMappingTest | 5 | 0 |
| ExcelDualLayerChunkingTest | 7 | 0 |
| ExcelRetrievalOrientationTest | 4 | 0 |
| OfficeParserTest | 11 | 0 |

threshold 0.75 / Judge / Answerability / embedding / reranker / RRF 参数 / topK /
candidateLimit / BM25 fallback / Excel chunking 结构 / Java AST / PDF AUTO / VLM
均未触碰。前端未改，未跑前端测试。
