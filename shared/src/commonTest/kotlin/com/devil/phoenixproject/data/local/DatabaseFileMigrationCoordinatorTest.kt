package com.devil.phoenixproject.data.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseFileMigrationCoordinatorTest {
    private val fingerprint = DatabaseFingerprint(
        fileSize = 16_384,
        userVersion = 42,
        pageCount = 4,
        freePageCount = 0,
    )

    @Test
    fun `fresh install permits target creation and validates it after initialization`() {
        val operations = FakeDatabaseFileOperations()
        val coordinator = DatabaseFileMigrationCoordinator(operations)

        val preparation = coordinator.prepareTarget()

        assertEquals(DatabasePreparation(migratedThisLaunch = false, recoveryCleanupDue = false), preparation)
        assertEquals(listOf("lock:start", "inspect", "lock:end"), operations.calls)

        operations.add(DatabaseArtifact.TARGET, fingerprint)
        coordinator.targetValidated(preparation)

        assertEquals(listOf("lock:start", "validate:TARGET", "lock:end"), operations.calls.takeLast(3))
    }

    @Test
    fun `legacy upgrade creates verified recovery before deleting sidecars and cutting over`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.LEGACY),
            legacySidecarsExist = true,
            fingerprints = mapOf(DatabaseArtifact.LEGACY to fingerprint),
        )

        val preparation = DatabaseFileMigrationCoordinator(operations).prepareTarget()

        assertEquals(DatabasePreparation(migratedThisLaunch = true, recoveryCleanupDue = false), preparation)
        assertEquals(
            listOf(
                "lock:start",
                "inspect",
                "checkpoint:LEGACY",
                "copy:LEGACY:STAGING",
                "sync:STAGING",
                "validate:STAGING",
                "move:STAGING:RECOVERY",
                "deleteLegacySidecars",
                "move:LEGACY:TARGET",
                "lock:end",
            ),
            operations.calls,
        )
        assertTrue(operations.exists(DatabaseArtifact.TARGET))
        assertTrue(operations.exists(DatabaseArtifact.RECOVERY))
        assertFalse(operations.exists(DatabaseArtifact.LEGACY))
        assertFalse(operations.exists(DatabaseArtifact.STAGING))
        assertFalse(operations.sidecarsExist())
    }

    @Test
    fun `interrupted staging is discarded before legacy migration restarts`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.LEGACY, DatabaseArtifact.STAGING),
            fingerprints = mapOf(DatabaseArtifact.LEGACY to fingerprint),
        )

        DatabaseFileMigrationCoordinator(operations).prepareTarget()

        assertEquals("delete:STAGING", operations.calls[2])
        assertTrue(operations.calls.indexOf("delete:STAGING") < operations.calls.indexOf("checkpoint:LEGACY"))
        assertTrue(operations.exists(DatabaseArtifact.TARGET))
        assertTrue(operations.exists(DatabaseArtifact.RECOVERY))
    }

    @Test
    fun `interrupted recovery is reused only after matching the checkpointed legacy source`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.LEGACY, DatabaseArtifact.RECOVERY),
            legacySidecarsExist = true,
            fingerprints = mapOf(
                DatabaseArtifact.LEGACY to fingerprint,
                DatabaseArtifact.RECOVERY to fingerprint,
            ),
        )

        DatabaseFileMigrationCoordinator(operations).prepareTarget()

        assertEquals(
            listOf(
                "lock:start",
                "inspect",
                "checkpoint:LEGACY",
                "validate:RECOVERY",
                "deleteLegacySidecars",
                "move:LEGACY:TARGET",
                "lock:end",
            ),
            operations.calls,
        )
    }

    @Test
    fun `legacy recovery and staging discard only staging then reuse recovery`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.LEGACY, DatabaseArtifact.RECOVERY, DatabaseArtifact.STAGING),
            fingerprints = mapOf(
                DatabaseArtifact.LEGACY to fingerprint,
                DatabaseArtifact.RECOVERY to fingerprint,
            ),
        )

        DatabaseFileMigrationCoordinator(operations).prepareTarget()

        assertEquals("delete:STAGING", operations.calls[2])
        assertFalse(operations.calls.any { it == "copy:LEGACY:STAGING" })
        assertTrue(operations.exists(DatabaseArtifact.TARGET))
        assertTrue(operations.exists(DatabaseArtifact.RECOVERY))
    }

    @Test
    fun `target only is retained for post initialization validation`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.TARGET),
            fingerprints = mapOf(DatabaseArtifact.TARGET to fingerprint),
        )
        val coordinator = DatabaseFileMigrationCoordinator(operations)

        val preparation = coordinator.prepareTarget()
        coordinator.targetValidated(preparation)

        assertEquals(DatabasePreparation(migratedThisLaunch = false, recoveryCleanupDue = false), preparation)
        assertTrue(operations.exists(DatabaseArtifact.TARGET))
        assertEquals(1, operations.calls.count { it == "validate:TARGET" })
    }

    @Test
    fun `recovery only reconstructs target through verified staging and keeps recovery`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.RECOVERY),
            fingerprints = mapOf(DatabaseArtifact.RECOVERY to fingerprint),
        )

        val preparation = DatabaseFileMigrationCoordinator(operations).prepareTarget()

        assertEquals(DatabasePreparation(migratedThisLaunch = true, recoveryCleanupDue = false), preparation)
        assertEquals(
            listOf(
                "lock:start",
                "inspect",
                "validate:RECOVERY",
                "copy:RECOVERY:STAGING",
                "sync:STAGING",
                "validate:STAGING",
                "move:STAGING:TARGET",
                "lock:end",
            ),
            operations.calls,
        )
        assertTrue(operations.exists(DatabaseArtifact.RECOVERY))
        assertTrue(operations.exists(DatabaseArtifact.TARGET))
    }

    @Test
    fun `interrupted recovery reconstruction discards staging and resumes from recovery`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.RECOVERY, DatabaseArtifact.STAGING),
            fingerprints = mapOf(DatabaseArtifact.RECOVERY to fingerprint),
        )

        DatabaseFileMigrationCoordinator(operations).prepareTarget()

        assertEquals("delete:STAGING", operations.calls[2])
        assertTrue(operations.calls.indexOf("delete:STAGING") < operations.calls.indexOf("validate:RECOVERY"))
        assertTrue(operations.exists(DatabaseArtifact.TARGET))
    }

    @Test
    fun `clean restart deletes recovery only after target validation`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.TARGET, DatabaseArtifact.RECOVERY),
            fingerprints = mapOf(
                DatabaseArtifact.TARGET to fingerprint,
                DatabaseArtifact.RECOVERY to fingerprint,
            ),
        )
        val coordinator = DatabaseFileMigrationCoordinator(operations)

        val preparation = coordinator.prepareTarget()

        assertTrue(preparation.recoveryCleanupDue)
        assertTrue(operations.exists(DatabaseArtifact.RECOVERY))

        coordinator.targetValidated(preparation)

        assertTrue(operations.calls.indexOf("validate:TARGET") < operations.calls.indexOf("delete:RECOVERY"))
        assertFalse(operations.exists(DatabaseArtifact.RECOVERY))
    }

    @Test
    fun `recovery cleanup failure preserves validated target for retry`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.TARGET, DatabaseArtifact.RECOVERY),
            fingerprints = mapOf(
                DatabaseArtifact.TARGET to fingerprint,
                DatabaseArtifact.RECOVERY to fingerprint,
            ),
            failOnceAt = "delete:RECOVERY",
        )
        val coordinator = DatabaseFileMigrationCoordinator(operations)
        val preparation = coordinator.prepareTarget()

        assertFailsWith<DatabaseFileMigrationException> {
            coordinator.targetValidated(preparation)
        }

        val retry = coordinator.prepareTarget()
        coordinator.targetValidated(retry)

        assertTrue(operations.exists(DatabaseArtifact.TARGET))
        assertFalse(operations.exists(DatabaseArtifact.RECOVERY))
        assertFalse(operations.calls.any { it == "delete:TARGET" })
    }

    @Test
    fun `stale staging beside target is discarded before clean restart validation`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.TARGET, DatabaseArtifact.RECOVERY, DatabaseArtifact.STAGING),
            fingerprints = mapOf(
                DatabaseArtifact.TARGET to fingerprint,
                DatabaseArtifact.RECOVERY to fingerprint,
            ),
        )
        val coordinator = DatabaseFileMigrationCoordinator(operations)

        val preparation = coordinator.prepareTarget()
        coordinator.targetValidated(preparation)

        assertEquals("delete:STAGING", operations.calls[2])
        assertFalse(operations.exists(DatabaseArtifact.STAGING))
        assertFalse(operations.exists(DatabaseArtifact.RECOVERY))
    }

    @Test
    fun `canonical dual conflict preserves safe reason and pre-mutation snapshot`() {
        val snapshot = DatabasePresenceSnapshot(
            libraryLegacy = DatabasePresence(main = true, wal = true, shm = false, journal = false),
            sqliterLegacy = DatabasePresence(main = true, wal = false, shm = true, journal = false),
            target = DatabasePresence(main = true, wal = false, shm = false, journal = false),
            recovery = DatabasePresence(main = false, wal = false, shm = false, journal = false),
            staging = DatabasePresence(main = false, wal = false, shm = false, journal = true),
        )
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.LEGACY, DatabaseArtifact.TARGET),
            presenceSnapshot = snapshot,
        )

        val failure = assertFailsWith<DatabaseFileMigrationException> {
            DatabaseFileMigrationCoordinator(operations).prepareTarget()
        }

        assertEquals(DatabaseDiagnosticReason.CANONICAL_LEGACY_TARGET, failure.diagnosticReason)
        assertEquals(snapshot, failure.presenceSnapshot)
        // #764: an uninspectable legacy candidate stops the recovery before anything else runs.
        assertEquals(listOf("lock:start", "inspect", "probe:LEGACY", "lock:end"), operations.calls)
    }

    @Test
    fun `dual databases preserve every artifact and block before validation`() {
        val allArtifacts = DatabaseArtifact.entries.toSet()
        val operations = FakeDatabaseFileOperations(artifacts = allArtifacts, legacySidecarsExist = true)

        val failure = assertFailsWith<DatabaseFileMigrationException> {
            DatabaseFileMigrationCoordinator(operations).prepareTarget()
        }

        assertEquals(DatabaseMigrationFailureCode.DUAL_DATABASES, failure.code)
        assertEquals(allArtifacts, operations.artifacts())
        assertTrue(operations.sidecarsExist())
        assertEquals(listOf("lock:start", "inspect", "lock:end"), operations.calls)
    }

    @Test
    fun `retry with unchanged dual databases fails the same way without touching any file`() {
        val allArtifacts = DatabaseArtifact.entries.toSet()
        val operations = FakeDatabaseFileOperations(
            artifacts = allArtifacts,
            legacySidecarsExist = true,
            fingerprints = allArtifacts.associateWith { fingerprint },
        )
        // Startup retry re-resolves Koin, which reuses the same DriverFactory coordinator.
        val coordinator = DatabaseFileMigrationCoordinator(operations)
        val first = assertFailsWith<DatabaseFileMigrationException> { coordinator.prepareTarget() }

        val retry = assertFailsWith<DatabaseFileMigrationException> { coordinator.prepareTarget() }

        assertEquals(DatabaseMigrationFailureCode.DUAL_DATABASES, first.code)
        assertEquals(first.code, retry.code)
        assertEquals(first.startupDiagnosticCode, retry.startupDiagnosticCode)
        // Regression check for this change: DUAL_DATABASES now allows retry. The assertions below are
        // invariants that keep any future guard from mutating files on an unchanged retry.
        assertTrue(retry.startupRetryAllowed)
        assertEquals(allArtifacts, operations.artifacts())
        assertTrue(operations.sidecarsExist())
        assertEquals(List(2) { listOf("lock:start", "inspect", "lock:end") }.flatten(), operations.calls)
    }

    @Test
    fun `issue 764 an empty legacy candidate beside a target with data is set aside and the target opens`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.LEGACY, DatabaseArtifact.TARGET),
            contents = mapOf(
                DatabaseArtifact.LEGACY to CandidateContent.EMPTY,
                DatabaseArtifact.TARGET to CandidateContent.HAS_USER_DATA,
            ),
        )

        val preparation = DatabaseFileMigrationCoordinator(operations).prepareTarget()

        assertFalse(preparation.migratedThisLaunch)
        assertEquals(listOf(DatabaseArtifact.LEGACY), operations.quarantined)
        assertEquals(setOf(DatabaseArtifact.TARGET), operations.artifacts())
        assertEquals(
            listOf("lock:start", "inspect", "probe:LEGACY", "probe:TARGET", "quarantine:LEGACY", "inspect", "lock:end"),
            operations.calls,
        )
    }

    @Test
    fun `issue 764 an empty target beside a legacy database with data is set aside and the legacy migrates`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.LEGACY, DatabaseArtifact.TARGET),
            contents = mapOf(
                DatabaseArtifact.LEGACY to CandidateContent.HAS_USER_DATA,
                DatabaseArtifact.TARGET to CandidateContent.EMPTY,
            ),
        )

        val preparation = DatabaseFileMigrationCoordinator(operations).prepareTarget()

        assertTrue(preparation.migratedThisLaunch)
        assertEquals(listOf(DatabaseArtifact.TARGET), operations.quarantined)
        assertEquals(setOf(DatabaseArtifact.TARGET, DatabaseArtifact.RECOVERY), operations.artifacts())
        // Both probes run before the first mutation of any file.
        val firstMutation = operations.calls.indexOfFirst { it.startsWith("quarantine") }
        assertTrue(operations.calls.indexOf("probe:LEGACY") < firstMutation)
        assertTrue(operations.calls.indexOf("probe:TARGET") < firstMutation)
        assertTrue(operations.calls.contains("move:LEGACY:TARGET"))
    }

    @Test
    fun `issue 764 two empty candidates keep the target and set the legacy file aside`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.LEGACY, DatabaseArtifact.TARGET),
            contents = mapOf(
                DatabaseArtifact.LEGACY to CandidateContent.EMPTY,
                DatabaseArtifact.TARGET to CandidateContent.EMPTY,
            ),
        )

        DatabaseFileMigrationCoordinator(operations).prepareTarget()

        assertEquals(listOf(DatabaseArtifact.LEGACY), operations.quarantined)
        assertEquals(setOf(DatabaseArtifact.TARGET), operations.artifacts())
    }

    @Test
    fun `issue 764 conflicts that are not provably lossless stay fail closed without moving anything`() {
        val unresolvable = listOf(
            CandidateContent.HAS_USER_DATA to CandidateContent.HAS_USER_DATA,
            CandidateContent.HAS_USER_DATA to CandidateContent.UNINSPECTABLE,
            CandidateContent.EMPTY to CandidateContent.UNINSPECTABLE,
            CandidateContent.UNINSPECTABLE to CandidateContent.EMPTY,
        )
        unresolvable.forEach { (legacy, target) ->
            val operations = FakeDatabaseFileOperations(
                artifacts = setOf(DatabaseArtifact.LEGACY, DatabaseArtifact.TARGET),
                contents = mapOf(DatabaseArtifact.LEGACY to legacy, DatabaseArtifact.TARGET to target),
            )

            val failure = assertFailsWith<DatabaseFileMigrationException> {
                DatabaseFileMigrationCoordinator(operations).prepareTarget()
            }

            assertEquals(DatabaseMigrationFailureCode.DUAL_DATABASES, failure.code, "$legacy/$target")
            assertEquals(DatabaseDiagnosticReason.CANONICAL_LEGACY_TARGET, failure.diagnosticReason)
            assertTrue(operations.quarantined.isEmpty(), "$legacy/$target moved a file")
            assertEquals(setOf(DatabaseArtifact.LEGACY, DatabaseArtifact.TARGET), operations.artifacts())
        }
    }

    @Test
    fun `issue 764 a recovery or staging file beside the conflict blocks the recovery before probing`() {
        listOf(DatabaseArtifact.RECOVERY, DatabaseArtifact.STAGING).forEach { extra ->
            val operations = FakeDatabaseFileOperations(
                artifacts = setOf(DatabaseArtifact.LEGACY, DatabaseArtifact.TARGET, extra),
                contents = mapOf(
                    DatabaseArtifact.LEGACY to CandidateContent.EMPTY,
                    DatabaseArtifact.TARGET to CandidateContent.HAS_USER_DATA,
                ),
            )

            assertFailsWith<DatabaseFileMigrationException> {
                DatabaseFileMigrationCoordinator(operations).prepareTarget()
            }

            assertTrue(operations.calls.none { it.startsWith("probe") || it.startsWith("quarantine") })
        }
    }

    @Test
    fun `issue 764 a failed set-aside keeps the same fail-closed error and the next launch finishes it`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.LEGACY, DatabaseArtifact.TARGET),
            failOnceAt = "quarantine:LEGACY",
            contents = mapOf(
                DatabaseArtifact.LEGACY to CandidateContent.EMPTY,
                DatabaseArtifact.TARGET to CandidateContent.HAS_USER_DATA,
            ),
        )
        val coordinator = DatabaseFileMigrationCoordinator(operations)

        val failure = assertFailsWith<DatabaseFileMigrationException> { coordinator.prepareTarget() }
        assertEquals(DatabaseMigrationFailureCode.DUAL_DATABASES, failure.code)
        assertEquals(setOf(DatabaseArtifact.LEGACY, DatabaseArtifact.TARGET), operations.artifacts())

        coordinator.prepareTarget()
        assertEquals(setOf(DatabaseArtifact.TARGET), operations.artifacts())
    }

    @Test
    fun `issue 764 a probe that throws counts as uninspectable`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.LEGACY, DatabaseArtifact.TARGET),
            failAt = "probe:LEGACY",
            contents = mapOf(
                DatabaseArtifact.LEGACY to CandidateContent.EMPTY,
                DatabaseArtifact.TARGET to CandidateContent.HAS_USER_DATA,
            ),
        )

        assertFailsWith<DatabaseFileMigrationException> {
            DatabaseFileMigrationCoordinator(operations).prepareTarget()
        }
        assertTrue(operations.quarantined.isEmpty())
    }

    @Test
    fun `orphan staging blocks without deleting its only remaining artifact`() {
        val operations = FakeDatabaseFileOperations(artifacts = setOf(DatabaseArtifact.STAGING))

        val failure = assertFailsWith<DatabaseFileMigrationException> {
            DatabaseFileMigrationCoordinator(operations).prepareTarget()
        }

        assertEquals(DatabaseMigrationFailureCode.RECOVERY_COPY_FAILED, failure.code)
        assertEquals(setOf(DatabaseArtifact.STAGING), operations.artifacts())
        assertFalse(operations.calls.any { it.startsWith("delete:") })
    }

    @Test
    fun `checkpoint failure prevents copy cleanup and cutover`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.LEGACY),
            failAt = "checkpoint:LEGACY",
        )

        val failure = assertFailsWith<DatabaseFileMigrationException> {
            DatabaseFileMigrationCoordinator(operations).prepareTarget()
        }

        assertEquals(DatabaseMigrationFailureCode.CHECKPOINT_FAILED, failure.code)
        assertEquals(setOf(DatabaseArtifact.LEGACY), operations.artifacts())
        assertEquals(listOf("lock:start", "inspect", "checkpoint:LEGACY", "lock:end"), operations.calls)
    }

    @Test
    fun `corrupt legacy source preserves it and reports integrity failure`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.LEGACY),
            failures = mapOf(
                "checkpoint:LEGACY" to DatabaseFileMigrationException(
                    DatabaseMigrationFailureCode.INTEGRITY_CHECK_FAILED,
                    "Legacy database integrity check failed.",
                ),
            ),
        )

        val failure = assertFailsWith<DatabaseFileMigrationException> {
            DatabaseFileMigrationCoordinator(operations).prepareTarget()
        }

        assertEquals(DatabaseMigrationFailureCode.INTEGRITY_CHECK_FAILED, failure.code)
        assertEquals(setOf(DatabaseArtifact.LEGACY), operations.artifacts())
        assertFalse(operations.calls.any { it.startsWith("copy:") || it.startsWith("move:") })
    }

    @Test
    fun `copy failure leaves legacy untouched and skips later destructive operations`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.LEGACY),
            fingerprints = mapOf(DatabaseArtifact.LEGACY to fingerprint),
            failAt = "copy:LEGACY:STAGING",
        )

        val failure = assertFailsWith<DatabaseFileMigrationException> {
            DatabaseFileMigrationCoordinator(operations).prepareTarget()
        }

        assertEquals(DatabaseMigrationFailureCode.RECOVERY_COPY_FAILED, failure.code)
        assertEquals(setOf(DatabaseArtifact.LEGACY), operations.artifacts())
        assertFalse(operations.calls.any { it == "deleteLegacySidecars" || it == "move:LEGACY:TARGET" })
    }

    @Test
    fun `fingerprint mismatch preserves source and staging and prevents recovery promotion`() {
        val changedFingerprint = fingerprint.copy(fileSize = fingerprint.fileSize + 1)
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.LEGACY),
            fingerprints = mapOf(
                DatabaseArtifact.LEGACY to fingerprint,
                DatabaseArtifact.STAGING to changedFingerprint,
            ),
        )

        val failure = assertFailsWith<DatabaseFileMigrationException> {
            DatabaseFileMigrationCoordinator(operations).prepareTarget()
        }

        assertEquals(DatabaseMigrationFailureCode.RECOVERY_COPY_FAILED, failure.code)
        assertTrue(operations.exists(DatabaseArtifact.LEGACY))
        assertTrue(operations.exists(DatabaseArtifact.STAGING))
        assertFalse(operations.calls.any { it.startsWith("move:") || it == "deleteLegacySidecars" })
    }

    @Test
    fun `recovery promotion failure preserves legacy and staging and skips cleanup`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.LEGACY),
            fingerprints = mapOf(DatabaseArtifact.LEGACY to fingerprint),
            failAt = "move:STAGING:RECOVERY",
        )

        val failure = assertFailsWith<DatabaseFileMigrationException> {
            DatabaseFileMigrationCoordinator(operations).prepareTarget()
        }

        assertEquals(DatabaseMigrationFailureCode.ATOMIC_MOVE_FAILED, failure.code)
        assertTrue(operations.exists(DatabaseArtifact.LEGACY))
        assertTrue(operations.exists(DatabaseArtifact.STAGING))
        assertFalse(operations.calls.any { it == "deleteLegacySidecars" || it == "move:LEGACY:TARGET" })
    }

    @Test
    fun `sidecar cleanup failure preserves legacy and completed recovery but prevents cutover`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.LEGACY),
            legacySidecarsExist = true,
            fingerprints = mapOf(DatabaseArtifact.LEGACY to fingerprint),
            failAt = "deleteLegacySidecars",
        )

        val failure = assertFailsWith<DatabaseFileMigrationException> {
            DatabaseFileMigrationCoordinator(operations).prepareTarget()
        }

        assertEquals(DatabaseMigrationFailureCode.LEGACY_CLEANUP_FAILED, failure.code)
        assertTrue(operations.exists(DatabaseArtifact.LEGACY))
        assertTrue(operations.exists(DatabaseArtifact.RECOVERY))
        assertFalse(operations.exists(DatabaseArtifact.TARGET))
        assertFalse(operations.calls.any { it == "move:LEGACY:TARGET" })
    }

    @Test
    fun `final cutover failure preserves legacy and recovery`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.LEGACY),
            fingerprints = mapOf(DatabaseArtifact.LEGACY to fingerprint),
            failAt = "move:LEGACY:TARGET",
        )

        val failure = assertFailsWith<DatabaseFileMigrationException> {
            DatabaseFileMigrationCoordinator(operations).prepareTarget()
        }

        assertEquals(DatabaseMigrationFailureCode.ATOMIC_MOVE_FAILED, failure.code)
        assertTrue(operations.exists(DatabaseArtifact.LEGACY))
        assertTrue(operations.exists(DatabaseArtifact.RECOVERY))
        assertFalse(operations.exists(DatabaseArtifact.TARGET))
    }

    @Test
    fun `corrupt target is restored from verified recovery before retry`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.TARGET, DatabaseArtifact.RECOVERY),
            fingerprints = mapOf(DatabaseArtifact.RECOVERY to fingerprint),
            failures = mapOf(
                "validate:TARGET" to DatabaseFileMigrationException(
                    DatabaseMigrationFailureCode.INTEGRITY_CHECK_FAILED,
                    "Target database is corrupt.",
                ),
            ),
        )
        val coordinator = DatabaseFileMigrationCoordinator(operations)

        val preparation = coordinator.prepareTarget()

        assertEquals(DatabasePreparation(migratedThisLaunch = true, recoveryCleanupDue = false), preparation)
        assertEquals(
            listOf(
                "lock:start",
                "inspect",
                "validate:TARGET",
                "validate:RECOVERY",
                "delete:TARGET",
                "copy:RECOVERY:STAGING",
                "sync:STAGING",
                "validate:STAGING",
                "move:STAGING:TARGET",
                "lock:end",
            ),
            operations.calls,
        )
        assertTrue(operations.exists(DatabaseArtifact.TARGET))
        assertTrue(operations.exists(DatabaseArtifact.RECOVERY))
        assertFalse(operations.exists(DatabaseArtifact.STAGING))
    }

    @Test
    fun `target validation failure restores recovery before another retry`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.LEGACY),
            fingerprints = mapOf(DatabaseArtifact.LEGACY to fingerprint),
            failOnceAt = "validate:TARGET",
        )
        val coordinator = DatabaseFileMigrationCoordinator(operations)

        val preparation = coordinator.prepareTarget()
        assertTrue(preparation.migratedThisLaunch)

        assertFailsWith<DatabaseFileMigrationException> {
            coordinator.targetValidated(preparation)
        }

        val retry = coordinator.prepareTarget()

        assertEquals(DatabasePreparation(migratedThisLaunch = true, recoveryCleanupDue = false), retry)
        assertEquals(
            listOf(
                "lock:start",
                "inspect",
                "checkpoint:LEGACY",
                "copy:LEGACY:STAGING",
                "sync:STAGING",
                "validate:STAGING",
                "move:STAGING:RECOVERY",
                "deleteLegacySidecars",
                "move:LEGACY:TARGET",
                "lock:end",
                "lock:start",
                "validate:TARGET",
                "lock:end",
                "lock:start",
                "inspect",
                "validate:RECOVERY",
                "delete:TARGET",
                "copy:RECOVERY:STAGING",
                "sync:STAGING",
                "validate:STAGING",
                "move:STAGING:TARGET",
                "lock:end",
            ),
            operations.calls,
        )
        assertTrue(operations.exists(DatabaseArtifact.TARGET))
        assertTrue(operations.exists(DatabaseArtifact.RECOVERY))
        assertFalse(operations.exists(DatabaseArtifact.STAGING))
    }

    @Test
    fun `same process retry after cutover retains recovery until a real restart`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.LEGACY),
            fingerprints = mapOf(DatabaseArtifact.LEGACY to fingerprint),
        )
        val coordinator = DatabaseFileMigrationCoordinator(operations)

        val migrated = coordinator.prepareTarget()
        assertTrue(migrated.migratedThisLaunch)

        // Simulate SQLDelight or reconciliation failing after physical cutover.
        // Koin retries this singleton in the same process with the same coordinator.
        val retry = coordinator.prepareTarget()
        coordinator.targetValidated(retry)

        assertFalse(retry.recoveryCleanupDue)
        assertTrue(operations.exists(DatabaseArtifact.RECOVERY))
        assertFalse(operations.calls.any { it == "delete:RECOVERY" })
    }

    private class FakeDatabaseFileOperations(
        artifacts: Set<DatabaseArtifact> = emptySet(),
        private var legacySidecarsExist: Boolean = false,
        fingerprints: Map<DatabaseArtifact, DatabaseFingerprint> = emptyMap(),
        failAt: String? = null,
        failOnceAt: String? = null,
        failures: Map<String, Throwable> = emptyMap(),
        private val presenceSnapshot: DatabasePresenceSnapshot? = null,
        private val contents: Map<DatabaseArtifact, CandidateContent> = emptyMap(),
    ) : DatabaseFileOperations {
        val quarantined = mutableListOf<DatabaseArtifact>()
        private val fallbackFingerprint = DatabaseFingerprint(
            fileSize = 16_384,
            userVersion = 42,
            pageCount = 4,
            freePageCount = 0,
        )
        val calls = mutableListOf<String>()
        private val present = artifacts.toMutableSet()
        private val fingerprints = fingerprints.toMutableMap()
        private val failures = failures.toMutableMap().apply {
            if (failAt != null) put(failAt, IllegalStateException("Injected failure at $failAt"))
        }
        private val failOnceAt = failOnceAt
        private var failOnceTriggered = false

        fun add(artifact: DatabaseArtifact, fingerprint: DatabaseFingerprint) {
            present += artifact
            fingerprints[artifact] = fingerprint
        }

        fun artifacts(): Set<DatabaseArtifact> = present.toSet()

        fun exists(artifact: DatabaseArtifact): Boolean = artifact in present

        fun sidecarsExist(): Boolean = legacySidecarsExist

        override fun inspect(): DatabaseFileLayout {
            record("inspect")
            return DatabaseFileLayout(
                legacyExists = DatabaseArtifact.LEGACY in present,
                targetExists = DatabaseArtifact.TARGET in present,
                recoveryExists = DatabaseArtifact.RECOVERY in present,
                stagingExists = DatabaseArtifact.STAGING in present,
                legacySidecarsExist = legacySidecarsExist,
            )
        }

        override fun capturePresenceSnapshot(): DatabasePresenceSnapshot? = presenceSnapshot

        override fun checkpointAndValidate(artifact: DatabaseArtifact): DatabaseFingerprint {
            record("checkpoint:$artifact")
            return fingerprints[artifact] ?: fallbackFingerprint
        }

        override fun validate(artifact: DatabaseArtifact): DatabaseFingerprint {
            record("validate:$artifact")
            return fingerprints[artifact] ?: fallbackFingerprint
        }

        override fun copy(from: DatabaseArtifact, to: DatabaseArtifact) {
            record("copy:$from:$to")
            present += to
            if (to !in fingerprints) {
                fingerprints[to] = fingerprints[from] ?: fallbackFingerprint
            }
        }

        override fun sync(artifact: DatabaseArtifact) {
            record("sync:$artifact")
        }

        override fun atomicMove(from: DatabaseArtifact, to: DatabaseArtifact) {
            record("move:$from:$to")
            check(from in present) { "Missing source $from" }
            check(to !in present) { "Destination $to already exists" }
            present -= from
            present += to
            fingerprints.remove(from)?.let { fingerprints[to] = it }
        }

        override fun delete(artifact: DatabaseArtifact) {
            record("delete:$artifact")
            present -= artifact
            fingerprints -= artifact
        }

        override fun deleteLegacySidecars() {
            record("deleteLegacySidecars")
            legacySidecarsExist = false
        }

        override fun probeUserData(artifact: DatabaseArtifact): CandidateContent {
            record("probe:$artifact")
            return contents[artifact] ?: CandidateContent.UNINSPECTABLE
        }

        override fun quarantine(artifact: DatabaseArtifact, reason: DatabaseDiagnosticReason) {
            record("quarantine:$artifact")
            check(artifact in present) { "Missing quarantine source $artifact" }
            present -= artifact
            fingerprints -= artifact
            quarantined += artifact
        }

        override fun <T> withExclusiveMigrationLock(block: () -> T): T {
            calls += "lock:start"
            return try {
                block()
            } finally {
                calls += "lock:end"
            }
        }

        private fun record(call: String) {
            calls += call
            failures[call]?.let { throw it }
            if (call == failOnceAt && !failOnceTriggered) {
                failOnceTriggered = true
                throw IllegalStateException("Injected one-shot failure at $call")
            }
        }
    }
}
