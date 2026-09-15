---
name: supie-edit-flow
description: Controlled modification workflow for existing functionality. Classifies work as fast/standard/strict, keeps scope narrow, uses proportional approval gates, independent verification and runtime state.
---
# Supie Edit Flow

Use for changing an existing feature without turning the task into a refactor project.

## 0. Resolve execution mode first

An explicit user mode wins. Otherwise classify using repository evidence:

### FAST
Use only when all are true:
- low-risk and easily reversible;
- usually one/few files with an obvious change;
- no auth/permission, data migration, public API contract, concurrency, money, security-sensitive logic or destructive behavior;
- no material layout/interaction redesign;
- verification is cheap and local.

FAST removes ceremony, not discipline. State the intended scope in one compact sentence, implement directly, run focused verification, and report. Do not create a separate approval pause unless new risk is discovered.

### STANDARD
Default for normal feature changes. Use one meaningful pre-write approval after understanding the current behavior and proposing the change/test plan.

### STRICT
Use when any of these apply: auth/permissions, schema/migration, public API shape, concurrency/state-machine behavior, cross-module changes, data loss risk, security-sensitive code, broad UI workflow change, or unclear blast radius. Require explicit approval before implementation and independent verification afterward; if approved API behavior changes, update the machine-readable contract first.

Record mode in `.supie/state/current.yaml` via `supie-workflow-state`.

## Scope rules

- Modify only what the user requested.
- Do not opportunistically reformat, rename, upgrade dependencies, move files or refactor unrelated code.
- If you discover adjacent problems, report them separately instead of fixing them silently.
- Follow repository `AGENTS.md` and existing conventions.

## Workflow

### Step 1 — Understand current behavior
- Inspect the relevant implementation, tests, callers and nearby configuration.
- Summarize current behavior, requested delta, likely files and explicit non-goals.
- For an established repo, resolve stack/tooling from repository evidence rather than example profiles.

FAST may continue immediately when the scope is still clearly low-risk. STANDARD/STRICT continue to Step 2.

### Step 2 — UI/layout impact when applicable
For frontend changes, read `enterprise-frontend-design` and apply only the relevant rules, preserving the existing stack and approved design. If the change materially alters layout/navigation/interaction, first show its structure, update the affected decisions in `prototype/DESIGN.md` and build a scoped mock prototype under `prototype/`. Obtain explicit UX approval before production edits, following the Stage 2 rules in `AGENTS.md`. This decision can be combined with Step 3 when the change plan is ready; do not ask twice for the same decision. A material redesign cannot remain FAST. Cosmetic/local visual changes can stay FAST and do not require a new prototype or redundant UX approval.

### Step 3 — Change plan and gate
For STANDARD/STRICT provide a compact plan containing:
- behavior change;
- files/components expected to change;
- tests/verification;
- explicit “will not change” boundary;
- contract/migration impact.

Wait for approval before Step 4. STRICT may also require a second gate if the approved solution later needs a materially different contract/schema.

### Step 4 — Implement
- Use the smallest coherent patch.
- If delegating, use `implementer`; give it the objective, scope, acceptance criteria and anchor files. Do **not** dump the whole repo into its prompt.
- The implementer may inspect dependencies needed for its task but must write only inside the delegated scope.
- If an HTTP API shape changes, update `contracts/openapi.yaml` first and reconcile backend/frontend/tests to it.

### Step 5 — Independent verification
Prefer the `feature-tester` custom agent. It is read-only and should inspect repository context and run focused tests itself, not merely trust a pasted diff. Verify:
- requested behavior works;
- nearby behavior did not regress;
- contract/schema alignment remains valid;
- relevant tests/checks actually ran.

If the named role is unavailable in the current ZCode build, spawn a generic subagent with the same read-only responsibilities; do not skip independent verification silently.

### Step 6 — Close
Summarize changed behavior, files, verification evidence and remaining risks. Mark workflow state completed. Do not commit/push unless requested.

## State transitions

Update `.supie/state/current.yaml` after every completed/skipped step and approval so an interrupted ZCode session can resume from exact state instead of reconstructing progress from conversation history.
