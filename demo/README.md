# GeneralRAG Golden Demo

Demo Dataset Version: v1

一套独立于正式 Eval Dataset 的演示数据：围绕统一虚构产品 **Aurora Cloud** 的 6 份短文档 + 12 道演示问题，10~15 分钟内可以完整展示 GeneralRAG 的核心能力——文档入库、混合检索（BM25 + 向量 RRF 融合 + 重排）、证据充分性判定（低分直拒 + LLM Evidence Judge）、SSE 流式回答与来源引用、跨文档组合、Follow-up Query Rewrite 与表格检索。

## Goal

让第一次看到项目的人，不读任何历史文档，只看本文件就能完成一次完整演示。

## Requirements

- 已按根目录 `README.md` Quick Start 启动：中间件（MySQL/ES/MinIO）、后端（:8080）、前端（:3000，可选）。
- 后端 `.env` 中的模型服务（CHAT_MODEL_* / EMBEDDING_MODEL_*）可用。
- `curl`（命令行演示）或浏览器（前端演示）。两条路径效果一致，本文以 curl 为主。

## Documents

全部文档短小（最大约 3 KB），围绕 Aurora Cloud：

| File | Format | Size | Purpose |
|---|---|---:|---|
| product-guide.md | Markdown | ~0.9 KB | 产品基本事实：版本 3.2、部署区域、核心能力 |
| operations-guide.pdf | PDF（纯文本层） | ~2 KB | 维护窗口、RTO/RPO、故障升级（默认 pdfbox 解析） |
| database-guide.md | Markdown | ~1.2 KB | 连接池默认配置（Follow-up Query Rewrite 演示） |
| change-policy.docx | DOCX | ~1.7 KB | 变更审批规范（跨文档组合演示） |
| regional-capacity.xlsx | XLSX | ~5 KB | 区域容量表（表格检索演示） |
| release-notes.md | Markdown | ~1 KB | 版本功能区分（3.1 vs 3.2） |

`questions.json` 记录 12 道问题（id / category / expectedBehavior / expectedAnswerContains / evidence / demoPurpose）。注意：这**不是**正式评测集，没有 EvalQuestion/failureMode schema，不要导入 `/api/v1/eval/datasets`。

## Create Demo KB

```bash
KB_ID=$(curl -s -X POST http://localhost:8080/api/v1/knowledge-bases \
  -H 'Content-Type: application/json' \
  -d '{"name":"GeneralRAG Golden Demo v1","description":"Golden demo (Aurora Cloud)"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")
echo $KB_ID
```

## Upload Documents

```bash
cd demo/documents
for f in product-guide.md operations-guide.pdf database-guide.md \
         change-policy.docx regional-capacity.xlsx release-notes.md; do
  curl -s -X POST "http://localhost:8080/api/v1/knowledge-bases/$KB_ID/documents" -F "file=@$f"
done
```

重复上传同一文件会返回 `DUPLICATE_DOCUMENT`（409），属正常防护。

## Wait for Ingestion

```bash
# 逐个轮询任务状态，直到 status=COMPLETED
curl -s http://localhost:8080/api/v1/documents/<docId>/task
# 或列出该 KB 全部文档确认 status 全为 COMPLETED
curl -s http://localhost:8080/api/v1/knowledge-bases/$KB_ID/documents
```

## Quick Demo（3~5 分钟版本）

1. **Direct QA**：问 `Aurora Cloud 当前稳定版本是什么？` → 答 3.2，citation 指向 product-guide.md。
2. **Follow-up Query Rewrite**：先问连接池默认配置，再问 `前者是多少？` → 答 600000 ms；打开调试页可看到 `queryRewritten=true`。
3. **Out-of-KB Refusal**：问 `Aurora Cloud 的 CEO 是谁？` → 低分拒答（`LOW_SCORE_REFUSAL`），citations 为空。
4. **Spreadsheet Retrieval**：问 `cn-east-2 的容量是多少？` → 答 800，citation 指向 xlsx 表格分块。

前端路径：打开 http://localhost:3000 → 选择 `GeneralRAG Golden Demo v1` 知识库 → 新建会话提问；调试页（检索调试）输入同样问题可看分阶段位次。

## Full Golden Demo

| # | ID | 演示点 | Question | 预期 |
|---|---|---|---|---|
| 1 | demo-01 | Direct QA | Aurora Cloud 当前稳定版本是什么？ | 3.2 + product-guide.md citation |
| 2 | demo-02 | Direct（多事实） | Aurora Cloud 主要部署在哪些区域？ | 三个区域 + product-guide.md citation |
| 3 | demo-03 | Hybrid Retrieval | Aurora 最多允许丢失多长时间的数据？ | RPO 15 分钟 + operations-guide.pdf citation |
| 4 | demo-04 | Multi-source | Aurora 的默认维护窗口是什么时候？普通生产变更需要谁批准？ | 每周三 02:00-04:00 + 服务负责人（PDF + DOCX 两路 citation） |
| 5 | demo-05/06/07 | Follow-up | 先问连接池两项默认配置，再问 `前者是多少？`、`后者默认开启吗？` | 600000 ms / 默认开启；Q2、Q3 均触发 query rewrite |
| 6 | demo-08 | Corrective | Aurora 的 RPO 是 1 小时吗？ | 明确纠正：RPO 是 15 分钟，不是 1 小时 |
| 7 | demo-09 | Out-of-KB | Aurora Cloud 的 CEO 是谁？ | 拒答，citations=[] |
| 8 | demo-10/11 | Spreadsheet | `cn-east-2 的容量是多少？` / `哪个区域的容量是 1200？` | 800 / cn-north-1，citation 指向 xlsx |
| 9 | demo-12 | 版本区分 | 跨区域健康检查是在哪个版本默认启用的？ | 3.2 + release-notes.md citation |

QA curl 模板（SSE）：

```bash
SESSION_ID=$(curl -s -X POST http://localhost:8080/api/v1/sessions \
  -H 'Content-Type: application/json' -d "{\"kbId\":\"$KB_ID\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")

curl -N -X POST http://localhost:8080/api/v1/qa/stream \
  -H 'Content-Type: application/json' \
  -d "{\"kbId\":\"$KB_ID\",\"sessionId\":\"$SESSION_ID\",\"question\":\"Aurora Cloud 当前稳定版本是什么？\",\"clientRequestId\":\"$(uuidgen)\"}"
```

同一 `SESSION_ID` 内连续提问即为多轮会话（demo-05→06→07 必须同一会话）。

## Retrieval Debug

对 demo-03 可用检索调试 API 观察混合检索链路（也可用前端调试页）：

```bash
curl -s -X POST http://localhost:8080/api/v1/debug/retrieval \
  -H 'Content-Type: application/json' \
  -d "{\"kbId\":\"$KB_ID\",\"question\":\"Aurora 最多允许丢失多长时间的数据？\"}"
```

响应 `trace` 中依次查看：

- `vectorCandidates` / `bm25Candidates` —— 两路召回是否都命中目标证据；
- `fusedCandidates` —— RRF 融合后目标证据是否保留；
- `rerankedCandidates` / `finalTopK` —— 重排后是否进入最终上下文。

每个 hit 的 `stages` 数组给出该 chunk 在 vector/bm25/fused/rerank 各阶段的位次与分数。分数是模型相关变量，会随模型与语料变化，**不要把具体数值当成固定预期**；演示时只讲"两路都召回了它、融合后保住了、重排后排到了第一"。

## Follow-up Query Rewrite

在检索调试 API 中传 `history` 可复现 Query Rewrite（问答会话内自动完成，无需手工传）：

```bash
curl -s -X POST http://localhost:8080/api/v1/debug/retrieval \
  -H 'Content-Type: application/json' \
  -d '{
    "kbId": "'$KB_ID'",
    "question": "前者是多少？",
    "history": [
      {"role":"user","content":"Aurora 数据库连接池的 idleTimeout 和 cachePrepStmts 默认配置分别是什么？"},
      {"role":"assistant","content":"idleTimeout 默认 600000 ms，cachePrepStmts 默认 true。"}
    ]
  }'
```

观察响应 `queryRewrite` 字段：`originalQuery`（前者是多少？）→ `retrievalQuery`（含 idleTimeout 实体的完整查询）→ `queryRewritten: true`。

## Optional PDF AUTO Demo

Golden Demo 完全不依赖 MinerU：`operations-guide.pdf` 是纯文本层 PDF，走默认 `pdf-parser=pdfbox`。

如需额外演示扫描件路由（非 Golden Demo PASS 条件）：配置 MinerU（见根 README Optional Services）并设 `RAG_INGESTION_PDF_PARSER=auto` 后，上传一个低文本/扫描 PDF，从 `GET /api/v1/documents/{docId}` 的 parseMetadata 可看到 `selected=MINERU` 的路由结果。

## Cleanup

```bash
# 删除 Demo KB（级联删除文档/会话/ES chunks），走正常 API，勿直接操作 DB
curl -s -X DELETE http://localhost:8080/api/v1/knowledge-bases/$KB_ID
```

保留亦可：Demo KB 与 Eval KB 相互独立，不影响评测数据。
