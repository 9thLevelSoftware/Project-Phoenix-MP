package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.testutil.readProjectFile
import java.io.File
import java.sql.SQLException
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * IntervalsMigrationTest -- migration 63 (schema 63 -> 64, issue #1384) upgrade,
 * parity, fallback, heal, FK/reopen and downgrade-safety evidence.
 *
 * Migration 63 is purely additive: six Intervals.icu outbound-sync tables
 * (IntervalsConnection, WorkoutCompletionJournal, WorkoutCompletionJournalChild,
 * WorkoutCompletionReceipt, IntervalsOutbox, IntervalsExternalIdReservation) plus
 * five indexes. Fresh and upgraded installs must both seed ZERO rows, and legacy
 * [WorkoutSession] rows must never be interpreted as completion records.
 *
 * Raw [JdbcSqliteDriver] following [SchemaParityTest]'s helper style: in-memory
 * drivers build schema with foreign keys OFF (production migrates/reconciles
 * before enabling FKs), while file-backed drivers enable FK enforcement through
 * the JDBC connection properties (see [openFileDriver]) so crash/reopen evidence
 * runs on real files with real constraint enforcement.
 */
class IntervalsMigrationTest {

    // ==================== 1. 63 -> 64 UPGRADE ====================

    @Test
    fun `migration 63 to 64 adds the six Intervals tables with exact columns and indexes`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        buildSchemaAtVersion(driver, 63)

        // Precondition: a real v63 database has none of the Intervals tables.
        val tablesBefore = getTables(driver)
        for (table in EXPECTED_TABLES.keys) {
            assertTrue(table !in tablesBefore, "v63 must not contain $table")
        }
        for (index in EXPECTED_INDEX_DEFINITIONS.keys) {
            assertTrue(!indexExistsInDriver(driver, index), "v63 must not contain $index")
        }

        // A legacy session row must survive the upgrade untouched.
        insertLegacySession(driver, "legacy-s1")

        PhoenixDatabase.Schema.migrate(driver, 63, 64)

        assertIntervalsShape(driver, "after generated migration 63 -> 64")
        assertEquals(
            "0",
            queryScalar(driver, "SELECT CAST(COUNT(*) AS TEXT) FROM WorkoutCompletionJournal"),
            "upgrade must not seed journal rows",
        )
        assertEquals("legacy-s1", queryScalar(driver, "SELECT id FROM WorkoutSession WHERE id = 'legacy-s1'"))
    }

    // ==================== 2. FRESH / UPGRADE PARITY ====================

    @Test
    fun `fresh install and v1 upgrade produce identical Intervals table shapes`() {
        val freshDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhoenixDatabase.Schema.create(freshDriver)

        // The exact production upgrade path for every existing user: v1 base,
        // migrate 1..63 with resilient fallback, then the on-open reconciliation.
        val upgradeDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        buildSchemaAtVersion(upgradeDriver, 64)
        reconcileFullSchema(upgradeDriver)

        for ((table, expectedColumns) in EXPECTED_TABLES) {
            assertEquals(expectedColumns, getColumns(freshDriver, table), "fresh columns for $table")
            assertEquals(
                getColumns(freshDriver, table),
                getColumns(upgradeDriver, table),
                "column mismatch between fresh install and v1+migrate+reconcile for $table",
            )
            assertEquals(
                getPrimaryKeyColumns(freshDriver, table),
                getPrimaryKeyColumns(upgradeDriver, table),
                "primary key mismatch for $table",
            )
            assertEquals(
                describeForeignKeys(freshDriver, table),
                describeForeignKeys(upgradeDriver, table),
                "foreign key mismatch for $table",
            )
            assertEquals(
                getUserIndexes(freshDriver, table),
                getUserIndexes(upgradeDriver, table),
                "index set mismatch for $table",
            )
        }

        assertEquals(
            EXPECTED_INDEX_DEFINITIONS,
            intervalsIndexDefinitions(upgradeDriver),
            "upgrade-path index definitions must match the 63.sqm shape",
        )
        assertEquals(
            intervalsIndexDefinitions(freshDriver),
            intervalsIndexDefinitions(upgradeDriver),
            "index definitions differ between fresh install and upgrade path",
        )
    }

    // ==================== 3. FALLBACK MIGRATION ====================

    @Test
    fun `resilient fallback replays the migration 63 mirror when Schema_migrate fails mid-migration`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        buildSchemaAtVersion(driver, 63)
        val wrapped = FaultInjectingDriver(driver)

        // Crash mid-migration: statements before IntervalsOutbox already applied,
        // then the driver fails (injected IO/SQLite fault) exactly where production
        // DriverFactory.onUpgrade catches and falls back to applyMigrationResilient.
        wrapped.failOnSqlFragment = "CREATE TABLE IF NOT EXISTS IntervalsOutbox"
        val failure = runCatching {
            PhoenixDatabase.Schema.migrate(wrapped, 63, 64)
        }.exceptionOrNull()
        assertNotNull(failure, "the injected fault must make Schema.migrate fail")

        // The crash left a PARTIALLY applied migration: earlier statements stuck.
        val partialTables = getTables(driver)
        assertTrue("IntervalsConnection" in partialTables, "statements before the fault were applied")
        assertTrue("WorkoutCompletionReceipt" in partialTables, "statements before the fault were applied")
        assertTrue("IntervalsOutbox" !in partialTables, "the faulted statement was not applied")
        assertTrue("IntervalsExternalIdReservation" !in partialTables, "later statements were not applied")

        // Production fallback (DriverFactory.onUpgrade): replay the mirror keyed by
        // the .sqm number being migrated FROM (63), statements one by one.
        wrapped.failOnSqlFragment = null
        val results = applyMigrationResilient(wrapped, 63)
        assertEquals(
            getMigrationStatements(63).size,
            results.size,
            "fallback must replay every mirrored migration-63 statement",
        )
        assertTrue(
            results.none { !it.success && !it.recoverable },
            results.filter { !it.success }.joinToString { "${it.sql}: ${it.error}" },
        )

        assertIntervalsShape(driver, "after resilient fallback for migration 63")
    }

    // ==================== 4. PARTIAL SCHEMA HEAL ====================

    @Test
    fun `schema heal restores a dropped Intervals table and a dropped index exactly`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        buildSchemaAtVersion(driver, 64)
        reconcileFullSchema(driver)

        // Data in an untouched table must survive the heal (additive only).
        executeSql(
            driver,
            """
            INSERT INTO IntervalsOutbox (
                profile_id, resolved_athlete_id, external_id, receipt_profile_id, receipt_local_kind,
                receipt_completion_id, payload_json, payload_version, payload_digest,
                original_generation, state, created_at, updated_at
            ) VALUES ('p1', 'a1', 'ext-1', 'p1', 'ROUTINE', 'c-1', '{}', 1, 'digest', 1, 'PENDING', 1, 1)
            """.trimIndent(),
        )

        // Damage: one whole new table gone (its index goes with it) and one
        // standalone index dropped.
        executeSql(driver, "DROP TABLE WorkoutCompletionJournalChild")
        executeSql(driver, "DROP INDEX idx_completion_receipt_external")
        assertTrue("WorkoutCompletionJournalChild" !in getTables(driver))

        val report = reconcileFullSchema(driver)

        assertTrue(report.failures.isEmpty(), "heal reported failures: ${report.failures}")
        assertEquals(3, report.created, "heal must recreate the table and the two indexes")
        assertIntervalsShape(driver, "after reconcileFullSchema heals dropped objects")
        assertEquals("1", queryScalar(driver, "SELECT CAST(COUNT(*) AS TEXT) FROM IntervalsOutbox"))
    }

    // ==================== 5. FK INTEGRITY / REOPEN ====================

    @Test
    fun `journal children, receipts and rows survive reopen and session deletes without cascades`() {
        val file = File.createTempFile("intervals-migration-fk", ".db")
        try {
            val first = openFileDriver(file)
            try {
                buildSchemaAtVersion(first, 64)
                reconcileFullSchema(first)

                // Positive control: with enforcement active (see openFileDriver), an
                // orphan journal child must be rejected outright.
                val orphan = runCatching {
                    executeSql(
                        first,
                        """
                        INSERT INTO WorkoutCompletionJournalChild (
                            journal_profile_id, journal_local_kind, journal_completion_id,
                            child_key, child_kind, session_id, codec_version
                        ) VALUES ('p1', 'ROUTINE', 'missing-parent', 'orphan', 'SESSION', 's-x', 1)
                        """.trimIndent(),
                    )
                }.exceptionOrNull()
                assertNotNull(orphan, "FK enforcement must reject an orphan journal child")

                insertLegacySession(first, "s-1")
                executeSql(
                    first,
                    """
                    INSERT INTO WorkoutCompletionJournal (
                        profile_id, local_kind, completion_id, state, finish_reason, routine_title,
                        expected_session_count, expected_set_count, membership_digest,
                        start_epoch_ms, start_local_iso, time_zone_id, utc_offset_minutes,
                        completed_epoch_ms, eligible, destination_digest, created_at, updated_at
                    ) VALUES (
                        'p1', 'ROUTINE', 'c-1', 'REQUESTED', 'FINISHED', 'Day',
                        1, 2, 'digest-1',
                        1000, '2026-01-01T00:00:00', 'America/New_York', -300,
                        2000, 1, 'dest-1', 1000, 1000
                    )
                    """.trimIndent(),
                )
                for (childKey in listOf("s-1" to "SESSION", "set-1" to "COMPLETED_SET")) {
                    executeSql(
                        first,
                        """
                        INSERT INTO WorkoutCompletionJournalChild (
                            journal_profile_id, journal_local_kind, journal_completion_id,
                            child_key, child_kind, session_id, codec_version, replay_payload, committed
                        ) VALUES ('p1', 'ROUTINE', 'c-1', '${childKey.first}', '${childKey.second}', 's-1', 1, 'payload-${childKey.first}', 0)
                        """.trimIndent(),
                    )
                }
                executeSql(
                    first,
                    """
                    INSERT INTO WorkoutCompletionReceipt (
                        profile_id, local_kind, completion_id, external_id, snapshot_version,
                        membership_json, finish_reason, start_epoch_ms, start_local_iso,
                        time_zone_id, utc_offset_minutes, completed_epoch_ms, eligible, created_at
                    ) VALUES (
                        'p1', 'ROUTINE', 'c-1', 'ext-1', 1,
                        '["s-1"]', 'FINISHED', 1000, '2026-01-01T00:00:00',
                        'America/New_York', -300, 2000, 1, 1000
                    )
                    """.trimIndent(),
                )
                executeSql(
                    first,
                    "INSERT INTO IntervalsConnection (profile_id, resolved_athlete_id, installation_id, updated_at) " +
                        "VALUES ('p1', 'athlete-1', 'install-1', 1000)",
                )
                executeSql(
                    first,
                    """
                    INSERT INTO IntervalsOutbox (
                        profile_id, resolved_athlete_id, external_id, receipt_profile_id, receipt_local_kind,
                        receipt_completion_id, payload_json, payload_version, payload_digest,
                        original_generation, state, created_at, updated_at
                    ) VALUES ('p1', 'athlete-1', 'ext-1', 'p1', 'ROUTINE', 'c-1', '{}', 1, 'digest-1', 1, 'PENDING', 1000, 1000)
                    """.trimIndent(),
                )
                executeSql(
                    first,
                    "INSERT INTO IntervalsExternalIdReservation " +
                        "(resolved_athlete_id, external_id, owner_profile_id, owner_local_kind, owner_completion_id, created_at) " +
                        "VALUES ('athlete-1', 'ext-1', 'p1', 'ROUTINE', 'c-1', 1000)",
                )

                assertEquals(emptyList(), foreignKeyViolations(first), "seeded rows must satisfy FK integrity")
            } finally {
                first.close()
            }

            // Crash/reopen: a fresh driver on the same file must see every row.
            val reopened = openFileDriver(file)
            try {
                assertEquals("1", queryScalar(reopened, "SELECT CAST(COUNT(*) AS TEXT) FROM WorkoutCompletionJournal"))
                assertEquals("2", queryScalar(reopened, "SELECT CAST(COUNT(*) AS TEXT) FROM WorkoutCompletionJournalChild"))
                assertEquals("1", queryScalar(reopened, "SELECT CAST(COUNT(*) AS TEXT) FROM WorkoutCompletionReceipt"))
                assertEquals("1", queryScalar(reopened, "SELECT CAST(COUNT(*) AS TEXT) FROM IntervalsConnection"))
                assertEquals("1", queryScalar(reopened, "SELECT CAST(COUNT(*) AS TEXT) FROM IntervalsOutbox"))
                assertEquals("1", queryScalar(reopened, "SELECT CAST(COUNT(*) AS TEXT) FROM IntervalsExternalIdReservation"))
                assertEquals("payload-s-1", queryScalar(reopened, "SELECT replay_payload FROM WorkoutCompletionJournalChild WHERE child_key = 's-1'"))

                // The receipt carries the session only as membership data; deleting
                // the WorkoutSession must not cascade into any Intervals table.
                executeSql(reopened, "DELETE FROM WorkoutSession WHERE id = 's-1'")
                assertEquals("1", queryScalar(reopened, "SELECT CAST(COUNT(*) AS TEXT) FROM WorkoutCompletionReceipt"), "receipt must survive session delete")
                assertEquals("2", queryScalar(reopened, "SELECT CAST(COUNT(*) AS TEXT) FROM WorkoutCompletionJournalChild"), "replay children must survive session delete")
                assertEquals("1", queryScalar(reopened, "SELECT CAST(COUNT(*) AS TEXT) FROM WorkoutCompletionJournal"))

                // The designed cascade: children live and die with their journal parent.
                executeSql(reopened, "DELETE FROM WorkoutCompletionJournal WHERE completion_id = 'c-1'")
                assertEquals("0", queryScalar(reopened, "SELECT CAST(COUNT(*) AS TEXT) FROM WorkoutCompletionJournalChild"), "children cascade with their journal parent")
                assertEquals("1", queryScalar(reopened, "SELECT CAST(COUNT(*) AS TEXT) FROM WorkoutCompletionReceipt"), "receipt is independent of the journal lifecycle")

                assertEquals(emptyList(), foreignKeyViolations(reopened), "FK check must stay clean after deletes")
            } finally {
                reopened.close()
            }
        } finally {
            deleteDatabaseFiles(file)
        }
    }

    // ==================== 6. INTERRUPTED JOURNAL REPLAY ====================

    @Test
    fun `interrupted journal replay survives a driver reopen and commits children individually`() {
        val file = File.createTempFile("intervals-migration-replay", ".db")
        try {
            val first = openFileDriver(file)
            try {
                PhoenixDatabase.Schema.create(first)
                val queries = PhoenixDatabase(first).phoenixDatabaseQueries

                // Admission writes a REQUESTED journal row with two pending children
                // through the generated queries; the crash happens before any commit.
                queries.insertCompletionJournalIfAbsent(
                    profile_id = "p1",
                    local_kind = "ROUTINE",
                    completion_id = "cr-1",
                    finish_reason = "FINISHED",
                    routine_title = "Day",
                    expected_session_count = 1L,
                    expected_set_count = 2L,
                    membership_digest = "digest-1",
                    start_epoch_ms = 1000L,
                    start_local_iso = "2026-01-01T00:00:00",
                    time_zone_id = "America/New_York",
                    utc_offset_minutes = -300L,
                    completed_epoch_ms = 2000L,
                    eligible = 1L,
                    resolved_athlete_id = null,
                    connection_generation = null,
                    destination_digest = "dest-1",
                    created_at = 1000L,
                )
                queries.insertCompletionJournalChildIfAbsent(
                    journal_profile_id = "p1",
                    journal_local_kind = "ROUTINE",
                    journal_completion_id = "cr-1",
                    child_key = "s-1",
                    child_kind = "SESSION",
                    session_id = "s-1",
                    codec_version = 1L,
                    replay_payload = "payload-1",
                )
                queries.insertCompletionJournalChildIfAbsent(
                    journal_profile_id = "p1",
                    journal_local_kind = "ROUTINE",
                    journal_completion_id = "cr-1",
                    child_key = "set-1",
                    child_kind = "COMPLETED_SET",
                    session_id = "s-1",
                    codec_version = 1L,
                    replay_payload = "payload-2",
                )
            } finally {
                first.close()
            }

            // Reopen after the interruption: the REQUESTED row must still be replayable.
            val reopened = openFileDriver(file)
            try {
                val queries = PhoenixDatabase(reopened).phoenixDatabaseQueries

                val pending = queries.selectRequestedCompletions().executeAsList()
                assertEquals(1, pending.size, "the REQUESTED journal row must survive the reopen")
                assertEquals("cr-1", pending.single().completion_id)
                assertEquals("REQUESTED", pending.single().state)

                // Committing one child clears only its replay payload (CAS on committed=0).
                queries.markCompletionChildCommitted(
                    committed_at = 5000L,
                    journal_profile_id = "p1",
                    journal_local_kind = "ROUTINE",
                    journal_completion_id = "cr-1",
                    child_key = "s-1",
                )

                val children = queries.selectCompletionJournalChildren("p1", "ROUTINE", "cr-1").executeAsList()
                    .associateBy { it.child_key }
                val committed = children.getValue("s-1")
                assertEquals(1L, committed.committed)
                assertNull(committed.replay_payload, "a committed child keeps no replay payload")
                assertEquals(5000L, committed.committed_at)

                val stillPending = children.getValue("set-1")
                assertEquals(0L, stillPending.committed)
                assertEquals("payload-2", stillPending.replay_payload, "other children keep their replay payloads")
                assertNull(stillPending.committed_at)

                // The journal row itself is untouched by a child commit.
                assertEquals(1, queries.selectRequestedCompletions().executeAsList().size)
            } finally {
                reopened.close()
            }
        } finally {
            deleteDatabaseFiles(file)
        }
    }

    // ==================== 7. DOWNGRADE / OLDER-BINARY SAFETY ====================

    @Test
    fun `an older binary opening a schema 64 database fails safely without deleting data`() {
        // Schema.version is the version every open validates against; the six
        // additive tables are what a 64-schema database carries beyond v63.
        assertEquals(64L, PhoenixDatabase.Schema.version)

        val file = File.createTempFile("intervals-migration-downgrade", ".db")
        try {
            val first = openFileDriver(file)
            try {
                buildSchemaAtVersion(first, 64)
                reconcileFullSchema(first)
                // Production leaves user_version at the schema version after upgrade.
                executeSql(first, "PRAGMA user_version = 64")
                executeSql(
                    first,
                    """
                    INSERT INTO WorkoutCompletionReceipt (
                        profile_id, local_kind, completion_id, external_id, snapshot_version,
                        membership_json, finish_reason, start_epoch_ms, start_local_iso,
                        time_zone_id, utc_offset_minutes, completed_epoch_ms, eligible, created_at
                    ) VALUES (
                        'p1', 'ROUTINE', 'c-1', 'ext-1', 1,
                        '[]', 'FINISHED', 1000, '2026-01-01T00:00:00',
                        'America/New_York', -300, 2000, 1, 1000
                    )
                    """.trimIndent(),
                )
                executeSql(
                    first,
                    """
                    INSERT INTO IntervalsOutbox (
                        profile_id, resolved_athlete_id, external_id, receipt_profile_id, receipt_local_kind,
                        receipt_completion_id, payload_json, payload_version, payload_digest,
                        original_generation, state, created_at, updated_at
                    ) VALUES ('p1', 'athlete-1', 'ext-1', 'p1', 'ROUTINE', 'c-1', '{}', 1, 'digest-1', 1, 'PENDING', 1000, 1000)
                    """.trimIndent(),
                )
            } finally {
                first.close()
            }

            // An older binary expecting schema 63 opens the schema-64 database:
            // exactly DriverFactory.createDriver's version-check safe branch --
            // typed TARGET_VALIDATION_FAILED, driver closed, NOTHING deleted.
            val failure = runCatching { openAsOlderBinary(file, olderExpectedVersion = 63L) }.exceptionOrNull()
            val migrationFailure = failure as? DatabaseFileMigrationException
                ?: fail("expected DatabaseFileMigrationException, got $failure")
            assertEquals(DatabaseMigrationFailureCode.TARGET_VALIDATION_FAILED, migrationFailure.code)
            assertTrue(migrationFailure.startupRetryAllowed, "a version mismatch must stay retryable, never destructive")

            // The database must be exactly as it was: file present, version still 64,
            // every row intact -- no downgrade, no destructive reset, no reseed.
            assertTrue(file.exists(), "the database file must never be deleted on version mismatch")
            val reopened = openFileDriver(file)
            try {
                assertEquals("64", queryScalar(reopened, "PRAGMA user_version"), "user_version must be untouched")
                assertIntervalsShape(reopened, "after a rejected older-binary open")
                assertEquals("1", queryScalar(reopened, "SELECT CAST(COUNT(*) AS TEXT) FROM WorkoutCompletionReceipt"))
                assertEquals("1", queryScalar(reopened, "SELECT CAST(COUNT(*) AS TEXT) FROM IntervalsOutbox"))
            } finally {
                reopened.close()
            }

            // Guard the real Android production branch: the version-mismatch failure
            // throws the typed error and only closes the driver -- it never deletes
            // the database file.
            val driverFactorySource = readProjectFile("src/androidMain/kotlin/com/devil/phoenixproject/data/local/DriverFactory.android.kt")
                ?: fail("DriverFactory.android.kt not found")
            assertTrue(
                driverFactorySource.contains("The Phoenix database schema version is invalid."),
                "DriverFactory must keep the fail-safe version-mismatch branch",
            )
            assertTrue(
                driverFactorySource.contains("runCatching { driver.close() }"),
                "the version-mismatch failure path must only close the driver",
            )
            assertTrue(
                !driverFactorySource.contains("deleteDatabase"),
                "DriverFactory must never delete the database on version mismatch",
            )
        } finally {
            deleteDatabaseFiles(file)
        }
    }

    // ==================== 8. FRESH INSTALL SEEDS ZERO ====================

    @Test
    fun `fresh install seeds zero Intervals rows and legacy sessions never become completions`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhoenixDatabase.Schema.create(driver)

        for (table in EXPECTED_TABLES.keys) {
            assertEquals("0", queryScalar(driver, "SELECT CAST(COUNT(*) AS TEXT) FROM $table"), "$table must start empty")
        }

        // Legacy history exists before any Intervals feature ever ran.
        insertLegacySession(driver, "legacy-session")

        // Run the whole open-time convergence: heal plus a migration replay. Both
        // are no-ops on shape here and must never interpret sessions as completions.
        reconcileFullSchema(driver)
        applyMigrationResilient(driver, 63)

        for (table in EXPECTED_TABLES.keys) {
            assertEquals(
                "0",
                queryScalar(driver, "SELECT CAST(COUNT(*) AS TEXT) FROM $table"),
                "$table must stay empty after heal/migration on legacy data",
            )
        }
        assertEquals("legacy-session", queryScalar(driver, "SELECT id FROM WorkoutSession WHERE id = 'legacy-session'"))
        val pending = PhoenixDatabase(driver).phoenixDatabaseQueries.selectRequestedCompletions().executeAsList()
        assertTrue(pending.isEmpty(), "legacy sessions must never surface as requested completions")
    }

    // ==================== EXPECTED 63.sqm SHAPE ====================

    private companion object {
        private const val EXPECTED_SCHEMA_VERSION = 64L

        private val EXPECTED_TABLES: Map<String, Map<String, String>> = linkedMapOf(
            "IntervalsConnection" to linkedMapOf(
                "profile_id" to "TEXT",
                "resolved_athlete_id" to "TEXT",
                "connection_generation" to "INTEGER",
                "dispatch_auth_generation" to "INTEGER",
                "installation_id" to "TEXT",
                "secret_key_ref" to "TEXT",
                "enabled" to "INTEGER",
                "paused" to "INTEGER",
                "auth_required" to "INTEGER",
                "validated_at" to "INTEGER",
                "enabled_at" to "INTEGER",
                "paused_at" to "INTEGER",
                "consent_version" to "INTEGER",
                "disclosure_version" to "INTEGER",
                "eligibility_epoch" to "INTEGER",
                "updated_at" to "INTEGER",
            ),
            "WorkoutCompletionJournal" to linkedMapOf(
                "profile_id" to "TEXT",
                "local_kind" to "TEXT",
                "completion_id" to "TEXT",
                "state" to "TEXT",
                "finish_reason" to "TEXT",
                "routine_title" to "TEXT",
                "expected_session_count" to "INTEGER",
                "expected_set_count" to "INTEGER",
                "membership_digest" to "TEXT",
                "start_epoch_ms" to "INTEGER",
                "start_local_iso" to "TEXT",
                "time_zone_id" to "TEXT",
                "utc_offset_minutes" to "INTEGER",
                "completed_epoch_ms" to "INTEGER",
                "eligible" to "INTEGER",
                "resolved_athlete_id" to "TEXT",
                "connection_generation" to "INTEGER",
                "destination_digest" to "TEXT",
                "created_at" to "INTEGER",
                "updated_at" to "INTEGER",
            ),
            "WorkoutCompletionJournalChild" to linkedMapOf(
                "journal_profile_id" to "TEXT",
                "journal_local_kind" to "TEXT",
                "journal_completion_id" to "TEXT",
                "child_key" to "TEXT",
                "child_kind" to "TEXT",
                "session_id" to "TEXT",
                "codec_version" to "INTEGER",
                "replay_payload" to "TEXT",
                "committed" to "INTEGER",
                "committed_at" to "INTEGER",
            ),
            "WorkoutCompletionReceipt" to linkedMapOf(
                "profile_id" to "TEXT",
                "local_kind" to "TEXT",
                "completion_id" to "TEXT",
                "external_id" to "TEXT",
                "snapshot_version" to "INTEGER",
                "membership_json" to "TEXT",
                "finish_reason" to "TEXT",
                "start_epoch_ms" to "INTEGER",
                "start_local_iso" to "TEXT",
                "time_zone_id" to "TEXT",
                "utc_offset_minutes" to "INTEGER",
                "completed_epoch_ms" to "INTEGER",
                "eligible" to "INTEGER",
                "resolved_athlete_id" to "TEXT",
                "connection_generation" to "INTEGER",
                "export_payload" to "TEXT",
                "payload_digest" to "TEXT",
                "created_at" to "INTEGER",
            ),
            "IntervalsOutbox" to linkedMapOf(
                "profile_id" to "TEXT",
                "resolved_athlete_id" to "TEXT",
                "external_id" to "TEXT",
                "receipt_profile_id" to "TEXT",
                "receipt_local_kind" to "TEXT",
                "receipt_completion_id" to "TEXT",
                "payload_json" to "TEXT",
                "payload_version" to "INTEGER",
                "payload_digest" to "TEXT",
                "original_generation" to "INTEGER",
                "dispatch_auth_generation" to "INTEGER",
                "remote_activity_id" to "TEXT",
                "state" to "TEXT",
                "reason_code" to "TEXT",
                "attempts" to "INTEGER",
                "next_attempt_at" to "INTEGER",
                "lease_token" to "TEXT",
                "lease_expires_at" to "INTEGER",
                "first_sent_at" to "INTEGER",
                "created_at" to "INTEGER",
                "updated_at" to "INTEGER",
            ),
            "IntervalsExternalIdReservation" to linkedMapOf(
                "resolved_athlete_id" to "TEXT",
                "external_id" to "TEXT",
                "owner_profile_id" to "TEXT",
                "owner_local_kind" to "TEXT",
                "owner_completion_id" to "TEXT",
                "created_at" to "INTEGER",
            ),
        )

        private val EXPECTED_PRIMARY_KEYS: Map<String, List<String>> = mapOf(
            "IntervalsConnection" to listOf("profile_id"),
            "WorkoutCompletionJournal" to listOf("profile_id", "local_kind", "completion_id"),
            "WorkoutCompletionJournalChild" to listOf(
                "journal_profile_id",
                "journal_local_kind",
                "journal_completion_id",
                "child_key",
            ),
            "WorkoutCompletionReceipt" to listOf("profile_id", "local_kind", "completion_id"),
            "IntervalsOutbox" to listOf("profile_id", "resolved_athlete_id", "external_id"),
            "IntervalsExternalIdReservation" to listOf("resolved_athlete_id", "external_id"),
        )

        /** The one and only foreign key in the six tables: children -> journal, ON DELETE CASCADE. */
        private val EXPECTED_FOREIGN_KEYS: Map<String, List<String>> = mapOf(
            "IntervalsConnection" to emptyList(),
            "WorkoutCompletionJournal" to emptyList(),
            "WorkoutCompletionJournalChild" to listOf(
                "WorkoutCompletionJournal(journal_profile_id -> profile_id, " +
                    "journal_local_kind -> local_kind, journal_completion_id -> completion_id) ON DELETE CASCADE",
            ),
            "WorkoutCompletionReceipt" to emptyList(),
            "IntervalsOutbox" to emptyList(),
            "IntervalsExternalIdReservation" to emptyList(),
        )

        private val EXPECTED_TABLE_INDEXES: Map<String, List<String>> = mapOf(
            "IntervalsConnection" to emptyList(),
            "WorkoutCompletionJournal" to listOf("idx_completion_journal_state"),
            "WorkoutCompletionJournalChild" to listOf("idx_completion_journal_child_session"),
            "WorkoutCompletionReceipt" to listOf("idx_completion_receipt_external"),
            "IntervalsOutbox" to listOf("idx_intervals_outbox_due", "idx_intervals_outbox_lease"),
            "IntervalsExternalIdReservation" to emptyList(),
        )

        private val EXPECTED_INDEX_DEFINITIONS: Map<String, String> = mapOf(
            "idx_completion_journal_state" to "WorkoutCompletionJournal(state, created_at)",
            "idx_completion_journal_child_session" to "WorkoutCompletionJournalChild(session_id)",
            "idx_completion_receipt_external" to "WorkoutCompletionReceipt(external_id)",
            "idx_intervals_outbox_due" to "IntervalsOutbox(state, next_attempt_at)",
            "idx_intervals_outbox_lease" to "IntervalsOutbox(state, lease_expires_at)",
        )
    }

    /** Asserts the six tables carry exactly the 63.sqm shape: columns, PKs, FKs, indexes. */
    private fun assertIntervalsShape(driver: SqlDriver, scenario: String) {
        for ((table, expectedColumns) in EXPECTED_TABLES) {
            assertTrue(table in getTables(driver), "$scenario: missing table $table")
            assertEquals(expectedColumns, getColumns(driver, table), "$scenario: columns of $table")
            assertEquals(
                EXPECTED_PRIMARY_KEYS.getValue(table),
                getPrimaryKeyColumns(driver, table),
                "$scenario: primary key of $table",
            )
            assertEquals(
                EXPECTED_FOREIGN_KEYS.getValue(table),
                describeForeignKeys(driver, table),
                "$scenario: foreign keys of $table",
            )
            assertEquals(
                EXPECTED_TABLE_INDEXES.getValue(table),
                getUserIndexes(driver, table),
                "$scenario: user indexes of $table",
            )
        }
        assertEquals(
            EXPECTED_INDEX_DEFINITIONS,
            intervalsIndexDefinitions(driver),
            "$scenario: Intervals index definitions",
        )
        assertEquals(EXPECTED_SCHEMA_VERSION, PhoenixDatabase.Schema.version, "$scenario: schema version")
    }

    // ==================== TEST DRIVER / UPGRADE HELPERS ====================
    // Style follows SchemaParityTest: minimal v1 base, migrate each step with the
    // resilient fallback, foreign keys off during construction.

    /**
     * File-backed driver. JdbcSqliteDriver closes its connection after every
     * statement outside a transaction (ThreadedConnectionManager), so a
     * `PRAGMA foreign_keys = ON` would not survive to the next statement --
     * foreign keys are therefore enabled through the JDBC connection properties,
     * which every new connection picks up (same enforcement as production).
     */
    private fun openFileDriver(file: File): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver(
            "jdbc:sqlite:${file.absolutePath}",
            Properties().apply { setProperty("foreign_keys", "on") },
        )
        check(queryScalar(driver, "PRAGMA foreign_keys") == "1") {
            "file-backed test driver must run with foreign key enforcement ON"
        }
        return driver
    }

    /**
     * Mirrors [DriverFactory]'s post-open validation as an older binary would run
     * it: read `PRAGMA user_version`, compare against the schema version THAT
     * binary ships, and on mismatch throw the same typed
     * [DatabaseFileMigrationException] after closing the driver -- the database
     * file is never touched.
     */
    private fun openAsOlderBinary(file: File, olderExpectedVersion: Long) {
        val driver = openFileDriver(file)
        try {
            val schemaVersion = queryScalar(driver, "PRAGMA user_version")?.toLongOrNull()
            if (schemaVersion != olderExpectedVersion) {
                throw DatabaseFileMigrationException(
                    DatabaseMigrationFailureCode.TARGET_VALIDATION_FAILED,
                    "The Phoenix database schema version is invalid.",
                )
            }
        } finally {
            runCatching { driver.close() }
        }
    }

    private fun deleteDatabaseFiles(file: File) {
        for (suffix in listOf("", "-journal", "-wal", "-shm")) {
            File(file.absolutePath + suffix).delete()
        }
    }

    /**
     * The original v1 schema -- the seven tables that existed before any
     * migrations (no foreign keys on RoutineExercise; those came in migration 3).
     */
    private fun buildMinimalV1Schema(driver: SqlDriver) {
        executeSql(
            driver,
            """
            CREATE TABLE Exercise (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                created INTEGER NOT NULL DEFAULT 0,
                muscleGroup TEXT NOT NULL DEFAULT '',
                muscleGroups TEXT NOT NULL DEFAULT '[]',
                muscles TEXT NOT NULL DEFAULT '[]',
                equipment TEXT NOT NULL DEFAULT '',
                movement TEXT NOT NULL DEFAULT '',
                sidedness TEXT NOT NULL DEFAULT '',
                grip TEXT NOT NULL DEFAULT '',
                gripWidth TEXT NOT NULL DEFAULT '',
                minRepRange INTEGER NOT NULL DEFAULT 1,
                popularity INTEGER NOT NULL DEFAULT 0,
                archived INTEGER NOT NULL DEFAULT 0,
                isFavorite INTEGER NOT NULL DEFAULT 0,
                isCustom INTEGER NOT NULL DEFAULT 0,
                timesPerformed INTEGER NOT NULL DEFAULT 0,
                lastPerformed INTEGER,
                aliases TEXT NOT NULL DEFAULT '[]',
                defaultCableConfig TEXT NOT NULL DEFAULT 'DOUBLE'
            )
            """.trimIndent(),
        )
        executeSql(
            driver,
            """
            CREATE TABLE ExerciseVideo (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                exerciseId TEXT NOT NULL,
                angle TEXT NOT NULL DEFAULT '',
                videoUrl TEXT NOT NULL DEFAULT '',
                thumbnailUrl TEXT NOT NULL DEFAULT '',
                isTutorial INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        executeSql(
            driver,
            """
            CREATE TABLE WorkoutSession (
                id TEXT NOT NULL PRIMARY KEY,
                timestamp INTEGER NOT NULL,
                mode TEXT NOT NULL,
                targetReps INTEGER NOT NULL,
                weightPerCableKg REAL NOT NULL,
                progressionKg REAL NOT NULL DEFAULT 0.0,
                duration INTEGER NOT NULL DEFAULT 0,
                totalReps INTEGER NOT NULL DEFAULT 0,
                warmupReps INTEGER NOT NULL DEFAULT 0,
                workingReps INTEGER NOT NULL DEFAULT 0,
                isJustLift INTEGER NOT NULL DEFAULT 0,
                stopAtTop INTEGER NOT NULL DEFAULT 0,
                eccentricLoad INTEGER NOT NULL DEFAULT 100,
                echoLevel INTEGER NOT NULL DEFAULT 1,
                exerciseId TEXT,
                exerciseName TEXT,
                routineSessionId TEXT,
                routineName TEXT,
                routineId TEXT
            )
            """.trimIndent(),
        )
        executeSql(
            driver,
            """
            CREATE TABLE MetricSample (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sessionId TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                position REAL,
                positionB REAL,
                velocity REAL,
                velocityB REAL,
                load REAL,
                loadB REAL,
                power REAL,
                status INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (sessionId) REFERENCES WorkoutSession(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        executeSql(
            driver,
            """
            CREATE TABLE PersonalRecord (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                exerciseId TEXT NOT NULL,
                exerciseName TEXT NOT NULL DEFAULT '',
                weight REAL NOT NULL DEFAULT 0.0,
                reps INTEGER NOT NULL DEFAULT 0,
                oneRepMax REAL NOT NULL DEFAULT 0.0,
                achievedAt INTEGER NOT NULL DEFAULT 0,
                workoutMode TEXT NOT NULL DEFAULT '',
                prType TEXT NOT NULL DEFAULT 'MAX_WEIGHT',
                volume REAL
            )
            """.trimIndent(),
        )
        executeSql(
            driver,
            """
            CREATE TABLE Routine (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                createdAt INTEGER NOT NULL DEFAULT 0,
                lastUsed INTEGER,
                useCount INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        // No foreign keys in v1 RoutineExercise
        executeSql(
            driver,
            """
            CREATE TABLE RoutineExercise (
                id TEXT NOT NULL PRIMARY KEY,
                routineId TEXT NOT NULL,
                exerciseName TEXT NOT NULL,
                exerciseMuscleGroup TEXT NOT NULL DEFAULT '',
                exerciseEquipment TEXT NOT NULL DEFAULT '',
                exerciseDefaultCableConfig TEXT NOT NULL DEFAULT 'DOUBLE',
                exerciseId TEXT,
                cableConfig TEXT NOT NULL DEFAULT 'DOUBLE',
                orderIndex INTEGER NOT NULL,
                setReps TEXT NOT NULL DEFAULT '10,10,10',
                weightPerCableKg REAL NOT NULL DEFAULT 0.0,
                setWeights TEXT NOT NULL DEFAULT '',
                mode TEXT NOT NULL DEFAULT 'OldSchool',
                eccentricLoad INTEGER NOT NULL DEFAULT 100,
                echoLevel INTEGER NOT NULL DEFAULT 1,
                progressionKg REAL NOT NULL DEFAULT 0.0,
                restSeconds INTEGER NOT NULL DEFAULT 60,
                duration INTEGER,
                setRestSeconds TEXT NOT NULL DEFAULT '[]',
                perSetRestTime INTEGER NOT NULL DEFAULT 0,
                isAMRAP INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        executeSql(driver, "PRAGMA user_version = 1")
    }

    /** v1 base, then every migration up to [targetVersion] with the resilient fallback. */
    private fun buildSchemaAtVersion(driver: SqlDriver, targetVersion: Long) {
        buildMinimalV1Schema(driver)
        if (targetVersion > 1) {
            migrateWithResilience(driver, 1, targetVersion)
        }
    }

    /** Per-step migrate with `applyMigrationResilient` fallback (production onUpgrade order). */
    private fun migrateWithResilience(driver: SqlDriver, from: Long, to: Long) {
        for (v in from until to) {
            try {
                PhoenixDatabase.Schema.migrate(driver, v, v + 1)
            } catch (_: Exception) {
                applyMigrationResilient(driver, v.toInt())
            }
        }
    }

    private fun insertLegacySession(driver: SqlDriver, id: String) {
        executeSql(
            driver,
            "INSERT INTO WorkoutSession (id, timestamp, mode, targetReps, weightPerCableKg) " +
                "VALUES ('$id', 1, 'OldSchool', 10, 40.0)",
        )
    }

    // ==================== FAULT INJECTION ====================

    /** Fails every executed statement whose SQL contains [failOnSqlFragment]; null disables injection. */
    private class FaultInjectingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        var failOnSqlFragment: String? = null

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            failOnSqlFragment?.let { fragment ->
                if (sql.contains(fragment)) {
                    throw SQLException("Injected fault before: $fragment")
                }
            }
            return delegate.execute(identifier, sql, parameters, binders)
        }
    }

    // ==================== SQLITE INTROSPECTION ====================

    private fun executeSql(driver: SqlDriver, sql: String) {
        driver.execute(null, sql, 0)
    }

    private fun getTables(driver: SqlDriver): List<String> {
        val tables = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
            mapper = { cursor ->
                while (cursor.next().value) {
                    tables += cursor.getString(0).orEmpty()
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return tables
    }

    private fun getColumns(driver: SqlDriver, table: String): Map<String, String> {
        val columns = mutableMapOf<String, String>()
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info($table)",
            mapper = { cursor ->
                while (cursor.next().value) {
                    columns[cursor.getString(1).orEmpty()] = cursor.getString(2).orEmpty()
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return columns
    }

    private fun getPrimaryKeyColumns(driver: SqlDriver, table: String): List<String> {
        val columns = mutableListOf<Pair<Long, String>>()
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info($table)",
            mapper = { cursor ->
                while (cursor.next().value) {
                    val primaryKeyOrder = cursor.getLong(5) ?: 0L
                    if (primaryKeyOrder > 0) {
                        columns += primaryKeyOrder to cursor.getString(1).orEmpty()
                    }
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return columns.sortedBy { it.first }.map { it.second }
    }

    /**
     * Foreign keys as "Parent(from -> to, ...) ON DELETE <action>" strings, one
     * entry per FK (composite FKs fold their columns in declaration order).
     */
    private fun describeForeignKeys(driver: SqlDriver, table: String): List<String> {
        data class Fk(val id: Long, val parent: String, val onDelete: String, val columns: MutableList<String>)

        val byId = LinkedHashMap<Long, Fk>()
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA foreign_key_list($table)",
            mapper = { cursor ->
                while (cursor.next().value) {
                    val id = cursor.getLong(0) ?: 0L
                    val fk = byId.getOrPut(id) {
                        Fk(id, cursor.getString(2).orEmpty(), cursor.getString(6).orEmpty(), mutableListOf())
                    }
                    fk.columns += "${cursor.getString(3).orEmpty()} -> ${cursor.getString(4).orEmpty()}"
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return byId.values.map { "${it.parent}(${it.columns.joinToString(", ")}) ON DELETE ${it.onDelete}" }.sorted()
    }

    private fun getUserIndexes(driver: SqlDriver, table: String): List<String> {
        val indexes = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA index_list($table)",
            mapper = { cursor ->
                while (cursor.next().value) {
                    val name = cursor.getString(1).orEmpty()
                    if (!name.startsWith("sqlite_")) indexes += name
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return indexes.sorted()
    }

    /** The five migration-63 index definitions only (the DB carries many more). */
    private fun intervalsIndexDefinitions(driver: SqlDriver): Map<String, String> =
        getIndexDefinitions(driver).filterKeys { it in EXPECTED_INDEX_DEFINITIONS }

    /** Index name -> "Table(column, ...)" for every user index in the database. */
    private fun getIndexDefinitions(driver: SqlDriver): Map<String, String> {
        data class IndexRow(val name: String, val table: String)

        val rows = mutableListOf<IndexRow>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT name, tbl_name FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_%'",
            mapper = { cursor ->
                while (cursor.next().value) {
                    rows += IndexRow(cursor.getString(0).orEmpty(), cursor.getString(1).orEmpty())
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return rows.sortedBy { it.name }.associate { row ->
            val columns = mutableListOf<Pair<Long, String>>()
            driver.executeQuery(
                identifier = null,
                sql = "PRAGMA index_info('${row.name}')",
                mapper = { cursor ->
                    while (cursor.next().value) {
                        columns += (cursor.getLong(0) ?: 0L) to cursor.getString(2).orEmpty()
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 0,
            )
            row.name to "${row.table}(${columns.sortedBy { it.first }.joinToString(", ") { it.second }})"
        }
    }

    private fun indexExistsInDriver(driver: SqlDriver, indexName: String): Boolean {
        var exists = false
        driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE type = 'index' AND name = '$indexName'",
            mapper = { cursor ->
                exists = cursor.next().value
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return exists
    }

    /** Row descriptions of PRAGMA foreign_key_check violations; empty means clean. */
    private fun foreignKeyViolations(driver: SqlDriver): List<String> {
        val violations = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA foreign_key_check",
            mapper = { cursor ->
                while (cursor.next().value) {
                    violations += listOfNotNull(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                    ).joinToString(" / ")
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return violations
    }

    /** Returns the first column of the first row as a String, or null when NULL / no row. */
    private fun queryScalar(driver: SqlDriver, sql: String): String? {
        var value: String? = null
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                if (cursor.next().value) {
                    value = cursor.getString(0)
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return value
    }
}
