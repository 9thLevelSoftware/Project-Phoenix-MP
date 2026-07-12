# Profile Preferences Sync and Backend Handoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Synchronize the five approved profile-preference sections through the existing Supabase push/pull endpoint with revision-based conflict resolution, while delivering an executable, security-hardened backend handoff from the mobile repository.

**Architecture:** SQLDelight remains the local source of truth. Mobile takes valid dirty section snapshots, maps them to additive wire DTOs, sends metadata first and then preference-only payloads, and applies canonical acknowledgements with an in-flight local-generation ledger. Supabase stores one `local_profile_preferences` row per existing remote `local_profiles` parent, and an Edge-only, service-role-backed atomic mutation RPC owns server revisions and timestamps.

**Tech Stack:** Kotlin Multiplatform, kotlinx.serialization `JsonObject`, Coroutines/Flow, SQLDelight 2.2.1, Ktor, multiplatform-settings, Supabase Postgres/RLS, Supabase Edge Functions (Deno/TypeScript), Kotlin Test.

## Global Constraints

- Implement only the approved design in `docs/superpowers/specs/2026-07-11-profile-tab-preferences-design.md`.
- This plan depends on `docs/superpowers/plans/2026-07-11-profile-preferences-data-foundation.md`; complete its schema-43, typed-document, repository, required-migration-gate, and profile-local safety tasks before Task 3 here.
- The five syncable section names are exactly `CORE`, `RACK`, `WORKOUT`, `LED`, and `VBT`.
- `safeWord`, `safeWordCalibrated`, `adultsOnlyConfirmed`, and `adultsOnlyPrompted` are profile-prefixed local Settings values. They never enter sync DTOs, logs, backend SQL payloads, or backup payloads.
- `voiceStopEnabled` is syncable intent. Effective voice stop still requires a locally configured and calibrated phrase.
- Local wall-clock timestamps are audit metadata only. Cross-device ordering uses per-section server revisions exclusively.
- `localGeneration` is device-local, monotonic, and never serialized.
- The current Kotlin push request type is `PortalSyncPayload`; do not introduce a duplicate `PortalSyncPushRequest` type.
- All new request and response fields are additive and have backward-compatible constructor defaults.
- A single encoded preference mutation is at most 262,144 bytes. A payload containing `profilePreferenceSections` is at most 524,288 bytes. The existing 9,500,000-byte endpoint cap remains in force.
- Preference sections are sent only in preference-only pushes after an ordinary payload has sent `allProfiles`. They are never attached to workout/session batches.
- Pull never creates, renames, deletes, or resurrects a local profile. Unknown `localProfileId` values are logged and ignored.
- Direct `PUBLIC`, `anon`, and `authenticated` DML on `public.local_profile_preferences` is revoked. Remote mutation is Edge-only.
- Edge code verifies the user JWT, derives `user_id` from `auth.getUser`, and uses a server-only service-role client with an explicit verified `user_id` predicate for every privileged query.
- The service-role secret is never sent to mobile, returned in an Edge response, written to logs, or copied into either handoff artifact.
- Backend code is not deployed from this repository. The repository produces executable SQL and an exact portal implementation contract only.
- Keep the handoff aligned with Supabase's official [RLS guidance](https://supabase.com/docs/guides/database/postgres/row-level-security), [Edge authorization-header guidance](https://supabase.com/docs/guides/functions/auth-headers), and [2026 Data API exposure change](https://supabase.com/changelog/45329-breaking-change-tables-not-exposed-to-data-and-graphql-api-automatically); include these links in the Edge handoff artifact.
- Use PowerShell Gradle syntax on this Windows workspace and pass `-Pskip.supabase.check=true` to local verification commands.
- Preserve unrelated user changes and stage only files named by the current task.

---

## Dependency Contract from the Data-Foundation Plan

The data-foundation plan owns schema 43, the public profile façade, typed values, validation, and local document codecs. This plan consumes these exact foundation types without adding sync methods to public `UserProfileRepository` or `ProfilePreferencesRepository`:

```kotlin
enum class ProfilePreferenceSectionName { CORE, RACK, WORKOUT, LED, VBT }

data class ProfilePreferenceSection<T>(
    val value: T,
    val raw: String? = null,
    val validity: ProfilePreferenceValidity,
    val metadata: ProfileSectionMetadata,
)

object ProfilePreferencesValidator
object ProfilePreferencesCodec
```

The foundation also provides `CoreProfilePreferences`, `RackPreferences(version, items)`, `WorkoutPreferences`, `LedPreferences(colorScheme, ...)`, `VbtPreferences(enabled, ...)`, and the schema-43 `UserProfilePreferences` SQLDelight row. Local LED JSON deliberately omits row-owned `colorScheme`; local VBT JSON deliberately omits row-owned `enabled`. This plan's internal sync codec reconstructs those values into the approved complete wire wrappers and parses local JSON documents into JSON objects; it never serializes a stored JSON string as a nested string.

The foundation must expose the required migration state used by the Koin binding:

```kotlin
val requiredMigrationState: StateFlow<RequiredMigrationState>

sealed interface RequiredMigrationState {
    data object NotStarted : RequiredMigrationState
    data object Applying : RequiredMigrationState
    data object Ready : RequiredMigrationState
    data class Failed(val message: String) : RequiredMigrationState
}
```

This plan adds a focused internal `ProfilePreferenceSyncRepository` and `SqlDelightProfilePreferenceSyncRepository(database, codec)` over the same schema-43 table and codecs. Push outcomes are grouped into one SQL transaction per profile. Matching generations apply the canonical payload/revision and clear dirty state; a newer generation advances only the stored server revision while preserving the newer local payload, timestamp, generation, and dirty state.

## File Structure

### Backend handoff artifacts

- Create: `docs/backend-handoff/profile-preferences-supabase.sql` — executable table, constraints, RLS, grants, canonical projection, and atomic service-role RPC.
- Create: `docs/backend-handoff/profile-preferences-edge-functions.md` — exact TypeScript request/response, authentication, validation, push, pull, concurrency, testing, and deployment contract.
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/BackendHandoffContractTest.kt` — keeps the mobile-only handoff security and wire requirements from drifting.

### Mobile sync implementation

- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncModels.kt` — non-wire section keys, snapshots, canonicals, outcomes, and diagnostics.
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncDtos.kt` — additive serializable mutation/canonical/rejection DTOs and push/pull fields.
- Modify: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq` — dirty snapshot and generation/revision-guarded canonical merge queries.
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncRepository.kt` — internal persistence boundary and SQLDelight implementation grouped transactionally per profile.
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncCodec.kt` — strict typed-row-to-wire and canonical-to-column codec, including LED/VBT row-owned wrappers.
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalWireJson.kt` — one byte-identical JSON configuration for planning and transport.
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapter.kt` — local valid section to push mutation.
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalPullAdapter.kt` — canonical wire section validation and mapping.
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncPlanner.kt` — deterministic 256 KiB/512 KiB chunk planning and generation ledgers.
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt` — shared JSON and transport-level preference size enforcement.
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt` — readiness gating, metadata-first push, preference acknowledgements, per-request rate limiting, and preference-first pull merge.
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/SyncModule.kt` — inject the required-migration readiness function.
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakePortalApiClient.kt` — payload history, queued push responses, and pull profile capture.
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeProfilePreferenceSyncRepository.kt` — dirty snapshots and captured push/pull application results.
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeSyncRepository.kt` — optional pull-merge event hook for preference-before-entity ordering evidence.

### Focused tests

- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncDtosTest.kt`.
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapterProfilePreferencesTest.kt`.
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalPullAdapterProfilePreferencesTest.kt`.
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncPlannerTest.kt`.
- Create: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/sync/SqlDelightProfilePreferenceSyncRepositoryTest.kt`.
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalApiClientProfilePreferenceLimitsTest.kt`.
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/SyncManagerProfilePreferencesTest.kt`.
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalPushLimitsTest.kt`.
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalPullPaginationTest.kt`.
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/di/KoinModuleVerifyTest.kt` if constructor verification requires an explicit migration-state binding.

---

### Task 1: Create the Supabase Table, Constraints, RLS, and Grant Handoff

**Files:**
- Create: `docs/backend-handoff/profile-preferences-supabase.sql`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/BackendHandoffContractTest.kt`

**Interfaces:**
- Consumes: existing remote parent key `public.local_profiles(user_id uuid, id text)`.
- Produces: secured `public.local_profile_preferences(user_id, local_profile_id)` storage for Task 2's atomic RPC.

- [ ] **Step 1: Write the failing SQL handoff contract test**

```kotlin
package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackendHandoffContractTest {
    private fun sql(): String = assertNotNull(
        readProjectFile("docs/backend-handoff/profile-preferences-supabase.sql"),
        "Supabase handoff SQL must be tracked in the mobile repository",
    )

    private fun normalizedSql(): String {
        val compact = sql()
            .replace(Regex("""(?s)/[*].*?[*]/"""), " ")
            .lineSequence()
            .map { line -> line.substringBefore("--") }
            .joinToString(" ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return lowercaseOutsideLiterals(compact)
    }

    private fun lowercaseOutsideLiterals(value: String): String = buildString(value.length) {
        var inLiteral = false
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (
                character == '\'' &&
                inLiteral &&
                index + 1 < value.length &&
                value[index + 1] == '\''
            ) {
                append(character)
                append(value[index + 1])
                index += 2
                continue
            }
            if (character == '\'') {
                append(character)
                inLiteral = !inLiteral
            } else if (
                !inLiteral &&
                character == ' ' &&
                ((length > 0 && this[length - 1] == '(') ||
                    (index + 1 < value.length && value[index + 1] == ')'))
            ) {
                // Normalize formatting-only whitespace inside SQL parentheses.
            } else {
                append(if (inLiteral) character else character.lowercaseChar())
            }
            index += 1
        }
    }

    private fun tableEntries(sql: String): List<String> {
        val body = assertNotNull(
            Regex(
                """(?s)\bcreate table public[.]local_profile_preferences\s*[(](.*?)[)]\s*;""",
            ).find(sql),
            "Expected one executable local_profile_preferences table declaration",
        ).groupValues[1]
        return splitTopLevel(body)
    }

    private fun splitTopLevel(value: String): List<String> {
        val entries = mutableListOf<String>()
        var depth = 0
        var inLiteral = false
        var start = 0
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (
                character == '\'' &&
                inLiteral &&
                index + 1 < value.length &&
                value[index + 1] == '\''
            ) {
                index += 2
                continue
            }
            when {
                character == '\'' -> inLiteral = !inLiteral
                !inLiteral && character == '(' -> depth += 1
                !inLiteral && character == ')' -> depth -= 1
                !inLiteral && character == ',' && depth == 0 -> {
                    entries += value.substring(start, index).trim()
                    start = index + 1
                }
            }
            index += 1
        }
        entries += value.substring(start).trim()
        return entries.filter { entry -> entry.isNotEmpty() }
    }

    @Test
    fun sqlHandoffDeclaresTheExactProfilePreferenceSchema() {
        val sql = normalizedSql()
        val entries = tableEntries(sql)
        val columns = entries.filterNot { entry -> entry.startsWith("constraint ") }
        assertEquals(
            listOf(
                "user_id uuid not null",
                "local_profile_id text not null",
                "schema_version integer not null default 1 check (schema_version = 1)",
                "body_weight_kg double precision not null default 0",
                "weight_unit text not null default 'LB'",
                "weight_increment double precision not null default -1",
                "core_revision bigint not null default 0 check (core_revision >= 0)",
                "core_updated_at timestamptz not null default now()",
                """equipment_rack jsonb not null default '{"version":1,"items":[]}'::jsonb""",
                "rack_revision bigint not null default 0 check (rack_revision >= 0)",
                "rack_updated_at timestamptz not null default now()",
                """workout_preferences jsonb not null default '{"version":1}'::jsonb""",
                "workout_revision bigint not null default 0 check (workout_revision >= 0)",
                "workout_updated_at timestamptz not null default now()",
                "led_color_scheme_id integer not null default 0",
                """led_preferences jsonb not null default '{"version":1,"discoModeUnlocked":false}'::jsonb""",
                "led_revision bigint not null default 0 check (led_revision >= 0)",
                "led_updated_at timestamptz not null default now()",
                "vbt_enabled boolean not null default true",
                """vbt_preferences jsonb not null default '{"version":1,"velocityLossThresholdPercent":20,"autoEndOnVelocityLoss":false,"defaultScalingBasis":"MAX_WEIGHT_PR","verbalEncouragementEnabled":false,"vulgarModeEnabled":false,"vulgarTier":"STRONG","dominatrixModeUnlocked":false,"dominatrixModeActive":false}'::jsonb""",
                "vbt_revision bigint not null default 0 check (vbt_revision >= 0)",
                "vbt_updated_at timestamptz not null default now()",
                "updated_at timestamptz generated always as (" +
                    "greatest(core_updated_at, rack_updated_at, workout_updated_at, " +
                    "led_updated_at, vbt_updated_at)) stored",
            ),
            columns,
        )

        val constraintPairs = entries
            .filter { entry -> entry.startsWith("constraint ") }
            .map { entry ->
                val match = assertNotNull(
                    Regex("""^constraint ([a-z_][a-z0-9_]*)\b""").find(entry),
                    "Every table constraint must be explicitly named",
                )
                match.groupValues[1] to entry
            }
        val constraints = constraintPairs.toMap()
        assertEquals(constraintPairs.size, constraints.size, "Constraint names must be unique")

        val expectedConstraints = mapOf(
            "local_profile_preferences_pkey" to
                "constraint local_profile_preferences_pkey " +
                "primary key (user_id, local_profile_id)",
            "local_profile_preferences_parent_fkey" to
                "constraint local_profile_preferences_parent_fkey " +
                "foreign key (user_id, local_profile_id) " +
                "references public.local_profiles(user_id, id) on delete cascade",
            "local_profile_preferences_body_weight_check" to
                "constraint local_profile_preferences_body_weight_check check (" +
                "body_weight_kg not in (" +
                "'NaN'::double precision, 'Infinity'::double precision, " +
                "'-Infinity'::double precision) and " +
                "(body_weight_kg = 0 or body_weight_kg between 20 and 300))",
            "local_profile_preferences_weight_unit_check" to
                "constraint local_profile_preferences_weight_unit_check " +
                "check (weight_unit in ('KG', 'LB'))",
            "local_profile_preferences_weight_increment_check" to
                "constraint local_profile_preferences_weight_increment_check check (" +
                "weight_increment not in (" +
                "'NaN'::double precision, 'Infinity'::double precision, " +
                "'-Infinity'::double precision) and " +
                "(weight_increment = -1 or weight_increment > 0))",
            "local_profile_preferences_led_scheme_check" to
                "constraint local_profile_preferences_led_scheme_check " +
                "check (led_color_scheme_id >= 0)",
            "local_profile_preferences_rack_object_check" to
                "constraint local_profile_preferences_rack_object_check check (" +
                "jsonb_typeof(equipment_rack) = 'object' and " +
                """equipment_rack @> '{"version":1}'::jsonb and """ +
                "jsonb_typeof(equipment_rack -> 'items') = 'array' and " +
                "not jsonb_path_exists(" +
                "equipment_rack, '$.items[*] ? (@.weightKg < 0)') and " +
                "octet_length(equipment_rack::text) <= 262144)",
            "local_profile_preferences_workout_object_check" to
                "constraint local_profile_preferences_workout_object_check check (" +
                "jsonb_typeof(workout_preferences) = 'object' and " +
                """workout_preferences @> '{"version":1}'::jsonb and """ +
                "(not workout_preferences ? 'summaryCountdownSeconds' or (" +
                "jsonb_typeof(workout_preferences -> 'summaryCountdownSeconds') = " +
                "'number' and " +
                "(workout_preferences ->> 'summaryCountdownSeconds')::integer " +
                "in (-1, 0, 5, 10, 15, 20, 25, 30))) and " +
                "(not workout_preferences ? 'autoStartCountdownSeconds' or (" +
                "jsonb_typeof(workout_preferences -> 'autoStartCountdownSeconds') = " +
                "'number' and " +
                "(workout_preferences ->> 'autoStartCountdownSeconds')::integer " +
                "between 2 and 10)) and " +
                "(not workout_preferences ? " +
                "'defaultRoutineExerciseWeightPercentOfPR' or (" +
                "jsonb_typeof(workout_preferences -> " +
                "'defaultRoutineExerciseWeightPercentOfPR') = 'number' and " +
                "(workout_preferences ->> " +
                "'defaultRoutineExerciseWeightPercentOfPR')::integer " +
                "between 50 and 120)) and " +
                "octet_length(workout_preferences::text) <= 262144)",
            "local_profile_preferences_led_object_check" to
                "constraint local_profile_preferences_led_object_check check (" +
                "jsonb_typeof(led_preferences) = 'object' and " +
                """led_preferences @> '{"version":1}'::jsonb and """ +
                "jsonb_typeof(led_preferences -> 'discoModeUnlocked') = " +
                "'boolean' and octet_length(led_preferences::text) <= 262144)",
            "local_profile_preferences_vbt_object_check" to
                "constraint local_profile_preferences_vbt_object_check check (" +
                "jsonb_typeof(vbt_preferences) = 'object' and " +
                """vbt_preferences @> '{"version":1}'::jsonb and """ +
                "jsonb_typeof(vbt_preferences -> " +
                "'velocityLossThresholdPercent') = 'number' and " +
                "(vbt_preferences ->> 'velocityLossThresholdPercent')::integer " +
                "between 10 and 50 and " +
                "octet_length(vbt_preferences::text) <= 262144)",
        )
        assertEquals(expectedConstraints, constraints)

        assertEquals(
            "updated_at timestamptz generated always as (" +
                "greatest(core_updated_at, rack_updated_at, workout_updated_at, " +
                "led_updated_at, vbt_updated_at)) stored",
            columns.single { column -> column.startsWith("updated_at ") },
        )

        val aggregate = "array_agg(attribute.attname::text order by key_column.ordinality)"
        assertEquals(2, Regex(Regex.escape(aggregate)).findAll(sql).count())
        assertTrue(sql.contains("select " + aggregate + " into matching_key"))
        assertTrue(
            sql.contains(
                "having " + aggregate + " = array['user_id', 'id']::text[]",
            ),
        )

        val workoutConstraint = constraints.getValue(
            "local_profile_preferences_workout_object_check",
        )
        val countdownSets = Regex(
            """[(]workout_preferences ->> 'summaryCountdownSeconds'[)]::integer """ +
                """in [(]([^)]*)[)]""",
        ).findAll(workoutConstraint).toList()
        assertEquals(1, countdownSets.size)
        assertEquals("-1, 0, 5, 10, 15, 20, 25, 30", countdownSets.single().groupValues[1])

        mapOf(
            "local_profile_preferences_rack_object_check" to
                "octet_length(equipment_rack::text) <= 262144",
            "local_profile_preferences_workout_object_check" to
                "octet_length(workout_preferences::text) <= 262144",
            "local_profile_preferences_led_object_check" to
                "octet_length(led_preferences::text) <= 262144",
            "local_profile_preferences_vbt_object_check" to
                "octet_length(vbt_preferences::text) <= 262144",
        ).forEach { (name, ceiling) ->
            assertTrue(constraints.getValue(name).contains(ceiling))
        }
    }

    @Test
    fun sqlHandoffSecuresTheExactProfilePreferenceSurface() {
        val sql = normalizedSql()
        val statements = sql.split(';')
            .map { statement -> statement.trim() }
            .filter { statement -> statement.isNotEmpty() }
        assertTrue(
            statements.contains(
                "alter table public.local_profile_preferences enable row level security",
            ),
        )
        assertFalse(
            Regex("""\bdisable\s+row\s+level\s+security\b""").containsMatchIn(sql),
            "The handoff must never disable RLS",
        )

        val expectedPolicies = mapOf(
            "local_profile_preferences_owner_select" to
                "create policy local_profile_preferences_owner_select " +
                "on public.local_profile_preferences for select to authenticated " +
                "using ((select auth.uid()) = user_id)",
            "local_profile_preferences_owner_insert" to
                "create policy local_profile_preferences_owner_insert " +
                "on public.local_profile_preferences for insert to authenticated " +
                "with check ((select auth.uid()) = user_id)",
            "local_profile_preferences_owner_update" to
                "create policy local_profile_preferences_owner_update " +
                "on public.local_profile_preferences for update to authenticated " +
                "using ((select auth.uid()) = user_id) " +
                "with check ((select auth.uid()) = user_id)",
            "local_profile_preferences_owner_delete" to
                "create policy local_profile_preferences_owner_delete " +
                "on public.local_profile_preferences for delete to authenticated " +
                "using ((select auth.uid()) = user_id)",
        )
        val createPolicyPattern = Regex("""^create\s+policy\b""")
        val policyStatements = statements.filter { statement ->
            createPolicyPattern.containsMatchIn(statement)
        }
        assertEquals(expectedPolicies.size, policyStatements.size)
        assertEquals(expectedPolicies.values.toSet(), policyStatements.toSet())
        assertEquals(
            expectedPolicies.keys,
            policyStatements
                .map { statement ->
                    statement.removePrefix("create policy ").substringBefore(" on ")
                }
                .toSet(),
        )

        val forbiddenPolicyDdl = Regex("""^(?:alter|drop)\s+policy\b""")
        assertFalse(
            statements.any { statement -> forbiddenPolicyDdl.containsMatchIn(statement) },
            "ALTER POLICY and DROP POLICY are forbidden in the handoff",
        )

        assertTrue("revoke all on table public.local_profile_preferences from public" in statements)
        assertTrue("revoke all on table public.local_profile_preferences from anon" in statements)
        assertTrue(
            "revoke all on table public.local_profile_preferences from authenticated" in statements,
        )

        val grantPattern = Regex(
            """^grant\s+(.+?)\s+on\s+(?:table\s+)?(.+?)\s+to\s+(.+)$""",
        )
        val tableGrants = statements.mapNotNull { statement ->
            grantPattern.matchEntire(statement)
        }.filter { grant ->
            grant.groupValues[2].split(',').any { target ->
                target.trim() == "public.local_profile_preferences"
            }
        }
        val forbiddenClientRole = Regex("""\b(?:public|anon|authenticated)\b""")
        assertFalse(
            tableGrants.any { grant ->
                forbiddenClientRole.containsMatchIn(grant.groupValues[3])
            },
            "Client roles must not receive direct table grants",
        )
        assertEquals(1, tableGrants.size, "Only the service-role table grant is allowed")
        assertEquals(
            "select, insert, update, delete",
            tableGrants.single().groupValues[1].trim(),
        )
        assertEquals("public.local_profile_preferences", tableGrants.single().groupValues[2].trim())
        assertEquals("service_role", tableGrants.single().groupValues[3].trim())

        val forbiddenLocalFieldPatterns = mapOf(
            "local safety or consent" to Regex(
                """(?i)\b[a-z0-9_]*(?:local_?safety|safe_?word(?:_?(?:calibrated|calibration))?|""" +
                    """adults?_?only_?(?:confirmed|prompted|consent)|adult_?consent)\b""",
            ),
            "local generation" to Regex("""(?i)\b[a-z0-9_]*local_?generation\b"""),
            "dirty state" to Regex("""(?i)\b[a-z0-9_]*dirty\b"""),
            "legacy migration version" to
                Regex(
                    """(?i)\b[a-z0-9_]*(?:legacy_migration_version|""" +
                        """legacymigrationversion)\b""",
                ),
        )
        forbiddenLocalFieldPatterns.forEach { (field, pattern) ->
            assertFalse(
                pattern.containsMatchIn(sql),
                "Local-only field must not enter backend SQL: $field",
            )
        }
    }
}
```

- [ ] **Step 2: Run the test and verify the missing artifact failure**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "com.devil.phoenixproject.data.sync.BackendHandoffContractTest" -Pskip.supabase.check=true
```

Expected: FAIL because `docs/backend-handoff/profile-preferences-supabase.sql` does not exist.

- [ ] **Step 3: Create the executable SQL preflight and table**

Create `docs/backend-handoff/profile-preferences-supabase.sql` with this table contract. Keep the preflight before all DDL so a mismatched portal schema aborts without partial changes.

```sql
BEGIN;

DO $preflight$
DECLARE
    matching_key text[];
BEGIN
    IF to_regclass('public.local_profiles') IS NULL THEN
        RAISE EXCEPTION 'profile preferences preflight: public.local_profiles does not exist';
    END IF;

    SELECT array_agg(attribute.attname::text ORDER BY key_column.ordinality)
      INTO matching_key
      FROM pg_constraint constraint_row
      CROSS JOIN LATERAL unnest(constraint_row.conkey)
          WITH ORDINALITY AS key_column(attnum, ordinality)
      JOIN pg_attribute attribute
        ON attribute.attrelid = constraint_row.conrelid
       AND attribute.attnum = key_column.attnum
     WHERE constraint_row.conrelid = 'public.local_profiles'::regclass
       AND constraint_row.contype IN ('p', 'u')
     GROUP BY constraint_row.oid
    HAVING array_agg(attribute.attname::text ORDER BY key_column.ordinality)
           = ARRAY['user_id', 'id']::text[]
     LIMIT 1;

    IF matching_key IS NULL THEN
        RAISE EXCEPTION
            'profile preferences preflight: expected unique local_profiles(user_id, id) parent key';
    END IF;
END
$preflight$;

CREATE TABLE public.local_profile_preferences (
    user_id uuid NOT NULL,
    local_profile_id text NOT NULL,
    schema_version integer NOT NULL DEFAULT 1 CHECK (schema_version = 1),

    body_weight_kg double precision NOT NULL DEFAULT 0,
    weight_unit text NOT NULL DEFAULT 'LB',
    weight_increment double precision NOT NULL DEFAULT -1,
    core_revision bigint NOT NULL DEFAULT 0 CHECK (core_revision >= 0),
    core_updated_at timestamptz NOT NULL DEFAULT now(),

    equipment_rack jsonb NOT NULL DEFAULT '{"version":1,"items":[]}'::jsonb,
    rack_revision bigint NOT NULL DEFAULT 0 CHECK (rack_revision >= 0),
    rack_updated_at timestamptz NOT NULL DEFAULT now(),

    workout_preferences jsonb NOT NULL DEFAULT '{"version":1}'::jsonb,
    workout_revision bigint NOT NULL DEFAULT 0 CHECK (workout_revision >= 0),
    workout_updated_at timestamptz NOT NULL DEFAULT now(),

    led_color_scheme_id integer NOT NULL DEFAULT 0,
    led_preferences jsonb NOT NULL DEFAULT '{"version":1,"discoModeUnlocked":false}'::jsonb,
    led_revision bigint NOT NULL DEFAULT 0 CHECK (led_revision >= 0),
    led_updated_at timestamptz NOT NULL DEFAULT now(),

    vbt_enabled boolean NOT NULL DEFAULT true,
    vbt_preferences jsonb NOT NULL DEFAULT '{"version":1,"velocityLossThresholdPercent":20,"autoEndOnVelocityLoss":false,"defaultScalingBasis":"MAX_WEIGHT_PR","verbalEncouragementEnabled":false,"vulgarModeEnabled":false,"vulgarTier":"STRONG","dominatrixModeUnlocked":false,"dominatrixModeActive":false}'::jsonb,
    vbt_revision bigint NOT NULL DEFAULT 0 CHECK (vbt_revision >= 0),
    vbt_updated_at timestamptz NOT NULL DEFAULT now(),

    updated_at timestamptz GENERATED ALWAYS AS (
        greatest(core_updated_at, rack_updated_at, workout_updated_at, led_updated_at, vbt_updated_at)
    ) STORED,

    CONSTRAINT local_profile_preferences_pkey PRIMARY KEY (user_id, local_profile_id),
    CONSTRAINT local_profile_preferences_parent_fkey
        FOREIGN KEY (user_id, local_profile_id)
        REFERENCES public.local_profiles(user_id, id)
        ON DELETE CASCADE,
    CONSTRAINT local_profile_preferences_body_weight_check CHECK (
        body_weight_kg NOT IN ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        AND (body_weight_kg = 0 OR body_weight_kg BETWEEN 20 AND 300)
    ),
    CONSTRAINT local_profile_preferences_weight_unit_check CHECK (weight_unit IN ('KG', 'LB')),
    CONSTRAINT local_profile_preferences_weight_increment_check CHECK (
        weight_increment NOT IN ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        AND (weight_increment = -1 OR weight_increment > 0)
    ),
    CONSTRAINT local_profile_preferences_led_scheme_check CHECK (led_color_scheme_id >= 0),
    CONSTRAINT local_profile_preferences_rack_object_check CHECK (
        jsonb_typeof(equipment_rack) = 'object'
        AND equipment_rack @> '{"version":1}'::jsonb
        AND jsonb_typeof(equipment_rack -> 'items') = 'array'
        AND NOT jsonb_path_exists(equipment_rack, '$.items[*] ? (@.weightKg < 0)')
        AND octet_length(equipment_rack::text) <= 262144
    ),
    CONSTRAINT local_profile_preferences_workout_object_check CHECK (
        jsonb_typeof(workout_preferences) = 'object'
        AND workout_preferences @> '{"version":1}'::jsonb
        AND (
            NOT workout_preferences ? 'summaryCountdownSeconds'
            OR (
                jsonb_typeof(workout_preferences -> 'summaryCountdownSeconds') = 'number'
                AND (workout_preferences ->> 'summaryCountdownSeconds')::integer
                    IN (-1, 0, 5, 10, 15, 20, 25, 30)
            )
        )
        AND (
            NOT workout_preferences ? 'autoStartCountdownSeconds'
            OR (
                jsonb_typeof(workout_preferences -> 'autoStartCountdownSeconds') = 'number'
                AND (workout_preferences ->> 'autoStartCountdownSeconds')::integer BETWEEN 2 AND 10
            )
        )
        AND (
            NOT workout_preferences ? 'defaultRoutineExerciseWeightPercentOfPR'
            OR (
                jsonb_typeof(workout_preferences -> 'defaultRoutineExerciseWeightPercentOfPR') = 'number'
                AND (workout_preferences ->> 'defaultRoutineExerciseWeightPercentOfPR')::integer BETWEEN 50 AND 120
            )
        )
        AND octet_length(workout_preferences::text) <= 262144
    ),
    CONSTRAINT local_profile_preferences_led_object_check CHECK (
        jsonb_typeof(led_preferences) = 'object'
        AND led_preferences @> '{"version":1}'::jsonb
        AND jsonb_typeof(led_preferences -> 'discoModeUnlocked') = 'boolean'
        AND octet_length(led_preferences::text) <= 262144
    ),
    CONSTRAINT local_profile_preferences_vbt_object_check CHECK (
        jsonb_typeof(vbt_preferences) = 'object'
        AND vbt_preferences @> '{"version":1}'::jsonb
        AND jsonb_typeof(vbt_preferences -> 'velocityLossThresholdPercent') = 'number'
        AND (vbt_preferences ->> 'velocityLossThresholdPercent')::integer BETWEEN 10 AND 50
        AND octet_length(vbt_preferences::text) <= 262144
    )
);
```

- [ ] **Step 4: Add RLS policies and explicit Data API privileges**

Append this security block before the final `COMMIT`:

```sql
ALTER TABLE public.local_profile_preferences ENABLE ROW LEVEL SECURITY;

CREATE POLICY local_profile_preferences_owner_select
    ON public.local_profile_preferences
    FOR SELECT TO authenticated
    USING ((select auth.uid()) = user_id);

CREATE POLICY local_profile_preferences_owner_insert
    ON public.local_profile_preferences
    FOR INSERT TO authenticated
    WITH CHECK ((select auth.uid()) = user_id);

CREATE POLICY local_profile_preferences_owner_update
    ON public.local_profile_preferences
    FOR UPDATE TO authenticated
    USING ((select auth.uid()) = user_id)
    WITH CHECK ((select auth.uid()) = user_id);

CREATE POLICY local_profile_preferences_owner_delete
    ON public.local_profile_preferences
    FOR DELETE TO authenticated
    USING ((select auth.uid()) = user_id);

REVOKE ALL ON TABLE public.local_profile_preferences FROM PUBLIC;
REVOKE ALL ON TABLE public.local_profile_preferences FROM anon;
REVOKE ALL ON TABLE public.local_profile_preferences FROM authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.local_profile_preferences TO service_role;

COMMIT;
```

- [ ] **Step 5: Run the contract test and verify it passes**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "com.devil.phoenixproject.data.sync.BackendHandoffContractTest" -Pskip.supabase.check=true
```

Expected: PASS.

- [ ] **Step 6: Commit the secured table handoff**

```powershell
git add docs/backend-handoff/profile-preferences-supabase.sql shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/BackendHandoffContractTest.kt
git commit -m "docs(sync): add secured profile preferences schema handoff"
```

---

### Task 2: Add the Atomic Mutation RPC and Edge Function Contract

**Files:**
- Modify: `docs/backend-handoff/profile-preferences-supabase.sql`
- Create: `docs/backend-handoff/profile-preferences-edge-functions.md`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/BackendHandoffContractTest.kt`

**Interfaces:**
- Consumes: secured table from Task 1.
- Produces: service-role-only `mutate_local_profile_preference_section` RPC and exact portal push/pull implementation instructions.

- [ ] **Step 1: Extend the contract test for atomic mutation and Edge authorization**

Add these tests to `BackendHandoffContractTest`:

```kotlin
private fun edgeContract(): String = assertNotNull(
    readProjectFile("docs/backend-handoff/profile-preferences-edge-functions.md"),
    "Edge Function handoff must be tracked in the mobile repository",
)

@Test
fun `sql handoff exposes only a service role mutation rpc`() {
    val sql = sql()
    assertTrue(sql.contains("CREATE FUNCTION public.mutate_local_profile_preference_section"))
    assertTrue(sql.contains("core_revision = p_base_revision"))
    assertTrue(sql.contains("rack_revision = p_base_revision"))
    assertTrue(sql.contains("workout_revision = p_base_revision"))
    assertTrue(sql.contains("led_revision = p_base_revision"))
    assertTrue(sql.contains("vbt_revision = p_base_revision"))
    assertTrue(sql.contains("ON CONFLICT DO NOTHING"))
    assertFalse(sql.contains("ON CONFLICT DO UPDATE"))
    assertTrue(sql.contains("REVOKE ALL ON FUNCTION public.mutate_local_profile_preference_section"))
    assertTrue(sql.contains("GRANT EXECUTE ON FUNCTION public.mutate_local_profile_preference_section"))
    assertTrue(sql.contains("GRANT EXECUTE ON FUNCTION public.local_profile_preference_section_canonical"))
    assertTrue(sql.contains("TO service_role"))
}

@Test
fun `edge handoff derives user identity and enforces section limits`() {
    val contract = edgeContract()
    assertTrue(contract.contains("auth.getUser(userJwt)"))
    assertTrue(contract.contains("SUPABASE_SERVICE_ROLE_KEY"))
    assertTrue(contract.contains("262_144"))
    assertTrue(contract.contains("524_288"))
    assertTrue(contract.contains("profilePreferencesAccepted"))
    assertTrue(contract.contains("profilePreferenceRejections"))
    assertTrue(contract.contains("profilePreferenceSections"))
    assertFalse(contract.contains("userId: string"), "The preference mutation wire type must not accept user identity")
}
```

- [ ] **Step 2: Run the test and verify the missing RPC/document failures**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "com.devil.phoenixproject.data.sync.BackendHandoffContractTest" -Pskip.supabase.check=true
```

Expected: FAIL because the RPC and Edge handoff are absent.

- [ ] **Step 3: Add a canonical-section SQL projection**

Insert this function before Task 1's privilege block and `COMMIT`:

```sql
CREATE FUNCTION public.local_profile_preference_section_canonical(
    p_row public.local_profile_preferences,
    p_section text
) RETURNS jsonb
LANGUAGE sql
STABLE
SET search_path = public, pg_temp
AS $canonical$
    SELECT jsonb_build_object(
        'localProfileId', p_row.local_profile_id,
        'section', p_section,
        'documentVersion', CASE p_section
            WHEN 'CORE' THEN 1
            WHEN 'RACK' THEN (p_row.equipment_rack ->> 'version')::integer
            WHEN 'WORKOUT' THEN (p_row.workout_preferences ->> 'version')::integer
            WHEN 'LED' THEN (p_row.led_preferences ->> 'version')::integer
            WHEN 'VBT' THEN (p_row.vbt_preferences ->> 'version')::integer
        END,
        'serverRevision', CASE p_section
            WHEN 'CORE' THEN p_row.core_revision
            WHEN 'RACK' THEN p_row.rack_revision
            WHEN 'WORKOUT' THEN p_row.workout_revision
            WHEN 'LED' THEN p_row.led_revision
            WHEN 'VBT' THEN p_row.vbt_revision
        END,
        'serverUpdatedAt', to_char(
            (CASE p_section
                WHEN 'CORE' THEN p_row.core_updated_at
                WHEN 'RACK' THEN p_row.rack_updated_at
                WHEN 'WORKOUT' THEN p_row.workout_updated_at
                WHEN 'LED' THEN p_row.led_updated_at
                WHEN 'VBT' THEN p_row.vbt_updated_at
            END) AT TIME ZONE 'UTC',
            'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'
        ),
        'payload', CASE p_section
            WHEN 'CORE' THEN jsonb_build_object(
                'bodyWeightKg', p_row.body_weight_kg,
                'weightUnit', p_row.weight_unit,
                'weightIncrement', p_row.weight_increment
            )
            WHEN 'RACK' THEN p_row.equipment_rack
            WHEN 'WORKOUT' THEN p_row.workout_preferences
            WHEN 'LED' THEN jsonb_build_object(
                'ledColorSchemeId', p_row.led_color_scheme_id,
                'preferences', p_row.led_preferences
            )
            WHEN 'VBT' THEN jsonb_build_object(
                'vbtEnabled', p_row.vbt_enabled,
                'preferences', p_row.vbt_preferences
            )
        END
    );
$canonical$;
```

- [ ] **Step 4: Add the service-role-only revision-checked RPC**

Add the following RPC immediately after the canonical projection and before the privilege block/`COMMIT`. Its five branches are explicit so typed columns and JSON documents cannot be accidentally swapped. The function call is one Postgres transaction, so the conflict fetch observes the same atomic operation.

```sql
CREATE FUNCTION public.mutate_local_profile_preference_section(
    p_user_id uuid,
    p_local_profile_id text,
    p_section text,
    p_document_version integer,
    p_base_revision bigint,
    p_payload jsonb
) RETURNS TABLE (
    accepted boolean,
    rejection_reason text,
    server_revision bigint,
    canonical_section jsonb
)
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public, pg_temp
AS $mutation$
DECLARE
    current_row public.local_profile_preferences%ROWTYPE;
    current_revision bigint;
BEGIN
    IF p_section NOT IN ('CORE', 'RACK', 'WORKOUT', 'LED', 'VBT') THEN
        RETURN QUERY SELECT false, 'UNSUPPORTED_SECTION', 0::bigint, NULL::jsonb;
        RETURN;
    END IF;
    IF p_base_revision < 0 OR p_document_version <> 1 OR jsonb_typeof(p_payload) <> 'object' THEN
        RETURN QUERY SELECT false, 'VALIDATION_FAILED', 0::bigint, NULL::jsonb;
        RETURN;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.local_profiles
         WHERE user_id = p_user_id AND id = p_local_profile_id
    ) THEN
        RETURN QUERY SELECT false, 'UNKNOWN_PROFILE', 0::bigint, NULL::jsonb;
        RETURN;
    END IF;

    CASE p_section
        WHEN 'CORE' THEN
            UPDATE public.local_profile_preferences
               SET body_weight_kg = (p_payload ->> 'bodyWeightKg')::double precision,
                   weight_unit = p_payload ->> 'weightUnit',
                   weight_increment = (p_payload ->> 'weightIncrement')::double precision,
                   core_revision = core_revision + 1,
                   core_updated_at = clock_timestamp()
             WHERE user_id = p_user_id
               AND local_profile_id = p_local_profile_id
               AND core_revision = p_base_revision
            RETURNING * INTO current_row;
        WHEN 'RACK' THEN
            UPDATE public.local_profile_preferences
               SET equipment_rack = p_payload,
                   rack_revision = rack_revision + 1,
                   rack_updated_at = clock_timestamp()
             WHERE user_id = p_user_id
               AND local_profile_id = p_local_profile_id
               AND rack_revision = p_base_revision
            RETURNING * INTO current_row;
        WHEN 'WORKOUT' THEN
            UPDATE public.local_profile_preferences
               SET workout_preferences = p_payload,
                   workout_revision = workout_revision + 1,
                   workout_updated_at = clock_timestamp()
             WHERE user_id = p_user_id
               AND local_profile_id = p_local_profile_id
               AND workout_revision = p_base_revision
            RETURNING * INTO current_row;
        WHEN 'LED' THEN
            UPDATE public.local_profile_preferences
               SET led_color_scheme_id = (p_payload ->> 'ledColorSchemeId')::integer,
                   led_preferences = p_payload -> 'preferences',
                   led_revision = led_revision + 1,
                   led_updated_at = clock_timestamp()
             WHERE user_id = p_user_id
               AND local_profile_id = p_local_profile_id
               AND led_revision = p_base_revision
            RETURNING * INTO current_row;
        WHEN 'VBT' THEN
            UPDATE public.local_profile_preferences
               SET vbt_enabled = (p_payload ->> 'vbtEnabled')::boolean,
                   vbt_preferences = p_payload -> 'preferences',
                   vbt_revision = vbt_revision + 1,
                   vbt_updated_at = clock_timestamp()
             WHERE user_id = p_user_id
               AND local_profile_id = p_local_profile_id
               AND vbt_revision = p_base_revision
            RETURNING * INTO current_row;
    END CASE;

    IF FOUND THEN
        canonical_section := public.local_profile_preference_section_canonical(current_row, p_section);
        server_revision := (canonical_section ->> 'serverRevision')::bigint;
        RETURN QUERY SELECT true, NULL::text, server_revision, canonical_section;
        RETURN;
    END IF;

    IF p_base_revision = 0 THEN
        INSERT INTO public.local_profile_preferences (
            user_id, local_profile_id,
            body_weight_kg, weight_unit, weight_increment, core_revision,
            equipment_rack, rack_revision,
            workout_preferences, workout_revision,
            led_color_scheme_id, led_preferences, led_revision,
            vbt_enabled, vbt_preferences, vbt_revision
        ) VALUES (
            p_user_id,
            p_local_profile_id,
            CASE WHEN p_section = 'CORE' THEN (p_payload ->> 'bodyWeightKg')::double precision ELSE 0 END,
            CASE WHEN p_section = 'CORE' THEN p_payload ->> 'weightUnit' ELSE 'LB' END,
            CASE WHEN p_section = 'CORE' THEN (p_payload ->> 'weightIncrement')::double precision ELSE -1 END,
            CASE WHEN p_section = 'CORE' THEN 1 ELSE 0 END,
            CASE WHEN p_section = 'RACK' THEN p_payload ELSE '{"version":1,"items":[]}'::jsonb END,
            CASE WHEN p_section = 'RACK' THEN 1 ELSE 0 END,
            CASE WHEN p_section = 'WORKOUT' THEN p_payload ELSE '{"version":1}'::jsonb END,
            CASE WHEN p_section = 'WORKOUT' THEN 1 ELSE 0 END,
            CASE WHEN p_section = 'LED' THEN (p_payload ->> 'ledColorSchemeId')::integer ELSE 0 END,
            CASE WHEN p_section = 'LED' THEN p_payload -> 'preferences' ELSE '{"version":1,"discoModeUnlocked":false}'::jsonb END,
            CASE WHEN p_section = 'LED' THEN 1 ELSE 0 END,
            CASE WHEN p_section = 'VBT' THEN (p_payload ->> 'vbtEnabled')::boolean ELSE true END,
            CASE WHEN p_section = 'VBT' THEN p_payload -> 'preferences' ELSE '{"version":1,"velocityLossThresholdPercent":20,"autoEndOnVelocityLoss":false,"defaultScalingBasis":"MAX_WEIGHT_PR","verbalEncouragementEnabled":false,"vulgarModeEnabled":false,"vulgarTier":"STRONG","dominatrixModeUnlocked":false,"dominatrixModeActive":false}'::jsonb END,
            CASE WHEN p_section = 'VBT' THEN 1 ELSE 0 END
        )
        ON CONFLICT DO NOTHING
        RETURNING * INTO current_row;

        IF FOUND THEN
            canonical_section := public.local_profile_preference_section_canonical(current_row, p_section);
            server_revision := (canonical_section ->> 'serverRevision')::bigint;
            RETURN QUERY SELECT true, NULL::text, server_revision, canonical_section;
            RETURN;
        END IF;

        CASE p_section
            WHEN 'CORE' THEN
                UPDATE public.local_profile_preferences
                   SET body_weight_kg = (p_payload ->> 'bodyWeightKg')::double precision,
                       weight_unit = p_payload ->> 'weightUnit',
                       weight_increment = (p_payload ->> 'weightIncrement')::double precision,
                       core_revision = 1,
                       core_updated_at = clock_timestamp()
                 WHERE user_id = p_user_id AND local_profile_id = p_local_profile_id AND core_revision = 0
                RETURNING * INTO current_row;
            WHEN 'RACK' THEN
                UPDATE public.local_profile_preferences
                   SET equipment_rack = p_payload, rack_revision = 1, rack_updated_at = clock_timestamp()
                 WHERE user_id = p_user_id AND local_profile_id = p_local_profile_id AND rack_revision = 0
                RETURNING * INTO current_row;
            WHEN 'WORKOUT' THEN
                UPDATE public.local_profile_preferences
                   SET workout_preferences = p_payload, workout_revision = 1, workout_updated_at = clock_timestamp()
                 WHERE user_id = p_user_id AND local_profile_id = p_local_profile_id AND workout_revision = 0
                RETURNING * INTO current_row;
            WHEN 'LED' THEN
                UPDATE public.local_profile_preferences
                   SET led_color_scheme_id = (p_payload ->> 'ledColorSchemeId')::integer,
                       led_preferences = p_payload -> 'preferences',
                       led_revision = 1,
                       led_updated_at = clock_timestamp()
                 WHERE user_id = p_user_id AND local_profile_id = p_local_profile_id AND led_revision = 0
                RETURNING * INTO current_row;
            WHEN 'VBT' THEN
                UPDATE public.local_profile_preferences
                   SET vbt_enabled = (p_payload ->> 'vbtEnabled')::boolean,
                       vbt_preferences = p_payload -> 'preferences',
                       vbt_revision = 1,
                       vbt_updated_at = clock_timestamp()
                 WHERE user_id = p_user_id AND local_profile_id = p_local_profile_id AND vbt_revision = 0
                RETURNING * INTO current_row;
        END CASE;

        IF FOUND THEN
            canonical_section := public.local_profile_preference_section_canonical(current_row, p_section);
            server_revision := (canonical_section ->> 'serverRevision')::bigint;
            RETURN QUERY SELECT true, NULL::text, server_revision, canonical_section;
            RETURN;
        END IF;
    END IF;

    SELECT * INTO current_row
      FROM public.local_profile_preferences
     WHERE user_id = p_user_id AND local_profile_id = p_local_profile_id;

    IF NOT FOUND THEN
        RETURN QUERY SELECT false, 'REVISION_CONFLICT', 0::bigint, NULL::jsonb;
        RETURN;
    END IF;

    canonical_section := public.local_profile_preference_section_canonical(current_row, p_section);
    current_revision := (canonical_section ->> 'serverRevision')::bigint;
    RETURN QUERY SELECT false, 'REVISION_CONFLICT', current_revision, canonical_section;
END
$mutation$;

REVOKE ALL ON FUNCTION public.local_profile_preference_section_canonical(
    public.local_profile_preferences, text
) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.mutate_local_profile_preference_section(
    uuid, text, text, integer, bigint, jsonb
) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.local_profile_preference_section_canonical(
    public.local_profile_preferences, text
) TO service_role;
GRANT EXECUTE ON FUNCTION public.mutate_local_profile_preference_section(
    uuid, text, text, integer, bigint, jsonb
) TO service_role;
```

- [ ] **Step 5: Create the exact Edge Function handoff document**

Create `docs/backend-handoff/profile-preferences-edge-functions.md` with the portal target paths and these concrete TypeScript types:

```typescript
type ProfilePreferenceSection = "CORE" | "RACK" | "WORKOUT" | "LED" | "VBT";

interface PortalProfilePreferenceSectionMutation {
  localProfileId: string;
  section: ProfilePreferenceSection;
  documentVersion: number;
  baseRevision: number;
  clientModifiedAt: string;
  payload: Record<string, unknown>;
}

interface PortalProfilePreferenceSectionCanonical {
  localProfileId: string;
  section: ProfilePreferenceSection;
  documentVersion: number;
  serverRevision: number;
  serverUpdatedAt: string;
  payload: Record<string, unknown>;
}

interface ProfilePreferenceSectionRejection {
  localProfileId: string;
  section: string;
  serverRevision: number;
  reason:
    | "REVISION_CONFLICT"
    | "VALIDATION_FAILED"
    | "UNSUPPORTED_SECTION"
    | "UNSUPPORTED_DOCUMENT_VERSION"
    | "SECTION_TOO_LARGE"
    | "UNKNOWN_PROFILE";
  canonicalSection?: PortalProfilePreferenceSectionCanonical;
}

interface MobileSyncPushAdditions {
  profilePreferenceSections?: PortalProfilePreferenceSectionMutation[];
}

interface MobileSyncPushResponseAdditions {
  profilePreferencesAccepted?: boolean;
  canonicalProfilePreferenceSections: PortalProfilePreferenceSectionCanonical[];
  profilePreferenceRejections: ProfilePreferenceSectionRejection[];
}

interface MobileSyncPullResponseAdditions {
  profilePreferenceSections?: PortalProfilePreferenceSectionCanonical[];
}
```

The document must name these portal implementation targets exactly:

```text
supabase/functions/mobile-sync-push/index.ts
supabase/functions/mobile-sync-pull/index.ts
supabase/config.toml
supabase/migrations/  (created by: supabase migration new profile_preferences)
```

Add this authorization and byte-counting implementation contract:

```typescript
const MAX_PROFILE_PREFERENCE_SECTION_BYTES = 262_144;
const MAX_PROFILE_PREFERENCE_REQUEST_BYTES = 524_288;
const encodedBytes = (value: unknown): number =>
  new TextEncoder().encode(JSON.stringify(value)).byteLength;

const authorization = req.headers.get("Authorization");
if (!authorization?.startsWith("Bearer ")) {
  return new Response(JSON.stringify({ error: "Missing bearer token" }), { status: 401 });
}
const userJwt = authorization.slice("Bearer ".length);
const authClient = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
  global: { headers: { Authorization: authorization } },
  auth: { persistSession: false, autoRefreshToken: false },
});
const { data: userData, error: userError } = await authClient.auth.getUser(userJwt);
if (userError || !userData.user) {
  return new Response(JSON.stringify({ error: "Invalid bearer token" }), { status: 401 });
}
const verifiedUserId = userData.user.id;
const admin = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
  auth: { persistSession: false, autoRefreshToken: false },
});

const rawBody = await req.text();
const rawBodyBytes = new TextEncoder().encode(rawBody).byteLength;
if (rawBodyBytes > 9_500_000) {
  return new Response(JSON.stringify({ error: "Request too large" }), { status: 413 });
}
let body: Record<string, unknown>;
try {
  body = JSON.parse(rawBody) as Record<string, unknown>;
} catch {
  return new Response(JSON.stringify({ error: "Malformed JSON" }), { status: 400 });
}
if (Object.hasOwn(body, "profilePreferenceSections") &&
    rawBodyBytes > MAX_PROFILE_PREFERENCE_REQUEST_BYTES) {
  return new Response(JSON.stringify({ error: "Profile preference request exceeds 524288 bytes" }), {
    status: 413,
  });
}
```

Specify that push validates the whole body before database work, rejects a preference request over 524,288 bytes at HTTP 413, converts each invalid/oversized section into its own rejection, and calls the RPC once per valid section:

```typescript
if (encodedBytes(mutation) > MAX_PROFILE_PREFERENCE_SECTION_BYTES) {
  profilePreferenceRejections.push({
    localProfileId: mutation.localProfileId,
    section: mutation.section,
    serverRevision: 0,
    reason: "SECTION_TOO_LARGE",
  });
  continue;
}

const { data, error } = await admin.rpc("mutate_local_profile_preference_section", {
  p_user_id: verifiedUserId,
  p_local_profile_id: mutation.localProfileId,
  p_section: mutation.section,
  p_document_version: mutation.documentVersion,
  p_base_revision: mutation.baseRevision,
  p_payload: mutation.payload,
});
```

The document must state these response and pull rules in executable terms:

- `profilePreferencesAccepted` is emitted as `true` only when `profilePreferenceSections` was present and evaluated.
- An accepted RPC row appends its canonical object to `canonicalProfilePreferenceSections`.
- A rejected RPC row appends one `profilePreferenceRejections` entry and preserves its canonical object when present.
- A single RPC error becomes one `VALIDATION_FAILED` rejection; the loop continues with valid sibling sections.
- Pull uses the service-role client with both `.eq("user_id", verifiedUserId)` and `.eq("local_profile_id", requestedProfileId)`.
- Pull emits all five canonical sections only when the cursor is absent; later pages omit `profilePreferenceSections`.
- Push and pull responses retain the existing required `syncTime` field.
- `verify_jwt = true` remains configured for both functions.
- No network call occurs while a database row lock is held because the RPC owns the complete mutation transaction.
- Concurrent first writes to the same section yield one revision-1 acceptance and one canonical conflict. Concurrent first writes to different sections both reach revision 1 without overwriting the other section.

Use an explicit verified-owner predicate for pull, then map the typed columns/documents into the same canonical wrappers returned by the mutation RPC:

```typescript
const { data: preferenceRow, error: preferenceError } = await admin
  .from("local_profile_preferences")
  .select("*")
  .eq("user_id", verifiedUserId)
  .eq("local_profile_id", requestedProfileId)
  .maybeSingle();
if (preferenceError) throw preferenceError;

const canonical = (
  section: ProfilePreferenceSection,
  documentVersion: number,
  serverRevision: number,
  serverUpdatedAt: string,
  payload: Record<string, unknown>,
): PortalProfilePreferenceSectionCanonical => ({
  localProfileId: requestedProfileId,
  section,
  documentVersion,
  serverRevision,
  serverUpdatedAt,
  payload,
});

const profilePreferenceSections = cursor || !preferenceRow ? undefined : [
  canonical("CORE", 1, preferenceRow.core_revision, preferenceRow.core_updated_at, {
    bodyWeightKg: preferenceRow.body_weight_kg,
    weightUnit: preferenceRow.weight_unit,
    weightIncrement: preferenceRow.weight_increment,
  }),
  canonical("RACK", preferenceRow.equipment_rack.version, preferenceRow.rack_revision,
    preferenceRow.rack_updated_at, preferenceRow.equipment_rack),
  canonical("WORKOUT", preferenceRow.workout_preferences.version,
    preferenceRow.workout_revision, preferenceRow.workout_updated_at,
    preferenceRow.workout_preferences),
  canonical("LED", preferenceRow.led_preferences.version, preferenceRow.led_revision,
    preferenceRow.led_updated_at, {
      ledColorSchemeId: preferenceRow.led_color_scheme_id,
      preferences: preferenceRow.led_preferences,
    }),
  canonical("VBT", preferenceRow.vbt_preferences.version, preferenceRow.vbt_revision,
    preferenceRow.vbt_updated_at, {
      vbtEnabled: preferenceRow.vbt_enabled,
      preferences: preferenceRow.vbt_preferences,
    }),
];
```

List the required portal verification commands:

```bash
supabase db reset
supabase migration list
supabase test db
deno test --allow-env --allow-net supabase/functions/mobile-sync-push
deno test --allow-env --allow-net supabase/functions/mobile-sync-pull
supabase inspect db lint
```

- [ ] **Step 6: Run the handoff contract test and verify it passes**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "com.devil.phoenixproject.data.sync.BackendHandoffContractTest" -Pskip.supabase.check=true
```

Expected: PASS.

- [ ] **Step 7: Commit the atomic backend handoff**

```powershell
git add docs/backend-handoff/profile-preferences-supabase.sql docs/backend-handoff/profile-preferences-edge-functions.md shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/BackendHandoffContractTest.kt
git commit -m "docs(sync): define atomic profile preference edge contract"
```

---

### Task 3: Add Internal and Backward-Compatible Wire DTOs

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncModels.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncDtos.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncDtosTest.kt`

**Interfaces:**
- Consumes: typed, validated section documents and repository methods from the data-foundation plan.
- Produces: internal snapshot/outcome types and additive HTTP DTOs for every later mobile task.

- [ ] **Step 1: Write failing DTO compatibility and privacy tests**

Create `ProfilePreferenceSyncDtosTest.kt`:

```kotlin
package com.devil.phoenixproject.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProfilePreferenceSyncDtosTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `legacy responses decode without falsely acknowledging preferences`() {
        val push = json.decodeFromString<PortalSyncPushResponse>(
            """{"syncTime":"2026-07-11T12:00:00Z"}""",
        )
        val pull = json.decodeFromString<PortalSyncPullResponse>(
            """{"syncTime":1783771200000}""",
        )

        assertNull(push.profilePreferencesAccepted)
        assertTrue(push.canonicalProfilePreferenceSections.isEmpty())
        assertTrue(push.profilePreferenceRejections.isEmpty())
        assertNull(pull.profilePreferenceSections)
    }

    @Test
    fun `mutation serializes payload object and excludes local safety`() {
        val mutation = PortalProfilePreferenceSectionMutationDto(
            localProfileId = "profile-a",
            section = "WORKOUT",
            documentVersion = 1,
            baseRevision = 4,
            clientModifiedAt = "2026-07-11T12:00:00Z",
            payload = buildJsonObject {
                put("version", 1)
                put("voiceStopEnabled", true)
            },
        )

        val encoded = json.encodeToString(mutation)
        assertTrue(encoded.contains("\"voiceStopEnabled\":true"))
        assertFalse(encoded.contains("safeWord"))
        assertFalse(encoded.contains("safeWordCalibrated"))
        assertFalse(encoded.contains("adultsOnlyConfirmed"))
        assertFalse(encoded.contains("adultsOnlyPrompted"))
    }

    @Test
    fun `canonical and rejection preserve revision identity`() {
        val canonical = PortalProfilePreferenceSectionCanonicalDto(
            localProfileId = "profile-a",
            section = "CORE",
            documentVersion = 1,
            serverRevision = 7,
            serverUpdatedAt = "2026-07-11T12:01:00Z",
            payload = buildJsonObject { put("weightUnit", "KG") },
        )
        val rejection = ProfilePreferenceSectionRejectionDto(
            localProfileId = "profile-a",
            section = "CORE",
            serverRevision = 7,
            reason = "REVISION_CONFLICT",
            canonicalSection = canonical,
        )

        assertEquals(7, rejection.serverRevision)
        assertEquals(canonical, rejection.canonicalSection)
    }
}
```

- [ ] **Step 2: Run the DTO tests and verify missing-type failures**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "com.devil.phoenixproject.data.sync.ProfilePreferenceSyncDtosTest" -Pskip.supabase.check=true
```

Expected: FAIL because the preference DTOs and additive fields do not exist.

- [ ] **Step 3: Add non-serializable internal sync models**

Add these types to `SyncModels.kt`. Import and reuse the foundation's `com.devil.phoenixproject.domain.model.ProfilePreferenceSectionName`; do not declare a second section enum. Deliberately omit `@Serializable` from every type containing `localGeneration`.

```kotlin
data class ProfilePreferenceSectionKey(
    val localProfileId: String,
    val section: com.devil.phoenixproject.domain.model.ProfilePreferenceSectionName,
)

data class ProfilePreferenceSectionSyncDto(
    val key: ProfilePreferenceSectionKey,
    val documentVersion: Int,
    val baseRevision: Long,
    val clientModifiedAtEpochMs: Long,
    val localGeneration: Long,
    val payload: kotlinx.serialization.json.JsonObject,
)

data class PreparedProfilePreferenceMutation(
    val wire: PortalProfilePreferenceSectionMutationDto,
    val key: ProfilePreferenceSectionKey,
    val sentLocalGeneration: Long,
)

data class CanonicalProfilePreferenceSection(
    val key: ProfilePreferenceSectionKey,
    val documentVersion: Int,
    val serverRevision: Long,
    val serverUpdatedAtEpochMs: Long,
    val payload: kotlinx.serialization.json.JsonObject,
)

data class ProfilePreferencePushOutcome(
    val key: ProfilePreferenceSectionKey,
    val sentLocalGeneration: Long,
    val serverRevision: Long,
    val canonical: CanonicalProfilePreferenceSection?,
    val rejectionReason: String?,
)

data class ProfilePreferenceSyncApplyReport(
    val applied: Int = 0,
    val preservedNewerLocal: Int = 0,
    val ignoredUnknownProfile: Int = 0,
    val invalid: Int = 0,
)

data class ProfilePreferenceSyncIssue(
    val key: ProfilePreferenceSectionKey,
    val reason: String,
)

data class ProfilePreferenceDirtySnapshot(
    val valid: List<ProfilePreferenceSectionSyncDto>,
    val unsyncable: List<ProfilePreferenceSyncIssue>,
)

sealed interface ProfilePreferenceCanonicalDecodeResult {
    data class Valid(val section: CanonicalProfilePreferenceSection) : ProfilePreferenceCanonicalDecodeResult
    data class Invalid(
        val localProfileId: String,
        val section: String,
        val reason: String,
    ) : ProfilePreferenceCanonicalDecodeResult
}
```

- [ ] **Step 4: Add the wire DTOs and additive fields**

Add the three `@Serializable` DTOs to `PortalSyncDtos.kt`:

```kotlin
@Serializable
data class PortalProfilePreferenceSectionMutationDto(
    val localProfileId: String,
    val section: String,
    val documentVersion: Int,
    val baseRevision: Long,
    val clientModifiedAt: String,
    val payload: kotlinx.serialization.json.JsonObject,
)

@Serializable
data class PortalProfilePreferenceSectionCanonicalDto(
    val localProfileId: String,
    val section: String,
    val documentVersion: Int,
    val serverRevision: Long,
    val serverUpdatedAt: String,
    val payload: kotlinx.serialization.json.JsonObject,
)

@Serializable
data class ProfilePreferenceSectionRejectionDto(
    val localProfileId: String,
    val section: String,
    val serverRevision: Long,
    val reason: String,
    val canonicalSection: PortalProfilePreferenceSectionCanonicalDto? = null,
)
```

Add these fields with the exact defaults:

```kotlin
// PortalSyncPayload
val profilePreferenceSections: List<PortalProfilePreferenceSectionMutationDto>? = null

// PortalSyncPushResponse
val profilePreferencesAccepted: Boolean? = null
val canonicalProfilePreferenceSections: List<PortalProfilePreferenceSectionCanonicalDto> = emptyList()
val profilePreferenceRejections: List<ProfilePreferenceSectionRejectionDto> = emptyList()

// PortalSyncPullResponse
val profilePreferenceSections: List<PortalProfilePreferenceSectionCanonicalDto>? = null
```

Update the stale `SyncModels.kt` comment to name `PortalSyncPayload`, the actual push request class.

- [ ] **Step 5: Run the DTO tests and verify they pass**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "com.devil.phoenixproject.data.sync.ProfilePreferenceSyncDtosTest" -Pskip.supabase.check=true
```

Expected: PASS.

- [ ] **Step 6: Commit the additive wire contract**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncModels.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncDtos.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncDtosTest.kt
git commit -m "feat(sync): add profile preference wire contract"
```

---

### Task 4: Add the Focused SQLDelight Sync Persistence Adapter

**Files:**
- Modify: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncRepository.kt`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncCodec.kt`
- Create: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/sync/SqlDelightProfilePreferenceSyncRepositoryTest.kt`

**Interfaces:**
- Consumes: schema-43 `UserProfilePreferences`, the foundation typed models/validator/codec, and Task 3's internal sync models.
- Produces: an internal sync-only repository that snapshots dirty valid sections, reports invalid sections, applies push canonicals with generation guards, and merges pull canonicals without creating profiles.

- [ ] **Step 1: Write failing row-owned-wrapper and invalid-section snapshot tests**

Create `SqlDelightProfilePreferenceSyncRepositoryTest.kt` with an in-memory database using the existing Android-host database test fixture. Insert profile `profile-a`, insert its default preference row, and write typed LED/VBT values through `SqlDelightProfilePreferencesRepository`:

```kotlin
@Test
fun `dirty snapshot reconstructs row owned LED and VBT values as objects`() = runTest {
    createProfile("profile-a")
    foundationRepository.insertDefaults("profile-a")
    foundationRepository.updateLed(
        "profile-a",
        LedPreferences(colorScheme = 7, discoModeUnlocked = true),
        now = 20,
    )
    foundationRepository.updateVbt(
        "profile-a",
        VbtPreferences(enabled = false, velocityLossThresholdPercent = 35),
        now = 30,
    )

    val snapshot = repository.snapshotDirtySections()
    val led = snapshot.valid.single { it.key.section == ProfilePreferenceSectionName.LED }
    val vbt = snapshot.valid.single { it.key.section == ProfilePreferenceSectionName.VBT }

    assertEquals(7, led.payload.getValue("ledColorSchemeId").jsonPrimitive.int)
    assertTrue(led.payload.getValue("preferences") is JsonObject)
    assertNull(led.payload.getValue("preferences").jsonObject["colorScheme"])
    assertFalse(vbt.payload.getValue("vbtEnabled").jsonPrimitive.boolean)
    assertTrue(vbt.payload.getValue("preferences") is JsonObject)
    assertNull(vbt.payload.getValue("preferences").jsonObject["enabled"])
    assertFalse(led.payload.toString().contains("\\\"{", ignoreCase = false))
    assertFalse(vbt.payload.toString().contains("\\\"{", ignoreCase = false))
}

@Test
fun `malformed dirty document is reported and valid sibling remains syncable`() = runTest {
    createProfile("profile-a")
    foundationRepository.insertDefaults("profile-a")
    database.vitruvianDatabaseQueries.updateWorkoutProfilePreferences(
        workout_preferences_json = "{broken",
        workout_updated_at = 20,
        profile_id = "profile-a",
    )
    foundationRepository.updateRack(
        "profile-a",
        RackPreferences(items = listOf(RackItem(id = "rack-1", name = "Bar", weightKg = 20f))),
        now = 30,
    )

    val snapshot = repository.snapshotDirtySections()

    assertTrue(snapshot.valid.any { it.key.section == ProfilePreferenceSectionName.RACK })
    assertTrue(snapshot.valid.none { it.key.section == ProfilePreferenceSectionName.WORKOUT })
    assertEquals(ProfilePreferenceSectionName.WORKOUT, snapshot.unsyncable.single().key.section)
    assertTrue(snapshot.unsyncable.single().reason.startsWith("invalid local document:"))
}
```

The fixture's `createProfile` calls the existing generated `insertProfile` query. Import `jsonObject`, `jsonPrimitive`, `int`, and `boolean`; assertions inspect JSON elements, not stringified nested JSON.

- [ ] **Step 2: Write failing generation-race and pull-merge tests**

Add these repository tests. `coreCanonical` returns a valid `CanonicalProfilePreferenceSection` with the literal CORE payload shown here, so no fallback decoder participates:

```kotlin
private fun coreCanonical(
    revision: Long,
    bodyWeightKg: Double,
    updatedAt: Long = 1_783_771_200_000L,
) = CanonicalProfilePreferenceSection(
    key = ProfilePreferenceSectionKey("profile-a", ProfilePreferenceSectionName.CORE),
    documentVersion = 1,
    serverRevision = revision,
    serverUpdatedAtEpochMs = updatedAt,
    payload = buildJsonObject {
        put("bodyWeightKg", bodyWeightKg)
        put("weightUnit", "KG")
        put("weightIncrement", 0.5)
    },
)

@Test
fun `push canonical racing newer edit advances revision without clearing dirty`() = runTest {
    createProfile("profile-a")
    foundationRepository.insertDefaults("profile-a")
    foundationRepository.updateCore("profile-a", CoreProfilePreferences(bodyWeightKg = 80f), now = 20)
    val sent = repository.snapshotDirtySections().valid.single {
        it.key.section == ProfilePreferenceSectionName.CORE
    }
    foundationRepository.updateCore("profile-a", CoreProfilePreferences(bodyWeightKg = 90f), now = 30)

    repository.applyPushOutcomes(
        listOf(
            ProfilePreferencePushOutcome(
                key = sent.key,
                sentLocalGeneration = sent.localGeneration,
                serverRevision = 4,
                canonical = coreCanonical(revision = 4, bodyWeightKg = 80.0),
                rejectionReason = null,
            ),
        ),
    )

    val current = foundationRepository.get("profile-a").core
    assertEquals(90f, current.value.bodyWeightKg)
    assertEquals(4, current.metadata.serverRevision)
    assertTrue(current.metadata.dirty)
    assertTrue(current.metadata.localGeneration > sent.localGeneration)
}

@Test
fun `matching generation conflict canonical replaces snapshot and clears dirty`() = runTest {
    createProfile("profile-a")
    foundationRepository.insertDefaults("profile-a")
    foundationRepository.updateCore("profile-a", CoreProfilePreferences(bodyWeightKg = 80f), now = 20)
    val sent = repository.snapshotDirtySections().valid.single {
        it.key.section == ProfilePreferenceSectionName.CORE
    }

    repository.applyPushOutcomes(
        listOf(
            ProfilePreferencePushOutcome(
                key = sent.key,
                sentLocalGeneration = sent.localGeneration,
                serverRevision = 6,
                canonical = coreCanonical(revision = 6, bodyWeightKg = 85.0),
                rejectionReason = "REVISION_CONFLICT",
            ),
        ),
    )

    val current = foundationRepository.get("profile-a").core
    assertEquals(85f, current.value.bodyWeightKg)
    assertEquals(6, current.metadata.serverRevision)
    assertFalse(current.metadata.dirty)
}

@Test
fun `pull updates only clean nonnewer rows and never creates unknown profile`() = runTest {
    createProfile("profile-a")
    foundationRepository.insertDefaults("profile-a")
    foundationRepository.updateCore("profile-a", CoreProfilePreferences(bodyWeightKg = 80f), now = 20)
    val sent = repository.snapshotDirtySections().valid.single {
        it.key.section == ProfilePreferenceSectionName.CORE
    }
    repository.applyPushOutcomes(
        listOf(
            ProfilePreferencePushOutcome(
                key = sent.key,
                sentLocalGeneration = sent.localGeneration,
                serverRevision = 2,
                canonical = coreCanonical(revision = 2, bodyWeightKg = 80.0, updatedAt = 100),
                rejectionReason = null,
            ),
        ),
    )

    val report = repository.applyPulledSections(
        listOf(
            coreCanonical(revision = 3, bodyWeightKg = 83.0),
            coreCanonical(revision = 9, bodyWeightKg = 99.0).copy(
                key = ProfilePreferenceSectionKey("remote-only", ProfilePreferenceSectionName.CORE),
            ),
        ),
    )

    val current = foundationRepository.get("profile-a").core
    assertEquals(83f, current.value.bodyWeightKg)
    assertEquals(3, current.metadata.serverRevision)
    assertFalse(current.metadata.dirty)
    assertEquals(1, report.ignoredUnknownProfile)
    assertTrue(userProfileRepository.allProfiles.value.none { it.id == "remote-only" })
}

@Test
fun `dirty pull leaves payload revision timestamp generation and dirty flag unchanged`() = runTest {
    createProfile("profile-a")
    foundationRepository.insertDefaults("profile-a")
    foundationRepository.updateCore("profile-a", CoreProfilePreferences(bodyWeightKg = 80f), now = 20)
    val before = foundationRepository.get("profile-a").core

    repository.applyPulledSections(listOf(coreCanonical(revision = 3, bodyWeightKg = 83.0)))

    val after = foundationRepository.get("profile-a").core
    assertEquals(before.value, after.value)
    assertEquals(before.metadata.serverRevision, after.metadata.serverRevision)
    assertEquals(before.metadata.updatedAt, after.metadata.updatedAt)
    assertEquals(before.metadata.localGeneration, after.metadata.localGeneration)
    assertTrue(after.metadata.dirty)
}

@Test
fun `equal revision different payload repairs clean row`() = runTest {
    createProfile("profile-a")
    foundationRepository.insertDefaults("profile-a")
    foundationRepository.updateCore("profile-a", CoreProfilePreferences(bodyWeightKg = 80f), now = 20)
    val sent = repository.snapshotDirtySections().valid.single {
        it.key.section == ProfilePreferenceSectionName.CORE
    }
    repository.applyPushOutcomes(
        listOf(
            ProfilePreferencePushOutcome(
                key = sent.key,
                sentLocalGeneration = sent.localGeneration,
                serverRevision = 2,
                canonical = coreCanonical(revision = 2, bodyWeightKg = 80.0, updatedAt = 100),
                rejectionReason = null,
            ),
        ),
    )

    repository.applyPulledSections(
        listOf(coreCanonical(revision = 2, bodyWeightKg = 83.0, updatedAt = 200)),
    )

    val repaired = foundationRepository.get("profile-a").core
    assertEquals(83f, repaired.value.bodyWeightKg)
    assertEquals(2, repaired.metadata.serverRevision)
    assertEquals(200, repaired.metadata.updatedAt)
    assertFalse(repaired.metadata.dirty)
}
```

- [ ] **Step 3: Run the repository tests and verify the red state**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "*SqlDelightProfilePreferenceSyncRepositoryTest*" -Pskip.supabase.check=true
```

Expected: FAIL because the sync repository, codec, and guarded SQLDelight queries do not exist.

- [ ] **Step 4: Add the exact dirty, acknowledgement, and pull query matrix**

Add `selectDirtyProfilePreferenceRows` and `selectProfilePreferenceSyncRow` to `VitruvianDatabase.sq`:

```sql
selectDirtyProfilePreferenceRows:
SELECT *
FROM UserProfilePreferences
WHERE core_dirty = 1
   OR rack_dirty = 1
   OR workout_dirty = 1
   OR led_dirty = 1
   OR vbt_dirty = 1
ORDER BY profile_id;

selectProfilePreferenceSyncRow:
SELECT *
FROM UserProfilePreferences
WHERE profile_id = :profile_id;
```

For push canonicals, add these ten query names. Each `apply...ForGeneration` updates only its section's value columns, `*_updated_at`, `*_server_revision`, and `*_dirty = 0`, guarded by `profile_id` and equality with `:sent_local_generation`. Each `advance...ForNewerGeneration` updates only `*_server_revision`, guarded by a strictly newer generation and a lower stored revision.

| Section | Matching-generation query | Newer-generation query | Value columns |
|---|---|---|---|
| CORE | `applyCoreCanonicalForGeneration` | `advanceCoreRevisionForNewerGeneration` | `body_weight_kg`, `weight_unit`, `weight_increment` |
| RACK | `applyRackCanonicalForGeneration` | `advanceRackRevisionForNewerGeneration` | `equipment_rack_json` |
| WORKOUT | `applyWorkoutCanonicalForGeneration` | `advanceWorkoutRevisionForNewerGeneration` | `workout_preferences_json` |
| LED | `applyLedCanonicalForGeneration` | `advanceLedRevisionForNewerGeneration` | `led_color_scheme_id`, `led_preferences_json` |
| VBT | `applyVbtCanonicalForGeneration` | `advanceVbtRevisionForNewerGeneration` | `vbt_enabled`, `vbt_preferences_json` |

CORE establishes the exact SQL shape:

```sql
applyCoreCanonicalForGeneration:
UPDATE UserProfilePreferences
SET body_weight_kg = :body_weight_kg,
    weight_unit = :weight_unit,
    weight_increment = :weight_increment,
    core_updated_at = :server_updated_at,
    core_server_revision = :server_revision,
    core_dirty = 0
WHERE profile_id = :profile_id
  AND core_local_generation = :sent_local_generation;

advanceCoreRevisionForNewerGeneration:
UPDATE UserProfilePreferences
SET core_server_revision = :server_revision
WHERE profile_id = :profile_id
  AND core_local_generation > :sent_local_generation
  AND core_server_revision < :server_revision;
```

The RACK/WORKOUT/LED/VBT matching queries use the value columns in the table above and the corresponding section metadata names. Their newer-generation queries contain no value, timestamp, generation, or dirty assignment.

For pull canonicals, add `applyPulledCoreWhenClean`, `applyPulledRackWhenClean`, `applyPulledWorkoutWhenClean`, `applyPulledLedWhenClean`, and `applyPulledVbtWhenClean`. They update the same value/revision/timestamp columns and retain `dirty = 0`; their guard is exactly `profile_id = :profile_id AND *_dirty = 0 AND *_server_revision <= :server_revision`. The `<=` intentionally repairs equal-revision content divergence while rejecting lower server revisions. CORE establishes the exact shape:

```sql
applyPulledCoreWhenClean:
UPDATE UserProfilePreferences
SET body_weight_kg = :body_weight_kg,
    weight_unit = :weight_unit,
    weight_increment = :weight_increment,
    core_updated_at = :server_updated_at,
    core_server_revision = :server_revision,
    core_dirty = 0
WHERE profile_id = :profile_id
  AND core_dirty = 0
  AND core_server_revision <= :server_revision;
```

- [ ] **Step 5: Implement the strict sync codec and complete wire wrappers**

Create `ProfilePreferenceSyncCodec.kt` as an internal class. It parses foundation codec output back to `JsonObject`, so JSON documents remain objects:

```kotlin
internal class ProfilePreferenceSyncCodec {
    private fun document(encoded: String): JsonObject =
        PortalWireJson.parseToJsonElement(encoded).jsonObject

    fun ledPayload(value: LedPreferences): JsonObject = buildJsonObject {
        put("ledColorSchemeId", value.colorScheme)
        put("preferences", document(ProfilePreferencesCodec.encodeLed(value)))
    }

    fun vbtPayload(value: VbtPreferences): JsonObject = buildJsonObject {
        put("vbtEnabled", value.enabled)
        put("preferences", document(ProfilePreferencesCodec.encodeVbt(value)))
    }

    fun corePayload(value: CoreProfilePreferences): JsonObject = buildJsonObject {
        put("bodyWeightKg", value.bodyWeightKg)
        put("weightUnit", value.weightUnit.name)
        put("weightIncrement", value.weightIncrement)
    }

    fun rackPayload(value: RackPreferences): JsonObject =
        document(ProfilePreferencesCodec.encodeRack(value))

    fun workoutPayload(value: WorkoutPreferences): JsonObject =
        document(ProfilePreferencesCodec.encodeWorkout(value))

    fun validateCanonicalPayload(
        section: ProfilePreferenceSectionName,
        documentVersion: Int,
        payload: JsonObject,
    ): ProfilePreferencePayloadValidation = runCatching {
        require(documentVersion == 1) { "unsupported document version" }
        decodeAndValidateTypedValue(section, payload)
    }.fold(
        onSuccess = { ProfilePreferencePayloadValidation(isValid = true, reason = "") },
        onFailure = { error ->
            ProfilePreferencePayloadValidation(
                isValid = false,
                reason = error.message ?: "invalid canonical payload",
            )
        },
    )
}

internal data class ProfilePreferencePayloadValidation(
    val isValid: Boolean,
    val reason: String,
)

internal sealed interface DecodedProfilePreferenceValue {
    data class Core(val value: CoreProfilePreferences) : DecodedProfilePreferenceValue
    data class Rack(val value: RackPreferences) : DecodedProfilePreferenceValue
    data class Workout(val value: WorkoutPreferences) : DecodedProfilePreferenceValue
    data class Led(val value: LedPreferences) : DecodedProfilePreferenceValue
    data class Vbt(val value: VbtPreferences) : DecodedProfilePreferenceValue
}
```

Add `encodeDirtyRow(row)`, `decodeAndValidateTypedValue(section, payload)`, and `decodeCanonical(canonical)` methods. `encodeDirtyRow` decodes each dirty section independently with the foundation validator/codec; it emits a `ProfilePreferenceSectionSyncDto` only for `ProfilePreferenceValidity.Valid`, and emits a `ProfilePreferenceSyncIssue` retaining the profile/section identity for every invalid dirty document. It uses the section's row timestamp, local generation, and server revision.

Use this exact typed decoding boundary; kotlinx.serialization document defaults remain compatible, while wrapper fields and JSON types are mandatory:

```kotlin
private fun decodeAndValidateTypedValue(
    section: ProfilePreferenceSectionName,
    payload: JsonObject,
): DecodedProfilePreferenceValue {
    val decoded = when (section) {
        ProfilePreferenceSectionName.CORE -> DecodedProfilePreferenceValue.Core(
            CoreProfilePreferences(
                bodyWeightKg = payload.getValue("bodyWeightKg").jsonPrimitive.float,
                weightUnit = WeightUnit.valueOf(payload.getValue("weightUnit").jsonPrimitive.content),
                weightIncrement = payload.getValue("weightIncrement").jsonPrimitive.float,
            ),
        )
        ProfilePreferenceSectionName.RACK -> DecodedProfilePreferenceValue.Rack(
            PortalWireJson.decodeFromJsonElement<RackPreferences>(payload),
        )
        ProfilePreferenceSectionName.WORKOUT -> DecodedProfilePreferenceValue.Workout(
            PortalWireJson.decodeFromJsonElement<WorkoutPreferences>(payload),
        )
        ProfilePreferenceSectionName.LED -> DecodedProfilePreferenceValue.Led(
            PortalWireJson.decodeFromJsonElement<LedPreferences>(
                payload.getValue("preferences").jsonObject,
            ).copy(
                colorScheme = payload.getValue("ledColorSchemeId").jsonPrimitive.int,
            ),
        )
        ProfilePreferenceSectionName.VBT -> DecodedProfilePreferenceValue.Vbt(
            PortalWireJson.decodeFromJsonElement<VbtPreferences>(
                payload.getValue("preferences").jsonObject,
            ).copy(
                enabled = payload.getValue("vbtEnabled").jsonPrimitive.boolean,
            ),
        )
    }
    val errors = when (decoded) {
        is DecodedProfilePreferenceValue.Core -> ProfilePreferencesValidator.core(decoded.value)
        is DecodedProfilePreferenceValue.Rack -> ProfilePreferencesValidator.rack(decoded.value)
        is DecodedProfilePreferenceValue.Workout -> ProfilePreferencesValidator.workout(decoded.value)
        is DecodedProfilePreferenceValue.Led -> ProfilePreferencesValidator.led(decoded.value)
        is DecodedProfilePreferenceValue.Vbt -> ProfilePreferencesValidator.vbt(decoded.value)
    }
    require(errors.isEmpty()) { errors.joinToString(",") }
    return decoded
}
```

`validateCanonicalPayload` additionally checks `documentVersion == 1` and, for RACK/WORKOUT, equality with the decoded value's `version`; for LED/VBT it checks equality with the decoded `preferences.version`. `decodeCanonical` calls the same boundary and returns typed column values; it never substitutes defaults after malformed JSON, wrong JSON types, invalid enums, non-finite/range-invalid values, or version mismatches.

- [ ] **Step 6: Implement the internal repository with one transaction per profile**

Create `ProfilePreferenceSyncRepository.kt`:

```kotlin
/** Sync-layer boundary; bind only in SyncModule and do not expose through profile/domain APIs. */
interface ProfilePreferenceSyncRepository {
    suspend fun snapshotDirtySections(): ProfilePreferenceDirtySnapshot
    suspend fun applyPushOutcomes(
        outcomes: List<ProfilePreferencePushOutcome>,
    ): ProfilePreferenceSyncApplyReport
    suspend fun applyPulledSections(
        sections: List<CanonicalProfilePreferenceSection>,
    ): ProfilePreferenceSyncApplyReport
}

internal class SqlDelightProfilePreferenceSyncRepository(
    private val database: VitruvianDatabase,
    private val codec: ProfilePreferenceSyncCodec,
) : ProfilePreferenceSyncRepository {
    private val queries = database.vitruvianDatabaseQueries

    override suspend fun snapshotDirtySections(): ProfilePreferenceDirtySnapshot {
        val encoded = queries.selectDirtyProfilePreferenceRows().executeAsList()
            .map(codec::encodeDirtyRow)
        return ProfilePreferenceDirtySnapshot(
            valid = encoded.flatMap { it.valid },
            unsyncable = encoded.flatMap { it.unsyncable },
        )
    }

    override suspend fun applyPushOutcomes(
        outcomes: List<ProfilePreferencePushOutcome>,
    ): ProfilePreferenceSyncApplyReport {
        var applied = 0
        var preserved = 0
        outcomes.groupBy { it.key.localProfileId }.forEach { (_, profileOutcomes) ->
            database.transaction {
                profileOutcomes.forEach { outcome ->
                    val canonical = outcome.canonical ?: return@forEach
                    val columns = codec.decodeCanonical(canonical)
                    if (applyCanonicalForGeneration(columns, outcome.sentLocalGeneration)) {
                        applied++
                    } else if (advanceRevisionForNewerGeneration(columns, outcome.sentLocalGeneration)) {
                        preserved++
                    }
                }
            }
        }
        return ProfilePreferenceSyncApplyReport(applied = applied, preservedNewerLocal = preserved)
    }

    override suspend fun applyPulledSections(
        sections: List<CanonicalProfilePreferenceSection>,
    ): ProfilePreferenceSyncApplyReport {
        var applied = 0
        var unknown = 0
        sections.groupBy { it.key.localProfileId }.forEach { (profileId, canonicals) ->
            database.transaction {
                if (queries.selectProfilePreferenceSyncRow(profileId).executeAsOneOrNull() == null) {
                    unknown += canonicals.size
                    return@transaction
                }
                canonicals.forEach { canonical ->
                    if (applyPulledWhenClean(codec.decodeCanonical(canonical))) applied++
                }
            }
        }
        return ProfilePreferenceSyncApplyReport(applied = applied, ignoredUnknownProfile = unknown)
    }
}
```

Add this codec result and method; `currentState` uses the same row-owned LED/VBT wrapper methods as `encodeDirtyRow`:

```kotlin
internal data class CurrentProfilePreferenceSyncState(
    val serverRevision: Long,
    val dirty: Boolean,
    val payload: JsonObject,
)

fun currentState(
    row: com.devil.phoenixproject.database.UserProfilePreferences,
    section: ProfilePreferenceSectionName,
): CurrentProfilePreferenceSyncState = when (section) {
    ProfilePreferenceSectionName.CORE -> CurrentProfilePreferenceSyncState(
        row.core_server_revision,
        row.core_dirty == 1L,
        corePayload(
            CoreProfilePreferences(
                row.body_weight_kg.toFloat(),
                WeightUnit.valueOf(row.weight_unit),
                row.weight_increment.toFloat(),
            ),
        ),
    )
    ProfilePreferenceSectionName.RACK -> CurrentProfilePreferenceSyncState(
        row.rack_server_revision,
        row.rack_dirty == 1L,
        rackPayload(ProfilePreferencesCodec.decodeRack(row.equipment_rack_json).value),
    )
    ProfilePreferenceSectionName.WORKOUT -> CurrentProfilePreferenceSyncState(
        row.workout_server_revision,
        row.workout_dirty == 1L,
        workoutPayload(ProfilePreferencesCodec.decodeWorkout(row.workout_preferences_json).value),
    )
    ProfilePreferenceSectionName.LED -> CurrentProfilePreferenceSyncState(
        row.led_server_revision,
        row.led_dirty == 1L,
        ledPayload(
            ProfilePreferencesCodec.decodeLed(
                row.led_preferences_json,
                row.led_color_scheme_id.toInt(),
            ).value,
        ),
    )
    ProfilePreferenceSectionName.VBT -> CurrentProfilePreferenceSyncState(
        row.vbt_server_revision,
        row.vbt_dirty == 1L,
        vbtPayload(
            ProfilePreferencesCodec.decodeVbt(
                row.vbt_preferences_json,
                row.vbt_enabled == 1L,
            ).value,
        ),
    )
}
```

Before `applyPulledWhenClean`, read the row through `selectProfilePreferenceSyncRow`, call `codec.currentState(row, canonical.key.section)`, and when the row is clean, its server revision equals the canonical revision, and its reconstructed payload differs, log only the profile/section key as an invariant repair, then apply the canonical query:

```kotlin
if (!current.dirty &&
    current.serverRevision == canonical.serverRevision &&
    current.payload != canonical.payload
) {
    Logger.w("ProfilePreferenceSync") {
        "Repairing equal-revision canonical divergence for ${canonical.key}"
    }
}
```

Never log either payload.

Implement the three dispatch helpers as exhaustive `when (columns.section)` calls to the exact SQL query for CORE/RACK/WORKOUT/LED/VBT. After each update, use SQLDelight's `SELECT changes()` scalar query to return whether one row changed; add `selectChangedRowCount: SELECT changes();` beside the query matrix. A push outcome without a valid canonical is a no-op and remains dirty. Duplicate keys are rejected by the SyncManager response mapper before this repository is called.

- [ ] **Step 7: Run repository and foundation regression tests**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "*SqlDelightProfilePreferenceSyncRepositoryTest*" --tests "*SqlDelightProfilePreferencesRepositoryTest*" -Pskip.supabase.check=true
```

Expected: PASS, including malformed-local retention, both in-flight response races, equal-revision repair, dirty-pull preservation, and unknown-profile no-create behavior.

- [ ] **Step 8: Commit the sync persistence boundary**

```powershell
git add shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncRepository.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncCodec.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/sync/SqlDelightProfilePreferenceSyncRepositoryTest.kt
git commit -m "feat(sync): add profile preference sync repository"
```

---

### Task 5: Add Exact Wire JSON, Adapters, and Preference Chunk Planning

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalWireJson.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapter.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalPullAdapter.kt`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncPlanner.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapterProfilePreferencesTest.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalPullAdapterProfilePreferencesTest.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncPlannerTest.kt`

**Interfaces:**
- Consumes: Task 3's internal/wire DTOs and Task 4's strict sync codec.
- Produces: valid mutation envelopes, validated canonicals, deterministic request chunks, and unsyncable-section diagnostics.

- [ ] **Step 1: Write failing adapter tests**

Create tests that assert the timestamp/revision/generation boundary and strict pull validation:

```kotlin
@Test
fun `push adapter maps audit timestamp but keeps generation off wire`() {
    val source = ProfilePreferenceSectionSyncDto(
        key = ProfilePreferenceSectionKey("profile-a", ProfilePreferenceSectionName.CORE),
        documentVersion = 1,
        baseRevision = 5,
        clientModifiedAtEpochMs = 1_783_771_200_000L,
        localGeneration = 9,
        payload = buildJsonObject {
            put("bodyWeightKg", 82.5)
            put("weightUnit", "KG")
            put("weightIncrement", 0.5)
        },
    )

    val prepared = PortalSyncAdapter.toPortalProfilePreferenceMutation(source)

    assertEquals(9, prepared.sentLocalGeneration)
    assertEquals(5, prepared.wire.baseRevision)
    assertEquals("CORE", prepared.wire.section)
    assertFalse(
        PortalWireJson.encodeToString(
            PortalProfilePreferenceSectionMutationDto.serializer(),
            prepared.wire,
        ).contains("localGeneration"),
    )
}

@Test
fun `pull adapter rejects document version mismatch without fallback`() {
    val wire = PortalProfilePreferenceSectionCanonicalDto(
        localProfileId = "profile-a",
        section = "VBT",
        documentVersion = 2,
        serverRevision = 3,
        serverUpdatedAt = "2026-07-11T12:00:00Z",
        payload = buildJsonObject {
            put("vbtEnabled", true)
            putJsonObject("preferences") { put("version", 1) }
        },
    )

    assertIs<ProfilePreferenceCanonicalDecodeResult.Invalid>(
        PortalPullAdapter.toCanonicalProfilePreferenceSection(wire),
    )
}
```

Place the first test in `PortalSyncAdapterProfilePreferencesTest.kt` and the second in `PortalPullAdapterProfilePreferencesTest.kt`, with the required Kotlin test and JSON imports.

- [ ] **Step 2: Write failing planner boundary tests**

Create `ProfilePreferenceSyncPlannerTest.kt` with deterministic fixtures and exact encoded-size assertions:

```kotlin
@Test
fun `planner isolates oversized section and keeps valid siblings`() {
    val valid = preparedMutation("profile-a", ProfilePreferenceSectionName.CORE, payloadChars = 64)
    val oversized = preparedMutation(
        "profile-a",
        ProfilePreferenceSectionName.RACK,
        payloadChars = MAX_PROFILE_PREFERENCE_SECTION_BYTES + 1,
    )

    val plan = planProfilePreferencePushChunks(basePayload(), listOf(oversized, valid))

    assertEquals(1, plan.unsyncable.size)
    assertEquals(ProfilePreferenceSectionName.RACK, plan.unsyncable.single().key.section)
    assertEquals(listOf(ProfilePreferenceSectionName.CORE), plan.chunks.single().ledger.keys.map { it.section })
}

@Test
fun `every planned request stays within preference request cap`() {
    val mutations = (0 until 12).map { index ->
        preparedMutation("profile-$index", ProfilePreferenceSectionName.WORKOUT, payloadChars = 90_000)
    }

    val plan = planProfilePreferencePushChunks(basePayload(), mutations)

    assertTrue(plan.chunks.size > 1)
    plan.chunks.forEach { chunk ->
        val bytes = PortalWireJson.encodeToString(PortalSyncPayload.serializer(), chunk.payload)
            .encodeToByteArray()
            .size
        assertTrue(bytes <= MAX_PROFILE_PREFERENCE_REQUEST_BYTES)
    }
}
```

Add these deterministic helpers in the same test file:

```kotlin
private fun preparedMutation(
    profileId: String,
    section: ProfilePreferenceSectionName,
    payloadChars: Int,
): PreparedProfilePreferenceMutation {
    val key = ProfilePreferenceSectionKey(profileId, section)
    return PreparedProfilePreferenceMutation(
        wire = PortalProfilePreferenceSectionMutationDto(
            localProfileId = profileId,
            section = section.name,
            documentVersion = 1,
            baseRevision = 0,
            clientModifiedAt = "2026-07-11T12:00:00Z",
            payload = buildJsonObject { put("padding", "x".repeat(payloadChars)) },
        ),
        key = key,
        sentLocalGeneration = 1,
    )
}

private fun basePayload() = PortalSyncPayload(
    deviceId = "device",
    lastSync = 0,
    profileId = "profile-a",
)
```

- [ ] **Step 3: Run the tests and verify missing adapter/planner failures**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "*PortalSyncAdapterProfilePreferencesTest*" --tests "*PortalPullAdapterProfilePreferencesTest*" --tests "*ProfilePreferenceSyncPlannerTest*" -Pskip.supabase.check=true
```

Expected: FAIL because `PortalWireJson`, adapter methods, constants, and planner are absent.

- [ ] **Step 4: Create one shared wire JSON instance**

Create `PortalWireJson.kt`:

```kotlin
package com.devil.phoenixproject.data.sync

import kotlinx.serialization.json.Json

internal val PortalWireJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}
```

- [ ] **Step 5: Implement the push and pull adapters**

Add this push mapper to `PortalSyncAdapter`:

```kotlin
fun toPortalProfilePreferenceMutation(
    section: ProfilePreferenceSectionSyncDto,
): PreparedProfilePreferenceMutation = PreparedProfilePreferenceMutation(
    wire = PortalProfilePreferenceSectionMutationDto(
        localProfileId = section.key.localProfileId,
        section = section.key.section.name,
        documentVersion = section.documentVersion,
        baseRevision = section.baseRevision,
        clientModifiedAt = kotlin.time.Instant
            .fromEpochMilliseconds(section.clientModifiedAtEpochMs)
            .toString(),
        payload = section.payload,
    ),
    key = section.key,
    sentLocalGeneration = section.localGeneration,
)
```

Add this pull mapper to `PortalPullAdapter`; the stateless Task 4 sync codec validates the exact section wrapper without replacing invalid content with defaults:

```kotlin
fun toCanonicalProfilePreferenceSection(
    dto: PortalProfilePreferenceSectionCanonicalDto,
): ProfilePreferenceCanonicalDecodeResult {
    val section = ProfilePreferenceSectionName.entries.firstOrNull { it.name == dto.section }
        ?: return ProfilePreferenceCanonicalDecodeResult.Invalid(
            dto.localProfileId,
            dto.section,
            "unsupported section",
        )
    val updatedAt = runCatching {
        kotlin.time.Instant.parse(dto.serverUpdatedAt).toEpochMilliseconds()
    }.getOrElse {
        return ProfilePreferenceCanonicalDecodeResult.Invalid(
            dto.localProfileId,
            dto.section,
            "invalid serverUpdatedAt",
        )
    }
    val validation = ProfilePreferenceSyncCodec().validateCanonicalPayload(
        section = section,
        documentVersion = dto.documentVersion,
        payload = dto.payload,
    )
    if (!validation.isValid) {
        return ProfilePreferenceCanonicalDecodeResult.Invalid(
            dto.localProfileId,
            dto.section,
            validation.reason,
        )
    }
    return ProfilePreferenceCanonicalDecodeResult.Valid(
        CanonicalProfilePreferenceSection(
            key = ProfilePreferenceSectionKey(dto.localProfileId, section),
            documentVersion = dto.documentVersion,
            serverRevision = dto.serverRevision,
            serverUpdatedAtEpochMs = updatedAt,
            payload = dto.payload,
        ),
    )
}
```

- [ ] **Step 6: Implement deterministic section/request chunking**

Create `ProfilePreferenceSyncPlanner.kt`:

```kotlin
package com.devil.phoenixproject.data.sync

import kotlinx.serialization.encodeToString

internal const val MAX_PROFILE_PREFERENCE_SECTION_BYTES = 256 * 1024
internal const val MAX_PROFILE_PREFERENCE_REQUEST_BYTES = 512 * 1024

internal data class ProfilePreferencePushChunk(
    val payload: PortalSyncPayload,
    val ledger: Map<ProfilePreferenceSectionKey, Long>,
)

internal data class ProfilePreferencePushPlan(
    val chunks: List<ProfilePreferencePushChunk>,
    val unsyncable: List<ProfilePreferenceSyncIssue>,
)

internal fun planProfilePreferencePushChunks(
    basePayload: PortalSyncPayload,
    mutations: List<PreparedProfilePreferenceMutation>,
): ProfilePreferencePushPlan {
    val sorted = mutations.sortedWith(
        compareBy<PreparedProfilePreferenceMutation>({ it.key.localProfileId }, { it.key.section.ordinal }),
    )
    val valid = mutableListOf<PreparedProfilePreferenceMutation>()
    val issues = mutableListOf<ProfilePreferenceSyncIssue>()
    sorted.forEach { mutation ->
        val bytes = PortalWireJson.encodeToString(
            PortalProfilePreferenceSectionMutationDto.serializer(),
            mutation.wire,
        ).encodeToByteArray().size
        if (bytes > MAX_PROFILE_PREFERENCE_SECTION_BYTES) {
            issues += ProfilePreferenceSyncIssue(mutation.key, "section exceeds 262144 encoded bytes")
        } else {
            valid += mutation
        }
    }

    val chunks = mutableListOf<ProfilePreferencePushChunk>()
    var current = mutableListOf<PreparedProfilePreferenceMutation>()
    fun encodedPayload(items: List<PreparedProfilePreferenceMutation>): PortalSyncPayload = basePayload.copy(
        sessions = emptyList(),
        telemetry = emptyList(),
        routines = emptyList(),
        deletedRoutineIds = emptyList(),
        cycles = emptyList(),
        deletedCycleIds = emptyList(),
        rpgAttributes = null,
        badges = emptyList(),
        gamificationStats = null,
        phaseStatistics = emptyList(),
        exerciseSignatures = emptyList(),
        assessments = emptyList(),
        customExercises = emptyList(),
        externalActivities = emptyList(),
        personalRecords = emptyList(),
        allProfiles = null,
        profilePreferenceSections = items.map { it.wire },
    )
    fun emit() {
        if (current.isEmpty()) return
        chunks += ProfilePreferencePushChunk(
            payload = encodedPayload(current),
            ledger = current.associate { it.key to it.sentLocalGeneration },
        )
        current = mutableListOf()
    }

    valid.forEach { mutation ->
        val candidate = current + mutation
        val bytes = PortalWireJson.encodeToString(
            PortalSyncPayload.serializer(),
            encodedPayload(candidate),
        ).encodeToByteArray().size
        if (current.isNotEmpty() && bytes > MAX_PROFILE_PREFERENCE_REQUEST_BYTES) emit()
        current += mutation
    }
    emit()
    return ProfilePreferencePushPlan(chunks = chunks, unsyncable = issues)
}
```

- [ ] **Step 7: Run the focused adapter/planner tests and verify they pass**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "*PortalSyncAdapterProfilePreferencesTest*" --tests "*PortalPullAdapterProfilePreferencesTest*" --tests "*ProfilePreferenceSyncPlannerTest*" -Pskip.supabase.check=true
```

Expected: PASS.

- [ ] **Step 8: Commit adapters and deterministic chunk planning**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalWireJson.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapter.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalPullAdapter.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncPlanner.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapterProfilePreferencesTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalPullAdapterProfilePreferencesTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncPlannerTest.kt
git commit -m "feat(sync): map and chunk profile preference sections"
```

---

### Task 6: Enforce Transport Limits and Capture Multi-Request Tests

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakePortalApiClient.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalApiClientProfilePreferenceLimitsTest.kt`

**Interfaces:**
- Consumes: Task 5's `PortalWireJson` and size constants.
- Produces: transport defense-in-depth and a fake capable of metadata-first/chunk/legacy-response tests.

- [ ] **Step 1: Write failing transport limit tests**

Create `PortalApiClientProfilePreferenceLimitsTest.kt`:

```kotlin
package com.devil.phoenixproject.data.sync

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PortalApiClientProfilePreferenceLimitsTest {
    private val client = PortalApiClient(
        SupabaseConfig("https://fake.supabase.co", "anon"),
        PortalTokenStorage(MapSettings()),
    )

    @Test
    fun `transport rejects oversized preference section before authentication`() = runTest {
        val payload = PortalSyncPayload(
            deviceId = "device",
            lastSync = 0,
            profilePreferenceSections = listOf(
                PortalProfilePreferenceSectionMutationDto(
                    localProfileId = "profile-a",
                    section = "RACK",
                    documentVersion = 1,
                    baseRevision = 0,
                    clientModifiedAt = "2026-07-11T12:00:00Z",
                    payload = buildJsonObject { put("padding", "x".repeat(262_145)) },
                ),
            ),
        )

        val error = client.pushPortalPayload(payload).exceptionOrNull()

        assertIs<PortalApiException>(error)
        assertEquals(413, error.statusCode)
        assertTrue(error.message.orEmpty().contains("262144"))
    }

    @Test
    fun `transport rejects oversized preference request before authentication`() = runTest {
        fun mutation(index: Int) = PortalProfilePreferenceSectionMutationDto(
            localProfileId = "profile-$index",
            section = "RACK",
            documentVersion = 1,
            baseRevision = 0,
            clientModifiedAt = "2026-07-11T12:00:00Z",
            payload = buildJsonObject { put("padding", "x".repeat(174_700)) },
        )
        val payload = PortalSyncPayload(
            deviceId = "device",
            lastSync = 0,
            profilePreferenceSections = List(3, ::mutation),
        )
        assertTrue(
            payload.profilePreferenceSections.orEmpty().all { section ->
                PortalWireJson.encodeToString(
                    PortalProfilePreferenceSectionMutationDto.serializer(),
                    section,
                ).encodeToByteArray().size <= MAX_PROFILE_PREFERENCE_SECTION_BYTES
            },
        )
        assertTrue(
            PortalWireJson.encodeToString(PortalSyncPayload.serializer(), payload)
                .encodeToByteArray().size > MAX_PROFILE_PREFERENCE_REQUEST_BYTES,
        )

        val error = client.pushPortalPayload(payload).exceptionOrNull()

        assertIs<PortalApiException>(error)
        assertEquals(413, error.statusCode)
        assertTrue(error.message.orEmpty().contains("524288"))
    }
}
```

- [ ] **Step 2: Run the test and verify it fails against the existing overall-only guard**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "com.devil.phoenixproject.data.sync.PortalApiClientProfilePreferenceLimitsTest" -Pskip.supabase.check=true
```

Expected: FAIL because the call reaches authentication or does not return a 413 section error.

- [ ] **Step 3: Reuse `PortalWireJson` and add preference-specific guards**

Remove `PortalApiClient`'s private `Json` construction and use `PortalWireJson`. Before the existing overall byte check, add:

```kotlin
payload.profilePreferenceSections?.forEach { section ->
    val sectionBytes = PortalWireJson.encodeToString(
        PortalProfilePreferenceSectionMutationDto.serializer(),
        section,
    ).encodeToByteArray().size
    if (sectionBytes > MAX_PROFILE_PREFERENCE_SECTION_BYTES) {
        return Result.failure(
            PortalApiException(
                "Profile preference section ${section.localProfileId}/${section.section} is " +
                    "$sectionBytes bytes; cap is $MAX_PROFILE_PREFERENCE_SECTION_BYTES bytes.",
                statusCode = 413,
            ),
        )
    }
}

val serialized = PortalWireJson.encodeToString(PortalSyncPayload.serializer(), payload)
val payloadBytes = serialized.encodeToByteArray()
if (payload.profilePreferenceSections != null &&
    payloadBytes.size > MAX_PROFILE_PREFERENCE_REQUEST_BYTES
) {
    return Result.failure(
        PortalApiException(
            "Profile preference request is ${payloadBytes.size} bytes; " +
                "cap is $MAX_PROFILE_PREFERENCE_REQUEST_BYTES bytes.",
            statusCode = 413,
        ),
    )
}
```

Retain the existing `MAX_PAYLOAD_BYTES` check after this block.

- [ ] **Step 4: Extend the fake for multi-request orchestration**

Add these captures to `FakePortalApiClient`:

```kotlin
val pushPayloads: MutableList<PortalSyncPayload> = mutableListOf()
var pushResultsQueue: MutableList<Result<PortalSyncPushResponse>>? = null
var lastPullProfileId: String? = null
```

Update the overrides:

```kotlin
override suspend fun pushPortalPayload(payload: PortalSyncPayload): Result<PortalSyncPushResponse> {
    pushCallCount++
    lastPushPayload = payload
    pushPayloads += payload
    return pushResultsQueue?.removeFirstOrNull() ?: pushResult
}

override suspend fun pullPortalPayload(
    knownEntityIds: KnownEntityIds,
    deviceId: String,
    profileId: String?,
    cursor: String?,
    pageSize: Int?,
): Result<PortalSyncPullResponse> {
    pullCallCount++
    lastPullKnownEntityIds = knownEntityIds
    lastPullDeviceId = deviceId
    lastPullProfileId = profileId
    lastPullCursor = cursor
    lastPullPageSize = pageSize
    pullCallCursors += cursor
    pullCallTimestampsMs += pullTimestampSourceMs()
    return pullResultsQueue?.removeFirstOrNull() ?: pullResult
}
```

- [ ] **Step 5: Run the transport and existing token tests**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "*PortalApiClientProfilePreferenceLimitsTest*" --tests "*PortalTokenRefreshTest*" -Pskip.supabase.check=true
```

Expected: PASS.

- [ ] **Step 6: Commit transport guards and test captures**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakePortalApiClient.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalApiClientProfilePreferenceLimitsTest.kt
git commit -m "feat(sync): enforce profile preference payload limits"
```

---

### Task 7: Send Metadata First and Apply Preference Acknowledgements Safely

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/SyncModule.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeProfilePreferenceSyncRepository.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/SyncManagerProfilePreferencesTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalPushLimitsTest.kt`

**Interfaces:**
- Consumes: Task 4's internal persistence adapter, the data-foundation migration gate, Task 5's planner, and Task 6's transport/fakes.
- Produces: metadata-first preference-only pushes and race-safe canonical/rejection application.

- [ ] **Step 1: Add the focused fake sync repository with captures**

Create `FakeProfilePreferenceSyncRepository.kt`:

```kotlin
internal class FakeProfilePreferenceSyncRepository : ProfilePreferenceSyncRepository {
    var dirtySnapshot = ProfilePreferenceDirtySnapshot(emptyList(), emptyList())
    var snapshotCallCount = 0
    var knownProfileIds: Set<String> = setOf("profile-a")
    var onApplyPulledSections: (() -> Unit)? = null
    val appliedPushOutcomes = mutableListOf<List<ProfilePreferencePushOutcome>>()
    val appliedPulledSections = mutableListOf<List<CanonicalProfilePreferenceSection>>()

    override suspend fun snapshotDirtySections(): ProfilePreferenceDirtySnapshot {
        snapshotCallCount++
        return dirtySnapshot
    }

    override suspend fun applyPushOutcomes(
        outcomes: List<ProfilePreferencePushOutcome>,
    ): ProfilePreferenceSyncApplyReport {
        appliedPushOutcomes += outcomes
        return ProfilePreferenceSyncApplyReport(applied = outcomes.size)
    }

    override suspend fun applyPulledSections(
        sections: List<CanonicalProfilePreferenceSection>,
    ): ProfilePreferenceSyncApplyReport {
        onApplyPulledSections?.invoke()
        val known = sections.filter { it.key.localProfileId in knownProfileIds }
        appliedPulledSections += known
        return ProfilePreferenceSyncApplyReport(
            applied = known.size,
            ignoredUnknownProfile = sections.size - known.size,
        )
    }
}
```

- [ ] **Step 2: Write failing metadata-first, readiness, and legacy-backend tests**

Create `SyncManagerProfilePreferencesTest.kt` with a `Harness` that owns `FakePortalApiClient`, `PortalTokenStorage(MapSettings())`, the existing sync/gamification/metric/profile/activity fakes, and `FakeProfilePreferenceSyncRepository`. Include these assertions:

```kotlin
@Test
fun `ordinary metadata push precedes preference-only chunks`() = runTest {
    harness.preferenceSyncRepository.dirtySnapshot = ProfilePreferenceDirtySnapshot(
        valid = listOf(coreSection(generation = 1)),
        unsyncable = emptyList(),
    )
    harness.api.pushResultsQueue = mutableListOf(
        successResponse(),
        successResponse(
            profilePreferencesAccepted = true,
            canonicalProfilePreferenceSections = listOf(coreCanonical(revision = 1)),
        ),
    )

    assertTrue(harness.manager(migrationReady = true).sync().isSuccess)

    assertEquals(2, harness.api.pushPayloads.size)
    assertNotNull(harness.api.pushPayloads[0].allProfiles)
    assertNull(harness.api.pushPayloads[0].profilePreferenceSections)
    assertNull(harness.api.pushPayloads[1].allProfiles)
    assertEquals(1, harness.api.pushPayloads[1].profilePreferenceSections?.size)
}

@Test
fun `migration not ready never reads or sends profile preferences`() = runTest {
    harness.preferenceSyncRepository.dirtySnapshot = ProfilePreferenceDirtySnapshot(
        valid = listOf(coreSection(generation = 1)),
        unsyncable = emptyList(),
    )

    assertTrue(harness.manager(migrationReady = false).sync().isSuccess)

    assertEquals(1, harness.api.pushPayloads.size)
    assertEquals(0, harness.preferenceSyncRepository.snapshotCallCount)
    assertTrue(harness.preferenceSyncRepository.appliedPushOutcomes.isEmpty())
}

@Test
fun `legacy backend leaves every preference section dirty`() = runTest {
    harness.preferenceSyncRepository.dirtySnapshot = ProfilePreferenceDirtySnapshot(
        valid = listOf(coreSection(generation = 1), workoutSection(generation = 2)),
        unsyncable = emptyList(),
    )
    harness.api.pushResultsQueue = mutableListOf(successResponse(), successResponse())

    assertTrue(harness.manager(migrationReady = true).sync().isSuccess)

    assertTrue(harness.preferenceSyncRepository.appliedPushOutcomes.isEmpty())
}
```

Use this exact constructor boundary in `Harness.manager`; initialize authentication once in the harness with `tokenStorage.saveAuth(PortalAuthResponse("token", PortalUser("user", "u@example.com", null, false)))`:

```kotlin
fun manager(
    migrationReady: Boolean,
    rateLimiter: ClientRateLimiter = ClientRateLimiter(),
) = SyncManager(
    apiClient = api,
    tokenStorage = tokenStorage,
    syncRepository = syncRepository,
    gamificationRepository = gamificationRepository,
    repMetricRepository = repMetricRepository,
    userProfileRepository = profileRepository,
    profilePreferenceSyncRepository = preferenceSyncRepository,
    externalActivityRepository = externalActivityRepository,
    velocityOneRepMaxRepository = velocityOneRepMaxRepository,
    rateLimiter = rateLimiter,
    isProfilePreferenceMigrationReady = { migrationReady },
)

private fun coreSection(generation: Long) = ProfilePreferenceSectionSyncDto(
    key = ProfilePreferenceSectionKey("profile-a", ProfilePreferenceSectionName.CORE),
    documentVersion = 1,
    baseRevision = 0,
    clientModifiedAtEpochMs = 1_783_771_200_000L,
    localGeneration = generation,
    payload = buildJsonObject {
        put("bodyWeightKg", 80.0)
        put("weightUnit", "KG")
        put("weightIncrement", 0.5)
    },
)

private fun workoutSection(generation: Long) = ProfilePreferenceSectionSyncDto(
    key = ProfilePreferenceSectionKey("profile-a", ProfilePreferenceSectionName.WORKOUT),
    documentVersion = 1,
    baseRevision = 0,
    clientModifiedAtEpochMs = 1_783_771_200_000L,
    localGeneration = generation,
    payload = ProfilePreferenceSyncCodec().workoutPayload(WorkoutPreferences()),
)

private fun coreCanonical(revision: Long) = PortalProfilePreferenceSectionCanonicalDto(
    localProfileId = "profile-a",
    section = "CORE",
    documentVersion = 1,
    serverRevision = revision,
    serverUpdatedAt = "2026-07-11T12:00:00Z",
    payload = coreSection(generation = 1).payload,
)

private fun successResponse(
    profilePreferencesAccepted: Boolean? = null,
    canonicalProfilePreferenceSections: List<PortalProfilePreferenceSectionCanonicalDto> = emptyList(),
    profilePreferenceRejections: List<ProfilePreferenceSectionRejectionDto> = emptyList(),
) = PortalSyncPushResponse(
    syncTime = "2026-07-11T12:00:00Z",
    profilePreferencesAccepted = profilePreferencesAccepted,
    canonicalProfilePreferenceSections = canonicalProfilePreferenceSections,
    profilePreferenceRejections = profilePreferenceRejections,
)
```

- [ ] **Step 3: Write failing acknowledgement and in-flight generation tests**

Add tests that verify:

```kotlin
@Test
fun `accepted canonical carries sent generation into repository outcome`() = runTest {
    harness.preferenceSyncRepository.dirtySnapshot = ProfilePreferenceDirtySnapshot(
        valid = listOf(coreSection(generation = 8)),
        unsyncable = emptyList(),
    )
    harness.api.pushResultsQueue = mutableListOf(
        successResponse(),
        successResponse(
            profilePreferencesAccepted = true,
            canonicalProfilePreferenceSections = listOf(coreCanonical(revision = 4)),
        ),
    )

    harness.manager(migrationReady = true).sync()

    val outcome = harness.preferenceSyncRepository.appliedPushOutcomes.single().single()
    assertEquals(8, outcome.sentLocalGeneration)
    assertEquals(4, outcome.serverRevision)
    assertNull(outcome.rejectionReason)
}

@Test
fun `canonical conflict is applied only through generation ledger`() = runTest {
    harness.preferenceSyncRepository.dirtySnapshot = ProfilePreferenceDirtySnapshot(
        valid = listOf(coreSection(generation = 11)),
        unsyncable = emptyList(),
    )
    harness.api.pushResultsQueue = mutableListOf(
        successResponse(),
        successResponse(
            profilePreferencesAccepted = true,
            profilePreferenceRejections = listOf(
                ProfilePreferenceSectionRejectionDto(
                    localProfileId = "profile-a",
                    section = "CORE",
                    serverRevision = 6,
                    reason = "REVISION_CONFLICT",
                    canonicalSection = coreCanonical(revision = 6),
                ),
            ),
        ),
    )

    harness.manager(migrationReady = true).sync()

    val outcome = harness.preferenceSyncRepository.appliedPushOutcomes.single().single()
    assertEquals(11, outcome.sentLocalGeneration)
    assertEquals("REVISION_CONFLICT", outcome.rejectionReason)
    assertEquals(6, outcome.canonical?.serverRevision)
}
```

Task 4's SQLDelight sync repository tests separately mutate the row after snapshot and prove that both acceptance and conflict outcomes advance the server revision without overwriting the newer payload or clearing dirty state.

- [ ] **Step 4: Run the tests and verify missing orchestration failures**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "*SyncManagerProfilePreferencesTest*" -Pskip.supabase.check=true
```

Expected: FAIL because `SyncManager` has no readiness function or preference push loop.

- [ ] **Step 5: Inject migration readiness without blocking ordinary sync**

Add to the `SyncManager` constructor:

```kotlin
private val profilePreferenceSyncRepository: ProfilePreferenceSyncRepository,
private val isProfilePreferenceMigrationReady: () -> Boolean,
```

Bind the focused codec/repository and capture the migration manager before constructing `SyncManager` in `SyncModule`:

```kotlin
single { ProfilePreferenceSyncCodec() }
single<ProfilePreferenceSyncRepository> {
    SqlDelightProfilePreferenceSyncRepository(database = get(), codec = get())
}

single {
    val migrationManager = get<com.devil.phoenixproject.data.migration.MigrationManager>()
    SyncManager(
        apiClient = get(),
        tokenStorage = get(),
        syncRepository = get(),
        gamificationRepository = get(),
        repMetricRepository = get(),
        userProfileRepository = get(),
        profilePreferenceSyncRepository = get(),
        externalActivityRepository = get(),
        velocityOneRepMaxRepository = get(),
        isProfilePreferenceMigrationReady = {
            migrationManager.requiredMigrationState.value is
                com.devil.phoenixproject.data.migration.RequiredMigrationState.Ready
        },
    )
}
```

Update every existing `SyncManager` test constructor to pass a `FakeProfilePreferenceSyncRepository` and `{ true }`; only the explicit readiness test passes `{ false }`.

- [ ] **Step 6: Rate-limit every physical push request**

Replace the single acquisition at the top of `pushLocalChanges` with this helper and route every ordinary and preference call through it:

```kotlin
private suspend fun pushPayloadWithRateLimit(
    payload: PortalSyncPayload,
): Result<PortalSyncPushResponse> {
    if (!rateLimiter.tryAcquire("push", SyncConfig.PUSH_RATE_LIMIT_PER_MIN)) {
        return Result.failure(
            PortalApiException(
                "Client rate limit exceeded for push (${SyncConfig.PUSH_RATE_LIMIT_PER_MIN}/min). " +
                    "Remaining profile preferences stay dirty for the next sync.",
                statusCode = 429,
            ),
        )
    }
    return apiClient.pushPortalPayload(payload)
}
```

The existing session-batch failure contract remains unchanged. A rate limit reached during preference-only chunks stops preference sending, leaves unsent sections dirty, and retains the successful ordinary push.

- [ ] **Step 7: Implement the preference-only push loop**

After all ordinary batches succeed and after `allProfiles` has been sent, call:

```kotlin
private suspend fun pushDirtyProfilePreferences(
    deviceId: String,
    platform: String,
    lastSync: Long,
    activeProfileId: String,
    activeProfileName: String,
) {
    if (!isProfilePreferenceMigrationReady()) return
    val snapshot = profilePreferenceSyncRepository.snapshotDirtySections()
    snapshot.unsyncable.forEach { issue ->
        Logger.w("SyncManager") { "Profile preference ${issue.key} not sent: ${issue.reason}" }
    }
    val prepared = snapshot.valid.map(PortalSyncAdapter::toPortalProfilePreferenceMutation)
    if (prepared.isEmpty()) return

    val base = PortalSyncPayload(
        deviceId = deviceId,
        platform = platform,
        lastSync = lastSync,
        profileId = activeProfileId,
        profileName = activeProfileName,
    )
    val plan = planProfilePreferencePushChunks(base, prepared)
    plan.unsyncable.forEach { issue ->
        Logger.w("SyncManager") { "Profile preference ${issue.key} not sent: ${issue.reason}" }
    }

    for (chunk in plan.chunks) {
        val result = pushPayloadWithRateLimit(chunk.payload)
        if (result.isFailure) {
            Logger.w("SyncManager") {
                "Profile preference chunk remains dirty: ${result.exceptionOrNull()?.message}"
            }
            return
        }
        val response = result.getOrThrow()
        if (response.profilePreferencesAccepted != true) {
            Logger.i("SyncManager") { "Backend did not acknowledge profile preference support" }
            return
        }
        val outcomes = buildProfilePreferencePushOutcomes(response, chunk.ledger)
        if (outcomes.isNotEmpty()) {
            profilePreferenceSyncRepository.applyPushOutcomes(outcomes)
        }
    }
}
```

Implement `buildProfilePreferencePushOutcomes` so it:

- accepts only canonical/rejection keys present in the chunk ledger;
- treats duplicate canonical/rejection entries for one key as an invariant violation and produces no outcome for that key;
- maps canonical DTOs through `PortalPullAdapter.toCanonicalProfilePreferenceSection`;
- attaches `sentLocalGeneration` from the ledger;
- leaves a sent key dirty when neither a valid canonical nor a rejection exists;
- never maps local safety/consent fields.

Use one candidate list and group before applying anything:

```kotlin
private data class PreferenceOutcomeCandidate(
    val key: ProfilePreferenceSectionKey,
    val serverRevision: Long,
    val canonical: CanonicalProfilePreferenceSection?,
    val rejectionReason: String?,
)

private fun responseKey(localProfileId: String, section: String): ProfilePreferenceSectionKey? {
    val parsed = ProfilePreferenceSectionName.entries.firstOrNull { it.name == section }
        ?: return null
    return ProfilePreferenceSectionKey(localProfileId, parsed)
}

private fun buildProfilePreferencePushOutcomes(
    response: PortalSyncPushResponse,
    ledger: Map<ProfilePreferenceSectionKey, Long>,
): List<ProfilePreferencePushOutcome> {
    val candidates = mutableListOf<PreferenceOutcomeCandidate>()
    val responseCounts = mutableMapOf<ProfilePreferenceSectionKey, Int>()
    response.canonicalProfilePreferenceSections.forEach { dto ->
        val key = responseKey(dto.localProfileId, dto.section) ?: return@forEach
        if (key !in ledger) return@forEach
        responseCounts[key] = responseCounts.getOrElse(key) { 0 } + 1
        val decoded = PortalPullAdapter.toCanonicalProfilePreferenceSection(dto)
        if (decoded is ProfilePreferenceCanonicalDecodeResult.Valid && decoded.section.key == key) {
            candidates += PreferenceOutcomeCandidate(
                key = key,
                serverRevision = decoded.section.serverRevision,
                canonical = decoded.section,
                rejectionReason = null,
            )
        }
    }
    response.profilePreferenceRejections.forEach { rejection ->
        val key = responseKey(rejection.localProfileId, rejection.section) ?: return@forEach
        if (key !in ledger) return@forEach
        responseCounts[key] = responseCounts.getOrElse(key) { 0 } + 1
        val canonical = rejection.canonicalSection?.let(
            PortalPullAdapter::toCanonicalProfilePreferenceSection,
        )
        if (canonical is ProfilePreferenceCanonicalDecodeResult.Invalid) return@forEach
        val decodedCanonical = (canonical as? ProfilePreferenceCanonicalDecodeResult.Valid)?.section
        if (decodedCanonical != null && decodedCanonical.key != key) return@forEach
        candidates += PreferenceOutcomeCandidate(
            key = key,
            serverRevision = rejection.serverRevision,
            canonical = decodedCanonical,
            rejectionReason = rejection.reason,
        )
    }
    return candidates.groupBy(PreferenceOutcomeCandidate::key).mapNotNull { (key, entries) ->
        if (entries.size != 1 || responseCounts[key] != 1) {
            Logger.w("SyncManager") { "Duplicate profile preference result for $key" }
            return@mapNotNull null
        }
        val candidate = entries.single()
        ProfilePreferencePushOutcome(
            key = key,
            sentLocalGeneration = ledger.getValue(key),
            serverRevision = candidate.serverRevision,
            canonical = candidate.canonical,
            rejectionReason = candidate.rejectionReason,
        )
    }
}
```

Call `pushDirtyProfilePreferences` after the ordinary batch loop and before external-activity/PR acknowledgement stamping. Preserve and return the ordinary `lastResponse` so existing entity rejection handling remains intact.

- [ ] **Step 8: Extend batching tests for metadata ordering and physical rate accounting**

Add to `PortalPushLimitsTest`:

```kotlin
@Test
fun preferenceChunksFollowTheFinalMetadataBatch() = runTest {
    authenticate()
    fakeSyncRepo.workoutSessionsToReturn = buildSessions(73)
    fakeProfilePreferenceSyncRepo.dirtySnapshot = ProfilePreferenceDirtySnapshot(
        valid = listOf(coreSectionForSync()),
        unsyncable = emptyList(),
    )
    fakeApi.pushResult = Result.success(
        PortalSyncPushResponse(
            syncTime = "2026-07-11T12:00:00Z",
            profilePreferencesAccepted = true,
            canonicalProfilePreferenceSections = listOf(coreCanonicalForSync()),
        ),
    )

    createManager().sync()

    val preferenceIndex = fakeApi.pushPayloads.indexOfFirst { it.profilePreferenceSections != null }
    val metadataIndex = fakeApi.pushPayloads.indexOfLast { it.allProfiles != null }
    assertTrue(metadataIndex >= 0)
    assertTrue(preferenceIndex > metadataIndex)
}

@Test
fun everyPhysicalPushConsumesTheSharedRateLimit() = runTest {
    authenticate()
    fakeProfilePreferenceSyncRepo.dirtySnapshot = ProfilePreferenceDirtySnapshot(
        valid = List(20) { index ->
            ProfilePreferenceSectionSyncDto(
                key = ProfilePreferenceSectionKey(
                    "profile-$index",
                    ProfilePreferenceSectionName.RACK,
                ),
                documentVersion = 1,
                baseRevision = 0,
                clientModifiedAtEpochMs = 1_783_771_200_000L,
                localGeneration = 1,
                payload = buildJsonObject { put("padding", "x".repeat(174_700)) },
            )
        },
        unsyncable = emptyList(),
    )
    fakeApi.pushResult = Result.success(
        PortalSyncPushResponse(
            syncTime = "2026-07-11T12:00:00Z",
            profilePreferencesAccepted = true,
        ),
    )

    val result = createManager(
        rateLimiter = ClientRateLimiter(nowMs = { 0L }),
    ).sync()

    assertTrue(result.isSuccess)
    assertEquals(SyncConfig.PUSH_RATE_LIMIT_PER_MIN, fakeApi.pushPayloads.size)
    assertEquals(
        SyncConfig.PUSH_RATE_LIMIT_PER_MIN - 1,
        fakeApi.pushPayloads.count { it.profilePreferenceSections != null },
    )
    assertTrue(fakeProfilePreferenceSyncRepo.appliedPushOutcomes.isEmpty())
}
```

Add these local fixtures to `PortalPushLimitsTest`:

```kotlin
private fun coreSectionForSync() = ProfilePreferenceSectionSyncDto(
    key = ProfilePreferenceSectionKey("profile-a", ProfilePreferenceSectionName.CORE),
    documentVersion = 1,
    baseRevision = 0,
    clientModifiedAtEpochMs = 1_783_771_200_000L,
    localGeneration = 1,
    payload = buildJsonObject {
        put("bodyWeightKg", 80.0)
        put("weightUnit", "KG")
        put("weightIncrement", 0.5)
    },
)

private fun coreCanonicalForSync() = PortalProfilePreferenceSectionCanonicalDto(
    localProfileId = "profile-a",
    section = "CORE",
    documentVersion = 1,
    serverRevision = 1,
    serverUpdatedAt = "2026-07-11T12:00:00Z",
    payload = coreSectionForSync().payload,
)
```

- [ ] **Step 9: Run push orchestration and regression tests**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "*SyncManagerProfilePreferencesTest*" --tests "*PortalPushLimitsTest*" --tests "*SyncManagerTest*" -Pskip.supabase.check=true
```

Expected: PASS.

- [ ] **Step 10: Commit metadata-first preference push**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/di/SyncModule.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeProfilePreferenceSyncRepository.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/SyncManagerProfilePreferencesTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalPushLimitsTest.kt
git commit -m "feat(sync): push revisioned profile preference sections"
```

---

### Task 8: Merge Preference Pulls Before Existing Entities

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/SyncManagerProfilePreferencesTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalPullPaginationTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeSyncRepository.kt`

**Interfaces:**
- Consumes: canonical pull DTOs, the strict pull adapter, and Task 4's transactional sync repository.
- Produces: unknown-profile-safe, preference-first pull convergence without changing `localProfiles` lifecycle behavior.

- [ ] **Step 1: Write failing pull ordering and unknown-profile tests**

Add to `SyncManagerProfilePreferencesTest`:

```kotlin
@Test
fun `pull applies known preference section before existing entities`() = runTest {
    val mergeEvents = mutableListOf<String>()
    harness.preferenceSyncRepository.onApplyPulledSections = {
        mergeEvents += "preferences"
    }
    harness.syncRepository.onMergeAllPullData = {
        mergeEvents += "entities"
    }
    harness.api.pullResult = Result.success(
        PortalSyncPullResponse(
            syncTime = 1_783_771_200_000L,
            profilePreferenceSections = listOf(coreCanonical(revision = 3)),
            routines = listOf(PullRoutineDto(id = "routine-1", name = "Routine")),
        ),
    )

    assertTrue(harness.manager(migrationReady = true).sync().isSuccess)

    assertEquals(
        3,
        harness.preferenceSyncRepository.appliedPulledSections.single().single().serverRevision,
    )
    assertEquals(1, harness.syncRepository.atomicMergeCallCount)
    assertEquals(listOf("preferences", "entities"), mergeEvents)
}

@Test
fun `pull ignores preference for profile absent on device`() = runTest {
    harness.api.pullResult = Result.success(
        PortalSyncPullResponse(
            syncTime = 1_783_771_200_000L,
            profilePreferenceSections = listOf(
                coreCanonical(revision = 3).copy(localProfileId = "remote-only-profile"),
            ),
        ),
    )

    assertTrue(harness.manager(migrationReady = true).sync().isSuccess)

    assertTrue(harness.preferenceSyncRepository.appliedPulledSections.flatten().isEmpty())
    assertTrue(harness.profileRepository.allProfiles.value.none { it.id == "remote-only-profile" })
}

@Test
fun `localProfiles metadata still does not create mobile profiles`() = runTest {
    harness.api.pullResult = Result.success(
        PortalSyncPullResponse(
            syncTime = 1_783_771_200_000L,
            localProfiles = listOf(LocalProfileDto("remote-only-profile", "Remote", 2)),
        ),
    )

    harness.manager(migrationReady = true).sync()

    assertTrue(harness.profileRepository.allProfiles.value.none { it.id == "remote-only-profile" })
}
```

The focused fake's `knownProfileIds` defaults to `profile-a` and filters captured pull applications. Task 4 proves the production repository performs the equivalent check against schema-43 rows without creating either a profile or a preference row.

Add this test-only hook to `FakeSyncRepository` and invoke it immediately after the simulated-failure guard and before `atomicMergeCallCount++` in `mergeAllPullData`:

```kotlin
var onMergeAllPullData: (() -> Unit)? = null

// Inside mergeAllPullData, before captures are mutated:
onMergeAllPullData?.invoke()
```

- [ ] **Step 2: Write a failing preference-only pagination test**

Add to `PortalPullPaginationTest`:

```kotlin
@Test
fun preferenceOnlyPageCountsAsNonEmptyAndContinuesPagination() = runTest {
    authenticate()
    fakeApi.pullResultsQueue = mutableListOf(
        Result.success(
            PortalSyncPullResponse(
                syncTime = 1_783_771_200_000L,
                hasMore = true,
                nextCursor = "page-2",
                profilePreferenceSections = listOf(coreCanonicalForPull(revision = 2)),
            ),
        ),
        Result.success(
            PortalSyncPullResponse(
                syncTime = 1_783_771_201_000L,
                hasMore = false,
            ),
        ),
    )

    val result = createManager().sync()

    assertTrue(result.isSuccess)
    assertEquals(2, fakeApi.pullCallCount)
    assertEquals(listOf(null, "page-2"), fakeApi.pullCallCursors)
}
```

Add this local pull fixture:

```kotlin
private fun coreCanonicalForPull(revision: Long) =
    PortalProfilePreferenceSectionCanonicalDto(
        localProfileId = "profile-a",
        section = "CORE",
        documentVersion = 1,
        serverRevision = revision,
        serverUpdatedAt = "2026-07-11T12:00:00Z",
        payload = buildJsonObject {
            put("bodyWeightKg", 80.0)
            put("weightUnit", "KG")
            put("weightIncrement", 0.5)
        },
    )
```

- [ ] **Step 3: Run the tests and verify pull handling failures**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "*SyncManagerProfilePreferencesTest*" --tests "*PortalPullPaginationTest*" -Pskip.supabase.check=true
```

Expected: FAIL because preference sections are not counted or merged.

- [ ] **Step 4: Count preference-only pull pages correctly**

Add preference sections to `pageEntityCount`:

```kotlin
val pageEntityCount = pullResponse.sessions.size +
    pullResponse.routines.size +
    pullResponse.cycles.size +
    pullResponse.badges.size +
    pullResponse.personalRecords.size +
    (pullResponse.profilePreferenceSections?.size ?: 0) +
    (if (pullResponse.rpgAttributes != null) 1 else 0) +
    (if (pullResponse.gamificationStats != null) 1 else 0) +
    pullResponse.externalActivities.size
```

- [ ] **Step 5: Apply validated preference sections first**

At the start of `mergePullPage`, before preparing sessions, add:

```kotlin
if (isProfilePreferenceMigrationReady()) {
    val decoded = pullResponse.profilePreferenceSections.orEmpty().map(
        PortalPullAdapter::toCanonicalProfilePreferenceSection,
    )
    decoded.filterIsInstance<ProfilePreferenceCanonicalDecodeResult.Invalid>().forEach { invalid ->
        Logger.w("SyncManager") {
            "Ignored invalid profile preference ${invalid.localProfileId}/${invalid.section}: ${invalid.reason}"
        }
    }
    val valid = decoded
        .filterIsInstance<ProfilePreferenceCanonicalDecodeResult.Valid>()
        .map { it.section }
    if (valid.isNotEmpty()) {
        val report = profilePreferenceSyncRepository.applyPulledSections(valid)
        if (report.ignoredUnknownProfile > 0) {
            Logger.i("SyncManager") {
                "Ignored ${report.ignoredUnknownProfile} profile preference sections for absent profiles"
            }
        }
    }
}
```

Do not read or merge profile preferences before required migration `Ready`. Do not inspect or apply `pullResponse.localProfiles`.

- [ ] **Step 6: Re-run the Task 4 persistence merge contract**

Run the focused SQLDelight repository suite again after wiring pull. It must still prove clean+higher application, dirty+higher preservation, clean+equal repair, lower-revision ignore, matching/newer generation races, malformed canonical non-application, and unknown-profile no-create using literal CORE and WORKOUT fixtures.

- [ ] **Step 7: Run pull, pagination, invariant, and repository tests**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "*SyncManagerProfilePreferencesTest*" --tests "*PortalPullPaginationTest*" --tests "*SyncInvariantCheckerTest*" --tests "*SqlDelightProfilePreferenceSyncRepositoryTest*" -Pskip.supabase.check=true
```

Expected: PASS.

- [ ] **Step 8: Commit preference-first pull convergence**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/SyncManagerProfilePreferencesTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalPullPaginationTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeSyncRepository.kt
git commit -m "feat(sync): merge canonical profile preferences on pull"
```

---

### Task 9: Verify Compatibility, DI, Schema Dependency, and the Full Mobile Build

**Files:**
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/di/KoinModuleVerifyTest.kt` only if the existing verification test requires an explicit assertion for the new constructor dependency.
- Verify: all files changed by Tasks 1–8 and the completed data-foundation plan.

**Interfaces:**
- Consumes: complete backend handoff, schema 43 foundation, and mobile sync implementation.
- Produces: a green, reviewable sync/backend slice ready to integrate with consumer and UI plans.

- [ ] **Step 1: Run formatting checks**

Run:

```powershell
.\gradlew.bat spotlessCheck -Pskip.supabase.check=true
```

Expected: BUILD SUCCESSFUL. If formatting fails, run `spotlessApply`, inspect the changed files, and rerun `spotlessCheck`.

- [ ] **Step 2: Regenerate SQLDelight and verify the schema-43 dependency**

Run:

```powershell
.\gradlew.bat :shared:generateCommonMainVitruvianDatabaseInterface :shared:verifyCommonMainVitruvianDatabaseMigration :shared:validateSchemaManifest -Pskip.supabase.check=true
```

Expected: BUILD SUCCESSFUL with generated schema version 43, `42.sqm` included, and no manifest gaps.

- [ ] **Step 3: Run the complete focused sync suite**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "*BackendHandoffContractTest*" --tests "*ProfilePreferenceSync*" --tests "*PortalSyncAdapterProfilePreferencesTest*" --tests "*PortalPullAdapterProfilePreferencesTest*" --tests "*PortalApiClientProfilePreferenceLimitsTest*" --tests "*SyncManagerProfilePreferencesTest*" --tests "*PortalPushLimitsTest*" --tests "*PortalPullPaginationTest*" --tests "*PortalTokenRefreshTest*" --tests "*SyncManagerTest*" -Pskip.supabase.check=true
```

Expected: PASS with no skipped profile-preference tests.

- [ ] **Step 4: Run repository race and migration-gate tests**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "*SqlDelightProfilePreferenceSyncRepositoryTest*" --tests "*SqlDelightProfilePreferencesRepositoryTest*" --tests "*ProfilePreferencesMigrationTest*" --tests "*MigrationManagerTest*" -Pskip.supabase.check=true
```

Expected: PASS, including accepted/conflict acknowledgement races against a newer local generation.

- [ ] **Step 5: Run Koin graph verification**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "com.devil.phoenixproject.di.KoinModuleVerifyTest" -Pskip.supabase.check=true
```

Expected: PASS. If it fails on the new constructor boundary, bind the real `MigrationManager`, `ProfilePreferenceSyncCodec`, and `SqlDelightProfilePreferenceSyncRepository` in the verification graph rather than adding nullable production dependencies.

- [ ] **Step 6: Run all shared Android-host tests**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --continue -Pskip.supabase.check=true
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Assemble the Android debug app**

Run:

```powershell
.\gradlew.bat :androidApp:assembleDebug -Pskip.supabase.check=true
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Inspect the final diff for privacy and wire regressions**

Run:

```powershell
git diff --check
git diff -- docs/backend-handoff shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync shared/src/commonMain/kotlin/com/devil/phoenixproject/di/SyncModule.kt shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/sync shared/src/androidHostTest/kotlin/com/devil/phoenixproject/di/KoinModuleVerifyTest.kt
rg -n "safeWord|safeWordCalibrated|adultsOnlyConfirmed|adultsOnlyPrompted" shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync docs/backend-handoff
```

Expected: `git diff --check` emits nothing. The final `rg` command emits no sync DTO/payload/SQL occurrence; prose in the Edge handoff may name these fields only in an explicit prohibition section.

- [ ] **Step 9: Commit final verification-only adjustments**

If formatting or DI verification changed tracked files, commit only those verified adjustments:

```powershell
git add shared/src/androidHostTest/kotlin/com/devil/phoenixproject/di/KoinModuleVerifyTest.kt
git commit -m "test(sync): verify profile preference integration"
```

If Step 5 required no file changes, do not create an empty commit.

---

## Portal Handoff Acceptance Checklist

The portal implementer must record all of these results before the mobile release is enabled:

- [ ] The real portal migration was created with `supabase migration new profile_preferences` and the reviewed mobile SQL was copied without weakening grants, constraints, RLS, or RPC execution privileges.
- [ ] `supabase db reset`, `supabase migration list`, and `supabase test db` pass in a disposable/local environment.
- [ ] Supabase security and performance advisors were run; every finding is fixed or documented with a concrete disposition.
- [ ] Direct `anon` and `authenticated` table SELECT/INSERT/UPDATE/DELETE fail under normal grants.
- [ ] Temporary-grant RLS tests prove owner SELECT/INSERT/UPDATE/DELETE and ownership-reassignment rejection in an isolated transaction.
- [ ] Edge tests prove the JWT-derived user can mutate only that user's composite-key rows.
- [ ] Cross-user `localProfileId`, forged revisions/timestamps, unsupported versions, malformed payloads, sections over 256 KiB, and requests over 512 KiB are rejected.
- [ ] Same-section concurrent first writes produce one revision-1 winner and one canonical conflict.
- [ ] Different-section concurrent first writes both succeed at revision 1 without overwriting either section.
- [ ] Lost-ack retry converges through a canonical revision conflict.
- [ ] Pull emits preference canonicals only on its first page and retains the existing required `syncTime`.
- [ ] Database migration and both Edge Functions deploy before the mobile release begins preference sync.
