import { defineConfig } from "@playwright/test";

/**
 * E2E 配置：依赖运行中的前端与后端。后端未启动时 specs 内 test.skip 生效。
 * 运行：pnpm exec playwright test（首次需 pnpm exec playwright install chromium）。
 */
export default defineConfig({
  testDir: "./specs",
  timeout: 120_000,
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    trace: "on-first-retry",
  },
  retries: process.env.CI ? 1 : 0,
});
