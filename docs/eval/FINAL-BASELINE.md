# GeneralRAG Final Evaluation Baseline

> Release Candidate 的 canonical evaluation baseline：Feature Freeze 后最终代码上的一次
> 环境健康、口径明确、可复盘的 Hard Eval。**MEASURE, DON'T TUNE** —— 本 run 未调整任何
> prompt / threshold / 检索参数 / 数据集；指标反映当前冻结系统的真实表现。

## Identity

| 字段 | 值 |
| --- | --- |
| Commit | `af16325`（main，working tree clean；生产代码相对 R6-D.1 零改动） |
| Dataset | `eval-v3-hard` v2（`docs/eval/eval-v3-hard-v2-array.json`），datasetType=TEST |
| Dataset ID | `2715c06d-5974-4306-ae7b-74bc6f2e3cb4` |
| Dataset Version ID | `5694392f-33fb-4d42-b490-b6ae0772b6db` |
| Question Count | 80（positive=61 / negative=19；failureMode：OUT_OF_KB 8、PARTIAL_EVIDENCE 4、MISSING_CONDITION 3、ENTITY_MISMATCH 3、UNSUPPORTED_INFERENCE 1；evidenceMode：SINGLE_CHUNK 49、MULTI_CHUNK 8、FOLLOW_UP 4） |
| KB | `eb0917bc-86c8-4712-8cc4-6b62d40bde9a`「R5双层内容验证库」 |
| KB 文档 | 8 篇（`docs/eval-corpus/`：nginx-proxy-buffering.md、nginx-proxy-guide.docx、elasticsearch-cluster-health.md、kafka-consumer-groups.md、kafka-error-codes.csv、redis-persistence.md、hikari-cp-reference.xlsx、sales-r5-fixture.xlsx），全部 COMPLETED、versionNo=1 且 isActive=true，chunk 数 4/3/6/6/7/7/6/9；无 Demo / Stage2 smoke / 重复版本混入 |
| Run ID | **`0899d287-3e17-4e4e-9c63-b77ed0c02212`**（COMPLETED，2026-09-17 22:12:45 → 22:19:55） |
| Date | 2026-09-17 |

## Model Identity（.env 实际配置 + 运行时探活）

| 能力 | 模型 | Provider / Base URL |
| --- | --- | --- |
| Embedding | `qwen3.7-text-embedding`（维度 1024，startup-check=true 探活通过） | DashScope `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| Generation / Judge / Query Rewrite | `deepseek-v4-flash-0731`（同一 ChatModel 端点；Judge 实例 temperature=0、maxRetries=0、timeout=15s；Rewrite 实例 temperature=0、timeout=5s） | DashScope `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| Reranker | `qwen3.7-text-rerank`（探活通过） | DashScope 原生端点 `https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank` |

## Retrieval Configuration（run configSnapshot，未修改）

`mode=HYBRID_RERANK`，`topK=6`，`minScore=0.30`，`rrfK=60`，`candidateLimit=30`（Vector/BM25 各通道上限），
`refusal.rerankThreshold=0.75`，`refusal.cosineThreshold=0.30`，
`answerability.enabled=true`，`failClosed=false`，`judgeTimeoutSeconds=15`（bulkhead 4 / wait 500ms，默认值），
`queryRewrite.enabled=true`，`rewriteTimeoutSeconds=5`。

## Execution Health

| 指标 | 值 | 说明 |
| --- | ---: | --- |
| attemptedCount | 80 | 进入执行循环 |
| executedCount | 80 | 全部成功执行，无执行失败 |
| evaluatedCount | 78 | 2 题 Judge degraded（TIMEOUT）按 R4.1.1 口径不进混淆矩阵 |
| executionFailed | 0 | — |
| judgeDegraded | 2（TIMEOUT×2，seq7 / seq34） | fail-open 放行并标注，属第 12 条 production behavior |
| judgeInvocationRate | 0.8375 | 分母 = executedCount（成本口径） |
| judgeDegradedRate | 0.0299 | 分母 = judgeInvoked |
| Invalid infrastructure runs | 无 | 本次为环境确认健康后预先声明的唯一 official run |

混淆矩阵不变式已验证：**evaluatedCount(78) == TP+FP+FN+TN (60+1+1+16)**，全部计数取自 run report 原始字段。

## Retrieval Metrics

| Metric | Value |
| --- | ---: |
| Hit@1 | 0.9508 |
| Hit@3 | 1.0 |
| Hit@5 | 1.0 |
| MRR | 0.9754 |
| Recall@5（分块级 evidenceRecall） | 0.9855 |
| Vector Recall@30（stageRetrieval.vectorRecall30） | 1.0 |
| BM25 Recall@30（stageRetrieval.bm25Recall30） | 1.0 |
| Union Recall（stageRetrieval.unionRecall） | 1.0 |
| RRF Recall@30（stageRetrieval.rrfRecall30） | 1.0 |
| Rerank Recall@6（stageRetrieval.rerankRecall6） | 1.0 |

排名分布：rank1=58、rank2=3、notFound=0。延迟：avg 5362ms（retrieval 607ms / generation 2207ms）、P50 4480ms、P95 12344ms、max 28561ms（degraded 题含 Judge 15s 超时）。

## Judge / Answerability Metrics

Judge 指标 ≠ End-to-End QA Success（seq54 即为例证：Judge ACCEPT 但生成侧软拒答）。

| Metric | Value |
| --- | ---: |
| TP | 60 |
| FP | 1 |
| FN | 1 |
| TN | 16 |
| Judge FAR（FP/(FP+TN)） | 0.0588 |
| Judge FRR（FN/(FN+TP)） | 0.0164 |
| Refusal Accuracy | 0.9744 |
| Judge Degraded Rate | 0.0299（TIMEOUT×2） |

categoryBreakdown：DIRECT 56（refused 1 / misjudged 1）、CONFUSABLE 11（refused 10 / misjudged 1）、OUT_OF_KB 6（refused 6 / misjudged 0）、FOLLOW_UP 4（refused 0 / misjudged 0）、TERM_VARIATION 1（refused 0 / misjudged 0）。注：CONFUSABLE 与 OUT_OF_KB 的 category 计数覆盖数据集 19 个负例中参与判定的 17 个（另 2 个负例为 degraded）。

## Evidence Metrics

| Metric | Value |
| --- | ---: |
| evidenceCoverageAvg（macro，逐题均值） | 0.9918 |
| evidenceRecall / Recall@5（micro） | 0.9855 |
| fullEvidenceCoverageRate | 0.9836 |

## Evidence Mode Breakdown

### SINGLE_CHUNK（49 题）
Hit@1 0.9592 · Hit@3/5 1.0 · MRR 0.9796 · evidenceCoverageAvg 1.0 · evidenceRecall 1.0 · fullCoverage 1.0 · FRR 0.0204（1 题 FN）

### MULTI_CHUNK（8 题）
Hit@1 0.875 · Hit@3/5 1.0 · MRR 0.9375 · evidenceCoverageAvg 0.9375 · evidenceRecall 0.9375 · fullCoverage 0.875 · FRR 0

### FOLLOW_UP（4 题）
Hit@1 1.0 · Hit@3/5 1.0 · MRR 1.0 · evidenceCoverageAvg 1.0 · evidenceRecall 1.0 · fullCoverage 1.0 · FRR 0

## Failure Mode Breakdown（negative cases）

| Failure Mode | Count | FP | FAR |
| --- | ---: | ---: | ---: |
| OUT_OF_KB | 8 | 0 | 0.0 |
| ENTITY_MISMATCH | 3 | 0 | 0.0 |
| PARTIAL_EVIDENCE | 3 | 1 | 0.3333 |
| MISSING_CONDITION | 3 | 0 | 0.0 |
| UNSUPPORTED_INFERENCE | 0 | — | 无样本，不硬写 0 |

## Query Rewrite

invoked=4 · rewritten=4 · unchanged=0 · fallback=0 · latencyAvg 1921.75ms / P95 2127ms。
FOLLOW_UP 4/4 全部触发且生效，改写查询正确补回实体（如 `那 minimumIdle 呢？` → `HikariCP 的 minimumIdle 默认是多少？`、`前者是多少？` → `idleTimeout 的默认值是多少？`），4 题 hit 全为 true。

## Reranker Effect（stageRetrieval）

promoted=17 · stable=47 · degraded=5（分母 = preRerank 序可见的证据 chunk 数）。5 个 degraded chunk 均未导致任何题的证据跌出 final topK（Rerank Recall@6 = 1.0）。本记录仅描述事实，不据此调参。

## Representative BadCases

### Case 1 — PARTIAL_EVIDENCE FP（本轮唯一混淆矩阵 FP）
- Question: `nginx 开源版主动健康检查的间隔默认是多少秒？`（seq29，PARTIAL_EVIDENCE）
- Expected: 拒答（开源版无主动健康检查，那是商业版功能；文档已明确该事实）
- Retrieved evidence: nginx-proxy-guide.docx `1.4. 健康检查与故障转移`（rank1, 0.897）
- Judge: JUDGE_ACCEPT（conf 0.95），reason 恰好复述了"开源版没有主动健康检查"
- Final behavior: 回答文本实际是安全的（"当前文档证据未提及开源版主动健康检查的间隔默认值……无法回答"），但系统侧 refused=false → 计 FP
- Root cause: Judge 判定与生成行为语义错位——Judge 把"证据明确说明功能不存在"当作可回答，生成侧却按证据不足作答。判定正确性与行为口径的边界 case。
- Status: KNOWN_LIMITATION（Judge 决策文本正确、结构化判定摇摆的边界样本）

### Case 2 — Judge boundary FN
- Question: `HikariCP 建议 production 环境把 maximumPoolSize 设为 40 吗？`（seq61，SINGLE_CHUNK）
- Expected: 回答（xlsx 证据含 production 建议值 40）
- Retrieved evidence: hikari-cp-reference.xlsx 参数表（hit=true，证据在 topK 内）
- Judge: JUDGE_REFUSE（conf 0.95）："证据只给出各场景建议……需要额外推断"
- Final behavior: "当前资料不足以回答该问题。"，citations=[]
- Root cause: Judge 对表格参数建议类证据的"是否需要额外推断"判断偏保守；同题在历史 run 曾被 ACCEPT，属 Judge 边界非确定性。
- Status: MODEL_VARIANCE

### Case 3 — Generation soft-refusal（Judge ACCEPT 但生成侧拒答）
- Question: `Redis RDB 快照为什么大数据集下重启比 AOF 快？`（seq54，MULTI_CHUNK）
- Expected: 回答原因（文档含 RDB 优缺点/AOF 机制分块）
- Retrieved evidence: redis-persistence.md 6 个 chunk 全部命中（evidenceRank=1）
- Judge: JUDGE_ACCEPT（conf 0.97）
- Final behavior: 只复述"确实更快"，不答"为什么"，以"文档未说明原因"收尾——结构上算回答（refused=false，TP），语义上部分拒答
- Root cause: 生成 prompt 对"机制类问题"的语义对齐限制（R6-B 已收敛过一轮）；与 Judge 判定无关。
- Status: KNOWN_LIMITATION

### Case 4 — Judge degraded fail-open（系统故障路径的真实行为）
- Question: `nginx 的 proxy_busy_buffers_size 默认是多少字节？`（seq7，负例）/ `华东地区总销售额占全公司一半以上吗？`（seq34，负例）
- Expected: 拒答
- Judge: JUDGE_DEGRADED（TIMEOUT×2，15s 超时）→ fail-open 放行
- Final behavior: 两题生成侧都做了正确的证据边界表述（seq7 给出"取决于内存页、无唯一数值"、seq34 指出"证据未直接提供总数、不允许推算"），未产生事实性错误回答；不计入混淆矩阵
- Root cause: Judge 模型端点单次超时（P95 波动）；bulkhead/timeout 语义按设计工作。
- Status: MODEL_VARIANCE（已知降级路径）

### Case 5 — Follow-up 修复成功样本（R6-C 因果验证）
- Question: `前者是多少？`（seq65，FOLLOW_UP，history 指代 idleTimeout/cachePrepStmts）
- Expected: 命中并回答 idleTimeout 默认值
- Retrieved evidence: hikari-cp-reference.xlsx（hit=true）
- Query Rewrite: `前者是多少？` → `idleTimeout 的默认值是多少？`（rewritten=true）
- Final behavior: 正确回答 + 正确引用
- Root cause / 归因: R6-C history-aware rewrite 使 FOLLOW_UP 组 Hit@1 从 R6-A 时代的 0.5 升至 1.0（检索路径有明确因果变化）。
- Status: FIXED_BY_R6-C

## Historical Comparison

| 指标 | R6-A v1 run（b2bcd4c2, 01:12） | R6-era v2 run（828a481f, 12:34） | **Official（0899d287, 22:12）** |
| --- | ---: | ---: | ---: |
| Hit@1 / Hit@5 | 0.9211 / 0.9474 | 0.9180 / 0.9672 | **0.9508 / 1.0** |
| Recall@5 | 0.95 | 0.9565 | **0.9855** |
| MRR | 0.9342 | 0.9426 | **0.9754** |
| Judge FAR | 0.5476 | 0.1053 | **0.0588** |
| Judge FRR | 0.0789 | 0.0820 | **0.0164** |
| FOLLOW_UP Hit@1 | 0.5 | 0.5 | **1.0** |

归因说明（避免错误归因）：
- **R6-A v1 → v2 指标跨越主要来自数据集版本更换**（v1→v2 标签修订），两列不可直接比。
- **R6-era → Official 的 FOLLOW_UP 0.5→1.0**：检索路径有明确因果变化（R6-C rewrite + c91f753 effective query 一致性修复），可归因于 R6-C/C.1。
- **FAR 0.1053→0.0588 / FRR 0.082→0.0164**：retrieval path 未变（stage 指标同源），主要是 R6-B Judge 硬化 + Judge 边界样本（seq61 等）本轮未摇摆；部分归 Judge variance，不宣称 retrieval 改进。
- 16:40 的 run `5d3626bd`（exec=78，seq79/80 模型端点抖动中断）为 **Invalid Infrastructure Run**，不参与比较。

## Known Variance（本轮真实观察）

- Judge 边界非确定性：seq61 同题历史 ACCEPT / 本轮 REFUSE；seq29 Judge reason 与结构化判定语义错位。
- Judge TIMEOUT 2/67（15s 超时，fail-open 放行且生成侧未产生事实错误）。
- Generation soft-refusal：seq54 机制类问题只复述结论不答原因（R6-B 后残留）。

## Canonical Baseline Decision

**VALID。** attempted=80、executed=80、无执行失败、无基础设施损坏、executor 口径不变式通过（evaluated == TP+FP+FN+TN）。环境在 run 前逐项探活通过，run 为预先声明的唯一 official run，未因指标重跑。Judge FAR/FRR 优于近期历史 run，但本 baseline 的效力来自可复盘性而非分数。

## Production Code Changes

NONE（`git diff af16325 -- rag-server/src/main/java rag-web/app` 为空）。

## Dataset Changes

NONE（`docs/eval/eval-v3-hard-v2-array.json` 未修改，导入版本 `5694392f` 与既有 v2 run 使用同一 datasetVersionId）。

## Config Changes

NONE（retrieval/threshold/answerability/rewrite 全部沿用冻结默认值，见上文快照）。

## 完整数据

逐题结果（retrieved 快照、stage 位次、Judge 决策、回答与引用）由系统 eval run 持久化，
runId `0899d287-3e17-4e4e-9c63-b77ed0c02212`，可通过 `GET /api/v1/eval/runs/{runId}` 与前端「效果评测」页查看。
