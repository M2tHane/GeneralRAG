# Golden Demo Validation

Demo Dataset Version: v1 的真实运行验证记录（2026-09-17，本地环境，默认配置 HYBRID_RERANK）。

只记录稳定事实（行为与 citation 归属），不记录实时分数、token 用量、精确延迟与完整模型回答——这些会随模型漂移。

## Runtime Validation

| ID | Category | Result | Behavior | Citation（人工核实） |
|---|---|---|---|---|
| demo-01 | DIRECT | PASS | 答出 3.2；JUDGE_ACCEPT | product-guide.md ✓（chunk c0000，含"当前稳定版本为 3.2"） |
| demo-02 | DIRECT | PASS | 逐一列出 cn-north-1 / cn-east-2 / ap-southeast-1；JUDGE_ACCEPT | product-guide.md ✓ |
| demo-03 | HYBRID | PASS | 答 RPO=15 分钟；JUDGE_ACCEPT | operations-guide.pdf ✓（chunk c0000，DR Objectives 段） |
| demo-04 | MULTI_CHUNK | PASS | 同时答出维护窗口（每周三 02:00-04:00）与服务负责人批准；JUDGE_ACCEPT | operations-guide.pdf ✓ + change-policy.docx ✓（两个不同文档的 chunk 同时入选） |
| demo-05 | FOLLOW_UP | PASS | Q1 建上下文：答 600000 ms + true；JUDGE_ACCEPT | database-guide.md ✓ |
| demo-06 | FOLLOW_UP | PASS | 同一会话内答"前者"= 600000 ms；JUDGE_ACCEPT | database-guide.md ✓ |
| demo-07 | FOLLOW_UP | PASS | 同一会话内答"后者"默认开启（true）；JUDGE_ACCEPT | database-guide.md ✓ |
| demo-08 | CORRECTIVE | PASS | 明确纠正"RPO 为 15 分钟，而不是 1 小时"；未拒答；JUDGE_ACCEPT | operations-guide.pdf ✓ |
| demo-09 | OUT_OF_KB | PASS | LOW_SCORE_REFUSAL（最高分低于 0.75 阈值，未调 Judge）；回答"当前资料不足以回答该问题" | citations=[] ✓ |
| demo-10 | SPREADSHEET | PASS | 答 800；JUDGE_ACCEPT | regional-capacity.xlsx ✓（cn-east-2 所在数据行 chunk） |
| demo-11 | SPREADSHEET | PASS | 答 cn-north-1；JUDGE_ACCEPT | regional-capacity.xlsx ✓ |
| demo-12 | DIRECT | PASS | 答 3.2；JUDGE_ACCEPT | release-notes.md ✓ |

统计：12 题，12 PASS，0 VARIANT，0 FAIL。demo-05 为 follow-up 上下文题，demo-06/07 为正式计入的 follow-up 结果。

## Ingestion

| File | Status | ES chunks |
|---|---|---:|
| product-guide.md | COMPLETED | 1 |
| operations-guide.pdf | COMPLETED | 2 |
| database-guide.md | COMPLETED | 1 |
| change-policy.docx | COMPLETED | 1 |
| regional-capacity.xlsx | COMPLETED | 2 |
| release-notes.md | COMPLETED | 1 |

parseMetadata（PDF）：requested=PDFBOX, selected=PDFBOX（默认 pdfbox 路径，未依赖 MinerU）。

## Hybrid Retrieval（demo-03 debug 现象）

问题「Aurora 最多允许丢失多长时间的数据？」（原文无此句式，RPO 事实在 operations-guide.pdf）：

- Vector：目标 chunk `operations-guide.pdf#c0000` 第 1 位召回；另一 chunk 亦召回。
- BM25：目标 chunk 第 5 位召回（中文问题对英文 PDF，仍进入候选）。
- RRF 融合：目标 chunk 融合后保持第 2 位，未被单路劣势淘汰。
- Rerank：目标 chunk 升至第 1 位，进入 finalTopK 并成为回答 citation。

各阶段位次随模型/语料漂移，本表只记录"两路召回 → 融合保留 → 重排第一"的链路事实。

## Follow-up Query Rewrite（demo-06/07 debug 证据）

| originalQuery | retrievalQuery | queryRewritten |
|---|---|---|
| 前者是多少？ | Aurora 数据库连接池的 idleTimeout 默认值是多少？ | true |
| 后者默认开启吗？ | Aurora 数据库连接池的 cachePrepStmts 默认是开启的吗？ | true |

两次改写均显式补回实体（idleTimeout / cachePrepStmts），top hit 均回到 database-guide.md。
