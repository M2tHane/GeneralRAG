/**
 * 分页工具（与原型 SupieUI.renderPagination 的跳页校验语义一致）：
 * 非法/越界输入报错且不改变当前页。
 */
export type PageJumpResult =
  | { ok: true; page: number }
  | { ok: false; error: string };

export function parsePageJump(input: string, totalPages: number): PageJumpResult {
  const v = input.trim();
  if (!/^\d+$/.test(v) || parseInt(v, 10) < 1 || parseInt(v, 10) > totalPages) {
    return { ok: false, error: `请输入 1–${totalPages} 的整数` };
  }
  return { ok: true, page: parseInt(v, 10) };
}

export function totalPagesOf(total: number, pageSize: number): number {
  return Math.max(1, Math.ceil(total / Math.max(1, pageSize)));
}

/** 页码收敛（删除/筛选后 page 超界时回最后一页） */
export function clampPage(page: number, totalPages: number): number {
  return Math.min(Math.max(1, page), totalPages);
}
