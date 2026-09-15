"use client";

import * as React from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { Topbar } from "@/components/shell";
import { ErrorState, TableSkeleton, WarningAlert } from "@/components/states";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Pagination } from "@/components/pagination";
import { getEvalRun, listEvalRuns } from "@/lib/api/client";
import type { EvalRunDetail, EvalRunItem } from "@/lib/api/types";
import {
  METRIC_META,
  checkComparability,
  compareItems,
  deltaArrow,
  fmtMetricValue,
  metricDelta,
  modeLabel,
  normItem,
  type RunConfigSnapshot,
} from "@/lib/eval";
import { fmtScore } from "@/lib/format";

const PAGE_SIZE = 20;

export default function EvalRunPage() {
  const params = useParams<{ runId: string }>();
  const router = useRouter();
  const runId = params.runId;

  const [compareId, setCompareId] = React.useState<string | null>(null);
  const [page, setPage] = React.useState(1);

  // ?compare=<runId> 进入对比模式（用 window 读取，避免 useSearchParams 的 Suspense 要求）
  React.useEffect(() => {
    const fromUrl = new URLSearchParams(window.location.search).get("compare");
    setCompareId(fromUrl);
  }, []);

  const baseQuery = useQuery({
    queryKey: ["eval-run", runId, page],
    queryFn: () => getEvalRun(runId, { page, pageSize: PAGE_SIZE }),
    enabled: !!runId,
  });
  const expQuery = useQuery({
    queryKey: ["eval-run", compareId, page],
    queryFn: () => getEvalRun(compareId!, { page, pageSize: PAGE_SIZE }),
    enabled: !!compareId,
  });
  const runsQuery = useQuery({
    queryKey: ["eval-runs", "for-compare"],
    queryFn: () => listEvalRuns({ page: 1, pageSize: 50 }),
  });

  if (baseQuery.isPending) {
    return (
      <>
        <Topbar crumbs={[{ label: "效果评测", href: "/eval" }, { label: "运行详情" }]} />
        <div className="px-6 pt-4">
          <TableSkeleton rows={6} />
        </div>
      </>
    );
  }
  if (baseQuery.isError || !baseQuery.data) {
    return (
      <>
        <Topbar crumbs={[{ label: "效果评测", href: "/eval" }, { label: "运行详情" }]} />
        <div className="px-6 pt-4">
          <ErrorState
            title="运行详情加载失败"
            description="找不到该评测运行，或后端服务不可用。"
            onRetry={() => baseQuery.refetch()}
          />
          <div className="mt-3">
            <Link className="text-sm text-primary hover:underline" href="/eval">
              返回运行列表
            </Link>
          </div>
        </div>
      </>
    );
  }

  const base = baseQuery.data;
  const exp = compareId ? expQuery.data : undefined;
  const comparing = !!compareId && !!exp;

  return (
    <>
      <Topbar
        crumbs={[
          { label: "效果评测", href: "/eval" },
          { label: comparing ? "运行对比" : "运行详情" },
        ]}
      />
      <div className="flex min-h-0 flex-1 flex-col px-6 pt-4">
        <div className="flex flex-none items-start justify-between gap-3">
          <div className="min-w-0">
            <Link className="text-[13px] text-primary hover:underline" href="/eval">
              ← 返回运行列表
            </Link>
            <h1 className="mt-1 text-[22px] font-semibold leading-8">
              {comparing ? "运行对比" : "运行详情"}
            </h1>
            <RunMeta base={base} exp={comparing ? exp : undefined} />
          </div>
          <div className="flex flex-none gap-2">
            {comparing ? (
              <Button
                variant="outline"
                onClick={() => {
                  setCompareId(null);
                  router.replace(`/eval/${runId}`);
                }}
              >
                退出对比
              </Button>
            ) : (
              <ComparePicker
                runs={(runsQuery.data?.items ?? []).filter((r) => r.runId !== runId)}
                onPick={(id) => {
                  setCompareId(id);
                  router.replace(`/eval/${runId}?compare=${id}`);
                }}
              />
            )}
          </div>
        </div>

        <div className="mt-4 min-h-0 flex-1 overflow-auto pb-2">
          {comparing ? (
            <CompareView base={base} exp={exp!} page={page} onPage={setPage} />
          ) : (
            <SingleView base={base} page={page} onPage={setPage} />
          )}
        </div>
      </div>
    </>
  );
}

function RunMeta({ base, exp }: { base: EvalRunDetail; exp?: EvalRunDetail }) {
  const mode = (r: EvalRunDetail) =>
    modeLabel((r.configSnapshot as RunConfigSnapshot | undefined)?.retrievalMode);
  return (
    <div className="text-sm text-muted-foreground">
      {exp ? (
        <>
          基线 {mode(base)} → 实验 {mode(exp)}
        </>
      ) : (
        <>检索模式：{mode(base)}</>
      )}
    </div>
  );
}

function ComparePicker({
  runs,
  onPick,
}: {
  runs: { runId: string; configSnapshot?: unknown }[];
  onPick: (id: string) => void;
}) {
  const [value, setValue] = React.useState("");
  return (
    <div className="flex items-center gap-2">
      <select
        className="h-9 rounded-md border border-input bg-surface px-2.5 text-sm"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        aria-label="选择对比运行"
      >
        <option value="">选择另一次运行…</option>
        {runs.map((r) => (
          <option key={r.runId} value={r.runId}>
            {r.runId.slice(0, 8)}… ·{" "}
            {modeLabel((r.configSnapshot as RunConfigSnapshot | undefined)?.retrievalMode)}
          </option>
        ))}
      </select>
      <Button variant="outline" disabled={!value} onClick={() => value && onPick(value)}>
        与另一次运行对比
      </Button>
    </div>
  );
}

// ------------------------------------------------------------------
// 指标栏
// ------------------------------------------------------------------

function MetricBar({
  metrics,
  expMetrics,
}: {
  metrics: Record<string, unknown> | null | undefined;
  expMetrics?: Record<string, unknown> | null;
}) {
  const comparing = expMetrics !== undefined;
  return (
    <div className="flex flex-wrap gap-x-8 gap-y-4">
      {METRIC_META.map((meta) => {
        const bv = num(metrics, meta.key);
        const ev = comparing ? num(expMetrics, meta.key) : null;
        const delta = comparing ? metricDelta(meta, bv, ev) : null;
        return (
          <div key={meta.key} className="min-w-[112px]">
            <div className="text-[13px] text-muted-foreground" title={meta.desc}>
              {meta.label}
            </div>
            {comparing ? (
              <>
                <div className="num text-base tabular-nums">
                  {fmtMetricValue(meta, bv)} <span className="text-muted-foreground">→</span>{" "}
                  {fmtMetricValue(meta, ev)}
                </div>
                {delta ? (
                  <div
                    className={
                      delta.flat
                        ? "text-[13px] text-muted-foreground"
                        : delta.better
                          ? "text-[13px] text-success"
                          : "text-[13px] text-destructive"
                    }
                  >
                    {deltaArrow(delta)} {delta.text}
                  </div>
                ) : (
                  <div className="text-[13px] text-muted-foreground">—</div>
                )}
              </>
            ) : (
              <div className="num text-xl font-semibold tabular-nums">
                {fmtMetricValue(meta, bv)}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

function num(metrics: Record<string, unknown> | null | undefined, key: string): number | null {
  if (!metrics) return null;
  const v = metrics[key];
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

// ------------------------------------------------------------------
// 单次运行
// ------------------------------------------------------------------

function SingleView({
  base,
  page,
  onPage,
}: {
  base: EvalRunDetail;
  page: number;
  onPage: (p: number) => void;
}) {
  const metrics = (base as unknown as { metrics?: Record<string, unknown> }).metrics;
  const config = (base.configSnapshot ?? {}) as RunConfigSnapshot;
  const rankDist = metrics?.rankDistribution as Record<string, number> | undefined;

  return (
    <div className="space-y-6">
      <Section title="运行指标" hint="由逐题明细派生，与逐题表一致">
        <MetricBar metrics={metrics} />
      </Section>

      {rankDist && (
        <Section title="正确答案排名分布" hint="仅统计可回答题（分块级锚点判定）">
          <div className="flex flex-wrap items-center gap-4 text-sm">
            {["1", "2", "3", "4", "5"].map((k) => (
              <span key={k} className="num">
                <span className="text-muted-foreground">第{k}名</span>{" "}
                <span className="font-semibold tabular-nums">{rankDist[k] ?? 0}</span>
              </span>
            ))}
            <span className="num">
              <span className="text-muted-foreground">未召回</span>{" "}
              <span className="font-semibold tabular-nums text-destructive">
                {rankDist.notFound ?? 0}
              </span>
            </span>
          </div>
        </Section>
      )}

      <Section title="配置快照" hint="本次运行实际使用的配置，运行后不可变">
        <ConfigSnapshot config={config} />
      </Section>

      <Section title="逐题明细" hint="点击行展开回答与引用">
        <ItemTable items={base.items.items} />
        <Pagination total={base.items.total} page={page} pageSize={base.items.pageSize} onPage={onPage} />
      </Section>
    </div>
  );
}

// ------------------------------------------------------------------
// 两次对比
// ------------------------------------------------------------------

function CompareView({
  base,
  exp,
  page,
  onPage,
}: {
  base: EvalRunDetail;
  exp: EvalRunDetail;
  page: number;
  onPage: (p: number) => void;
}) {
  const guard = checkComparability(
    { datasetVersionId: base.datasetVersionId, kbId: base.kbId, configSnapshot: base.configSnapshot as RunConfigSnapshot },
    { datasetVersionId: exp.datasetVersionId, kbId: exp.kbId, configSnapshot: exp.configSnapshot as RunConfigSnapshot }
  );
  const baseMetrics = (base as unknown as { metrics?: Record<string, unknown> }).metrics;
  const expMetrics = (exp as unknown as { metrics?: Record<string, unknown> }).metrics;
  const diff = compareItems(
    base.items.items.map(normItem),
    exp.items.items.map(normItem)
  );

  return (
    <div className="space-y-6">
      {!guard.comparable ? (
        <WarningAlert
          message={
            <div className="space-y-1">
              <div className="font-medium">
                两次运行不可比：指标差异不能归因到检索配置
              </div>
              <ul className="list-disc pl-5">
                {guard.issues.map((i) => (
                  <li key={i.field}>
                    {i.field}：基线 {i.base} → 实验 {i.exp}
                  </li>
                ))}
              </ul>
              <div>以上差异会独立影响命中判定与指标口径，请先统一这些条件再解读变化。</div>
            </div>
          }
        />
      ) : guard.sameRetrievalConfig ? (
        <WarningAlert
          message={
            <div>
              <span className="font-medium">两次运行的检索配置完全相同。</span>
              指标无变化只会反映随机波动，不能解读为「没有提升」——请确认是否漏改了配置。
            </div>
          }
        />
      ) : (
        <div className="flex items-start gap-2.5 rounded-md border border-success/30 bg-success-bg px-3.5 py-3 text-sm leading-5">
          <span className="min-w-0">
            两次运行可比：数据集版本、知识库、embedding 与分块配置一致，仅检索配置不同，
            指标差异可归因到配置。
          </span>
        </div>
      )}

      <Section title="指标变化" hint="基线（较早的运行）→ 实验；耗时类指标下降为改善">
        <MetricBar metrics={baseMetrics} expMetrics={expMetrics} />
      </Section>

      <Section title="逐题变化" hint="两次运行按题号 1:1 关联">
        <div className="flex flex-wrap items-center gap-5 text-sm">
          <span className="num">
            <span className="text-success">变好</span>{" "}
            <span className="font-semibold tabular-nums">{diff.better}</span> 题
          </span>
          <span className="num">
            <span className="text-destructive">变差</span>{" "}
            <span className="font-semibold tabular-nums">{diff.worse}</span> 题
          </span>
          <span className="num">
            <span className="text-muted-foreground">不变</span>{" "}
            <span className="font-semibold tabular-nums">{diff.flat}</span> 题
          </span>
        </div>
        <div className="mt-4">
          <CompareTable rows={diff.rows} />
        </div>
        <Pagination total={base.items.total} page={page} pageSize={base.items.pageSize} onPage={onPage} />
      </Section>
    </div>
  );
}

function CompareTable({ rows }: { rows: ReturnType<typeof compareItems>["rows"] }) {
  const ordered = [...rows].sort((a, b) => {
    const rank = (v: string) => (v === "worse" ? 0 : v === "better" ? 1 : v === "flat" ? 2 : 3);
    return rank(a.verdict) - rank(b.verdict) || a.seq - b.seq;
  });
  return (
    <div className="overflow-auto rounded-md border border-border bg-surface">
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="border-b border-border text-left text-muted-foreground">
            <th className="px-3 py-2.5 font-medium">#</th>
            <th className="px-3 py-2.5 font-medium">问题</th>
            <th className="px-3 py-2.5 font-medium">类别</th>
            <th className="px-3 py-2.5 font-medium">基线命中</th>
            <th className="px-3 py-2.5 font-medium">基线耗时</th>
            <th className="px-3 py-2.5 font-medium">实验命中</th>
            <th className="px-3 py-2.5 font-medium">实验耗时</th>
            <th className="px-3 py-2.5 font-medium">变化</th>
          </tr>
        </thead>
        <tbody>
          {ordered.map((r) => (
            <tr key={r.seq} className="border-b border-border last:border-0">
              <td className="num px-3 py-2.5 tabular-nums">{r.seq}</td>
              <td className="px-3 py-2.5">{r.question}</td>
              <td className="px-3 py-2.5 text-[13px] text-muted-foreground">
                {CATEGORY_LABEL[r.category ?? ""] ?? "—"}
              </td>
              <td className="px-3 py-2.5">{hitLabel(r.baseHit)}</td>
              <td className="num px-3 py-2.5 tabular-nums">{msLabel(r.baseLatencyMs)}</td>
              <td className="px-3 py-2.5">{hitLabel(r.expHit)}</td>
              <td className="num px-3 py-2.5 tabular-nums">{msLabel(r.expLatencyMs)}</td>
              <td className="px-3 py-2.5">
                {r.verdict === "better" ? (
                  <span className="text-success">▲ 救回（未命中 → 命中）</span>
                ) : r.verdict === "worse" ? (
                  <span className="text-destructive">▼ 丢失（命中 → 未命中）</span>
                ) : r.verdict === "flat" ? (
                  <span className="text-muted-foreground">– 不变</span>
                ) : (
                  <span className="text-muted-foreground">— 不可比</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

const CATEGORY_LABEL: Record<string, string> = {
  DIRECT: "直接问答",
  TERM_VARIATION: "术语变体",
  FOLLOW_UP: "追问",
  OUT_OF_KB: "资料外",
  CONFUSABLE: "易混问题",
};

function hitLabel(hit: boolean | null): string {
  if (hit == null) return "—";
  return hit ? "命中" : "未命中";
}

function msLabel(v: number | null): string {
  return v == null ? "—" : `${v} ms`;
}

// ------------------------------------------------------------------
// 逐题表（单次）
// ------------------------------------------------------------------

function ItemTable({ items }: { items: EvalRunItem[] }) {
  const [open, setOpen] = React.useState<number | null>(null);
  return (
    <div className="overflow-auto rounded-md border border-border bg-surface">
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="border-b border-border text-left text-muted-foreground">
            <th className="w-10 px-3 py-2.5 font-medium">#</th>
            <th className="px-3 py-2.5 font-medium">问题</th>
            <th className="px-3 py-2.5 font-medium">类别</th>
            <th className="px-3 py-2.5 font-medium">命中</th>
            <th className="px-3 py-2.5 font-medium">证据排名</th>
            <th className="px-3 py-2.5 font-medium">耗时（检索/生成）</th>
            <th className="px-3 py-2.5 font-medium">人工标注</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => {
            const openThis = open === item.seq;
            const extra = item as unknown as {
              evidenceRank?: number | null;
              retrievalMs?: number | null;
              generationMs?: number | null;
            };
            return (
              <React.Fragment key={item.id}>
                <tr
                  className="cursor-pointer border-b border-border last:border-0 hover:bg-muted/50"
                  onClick={() => setOpen(openThis ? null : item.seq)}
                >
                  <td className="num px-3 py-2.5 tabular-nums">{item.seq}</td>
                  <td className="px-3 py-2.5">
                    <span className="mr-1.5 text-muted-foreground">{openThis ? "▼" : "▶"}</span>
                    {item.question}
                  </td>
                  <td className="px-3 py-2.5 text-[13px] text-muted-foreground">
                    {CATEGORY_LABEL[item.category ?? ""] ?? "—"}
                  </td>
                  <td className="px-3 py-2.5">{hitLabel(item.hit ?? null)}</td>
                  <td className="num px-3 py-2.5 tabular-nums">
                    {extra.evidenceRank ? `#${extra.evidenceRank}` : "—"}
                  </td>
                  <td className="num px-3 py-2.5 tabular-nums">
                    {msLabel(item.latencyMs ?? null)}
                    {extra.retrievalMs != null || extra.generationMs != null ? (
                      <span className="ml-1 text-[13px] text-muted-foreground">
                        （{extra.retrievalMs ?? "—"}/{extra.generationMs ?? "—"}）
                      </span>
                    ) : null}
                  </td>
                  <td className="px-3 py-2.5 text-[13px]">
                    {item.reviewTag ? (
                      <Badge variant="neutral">{REVIEW_LABEL[item.reviewTag] ?? item.reviewTag}</Badge>
                    ) : (
                      <span className="text-muted-foreground">未标注</span>
                    )}
                  </td>
                </tr>
                {openThis && (
                  <tr className="border-b border-border bg-muted/30 last:border-0">
                    <td />
                    <td colSpan={6} className="px-3 py-3">
                      <div className="space-y-3">
                        {/* R2-A1：拒答时引用为空是显式状态，不显示为空白 */}
                        {(item.citations?.length ?? 0) === 0 && (
                          <div className="rounded-sm border border-border bg-surface-subtle px-3 py-2 text-sm leading-5">
                            <div className="font-medium">本次未采用任何引用</div>
                            <div className="text-muted-foreground">
                              若该题为资料外且回答为拒答，说明证据不足已按规则清空引用；
                              低相关命中可在「检索调试」页查看。
                            </div>
                          </div>
                        )}
                        {item.generatedAnswer && (
                          <div>
                            <div className="text-[13px] text-muted-foreground">回答</div>
                            <div className="whitespace-pre-wrap text-sm leading-6">
                              {item.generatedAnswer}
                            </div>
                          </div>
                        )}
                        {(item.citations?.length ?? 0) > 0 && (
                          <div>
                            <div className="text-[13px] text-muted-foreground">引用</div>
                            <ul className="space-y-1 text-sm">
                              {item.citations!.map((c) => (
                                <li key={c.chunkId}>
                                  {c.docName} · {c.titlePath} ·{" "}
                                  <span className="num tabular-nums">{fmtScore(c.score)}</span>
                                </li>
                              ))}
                            </ul>
                          </div>
                        )}
                        <div>
                          <div className="text-[13px] text-muted-foreground">检索命中与分阶段位次</div>
                          <ul className="mt-1 space-y-1">
                            {item.retrieved.map((h) => (
                              <li key={h.chunkId} className="flex flex-wrap items-center gap-2 text-sm">
                                <span className="num tabular-nums">#{h.rank}</span>
                                <span className="text-muted-foreground">{h.docName}</span>
                                <span>{h.titlePath}</span>
                                <span className="num tabular-nums">{fmtScore(h.score)}</span>
                                {h.passedThreshold === false && (
                                  <Badge variant="warning">未过阈值</Badge>
                                )}
                                <span className="text-[13px] text-muted-foreground">
                                  {stageSummary(h)}
                                </span>
                              </li>
                            ))}
                          </ul>
                        </div>
                      </div>
                    </td>
                  </tr>
                )}
              </React.Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

const REVIEW_LABEL: Record<string, string> = {
  OK: "正常",
  WRONG_ANSWER: "答案错误",
  MISSING_EVIDENCE: "证据缺失",
  WRONG_SOURCE: "来源错误",
  OTHER: "其他",
};

function stageSummary(h: EvalRunItem["retrieved"][number]): string {
  const parts: string[] = [];
  if (h.vectorRank != null) parts.push(`向量 #${h.vectorRank}`);
  if (h.bm25Rank != null) parts.push(`BM25 #${h.bm25Rank}`);
  if (h.fusedRank != null) parts.push(`融合 #${h.fusedRank}`);
  if (h.rerankRank != null) parts.push(`重排 #${h.rerankRank}`);
  return parts.length ? `${parts.join(" · ")}` : "";
}

// ------------------------------------------------------------------

function Section({
  title,
  hint,
  children,
}: {
  title: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <section>
      <div className="mb-3 flex flex-wrap items-baseline gap-x-3">
        <h2 className="text-base font-semibold">{title}</h2>
        {hint && <span className="text-[13px] text-muted-foreground">{hint}</span>}
      </div>
      <div className="space-y-4">{children}</div>
    </section>
  );
}

function ConfigSnapshot({ config }: { config: RunConfigSnapshot }) {
  const rows: [string, React.ReactNode][] = [
    ["检索模式", modeLabel(config.retrievalMode)],
    ["向量通道 topK", config.topK ?? "—"],
    ["各通道候选上限", config.candidateLimit ?? "—"],
    ["RRF k", config.rrfK ?? "—"],
    [
      "重排",
      config.rerankEnabled
        ? `启用 · ${config.rerankModel ?? "—"}`
        : "未启用",
    ],
    ["minScore", config.minScore ?? "—"],
    ["拒答规则", config.refusalEnabled ? "启用" : "关闭（仅对照用）"],
    ["embedding 模型", config.embeddingModel ?? "—"],
    ["向量维度", config.embeddingDimensions ?? "—"],
    ["分块策略", (config.chunkStrategy ?? []).join(" / ") || "—"],
    ["分块上限", (config.maxChunkChars ?? []).join(" / ") || "—"],
  ];
  return (
    <dl className="grid grid-cols-[160px_1fr] gap-x-4 gap-y-2 text-sm">
      {rows.map(([k, v]) => (
        <React.Fragment key={k}>
          <dt className="text-muted-foreground">{k}</dt>
          <dd>{v}</dd>
        </React.Fragment>
      ))}
    </dl>
  );
}
