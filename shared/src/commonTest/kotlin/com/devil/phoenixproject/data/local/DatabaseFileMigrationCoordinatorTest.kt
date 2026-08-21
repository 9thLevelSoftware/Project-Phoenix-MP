package com.devil.phoenixproject.data.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
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
    fun `target validation failure retains recovery for another retry`() {
        val operations = FakeDatabaseFileOperations(
            artifacts = setOf(DatabaseArtifact.TARGET, DatabaseArtifact.RECOVERY),
            failAt = "validate:TARGET",
        )
        val coordinator = DatabaseFileMigrationCoordinator(operations)
        val preparation = coordinator.prepareTarget()

        val failure = assertFailsWith<DatabaseFileMigrationException> {
            coordinator.targetValidated(preparation)
        }

        assertEquals(DatabaseMigrationFailureCode.TARGET_VALIDATION_FAILED, failure.code)
        assertTrue(operations.exists(DatabaseArtifact.RECOVERY))
        assertFalse(operations.calls.any { it == "delete:RECOVERY" })
    }

    private class FakeDatabaseFileOperations(
        artifacts: Set<DatabaseArtifact> = emptySet(),
        private var legacySidecarsExist: Boolean = false,
        fingerprints: Map<DatabaseArtifact, DatabaseFingerprint> = emptyMap(),
        failAt: String? = null,
        failures: Map<String, Throwable> = emptyMap(),
    ) : DatabaseFileOperations {
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
            fingerprints.putIfAbsent(to, fingerprints[from] ?: fallbackFingerprint)
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
        }
    }
}
