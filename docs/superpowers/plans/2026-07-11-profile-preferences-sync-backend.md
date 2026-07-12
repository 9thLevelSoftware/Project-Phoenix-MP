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
- A single preference mutation's exact raw UTF-8 JSON element span is at most 262,144 bytes. Whenever `profilePreferenceSections` is present, the complete original HTTP `PortalSyncPayload` byte sequence is at most 524,288 bytes. Edge counts `request.arrayBuffer()` bytes before one fatal UTF-8 decode, rejects a leading UTF-8 BOM/U+FEFF, and scans only the successfully decoded string. The existing 9,500,000-byte endpoint cap remains in force for requests without that field.
- Kotlin `Long` values serialized as JSON numbers must be in `-9_007_199_254_740_991L..9_007_199_254_740_991L` before entering a preference DTO. The current kotlinx.serialization wire emits JSON numbers, while Edge parses JavaScript `Number` values; out-of-range `baseRevision` and rack timestamps are dead-lettered locally for their current generation with an explicit diagnostic instead of being rounded or retried forever.
- Every preference JSON number destined for a Kotlin `Int` must be an integer in `-2_147_483_648..2_147_483_647`; every number destined for a Kotlin `Float` must survive `Math.fround` as finite and must not turn a nonzero input into zero. Every narrower Float business predicate applies to both the original JavaScript number and the narrowed Float32 result, so rounding cannot admit an out-of-range wire value. JavaScript safe-integer validation is reserved for Kotlin `Long` revisions and rack timestamps.
- Every preference string value and object key must be PostgreSQL-compatible Unicode scalar text: U+0000 and unpaired UTF-16 high/low surrogates are rejected recursively before any privileged client exists, while valid supplementary pairs are accepted. Mutation Unicode validation occurs per non-duplicate, in-limit section inside its local rejection boundary, after envelope-level raw-span integrity checks; one invalid section must not suppress valid siblings.
- Preference timestamps use one strict timezone-bearing RFC3339/ISO-instant parser with explicit calendar, day, time, fraction, and offset validation. Mutation audit timestamps, RPC canonicals, and pull canonicals share it; canonical output is normalized with `toISOString()`.
- Mobile preference diagnostics use fixed category names only. Logs never print a raw profile id, remote section string, `ProfilePreferenceSectionKey`, exception message, JSON/string/numeric value, or payload fragment; trusted local section enums and sanitized HTTP status codes are allowed.
- Preference sections are sent only after an ordinary payload has sent `allProfiles`. Each preference-only body is freshly constructed from `deviceId`, `platform`, `lastSync`, and `profilePreferenceSections`; it carries no active `profileId`, `profileName`, `allProfiles`, or ordinary entity data and is never attached to workout/session batches.
- Pull never creates, renames, deletes, or resurrects a local profile. Unknown `localProfileId` values are ignored and reported only as a sanitized count/category; the raw id is never logged.
- Direct `PUBLIC`, `anon`, and `authenticated` DML on `public.local_profile_preferences` is revoked. Remote mutation is Edge-only.
- Edge code verifies the user JWT, derives `user_id` from `auth.getUser`, and uses a server-only service-role client with an explicit verified `user_id` predicate for every privileged query. Returned Auth errors with status 400/401/403 are definitive credential rejection (401); 429, 5xx, missing/other status, malformed results, and thrown/rejected auth calls are operational failures (sanitized 503 with name-only logging). The service-role client is never constructed on either path.
- The complete line-ending-normalized Edge handoff artifact is sealed by a pinned SHA-256 literal in `BackendHandoffContractTest`, in addition to focused fragment/allowlist tests.
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
- Create: `docs/backend-handoff/profile-preference-byte-goldens.json` — shared raw-JSON boundary recipes consumed by both Kotlin and Deno tests.
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

import com.devil.phoenixproject.data.auth.sha256
import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BackendHandoffContractTest {
    private fun sql(): String = assertNotNull(
        readProjectFile("docs/backend-handoff/profile-preferences-supabase.sql"),
        "Supabase handoff SQL must be tracked in the mobile repository",
    )

    private fun normalizedSql(): String = normalizedTopLevelStatements(sql())
        .joinToString(separator = "; ", postfix = ";")

    private fun normalizedTopLevelStatements(value: String): List<String> =
        topLevelStatements(value).map(::normalizeStatement)

    private fun normalizeStatement(value: String): String =
        lowercaseOutsideLiterals(value.replace(Regex("""\s+"""), " ").trim())

    private fun topLevelStatements(value: String): List<String> {
        val statements = mutableListOf<String>()
        val statement = StringBuilder()
        var quote: Char? = null
        var dollarDelimiter: String? = null
        var inLineComment = false
        var blockCommentDepth = 0
        var index = 0

        fun finishStatement() {
            statement.toString().trim().takeIf(String::isNotEmpty)?.let(statements::add)
            statement.clear()
        }

        while (index < value.length) {
            val character = value[index]
            val next = value.getOrNull(index + 1)
            if (dollarDelimiter != null) {
                if (value.startsWith(dollarDelimiter, index)) {
                    statement.append(dollarDelimiter)
                    index += dollarDelimiter.length
                    dollarDelimiter = null
                } else {
                    statement.append(character)
                    index += 1
                }
                continue
            }
            if (inLineComment) {
                if (character == '\n' || character == '\r') {
                    statement.append(' ')
                    inLineComment = false
                }
                index += 1
                continue
            }
            if (blockCommentDepth > 0) {
                when {
                    character == '/' && next == '*' -> {
                        blockCommentDepth += 1
                        index += 2
                    }
                    character == '*' && next == '/' -> {
                        blockCommentDepth -= 1
                        index += 2
                        if (blockCommentDepth == 0) statement.append(' ')
                    }
                    else -> index += 1
                }
                continue
            }
            if (quote != null) {
                statement.append(character)
                if (character == quote && next == quote) {
                    statement.append(next)
                    index += 2
                } else {
                    if (character == quote) quote = null
                    index += 1
                }
                continue
            }
            when {
                character == '-' && next == '-' -> {
                    inLineComment = true
                    index += 2
                }
                character == '/' && next == '*' -> {
                    blockCommentDepth = 1
                    index += 2
                }
                character == '\'' || character == '"' -> {
                    quote = character
                    statement.append(character)
                    index += 1
                }
                character == '$' -> {
                    val delimiter = dollarQuoteDelimiterAt(value, index)
                    if (delimiter == null) {
                        statement.append(character)
                        index += 1
                    } else {
                        dollarDelimiter = delimiter
                        statement.append(delimiter)
                        index += delimiter.length
                    }
                }
                character == ';' -> {
                    finishStatement()
                    index += 1
                }
                else -> {
                    statement.append(character)
                    index += 1
                }
            }
        }
        assertTrue(quote == null, "Unterminated quoted SQL token")
        assertTrue(dollarDelimiter == null, "Unterminated dollar-quoted SQL body")
        assertEquals(0, blockCommentDepth, "Unterminated SQL block comment")
        finishStatement()
        return statements
    }

    private fun dollarQuoteDelimiterAt(value: String, index: Int): String? {
        if (value.getOrNull(index) != '$') return null
        val previous = value.getOrNull(index - 1)
        if (previous != null && (previous.isLetterOrDigit() || previous == '_' || previous == '$')) {
            return null
        }
        var cursor = index + 1
        if (value.getOrNull(cursor) == '$') return value.substring(index, cursor + 1)
        val first = value.getOrNull(cursor) ?: return null
        if (!(first.isLetter() || first == '_')) return null
        cursor += 1
        while (cursor < value.length) {
            when (val current = value[cursor]) {
                '$' -> return value.substring(index, cursor + 1)
                else -> if (current.isLetterOrDigit() || current == '_') cursor += 1 else return null
            }
        }
        return null
    }

    private fun lowercaseOutsideLiterals(value: String): String = buildString(value.length) {
        var quote: Char? = null
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (quote != null && character == quote && value.getOrNull(index + 1) == quote) {
                append(character)
                append(value[index + 1])
                index += 2
                continue
            }
            when {
                quote != null -> {
                    append(character)
                    if (character == quote) quote = null
                }
                character == '\'' || character == '"' -> {
                    append(character)
                    quote = character
                }
                character == ' ' &&
                    ((length > 0 && this[length - 1] == '(') ||
                        value.getOrNull(index + 1) == ')') -> Unit
                else -> append(character.lowercaseChar())
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

    private fun exactPreflightStatement(): String {
        val delimiter = "\$preflight\$"
        return normalizeStatement(
            """
            DO $delimiter
            DECLARE
                matching_key text[];
            BEGIN
                IF to_regclass('public.local_profiles') IS NULL THEN
                    RAISE EXCEPTION
                        'profile preferences preflight: public.local_profiles does not exist';
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
            $delimiter
            """.trimIndent(),
        )
    }

    private fun exactTask1SecurityStatements(): List<String> = listOf(
        "alter table public.local_profile_preferences enable row level security",
        "create policy local_profile_preferences_owner_select " +
            "on public.local_profile_preferences for select to authenticated " +
            "using ((select auth.uid()) = user_id)",
        "create policy local_profile_preferences_owner_insert " +
            "on public.local_profile_preferences for insert to authenticated " +
            "with check ((select auth.uid()) = user_id)",
        "create policy local_profile_preferences_owner_update " +
            "on public.local_profile_preferences for update to authenticated " +
            "using ((select auth.uid()) = user_id) " +
            "with check ((select auth.uid()) = user_id)",
        "create policy local_profile_preferences_owner_delete " +
            "on public.local_profile_preferences for delete to authenticated " +
            "using ((select auth.uid()) = user_id)",
        "revoke all on table public.local_profile_preferences from public",
        "revoke all on table public.local_profile_preferences from anon",
        "revoke all on table public.local_profile_preferences from authenticated",
        "grant select, insert, update, delete on table " +
            "public.local_profile_preferences to service_role",
    )

    private fun assertTask1ExecutableEnvelope(statements: List<String>) {
        assertEquals(13, statements.size)
        assertEquals("begin", statements[0])
        assertEquals(exactPreflightStatement(), statements[1])
        assertTrue(statements[2].startsWith("create table public.local_profile_preferences("))
        assertEquals(exactTask1SecurityStatements(), statements.subList(3, 12))
        assertEquals("commit", statements[12])
    }

    @Test
    fun sqlHandoffDeclaresTheExactProfilePreferenceSchema() {
        val statements = normalizedTopLevelStatements(sql())
        assertTask1ExecutableEnvelope(statements)
        val sql = statements.joinToString(separator = "; ", postfix = ";")
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
        val statements = normalizedTopLevelStatements(sql())
        assertTask1ExecutableEnvelope(statements)
        val sql = statements.joinToString(separator = "; ", postfix = ";")
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
- Create: `docs/backend-handoff/profile-preference-byte-goldens.json`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/BackendHandoffContractTest.kt`

**Interfaces:**
- Consumes: secured table from Task 1.
- Produces: service-role-only `mutate_local_profile_preference_section` RPC and exact portal push/pull implementation instructions.

- [ ] **Step 1: Extend the contract test for atomic mutation and Edge authorization**

Extend the committed Task 1 test rather than adding a second SQL parser. Replace
`assertTask1ExecutableEnvelope` with `assertFinalExecutableEnvelope`, update both schema/security
tests to call the final helper, and keep Task 1's quote/comment/dollar-aware
`topLevelStatements` → `normalizeStatement` pipeline. The table-only commit is intentionally
13 statements; this Task 2 RED step changes the final contract to the exact 19-statement chain.
Add imports for `com.devil.phoenixproject.data.auth.sha256` and `assertNotEquals`, then add these helpers/tests to `BackendHandoffContractTest`. The known SHA-256-of-empty-string value is an intentional RED sentinel, not an accepted final hash. Only after Step 5 writes and reviews the complete Edge handoff may its actual LF-normalized digest replace the RHS of `EXPECTED_EDGE_HANDOFF_SHA256`; the final value must be a concrete independent 64-character lowercase literal and must never be computed from `edgeContract()` at assertion time.

```kotlin
private fun edgeContract(): String = assertNotNull(
    readProjectFile("docs/backend-handoff/profile-preferences-edge-functions.md"),
    "Edge Function handoff must be tracked in the mobile repository",
)

private fun byteGoldenArtifact(): String = assertNotNull(
    readProjectFile("docs/backend-handoff/profile-preference-byte-goldens.json"),
    "Shared profile preference byte goldens must be tracked in the mobile repository",
)

private val exactByteGoldenArtifact = """
{
  "version": 1,
  "paddingMarker": "__ASCII_PADDING__",
  "sectionMarker": "__SECTION_JSON__",
  "sectionRawTemplate": "{\"localProfileId\":\"profile-a\",\"section\":\"RACK\",\"documentVersion\":1,\"baseRevision\":0,\"clientModifiedAt\":\"2026-07-11T12:00:00Z\",\"payload\":{\"version\":1,\"items\":[{\"id\":\"rack-a\",\"name\":\"π界🙂\\\"\\\\__ASCII_PADDING__\",\"category\":\"OTHER\",\"weightKg\":20.0,\"behavior\":\"DISPLAY_ONLY\",\"enabled\":true,\"sortOrder\":0,\"createdAt\":-1e3,\"updatedAt\":0}]}}",
  "requestRawTemplate": "{\"deviceId\":\"golden-device\",\"platform\":\"android\",\"lastSync\":0,\"profileId\":\"profile-a\",\"profileName\":\"π界🙂\\\"\\\\__ASCII_PADDING__\",\"profilePreferenceSections\":[__SECTION_JSON__]}",
  "sectionTargetBytes": [262143, 262144, 262145],
  "requestTargetBytes": [524287, 524288, 524289]
}
""".trimIndent()

private fun normalizedTrackedText(value: String): String =
    value.replace("\r\n", "\n").removeSuffix("\n")

private val SHA256_EMPTY_RED_SENTINEL =
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

// Task 2 RED only. Step 5 replaces this RHS with the reviewed artifact's concrete digest.
private val EXPECTED_EDGE_HANDOFF_SHA256 = SHA256_EMPTY_RED_SENTINEL

private fun normalizedEdgeHandoff(value: String): String =
    value.replace("\r\n", "\n").replace('\r', '\n')

private fun ByteArray.lowerHex(): String = joinToString("") {
    (it.toInt() and 0xff).toString(16).padStart(2, '0')
}

private fun edgeHandoffSha256(value: String): String =
    sha256(normalizedEdgeHandoff(value).encodeToByteArray()).lowerHex()

private fun executableTypeScript(): String = Regex(
    pattern = """(?s)```typescript\s+(.*?)```""",
).findAll(edgeContract())
    .joinToString("\n") { match -> match.groupValues[1] }
    .replace(Regex("""(?s)/[*].*?[*]/"""), " ")
    .lineSequence()
    .map { line -> line.substringBefore("//") }
    .joinToString("\n")

private fun portalTestManifest(): Set<String> {
    val matches = Regex("""(?s)```portal-test-manifest\s+(.*?)```""")
        .findAll(edgeContract())
        .toList()
    assertEquals(1, matches.size, "Expected exactly one portal test manifest")
    val lines = matches.single().groupValues[1]
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toList()
    assertEquals(lines.size, lines.toSet().size, "Portal test manifest entries must be unique")
    return lines.toSet()
}

private fun exactFunctionAclStatements(): List<String> = listOf(
    "revoke all on function public.local_profile_preference_section_canonical(" +
        "public.local_profile_preferences, text) from public, anon, authenticated",
    "revoke all on function public.mutate_local_profile_preference_section(" +
        "uuid, text, text, integer, bigint, jsonb) from public, anon, authenticated",
    "grant execute on function public.local_profile_preference_section_canonical(" +
        "public.local_profile_preferences, text) to service_role",
    "grant execute on function public.mutate_local_profile_preference_section(" +
        "uuid, text, text, integer, bigint, jsonb) to service_role",
)

/*
 * Deliberately duplicate the complete Step 3 and Step 4 SQL blocks as raw triple-quoted
 * literals. These constants are independent test oracles; never derive them from sql().
 * The literal contents are exactly the full CREATE FUNCTION statements shown below,
 * including every branch and dollar-quoted body.
 */
private val EXACT_CANONICAL_FUNCTION_SQL = """
CREATE FUNCTION public.local_profile_preference_section_canonical(
    p_row public.local_profile_preferences,
    p_section text
) RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = ''
AS ${'$'}canonical${'$'}
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
${'$'}canonical${'$'}
"""

private val EXACT_MUTATION_FUNCTION_SQL = """
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
SET search_path = ''
AS ${'$'}mutation${'$'}
DECLARE
    current_row public.local_profile_preferences%ROWTYPE;
    current_revision bigint;
BEGIN
    IF p_section IS NULL OR p_section NOT IN ('CORE', 'RACK', 'WORKOUT', 'LED', 'VBT') THEN
        RETURN QUERY SELECT false, 'UNSUPPORTED_SECTION', 0::bigint, NULL::jsonb;
        RETURN;
    END IF;
    IF p_document_version IS NULL OR p_document_version <> 1 THEN
        RETURN QUERY SELECT false, 'UNSUPPORTED_DOCUMENT_VERSION', 0::bigint, NULL::jsonb;
        RETURN;
    END IF;
    IF p_base_revision IS NULL OR p_base_revision < 0
       OR p_payload IS NULL OR jsonb_typeof(p_payload) <> 'object' THEN
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
        ON CONFLICT (user_id, local_profile_id) DO NOTHING
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
${'$'}mutation${'$'}
"""

private val exactCanonicalFunctionStatement =
    normalizeStatement(EXACT_CANONICAL_FUNCTION_SQL)
private val exactMutationFunctionStatement =
    normalizeStatement(EXACT_MUTATION_FUNCTION_SQL)

private fun assertFinalExecutableEnvelope(statements: List<String>) {
    assertEquals(19, statements.size, "Expected the exact final atomic statement chain")
    assertEquals("begin", statements[0])
    assertEquals(exactPreflightStatement(), statements[1])
    assertTrue(statements[2].startsWith("create table public.local_profile_preferences("))
    assertEquals(exactCanonicalFunctionStatement, statements[3])
    assertEquals(exactMutationFunctionStatement, statements[4])
    assertEquals(exactFunctionAclStatements(), statements.subList(5, 9))
    assertEquals(exactTask1SecurityStatements(), statements.subList(9, 18))
    assertEquals("commit", statements[18])
}

@Test
fun `sql handoff has the exact functions ACLs and 19 statement envelope`() {
    val statements = normalizedTopLevelStatements(sql())
    assertFinalExecutableEnvelope(statements)
    assertEquals(2, statements.count { it.startsWith("create function ") })
    assertEquals(
        exactFunctionAclStatements(),
        statements.filter { " on function " in it },
    )
    val tableAcls = statements.filter {
        (" on table public.local_profile_preferences " in it) &&
            (it.startsWith("revoke ") || it.startsWith("grant "))
    }
    assertEquals(exactTask1SecurityStatements().takeLast(4), tableAcls)
}

@Test
fun `exact function bodies reject executable additions`() {
    val valid = sql()
    val mutations = mapOf(
        "canonical DELETE" to valid.replace(
            "    SELECT jsonb_build_object(",
            "    DELETE FROM public.local_profile_preferences;\n" +
                "    SELECT jsonb_build_object(",
        ),
        "mutation PERFORM" to valid.replace(
            "BEGIN\n    IF p_section IS NULL",
            "BEGIN\n    PERFORM now();\n    IF p_section IS NULL",
        ),
        "mutation dblink_exec" to valid.replace(
            "BEGIN\n    IF p_section IS NULL",
            "BEGIN\n    PERFORM dblink_exec('remote', 'DELETE FROM public.local_profiles');\n" +
                "    IF p_section IS NULL",
        ),
        "mutation role change" to valid.replace(
            "BEGIN\n    IF p_section IS NULL",
            "BEGIN\n    SET LOCAL ROLE authenticated;\n    IF p_section IS NULL",
        ),
        "mutation transaction control" to valid.replace(
            "BEGIN\n    IF p_section IS NULL",
            "BEGIN\n    COMMIT;\n    IF p_section IS NULL",
        ),
    )
    mutations.forEach { (name, mutation) ->
        assertFailsWith<AssertionError>("Must reject $name") {
            assertFinalExecutableEnvelope(normalizedTopLevelStatements(mutation))
        }
    }
}

@Test
fun `edge executable contract authenticates validates fully then performs scoped admin calls`() {
    val code = executableTypeScript()
    listOf(
        "auth.getUser(userJwt)",
        "bearerMatch",
        "authOperationalFailure",
        "status === 400 || status === 401 || status === 403",
        "Object.hasOwn(authRecord, \"error\")",
        "Object.hasOwn(authRecord, \"data\")",
        "{ status: 503 }",
        "req.arrayBuffer()",
        "TextDecoder(\"utf-8\", { fatal: true",
        "hasLeadingUtf8Bom",
        "SUPABASE_SERVICE_ROLE_KEY",
        "parsePreferenceEnvelope",
        "validateCorePayload",
        "validateRackPayload",
        "validateWorkoutPayload",
        "validateLedPayload",
        "validateVbtPayload",
        "LOCAL_ONLY_KEYS",
        "requirePostgresTextTree",
        "requireInt32",
        "requireFloat32",
        "!predicate(value)",
        "!predicate(narrowed)",
        "requireSafeJsonLong",
        "requireRfc3339Instant",
        "INT32_MIN = -2_147_483_648",
        "originalBodyBytes.byteLength",
        "MAX_PROFILE_PREFERENCE_SECTION_BYTES = 262_144",
        "MAX_PROFILE_PREFERENCE_REQUEST_BYTES = 524_288",
        "scanTopLevelJsonObject",
        "preferenceElementSpans",
        "rawPreferenceElementBytes",
        "rawBodyBytes > MAX_PROFILE_PREFERENCE_REQUEST_BYTES",
        "duplicateIdentities",
        "validatedMutations",
        "admin.rpc(\"mutate_local_profile_preference_section\"",
        "p_user_id: verifiedUserId",
        ".eq(\"user_id\", verifiedUserId)",
        ".eq(\"local_profile_id\", requestedProfileId)",
        "throw new PreferenceInfrastructureError",
        "console.error({ name: safeErrorName(error",
        "normalized.toISOString()",
    ).forEach { fragment -> assertTrue(fragment in code, fragment) }

    fun quotedInitializer(pattern: String): List<String> {
        val body = assertNotNull(
            Regex(pattern).find(code),
            "Missing exact TypeScript allowlist: $pattern",
        ).groupValues[1]
        return Regex(""""([^"]+)"""")
            .findAll(body)
            .map { match -> match.groupValues[1] }
            .toList()
    }
    assertEquals(
        listOf(
            "deviceId", "platform", "lastSync", "sessions", "telemetry", "routines",
            "deletedRoutineIds", "cycles", "deletedCycleIds", "rpgAttributes", "badges",
            "gamificationStats", "phaseStatistics", "exerciseSignatures", "assessments",
            "customExercises", "profileId", "profileName", "allProfiles",
            "externalActivities", "personalRecords", "profilePreferenceSections",
        ),
        quotedInitializer(
            """(?s)const PUSH_BODY_KEYS = new Set[(][[](.+?)[]][)];""",
        ),
    )
    assertEquals(
        listOf(
            "localProfileId", "section", "documentVersion", "baseRevision",
            "clientModifiedAt", "payload",
        ),
        quotedInitializer("""(?s)const MUTATION_KEYS = [[](.+?)[]] as const;"""),
    )
    assertEquals(
        listOf(
            "safeword", "safewordcalibrated", "adultsonlyconfirmed",
            "adultsonlyprompted", "localgeneration", "dirty", "legacymigrationversion",
        ),
        quotedInitializer(
            """(?s)const LOCAL_ONLY_KEYS = new Set[(][[](.+?)[]][)];""",
        ),
    )

    assertTrue("rawBodyBytes > MAX_PROFILE_PREFERENCE_REQUEST_BYTES" in code)
    assertFalse("requirePostgresTextTree(rawMutation" in code)
    assertFalse("console.error(\"profile preference infrastructure failure\"" in code)
    assertFalse(Regex("""\buserId\s*[?:]?\s*:\s*string""").containsMatchIn(code))
    assertFalse(Regex("""console[.](?:log|info|warn|error)[(][^)]*SERVICE_ROLE""").containsMatchIn(code))
}

@Test
fun `shared byte golden artifact is the exact cross-language oracle`() {
    assertEquals(
        exactByteGoldenArtifact,
        normalizedTrackedText(byteGoldenArtifact()),
    )
}

@Test
fun `complete normalized Edge handoff is sealed by a pinned digest`() {
    val original = normalizedEdgeHandoff(edgeContract())
    val actual = edgeHandoffSha256(original)
    assertEquals(
        EXPECTED_EDGE_HANDOFF_SHA256,
        actual,
        "After writing and reviewing the exact handoff, pin this digest: $actual",
    )
    assertNotEquals(
        SHA256_EMPTY_RED_SENTINEL,
        EXPECTED_EDGE_HANDOFF_SHA256,
        "The Task 2 RED sentinel must not survive Step 5",
    )

    val appended = original +
        "\n```typescript\nthrow new Error(\"appended executable mutation\");\n```\n"
    val inverted = original.replaceFirst(
        "rawBodyBytes > MAX_PROFILE_PREFERENCE_REQUEST_BYTES",
        "rawBodyBytes <= MAX_PROFILE_PREFERENCE_REQUEST_BYTES",
    )
    assertNotEquals(original, inverted, "Digest mutation target must exist")
    assertNotEquals(EXPECTED_EDGE_HANDOFF_SHA256, edgeHandoffSha256(appended))
    assertNotEquals(EXPECTED_EDGE_HANDOFF_SHA256, edgeHandoffSha256(inverted))
}

@Test
fun `edge handoff names executable portal tests rather than prose-only assurances`() {
    assertEquals(
        setOf(
            "database:exact-function-acls-and-no-client-dml",
            "database:temporary-grant-owner-rls-and-cross-owner-user-id-protection",
            "database:base-revision-accept-and-stale-canonical-conflict",
            "edge:auth-and-cross-user-profile-rejection",
            "edge:auth-rejection-vs-operational-outage-classification",
            "edge:strict-five-section-validation-and-local-only-rejection",
            "edge:kotlin-int32-float32-unicode-and-rfc3339-parity",
            "edge:fatal-utf8-bom-and-original-byte-enforcement",
            "edge:section-262143-262144-262145-byte-boundaries",
            "edge:envelope-524287-524288-524289-byte-boundaries",
            "edge:unexpected-rpc-error-is-sanitized-5xx",
            "edge:same-section-concurrent-first-write",
            "edge:different-section-concurrent-first-write",
            "edge:lost-ack-retry-canonical-convergence",
            "edge:mutation-and-first-page-pull-canonical-equality",
            "edge:later-pull-pages-omit-preferences-and-keep-sync-time",
        ),
        portalTestManifest(),
    )
}
```

- [ ] **Step 2: Run the test and verify the missing RPC/document failures**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "com.devil.phoenixproject.data.sync.BackendHandoffContractTest" -Pskip.supabase.check=true
```

Expected: FAIL because the RPC/Edge handoff and hardening fragments are absent; after the handoff is first written, the deliberate empty-digest RED sentinel still fails until Step 5 pins the reviewed complete artifact digest.

- [ ] **Step 3: Add a canonical-section SQL projection**

Insert this function before Task 1's privilege block and `COMMIT`:

```sql
CREATE FUNCTION public.local_profile_preference_section_canonical(
    p_row public.local_profile_preferences,
    p_section text
) RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = ''
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
SET search_path = ''
AS $mutation$
DECLARE
    current_row public.local_profile_preferences%ROWTYPE;
    current_revision bigint;
BEGIN
    IF p_section IS NULL OR p_section NOT IN ('CORE', 'RACK', 'WORKOUT', 'LED', 'VBT') THEN
        RETURN QUERY SELECT false, 'UNSUPPORTED_SECTION', 0::bigint, NULL::jsonb;
        RETURN;
    END IF;
    IF p_document_version IS NULL OR p_document_version <> 1 THEN
        RETURN QUERY SELECT false, 'UNSUPPORTED_DOCUMENT_VERSION', 0::bigint, NULL::jsonb;
        RETURN;
    END IF;
    IF p_base_revision IS NULL OR p_base_revision < 0
       OR p_payload IS NULL OR jsonb_typeof(p_payload) <> 'object' THEN
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
        ON CONFLICT (user_id, local_profile_id) DO NOTHING
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

Create `docs/backend-handoff/profile-preferences-edge-functions.md` as a local-only backend handoff. It tells the portal implementer exactly what to create and test; this mobile-repository task does not deploy an Edge Function, apply a remote migration, or mutate the Supabase project. Name these portal targets exactly:

```text
supabase/functions/mobile-sync-push/index.ts
supabase/functions/mobile-sync-push/index.test.ts
supabase/functions/mobile-sync-pull/index.ts
supabase/functions/mobile-sync-pull/index.test.ts
supabase/functions/_shared/profile-preference-byte-goldens.json
supabase/tests/database/profile_preferences.test.sql
supabase/config.toml
supabase/migrations/  (created by: supabase migration new profile_preferences)
docs/profile-preferences-advisor-dispositions.md
```

Create `docs/backend-handoff/profile-preference-byte-goldens.json` with this exact content. The portal implementer copies it byte-for-byte to `supabase/functions/_shared/profile-preference-byte-goldens.json`; the Kotlin contract test compares the tracked object exactly, and the Deno tests compare the portal copy's SHA-256 to the handoff artifact before using it:

```json
{
  "version": 1,
  "paddingMarker": "__ASCII_PADDING__",
  "sectionMarker": "__SECTION_JSON__",
  "sectionRawTemplate": "{\"localProfileId\":\"profile-a\",\"section\":\"RACK\",\"documentVersion\":1,\"baseRevision\":0,\"clientModifiedAt\":\"2026-07-11T12:00:00Z\",\"payload\":{\"version\":1,\"items\":[{\"id\":\"rack-a\",\"name\":\"π界🙂\\\"\\\\__ASCII_PADDING__\",\"category\":\"OTHER\",\"weightKg\":20.0,\"behavior\":\"DISPLAY_ONLY\",\"enabled\":true,\"sortOrder\":0,\"createdAt\":-1e3,\"updatedAt\":0}]}}",
  "requestRawTemplate": "{\"deviceId\":\"golden-device\",\"platform\":\"android\",\"lastSync\":0,\"profileId\":\"profile-a\",\"profileName\":\"π界🙂\\\"\\\\__ASCII_PADDING__\",\"profilePreferenceSections\":[__SECTION_JSON__]}",
  "sectionTargetBytes": [262143, 262144, 262145],
  "requestTargetBytes": [524287, 524288, 524289]
}
```

Both languages implement the same recipe: parse only the artifact wrapper, preserve each raw-template string verbatim, replace the section padding marker with enough ASCII `x` bytes to reach the requested section target, and assert that the marker occurs exactly once. For a request golden, first replace `__SECTION_JSON__` with the valid section template containing one ASCII padding byte, then replace the request padding marker with enough ASCII `x` bytes to reach the requested complete-body target. Compute padding from UTF-8 byte counts, not character counts, and assert the final count equals its target. Both test suites assert that the generated raw JSON retains the decimal lexeme `20.0`, exponent lexeme `-1e3`, escaped quote and backslash, and multibyte `π界🙂`. Kotlin owns parity between these raw spans and the real kotlinx mutation/request decoders plus the mobile scanner; it does not exercise an HTTP raw-body handler. Deno owns enforcement through the real raw Edge handler, including 400/413 behavior, privileged-call suppression, and inclusive size boundaries. These are shared cross-language fixtures, not independently reconstructed goldens.

Start the handoff with the current official [Edge authorization guidance](https://supabase.com/docs/guides/functions/auth), [RLS guidance](https://supabase.com/docs/guides/database/postgres/row-level-security), and [Data API exposure change](https://supabase.com/changelog/45329-breaking-change-tables-not-exposed-to-data-and-graphql-api-automatically). Use these concrete TypeScript types:

```typescript
type JsonRecord = Record<string, unknown>;
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
    | "DUPLICATE_SECTION"
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

`REVISION_CONFLICT`, `VALIDATION_FAILED`, `UNSUPPORTED_SECTION`, `UNSUPPORTED_DOCUMENT_VERSION`, and `UNKNOWN_PROFILE` are domain rejections returned by the RPC and may coexist with successful sibling sections. `SECTION_TOO_LARGE` and `DUPLICATE_SECTION` are Edge validation rejections. A missing/malformed bearer header or a returned `auth.getUser` Auth error whose numeric status is exactly 400, 401, or 403 is a definitive credential rejection and returns 401. Returned Auth errors with 429, 5xx, any other/missing status, malformed success/no-user results, and thrown/rejected calls are operational auth failures: return a generic 503, log only `{ name }`, and never construct the admin client. Transport, PostgREST/RPC, permission, timeout, malformed-RPC-row, and pull-query failures are likewise sanitized infrastructure 5xx responses and are never relabeled as domain rejection. Document version `1` is the only supported version; any other wrapper version, or an embedded `version` other than `1`, uses `UNSUPPORTED_DOCUMENT_VERSION` instead of a generic validation reason.

Put these executable runtime parsers in `mobile-sync-push/index.ts` (or import them from a tested sibling module without changing their behavior). This is the complete request allowlist and the complete version-1 schema for all five wrappers; no schema library may silently strip unknown keys or coerce strings into numbers/booleans:

```typescript
type ValidationReason =
  | "VALIDATION_FAILED"
  | "UNSUPPORTED_SECTION"
  | "UNSUPPORTED_DOCUMENT_VERSION";

class PreferenceValidationError extends Error {
  constructor(
    readonly reason: ValidationReason,
    readonly field: string,
  ) {
    super("Invalid profile preference field: " + field);
    this.name = "PreferenceValidationError";
  }
}

class PreferenceInfrastructureError extends Error {
  constructor(readonly operation: string) {
    super("Profile preference infrastructure failure");
    this.name = "PreferenceInfrastructureError";
  }
}

const MAX_PROFILE_PREFERENCE_SECTION_BYTES = 262_144;
const MAX_PROFILE_PREFERENCE_REQUEST_BYTES = 524_288;
const MAX_MOBILE_SYNC_REQUEST_BYTES = 9_500_000;
const INT32_MIN = -2_147_483_648;
const INT32_MAX = 2_147_483_647;
const utf8Bytes = (rawJson: string): number =>
  new TextEncoder().encode(rawJson).byteLength;

const PUSH_BODY_KEYS = new Set([
  "deviceId",
  "platform",
  "lastSync",
  "sessions",
  "telemetry",
  "routines",
  "deletedRoutineIds",
  "cycles",
  "deletedCycleIds",
  "rpgAttributes",
  "badges",
  "gamificationStats",
  "phaseStatistics",
  "exerciseSignatures",
  "assessments",
  "customExercises",
  "profileId",
  "profileName",
  "allProfiles",
  "externalActivities",
  "personalRecords",
  "profilePreferenceSections",
]);

const MUTATION_KEYS = [
  "localProfileId",
  "section",
  "documentVersion",
  "baseRevision",
  "clientModifiedAt",
  "payload",
] as const;

const LOCAL_ONLY_KEYS = new Set([
  "safeword",
  "safewordcalibrated",
  "adultsonlyconfirmed",
  "adultsonlyprompted",
  "localgeneration",
  "dirty",
  "legacymigrationversion",
]);
const normalizeKey = (key: string): string =>
  key.replace(/[^a-z0-9]/gi, "").toLowerCase();

const fail = (
  field: string,
  reason: ValidationReason = "VALIDATION_FAILED",
): never => {
  throw new PreferenceValidationError(reason, field);
};

const requireRecord = (value: unknown, field: string): JsonRecord => {
  if (typeof value !== "object" || value === null || Array.isArray(value)) fail(field);
  return value as JsonRecord;
};

const requirePostgresString = (value: unknown, field: string): string => {
  if (typeof value !== "string") fail(field);
  for (let index = 0; index < value.length; index += 1) {
    const codeUnit = value.charCodeAt(index);
    if (codeUnit === 0) fail(field);
    if (codeUnit >= 0xd800 && codeUnit <= 0xdbff) {
      const next = value.charCodeAt(index + 1);
      if (!(next >= 0xdc00 && next <= 0xdfff)) fail(field);
      index += 1;
    } else if (codeUnit >= 0xdc00 && codeUnit <= 0xdfff) {
      fail(field);
    }
  }
  return value;
};

function requirePostgresTextTree(value: unknown, field: string): void {
  if (typeof value === "string") {
    requirePostgresString(value, field);
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((child, index) => requirePostgresTextTree(child, field + "[" + index + "]"));
    return;
  }
  if (typeof value !== "object" || value === null) return;
  Object.entries(value as JsonRecord).forEach(([key, child]) => {
    requirePostgresString(key, field + ".<key>");
    requirePostgresTextTree(child, field + "." + key);
  });
}

const requireExactRecord = (
  value: unknown,
  keys: readonly string[],
  field: string,
): JsonRecord => {
  const record = requireRecord(value, field);
  requirePostgresTextTree(record, field);
  const allowed = new Set(keys);
  for (const key of Object.keys(record)) {
    if (!allowed.has(key)) fail(field + "." + key);
  }
  for (const key of keys) {
    if (!Object.hasOwn(record, key)) fail(field + "." + key);
  }
  return record;
};

const requireKnownKeys = (
  record: JsonRecord,
  allowed: ReadonlySet<string>,
  field: string,
): void => {
  for (const key of Object.keys(record)) {
    if (!allowed.has(key)) fail(field + "." + key);
  }
};

const requireArray = (value: unknown, field: string): unknown[] => {
  if (!Array.isArray(value)) fail(field);
  return value;
};

interface RawJsonSpan {
  start: number;
  end: number;
}

interface TopLevelJsonScan {
  valueSpans: Map<string, RawJsonSpan>;
  duplicateKeys: Set<string>;
}

const skipJsonWhitespace = (raw: string, from: number): number => {
  let index = from;
  while (index < raw.length && /[\u0009\u000a\u000d\u0020]/.test(raw[index])) index += 1;
  return index;
};

function scanJsonString(raw: string, start: number): number {
  if (raw[start] !== '"') fail("rawJson.string");
  let index = start + 1;
  while (index < raw.length) {
    const character = raw[index];
    if (character === '"') return index + 1;
    if (character === "\\") {
      index += 2;
    } else {
      index += 1;
    }
  }
  fail("rawJson.unterminatedString");
}

function scanJsonValue(raw: string, from: number, depth = 0): number {
  if (depth > 256) fail("rawJson.depth");
  let index = skipJsonWhitespace(raw, from);
  if (raw[index] === '"') return scanJsonString(raw, index);
  if (raw[index] === "[") {
    index = skipJsonWhitespace(raw, index + 1);
    if (raw[index] === "]") return index + 1;
    while (index < raw.length) {
      index = skipJsonWhitespace(raw, scanJsonValue(raw, index, depth + 1));
      if (raw[index] === "]") return index + 1;
      if (raw[index] !== ",") fail("rawJson.arrayDelimiter");
      index = skipJsonWhitespace(raw, index + 1);
    }
    fail("rawJson.unterminatedArray");
  }
  if (raw[index] === "{") {
    index = skipJsonWhitespace(raw, index + 1);
    if (raw[index] === "}") return index + 1;
    while (index < raw.length) {
      const keyEnd = scanJsonString(raw, index);
      index = skipJsonWhitespace(raw, keyEnd);
      if (raw[index] !== ":") fail("rawJson.objectColon");
      index = skipJsonWhitespace(raw, scanJsonValue(raw, index + 1, depth + 1));
      if (raw[index] === "}") return index + 1;
      if (raw[index] !== ",") fail("rawJson.objectDelimiter");
      index = skipJsonWhitespace(raw, index + 1);
    }
    fail("rawJson.unterminatedObject");
  }
  const tokenStart = index;
  while (
    index < raw.length &&
    !/[\u0009\u000a\u000d\u0020,\]}]/.test(raw[index])
  ) {
    index += 1;
  }
  if (index === tokenStart) fail("rawJson.value");
  return index;
}

function scanTopLevelJsonObject(raw: string): TopLevelJsonScan {
  let index = skipJsonWhitespace(raw, 0);
  if (raw[index] !== "{") fail("body");
  index = skipJsonWhitespace(raw, index + 1);
  const valueSpans = new Map<string, RawJsonSpan>();
  const duplicateKeys = new Set<string>();
  if (raw[index] === "}") {
    index = skipJsonWhitespace(raw, index + 1);
    if (index !== raw.length) fail("rawJson.trailingData");
    return { valueSpans, duplicateKeys };
  }
  while (index < raw.length) {
    const keyStart = index;
    const keyEnd = scanJsonString(raw, keyStart);
    const key = JSON.parse(raw.slice(keyStart, keyEnd)) as string;
    index = skipJsonWhitespace(raw, keyEnd);
    if (raw[index] !== ":") fail("rawJson.objectColon");
    const valueStart = skipJsonWhitespace(raw, index + 1);
    const valueEnd = scanJsonValue(raw, valueStart);
    if (valueSpans.has(key)) duplicateKeys.add(key);
    else valueSpans.set(key, { start: valueStart, end: valueEnd });
    index = skipJsonWhitespace(raw, valueEnd);
    if (raw[index] === "}") {
      index = skipJsonWhitespace(raw, index + 1);
      if (index !== raw.length) fail("rawJson.trailingData");
      return { valueSpans, duplicateKeys };
    }
    if (raw[index] !== ",") fail("rawJson.objectDelimiter");
    index = skipJsonWhitespace(raw, index + 1);
  }
  fail("rawJson.unterminatedObject");
}

function scanJsonArrayElementSpans(raw: string, arraySpan: RawJsonSpan): RawJsonSpan[] {
  let index = skipJsonWhitespace(raw, arraySpan.start);
  if (raw[index] !== "[") fail("body.profilePreferenceSections");
  index = skipJsonWhitespace(raw, index + 1);
  const spans: RawJsonSpan[] = [];
  if (raw[index] === "]") {
    if (index + 1 !== arraySpan.end) fail("body.profilePreferenceSections.span");
    return spans;
  }
  while (index < arraySpan.end) {
    const start = index;
    const end = scanJsonValue(raw, start);
    spans.push({ start, end });
    index = skipJsonWhitespace(raw, end);
    if (raw[index] === "]") {
      if (index + 1 !== arraySpan.end) fail("body.profilePreferenceSections.span");
      return spans;
    }
    if (raw[index] !== ",") fail("body.profilePreferenceSections.delimiter");
    index = skipJsonWhitespace(raw, index + 1);
  }
  fail("body.profilePreferenceSections.span");
}

const sameJsonValue = (left: unknown, right: unknown): boolean =>
  JSON.stringify(left) === JSON.stringify(right);

const requireBoolean = (value: unknown, field: string): boolean => {
  if (typeof value !== "boolean") fail(field);
  return value;
};

const requireFloat32 = (
  value: unknown,
  field: string,
  predicate: (number: number) => boolean = () => true,
): number => {
  if (typeof value !== "number" || !Number.isFinite(value) || !predicate(value)) fail(field);
  const narrowed = Math.fround(value);
  if (!Number.isFinite(narrowed) || (value !== 0 && narrowed === 0) || !predicate(narrowed)) {
    fail(field);
  }
  return narrowed;
};

const requireInt32 = (
  value: unknown,
  field: string,
  predicate: (number: number) => boolean = () => true,
): number => {
  if (
    typeof value !== "number" ||
    !Number.isInteger(value) ||
    value < INT32_MIN ||
    value > INT32_MAX ||
    !predicate(value)
  ) fail(field);
  return value;
};

const requireSafeJsonLong = (
  value: unknown,
  field: string,
  predicate: (number: number) => boolean = () => true,
): number => {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || !predicate(value)) fail(field);
  return value;
};

const requireNonBlank = (value: unknown, field: string): string => {
  const text = requirePostgresString(value, field);
  if (text.trim().length === 0) fail(field);
  return text;
};

const requireEnum = <T extends string>(
  value: unknown,
  allowed: readonly T[],
  field: string,
): T => {
  const text = requirePostgresString(value, field);
  if (!allowed.includes(text as T)) fail(field);
  return text as T;
};

const requireVersionOne = (value: unknown, field: string): 1 => {
  const version = requireInt32(value, field);
  if (version !== 1) fail(field, "UNSUPPORTED_DOCUMENT_VERSION");
  return 1;
};

const RFC3339_INSTANT =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|([+-])(\d{2}):(\d{2}))$/;

const isLeapYear = (year: number): boolean =>
  year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);

const daysInMonth = (year: number, month: number): number =>
  [31, isLeapYear(year) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1] ?? 0;

const requireRfc3339Instant = (value: unknown, field: string): string => {
  const text = requirePostgresString(value, field);
  const match = RFC3339_INSTANT.exec(text);
  if (!match) fail(field);
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6]);
  const offsetHour = match[8] === "Z" ? 0 : Number(match[10]);
  const offsetMinute = match[8] === "Z" ? 0 : Number(match[11]);
  if (
    year < 1 ||
    month < 1 || month > 12 ||
    day < 1 || day > daysInMonth(year, month) ||
    hour > 23 || minute > 59 || second > 59 ||
    offsetHour > 23 || offsetMinute > 59
  ) fail(field);
  const epoch = Date.parse(text);
  if (!Number.isFinite(epoch)) fail(field);
  const normalized = new Date(epoch);
  return normalized.toISOString();
};

const rejectLocalOnlyKeys = (value: unknown, field = "profilePreferenceSections"): void => {
  if (Array.isArray(value)) {
    value.forEach((child, index) => rejectLocalOnlyKeys(child, field + "[" + index + "]"));
    return;
  }
  if (typeof value !== "object" || value === null) return;
  for (const [key, child] of Object.entries(value as JsonRecord)) {
    if (LOCAL_ONLY_KEYS.has(normalizeKey(key))) fail(field + "." + key);
    rejectLocalOnlyKeys(child, field + "." + key);
  }
};

const RACK_ITEM_KEYS = [
  "id",
  "name",
  "category",
  "weightKg",
  "behavior",
  "enabled",
  "sortOrder",
  "createdAt",
  "updatedAt",
] as const;
const RACK_CATEGORIES = [
  "WEIGHTED_VEST",
  "DIP_BELT",
  "CHAINS",
  "BAND",
  "ASSISTANCE",
  "ATTACHMENT",
  "OTHER",
] as const;
const RACK_BEHAVIORS = [
  "ADDED_RESISTANCE",
  "COUNTERWEIGHT",
  "DISPLAY_ONLY",
] as const;
const WORKOUT_MODES = [0, 2, 3, 4, 6, 10] as const;
const REP_COUNT_TIMINGS = ["TOP", "BOTTOM"] as const;

function validateCorePayload(value: unknown): JsonRecord {
  const payload = requireExactRecord(
    value,
    ["bodyWeightKg", "weightUnit", "weightIncrement"],
    "payload",
  );
  requireFloat32(
    payload.bodyWeightKg,
    "payload.bodyWeightKg",
    (number) => number === 0 || (number >= 20 && number <= 300),
  );
  requireEnum(payload.weightUnit, ["KG", "LB"] as const, "payload.weightUnit");
  requireFloat32(
    payload.weightIncrement,
    "payload.weightIncrement",
    (number) => number === -1 || number > 0,
  );
  return payload;
}

function validateRackPayload(value: unknown): JsonRecord {
  const payload = requireExactRecord(value, ["version", "items"], "payload");
  requireVersionOne(payload.version, "payload.version");
  const ids = new Set<string>();
  requireArray(payload.items, "payload.items").forEach((rawItem, index) => {
    const field = "payload.items[" + index + "]";
    const item = requireExactRecord(rawItem, RACK_ITEM_KEYS, field);
    const id = requireNonBlank(item.id, field + ".id");
    requireNonBlank(item.name, field + ".name");
    if (ids.has(id)) fail(field + ".id");
    ids.add(id);
    requireEnum(item.category, RACK_CATEGORIES, field + ".category");
    requireFloat32(item.weightKg, field + ".weightKg", (number) => number >= 0);
    requireEnum(item.behavior, RACK_BEHAVIORS, field + ".behavior");
    requireBoolean(item.enabled, field + ".enabled");
    requireInt32(item.sortOrder, field + ".sortOrder");
    requireSafeJsonLong(item.createdAt, field + ".createdAt");
    requireSafeJsonLong(item.updatedAt, field + ".updatedAt");
  });
  return payload;
}

const JUST_LIFT_KEYS = [
  "workoutModeId",
  "weightPerCableKg",
  "weightChangePerRep",
  "eccentricLoadPercentage",
  "echoLevelValue",
  "stallDetectionEnabled",
  "repCountTimingName",
  "restSeconds",
] as const;

const SINGLE_EXERCISE_KEYS = [
  "exerciseId",
  "setReps",
  "weightPerCableKg",
  "setWeightsPerCableKg",
  "progressionKg",
  "setRestSeconds",
  "workoutModeId",
  "eccentricLoadPercentage",
  "echoLevelValue",
  "duration",
  "isAMRAP",
  "perSetRestTime",
  "defaultRackItemIds",
] as const;

const WORKOUT_KEYS = [
  "version",
  "stopAtTop",
  "beepsEnabled",
  "stallDetectionEnabled",
  "audioRepCountEnabled",
  "repCountTiming",
  "summaryCountdownSeconds",
  "autoStartCountdownSeconds",
  "gamificationEnabled",
  "autoStartRoutine",
  "countdownBeepsEnabled",
  "repSoundEnabled",
  "motionStartEnabled",
  "weightSuggestionsEnabled",
  "defaultRoutineExerciseUsePercentOfPR",
  "defaultRoutineExerciseWeightPercentOfPR",
  "voiceStopEnabled",
  "justLiftDefaults",
  "singleExerciseDefaults",
] as const;

function validateJustLiftDefaults(value: unknown, field: string): void {
  const defaults = requireExactRecord(value, JUST_LIFT_KEYS, field);
  requireInt32(
    defaults.workoutModeId,
    field + ".workoutModeId",
    (number) => WORKOUT_MODES.includes(number as typeof WORKOUT_MODES[number]),
  );
  requireFloat32(defaults.weightPerCableKg, field + ".weightPerCableKg", (number) => number >= 0);
  requireFloat32(defaults.weightChangePerRep, field + ".weightChangePerRep");
  requireInt32(
    defaults.eccentricLoadPercentage,
    field + ".eccentricLoadPercentage",
    (number) => number >= 0 && number <= 150,
  );
  requireInt32(
    defaults.echoLevelValue,
    field + ".echoLevelValue",
    (number) => number >= 0 && number <= 3,
  );
  requireBoolean(defaults.stallDetectionEnabled, field + ".stallDetectionEnabled");
  requireEnum(defaults.repCountTimingName, REP_COUNT_TIMINGS, field + ".repCountTimingName");
  requireInt32(
    defaults.restSeconds,
    field + ".restSeconds",
    (number) => number === 0 || (number >= 5 && number <= 300),
  );
}

function validateSingleExerciseDefaults(
  mapKey: string,
  value: unknown,
  field: string,
): void {
  const defaults = requireExactRecord(value, SINGLE_EXERCISE_KEYS, field);
  const exerciseId = requireNonBlank(defaults.exerciseId, field + ".exerciseId");
  if (mapKey.trim().length === 0 || exerciseId !== mapKey) fail(field + ".exerciseId");
  requireArray(defaults.setReps, field + ".setReps").forEach((rep, index) => {
    if (rep !== null) {
      requireInt32(rep, field + ".setReps[" + index + "]", (number) => number >= 0);
    }
  });
  requireFloat32(defaults.weightPerCableKg, field + ".weightPerCableKg", (number) => number >= 0);
  requireArray(defaults.setWeightsPerCableKg, field + ".setWeightsPerCableKg")
    .forEach((weight, index) => requireFloat32(
      weight,
      field + ".setWeightsPerCableKg[" + index + "]",
      (number) => number >= 0,
    ));
  requireFloat32(defaults.progressionKg, field + ".progressionKg");
  requireArray(defaults.setRestSeconds, field + ".setRestSeconds")
    .forEach((rest, index) => requireInt32(
      rest,
      field + ".setRestSeconds[" + index + "]",
      (number) => number === 0 || (number >= 5 && number <= 300),
    ));
  requireInt32(
    defaults.workoutModeId,
    field + ".workoutModeId",
    (number) => WORKOUT_MODES.includes(number as typeof WORKOUT_MODES[number]),
  );
  requireInt32(
    defaults.eccentricLoadPercentage,
    field + ".eccentricLoadPercentage",
    (number) => number >= 0 && number <= 150,
  );
  requireInt32(
    defaults.echoLevelValue,
    field + ".echoLevelValue",
    (number) => number >= 0 && number <= 3,
  );
  requireInt32(defaults.duration, field + ".duration", (number) => number >= 0);
  requireBoolean(defaults.isAMRAP, field + ".isAMRAP");
  requireBoolean(defaults.perSetRestTime, field + ".perSetRestTime");
  const rackIds = requireArray(defaults.defaultRackItemIds, field + ".defaultRackItemIds")
    .map((rackId, index) => requireNonBlank(
      rackId,
      field + ".defaultRackItemIds[" + index + "]",
    ));
  if (new Set(rackIds).size !== rackIds.length) fail(field + ".defaultRackItemIds");
}

function validateWorkoutPayload(value: unknown): JsonRecord {
  const payload = requireExactRecord(value, WORKOUT_KEYS, "payload");
  requireVersionOne(payload.version, "payload.version");
  [
    "stopAtTop",
    "beepsEnabled",
    "stallDetectionEnabled",
    "audioRepCountEnabled",
    "gamificationEnabled",
    "autoStartRoutine",
    "countdownBeepsEnabled",
    "repSoundEnabled",
    "motionStartEnabled",
    "weightSuggestionsEnabled",
    "defaultRoutineExerciseUsePercentOfPR",
    "voiceStopEnabled",
  ].forEach((key) => requireBoolean(payload[key], "payload." + key));
  requireEnum(payload.repCountTiming, REP_COUNT_TIMINGS, "payload.repCountTiming");
  requireInt32(
    payload.summaryCountdownSeconds,
    "payload.summaryCountdownSeconds",
    (number) => [-1, 0, 5, 10, 15, 20, 25, 30].includes(number),
  );
  requireInt32(
    payload.autoStartCountdownSeconds,
    "payload.autoStartCountdownSeconds",
    (number) => number >= 2 && number <= 10,
  );
  requireInt32(
    payload.defaultRoutineExerciseWeightPercentOfPR,
    "payload.defaultRoutineExerciseWeightPercentOfPR",
    (number) => number >= 50 && number <= 120,
  );
  validateJustLiftDefaults(payload.justLiftDefaults, "payload.justLiftDefaults");
  const singleExerciseDefaults = requireRecord(
    payload.singleExerciseDefaults,
    "payload.singleExerciseDefaults",
  );
  Object.entries(singleExerciseDefaults).forEach(([key, defaults]) =>
    validateSingleExerciseDefaults(
      key,
      defaults,
      "payload.singleExerciseDefaults." + key,
    )
  );
  return payload;
}

function validateLedPayload(value: unknown): JsonRecord {
  const payload = requireExactRecord(
    value,
    ["ledColorSchemeId", "preferences"],
    "payload",
  );
  requireInt32(
    payload.ledColorSchemeId,
    "payload.ledColorSchemeId",
    (number) => number >= 0,
  );
  const preferences = requireExactRecord(
    payload.preferences,
    ["version", "discoModeUnlocked"],
    "payload.preferences",
  );
  requireVersionOne(preferences.version, "payload.preferences.version");
  requireBoolean(preferences.discoModeUnlocked, "payload.preferences.discoModeUnlocked");
  return payload;
}

function validateVbtPayload(value: unknown): JsonRecord {
  const payload = requireExactRecord(value, ["vbtEnabled", "preferences"], "payload");
  requireBoolean(payload.vbtEnabled, "payload.vbtEnabled");
  const preferences = requireExactRecord(
    payload.preferences,
    [
      "version",
      "velocityLossThresholdPercent",
      "autoEndOnVelocityLoss",
      "defaultScalingBasis",
      "verbalEncouragementEnabled",
      "vulgarModeEnabled",
      "vulgarTier",
      "dominatrixModeUnlocked",
      "dominatrixModeActive",
    ],
    "payload.preferences",
  );
  requireVersionOne(preferences.version, "payload.preferences.version");
  requireInt32(
    preferences.velocityLossThresholdPercent,
    "payload.preferences.velocityLossThresholdPercent",
    (number) => number >= 10 && number <= 50,
  );
  requireBoolean(preferences.autoEndOnVelocityLoss, "payload.preferences.autoEndOnVelocityLoss");
  requireEnum(
    preferences.defaultScalingBasis,
    ["MAX_WEIGHT_PR", "MAX_VOLUME_PR", "ESTIMATED_1RM"] as const,
    "payload.preferences.defaultScalingBasis",
  );
  requireBoolean(
    preferences.verbalEncouragementEnabled,
    "payload.preferences.verbalEncouragementEnabled",
  );
  requireBoolean(preferences.vulgarModeEnabled, "payload.preferences.vulgarModeEnabled");
  requireEnum(
    preferences.vulgarTier,
    ["MILD", "STRONG", "MIX"] as const,
    "payload.preferences.vulgarTier",
  );
  requireBoolean(
    preferences.dominatrixModeUnlocked,
    "payload.preferences.dominatrixModeUnlocked",
  );
  requireBoolean(
    preferences.dominatrixModeActive,
    "payload.preferences.dominatrixModeActive",
  );
  return payload;
}

function parsePreferenceMutation(value: unknown): PortalProfilePreferenceSectionMutation {
  requirePostgresTextTree(value, "mutation");
  rejectLocalOnlyKeys(value);
  const mutation = requireExactRecord(value, MUTATION_KEYS, "mutation");
  const localProfileId = requireNonBlank(mutation.localProfileId, "mutation.localProfileId");
  if (typeof mutation.section !== "string") fail("mutation.section", "UNSUPPORTED_SECTION");
  if (!["CORE", "RACK", "WORKOUT", "LED", "VBT"].includes(mutation.section)) {
    fail("mutation.section", "UNSUPPORTED_SECTION");
  }
  const section = mutation.section as ProfilePreferenceSection;
  const documentVersion = requireVersionOne(
    mutation.documentVersion,
    "mutation.documentVersion",
  );
  const baseRevision = requireSafeJsonLong(
    mutation.baseRevision,
    "mutation.baseRevision",
    (number) => number >= 0,
  );
  const clientModifiedAt = requireRfc3339Instant(
    mutation.clientModifiedAt,
    "mutation.clientModifiedAt",
  );
  const payload = ({
    CORE: validateCorePayload,
    RACK: validateRackPayload,
    WORKOUT: validateWorkoutPayload,
    LED: validateLedPayload,
    VBT: validateVbtPayload,
  } as const)[section](mutation.payload);
  return {
    localProfileId,
    section,
    documentVersion,
    baseRevision,
    clientModifiedAt,
    payload,
  };
}

interface PreferenceEnvelope {
  present: boolean;
  validatedMutations: PortalProfilePreferenceSectionMutation[];
  rejections: ProfilePreferenceSectionRejection[];
}

interface PreferenceRawContext {
  rawBody: string;
  preferenceElementSpans: RawJsonSpan[];
}

const rawPreferenceIdentity = (value: unknown): string | null => {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
  const record = value as JsonRecord;
  if (typeof record.localProfileId !== "string" || typeof record.section !== "string") {
    return null;
  }
  return JSON.stringify([record.localProfileId, record.section]);
};

const rawPreferenceLabel = (value: unknown): { localProfileId: string; section: string } => {
  const record =
    typeof value === "object" && value !== null && !Array.isArray(value)
      ? value as JsonRecord
      : {};
  return {
    localProfileId: typeof record.localProfileId === "string" ? record.localProfileId : "",
    section: typeof record.section === "string" ? record.section : "UNKNOWN",
  };
};

function parsePreferenceEnvelope(
  body: JsonRecord,
  rawContext: PreferenceRawContext,
): PreferenceEnvelope {
  requireKnownKeys(body, PUSH_BODY_KEYS, "body");
  if (!Object.hasOwn(body, "profilePreferenceSections")) {
    if (rawContext.preferenceElementSpans.length !== 0) {
      fail("body.profilePreferenceSections.span");
    }
    return { present: false, validatedMutations: [], rejections: [] };
  }
  const rawMutations = requireArray(
    body.profilePreferenceSections,
    "body.profilePreferenceSections",
  );
  if (rawMutations.length !== rawContext.preferenceElementSpans.length) {
    fail("body.profilePreferenceSections.span");
  }
  rawMutations.forEach((rawMutation, index) => {
    const span = rawContext.preferenceElementSpans[index];
    let reparsed: unknown;
    try {
      reparsed = JSON.parse(rawContext.rawBody.slice(span.start, span.end));
    } catch {
      fail("body.profilePreferenceSections.span");
    }
    if (!sameJsonValue(reparsed, rawMutation)) {
      fail("body.profilePreferenceSections.span");
    }
  });

  // Raw-span structural/reparse failures above are envelope-level 400 errors. From this point,
  // duplicate, size, and schema/Unicode outcomes are isolated to their section identities.
  const identityCounts = new Map<string, number>();
  rawMutations.forEach((rawMutation) => {
    const identity = rawPreferenceIdentity(rawMutation);
    if (identity !== null) {
      identityCounts.set(identity, (identityCounts.get(identity) ?? 0) + 1);
    }
  });
  const duplicateIdentities = new Set(
    [...identityCounts.entries()]
      .filter(([, count]) => count > 1)
      .map(([identity]) => identity),
  );

  const validatedMutations: PortalProfilePreferenceSectionMutation[] = [];
  const rejections: ProfilePreferenceSectionRejection[] = [];
  const duplicateReported = new Set<string>();
  rawMutations.forEach((rawMutation, index) => {
    const label = rawPreferenceLabel(rawMutation);
    const identity = rawPreferenceIdentity(rawMutation);
    if (identity !== null && duplicateIdentities.has(identity)) {
      if (!duplicateReported.has(identity)) {
        duplicateReported.add(identity);
        rejections.push({
          ...label,
          serverRevision: 0,
          reason: "DUPLICATE_SECTION",
        });
      }
      return;
    }
    const span = rawContext.preferenceElementSpans[index];
    const rawPreferenceElementBytes = utf8Bytes(
      rawContext.rawBody.slice(span.start, span.end),
    );
    if (rawPreferenceElementBytes > MAX_PROFILE_PREFERENCE_SECTION_BYTES) {
      rejections.push({
        ...label,
        serverRevision: 0,
        reason: "SECTION_TOO_LARGE",
      });
      return;
    }
    try {
      const mutation = parsePreferenceMutation(rawMutation);
      validatedMutations.push(mutation);
    } catch (error) {
      if (!(error instanceof PreferenceValidationError)) throw error;
      rejections.push({
        ...label,
        serverRevision: 0,
        reason: error.reason,
      });
    }
  });
  return { present: true, validatedMutations, rejections };
}
```

Authenticate with an anon client, parse and validate the complete body, and only then construct or call the privileged client. `validateExistingMobileSyncPushBody` below means the existing strict, side-effect-free validator for every non-preference field in `PUSH_BODY_KEYS`; it must neither recurse into nor revalidate `profilePreferenceSections`, because `parsePreferenceMutation` owns that field's per-section Unicode/schema rejection boundary. Wire the ordinary validator to the endpoint's real parser and add a regression proving a malformed final ordinary or preference item causes zero admin table/RPC calls:

```typescript
const authorization = req.headers.get("Authorization");
const bearerMatch = authorization === null
  ? null
  : /^Bearer ([^\s]+)$/.exec(authorization);
if (!bearerMatch) {
  return new Response(JSON.stringify({ error: "Missing bearer token" }), { status: 401 });
}
const userJwt = bearerMatch[1];
const authClient = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
  global: { headers: { Authorization: authorization } },
  auth: { persistSession: false, autoRefreshToken: false },
});

const safeErrorName = (error: unknown, fallback: string): string => {
  let candidate = fallback;
  if (error instanceof Error) {
    candidate = error.name;
  } else if (
    typeof error === "object" && error !== null &&
    typeof (error as JsonRecord).name === "string"
  ) {
    candidate = (error as JsonRecord).name as string;
  }
  return /^[A-Za-z][A-Za-z0-9_.-]{0,63}$/.test(candidate) ? candidate : fallback;
};

const authOperationalFailure = (error: unknown): Response => {
  console.error({ name: safeErrorName(error, "AuthOperationalFailure") });
  return new Response(
    JSON.stringify({ error: "Authentication service unavailable" }),
    { status: 503 },
  );
};

const returnedAuthStatus = (error: unknown): number | null => {
  if (typeof error !== "object" || error === null) return null;
  const record = error as JsonRecord;
  const status = record.status ?? record.statusCode;
  return typeof status === "number" && Number.isInteger(status) ? status : null;
};

let authResult: unknown;
try {
  authResult = await authClient.auth.getUser(userJwt);
} catch (error) {
  return authOperationalFailure(error);
}
if (typeof authResult !== "object" || authResult === null || Array.isArray(authResult)) {
  return authOperationalFailure({ name: "AuthUnexpectedResult" });
}
const authRecord = authResult as JsonRecord;
if (!Object.hasOwn(authRecord, "error") || !Object.hasOwn(authRecord, "data")) {
  return authOperationalFailure({ name: "AuthUnexpectedResult" });
}
const userError = authRecord.error;
if (userError !== null) {
  const status = returnedAuthStatus(userError);
  if (status === 400 || status === 401 || status === 403) {
    return new Response(JSON.stringify({ error: "Invalid bearer token" }), { status: 401 });
  }
  return authOperationalFailure(userError);
}
const userData = authRecord.data;
if (typeof userData !== "object" || userData === null || Array.isArray(userData)) {
  return authOperationalFailure({ name: "AuthUnexpectedResult" });
}
const verifiedUser = (userData as JsonRecord).user;
if (
  typeof verifiedUser !== "object" || verifiedUser === null || Array.isArray(verifiedUser) ||
  typeof (verifiedUser as JsonRecord).id !== "string" ||
  ((verifiedUser as JsonRecord).id as string).length === 0
) {
  return authOperationalFailure({ name: "AuthUnexpectedResult" });
}
const verifiedUserId = (verifiedUser as JsonRecord).id as string;

let originalBodyBytes: Uint8Array;
try {
  originalBodyBytes = new Uint8Array(await req.arrayBuffer());
} catch (error) {
  console.error({ name: safeErrorName(error, "RequestBodyReadFailure") });
  return new Response(JSON.stringify({ error: "Request unavailable" }), { status: 503 });
}
const rawBodyBytes = originalBodyBytes.byteLength;
if (rawBodyBytes > MAX_MOBILE_SYNC_REQUEST_BYTES) {
  return new Response(JSON.stringify({ error: "Request too large" }), { status: 413 });
}
const hasLeadingUtf8Bom =
  originalBodyBytes.length >= 3 &&
  originalBodyBytes[0] === 0xef &&
  originalBodyBytes[1] === 0xbb &&
  originalBodyBytes[2] === 0xbf;
if (hasLeadingUtf8Bom) {
  return new Response(JSON.stringify({ error: "Invalid sync request" }), { status: 400 });
}
let rawBody: string;
try {
  rawBody = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true })
    .decode(originalBodyBytes);
} catch {
  return new Response(JSON.stringify({ error: "Invalid sync request" }), { status: 400 });
}
if (rawBody.startsWith("\uFEFF")) {
  return new Response(JSON.stringify({ error: "Invalid sync request" }), { status: 400 });
}

let topLevelScan: TopLevelJsonScan;
try {
  topLevelScan = scanTopLevelJsonObject(rawBody);
  for (const duplicateKey of topLevelScan.duplicateKeys) {
    if (PUSH_BODY_KEYS.has(duplicateKey)) fail("body." + duplicateKey);
  }
} catch (error) {
  if (!(error instanceof PreferenceValidationError) && !(error instanceof SyntaxError)) {
    throw error;
  }
  return new Response(JSON.stringify({ error: "Invalid sync request" }), { status: 400 });
}
const preferenceValueSpan = topLevelScan.valueSpans.get("profilePreferenceSections");
if (
  preferenceValueSpan !== undefined &&
  rawBodyBytes > MAX_PROFILE_PREFERENCE_REQUEST_BYTES
) {
  return new Response(JSON.stringify({ error: "Request too large" }), { status: 413 });
}

let body: JsonRecord;
let preferenceEnvelope: PreferenceEnvelope;
try {
  const preferenceElementSpans = preferenceValueSpan === undefined
    ? []
    : scanJsonArrayElementSpans(rawBody, preferenceValueSpan);
  const parsedBody = JSON.parse(rawBody) as unknown;
  body = requireRecord(parsedBody, "body");
  preferenceEnvelope = parsePreferenceEnvelope(body, {
    rawBody,
    preferenceElementSpans,
  });
  validateExistingMobileSyncPushBody(body);
} catch (error) {
  if (!(error instanceof PreferenceValidationError) && !(error instanceof SyntaxError)) {
    throw error;
  }
  return new Response(JSON.stringify({ error: "Invalid sync request" }), { status: 400 });
}

const admin = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
  auth: { persistSession: false, autoRefreshToken: false },
});
```

The 9,500,000-byte cap counts `originalBodyBytes.byteLength` only when `profilePreferenceSections` is absent. Whenever that top-level field is present, the 524,288-byte cap applies to that same complete original byte sequence, including all whitespace, ordinary fields, and preference fields. Reject oversize requests from the original count, never from a decoded/re-encoded surrogate. The 262,144-byte cap applies to each exact raw JSON array-element span measured by the quote-, escape-, and nesting-aware scanner. Only after one fatal valid-UTF-8 decode and explicit no-BOM check is `TextEncoder.encode(rawBody.slice(span.start, span.end))` byte-identical to the corresponding original octets: valid UTF-8 has a unique encoding and JSON structural offsets fall on scalar boundaries. It is never reconstructed with `JSON.stringify`. Reject malformed UTF-8, a leading BOM/U+FEFF, a duplicate relevant top-level key, element-count mismatch, or reparsed-span/value mismatch before constructing the admin client. Exact-size payloads are accepted; 262,145 and 524,289 bytes are rejected. `clientModifiedAt` is strict timezone-bearing RFC3339 audit metadata normalized by the shared instant helper and is never used for ordering.

Treat only a successfully parsed RPC row as a domain result. Use the exact runtime parser and loop below so empty/multiple/malformed rows, unknown rejection reasons, permission failures, and PostgREST errors are infrastructure failures rather than fabricated `VALIDATION_FAILED` results:

```typescript
interface RpcMutationRow {
  accepted: boolean;
  rejection_reason: string | null;
  server_revision: number | string;
  canonical_section: unknown | null;
}

const RPC_DOMAIN_REASONS = new Set([
  "REVISION_CONFLICT",
  "VALIDATION_FAILED",
  "UNSUPPORTED_SECTION",
  "UNSUPPORTED_DOCUMENT_VERSION",
  "UNKNOWN_PROFILE",
]);

const infrastructureRevision = (value: unknown): number => {
  const number = typeof value === "string" && /^[0-9]+$/.test(value)
    ? Number(value)
    : value;
  if (typeof number !== "number" || !Number.isSafeInteger(number) || number < 0) {
    throw new PreferenceInfrastructureError("malformed revision");
  }
  return number;
};

function parseInfrastructureCanonical(
  value: unknown,
  mutation: PortalProfilePreferenceSectionMutation,
): PortalProfilePreferenceSectionCanonical {
  try {
    requirePostgresTextTree(value, "canonical");
    const canonical = requireExactRecord(
      value,
      [
        "localProfileId",
        "section",
        "documentVersion",
        "serverRevision",
        "serverUpdatedAt",
        "payload",
      ],
      "canonical",
    );
    if (canonical.localProfileId !== mutation.localProfileId) fail("canonical.localProfileId");
    if (canonical.section !== mutation.section) fail("canonical.section");
    requireVersionOne(canonical.documentVersion, "canonical.documentVersion");
    const serverRevision = infrastructureRevision(canonical.serverRevision);
    const serverUpdatedAt = requireRfc3339Instant(
      canonical.serverUpdatedAt,
      "canonical.serverUpdatedAt",
    );
    const payload = ({
      CORE: validateCorePayload,
      RACK: validateRackPayload,
      WORKOUT: validateWorkoutPayload,
      LED: validateLedPayload,
      VBT: validateVbtPayload,
    } as const)[mutation.section](canonical.payload);
    return {
      localProfileId: mutation.localProfileId,
      section: mutation.section,
      documentVersion: 1,
      serverRevision,
      serverUpdatedAt,
      payload,
    };
  } catch (error) {
    if (error instanceof PreferenceInfrastructureError) throw error;
    throw new PreferenceInfrastructureError("malformed canonical");
  }
}

function parseRpcMutationRow(
  data: unknown,
  mutation: PortalProfilePreferenceSectionMutation,
): {
  accepted: boolean;
  rejectionReason: string | null;
  serverRevision: number;
  canonicalSection?: PortalProfilePreferenceSectionCanonical;
} {
  if (!Array.isArray(data) || data.length !== 1) {
    throw new PreferenceInfrastructureError("RPC row cardinality");
  }
  try {
    const row = requireExactRecord(
      data[0],
      ["accepted", "rejection_reason", "server_revision", "canonical_section"],
      "rpcRow",
    ) as unknown as RpcMutationRow;
    if (typeof row.accepted !== "boolean") fail("rpcRow.accepted");
    const serverRevision = infrastructureRevision(row.server_revision);
    const canonicalSection = row.canonical_section === null
      ? undefined
      : parseInfrastructureCanonical(row.canonical_section, mutation);
    if (row.accepted) {
      if (row.rejection_reason !== null || !canonicalSection) fail("rpcRow");
    } else {
      if (
        typeof row.rejection_reason !== "string" ||
        !RPC_DOMAIN_REASONS.has(row.rejection_reason)
      ) {
        fail("rpcRow.rejection_reason");
      }
      if (row.rejection_reason === "REVISION_CONFLICT" && !canonicalSection) {
        fail("rpcRow.canonical_section");
      }
    }
    if (canonicalSection && canonicalSection.serverRevision !== serverRevision) fail("rpcRow");
    return {
      accepted: row.accepted,
      rejectionReason: row.rejection_reason,
      serverRevision,
      canonicalSection,
    };
  } catch (error) {
    if (error instanceof PreferenceInfrastructureError) throw error;
    throw new PreferenceInfrastructureError("malformed RPC row");
  }
}

const canonicalProfilePreferenceSections: PortalProfilePreferenceSectionCanonical[] = [];
const profilePreferenceRejections: ProfilePreferenceSectionRejection[] = [
  ...preferenceEnvelope.rejections,
];

try {
  for (const mutation of preferenceEnvelope.validatedMutations) {
    const { data, error } = await admin.rpc("mutate_local_profile_preference_section", {
      p_user_id: verifiedUserId,
      p_local_profile_id: mutation.localProfileId,
      p_section: mutation.section,
      p_document_version: mutation.documentVersion,
      p_base_revision: mutation.baseRevision,
      p_payload: mutation.payload,
    });
    if (error) throw new PreferenceInfrastructureError("mutation RPC");
    const result = parseRpcMutationRow(data, mutation);
    if (result.accepted) {
      canonicalProfilePreferenceSections.push(result.canonicalSection!);
    } else {
      profilePreferenceRejections.push({
        localProfileId: mutation.localProfileId,
        section: mutation.section,
        serverRevision: result.serverRevision,
        reason: result.rejectionReason as ProfilePreferenceSectionRejection["reason"],
        ...(result.canonicalSection
          ? { canonicalSection: result.canonicalSection }
          : {}),
      });
    }
  }
} catch (error) {
  console.error({ name: safeErrorName(error, "PreferenceInfrastructureFailure") });
  return new Response(JSON.stringify({ error: "Sync temporarily unavailable" }), {
    status: 503,
  });
}

const preferenceResponseAdditions = {
  ...(preferenceEnvelope.present ? { profilePreferencesAccepted: true } : {}),
  canonicalProfilePreferenceSections,
  profilePreferenceRejections,
};
```

The document must state these response and pull rules in executable terms:

- `profilePreferencesAccepted` is emitted as `true` only when `profilePreferenceSections` was present, envelope-validated, and evaluated. It is omitted for legacy callers.
- Every accepted RPC row contributes its canonical object; every well-formed domain rejection contributes its reason and canonical object when supplied. Local validation rejections coexist with valid siblings.
- Any unexpected RPC/query/permission/transport error aborts the HTTP response with a sanitized 5xx. It is never converted to `VALIDATION_FAILED`, and no payload, JWT, service-role secret, profile id, PostgREST message, or raw error message is logged.
- Push and pull merge these additions into the existing response without removing or changing the required `syncTime` field.
- Keep `verify_jwt = true` for both functions in `supabase/config.toml`.
- The Edge function performs no network call while a database row lock is held; one RPC invocation owns one complete Postgres transaction.
- Same-section concurrent first writes produce exactly one revision-1 acceptance and one canonical revision conflict. Different-section concurrent first writes against the same profile each produce revision 1 and preserve the sibling section.

Lost acknowledgement is an expected convergence path, not an idempotency-key feature. If mutation A commits and a later sibling B experiences an infrastructure failure, the endpoint returns 5xx and acknowledges neither. Mobile retains both dirty generations. Retrying A with its old `baseRevision` returns `REVISION_CONFLICT` plus A's committed canonical revision without incrementing it a second time; the mobile generation ledger applies that canonical state and converges. B then retries normally. Do not trust `deviceId`, `clientModifiedAt`, or a client-generated idempotency value as mutation ordering.

```toml
[functions.mobile-sync-push]
verify_jwt = true

[functions.mobile-sync-pull]
verify_jwt = true
```

In `mobile-sync-pull/index.ts`, reuse the exact bearer-token/`auth.getUser(userJwt)` classifier above: only returned Auth errors with 400/401/403 become 401; 429/5xx/other or missing status, malformed results, and thrown/rejected calls become name-only-logged generic 503 responses. Derive `verifiedUserId` only from the verified user, and construct the service-role client only after successful authentication and strict pull-request validation. Use an explicit verified-owner predicate, then map the typed columns/documents into the same canonical wrappers returned by the mutation RPC:

```typescript
const canonical = (
  localProfileId: string,
  section: ProfilePreferenceSection,
  serverRevision: number,
  serverUpdatedAt: string,
  payload: JsonRecord,
): PortalProfilePreferenceSectionCanonical => ({
  localProfileId,
  section,
  documentVersion: 1,
  serverRevision,
  serverUpdatedAt,
  payload,
});

const canonicalTimestamp = (value: unknown): string =>
  requireRfc3339Instant(value, "pull.serverUpdatedAt");

async function loadFirstPageProfilePreferences(
  cursor: string | null | undefined,
  requestedProfileId: string | null | undefined,
): Promise<PortalProfilePreferenceSectionCanonical[] | undefined> {
  if (cursor || !requestedProfileId || requestedProfileId.trim().length === 0) {
    return undefined;
  }
  const { data: preferenceRow, error: preferenceError } = await admin
    .from("local_profile_preferences")
    .select(
      "local_profile_id,body_weight_kg,weight_unit,weight_increment," +
        "core_revision,core_updated_at,equipment_rack,rack_revision,rack_updated_at," +
        "workout_preferences,workout_revision,workout_updated_at," +
        "led_color_scheme_id,led_preferences,led_revision,led_updated_at," +
        "vbt_enabled,vbt_preferences,vbt_revision,vbt_updated_at",
    )
    .eq("user_id", verifiedUserId)
    .eq("local_profile_id", requestedProfileId)
    .maybeSingle();
  if (preferenceError) throw new PreferenceInfrastructureError("preference pull");
  if (!preferenceRow) return undefined;

  try {
    const core = validateCorePayload({
      bodyWeightKg: preferenceRow.body_weight_kg,
      weightUnit: preferenceRow.weight_unit,
      weightIncrement: preferenceRow.weight_increment,
    });
    const rack = validateRackPayload(preferenceRow.equipment_rack);
    const workout = validateWorkoutPayload(preferenceRow.workout_preferences);
    const led = validateLedPayload({
      ledColorSchemeId: preferenceRow.led_color_scheme_id,
      preferences: preferenceRow.led_preferences,
    });
    const vbt = validateVbtPayload({
      vbtEnabled: preferenceRow.vbt_enabled,
      preferences: preferenceRow.vbt_preferences,
    });
    return [
      canonical(
        requestedProfileId,
        "CORE",
        infrastructureRevision(preferenceRow.core_revision),
        canonicalTimestamp(preferenceRow.core_updated_at),
        core,
      ),
      canonical(
        requestedProfileId,
        "RACK",
        infrastructureRevision(preferenceRow.rack_revision),
        canonicalTimestamp(preferenceRow.rack_updated_at),
        rack,
      ),
      canonical(
        requestedProfileId,
        "WORKOUT",
        infrastructureRevision(preferenceRow.workout_revision),
        canonicalTimestamp(preferenceRow.workout_updated_at),
        workout,
      ),
      canonical(
        requestedProfileId,
        "LED",
        infrastructureRevision(preferenceRow.led_revision),
        canonicalTimestamp(preferenceRow.led_updated_at),
        led,
      ),
      canonical(
        requestedProfileId,
        "VBT",
        infrastructureRevision(preferenceRow.vbt_revision),
        canonicalTimestamp(preferenceRow.vbt_updated_at),
        vbt,
      ),
    ];
  } catch (error) {
    if (error instanceof PreferenceInfrastructureError) throw error;
    throw new PreferenceInfrastructureError("malformed preference pull row");
  }
}

const profilePreferenceSections = await loadFirstPageProfilePreferences(
  cursor,
  requestedProfileId,
);
```

Only the first page (`!cursor`) for a nonblank requested profile may execute this query. Later pages omit the field but keep normal pagination and `syncTime`. An absent preference row also omits the field and never creates a row. The containing pull handler catches `PreferenceInfrastructureError`, logs only its sanitized error class name, and returns a generic 5xx.

Add this machine-readable manifest exactly; the mobile handoff contract test parses only this fenced block, so prose cannot impersonate test coverage:

```portal-test-manifest
database:exact-function-acls-and-no-client-dml
database:temporary-grant-owner-rls-and-cross-owner-user-id-protection
database:base-revision-accept-and-stale-canonical-conflict
edge:auth-and-cross-user-profile-rejection
edge:auth-rejection-vs-operational-outage-classification
edge:strict-five-section-validation-and-local-only-rejection
edge:kotlin-int32-float32-unicode-and-rfc3339-parity
edge:fatal-utf8-bom-and-original-byte-enforcement
edge:section-262143-262144-262145-byte-boundaries
edge:envelope-524287-524288-524289-byte-boundaries
edge:unexpected-rpc-error-is-sanitized-5xx
edge:same-section-concurrent-first-write
edge:different-section-concurrent-first-write
edge:lost-ack-retry-canonical-convergence
edge:mutation-and-first-page-pull-canonical-equality
edge:later-pull-pages-omit-preferences-and-keep-sync-time
```

Implement the manifest with real database and function tests, not string searches:

- In `supabase/tests/database/profile_preferences.test.sql`, use pgTAP in a transaction. Assert the two exact function identities/signatures, `SECURITY INVOKER`, empty `search_path`, owner, volatility, return shapes, and execute ACLs; assert `PUBLIC`, `anon`, and `authenticated` have neither function execute nor table DML while `service_role` has only the intended table/function privileges.
- In that pgTAP file, temporarily grant table DML inside the test transaction solely to exercise RLS. Set authenticated JWT claims for owner A and owner B, prove each CRUD operation sees/mutates only its own composite parent, and prove owner A cannot update a row's `user_id` to owner B because the `WITH CHECK` predicate fails. Do not claim that RLS makes a same-owner `local_profile_id` immutable; no trigger is part of this plan. Revoke the temporary grants and verify the production ACLs again before rollback.
- Seed two composite profile keys and call the real mutation function for all five sections. Prove base revision 0 accepts revision 1, the next matching base accepts exactly the next revision, a stale base returns `REVISION_CONFLICT` plus byte/equality-identical canonical JSON, and mutating one section leaves all four sibling payloads/revisions unchanged. For every call, prove the targeted row keeps its `user_id` and `local_profile_id`, the non-target key is untouched, and the RPC cannot mutate either key. Exercise explicit `UNSUPPORTED_SECTION`, `UNSUPPORTED_DOCUMENT_VERSION`, `VALIDATION_FAILED`, and `UNKNOWN_PROFILE` rows.
- In `mobile-sync-push/index.test.ts`, invoke the exported handler with injected anon/admin clients and two real local Supabase users. Cover missing headers plus blank, whitespace-bearing, multi-token, and otherwise malformed `Bearer` suffixes as immediate HTTP 401 responses with zero `getUser` or admin calls. Cover returned Auth errors with each of 400, 401, and 403 as HTTP 401. Separately inject returned 429, 500, and 503 errors, an error with no status, a null/array/malformed result, missing `error` or `data` discriminants, a non-null error without status, a success without a user, and thrown/rejected `getUser` calls; each is a generic 503 whose captured console call has exactly one argument equal to an object with exactly the `name` key. Every auth failure constructs/calls zero admin clients. Also cover attempted cross-user profile mutation and the absence of any request-body `userId` authority. Spy on every privileged table/RPC method and prove malformed data in the final ordinary or preference item produces zero privileged calls.
- Table-drive every required and unknown key, primitive type, enum, range, nested object, duplicate rack id, duplicate `(localProfileId, section)`, all five version-1 wrappers, every unsupported wrapper/embedded version, and recursive normalized local-only names. For Kotlin `Int`, prove rack `sortOrder` accepts exactly -2147483648 and 2147483647 and rejects either adjacent overflow; cover workout `setReps`/`duration` and LED color scheme with both Int32 and their narrower business rules. For Kotlin `Float`, prove `Float.MAX_VALUE` and the smallest nonzero Float32 survive where business rules allow, while positive/negative Float32 overflow and nonzero underflow-to-zero are rejected; apply every business predicate to both the original JavaScript number and its `Math.fround` result. In CORE, accept exact `bodyWeightKg` values `20` and `300`, but reject exact adjacent inputs `19.9999999` and `300.00001` even though Float32 rounding produces `20` and `300`. For the nonnegative RACK `weightKg` JSONB field, accept `0` and the smallest positive Float32, but reject exact `-1e-46` rather than allowing its `-0` Float32 result through. Safe JSON integers apply only to Long revisions/timestamps. Recursively test raw and escaped U+0000 plus lone high/low surrogates in nested string values and `singleExerciseDefaults`/other object keys; each is rejected pre-admin, while a valid supplementary pair/emoji passes. Put one such Unicode-invalid unique-key section beside a valid unique-key sibling and assert exactly one `VALIDATION_FAILED` for the invalid key, zero RPC calls for that key, and one successful RPC for the valid sibling. Keep malformed raw-span structure or a raw/parsed element mismatch as an envelope-level HTTP 400 with zero privileged calls. Rack names may repeat, and signed safe-integer `createdAt`/`updatedAt` values are accepted. Pre-count duplicate section identities before size or document validation; assert exactly one `DUPLICATE_SECTION` rejection per duplicated key, zero RPC calls for every occurrence of that key, and one RPC for each valid non-duplicated sibling.
- Table-drive the shared strict instant helper through mutation, RPC canonical, and pull paths. Reject numeric/string `0`, prose dates, date-only/space forms, February 30, invalid leap days/times/offsets, and any missing timezone. Accept valid `Z`, fractional-second, and positive/negative-offset instants and assert canonical output is the exact `toISOString()` normalization.
- Send raw `Uint8Array` bodies through the real handler. Reject a leading UTF-8 BOM, truncated sequences, overlong encodings, and isolated continuation bytes with HTTP 400 and zero admin construction/calls; accept a legitimately encoded U+FFFD scalar. Use the shared byte-golden artifact to build original bodies at exactly 262143, 262144, and 262145 bytes for one preference array element and exactly 524287, 524288, and 524289 bytes for the complete HTTP `PortalSyncPayload`. Assert inclusive limits from the original `Uint8Array.byteLength`, HTTP 413 only for full-request overflow when the preference field is present, per-section `SECTION_TOO_LARGE` for section overflow, exact scanner offsets despite whitespace/escapes/nesting, and that a large ordinary request below 9,500,000 bytes without the preference field is not subjected to the preference cap.
- Inject RPC error, null data, empty array, two rows, malformed canonical, mismatched revision, and unknown rejection reason. Each must return generic 5xx, call the logger with exactly one `{ name: safeErrorName(...) }` object, expose no payload/profile/token/secret/error message, and never emit `VALIDATION_FAILED`. Include a thrown error whose custom `name` is invalid/oversized and prove the fallback name is logged. A well-formed domain rejection continues with valid siblings.
- With real RPC calls and `Promise.all`, prove same-section concurrent base-0 writes yield one revision-1 acceptance and one canonical conflict, while different-section base-0 writes both reach revision 1 and preserve both documents. Assert one returned row per call.
- For lost acknowledgement, commit A directly, force the handler's later B RPC to fail after A has committed, discard the failed response, and retry the original A mutation at base 0. Assert the retry is a canonical revision-1 conflict and the stored revision remains 1; then retry B normally and assert convergence.
- In `mobile-sync-pull/index.test.ts`, repeat the exact auth classification matrix, seed all five typed documents through the mutation RPC, invoke first-page pull as the owner, and deep-compare every canonical wrapper to the mutation responses, including strict RFC3339 `toISOString()` normalization. Inject malformed database timestamps and string/object-key Unicode to prove a name-only-logged generic 5xx rather than silent normalization. Assert both owner predicates are applied, a cross-user profile cannot be read, an absent row is not created, later cursor pages omit `profilePreferenceSections`, and every response retains `syncTime`.

After the exact `profile-preferences-edge-functions.md` content—including prose, comments, every fence, configuration, and the manifest—is written and reviewed, run `BackendHandoffContractTest` once. Its mismatch message prints the LF-normalized SHA-256 produced by the existing pure Kotlin implementation. Review the complete focused artifact diff, replace only the RHS of `EXPECTED_EDGE_HANDOFF_SHA256` with that concrete 64-character lowercase digest, and rerun. The empty-string sentinel must be gone. Never derive the expected value from the file, never place the digest inside the hashed handoff, and repin only when a reviewed handoff edit is intentional. The appended/inverted-executable mutation assertions must continue proving the seal detects drift.

Run this exact portal verification sequence from the portal repository:

```bash
supabase --version
supabase start
supabase db reset --local
supabase migration list --local
supabase test db --local
deno test --allow-env --allow-net supabase/functions/mobile-sync-push
deno test --allow-env --allow-net supabase/functions/mobile-sync-pull
supabase db lint --local --fail-on warning
supabase db advisors --local
git status --short
git diff --check
git diff --name-only
git diff -- supabase/functions/mobile-sync-push supabase/functions/mobile-sync-pull supabase/functions/_shared/profile-preference-byte-goldens.json supabase/tests/database/profile_preferences.test.sql supabase/config.toml supabase/migrations docs/profile-preferences-advisor-dispositions.md
```

Require Supabase CLI 2.81.3 or newer for `db advisors`. If that command is unavailable in the installed CLI, run the equivalent Supabase Advisors through the project MCP integration or Dashboard before handoff approval; do not skip it. Record every lint/advisor finding with id, severity, affected object, fix or evidence-backed disposition, command/source, and rerun result in `docs/profile-preferences-advisor-dispositions.md`. The final `git diff --name-only` must contain only the listed portal targets, the focused diff must be reviewed, and no `supabase db push`, `functions deploy`, remote migration, commit, or deployment belongs to this handoff step.

- [ ] **Step 6: Run the handoff contract test and verify it passes**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "com.devil.phoenixproject.data.sync.BackendHandoffContractTest" -Pskip.supabase.check=true
```

Expected: PASS with the concrete non-sentinel Edge digest, exact golden artifact, exact 19-statement SQL envelope, new hardening fragments, and expanded manifest all sealed.

- [ ] **Step 7: Commit the atomic backend handoff**

```powershell
git add docs/backend-handoff/profile-preferences-supabase.sql docs/backend-handoff/profile-preferences-edge-functions.md docs/backend-handoff/profile-preference-byte-goldens.json shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/BackendHandoffContractTest.kt
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

        val encodedPush = json.encodeToJsonElement(push).jsonObject
        val encodedPull = json.encodeToJsonElement(pull).jsonObject
        assertFalse("profilePreferencesAccepted" in encodedPush)
        assertEquals(
            JsonArray(emptyList()),
            encodedPush.getValue("canonicalProfilePreferenceSections"),
        )
        assertEquals(
            JsonArray(emptyList()),
            encodedPush.getValue("profilePreferenceRejections"),
        )
        assertFalse("profilePreferenceSections" in encodedPull)
    }

    @Test
    fun `mutation has exact value-only wire keys and JSON number revisions`() {
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

        val encoded = json.encodeToJsonElement(mutation).jsonObject
        assertEquals(
            setOf(
                "localProfileId", "section", "documentVersion", "baseRevision",
                "clientModifiedAt", "payload",
            ),
            encoded.keys,
        )
        assertEquals("profile-a", encoded.getValue("localProfileId").jsonPrimitive.content)
        assertEquals("WORKOUT", encoded.getValue("section").jsonPrimitive.content)
        assertFalse(encoded.getValue("documentVersion").jsonPrimitive.isString)
        assertEquals(1, encoded.getValue("documentVersion").jsonPrimitive.int)
        assertFalse(encoded.getValue("baseRevision").jsonPrimitive.isString)
        assertEquals(4L, encoded.getValue("baseRevision").jsonPrimitive.long)
        assertEquals(
            "2026-07-11T12:00:00Z",
            encoded.getValue("clientModifiedAt").jsonPrimitive.content,
        )
        val payload = encoded.getValue("payload")
        assertTrue(payload is JsonObject)
        assertEquals(setOf("version", "voiceStopEnabled"), payload.jsonObject.keys)
        assertFalse(payload.jsonObject.getValue("version").jsonPrimitive.isString)
        assertTrue(payload.jsonObject.getValue("voiceStopEnabled").jsonPrimitive.boolean)
    }

    @Test
    fun `recursive normalized local-only names cannot enter mutation or canonical DTOs`() {
        listOf(
            "safeWord",
            "safeWord\u0130",
            "SAFE_WORD",
            "safe-word-calibrated",
            "adults_only_confirmed",
            "adultsOnlyPrompted",
            "local_generation",
            "DIRTY",
            "legacy-migration-version",
        ).forEach { forbidden ->
            val adversarial = buildJsonObject {
                put("version", 1)
                putJsonArray("nested") {
                    add(buildJsonObject { put(forbidden, "must-not-enter-wire-dto") })
                }
            }
            assertFailsWith<IllegalArgumentException>(forbidden) {
                PortalProfilePreferenceSectionMutationDto(
                    localProfileId = "profile-a",
                    section = "WORKOUT",
                    documentVersion = 1,
                    baseRevision = 4,
                    clientModifiedAt = "2026-07-11T12:00:00Z",
                    payload = adversarial,
                )
            }
            assertFailsWith<IllegalArgumentException>(forbidden) {
                PortalProfilePreferenceSectionCanonicalDto(
                    localProfileId = "profile-a",
                    section = "WORKOUT",
                    documentVersion = 1,
                    serverRevision = 7,
                    serverUpdatedAt = "2026-07-11T12:01:00Z",
                    payload = adversarial,
                )
            }
        }
    }

    @Test
    fun `canonical and rejection encode exact keys types and revision identity`() {
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

        val encodedCanonical = json.encodeToJsonElement(canonical).jsonObject
        assertEquals(
            setOf(
                "localProfileId", "section", "documentVersion", "serverRevision",
                "serverUpdatedAt", "payload",
            ),
            encodedCanonical.keys,
        )
        assertFalse(encodedCanonical.getValue("serverRevision").jsonPrimitive.isString)
        assertEquals(7L, encodedCanonical.getValue("serverRevision").jsonPrimitive.long)
        assertTrue(encodedCanonical.getValue("payload") is JsonObject)

        val encodedRejection = json.encodeToJsonElement(rejection).jsonObject
        assertEquals(
            setOf(
                "localProfileId", "section", "serverRevision", "reason", "canonicalSection",
            ),
            encodedRejection.keys,
        )
        assertFalse(encodedRejection.getValue("serverRevision").jsonPrimitive.isString)
        assertEquals(7L, encodedRejection.getValue("serverRevision").jsonPrimitive.long)
        assertEquals("REVISION_CONFLICT", encodedRejection.getValue("reason").jsonPrimitive.content)
        assertEquals(encodedCanonical, encodedRejection.getValue("canonicalSection"))
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
    val localGeneration: Long,
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

Add a recursive value-only guard and the three `@Serializable` DTOs to `PortalSyncDtos.kt`. The guard normalizes punctuation/case exactly like the Edge validator and rejects local metadata or consent/safety fields at any payload depth. Filter to ASCII alphanumerics before lowercasing, matching JavaScript's `/[^a-z0-9]/gi`; reversing those operations diverges for Unicode case expansion such as `\u0130`. `Long` fields deliberately use the default kotlinx.serialization JSON-number representation; they are not quoted strings. Task 4 prevents values outside JavaScript's exact-integer range from reaching these DTOs.

```kotlin
private val LOCAL_ONLY_PROFILE_PREFERENCE_KEYS = setOf(
    "safeword",
    "safewordcalibrated",
    "adultsonlyconfirmed",
    "adultsonlyprompted",
    "localgeneration",
    "dirty",
    "legacymigrationversion",
)

private fun normalizedProfilePreferenceWireKey(key: String): String =
    key
        .filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        .lowercase()

private fun requireValueOnlyProfilePreferencePayload(value: kotlinx.serialization.json.JsonElement) {
    when (value) {
        is kotlinx.serialization.json.JsonArray ->
            value.forEach(::requireValueOnlyProfilePreferencePayload)
        is kotlinx.serialization.json.JsonObject -> value.forEach { (key, child) ->
            require(normalizedProfilePreferenceWireKey(key) !in LOCAL_ONLY_PROFILE_PREFERENCE_KEYS) {
                "Local-only profile preference fields are not wire-safe"
            }
            requireValueOnlyProfilePreferencePayload(child)
        }
        else -> Unit
    }
}

@Serializable
data class PortalProfilePreferenceSectionMutationDto(
    val localProfileId: String,
    val section: String,
    val documentVersion: Int,
    val baseRevision: Long,
    val clientModifiedAt: String,
    val payload: kotlinx.serialization.json.JsonObject,
) {
    init {
        requireValueOnlyProfilePreferencePayload(payload)
    }
}

@Serializable
data class PortalProfilePreferenceSectionCanonicalDto(
    val localProfileId: String,
    val section: String,
    val documentVersion: Int,
    val serverRevision: Long,
    val serverUpdatedAt: String,
    val payload: kotlinx.serialization.json.JsonObject,
) {
    init {
        requireValueOnlyProfilePreferencePayload(payload)
    }
}

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
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncDtos.kt`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalWireJson.kt`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncRepository.kt`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncCodec.kt`
- Create: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/sync/SqlDelightProfilePreferenceSyncRepositoryTest.kt`

**Interfaces:**
- Consumes: schema-43 `UserProfilePreferences`, the foundation typed models/validator/codec, and Task 3's internal sync models.
- Produces: an internal reusable wire-safety guard, a strict decoded-canonical column boundary, and a sync-only repository that snapshots dirty valid sections, reports invalid sections with category-only reasons, applies push canonicals with generation/revision guards, and merges pull canonicals without creating profiles.
- Invariant: every invalid section is isolated by `(localProfileId, section, localGeneration)`, remains dirty, and is excluded from wire construction; neither raw JSON, string values, exception messages, nor payload fragments enter an issue reason or log.

- [ ] **Step 1: Write failing row-owned-wrapper and invalid-section snapshot tests**

Create `SqlDelightProfilePreferenceSyncRepositoryTest.kt` with an owned in-memory JDBC driver. Do not call `createTestDatabase()`: that helper intentionally hides its driver, while these boundary tests need the driver for valid-but-out-of-domain SQLite seeds. Use this exact fixture and the generated profile lookup for no-create assertions; do not construct `SqlDelightUserProfileRepository` or its unrelated safety/gamification dependencies:

```kotlin
private lateinit var driver: JdbcSqliteDriver
private lateinit var database: VitruvianDatabase
private lateinit var foundationRepository: SqlDelightProfilePreferencesRepository
private lateinit var codec: ProfilePreferenceSyncCodec
private lateinit var repository: SqlDelightProfilePreferenceSyncRepository

@Before
fun setup() {
    driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    VitruvianDatabase.Schema.create(driver)
    database = VitruvianDatabase(driver)
    foundationRepository = SqlDelightProfilePreferencesRepository(database)
    codec = ProfilePreferenceSyncCodec()
    repository = SqlDelightProfilePreferenceSyncRepository(database, codec)
}

@After
fun tearDown() {
    driver.close()
}

private fun createProfile(id: String) {
    database.vitruvianDatabaseQueries.insertProfile(id, id, 0, 1, 0)
}

private fun assertProfileDoesNotExist(id: String) {
    assertNull(database.vitruvianDatabaseQueries.getProfileById(id).executeAsOneOrNull())
}
```

Import `JdbcSqliteDriver`, JUnit `Before`/`After`/`Test`, and the existing domain/foundation types. Insert profile `profile-a`, insert its default preference row, and write typed LED/VBT values through `SqlDelightProfilePreferencesRepository`:

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
    assertEquals(
        ProfilePreferenceSyncIssueReason.INVALID_LOCAL_DOCUMENT.name,
        snapshot.unsyncable.single().reason,
    )
}

private companion object {
    const val MAX_EXACT_JSON_INTEGER = 9_007_199_254_740_991L
    const val MIN_EXACT_JSON_INTEGER = -9_007_199_254_740_991L
    const val MIN_RFC3339_EPOCH_MILLIS = -62_135_596_800_000L
    const val MAX_RFC3339_EPOCH_MILLIS = 253_402_300_799_999L
}

private fun forceDirtyCoreRevision(profileId: String, revision: Long) {
    driver.execute(
        identifier = null,
        sql = """
            UPDATE UserProfilePreferences
               SET core_server_revision = ?, core_dirty = 1
             WHERE profile_id = ?
        """.trimIndent(),
        parameters = 2,
    ) {
        bindLong(0, revision)
        bindString(1, profileId)
    }
}

@Test
fun `base revision max is syncable while max plus one is a dead letter`() = runTest {
    val cases = listOf(
        "profile-max" to MAX_EXACT_JSON_INTEGER,
        "profile-over" to MAX_EXACT_JSON_INTEGER + 1,
    )
    cases.forEach { (profileId, revision) ->
        createProfile(profileId)
        foundationRepository.insertDefaults(profileId)
        forceDirtyCoreRevision(profileId, revision)
    }

    val first = repository.snapshotDirtySections()
    val max = first.valid.single {
        it.key.localProfileId == "profile-max" &&
            it.key.section == ProfilePreferenceSectionName.CORE
    }
    assertEquals(MAX_EXACT_JSON_INTEGER, max.baseRevision)
    assertTrue(first.valid.none {
        it.key.localProfileId == "profile-over" &&
            it.key.section == ProfilePreferenceSectionName.CORE
    })
    val issue = first.unsyncable.single {
        it.key.localProfileId == "profile-over" &&
            it.key.section == ProfilePreferenceSectionName.CORE
    }
    assertEquals(
        foundationRepository.get("profile-over").core.metadata.localGeneration,
        issue.localGeneration,
    )
    assertEquals(
        ProfilePreferenceSyncIssueReason.UNREPRESENTABLE_JSON_INTEGER.name,
        issue.reason,
    )
    assertTrue(foundationRepository.get("profile-over").core.metadata.dirty)

    val second = repository.snapshotDirtySections()
    assertEquals(
        setOf("profile-over"),
        second.unsyncable.filter { it.key.section == ProfilePreferenceSectionName.CORE }
            .map { it.key.localProfileId }
            .toSet(),
    )
}

@Test
fun `rack signed timestamp bounds stay JSON numbers and overflow is dead lettered`() = runTest {
    suspend fun writeRack(
        profileId: String,
        createdAt: Long,
        updatedAt: Long,
        duplicateName: Boolean = false,
    ) {
        createProfile(profileId)
        foundationRepository.insertDefaults(profileId)
        val items = buildList {
            add(
                RackItem(
                    id = "rack-1",
                    name = "Same name",
                    weightKg = 20f,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                ),
            )
            if (duplicateName) add(
                RackItem(
                    id = "rack-2",
                    name = "Same name",
                    weightKg = 10f,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                ),
            )
        }
        foundationRepository.updateRack(profileId, RackPreferences(items = items), now = 30)
    }
    writeRack(
        "rack-bounds",
        createdAt = MIN_EXACT_JSON_INTEGER,
        updatedAt = MAX_EXACT_JSON_INTEGER,
        duplicateName = true,
    )
    writeRack("rack-created-over", MAX_EXACT_JSON_INTEGER + 1, 0)
    writeRack("rack-updated-under", 0, MIN_EXACT_JSON_INTEGER - 1)

    val snapshot = repository.snapshotDirtySections()
    val bounds = snapshot.valid.single {
        it.key.localProfileId == "rack-bounds" &&
            it.key.section == ProfilePreferenceSectionName.RACK
    }
    val firstItem = bounds.payload.getValue("items").jsonArray.first().jsonObject
    assertFalse(firstItem.getValue("createdAt").jsonPrimitive.isString)
    assertFalse(firstItem.getValue("updatedAt").jsonPrimitive.isString)
    assertEquals(MIN_EXACT_JSON_INTEGER, firstItem.getValue("createdAt").jsonPrimitive.long)
    assertEquals(MAX_EXACT_JSON_INTEGER, firstItem.getValue("updatedAt").jsonPrimitive.long)
    assertEquals(2, bounds.payload.getValue("items").jsonArray.size)

    mapOf(
        "rack-created-over" to "RACK.items[0].createdAt",
        "rack-updated-under" to "RACK.items[0].updatedAt",
    ).forEach { (profileId, field) ->
        assertTrue(snapshot.valid.none {
            it.key.localProfileId == profileId &&
                it.key.section == ProfilePreferenceSectionName.RACK
        })
        val issue = snapshot.unsyncable.single {
            it.key.localProfileId == profileId &&
                it.key.section == ProfilePreferenceSectionName.RACK
        }
        assertEquals(
            ProfilePreferenceSyncIssueReason.UNREPRESENTABLE_JSON_INTEGER.name,
            issue.reason,
            field,
        )
        assertTrue(foundationRepository.get(profileId).rack.metadata.dirty)
    }

    val oldGeneration = snapshot.unsyncable.single {
        it.key.localProfileId == "rack-created-over" &&
            it.key.section == ProfilePreferenceSectionName.RACK
    }.localGeneration
    foundationRepository.updateRack(
        "rack-created-over",
        RackPreferences(
            items = listOf(
                RackItem(
                    id = "rack-1",
                    name = "Same name",
                    weightKg = 20f,
                    createdAt = 0,
                    updatedAt = 0,
                ),
            ),
        ),
        now = 40,
    )
    val revalidated = repository.snapshotDirtySections().valid.single {
        it.key.localProfileId == "rack-created-over" &&
            it.key.section == ProfilePreferenceSectionName.RACK
    }
    assertTrue(revalidated.localGeneration > oldGeneration)
}

private fun forceDirtyLedColorScheme(profileId: String, colorScheme: Long) {
    driver.execute(
        identifier = null,
        sql = """
            UPDATE UserProfilePreferences
               SET led_color_scheme_id = ?, led_dirty = 1
             WHERE profile_id = ?
        """.trimIndent(),
        parameters = 2,
    ) {
        bindLong(0, colorScheme)
        bindString(1, profileId)
    }
}

@Test
fun `LED color scheme validates Int32 before conversion and never wraps`() = runTest {
    mapOf(
        "led-max" to Int.MAX_VALUE.toLong(),
        "led-over" to Int.MAX_VALUE.toLong() + 1,
        "led-wraps-to-zero" to 4_294_967_296L,
    ).forEach { (profileId, value) ->
        createProfile(profileId)
        foundationRepository.insertDefaults(profileId)
        forceDirtyLedColorScheme(profileId, value)
    }

    val snapshot = repository.snapshotDirtySections()
    val max = snapshot.valid.single {
        it.key.localProfileId == "led-max" && it.key.section == ProfilePreferenceSectionName.LED
    }
    assertEquals(Int.MAX_VALUE, max.payload.getValue("ledColorSchemeId").jsonPrimitive.int)
    listOf("led-over", "led-wraps-to-zero").forEach { profileId ->
        assertTrue(snapshot.valid.none {
            it.key.localProfileId == profileId && it.key.section == ProfilePreferenceSectionName.LED
        })
        assertEquals(
            ProfilePreferenceSyncIssueReason.INVALID_INT32.name,
            snapshot.unsyncable.single {
                it.key.localProfileId == profileId &&
                    it.key.section == ProfilePreferenceSectionName.LED
            }.reason,
        )
    }
}

private fun singleExerciseDefaults(exerciseId: String) = SingleExerciseDefaultsDocument(
    exerciseId = exerciseId,
    setReps = emptyList(),
    weightPerCableKg = 20f,
    setWeightsPerCableKg = emptyList(),
    progressionKg = 0f,
    setRestSeconds = emptyList(),
    workoutModeId = 0,
    eccentricLoadPercentage = 100,
    echoLevelValue = 1,
    duration = 0,
    isAMRAP = false,
    perSetRestTime = false,
)

@Test
fun `Postgres incompatible text and normalized local only keys dead letter only their sections`() = runTest {
    createProfile("nul-profile")
    foundationRepository.insertDefaults("nul-profile")
    foundationRepository.updateRack(
        "nul-profile",
        RackPreferences(
            items = listOf(
                RackItem(id = "rack-nul", name = "SECRET_SENTINEL\u0000", weightKg = 20f),
            ),
        ),
        now = 20,
    )

    createProfile("surrogate-profile")
    foundationRepository.insertDefaults("surrogate-profile")
    foundationRepository.updateRack(
        "surrogate-profile",
        RackPreferences(
            items = listOf(
                RackItem(id = "rack-surrogate", name = "bad\uD800", weightKg = 20f),
            ),
        ),
        now = 20,
    )

    val forbiddenKey = "safeWord\u0130"
    createProfile("local-key-profile")
    foundationRepository.insertDefaults("local-key-profile")
    foundationRepository.updateWorkout(
        "local-key-profile",
        WorkoutPreferences(
            singleExerciseDefaults = mapOf(
                forbiddenKey to singleExerciseDefaults(forbiddenKey),
            ),
        ),
        now = 20,
    )

    val snapshot = repository.snapshotDirtySections()
    mapOf(
        ProfilePreferenceSectionKey("nul-profile", ProfilePreferenceSectionName.RACK) to
            ProfilePreferenceSyncIssueReason.INVALID_TEXT_TREE,
        ProfilePreferenceSectionKey("surrogate-profile", ProfilePreferenceSectionName.RACK) to
            ProfilePreferenceSyncIssueReason.INVALID_TEXT_TREE,
        ProfilePreferenceSectionKey("local-key-profile", ProfilePreferenceSectionName.WORKOUT) to
            ProfilePreferenceSyncIssueReason.LOCAL_ONLY_WIRE_KEY,
    ).forEach { (key, expectedReason) ->
        assertTrue(snapshot.valid.none { it.key == key })
        val issue = snapshot.unsyncable.single { it.key == key }
        assertEquals(expectedReason.name, issue.reason)
        assertFalse(issue.reason.contains("SECRET_SENTINEL"))
        assertFalse(issue.reason.contains(forbiddenKey))
    }
    assertTrue(snapshot.valid.any {
        it.key == ProfilePreferenceSectionKey(
            "nul-profile",
            ProfilePreferenceSectionName.CORE,
        )
    })
    assertTrue(snapshot.valid.any {
        it.key == ProfilePreferenceSectionKey(
            "local-key-profile",
            ProfilePreferenceSectionName.RACK,
        )
    })
}
```

Add boundary cases using the retained driver for both wrapper fields that Task 5 otherwise assumes are total. For each section timestamp column, `MIN_RFC3339_EPOCH_MILLIS` and `MAX_RFC3339_EPOCH_MILLIS` remain valid and serialize as four-digit-year RFC3339 instants; either adjacent millisecond is excluded from `valid` with only `INVALID_CLIENT_MODIFIED_AT`. Insert profiles with a blank id, an id containing U+0000, and an id containing a lone surrogate; every dirty section for those rows is excluded with only `INVALID_PROFILE_ID`, and neither the id nor a sentinel substring appears in any reason. A valid profile/section beside each invalid case remains syncable.

The fixture's `createProfile` calls the existing generated `insertProfile` query, and its retained in-memory `driver` is used only for boundary states that the public foundation repository cannot create. Do not seed a negative `core_server_revision`: schema 43's `CHECK (core_server_revision >= 0)` correctly makes that database state impossible. Keep the codec's negative-base-revision branch as defense in depth for non-database callers. Import `jsonArray`, `jsonObject`, `jsonPrimitive`, `int`, `long`, and `boolean`; assertions inspect JSON elements, not stringified nested JSON. “Dead letter for the current generation” has an executable meaning: the issue carries that `localGeneration`, the section remains dirty, every snapshot excludes it from `valid` (so no wire DTO, chunk, or RPC retry can be created), and it remains diagnosable in `unsyncable`. A subsequent local edit increments the generation and is validated afresh. No value is rounded, wrapped, replacement-character-normalized, coerced to a string, or sent repeatedly.

- [ ] **Step 2: Write failing generation-race and pull-merge tests**

Add these repository tests. `coreCanonical` returns a valid `CanonicalProfilePreferenceSection` with the literal CORE payload shown here, so no fallback decoder participates:

```kotlin
private fun coreCanonical(
    revision: Long,
    bodyWeightKg: Double,
    updatedAt: Long = 1_783_771_200_000L,
    profileId: String = "profile-a",
) = CanonicalProfilePreferenceSection(
    key = ProfilePreferenceSectionKey(profileId, ProfilePreferenceSectionName.CORE),
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
    assertProfileDoesNotExist("remote-only")
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

private fun canonical(
    profileId: String,
    section: ProfilePreferenceSectionName,
    revision: Long,
    variant: Int,
    updatedAt: Long = 1_783_771_200_000L + variant,
) = CanonicalProfilePreferenceSection(
    key = ProfilePreferenceSectionKey(profileId, section),
    documentVersion = 1,
    serverRevision = revision,
    serverUpdatedAtEpochMs = updatedAt,
    payload = when (section) {
        ProfilePreferenceSectionName.CORE -> buildJsonObject {
            put("bodyWeightKg", 80 + variant)
            put("weightUnit", "KG")
            put("weightIncrement", 0.5)
        }
        ProfilePreferenceSectionName.RACK -> buildJsonObject {
            put("version", 1)
            putJsonArray("items") {
                add(buildJsonObject {
                    put("id", "rack-$variant")
                    put("name", "Rack $variant")
                    put("category", "OTHER")
                    put("weightKg", variant)
                    put("behavior", "ADDED_RESISTANCE")
                    put("enabled", true)
                    put("sortOrder", variant)
                    put("createdAt", variant.toLong())
                    put("updatedAt", variant.toLong())
                })
            }
        }
        ProfilePreferenceSectionName.WORKOUT -> buildJsonObject {
            put("version", 1)
            put("stopAtTop", variant % 2 == 1)
        }
        ProfilePreferenceSectionName.LED -> buildJsonObject {
            put("ledColorSchemeId", variant)
            putJsonObject("preferences") {
                put("version", 1)
                put("discoModeUnlocked", variant % 2 == 1)
            }
        }
        ProfilePreferenceSectionName.VBT -> buildJsonObject {
            put("vbtEnabled", variant % 2 == 0)
            putJsonObject("preferences") {
                put("version", 1)
                put("velocityLossThresholdPercent", 20 + variant)
            }
        }
    },
)

private fun assertVariant(preferences: UserProfilePreferences, variant: Int) {
    assertEquals((80 + variant).toFloat(), preferences.core.value.bodyWeightKg)
    assertEquals(WeightUnit.KG, preferences.core.value.weightUnit)
    assertEquals(0.5f, preferences.core.value.weightIncrement)
    assertEquals("rack-$variant", preferences.rack.value.items.single().id)
    assertEquals(variant.toFloat(), preferences.rack.value.items.single().weightKg)
    assertEquals(variant % 2 == 1, preferences.workout.value.stopAtTop)
    assertEquals(variant, preferences.led.value.colorScheme)
    assertEquals(variant % 2 == 1, preferences.led.value.discoModeUnlocked)
    assertEquals(variant % 2 == 0, preferences.vbt.value.enabled)
    assertEquals(20 + variant, preferences.vbt.value.velocityLossThresholdPercent)
}

private fun allMetadata(preferences: UserProfilePreferences) = listOf(
    preferences.core.metadata,
    preferences.rack.metadata,
    preferences.workout.metadata,
    preferences.led.metadata,
    preferences.vbt.metadata,
)

private suspend fun acknowledgeAllDirty(profileId: String, revision: Long, variant: Int) {
    val sent = repository.snapshotDirtySections().valid
        .filter { it.key.localProfileId == profileId }
        .associateBy { it.key.section }
    repository.applyPushOutcomes(
        ProfilePreferenceSectionName.entries.map { section ->
            val snapshot = sent.getValue(section)
            ProfilePreferencePushOutcome(
                key = snapshot.key,
                sentLocalGeneration = snapshot.localGeneration,
                serverRevision = revision,
                canonical = canonical(profileId, section, revision, variant),
                rejectionReason = null,
            )
        },
    )
}

@Test
fun `matching generation push persists all five sections and row owned columns`() = runTest {
    createProfile("push-all")
    foundationRepository.insertDefaults("push-all")

    acknowledgeAllDirty("push-all", revision = 2, variant = 1)

    val preferences = foundationRepository.get("push-all")
    assertVariant(preferences, variant = 1)
    allMetadata(preferences).forEach { metadata ->
        assertEquals(2, metadata.serverRevision)
        assertFalse(metadata.dirty)
    }
    val row = database.vitruvianDatabaseQueries
        .selectProfilePreferenceSyncRow("push-all")
        .executeAsOne()
    assertEquals(1L, row.led_color_scheme_id)
    assertFalse(row.led_preferences_json.contains("colorScheme"))
    assertEquals(0L, row.vbt_enabled)
    assertFalse(row.vbt_preferences_json.contains("enabled"))
}

@Test
fun `newer local generations preserve all five values while advancing revisions`() = runTest {
    createProfile("race-all")
    foundationRepository.insertDefaults("race-all")
    val sent = repository.snapshotDirtySections().valid
        .filter { it.key.localProfileId == "race-all" }
        .associateBy { it.key.section }
    foundationRepository.updateCore(
        "race-all",
        CoreProfilePreferences(95f, WeightUnit.KG, 1f),
        now = 30,
    )
    foundationRepository.updateRack(
        "race-all",
        RackPreferences(items = listOf(RackItem(id = "local", name = "Local", weightKg = 9f))),
        now = 30,
    )
    foundationRepository.updateWorkout(
        "race-all",
        WorkoutPreferences(stopAtTop = false, beepsEnabled = false),
        now = 30,
    )
    foundationRepository.updateLed(
        "race-all",
        LedPreferences(colorScheme = 9, discoModeUnlocked = false),
        now = 30,
    )
    foundationRepository.updateVbt(
        "race-all",
        VbtPreferences(enabled = true, velocityLossThresholdPercent = 45),
        now = 30,
    )

    val report = repository.applyPushOutcomes(
        ProfilePreferenceSectionName.entries.map { section ->
            val snapshot = sent.getValue(section)
            ProfilePreferencePushOutcome(
                key = snapshot.key,
                sentLocalGeneration = snapshot.localGeneration,
                serverRevision = 4,
                canonical = canonical("race-all", section, revision = 4, variant = 1),
                rejectionReason = null,
            )
        },
    )

    val current = foundationRepository.get("race-all")
    assertEquals(95f, current.core.value.bodyWeightKg)
    assertEquals("local", current.rack.value.items.single().id)
    assertFalse(current.workout.value.beepsEnabled)
    assertEquals(9, current.led.value.colorScheme)
    assertEquals(45, current.vbt.value.velocityLossThresholdPercent)
    allMetadata(current).forEach { metadata ->
        assertEquals(4, metadata.serverRevision)
        assertTrue(metadata.dirty)
    }
    assertEquals(5, report.preservedNewerLocal)
}

@Test
fun `clean pull applies all five sections and rejects every lower revision`() = runTest {
    createProfile("pull-all")
    foundationRepository.insertDefaults("pull-all")
    acknowledgeAllDirty("pull-all", revision = 2, variant = 1)

    val applied = repository.applyPulledSections(
        ProfilePreferenceSectionName.entries.map { section ->
            canonical("pull-all", section, revision = 3, variant = 2)
        },
    )
    val afterHigher = foundationRepository.get("pull-all")
    assertEquals(5, applied.applied)
    assertVariant(afterHigher, variant = 2)
    allMetadata(afterHigher).forEach { metadata ->
        assertEquals(3, metadata.serverRevision)
        assertFalse(metadata.dirty)
    }

    val lower = repository.applyPulledSections(
        ProfilePreferenceSectionName.entries.map { section ->
            canonical("pull-all", section, revision = 2, variant = 3)
        },
    )
    assertEquals(0, lower.applied)
    assertEquals(afterHigher, foundationRepository.get("pull-all"))
}

@Test
fun `canonical null identity mismatch and revision mismatch remain dirty`() = runTest {
    createProfile("invalid-outcome")
    foundationRepository.insertDefaults("invalid-outcome")
    foundationRepository.updateCore(
        "invalid-outcome",
        CoreProfilePreferences(bodyWeightKg = 80f),
        now = 20,
    )
    val sent = repository.snapshotDirtySections().valid.single {
        it.key.localProfileId == "invalid-outcome" &&
            it.key.section == ProfilePreferenceSectionName.CORE
    }

    val noCanonical = repository.applyPushOutcomes(
        listOf(ProfilePreferencePushOutcome(sent.key, sent.localGeneration, 2, null, null)),
    )
    assertEquals(0, noCanonical.applied)
    assertTrue(foundationRepository.get("invalid-outcome").core.metadata.dirty)

    val wrongKey = repository.applyPushOutcomes(
        listOf(
            ProfilePreferencePushOutcome(
                sent.key,
                sent.localGeneration,
                2,
                canonical("other-profile", ProfilePreferenceSectionName.CORE, 2, 1),
                null,
            ),
        ),
    )
    val wrongRevision = repository.applyPushOutcomes(
        listOf(
            ProfilePreferencePushOutcome(
                sent.key,
                sent.localGeneration,
                3,
                canonical("invalid-outcome", ProfilePreferenceSectionName.CORE, 2, 1),
                null,
            ),
        ),
    )
    assertEquals(1, wrongKey.invalid)
    assertEquals(1, wrongRevision.invalid)
    assertTrue(foundationRepository.get("invalid-outcome").core.metadata.dirty)
}

@Test
fun `canonical revision bounds and malformed payload fail closed with category only reasons`() {
    val max = coreCanonical(MAX_EXACT_JSON_INTEGER, 80.0)
    assertIs<ProfilePreferenceCanonicalColumnsResult.Valid>(codec.decodeCanonical(max))
    listOf(-1L, MAX_EXACT_JSON_INTEGER + 1).forEach { revision ->
        val invalid = assertIs<ProfilePreferenceCanonicalColumnsResult.Invalid>(
            codec.decodeCanonical(coreCanonical(revision, 80.0)),
        )
        assertEquals(ProfilePreferenceSyncIssueReason.INVALID_CANONICAL_REVISION, invalid.reason)
    }
    val sentinel = "SECRET_SENTINEL"
    val malformed = canonical("profile-a", ProfilePreferenceSectionName.WORKOUT, 3, 1)
        .copy(payload = buildJsonObject {
            put("version", 1)
            put("repCountTiming", sentinel)
        })
    val invalid = assertIs<ProfilePreferenceCanonicalColumnsResult.Invalid>(
        codec.decodeCanonical(malformed),
    )
    assertEquals(ProfilePreferenceSyncIssueReason.INVALID_CANONICAL_PAYLOAD, invalid.reason)
    assertFalse(invalid.reason.name.contains(sentinel))
}

@Test
fun `malformed pulled canonical is isolated while valid sibling applies`() = runTest {
    createProfile("malformed-pull")
    foundationRepository.insertDefaults("malformed-pull")
    acknowledgeAllDirty("malformed-pull", revision = 2, variant = 1)
    val malformed = canonical(
        "malformed-pull",
        ProfilePreferenceSectionName.WORKOUT,
        revision = 3,
        variant = 2,
    ).copy(payload = buildJsonObject {
        put("version", 1)
        put("repCountTiming", "SECRET_SENTINEL")
    })

    val report = repository.applyPulledSections(
        listOf(
            malformed,
            canonical("malformed-pull", ProfilePreferenceSectionName.CORE, 3, 2),
        ),
    )

    val current = foundationRepository.get("malformed-pull")
    assertEquals(1, report.invalid)
    assertEquals(1, report.applied)
    assertTrue(current.workout.value.stopAtTop)
    assertEquals(82f, current.core.value.bodyWeightKg)
}

@Test
fun `matching generation requires dirty state and nonregressing revision`() = runTest {
    createProfile("stale-push")
    foundationRepository.insertDefaults("stale-push")
    val sent = repository.snapshotDirtySections().valid.single {
        it.key.localProfileId == "stale-push" &&
            it.key.section == ProfilePreferenceSectionName.CORE
    }
    repository.applyPushOutcomes(
        listOf(
            ProfilePreferencePushOutcome(
                sent.key,
                sent.localGeneration,
                6,
                canonical("stale-push", ProfilePreferenceSectionName.CORE, 6, 6),
                null,
            ),
        ),
    )
    val stale = ProfilePreferencePushOutcome(
        sent.key,
        sent.localGeneration,
        5,
        canonical("stale-push", ProfilePreferenceSectionName.CORE, 5, 5),
        null,
    )
    assertEquals(0, repository.applyPushOutcomes(listOf(stale)).applied)

    driver.execute(
        null,
        "UPDATE UserProfilePreferences SET core_dirty = 1 WHERE profile_id = ?",
        1,
    ) { bindString(0, "stale-push") }
    assertEquals(0, repository.applyPushOutcomes(listOf(stale)).applied)
    val current = foundationRepository.get("stale-push").core
    assertEquals(86f, current.value.bodyWeightKg)
    assertEquals(6, current.metadata.serverRevision)
    assertTrue(current.metadata.dirty)
}

@Test
fun `semantic numeric equality does not become equal revision divergence`() = runTest {
    createProfile("numeric-equality")
    foundationRepository.insertDefaults("numeric-equality")
    val integerToken = coreCanonical(0, 80.0, profileId = "numeric-equality").copy(
        payload = PortalWireJson.parseToJsonElement(
            """{"bodyWeightKg":80,"weightUnit":"KG","weightIncrement":0.5}""",
        ).jsonObject,
    )
    driver.execute(
        null,
        """
            UPDATE UserProfilePreferences
               SET body_weight_kg = 80, weight_unit = 'KG', weight_increment = 0.5,
                   core_dirty = 0
             WHERE profile_id = ?
        """.trimIndent(),
        1,
    ) { bindString(0, "numeric-equality") }
    val normalizedRow = database.vitruvianDatabaseQueries
        .selectProfilePreferenceSyncRow("numeric-equality")
        .executeAsOne()

    assertFalse(codec.hasCanonicalDivergence(normalizedRow, integerToken))
    assertTrue(
        codec.hasCanonicalDivergence(
            normalizedRow,
            coreCanonical(0, 83.0, profileId = "numeric-equality"),
        ),
    )
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

selectChangedRowCount:
SELECT changes();
```

For push canonicals, add these ten query names. Each `apply...ForGeneration` updates only its section's value columns, `*_updated_at`, `*_server_revision`, and `*_dirty = 0`, guarded by `profile_id`, equality with `:sent_local_generation`, `*_dirty = 1`, and `*_server_revision <= :server_revision`. The dirty guard rejects a delayed replay after a clean pull; the monotonic guard rejects revision regression even if a corrupt/replayed caller re-marks the same generation dirty. Each `advance...ForNewerGeneration` updates only `*_server_revision`, guarded by a strictly newer generation and a lower stored revision.

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
  AND core_local_generation = :sent_local_generation
  AND core_dirty = 1
  AND core_server_revision <= :server_revision;

advanceCoreRevisionForNewerGeneration:
UPDATE UserProfilePreferences
SET core_server_revision = :server_revision
WHERE profile_id = :profile_id
  AND core_local_generation > :sent_local_generation
  AND core_server_revision < :server_revision;
```

The RACK/WORKOUT/LED/VBT matching queries use the value columns in the table above and the corresponding section metadata names, including both the exact `*_dirty = 1` and `*_server_revision <= :server_revision` guards. Their newer-generation queries contain no value, timestamp, generation, or dirty assignment.

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

First create `PortalWireJson.kt`; Task 4's codec uses this shared instance and must compile independently before Task 5 adds raw request scanning:

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

In `PortalSyncDtos.kt`, replace the private recursive guard with this internal reusable result. Both DTO initializers continue to call `requireValueOnlyProfilePreferencePayload`; Task 4 calls `profilePreferenceWireSafetyViolation` before a local section can enter the valid snapshot. This is the one implementation of ASCII-filter-then-lowercase local-only normalization and PostgreSQL-compatible UTF-16 validation; do not duplicate it in the codec:

```kotlin
internal enum class ProfilePreferenceWireSafetyViolation {
    INVALID_TEXT_TREE,
    LOCAL_ONLY_KEY,
}

private fun isPostgresCompatibleText(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val codeUnit = value[index]
        when {
            codeUnit == '\u0000' -> return false
            codeUnit in '\uD800'..'\uDBFF' -> {
                if (index + 1 >= value.length || value[index + 1] !in '\uDC00'..'\uDFFF') {
                    return false
                }
                index += 1
            }
            codeUnit in '\uDC00'..'\uDFFF' -> return false
        }
        index += 1
    }
    return true
}

internal fun profilePreferenceWireSafetyViolation(
    value: kotlinx.serialization.json.JsonElement,
): ProfilePreferenceWireSafetyViolation? = when (value) {
    is kotlinx.serialization.json.JsonArray -> value.firstNotNullOfOrNull(
        ::profilePreferenceWireSafetyViolation,
    )
    is kotlinx.serialization.json.JsonObject -> {
        value.entries.firstNotNullOfOrNull { (key, child) ->
            when {
                !isPostgresCompatibleText(key) ->
                    ProfilePreferenceWireSafetyViolation.INVALID_TEXT_TREE
                normalizedProfilePreferenceWireKey(key) in LOCAL_ONLY_PROFILE_PREFERENCE_KEYS ->
                    ProfilePreferenceWireSafetyViolation.LOCAL_ONLY_KEY
                else -> profilePreferenceWireSafetyViolation(child)
            }
        }
    }
    is kotlinx.serialization.json.JsonPrimitive -> if (
        value.isString && !isPostgresCompatibleText(value.content)
    ) {
        ProfilePreferenceWireSafetyViolation.INVALID_TEXT_TREE
    } else {
        null
    }
}

private fun requireValueOnlyProfilePreferencePayload(
    value: kotlinx.serialization.json.JsonElement,
) {
    require(profilePreferenceWireSafetyViolation(value) == null) {
        "Profile preference payload is not wire-safe"
    }
}
```

Then create `ProfilePreferenceSyncCodec.kt` as an internal class. It parses foundation codec output back to `JsonObject`, so JSON documents remain objects:

```kotlin
internal class ProfilePreferenceSyncCodec {
    companion object {
        const val MAX_EXACT_JSON_INTEGER = 9_007_199_254_740_991L
        const val MIN_EXACT_JSON_INTEGER = -9_007_199_254_740_991L
        const val MIN_RFC3339_EPOCH_MILLIS = -62_135_596_800_000L
        const val MAX_RFC3339_EPOCH_MILLIS = 253_402_300_799_999L
    }

    private fun exactJsonIntegerIssue(value: Long): ProfilePreferenceSyncIssueReason? =
        if (value in MIN_EXACT_JSON_INTEGER..MAX_EXACT_JSON_INTEGER) {
            null
        } else {
            ProfilePreferenceSyncIssueReason.UNREPRESENTABLE_JSON_INTEGER
        }

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

    fun normalizedPayload(value: DecodedProfilePreferenceValue): JsonObject = when (value) {
        is DecodedProfilePreferenceValue.Core -> corePayload(value.value)
        is DecodedProfilePreferenceValue.Rack -> rackPayload(value.value)
        is DecodedProfilePreferenceValue.Workout -> workoutPayload(value.value)
        is DecodedProfilePreferenceValue.Led -> ledPayload(value.value)
        is DecodedProfilePreferenceValue.Vbt -> vbtPayload(value.value)
    }

    fun validateCanonicalPayload(
        section: ProfilePreferenceSectionName,
        documentVersion: Int,
        payload: JsonObject,
    ): ProfilePreferencePayloadValidation {
        val reason = canonicalPayloadIssue(section, documentVersion, payload)
        return ProfilePreferencePayloadValidation(
            isValid = reason == null,
            reason = reason?.name.orEmpty(),
        )
    }
}

internal data class ProfilePreferencePayloadValidation(
    val isValid: Boolean,
    val reason: String,
)

internal enum class ProfilePreferenceSyncIssueReason {
    INVALID_PROFILE_ID,
    INVALID_LOCAL_DOCUMENT,
    INVALID_CLIENT_MODIFIED_AT,
    UNREPRESENTABLE_JSON_INTEGER,
    INVALID_INT32,
    INVALID_TEXT_TREE,
    LOCAL_ONLY_WIRE_KEY,
    UNSUPPORTED_DOCUMENT_VERSION,
    INVALID_CANONICAL_PAYLOAD,
    INVALID_CANONICAL_REVISION,
}

internal sealed interface DecodedProfilePreferenceValue {
    data class Core(val value: CoreProfilePreferences) : DecodedProfilePreferenceValue
    data class Rack(val value: RackPreferences) : DecodedProfilePreferenceValue
    data class Workout(val value: WorkoutPreferences) : DecodedProfilePreferenceValue
    data class Led(val value: LedPreferences) : DecodedProfilePreferenceValue
    data class Vbt(val value: VbtPreferences) : DecodedProfilePreferenceValue
}

internal data class DecodedProfilePreferenceColumns(
    val key: ProfilePreferenceSectionKey,
    val documentVersion: Int,
    val serverRevision: Long,
    val serverUpdatedAtEpochMs: Long,
    val value: DecodedProfilePreferenceValue,
    val normalizedPayload: JsonObject,
) {
    val section: ProfilePreferenceSectionName get() = key.section
}

internal sealed interface ProfilePreferenceCanonicalColumnsResult {
    data class Valid(
        val columns: DecodedProfilePreferenceColumns,
    ) : ProfilePreferenceCanonicalColumnsResult

    data class Invalid(
        val reason: ProfilePreferenceSyncIssueReason,
    ) : ProfilePreferenceCanonicalColumnsResult
}

internal data class EncodedDirtyProfilePreferenceRow(
    val valid: List<ProfilePreferenceSectionSyncDto>,
    val unsyncable: List<ProfilePreferenceSyncIssue>,
)
```

Add `encodeDirtyRow(row)`, `decodeAndValidateTypedValue(section, payload)`, `canonicalPayloadIssue(section, documentVersion, payload)`, and `decodeCanonical(canonical)` methods. `encodeDirtyRow` returns `EncodedDirtyProfilePreferenceRow` and catches each section independently; no exception from one section may abort a valid sibling. It decodes local JSON only through the foundation codec, checks the returned `ProfilePreferenceValidity`, and never syncs a fallback `.value` from an invalid document.

Before constructing a `ProfilePreferenceSectionSyncDto`, apply these checks in order and emit only the fixed `ProfilePreferenceSyncIssueReason.name` as `reason`:

1. Before section decoding, require `row.profile_id.isNotBlank()` and require `profilePreferenceWireSafetyViolation(JsonPrimitive(row.profile_id)) == null`. A blank or PostgreSQL-incompatible id yields `INVALID_PROFILE_ID` for every dirty section in that row; no DTO is constructed. The issue key may retain the id for local repair identity, but no later log may print that raw key.
2. A negative base revision is `INVALID_LOCAL_DOCUMENT`; a base revision outside the exact JSON integer interval is `UNREPRESENTABLE_JSON_INTEGER`.
3. Require the section's `*_updated_at` in `MIN_RFC3339_EPOCH_MILLIS..MAX_RFC3339_EPOCH_MILLIS`; otherwise use `INVALID_CLIENT_MODIFIED_AT`. These inclusive bounds are exactly `0001-01-01T00:00:00.000Z` through `9999-12-31T23:59:59.999Z`, keeping Task 5's `Instant.fromEpochMilliseconds(...).toString()` within the Edge four-digit-year contract.
4. For RACK, every signed `createdAt` and `updatedAt` must be in the exact JSON integer interval. Repeated rack names remain valid; duplicate IDs remain invalid through the foundation validator.
5. Before `row.led_color_scheme_id.toInt()`, require the stored `Long` to be in `Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()`; otherwise use `INVALID_INT32`. Schema 43 already excludes negative values, but the codec performs the complete conversion guard.
6. Build the exact typed payload and call `profilePreferenceWireSafetyViolation`. Map `INVALID_TEXT_TREE` and `LOCAL_ONLY_KEY` to their corresponding sync issue reason. This must happen in Task 4 so Task 5's DTO constructor cannot throw while mapping the full valid snapshot.

The issue retains the current profile/section generation and stays out of `valid`, so serialization, chunking, and RPC retry cannot see it. Valid `Long` values use `JsonPrimitive(Long)` and remain JSON numbers. Never retain an exception message, raw JSON, invalid string, numeric value, or payload fragment in a reason.

`canonicalPayloadIssue` first requires document version 1, then the reusable wire-safety guard, then direct typed decoding/validation. It returns only a `ProfilePreferenceSyncIssueReason`; `runCatching` may contain decoder exceptions locally, but their messages are discarded. `decodeCanonical` additionally requires `serverRevision` in `0..MAX_EXACT_JSON_INTEGER`, returns `ProfilePreferenceCanonicalColumnsResult.Invalid(INVALID_CANONICAL_REVISION)` otherwise, and returns `Valid` with the exact key/revision/timestamp, typed value, and `normalizedPayload(value)` only after all checks pass.

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

Implement the version/safety/category boundary exactly once and have both public codec entry points call it:

```kotlin
private fun canonicalPayloadIssue(
    section: ProfilePreferenceSectionName,
    documentVersion: Int,
    payload: JsonObject,
): ProfilePreferenceSyncIssueReason? {
    if (documentVersion != 1) {
        return ProfilePreferenceSyncIssueReason.UNSUPPORTED_DOCUMENT_VERSION
    }
    when (profilePreferenceWireSafetyViolation(payload)) {
        ProfilePreferenceWireSafetyViolation.INVALID_TEXT_TREE ->
            return ProfilePreferenceSyncIssueReason.INVALID_TEXT_TREE
        ProfilePreferenceWireSafetyViolation.LOCAL_ONLY_KEY ->
            return ProfilePreferenceSyncIssueReason.LOCAL_ONLY_WIRE_KEY
        null -> Unit
    }
    val explicitEmbeddedVersion = runCatching {
        when (section) {
            ProfilePreferenceSectionName.CORE -> null
            ProfilePreferenceSectionName.RACK,
            ProfilePreferenceSectionName.WORKOUT ->
                payload["version"]?.jsonPrimitive?.int
            ProfilePreferenceSectionName.LED,
            ProfilePreferenceSectionName.VBT ->
                payload.getValue("preferences").jsonObject["version"]?.jsonPrimitive?.int
        }
    }.getOrElse {
        return ProfilePreferenceSyncIssueReason.INVALID_CANONICAL_PAYLOAD
    }
    if (explicitEmbeddedVersion != null && explicitEmbeddedVersion != documentVersion) {
        return ProfilePreferenceSyncIssueReason.UNSUPPORTED_DOCUMENT_VERSION
    }
    val decoded = runCatching {
        decodeAndValidateTypedValue(section, payload)
    }.getOrElse {
        return ProfilePreferenceSyncIssueReason.INVALID_CANONICAL_PAYLOAD
    }
    val decodedVersion = when (decoded) {
        is DecodedProfilePreferenceValue.Core -> 1
        is DecodedProfilePreferenceValue.Rack -> decoded.value.version
        is DecodedProfilePreferenceValue.Workout -> decoded.value.version
        is DecodedProfilePreferenceValue.Led -> decoded.value.version
        is DecodedProfilePreferenceValue.Vbt -> decoded.value.version
    }
    return if (decodedVersion == documentVersion) {
        null
    } else {
        ProfilePreferenceSyncIssueReason.UNSUPPORTED_DOCUMENT_VERSION
    }
}

fun decodeCanonical(
    canonical: CanonicalProfilePreferenceSection,
): ProfilePreferenceCanonicalColumnsResult {
    if (canonical.serverRevision !in 0..MAX_EXACT_JSON_INTEGER) {
        return ProfilePreferenceCanonicalColumnsResult.Invalid(
            ProfilePreferenceSyncIssueReason.INVALID_CANONICAL_REVISION,
        )
    }
    canonicalPayloadIssue(
        canonical.key.section,
        canonical.documentVersion,
        canonical.payload,
    )?.let { reason ->
        return ProfilePreferenceCanonicalColumnsResult.Invalid(reason)
    }
    val value = runCatching {
        decodeAndValidateTypedValue(canonical.key.section, canonical.payload)
    }.getOrElse {
        return ProfilePreferenceCanonicalColumnsResult.Invalid(
            ProfilePreferenceSyncIssueReason.INVALID_CANONICAL_PAYLOAD,
        )
    }
    return ProfilePreferenceCanonicalColumnsResult.Valid(
        DecodedProfilePreferenceColumns(
            key = canonical.key,
            documentVersion = canonical.documentVersion,
            serverRevision = canonical.serverRevision,
            serverUpdatedAtEpochMs = canonical.serverUpdatedAtEpochMs,
            value = value,
            normalizedPayload = normalizedPayload(value),
        ),
    )
}
```

`decodeCanonical` never substitutes defaults after malformed JSON, wrong JSON types, invalid enums, non-finite/range-invalid values, wire-unsafe text/keys, unsafe revisions, or version mismatches. Kotlin serialization defaults remain compatible only for omitted valid version-1 document fields.

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
        var invalid = 0
        outcomes.groupBy { it.key.localProfileId }.forEach { (_, profileOutcomes) ->
            database.transaction {
                profileOutcomes.forEach { outcome ->
                    val canonical = outcome.canonical ?: return@forEach
                    if (canonical.key != outcome.key ||
                        canonical.serverRevision != outcome.serverRevision
                    ) {
                        invalid++
                        return@forEach
                    }
                    val decoded = codec.decodeCanonical(canonical)
                    if (decoded is ProfilePreferenceCanonicalColumnsResult.Invalid) {
                        invalid++
                        return@forEach
                    }
                    val columns =
                        (decoded as ProfilePreferenceCanonicalColumnsResult.Valid).columns
                    if (applyCanonicalForGeneration(columns, outcome.sentLocalGeneration)) {
                        applied++
                    } else if (advanceRevisionForNewerGeneration(columns, outcome.sentLocalGeneration)) {
                        preserved++
                    }
                }
            }
        }
        return ProfilePreferenceSyncApplyReport(
            applied = applied,
            preservedNewerLocal = preserved,
            invalid = invalid,
        )
    }

    override suspend fun applyPulledSections(
        sections: List<CanonicalProfilePreferenceSection>,
    ): ProfilePreferenceSyncApplyReport {
        var applied = 0
        var unknown = 0
        var invalid = 0
        sections.groupBy { it.key.localProfileId }.forEach { (profileId, canonicals) ->
            database.transaction {
                if (queries.selectProfilePreferenceSyncRow(profileId).executeAsOneOrNull() == null) {
                    unknown += canonicals.size
                    return@transaction
                }
                canonicals.forEach { canonical ->
                    when (val decoded = codec.decodeCanonical(canonical)) {
                        is ProfilePreferenceCanonicalColumnsResult.Invalid -> invalid++
                        is ProfilePreferenceCanonicalColumnsResult.Valid -> {
                            if (applyPulledWhenClean(decoded.columns)) applied++
                        }
                    }
                }
            }
        }
        return ProfilePreferenceSyncApplyReport(
            applied = applied,
            ignoredUnknownProfile = unknown,
            invalid = invalid,
        )
    }
}
```

A null canonical is an intentional no-op and does not increment `invalid`; its section remains dirty. A present canonical is defense-in-depth validated at the repository boundary even though Task 6's response mapper performs the same identity checks. Key mismatch, canonical/outcome revision mismatch, unsafe canonical revision, malformed payload, or wire-safety failure increments `invalid` and performs no SQL mutation. Never log or throw with the invalid content.

Add this codec result and method; `currentState` uses the same row-owned LED/VBT wrapper methods as `encodeDirtyRow`:

```kotlin
internal data class CurrentProfilePreferenceSyncState(
    val serverRevision: Long,
    val dirty: Boolean,
    val normalizedPayload: JsonObject?,
)

fun currentState(
    row: com.devil.phoenixproject.database.UserProfilePreferences,
    section: ProfilePreferenceSectionName,
): CurrentProfilePreferenceSyncState = when (section) {
    ProfilePreferenceSectionName.CORE -> CurrentProfilePreferenceSyncState(
        row.core_server_revision,
        row.core_dirty == 1L,
        runCatching {
            CoreProfilePreferences(
                row.body_weight_kg.toFloat(),
                WeightUnit.valueOf(row.weight_unit),
                row.weight_increment.toFloat(),
            )
        }.getOrNull()?.takeIf { ProfilePreferencesValidator.core(it).isEmpty() }
            ?.let(::corePayload),
    )
    ProfilePreferenceSectionName.RACK -> CurrentProfilePreferenceSyncState(
        row.rack_server_revision,
        row.rack_dirty == 1L,
        ProfilePreferencesCodec.decodeRack(row.equipment_rack_json)
            .takeIf { it.validity is ProfilePreferenceValidity.Valid }
            ?.value
            ?.let(::rackPayload),
    )
    ProfilePreferenceSectionName.WORKOUT -> CurrentProfilePreferenceSyncState(
        row.workout_server_revision,
        row.workout_dirty == 1L,
        ProfilePreferencesCodec.decodeWorkout(row.workout_preferences_json)
            .takeIf { it.validity is ProfilePreferenceValidity.Valid }
            ?.value
            ?.let(::workoutPayload),
    )
    ProfilePreferenceSectionName.LED -> CurrentProfilePreferenceSyncState(
        row.led_server_revision,
        row.led_dirty == 1L,
        row.led_color_scheme_id
            .takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
            ?.toInt()
            ?.let { ProfilePreferencesCodec.decodeLed(row.led_preferences_json, it) }
            ?.takeIf { it.validity is ProfilePreferenceValidity.Valid }
            ?.value
            ?.let(::ledPayload),
    )
    ProfilePreferenceSectionName.VBT -> CurrentProfilePreferenceSyncState(
        row.vbt_server_revision,
        row.vbt_dirty == 1L,
        ProfilePreferencesCodec.decodeVbt(
            row.vbt_preferences_json,
            row.vbt_enabled == 1L,
        ).takeIf { it.validity is ProfilePreferenceValidity.Valid }
            ?.value
            ?.let(::vbtPayload),
    )
}

fun hasCanonicalDivergence(
    row: com.devil.phoenixproject.database.UserProfilePreferences,
    columns: DecodedProfilePreferenceColumns,
): Boolean {
    val current = currentState(row, columns.section)
    return !current.dirty &&
        current.serverRevision == columns.serverRevision &&
        current.normalizedPayload != columns.normalizedPayload
}

fun hasCanonicalDivergence(
    row: com.devil.phoenixproject.database.UserProfilePreferences,
    canonical: CanonicalProfilePreferenceSection,
): Boolean = when (val decoded = decodeCanonical(canonical)) {
    is ProfilePreferenceCanonicalColumnsResult.Invalid -> false
    is ProfilePreferenceCanonicalColumnsResult.Valid ->
        hasCanonicalDivergence(row, decoded.columns)
}
```

Before `applyPulledWhenClean`, read the row through `selectProfilePreferenceSyncRow` and use the decoded columns' normalized typed payload. Numeric token spelling (`80` versus `80.0`), omitted fields that decode to the same version-1 defaults, and object key order must not count as divergence. A null current normalized payload means the clean local row is malformed and therefore differs from a valid canonical. Log only the trusted section enum for a true invariant repair, then apply the canonical query:

```kotlin
if (codec.hasCanonicalDivergence(row, columns)) {
    Logger.w("ProfilePreferenceSync") {
        "Repairing equal-revision canonical divergence section=${columns.section.name}"
    }
}
```

Never log the profile id/key, either payload, decoder exception messages, or invalid values.

Implement the three dispatch helpers as exhaustive `when (columns.value)` calls, using the typed value carried by `DecodedProfilePreferenceColumns`:

- CORE binds Float values as `Double`, `weightUnit.name`, and the canonical revision/timestamp.
- RACK and WORKOUT persist `ProfilePreferencesCodec.encodeRack/encodeWorkout`.
- LED persists `colorScheme.toLong()` separately and `ProfilePreferencesCodec.encodeLed`, which omits the row-owned color scheme.
- VBT persists `enabled` as `1L`/`0L` separately and `ProfilePreferencesCodec.encodeVbt`, which omits the row-owned enabled flag.

Each branch calls the exact section query from Step 4. Immediately after each update, call `queries.selectChangedRowCount().executeAsOne()` and return whether it is greater than zero; add `selectChangedRowCount: SELECT changes();` beside the query matrix. No SELECT or second mutation may occur between the guarded UPDATE and this scalar read. A push outcome without a canonical is a no-op and remains dirty. Task 6 rejects duplicate response keys, while this repository independently rejects canonical/outcome identity mismatches.

- [ ] **Step 7: Run repository and foundation regression tests**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "*SqlDelightProfilePreferenceSyncRepositoryTest*" --tests "*SqlDelightProfilePreferencesRepositoryTest*" -Pskip.supabase.check=true
```

Expected: PASS, including category-only malformed-local dead letters, valid-sibling isolation, exact integer and LED Int32 boundaries, NUL/lone-surrogate/local-only-key isolation, all-five matching/newer-generation push dispatch, row-owned LED/VBT persistence, all-five higher/equal/lower pull dispatch, semantic numeric equality, true equal-revision repair, dirty-pull preservation, malformed-canonical isolation, canonical-null/identity/revision fail-closed behavior, and generated-query unknown-profile no-create behavior.

- [ ] **Step 8: Commit the sync persistence boundary**

```powershell
git add shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncDtos.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalWireJson.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncRepository.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncCodec.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/sync/SqlDelightProfilePreferenceSyncRepositoryTest.kt
git commit -m "feat(sync): add profile preference sync repository"
```

---

### Task 5: Add Exact Wire JSON, Adapters, and Preference Chunk Planning

**Files:**
- Read: `docs/backend-handoff/profile-preference-byte-goldens.json`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncCodec.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalWireJson.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapter.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalPullAdapter.kt`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncPlanner.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapterProfilePreferencesTest.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalPullAdapterProfilePreferencesTest.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncPlannerTest.kt`

**Interfaces:**
- Consumes: Task 3's internal/wire DTOs and Task 4's strict sync codec. A section in Task 4's `valid` snapshot already has a nonblank PostgreSQL-compatible profile id, an RFC3339-representable client timestamp, an exact JSON-number revision, and a typed wire-safe payload.
- Produces: valid mutation envelopes, canonicals accepted through Task 4's `decodeCanonical` boundary, deterministic request chunks that never exceed either byte cap, and fixed-category unsyncable-section diagnostics.

- [ ] **Step 1: Write failing adapter tests**

Create tests that assert the timestamp/revision/generation boundary and strict pull validation. Use only fixed `ProfilePreferenceSyncIssueReason.name` values in invalid results:

```kotlin
@Test
fun `push adapter maps audit timestamp but keeps generation off wire`() {
    val source = ProfilePreferenceSectionSyncDto(
        key = ProfilePreferenceSectionKey("profile-a", ProfilePreferenceSectionName.CORE),
        documentVersion = 1,
        baseRevision = 9_007_199_254_740_991L,
        clientModifiedAtEpochMs = 1_783_771_200_000L,
        localGeneration = 9,
        payload = buildJsonObject {
            put("bodyWeightKg", 82.5)
            put("weightUnit", "KG")
            put("weightIncrement", 0.5)
        },
    )

    val prepared = PortalSyncAdapter.toPortalProfilePreferenceMutation(source)

    assertEquals(9L, prepared.sentLocalGeneration)
    assertEquals(9_007_199_254_740_991L, prepared.wire.baseRevision)
    assertEquals("CORE", prepared.wire.section)
    assertEquals("2026-07-11T12:00:00Z", prepared.wire.clientModifiedAt)
    val encoded = PortalWireJson.encodeToJsonElement(
        PortalProfilePreferenceSectionMutationDto.serializer(),
        prepared.wire,
    ).jsonObject
    assertFalse(encoded.getValue("baseRevision").jsonPrimitive.isString)
    assertEquals(
        9_007_199_254_740_991L,
        encoded.getValue("baseRevision").jsonPrimitive.long,
    )
    assertFalse("localGeneration" in encoded)
}

@Test
fun `pull adapter rejects document version mismatch without fallback`() {
    val wire = PortalProfilePreferenceSectionCanonicalDto(
        localProfileId = "profile-a",
        section = "VBT",
        documentVersion = 2,
        serverRevision = 3,
        serverUpdatedAt = "2026-07-11T12:00:00.000Z",
        payload = buildJsonObject {
            put("vbtEnabled", true)
            putJsonObject("preferences") { put("version", 1) }
        },
    )

    val invalid = assertIs<ProfilePreferenceCanonicalDecodeResult.Invalid>(
        PortalPullAdapter.toCanonicalProfilePreferenceSection(wire),
    )
    assertEquals(ProfilePreferenceSyncIssueReason.UNSUPPORTED_DOCUMENT_VERSION.name, invalid.reason)
}

private fun coreCanonicalWire(
    revision: Long = 3,
    localProfileId: String = "profile-a",
    serverUpdatedAt: String = "2026-07-11T12:00:00.000Z",
) = PortalProfilePreferenceSectionCanonicalDto(
    localProfileId = localProfileId,
    section = "CORE",
    documentVersion = 1,
    serverRevision = revision,
    serverUpdatedAt = serverUpdatedAt,
    payload = buildJsonObject {
        put("bodyWeightKg", 82.5)
        put("weightUnit", "KG")
        put("weightIncrement", 0.5)
    },
)

@Test
fun `pull adapter enforces exact canonical revision interval`() {
    assertIs<ProfilePreferenceCanonicalDecodeResult.Valid>(
        PortalPullAdapter.toCanonicalProfilePreferenceSection(
            coreCanonicalWire(revision = 9_007_199_254_740_991L),
        ),
    )

    listOf(-1L, 9_007_199_254_740_992L).forEach { revision ->
        val invalid = assertIs<ProfilePreferenceCanonicalDecodeResult.Invalid>(
            PortalPullAdapter.toCanonicalProfilePreferenceSection(
                coreCanonicalWire(revision = revision),
            ),
        )
        assertEquals(ProfilePreferenceSyncIssueReason.INVALID_CANONICAL_REVISION.name, invalid.reason)
    }
}

@Test
fun `pull adapter rejects wrapper identity timestamp and section with category only`() {
    val sentinel = "SECRET_REMOTE_SENTINEL"
    val cases = listOf(
        coreCanonicalWire(localProfileId = " ") to
            ProfilePreferenceSyncIssueReason.INVALID_PROFILE_ID,
        coreCanonicalWire(localProfileId = "$sentinel\u0000") to
            ProfilePreferenceSyncIssueReason.INVALID_PROFILE_ID,
        coreCanonicalWire(serverUpdatedAt = "+10000-01-01T00:00:00.000Z") to
            ProfilePreferenceSyncIssueReason.INVALID_CANONICAL_TIMESTAMP,
        coreCanonicalWire().copy(section = sentinel) to
            ProfilePreferenceSyncIssueReason.UNSUPPORTED_SECTION,
    )

    cases.forEach { (wire, expectedReason) ->
        val invalid = assertIs<ProfilePreferenceCanonicalDecodeResult.Invalid>(
            PortalPullAdapter.toCanonicalProfilePreferenceSection(wire),
        )
        assertEquals(expectedReason.name, invalid.reason)
        assertFalse(sentinel in invalid.reason)
    }
}
```

Place the push test in `PortalSyncAdapterProfilePreferencesTest.kt` and every pull fixture/test in `PortalPullAdapterProfilePreferencesTest.kt`, with the required Kotlin test and JSON imports. The `-1/MAX/MAX+1` table is mandatory: Task 7 must never receive an unsafe revision in a nominally valid canonical.

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
    assertEquals(1L, plan.unsyncable.single().localGeneration)
    assertEquals(ProfilePreferenceSyncIssueReason.SECTION_TOO_LARGE.name, plan.unsyncable.single().reason)
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
        val bytes = encodePortalSyncPayload(chunk.payload).rawBytes.size
        assertTrue(bytes <= MAX_PROFILE_PREFERENCE_REQUEST_BYTES)
    }
}

@Test
fun `shared raw byte goldens pin scanner spans and inclusive boundaries`() {
    val recipes = byteGoldenRecipes()
    assertEquals(1, recipes.version)

    recipes.sectionTargetBytes.forEach { target ->
        val sectionRaw = fillAsciiPadding(
            recipes.sectionRawTemplate,
            recipes.paddingMarker,
            target,
        )
        val completeRaw = "{\"profilePreferenceSections\":[$sectionRaw]}"
        val span = scanProfilePreferenceElementSpans(completeRaw).single()
        assertEquals(sectionRaw, completeRaw.substring(span.start, span.endExclusive))
        assertEquals(
            target,
            completeRaw.substring(span.start, span.endExclusive).encodeToByteArray().size,
        )
        assertEquals(
            target == 262_145,
            completeRaw.substring(span.start, span.endExclusive).encodeToByteArray().size >
                MAX_PROFILE_PREFERENCE_SECTION_BYTES,
        )
        assertTrue("\"weightKg\":20.0" in sectionRaw)
        assertTrue("\"createdAt\":-1e3" in sectionRaw)
        assertTrue("π界🙂" in sectionRaw)
        assertTrue("\\\"" in sectionRaw)
        assertTrue("\\\\" in sectionRaw)
        val decodedMutation = PortalWireJson.decodeFromString(
            PortalProfilePreferenceSectionMutationDto.serializer(),
            sectionRaw,
        )
        assertEquals("profile-a", decodedMutation.localProfileId)
        assertEquals("RACK", decodedMutation.section)
        assertEquals(0L, decodedMutation.baseRevision)
        assertTrue(decodedMutation.payload.getValue("items") is JsonArray)
        PortalWireJson.parseToJsonElement(completeRaw)
    }

    recipes.requestTargetBytes.forEach { target ->
        val sectionRaw = recipes.sectionRawTemplate.replace(recipes.paddingMarker, "x")
        val requestTemplate = recipes.requestRawTemplate.replace(
            recipes.sectionMarker,
            sectionRaw,
        )
        val completeRaw = fillAsciiPadding(
            requestTemplate,
            recipes.paddingMarker,
            target,
        )
        assertEquals(target, completeRaw.encodeToByteArray().size)
        assertEquals(
            target == 524_289,
            completeRaw.encodeToByteArray().size > MAX_PROFILE_PREFERENCE_REQUEST_BYTES,
        )
        val span = scanProfilePreferenceElementSpans(completeRaw).single()
        assertEquals(sectionRaw, completeRaw.substring(span.start, span.endExclusive))
        val decodedRequest = PortalWireJson.decodeFromString(
            PortalSyncPayload.serializer(),
            completeRaw,
        )
        assertEquals("golden-device", decodedRequest.deviceId)
        assertEquals("profile-a", decodedRequest.profileId)
        assertEquals(1, decodedRequest.profilePreferenceSections?.size)
        assertEquals("RACK", decodedRequest.profilePreferenceSections?.single()?.section)
    }
}
```

The shared raw artifact is a scanner/decoder oracle. Because the production serializer uses `encodeDefaults = true`, add separate tests over the real `encodePortalSyncPayload` output; do not claim that the hand-authored request template is the serializer's byte output:

```kotlin
@Test
fun `planner enforces actual reencoded section boundaries`() {
    val recipes = byteGoldenRecipes()
    recipes.sectionTargetBytes.forEach { target ->
        val raw = fillAsciiPadding(
            recipes.sectionRawTemplate,
            recipes.paddingMarker,
            target,
        )
        val wire = PortalWireJson.decodeFromString(
            PortalProfilePreferenceSectionMutationDto.serializer(),
            raw,
        )
        val section = ProfilePreferenceSectionName.valueOf(wire.section)
        val prepared = PreparedProfilePreferenceMutation(
            wire = wire,
            key = ProfilePreferenceSectionKey(wire.localProfileId, section),
            sentLocalGeneration = 1,
        )
        val reencoded = encodePortalSyncPayload(minimalPayload(listOf(prepared)))
        val actualBytes = reencoded.preferenceElementByteCount(
            reencoded.preferenceElementSpans.single(),
        )
        assertEquals(target, actualBytes)

        val plan = planProfilePreferencePushChunks(basePayload(), listOf(prepared))
        if (target <= MAX_PROFILE_PREFERENCE_SECTION_BYTES) {
            assertTrue(plan.unsyncable.isEmpty())
            assertEquals(listOf(prepared.key), plan.chunks.single().ledger.keys.toList())
        } else {
            assertTrue(plan.chunks.isEmpty())
            assertEquals(
                ProfilePreferenceSyncIssueReason.SECTION_TOO_LARGE.name,
                plan.unsyncable.single().reason,
            )
        }
    }
}

@Test
fun `actual encoder request boundary is inclusive and overflow is split`() {
    listOf(MAX_PROFILE_PREFERENCE_REQUEST_BYTES, MAX_PROFILE_PREFERENCE_REQUEST_BYTES + 1)
        .forEach { target ->
            val mutations = requestSizedMutations(target)
            val encoded = encodePortalSyncPayload(minimalPayload(mutations))
            assertEquals(target, encoded.rawBytes.size)
            assertTrue(
                encoded.preferenceElementSpans.all { span ->
                    encoded.preferenceElementByteCount(span) <=
                        MAX_PROFILE_PREFERENCE_SECTION_BYTES
                },
            )

            val plan = planProfilePreferencePushChunks(basePayload(), mutations)
            assertTrue(plan.unsyncable.isEmpty())
            if (target == MAX_PROFILE_PREFERENCE_REQUEST_BYTES) {
                assertEquals(1, plan.chunks.size)
                assertEquals(target, encodePortalSyncPayload(plan.chunks.single().payload).rawBytes.size)
            } else {
                assertTrue(plan.chunks.size > 1)
            }
            plan.chunks.forEach { chunk ->
                assertTrue(
                    encodePortalSyncPayload(chunk.payload).rawBytes.size <=
                        MAX_PROFILE_PREFERENCE_REQUEST_BYTES,
                )
                assertEquals(
                    chunk.payload.profilePreferenceSections.orEmpty().size,
                    chunk.ledger.size,
                )
            }
        }
}

@Test
fun `one item request overflow is diagnosed and never emitted`() {
    val base = PortalSyncPayload(
        deviceId = "x".repeat(MAX_PROFILE_PREFERENCE_REQUEST_BYTES),
        lastSync = 0,
    )
    val mutation = preparedMutation("profile-a", ProfilePreferenceSectionName.CORE, 32)

    val plan = planProfilePreferencePushChunks(base, listOf(mutation))

    assertTrue(plan.chunks.isEmpty())
    assertEquals(ProfilePreferenceSyncIssueReason.REQUEST_TOO_LARGE.name, plan.unsyncable.single().reason)
}

@Test
fun `planner excludes every duplicate key and preserves unique sibling`() {
    val first = preparedMutation("profile-a", ProfilePreferenceSectionName.CORE, 32)
    val second = first.copy(sentLocalGeneration = 2)
    val sibling = preparedMutation("profile-b", ProfilePreferenceSectionName.RACK, 32)

    val plan = planProfilePreferencePushChunks(basePayload(), listOf(second, sibling, first))

    val duplicateIssues = plan.unsyncable.filter { it.key == first.key }
    assertEquals(listOf(1L, 2L), duplicateIssues.map { it.localGeneration })
    assertTrue(
        duplicateIssues.all {
            it.reason == ProfilePreferenceSyncIssueReason.DUPLICATE_SECTION.name
        },
    )
    assertEquals(listOf(sibling.key), plan.chunks.single().ledger.keys.toList())
}

@Test
fun `planner is permutation deterministic and strips profile metadata`() {
    val sentinel = "SECRET_PROFILE_SENTINEL"
    val base = basePayload().copy(
        profileId = "$sentinel\u0000",
        profileName = sentinel,
        allProfiles = listOf(LocalProfileDto(sentinel, sentinel, 0)),
    )
    val mutations = List(6) { index ->
        preparedMutation("profile-$index", ProfilePreferenceSectionName.RACK, 100_000)
    }

    val forward = planProfilePreferencePushChunks(base, mutations)
    val reversed = planProfilePreferencePushChunks(base, mutations.reversed())

    assertEquals(
        forward.chunks.map { encodePortalSyncPayload(it.payload).raw },
        reversed.chunks.map { encodePortalSyncPayload(it.payload).raw },
    )
    assertEquals(
        forward.chunks.map { it.ledger.entries.toList() },
        reversed.chunks.map { it.ledger.entries.toList() },
    )
    forward.chunks.forEach { chunk ->
        assertNull(chunk.payload.profileId)
        assertNull(chunk.payload.profileName)
        assertNull(chunk.payload.allProfiles)
        assertFalse(sentinel in encodePortalSyncPayload(chunk.payload).raw)
        assertEquals(chunk.payload.profilePreferenceSections.orEmpty().size, chunk.ledger.size)
    }
}

@Test
fun `scanner decodes escaped top level key and ignores nested decoy`() {
    val escapedKey = "\\u0070rofilePreferenceSections"
    val element = """{"text":"brackets ] } , [ { and quote \" and slash \\"}"""
    val raw = """{"nested":{"profilePreferenceSections":[0]},"$escapedKey":[$element]}"""

    val span = scanProfilePreferenceElementSpans(raw).single()

    assertEquals(element, raw.substring(span.start, span.endExclusive))
}

@Test
fun `scanner rejects escaped duplicate and malformed structure with fixed messages`() {
    val escapedKey = "\\u0070rofilePreferenceSections"
    val duplicate = """{"profilePreferenceSections":[],"$escapedKey":[]}"""
    assertEquals(
        "DUPLICATE_PROFILE_PREFERENCE_SECTIONS",
        assertFailsWith<IllegalArgumentException> {
            scanProfilePreferenceElementSpans(duplicate)
        }.message,
    )

    val sentinel = "SECRET_SCAN_SENTINEL"
    mapOf(
        "[]" to "INVALID_JSON_ROOT",
        """{"profilePreferenceSections":[}""" to "INVALID_JSON_STRUCTURE",
        """{"profilePreferenceSections":[]} $sentinel""" to "TRAILING_JSON_DATA",
    ).forEach { (raw, expected) ->
        val error = assertFailsWith<IllegalArgumentException> {
            scanProfilePreferenceElementSpans(raw)
        }
        assertEquals(expected, error.message)
        assertFalse(sentinel in error.message.orEmpty())
    }
    assertTrue(scanProfilePreferenceElementSpans("""{"other":[]}""").isEmpty())
    assertTrue(scanProfilePreferenceElementSpans("""{"profilePreferenceSections":[]}""").isEmpty())
}
```

Import `readProjectFile`, `kotlinx.serialization.Serializable`, `JsonArray`, `assertFailsWith`, `assertNotNull`, and `assertNull`. Add the serializable `ProfilePreferenceByteGoldenRecipes` shape with fields matching the exact Task 2 artifact, plus `fillAsciiPadding(template, marker, targetBytes)`. The helper requires exactly one marker, removes it to measure the UTF-8 fixed bytes, requires a nonnegative padding count, inserts only ASCII `x`, and asserts the result is exactly `targetBytes`. Add these deterministic mutation helpers in the same test file:

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

private fun byteGoldenRecipes() =
    PortalWireJson.decodeFromString<ProfilePreferenceByteGoldenRecipes>(
        assertNotNull(
            readProjectFile("docs/backend-handoff/profile-preference-byte-goldens.json"),
        ),
    )

private fun minimalPayload(
    mutations: List<PreparedProfilePreferenceMutation>,
) = PortalSyncPayload(
    deviceId = "device",
    platform = "android",
    lastSync = 0,
    profilePreferenceSections = mutations.map { it.wire },
)

private fun requestSizedMutations(targetBytes: Int): List<PreparedProfilePreferenceMutation> {
    val seeds = List(3) { index ->
        preparedMutation("profile-$index", ProfilePreferenceSectionName.RACK, payloadChars = 0)
    }
    val fixedBytes = encodePortalSyncPayload(minimalPayload(seeds)).rawBytes.size
    val paddingBytes = targetBytes - fixedBytes
    require(paddingBytes >= 0)
    return seeds.mapIndexed { index, seed ->
        val padding = paddingBytes / seeds.size +
            if (index < paddingBytes % seeds.size) 1 else 0
        seed.copy(
            wire = seed.wire.copy(
                payload = buildJsonObject { put("padding", "x".repeat(padding)) },
            ),
        )
    }
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

Expected: FAIL because Task 4's `PortalWireJson` has no raw encoder/scanner yet and the adapter methods, constants, and planner are absent.

- [ ] **Step 4: Extend the shared wire JSON boundary with raw spans**

Modify Task 4's `PortalWireJson.kt`; keep its existing `PortalWireJson` configuration byte-for-byte and add this import:

```kotlin
import kotlinx.serialization.encodeToString
```

Then add one raw-wire encoding boundary used by both planning and transport:

```kotlin
internal data class RawJsonSpan(val start: Int, val endExclusive: Int)

internal data class EncodedPortalSyncPayload(
    val raw: String,
    val rawBytes: ByteArray,
    val preferenceElementSpans: List<RawJsonSpan>,
) {
    fun preferenceElementByteCount(span: RawJsonSpan): Int =
        raw.substring(span.start, span.endExclusive).encodeToByteArray().size
}

internal fun encodePortalSyncPayload(payload: PortalSyncPayload): EncodedPortalSyncPayload {
    val raw = PortalWireJson.encodeToString(PortalSyncPayload.serializer(), payload)
    val spans = scanProfilePreferenceElementSpans(raw)
    check(spans.size == payload.profilePreferenceSections.orEmpty().size) {
        "Serialized profile preference span count mismatch"
    }
    return EncodedPortalSyncPayload(raw, raw.encodeToByteArray(), spans)
}
```

Implement `scanProfilePreferenceElementSpans(raw)` as a small index scanner over the complete encoded root object. Decode every valid JSON escape in top-level property names before comparing them, require at most one decoded `profilePreferenceSections` key, recursively skip JSON values while tracking object/array nesting, and scan strings by honoring every backslash escape so quotes, commas, and brackets inside strings never change structure. A nested property with the same name is a decoy, not the top-level field. For the preference array, pin each element's start after leading JSON whitespace and its exclusive end immediately after the value but before separator whitespace/comma/closing bracket. Require matching delimiters, no trailing non-whitespace, and an array span count equal to the typed list.

All scanner failures are `IllegalArgumentException`s with one of these fixed messages and no raw substring, property value, or exception message appended: `INVALID_JSON_ROOT`, `INVALID_JSON_STRUCTURE`, `INVALID_PROFILE_PREFERENCE_ARRAY`, `DUPLICATE_PROFILE_PREFERENCE_SECTIONS`, or `TRAILING_JSON_DATA`. Measure only `raw.substring(start, endExclusive).encodeToByteArray()`; neither this helper nor its callers may reconstruct an element with `JSON.stringify`, `toString`, or a second serialization. The shared decimal/exponent/escape/multibyte goldens and the escaped-key/nested-decoy tests are the executable scanner oracle.

- [ ] **Step 5: Implement the push and pull adapters**

First extend Task 4's category enum in `ProfilePreferenceSyncCodec.kt`; this is the complete enum after Task 5. Keep the Task 4 companion's `MIN_RFC3339_EPOCH_MILLIS` and `MAX_RFC3339_EPOCH_MILLIS` constants at `-62_135_596_800_000L` and `253_402_300_799_999L` so push and pull share the same four-digit-year interval:

```kotlin
internal enum class ProfilePreferenceSyncIssueReason {
    INVALID_PROFILE_ID,
    INVALID_LOCAL_DOCUMENT,
    INVALID_CLIENT_MODIFIED_AT,
    UNREPRESENTABLE_JSON_INTEGER,
    INVALID_INT32,
    INVALID_TEXT_TREE,
    LOCAL_ONLY_WIRE_KEY,
    UNSUPPORTED_SECTION,
    UNSUPPORTED_DOCUMENT_VERSION,
    INVALID_CANONICAL_PAYLOAD,
    INVALID_CANONICAL_REVISION,
    INVALID_CANONICAL_TIMESTAMP,
    SECTION_TOO_LARGE,
    REQUEST_TOO_LARGE,
    DUPLICATE_SECTION,
}
```

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

Add this pull mapper to `PortalPullAdapter`. Parse the wrapper identity and timestamp first, construct one candidate, and then call Task 4's stronger `decodeCanonical` boundary so document, payload, and exact JSON revision checks cannot drift. The repository repeats the same decode before SQL mutation as defense in depth:

```kotlin
fun toCanonicalProfilePreferenceSection(
    dto: PortalProfilePreferenceSectionCanonicalDto,
): ProfilePreferenceCanonicalDecodeResult {
    fun invalid(reason: ProfilePreferenceSyncIssueReason) =
        ProfilePreferenceCanonicalDecodeResult.Invalid(
            localProfileId = dto.localProfileId,
            section = dto.section,
            reason = reason.name,
        )

    if (dto.localProfileId.isBlank() ||
        profilePreferenceWireSafetyViolation(JsonPrimitive(dto.localProfileId)) != null
    ) {
        return invalid(ProfilePreferenceSyncIssueReason.INVALID_PROFILE_ID)
    }
    val section = ProfilePreferenceSectionName.entries.firstOrNull { it.name == dto.section }
        ?: return invalid(ProfilePreferenceSyncIssueReason.UNSUPPORTED_SECTION)
    val updatedAt = runCatching {
        kotlin.time.Instant.parse(dto.serverUpdatedAt).toEpochMilliseconds()
    }.getOrNull()
    if (updatedAt == null ||
        updatedAt !in ProfilePreferenceSyncCodec.MIN_RFC3339_EPOCH_MILLIS..
            ProfilePreferenceSyncCodec.MAX_RFC3339_EPOCH_MILLIS
    ) {
        return invalid(ProfilePreferenceSyncIssueReason.INVALID_CANONICAL_TIMESTAMP)
    }

    val candidate = CanonicalProfilePreferenceSection(
        key = ProfilePreferenceSectionKey(dto.localProfileId, section),
        documentVersion = dto.documentVersion,
        serverRevision = dto.serverRevision,
        serverUpdatedAtEpochMs = updatedAt,
        payload = dto.payload,
    )
    return when (val decoded = ProfilePreferenceSyncCodec().decodeCanonical(candidate)) {
        is ProfilePreferenceCanonicalColumnsResult.Invalid -> invalid(decoded.reason)
        is ProfilePreferenceCanonicalColumnsResult.Valid ->
            ProfilePreferenceCanonicalDecodeResult.Valid(candidate)
    }
}
```

Import `kotlinx.serialization.json.JsonPrimitive` in `PortalPullAdapter.kt`. Do not catch or retain decoder exception messages; every invalid result carries only an enum name.

- [ ] **Step 6: Implement deterministic section/request chunking**

Create `ProfilePreferenceSyncPlanner.kt`:

```kotlin
package com.devil.phoenixproject.data.sync

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
    fun preferencePayload(items: List<PreparedProfilePreferenceMutation>) = PortalSyncPayload(
        deviceId = basePayload.deviceId,
        platform = basePayload.platform,
        lastSync = basePayload.lastSync,
        profilePreferenceSections = items.map { it.wire },
    )

    val sorted = mutations.sortedWith(
        compareBy<PreparedProfilePreferenceMutation>(
            { it.key.localProfileId },
            { it.key.section.ordinal },
            { it.sentLocalGeneration },
        ),
    )
    val duplicateKeys = sorted.groupingBy { it.key }
        .eachCount()
        .filterValues { it > 1 }
        .keys
    val valid = mutableListOf<PreparedProfilePreferenceMutation>()
    val issues = mutableListOf<ProfilePreferenceSyncIssue>()

    sorted.forEach { mutation ->
        if (mutation.key in duplicateKeys) {
            issues += ProfilePreferenceSyncIssue(
                key = mutation.key,
                localGeneration = mutation.sentLocalGeneration,
                reason = ProfilePreferenceSyncIssueReason.DUPLICATE_SECTION.name,
            )
            return@forEach
        }

        val oneElement = encodePortalSyncPayload(preferencePayload(listOf(mutation)))
        val sectionBytes = oneElement.preferenceElementByteCount(
            oneElement.preferenceElementSpans.single(),
        )
        val reason = when {
            sectionBytes > MAX_PROFILE_PREFERENCE_SECTION_BYTES ->
                ProfilePreferenceSyncIssueReason.SECTION_TOO_LARGE
            oneElement.rawBytes.size > MAX_PROFILE_PREFERENCE_REQUEST_BYTES ->
                ProfilePreferenceSyncIssueReason.REQUEST_TOO_LARGE
            else -> null
        }
        if (reason != null) {
            issues += ProfilePreferenceSyncIssue(
                key = mutation.key,
                localGeneration = mutation.sentLocalGeneration,
                reason = reason.name,
            )
            return@forEach
        }
        valid += mutation
    }

    val chunks = mutableListOf<ProfilePreferencePushChunk>()
    var current = mutableListOf<PreparedProfilePreferenceMutation>()
    fun emit() {
        if (current.isEmpty()) return
        val payload = preferencePayload(current)
        val encoded = encodePortalSyncPayload(payload)
        check(encoded.rawBytes.size <= MAX_PROFILE_PREFERENCE_REQUEST_BYTES) {
            "PROFILE_PREFERENCE_PLANNER_OVERSIZED_REQUEST"
        }
        val ledger = current.associate { it.key to it.sentLocalGeneration }
        check(ledger.size == current.size &&
            ledger.size == payload.profilePreferenceSections.orEmpty().size
        ) {
            "PROFILE_PREFERENCE_LEDGER_CARDINALITY_MISMATCH"
        }
        chunks += ProfilePreferencePushChunk(
            payload = payload,
            ledger = ledger,
        )
        current = mutableListOf()
    }

    valid.forEach { mutation ->
        val candidate = current + mutation
        val bytes = encodePortalSyncPayload(preferencePayload(candidate)).rawBytes.size
        if (bytes > MAX_PROFILE_PREFERENCE_REQUEST_BYTES) emit()
        current += mutation
    }
    emit()

    check(
        chunks.all {
            encodePortalSyncPayload(it.payload).rawBytes.size <=
                MAX_PROFILE_PREFERENCE_REQUEST_BYTES
        },
    ) {
        "PROFILE_PREFERENCE_PLANNER_OVERSIZED_REQUEST"
    }
    return ProfilePreferencePushPlan(chunks = chunks, unsyncable = issues)
}
```

The one-item full-request check occurs before greedy chunking, so `emit()` may rely on every first item fitting. Duplicate keys never enter a request or ledger, and every duplicate occurrence receives its own generation-specific `DUPLICATE_SECTION` issue while valid siblings continue. `preferencePayload` intentionally does not copy `profileId`, `profileName`, `allProfiles`, or any ordinary entity data from `basePayload`.

- [ ] **Step 7: Run the focused adapter/planner tests and verify they pass**

Run:

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "*PortalSyncAdapterProfilePreferencesTest*" --tests "*PortalPullAdapterProfilePreferencesTest*" --tests "*ProfilePreferenceSyncPlannerTest*" --tests "*ProfilePreferenceSyncDtosTest*" --tests "*PortalSyncAdapterTest*" --tests "*PortalPullAdapterTest*" --tests "*SqlDelightProfilePreferenceSyncRepositoryTest*" -Pskip.supabase.check=true
```

Expected: PASS, including Task 3 wire DTO guards, existing adapter behavior, and Task 4's duplicate validation at the persistence boundary.

- [ ] **Step 8: Commit adapters and deterministic chunk planning**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncCodec.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalWireJson.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapter.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalPullAdapter.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncPlanner.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapterProfilePreferencesTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalPullAdapterProfilePreferencesTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/ProfilePreferenceSyncPlannerTest.kt
git commit -m "feat(sync): map and chunk profile preference sections"
```

---

### Task 6: Enforce Transport Limits and Capture Multi-Request Tests

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakePortalApiClient.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalApiClientProfilePreferenceLimitsTest.kt`

**Interfaces:**
- Consumes: Task 5's `PortalWireJson`, `encodePortalSyncPayload`, exact `rawBytes`, spans, and size constants.
- Produces: transport defense in depth that sizes and sends the same never-mutated UTF-8 byte array, plus a fake capable of metadata-first/chunk/legacy-response tests.

- [ ] **Step 1: Write failing transport limit tests**

Create `PortalApiClientProfilePreferenceLimitsTest.kt`:

```kotlin
package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.testutil.readProjectFile
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
        assertEquals("SECTION_TOO_LARGE: cap=262144", error.message)
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
        val encoded = encodePortalSyncPayload(payload)
        assertTrue(
            encoded.preferenceElementSpans.all { span ->
                encoded.preferenceElementByteCount(span) <= MAX_PROFILE_PREFERENCE_SECTION_BYTES
            },
        )
        assertTrue(
            encoded.rawBytes.size > MAX_PROFILE_PREFERENCE_REQUEST_BYTES,
        )

        val error = client.pushPortalPayload(payload).exceptionOrNull()

        assertIs<PortalApiException>(error)
        assertEquals(413, error.statusCode)
        assertEquals("REQUEST_TOO_LARGE: cap=524288", error.message)
    }

    @Test
    fun `transport sends exact shared bytes used for both limits`() {
        val source = assertNotNull(
            readProjectFile(
                "src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt",
            ),
        )

        assertTrue("json(PortalWireJson)" in source)
        assertTrue("val encoded = encodePortalSyncPayload(payload)" in source)
        assertTrue("setBody(encoded.rawBytes)" in source)
        assertFalse(Regex("""setBody[(]encoded[.]raw[)]""").containsMatchIn(source))
        assertFalse(Regex("""private\s+val\s+json\s*=\s*Json""").containsMatchIn(source))
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

Remove the `kotlinx.serialization.json.Json` import and `PortalApiClient`'s private `Json` construction. Configure response decoding with the same shared instance:

```kotlin
install(ContentNegotiation) {
    json(PortalWireJson)
}
```

Inside `pushPortalPayload`, call `encodePortalSyncPayload` exactly once and use its `rawBytes` for every limit and for the request body. Before the existing overall byte check, add:

```kotlin
val encoded = encodePortalSyncPayload(payload)
if (payload.profilePreferenceSections != null) {
    encoded.preferenceElementSpans.forEach { span ->
        if (encoded.preferenceElementByteCount(span) > MAX_PROFILE_PREFERENCE_SECTION_BYTES) {
            return Result.failure(
                PortalApiException(
                    "SECTION_TOO_LARGE: cap=$MAX_PROFILE_PREFERENCE_SECTION_BYTES",
                    statusCode = 413,
                ),
            )
        }
    }
    if (encoded.rawBytes.size > MAX_PROFILE_PREFERENCE_REQUEST_BYTES) {
        return Result.failure(
            PortalApiException(
                "REQUEST_TOO_LARGE: cap=$MAX_PROFILE_PREFERENCE_REQUEST_BYTES",
                statusCode = 413,
            ),
        )
    }
}
```

Retain the existing `MAX_PAYLOAD_BYTES` check against `encoded.rawBytes` after this block. Replace every remaining `serialized` reference in this method and send the exact already-counted bytes:

```kotlin
httpClient.post("${supabaseConfig.url}/functions/v1/mobile-sync-push") {
    bearerAuth(token)
    header("apikey", supabaseConfig.anonKey)
    header("Content-Type", "application/json")
    setBody(encoded.rawBytes)
}
```

Do not mutate `encoded.rawBytes`, call `encodeToByteArray` again, or use the encoded `String` as the body: the same byte array is measured by the per-section/full-request/overall guards and written to HTTP. A non-null preference field, including an empty array, always applies 512 KiB to the complete raw `PortalSyncPayload`; without that field only the existing 9,500,000-byte overall cap applies. Preference-specific errors are fixed categories plus the public cap only; they never include profile identity, section values, or measured user-data sizes.

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
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/SyncManagerTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalTokenRefreshTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalPullPaginationTest.kt`

**Interfaces:**
- Consumes: Task 4's internal persistence adapter, the data-foundation migration gate, Task 5's planner, and Task 6's transport/fakes.
- Produces: metadata-first minimal preference-only pushes, race-safe canonical/rejection application, and reusable sanitized diagnostic-line helpers that never expose raw profile ids, remote section strings, issue keys, exception messages, or payload fragments.

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
    val preferencePayload = harness.api.pushPayloads[1]
    assertNull(preferencePayload.profileId)
    assertNull(preferencePayload.profileName)
    assertNull(preferencePayload.allProfiles)
    assertTrue(preferencePayload.sessions.isEmpty())
    assertTrue(preferencePayload.routines.isEmpty())
    assertTrue(preferencePayload.personalRecords.isEmpty())
    assertEquals(1, preferencePayload.profilePreferenceSections?.size)
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

private fun workoutCanonical(revision: Long) = PortalProfilePreferenceSectionCanonicalDto(
    localProfileId = "profile-a",
    section = "WORKOUT",
    documentVersion = 1,
    serverRevision = revision,
    serverUpdatedAt = "2026-07-11T12:00:00Z",
    payload = workoutSection(generation = 1).payload,
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
    assertEquals(8L, outcome.sentLocalGeneration)
    assertEquals(4L, outcome.serverRevision)
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
    assertEquals(11L, outcome.sentLocalGeneration)
    assertEquals("REVISION_CONFLICT", outcome.rejectionReason)
    assertEquals(6L, outcome.canonical?.serverRevision)
}

@Test
fun `duplicate response cardinality invalidates only that ledger key`() {
    val coreKey = coreSection(generation = 11).key
    val workoutKey = workoutSection(generation = 12).key
    val ledger = mapOf(coreKey to 11L, workoutKey to 12L)
    val coreRejection = ProfilePreferenceSectionRejectionDto(
        localProfileId = "profile-a",
        section = "CORE",
        serverRevision = 7,
        reason = "REVISION_CONFLICT",
        canonicalSection = coreCanonical(revision = 7),
    )
    val cases = listOf(
        successResponse(
            profilePreferencesAccepted = true,
            canonicalProfilePreferenceSections = listOf(
                coreCanonical(7),
                workoutCanonical(4),
            ),
            profilePreferenceRejections = listOf(coreRejection),
        ),
        successResponse(
            profilePreferencesAccepted = true,
            canonicalProfilePreferenceSections = listOf(
                coreCanonical(7),
                coreCanonical(8),
                workoutCanonical(4),
            ),
        ),
        successResponse(
            profilePreferencesAccepted = true,
            canonicalProfilePreferenceSections = listOf(workoutCanonical(4)),
            profilePreferenceRejections = listOf(coreRejection, coreRejection),
        ),
    )

    cases.forEach { response ->
        val outcomes = buildProfilePreferencePushOutcomes(response, ledger)
        assertEquals(listOf(workoutKey), outcomes.map { it.key })
        assertEquals(12L, outcomes.single().sentLocalGeneration)
        assertEquals(4L, outcomes.single().serverRevision)
    }
}

@Test
fun `rejection canonical key or revision mismatch invalidates only that ledger key`() {
    val coreKey = coreSection(generation = 11).key
    val workoutKey = workoutSection(generation = 12).key
    val mismatchedRejections = listOf(
        ProfilePreferenceSectionRejectionDto(
            localProfileId = "profile-a",
            section = "CORE",
            serverRevision = 8,
            reason = "REVISION_CONFLICT",
            canonicalSection = coreCanonical(revision = 7),
        ),
        ProfilePreferenceSectionRejectionDto(
            localProfileId = "profile-a",
            section = "CORE",
            serverRevision = 8,
            reason = "REVISION_CONFLICT",
            canonicalSection = workoutCanonical(revision = 8),
        ),
    )

    mismatchedRejections.forEach { rejection ->
        val outcomes = buildProfilePreferencePushOutcomes(
            successResponse(
                profilePreferencesAccepted = true,
                canonicalProfilePreferenceSections = listOf(workoutCanonical(4)),
                profilePreferenceRejections = listOf(rejection),
            ),
            ledger = mapOf(coreKey to 11L, workoutKey to 12L),
        )

        assertEquals(listOf(workoutKey), outcomes.map { it.key })
        assertEquals(12L, outcomes.single().sentLocalGeneration)
        assertEquals(4L, outcomes.single().serverRevision)
    }
}

@Test
fun `profile preference diagnostics never expose raw identities sections or messages`() {
    val sentinel = "SECRET_PROFILE_DIAGNOSTIC_SENTINEL"
    val key = ProfilePreferenceSectionKey(
        localProfileId = "$sentinel\u0000",
        section = ProfilePreferenceSectionName.CORE,
    )
    val lines = listOf(
        profilePreferenceIssueLogLine(
            ProfilePreferenceSyncIssue(
                key = key,
                localGeneration = 9,
                reason = ProfilePreferenceSyncIssueReason.INVALID_PROFILE_ID.name,
            ),
        ),
        profilePreferenceDuplicateResultLogLine(key),
        profilePreferenceInvalidCanonicalLogLine(
            ProfilePreferenceCanonicalDecodeResult.Invalid(
                localProfileId = sentinel,
                section = sentinel,
                reason = sentinel,
            ),
        ),
        profilePreferenceChunkFailureLogLine(
            PortalApiException(sentinel, statusCode = 503),
        ),
    )

    assertEquals(
        listOf(
            "PROFILE_PREFERENCE_NOT_SENT section=CORE reason=INVALID_PROFILE_ID",
            "PROFILE_PREFERENCE_DUPLICATE_RESULT section=CORE",
            "PROFILE_PREFERENCE_INVALID_CANONICAL reason=INVALID_PROFILE_PREFERENCE_DIAGNOSTIC",
            "PROFILE_PREFERENCE_CHUNK_FAILED status=503",
        ),
        lines,
    )
    lines.forEach { line ->
        assertFalse(sentinel in line)
        assertFalse('\u0000' in line)
    }
}
```

Import `assertFalse` for the sentinel diagnostic test. The test passes attacker-controlled text through every diagnostic helper, including the later pull helper, without relying on a logging backend.

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

Add these module-internal pure diagnostic helpers in `SyncManager.kt`; production logging in Tasks 7 and 8 must call them rather than interpolating raw objects or throwable messages:

```kotlin
private val PROFILE_PREFERENCE_REASON_NAMES =
    ProfilePreferenceSyncIssueReason.entries.mapTo(mutableSetOf<String>()) { it.name }

private fun safeProfilePreferenceReason(reason: String): String =
    reason.takeIf { it in PROFILE_PREFERENCE_REASON_NAMES }
        ?: "INVALID_PROFILE_PREFERENCE_DIAGNOSTIC"

internal fun profilePreferenceIssueLogLine(issue: ProfilePreferenceSyncIssue): String =
    "PROFILE_PREFERENCE_NOT_SENT section=${issue.key.section.name} " +
        "reason=${safeProfilePreferenceReason(issue.reason)}"

internal fun profilePreferenceDuplicateResultLogLine(
    key: ProfilePreferenceSectionKey,
): String = "PROFILE_PREFERENCE_DUPLICATE_RESULT section=${key.section.name}"

internal fun profilePreferenceInvalidCanonicalLogLine(
    invalid: ProfilePreferenceCanonicalDecodeResult.Invalid,
): String = "PROFILE_PREFERENCE_INVALID_CANONICAL " +
    "reason=${safeProfilePreferenceReason(invalid.reason)}"

internal fun profilePreferenceChunkFailureLogLine(error: Throwable?): String {
    val status = (error as? PortalApiException)?.statusCode?.toString() ?: "UNKNOWN"
    return "PROFILE_PREFERENCE_CHUNK_FAILED status=$status"
}
```

These helpers intentionally omit `localProfileId`, the raw remote `section`, `ProfilePreferenceSectionKey.toString()`, local generation, exception class/message, and all values. After all ordinary batches succeed and after `allProfiles` has been sent, call the preference loop with only `deviceId`, `platform`, and `lastSync`:

```kotlin
private suspend fun pushDirtyProfilePreferences(
    deviceId: String,
    platform: String,
    lastSync: Long,
) {
    if (!isProfilePreferenceMigrationReady()) return
    val snapshot = profilePreferenceSyncRepository.snapshotDirtySections()
    snapshot.unsyncable.forEach { issue ->
        Logger.w("SyncManager") { profilePreferenceIssueLogLine(issue) }
    }
    val prepared = snapshot.valid.map(PortalSyncAdapter::toPortalProfilePreferenceMutation)
    if (prepared.isEmpty()) return

    val base = PortalSyncPayload(
        deviceId = deviceId,
        platform = platform,
        lastSync = lastSync,
    )
    val plan = planProfilePreferencePushChunks(base, prepared)
    plan.unsyncable.forEach { issue ->
        Logger.w("SyncManager") { profilePreferenceIssueLogLine(issue) }
    }

    for (chunk in plan.chunks) {
        val result = pushPayloadWithRateLimit(chunk.payload)
        if (result.isFailure) {
            Logger.w("SyncManager") {
                profilePreferenceChunkFailureLogLine(result.exceptionOrNull())
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

Implement module-internal `buildProfilePreferencePushOutcomes` so the focused tests can call the pure response mapper directly. It:

- accepts only canonical/rejection keys present in the chunk ledger;
- requires exactly one total response entry per ledger key across both response arrays; canonical-plus-rejection, two canonicals, or two rejections for one key are invariant violations and produce no outcome for only that key;
- maps canonical DTOs through `PortalPullAdapter.toCanonicalProfilePreferenceSection`;
- rejects a rejection for only that key when its optional canonical has a different key or when `canonical.serverRevision != rejection.serverRevision`;
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

internal fun buildProfilePreferencePushOutcomes(
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
        if (decodedCanonical != null && (
                decodedCanonical.key != key ||
                    decodedCanonical.serverRevision != rejection.serverRevision
            )
        ) return@forEach
        candidates += PreferenceOutcomeCandidate(
            key = key,
            serverRevision = rejection.serverRevision,
            canonical = decodedCanonical,
            rejectionReason = rejection.reason,
        )
    }
    return candidates.groupBy(PreferenceOutcomeCandidate::key).mapNotNull { (key, entries) ->
        if (entries.size != 1 || responseCounts[key] != 1) {
            Logger.w("SyncManager") { profilePreferenceDuplicateResultLogLine(key) }
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

Call it after the ordinary batch loop and before external-activity/PR acknowledgement stamping:

```kotlin
pushDirtyProfilePreferences(
    deviceId = deviceId,
    platform = platform,
    lastSync = lastSync,
)
```

Do not pass the active profile id or name. Preserve and return the ordinary `lastResponse` so existing entity rejection handling remains intact.

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
.\gradlew.bat :shared:testAndroidHostTest --tests "*SyncManagerProfilePreferencesTest*" --tests "*PortalPushLimitsTest*" --tests "*SyncManagerTest*" --tests "*PortalTokenRefreshTest*" --tests "*PortalPullPaginationTest*" -Pskip.supabase.check=true
```

Expected: PASS.

- [ ] **Step 10: Commit metadata-first preference push**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/di/SyncModule.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeProfilePreferenceSyncRepository.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/SyncManagerProfilePreferencesTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalPushLimitsTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/SyncManagerTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalTokenRefreshTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalPullPaginationTest.kt
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
        Logger.w("SyncManager") { profilePreferenceInvalidCanonicalLogLine(invalid) }
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

Run the focused SQLDelight repository suite again after wiring pull; Task 7 adds no new persistence behavior. It must re-run Task 4's already-green contract: all-five matching-generation push application, all-five newer-generation preservation, row-owned LED/VBT columns, all-five clean+higher application and lower-revision rejection, dirty-pull preservation, normalized `80` versus `80.0` equality, true equal-revision repair, malformed-canonical valid-sibling isolation, canonical-null and canonical/outcome identity no-ops, safe canonical revision boundaries, category-only local dead letters for malformed JSON/unsafe integers/LED Int32/NUL/lone-surrogate/local-only keys, and generated-query unknown-profile no-create behavior.

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
- [ ] `supabase start` precedes `supabase db reset`, `supabase migration list`, `supabase test db`, function tests, lint, and advisors in a disposable/local environment.
- [ ] Supabase security and performance advisors were run; every finding is fixed or documented with a concrete disposition.
- [ ] Direct `anon` and `authenticated` table SELECT/INSERT/UPDATE/DELETE fail under normal grants.
- [ ] Temporary-grant RLS tests prove owner SELECT/INSERT/UPDATE/DELETE and cross-owner `user_id` reassignment rejection in an isolated transaction; they do not claim same-owner `local_profile_id` immutability.
- [ ] Edge tests prove the JWT-derived user can mutate only that user's composite-key rows; returned 400/401/403 Auth errors map to 401 while operational/malformed/thrown auth failures map to generic 503 with name-only logs and zero admin construction.
- [ ] Cross-user `localProfileId`, forged revisions/timestamps, unsupported versions, malformed payloads, exact raw section spans over 256 KiB, and complete original-byte requests over 512 KiB whenever the preference field is present are rejected using the shared cross-language goldens.
- [ ] Raw-handler tests reject BOM and malformed UTF-8 before admin, use original `Uint8Array.byteLength` for caps, and accept valid U+FFFD.
- [ ] Int32, Float32 overflow/nonzero-underflow, recursive PostgreSQL Unicode scalar, and strict timezone-bearing RFC3339 boundary matrices pass in push/RPC/pull paths.
- [ ] `BackendHandoffContractTest` contains a reviewed non-sentinel SHA-256 literal sealing the complete LF-normalized Edge handoff; appended/inverted executable mutations fail it.
- [ ] Same-section concurrent first writes produce one revision-1 winner and one canonical conflict.
- [ ] Different-section concurrent first writes both succeed at revision 1 without overwriting either section.
- [ ] Lost-ack retry converges through a canonical revision conflict.
- [ ] Pull emits preference canonicals only on its first page and retains the existing required `syncTime`.
- [ ] Database migration and both Edge Functions deploy before the mobile release begins preference sync.
