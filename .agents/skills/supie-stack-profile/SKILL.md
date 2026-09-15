---
name: supie-stack-profile
description: Resolve the repository's technology profile before Supie planning, testing, security scanning or implementation. Use to avoid hard-coded framework/tool assumptions.
---
# Supie Stack Profile

Use `.supie/profiles/auto.yaml` by default. Inspect the repository before choosing commands.

## Detection order

1. Existing project instructions (`AGENTS.md`) and build documentation.
2. Build/package files: `pom.xml`, `build.gradle*`, `pyproject.toml`, `uv.lock`, `package.json`, lockfiles, `go.mod`, `Cargo.toml`, etc.
3. Framework markers in dependencies/imports/config.
4. Existing migration and test tooling.
5. Only then use an example profile under `.supie/profiles/` as a starting point.

## Rules

- Established repository conventions beat every example profile.
- Never assume FastAPI, React, PostgreSQL, `uv`, Maven or any scanner merely because this workflow kit mentions them.
- If stack detection is ambiguous and the choice materially changes architecture, surface the ambiguity at the relevant approval gate.
- For security/test commands, choose tools that match the detected stack and explicitly mark unavailable coverage.
- Record the resolved stack summary in the technical plan and workflow state; do not mutate the example profile just to record a one-off run.
