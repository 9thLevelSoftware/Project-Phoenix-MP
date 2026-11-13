package com.devil.phoenixproject.util

import app.cash.sqldelight.db.SqlDriver
import com.devil.phoenixproject.data.integration.CsvExporter
import com.devil.phoenixproject.data.integration.intervals.WorkoutCompletionFinalizer
import com.devil.phoenixproject.data.preferences.SettingsProfileLocalSafetyStore
import com.devil.phoenixproject.data.repository.SqlDelightGamificationRepository
import com.devil.phoenixproject.data.repository.SqlDelightProfilePreferencesRepository
import com.devil.phoenixproject.data.repository.SqlDelightUserProfileRepository
import com.devil.phoenixproject.data.repository.SqlDelightWorkoutRepository
import com.devil.phoenixproject.data.sync.PortalExerciseDto
import com.devil.phoenixproject.data.sync.PortalWorkoutSessionDto
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.createTestDriver
import com.russhwolf.settings.MapSettings
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Issue #1384 P3 (integration-model §7, signoff decision 5): the Intervals.icu integration
 * state — connection metadata, secret key references, completion journal/receipt rows,
 * outbox jobs, external-id reservations and installation authorization — is device-local.
 * Every backup/serializer surface is seeded with distinctive byte-sentinels in those tables
 * and asserted to carry none of them; restores revoke dispatch authorization before any row
 * write and mint no receipts or jobs; and the raw database support ZIP surface is disabled
 * (diagnostics only), including for startup-failure and quarantined dual-database cases.
 */
class IntervalsBackupExclusionTest {

    private val testJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    // ---- binding allowlist: a future field cannot slip into the wire surface silently ----

    @Test
    fun `backup wire surface has no intervals state and cannot grow one silently`() {
        assertEquals(
            emptyList(),
            backupSurfaceAllowlistViolations(),
            "BackupData/BackupContent must match the binding allowlist exactly (integration-model §7)",
        )
    }

    // ---- typed + streaming exports ----

    @Test
    fun `typed json export carries no intervals byte-sentinels`() = runTest {
        val fixture = Fixture()
        fixture.seedProfiles()
        fixture.saveSession("session-1")
        fixture.seedIntervalsState("session-1")

        val typed = fixture.manager.exportToJson()
        assertNoSentinels(typed, "typed exportToJson")
        assertNoSentinels(testJson.encodeToString(fixture.manager.exportAllData()), "typed exportAllData")
    }

    @Test
    fun `streaming export carries no intervals byte-sentinels`() = runTest {
        val fixture = Fixture()
        fixture.seedProfiles()
        fixture.saveSession("session-1")
        fixture.seedIntervalsState("session-1")

        val streamed = File(fixture.manager.exportToCachePublic()).readText()
        assertNoSentinels(streamed, "streaming export")
        // Sanity: the export really carried the training row, so absence above is meaningful.
        assertTrue("session-1" in streamed, "streaming export must carry the seeded session")
    }

    @Test
    fun `per-session and per-routine auto-exports carry no intervals byte-sentinels`() = runTest {
        val fixture = Fixture()
        fixture.seedProfiles()
        fixture.saveSession("session-1", routineSessionId = "routine-run-1")
        fixture.saveSession("session-2", routineSessionId = "routine-run-1")
        fixture.seedIntervalsState("session-1")
        val jobsBefore = fixture.queries.selectIntervalsJobCounts("default").executeAsList().size
        val journalBefore = fixture.queries.selectRequestedCompletions().executeAsList().size

        val sessionPath = fixture.manager.exportSession("session-1").getOrThrow()
        assertNoSentinels(File(sessionPath).readText(), "per-session auto-export")

        val routinePath = fixture.manager.exportRoutine("routine-run-1").getOrThrow()
        assertNoSentinels(File(routinePath).readText(), "per-routine auto-export")

        // Their successful execution never creates completion eligibility.
        assertEquals(jobsBefore, fixture.queries.selectIntervalsJobCounts("default").executeAsList().size)
        assertEquals(journalBefore, fixture.queries.selectRequestedCompletions().executeAsList().size)
    }

    // ---- CSV ----

    @Test
    fun `csv export carries no intervals byte-sentinels`() = runTest {
        val fixture = Fixture()
        fixture.seedProfiles()
        fixture.seedIntervalsState("session-1")

        val csv = CsvExporter.generateStrongCsv(
            sessions = listOf(
                WorkoutSession(
                    id = "csv-session",
                    routineSessionId = null,
                    routineName = null,
                    timestamp = 1_700_000_000_000L,
                    mode = "OLD_SCHOOL",
                    reps = 8,
                    weightPerCableKg = 30f,
                    duration = 60_000L,
                    totalReps = 8,
                    workingReps = 8,
                    profileId = "default",
                ),
            ),
            weightUnit = WeightUnit.KG,
        )
        assertNoSentinels(csv, "CSV export")
    }

    // ---- portal payloads ----

    @Test
    fun `portal dto serialization carries no intervals byte-sentinels or names`() {
        // Byte-sentinels seeded in the Intervals tables can only reach a portal payload if
        // the DTO surface had a field for them. It has not: names are allowlisted below and
        // values are explicit DTO construction, never a database dump.
        val portalDto = PortalWorkoutSessionDto(
            id = "portal-1",
            userId = "user-1",
            startedAt = "2026-01-01T00:00:00Z",
            exercises = listOf(PortalExerciseDto(id = "pe-1", sessionId = "portal-1", name = "Row")),
        )
        val json = testJson.encodeToString(portalDto)
        assertNoSentinels(json, "portal payload")

        for (descriptor in PORTAL_WIRE_DESCRIPTORS) {
            val violations = forbiddenTokenHits(descriptor, "PortalSyncDtos")
            assertTrue(
                violations.isEmpty(),
                "portal wire surface must carry no Intervals names: $violations",
            )
        }
    }

    // ---- restore / pull never mint receipts or jobs ----

    @Test
    fun `restore revokes intervals dispatch state before row replacement and mints nothing`() = runTest {
        // A backup carrying a training row whose session id matches a pending completion
        // journal's replayed child — the exact shape that could otherwise mint receipts
        // over restored rows through the live finalizer.
        val source = Fixture()
        source.seedProfiles()
        source.saveSession("session-replayed")
        val backup = source.manager.exportToJson()

        val target = Fixture()
        target.seedProfiles()
        target.seedIntervalsState("session-replayed")

        val result = target.manager.importFromJson(backup).getOrThrow()
        assertEquals(0, result.entitiesWithErrors)

        // Dispatch authorization revoked before row replacement: journal, jobs,
        // reservations and connection metadata are gone (secrets never live in SQL).
        assertTrue(target.queries.selectRequestedCompletions().executeAsList().isEmpty(), "journal revoked")
        assertTrue(target.queries.selectIntervalsJobCounts("default").executeAsList().isEmpty(), "jobs revoked")
        assertNull(target.queries.selectIntervalsStatus("default").executeAsOneOrNull(), "connection revoked")
        assertNull(
            target.queries.selectIntervalsExternalIdReservation(ATHLETE_SENTINEL, EXTERNAL_ID_SENTINEL).executeAsOneOrNull(),
            "reservations revoked",
        )
        // Historical receipts survive (facts), and the restore added none.
        val receipts = target.queries.selectCompletionReceiptsByExternalId(EXTERNAL_ID_SENTINEL).executeAsList()
        assertEquals(1, receipts.size, "restore neither deletes nor mints receipts")

        // The live finalizer cannot mint over the restored rows: the replayable journal is
        // revoked, so recovery is a no-op and the receipt count stands still.
        val outcomes = WorkoutCompletionFinalizer(target.database, target.workoutRepository).finalizePending()
        assertTrue(outcomes.isEmpty(), "no pending admission may survive a restore")
        assertEquals(
            1,
            target.queries.selectCompletionReceiptsByExternalId(EXTERNAL_ID_SENTINEL).executeAsList().size,
        )

        // Pull-style writes replay through the ordinary session machinery, never the
        // live-completion entry point: they mint no receipts and no jobs.
        target.workoutRepository.saveSession(
            WorkoutSession(
                id = "pulled-session",
                routineSessionId = null,
                routineName = null,
                timestamp = 1_700_000_100_000L,
                mode = "OLD_SCHOOL",
                reps = 5,
                weightPerCableKg = 20f,
                duration = 30_000L,
                totalReps = 5,
                workingReps = 5,
                profileId = "default",
            ),
        )
        assertZeroJobsAndJournal(target)
        assertEquals(
            1,
            target.queries.selectCompletionReceiptsByExternalId(EXTERNAL_ID_SENTINEL).executeAsList().size,
            "pull-style writes never mint receipts",
        )
    }

    @Test
    fun `a backup carrying intervals sections restores none of them`() = runTest {
        val source = Fixture()
        source.seedProfiles()
        source.saveSession("session-1")
        val root = testJson.parseToJsonElement(source.manager.exportToJson()).let { it as kotlinx.serialization.json.JsonObject }
        val data = root.getValue("data") as kotlinx.serialization.json.JsonObject
        val tamperedData = kotlinx.serialization.json.JsonObject(
            data + mapOf(
                "workoutCompletionReceipts" to testJson.parseToJsonElement(
                    """[{"profile_id":"default","external_id":"$EXTERNAL_ID_SENTINEL","membership_json":"$MEMBERSHIP_SENTINEL"}]""",
                ),
                "intervalsOutbox" to testJson.parseToJsonElement(
                    """[{"payload_json":"$PAYLOAD_SENTINEL"}]""",
                ),
                "intervalsConnection" to testJson.parseToJsonElement(
                    """[{"secret_key_ref":"$KEY_REF_MARKER","resolved_athlete_id":"$ATHLETE_SENTINEL"}]""",
                ),
            ),
        )
        val tampered = kotlinx.serialization.json.JsonObject(root + mapOf("data" to tamperedData)).toString()

        val target = Fixture()
        target.seedProfiles()
        val result = target.manager.importFromJson(tampered).getOrThrow()
        assertEquals(0, result.entitiesWithErrors, "unknown Intervals sections are skipped, not fatal")

        assertNull(
            target.queries.selectCompletionReceipt("default", "ROUTINE", "restored-completion").executeAsOneOrNull(),
        )
        assertTrue(target.queries.selectCompletionReceiptsByExternalId(EXTERNAL_ID_SENTINEL).executeAsList().isEmpty())
        assertTrue(target.queries.selectIntervalsJobCounts("default").executeAsList().isEmpty())
        assertNull(target.queries.selectIntervalsStatus("default").executeAsOneOrNull())
        assertTrue(target.queries.selectRequestedCompletions().executeAsList().isEmpty())
        assertZeroJobsAndJournal(target)
    }

    // ---- raw support ZIP surface (signoff decision 5) ----

    @Test
    fun `raw zip surface is disabled for startup-failure and quarantine cases`() {
        // No toggle exists (the inert RAW_DATABASE_SHARING_ENABLED knob was deleted):
        // suppression is pinned by the archive-content assertions below — raw DB/WAL
        // bytes must never appear as zip entries (signoff decision 5).

        // The startup-failure dual-databases case (DB_DUAL_DATABASES) is exactly when the
        // old flow shared raw bytes — including quarantined, unsafe-to-open candidates.
        val databases = File(workDir, "databases").apply { mkdirs() }
        val sentinels = mapOf(
            "phoenix.db" to SENTINEL_BYTES,
            "phoenix.db-wal" to "marker-wal-bytes".encodeToByteArray(),
            "phoenix.db-shm" to "marker-shm-bytes".encodeToByteArray(),
            "phoenix.db-journal" to "marker-journal-bytes".encodeToByteArray(),
        )
        sentinels.forEach { (name, bytes) -> File(databases, name).writeBytes(bytes) }
        val quarantine = File(databases, com.devil.phoenixproject.data.local.DATABASE_QUARANTINE_DIRECTORY).apply { mkdirs() }
        File(quarantine, "1700000000000-DUAL_DATABASES-phoenix.db").writeBytes(SENTINEL_BYTES)

        val archive = buildDatabaseArchive(databases, File(workDir, "cache"))!!
        java.util.zip.ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toList()
            assertEquals(
                listOf(DatabaseFileExport.DIAGNOSTICS_ENTRY_NAME),
                entries,
                "the support archive may carry diagnostics only — never raw DB/WAL/SHM/journal/quarantine bytes",
            )
            val report = zip.getInputStream(zip.entries().nextElement()).readBytes().decodeToString()
            assertNoSentinels(report, "support diagnostics")
        }
    }

    // ---- helpers ----

    private val workDir: File = kotlin.io.path.createTempDirectory("intervals-exclusion").toFile()
        .also { it.deleteOnExit() }

    private fun assertNoSentinels(text: String, surface: String) {
        for (sentinel in ALL_SENTINELS) {
            assertFalse(
                text.contains(sentinel),
                "$surface must not carry Intervals sentinel '$sentinel' (integration-model §7)",
            )
        }
    }

    private fun assertZeroJobsAndJournal(fixture: Fixture) {
        assertTrue(fixture.queries.selectIntervalsJobCounts("default").executeAsList().isEmpty())
        assertTrue(fixture.queries.selectRequestedCompletions().executeAsList().isEmpty())
    }

    private fun forbiddenTokenHits(descriptor: SerialDescriptor, path: String): List<String> {
        val hits = mutableListOf<String>()
        val visited = mutableSetOf<SerialDescriptor>()
        fun walk(descriptor: SerialDescriptor, path: String) {
            // Guard against cyclic/self-referential descriptors.
            if (!visited.add(descriptor)) return
            val serialName = descriptor.serialName.lowercase()
            for (token in INTERVALS_STATE_FORBIDDEN_TOKENS) {
                if (token in serialName) hits += "$path serial '${descriptor.serialName}' ~ '$token'"
            }
            for (index in 0 until descriptor.elementsCount) {
                val name = descriptor.getElementName(index)
                val lowered = name.lowercase()
                for (token in INTERVALS_STATE_FORBIDDEN_TOKENS) {
                    if (token in lowered) hits += "$path.$name ~ '$token'"
                }
                walk(descriptor.getElementDescriptor(index), "$path.$name")
            }
        }
        walk(descriptor, path)
        return hits
    }

    // ---- fixture ----

    private class Fixture {
        val driver: SqlDriver = createTestDriver()
        val database = PhoenixDatabase(driver)
        val queries = database.phoenixDatabaseQueries
        val workoutRepository = SqlDelightWorkoutRepository(database, FakeExerciseRepository())
        val manager = TestManager(database)

        fun seedProfiles() {
            queries.insertUserProfileIgnore("default", "Default", 0L, 1L, 1L)
            queries.insertDefaultProfilePreferences("default", 1L)
        }

        suspend fun saveSession(id: String, routineSessionId: String? = null) {
            workoutRepository.saveSession(
                WorkoutSession(
                    id = id,
                    routineSessionId = routineSessionId,
                    routineName = routineSessionId?.let { "Day" },
                    timestamp = 1_700_000_000_000L,
                    mode = "OLD_SCHOOL",
                    reps = 8,
                    weightPerCableKg = 30f,
                    duration = 60_000L,
                    totalReps = 8,
                    workingReps = 8,
                    profileId = "default",
                ),
            )
        }

        /**
         * Seeds DISTINCTIVE sentinel rows into EVERY Intervals.icu table: secret key
         * reference (a reference only — the secret itself lives in the platform secure
         * store and can never appear here), resolved athlete id, installation id, literal
         * external id, job payload bytes, journal + replayed child and a receipt row.
         */
        fun seedIntervalsState(sessionId: String) {
            queries.insertIntervalsConnectionIfAbsent(
                profile_id = "default",
                resolved_athlete_id = ATHLETE_SENTINEL,
                connection_generation = 1L,
                dispatch_auth_generation = 1L,
                installation_id = INSTALLATION_SENTINEL,
                secret_key_ref = KEY_REF_MARKER,
                enabled = 1L,
                paused = 0L,
                auth_required = 0L,
                validated_at = NOW,
                enabled_at = NOW,
                paused_at = null,
                consent_version = 1L,
                disclosure_version = 1L,
                eligibility_epoch = 1L,
                updated_at = NOW,
            )
            queries.insertCompletionJournalIfAbsent(
                profile_id = "default",
                local_kind = "ROUTINE",
                completion_id = "completion-1",
                finish_reason = "FINISHED",
                routine_title = JOURNAL_TITLE_SENTINEL,
                expected_session_count = 1L,
                expected_set_count = 1L,
                membership_digest = DIGEST_SENTINEL,
                start_epoch_ms = NOW,
                start_local_iso = "2026-01-01T00:00:00",
                time_zone_id = "America/New_York",
                utc_offset_minutes = -300L,
                completed_epoch_ms = NOW + 60_000L,
                eligible = 1L,
                resolved_athlete_id = ATHLETE_SENTINEL,
                connection_generation = 1L,
                destination_digest = DIGEST_SENTINEL,
                created_at = NOW,
            )
            queries.insertCompletionJournalChildIfAbsent(
                journal_profile_id = "default",
                journal_local_kind = "ROUTINE",
                journal_completion_id = "completion-1",
                child_key = "$sessionId|SESSION",
                child_kind = "SESSION",
                session_id = sessionId,
                codec_version = 1L,
                replay_payload = CHILD_PAYLOAD_SENTINEL,
            )
            queries.insertCompletionReceiptIfAbsent(
                profile_id = "default",
                local_kind = "ROUTINE",
                completion_id = "completion-1",
                external_id = EXTERNAL_ID_SENTINEL,
                snapshot_version = 1L,
                membership_json = MEMBERSHIP_SENTINEL,
                finish_reason = "FINISHED",
                start_epoch_ms = NOW,
                start_local_iso = "2026-01-01T00:00:00",
                time_zone_id = "America/New_York",
                utc_offset_minutes = -300L,
                completed_epoch_ms = NOW + 60_000L,
                eligible = 1L,
                resolved_athlete_id = ATHLETE_SENTINEL,
                connection_generation = 1L,
                export_payload = PAYLOAD_SENTINEL,
                payload_digest = DIGEST_SENTINEL,
                created_at = NOW,
            )
            queries.reserveIntervalsExternalId(
                resolved_athlete_id = ATHLETE_SENTINEL,
                external_id = EXTERNAL_ID_SENTINEL,
                owner_profile_id = "default",
                owner_local_kind = "ROUTINE",
                owner_completion_id = "completion-1",
                created_at = NOW,
            )
            queries.insertIntervalsJobIfAbsent(
                profile_id = "default",
                resolved_athlete_id = ATHLETE_SENTINEL,
                external_id = EXTERNAL_ID_SENTINEL,
                receipt_profile_id = "default",
                receipt_local_kind = "ROUTINE",
                receipt_completion_id = "completion-1",
                payload_json = PAYLOAD_SENTINEL,
                payload_version = 1L,
                payload_digest = DIGEST_SENTINEL,
                original_generation = 1L,
                dispatch_auth_generation = 1L,
                created_at = NOW,
            )
        }
    }

    private class TestManager(
        database: PhoenixDatabase,
    ) : BaseDataBackupManager(
        database,
        SqlDelightProfilePreferencesRepository(database),
        SqlDelightUserProfileRepository(
            database = database,
            profilePreferencesRepository = SqlDelightProfilePreferencesRepository(database),
            profileLocalSafetyStore = SettingsProfileLocalSafetyStore(MapSettings()),
            gamificationRepository = SqlDelightGamificationRepository(database),
        ),
    ) {
        private val dir = kotlin.io.path.createTempDirectory("intervals-backups").toFile()

        suspend fun exportToCachePublic(): String = exportToCache()

        override fun createBackupWriter(): BackupJsonWriter =
            BackupJsonWriter(File.createTempFile("intervals-export-", ".json", dir).absolutePath)

        override suspend fun finalizeExport(tempFilePath: String): Result<String> = Result.success(tempFilePath)
        override suspend fun importFromFile(filePath: String): Result<ImportResult> = error("unused")
        override suspend fun shareBackup() = Unit
        override fun getSessionBackupDirectory(): String = dir.absolutePath
        override fun listBackupFileSizes(): List<Long> = emptyList()
        override fun openBackupFolder() = Unit
        override fun pruneOldBackups(keepCount: Int) = Unit
    }

    private companion object {
        const val NOW = 1_700_000_000_000L

        // Distinctive byte-sentinels (integration-model §7): seeded into every Intervals
        // table and asserted absent from every serializer/backup/portal/CSV surface.
        const val KEY_REF_MARKER = "marker-key-ref-alpha"
        const val ATHLETE_SENTINEL = "marker-resolved-athlete"
        const val EXTERNAL_ID_SENTINEL = "marker-external-id"
        const val PAYLOAD_SENTINEL = "marker-payload-json"
        const val MEMBERSHIP_SENTINEL = "marker-membership-json"
        const val DIGEST_SENTINEL = "marker-payload-digest"
        const val CHILD_PAYLOAD_SENTINEL = "marker-child-replay"
        const val INSTALLATION_SENTINEL = "marker-installation-id"
        const val JOURNAL_TITLE_SENTINEL = "marker-routine-title"

        val SENTINEL_BYTES = "marker-db-page-bytes".encodeToByteArray()

        val ALL_SENTINELS = listOf(
            KEY_REF_MARKER,
            ATHLETE_SENTINEL,
            EXTERNAL_ID_SENTINEL,
            PAYLOAD_SENTINEL,
            MEMBERSHIP_SENTINEL,
            DIGEST_SENTINEL,
            CHILD_PAYLOAD_SENTINEL,
            INSTALLATION_SENTINEL,
            JOURNAL_TITLE_SENTINEL,
        )

        val PORTAL_WIRE_DESCRIPTORS = listOf(
            PortalWorkoutSessionDto.serializer().descriptor,
            PortalExerciseDto.serializer().descriptor,
        )
    }
}
