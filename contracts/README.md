# API Contracts

For workflows that expose HTTP APIs, `contracts/openapi.yaml` is the machine-readable source of truth after Stage 3 approval. Backend routes, frontend clients/types, contract tests and E2E assumptions must reconcile against it.

If a project has no HTTP API, the workflow may replace this with the appropriate machine-readable contract (for example AsyncAPI, protobuf, GraphQL schema) and record that decision in the technical route document.
