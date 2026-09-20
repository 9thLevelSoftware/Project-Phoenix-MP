package com.devil.phoenixproject.data.local

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `getMigrationStatements` is the resilient fallback: when `Schema.migrate` throws
 * (a partially applied migration, a branch-merge gap), both platforms replay these
 * statements one by one. A migration missing from it silently falls through to
 * `else -> emptyList()`, and the upgrade is then skipped entirely.
 *
 * This test pairs every `N.sqm` with its mirror entry, so a new migration cannot ship
 * without one. Ten legacy migrations do not mirror their file exactly; each carries its
 * own recorded reason in [MIRROR_EXEMPTIONS], and no migration after 31 may be exempt.
 */
class MigrationStatementsMirrorTest {

    @Test
    fun `every migration file has a non-empty mirror entry`() {
        val missing = migrationFiles()
            .filterValues { statementsOf(it).isNotEmpty() }
            .keys
            .filter { getMigrationStatements(it).isEmpty() }

        assertTrue(
            missing.isEmpty(),
            "Migrations $missing have SQL in their .sqm file but no getMigrationStatements entry. " +
                "The resilient fallback would skip them.",
        )
    }

    @Test
    fun `mirror entries match their migration file statement for statement`() {
        val diffs = mutableListOf<String>()
        for ((version, sql) in migrationFiles()) {
            if (version in MIRROR_EXEMPTIONS) continue
            val fromFile = statementsOf(sql)
            val fromMirror = mirrorStatements(version)
            if (fromFile != fromMirror) {
                diffs += "migration $version:\n  .sqm   = $fromFile\n  mirror = $fromMirror"
            }
        }
        assertTrue(diffs.isEmpty(), "getMigrationStatements drifted from the .sqm files:\n${diffs.joinToString("\n")}")
    }

    @Test
    fun `superset exemptions still replay every statement of their migration file`() {
        val files = migrationFiles()
        val diffs = mutableListOf<String>()
        MIRROR_EXEMPTIONS.filterValues { it.mirrorIsSuperset }.keys.forEach { version ->
            val fromFile = statementsOf(files.getValue(version)).toSet()
            val missing = fromFile - mirrorStatements(version).toSet()
            if (missing.isNotEmpty()) {
                diffs += "migration $version is declared a superset but its mirror is missing: $missing"
            }
        }
        assertTrue(diffs.isEmpty(), diffs.joinToString("\n"))
    }

    @Test
    fun `no migration after 31 is exempt from the exact mirror assertion`() {
        val late = MIRROR_EXEMPTIONS.keys.filter { it > LAST_EXEMPT_MIGRATION }
        assertTrue(
            late.isEmpty(),
            "Migrations $late were added to MIRROR_EXEMPTIONS. A migration written after " +
                "$LAST_EXEMPT_MIGRATION must mirror its .sqm statement for statement; fix the mirror " +
                "entry instead of exempting it.",
        )
    }

    @Test
    fun `every version below the schema version has a migration file`() {
        val files = migrationFiles()
        val expected = (1 until PhoenixDatabase.Schema.version).map { it.toInt() }
        assertEquals(expected, files.keys.toList(), "One .sqm file per schema step, 1..${PhoenixDatabase.Schema.version - 1}")
    }

    @Test
    fun `no migration file hides a statement separator inside a string literal`() {
        // statementsOf() splits on ';' and strips '--' comments without tracking quotes.
        // No current .sqm needs more, and this keeps a future data-fix migration from
        // being mis-split into a confusing diff or a bogus pass.
        val offenders = migrationFiles().filterValues { sql ->
            STRING_LITERAL.findAll(stripComments(sql)).any { it.groupValues[1].contains(';') || it.groupValues[1].contains("--") }
        }.keys
        assertTrue(
            offenders.isEmpty(),
            "Migrations $offenders contain ';' or '--' inside a string literal; teach statementsOf() " +
                "about quotes before relying on this test for them.",
        )
    }

    /** Every `N.sqm`, keyed by the version it migrates FROM (`N.sqm` migrates N -> N+1). */
    private fun migrationFiles(): Map<Int, String> = (1 until PhoenixDatabase.Schema.version)
        .associate { version ->
            val path = "src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/$version.sqm"
            val text = readProjectFile(path) ?: fail("Missing migration file: $path")
            version.toInt() to text
        }

    private fun mirrorStatements(version: Int): List<String> =
        getMigrationStatements(version).map(::normalize).filter { it.isNotEmpty() }

    /** SQL statements of a `.sqm` file, comments stripped and whitespace collapsed. */
    private fun statementsOf(sql: String): List<String> = stripComments(sql)
        .split(';')
        .map(::normalize)
        .filter { it.isNotEmpty() }

    private fun normalize(sql: String): String = stripComments(sql).replace(WHITESPACE, " ").trim()

    private fun stripComments(sql: String): String = sql.replace(LINE_COMMENT, "")

    /**
     * Why one legacy migration's mirror does not match its file.
     * [mirrorIsSuperset] means the mirror replays every statement of the file and adds
     * more, which the superset test asserts; the others are documented, unasserted gaps.
     */
    private data class MirrorExemption(val reason: String, val mirrorIsSuperset: Boolean = false)

    private companion object {
        private val WHITESPACE = Regex("\\s+")
        private val LINE_COMMENT = Regex("--[^\\n]*")
        private val STRING_LITERAL = Regex("'([^']*)'")

        /** Nothing newer than this may be exempt — enforced by a test above. */
        private const val LAST_EXEMPT_MIGRATION = 31

        /**
         * Legacy entries that deliberately do NOT mirror their file, each with its own
         * reason. Do not add to this map: a migration written after
         * [LAST_EXEMPT_MIGRATION] must mirror its `.sqm` exactly, which is what makes
         * this test a guard for every new schema change.
         */
        private val MIRROR_EXEMPTIONS: Map<Int, MirrorExemption> = mapOf(
            3 to MirrorExemption(
                "3.sqm rebuilds RoutineExercise through a _new table; replaying that over a " +
                    "half-applied migration would lose rows, so the fallback adds the three " +
                    "superset columns with ALTER TABLE instead.",
            ),
            4 to MirrorExemption(
                "4.sqm creates Superset and rebuilds RoutineExercise again; the fallback " +
                    "creates the table IF NOT EXISTS and adds the columns with ALTER TABLE.",
            ),
            5 to MirrorExemption(
                "the mirror is MISSING 5.sqm's four UserProfile auth columns " +
                    "(supabase_user_id, subscription_status, subscription_expires_at, " +
                    "last_auth_at). They are added on every open by the SchemaManifest heal " +
                    "ops for UserProfile, which is why the gap is not a live defect.",
            ),
            6 to MirrorExemption(
                "the mirror's CREATE TABLEs carry the tables' current forward shape " +
                    "(profile_id, deletedAt, template_id, week_number) rather than the shape " +
                    "6.sqm created, so the statements cannot be compared literally.",
            ),
            8 to MirrorExemption(
                "8.sqm rebuilds tables to widen columns; the fallback applies the equivalent " +
                    "additive DDL and skips the copy/rename steps.",
            ),
            9 to MirrorExemption(
                "the mirror applies 9.sqm's DDL only and skips its superset composite-id data " +
                    "step (INSERT OR IGNORE / UPDATE / DELETE), so the fallback does less than " +
                    "the migration. Documented gap, not a rebuild.",
            ),
            10 to MirrorExemption(
                "10.sqm is the large multi-table rebuild; the fallback recreates the tables " +
                    "IF NOT EXISTS and adds columns additively.",
            ),
            11 to MirrorExemption(
                "the mirror adds five EarnedBadge/GamificationStats repair ALTERs on top of " +
                    "11.sqm's statements.",
                mirrorIsSuperset = true,
            ),
            19 to MirrorExemption(
                "the mirror deletes duplicate PersonalRecord rows before creating 19.sqm's " +
                    "UNIQUE index, so the index creation cannot fail on legacy data.",
                mirrorIsSuperset = true,
            ),
            31 to MirrorExemption(
                "the mirror deletes duplicate ExternalActivity rows before creating 31.sqm's " +
                    "UNIQUE index, for the same reason as 19.",
                mirrorIsSuperset = true,
            ),
        )
    }
}
