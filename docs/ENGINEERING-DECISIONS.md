# GeneralRAG 工程决策记录（Engineering Decisions）

> Current-State 文档：提炼项目中最影响形态的 11 个决策。每个决策只写 Problem / Decision / Why / Tradeoff；
> 实现细节见 [ARCHITECTURE.md](ARCHITECTURE.md)、[INGESTION.md](INGESTION.md)、[RETRIEVAL-AND-QA.md](RETRIEVAL-AND-QA.md)、[EVALUATION.md](EVALUATION.md)。

## D1 — Hybrid Retrieval instead of Vector-only

### Problem
纯向量检索对精确实体、参数值、错误码的字面召回不稳定（语义近似 ≠ 字面命中）；
纯 BM25 对同义表达与口语化改写不稳定。二者失败模式互补。

### Decision
双通道并行召回（Vector kNN + BM25），覆盖两类失败模式。

### Why
知识问答的问题类型天然两分："HikariCP maximumPoolSize 默认值"（字面）与
"连接池闲置连接怎么处理"（语义）。任一单通道都会系统性漏掉另一类。

### Tradeoff
计算成本翻倍；两路分数量纲不同，必须引入融合层（见 D2）。

## D2 — RRF for rank fusion

### Problem
BM25 分与余弦相似度量纲完全不同，直接加权需要持续标定且不可移植。

### Decision
应用侧 Reciprocal Rank Fusion：`score(d) = Σ 1/(k + rank)`，k=60，归一化到 (0,1]。

### Why
只利用 rank、免标定；并列时按首通道位次 + chunkId 稳定排序，保证两次运行可比。
ES basic license 无原生 RRF（付费特性），应用侧实现反而可单测、不依赖集群版本。

### Tradeoff
丢弃绝对分数信息；k 值为惯例值而非调优对象。

## D3 — retrievalContent / answerContent 分离（R5-A）

### Problem
检索希望 chunk 携带更多上下文（文档名、章节路径显著提高"文档归属"类查询命中），
但生成要求忠实证据——metadata 混入正文会被 LLM 当作新事实来源污染回答。

### Decision
每个 chunk 双表示：`answerContent`（忠实正文，供 Judge/Generation/Citation/评测锚点）
与 `retrievalContent`（确定性前缀 + 正文，仅供 Embedding/BM25/Reranker）。

### Why
`retrieval representation ≠ answer representation`；增强只用确定性已有信息，
禁止 LLM contextual retrieval，无 titlePath 不虚构——保证可复现、可审计。

### Tradeoff
ES 多存一份正文；旧 chunk 无新字段需读侧回退，重新入库才生效（存量兼容已记录）。

## D4 — Stage-level retrieval tracing（R5-B）

### Problem
早期只有最终 topK 指标，Hit 下降时无法区分是召回不足、融合排序问题还是重排打回——归因靠猜。

### Decision
生产执行路径顺带收集 `RetrievalTrace`（vector/BM25/union/fused/preRerank/reranked
各阶段候选 + 名次），证据即使掉出 final topK 仍可在各阶段快照中定位名次。

### Why
评测不复制检索管线（单一代码路径原则），trace 是生产路径的副产品而非重放；
Stage 指标让"瓶颈在哪一层"从猜测变成读数（详见 EVOLUTION § Retrieval 饱和的判断）。

### Tradeoff
执行路径携带少量额外内存；trace 不携带正文（只有锚点），控制成本。

## D5 — Evidence Sufficiency Judge（R4/R4.1）

### Problem
相似度分数衡量"相关"，不衡量"充分"。PARTIAL_EVIDENCE/UNSUPPORTED_INFERENCE 类问题
检索分可以很高（与可答题分布完全重叠），单阈值必然在高分区漏杀。

### Decision
两层门控：低分直拒（双口径阈值，零成本）→ 其余全部交给 LLM Evidence Judge
（temperature=0，判定"仅凭证据能否完整回答"，不看相关性）。

### Why
门控形态来自 Baseline 数据而非直觉（先跑分布、再设计机制）；Judge 与生成共用
同一 Context 实例（R4.1），判定与生成的输入一致才有可比性。

### Tradeoff
每次灰区/高分问题多一次模型调用；Judge 本身会出错（边界摇摆、degraded），
需要 fail-open/fail-closed 策略与 degraded 剔除口径（见 D10 口径纪律）。

## D6 — Query Rewrite only affects the retrieval query（R6-C）

### Problem
追问（"前者是多少？"）缺少实体，直接检索必然失败；但把改写后的问题交给
Judge/Generation 会篡改用户原始语义与对话语境。

### Decision
rewrite 只产生 `effectiveRetrievalQuery`，作用于 Embedding/BM25/Reranker；
Judge 与 Generation 永远看到 original question + history。

### Why
改写查询不是用户实际提出的问题；Rewriter 指令禁止外部知识与语义扩张，
失败回退原始问题（fail-open），rewrite 故障不阻断 QA。

### Tradeoff
多一次模型调用（仅 history 非空时）；rewrite 质量决定检索质量（有
`max-query-length`/结构化输出校验兜底）。

## D7 — Effective query consistency invariant（R6-C.1）

### Problem
真实 bug：rewrite 后 Vector/BM25 用新查询，Reranker 仍按原始问题打分——
一次检索里存在两个查询语义，FOLLOW_UP 证据被重排打回。

### Decision
确立不变式：**一次检索执行只允许一个查询语义**，Embedding/BM25/Reranker 统一使用
effectiveRetrievalQuery，由回归测试锁定。

### Why
这暴露的是"流水线各阶段共享隐式输入"的通病；显式不变式 + 测试防漂移比逐处修补可靠。

### Tradeoff
无——纯正确性修复（细节见 EVOLUTION）。

## D8 — PDF AUTO uses deterministic routing（R6-D）

### Problem
普通文本 PDF 用 PDFBox 更快更稳；扫描件/低质量 PDF 需要 MinerU OCR。
"什么文件用哪个解析器"不能靠人每次指定。

### Decision
`pdf-parser=auto`：PdfQualityProbe（页数/字符密度/空页率/可打印率/乱码率，纯确定性指标）
→ PdfAutoRouter 固定优先级规则 → PDFBox 或 MinerU；决策与探针指标持久化（parse_metadata）。

### Why
同一 PDF 永远得到同一 routing reason（规则顺序固定，多条件命中取第一个）；
无 LLM 参与意味着路由本身不会成为不确定源。

### Tradeoff
阈值（minChars 等）为经验默认值；探针多一次全文件读取（大文件多持有一份 byte[]，已记录）。

## D9 — AUTO routing ≠ fallback

### Problem
直觉设计是"先 PDFBox，失败换 MinerU"。这会让"同一文件、不同环境"产生不同解析器与
chunk 结构，版本、评测锚点、调试记录全部失去复现基础。

### Decision
AUTO 选定即所有权：选 MINERU 后 MinerU 失败 → FAILED（可显式重试），
不静默回退 PDFBox。失败路径同样持久化路由元数据。

### Why
解析器 ownership 漂移是数据一致性问题，不是可用性问题——README 的 Quick Start
已把"要 OCR 就配 MinerU"作为显式部署决策。

### Tradeoff
MinerU 不可达时 AUTO 的扫描件直接失败（而非降级出低质量结果）——这是刻意选择。

## D10 — Judge degraded is not a decision（R4.1 口径）

### Problem
Judge 超时/故障时 fail-open 放行，若把"放行"计为"正确拒绝"或计入混淆矩阵，
每次故障都会污染指标（fail-closed 时则反向虚增"正确拒答"）。

### Decision
degraded（TIMEOUT/OVERLOADED/MODEL_ERROR/INVALID_RESPONSE 四分类）排除出
混淆矩阵与 refusalAccuracy，单独按类型透出；逐题持久化 degraded 标记保证可复算。

### Why
"系统故障不是决策"适用于一切聚合系统输出的指标——先按失败模式分区，再谈指标。

### Tradeoff
故障期间的指标覆盖面变小（evaluated 减少）；换来的指标才可信。

## D11 — Eval reuses the production pipeline（R2 起）

### Problem
评测如果自建一套检索实现，测的是"第二套代码"；管线每次改动评测都要跟改，且永远测不到真实路径。

### Decision
QA、Debug、Eval 共用唯一 `RetrievalPipeline`；判定层同样单一路径（AnswerabilityPolicy）；
调试服务退化为薄封装。

### Why
第一轮调试服务逐行复刻检索语义的教训：复制=漂移。评测结论只有对真实路径成立才有意义。

### Tradeoff
评测与线上共享代码路径的耦合（trace 等观测代码在执行路径内），用"trace 只是副产品"约束控制复杂度。

## D12 — Feature Freeze after baseline saturation

### Problem
能力继续横向叠加（AST/VLM/GraphRAG）会稀释主线，且每项都缺乏 BadCase 证据支持。

### Decision
Final Baseline 达成 Hit@5=1.0、Rerank Recall@6=1.0、FOLLOW_UP Hit@1=1.0 后冻结范围
（`FEATURE-FREEZE.md`）：新想法入 Future Work，参数/提示词冻结，只允许 correctness 级修复。

### Why
剩余错误集中在 Judge 边界摇摆与生成语义对齐，继续堆检索能力无益；
"当前系统真实表现"比"最好看的系统表现"更有交付价值。

### Tradeoff
明确放弃若干可展示的方向（见 FEATURE-FREEZE Future Work），等待新一轮需求评审解锁。
