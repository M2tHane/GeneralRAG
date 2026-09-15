"use client";

import * as React from "react";
import Link from "next/link";
import {
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { toast } from "sonner";
import { Plus, Trash2 } from "lucide-react";
import { Topbar } from "@/components/shell";
import {
  EmptyState,
  InfoAlert,
  TableSkeleton,
} from "@/components/states";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DrawerContent,
} from "@/components/ui/dialog";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import {
  ApiError,
  createSession,
  deleteSession,
  listDocumentChunks,
  listKnowledgeBases,
  listMessages,
  listSessions,
  qaCancel,
  streamQa,
} from "@/lib/api/client";
import type { Citation, Chunk, Message, Session } from "@/lib/api/types";
import { fmtDateTime } from "@/lib/format";
import { SAMPLE_QUESTIONS } from "@/lib/samples";
import { cn } from "@/lib/utils";

interface StageLine {
  text: string;
  state: "run" | "done" | "fail";
}

interface LiveState {
  question: string;
  clientRequestId: string;
  stageLines: StageLine[];
  answer: string;
  citations: Citation[] | null;
  stopped?: boolean;
  error?: string;
}

export default function ChatPage() {
  const queryClient = useQueryClient();

  const [kbId, setKbId] = React.useState<string | null>(null);
  const [sessionId, setSessionId] = React.useState<string | null>(null);
  const [live, setLive] = React.useState<LiveState | null>(null);
  const [pendingUser, setPendingUser] = React.useState<string | null>(null);
  const [input, setInput] = React.useState("");
  const [citation, setCitation] = React.useState<{
    citation: Citation;
    kbId: string;
  } | null>(null);
  const [deleting, setDeleting] = React.useState<Session | null>(null);
  const [sessDrawerOpen, setSessDrawerOpen] = React.useState(false);

  const abortRef = React.useRef<AbortController | null>(null);
  const liveRef = React.useRef<LiveState | null>(null);
  liveRef.current = live;
  const msgAreaRef = React.useRef<HTMLDivElement | null>(null);
  const stickRef = React.useRef(true);

  const kbsQuery = useQuery({
    queryKey: ["knowledge-bases"],
    queryFn: listKnowledgeBases,
  });
  const kbs = kbsQuery.data ?? [];

  // 初始知识库：URL ?kb=（避免 useSearchParams 的 Suspense 要求，挂载后读取）
  React.useEffect(() => {
    if (kbId || kbsQuery.isPending || kbs.length === 0) return;
    const fromUrl = new URLSearchParams(window.location.search).get("kb");
    const valid = kbs.some((k) => k.id === fromUrl) ? fromUrl : null;
    setKbId(valid ?? kbs[0].id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kbsQuery.isPending, kbs.length]);

  const sessionsQuery = useQuery({
    queryKey: ["sessions", kbId],
    queryFn: () => listSessions(kbId ?? undefined),
    enabled: !!kbId,
  });

  // 切换知识库后选中第一个会话
  React.useEffect(() => {
    if (!kbId || sessionsQuery.isPending) return;
    setSessionId((prev) => {
      if (prev && sessionsQuery.data?.some((s) => s.id === prev)) return prev;
      return sessionsQuery.data?.[0]?.id ?? null;
    });
  }, [kbId, sessionsQuery.data, sessionsQuery.isPending]);

  const session = sessionsQuery.data?.find((s) => s.id === sessionId) ?? null;
  const kb = kbs.find((k) => k.id === kbId) ?? null;

  const messagesQuery = useQuery({
    queryKey: ["messages", sessionId],
    queryFn: () => listMessages(sessionId!, { page: 1, pageSize: 100 }),
    enabled: !!sessionId,
  });
  const messages = messagesQuery.data?.items ?? [];

  // 流式输出/新消息时保持贴底（用户上滚查看历史时不打扰）
  React.useEffect(() => {
    const el = msgAreaRef.current;
    if (el && stickRef.current) el.scrollTop = el.scrollHeight;
  }, [messages.length, live?.answer, live?.stageLines.length, pendingUser]);

  // ---------------- 流式问答 ----------------

  const finishLive = React.useCallback(
    async (delayMs = 0) => {
      if (delayMs > 0) await new Promise((r) => setTimeout(r, delayMs));
      await queryClient.invalidateQueries({
        queryKey: ["messages", sessionId],
      });
      queryClient.invalidateQueries({ queryKey: ["sessions", kbId] });
      setLive(null);
      setPendingUser(null);
    },
    [queryClient, sessionId, kbId]
  );

  const runAsk = React.useCallback(
    async (question: string) => {
      if (liveRef.current) {
        toast.error("回答生成中，请先停止或等待完成");
        return;
      }
      if (!kbId) return;
      let sid = sessionId;
      if (!sid) {
        try {
          const created = await createSession({ kbId });
          sid = created.id;
          setSessionId(sid);
          queryClient.setQueryData(["sessions", kbId], (old?: Session[]) =>
            old ? [created, ...old] : [created]
          );
        } catch (err) {
          toast.error(err instanceof Error ? err.message : "创建会话失败");
          return;
        }
      }

      const clientRequestId =
        typeof crypto !== "undefined" && "randomUUID" in crypto
          ? crypto.randomUUID()
          : `${Date.now()}-${Math.random()}`;
      const controller = new AbortController();
      abortRef.current = controller;

      setLive({
        question,
        clientRequestId,
        stageLines: [],
        answer: "",
        citations: null,
      });

      const upStage = (fn: (lines: StageLine[]) => StageLine[]) =>
        setLive((l) => (l ? { ...l, stageLines: fn(l.stageLines) } : l));

      try {
        await streamQa(
          { kbId, sessionId: sid, question, clientRequestId },
          {
            onStage: (e) => {
              if (e.stage === "RETRIEVAL_STARTED") {
                upStage((ls) => [...ls, { text: "检索中…", state: "run" }]);
              } else if (e.stage === "RETRIEVAL_COMPLETED") {
                upStage((ls) =>
                  ls.map((line, i) =>
                    i === ls.length - 1 && line.state === "run"
                      ? {
                          text: `完成 · ${e.detail ?? "检索完成"}`,
                          state: "done",
                        }
                      : line
                  )
                );
              } else if (e.stage === "GENERATION_STARTED") {
                upStage((ls) => [...ls, { text: "生成中…", state: "run" }]);
              }
            },
            onToken: (e) =>
              setLive((l) => (l ? { ...l, answer: l.answer + e.delta } : l)),
            onCitations: (e) =>
              setLive((l) => (l ? { ...l, citations: e.citations } : l)),
            onDone: () => {
              void finishLive(200);
            },
            onError: (e) => {
              setLive((l) => (l ? { ...l, error: e.message } : l));
              void finishLive(800);
            },
            onCanceled: () => {
              setLive((l) => (l ? { ...l, stopped: true } : l));
              // 服务端需要片刻将 CANCELED 消息落库后再刷新
              void finishLive(1200);
            },
          },
          controller.signal
        );
      } catch (err) {
        // HTTP 层错误（409 STREAM_ALREADY_ACTIVE / 404）或网络异常：
        // 用户消息可能未持久化，保留现场错误块与重试入口
        const message =
          err instanceof ApiError
            ? err.message
            : "SSE 流中断：与生成服务的连接意外断开，已生成内容已保留，可重试。";
        setLive((l) =>
          l
            ? {
                ...l,
                error: message,
                stageLines: l.stageLines.map((line) =>
                  line.state === "run"
                    ? { ...line, state: "fail" as const }
                    : line
                ),
              }
            : l
        );
        setPendingUser(null);
      } finally {
        abortRef.current = null;
      }
    },
    [kbId, sessionId, queryClient, finishLive]
  );

  const send = React.useCallback(() => {
    if (liveRef.current) {
      toast.error("回答生成中，请先等待完成或点击停止");
      return;
    }
    const q = input.trim();
    if (!q) return;
    if (kb && kb.documentCount === 0) return;
    setInput("");
    setPendingUser(q);
    void runAsk(q);
  }, [input, kb, runAsk]);

  const stop = React.useCallback(() => {
    const l = liveRef.current;
    if (!l) return;
    abortRef.current?.abort();
    qaCancel({ clientRequestId: l.clientRequestId }).catch(() => {
      // 404 CANCEL_TARGET_NOT_FOUND（流已结束）等情况忽略
    });
    setLive((prev) => (prev ? { ...prev, stopped: true } : prev));
  }, []);

  // ---------------- 会话管理 ----------------

  const newSession = async () => {
    if (liveRef.current) {
      toast.error("回答生成中，请先停止或等待完成");
      return;
    }
    if (!kbId || !kb) return;
    if (kb.documentCount === 0) {
      toast.error("该知识库暂无文档，请先上传文档");
      return;
    }
    try {
      const created = await createSession({ kbId });
      queryClient.setQueryData(["sessions", kbId], (old?: Session[]) =>
        old ? [created, ...old] : [created]
      );
      setSessionId(created.id);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "创建会话失败");
    }
  };

  const changeKb = (id: string) => {
    if (liveRef.current) {
      toast.error("回答生成中，请先停止或等待完成");
      return;
    }
    setKbId(id);
    setSessionId(null);
    setPendingUser(null);
    try {
      window.history.replaceState(null, "", `/chat?kb=${encodeURIComponent(id)}`);
    } catch {
      // 忽略
    }
  };

  const changeSession = (id: string) => {
    if (liveRef.current) {
      toast.error("回答生成中，请先停止或等待完成");
      return;
    }
    setSessionId(id);
  };

  // ---------------- 渲染 ----------------

  const kbNoDocs = !!kb && kb.documentCount === 0;
  const streaming = !!live;

  return (
    <>
      <Topbar
        crumbs={[{ label: "知识问答" }]}
        extra={
          <div className="flex items-center gap-2">
            <label htmlFor="kb-select" className="hidden text-[13px] text-muted-foreground sm:inline">
              知识库
            </label>
            <Select
              id="kb-select"
              aria-label="选择知识库"
              className="h-8 w-auto min-w-[180px] max-w-[260px]"
              value={kbId ?? ""}
              onChange={(e) => changeKb(e.target.value)}
            >
              {kbs.map((k) => (
                <option key={k.id} value={k.id}>
                  {k.name}
                  {k.documentCount === 0 ? "（暂无文档）" : ""}
                </option>
              ))}
            </Select>
          </div>
        }
      />

      <div className="flex min-h-0 flex-1">
        {/* 会话列表（Master，桌面常驻 / 窄屏抽屉） */}
        <aside
          aria-label="会话列表"
          className="hidden w-[264px] flex-none flex-col border-r border-border bg-surface-subtle lg:flex"
        >
          <div className="flex flex-none items-center justify-between px-4 pb-2 pt-3">
            <span className="text-sm font-semibold">会话</span>
            <Button size="sm" onClick={() => void newSession()}>
              <Plus aria-hidden /> 新建会话
            </Button>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-3">
            <SessionsList
              loading={sessionsQuery.isPending}
              sessions={sessionsQuery.data ?? []}
              activeId={sessionId}
              onSwitch={changeSession}
              onDelete={(s) => {
                if (liveRef.current) {
                  toast.error("回答生成中，请先停止或等待完成");
                  return;
                }
                setDeleting(s);
              }}
            />
          </div>
        </aside>

        {/* 对话区（Detail） */}
        <section className="flex min-w-0 flex-1 flex-col bg-background">
          <div className="flex h-12 flex-none items-center gap-2.5 border-b border-border bg-surface px-5">
            <Button
              variant="outline"
              size="sm"
              className="lg:hidden"
              onClick={() => setSessDrawerOpen(true)}
              aria-label="展开会话列表"
            >
              会话
            </Button>
            <div className="min-w-0 truncate text-[15px] font-semibold">
              {session?.title ?? (kb ? kb.name : "")}
            </div>
            {messages.length > 0 && (
              <div className="num flex-none text-[13px] text-muted-foreground">
                {messages.length + (pendingUser ? 1 : 0)} 条消息
              </div>
            )}
          </div>

          <div
            className="min-h-0 flex-1 overflow-y-auto px-6 py-5 max-md:px-4"
            ref={msgAreaRef}
            onScroll={(e) => {
              const el = e.currentTarget;
              stickRef.current =
                el.scrollHeight - el.scrollTop - el.clientHeight < 48;
            }}
          >
            {kbNoDocs ? (
              <div className="flex h-full items-center justify-center">
                <EmptyState
                  title="该知识库还没有文档"
                  description={`「${kb?.name}」暂无可检索内容，请先上传文档，完成解析入库后即可提问。`}
                  action={
                    <Link
                      href={`/kb/${kbId}`}
                      className="inline-flex h-9 items-center justify-center gap-1.5 whitespace-nowrap rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary-hover"
                    >
                      前往上传文档
                    </Link>
                  }
                />
              </div>
            ) : messagesQuery.isPending ? (
              <div className="mx-auto flex max-w-[900px] flex-col gap-4" aria-hidden>
                <Skeleton className="ml-auto h-10 w-1/3 rounded-lg" />
                <Skeleton className="h-24 w-3/4 rounded-lg" />
                <Skeleton className="ml-auto h-10 w-1/2 rounded-lg" />
              </div>
            ) : messages.length === 0 && !live && !pendingUser ? (
              <div className="flex h-full items-center justify-center">
                <div className="max-w-[560px] px-6 text-center">
                  <div className="text-base font-semibold">
                    {session ? "开始新的会话" : "当前知识库暂无会话"}
                  </div>
                  <div className="mt-1.5 text-sm leading-5 text-muted-foreground">
                    提问将只在「{kb?.name}」范围内检索并回答，回答附带来源引用。
                  </div>
                  <div className="mt-4 flex flex-wrap justify-center gap-2">
                    {SAMPLE_QUESTIONS.map((q) => (
                      <button
                        key={q}
                        type="button"
                        className="rounded-full border border-border bg-surface px-3.5 py-1.5 text-[13px] transition-colors hover:border-primary hover:text-primary"
                        onClick={() => {
                          if (liveRef.current) {
                            toast.error("回答生成中，请先停止或等待完成");
                            return;
                          }
                          setInput("");
                          setPendingUser(q);
                          void runAsk(q);
                        }}
                      >
                        {q}
                      </button>
                    ))}
                  </div>
                </div>
              </div>
            ) : (
              <div className="mx-auto flex max-w-[900px] flex-col gap-4">
                {messages.map((m, i) => (
                  <MessageRow
                    key={m.id}
                    message={m}
                    question={findQuestion(messages, i)}
                    onReask={(q) => {
                      if (liveRef.current) {
                        toast.error("回答生成中，请先停止或等待完成");
                        return;
                      }
                      setPendingUser(q);
                      void runAsk(q);
                    }}
                    onOpenCitation={(c) =>
                      setCitation({ citation: c, kbId: session?.kbId ?? kbId! })
                    }
                  />
                ))}

                {pendingUser && (
                  <div className="flex justify-end">
                    <div className="max-w-[86%] whitespace-pre-wrap break-words rounded-lg bg-primary px-3.5 py-2 text-[15px] leading-[23px] text-primary-foreground">
                      {pendingUser}
                    </div>
                  </div>
                )}

                {live && (
                  <div className="flex">
                    <div className="max-w-[min(760px,86%)] rounded-lg border border-border bg-surface px-4 py-3">
                      <div className="mb-1 flex flex-col gap-1.5">
                        {live.stageLines.map((line, i) => (
                          <StageLineView key={i} line={line} />
                        ))}
                        {live.stageLines.length === 0 && (
                          <StageLineView line={{ text: "连接中…", state: "run" }} />
                        )}
                      </div>
                      {live.answer && (
                        <div className="mt-1.5 whitespace-pre-wrap break-words text-[15px] leading-6">
                          {live.answer}
                        </div>
                      )}
                      {live.stopped && (
                        <p className="mt-2 text-[13px] leading-[18px] text-warning">
                          已手动停止，以上为已生成内容；本次未生成来源引用。
                        </p>
                      )}
                      {live.error && (
                        <div className="mt-2.5 flex items-center gap-2.5 rounded-md border border-destructive/30 bg-destructive-bg px-3 py-2.5 text-sm leading-5">
                          <span className="min-w-0">{live.error}</span>
                          <Button
                            variant="outline"
                            size="sm"
                            className="ml-auto flex-none"
                            onClick={() => {
                              const q = live.question;
                              setLive(null);
                              setPendingUser(q);
                              void runAsk(q);
                            }}
                          >
                            重试
                          </Button>
                        </div>
                      )}
                      {live.citations && live.citations.length > 0 && (
                        <CitationsBar
                          citations={live.citations}
                          onOpen={(c) =>
                            setCitation({ citation: c, kbId: session?.kbId ?? kbId! })
                          }
                        />
                      )}
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>

          {/* 输入区 */}
          <div className="flex-none border-t border-border bg-surface px-6 pb-4 pt-3 max-md:px-4">
            <div className="mx-auto flex max-w-[900px] items-end gap-2">
              <textarea
                aria-label="输入问题"
                rows={1}
                disabled={kbNoDocs}
                value={input}
                placeholder={
                  kbNoDocs
                    ? "该知识库暂无文档，上传后即可提问"
                    : "输入问题，Ctrl/Cmd + Enter 发送"
                }
                className="max-h-[132px] min-h-[40px] flex-1 resize-none rounded-md border border-input bg-surface px-3 py-2 text-sm leading-5 placeholder:text-muted-foreground focus-visible:border-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/20 disabled:bg-muted disabled:text-muted-foreground"
                onChange={(e) => {
                  setInput(e.target.value);
                  e.target.style.height = "auto";
                  e.target.style.height =
                    Math.min(e.target.scrollHeight, 132) + "px";
                }}
                onKeyDown={(e) => {
                  if ((e.ctrlKey || e.metaKey) && e.key === "Enter") {
                    e.preventDefault();
                    send();
                  }
                }}
              />
              {streaming ? (
                <Button variant="destructive" className="flex-none" onClick={stop}>
                  停止
                </Button>
              ) : (
                <Button
                  className="flex-none"
                  disabled={kbNoDocs}
                  onClick={send}
                >
                  发送
                </Button>
              )}
            </div>
            <p className="mx-auto mt-1.5 max-w-[900px] text-[13px] leading-[18px] text-muted-foreground">
              {kbNoDocs
                ? "上传文档并完成解析入库后，即可在该知识库内提问"
                : "Ctrl/Cmd + Enter 发送 · 回答仅基于当前所选知识库的资料，引用可点开查看分块原文"}
            </p>
          </div>
        </section>
      </div>

      {/* 窄屏会话抽屉 */}
      <Dialog open={sessDrawerOpen} onOpenChange={setSessDrawerOpen}>
        <DrawerContent side="left" className="max-w-[280px] bg-surface-subtle">
          <DialogHeader className="flex-row items-center justify-between px-4 pb-2 pt-5">
            <DialogTitle className="text-sm font-semibold">会话</DialogTitle>
            <Button size="sm" onClick={() => void newSession()}>
              <Plus aria-hidden /> 新建会话
            </Button>
          </DialogHeader>
          <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-3">
            <SessionsList
              loading={sessionsQuery.isPending}
              sessions={sessionsQuery.data ?? []}
              activeId={sessionId}
              onSwitch={(id) => {
                changeSession(id);
                setSessDrawerOpen(false);
              }}
              onDelete={(s) => {
                if (liveRef.current) {
                  toast.error("回答生成中，请先停止或等待完成");
                  return;
                }
                setDeleting(s);
              }}
            />
          </div>
        </DrawerContent>
      </Dialog>

      {/* 引用分块 Drawer */}
      <CitationDrawer
        state={citation}
        onClose={() => setCitation(null)}
      />

      {/* 删除会话确认 */}
      <Dialog open={!!deleting} onOpenChange={(o) => !o && setDeleting(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>删除会话</DialogTitle>
          </DialogHeader>
          <p className="text-sm leading-6">
            将删除会话「<b>{deleting?.title}</b>」及其全部 {deleting?.messageCount ?? 0}{" "}
            条消息记录，删除后不可恢复。
          </p>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleting(null)}>
              取消
            </Button>
            <Button
              variant="destructive"
              onClick={async () => {
                if (!deleting) return;
                try {
                  await deleteSession(deleting.id);
                  toast.success("会话已删除");
                  queryClient.setQueryData(["sessions", kbId], (old?: Session[]) =>
                    (old ?? []).filter((s) => s.id !== deleting.id)
                  );
                  if (sessionId === deleting.id) {
                    const rest = (sessionsQuery.data ?? []).filter(
                      (s) => s.id !== deleting.id
                    );
                    setSessionId(rest[0]?.id ?? null);
                  }
                  setDeleting(null);
                } catch (err) {
                  toast.error(err instanceof Error ? err.message : "删除失败");
                }
              }}
            >
              删除
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

function findQuestion(messages: Message[], assistantIdx: number): string {
  for (let i = assistantIdx - 1; i >= 0; i--) {
    if (messages[i].role === "USER") return messages[i].content;
  }
  return "";
}

/** 会话列表内容（桌面侧栏与窄屏抽屉共用） */
function SessionsList({
  loading,
  sessions,
  activeId,
  onSwitch,
  onDelete,
}: {
  loading: boolean;
  sessions: Session[];
  activeId: string | null;
  onSwitch: (id: string) => void;
  onDelete: (s: Session) => void;
}) {
  if (loading) {
    return (
      <div className="space-y-3 p-2" aria-hidden>
        {[0, 1, 2].map((i) => (
          <div key={i}>
            <Skeleton className="h-3.5 w-[70%]" />
            <Skeleton className="mt-2 h-2.5 w-[40%]" />
          </div>
        ))}
      </div>
    );
  }
  if (sessions.length === 0) {
    return (
      <p className="p-4 text-[13px] leading-5 text-muted-foreground">
        当前知识库暂无会话，点击「新建会话」开始提问。
      </p>
    );
  }
  return (
    <>
      {sessions.map((s) => (
        <div
          key={s.id}
          role="button"
          tabIndex={0}
          aria-label={`切换到会话：${s.title}`}
          className={cn(
            "group flex cursor-pointer items-center gap-1.5 rounded-md py-2 pl-3 pr-2 transition-colors",
            s.id === activeId ? "bg-primary-subtle" : "hover:bg-muted"
          )}
          onClick={() => onSwitch(s.id)}
          onKeyDown={(e) => {
            if (e.key === "Enter" || e.key === " ") {
              e.preventDefault();
              onSwitch(s.id);
            }
          }}
        >
          <div className="min-w-0 flex-1">
            <div
              className={cn(
                "truncate text-sm font-medium",
                s.id === activeId && "text-primary"
              )}
            >
              {s.title}
            </div>
            <div className="num mt-0.5 text-xs text-muted-foreground">
              {fmtDateTime(s.updatedAt)}
            </div>
          </div>
          <button
            type="button"
            aria-label={`删除会话：${s.title}`}
            className="rounded-sm p-1.5 text-muted-foreground opacity-0 transition-opacity hover:bg-destructive-bg hover:text-destructive focus-visible:opacity-100 group-hover:opacity-100"
            onClick={(e) => {
              e.stopPropagation();
              onDelete(s);
            }}
          >
            <Trash2 className="size-3.5" />
          </button>
        </div>
      ))}
    </>
  );
}

function StageLineView({ line }: { line: StageLine }) {  return (
    <div className="flex items-center gap-2 text-[13px] text-muted-foreground">
      {line.state === "run" && (
        <span
          aria-hidden
          className="inline-block size-3 animate-spin rounded-full border-[1.5px] border-muted-foreground border-t-transparent"
        />
      )}
      {line.state === "done" && <span className="text-success">✓</span>}
      {line.state === "fail" && <span className="text-destructive">✕</span>}
      <span>{line.text}</span>
    </div>
  );
}

function CitationsBar({
  citations,
  onOpen,
}: {
  citations: Citation[];
  onOpen: (c: Citation) => void;
}) {
  return (
    <div className="mt-2.5 flex flex-wrap items-center gap-1.5 border-t border-border pt-2">
      <span className="w-full text-[13px] text-muted-foreground">来源引用</span>
      {citations.map((c) => (
        <button
          key={c.chunkId}
          type="button"
          aria-label={`查看引用原文：${c.docName}`}
          className="inline-flex max-w-full min-w-0 items-center gap-1.5 rounded-full border border-border bg-surface-subtle px-2.5 py-0.5 text-left text-[13px] leading-[18px] transition-colors hover:border-primary hover:bg-primary-subtle hover:text-primary"
          onClick={() => onOpen(c)}
        >
          <span className="flex-none">{c.docName}</span>
          <span className="min-w-0 truncate text-muted-foreground">
            {c.titlePath}
            {c.page != null ? ` · 第 ${c.page} 页` : ""}
          </span>
        </button>
      ))}
    </div>
  );
}

function MessageRow({
  message,
  question,
  onReask,
  onOpenCitation,
}: {
  message: Message;
  question: string;
  onReask: (q: string) => void;
  onOpenCitation: (c: Citation) => void;
}) {
  if (message.role === "USER") {
    return (
      <div className="flex justify-end">
        <div className="max-w-[86%] whitespace-pre-wrap break-words rounded-lg bg-primary px-3.5 py-2 text-[15px] leading-[23px] text-primary-foreground">
          {message.content}
        </div>
      </div>
    );
  }
  return (
    <div className="flex">
      <div className="max-w-[min(760px,86%)] rounded-lg border border-border bg-surface px-4 py-3">
        <div className="whitespace-pre-wrap break-words text-[15px] leading-6">
          {message.content}
        </div>
        {message.status === "CANCELED" && (
          <p className="mt-2 text-[13px] leading-[18px] text-warning">
            已手动停止，以上为已生成内容；本次未生成来源引用。
          </p>
        )}
        {message.status === "ERROR" && (
          <div className="mt-2.5 flex items-center gap-2.5 rounded-md border border-destructive/30 bg-destructive-bg px-3 py-2.5 text-sm leading-5">
            <span className="min-w-0">
              {message.error?.message || "回答生成失败，可重试。"}
            </span>
            <Button
              variant="outline"
              size="sm"
              className="ml-auto flex-none"
              onClick={() => question && onReask(question)}
            >
              重试
            </Button>
          </div>
        )}
        {message.status === "COMPLETED" &&
          message.citations &&
          message.citations.length > 0 && (
            <CitationsBar citations={message.citations} onOpen={onOpenCitation} />
          )}
        {message.status === "COMPLETED" &&
          (!message.citations || message.citations.length === 0) && (
            <div className="mt-2.5 border-t border-border pt-2.5">
              <p className="text-sm font-medium">本次未采用任何引用</p>
              <p className="mt-0.5 text-[13px] leading-5 text-muted-foreground">
                回答未依据知识库资料得出。若本次检索有命中但证据不足以支撑回答，
                低相关命中可在「检索调试」页查看。
              </p>
            </div>
          )}
        <div className="mt-2 flex gap-2">
          <Button variant="ghost" size="sm" onClick={() => question && onReask(question)}>
            重新提问
          </Button>
        </div>
      </div>
    </div>
  );
}

/** 引用分块 Drawer：按 chunkId 定位分块原文 */
function CitationDrawer({
  state,
  onClose,
}: {
  state: { citation: Citation; kbId: string } | null;
  onClose: () => void;
}) {
  const chunkQuery = useQuery({
    queryKey: [
      "cite-chunk",
      state?.citation.docId,
      state?.citation.chunkId,
    ],
    queryFn: () => fetchChunkById(state!.citation.docId, state!.citation.chunkId),
    enabled: !!state,
    staleTime: 60_000,
  });

  const c = state?.citation;
  return (
    <Dialog open={!!state} onOpenChange={(o) => !o && onClose()}>
      <DrawerContent>
        <DialogHeader className="border-b border-border px-5 pb-3.5 pt-5">
          <DialogTitle>引用分块原文</DialogTitle>
        </DialogHeader>
        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">
          {c && (
            <>
              <Meta label="所属文档" value={c.docName} />
              <Meta label="标题路径" value={c.titlePath} />
              <Meta
                label="页码"
                value={c.page != null ? `第 ${c.page} 页` : "未标注（正文型文档无固定页码）"}
              />
              <Meta label="分块标识" value={c.chunkId} mono />
              <div className="my-3 border-t border-border pt-3">
                {chunkQuery.isPending ? (
                  <TableSkeleton rows={3} />
                ) : chunkQuery.isError ? (
                  <InfoAlert message="分块原文加载失败，请稍后重试。" />
                ) : chunkQuery.data ? (
                  <div className="whitespace-pre-wrap break-words text-[15px] leading-6">
                    {chunkQuery.data.text}
                  </div>
                ) : (
                  <InfoAlert message="该分块内容暂不可用（可能已被清理或文档已更新）。" />
                )}
              </div>
              <Link
                href={`/kb/${state.kbId}/doc/${c.docId}?chunk=${encodeURIComponent(c.chunkId)}`}
                className="text-sm text-primary hover:underline"
              >
                查看完整文档 →
              </Link>
            </>
          )}
        </div>
      </DrawerContent>
    </Dialog>
  );
}

function Meta({
  label,
  value,
  mono,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="mb-2.5">
      <div className="text-[13px] text-muted-foreground">{label}</div>
      <div
        className={cn(
          "mt-0.5 break-all text-sm leading-5",
          mono && "font-mono text-[13px]"
        )}
      >
        {value}
      </div>
    </div>
  );
}

/** 按 chunkId（{docId}-c{seq}）定位分块：按 seq 推算页码后拉取分块页 */
async function fetchChunkById(
  docId: string,
  chunkId: string
): Promise<Chunk | null> {
  const m = chunkId.match(/-c(\d+)$/);
  const seq = m ? parseInt(m[1], 10) : null;
  const pageSize = 50;
  const startPage = seq != null ? Math.floor(seq / pageSize) + 1 : 1;
  for (let p = startPage; p < startPage + 3; p++) {
    const res = await listDocumentChunks(docId, { page: p, pageSize });
    const found = res.items.find((c) => c.id === chunkId);
    if (found) return found;
    if (res.items.length === 0 || p * pageSize >= res.total) break;
  }
  return null;
}
