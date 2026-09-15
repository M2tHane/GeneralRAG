---
name: supie-workflow-state
description: Maintain short-lived Supie workflow execution state in .supie/state/current.yaml. Use whenever a supie workflow starts, advances stages, records an approval, resumes after interruption, or completes.
---
# Supie Workflow State

Treat `.supie/state/current.yaml` as **runtime state**, not memory and not product documentation.

## Required behavior

1. At workflow start, create/reset state with flow, mode, goal, baseline and first stage.
2. After every stage transition, approval, skip, task completion or verification result, update state immediately.
3. On resume, read state first and continue from the recorded stage instead of inferring progress from chat prose.
4. Never place passwords, tokens, cookies or other secrets in state.
5. When the workflow finishes, mark `status: completed`. Keep the file until the next Supie workflow starts so the last run is inspectable.

Use `scripts/state.py` when practical; otherwise make the smallest valid edit yourself. The helper stores JSON syntax in the `.yaml` file (JSON is valid YAML 1.2) so nested tasks and verification records work without a PyYAML dependency.

## Minimum schema

```yaml
schema: 1
flow: supie-edit
mode: standard
status: running
goal: "..."
baseline: "git HEAD or n/a"
current_stage: 3
stages:
  "1": {status: completed, artifact: null}
  "2": {status: completed, approved: true}
  "3": {status: in_progress}
tasks:
  "backend-filter": {status: completed}
verification:
  - {label: unit-tests, result: pass, command: "mvn test"}
updated_at: "ISO-8601"
```

Runtime state answers **“where are we now?”**. Durable memory answers **“what did this project teach us?”**. Never mix them.
