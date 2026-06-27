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
| F088 | REAL | `DiscoMode._isActive` stayed true if the cycling loop broke on a non-cancellation exception. | **FIXED (C12)** — reset `_isActive=false` in a `finally` around the loop. |
| F090 | REAL | `HandleStateDetector.disable()` left `_handleState`/`_handleDetection`/timers stale. | **FIXED (C12)** — `disable()` now resets handle state/detection and calls `resetInternalState()`. |
| F091 | REAL | `maxPositionSeen` initialized/reset to `Double.MIN_VALUE` (smallest positive, not most-negative). | **FIXED (C12)** — use `-Double.MAX_VALUE` at the field decl and in `resetInternalState()`. |
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

## C11 — Build & CI correctness

| ID | Verdict | Evidence | Disposition |
|----|---------|----------|-------------|
| F072 | PARTIALLY-REAL | `ci-tests.yml` compiles the iosArm64 target on `ubuntu-latest`. | **DOCUMENTED, no change** — the Linux job DOES validate compilation (it caught a real commonTest iOS-incompatibility during this work: parentheses in test names). It does not link/run on a real iOS host, but that compile-check is valuable and cheap; moving to macOS trades cost for marginal gain. Left as-is intentionally. |
| F073 | REAL | `ios-testflight.yml` logs group-assignment and Beta App Review failures as warnings and continues. | **FIXED** — added an opt-in `fail_on_distribution_error` input (default false = current lenient behavior for internal groups); when true the two distribution steps `exit 1`. |
| F074 | REAL | `release-all.yml` `gh workflow run` calls omit `--ref`, dispatching children on the default branch. | **FIXED** — pass `--ref "${{ github.ref_name }}"` to all four dispatches; ref echoed in logs and summary. |
| F075 | DOCUMENTED | `release-all.yml` only dispatches children and reports "Triggered"; it does not await their conclusions. | **DOCUMENTED** — added an explicit note to the job summary that green ≠ published and each child run must be checked. Full run-id capture + polling is a larger workflow rewrite (recommended follow-up). |

## C8 — Error handling & silent resource-failure

| ID | Verdict | Evidence | Disposition |
|----|---------|----------|-------------|
| F026 | REAL | `SyncTriggerManager.attemptSync()` called `syncManager.sync()` unguarded; `onWorkoutCompleted()`/`onConnectivityRestored()` call it directly with no outer catch. | **FIXED** — cancellation-aware try/catch around `sync()`; non-cancellation throws routed to `onSyncFailure` and return. |
| F039 | REAL | `SettingsTab` restore handled only `Result.onFailure`; a thrown `importFromFile` escaped after `finally`, leaving no error dialog. | **FIXED** — added cancellation-aware catch mirroring the onFailure path. |
| F055 | REAL | `LinkAccountViewModel` launched ops caught only `CancellationException`; other throws left the screen stuck in Loading / surfaced as unhandled coroutine exceptions. | **FIXED** — added a non-cancellation catch → `Error` state on all 5 ops (login/oauth/signup/logout/sync/resync). |
| F056 | REAL | `WorkoutForegroundService.startWorkoutForeground()` called `startForeground()` unguarded; a throw inside the service process can't be caught by the controller's try around `startForegroundService()`. | **FIXED** — wrapped in try/catch returning Boolean; on failure logs, leaves `isForegroundActive=false`, and `stopSelf()`. |
| F059 | REAL | `migrateTokensToEncrypted` used async `apply()` for the encrypted write then immediately removed plaintext keys; a crash between could lose tokens. | **FIXED** — synchronous `commit()` + verify before removing plaintext; keep plaintext on failure for retry. |
| F060 / F061 | REAL | Android backup save paths used `openOutputStream(...)?.use { }` and continued as success when the stream was null. | **FIXED** — null stream now fails (and removes the empty MediaStore row) instead of reporting success / deleting the temp file. |
| F063 | REAL | iOS `IosSoundManager.release()` unconditionally `setActive(false)` on the process-wide `AVAudioSession`, which can kill the safe-word mic. | **FIXED** — skip deactivation when the category is `playAndRecord` (owned by the safe-word listener), mirroring the setup-time guard. |
| F065 | REAL | iOS `BackupJsonWriter.open()/write()` ignored directory/file/handle creation failures and silently dropped writes. | **FIXED** — throw on directory/file/handle creation failure and on missing handle / failed UTF-8 encoding. |
| F064 | DOCUMENTED | iOS `VideoPlayer` calls `onReady()` immediately after `play()`; initial-load failures aren't observed, so the UI can show a blank player forever. | **DOCUMENTED** — proper fix is KVO on `AVPlayerItem.status`; not shipping an unverified KVO impl for an iOS-only Medium without device testing. Recommended follow-up. |

## C3 — Multi-profile data isolation

| ID | Verdict | Evidence | Disposition |
|----|---------|----------|-------------|
| F016 / F028 | REAL | `getRecentCompletedSetsForExercise` and its query (`selectRecentCompletedSetsForExercise`) had no `profile_id`/`deletedAt` filter, leaking other profiles' (and soft-deleted) sets into progression/deload analysis. Only 2 callers, both in `ProgressionUseCase` (which has `profileId`). | **FIXED** — added `ws.profile_id = ? AND ws.deletedAt IS NULL` to the query; threaded `profileId` through the interface/impl and both call sites. |
| F029 | REAL | `checkForProgression` used `recentSets.maxOfOrNull { it.actualWeightKg }` despite the "most recent weight" comment; `checkForDeload` already used `.first()`. | **FIXED** — uses the most-recent (newest-first) working set's weight. Regression test added. |
| F019 | REAL | `getRpgInput` peak-power inputs used global `selectPeakRepPower`/`selectPeakPower` (no profile filter), so one profile's metrics inflated another's RPG power. | **FIXED** — added profile-scoped `selectPeakRepPowerForProfile`/`selectPeakPowerForProfile` (join WorkoutSession, filter profile + deletedAt) and used them in `getRpgInput`. |
| F047 | REAL | `AssessmentViewModel` saves with no active profile. | See C7 (deferred — needs profile injection). |
| F049 | REAL | `GamificationViewModel` badge progress not reactive to profile changes. | **FIXED** in C7. |

## C7 — Compose state, lifecycle & navigation

| ID | Verdict | Evidence | Disposition |
|----|---------|----------|-------------|
| F050 | REAL | `MainViewModel` velocity-1RM backfill launched with `viewModelScope.launch(NonCancellable)` — defeats lifecycle cancellation of a long DB job. | **FIXED** — launched as a normal `viewModelScope` child; work is idempotent/run-once so cancel-on-clear is safe. |
| F049 | REAL | `_badgesWithProgress` loaded once in `init` via `activeProfileId.value`, not reactive (unlike streaks/stats/uncelebrated). | **FIXED** — `init` collects `activeProfileId` with `collectLatest`, reloading per profile and clearing stale badges; in-flight load cancelled on switch. |
| F046 | REAL | Saving a new routine replaced the id with a fresh UUID but left child `Superset.routineId = "new"`. | **FIXED** — resolve the final id first, then remap every `superset.routineId` before persisting. |
| F043 | REAL | `WorkoutSetupDialog` fed `weightPerCableKg` (default 0) into a slider whose range starts at 1. | **FIXED** — clamp the displayed value into the range. |
| F042 | DOCUMENTED | `ModeConfirmationScreen` keys exercise configs by `exerciseName`; duplicate exercises collide (and LazyColumn key can crash). | **DOCUMENTED** — needs a stable occurrence-id keying scheme threaded through every config read/write in the screen; a broad UI refactor best verified with UI tests. Recommended follow-up. |
| F040 / F041 | DOCUMENTED | `JustLiftScreen` nav effect can push duplicate ActiveWorkout entries; entering with non-Idle/Active state resets the session. | **DOCUMENTED** — Compose nav/state-machine changes, UI-verification-heavy; recommended follow-up. |
| F045 | DOCUMENTED | `OneRepMaxInputScreen` `remember(existingOneRepMaxValues)` initializer can run before async values arrive. | **DOCUMENTED** — key the remember on contents/version or update in a `LaunchedEffect`; UI follow-up. |
| F048 | DOCUMENTED | `ExerciseConfigViewModel` async PR/baseline loaders write shared state without versioning/cancellation. | **DOCUMENTED** — needs request-token/cancellation rework; recommended follow-up. |
| F047 | DOCUMENTED | `AssessmentViewModel.acceptResult` saves with the default profile (no active-profile dependency). | **DOCUMENTED** — needs `UserProfileRepository` injection / profile captured at assessment start; recommended follow-up. |
| F051 | DOCUMENTED | `MainViewModel.onCleared` launches `NonCancellable` on the (cleared) `viewModelScope` to disconnect BLE. | **DOCUMENTED** — proper fix is an application-owned cleanup scope (DI change); no app scope is currently injected. Recommended follow-up. |
| F054 | DOCUMENTED | `WorkoutCoordinator.setActiveRackSelection` reuses the prior adjustment when `precomputedAdjustment` is null, pairing new rack ids with stale load. | **DOCUMENTED** — needs a reset/recompute contract or immutable update object; recommended follow-up. |

## C12 — Stubs (triage)

The 12 stub findings are mostly intentional incomplete features / placeholders,
not active bugs. Disposition:

| ID | Target | Disposition |
|----|--------|-------------|
| F207 | SettingsTab safe-word calibration timeout | DOCUMENTED — feature gap (no calibration-failed path); needs a bounded timeout. |
| F215 | SingleExerciseScreen multi-muscle filter (`flows.firstOrNull()`) | DOCUMENTED — placeholder; needs combine/intersect semantics. |
| F268 | AndroidManifest CAMERA permission for unshipped Form Check | DOCUMENTED — remove until the feature ships, or implement runtime UX. |
| F278 | Android accessibility settings constant `boldTextEnabled=false` | DOCUMENTED — read real Configuration/Settings. |
| F298 / F312 | Health permission settings launcher no-op / no result contract | DOCUMENTED — make `openSettings` report success; iOS actual is a no-op. |
| F310 | HardwareDetection.getCapabilities ignores input → DEFAULT | DOCUMENTED — root of F071; needs VERSION/firmware-characteristic wiring + an explicit unknown state. |
| F351 | Dead `MonitorDataProcessor.calculateRawVelocity()` | DOCUMENTED — safe to remove/mark test-only. |
| F375 | ComparativeAnalytics `isSignificant` is a 5% heuristic | DOCUMENTED — rename to `isMeaningfulChange` or implement real significance. |
| F383 | Stale `BottomNavItem` enum | DOCUMENTED — single source of truth for the bottom bar. |
| F403 | Deprecated `AndroidTheme` wrapper collapses ThemeMode.SYSTEM | DOCUMENTED — remove once callers migrated. |
| F424 | FUNDING.yml placeholder keys | DOCUMENTED — cosmetic template residue. |

---

## Summary & remaining work

**Resolved with code + tests (clusters C1–C12):** the 2 Critical findings (one
real → fixed, one false positive → closed) and the validated High-severity
findings across sync/LWW, auth/token lifecycle, multi-profile isolation,
migration/schema integrity, BLE safety & protocol, integration sync, error
handling, import/export, voice safety, build/CI, Compose lifecycle, and BLE
mediums. New regression tests were added where the logic was unit-testable
(PR exercise-id resolution, premium isolation, index-replacement rollback,
rep-byte boundaries, counter-reset flood, progression-from-recent-weight,
upgrade-required handling, lb→kg, etc.). Android + iOS (`iosArm64`) both compile;
the shared host test suites pass locally.

**Validated false positives / intentional behavior (closed, no code change):**
F002 (iOS log strings compile fine — one of the two Criticals), F009
(requiresUpgrade intentionally stays CONNECTED, per existing test), F023
(portal already delete-then-inserts per session). These illustrate why each
finding was re-validated rather than fixed blindly.

**Deferred (real but require coordinated/architectural change), documented in
place + here:** F022 (domain-wide `updated_at` separate from the sync marker +
portal contract), F018 (external-activity deletion needs a wire + Edge Function
change), F071/F310 (hardware-capability detection from the firmware VERSION
characteristic), F051 (application-owned cleanup scope / DI), F064 (iOS KVO on
AVPlayerItem.status), F042/F045/F048/F047/F054/F040/F041 (Compose
state-machine/DI changes best verified with UI tests), F066 (CSV Time column +
platform importers), F078 (secure-storage type hardening), F132 (SyncTrigger
constant/doc), plus the C12 stubs (intentional incomplete features).

**Remaining Medium/Low tail (F076–F431):** the per-area source reports contain a
large Medium/Low tail beyond the items pulled into the clusters above. Each
still needs the same per-finding re-validation applied here (the spot checks
found a real false-positive rate, so none should be fixed blindly). They are
best worked in follow-up passes grouped by the same RCA clusters; this ledger
plus the per-area reports are the work-list.
