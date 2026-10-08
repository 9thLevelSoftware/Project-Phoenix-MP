package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.testutil.createTestDatabase
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test

/**
 * Issue #1162 — independent senior merge-gate diagnostics, RETAINED as
 * regressions for the R1/R2/R3 rework (cancelled-first-collector maintenance,
 * resolver coalesce retention, restore expiry). The wider remediation matrix
 * lives in [Issue1162SeniorGateReworkTest].
 */
class Issue1162SeniorGateTest {
    private val lower = "abcdefab-1234-4abc-8def-abcdef123456"
    private val profile = "active-profile"
    private fun seed(db: PhoenixDatabase, id: String) {
        db.phoenixDatabaseQueries.insertRoutine(id=id, name="Push Day", description="", createdAt=1L,
            lastUsed=null, useCount=0L, profile_id=profile, groupId=null, deletedAt=null)
    }

    @Test fun lazyResolverMustRetainGraphsBeforeCoalescing() {
        val db = createTestDatabase()
        seed(db, lower); seed(db, lower.uppercase())
        db.transaction {
            RoutineIdentityResolver(db.phoenixDatabaseQueries).resolve(lower, scopeProfileId=profile) { it.profile_id == profile }
        }
        assertEquals(1, db.phoenixDatabaseQueries.selectAllRoutinesByProfileIncludingDeleted(profile).executeAsList().size)
        assertEquals(1, db.phoenixDatabaseQueries.selectRoutineRecoveriesByProfile(profile, "", 1L).executeAsList().size,
            "destructive named-write resolver must retain a source snapshot too")
    }

    @Test fun expiredRecoveryCannotBeRestoredFromStaleSelection() {
        val db = createTestDatabase()
        seed(db, lower)
        val store = RoutineRecoveryStore(db.phoenixDatabaseQueries)
        db.transaction {
            store.retainRoutineGraphs(db.phoenixDatabaseQueries.selectAllRoutinesByProfileIncludingDeleted(profile).executeAsList(),
                lower, "owner-user", profile, RoutineRecoveryReasons.SERVER_DELETE, "pull", lower, 1L)
            db.phoenixDatabaseQueries.deleteRoutineById(lower)
        }
        val retained = db.phoenixDatabaseQueries.selectRoutineRecoveriesByProfile(profile, "owner-user", 1L).executeAsList().single()
        val now = retained.expires_at + 1L
        assertEquals(0, store.listRecoverableRoutines(profile, "owner-user", now).size)
        val restored = db.transactionWithResult { store.restoreAsCopy(retained.id, 0, profile, "owner-user", now) }
        assertNull(restored, "restore must enforce expiry even when a sheet cached the selection")
    }

    @Test fun cancelledFirstCollectorMustNotSuppressMaintenanceForNextCollector() = runBlocking {
        val db = createTestDatabase()
        seed(db, lower); seed(db, lower.uppercase())
        val repository = SqlDelightWorkoutRepository(db, FakeExerciseRepository())
        val job = launch(start=CoroutineStart.UNDISPATCHED) {
            cancel()
            repository.getAllRoutines(profile).first()
        }
        job.join()
        assertEquals(1, repository.getAllRoutines(profile).first().size,
            "cancelled first collection cannot permanently mark an unmaintained profile complete")
    }
}
