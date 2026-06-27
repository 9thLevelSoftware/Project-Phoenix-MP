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
| F015 | REAL | `SqlDelightAssessmentRepository.saveAssessmentSession` performs session + result + 1RM writes across 3 repositories, not atomically. | **FIXED** — compensating rollback deletes both the inserted result and the session on partial failure (review follow-up added the result delete). |

## C5 — BLE safety & protocol correctness

| ID | Verdict | Evidence | Disposition |
|----|---------|----------|-------------|
| F069 | REAL | `BlePacketFactory` PROGRAM byte 0x04 = `(reps+warmupReps).toByte()` and Echo byte 0x05 = `targetReps.toByte()`; 255 → 0xFF = the unlimited sentinel. | **FIXED** — factory clamps finite rep bytes to 254 (defense in depth). |
| F070 | REAL | `WorkoutCommandValidator` allowed finite rep bytes up to 255. | **FIXED** — `MAX_FINITE_REP_BYTE = 254`; boundary tests (254 accepted / 255 rejected) for activation + Echo. |
| F030 | REAL | `RepCounterFromMachine.calculateDelta` treated every backward jump as a 16-bit wrap (last=500/current=0 → 65036), driving `repeat(topDelta)`. | **FIXED** — only treat as wrap near the 16-bit boundary with a small delta; otherwise re-baseline (delta 0). Reset-flood regression test added. |
| F007 | REAL | `KableBleRepository.stopWorkout` ignored `sendWorkoutCommand`'s `Result`, returning success even when the RESET write failed. | **FIXED** — honor the send result (still stops local polling) and surface a send failure. |
| F005 | REAL | `isExplicitDisconnect` set true on disconnect/cancel, only reset inside the `State.Disconnected` handler; could leak into the next connection and suppress auto-reconnect. | **FIXED** — reset the flag at the start of each new `connect()` after cleanup. |
| F006 | REAL | Deload/ROM/reconnection events were `emit()`'d from launched coroutines with `DROP_OLDEST`; only REP drops were tracked. | **FIXED** — `publishSafetyEvent` helper uses `tryEmit` and records DELOAD/ROM_VIOLATION/RECONNECTION_REQUEST losses (no-subscriber / overflow) on `BleEventDeliveryTracker`. |
| F008 | REAL | `_repEvents` (replay=0): a rep emitted with no subscriber returns true from `tryEmit` but is lost, without recording a drop. | **FIXED** — record a REP drop on the tracker (and warn) when emitted with no active subscriber. Return value / "published" log contract preserved. |
| F088 | PENDING | `DiscoMode._isActive` may stay true if the cycling loop breaks on a non-cancellation exception. | To address in C5 follow-up / Medium sweep. |
| F090 / F091 | PENDING | `HandleStateDetector.disable()` leaves stale state; `maxPositionSeen` init to `Double.MIN_VALUE` (smallest positive, not most-negative). | To address in C5 follow-up / Medium sweep. |
| F071 | PENDING | `WorkoutCommandValidator` weight cap is hardware-agnostic (110kg global). | Needs hardware-capability threading; assess in C5 follow-up. |

## C6 — Integration sync robustness

| ID | Verdict | Evidence | Disposition |
|----|---------|----------|-------------|
| F009 | FALSE-POSITIVE | An `error` response with `requiresUpgrade` deliberately stays CONNECTED and surfaces the entitlement/upgrade state — confirmed by the existing test `IntegrationManagerTest.requiresUpgradeResponseKeepsProviderConnected`. The connection is valid; the upgrade prompt rides on the entitlement state. | Closed; original behavior restored + comment added. (A trial fix broke the intended UX and the existing test caught it.) |
| F010 | REAL | `persistResponse`'s multi-repository writes could throw and escape the `Result` flow, leaving a partial sync not reflected in status. | **FIXED** — wrapped in try/catch (cancellation-aware): mark ERROR and return failure on persistence failure. |
| F011 | PARTIALLY-REAL | `deletedExternalIds` deletions applied only to activities. | **DOCUMENTED** — the field is a flat, untyped list of external ACTIVITY ids; routing them to routine/template/etc. repos would delete wrong rows. Per-entity deletion needs a wire-contract change; comment added making the activity-only contract explicit. |
| F018 | DEFERRED | Deletion tombstones (`needsSync=1, deletedAt` set) are excluded from `getUnsyncedExternalActivities`. | **DEFERRED** — confirmed via portal `mobile-sync-push` that neither `ExternalActivitySyncDto` nor the Edge Function supports external-activity deletion; including tombstones would re-create them server-side. Requires a coordinated wire + portal change (documented at the push site). |

## C9 — Import/export data fidelity

| ID | Verdict | Evidence | Disposition |
|----|---------|----------|-------------|
| F067 | REAL | `CsvParser.parseWeight` stripped unit suffixes but never converted pounds; both CSV exporters write pounds when the display unit is LB, so a lb export round-tripped as kg. Existing test `parseWeight_withLbSuffix` encoded the bug. | **FIXED** — detect `lb`/`lbs` and convert via `UnitConverter.lbToKg`; existing test corrected to assert the converted value. |
| F068 | REAL | Streaming backup importer had no `routineGroups` case, so v4 group data was skipped (group organization dropped on streaming restore). | **FIXED** — added the streaming `routineGroups` case (mirrors the non-streaming insert). `Routine.groupId` has no FK constraint, so no risky export reordering was needed. |
| F066 | DOCUMENTED | The `util/` workout-history CSV (`Date,Exercise,…`) has no time-of-day/session-id column, so `CsvParser` maps same-date rows to midnight and same-day same-exercise workouts are indistinguishable for dedup. | **DOCUMENTED** — the parser already supports a time string; the fix is to add a Time column to `util/CsvExporter` + the platform importer column mapping (the `data/integration` Strong exporter already emits `HH:MM:SS`). Spans platform importers; recommended follow-up. |

## C10 — Voice / safe-word safety & privacy

| ID | Verdict | Evidence | Disposition |
|----|---------|----------|-------------|
| F032 | REAL | `SafeWordDetectionManager.startForWorkout()` logged the configured safe word verbatim, re-introducing the voice-PII leak the platform listeners deliberately redact. | **FIXED** — log only length/configured metadata. |
| F062 | REAL | `matchesSafeWord` split on whitespace and compared tokens exactly, so `"stop!"`/`"stop."`/`"stop, now"` failed to match `stop` — a safety-critical false negative. Present on BOTH iOS and Android listeners. | **FIXED** — strip non-alphanumerics from each token before comparison; applied to both `SafeWordListener.ios.kt` and `SafeWordListener.android.kt` for parity. |
