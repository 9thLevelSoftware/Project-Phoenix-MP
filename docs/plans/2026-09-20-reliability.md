# Phoenix reliability implementation

Implementation map for the Phoenix mobile reliability and release-readiness plan. Verification results, compatibility decisions, and outstanding release gates are recorded in [the delivery notes](2026-09-20-reliability-delivery.md).

## Global constraints

- Mobile: `Project-Phoenix-MP`, branch `codex/reliability-mobile`, base `a2dd2890` (review base `a33f8198` plus unrelated platform cleanup preserved).
- Portal: `Phoenix-portal`, branch `codex/reliability-portal`, base `97b2646`. Brownfield branches excluded.
- All ordinary weights are per cable; assessment Total values convert exactly once. Preserve existing hybrid/VBT/PR meanings and caller-specific precedence.
- No destructive REPLACE on FK parents. SQLite 3.18 compatibility. Schema/migration/manifest/fallback stay in parity; backfill fallback 47.
- No production deployment, publishing, or shared-branch mutation during implementation. Preserve user work and all retained tombstones.
- Full reconciliation remains; do not introduce a wall-clock pull cursor.
- Coordinate schema and cross-repository contracts with one integration owner and disjoint subsystem ownership.
- Write meaningful regressions before fixes and record observed failures and passes. Serialize Gradle runs against a shared checkout. Native/hardware checks must not be claimed without execution.

### Task 1: Persistence and schema foundation

Own PhoenixDatabase.sq, new .sqm migrations, SchemaManifest.kt, MigrationStatements.kt, safe session/exercise repository writes, catalog import writes, and focused real-database tests.
Replace both live WorkoutSession REPLACE merges with transactional insert-ignore/update. Preserve FK children, ownership, tombstones, unspecified fields, and locally captured facts when the portal provides a lossy reconstruction; track portal-origin rows so representable remote rows remain updateable. Reject stale versions within transaction and make replay idempotent. Replace Exercise REPLACE in import/custom/sync before baseline FK rollout. Preserve legacy baseline data until consumed.
Schema owner defines and integrates additive baseline, repair-ledger/pending-recovery, workout-deletion, ownership-outbox/events, portal-origin, cycle-updatedAt/conflict-draft structures needed by downstream tasks; share exact query contracts with root. Keep schema additions minimal and let root approve shared contracts before finalization. Backfill exact migration 47. All migrations mirror all four sources and have contiguity/parity tests.

### Task 2: Workout transitions and safety

Own ActiveSessionEngine, RoutineFlowManager, MachineSafetyCoordinator, safety-facing MainViewModel/EnhancedMainScreen code and tests. Capture autoplay once. Manual transition seeds SetReady then applies pending rack change; autoplay applies before command configuration. Cover same-entry and exercise changes, counterweights, consume-once.
Classify unexpected disconnect inside coordinator using durable/armed execution; ignore idle disconnect with no obligation. Use trainer+generation identity for acknowledgement/recovery. Mutex covers exact conditional persistence, cancellation, UI change. Stale acknowledgement cannot hide/delete newer hazard; storage errors remain visible and fail closed. Recovery from Hidden must report explicit result, not no-op. Preserve successful RESET resolving exact execution before teardown-ready. Remove dormant interrupted-resume bypass and exclusively unused support chain. Do not change speculative warmup telemetry algorithm: document V-Form/Trainer+ hardware gate.

### Task 3: Scoped training baselines

After Task 1 contracts, own ProfileExerciseBaseline repository/domain operations, all baseline readers/writers and associated tests/DI. Key by explicit nonblank profileId+exerciseId; nullable per-cable value records clear, updatedAt and revision support CAS.
Operations: manual set/clear, atomic raise-if-greater for existing COMBINED PR behavior, revision-checked assessment compensation. Move scaling/modifiers/templates/training-cycle input/5-3-1/PR/assessments off ordinary global Exercise.oneRepMax access. Preserve precedence separately: scaling VBT -> scoped -> PR; modifier mode PR -> scoped -> configured; cycle input PR -> scoped; templates supplied profile; 5-3-1 cycle.profileId. Global column exposed only for legacy recovery. Sole-profile copy+consume after bootstrap; multiple profiles explicit assignment+consume. Profile deletion target wins, source only if target absent. Catalog remap newer timestamp wins, target on tie. Local-only, no baseline portal DTO. Coordinate shared file handoffs with schema/startup workers.

### Task 4: Startup and explicit profile recovery

Own startup hosts/App, MigrationManager, profile bootstrap/context/recovery service/UI and tests after baseline foundation. First open/reconcile DB and resolve startup-only dependencies. Bootstrap profiles, finish idempotent preference writes, transactionally apply deterministic repairs+ledger, publish profile-ready, then construct MainViewModel and enable normal UI/health/sync. Required failure is retriable and never Ready.
Remove guessing based on active-empty. Persist ambiguous source groups once across all profile tables. Settings shows source/counts/target and Keep with Default. Reject while workout/assessment busy; exclude profile switching/sync with fixed lock ordering. Move whole workout aggregates preserving IDs. Write account-bound ownership transfer in same transaction; pending until exact portal ack, processed before ordinary sync. Ordinary uploads cannot change existing ownership. Account-level ownership events reconcile other devices. Baselines remain local. Portal absent/failure leaves visible pending state; never pretend cloud convergence.

### Task 5: Mobile workout sync and deletion

Own SyncManager, PortalApiClient sync endpoints, sync DTOs/adapters/repositories after task handoffs, WorkoutRepository user deletes, History/Settings deletion UI, durable operation integration and tests.
Expand every dirty portal parent (routineSessionId ?: id) to all live components in a consistent snapshot; clear only acknowledged snapshot version. Batch complete groups; oversized remains pending with error. Server replaces incoming component IDs only, never omitted siblings.
Retained deletion ledger fields: mutationId UUID, ownerUserId nullable, profileId, scope COMPONENT|WORKOUT, portalSessionId, componentSessionId nullable, deletedAt, acknowledgedAt nullable, source LOCAL|REMOTE. User delete records ledger then hard-deletes atomically. Standalone/group/final member -> WORKOUT; member with survivors -> COMPONENT. Delete-all captured explicit profile, named confirmation. Separate internal discard for assessment compensation.
Push workoutDeletions entries; response acknowledgedWorkoutDeletionIds; pull workoutDeletions. Tombstone target matching is account+identity, not profile; profile routes outbound queue. Keep acked ledger indefinitely. Logged-out known owner waits; unknown owner never rebound. Exact ack only, retries independent of watermark. Apply deletions before live merges transactionally. Restore deleted content uses new IDs. Ownership operations before ordinary sync; exact ack, same-account only, route incoming account events.

### Task 6: Cycle persistence and conflicts

Own TrainingCycle repository/model/mappings and cycle conflict UI/repository/tests; coordinate shared SyncManager and schema through owners. Persist updatedAt backfilled createdAt; every pushed structural/nested mutation stamps parent in same transaction. Pull must retain incoming clock and not create local edit. Push dirty complete cycles. Portal always uses existing LWW for cycles; reject stale parent before child writes/deletion. Legacy unusable timestamp may create but not replace existing structure. Save rejected local structure as recoverable draft before canonical pull; user keeps server or saves draft as separate cycle. Keep timestamp-based conflict model.

### Task 7: Backup v6

Own BackupModels, DataBackupManager common/platform, stream staging abstractions, restore result UI and tests after data contracts. V6 adds optional custom exercises, scoped baselines, cycle timestamps, durable deletion/recovery state. Import v1-v5.
Stage forward-only source once to private per-section files with bounded memory; validate whole JSON before DB writes. Shared small/streaming core, dependency-order replay in batch transactions. Profiles/catalog/groups before routine graph; supersets before routine exercises; sessions before children; deletion guards before live inserts. Existing matching parents permit missing children on retry; preserve existing data, reject owner conflicts. Missing optional reference -> null plus copied fields + repaired count; required parent -> explicit failure. Imported/duplicate-skipped/failed mutually exclusive; repaired-reference additional. Honest partial completion, no double count. Cleanup success/error/cancel and stale interrupted temp files. Retain original account ownership in restored durable operations.

### Task 8: Auth, CSV, release, native verification

Own PortalApiClient refresh classifier only, SyncManager email log lines only (coordinate before broader sync owner), CsvExporter common/platform/integration, release workflows and tests, iOS production driver regression tests. Known definitive 400 codes only, including invalid_grant; unknown keeps credentials with recoverable result. Remove login/signup email logging. Escape all string CSV cells using existing helper.
Existing-release rebuild resolves requested tag SHA once; tests/build all that SHA. Preserve old assets until replacement passes; selected successful assets only, failed/skipped keep old. Add driver FK enforcement test after migration/reopen, don't blindly change foreignKeyConstraints flag. Root reviews dead queries during final Ponytail pass.

### Task 9: Portal contracts

Own portal workspace sync functions/shared contracts, migrations/RPC, portal delete/ownership callers, cycle conflict handling and tests. Read AGENTS.md; no credentials/environment files. Implement Task 5 additive wire semantics, permanent account+target tombstones, component replacement preserving siblings, exact postcommit ack, portal-side deletes, aggregate recomputation; suppress internal refresh deletion from creating tombstones. All paths honor tombstones independent LWW flag.
Ownership transfer operation validates auth owner/source/target/exact entity IDs, idempotent mutationId; atomic transfer and account-level event; normal upserts can't transfer existing rows. Target profile creation uses existing registration contract. Cycles always enforce existing LWW and gate all children/deletion on parent acceptance; no usable timestamp can insert but not overwrite existing. Deployable backward-compatible additive support first; no actual deployment in this task. Tests include real PostgreSQL transaction behavior when local services available, plus edge/sync suites.

## Acceptance and final validation

No FK-child loss or local capture degradation through either merge entrypoint; no incorrect pending rack load; no stale acknowledgement hiding current hazard; baseline/profile isolation; no cross-profile bulk delete or resurrection; no silently incomplete restore.
Required commands: mobile schema validation and full shared/Android host suites with `-Pskip.supabase.check=true`; targeted workflow/icon Python checks; portal verify:full, test:sync, test:edge and check:edge-functions as relevant. Actual PostgreSQL tests for atomicity, ownership, deletion/conflicts. macOS iOS build/native tests and trainer warmup/load checks remain explicit environment gates if unavailable on Windows.
Independent task reviews and final full-branch correctness/Ponytail review. Root records evidence and open external gates; never calls unrun checks passing. Do not publish or deploy until implementation is reviewable and release gates are satisfied.

## Release observability thresholds

Mobile sync logs durable queue depth and oldest age for workout deletions and ownership transfers, plus the number of sent mutations missing an exact acknowledgement. These events contain counts and ages only; they must not include account IDs, profile IDs, or email addresses.

- Investigate a durable queue when its oldest item is at least 24 hours old.
- Block release when any durable item is at least 72 hours old in a release-candidate run.
- Treat any missing exact acknowledgement as a failed sync attempt. Block dependent uploads immediately and investigate if the same queue still reports a missing acknowledgement on three consecutive manual retries.
- A nonzero queue count with an age below 24 hours is expected during offline use and is not a release blocker by itself.
