package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.testutil.createTestDatabase
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.assertTrue
import kotlin.test.assertNull
import org.junit.Test

/**
 * Issue #1162 final audit R4 regressions (originally the gate's diagnostics):
 * production eager maintenance of an unlinked profile must retain anonymous
 * snapshots WITHOUT ever listing or restoring them — empty-owner snapshots are
 * hidden by the authenticated-identity gate, not authorized by empty-string
 * equality. The repository is built WITHOUT auth providers on purpose:
 * deny-by-default is part of the contract.
 */
class Issue1162RecoveryAuthGateTest {
    private val profile = "local-profile"
    private val lower = "abcdefab-1234-4abc-8def-abcdef123456"
    private val upper = "ABCDEFAB-1234-4ABC-8DEF-ABCDEF123456"
    private fun seed(db: PhoenixDatabase) {
        val q = db.phoenixDatabaseQueries
        q.insertProfile(profile, "Local", 0L, 1L, 1L)
        for (id in listOf(lower, upper)) {
            q.insertRoutine(id, "Private programming", "", 1L, null, 0L, profile, null, null)
        }
    }
    @Test fun unsignedLocalRecoveryMustNotBeListedOrRestored() = runTest {
        val db = createTestDatabase()
        seed(db)
        val repository = SqlDelightWorkoutRepository(db, FakeExerciseRepository())
        assertTrue(!repository.runRoutineIdentityMaintenance(profile).failed)
        val items = repository.listRoutineRecoveries(profile, "")
        assertTrue(items.isEmpty(), "unsigned recovery must be hidden, not authorized by empty-string equality")
    }

    @Test fun unsignedLocalRecoveryMustNotBeRestored() = runTest {
        val db = createTestDatabase()
        seed(db)
        val repository = SqlDelightWorkoutRepository(db, FakeExerciseRepository())
        assertTrue(!repository.runRoutineIdentityMaintenance(profile).failed)
        val q = db.phoenixDatabaseQueries
        val recovery = q.selectRoutineRecoveriesByProfile(profile, "",
            com.devil.phoenixproject.domain.model.currentTimeMillis()).executeAsList().single()
        assertNull(repository.restoreRoutineRecoveryAsCopy(recovery.id, 0, profile, ""),
            "unsigned recovery must reject restore before writes")
    }
}
