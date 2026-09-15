"use client";

import * as React from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { ArrowLeft, MoreHorizontal, RotateCw } from "lucide-react";
import { Topbar } from "@/components/shell";
import { Pagination } from "@/components/pagination";
import {
  DocStatusBadge,
  EmptyState,
  ErrorAlert,
  ErrorState,
  InfoAlert,
  TableSkeleton,
} from "@/components/states";
import { Button, buttonVariants } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from "@/components/ui/tabs";
import {
  ApiError,
  deleteDocument,
  getDocument,
  getDocumentParsedText,
  getDocumentTask,
  listDocumentChunks,
  activateDocumentVersion,
  listDocumentVersions,
  retryDocumentIngestion,
} from "@/lib/api/client";
import type { DocumentDetail, PipelineStage } from "@/lib/api/types";
import {
  FILE_TYPE_LABEL,
  STAGE_LABEL,
  STAGE_SEQ,
  STRATEGY_META,
  fmtDateTime,
  fmtSize,
} from "@/lib/format";
import { cn } from "@/lib/utils";

const CHUNK_PAGE_SIZE = 10;

export default function DocDetailPage() {
  const params = useParams<{ kbId: string; docId: string }>();
  const { kbId, docId } = params;
  const queryClient = useQueryClient();

  const docQuery = useQuery({
    queryKey: ["document", docId],
    queryFn: () => getDocument(docId),
    retry: (count, err) =>
      !(err instanceof ApiError && err.status === 404) && count < 1,
  });
  const doc = docQuery.data;

  const [tab, setTab] = React.useState<"text" | "chunks" | "info">("text");
  const [chunkPage, setChunkPage] = React.useState(1);
  const [expanded, setExpanded] = React.useState<Record<string, boolean>>({});
  const [highlightChunkId, setHighlightChunkId] = React.useState<string | null>(
    null
  );
  const [deleteOpen, setDeleteOpen] = React.useState(false);

  // 处理中 → 轮询任务（2s），完成后刷新文档详情
  const taskQuery = useQuery({
    queryKey: ["document-task", docId],
    queryFn: () => getDocumentTask(docId),
    refetchInterval: (q) => {
      const st = q.state.data?.status;
      return st === "COMPLETED" || st === "FAILED" ? false : 2000;
    },
    enabled: !!doc && doc.status !== "COMPLETED" && doc.status !== "FAILED",
  });
  React.useEffect(() => {
    const st = taskQuery.data?.status;
    if ((st === "COMPLETED" || st === "FAILED") && doc?.status !== st) {
      queryClient.invalidateQueries({ queryKey: ["document", docId] });
      if (st === "COMPLETED") toast.success("文档处理完成");
    }
  }, [taskQuery.data, doc, docId, queryClient]);

  const parsedTextQuery = useQuery({
    queryKey: ["parsed-text", docId],
    queryFn: () => getDocumentParsedText(docId),
    enabled: doc?.status === "COMPLETED" && tab === "text",
  });

  const chunksQuery = useQuery({
    queryKey: ["chunks", docId, chunkPage],
    queryFn: () => listDocumentChunks(docId, { page: chunkPage, pageSize: CHUNK_PAGE_SIZE }),
    enabled: doc?.status === "COMPLETED" && tab === "chunks",
  });

  const retryMutation = useMutation({
    mutationFn: () => retryDocumentIngestion(docId),
    onSuccess: () => {
      toast.success("已重新提交处理任务");
      queryClient.invalidateQueries({ queryKey: ["document", docId] });
      queryClient.invalidateQueries({ queryKey: ["document-task", docId] });
    },
    onError: (err) =>
      toast.error(err instanceof Error ? err.message : "重试失败，请稍后重试"),
  });

  const deleteMutation = useMutation({
    mutationFn: () => deleteDocument(docId),
    onSuccess: () => {
      toast.success("文档已删除");
      queryClient.invalidateQueries({ queryKey: ["documents", kbId] });
      window.location.href = `/kb/${kbId}`;
    },
    onError: (err) =>
      toast.error(err instanceof Error ? err.message : "删除失败，请稍后重试"),
  });

  // R3-P3 版本管理：版本组 + 激活切换
  const versionsQuery = useQuery({
    queryKey: ["document-versions", docId],
    queryFn: () => listDocumentVersions(docId),
    enabled: (doc?.versionNo ?? 1) > 1 || (doc?.rootId != null && doc.rootId !== doc.id) || undefined,
    staleTime: 5_000,
  });
  const activateMutation = useMutation({
    mutationFn: (targetDocId: string) => activateDocumentVersion(targetDocId),
    onSuccess: (activated) => {
      toast.success(`已切换到 v${activated.versionNo}`);
      queryClient.invalidateQueries({ queryKey: ["document-versions", docId] });
      queryClient.invalidateQueries({ queryKey: ["documents", kbId] });
      if (activated.id !== docId) {
        // 切换到其它版本 → 跳转到该版本的详情页（各版本独立 docId/chunkId）
        window.location.href = `/kb/${kbId}/doc/${activated.id}`;
      } else {
        queryClient.invalidateQueries({ queryKey: ["document", docId] });
      }
    },
    onError: (err) =>
      toast.error(err instanceof Error ? err.message : "版本切换失败，请稍后重试"),
  });

  // ?chunk= 定位：切到分块 Tab、跳到所在页并高亮（seq → 页码）
  const locateApplied = React.useRef(false);
  React.useEffect(() => {
    if (locateApplied.current) return;
    if (doc?.status !== "COMPLETED" || !chunksQuery.data) return;
    locateApplied.current = true;
    const cp = new URLSearchParams(window.location.search).get("chunk");
    if (!cp) return;
    const m = cp.match(/-c(\d+)$/);
    if (!m) return;
    const seq = parseInt(m[1], 10);
    const page = Math.floor(seq / CHUNK_PAGE_SIZE) + 1;
    setTab("chunks");
    setChunkPage(page);
    setHighlightChunkId(cp);
    const timer = setTimeout(() => setHighlightChunkId(null), 2600);
    return () => clearTimeout(timer);
  }, [doc?.status, chunksQuery.data]);

  const notFound =
    docQuery.isError &&
    docQuery.error instanceof ApiError &&
    docQuery.error.status === 404;

  if (notFound) {
    return (
      <>
        <Topbar
          crumbs={[{ label: "知识库", href: "/kb" }, { label: "文档不存在" }]}
        />
        <main className="min-h-0 flex-1 overflow-y-auto">
          <div className="mx-auto max-w-[1280px] px-6 pt-6 max-md:px-4">
            <div className="rounded-lg border border-border bg-surface pb-8">
              <ErrorState
                title="未找到文档"
                description="该文档不存在或已被删除。"
              />
              <div className="flex justify-center gap-2">
                <Link href="/kb" className={buttonVariants({ variant: "outline" })}>
                  返回知识库列表
                </Link>
                <Link
                  href={`/kb/${kbId}`}
                  className={buttonVariants({ variant: "default" })}
                >
                  返回文档列表
                </Link>
              </div>
            </div>
          </div>
        </main>
      </>
    );
  }

  if (docQuery.isPending) {
    return (
      <>
        <Topbar
          crumbs={[{ label: "知识库", href: "/kb" }, { label: "文档详情" }]}
        />
        <main className="min-h-0 flex-1 overflow-y-auto">
          <div className="mx-auto max-w-[1280px] px-6 pt-6 max-md:px-4">
            <TableSkeleton rows={6} />
          </div>
        </main>
      </>
    );
  }

  if (docQuery.isError || !doc) {
    return (
      <>
        <Topbar
          crumbs={[{ label: "知识库", href: "/kb" }, { label: "文档详情" }]}
        />
        <main className="min-h-0 flex-1 overflow-y-auto">
          <ErrorState
            title="加载失败"
            description="文档详情加载失败，请稍后重试。"
            onRetry={() => docQuery.refetch()}
          />
        </main>
      </>
    );
  }

  const failed = doc.status === "FAILED";
  const task = doc.task ?? taskQuery.data;

  return (
    <>
      <Topbar
        crumbs={[
          { label: "知识库", href: "/kb" },
          { label: "文档列表", href: `/kb/${kbId}` },
          { label: doc.name },
        ]}
      />
      <main className="flex min-h-0 flex-1 flex-col">
        <div className="mx-auto flex min-h-0 w-full max-w-[1280px] flex-1 flex-col px-6 pt-6 max-md:px-4">
          {/* 头部：文档名 + 状态 + 失败原因 + 操作 */}
          <div className="mb-5 flex flex-wrap items-start justify-between gap-4">
            <div className="min-w-0">
              <Link
                href={`/kb/${kbId}`}
                className="mb-1 inline-flex items-center gap-1 text-[13px] text-muted-foreground hover:text-primary"
              >
                <ArrowLeft className="size-3.5" aria-hidden /> 返回文档列表
              </Link>
              <h1 className="truncate text-2xl-page font-semibold">{doc.name}</h1>
              <div className="mt-2 flex flex-wrap items-center gap-2.5">
                {doc.versionNo != null && doc.versionNo > 1 && (
                  <span
                    className={`inline-flex items-center rounded-full px-2 py-0.5 text-[12px] font-medium ${
                      doc.isActive
                        ? "bg-primary/10 text-primary"
                        : "bg-muted text-muted-foreground"
                    }`}
                  >
                    v{doc.versionNo}
                    {doc.isActive ? " · 使用中" : " · 历史版本"}
                  </span>
                )}
                <DocStatusBadge status={doc.status} />
                <span className="num text-[13px] text-muted-foreground">
                  {FILE_TYPE_LABEL[doc.fileType] ?? doc.fileType} ·{" "}
                  {fmtSize(doc.sizeBytes)} · 上传于 {fmtDateTime(doc.createdAt)}
                </span>
                {failed && (
                  <span className="text-[13px] font-medium text-destructive">
                    {doc.failureReason || task?.failureReason || "处理失败，原因未知"}
                  </span>
                )}
              </div>
            </div>
            <div className="flex items-center gap-2">
              {versionsQuery.data && versionsQuery.data.length > 1 && (
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button variant="outline">
                      版本 ({versionsQuery.data.length})
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end">
                    {versionsQuery.data.map((v) => (
                      <DropdownMenuItem
                        key={v.id}
                        disabled={v.isActive}
                        onClick={() => activateMutation.mutate(v.id)}
                      >
                        v{v.versionNo} · {fmtDateTime(v.createdAt)}
                        {v.isActive ? "（当前）" : ""}
                        {v.status === "FAILED" ? " · 失败" : ""}
                      </DropdownMenuItem>
                    ))}
                  </DropdownMenuContent>
                </DropdownMenu>
              )}
              {failed && (
                <Button
                  onClick={() => retryMutation.mutate()}
                  disabled={retryMutation.isPending}
                >
                  <RotateCw aria-hidden /> 重试
                </Button>
              )}
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="ghost" size="icon" aria-label="更多操作">
                    <MoreHorizontal className="size-4" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  <DropdownMenuItem danger onSelect={() => setDeleteOpen(true)}>
                    删除文档
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
          </div>

          <Tabs
            value={tab}
            onValueChange={(v) => setTab(v as typeof tab)}
            className="flex min-h-0 flex-1 flex-col"
          >
            <TabsList aria-label="文档内容视图">
              <TabsTrigger value="text">解析文本</TabsTrigger>
              <TabsTrigger value="chunks">分块预览</TabsTrigger>
              <TabsTrigger value="info">基本信息</TabsTrigger>
            </TabsList>

            {/* Tab 1：解析文本 */}
            <TabsContent value="text" className="min-h-0 flex-1 overflow-y-auto">
              {failed ? (
                <ErrorAlert
                  message={
                    <>
                      <b>
                        处理失败（
                        {STAGE_LABEL[(doc.failureStage ?? task?.failureStage ?? "PARSING") as PipelineStage]}阶段）
                      </b>
                      <div className="mt-2">
                        {doc.failureReason || task?.failureReason || "原因未获取"}
                      </div>
                      <div className="mt-2">可在页头点击「重试」重新提交处理任务。</div>
                    </>
                  }
                />
              ) : doc.status !== "COMPLETED" ? (
                <InfoAlert
                  message={
                    <>
                      <b>
                        {doc.status === "QUEUED"
                          ? "排队中，尚未开始处理。"
                          : `处理进行中，当前阶段：${STAGE_LABEL[doc.currentStage]}。`}
                      </b>
                      <div className="mt-2">处理完成后，此处展示清洗后的全文。</div>
                    </>
                  }
                />
              ) : parsedTextQuery.isPending ? (
                <TableSkeleton rows={6} />
              ) : parsedTextQuery.isError ? (
                <ErrorAlert message="解析文本加载失败，请稍后重试。" />
              ) : (parsedTextQuery.data?.content ?? "").length === 0 ? (
                <EmptyState
                  title="暂无解析文本"
                  description="该文档解析后没有可预览的文本内容。"
                />
              ) : (
                <>
                  <p className="mb-4 text-[13px] text-muted-foreground">
                    以下为清洗后的全文预览（共 {parsedTextQuery.data?.charCount} 字符
                    {parsedTextQuery.data?.truncated ? "，已截断展示" : ""}）。
                  </p>
                  <div className="max-w-[820px] whitespace-pre-wrap break-words text-[15px] leading-6">
                    {parsedTextQuery.data?.content}
                  </div>
                </>
              )}
            </TabsContent>

            {/* Tab 2：分块预览 */}
            <TabsContent
              value="chunks"
              className="flex min-h-0 flex-1 flex-col overflow-hidden"
            >
              {failed ? (
                <ErrorAlert message="处理失败，暂无分块结果。失败原因与重试入口见「解析文本」页签。" />
              ) : doc.status !== "COMPLETED" ? (
                <InfoAlert
                  message={
                    doc.status === "QUEUED"
                      ? "排队中，尚未生成分块。"
                      : `处理进行中（当前阶段：${STAGE_LABEL[doc.currentStage]}），完成后可在此预览分块结果。`
                  }
                />
              ) : (chunksQuery.data?.total ?? 0) === 0 && !chunksQuery.isPending ? (
                <EmptyState
                  title="暂无分块数据"
                  description="该文档尚未生成或没有可预览的分块。"
                />
              ) : (
                <>
                  <div className="min-h-0 flex-1 overflow-x-auto rounded-lg border border-border bg-surface">
                    {chunksQuery.isPending ? (
                      <TableSkeleton rows={5} />
                    ) : (
                      <table className="w-full min-w-[680px] text-left text-sm">
                        <thead>
                          <tr className="border-b border-border text-[13px] text-muted-foreground">
                            <th className="num w-[88px] px-4 py-3 font-medium">序号</th>
                            <th className="px-4 py-3 font-medium">标题路径</th>
                            <th className="num w-[64px] px-4 py-3 text-center font-medium">页码</th>
                            <th className="num w-[88px] px-4 py-3 text-right font-medium">字符数</th>
                            <th className="px-4 py-3 font-medium">内容摘要</th>
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-border">
                          {(chunksQuery.data?.items ?? []).flatMap((c) => {
                            const open = !!expanded[c.id];
                            const rows = [
                              <tr
                                key={c.id}
                                data-chunk={c.id}
                                className={cn(
                                  "cursor-pointer align-top transition-colors hover:bg-surface-subtle",
                                  highlightChunkId === c.id && "chunk-hl"
                                )}
                                aria-expanded={open}
                                onClick={() =>
                                  setExpanded((p) => ({ ...p, [c.id]: !p[c.id] }))
                                }
                              >
                                <td className="num px-4 py-3">{c.seq}</td>
                                <td className="max-w-[300px] px-4 py-3">
                                  <div className="truncate" title={c.titlePath}>
                                    {c.titlePath}
                                  </div>
                                </td>
                                <td className="num px-4 py-3 text-center">
                                  {c.page != null ? c.page : "—"}
                                </td>
                                <td className="num px-4 py-3 text-right">{c.charCount}</td>
                                <td className="px-4 py-3">
                                  <div className="truncate">
                                    {c.text.length > 64 ? c.text.slice(0, 64) + "…" : c.text}
                                  </div>
                                </td>
                              </tr>,
                            ];
                            if (open) {
                              rows.push(
                                <tr key={c.id + "-detail"} className="bg-surface-subtle">
                                  <td colSpan={5} className="px-4 py-3 pl-14">
                                    <div className="max-w-[860px] whitespace-pre-wrap break-words text-sm leading-[22px]">
                                      {c.text}
                                    </div>
                                  </td>
                                </tr>
                              );
                            }
                            return rows;
                          })}
                        </tbody>
                      </table>
                    )}
                  </div>
                  <Pagination
                    total={chunksQuery.data?.total ?? 0}
                    page={chunkPage}
                    pageSize={CHUNK_PAGE_SIZE}
                    onPage={setChunkPage}
                  />
                </>
              )}
            </TabsContent>

            {/* Tab 3：基本信息 */}
            <TabsContent value="info" className="min-h-0 flex-1 overflow-y-auto pb-8">
              <div className="max-w-[760px]">
                <section>
                  <h2 className="mb-3.5 text-base font-semibold">文件信息</h2>
                  <Dl
                    rows={[
                      ["文件哈希", <span key="h" className="break-all font-mono text-[13px]">{doc.contentSha256}</span>],
                      ["格式", FILE_TYPE_LABEL[doc.fileType] ?? doc.fileType],
                      ["大小", fmtSize(doc.sizeBytes)],
                      ["上传时间", fmtDateTime(doc.createdAt)],
                      ["所属知识库", <Link key="kb" href={`/kb/${kbId}`} className="text-primary hover:underline">{kbId}</Link>],
                      ["重试次数", `${Math.max(0, (task?.attempt ?? 1) - 1)} 次`],
                    ]}
                  />
                </section>
                <section className="mt-7">
                  <h2 className="mb-3.5 text-base font-semibold">分块策略</h2>
                  <Dl
                    rows={[
                      [
                        "策略",
                        <>
                          {STRATEGY_META[doc.chunkConfig.strategy].name}
                          <p className="mt-1 text-[13px] leading-5 text-muted-foreground">
                            {STRATEGY_META[doc.chunkConfig.strategy].note}
                          </p>
                        </>,
                      ],
                      ["单块最大字符数", `${doc.chunkConfig.maxLength}`],
                      ["相邻块重叠字符数", `${doc.chunkConfig.overlap}`],
                      [
                        "已生成分块",
                        doc.chunkCount > 0 ? `${doc.chunkCount} 块` : "—",
                      ],
                    ]}
                  />
                </section>
                <section className="mt-7">
                  <h2 className="mb-3.5 text-base font-semibold">入库任务记录</h2>
                  <StageRecord doc={doc} />
                </section>
              </div>
            </TabsContent>
          </Tabs>
        </div>
      </main>

      <Dialog open={deleteOpen} onOpenChange={setDeleteOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>删除文档</DialogTitle>
          </DialogHeader>
          <div className="text-sm leading-6">
            <p>
              将删除文档 <b>{doc.name}</b>，影响范围：
            </p>
            <ul className="mb-1 mt-2 list-disc space-y-1 pl-5">
              <li>
                该文档的 {doc.chunkCount > 0 ? `${doc.chunkCount} 个` : ""}分块及对应向量将从检索中移除；
              </li>
              <li>已有回答中引用该文档的分块将失效；</li>
              <li>解析文本与文件记录将被清除，此操作不可恢复。</li>
            </ul>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteOpen(false)}>
              取消
            </Button>
            <Button
              variant="destructive"
              disabled={deleteMutation.isPending}
              onClick={() => deleteMutation.mutate()}
            >
              {deleteMutation.isPending ? "删除中…" : "删除"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

function Dl({ rows }: { rows: [string, React.ReactNode][] }) {
  return (
    <dl className="grid grid-cols-[140px_1fr] gap-x-4 gap-y-3 max-md:grid-cols-[112px_1fr]">
      {rows.map(([k, v], i) => (
        <React.Fragment key={i}>
          <dt className="text-sm text-muted-foreground">{k}</dt>
          <dd className="min-w-0 break-words text-sm">{v}</dd>
        </React.Fragment>
      ))}
    </dl>
  );
}

/** 阶段记录：done / running / fail / pending（与原型 stageRecord 语义一致） */
function StageRecord({ doc }: { doc: DocumentDetail }) {
  const task = doc.task;
  const currentStage: PipelineStage | null =
    doc.status === "FAILED"
      ? (doc.failureStage ?? task?.failureStage ?? null)
      : doc.status === "COMPLETED"
        ? null
        : (task?.stage ?? doc.currentStage);
  const isRunning = doc.status === "QUEUED" || doc.status === "PROCESSING";

  return (
    <ol className="flex flex-col gap-3">
      {STAGE_SEQ.map(({ stage, name }) => {
        const stageIdx = STAGE_SEQ.findIndex((s) => s.stage === stage);
        const currentIdx = currentStage
          ? STAGE_SEQ.findIndex((s) => s.stage === currentStage)
          : -1;
        if (doc.status === "COMPLETED" || stageIdx < currentIdx) {
          return (
            <li key={stage} className="flex items-start gap-2.5 text-sm">
              <span className="w-[18px] flex-none text-center text-success">✓</span>
              <span>
                {name}
                {task?.finishedAt && doc.status === "COMPLETED" && (
                  <span className="num ml-3 text-[13px] text-muted-foreground">
                    {fmtDateTime(task.finishedAt)}
                  </span>
                )}
              </span>
            </li>
          );
        }
        if (doc.status === "FAILED" && stageIdx === currentIdx) {
          return (
            <li key={stage} className="flex items-start gap-2.5 text-sm text-destructive">
              <span className="w-[18px] flex-none text-center">✕</span>
              <span>
                {name} · 失败
                <p className="mt-0.5 font-normal leading-5">
                  {doc.failureReason || task?.failureReason || "原因未获取"}
                </p>
              </span>
            </li>
          );
        }
        if (isRunning && stageIdx === currentIdx) {
          return (
            <li key={stage} className="flex items-start gap-2.5 text-sm font-medium text-primary">
              <span className="w-[18px] flex-none text-center">·</span>
              <span>{name} · 进行中</span>
            </li>
          );
        }
        return (
          <li
            key={stage}
            className="flex items-start gap-2.5 text-sm text-muted-foreground"
          >
            <span className="w-[18px] flex-none text-center">·</span>
            <span>{name} · 等待前序阶段完成</span>
          </li>
        );
      })}
    </ol>
  );
}
