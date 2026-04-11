# Phase 28: Integration Validation — Context

## Phase Goal
End-to-end bidirectional sync is verified to work correctly across both repos in sequence, with a deployment runbook that ensures schema, Edge Functions, and mobile release land in the correct order.

## Success Criteria
1. Full push round-trip verified: workout → push → portal dashboard (correct mode names, weight units, exercises)
2. Full pull round-trip verified: portal routine → pull → mobile (all advanced fields preserved)
3. Auth edge cases pass: token expiry → silent refresh → sync completes; bad creds → clear error
4. Deployment runbook executed in correct order (schema migrations → Edge Functions → mobile release)

## Plan Breakdown (3 plans, all Wave 1)

| Plan | Scope | Validates |
|------|-------|-----------|
| 28-01 | Push & pull adapter unit tests + mapping tests | SC-1, SC-2 |
| 28-02 | SyncManager flow tests with fake test doubles + auth edge cases | SC-3 |
| 28-03 | Deployment runbook + checkStatus cleanup | SC-4 |

## Dependencies
- Phase 27 (complete) — all sync paths implemented
- No portal-side changes needed — Edge Functions already deployed
- Test infrastructure exists (commonTest with kotlin.test, Fake pattern)

## Key Decisions
- Tests go in `commonTest` (pure Kotlin, no platform deps needed)
- Use Fake test doubles (established project pattern), not mocking libraries
- Deployment runbook is a documentation artifact, not code
- `checkStatus()` returns error string (Railway abandoned) — either remove or document clearly
