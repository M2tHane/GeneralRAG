# GeneralRAG 评测体系（Evaluation）

> Current-State 文档：回答"GeneralRAG 如何知道自己改对了还是改坏了"。
> 当前指标的唯一来源是 [eval/FINAL-BASELINE.md](eval/FINAL-BASELINE.md)；本文只定义架构与口径，不复述数字。
> 实施细节见 `com.rag.eval`（`EvalRunExecutor` / `StageMetrics` / `EvidenceMatcher`）。

## 1. Eval 架构

```text
Dataset（版本化，JSON/CSV 导入）
   → Eval Run（异步执行，逐题串行）
      → RetrievalPipeline（生产路径原样复用）
      → QA / Answerability Judge（与线上同一代码路径）
      → RetrievalTrace（分阶段候选快照，执行路径顺带收集）
   → 分块级锚点判定 + Stage Metrics + 混淆矩阵
   → run_item 持久化（逐题 retrieved 快照 / Judge 决策 / 人工复核）
```

**Eval 不维护第二套检索管线。** 问答、调试、评测共用唯一一条 `RetrievalPipeline`
代码路径——第一轮的调试服务曾逐行复刻检索语义，第二轮即收敛为薄封装；
这条原则后来延伸到判定层（AnswerabilityPolicy 问答/调试/评测共用）。
Stage Trace 的候选与位次全部来自生产执行路径顺带收集（`RetrievalTrace`），不是重放计算。

## 2. 数据集与证据锚点

- 数据集分 TUNING（调优）与 TEST（独立测试）两类，严格分离：调参只用 TUNING，TEST 不参与迭代；
- 每次导入产生新版本（版本可比性由 `configSnapshot` 守卫：检索模式/融合参数/分块配置不一致即不可比）；
- 证据锚点四件套：`chunkId` / `titlePath` / `docName` / `contentHash`（answerContent 哈希）。
  命中判定按锚点精确匹配，**不回退 docName**——第一轮 docName 锚定退化为文档级判定、
  Hit@K=1.00 假满分的教训直接塑造了现在的口径；未锚定的证据如实判 miss；
- negative 题（不可答）无 evidence，只参与拒答指标，不进 Hit@K 分母；
- FOLLOW_UP 题带 `history` 字段（V5 迁移），真实测试追问语义——history 只喂 prompt/judge 的
  指代解析，不喂证据，检索仍只用当前问题。

## 3. 执行与计数口径

```text
attemptedCount ≥ executedCount ≥ evaluatedCount
```

| 计数 | 含义 |
| --- | --- |
| attempted | 进入执行循环的题数 |
| executed | 成功执行（含 Judge degraded——降级是生产行为，不算执行失败） |
| evaluated | 进入混淆矩阵与 Hit@K 口径的题数 |

- **执行失败**（模型端点不可达等）：记 reviewNote"执行失败（不计入 Hit@K 分母）"，hit=NULL，不计入 evaluated；
- **Judge degraded**（TIMEOUT/OVERLOADED/MODEL_ERROR/INVALID_RESPONSE）按 R4.1.1 口径
  **排除出混淆矩阵与 refusalAccuracy/categoryStats**——系统故障不是决策，不能把
  fail-open 放行计入"正确拒绝"或把故障题算成命中失败；degraded 计入 executed（成本口径），
  并在 `answerabilityConfusion.degraded` 按类型单独透出；
- **不变式**：`evaluated == TP + FP + FN + TN`，任何指标引用前先核对。

## 4. 检索指标（Retrieval Metrics）

| 指标 | 口径 | 用途 |
| --- | --- | --- |
| Hit@1 / @3 / @5 | ≥1 个证据 chunk 出现在 final topK 前 K 名的题数比例（分母 = answerable） | 用户可感知的"第一条就命中"程度 |
| MRR | Σ(1/首个证据名次) / answerable | 对排名位置敏感的整体质量 |
| Recall@5（evidenceRecall，micro） | 覆盖的证据 chunk 数 / 应命中的证据 chunk 总数 | 证据完整性 |
| Vector / BM25 / RRF Recall@30（stage） | 各阶段前 30 名内命中证据的题数比例 | 定位召回瓶颈在哪一阶段 |
| Rerank Recall@6（stage） | 重排后 topK（=6）内命中证据的比例 | 重排是否丢证据 |
| Reranker Effect（promoted/stable/degraded） | preRerank 序与重排序中证据 chunk 名次比较（分母 = preRerank 可见证据块；被过滤淘汰的不算 degraded） | 重排真实影响的方向与幅度 |

排名分布（rank1/rank2/…/notFound）与延迟分位（P50/P95/max，retrieval/generation 拆分）随 run 指标透出。

## 5. Answerability / Judge 指标

混淆矩阵（分母 = evaluated，degraded 不入）：

| | 实际应答 | 实际应拒 |
| --- | --- | --- |
| **系统放行** | TP | **FP** |
| **系统拒绝** | **FN** | TN |

| 指标 | 公式 | 含义 |
| --- | --- | --- |
| Judge FAR（False Answer Rate） | FP/(FP+TN) | **本应拒答却放行**——企业知识库的核心风险指标（错误答案比不回答危害大） |
| Judge FRR（False Refusal Rate） | FN/(FN+TP) | 应答却被拒——可用性损失 |
| Refusal Accuracy | (TP+TN)/evaluated | 拒答判定的整体正确率 |
| judgeInvocationRate | judgeInvoked/executedCount | Judge 成本口径（低分直拒不计入） |
| judgeDegradedRate | judgeDegraded/judgeInvoked | Judge 自身故障率（按类型透出） |

**命名纪律：Judge 指标 ≠ End-to-End QA Success。** Judge 判定"证据充分"而生成侧
软拒答（Final Baseline seq54）是真实观察；引用 Judge 指标时不得混称"问答正确率"。
逐题持久化 `refused` + decision type/confidence/reason/degraded（V4 迁移），
保证任何 FP/FN 可复现、可归因（检索错？排序错？证据缺？Judge 错？生成错？）。

## 6. Evidence 指标

| 指标 | 口径 | 用途 |
| --- | --- | --- |
| evidenceCoverageAvg（macro） | 逐题覆盖率（covered/该题证据数）的算术平均 | 单题视角的完整度 |
| evidenceRecall（micro） | Σcovered / Σtotal | 整体证据命中 |
| fullEvidenceCoverageRate | 全部证据都被命中的题数比例 | 严格口径：少一块即不达标 |

**Multi-chunk 为什么不能只看 Hit@1**：MULTI_CHUNK 题的证据分散在多个 chunk，
Hit@1=1.0 只说明"至少命中一块"，回答可能仍缺关键参数。fullCoverage 才反映
"证据凑齐了没有"——Final Baseline 中 MULTI_CHUNK 的 Hit@3/5=1.0 而 fullCoverage=0.875
即此差异的真实体现（数字见 FINAL-BASELINE）。

## 7. Breakdown 维度

- **evidenceMode**（SINGLE_CHUNK / MULTI_CHUNK / FOLLOW_UP）：12 槽位聚合
  （count / hit@1/3/5 / MRR / 覆盖率 / fullCoverage / micro / FN / refused），
  FOLLOW_UP 单独可观测（query rewrite 的验证维度）；
- **failureMode**（OUT_OF_KB / PARTIAL_EVIDENCE / MISSING_CONDITION / ENTITY_MISMATCH /
  SCOPE_MISMATCH / NUMERIC_MISMATCH / VERSION_CONFLICT / UNSUPPORTED_INFERENCE）：
  negative 题按失败类型拆 FP/FAR——**空类别如实缺省，不硬写 0**；
- **category**（DIRECT / CONFUSABLE / OUT_OF_KB / FOLLOW_UP / TERM_VARIATION）随混淆矩阵透出。

## 8. BadCase 闭环

run_item 支持人工复核（reviewTag/reviewNote）；每轮改善的证据链是：

```text
指标变化 → 逐题审计（FP/FN/degraded 全量人工过）→ badcase 归因
   → {KNOWN_LIMITATION, MODEL_VARIANCE, FIXED_BY_Rn, CORRECT_BEHAVIOR}
   → 有因果证据才做下一轮改动
```

历史教训（完整版见 [EVOLUTION.md](EVOLUTION.md) 与各轮记录）：先修指标（docName 假满分）、
先跑 baseline 再造机制（R4 门控形态来自数据）、换模型必须重校准阈值（0.65→0.75）、
系统故障不是决策（degraded 剔除）。

## 9. 当前基线

唯一当前指标来源：[eval/FINAL-BASELINE.md](eval/FINAL-BASELINE.md)
（commit `af16325`、dataset eval-v3-hard v2 80 题、runId `0899d287`、80/80/78）。
历史 run 比较必须遵守其归因守卫（数据集版本更换不可直接比；FAR/FRR 变化部分属 Judge variance）。
