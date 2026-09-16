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
