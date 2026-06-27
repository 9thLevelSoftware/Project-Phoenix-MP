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

## C2 — Auth / token lifecycle & concurrency

| ID | Verdict | Evidence | Disposition |
|----|---------|----------|-------------|
| F003 | REAL | `PortalAuthRepository.refreshSession().onFailure` only cleared auth when message contained `"Unauthorized"`/`"invalid"` (case-sensitive). `handleGoTrueResponse` already surfaces `PortalApiException` with `statusCode`. | **FIXED** — classify by statusCode 401/403; emit `SessionExpired`; preserve tokens on transient failures. |
| F020 / F077 | REAL | `ensureValidToken`/`forceRefresh` computed `isRecoverable` but called `clearAuthWithEvent(...)` unconditionally (PortalApiClient.kt ~579, ~613). | **FIXED** — recoverable failures now emit `RefreshFailed(isRecoverable=true)` via new `emitAuthEvent` without clearing; only 401/403 clear. |
| F004 | REAL | `saveGoTrueAuth`/`saveAuth`/`clearAuthInternal` performed unguarded multi-key writes (PortalTokenStorage.kt). | **FIXED (partial)** — serialized behind a platform `authLock` so writes can't interleave. Full session-generation/cancellation token (to reject stale completions logically) noted as a further hardening, not required for the write-race fix. |
| F024 | REAL | `saveGoTrueAuth` preserved `existingPremium` without comparing `response.user.id` to the stored user id. | **FIXED** — premium preserved only for same user; account switch fails closed to non-premium. |
| F078 | DEFERRED | `PortalTokenStorage` constructor accepts any `Settings`; `verifyStorageIntegrity` only round-trips read/write, so a future wiring mistake could pass plaintext settings. | **DEFERRED** — defensive DI hardening (distinct secure-storage type/marker). Real but no active bug; broader DI change. |
| F079 | REAL (iOS) | `OAuth.ios.kt` presentation anchor picks first scene / detached `UIWindow()`. | **DEFERRED to iOS pass** — cannot compile iOS in this Linux env; batch with other iosMain fixes, verify via CI. |
| F080 | REAL (iOS) | `OAuthLauncher.launch()` allows overlapping launches (no single-flight). | **DEFERRED to iOS pass** — same constraint. |

## C4 — Migration & schema-repair integrity

| ID | Verdict | Evidence | Disposition |
|----|---------|----------|-------------|
| F012 | REAL | `applyIndexCreate` ran `preDropSql` then `createSql`; a failed UNIQUE-index create left the table with no constraint (SchemaManifest.kt:141). | **FIXED** — drop+create wrapped in a SQLite SAVEPOINT; failed create rolls back the drop, preserving the prior constraint. Regression test added. |
| F013 | REAL (latent) | `repairOrphanedPRRecordsInternal` used raw `UPDATE PersonalRecord SET profile_id` with no dedup → idx_pr_unique abort when target already holds the key. Note: production DI builds `MigrationManager` without a driver, so the raw UPDATE was historically a no-op (bug dormant). | **FIXED** — routed through the canonical `mergePersonalRecords` dedup-merge (same helper the profile-scope move uses); skips safely when no driver is injected instead of crashing. |
| F014 | REAL (latent) | Same path used raw `UPDATE EarnedBadge SET profile_id` (no dedup) → idx_earned_badge_profile abort. | **FIXED** — routed through `mergeEarnedBadges`. |
| F015 | REAL | `SqlDelightAssessmentRepository.saveAssessmentSession` performs session + result + 1RM writes across 3 repositories, not atomically. | **FIXED** — compensating rollback: on any failure after the session write, the session is deleted (`deleteSession`) so no orphaned session/result is persisted. (True cross-repo transaction sharing isn't available.) |
