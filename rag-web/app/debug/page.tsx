"use client";

import * as React from "react";
import Link from "next/link";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Topbar } from "@/components/shell";
import {
  EmptyState,
  ErrorState,
  TableSkeleton,
  WarningAlert,
} from "@/components/states";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";
import { ApiError, debugRetrieval, listDocuments, listKnowledgeBases } from "@/lib/api/client";
import type { DebugRetrievalResult, RetrievalMode } from "@/lib/api/types";
import { fmtMs, fmtScore } from "@/lib/format";
import { modeLabel } from "@/lib/eval";
import { SAMPLE_QUESTIONS } from "@/lib/samples";

export default function DebugPage() {
  const kbsQuery = useQuery({
    queryKey: ["knowledge-bases"],
    queryFn: listKnowledgeBases,
  });
  const kbs = kbsQuery.data ?? [];

  const [kbId, setKbId] = React.useState<string>("");
  const [question, setQuestion] = React.useState("");
  const [qError, setQError] = React.useState<string | null>(null);
  const [topKOverride, setTopKOverride] = React.useState("");
  const [minScoreOverride, setMinScoreOverride] = React.useState("");
  const [modeOverride, setModeOverride] = React.useState<"" | RetrievalMode>("");
  const [result, setResult] = React.useState<DebugRetrievalResult | null>(null);
  const [lastRequest, setLastRequest] = React.useState<{
    kbId: string;
    question: string;
    topK?: number;
    minScore?: number;
    mode?: RetrievalMode;
  } | null>(null);

  // 初始知识库：URL ?kb=（避免 useSearchParams 的 Suspense 要求，挂载后读取）
  React.useEffect(() => {
    if (kbId || kbsQuery.isPending || kbs.length === 0) return;
    const fromUrl = new URLSearchParams(window.location.search).get("kb");
    setKbId(kbs.some((k) => k.id === fromUrl) ? fromUrl! : kbs[0].id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kbsQuery.isPending, kbs.length]);

  const kb = kbs.find((k) => k.id === kbId) ?? null;
  const kbNoDocs = !!kb && kb.documentCount === 0;

  const runMutation = useMutation({
    mutationFn: (req: {
      kbId: string;
      question: string;
      topK?: number;
      minScore?: number;
      mode?: RetrievalMode;
    }) => {
      setLastRequest(req);
      return debugRetrieval({
        kbId: req.kbId,
        question: req.question,
        topK: req.topK,
        minScore: req.minScore,
        mode: req.mode,
      });
    },
    onSuccess: (data) => setResult(data),
  });

  const execute = (q: string, modeArg?: string) => {
    const trimmed = q.trim();
    if (!trimmed) {
      setQError("请输入要调试的问题");
      return;
    }
    if (!kbId || kbNoDocs) return;
    setQError(null);
    const topK = topKOverride.trim() === "" ? undefined : Number(topKOverride);
    const minScore =
      minScoreOverride.trim() === "" ? undefined : Number(minScoreOverride);
    const mode = (modeArg ?? modeOverride) || undefined;
    runMutation.mutate({
      kbId,
      question: trimmed,
      topK: Number.isFinite(topK) ? topK : undefined,
      minScore: Number.isFinite(minScore) ? minScore : undefined,
      mode: (mode || undefined) as RetrievalMode | undefined,
    });
    try {
      window.history.replaceState(
        null,
        "",
        `/debug?kb=${encodeURIComponent(kbId)}&q=${encodeURIComponent(trimmed)}`
      );
    } catch {
      // 忽略
    }
  };

  return (
    <>
      <Topbar crumbs={[{ label: "检索调试" }]} />
      <main className="min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto max-w-[1280px] px-6 pb-10 pt-6 max-md:px-4">
          <div className="mb-6">
            <h1 className="text-2xl-page font-semibold">检索调试</h1>
            <p className="mt-1 text-sm text-muted-foreground">
              排查召回与生成依据；日常提问请使用「知识问答」。
            </p>
          </div>

          {kbsQuery.isPending ? (
            <TableSkeleton rows={4} />
          ) : kbsQuery.isError ? (
            <ErrorState
              title="服务暂不可用"
              description="知识库列表加载失败，请稍后重试。"
              onRetry={() => kbsQuery.refetch()}
            />
          ) : kbs.length === 0 ? (
            <EmptyState
              title="还没有知识库"
              description="创建知识库并上传文档后，即可执行检索调试。"
              action={
                <Link
                  href="/kb"
                  className="inline-flex h-9 items-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground hover:bg-primary-hover"
                >
                  前往创建
                </Link>
              }
            />
          ) : (
            <>
              {/* 查询区 */}
              <section className="mb-6">
                <div className="flex flex-wrap items-start gap-3">
                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="kb-select">知识库</Label>
                    <Select
                      id="kb-select"
                      className="w-[220px]"
                      value={kbId}
                      onChange={(e) => {
                        setKbId(e.target.value);
                        setResult(null);
                        setLastRequest(null);
                      }}
                    >
                      {kbs.map((k) => (
                        <option key={k.id} value={k.id}>
                          {k.name}
                        </option>
                      ))}
                    </Select>
                  </div>
                  <div className="flex min-w-[260px] flex-1 flex-col gap-1.5">
                    <Label htmlFor="q-input">问题</Label>
                    <Input
                      id="q-input"
                      placeholder="输入要调试的问题，例如：支付回调超时一般怎么排查？"
                      autoComplete="off"
                      aria-invalid={!!qError}
                      value={question}
                      onChange={(e) => {
                        setQuestion(e.target.value);
                        if (e.target.value.trim()) setQError(null);
                      }}
                      onKeyDown={(e) => {
                        if (e.key === "Enter") execute(question);
                      }}
                    />
                    {qError && (
                      <p role="alert" className="text-[13px] text-destructive">
                        {qError}
                      </p>
                    )}
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="mode-select">检索模式</Label>
                    <Select
                      id="mode-select"
                      className="w-[210px]"
                      value={modeOverride}
                      onChange={(e) => {
                        setModeOverride(e.target.value as "" | RetrievalMode);
                        if (lastRequest) {
                          execute(lastRequest.question, e.target.value);
                        }
                      }}
                    >
                      <option value="">默认（服务端配置）</option>
                      <option value="VECTOR">向量检索</option>
                      <option value="HYBRID">混合检索（BM25 + 融合）</option>
                      <option value="HYBRID_RERANK">混合检索 + 重排</option>
                    </Select>
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="topk-input">topK 覆盖（可选）</Label>
                    <Input
                      id="topk-input"
                      className="w-[110px]"
                      inputMode="numeric"
                      placeholder="默认"
                      value={topKOverride}
                      onChange={(e) => setTopKOverride(e.target.value)}
                    />
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="minscore-input">minScore 覆盖（可选）</Label>
                    <Input
                      id="minscore-input"
                      className="w-[110px]"
                      inputMode="decimal"
                      placeholder="默认"
                      value={minScoreOverride}
                      onChange={(e) => setMinScoreOverride(e.target.value)}
                    />
                  </div>
                  <Button
                    className="mt-[26px]"
                    disabled={kbNoDocs || runMutation.isPending}
                    onClick={() => execute(question)}
                  >
                    {runMutation.isPending ? "执行中…" : "执行检索调试"}
                  </Button>
                </div>

                <div className="mt-3 flex flex-wrap items-center gap-2">
                  <span className="text-[13px] text-muted-foreground">示例问题：</span>
                  {SAMPLE_QUESTIONS.map((q) => (
                    <button
                      key={q}
                      type="button"
                      className="rounded-full border border-border bg-surface px-3 py-1 text-[13px] transition-colors hover:border-primary hover:bg-primary-subtle"
                      onClick={() => {
                        setQuestion(q);
                        setQError(null);
                        execute(q);
                      }}
                    >
                      {q}
                    </button>
                  ))}
                </div>

                {kbNoDocs && (
                  <div className="mt-3">
                    <WarningAlert message="该知识库暂无文档，无法执行检索调试。请先在「知识库」中上传文档并完成入库。" />
                  </div>
                )}
              </section>

              {/* 结果区 */}
              <div aria-live="polite">
                {runMutation.isPending ? (
                  <DebugSkeleton />
                ) : runMutation.isError ? (
                  <ErrorState
                    title={
                      runMutation.error instanceof ApiError &&
                      runMutation.error.code === "KB_NOT_FOUND"
                        ? "未找到该知识库"
                        : "检索服务不可用"
                    }
                    description={
                      runMutation.error instanceof Error
                        ? runMutation.error.message +
                          " 可点击重试再次执行。"
                        : "检索服务无响应，本次调试未执行。可点击重试再次执行。"
                    }
                    onRetry={() => lastRequest && runMutation.mutate(lastRequest)}
                  />
                ) : result ? (
                  <DebugResult result={result} kbId={kbId} />
                ) : (
                  <EmptyState
                    title="输入问题执行一次调试"
                    description="选择知识库并输入问题，执行后可查看命中分块、检索分数、各阶段耗时与实际送入模型的上下文。"
                  />
                )}
              </div>
            </>
          )}
        </div>
      </main>
    </>
  );
}

function DebugSkeleton() {
  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-center gap-2 text-[13px] text-muted-foreground">
        <span
          aria-hidden
          className="inline-block size-3 animate-spin rounded-full border-[1.5px] border-muted-foreground border-t-transparent"
        />
        正在执行检索…
      </div>
      <TableSkeleton rows={4} />
      <div className="grid gap-6 lg:grid-cols-[300px_1fr]">
        <TableSkeleton rows={4} />
        <TableSkeleton rows={5} />
      </div>
    </div>
  );
}

function DebugResult({ result, kbId }: { result: DebugRetrievalResult; kbId: string }) {
  const cfg = result.effectiveConfig;
  const hits = result.hits ?? [];
  const passed = hits.filter((h) => h.passedThreshold);
  const filtered = hits.filter((h) => !h.passedThreshold);

  // 契约 RetrievalHit 不含文档名（只有 chunk.docId），拉一次文档列表做 id→名称映射
  const docsQuery = useQuery({
    queryKey: ["documents", kbId, "name-map"],
    queryFn: () => listDocuments(kbId, { page: 1, pageSize: 100 }),
    staleTime: 30_000,
  });
  const docNameOf = (docId: string): string =>
    docsQuery.data?.items.find((d) => d.id === docId)?.name ?? docId;

  return (
    <div className="flex flex-col gap-6">
      {/* 问题线索警示 */}
      {result.issues.length > 0 && (
        <div className="flex flex-col gap-2">
          {result.issues.map((issue, i) => (
            <WarningAlert
              key={i}
              message={
                <>
                  <b>问题线索：</b>
                  {issue.message}
                </>
              }
            />
          ))}
        </div>
      )}

      {/* 生效配置只读带（R2：增补检索模式与融合/重排参数） */}
      <section className="rounded-lg border border-border bg-surface p-4">
        <h2 className="mb-3 text-base font-semibold">生效检索配置</h2>
        <div className="grid grid-cols-[repeat(auto-fit,minmax(180px,1fr))] gap-3 gap-x-6">
          {cfg.mode && <CfgItem label="检索模式" value={modeLabel(cfg.mode)} />}
          <CfgItem label="topK" value={String(cfg.topK)} />
          {cfg.candidateLimit != null && (
            <CfgItem label="各通道候选上限" value={String(cfg.candidateLimit)} />
          )}
          {cfg.rrfK != null && <CfgItem label="RRF k" value={String(cfg.rrfK)} />}
          {cfg.mode !== "VECTOR" && (
            <CfgItem
              label="重排"
              value={
                cfg.rerankEnabled
                  ? cfg.rerankDegraded
                    ? `已降级、未重排（${cfg.rerankModel}）`
                    : `启用（${cfg.rerankModel}）`
                  : "未启用"
              }
            />
          )}
          <CfgItem label="minScore" value={fmtScore(cfg.minScore)} />
          <CfgItem label="向量维度" value={String(cfg.dimensions)} />
          <CfgItem label="Embedding 模型" value={cfg.embeddingModel} />
          {cfg.chatModel && <CfgItem label="生成模型" value={cfg.chatModel} />}
        </div>
      </section>

      {/* 命中分块 */}
      <section>
        <div className="mb-2.5 flex flex-wrap items-baseline gap-3">
          <h2 className="text-base font-semibold">命中分块</h2>
          <span className="num text-[13px] text-muted-foreground">
            召回 {hits.length} 个候选（topK={cfg.topK}）· 通过阈值 {passed.length} 个
          </span>
        </div>
        {hits.length === 0 ? (
          <EmptyState
            title="未召回任何候选"
            description="知识库中没有返回候选，请更换问法或检查知识库范围。"
          />
        ) : passed.length === 0 ? (
          <>
            <EmptyState
              title="无候选通过阈值"
              description={`共召回 ${hits.length} 个候选，最高分 ${fmtScore(
                Math.max(...hits.map((h) => h.score))
              )} 低于当前阈值 ${fmtScore(cfg.minScore)}。可降低 minScore 或更换问法。`}
            />
            {filtered.length > 0 && (
              <details className="mt-3">
                <summary className="cursor-pointer text-sm text-muted-foreground hover:text-foreground">
                  查看被过滤的候选（{filtered.length} 条）
                </summary>
                <div className="mt-2 overflow-hidden rounded-lg border border-border bg-surface">
                  <HitsTable hits={filtered} kbId={kbId} docNameOf={docNameOf} />
                </div>
              </details>
            )}
          </>
        ) : (
          <div className="overflow-x-auto rounded-lg border border-border bg-surface">
            <HitsTable hits={hits} kbId={kbId} docNameOf={docNameOf} />
          </div>
        )}
      </section>

      {/* 耗时 + 上下文 */}
      <div className="grid items-start gap-6 lg:grid-cols-[300px_1fr]">
        <section>
          <h2 className="mb-2.5 text-base font-semibold">各阶段耗时</h2>
          <div className="rounded-lg border border-border bg-surface">
            <TimingRow label="向量化" value={result.timings.embedMs} />
            <TimingRow label="向量检索" value={result.timings.searchMs} />
            <TimingRow label="总检索耗时" value={result.timings.totalMs} last />
          </div>
        </section>
        <section>
          <h2 className="mb-2.5 text-base font-semibold">
            实际送入模型的上下文
            <span className="num ml-3 text-[13px] font-normal text-muted-foreground">
              {result.context.charCount} 字符 · {result.context.chunkIds.length} 块
            </span>
          </h2>
          <div className="rounded-lg border border-border bg-surface">
            <pre className="max-h-[300px] overflow-y-auto whitespace-pre-wrap break-words p-4 font-mono text-[13px] leading-5">
              {result.context.text || "（无候选通过阈值，未构造上下文）"}
            </pre>
          </div>
        </section>
      </div>
    </div>
  );
}

function CfgItem({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <span className="text-[13px] text-muted-foreground">{label}</span>
      <span className="num break-all text-sm">{value}</span>
    </div>
  );
}

function TimingRow({
  label,
  value,
  last,
}: {
  label: string;
  value: number | null | undefined;
  last?: boolean;
}) {
  return (
    <div
      className={`flex items-center justify-between gap-3 px-4 py-2.5 ${
        last ? "" : "border-b border-border"
      }`}
    >
      <span className="text-[13px] text-muted-foreground">{label}</span>
      <span className="num text-sm font-medium">{fmtMs(value)}</span>
    </div>
  );
}

function HitsTable({
  hits,
  kbId,
  docNameOf,
}: {
  hits: DebugRetrievalResult["hits"];
  kbId: string;
  docNameOf: (docId: string) => string;
}) {
  // R2-D1：阶段位次就地展开（不占用主表列宽，保证一屏证据链）
  const [openId, setOpenId] = React.useState<string | null>(null);
  return (
    <table className="w-full min-w-[860px] text-left text-sm">
      <thead>
        <tr className="border-b border-border text-[13px] text-muted-foreground">
          <th className="num w-[56px] px-4 py-3 text-right font-medium">排名</th>
          <th className="num w-[110px] px-4 py-3 text-right font-medium">检索分数</th>
          <th className="w-[250px] px-4 py-3 font-medium">文档 / 标题路径</th>
          <th className="w-[80px] whitespace-nowrap px-4 py-3 font-medium">页码</th>
          <th className="px-4 py-3 font-medium">分块内容摘要</th>
          <th className="w-[96px] px-4 py-3 text-right font-medium">操作</th>
        </tr>
      </thead>
      <tbody className="divide-y divide-border">
        {hits.map((h) => {
          const stages = h.stages ?? [];
          const open = openId === h.chunk.id;
          return (
            <React.Fragment key={h.chunk.id}>
              <tr className={h.passedThreshold ? "" : "opacity-55"}>
                <td className="num px-4 py-3 text-right">
                  {stages.length > 0 ? (
                    <button
                      type="button"
                      className="text-muted-foreground hover:text-foreground"
                      aria-expanded={open}
                      aria-label={`${open ? "收起" : "展开"}第 ${h.rank} 名的阶段位次`}
                      onClick={() => setOpenId(open ? null : h.chunk.id)}
                    >
                      {open ? "▼" : "▶"} {h.rank}
                    </button>
                  ) : (
                    h.rank
                  )}
                </td>
                <td className="num px-4 py-3 text-right">
                  {fmtScore(h.score)}
                  {!h.passedThreshold && (
                    <div className="mt-1">
                      <Badge variant="neutral">已过滤</Badge>
                    </div>
                  )}
                </td>
                <td className="px-4 py-3">
                  <div className="truncate font-medium" title={docNameOf(h.chunk.docId)}>
                    {docNameOf(h.chunk.docId)}
                  </div>
                  <div className="truncate text-muted-foreground" title={h.chunk.titlePath}>
                    {h.chunk.titlePath}
                  </div>
                </td>
                <td className="num whitespace-nowrap px-4 py-3">
                  {h.chunk.page != null ? `第 ${h.chunk.page} 页` : "—"}
                </td>
                <td className="px-4 py-3">
                  <div className="truncate-2 leading-5">{h.chunk.text}</div>
                </td>
                <td className="px-4 py-3 text-right">
                  <Link
                    href={`/kb/${kbId}/doc/${h.chunk.docId}?chunk=${encodeURIComponent(h.chunk.id)}`}
                    className="text-[13px] text-primary hover:underline"
                  >
                    查看分块
                  </Link>
                </td>
              </tr>
              {open && stages.length > 0 && (
                <tr className="bg-muted/30">
                  <td />
                  <td colSpan={5} className="px-4 py-3">
                    <div className="text-[13px] text-muted-foreground">
                      各阶段位次与分数（本次实际参与 {stages.map((s) => STAGE_NAME[s.stage] ?? s.stage).join(" / ")}）
                    </div>
                    <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1.5 text-sm">
                      {stages.map((s) => (
                        <span
                          key={s.stage}
                          className="num rounded-sm border border-border bg-surface px-2 py-0.5 tabular-nums"
                        >
                          {STAGE_NAME[s.stage] ?? s.stage} #{s.rank} · {fmtScore(s.score)}
                        </span>
                      ))}
                    </div>
                    {h.rankChangedReason && (
                      <div className="mt-2 text-[13px] leading-5">{h.rankChangedReason}</div>
                    )}
                  </td>
                </tr>
              )}
            </React.Fragment>
          );
        })}
      </tbody>
    </table>
  );
}

/** 阶段英文名 → 中文（与契约 RetrievalStageRank.stage 对应） */
const STAGE_NAME: Record<string, string> = {
  vector: "向量",
  bm25: "BM25",
  fused: "融合",
  rerank: "重排",
};
