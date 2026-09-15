import { AlertTriangle, AlertCircle, Info, RotateCw } from "lucide-react";
import { Badge, BadgeDot } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { DOC_STATUS_META } from "@/lib/format";
import type { DocumentStatus } from "@/lib/api/types";

/** 文档状态徽标（文案与原型一致：排队中/处理中/已完成/失败） */
export function DocStatusBadge({ status }: { status: DocumentStatus }) {
  const meta = DOC_STATUS_META[status];
  return (
    <Badge variant={meta.variant}>
      <BadgeDot />
      {meta.label}
    </Badge>
  );
}

export function ErrorState({
  title,
  description,
  onRetry,
}: {
  title: string;
  description: string;
  onRetry?: () => void;
}) {
  return (
    <div className="flex flex-col items-center gap-2 px-6 py-16 text-center">
      <AlertCircle className="size-8 text-destructive" aria-hidden />
      <div className="text-base font-semibold">{title}</div>
      <div className="max-w-md text-sm leading-5 text-muted-foreground">
        {description}
      </div>
      {onRetry && (
        <Button variant="outline" className="mt-2" onClick={onRetry}>
          <RotateCw aria-hidden /> 重试
        </Button>
      )}
    </div>
  );
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string;
  description: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col items-center gap-2 px-6 py-16 text-center">
      <div className="text-base font-semibold">{title}</div>
      <div className="max-w-md text-sm leading-5 text-muted-foreground">
        {description}
      </div>
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}

export function WarningAlert({ message }: { message: React.ReactNode }) {
  return (
    <div className="flex items-start gap-2.5 rounded-md border border-warning/30 bg-warning-bg px-3.5 py-3 text-sm leading-5 text-foreground">
      <AlertTriangle className="mt-0.5 size-4 flex-none text-warning" aria-hidden />
      <div className="min-w-0">{message}</div>
    </div>
  );
}

export function ErrorAlert({ message }: { message: React.ReactNode }) {
  return (
    <div className="flex items-start gap-2.5 rounded-md border border-destructive/30 bg-destructive-bg px-3.5 py-3 text-sm leading-5 text-foreground">
      <AlertCircle className="mt-0.5 size-4 flex-none text-destructive" aria-hidden />
      <div className="min-w-0">{message}</div>
    </div>
  );
}

export function InfoAlert({ message }: { message: React.ReactNode }) {
  return (
    <div className="flex items-start gap-2.5 rounded-md border border-border bg-surface px-3.5 py-3 text-sm leading-5 text-foreground">
      <Info className="mt-0.5 size-4 flex-none text-primary" aria-hidden />
      <div className="min-w-0">{message}</div>
    </div>
  );
}

/** 表格加载骨架：模拟真实行列结构 */
export function TableSkeleton({ rows = 5 }: { rows?: number }) {
  return (
    <div className="divide-y divide-border" aria-hidden>
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className="flex items-center gap-4 px-4 py-3.5">
          <Skeleton className="h-5 flex-1" />
          <Skeleton className="hidden h-5 w-24 sm:block" />
          <Skeleton className="hidden h-5 w-20 md:block" />
          <Skeleton className="h-5 w-28" />
        </div>
      ))}
    </div>
  );
}
