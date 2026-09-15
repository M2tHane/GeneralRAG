"use client";

import * as React from "react";
import { parsePageJump, totalPagesOf } from "@/lib/pagination";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";

export interface PaginationProps {
  total: number;
  page: number;
  pageSize: number;
  onPage: (page: number) => void;
  /** 不传则隐藏每页条数切换 */
  onPageSize?: (size: number) => void;
}

/**
 * 表格分页（复刻原型语义）：
 * 左=共 N 项 + 每页 10/20；右=上一页/下一页 + 当前页/总页数（>7 页时提供跳页输入，
 * 非法/越界输入就近报错且不改变当前页）。
 * 由父级 flex 布局停靠工作区底部上方（pb-6 = 24px 底部安全间距）。
 */
export function Pagination({
  total,
  page,
  pageSize,
  onPage,
  onPageSize,
}: PaginationProps) {
  const totalPages = totalPagesOf(total, pageSize);
  const showJump = totalPages > 7;
  const [jumpInput, setJumpInput] = React.useState("");
  const [jumpError, setJumpError] = React.useState<string | null>(null);

  const doJump = () => {
    const r = parsePageJump(jumpInput, totalPages);
    if (!r.ok) {
      setJumpError(r.error);
      return;
    }
    setJumpError(null);
    setJumpInput("");
    onPage(r.page);
  };

  return (
    <nav
      aria-label="分页"
      className="flex flex-none flex-wrap items-center justify-between gap-x-4 gap-y-2 pb-6 pt-3"
    >
      <div className="flex items-center gap-3 text-[13px] text-muted-foreground">
        <span className="num">共 {total} 项</span>
        {onPageSize && (
          <span className="flex items-center gap-1.5">
            每页
            <Select
              aria-label="每页条数"
              className="h-8 w-[64px]"
              value={pageSize}
              onChange={(e) => onPageSize(Number(e.target.value))}
            >
              <option value={10}>10</option>
              <option value={20}>20</option>
            </Select>
            条
          </span>
        )}
      </div>
      <div className="flex items-center gap-2">
        <Button
          variant="outline"
          size="sm"
          disabled={page <= 1}
          onClick={() => onPage(page - 1)}
          aria-label="上一页"
        >
          上一页
        </Button>
        <span className="num min-w-[64px] text-center text-sm">
          {page} / {totalPages}
        </span>
        <Button
          variant="outline"
          size="sm"
          disabled={page >= totalPages}
          onClick={() => onPage(page + 1)}
          aria-label="下一页"
        >
          下一页
        </Button>
        {showJump && (
          <span className="flex items-center gap-1.5 text-[13px] text-muted-foreground">
            跳至
            <Input
              aria-label="跳转页码"
              inputMode="numeric"
              className="h-8 w-[72px]"
              value={jumpInput}
              onChange={(e) => {
                setJumpInput(e.target.value);
                setJumpError(null);
              }}
              onKeyDown={(e) => {
                if (e.key === "Enter") doJump();
              }}
            />
            页
            <Button variant="outline" size="sm" onClick={doJump}>
              跳转
            </Button>
            {jumpError && (
              <span role="alert" className="text-[13px] text-destructive">
                {jumpError}
              </span>
            )}
          </span>
        )}
      </div>
    </nav>
  );
}
