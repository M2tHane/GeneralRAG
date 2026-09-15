---
name: supie-api-contract
description: Create and enforce machine-readable API contracts for Supie development. Use when Stage 3 defines HTTP APIs or later stages implement/test an approved contract.
---
# Supie API Contract

For HTTP APIs, `contracts/openapi.yaml` is the source of truth after technical-route approval.

## Stage 3

- Write OpenAPI 3.1 with paths, methods, auth, parameters, request/response schemas, error responses and stable component schemas.
- Keep `docs/03-技术路线.md` for rationale and architecture; do not duplicate every field there.
- Validate the contract with an available OpenAPI linter/parser when possible. If no validator is available, say so explicitly.

## Stage 6 implementation

- Backend handlers/controllers must reconcile with the contract.
- Frontend types/client code should be generated from or checked against the contract when the stack has suitable tooling.
- Contract/integration tests must catch route/schema drift.
- A code change that requires an API shape change must update the contract first and, when it changes approved behavior, return to the approval gate.

If the system is not HTTP-based, use the closest machine-readable schema (AsyncAPI/protobuf/GraphQL schema) and record the substitution.
