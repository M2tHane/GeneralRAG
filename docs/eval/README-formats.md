# R3-P1 新格式评测集（round3-formats）

> 撰写时间：2026-09-14。R3-P1（Word/Excel/CSV 解析 + 稳定锚点键）配套评测资产说明。

## 0. 版本与锚定

| 版本 | 文件 | 语料 | 锚定方式 | 状态 |
| --- | --- | --- | --- | --- |
| r3-fmt-v1 | `eval-formats-r3-v1-array.json` | `docs/eval-corpus/` 下 3 篇新格式语料：`nginx-proxy-guide.docx` / `hikari-cp-reference.xlsx` / `kafka-error-codes.csv` | **contentHash + chunkId + titlePath 三重锚点**（全部来自服务端真实分块） | 当前版本 |

判定口径（服务端 `EvidenceMatcher`）：`contentHash` → `chunkId` → `titlePath` → `anchorPath` 精确相等，不回退 docName。

- **contentHash（R3-P1）**：分块正文规范化（去首尾空白 + 内部空白折叠为单空格）后
  SHA-256 前 16 hex，由 `EvidenceMatcher.contentHashOf(String)` 统一计算。
  与文件名/分块策略解耦——文档改名或重入库导致 chunkId 变化后仍可判定命中。
- 数据集的锚点值按「先入库 → 拉取真实分块 → 计算哈希 → 回填数据集」流程生成
  （见 §2），与 v2 数据集「锚点必须锚定真实分块」的既定原则一致。

## 1. 语料与题目覆盖

| 语料 | 格式 | 结构特征 | 覆盖验证点 |
| --- | --- | --- | --- |
| nginx-proxy-guide.docx | Word | Heading 1-3 层级 + 7 行参数表 | docx 标题样式→ATX 映射、表格→管道表、titlePath 编号 |
| hikari-cp-reference.xlsx | Excel | 3 个 sheet，各为表头+数据行 | 多 sheet 分块、`## sheet` 标题、单元格显示值 |
| kafka-error-codes.csv | CSV | 表头 + 6 数据行，含逗号字段 | 简化 RFC4180 解析、单块表结构 |

题目分布（10 题）：DIRECT 6、TERM_VARIATION 1、CONFUSABLE 1、OUT_OF_KB 2。

**titlePath 注意**：xlsx 的 sheet 标题是 2 级（`## sheet`）且文档无 1 级标题，
真实产物形如 `hikari-cp-reference.xlsx > 0.1. 参数说明`（父级计数为 0）——
与 StructureChunker 编号规则一致，不是缺陷。

## 2. 首轮实测结果（2026-09-14）

同一 KB（`33024cf2…`，R3 新格式验证库，STRUCTURE 分块）、本数据集 v1、
HYBRID_RERANK 模式、真实模型（DashScope）：

| 指标 | 数值 |
| --- | --- |
| Hit@1 / Hit@5 | **1.00 / 1.00**（8 题可答题全部 rank=1 命中） |
| MRR | **1.00** |
| 拒答正确率 | 0.80（8/10；2 题资料外未触发 refusal，见下） |
| 检索耗时均值 | 396.5 ms |
| 端到端 p95 | 5684 ms |

runId：`724c30f1…`（数据库内可查，逐题含分阶段位次与 contentHash）。

**拒答 2/10 的归因**：两道资料外题的 top1 分块重排分分别为 0.96 / 0.77
（题面词与语料表内容高度重叠），超过 rerank 阈值 0.65，未触发拒答；但回答
内容语义上仍明确说明「资料中没有」并给出相邻参数。这与第二轮 §1.4-5 的结论同源：
**极小语料上重排分数不可靠**，本题集 3 篇文档 6 个分块放大了该局限。
该结果不应外推为系统拒答能力退化。

## 3. 数据集生成与回填流程

数据集锚点必须来自**服务端真实分块**（任何模拟生成都可能与 POI 产物有空白差异）：

1. 建评测 KB → 上传 3 篇新格式语料（**chunkStrategy=STRUCTURE**）→ 等入库完成；
2. `GET /api/v1/documents/{docId}/chunks` 取每篇全部分块（含 text）；
3. 对目标分块运行 `EvidenceMatcher.contentHashOf(text)`；
4. 将 contentHash / chunkId / titlePath 写入数据集 evidence，
   `POST /api/v1/eval/datasets` 导入为新版本。

## 4. 与 v2（MD 数据集）的关系

- 本数据集只测**新格式解析 + 检索**，与 v2（4 篇 MD）分属不同 KB 语料，不可混跑对比；
- 跨格式总量对比应在「同 KB、MD+新格式混合语料、合并数据集」下进行，属后续工作；
- v2 数据集（`eval-test-v2-array.json` 等）仍是回归基线，不退役。

---

## 5. R4 补充：Answerability 专项集（answerability-v1）

- 文件：`docs/eval/eval-answerability-v1-array.json`（生成器 `scripts/gen-answerability-dataset.py`）
- 语料：KB `b78b6fb8`（R4-Answerability验证库）= 4 篇 MD + docx/xlsx/csv，7 文档 44 分块，全 STRUCTURE
- 72 题：DIRECT 13 / TERM_VARIATION 9 / CONFUSABLE 18（12 false）/ OUT_OF_KB 14 /
  PARTIAL_EVIDENCE 12（全 false）/ FOLLOW_UP 6；answerable=true 34 / false 38
- 用途：Answerability 策略调优与新旧对照（TUNING 性质），不用于宣称通用检索能力
- 详见 `docs/round4/02-实施记录.md` §6-§7
