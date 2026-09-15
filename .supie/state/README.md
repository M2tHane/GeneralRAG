# Workflow Runtime State

`current.yaml` is short-lived execution state, not project memory. It records the active Supie workflow, stage, approvals, task progress, artifacts and verification evidence so a Codex session can resume without reconstructing progress from prose.

- Commit this README, not `current.yaml`.
- Durable project facts/SOPs belong in `.supie/memory/`.
- Requirement/architecture/API/database truth belongs in their own artifacts under `docs/`, `contracts/`, and migrations.
