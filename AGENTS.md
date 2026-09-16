# Supie Workflow Instructions (ZCode)

This repository uses the Supie workflow kit adapted for ZCode.

The goal is not to make ZCode write code as quickly as possible. The goal is to make the correct product, validate the user experience before production implementation, then implement it with controlled scope and real verification.

## Core principle

> **No UX Approval, No Production Code.**

For new UI or a material change to layout, navigation or user journeys, production implementation must not begin until the user has personally reviewed and explicitly approved an interactive prototype. Scoped cosmetic or interaction-defect fixes that preserve the approved UX follow the proportional `$supie-edit` / `$supie-fix` rules below; they do not require a new prototype or repeated UX approval.

The required order is:

```text
Requirements correct
    ↓
Page structure and visual direction correct
    ↓
Interactive prototype correct
    ↓
UX APPROVED by user
    ↓
Technical contract correct
    ↓
Production implementation
    ↓
Independent verification
```

Before `UX APPROVED`, the agent may create or modify only planning/design artifacts needed by the workflow, such as:

```text
docs/01-需求理解.md
prototype/**
.supie/state/current.yaml
```

Do not edit production application source, production backend/frontend modules, database migrations, or implementation tests before the UX gate when the task has meaningful UI.

`prototype/**` is explicitly exempt from the production-code restriction. It is disposable design code used to validate layout, content, flow, states, and interaction before implementation.

If the task has no meaningful UI, Stage 2 may be skipped with the reason recorded in workflow state.

## Entry points

Invoke the workspace skills explicitly (Skill tool, by skill name) when you want a controlled workflow:

- `$supie-dev` — new project/module, requirements through acceptance.
- `$supie-edit` — scoped feature change; supports `fast|standard|strict`.
- `$supie-fix` — root-cause bug fix; supports `fast|standard|strict`.

## Project state (updated after R4.1-Answerability Stabilization, 2026-09-16)

**Current status**: rounds 1–2 complete; round 3 P1/P2/P3 complete (format expansion; MinerU
OCR with explicit `PARSER_UNAVAILABLE`, no silent fallback; document versioning with Flyway V3
`root_id/version_no/is_active`). R4-Excel done and **live-verified**: XLSX/CSV under STRUCTURE
strategy chunk via `SpreadsheetChunker` — per-sheet Summary chunk (fields/rowcount/sample rows)
+ Row Group chunks (header + context line re-injected per chunk, char-budget aggregation,
overlong rows stay atomic), row ranges carried in titlePath (`... > 数据行 a-b`) so
citations/debug/eval get row-level locating with zero schema/contract changes; retrieval
routing (RRF + rerank) unchanged.
R4-Answerability done and **A/B-verified** (72-item dedicated TUNING set): refusal is no longer a
single `topScore < threshold` scalar. `AnswerabilityPolicy` (shared by Chat/Debug/Eval — the
single-code-path principle now covers judging) gates: `LOW_SCORE_REFUSAL` below the R2-calibrated
dual thresholds (rerank 0.65 / cosine 0.30), everything else goes to `EvidenceSufficiencyJudge`
(structured output, temperature=0, Future-based timeout; six rules pinning "partial evidence /
same-product different-param / needs outside knowledge ⇒ false"). Judge failure degrades to the
old threshold behavior with explicit `JUDGE_DEGRADED` (fail-closed=true switches to conservative
refusal). **No highThreshold fast-accept exists** — baseline data proved PARTIAL_EVIDENCE and
answerable score distributions fully overlap (both reach 1.000). Result: False Answer Rate
47.4% → 10.5% with FRR 0% → 5.9%, retrieval metrics unchanged. Eval now records per-item
`refused` + decision columns (Flyway V4) and reports an answerability confusion matrix
(TP/FP/FN/TN, falseAnswerRate = FP/(FP+TN), falseRefusalRate = FN/(FN+TP), judge invocation/
degradation rate). SSE adds an `ANSWERABILITY_CHECKED` stage event (clients must tolerate
unknown stage values). EvalCategory gains `PARTIAL_EVIDENCE`.
R4.1 (Answerability stabilization, no new RAG capabilities) hardened the judge layer:
single-thread executor + Future replaced by **Semaphore bulkhead + synchronous call**
(`max-concurrent-judges` default 4, `bulkhead-wait-ms` 500; exhaustion → OVERLOADED
fail-fast); judge timeout now has a single meaning (it IS the judge HTTP connect/read
timeout, retries disabled — no orphaned background request); judge sees the **same
Context instance in full** as generation (`max-evidence-chars` deleted, no mid-chunk
substring); failure reasons classified TIMEOUT/OVERLOADED/MODEL_ERROR/INVALID_RESPONSE
in decision reason. New `AnswerabilityFlowIT` (7 scenarios: accept/refuse/malformed/
timeout/low-score/SSE-contract) runs with answerability ENABLED. Eval datasets support
optional `history` (Flyway V5) so FOLLOW_UP items actually test follow-up semantics —
history feeds prompt/judge coreference zones only, never evidence, retrieval still uses
the current question only. Labeling guide `docs/eval/answerability-labeling-guide.md`
+ dataset v2 (`eval-answerability-v2-array.json`: 3 label fixes 59/60/70 per guide,
all 6 residual R4 FP/FN re-audited, SYSTEM_ERROR=0). **OpenAPI `HIGH_CONFIDENCE_ACCEPT`
removed** (V4 migration comment left immutable; type never emitted).
**R4.1 evaluation loop CLOSED (2026-09-16, accounts recovered)** — full details in
`docs/round4/04-R4.1评测与校准.md`: R4 models restored (qwen3.7-text-embedding /
qwen3.7-text-rerank / deepseek-v4-flash-0731), KB rebuilt on the original embedding
(7 docs / 44 chunks, baseline v1-label run reproduces R4's numbers digit-for-digit:
TP34/FP18/FN0/TN20, Hit@1 0.8824, MRR 0.9363); rerank threshold re-swept over the
72-item score distribution → **0.75 replaces 0.65** (0.65 still leaked 17 threshold-FPs;
0.75 adds 3 correct direct refusals without new FRR — full sweep table §B); same-ground-
truth comparison (v2 labels, same KB/models/params): **FAR 45.95% → 5.41% (FP 17→2),
FRR 0% → 8.57% (3 items), retrieval metrics unchanged, Judge degraded-rate 0%**;
bad-case attribution: 12 of 17 baseline FPs caught by the judge, 2 residual FPs are
PARTIAL_EVIDENCE boundary items (judge conf 0.90–0.98 — confidence is not correctness),
0 FOLLOW_UP resolution errors across all 6 history items. Eval semantics fixed: judge-
degraded items are **excluded from the confusion matrix and refusalAccuracy** (system
failure must not inflate refusal metrics) and surfaced per decisionType in
`answerabilityConfusion.degraded` (red/green guarded in EvalFlowIT). Real-model
concurrency smoke (`scripts/smoke_concurrency.py`, SSE full path): concurrency 1/4 →
zero OVERLOADED; concurrency 8 → 6/16 fast OVERLOADED degradations = expected
backpressure, no failed requests; do NOT raise the semaphore to mask it.
The system is a locally runnable, measurably effective RAG:
ingestion (pdf/md/txt/**docx/xlsx/csv**; xlsx/csv+STRUCTURE → spreadsheet-aware chunking;
PDF parser selectable `pdfbox|mineru` via `rag.ingestion.pdf-parser`) →
hybrid retrieval (BM25 + vector RRF fusion + DashScope rerank with explicit degradation)
→ **answerability gate (low-score refusal → LLM evidence-sufficiency judge, shared single path)**
→ SSE streaming answers (refusal ⇒ empty citations) → retrieval debug (per-stage ranks +
answerability decision) → evaluation (chunk-level judging incl. **contentHash anchor** +
answerability confusion matrix + two-run comparison with comparability guard).

- Round-4 records: `docs/round4/01-Baseline分析.md` (baseline score distributions + Bad Case
  enumeration + why highThreshold was rejected), `02-实施记录.md` (final architecture, threshold
  provenance, A/B table, residual FP/FN per item, limitations), `03-R4.1稳定化.md`
  (stabilization: judge concurrency/timeout semantics/evidence consistency/FlowIT/contract/
  FOLLOW_UP history/label guide), `04-R4.1评测与校准.md` (closed loop: model restoration,
  KB rebuild, baseline reproduction, threshold sweep → 0.75, R4 vs R4.1 on same ground truth,
  bad-case attribution, concurrency smoke)
- Round-4 eval material: `docs/eval/eval-answerability-v1-array.json` (72 items, contentHash
  anchors; generator `scripts/gen-answerability-dataset.py`), **v2**: `docs/eval/eval-answerability-v2-array.json`
  (labels fixed per guide + FOLLOW_UP history), guide `docs/eval/answerability-labeling-guide.md`;
  comparison KB `1aab239e` (7 docs / 44 chunks, original qwen3.7-text-embedding — the
  authoritative comparable KB; old `b78b6fb8` holds text-embedding-v4 vectors, not comparable);
  runIds: baseline-v1 b00b513d, baseline-v2 7df5db44, R4.1 2decd02e (threshold 0.75);
  smoke script `scripts/smoke_concurrency.py` (SMOKE_KB_ID env selects the KB)
- Round-3 records: `docs/round3/01-实施记录-P1.md`, `02-实施记录-P2.md`, `03-实施记录-P3.md`,
  `04-实施记录-R4Excel.md`
- Scanned-PDF sample for retesting: `docs/eval-corpus/redis-scanned.pdf` (image-only, no text layer)
- New-format eval corpus & dataset: `docs/eval-corpus/{nginx-proxy-guide.docx,hikari-cp-reference.xlsx,kafka-error-codes.csv}`,
  `docs/eval/eval-formats-r3-v1-array.json` (pre-R4 chunking), `docs/eval/README-formats.md`
  (r4-excel-v1 dataset lives server-side; xlsx anchors rebound to R4 chunk structure)
- Progress & round-3 plan: `docs/round2/03-进度与第三轮计划.md`
- Round-2 implementation record: `docs/round2/02-实施记录.md`
- Round-1 handoff: `docs/HANDOFF.md`
- Test counts cited anywhere: backend 207 (`mvn test`), frontend 30 (vitest). Re-verify before relying on them.
- **Test strategy (changed in R4.1.2 closure)**: Testcontainers REMOVED (dependency, SharedInfraSupport,
  and its lifecycle tests deleted). All ITs connect directly to the dev Docker stack
  (MySQL/ES/MinIO; credentials/addresses via the standard MYSQL_*/ES_URIS/MINIO_* keys in
  `application-test.yaml`, defaults matching docker-compose.yml). Running `mvn test` creates
  ZERO containers and starts no Ryuk — if the dev stack is down, tests fail fast. Test data
  isolation: random UUID ids, per-suite MinIO buckets, dataset names with random suffix;
  no full-DB/index/bucket destructive cleanup. FakeOpenAiServer remains per-suite (stateful).
  Test profile matches dev ES mapping exactly: 1024-dim + ik_max_word (no analyzer override);
  MinIO endpoint/credentials come from the shared env config, ITs override only their bucket.
  Fake embedding is a deterministic pseudo-random L2-normalized vector (same text → cos=1.0,
  different text → ≈orthogonal) for test score control — NOT real semantic similarity;
  properties pinned by FakeOpenAiServerVectorTest. No test-only APIs in production code.

**Dead config watch**: `rag.retrieval.refusal.insufficient-threshold` / `RAG_REFUSAL_THRESHOLD`
were dead keys (removed in R4). Refusal thresholds are `rerank-threshold` /
`cosine-threshold` (+ `rag.answerability.*` for the judge). `max-evidence-chars` was
removed in R4.1 (judge now gets the full shared Context — do not reintroduce partial-evidence
truncation). Do not reintroduce undocumented keys.

**Remaining scope** (direction only; user approval per material step):
second corpus per new format, reliability hardening (P4),
structured Excel retrieval (row-level ES fields + filter/range queries) driven by real
BadCase attribution, code-file format support. Label re-audit of answerability-v1 is DONE
(v2 dataset + guide); R4.1 eval loop is CLOSED (04 report). Remaining R4.1-observed
candidates for future rounds: judge model separate from generation (2 residual
PARTIAL_EVIDENCE FPs at conf 0.90–0.98), PARTIAL_EVIDENCE guideline refinement.
Query Rewrite / Parent-Child / Metadata Filter /
GraphRAG have **no Bad Case support yet** (Hit@3=0.9429 residual is boundary chunking,
not recall) — do not add them without new evidence.

## Lessons learned (bind future rounds)

These were paid for in rounds 1–4. Treat them as constraints, not suggestions:

1. **Fix the metric before optimizing.** Round 1's Hit@K=1.00 was a degenerate document-level score;
   half of round 2 was spent making evaluation honest. Any round claiming "improvement" must first
   demonstrate the metric has discriminating power on the current corpus, and state the corpus size.
2. **Capability work ships with its own eval material.** BM25 landed with only Markdown corpus to
   test against. Any new capability (format, parser, chunking) must include realistic corpus +
   dataset entries (chunk-level anchors) in the same round, or its value cannot be demonstrated.
3. **"Interface seam reserved" ≠ "cheap to integrate".** The round-1 `Reranker` seam hid three real
   costs: a non-OpenAI-compatible endpoint (404 on `/compatible-mode/v1/rerank`), request-relative
   scores (not comparable across requests), and scale mismatch with `minScore`. Verify external
   dependencies with live calls during requirements convergence, not during implementation.
4. **Score semantics are load-bearing.** `score` feeds thresholds and refusal decisions; its meaning
   differs per retrieval mode (cosine / fused / rerank relevance) and is documented in the contract.
   Any new score source must define which threshold scale applies, with unit tests pinning the choice.
5. **Proportional agent usage.** A single-page prototype subagent in round 2 burned ~10M tokens.
   Prefer main-agent sequential implementation with independent read-only review; parallelize only
   across genuinely disjoint file domains.
6. **Rebuild ⇒ restart ⇒ verify styles.** After `pnpm build`, the running `next start` keeps a stale
   manifest (CSS 404, unstyled page). UI verification must include a fresh server start and a real
   stylesheet/computed-style check — not just HTTP 200 and DOM text.
7. **Deterministic integration tests for transport edge cases.** The round-1 disconnect test was
   intermittently failing because the response fit inside socket buffers. Tests that depend on the
   server *noticing* client behavior must force that condition (e.g. overflow the buffer), not hope
   for timing.
8. **Round-1 dataset format (docName anchoring) is retired.** Evaluation uses v2 datasets with real
   chunkId/titlePath anchors (`docs/eval/*-v2-array.json`). Chunk-level judging does not fall back to
   docName equality; unanchored evidence scores as a miss, by design.
9. **Run the baseline before building the mechanism (R4).** The low/high/gray-zone gate was the
   pre-approved candidate design; the 72-item baseline proved unanswerable questions reach
   rerank=1.000 (fully overlapping answerable scores), so the high-threshold fast-accept was
   dropped before any code was written. Sequence: dataset → old-policy baseline → distribution
   analysis → gate design → implement → A/B. Never implement a gate whose thresholds lack data
   provenance.
10. **Relevant ≠ Answerable, and the judge is not oracular.** Rerank scores measure semantic
    similarity; multi-part questions ("each of the three, and why") can score 1.000 while the
    evidence covers only part. The LLM judge cuts false answers ~78% but still misjudges with
    conf≥0.95 — after every A/B, re-audit the dataset's expected labels before tuning the system.
11. **Persist the decision, not just the outcome.** Refusal used to be inferred from the answer
    text (fragile). Since V4, `eval_run_item` stores `refused` + decision type/confidence/reason/
    degraded/latency so FP/FN can be reproduced and attributed (recall error? ranking? evidence?
    judge? generation?).
12. **System failure is not a decision.** R4's eval counted judge-degraded items (timeout etc.)
    as refused → under failClosed=true every outage inflated "correct refusal". Fixed in R4.1:
    degraded items are excluded from the confusion matrix and surfaced separately. Same rule
    applies to ANY metric that aggregates system outcomes: partition by failure mode first.
13. **Thresholds are model-bound calibrations, not constants.** The 0.65 rerank threshold was
    valid for one model version; swapping rerankers (even within one vendor) shifted the
    answerable distribution wholesale (P25 0.99 vs 0.45). Every reranker change requires a
    full score-distribution sweep before any comparison is meaningful, and embedding changes
    require rebuilding every KB (vector spaces are incompatible).

## Supie Dev stage discipline

For `$supie-dev`, follow this order unless a stage is explicitly inapplicable.

### Stage 1 — Requirements convergence【HARD APPROVAL GATE】

Before designing or implementing:

1. Understand the user outcome, not only the requested screens or endpoints.
2. Separate `MVP`, `Later`, and `Won't now`.
3. Give every MVP requirement an observable `done-when` acceptance criterion.
4. Identify meaningful business rules, edge cases, and explicit non-goals.
5. Write `docs/01-需求理解.md`.
6. Independently review the requirement document for contradictions or missing acceptance criteria.

Do not continue until the user explicitly approves the requirements.

Record the approval in `.supie/state/current.yaml`.

### Stage 2 — Prototype / UX validation【HARD APPROVAL GATE】

If the work has meaningful UI, Stage 2 is mandatory.

The purpose of this stage is to answer:

> **“If this were the finished product, would the user actually want to use it?”**

Do not use Stage 2 merely to create a decorative mockup. Build a functional prototype that lets the user experience the important flows before production code exists.

#### Stage 2A — Page structure and enterprise visual direction

Read `.agents/skills/enterprise-frontend-design/SKILL.md` and apply it through `prototype-designer`.

Start from the primary user task: choose the page type, main workspace, primary action, content priority and destinations for secondary information. Explain Modal / Drawer / Side Panel / independent-page choices and the scope of settings. Record decisions once in `prototype/DESIGN.md`.

For a new page or structural redesign, present a compact wireframe when the structure has not already been supplied or approved. Confirm only choices that materially affect the user journey; do not re-ask settled questions.

Use the included blue/white/cool-gray business design system by default; respect existing branding and explicit user choices. Do not require a three-style picker or component showcase. If the user requests alternatives, show separate complete business pages unless a comparison layout is explicitly requested.

Structure or visual approval is not final UX approval. Continue to the interactive prototype before requesting the Stage 2 UX decision.

#### Stage 2B — Interactive prototype

After the visual direction is known, create standalone prototype pages under:

```text
prototype/
```

Use `prototype-designer` and apply `enterprise-frontend-design`, including its Supie integration rules.

Prototype rules:

- Use standalone vanilla HTML/CSS/JS unless the user explicitly asks for another prototyping medium.
- Do not require the production application to build or run.
- Do not call the real backend.
- Do not modify production frontend/backend source.
- Use realistic mock data, not empty placeholders.
- Mock data should include meaningful variation in status, dates, names, values, permissions, and edge cases.
- Important actions must be clickable and produce visible feedback.
- Search/filter/sort/pagination must actually manipulate the mock data.
- Create/edit/delete flows may mutate in-memory prototype data.
- Forms must demonstrate both valid and invalid states.
- Main screens must explicitly represent `loading`, `empty`, `filtered-empty`, `error`, and `permission denied` where applicable.
- Apply the skill's layout, Card Gate, readable type, task containers, and Light/Dark/System rules.
- For paginated tables, keep the footer near the workspace bottom with 24–32px bottom spacing; support 10/20 rows and page-number input when total pages exceed 7 or controls become crowded. Use mock data to verify short, full and many-page cases.
- Put simulation controls in a separate prototype preview tool area, collapsed by default; do not fill the product header with debug state switches.
- Navigation and terminology must be consistent across screens.
- Shared visual rules belong in `prototype/styles.css`; do not let parallel prototype agents invent separate design systems.

A user should be able to understand the product by clicking through the prototype without reading its source code.

#### Stage 2C — Prototype review

Before asking the user to approve the prototype (browser operations are performed by the main agent via the `browser-use` skill; named reviewer subagents cannot drive the browser):

1. Run `page-walker` over the important journeys and interactive controls.
2. Run `layout-reviewer` at approximately:
   - `1440×900`
   - `768×1024`
   - `375×812`
3. Check the relevant `enterprise-frontend-design/references/quality-check.md` items; fix clear functional/layout defects found by those reviews. Record unavailable browser checks honestly.
4. Present the prototype entry path and a concise list of what the user should try.

The user must personally review the prototype.

Valid approval signals include unambiguous statements such as:

```text
原型通过
UX OK
就按这个做
页面没问题，继续
```

Do not infer approval from silence, from a minor comment, or from the agent's own judgment.

If the user requests UX changes:

```text
User feedback
    ↓
Update prototype
    ↓
page-walker
    ↓
layout-reviewer
    ↓
User reviews again
```

Repeat until explicit `UX APPROVED` is obtained.

If prototype feedback materially changes the product requirements, return to Stage 1, update `docs/01-需求理解.md`, and obtain requirement approval again before continuing.

Record `stages["2"].approved: true` with the approved prototype artifact and decision note only after explicit user approval. The workflow helper supports this through `stage 2 completed --approved true`.

### Stage 3 — Technical route + machine-readable contract【HARD APPROVAL GATE】

Stage 3 starts only after Stage 2 is approved or legitimately skipped.

Resolve the actual stack from repository evidence and `.supie/profiles/auto.yaml`. Existing project conventions override example profiles.

Write `docs/03-技术路线.md` covering, as relevant:

- resolved stack and rationale;
- module/component boundaries;
- key data flow;
- authentication/authorization boundaries;
- persistence model and migration strategy;
- error handling and observability;
- test strategy by layer;
- significant alternatives and tradeoffs;
- implementation constraints and non-goals.

For HTTP APIs, create or update:

```text
contracts/openapi.yaml
```

Use OpenAPI 3.1 as the machine-readable API source of truth for paths, methods, auth, parameters, request/response schemas, and stable error shapes.

`docs/03-技术路线.md` explains **why**.

`contracts/openapi.yaml` defines **what crosses the API boundary**.

Validate the contract with available tooling when practical. Never claim validation succeeded when no validator actually ran.

Do not start production implementation until the user explicitly approves the technical route and externally visible contract.

### Stage 4 — Business/auth flow diagram【OPTIONAL】

Run only when the user wants it or when a diagram has clear decision value.

The diagram must reflect approved requirements and architecture. If it exposes a contradiction, reconcile the source documents/contracts before implementation.

If declined, mark the stage skipped in workflow state and continue.

### Stage 5 — Database schema / migrations

Use the resolved stack and repository conventions.

Examples:

- existing Flyway/Liquibase/Alembic/Prisma/etc. → use the native migration convention;
- greenfield PostgreSQL without an established migration framework → `docs/sql/0001_init.sql` is acceptable;
- no persistence requirement → skip with reason.

Do not assume PostgreSQL, SQL, ORM, Maven, npm, uv, or any other tool merely because a workflow example uses it.

Schema constraints, indexes, and relations must match the approved Stage 3 data model and API behavior.

### Stage 6 — Plan, implement, review, verify

Only at this stage may production implementation begin.

#### 6.1 Implementation plan

Create:

```text
docs/06-实现计划.md
```

Decompose the work so shared foundations are built before dependent parallel work. Minimize overlapping write ownership.

Run an independent plan review before implementation.

#### 6.2 Scoped implementation

Use fresh implementation agents for discrete tasks.

The parent agent provides:

- objective;
- acceptance criteria;
- write boundary;
- important anchor paths;
- approved requirements/contract references.

Do not paste the entire repository into subagents.

Write-capable agents may inspect what they need, but may modify only the delegated scope.

No opportunistic refactors, dependency upgrades, format sweeps, architectural rewrites, or unrelated cleanup.

#### 6.3 Independent review

Reviewer/investigator agents are read-only, not context-blind.

They may independently inspect/search across the repository using targeted reads, `rg`, `git diff`, `git log`, tests, and other non-destructive evidence needed to trace real behavior.

Do not constrain a reviewer to only snippets selected by the implementer or parent agent.

For independent review, use separate perspectives for:

- specification/contract correctness;
- code quality/maintainability;
- root-cause investigation when debugging;
- security-sensitive correctness within the delegated change when relevant.

#### 6.4 Contract discipline

For API work:

- implementation must conform to `contracts/openapi.yaml`;
- generate/check frontend types or API clients from the contract when the project tooling supports it;
- add contract/integration tests that can detect drift;
- if implementation proves the approved external contract needs a material change, stop and return to the Stage 3 approval gate.

Never silently change both frontend and backend to hide contract drift.

#### 6.5 Real verification

A task is not complete because the code looks plausible.

Run the narrowest meaningful checks first, then broaden according to risk.

Examples include:

- focused unit tests;
- type checks;
- compile/build;
- integration tests;
- migration validation;
- API contract checks;
- browser/E2E checks;
- security tooling appropriate to the resolved stack.

Record actual command/result evidence.

Produce:

```text
docs/06-测试报告.html
```

The report should distinguish verified behavior, unverified gaps, skipped checks, and blockers.

After all Stage 6 implementation tasks pass the required review and verification, mark the workflow completed. If known gaps remain, complete only when the user explicitly accepts those gaps.

## FAST / STANDARD / STRICT execution levels

`$supie-edit` and `$supie-fix` support three execution levels.

### FAST

Use for low-risk, reversible, tightly scoped changes such as copy, obvious styling corrections, tiny configuration changes, or a clear single-file defect.

Characteristics:

- minimal ceremony;
- no artificial approval steps when intent is already explicit;
- narrow verification;
- still no unrelated edits.

Do not use FAST merely because the user wants speed if the change affects authentication, authorization, money, persistent data, public API contracts, migrations, concurrency, security boundaries, or broad cross-module behavior.

### STANDARD

Default for ordinary feature changes and bug fixes.

Characteristics:

- one meaningful pre-write understanding/plan checkpoint when needed;
- scoped implementation;
- real verification;
- independent review when risk justifies it.

### STRICT

Use for high-risk or difficult-to-reverse changes.

Examples:

- auth/permission changes;
- database migrations or data correction;
- payment/billing logic;
- public API compatibility;
- concurrency/distributed coordination;
- security-sensitive logic;
- large cross-module changes.

Characteristics:

- explicit approvals at material decision points;
- independent investigation/review;
- stronger verification and acceptance evidence.

## Shared workflow rules

1. **Use runtime state.** At the beginning of every Supie workflow read `.supie/state/current.yaml` if it exists. Start/reset it for a new run, update it at every stage transition/approval/task milestone, and use it to resume interrupted work. Runtime state is not durable memory.
2. **Do not confuse state with memory.** Workflow state answers “where are we now?” Durable memory answers “what verified knowledge should survive future sessions?”
3. **Resolve the stack before choosing tools.** Use `.supie/profiles/auto.yaml` and repository evidence. Existing build/test/migration conventions override example profiles. Never assume FastAPI/React/PostgreSQL/uv/Maven/npm from the workflow kit alone.
4. **Machine-readable contract wins.** For HTTP APIs, once Stage 3 is approved, `contracts/openapi.yaml` is the API source of truth. Backend, frontend, generated clients, and tests must reconcile against it.
5. **Investigators/reviewers may inspect the repo.** Read-only subagents may search/read across the repository as needed to establish real call/data flow. Their boundary is read-only access, not context blindness.
6. **Implementers stay scoped.** A write-capable agent edits only its delegated task. No opportunistic refactors, dependency upgrades, format sweeps, or unrelated cleanup.
7. **Verification must be real.** Do not report a task as tested unless the corresponding command/browser/tool action actually ran and its result was observed.
8. **Approval gates are decisions, not ceremony.** Pause where the user is choosing requirements, UX, externally visible contracts, or other materially different outcomes. Do not create fake approvals for deterministic implementation substeps.
9. **Explicit approval beats agent inference.** The agent must never approve its own requirements, UX, or architecture gate on the user's behalf.
10. **No UX Approval, No Production Code.** For meaningful UI work, production implementation cannot begin until Stage 2 has explicit user approval.
11. **Prototype first, production second.** UX feedback should be absorbed in disposable prototype code before being multiplied across production frontend/backend/contracts/tests.
12. **No silent contract drift.** Material changes to an approved API or business contract return to the relevant approval gate.
13. **Do not auto-commit/push.** Commit or push only when the user explicitly asks or approves it.

## Frontend design rules

When designing a new UI or materially changing an existing user journey:

1. Start from approved requirements, not from an arbitrary component library.
2. Use `enterprise-frontend-design` as the frontend design source, with its bundled layout, design-system and quality-check references. Do not layer another strong visual-style skill on top.
3. Prefer showing realistic product content over lorem ipsum or empty cards.
4. Prototype the primary journey before production React/Vue/etc. implementation.
5. Make prototype interactions work using mock data so the user can judge behavior, not just appearance.
6. Treat loading/empty/error/permission states as part of the design, not implementation leftovers.
7. Review desktop/tablet/mobile behavior before UX approval.
8. Let the user judge taste and workflow. Agents may identify usability defects, but they do not replace the user's UX approval.
9. After UX approval, production frontend must preserve the approved information hierarchy and core interaction semantics unless a new user approval supersedes them.
10. Read `prototype/DESIGN.md` alongside the approved prototype before implementation. The workflow controls phase and stack selection; the enterprise skill controls applicable layout, visual and interaction rules. Its production-stack defaults never force a migration of an existing project.

## Project memory

Durable project knowledge lives under `.supie/memory/` and follows `$supie-memory`:

- `.supie/memory/facts.md` — verified stable facts.
- `.supie/memory/sop/*.md` — validated reusable procedures.

Only record information that is:

1. verified by repository/tool execution;
2. likely to matter across sessions;
3. specific enough to guide future work.

When practical, include compact provenance such as:

```text
command/test
relevant file
commit/revision
observed result
```

Do not promote guesses, unexecuted plans, or conversational assumptions into durable memory.

Do not put active workflow progress in memory. Active progress belongs in `.supie/state/current.yaml`.

ZCode already persists session history. Do not duplicate raw transcripts into this repository. If native context-management notes/history are available, treat them as session continuity mechanisms, not project truth.

## Repository boundary

Stay inside the current repository unless the user explicitly authorizes another path.

Never read unrelated repositories, home-directory secrets, global private files, credentials, SSH material, browser profiles, or other accessible data merely because the environment permits it.

For prototype walkthroughs and implementation verification, use the explicitly authorized local application or disposable test environment. Keep browser actions within the delegated page/task scope; do not treat verification as permission to change unrelated data or environments.

## Completion standard

A Supie task is complete only when the level of evidence matches the risk.

At minimum, the final response should make clear:

- what changed;
- what was actually verified;
- what was not verified and why;
- whether any approved requirement/UX/API behavior changed;
- whether there are remaining blockers or known gaps.

Never substitute confidence language for evidence.
