---
name: supie-dev-flow
description: End-to-end 0→1 development workflow for ZCode: requirements, prototype, stack-aware technical route, machine-readable API contract, optional diagrams, schema/migrations, scoped multi-agent implementation, real verification and acceptance.
---
# Supie Dev Flow

Use for a new module/project where requirements and implementation both need to be shaped. The workflow is six stages, but only meaningful decision points pause for approval.

## Shared mechanics

- Start/reset `.supie/state/current.yaml` with `flow: supie-dev` and update it after every stage/approval/task milestone.
- Use `.supie/profiles/auto.yaml` and repository evidence to resolve stack/tooling. Existing repository conventions override example profiles.
- Do not pin a specific ZCode model in the workflow or custom-agent files. Model choice belongs to the user's current ZCode session/runtime.
- Prefer named project agents under `.zcode/agents/`; if a named role is unavailable, use a generic subagent with the same responsibilities. Never silently omit independent review/verification.
- Read-only agents are allowed to search/read the repository broadly enough to establish real evidence. Write-capable agents remain tightly scoped to their delegated task.
- For HTTP systems, `contracts/openapi.yaml` becomes the machine-readable API source of truth after Stage 3 approval.

## Approval gates

Hard gates exist where the user is choosing **what to build** or a materially different technical contract:
- Stage 1 requirements: approval required.
- Stage 2 interactive prototype / UX: explicit user approval required when meaningful UI exists.
- Stage 3 technical route + API contract: approval required.
- Stage 4 diagram: optional; only run if user wants it. The diagram itself does not require an additional ceremony unless it exposes a material requirements/architecture mismatch.

Do not pause merely because a deterministic implementation sub-step finished. Continue until the next real decision or blocker.

---

## Stage 1 — Requirements convergence【approval gate】

Use `req-user-advocate` and `req-dev-advocate`, preferably in parallel with independent viewpoints. Give them the user goal and repository/product context; they may inspect existing files read-only when this is an existing codebase/module.

Synthesize `docs/01-需求理解.md` with:
- problem/user outcome;
- in-scope and explicit out-of-scope;
- prioritized requirements (`MVP`, `Later`, `Won't now`);
- each MVP item has an observable `done-when` acceptance criterion;
- important edge cases/business rules;
- unresolved choices that actually need the user.

Run `spec-document-reviewer` or an equivalent independent read-only review before presenting the document. Fix internal contradictions first.

Pause for user approval. Record approval in workflow state.

## Stage 2 — Enterprise prototype / UX validation【approval gate】

For meaningful UI, read `enterprise-frontend-design` and use `prototype-designer`:

1. Reuse approved Stage 1 requirements. Read the skill's `references/product-layout.md`, choose the primary page type, content priority, task containers and settings scope. Maintain one compact `prototype/DESIGN.md`; confirm a wireframe only when material structural choices are still open.
2. Apply `references/design-system.md`: blue/white/cool-gray business UI, restrained information density, Card Gate and clear text. Respect existing branding. Do not create a three-style picker; each preview shows one complete page unless the user requests a comparison.
3. Build the MVP journeys as standalone HTML/CSS/JS under `prototype/`, with `prototype/index.html` as entry and shared `prototype/styles.css`. Initialize shared styles/data before assigning disjoint page work. Use realistic mock data and working interactions; no real backend requests or production source edits. Next.js and other production-stack defaults do not apply to this disposable prototype.
4. Demonstrate relevant loading, empty, filtered-empty, error, permission and submit states, Light/Dark/System, and narrow-screen behavior. Paginated tables use a stable bottom footer, 10/20 rows, and validated page jumps when needed. Keep demonstration controls separate from the business UI.
5. Use `page-walker` and `layout-reviewer` to check the main journeys, relevant `references/quality-check.md` items and desktop/tablet/mobile viewports. Fix observed defects, record actual evidence and any unverified gaps. Agent review cannot approve UX.
6. Present the prototype entry and a short suggested walkthrough. Wait for explicit user approval of the interactive experience, then record Stage 2 as completed and approved, with the approved artifact and decision in state. Do not advance to Stage 3 before this decision.

Only after the user has approved UX, record for example:

```bash
python3 .agents/skills/supie-workflow-state/scripts/state.py stage 2 completed \
  --approved true --artifact prototype/index.html --note "UX APPROVED; decisions in prototype/DESIGN.md"
```

On resume, inspect the existing approval and artifact; do not infer approval from a completed wireframe. If the main journey changes materially, reopen the affected UX decision. If feedback changes requirements materially, update Stage 1 and obtain approval for that delta before continuing.

If there is no meaningful UI, mark Stage 2 skipped with a reason and continue; do not create empty design artifacts.

## Stage 3 — Technical route + stack profile + machine-readable contract【approval gate】

First resolve the actual stack using `supie-stack-profile`:
- inspect build files, framework markers, lockfiles, migrations, tests and project instructions;
- prefer existing architecture over introducing a new framework/package manager;
- use `.supie/profiles/*.yaml` only as examples/reference.

Use `tech-route-architect` with read-only repository access. Write `docs/03-技术路线.md` covering:
- resolved stack and why;
- component/module boundaries and key data flow;
- authentication/authorization and security-sensitive boundaries when relevant;
- persistence model and migration strategy;
- error handling/observability considerations;
- test strategy by layer;
- significant alternatives/tradeoffs;
- implementation constraints/non-goals.

### API contract
For HTTP APIs, also create/update `contracts/openapi.yaml` (OpenAPI 3.1) with:
- paths/methods;
- auth/security requirements;
- parameters;
- request/response schemas;
- stable error responses;
- reusable components.

`docs/03-技术路线.md` explains **why**; `contracts/openapi.yaml` defines **exactly what crosses the API boundary**. Do not use prose as the only contract.

Validate the contract with available tooling when practical. If no parser/linter is available, record that validation gap rather than claiming success.

Pause for user approval of the technical route and externally visible contract. Record approval.

## Stage 4 — Business/auth flow diagram【optional】

Explain the value briefly and run only if the user wants it. Use `diagram-author` to create a draw.io artifact based on approved requirements/architecture. If the diagram reveals a contradiction, reconcile docs/contract before implementation.

If declined, mark Stage 4 skipped and continue.

## Stage 5 — Database schema / migrations

Do **not** assume PostgreSQL or raw SQL just because the original workflow did.

Use the resolved stack profile and repository conventions:
- existing Flyway/Liquibase/Alembic/Prisma/etc. → create the next native migration;
- greenfield PostgreSQL without a framework convention → `docs/sql/0001_init.sql` is acceptable;
- no persistence needed → mark skipped.

Use `db-schema-author` only within the selected migration convention. Ensure constraints/indexes/relationships reflect Stage 3 data model and API behavior. Run migration validation against a disposable/local test environment when practical.

## Stage 6 — Plan, implement, review, verify

### 6.1 Write a task plan
Use `writing-plans` to create `docs/06-实现计划.md`. Decompose into tasks that minimize write overlap. Shared foundations (schemas/contracts/models/migrations/common interfaces) come before parallel dependents.

Run `plan-document-reviewer` before implementation. Fix serious gaps.

For frontend tasks, include the approved `prototype/DESIGN.md`, prototype pages and `enterprise-frontend-design` in the plan's source references. Translate the prototype into the Stage 3 approved stack; do not copy demonstration controls or mock success paths into production.

### 6.2 Execute tasks with scoped agents
Use `subagent-driven-development` with these ZCode-specific rules:
- each implementation task gets a fresh `implementer` (or generic write-capable subagent if role selection is unavailable);
- the parent provides objective, acceptance criteria, boundaries and anchor paths—not a giant pasted copy of the repository;
- implementer may read what it needs but may write only its task scope;
- independent `spec-reviewer` and `code-quality-reviewer` are read-only and may search the repository themselves;
- parallel writes are allowed only when file ownership is disjoint or isolated worktrees are used;
- never rely on per-agent model pinning for correctness.

### 6.3 Contract discipline
For API work:
- implementation must conform to `contracts/openapi.yaml`;
- generate/check frontend types/client against the contract when suitable tooling exists;
- add contract/integration tests that catch drift;
- if implementation proves the approved API contract must change materially, stop and return to the Stage 3 gate rather than silently changing both sides.

### 6.4 Verification
Run the narrowest meaningful checks per task, then the project-level checks required by risk/profile. Capture actual commands/results.

For frontend changes, apply `frontend-e2e-testing` and the relevant enterprise skill quality checks. Verify actual routes/data/permissions and the approved layout, pagination, theme and failure-recovery behavior. Record prototype simulation separately from production verification.

Produce `docs/06-测试报告.html` using `supie-report-kit` with:
- what was tested;
- actual command/result evidence;
- requirement/test traceability at a useful level;
- known gaps/blocked checks.

Update workflow state continuously with current task and completed count so context resets do not lose execution position.

After all planned Stage 6 tasks pass the required review and verification, mark workflow state completed. If known gaps remain, complete only when the user explicitly accepts those gaps.

## Deliverables summary

Typical artifacts:

```text
docs/01-需求理解.md
docs/03-技术路线.md
contracts/openapi.yaml          # HTTP APIs
prototype/                      # interactive mock prototype when UI exists
prototype/DESIGN.md             # one compact approved UX decision record
migrations/ or docs/sql/        # according to stack
docs/06-实现计划.md
docs/06-测试报告.html
.supie/state/current.yaml       # local runtime state, gitignored
```

Do not auto-commit/push. Durable learnings may be promoted separately through `supie-memory`; runtime progress never goes into memory.
