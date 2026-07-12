package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.data.auth.sha256
import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackendHandoffContractTest {
    private fun sql(): String = assertNotNull(
        readProjectFile("docs/backend-handoff/profile-preferences-supabase.sql"),
        "Supabase handoff SQL must be tracked in the mobile repository",
    )

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

    // Task 2 RED only. Replace this RHS after reviewing the complete amended handoff.
    private val EXPECTED_EDGE_HANDOFF_SHA256 =
        "6b624d45038dfbc63a6d3a80be7ece5d09bc4cb292bc1df5353ab8f10f61a666"

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

    private fun normalizedSql(): String = normalizedSql(sql())

    private fun normalizedSql(value: String): String {
        val statements = normalizedTopLevelStatements(value)
        return if (statements.isEmpty()) {
            ""
        } else {
            statements.joinToString(separator = "; ", postfix = ";")
        }
    }

    private fun normalizedTopLevelStatements(value: String): List<String> =
        topLevelStatements(value).map { statement ->
            normalizeStatement(statement)
        }

    private fun normalizeStatement(value: String): String {
        val compact = value
            .replace(Regex("""\s+"""), " ")
            .trim()
        return lowercaseOutsideLiterals(compact)
    }

    private fun topLevelStatements(value: String): List<String> {
        val statements = mutableListOf<String>()
        val statement = StringBuilder()
        var quote: Char? = null
        var dollarDelimiter: String? = null
        var inLineComment = false
        var blockCommentDepth = 0
        var index = 0

        fun finishStatement() {
            val completed = statement.toString().trim()
            if (completed.isNotEmpty()) {
                statements += completed
            }
            statement.clear()
        }

        while (index < value.length) {
            val character = value[index]
            val next = value.getOrNull(index + 1)

            if (dollarDelimiter != null) {
                if (value.startsWith(dollarDelimiter, startIndex = index)) {
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
                        if (blockCommentDepth == 0) {
                            statement.append(' ')
                        }
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
                    if (character == quote) {
                        quote = null
                    }
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
        if (value.getOrNull(cursor) == '$') {
            return value.substring(index, cursor + 1)
        }
        val firstTagCharacter = value.getOrNull(cursor) ?: return null
        if (!(firstTagCharacter.isLetter() || firstTagCharacter == '_')) return null
        cursor += 1
        while (cursor < value.length) {
            val character = value[cursor]
            when {
                character == '$' -> return value.substring(index, cursor + 1)
                character.isLetterOrDigit() || character == '_' -> cursor += 1
                else -> return null
            }
        }
        return null
    }

    private fun lowercaseOutsideLiterals(value: String): String = buildString(value.length) {
        var quote: Char? = null
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (
                quote != null &&
                character == quote &&
                index + 1 < value.length &&
                value[index + 1] == quote
            ) {
                append(character)
                append(value[index + 1])
                index += 2
                continue
            }
            if (quote != null) {
                append(character)
                if (character == quote) {
                    quote = null
                }
            } else if (character == '\'' || character == '"') {
                append(character)
                quote = character
            } else if (
                character == ' ' &&
                ((length > 0 && this[length - 1] == '(') ||
                    (index + 1 < value.length && value[index + 1] == ')'))
            ) {
                // Normalize formatting-only whitespace inside SQL parentheses.
            } else {
                append(character.lowercaseChar())
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

    /*
     * These complete SQL literals are independent test oracles. Keep them separate from sql().
     * Any executable addition inside either dollar-quoted function body must fail this contract.
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
    """.trimIndent()

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
    """.trimIndent()

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

    private fun assertExactPreflight(preflight: String) {
        assertEquals(
            exactPreflightStatement(),
            preflight,
            "Only the exact approved preflight body is allowed",
        )
        val delimiter = "\$preflight\$"
        assertTrue(
            preflight.startsWith("do $delimiter declare matching_key text[]; begin "),
            "The preflight DO block must immediately follow BEGIN",
        )
        assertTrue(
            preflight.endsWith("end $delimiter"),
            "The preflight must be one closed tagged dollar body",
        )
        assertTrue(
            preflight.contains(
                "if to_regclass('public.local_profiles') is null then " +
                    "raise exception " +
                    "'profile preferences preflight: public.local_profiles does not exist'; " +
                    "end if;",
            ),
            "The preflight must abort when the parent table is absent",
        )
        assertTrue(
            preflight.contains(
                "where constraint_row.conrelid = 'public.local_profiles'::regclass",
            ),
            "The parent-key lookup must use a regclass cast",
        )
        assertTrue(
            preflight.contains("constraint_row.contype in ('p', 'u')"),
            "The parent key must be backed by a primary or unique constraint",
        )

        val aggregate = "array_agg(attribute.attname::text order by key_column.ordinality)"
        assertEquals(2, Regex(Regex.escape(aggregate)).findAll(preflight).count())
        assertTrue(preflight.contains("select $aggregate into matching_key"))
        assertTrue(
            preflight.contains(
                "having $aggregate = array['user_id', 'id']::text[]",
            ),
            "The ordered parent-key comparison must retain its text-array cast",
        )
        assertTrue(
            preflight.contains(
                "if matching_key is null then raise exception " +
                    "'profile preferences preflight: expected unique " +
                    "local_profiles(user_id, id) parent key'; end if;",
            ),
            "The preflight must abort when the composite parent key is absent",
        )

    }

    @Test
    fun topLevelScannerHonorsQuotesCommentsAndDollarBodies() {
        val guard = "\$guard\$"
        val statements = topLevelStatements(
            """
            BEGIN;
            DO $guard
            BEGIN
                PERFORM 'literal; still -- literal; and ''quoted''';
                PERFORM "quoted;identifier";
                -- ignored ; SET ROLE attacker;
                /* ignored ; ALTER TABLE hidden OWNER TO attacker; */
            END
            $guard;
            -- outside ignored ; SET SESSION AUTHORIZATION attacker;
            /* outside ignored ; ALTER TABLE hidden OWNER TO attacker; */
            SELECT 'outside;literal', "outside;identifier";
            COMMIT;
            """.trimIndent(),
        )

        assertEquals(4, statements.size)
        assertEquals("BEGIN", statements.first())
        assertTrue(statements[1].startsWith("DO $guard"))
        assertTrue(statements[2].startsWith("SELECT "))
        assertEquals("COMMIT", statements.last())
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
                "BEGIN\n    PERFORM dblink_exec('remote', " +
                    "'DELETE FROM public.local_profiles');\n" +
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
            "authOperationalFailure",
            "status === 400 || status === 401 || status === 403",
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
            "normalized.toISOString()",
        ).forEach { fragment -> assertTrue(fragment in code, fragment) }

        fun quotedInitializer(pattern: String): List<String> {
            val body = assertNotNull(
                Regex(pattern).find(code),
                "Missing exact TypeScript allowlist: $pattern",
            ).groupValues[1]
            return Regex("\"([^\"]+)\"")
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
                """(?s)const PUSH_BODY_KEYS = new Set\(\[(.+?)\]\);""",
            ),
        )
        assertEquals(
            listOf(
                "localProfileId", "section", "documentVersion", "baseRevision",
                "clientModifiedAt", "payload",
            ),
            quotedInitializer("""(?s)const MUTATION_KEYS = \[(.+?)\] as const;"""),
        )
        assertEquals(
            listOf(
                "safeword", "safewordcalibrated", "adultsonlyconfirmed",
                "adultsonlyprompted", "localgeneration", "dirty", "legacymigrationversion",
            ),
            quotedInitializer(
                """(?s)const LOCAL_ONLY_KEYS = new Set\(\[(.+?)\]\);""",
            ),
        )

        assertTrue("rawBodyBytes > MAX_PROFILE_PREFERENCE_REQUEST_BYTES" in code)
        assertFalse(Regex("""\buserId\s*[?:]?\s*:\s*string""").containsMatchIn(code))
        assertFalse(
            Regex("""console[.](?:log|info|warn|error)[(][^)]*SERVICE_ROLE""")
                .containsMatchIn(code),
        )
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
            "The Task 2 RED sentinel must not survive the reviewed handoff",
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

    @Test
    fun executableEnvelopeRejectsStructuralAndPrivilegeMutations() {
        val valid = sql()
        val preflightMarker = "\$preflight\$"
        val preflightStart = valid.indexOf("DO $preflightMarker")
        val preflightEnd =
            valid.indexOf("$preflightMarker;", startIndex = preflightStart) +
                preflightMarker.length +
                1
        assertTrue(preflightStart >= 0 && preflightEnd > preflightStart)
        val preflight = valid.substring(preflightStart, preflightEnd)
        val withoutPreflight = valid.removeRange(preflightStart, preflightEnd)
        val reorderedPreflight = withoutPreflight.replace(
            "ALTER TABLE public.local_profile_preferences ENABLE ROW LEVEL SECURITY;",
            "$preflight\n\n" +
                "ALTER TABLE public.local_profile_preferences ENABLE ROW LEVEL SECURITY;",
        )

        val mutations = mapOf(
            "missing BEGIN" to valid.replaceFirst("BEGIN;", ""),
            "missing COMMIT" to valid.replace("COMMIT;", ""),
            "duplicate target table" to valid.replace(
                "ALTER TABLE public.local_profile_preferences ENABLE ROW LEVEL SECURITY;",
                "CREATE TABLE public.local_profile_preferences (user_id uuid);\n\n" +
                    "ALTER TABLE public.local_profile_preferences ENABLE ROW LEVEL SECURITY;",
            ),
            "additional ALTER TABLE owner mutation" to valid.replace(
                "ALTER TABLE public.local_profile_preferences ENABLE ROW LEVEL SECURITY;",
                "ALTER TABLE public.local_profile_preferences ENABLE ROW LEVEL SECURITY;\n" +
                    "ALTER TABLE public.local_profile_preferences OWNER TO authenticated;",
            ),
            "role mutation" to valid.replace(
                "BEGIN;",
                "BEGIN;\nSET ROLE authenticated;",
            ),
            "dynamic DDL" to valid.replace(
                "BEGIN\n    IF to_regclass",
                "BEGIN\n" +
                    "    EXECUTE 'ALTER TABLE public.local_profile_preferences " +
                    "OWNER TO authenticated';\n" +
                    "    IF to_regclass",
            ),
            "dynamic DDL after a line comment" to valid.replace(
                "    IF matching_key IS NULL THEN",
                "    -- a comment must not hide the next executable line\n" +
                    "    EXECUTE 'ALTER TABLE public.local_profile_preferences " +
                    "OWNER TO authenticated';\n\n" +
                    "    IF matching_key IS NULL THEN",
            ),
            "session role mutation inside preflight" to valid.replace(
                "    IF matching_key IS NULL THEN",
                "    SET SESSION ROLE authenticated;\n\n" +
                    "    IF matching_key IS NULL THEN",
            ),
            "direct DML inside preflight" to valid.replace(
                "    IF matching_key IS NULL THEN",
                "    DELETE FROM public.local_profiles;\n\n" +
                    "    IF matching_key IS NULL THEN",
            ),
            "indirect database call inside preflight" to valid.replace(
                "    IF matching_key IS NULL THEN",
                "    PERFORM dblink_exec('remote', 'DELETE FROM public.local_profiles');\n\n" +
                    "    IF matching_key IS NULL THEN",
            ),
            "extra transaction control" to valid.replace(
                "BEGIN;",
                "BEGIN;\nSAVEPOINT before_profile_preferences;",
            ),
            "preflight after table DDL" to reorderedPreflight,
            "missing parent guard" to valid.replace(
                "IF to_regclass('public.local_profiles') IS NULL THEN",
                "IF false THEN",
            ),
            "duplicate security revoke" to valid.replace(
                "REVOKE ALL ON TABLE public.local_profile_preferences FROM PUBLIC;",
                "REVOKE ALL ON TABLE public.local_profile_preferences FROM PUBLIC;\n" +
                    "REVOKE ALL ON TABLE public.local_profile_preferences FROM PUBLIC;",
            ),
        )

        mutations.forEach { (name, mutation) ->
            assertFailsWith<AssertionError>("Must reject $name") {
                assertSecuredSurface(mutation)
            }
        }
    }

    @Test
    fun sqlHandoffDeclaresTheExactProfilePreferenceSchema() {
        val source = sql()
        val statements = normalizedTopLevelStatements(source)
        assertFinalExecutableEnvelope(statements)
        val sql = normalizedSql(source)
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
        assertSecuredSurface(sql())
    }

    private fun assertSecuredSurface(value: String) {
        val statements = normalizedTopLevelStatements(value)
        assertFinalExecutableEnvelope(statements)
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
        val grantStatements = statements.filter { statement ->
            statement.startsWith("grant ")
        }
        val expectedTableGrant =
            "grant select, insert, update, delete on table " +
                "public.local_profile_preferences to service_role"
        assertEquals(
            exactFunctionAclStatements().filter { statement ->
                statement.startsWith("grant ")
            } + expectedTableGrant,
            grantStatements,
            "Only the exact service-role function and table grants are allowed",
        )

        val tableGrants = grantStatements.mapNotNull { statement ->
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
