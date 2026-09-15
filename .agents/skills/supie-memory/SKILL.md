---
name: supie-memory
description: Maintain small, validated project-local memory under .supie/memory. Use when a workflow discovers a stable fact or reusable SOP that was verified by repository/tool execution and will matter across future sessions.
---
# Supie Project Memory

This is durable **project knowledge**, not active workflow progress and not a transcript archive.

## Storage

- L1 routing: the `Project memory` section of root `AGENTS.md` stays tiny and stable.
- L2 facts: `.supie/memory/facts.md`.
- L3 SOPs: `.supie/memory/sop/<topic>.md`.
- Raw session history: leave to ZCode's native persisted sessions/history; do not duplicate it into the repository.

## Admission rule: No execution, no memory

A candidate may be written only if:
1. it was verified by repository/tool execution (test, command, file inspection, real runtime behavior, accepted migration, etc.);
2. it is likely to matter across future sessions;
3. it is not a secret or short-lived runtime state.

Prefer compact provenance:

```text
Fact: local integration tests require Docker.
Evidence: ./mvnw verify succeeded with Testcontainers on 2026-09-11; see pom.xml + command result.
```

Do not store assistant speculation as project truth.

## State vs memory

- `.supie/state/current.yaml` = where the current workflow is now; transient, gitignored.
- `.supie/memory/*` = stable reusable knowledge; may be committed/shared.
- `docs/*`, `contracts/*`, migrations = authoritative product artifacts; memory should point to them rather than duplicate them.

## Maintenance

Patch minimally. Merge duplicates, remove stale guidance when directly disproven, and keep SOPs actionable. If a fact is cheap to re-verify and likely to drift, verify it before relying on it.

ZCode experimental `notes/history` (when enabled) are internal context-window bookkeeping and do not replace this project memory layer.
