# rag-web — GeneralRAG 前端

Next.js 14（App Router）+ Tailwind。启动、环境要求与完整功能说明见仓库根目录 `README.md`。

```bash
pnpm install
pnpm dev          # 开发模式，http://localhost:3000
pnpm build && pnpm start   # 生产模式
```

- `/api/*` 由 `next.config.mjs` rewrites 代理到本机 rag-server（8080），前端无独立后端。
- API 类型：`lib/api/schema.d.ts`，由 `pnpm gen:api` 从 `contracts/openapi.yaml` 生成，禁止手改。
- 契约漂移守护：`rag-server` 的 `OpenApiDriftTest` 运行时比对 `/v3/api-docs` 与契约文件。

## 测试

```bash
pnpm test         # vitest（lib 单元测试）
pnpm lint         # ESLint
pnpm e2e          # Playwright（specs/smoke.spec.ts，需前后端同时在线）
```
