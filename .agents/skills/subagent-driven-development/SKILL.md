---
name: subagent-driven-development
description: Execute an approved implementation plan with scoped ZCode subagents, isolated write ownership, independent repository-aware review, and real verification.
---
# Subagent-Driven Development for ZCode

Use after an implementation plan is approved or otherwise ready to execute.

## Principle

Separate **implementation ownership** from **independent verification**, while avoiding context-selection bias.

- Implementer: write-capable, scoped to one task.
- Spec reviewer: read-only, checks requested behavior vs actual code.
- Code-quality reviewer: read-only, checks concrete correctness/regression/maintainability/test risks.
- Reviewers may search/read repository context beyond the files named by the implementer. Do not cripple them by pasting only selected snippets.

## Per-task loop

1. Parent extracts one task from the plan with objective, acceptance criteria, dependencies, non-goals and anchor paths.
2. Spawn `implementer` (or generic write-capable subagent if the named role is unavailable).
3. Implementer inspects needed context and edits only its delegated scope; it runs focused tests and returns files/commands/results/concerns.
4. Spawn `spec-reviewer` and `code-quality-reviewer` as independent read-only reviews. They may run in parallel because neither writes.
5. If either finds a real issue, return the finding to an implementer/fix worker; then re-run the relevant reviewer(s).
6. Mark the task complete only when implementation verification is green and no blocking review finding remains.
7. Update `.supie/state/current.yaml` task progress immediately.

## Parallelism

Parallelize only when safe:
- Read-only reviewers/explorers: generally safe to parallelize.
- Writers: parallelize only with disjoint file ownership or isolated git worktrees.
- Shared schema/model/contract/migration foundations: serialize first, then fan out dependents.

## Context handoff

Do **not** paste a whole plan/repository into every child. Provide:
- exact task objective and done-when;
- approved contract/spec references;
- known anchor paths;
- write boundary for implementers;
- known constraints.

Then allow the child to inspect repository context itself. For read-only reviewers/investigators, repository discovery is a feature, not a violation.

## ZCode custom-agent compatibility

Project roles live under `.zcode/agents/*.md` (frontmatter: name, description; read-only roles disallow write tools). If a named role is unavailable, use a generic subagent with the same role instructions and preserve the same read/write boundary. Correctness must not depend on a model pin or role-selection quirk.

## Red flags

Never:
- let two writers race on overlapping files;
- let an implementer approve its own work as the only reviewer;
- accept reviewer claims without file/command evidence;
- force reviewers to trust the implementer's summary;
- silently broaden task scope;
- continue past a material contract/spec mismatch;
- mark a task done when verification did not actually run.
