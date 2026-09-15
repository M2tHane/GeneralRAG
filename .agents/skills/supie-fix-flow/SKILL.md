---
name: supie-fix-flow
description: Root-cause bug-fix workflow with fast/standard/strict modes, repo-reading investigation, regression protection, minimal implementation and independent verification.
---
# Supie Fix Flow

Fix the root cause, not the symptom.

## 0. Resolve mode

An explicit user mode wins.

### FAST
Use only when the bug is reproducible, the root cause is already strongly evidenced, the patch is low-risk/reversible and does not touch auth, migrations, public API contracts, concurrency, critical data or cross-module architecture. FAST still requires a focused regression check; it simply removes the approval ceremony.

### STANDARD
Default. Reproduce → independent read-only root-cause investigation → one fix-plan approval → test-first/minimal fix → independent verification.

### STRICT
Use for intermittent/unclear bugs, data corruption, auth/security, concurrency, migrations, public API behavior, broad blast radius or production-critical paths. Require explicit fix-plan approval and broader independent verification.

Record mode/runtime progress with `supie-workflow-state`.

## Step 1 — Reproduce and capture RED baseline
- Reproduce the actual symptom using the narrowest reliable command/test/request.
- Capture exact error/behavior and relevant environment facts.
- If reproduction is impossible, document what is missing; do not pretend a red baseline exists.

## Step 2 — Investigate root cause
Use `root-cause-investigator` when available.

**Important:** the investigator is read-only but **not context-blind**. Give it the symptom, reproduction, known anchors and scope; then let it use repository search/file reads/git history/tests to trace call/data flow across files. Do not constrain it to only a pasted snippet.

Expected output:
- root cause and causal chain;
- evidence with `file:line`/commands;
- why plausible alternatives are less likely;
- smallest safe fix boundary;
- regression-test target.

If the named custom role is unavailable, use a generic read-only subagent with the same contract.

## Step 3 — Fix plan and gate
For STANDARD/STRICT summarize:
- root cause;
- minimum code change;
- regression test;
- contract/schema implications;
- explicit non-goals.

Wait for approval. FAST may proceed without a separate pause if risk classification still holds.

## Step 4 — Test-first fix
- Prefer a failing regression test that reproduces the bug before modifying production code.
- Make the minimum coherent production change to make it pass.
- Do not perform unrelated cleanup.
- If fixing the bug requires an approved API-contract change, update `contracts/openapi.yaml` first and return to a gate when the behavior change is material.

## Step 5 — Independent verification
Use `bug-verifier` or a generic read-only verifier. It must independently:
- replay the original reproduction;
- run the regression test;
- inspect nearby risk areas and relevant tests;
- verify no contract/schema drift;
- report commands and outcomes.

Do not accept “tests should pass” as evidence.

## Step 6 — Close
Report root cause, patch, regression evidence, any residual uncertainty and whether a broader follow-up is advisable. Mark runtime state completed. Never auto-commit/push.
