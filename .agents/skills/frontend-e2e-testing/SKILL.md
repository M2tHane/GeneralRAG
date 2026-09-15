---
name: frontend-e2e-testing
description: "Frontend testing sub-skill of supie-dev-flow (Stage 6). Use when testing the UI layer of a web app — covering main user journeys with end-to-end tests (Playwright/Cypress) plus a few component tests for genuinely tricky interactions, instead of unit-TDD'ing every component. Use this for the frontend half of the layered testing strategy; backend/core logic uses strict TDD via the test-driven-development skill."
---

# Frontend E2E Testing

The frontend half of `supie-dev-flow`'s layered testing strategy. The backend
and core logic get strict red-green-refactor TDD (`test-driven-development`).
The frontend gets a different treatment, on purpose.

## Why the frontend is different

Unit-TDD'ing every React/Vue component has poor ROI: UI churns constantly, so
per-component tests go brittle and you spend more time fixing tests than catching
bugs. And a passing component test rarely tells you the *feature* works — the
bugs that actually hurt live in the seams between components, the routing, the
data fetching, and the real DOM.

So invert the usual pyramid for the UI layer: **lean on end-to-end tests that
exercise real user journeys**, and add component tests only where an interaction
is genuinely tricky and worth pinning down in isolation.

## What to test, in priority order

1. **Main user journeys (E2E) — the bulk of your effort.**
   Each journey is one acceptance criterion from `docs/01-需求理解.md`, driven
   through the real UI: load the app, click through the flow, assert on what the
   user actually sees. These are direct Stage 6 verification evidence.

2. **Critical non-happy-path states (E2E) — the high-value bugs.**
   The `loading` / `empty` / `error` / `no-permission` states you settled in the
   Stage 2 prototype. These are the usual rework hotspots; assert each renders
   and behaves as designed (e.g. a persistent inline/section error appears with recovery on a failed request, empty
   state shows the call-to-action, a no-permission route redirects).

3. **Tricky component logic (component test) — sparingly.**
   A few isolated tests for genuinely fiddly bits: a date-range picker's edge
   cases, a form's cross-field validation, a reducer with non-obvious
   transitions. If a component is just markup + props, an E2E journey already
   covers it — don't add a component test for it.

## Enterprise frontend acceptance

Read the approved `prototype/DESIGN.md` and the relevant `.agents/skills/enterprise-frontend-design/references/quality-check.md` checks. For changed table flows, exercise short/full/many-page datasets, stable footer placement without overlap, 10/20 rows, valid and invalid page jumps, and page correction after filtering/deletion. For changed theme or container flows, verify Light/Dark/System persistence, Portal styling, keyboard/focus return, narrow screens and failed-submit input preservation. Test only features actually in scope.

Prototype mock interactions and intercepted API tests do not prove that the production backend is integrated. Include a real integration check when the task changes that boundary, or report the gap.

## Recommended tooling

- **E2E:** [Playwright](https://playwright.dev) (preferred — fast, reliable
  auto-waiting, great trace viewer) or Cypress. Pick one and stay consistent.
- **Component tests:** the framework's testing-library (e.g.
  `@testing-library/react`) with Vitest/Jest, or Playwright component testing.

## How to write a good E2E test

Write tests the way a user behaves, not the way the code is structured.

- **Select by what the user perceives**, not by implementation detail. Prefer
  roles / labels / visible text (`getByRole`, `getByLabelText`,
  `getByText`) over CSS classes or test-only ids. A test coupled to markup
  breaks on every refactor; a test coupled to *behavior* survives.
- **Assert on observable outcomes** — what appears on screen, the URL, a network
  call's effect — not on internal state.
- **Control the backend at the boundary.** Stub/mock network responses (route
  interception) so a test can deterministically drive the `loading`, `empty`,
  and `error` states. Don't reach into component internals to fake them.
- **Each test is independent and idempotent**: it sets up its own state and can
  run alone, in any order, repeatably.

### Example: an E2E journey + its error state (Playwright)

```typescript
import { test, expect } from '@playwright/test';

test('user sees their research reports, empty state when none', async ({ page }) => {
  await page.route('**/api/reports', (route) =>
    route.fulfill({ json: { reports: [] } }));      // drive the EMPTY state

  await page.goto('/reports');

  await expect(page.getByText('No reports yet')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Start a research run' })).toBeVisible();
});

test('a failed load shows an error state, not a blank page', async ({ page }) => {
  await page.route('**/api/reports', (route) =>
    route.fulfill({ status: 500 }));                 // drive the ERROR state

  await page.goto('/reports');

  await expect(page.getByRole('alert')).toContainText(/something went wrong/i);
});
```

## Relationship to the rest of the flow

- The journeys you test here are **the acceptance criteria from Stage 1** — so
  *passing, actually-run* E2E tests are core Stage 6 verification evidence. If the
  UI layer isn't built yet (a slice build), these specs are artifacts, not
  evidence. Record the unbuilt or unverified scope explicitly in the Stage 6
  test report. Don't treat an unrun suite as proof.
- The non-happy-path states you assert here are **the ones drawn in Stage 2** —
  the prototype told you what they should look like; these tests prove they do.
- Keep the suite green as part of Stage 6's exit gate, alongside the backend
  unit tests.

## Anti-patterns
- E2E-testing everything, including trivial markup — slow suite, no extra signal.
- Selecting by CSS class / test-id everywhere — couples tests to structure; they
  break on every refactor.
- Asserting on component internal state instead of what the user sees.
- Skipping the error/empty/loading journeys because "the happy path works" —
  those states are exactly where the bugs and rework live.
