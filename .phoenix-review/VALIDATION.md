# Phoenix Review — Validation Ledger

Re-validation of every finding in `CONSOLIDATED-REPORT.md` against the current
code on branch `claude/phoenix-review-and-rca-y8t5u6`. Each finding is triaged to:

- **REAL** — confirmed in current code; fix applied (or planned in its cluster).
- **PARTIALLY-REAL** — real but narrower/mitigated than described.
- **DEFERRED** — real but requires a coordinated/architectural change (documented, not patched in this pass).
- **FALSE-POSITIVE** — claim does not hold against current code.
- **OBSOLETE** — target file/symbol no longer exists (renamed/deleted).

The portal (`9thLevelSoftware/phoenix-portal`) Edge Functions were consulted via
raw GitHub for the parity-critical sync contracts.

---

## C1 — Sync LWW correctness & convergence

| ID | Verdict | Evidence | Disposition |
|----|---------|----------|-------------|
| F001 | REAL | `SyncManager.sync()` stamped pushed sessions with `currentTimeMillis()` (old line 412) before `pullRemoteChangesWithResult()`; `mergeSessionsLww` accepts incoming only when `incomingTs >= existingTs` (SqlDelightSyncRepository.kt:2121), so a stamped-to-now rejected row beats the authoritative server row and suppresses repair. | **FIXED** — rejected sessions are now excluded from stamping; pull repairs them. |
| F025 | REAL | `PortalSyncPushResponse.rejections` (PortalSyncDtos.kt:441) was decoded but never read in `SyncManager` (no `rejections` reference). | **FIXED** — rejections inspected and logged per entity; rejected session ids drive F001 skip. |
| F021 | REAL | `PortalPullAdapter.toPersonalRecordSyncDto` mapped `exerciseId = pr.id` (PR row id). Portal `personal_records` projection has no `exercise_id` column (confirmed in `mobile-sync-pull/index.ts`), only `exercise_name`. | **FIXED** — resolve catalog id via `SyncRepository.findExerciseId(name, muscleGroup)` at the call site; fall back to `pr.id` only when unmatched. `oneRepMax = 0f` left as-is: per CLAUDE.md, `personal_records` are max-weight PRs, NOT the estimated 1RM (which lives in `exercise_progress`); conflating them would violate the parity contract. |
| F022 | DEFERRED | `PortalSyncAdapter` sends `updatedAt = epochToIso8601(currentTimeMillis())` for sessions (line 222) and cycles (~690). Portal uses the client `updated_at` directly in the `upsert_*_lww` RPC (confirmed in `mobile-sync-push/index.ts`), so push-time stamping makes mobile always win LWW for mutable entities. BUT the local `updatedAt` column serves double duty (LWW timestamp AND the post-push "synced" marker overwritten with now()), and the in-code comment documents this as a known Phase-3.2 limitation pending domain-wide per-row `updated_at` tracking. | **DEFERRED** — needs a separate `contentModifiedAt` column distinct from the sync marker, coordinated with the portal. Recommended follow-up; not safely patchable without the live portal contract + schema migration. |
| F023 | FALSE-POSITIVE | Child ids (`setId`, rep-summary, telemetry) are `generateUUID()` per push, but the portal `mobile-sync-push` handler deletes existing exercises per affected session before re-insert (issue #33 "safe replace"), and FK cascade removes child sets/rep_summaries/telemetry — so re-push does NOT duplicate. | Closed; no change. Server-side mitigation already exists. |
| F132 | PENDING | SyncTriggerManager `MAX_CONSECUTIVE_FAILURES` reportedly unused / doc mismatch. | To validate in C12 sweep. |
