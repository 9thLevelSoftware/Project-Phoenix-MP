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
 * without one.
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
            if (version in INTENTIONALLY_DIVERGENT) continue
            val fromFile = statementsOf(sql)
            val fromMirror = getMigrationStatements(version).map(::normalize).filter { it.isNotEmpty() }
            if (fromFile != fromMirror) {
                diffs += "migration $version:\n  .sqm   = $fromFile\n  mirror = $fromMirror"
            }
        }
        assertTrue(diffs.isEmpty(), "getMigrationStatements drifted from the .sqm files:\n${diffs.joinToString("\n")}")
    }

    @Test
    fun `every version below the schema version has a migration file`() {
        val files = migrationFiles()
        val expected = (1 until PhoenixDatabase.Schema.version).map { it.toInt() }
        assertEquals(expected, files.keys.toList(), "One .sqm file per schema step, 1..${PhoenixDatabase.Schema.version - 1}")
    }

    /** Every `N.sqm`, keyed by the version it migrates FROM (`N.sqm` migrates N -> N+1). */
    private fun migrationFiles(): Map<Int, String> = (1 until PhoenixDatabase.Schema.version)
        .mapNotNull { version ->
            val path = "src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/$version.sqm"
            val text = readProjectFile(path) ?: fail("Missing migration file: $path")
            version.toInt() to text
        }
        .toMap()

    /** SQL statements of a `.sqm` file, comments stripped and whitespace collapsed. */
    private fun statementsOf(sql: String): List<String> = stripComments(sql)
        .split(';')
        .map(::normalize)
        .filter { it.isNotEmpty() }

    private fun normalize(sql: String): String = stripComments(sql).replace(WHITESPACE, " ").trim()

    private fun stripComments(sql: String): String = sql.replace(LINE_COMMENT, "")

    private companion object {
        private val WHITESPACE = Regex("\\s+")
        private val LINE_COMMENT = Regex("--[^\\n]*")

        /**
         * Legacy entries that deliberately do NOT mirror their file. These migrations rebuild
         * a table (CREATE _new / INSERT SELECT / DROP / RENAME); replaying that on a database
         * whose migration already half-applied would lose rows, so the fallback applies the
         * equivalent additive ALTER TABLE statements instead. Do not add to this set: a new
         * migration must mirror its file exactly.
         */
        private val INTENTIONALLY_DIVERGENT = setOf(3, 4, 5, 6, 8, 9, 10, 11, 19, 31)
    }
}
