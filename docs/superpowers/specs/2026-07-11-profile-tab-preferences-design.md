# Profile Tab, Contextual Preferences, and Settings Migration — Design

**Date:** 2026-07-11

**Profile preferences correction:** 2026-07-15 (PR #651)

**Status:** Approved (design), pending written-spec review
**Scope:** `Project-Phoenix-MP` mobile implementation plus a Supabase/Edge Function backend handoff. The `phoenix-portal` source is not present in this workspace, so applying backend changes is out of scope for this repository.

## Summary

Add a dedicated Profile root tab to the shared Compose bottom navigation. A normal tap opens a new `ProfileScreen`; a long press opens a profile-switcher sheet. Remove the Home screen's edge-swipe `ProfileSidePanel` and obsolete `ProfileSpeedDial` UI.

Move person-specific training preferences out of the global multiplatform Settings store and into a profile-scoped SQLDelight aggregate. Existing profiles receive a snapshot of today's global values during an idempotent startup migration; profiles created afterward receive clean product defaults. App-, device-, account-, and maintenance-level settings remain global.

The Profile screen combines a compact, exercise-specific insights dashboard with profile management and preference controls. Sync mirrors safe preference sections to a new Supabase `local_profile_preferences` relation associated with the existing `local_profiles` model. Sensitive safety and consent values remain profile-scoped but device-local.

## Goals

- Make Profile a first-class root destination.
- Make switching profiles switch all person-specific workout behavior without restarting the app.
- Establish SQLDelight as the source of truth for syncable profile preferences.
- Preserve existing behavior for every profile present at upgrade time.
- Keep sensitive safety and consent values off the network and out of backup exports.
- Reuse the existing exercise picker and exercise-detail analytics logic.
- Leave Settings with only global app/device/account/maintenance controls.
- Provide an executable Supabase SQL handoff and an exact Edge Function contract change list.

## Non-goals

- Applying or deploying Supabase migrations or Edge Functions from this repository.
- Redesigning the full `ExerciseDetailScreen` analytics experience.
- Changing workout-history, routine, PR, or profile-deletion ownership rules.
- Introducing client-side encryption or cross-device key management for the voice-stop phrase.
- Deleting legacy Settings keys in the same release as the migration.
- Reworking portal authentication or mapping one local profile to one Supabase auth user.
- Adding cross-device create/rename/delete synchronization for local-profile metadata. Preference pull applies only to profile IDs already present on the device; it does not invent a profile tombstone protocol.

## Approved product decisions

- Profiles own all person-specific training behavior, not merely the six originally listed fields.
- The remote parent is the existing `local_profiles` model, not account-level `user_profiles`.
- The voice-stop phrase, calibration state, and 18+ consent/prompt state are profile-scoped and device-local.
- All profiles existing at migration time receive the legacy global snapshot. New profiles start from defaults.
- `vbtEnabled` controls live workout VBT enforcement and feedback. It does not hide historical VBT data or disable assessment workflows.
- Rename and delete actions live on `ProfileScreen`; the switcher sheet switches and creates only.
- Exercise Insights is a compact dashboard with a link to the existing full exercise history.
- Backend work is delivered as a handoff artifact rather than modified in a second repository.
- The selected storage architecture is a dedicated 1:1 preference aggregate with per-section local timestamps, dirty flags, and server revisions.

## Existing-system constraints

- The current SQLDelight schema version is 42; `41.sqm` is the latest existing migration. The new migration is `42.sqm`, upgrading schema 42 to 43.
- `shared/build.gradle.kts` is stale at `version = 41` despite the presence of `41.sqm` and parity tests expecting logical schema 42. Implementation must reconcile the Gradle version, generated schema, parity constant, fallback map, and migration numbering together, ending at version 43 with `42.sqm` included.
- A `.sqm` migration can access only SQLite. Legacy preferences live in Android SharedPreferences and iOS NSUserDefaults through multiplatform Settings, so copying legacy values must happen in application code after the schema upgrade.
- `MigrationManager` already has access to the database, profile repository, and Settings store, providing the correct home for the idempotent cross-store migration.
- The current remote DTO is `LocalProfileDto(id, name, colorIndex)`, and pull already models `localProfiles`, but production pull does not apply that metadata locally.
- Equipment Rack is currently a JSON list of `RackItem` values, not a selectable rack entity. The migrated value is therefore the complete profile-owned rack catalog, not `equipment_rack_id`.
- Backup is a manually mapped, versioned JSON contract rather than a raw database copy. Version 4 exports one global `equipmentRackItems` list, so the new profile aggregate must be added explicitly to both buffered and streaming backup paths.
- `ExerciseDetailScreen` already implements most requested analytics: a velocity estimate, formula estimate, progression, volume, and recent history. Shared logic should be extracted rather than duplicated.
- `SettingsTab` is currently a large monolith. Preference controls must be extracted into focused reusable components before composing the new Profile screen.

## Preference ownership

### Profile-scoped and syncable

#### Core measurements

- `weightUnit`
- `weightIncrement`
- `bodyWeightKg`

#### Equipment Rack

- Complete `List<RackItem>` catalog, including enabled state, ordering, behavior, and weight

#### Workout behavior

- `stopAtTop`
- `beepsEnabled`
- `stallDetectionEnabled`
- `audioRepCountEnabled`
- `repCountTiming`
- `summaryCountdownSeconds`
- `autoStartCountdownSeconds`
- `gamificationEnabled`
- `autoStartRoutine`
- `countdownBeepsEnabled`
- `repSoundEnabled`
- `motionStartEnabled`
- `weightSuggestionsEnabled`
- `defaultRoutineExerciseUsePercentOfPR`
- `defaultRoutineExerciseWeightPercentOfPR`
- Just Lift defaults
- Per-exercise quick-start defaults
- `voiceStopEnabled` as user intent; effective voice-stop operation still requires a locally configured and calibrated phrase

#### LED behavior

- `colorScheme`
- `discoModeUnlocked`

`discoModeActive` remains transient BLE runtime state. It is neither persisted nor synchronized.

#### VBT behavior

- `vbtEnabled`
- `velocityLossThresholdPercent`
- `autoEndOnVelocityLoss`
- `defaultScalingBasis`
- `verbalEncouragementEnabled`
- `vulgarModeEnabled`
- `vulgarTier`
- `dominatrixModeUnlocked`
- `dominatrixModeActive`

### Profile-scoped and device-local

- `safeWord`
- `safeWordCalibrated`
- `adultsOnlyConfirmed`
- `adultsOnlyPrompted`

These values use profile-prefixed multiplatform Settings keys. They are excluded from portal DTOs and backup exports. If a synced `voiceStopEnabled` value arrives on a device without a local calibrated phrase, the UI shows that setup is required and the workout engine treats voice stop as disabled. Synced vulgar/Dominatrix preferences likewise remain ineffective until local adult consent is present.

### Global app/device/account preferences

- Theme mode and dynamic color
- Language
- Video loading/playback behavior
- Auto-backup and backup destination
- Manual backup/restore and destructive data-management actions
- Health and external integration configuration
- Portal authentication and sync status
- BLE compatibility mode
- Connection logs, diagnostics, and developer tools
- App-version and donation information
- Internal run-once flags such as `velocityOneRepMaxBackfillDone`

## Local data model

Create `UserProfilePreferences` as a 1:1 relation to `UserProfile`:

```sql
CREATE TABLE UserProfilePreferences (
    profile_id TEXT PRIMARY KEY NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    legacy_migration_version INTEGER NOT NULL DEFAULT 0,

    body_weight_kg REAL NOT NULL DEFAULT 0,
    weight_unit TEXT NOT NULL DEFAULT 'LB',
    weight_increment REAL NOT NULL DEFAULT -1,
    core_updated_at INTEGER NOT NULL DEFAULT 0,
    core_local_generation INTEGER NOT NULL DEFAULT 0,
    core_server_revision INTEGER NOT NULL DEFAULT 0,
    core_dirty INTEGER NOT NULL DEFAULT 1,

    equipment_rack_json TEXT NOT NULL DEFAULT '{"version":1,"items":[]}',
    rack_updated_at INTEGER NOT NULL DEFAULT 0,
    rack_local_generation INTEGER NOT NULL DEFAULT 0,
    rack_server_revision INTEGER NOT NULL DEFAULT 0,
    rack_dirty INTEGER NOT NULL DEFAULT 1,

    workout_preferences_json TEXT NOT NULL DEFAULT '{"version":1}',
    workout_updated_at INTEGER NOT NULL DEFAULT 0,
    workout_local_generation INTEGER NOT NULL DEFAULT 0,
    workout_server_revision INTEGER NOT NULL DEFAULT 0,
    workout_dirty INTEGER NOT NULL DEFAULT 1,

    led_color_scheme_id INTEGER NOT NULL DEFAULT 0,
    led_preferences_json TEXT NOT NULL DEFAULT '{"version":1}',
    led_updated_at INTEGER NOT NULL DEFAULT 0,
    led_local_generation INTEGER NOT NULL DEFAULT 0,
    led_server_revision INTEGER NOT NULL DEFAULT 0,
    led_dirty INTEGER NOT NULL DEFAULT 1,

    vbt_enabled INTEGER NOT NULL DEFAULT 1,
    vbt_preferences_json TEXT NOT NULL DEFAULT '{"version":1}',
    vbt_updated_at INTEGER NOT NULL DEFAULT 0,
    vbt_local_generation INTEGER NOT NULL DEFAULT 0,
    vbt_server_revision INTEGER NOT NULL DEFAULT 0,
    vbt_dirty INTEGER NOT NULL DEFAULT 1,

    FOREIGN KEY (profile_id) REFERENCES UserProfile(id) ON DELETE CASCADE
);

CREATE TABLE PendingProfileLocalCleanup (
    profile_id TEXT PRIMARY KEY NOT NULL,
    enqueued_at INTEGER NOT NULL
);
```

Each `*_updated_at` value is local audit/UI metadata, not a cross-device ordering authority. Each `*_local_generation` is a device-local monotonic counter incremented with every section edit and used to detect an edit racing an in-flight sync request; it is never serialized. Each `*_server_revision` is the last canonical revision acknowledged by Supabase. A local edit increments the generation and sets that section's dirty flag without changing its server revision. A canonical acknowledgement updates the revision and clears the dirty flag only when the current generation still matches the generation captured for the sent snapshot.

The canonical schema will add validation where SQLite can enforce it safely:

- body weight is `0` (unset) or 20–300 kg;
- weight unit is `KG` or `LB`;
- weight increment is `-1` (unit default) or positive;
- LED scheme IDs are non-negative;
- Boolean values are 0 or 1.

The JSON columns are versioned Kotlin documents decoded with `ignoreUnknownKeys = true` and explicit defaults. UI and domain code never manipulate raw JSON.

The schema change must be represented in all project migration paths:

- `VitruvianDatabase.sq` canonical table and queries;
- `migrations/42.sqm`, including default rows for existing profiles;
- fallback migration statements for source schema 42;
- `SchemaManifest` table/column reconciliation entries;
- schema version/parity tests updated to version 43.

Profile deletion explicitly removes preference rows as a fallback even when SQLite foreign-key cascading is unavailable on a platform connection.

### Serialized document contract

All local JSON and wire JSON uses the camelCase names below. Enum values use their current uppercase Kotlin names. The row-level `schema_version` versions the aggregate storage contract; every JSON document also has its own integer `version` so one section can evolve independently.

`RackPreferencesDocument` defaults to `{"version":1,"items":[]}`. Each item uses the existing `RackItem` fields: `id: String`, `name: String`, `category: String`, `weightKg: Float`, `behavior: String`, `enabled: Boolean`, `sortOrder: Int`, `createdAt: Long`, and `updatedAt: Long`.

`WorkoutPreferencesDocument` version 1 has these fields and defaults:

- `stopAtTop: Boolean = false`
- `beepsEnabled: Boolean = true`
- `stallDetectionEnabled: Boolean = true`
- `audioRepCountEnabled: Boolean = false`
- `repCountTiming: String = "TOP"`
- `summaryCountdownSeconds: Int = 10`
- `autoStartCountdownSeconds: Int = 5`
- `gamificationEnabled: Boolean = true`
- `autoStartRoutine: Boolean = false`
- `countdownBeepsEnabled: Boolean = true`
- `repSoundEnabled: Boolean = true`
- `motionStartEnabled: Boolean = false`
- `weightSuggestionsEnabled: Boolean = true`
- `defaultRoutineExerciseUsePercentOfPR: Boolean = false`
- `defaultRoutineExerciseWeightPercentOfPR: Int = 80`
- `voiceStopEnabled: Boolean = false`
- `justLiftDefaults: JustLiftDefaultsDocument`
- `singleExerciseDefaults: Map<String, SingleExerciseDefaultsDocument> = emptyMap()`

`JustLiftDefaultsDocument` preserves the existing serialized fields and defaults: `workoutModeId = 0`, `weightPerCableKg = 20`, `weightChangePerRep = 0`, `eccentricLoadPercentage = 100`, `echoLevelValue = 1`, `stallDetectionEnabled = true`, `repCountTimingName = "TOP"`, and `restSeconds = 60`.

`SingleExerciseDefaultsDocument` preserves the existing serialized fields: `exerciseId`, `setReps`, `weightPerCableKg`, `setWeightsPerCableKg`, `progressionKg`, `setRestSeconds`, `workoutModeId`, `eccentricLoadPercentage`, `echoLevelValue`, `duration`, `isAMRAP`, `perSetRestTime`, and `defaultRackItemIds`.

`LedPreferencesDocument` version 1 contains only `discoModeUnlocked: Boolean = false`; the selected scheme remains the typed `led_color_scheme_id` column.

`VbtPreferencesDocument` version 1 has these fields and defaults:

- `velocityLossThresholdPercent: Int = 20`
- `autoEndOnVelocityLoss: Boolean = false`
- `defaultScalingBasis: String = "MAX_WEIGHT_PR"`
- `verbalEncouragementEnabled: Boolean = false`
- `vulgarModeEnabled: Boolean = false`
- `vulgarTier: String = "STRONG"`
- `dominatrixModeUnlocked: Boolean = false`
- `dominatrixModeActive: Boolean = false`

The typed `vbt_enabled` column defaults to true. It is an independent master value and is never inferred from subordinate VBT fields after migration.

Decoders ignore unknown fields and apply the defaults above for missing fields. Encoders always emit the document version. The backend handoff uses the same object shapes in JSONB rather than embedding JSON strings inside JSON.

Validation preserves current product ranges: summary countdown is `-1`, `0`, or 5–30 seconds; auto-start countdown is 2–10 seconds; routine default percentage is 50–120; velocity-loss threshold is 10–50; rest time is 0/off or 5–300 seconds; weights must be finite and non-negative, with body weight using the separate 0/unset or 20–300 kg rule. Mobile validates before write, and the Edge Function repeats validation before accepting a mutation.

Repository decode results carry section validity alongside the typed value. Unknown fields and missing optional fields remain valid; malformed JSON, unsupported document versions, and invalid field values mark only that section invalid. UI may render safe typed defaults, but sync never serializes those fallback defaults. The section remains locally visible, diagnostically unsynchronized, and dirty until the user explicitly edits or resets it to a valid document.

## Kotlin models and repository boundary

Introduce typed models:

- `UserProfilePreferences`
- `CoreProfilePreferences`
- `WorkoutPreferences`
- `LedPreferences`
- `VbtPreferences`
- `ProfileLocalSafetyPreferences`
- `ActiveProfileContext`

`UserProfileRepository` remains the public profile façade and delegates persistence to focused internal stores. It exposes:

- active and all-profile identity streams;
- `ActiveProfileContext`, with `Switching` and `Ready(profile, preferences, localSafety)` states;
- observation by profile ID;
- typed update operations for core, rack, workout, LED, VBT, and local-safety sections;
- transactional SQL create-with-defaults and profile-data reassignment operations;
- idempotent, journaled cleanup of profile-prefixed Settings keys after SQL deletion commits.

Each section update modifies only that section, local timestamp, local generation, and dirty flag. `ActiveProfileContext` emits `Switching` before changing the active row and emits `Ready` only after the new profile's preferences and local safety values are loaded. Workout-start actions are disabled while context is `Switching`, preventing mixed-profile configuration.

`SettingsManager` remains a compatibility façade during this release:

- global fields continue reading/writing `PreferencesManager`;
- profile fields read/write the active `UserProfileRepository` context;
- a compatibility `UserPreferences` stream may combine both sources for existing consumers;
- `ProfileScreen` uses `UserProfileRepository` directly;
- no profile-specific setter writes to the legacy global store.

The equipment-rack repository becomes active-profile-aware and delegates its catalog to the rack section. Just Lift and per-exercise defaults move from unscoped Settings keys into the workout document. Health-imported body weight updates the active profile's core section.

SQL and multiplatform Settings cannot share a transaction. Profile deletion therefore inserts `PendingProfileLocalCleanup(profile_id)` in the same SQL transaction that removes/reassigns profile data. After commit, the repository removes the profile-prefixed Settings keys and deletes the cleanup row. Startup retries every queued cleanup. Once the SQL profile is gone, queued sensitive values are unreachable even if physical key deletion must be retried.

## Legacy migration

Migration has two coordinated stages.

### Stage 1: SQLDelight 42 → 43

`42.sqm` creates `UserProfilePreferences` and `PendingProfileLocalCleanup`, then inserts a default preference row for every `UserProfile` already in SQLite. This stage cannot read legacy Settings values.

### Stage 2: idempotent startup copy

After profiles are ensured, application code runs the following required migration behind a boot gate:

1. Upserts a default `UserProfilePreferences` row for every `UserProfile`. This heals a current-version database where schema reconciliation created the table after the numbered migration had already been recorded.
2. Reads one snapshot from the legacy Settings store, including rack and quick-start JSON.
3. Normalizes the snapshot section by section before persistence: non-finite or out-of-range numbers use that field's documented default; unknown enums use the current default; malformed JSON uses the section default; rack arrays retain only valid, uniquely identified items; and invalid nested entries are dropped without discarding valid siblings. Every normalization is logged with the profile-independent legacy key and reason, never the sensitive value.
4. Writes the normalized snapshot to every existing preference row whose `legacy_migration_version` is 0.
5. Uses one migration timestamp for every copied section, sets every copied section dirty, and leaves every server revision at 0.
6. Sets `vbtEnabled = true` for every migrated profile because live velocity-loss analysis/indicator behavior is enabled unconditionally in the pre-migration app. New profiles also default to true. Only an explicit user edit or canonical synced value can turn the master off; subordinate toggles never derive or silently change it.
7. Copies current safe-word/calibration and consent values into profile-prefixed local keys for every existing profile.
8. Marks each completed row with migration version 1.
9. Writes a global completion flag only after all existing rows and profile-local keys succeed.

On retry, rows already marked version 1 are not overwritten. New profiles are inserted with product defaults, dirty sections, server revision 0, and migration version 1. The active profile does not change during migration.

The existing fire-and-forget `MigrationManager.checkAndRunMigrations()` call is insufficient for this required copy. Add a required-migration state (`NotStarted`, `Applying`, `Ready`, `Failed`) and a suspending/retryable entry point. Splash/root navigation observes that state and does not construct the main navigation graph or enable workout start until `Ready`. `Failed` shows an error and Retry action; it does not continue with partially migrated preferences. Malformed legacy values do not cause `Failed` because they normalize as described above; `Failed` is reserved for storage/transaction failures that can succeed on retry. Existing non-critical repair passes may remain asynchronous after this required gate.

The same gate protects non-UI consumers. `SyncManager` may continue syncing unrelated workout entities while migration is not `Ready`, but it must neither read nor send profile-preference sections. Health imports and every background reader/writer of the new profile aggregate suspend or queue work until `Ready`; no consumer can observe or upload the temporary SQL defaults created by Stage 1.

Route the current Android `VitruvianApp` and common `KoinInit` migration triggers through one idempotent coordinator so initialization cannot run twice. Android and iOS/shared entry paths must both reach the same required state before rendering the main app.

Legacy keys remain in place for one release as downgrade protection, but all writes switch immediately to the new stores.

`vbtEnabled` gates live velocity-loss evaluation/auto-end, live VBT threshold/zone feedback, and verbal/vulgar/Dominatrix VBT-failure feedback. It does not gate assessments, historical velocity estimates, PR/history display, or the stored scaling-basis preference. Turning the master off preserves subordinate configuration except that an active Dominatrix mode is forced off; the permanent unlock flag remains set.

## Sync model and conflict resolution

Add an internal `ProfilePreferenceSectionSyncDto` in `SyncModels.kt`. Wire format uses one section mutation at a time so large rack or per-exercise-default documents can be chunked independently:

- `PortalProfilePreferenceSectionMutationDto(localProfileId, section, documentVersion, baseRevision, clientModifiedAt, payload)` for push;
- `PortalProfilePreferenceSectionCanonicalDto(localProfileId, section, documentVersion, serverRevision, serverUpdatedAt, payload)` for acknowledgements and pull;
- `ProfilePreferenceSectionRejectionDto(localProfileId, section, serverRevision, reason, canonicalSection = null)` for conflicts or validation failures.

IDs, section, and reason are strings; document versions are `Int`; revisions are `Long`; client/server timestamps are ISO-8601 strings; `canonicalSection` is nullable; and `payload` is a JSON object. `section` is one of `CORE`, `RACK`, `WORKOUT`, `LED`, or `VBT`.

The literal payload wrappers are:

```text
CORE:    {"bodyWeightKg":0.0,"weightUnit":"LB","weightIncrement":-1.0}
RACK:    {"version":1,"items":[]}
WORKOUT: {"version":1,"stopAtTop":false,"beepsEnabled":true,"stallDetectionEnabled":true,"audioRepCountEnabled":false,"repCountTiming":"TOP","summaryCountdownSeconds":10,"autoStartCountdownSeconds":5,"gamificationEnabled":true,"autoStartRoutine":false,"countdownBeepsEnabled":true,"repSoundEnabled":true,"motionStartEnabled":false,"weightSuggestionsEnabled":true,"defaultRoutineExerciseUsePercentOfPR":false,"defaultRoutineExerciseWeightPercentOfPR":80,"voiceStopEnabled":false,"justLiftDefaults":{"workoutModeId":0,"weightPerCableKg":20.0,"weightChangePerRep":0.0,"eccentricLoadPercentage":100,"echoLevelValue":1,"stallDetectionEnabled":true,"repCountTimingName":"TOP","restSeconds":60},"singleExerciseDefaults":{}}
LED:     {"ledColorSchemeId":0,"preferences":{"version":1,"discoModeUnlocked":false}}
VBT:     {"vbtEnabled":true,"preferences":{"version":1,"velocityLossThresholdPercent":20,"autoEndOnVelocityLoss":false,"defaultScalingBasis":"MAX_WEIGHT_PR","verbalEncouragementEnabled":false,"vulgarModeEnabled":false,"vulgarTier":"STRONG","dominatrixModeUnlocked":false,"dominatrixModeActive":false}}
```

The labels before each object above name examples and are not part of the JSON payload. `documentVersion` is 1 for `CORE`. For `RACK` and `WORKOUT`, it must equal the payload's `version`; for `LED` and `VBT`, it must equal `preferences.version`. A mismatch is a section validation failure. Rack items and per-exercise-default values use the exact field names already listed in the serialized document contract.

The additive wire fields are explicit:

- `PortalSyncPushRequest.profilePreferenceSections: List<PortalProfilePreferenceSectionMutationDto>? = null`;
- `PortalSyncPushResponse.profilePreferencesAccepted: Boolean? = null`, where `null` identifies a legacy backend;
- `PortalSyncPushResponse.canonicalProfilePreferenceSections: List<PortalProfilePreferenceSectionCanonicalDto> = emptyList()`;
- `PortalSyncPushResponse.profilePreferenceRejections: List<ProfilePreferenceSectionRejectionDto> = emptyList()`;
- `PortalSyncPullResponse.profilePreferenceSections: List<PortalProfilePreferenceSectionCanonicalDto>? = null`.

These constructor defaults are required so kotlinx.serialization can decode responses from an older backend that omits every additive field.

`SyncManager` first sends the existing `allProfiles` metadata snapshot so remote parent rows exist, even when no workout data is pending. Once required migration is `Ready`, it sends valid dirty preference sections through the same push endpoint in preference-only chunks capped at 512 KiB encoded size. A single encoded section is capped at 256 KiB. Invalid locally decoded sections are excluded rather than encoded from fallback defaults. Normal writes are validated before persistence; an oversized legacy section remains fully usable locally, is not truncated, and is reported as permanently unsynchronized while other sections continue. `profilePreferencesAccepted == true` means the backend recognized and evaluated the feature payload; canonical/rejection lists still determine each section's result.

Pull applies entities in this order:

1. Match preference rows to profile IDs already present locally. Log and ignore unknown profile IDs; never create or resurrect a profile from a preference row.
2. Merge matched profile preferences section by section.
3. Continue applying workouts, routines, PRs, and other existing entities.

The existing `localProfiles` response remains metadata for the portal and is not promoted to a mobile profile-lifecycle protocol in this feature. Active-profile selection remains device-local and is never synchronized.

Merge rules:

- local wall-clock timestamps are never used to order writes across devices;
- a local edit retains its last acknowledged `baseRevision` and marks the section dirty;
- the server accepts a mutation only when `baseRevision` matches the current section revision, then increments the revision and stamps server time;
- a revision mismatch returns the canonical server section and the server value wins for the sent snapshot, subject to the in-flight local-generation rule below;
- pull applies a higher server revision to a clean local section; a dirty section is resolved by its subsequent push rather than overwritten during pull;
- equal revisions with different content are treated as an invariant violation and repaired to the canonical server value;
- an invalid section is rejected independently while valid sections continue;
- when no newer local generation exists, a valid canonical server section returned for a revision conflict replaces the dirty local section; malformed or unsupported canonical data never replaces a valid local section;
- local-only safety and consent fields never enter conversion or merge code;
- resetting a section sends its explicit default payload as a new mutation; `null` or omission means “legacy/unsupported field,” not “reset.”

Every outbound section snapshot records its local generation in an in-memory request ledger. Response application is one SQL transaction that compares the row's current generation with the sent generation. If they match, an accepted canonical section or conflict canonical section applies normally and clears dirty state. If the current generation is newer, the response advances the stored server revision but preserves the newer local payload, timestamp, generation, and dirty state so it can be retried against that revision. Thus neither an acceptance nor a conflict response can overwrite an edit made while the request was in flight.

`PortalSyncAdapter` maps profile preference sections for push. `PortalPullAdapter` validates and maps the canonical server sections for pull. The SQLDelight repository applies all accepted sections for one profile in a transaction.

An older backend may ignore the optional request field without breaking workout sync. The client retains dirty local sections and does not claim they are synchronized until the new acknowledgement appears. Deployment documentation requires the database and Edge Functions to land before the mobile release.

## Backup and restore

Bump `CURRENT_BACKUP_VERSION` from 4 to 5 and add `profilePreferences: List<ProfilePreferencesBackup>` to `BackupContent`. `ProfilePreferencesBackup` contains a profile ID and five nullable syncable value sections so one corrupt section can be omitted without substituting typed defaults or dropping its valid siblings. A normal valid export includes all five. It excludes local/server timestamps, dirty flags, server revisions, the voice-stop phrase, calibration state, and adult consent/prompt state. Update both the buffered `exportAllData` path and the streaming writer/parser so they emit and consume the same v5 shape, and update the privacy metadata summary to mention profile preferences without claiming runtime secrets are present.

Import creates or matches `UserProfile` rows before applying preference rows. A restored section is treated as a new local edit: it keeps the target row's current acknowledged server revision, receives a fresh local timestamp, and becomes dirty. Sync then follows the ordinary revision-conflict contract; backup files never transplant portal revision state between installations or accounts.

For version 4 and older backups, the legacy top-level `equipmentRackItems` list retains its old global meaning. Track field presence separately from its decoded list: versions 1–3 normally omit it and therefore make no rack change, while a valid version-4 backup always contains it. A present non-empty list uses the existing merge-by-item-ID behavior for every profile represented by the backup that exists after profile import, or for the current active profile when the backup has no profiles. A present empty list explicitly clears those target profiles' racks. A version-4 file that omits the required field is treated as malformed for that section and leaves racks unchanged. Version-5 import uses `profilePreferences` and ignores the legacy top-level rack field.

Profile-preference import validates each section independently. A malformed section is reported and skipped without blocking valid sections or ordinary workout data. Existing backup compatibility and partial-import accounting remain intact.

## Supabase backend handoff

The implementation will provide two backend artifacts under `docs/backend-handoff/`:

- `profile-preferences-supabase.sql`
- `profile-preferences-edge-functions.md`

The SQL contract creates `public.local_profile_preferences` with:

- `user_id uuid NOT NULL`;
- `local_profile_id text NOT NULL`;
- a composite primary key on both columns;
- typed core/LED/VBT columns using `double precision` for measurements, `integer` for scheme IDs, and native `boolean` for flags;
- JSONB rack, workout, LED, and VBT documents;
- a non-negative `bigint` monotonically increasing server revision and server-generated `timestamptz` per section;
- an aggregate `updated_at timestamptz` maintained from section timestamps;
- a composite foreign key to the owning `local_profiles` row with delete cascade;
- ownership, JSON/range, and per-document encoded-size constraints, including a database-side 256 KiB ceiling as defense-in-depth behind Edge validation.

The primary key order is `(user_id, local_profile_id)`, so the leading `user_id` column used by RLS and the complete composite foreign key are indexed without an extra redundant index. JSONB documents do not receive speculative GIN indexes because sync looks up rows by the primary key and replaces whole validated sections rather than querying inside the documents.

The script begins with a preflight `DO` block that verifies the expected `local_profiles(user_id, id)` key contract and aborts with a descriptive error before making changes if the portal uses different names. This is necessary because the portal repository is not available here.

RLS is enabled before API grants. Use four owner policies targeted with `TO authenticated`: SELECT and DELETE use `USING ((select auth.uid()) = user_id)`; INSERT uses `WITH CHECK ((select auth.uid()) = user_id)`; UPDATE includes both the same `USING` and `WITH CHECK` expressions. The SELECT policy is required for UPDATE to work, and the UPDATE check prevents ownership reassignment. Do not use `auth.role()` or user-editable metadata for authorization.

The SQL handoff also makes Data API exposure explicit. Revoke all table privileges from `PUBLIC`, `anon`, and `authenticated`; grant the required CRUD privileges only to `service_role` for the server-side sync path. Direct authenticated INSERT/UPDATE would let a caller forge server revisions/timestamps or bypass Edge validation and request-size limits, and RLS cannot enforce column-transition invariants by itself. The owner RLS policies remain part of the schema contract and are tested as defense-in-depth, but they are not a substitute for the Edge mutation boundary. If the portal later exposes direct authenticated access, it must first move the full mutation protocol into a narrowly granted database API that enforces the same invariants; broad table writes remain prohibited. Grants and RLS are separate controls, and both decisions must be explicit because new `public` tables are no longer guaranteed automatic Data API grants. See Supabase's [2026 exposure change](https://supabase.com/changelog/45329-breaking-change-tables-not-exposed-to-data-and-graphql-api-automatically) and [current RLS guidance](https://supabase.com/docs/guides/database/postgres/row-level-security).

The Edge Function keeps platform `verify_jwt = true`; the mobile request supplies its Supabase user JWT in `Authorization`, not a publishable/secret key as a bearer token. The handler resolves the caller with `auth.getUser(userJwt)` and derives `user_id` only from that verified user; it never trusts a user ID supplied by the mobile payload. After verification it uses a server-only secret/service-role client for the short database transaction, always includes the verified `user_id` in the primary-key predicate, and never returns or logs the secret. Because `service_role` bypasses RLS, these explicit predicates, payload validation, and cross-user Edge tests are mandatory rather than relying on RLS to protect privileged queries. See Supabase's current [Edge Function authorization-header guidance](https://supabase.com/docs/guides/functions/auth-headers).

The Edge Function handoff specifies:

- additive request and response types;
- revision-checked section upserts rather than whole-row replacement or device-clock ordering;
- canonical accepted/rejected sections in the push response;
- per-profile, per-section rejection details;
- pull selection and response fields;
- schema-version validation;
- 256 KiB section and 512 KiB request limits with independent-section failure;
- validation and authentication happen before the database transaction, and no network call occurs while row locks are held;
- each mutation uses one atomic conditional `UPDATE ... WHERE user_id = ? AND local_profile_id = ? AND section_revision = ? RETURNING ...`. For a missing row, only `baseRevision = 0` may create it: `INSERT ... ON CONFLICT DO NOTHING RETURNING ...` writes the mutated section at revision 1 and leaves other sections at revision 0. If that insert returns no row, the same transaction runs the ordinary revision-checked UPDATE for the target section; it never uses an unconditional `DO UPDATE`. A zero-row conditional update then fetches the canonical conflict row in the same short transaction;
- cascade/deletion behavior inherited from `local_profiles`;
- deployment ordering and required server tests;
- portal implementation creates the real migration with `supabase migration new profile_preferences`, applies it in a disposable/local environment, runs Supabase security and performance advisors, and records a clean migration-list check before deployment.

## Navigation and profile switching

Add `NavigationRoutes.Profile` as a root tab. The bottom-bar order is:

1. Analytics
2. Workout
3. Insights
4. Profile
5. Settings

The bar remains icon-only. Profile uses a person icon. Because the existing four-item geometry (`24.dp` outer padding, spaced items, and a `64.dp` minimum inner width) cannot fit five items on a narrow phone, update the row to five equal-width cells with at most `8.dp` outer padding and no fixed `64.dp` minimum. Each cell keeps a full-width clickable surface at least `48.dp` high/wide, so the five destinations fit at 320 dp without sacrificing the minimum touch target.

The Profile navigation item uses `combinedClickable`:

- tap navigates to `ProfileScreen` with the existing root-tab save/restore behavior;
- long press provides haptic feedback and opens `ProfileSwitcherSheet` without navigation;
- semantics expose labeled click and long-click actions with `Role.Tab`.

`EnhancedMainScreen` owns the switcher-sheet state so it can open from any root tab where the bottom bar is visible. `ProfileScreen` also contains a visible Switch Profile action that opens the same sheet for accessibility and discoverability.

`ProfileSwitcherSheet` contains:

- existing profiles with avatar, name, and active indicator;
- tap-to-switch behavior;
- an Add Profile action;
- no rename or delete actions.

Creating a profile inserts default preferences atomically, activates the new profile, and dismisses the creation flow.

The Profile screen header owns rename/color and delete actions. The default profile cannot be deleted. Deleting another profile uses the existing data-reassignment behavior, warns the user explicitly, cleans preference/local-safety storage, and returns to Default if the active profile was removed.

Before exposing delete on the new screen, harden the existing reassignment transaction against profile-inclusive unique-index collisions:

- For duplicate `PersonalRecord` keys `(exerciseId, workoutMode, prType, phase)`, retain one deterministic best row. `MAX_WEIGHT` compares weight, then estimated 1RM, then achieved time; `MAX_VOLUME` compares volume, then weight, then achieved time.
- For duplicate earned badges, retain the earliest earned time and preserve a non-null celebration time when either row has one.
- Remove the losing duplicate before reassigning the remaining source rows, then run the existing gamification recomputation.

Tests must cover deletion into a target profile that already owns overlapping PR and badge keys.

Remove every `ProfileSidePanel` call site, including both `EnhancedMainScreen` and `JustLiftScreen`, plus the Home/root edge-swipe gesture. Delete `ProfileSidePanel` and obsolete speed-dial UI after extracting reusable profile dialogs, avatars, and colors into focused component files. Profile switching is intentionally unavailable after the user leaves a root tab for Just Lift or another workout flow; the user returns to a root tab before switching, which prevents mid-configuration profile changes.

## Profile screen

Use a focused `ProfileViewModel` rather than expanding `MainViewModel`. The screen is one scrolling surface with a profile header, compact insights, and grouped preferences.

### Profile header

- Active avatar, name, and color
- Switch Profile
- Edit name/color
- Guarded delete for non-default profiles
- Achievements entry point, because badges and gamification visibility are profile-scoped

### Compact Exercise Insights

- The selector opens the existing searchable/filterable `ExercisePickerDialog` with custom-exercise creation disabled.
- Selection is kept in a per-profile map for the lifetime of the current root-navigation state.
- Without a selection, choose the most recently completed exercise for that profile; profiles without history show an empty selector state.
- A profile change clears the currently rendered exercise while the new context loads, then restores that profile's still-valid saved selection. Only a profile with no saved selection resolves its most recently completed exercise. Switching back therefore restores the prior selection instead of recomputing it.

Extract a shared current-1RM resolver used by Profile and Exercise Detail. It returns both value and source:

1. latest passing velocity estimate for the active profile;
2. latest profile-scoped `AssessmentResult` for the exercise;
3. the most recent profile-scoped completed session's canonical hybrid estimate through `OneRepMaxCalculator`.

The global `Exercise.one_rep_max_kg` field is not a fallback because it is not profile-scoped and could leak one profile's manual value into another profile's dashboard.

The current assessment-save path must also receive the active profile ID explicitly. `AssessmentViewModel` and `saveAssessmentSession` may not fall back silently to `"default"`; assessment save, latest-assessment lookup, and the resolver above all use the same active profile ID. This is a prerequisite for claiming profile-correct 1RM data.

Historical velocity estimates and assessment actions remain visible even when `vbtEnabled` is false.

PR highlights show the applicable all-time max-weight, estimated-1RM, and volume highs for the selected movement. Recent history shows at most the latest five completed, non-deleted sessions plus a compact volume trend. View Full History opens the existing `ExerciseDetailScreen`.

Repository queries are scoped by profile and exercise and return only the data required for the compact dashboard. Loading, empty, missing-exercise, and malformed-metric states render independently.

### Preferences

Compose grouped cards from extracted reusable controls:

- Measurements
- Equipment Rack
- Workout Behavior
- LED
- VBT
- Safety

Complex groups may open a dialog or bottom sheet, but the row always shows its current value. Typed repository methods validate values before persistence; JSON encoding stays inside the data layer. Body weight supports 0/unset and the existing 20–300 kg range with unit conversion.

The Profile presentation applies these corrections without changing the database, document versions, or sync wire keys:

- The nested Profile scaffold consumes no system window insets. The outer app scaffold owns them, and the scrolling content keeps 12 dp between the top bar and profile card.
- Weight Increment is an explicit selector for the step used by routine and single-exercise per-set weight controls. Kilograms offer 0.5, 1, 2.5, and 5 kg; pounds offer 0.1, 0.5, 1, 2.5, and 5 lb. A legacy stored `-1` renders as the unit default without an automatic write. Changing units writes the explicit default of 0.5 kg or 1 lb.
- Default Rest, Rep Count Timing, Stop at Top, and Stall Detection remain decodable workout/exercise defaults but are not global Profile controls. VBT auto-end no longer depends on the profile-level stall-detection value.
- `defaultRoutineExerciseUsePercentOfPR` is presented as “Use % of PR for new routine exercises,” with explanatory copy. Its existing 50–120% seeding control exists only while the switch is enabled.
- LED selection is one horizontally scrolling radio group of eight 48 dp swatches built from the trainer's existing gradients. Off is crossed out; selection has a border, check, and visible localized name. The LED card title retains the seven-tap Disco Mode unlock.
- There is no standalone Adults Only action. Attempting to enable Vulgar Mode is the only age-confirmation entry point. Confirmation persists prompted/confirmed local safety before enabling Vulgar Mode. An under-18 response first writes Vulgar Mode and active Dominatrix Mode off, then records prompted/unconfirmed safety; partial failures remain retryable and fail closed.
- Dominatrix Mode is absent from the UI until permanently unlocked. With local adult confirmation, VBT, verbal encouragement, and Vulgar Mode all enabled, seven VBT title-bar taps within two seconds request the unlock. The whip sound and popup are emitted only from the matching successful post-commit event. Disabling VBT, verbal encouragement, or Vulgar Mode deactivates Dominatrix Mode without clearing its unlock flag.

## Settings pruning

Settings retains only:

- Portal account and cloud sync
- Theme and dynamic color
- Language
- Video behavior
- Health/external integrations
- Backup, restore, destination, and destructive data management
- BLE compatibility, logs, diagnostics, and developer tools
- Donation and app information

For integrations, the Settings entry point, platform permissions, provider authorization, and device/account diagnostics remain global. Imported measurements, profile-keyed cursors or last-sync markers, and other provider data keep their existing profile ownership. This feature does not rescope integration tables; it only ensures an imported body-weight value writes to the active profile's core preferences.

Remove weight unit/increment, body weight, equipment rack, workout behavior, audio, gamification controls, achievements navigation, LED, VBT, verbal feedback, and voice-stop controls from Settings.

Extract controls before moving them so neither `SettingsTab` nor `ProfileScreen` becomes another monolith. Shared components remain presentation-only and receive typed state/callbacks.

## Failure handling

- Local edits work offline and are immediately authoritative.
- A failed local update restores the prior UI value and presents a concise error.
- Profile switching exposes `Switching` state and blocks workout start until the new context is ready.
- Main navigation and workout actions remain behind the required-migration gate; a partial or failed copy shows Retry and is never recorded as complete.
- Profile-local Settings cleanup is journaled in SQL and retried after a partial cross-store delete.
- Invalid local JSON produces a logged section error and typed defaults without rewriting the raw value until the user makes a valid edit.
- Invalid remote JSON rejects only its section and retains valid local data.
- Oversized legacy sections remain local and visible, are never silently truncated, and report a section-specific sync diagnostic.
- Sync failures leave local data usable and retryable.
- An absent preference acknowledgement leaves preference sync pending while ordinary workout sync remains functional.

## Testing strategy

### Schema and migration

- Fresh schema version 43 contains both new tables, constraints, and queries.
- Migration 42 → 43 produces the same shape as a fresh database.
- Gradle SQLDelight version, generated schema version, parity constant, fallback map, and latest migration number all agree on version 43.
- Every historical schema upgrade still converges through parity tests.
- Fallback migration and reconciliation manifest cover both new tables and all preference columns.
- A logically current database missing the preference table or some profile rows is reconciled and seeded before legacy copy.
- The cross-store migration copies one legacy snapshot to every existing profile.
- Retry and partial-failure tests prove migrated rows are not overwritten.
- A deliberately delayed migration proves Splash/root navigation cannot expose workout start before `Ready`; failure exposes Retry.
- A deliberately delayed migration proves preference sync is suppressed and health/body-weight imports are queued until `Ready`, while unrelated workout sync may continue.
- Corrupt legacy primitives, enums, rack entries, and JSON documents normalize deterministically without trapping startup in a permanent Retry loop.
- New profiles receive defaults and do not inherit the active profile.
- The active profile remains unchanged during migration.
- Migrated and newly created profiles both default `vbtEnabled` to true.

### Repository and runtime isolation

- Profile A/B tests cover every preference section.
- Switching emits `Switching` then one consistent `Ready` context.
- Only the edited section's local timestamp, local generation, and dirty flag change; its server revision remains unchanged until acknowledgement.
- Rack, Just Lift, per-exercise defaults, body weight, LED, and VBT consumers follow the active profile.
- Disabling VBT stops live velocity-loss evaluation/auto-end and VBT failure feedback, deactivates Dominatrix Mode while preserving its unlock and other subordinate configuration, and leaves assessments/history visible.
- Profile deletion merges overlapping PR/badge keys, removes SQL preferences, and eventually removes profile-prefixed local keys through the cleanup journal.
- A failed Settings-key cleanup remains queued and succeeds on a later startup.
- Effective voice/vulgar features remain disabled without local setup/consent.

### Sync

- Serialization includes all syncable fields and excludes all local-only fields.
- Dirty preference sections use preference-only chunks after profile metadata has been pushed.
- Encoded section/request limits are enforced, and a multi-profile maximum-size test stays below the existing endpoint body limit.
- Pull ignores unknown profile IDs and never creates or resurrects a local profile.
- Base-revision accept, stale-revision reject, clean pull, dirty pull, retry-after-lost-ack, and equal-revision invariant cases converge deterministically without trusting device clocks.
- Accepted and conflict responses racing a newer local edit advance the base revision without overwriting the newer payload or clearing its dirty state.
- Malformed local JSON never pushes typed fallback defaults and becomes syncable only after explicit repair/reset.
- Malformed sections do not poison valid sections.
- Older-backend responses remain decodable and do not falsely acknowledge preferences.
- Backend handoff tests prove direct `anon`/`authenticated` table DML is denied by grants; authenticated Edge calls can mutate only the verified user's rows; cross-user IDs, forged revision/timestamp fields, invalid payloads, and oversized sections are rejected; and section-wise merge/canonical acknowledgement/pull round-trips succeed.
- RLS policy tests, run in an isolated transaction with only the temporary grants needed by the test harness, prove owner SELECT/INSERT/UPDATE/DELETE predicates and ownership-reassignment checks independently of the Edge authorization tests.
- Concurrent first writes for the same profile/section produce one revision-1 winner and one canonical conflict; concurrent first writes for two different sections both succeed without overwriting either section.
- Supabase security/performance advisors are run after applying the portal migration, with every finding either fixed or explicitly dispositioned in the backend handoff.

### Backup and restore

- Buffered and streaming v5 exports contain identical profile-preference values and exclude local safety and sync metadata.
- A v5 round-trip restores each profile's sections as valid local edits and marks them dirty against the target row's existing revision.
- Version 1–3 files with no rack field leave current racks unchanged; a version-4 non-empty rack merges into every target profile; a present empty version-4 rack clears every target profile.
- One malformed preference section is counted and skipped without blocking valid sections or workout entities.

### UI and navigation

- Bottom bar has five items in the approved order.
- Five equal-width bottom-bar targets fit at 320 dp and retain at least 48 dp touch targets in normal and compact-height layouts.
- Profile tap navigates; long press opens the sheet without navigating.
- Accessibility exposes both actions.
- Create activates a default-initialized profile.
- Rename/delete live on Profile, not the switcher sheet.
- No source file—including `EnhancedMainScreen` and `JustLiftScreen`—references the side panel or speed dial.
- Settings source no longer contains migrated controls.
- Assessment save/read paths and Insights use the active profile, correct 1RM precedence, correct PRs, and at most five recent sessions.
- Switching Profile A → B → A restores A's still-valid exercise selection; a profile without a saved selection resolves its own most recent exercise.
- Profile content begins approximately 12 dp below the top bar, exercise-level rows and the standalone Adults Only action are absent, and the LED choices form one compact horizontal radio group with 48 dp targets.
- Vulgar Mode alone opens age confirmation; under-18 responses visibly reset it. Dominatrix remains absent before unlock, and the seventh eligible VBT title tap produces the committed unlock popup/sound while six taps or a two-second reset do not.
- TalkBack traverses the modified switches and LED swatches once each with their role, selected/disabled state, and localized name.

## Acceptance criteria

- Switching profiles updates all person-specific preferences and workout behavior without restarting.
- No profile switch can start a workout with mixed-profile context.
- Existing profiles retain current behavior after upgrade; future profiles start clean.
- Syncable preference sections round-trip through the documented portal contract with section-level conflict handling.
- The voice phrase, calibration, and consent flags never appear in sync or backup payloads.
- A v5 backup round-trip preserves all syncable profile preference values for each profile without exporting sync metadata or device-local safety values.
- Standard Profile tap and long press behave distinctly and accessibly.
- Home and Just Lift have no slide-out profile selector; profile switching is confined to root-tab UI.
- Profile presents compact, profile-correct exercise analytics and links to full history.
- Settings contains only global app/device/account/maintenance configuration.
- Mobile remains usable offline and remains compatible with an older backend for non-preference sync.

## Delivery order

1. Produce and review the Supabase SQL and Edge Function handoff.
2. Reconcile schema-version metadata, add schema 43, typed models, cleanup journal, awaited migration gate, and legacy migration.
3. Add profile-preference sync DTOs, adapters, merge behavior, and tests.
4. Route current consumers through the new active profile context.
5. Harden overlapping-data profile deletion, then add Profile navigation, switcher sheet, and profile management header.
6. Build compact insights and grouped preference UI.
7. Prune Settings and remove the old Home panel/speed dial.
8. Run migration, repository, sync, navigation, UI-contract, and build verification.
