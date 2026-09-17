# Documentation Index

> 本目录分三层：**Current-State 文档**（描述当前代码事实）、**Decision Records**（历史审批门产物，
> 记录"当时的决策依据"，行为以代码与 Current 文档为准）、**Historical / Eval Artifacts**
> （实施记录与评测材料，保留证据价值，不作为当前事实来源）。

## Current-State Documentation（当前事实）

| 文档 | 回答的问题 |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 系统整体怎么工作：组件、数据流、存储职责 |
| [INGESTION.md](INGESTION.md) | 文件上传后发生什么：状态机、解析器、PDF AUTO 路由、双层内容 |
| [RETRIEVAL-AND-QA.md](RETRIEVAL-AND-QA.md) | 问题如何变成答案：rewrite、混合检索、RRF、重排、两层拒答、Judge |
| [EVALUATION.md](EVALUATION.md) | 系统如何知道自己改对了：评测架构、指标口径、BadCase 闭环 |
| [ENGINEERING-DECISIONS.md](ENGINEERING-DECISIONS.md) | 为什么这么设计：11 个关键决策的 Problem/Decision/Tradeoff |
| [EVOLUTION.md](EVOLUTION.md) | 如何演进到今天：Eval/BadCase 驱动的 Phase 叙事 |

## 权威单点

| 主题 | 唯一来源 |
| --- | --- |
| 当前指标 | [eval/FINAL-BASELINE.md](eval/FINAL-BASELINE.md)（Feature Freeze 后 canonical baseline） |
| 当前演示 | `../demo/README.md`（Golden Demo） |
| 启动方式 | `../README.md`（Quick Start，本目录不重复） |
| API 契约 | `../contracts/openapi.yaml` |
| 范围冻结 | [FEATURE-FREEZE.md](FEATURE-FREEZE.md)（Current Scope / Known Limitations / Future Work） |

## Decision Records（历史审批门产物）

| 文档 | 性质 |
| --- | --- |
| [01-需求理解.md](01-需求理解.md) | 第一轮需求基线（用户批准） |
| [03-技术路线.md](03-技术路线.md) | 第一轮技术路线（用户批准；解释"为什么"） |
| [06-实现计划.md](06-实现计划.md) / [06-测试报告.html](06-测试报告.html) | 第一轮实现计划与验证报告 |

## Historical Implementation Notes（实施记录，保留证据价值）

> 均为"当时当轮"的记录：内含已过时的参数（如 rerank 阈值 0.65）与已被后续轮次取代的行为。
> 阈值是模型绑定的校准值，历史数值不得当作当前配置——当前值见 `rag-server/src/main/resources/application.yaml`
> 与 [eval/FINAL-BASELINE.md](eval/FINAL-BASELINE.md)。

| 目录 | 内容 |
| --- | --- |
| [round2/](round2/) | 第二轮（混合检索/RRF/Rerank/分块级评测口径）：需求、实施记录、第三轮计划 |
| [round3/](round3/) | 第三轮（DOCX/XLSX/CSV 格式扩展、contentHash 锚点、文档多版本、Excel 分块） |
| [round4/](round4/) | 第四轮（Answerability 判定、R4.1 稳定化、阈值重校准 0.65→0.75 的数据依据） |
| [round5/](round5/) | 第五轮（双层内容分离、stage-level 评测、Excel 结构分块） |

> R6 系列（Hard Eval / Judge 硬化 / Query Rewrite / PDF AUTO）记录见 git log `44536ce..006635a`
> 与 [FEATURE-FREEZE.md](FEATURE-FREEZE.md) 的轮次表。

## Evaluation Artifacts（评测材料）

| 路径 | 内容 |
| --- | --- |
| [eval/README.md](eval/README.md) | 数据集版本演进（v1 退役原因）、语料来源、执行前置条件 |
| [eval/FINAL-BASELINE.md](eval/FINAL-BASELINE.md) | **canonical baseline**：runId 0899d287、全部指标、badcase 归因、历史对照 |
| [eval/eval-v3-hard-v2-array.json](eval/eval-v3-hard-v2-array.json) | Hard Eval 数据集（80 题，7 类 failureMode） |
| [eval/eval-tuning-v2-array.json](eval/eval-tuning-v2-array.json) / [eval-test-v2-array.json](eval/eval-test-v2-array.json) | v2 调优/测试集（分块锚点） |
| [eval/eval-answerability-v1-array.json](eval/eval-answerability-v1-array.json) / [eval-answerability-v2-array.json](eval/eval-answerability-v2-array.json) | Answerability 专项集（v1/v2） |
| [eval/eval-formats-r3-v1-array.json](eval/eval-formats-r3-v1-array.json) / [README-formats.md](eval/README-formats.md) | 格式扩展评测（r3/excel） |
| [eval/answerability-labeling-guide.md](eval/answerability-labeling-guide.md) | 标注指南（v2 标签修订依据） |
| [eval-corpus/](eval-corpus/) | 评测语料（4 篇 MD + docx/xlsx/csv fixture） |
