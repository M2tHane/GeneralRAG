/**
 * 评测指标展示与对比逻辑（第二轮）。
 *
 * 纯函数，便于单测：契约把指标放在 EvalRunSummary / metrics 里，缺省为 null
 * （运行中/失败运行没有指标）——展示层必须区分"0"与"未产出"。
 */
import type { EvalRunSummary } from "@/lib/api/types";

/** 指标展示顺序与定义（比率类显示为百分比，耗时类显示 ms）。 */
export interface MetricMeta {
  key: string;
  label: string;
  desc: string;
  kind: "ratio" | "ms" | "count";
  /** 该指标是否"越小越好"（耗时类）。 */
  lowerIsBetter?: boolean;
}

export const METRIC_META: MetricMeta[] = [
  { key: "hitAt1", label: "Hit@1", desc: "正确答案分块排在第 1 位的题目占比", kind: "ratio" },
  { key: "hitAt3", label: "Hit@3", desc: "正确答案分块排在前 3 位的题目占比", kind: "ratio" },
  { key: "hitAt5", label: "Hit@5", desc: "正确答案分块排在前 5 位的题目占比", kind: "ratio" },
  {
    key: "recallAt5",
    label: "Recall@5",
    desc: "前 5 条候选中覆盖到的参考证据分块比例（分块级口径）",
    kind: "ratio",
  },
  {
    key: "mrr",
    label: "MRR",
    desc: "每题首个命中证据分块排名倒数的平均值；未召回计 0",
    kind: "ratio",
  },
  {
    key: "refusalAccuracy",
    label: "拒答正确率",
    desc: "资料外题正确拒答、资料内题未误拒的比例；与 Hit@K 分列",
    kind: "ratio",
  },
  { key: "retrievalMsAvg", label: "检索耗时", desc: "单题平均检索耗时", kind: "ms", lowerIsBetter: true },
  { key: "generationMsAvg", label: "生成耗时", desc: "单题平均生成耗时", kind: "ms", lowerIsBetter: true },
  { key: "latencyP50Ms", label: "耗时 p50", desc: "单题端到端耗时中位数", kind: "ms", lowerIsBetter: true },
  { key: "latencyP95Ms", label: "耗时 p95", desc: "单题端到端耗时 95 分位", kind: "ms", lowerIsBetter: true },
  { key: "latencyMaxMs", label: "耗时 max", desc: "最慢单题端到端耗时", kind: "ms", lowerIsBetter: true },
];

/** 表格里紧凑展示的列（避免首屏列过多）。 */
export const TABLE_METRIC_KEYS = [
  "hitAt1",
  "hitAt3",
  "hitAt5",
  "recallAt5",
  "mrr",
  "refusalAccuracy",
  "latencyP95Ms",
] as const;

export function metricValue(
  run: Pick<EvalRunSummary, "status">,
  metrics: Record<string, unknown> | null | undefined,
  key: string
): number | null {
  if (!metrics) return null;
  const v = metrics[key];
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

/** 比率 → 百分比字符串；null → "—"（未产出，绝不显示 0）。 */
export function fmtRatio(v: number | null | undefined): string {
  return v == null ? "—" : `${(v * 100).toFixed(1)}%`;
}

export function fmtMetricValue(meta: MetricMeta, v: number | null | undefined): string {
  if (v == null) return "—";
  if (meta.kind === "ratio") return fmtRatio(v);
  if (meta.kind === "ms") return `${Math.round(v)} ms`;
  return String(v);
}

export interface MetricDelta {
  /** 差值原文（pp 或 ms），含正负号。 */
  text: string;
  /** 该变化是否"更好"（比率升高为更好；耗时降低为更好）。 */
  better: boolean;
  /** 是否无变化（在展示精度内）。 */
  flat: boolean;
}

/**
 * 指标变化。
 *
 * 注意：耗时的"更好"方向与比率相反——调用方必须依据返回的 better 判断颜色，
 * 不要用差值的符号判断。箭头表示数值升降，颜色表示好坏，二者语义不同。
 */
export function metricDelta(
  meta: MetricMeta,
  base: number | null | undefined,
  exp: number | null | undefined
): MetricDelta | null {
  if (base == null || exp == null) return null;
  const diff = exp - base;
  const flat = meta.kind === "ms" ? Math.abs(diff) < 1 : Math.abs(diff) < 0.00005;
  const better = meta.lowerIsBetter ? diff < 0 : diff > 0;
  const text =
    meta.kind === "ms"
      ? `${diff > 0 ? "+" : ""}${Math.round(diff)} ms`
      : `${diff > 0 ? "+" : ""}${(diff * 100).toFixed(1)}pp`;
  return { text, better, flat };
}

/** 箭头：表示数值升降方向（与带符号数值一致）。 */
export function deltaArrow(d: MetricDelta): string {
  return d.flat ? "–" : d.text.startsWith("-") ? "▼" : "▲";
}

// ------------------------------------------------------------------
// 可比性与逐题对比
// ------------------------------------------------------------------

export interface RunConfigSnapshot {
  retrievalMode?: string;
  topK?: number;
  minScore?: number;
  rrfK?: number;
  candidateLimit?: number;
  rerankEnabled?: boolean;
  rerankModel?: string;
  refusalEnabled?: boolean;
  embeddingModel?: string;
  embeddingDimensions?: number;
  chunkStrategy?: string[];
  maxChunkChars?: (string | number)[];
  [k: string]: unknown;
}

export interface ComparabilityIssue {
  field: string;
  base: string;
  exp: string;
}

export interface ComparabilityResult {
  comparable: boolean;
  issues: ComparabilityIssue[];
  /** 检索配置是否完全相同（相同则指标变化必然为 0，避免误读为"没有提升"）。 */
  sameRetrievalConfig: boolean;
}

const MODE_LABEL: Record<string, string> = {
  VECTOR: "向量检索",
  HYBRID: "混合检索（BM25 + 向量 · RRF 融合）",
  HYBRID_RERANK: "混合检索 + 重排",
};

export function modeLabel(mode?: string): string {
  if (!mode) return "—";
  return MODE_LABEL[mode] ?? mode;
}

function sameJson(a: unknown, b: unknown): boolean {
  return JSON.stringify(a ?? null) === JSON.stringify(b ?? null);
}

/**
 * 可比性守卫（R2-C3）。
 *
 * 数据集版本 / 知识库 / embedding 模型或维度 / 语料指纹任一不同 → 不可比：
 * 这些差异会独立影响命中判定与指标口径，指标变化不能归因到检索配置。
 */
export function checkComparability(
  base: { datasetVersionId: string; kbId: string; configSnapshot?: RunConfigSnapshot | null },
  exp: { datasetVersionId: string; kbId: string; configSnapshot?: RunConfigSnapshot | null }
): ComparabilityResult {
  const a = base.configSnapshot ?? {};
  const b = exp.configSnapshot ?? {};
  const issues: ComparabilityIssue[] = [];
  if (base.datasetVersionId !== exp.datasetVersionId) {
    issues.push({
      field: "数据集版本",
      base: base.datasetVersionId,
      exp: exp.datasetVersionId,
    });
  }
  if (base.kbId !== exp.kbId) {
    issues.push({ field: "知识库", base: base.kbId, exp: exp.kbId });
  }
  if (!sameJson(a.embeddingModel, b.embeddingModel)) {
    issues.push({
      field: "embedding 模型",
      base: String(a.embeddingModel ?? "—"),
      exp: String(b.embeddingModel ?? "—"),
    });
  }
  if (!sameJson(a.embeddingDimensions, b.embeddingDimensions)) {
    issues.push({
      field: "向量维度",
      base: String(a.embeddingDimensions ?? "—"),
      exp: String(b.embeddingDimensions ?? "—"),
    });
  }
  if (!sameJson(a.chunkStrategy, b.chunkStrategy) || !sameJson(a.maxChunkChars, b.maxChunkChars)) {
    issues.push({
      field: "分块配置",
      base: `${(a.chunkStrategy ?? []).join("/") || "—"} · ${(a.maxChunkChars ?? []).join("/") || "—"}`,
      exp: `${(b.chunkStrategy ?? []).join("/") || "—"} · ${(b.maxChunkChars ?? []).join("/") || "—"}`,
    });
  }
  const sameRetrievalConfig =
    sameJson(a.retrievalMode, b.retrievalMode) &&
    sameJson(a.topK, b.topK) &&
    sameJson(a.minScore, b.minScore) &&
    sameJson(a.rrfK, b.rrfK) &&
    sameJson(a.candidateLimit, b.candidateLimit) &&
    sameJson(a.rerankEnabled, b.rerankEnabled) &&
    sameJson(a.refusalEnabled, b.refusalEnabled);
  return { comparable: issues.length === 0, issues, sameRetrievalConfig };
}

export interface ItemComparison {
  seq: number;
  question: string;
  category?: string | null;
  baseHit: boolean | null;
  expHit: boolean | null;
  baseRank: number | null;
  expRank: number | null;
  baseLatencyMs: number | null;
  expLatencyMs: number | null;
  /** 逐题判定：改善 / 退化 / 不变 / 不可比。 */
  verdict: "better" | "worse" | "flat" | "unknown";
}

/**
 * 逐题对比（按 seq 1:1 关联）。
 *
 * 同一数据集版本下 seq 与样本一一对应，因此可直接按 seq 关联，不需要服务端端点。
 * rank 取首个命中证据分块的排名（从 retrieved 中按 hit 判定推导不可行——
 * 服务端只在 item.hit 记录了是否命中，排名由第一个 passedThreshold 的命中近似），
 * 这里用"未命中记 null、命中记 evidence 命中位次无法获取"的保守口径：
 * 排名展示依赖 retrieved 中首个文档名匹配项，缺失则显示 —。
 */
export function compareItems(
  baseItems: { seq: number; question: string; category?: string | null; hit: boolean | null; latencyMs?: number | null }[],
  expItems: { seq: number; question: string; category?: string | null; hit: boolean | null; latencyMs?: number | null }[]
): { rows: ItemComparison[]; better: number; worse: number; flat: number } {
  const expBySeq = new Map(expItems.map((i) => [i.seq, i]));
  const rows: ItemComparison[] = baseItems.map((b) => {
    const e = expBySeq.get(b.seq);
    const baseHit = b.hit;
    const expHit = e ? e.hit : null;
    let verdict: ItemComparison["verdict"] = "unknown";
    if (baseHit != null && expHit != null) {
      if (baseHit === expHit) verdict = "flat";
      else verdict = expHit && !baseHit ? "better" : "worse";
    }
    return {
      seq: b.seq,
      question: b.question,
      category: b.category,
      baseHit,
      expHit,
      baseRank: null,
      expRank: null,
      baseLatencyMs: b.latencyMs ?? null,
      expLatencyMs: e?.latencyMs ?? null,
      verdict,
    };
  });
  return {
    rows,
    better: rows.filter((r) => r.verdict === "better").length,
    worse: rows.filter((r) => r.verdict === "worse").length,
    flat: rows.filter((r) => r.verdict === "flat").length,
  };
}

/**
 * 契约里 hit/latencyMs 为可选（undefined），对比逻辑统一按 null 处理：
 * undefined 与 null 在展示上都表示"未产出"，必须区分于 false（未命中）。
 */
export function normItem<
  T extends {
    seq: number;
    question: string;
    category?: string | null;
    hit?: boolean | null;
    latencyMs?: number | null;
  },
>(item: T): { seq: number; question: string; category?: string | null; hit: boolean | null; latencyMs?: number | null } {
  return {
    seq: item.seq,
    question: item.question,
    category: item.category ?? null,
    hit: item.hit ?? null,
    latencyMs: item.latencyMs ?? null,
  };
}
