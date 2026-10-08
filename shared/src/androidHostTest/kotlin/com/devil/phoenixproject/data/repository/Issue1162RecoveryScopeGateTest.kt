package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.testutil.createTestDatabase
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Issue #1162 final audit R4/R5 regressions — recovery authorization at the
 * repository boundary.
 *
 * Recovery listing/restoration must be authorized ONLY by the authenticated
 * portal identity and the active profile, resolved at execution time. This
 * covers the gate's required regressions:
 * - linked-owner logout (stored linkage survives, authentication does not),
 * - a different signed-in account with the unchanged stored owner,
 * - an inactive profile (not the selected one),
 * - restore revalidation at execution time with zero writes on refusal,
 * - new retained snapshots surfacing to a mounted observer without a remount.
 *
 * Refusal assertions use the REAL retained recovery id so a refusal can only
 * come from the authorization gate, never from a missing row. Anonymous
 * (empty-owner) snapshots stay retained but hidden — see
 * Issue1162RecoveryAuthGateTest. Nothing here claims reporter data recovery.
 */
class Issue1162RecoveryScopeGateTest {

    private val lower = "abcdefab-1234-4abc-8def-abcdef123456"
    private val upper = "ABCDEFAB-1234-4ABC-8DEF-ABCDEF123456"

    private class MutableIdentity {
        var userId: String? = "owner-user"
        var activeProfile: String? = "active-profile"
    }

    private fun seedOwnerProfile(
        db: PhoenixDatabase,
        profileId: String = "active-profile",
        owner: String = "owner-user",
    ) {
        val q = db.phoenixDatabaseQueries
        q.insertProfile(profileId, "Active", 0L, 1_700_000_000_000L, 1L)
        q.linkProfileToSupabase(
            supabase_user_id = owner,
            last_auth_at = 1_700_000_000_000L,
            id = profileId,
        )
    }

    /** Two UUID-equivalent rows of one routine identity; maintenance coalesces them. */
    private fun seedAliasPair(
        db: PhoenixDatabase,
        first: String = lower,
        second: String = upper,
        name: String = "Private programming",
    ) {
        val q = db.phoenixDatabaseQueries
        for (id in listOf(first, second)) {
            q.insertRoutine(id, name, "", 1L, null, 0L, "active-profile", null, null)
        }
    }

    private fun repository(db: PhoenixDatabase, identity: MutableIdentity) =
        SqlDelightWorkoutRepository(
            db = db,
            exerciseRepository = FakeExerciseRepository(),
            signedInPortalUserId = { identity.userId },
            activeProfileId = { identity.activeProfile },
        )

    private fun routineCount(db: PhoenixDatabase): Int =
        db.phoenixDatabaseQueries
            .selectAllRoutinesByProfileIncludingDeleted("active-profile")
            .executeAsList()
            .size

    /** The real retained recovery row id (so refusals can only come from the gate). */
    private fun retainedRecoveryId(db: PhoenixDatabase): String =
        db.phoenixDatabaseQueries
            .selectRoutineRecoveriesByProfile(
                profileId = "active-profile",
                portalUserId = "owner-user",
                now = currentTimeMillis(),
            ).executeAsList()
            .first()
            .id

    @Test
    fun linkedOwnerLogoutHidesRecoveriesAndRejectsRestoreBeforeWrites() = runBlocking {
        val db = createTestDatabase()
        seedOwnerProfile(db)
        seedAliasPair(db)
        val identity = MutableIdentity()
        val repository = repository(db, identity)

        assertTrue(!repository.runRoutineIdentityMaintenance("active-profile").failed)
        val item = repository.listRoutineRecoveries("active-profile", "owner-user").single()
        assertTrue(item.routineName == "Private programming")

        // Linked-owner logout: the stored linkage still names "owner-user", but
        // there is no authenticated identity any more.
        identity.userId = null
        assertTrue(
            repository.listRoutineRecoveries("active-profile", "owner-user").isEmpty(),
            "logged-out recovery must be hidden even when stored linkage still matches",
        )
        val before = routineCount(db)
        assertNull(
            repository.restoreRoutineRecoveryAsCopy(
                recoveryId = item.recoveryId,
                graphIndex = item.graphIndex,
                profileId = "active-profile",
                portalUserId = "owner-user",
            ),
            "logged-out restore must be rejected",
        )
        assertEquals(before, routineCount(db), "refused restore writes nothing")
    }

    @Test
    fun blankAuthenticatedIdentityIsRejected() = runBlocking {
        val db = createTestDatabase()
        seedOwnerProfile(db)
        seedAliasPair(db)
        val identity = MutableIdentity()
        val repository = repository(db, identity)

        repository.runRoutineIdentityMaintenance("active-profile")
        val recoveryId = retainedRecoveryId(db)

        identity.userId = ""
        assertTrue(
            repository.listRoutineRecoveries("active-profile", "").isEmpty(),
            "a blank authenticated identity is not an identity",
        )
        assertNull(repository.restoreRoutineRecoveryAsCopy(recoveryId, 0, "active-profile", ""))
    }

    @Test
    fun differentSignedInAccountWithUnchangedStoredOwnerIsRejected() = runBlocking {
        val db = createTestDatabase()
        seedOwnerProfile(db) // stored owner remains "owner-user"
        seedAliasPair(db)
        val identity = MutableIdentity()
        val repository = repository(db, identity)
        repository.runRoutineIdentityMaintenance("active-profile")
        val recoveryId = retainedRecoveryId(db)

        // The account changes to a different user while the profile row is
        // untouched (account switch without relinking).
        identity.userId = "other-user"
        // Caller passes the stored linkage (what the old UI derived from the
        // profile row): rejected because it is not the authenticated identity.
        assertTrue(
            repository.listRoutineRecoveries("active-profile", "owner-user").isEmpty(),
            "stored linkage alone must never authorize recovery access",
        )
        // Caller equality with the authenticated identity is still not enough:
        // the profile is owned by a different account.
        assertTrue(
            repository.listRoutineRecoveries("active-profile", "other-user").isEmpty(),
            "caller equality must never authorize another owner's recoveries",
        )
        assertNull(repository.restoreRoutineRecoveryAsCopy(recoveryId, 0, "active-profile", "owner-user"))
        assertNull(repository.restoreRoutineRecoveryAsCopy(recoveryId, 0, "active-profile", "other-user"))
    }

    @Test
    fun inactiveProfileIsRejected() = runBlocking {
        val db = createTestDatabase()
        seedOwnerProfile(db)
        seedAliasPair(db)
        val identity = MutableIdentity()
        val repository = repository(db, identity)

        repository.runRoutineIdentityMaintenance("active-profile")
        val item = repository.listRoutineRecoveries("active-profile", "owner-user").single()

        // The profile is no longer the selected one (switched away).
        identity.activeProfile = "other-profile"
        assertTrue(
            repository.listRoutineRecoveries("active-profile", "owner-user").isEmpty(),
            "recoveries of a non-active profile must be hidden",
        )
        assertNull(
            repository.restoreRoutineRecoveryAsCopy(
                recoveryId = item.recoveryId,
                graphIndex = item.graphIndex,
                profileId = "active-profile",
                portalUserId = "owner-user",
            ),
        )

        // No active profile at all (mid switch): same refusal.
        identity.activeProfile = null
        assertTrue(repository.listRoutineRecoveries("active-profile", "owner-user").isEmpty())
        assertNull(
            repository.restoreRoutineRecoveryAsCopy(
                recoveryId = item.recoveryId,
                graphIndex = item.graphIndex,
                profileId = "active-profile",
                portalUserId = "owner-user",
            ),
        )
    }

    @Test
    fun restoreRevalidatesExecutionTimeIdentityAfterScopeChange() = runBlocking {
        val db = createTestDatabase()
        seedOwnerProfile(db)
        seedAliasPair(db)
        val identity = MutableIdentity()
        val repository = repository(db, identity)

        repository.runRoutineIdentityMaintenance("active-profile")
        val item = repository.listRoutineRecoveries("active-profile", "owner-user").single()

        // The account changes AFTER the list was loaded (in-flight scope change):
        // restore must revalidate and refuse before any write.
        identity.userId = "other-user"
        val before = routineCount(db)
        assertNull(
            repository.restoreRoutineRecoveryAsCopy(
                recoveryId = item.recoveryId,
                graphIndex = item.graphIndex,
                profileId = "active-profile",
                portalUserId = "owner-user",
            ),
            "restore must revalidate the authenticated identity at execution time",
        )
        assertEquals(before, routineCount(db), "refused restore writes nothing")
    }

    @Test
    fun newSnapshotsSurfaceWithoutRemountThroughObservationAndStayHiddenAfterLogout() = runBlocking {
        val db = createTestDatabase()
        seedOwnerProfile(db)
        seedAliasPair(db)
        val identity = MutableIdentity()
        val repository = repository(db, identity)

        val emissions = Channel<List<RoutineRecoveryItem>>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) {
            repository.observeRoutineRecoveries("active-profile", "owner-user").collect {
                emissions.send(it)
            }
        }
        try {
            // First emission: nothing has been retained yet.
            val initial = withTimeout(30_000) { emissions.receive() }
            assertTrue(initial.isEmpty(), "an empty initial load must not show the entry")

            // A destructive coalesce retains a snapshot while the observer stays
            // mounted: the change must surface without a remount.
            repository.runRoutineIdentityMaintenance("active-profile")
            val discovered = withTimeout(30_000) { emissions.receive() }
            assertEquals(1, discovered.size, "newly retained snapshots must surface without a remount")

            // Logout: the mounted observer must stop publishing this scope's data.
            identity.userId = null
            seedAliasPair(
                db,
                first = "aaaaaaaa-1111-4aaa-8aaa-aaaaaaaaaaaa",
                second = "AAAAAAAA-1111-4AAA-8AAA-AAAAAAAAAAAA",
                name = "Pull Day",
            )
            repository.runRoutineIdentityMaintenance("active-profile")
            val afterLogout = withTimeout(30_000) { emissions.receive() }
            assertTrue(afterLogout.isEmpty(), "after logout the mounted observer publishes nothing")

            // The data itself is retained, not destroyed or relabeled.
            val retained = db.phoenixDatabaseQueries
                .selectRoutineRecoveriesByProfile(
                    profileId = "active-profile",
                    portalUserId = "owner-user",
                    now = currentTimeMillis(),
                ).executeAsList()
            assertTrue(retained.isNotEmpty(), "refused scopes never destroy retained evidence")
        } finally {
            job.cancel()
        }
    }
}
