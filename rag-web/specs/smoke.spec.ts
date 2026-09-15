/**
 * Playwright 主旅程（建库 → 上传 md → 完成入库 → 提问 → 引用 → 调试页）。
 *
 * 依赖运行中的前端（pnpm dev，端口 3000）与后端 rag-server（localhost:8080，经 rewrites 代理）。
 * 后端未启动时全部用例自动跳过，不阻塞 `pnpm test`（vitest）。
 */
import { test, expect } from "@playwright/test";

const BACKEND_HEALTH = "/api/v1/knowledge-bases";

test.beforeEach(async ({ request }) => {
  const res = await request.get(BACKEND_HEALTH);
  test.skip(res.status() !== 200, `后端未运行（GET ${BACKEND_HEALTH} → ${res.status()}），跳过 E2E`);
});

test("主旅程：建库 → 上传 → 提问 → 引用 → 调试", async ({ page }) => {
  const kbName = `e2e-kb-${Date.now()}`;

  // 1. 建库
  await page.goto("/kb");
  await page.getByRole("button", { name: "新建知识库" }).first().click();
  await page.getByLabel(/名称/).fill(kbName);
  await page.getByRole("button", { name: "创建", exact: true }).click();
  await expect(page.getByRole("link", { name: kbName })).toBeVisible();

  // 2. 上传 md（需要测试文件，由 specs/fixtures 提供）
  await page.getByRole("link", { name: kbName }).click();
  await page.getByRole("button", { name: "上传文档" }).first().click();
  await page
    .locator('input[type="file"]')
    .setInputFiles({
      name: "e2e-sample.md",
      mimeType: "text/markdown",
      buffer: Buffer.from(
        "# 测试文档\n\n## 支付回调超时\n\n支付回调超时应先检查网关线程池与下游确认接口延迟。\n"
      ),
    });
  await page.getByRole("button", { name: "开始上传" }).click();
  await expect(page.getByText("排队中").first()).toBeVisible();

  // 3. 等待入库完成（轮询 2s，最长 60s）
  await expect(
    page.getByRole("cell", { name: "已完成" }).or(page.getByText("已完成"))
  ).toBeVisible({ timeout: 60_000 });

  // 4. 提问（SSE 流式 + 引用）
  await page.goto("/chat");
  await page.getByLabel("选择知识库").selectOption({ label: kbName });
  await page.getByLabel("输入问题").fill("支付回调超时怎么排查？");
  await page.getByRole("button", { name: "发送" }).click();
  await expect(page.getByText("来源引用")).toBeVisible({ timeout: 60_000 });

  // 5. 引用 Drawer
  await page.getByRole("button", { name: /查看引用原文/ }).first().click();
  await expect(page.getByText("引用分块原文")).toBeVisible();

  // 6. 调试页
  await page.goto("/debug");
  await page.getByLabel("问题").fill("支付回调超时怎么排查？");
  await page.getByRole("button", { name: "执行检索调试" }).click();
  await expect(page.getByText("命中分块")).toBeVisible({ timeout: 30_000 });
});
