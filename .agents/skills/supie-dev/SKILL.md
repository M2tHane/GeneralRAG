---
name: supie-dev
description: Run the Supie 0→1 development workflow for a new module or project, from requirements through machine-readable API contract, implementation and acceptance.
---
# Supie Dev

Treat the user's remaining message as the goal. Read and follow:

1. `.agents/skills/supie-dev-flow/SKILL.md`
2. `.agents/skills/supie-workflow-state/SKILL.md`
3. `.agents/skills/supie-stack-profile/SKILL.md`
4. `.agents/skills/supie-api-contract/SKILL.md` when the project exposes an API.

Start from the stage recorded in `.supie/state/current.yaml` only when it clearly belongs to the same goal; otherwise start a new `supie-dev` state. Do not write implementation code before the required requirement/architecture approvals.
