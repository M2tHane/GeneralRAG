"use client";

import * as React from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Plus } from "lucide-react";
import { Topbar } from "@/components/shell";
import {
  EmptyState,
  ErrorState,
  TableSkeleton,
} from "@/components/states";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  createKnowledgeBase,
  deleteKnowledgeBase,
  listKnowledgeBases,
  updateKnowledgeBase,
  ApiError,
} from "@/lib/api/client";
import type { KnowledgeBase } from "@/lib/api/types";
import { fmtDateTime } from "@/lib/format";

export default function KbListPage() {
  const queryClient = useQueryClient();
  const kbsQuery = useQuery({
    queryKey: ["knowledge-bases"],
    queryFn: listKnowledgeBases,
  });

  // 新建/重命名 Modal 状态
  const [editing, setEditing] = React.useState<KnowledgeBase | null>(null);
  const [modalOpen, setModalOpen] = React.useState(false);
  // 删除确认
  const [deleting, setDeleting] = React.useState<KnowledgeBase | null>(null);

  const kbs = kbsQuery.data ?? [];

  return (
    <>
      <Topbar crumbs={[{ label: "知识库" }]} />
      <main className="min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto max-w-[1280px] px-6 pb-10 pt-6 max-md:px-4">
          <div className="mb-5 flex items-center justify-between gap-4">
            <h1 className="text-2xl-page font-semibold">知识库</h1>
            <Button
              onClick={() => {
                setEditing(null);
                setModalOpen(true);
              }}
            >
              <Plus aria-hidden /> 新建知识库
            </Button>
          </div>

          {kbsQuery.isPending ? (
            <div className="rounded-lg border border-border bg-surface">
              <TableSkeleton rows={4} />
            </div>
          ) : kbsQuery.isError ? (
            <div className="rounded-lg border border-border bg-surface">
              <ErrorState
                title="服务暂不可用"
                description="知识库数据加载失败，请稍后重试。"
                onRetry={() => kbsQuery.refetch()}
              />
            </div>
          ) : kbs.length === 0 ? (
            <div className="rounded-lg border border-border bg-surface">
              <EmptyState
                title="还没有知识库"
                description="创建知识库后即可上传文档，供知识问答与检索调试使用。"
                action={
                  <Button
                    onClick={() => {
                      setEditing(null);
                      setModalOpen(true);
                    }}
                  >
                    <Plus aria-hidden /> 新建知识库
                  </Button>
                }
              />
            </div>
          ) : (
            <div className="overflow-x-auto rounded-lg border border-border bg-surface">
              <table className="w-full min-w-[720px] text-left text-sm">
                <thead>
                  <tr className="border-b border-border text-[13px] text-muted-foreground">
                    <th className="px-4 py-3 font-medium">知识库名称</th>
                    <th className="px-4 py-3 font-medium">描述</th>
                    <th className="num px-4 py-3 text-right font-medium">文档数</th>
                    <th className="num px-4 py-3 text-right font-medium">分块数</th>
                    <th className="px-4 py-3 font-medium">创建时间</th>
                    <th className="px-4 py-3 font-medium">更新时间</th>
                    <th className="px-4 py-3 text-right font-medium">操作</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {kbs.map((kb) => (
                    <tr key={kb.id} className="transition-colors hover:bg-surface-subtle">
                      <td className="px-4 py-3">
                        <Link
                          href={`/kb/${kb.id}`}
                          className="font-medium text-primary hover:underline"
                        >
                          {kb.name}
                        </Link>
                      </td>
                      <td className="max-w-[280px] truncate px-4 py-3 text-muted-foreground">
                        {kb.description || "—"}
                      </td>
                      <td className="num px-4 py-3 text-right">{kb.documentCount}</td>
                      <td className="num px-4 py-3 text-right">{kb.chunkCount}</td>
                      <td className="num px-4 py-3 text-muted-foreground">
                        {fmtDateTime(kb.createdAt)}
                      </td>
                      <td className="num px-4 py-3 text-muted-foreground">
                        {fmtDateTime(kb.updatedAt)}
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex justify-end gap-1">
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => {
                              setEditing(kb);
                              setModalOpen(true);
                            }}
                          >
                            重命名
                          </Button>
                          <Button
                            variant="ghost"
                            size="sm"
                            className="text-destructive hover:bg-destructive-bg hover:text-destructive"
                            onClick={() => setDeleting(kb)}
                          >
                            删除
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </main>

      {/* 新建 / 重命名（居中 Modal，2 个简单字段） */}
      <KbFormDialog
        open={modalOpen}
        kb={editing}
        onOpenChange={setModalOpen}
        onSaved={() => queryClient.invalidateQueries({ queryKey: ["knowledge-bases"] })}
      />

      {/* 删除确认（Modal 明确影响范围） */}
      <KbDeleteDialog
        kb={deleting}
        onClose={() => setDeleting(null)}
        onDeleted={() =>
          queryClient.invalidateQueries({ queryKey: ["knowledge-bases"] })
        }
      />
    </>
  );
}

function KbFormDialog({
  open,
  kb,
  onOpenChange,
  onSaved,
}: {
  open: boolean;
  kb: KnowledgeBase | null;
  onOpenChange: (open: boolean) => void;
  onSaved: () => void;
}) {
  const isEdit = !!kb;
  const [name, setName] = React.useState("");
  const [description, setDescription] = React.useState("");
  const [nameError, setNameError] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (open) {
      setName(kb?.name ?? "");
      setDescription(kb?.description ?? "");
      setNameError(null);
    }
  }, [open, kb]);

  const mutation = useMutation({
    mutationFn: async () => {
      if (isEdit && kb) {
        return updateKnowledgeBase(kb.id, { name, description });
      }
      return createKnowledgeBase({ name, description });
    },
    onSuccess: () => {
      toast.success(isEdit ? "知识库已更新" : "知识库已创建");
      onOpenChange(false);
      onSaved();
    },
    onError: (err) => {
      if (err instanceof ApiError && err.code === "KB_NAME_DUPLICATED") {
        setNameError("已存在同名知识库");
      } else if (err instanceof ApiError && err.code === "INVALID_ARGUMENT") {
        setNameError(err.detailByField || err.message);
      } else {
        toast.error(err instanceof Error ? err.message : "保存失败，请稍后重试");
      }
    },
  });

  const submit = () => {
    const trimmed = name.trim();
    if (!trimmed) {
      setNameError("请输入知识库名称");
      return;
    }
    setNameError(null);
    mutation.mutate();
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{isEdit ? "重命名知识库" : "新建知识库"}</DialogTitle>
        </DialogHeader>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            submit();
          }}
        >
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="kb-name">
              名称 <span className="text-destructive">*</span>
            </Label>
            <Input
              id="kb-name"
              value={name}
              maxLength={100}
              placeholder="例如：团队技术文档库"
              aria-invalid={!!nameError}
              onChange={(e) => {
                setName(e.target.value);
                setNameError(null);
              }}
            />
            {nameError && (
              <p role="alert" className="text-[13px] text-destructive">
                {nameError}
              </p>
            )}
          </div>
          <div className="mt-4 flex flex-col gap-1.5">
            <Label htmlFor="kb-desc">描述</Label>
            <Textarea
              id="kb-desc"
              value={description}
              maxLength={500}
              placeholder="说明该知识库收录哪些文档（可选）"
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => onOpenChange(false)}>
              取消
            </Button>
            <Button type="submit" disabled={mutation.isPending} className="min-w-[96px]">
              {mutation.isPending ? (isEdit ? "保存中…" : "创建中…") : isEdit ? "保存" : "创建"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function KbDeleteDialog({
  kb,
  onClose,
  onDeleted,
}: {
  kb: KnowledgeBase | null;
  onClose: () => void;
  onDeleted: () => void;
}) {
  const mutation = useMutation({
    mutationFn: (id: string) => deleteKnowledgeBase(id),
    onSuccess: () => {
      toast.success("知识库已删除");
      onClose();
      onDeleted();
    },
    onError: (err) => {
      toast.error(err instanceof Error ? err.message : "删除失败，请稍后重试");
    },
  });

  return (
    <Dialog open={!!kb} onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>删除知识库</DialogTitle>
        </DialogHeader>
        {kb && (
          <div className="text-sm leading-6">
            {kb.documentCount > 0 ? (
              <>
                <p>
                  将删除知识库 <b>{kb.name}</b>，影响范围：
                </p>
                <ul className="mb-1 mt-2 list-disc space-y-1 pl-5">
                  <li>
                    其中 {kb.documentCount} 篇文档及其全部分块与向量数据将被清除；
                  </li>
                  <li>相关问答会话中对该知识库的引用将失效。</li>
                </ul>
              </>
            ) : (
              <p>
                将删除知识库 <b>{kb.name}</b>。该知识库暂无文档，删除后不可恢复。
              </p>
            )}
            {kb.documentCount > 0 && (
              <p className="mt-2 text-[13px] text-muted-foreground">该操作不可恢复。</p>
            )}
          </div>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            取消
          </Button>
          <Button
            variant="destructive"
            disabled={mutation.isPending}
            onClick={() => kb && mutation.mutate(kb.id)}
          >
            {mutation.isPending ? "删除中…" : "删除"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
