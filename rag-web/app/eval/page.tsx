"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { Topbar } from "@/components/shell";
import { EmptyState, ErrorState, TableSkeleton } from "@/components/states";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Pagination } from "@/components/pagination";
import { listEvalDatasets, listEvalRuns } from "@/lib/api/client";
import type { EvalRunSummary } from "@/lib/api/types";
import { METRIC_META, TABLE_METRIC_KEYS, fmtMetricValue, modeLabel } from "@/lib/eval";
import { fmtDateTime } from "@/lib/format";

const RUN_STATUS_META: Record<
  EvalRunSummary["status"],
  { label: string; variant: "neutral" | "info" | "success" | "danger" }
> = {
  RUNNING: { label: "运行中", variant: "info" },
  COMPLETED: { label: "已完成", variant: "success" },
  FAILED: { label: "失败", variant: "danger" },
};

const PAGE_SIZE_DEFAULT = 10;

export default function EvalListPage() {
  const router = useRouter();
  const [datasetId, setDatasetId] = React.useState("");
  const [page, setPage] = React.useState(1);
  const [pageSize, setPageSize] = React.useState(PAGE_SIZE_DEFAULT);
  const [selected, setSelected] = React.useState<Set<string>>(new Set());

  const datasetsQuery = useQuery({
    queryKey: ["eval-datasets"],
    queryFn: listEvalDatasets,
  });
  const runsQuery = useQuery({
    queryKey: ["eval-runs", datasetId, page, pageSize],
    queryFn: () =>
      listEvalRuns({
        datasetId: datasetId || undefined,
        page,
        pageSize,
      }),
  });

  const runs = runsQuery.data?.items ?? [];
  const total = runsQuery.data?.total ?? 0;

  const toggle = (id: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const selectedIds = Array.from(selected);
  const canCompare = selectedIds.length === 2;
  const goCompare = () => {
    if (!canCompare) return;
    router.push(`/eval/${selectedIds[0]}?compare=${selectedIds[1]}`);
  };

  // 选中项在筛选/翻页后可能已不在当前页——提示而不是静默保留
  const selectionHint =
    selectedIds.length === 0
      ? "勾选表格中 2 次运行后，可对比同一数据集上的指标变化。"
      : selectedIds.length === 1
        ? "已选 1 次运行，还需再选 1 次才能对比。"
        : selectedIds.length === 2
          ? "已选 2 次运行，可开始对比。"
          : `已选 ${selectedIds.length} 次运行，请只保留 2 次再对比。`;

  return (
    <>
      <Topbar crumbs={[{ label: "效果评测" }]} />
      <div className="flex min-h-0 flex-1 flex-col px-6 pt-4">
        <div className="flex flex-none flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-[22px] font-semibold leading-8">效果评测</h1>
          </div>
        </div>

        <div className="mt-4 flex flex-none flex-wrap items-center gap-3">
          <label className="flex items-center gap-2 text-sm">
            <span className="text-muted-foreground">数据集</span>
            <select
              className="h-9 rounded-md border border-input bg-surface px-2.5 text-sm"
              value={datasetId}
              onChange={(e) => {
                setDatasetId(e.target.value);
                setPage(1);
              }}
              aria-label="数据集"
            >
              <option value="">全部数据集</option>
              {(datasetsQuery.data ?? []).map((d) => (
                <option key={d.id} value={d.id}>
                  {d.name}
                </option>
              ))}
            </select>
          </label>
          <span className="text-[13px] text-muted-foreground">{selectionHint}</span>
          <div className="ml-auto flex items-center gap-2">
            <Button variant="outline" disabled={!canCompare} onClick={goCompare}>
              对比选中运行
            </Button>
          </div>
        </div>

        <div className="mt-3 flex min-h-0 flex-1 flex-col">
          {runsQuery.isPending ? (
            <TableSkeleton rows={6} />
          ) : runsQuery.isError ? (
            <ErrorState
              title="运行列表加载失败"
              description="无法获取评测运行列表，请检查后端服务后重试。"
              onRetry={() => runsQuery.refetch()}
            />
          ) : total === 0 ? (
            <EmptyState
              title={datasetId ? "该数据集暂无运行" : "还没有评测运行"}
              description={
                datasetId
                  ? "当前筛选条件下没有运行记录，可切换数据集查看全部运行。"
                  : "评测运行需通过 API 发起（POST /api/v1/eval/runs）：导入数据集后指定数据集、知识库与检索参数。"
              }
            />
          ) : (
            <>
              <div className="min-h-0 flex-1 overflow-auto rounded-md border border-border bg-surface">
                <table className="w-full border-collapse text-sm">
                  <thead>
                    <tr className="border-b border-border text-left text-muted-foreground">
                      <th className="px-4 py-2.5 font-medium">运行时间</th>
                      <th className="px-3 py-2.5 font-medium">数据集版本</th>
                      <th className="px-3 py-2.5 font-medium">检索模式</th>
                      {TABLE_METRIC_KEYS.map((k) => {
                        const meta = METRIC_META.find((m) => m.key === k)!;
                        return (
                          <th
                            key={k}
                            className="px-3 py-2.5 text-right font-medium"
                            title={meta.desc}
                          >
                            {meta.label}
                          </th>
                        );
                      })}
                      <th className="px-3 py-2.5 font-medium">状态</th>
                      <th className="w-10 px-2 py-2.5" aria-label="选择" />
                    </tr>
                  </thead>
                  <tbody>
                    {runs.map((run) => {
                      const statusMeta = RUN_STATUS_META[run.status];
                      const metrics = (run as unknown as { metrics?: Record<string, unknown> }).metrics;
                      return (
                        <tr
                          key={run.runId}
                          className="border-b border-border last:border-0 hover:bg-muted/50"
                        >
                          <td className="px-4 py-3 align-top">
                            <Link
                              href={`/eval/${run.runId}`}
                              className="cell-main block font-medium hover:text-primary"
                            >
                              {fmtDateTime(run.createdAt)}
                            </Link>
                          </td>
                          <td className="px-3 py-3 align-top text-[13px] text-muted-foreground">
                            {run.datasetVersionId.slice(0, 8)}…
                          </td>
                          <td className="px-3 py-3 align-top whitespace-nowrap">
                            {modeLabel(
                              (run as unknown as { configSnapshot?: { retrievalMode?: string } })
                                .configSnapshot?.retrievalMode
                            )}
                          </td>
                          {TABLE_METRIC_KEYS.map((k) => {
                            const meta = METRIC_META.find((m) => m.key === k)!;
                            const v =
                              metrics && typeof metrics[k] === "number"
                                ? (metrics[k] as number)
                                : null;
                            return (
                              <td
                                key={k}
                                className="num px-3 py-3 text-right tabular-nums"
                                title={meta.desc}
                              >
                                {fmtMetricValue(meta, v)}
                              </td>
                            );
                          })}
                          <td className="px-3 py-3 align-top">
                            <Badge variant={statusMeta.variant}>{statusMeta.label}</Badge>
                            {run.status === "FAILED" && (
                              <div className="mt-1.5 text-[13px] leading-5 text-destructive">
                                {(run as unknown as { failureReason?: string }).failureReason ??
                                  "运行失败，原因未记录"}
                              </div>
                            )}
                          </td>
                          <td className="px-2 py-3 align-top">
                            <input
                              type="checkbox"
                              className="size-4 accent-[var(--primary)]"
                              aria-label={`选择运行 ${run.runId}`}
                              checked={selected.has(run.runId)}
                              onChange={() => toggle(run.runId)}
                            />
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
              <Pagination
                total={total}
                page={page}
                pageSize={pageSize}
                onPage={setPage}
                onPageSize={(s) => {
                  setPageSize(s);
                  setPage(1);
                }}
              />
            </>
          )}
        </div>
      </div>
    </>
  );
}
