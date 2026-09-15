import { describe, it, expect } from "vitest";
import {
  parsePageJump,
  totalPagesOf,
  clampPage,
} from "@/lib/pagination";

describe("分页页码跳转校验（与原型语义一致：非法输入不改变当前页）", () => {
  it("合法整数返回目标页", () => {
    expect(parsePageJump("3", 8)).toEqual({ ok: true, page: 3 });
    expect(parsePageJump(" 2 ", 8)).toEqual({ ok: true, page: 2 });
    expect(parsePageJump("1", 8)).toEqual({ ok: true, page: 1 });
    expect(parsePageJump("8", 8)).toEqual({ ok: true, page: 8 });
  });

  it("非数字 / 非整数被拒绝", () => {
    expect(parsePageJump("abc", 8).ok).toBe(false);
    expect(parsePageJump("2.5", 8).ok).toBe(false);
    expect(parsePageJump("-1", 8).ok).toBe(false);
    expect(parsePageJump("", 8).ok).toBe(false);
  });

  it("越界输入被拒绝并给出 1–N 提示", () => {
    const r = parsePageJump("99", 8);
    expect(r).toEqual({ ok: false, error: "请输入 1–8 的整数" });
    expect(parsePageJump("0", 8).ok).toBe(false);
  });

  it("totalPages 计算", () => {
    expect(totalPagesOf(0, 10)).toBe(1);
    expect(totalPagesOf(1, 10)).toBe(1);
    expect(totalPagesOf(10, 10)).toBe(1);
    expect(totalPagesOf(11, 10)).toBe(2);
    expect(totalPagesOf(76, 10)).toBe(8);
  });

  it("clampPage 收敛页码（删除/筛选后超界回最后一页）", () => {
    expect(clampPage(9, 8)).toBe(8);
    expect(clampPage(0, 8)).toBe(1);
    expect(clampPage(3, 8)).toBe(3);
  });
});
