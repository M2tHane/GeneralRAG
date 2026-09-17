# GeneralRAG Feature Freeze（Release Candidate）

> 冻结时间：2026-09-14（会话日期）。基线：`main` @ R6-D.1（`006635a`）。
> 本文档是项目范围的权威声明：自本文档合入起，**禁止新增核心功能**。
> 任何新想法进入 Future Work，不进入实现计划。
> 完整已知限制（含数据依据）见根 `README.md`「已知局限」；架构与决策依据见 `docs/03-技术路线.md`。

## 项目定位

一个支持多格式文档入库、混合检索、Rerank、Evidence Sufficiency Judge、会话 Query Rewrite、PDF AUTO Routing 与完整评测体系的生产型通用 RAG 系统。

## Current Scope（冻结范围内 capability）

| 能力 | 说明 | 主要代码 |
| --- | --- | --- |
| 多格式入库 | MD/TXT/PDF/DOCX/XLSX/CSV 解析 → 清洗 → 结构分块（Excel 双层）→ 向量化 → ES 索引；MinIO 存源文件与解析产物；任务表驱动 + 阶段证据续跑 | `com.rag.ingestion` |
| PDF AUTO Routing | 全局 `pdf-parser ∈ {pdfbox, mineru, auto}` 互斥装配；auto 模式下 PDFBox 探针（页数/字符数/空页率/可打印率/乱码率）确定性路由到 PDFBox 或 MinerU；决策即所有权，无静默 fallback；失败路径同样持久化路由元数据（R6-D.1） | `ingestion.parse`（PdfQualityProbe/PdfAutoRouter/AutoPdfParser） |
| 混合检索 | BM25 + 向量双通道 → 应用侧 RRF 融合 → DashScope 原生 Rerank（失败显式降级）；VECTOR/HYBRID/HYBRID_RERANK 三模式 | `com.rag.retrieval` |
| retrievalContent/answerContent 分离 | 向量/BM25/重排输入用确定性增强文本（文档名+标题路径），生成与引用用忠实正文 | R5-A |
| Answerability 证据充分性判定 | 低分双口径直拒 → LLM Evidence Judge（不看相关性，只判断"仅凭证据能否完整回答"）；Judge 失败按 fail-closed 开关降级并四分类标注；bulkhead 并发闸门 | `com.rag.answerability` |
| History-aware Query Rewrite | 检索前将依赖历史的追问改写为独立查询；仅改检索查询，Judge/生成仍用原问题；失败回退（R6-C） | `retrieval.QueryRewriteService` |
| 会话问答 | SSE 流式回答、来源引用（拒答时引用清空）、多会话管理 | `com.rag.api` |
| 检索调试 | 分阶段位次/分数（vector/BM25/RRF/rerank/final）、生效配置、送入模型的上下文 | `com.rag.debug` |
| 效果评测 | 数据集导入（JSON/CSV）、分块级锚点判定（chunkId/titlePath/contentHash）、Hit@K/MRR、Answerability 混淆矩阵（Judge 降级不入混淆矩阵）、stage-level metrics、两次运行可比性对比 | `com.rag.eval` |
| 评测体系资产 | v2（分块锚点）/ formats-r3 / answerability v1-v2 / hard-v2（80 题 7 类 failureMode）数据集 + 生成器 + 标注指南 | `docs/eval` |
| 前端 | 知识库 / 知识问答 / 检索调试 / 效果评测 四域，Next.js App Router | `rag-web` |

## Completed Features（轮次与基线）

| 轮次 | 内容 | 收尾 commit |
| --- | --- | --- |
| R1 | 向量 RAG 基线（上传→解析→分块→检索→SSE 问答） | 初始提交 |
| R2 | 混合检索 + RRF + Rerank + 分块级评测口径（v1 数据集退役） | round2 记录 |
| R3 | DOCX/XLSX/CSV 解析、contentHash 锚点、文档多版本 | round3 记录 |
| R4 / R4.1 | Answerability 证据充分性判定 + bulkhead 稳定化 + 阈值重校准（0.65→0.75）+ FAR 45.95%→5.41% | `9da3469` |
| R5 | retrievalContent/answerContent 拆分、stage-level 评测、Excel 双层分块 | `2d0b02e` |
| R6-A/A.1 | Hard Eval Challenge Set（80 题 failureMode 标签）+ eval hygiene | `8ae9e69` |
| R6-B/B.1 | Judge 硬化 + 生成语义对齐 | `c9a640e` |
| R6-C/C.1 | History-aware Query Rewrite + effective query 一致性 | `c91f753` |
| R6-D/D.1 | PDF AUTO 确定性路由 + 路由可观测性与契约卫生 | `006635a` |

轮次级实施细节见 `docs/round2..5/` 与 `docs/06-测试报告.html`；R6 系列记录见 git log（`44536ce..006635a`）。

## Known Limitations（冻结时点摘要）

以下为已核实、已记录、**不作为本轮修复对象**的限制（完整数据依据见 README「已知局限」与各轮报告）：

1. **Judge 不是真值**：Evidence Judge 存在残留 FP/FN（PARTIAL_EVIDENCE 语义边界为主），置信度不可作正确性信号；Judge 与生成共用同一模型（同源偏差可能）。
2. **阈值/分数是模型绑定的**：rerank 阈值 0.75 绑定 `qwen3.7-text-rerank`；更换 reranker 必须重扫描重校准；更换 embedding 必须重建全部 KB 索引。
3. **余弦阈值区分度弱**：VECTOR/重排降级场景的拒答数字不代表系统能力。
4. **评测非逐位可复现**：embedding 浮点微差 + HNSW 近似；两次运行是「可比」而非「逐位相同」。
5. **Judge 边界摇摆**：少数边界题存在模型非确定性（如 hard 集生成侧软拒答个别样本）；不再做 prompt tuning。
6. **`max-history-turns` 命名语义**：rewrite 提示词截断的实际单位是历史消息条数（每轮 1 条），与 `max-history-messages` 的消息窗口口径一致——行为正确，仅命名近似。
7. **AUTO probe 内存**：探针对整个 PDF 字节做单次 `PDDocument.load`，大文件会额外持有一份 `byte[]`。
8. **单实例假设**：入库任务依赖进程内线程池 + DB 任务表，多实例化前需引入消息队列。
9. **ES basic license 无原生 RRF**：融合在应用侧实现（设计内，非缺陷）。
10. **存量数据兼容**：标题路径编号修复、retrieval_content 字段等仅对重新入库的语料生效。

## Future Work（仅登记，不实现）

- **Code-aware RAG / Java AST**：代码仓库问答、AST 级分块与符号索引。
- **Image / VLM**：图像理解、图表问答、视觉文档解析。
- **GraphRAG**：实体-关系图索引与图增强检索。
- **Advanced spreadsheet execution**：Excel Agent / SQL 执行 / 计算引擎型问答。

> 以上任何一项在本仓库的落地都需要新一轮需求评审（Supie Stage 1 起），并解除本冻结声明。

## Freeze 规则

1. 生产代码（`rag-server/src/main`、`rag-web/{app,components,lib}`）只允许 correctness/security/无法启动/数据损坏/契约严重错误级别的修复。
2. 参数调优（threshold/topK/RRF k/rerank/prompt）冻结——除非有新模型/新语料分布的重校准需求。
3. 已应用的 Flyway 迁移（V1~V8）与评测数据集（`docs/eval/*`）不可修改。
4. `contracts/openapi.yaml` 是 API 唯一事实源；契约变更必须走审批门。
