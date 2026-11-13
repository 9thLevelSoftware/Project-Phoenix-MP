package com.devil.phoenixproject.data.integration

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.data.integration.intervals.IntervalsApi
import com.devil.phoenixproject.data.integration.intervals.IntervalsConnectionStore
import com.devil.phoenixproject.data.integration.intervals.IntervalsCreateOutcome
import com.devil.phoenixproject.data.integration.intervals.IntervalsDispatchBinding
import com.devil.phoenixproject.data.integration.intervals.IntervalsListResult
import com.devil.phoenixproject.data.integration.intervals.IntervalsManualActivity
import com.devil.phoenixproject.data.integration.intervals.IntervalsReadbackResult
import com.devil.phoenixproject.data.integration.intervals.IntervalsValidationResult
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.testutil.createTestSchema
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Real-SQLite (JdbcSqliteDriver) tests for [IntervalsConnectionStore] (issue #1384
 * packet P3) with a fake secure [Settings] and a fake [IntervalsApi]. No real keys or
 * accounts: secrets here are obvious fixture strings.
 *
 * Covered per the packet's test list: generation rotation ordering (invalidate before
 * the metadata commit), metadata-without-secret never authorizing, disconnect ordering
 * (generation invalidated before key deletion; deletion failure keeps the connection
 * disabled), orphan-secret cleanup, pause/resume eligibility, profile deletion purge,
 * and the isDispatchAuthorized truth table (stale generation, paused, installation
 * mismatch, secret missing).
 */
class IntervalsConnectionStoreTest {

    // ------------------------------------------------------------------ fakes

    private class FakeSecureSettings(
        private val delegate: Settings = MapSettings(),
    ) : Settings by delegate {
        var failPutString = false
        var failRemove = false
        var onPutString: ((key: String, value: String) -> Unit)? = null
        var onRemove: ((key: String) -> Unit)? = null
        val removedKeys = mutableListOf<String>()

        override fun putString(key: String, value: String) {
            if (failPutString) throw IllegalStateException("secure store write failed")
            onPutString?.invoke(key, value)
            delegate.putString(key, value)
        }

        override fun remove(key: String) {
            onRemove?.invoke(key)
            if (failRemove) throw IllegalStateException("secure store delete failed")
            removedKeys += key
            delegate.remove(key)
        }
    }

    private class FakeIntervalsApi : IntervalsApi {
        var validateHandler: (athleteId: String, apiKey: String) -> IntervalsValidationResult =
            { _, _ -> IntervalsValidationResult.Valid(CANONICAL_ATHLETE, "Fixture Athlete") }
        val validateCalls = mutableListOf<Pair<String, String>>()

        override suspend fun validateAthlete(athleteId: String, apiKey: String): IntervalsValidationResult {
            validateCalls += athleteId to apiKey
            return validateHandler(athleteId, apiKey)
        }

        override suspend fun createManualActivity(
            athleteId: String,
            apiKey: String,
            activity: IntervalsManualActivity,
        ): IntervalsCreateOutcome = IntervalsCreateOutcome.Ambiguous

        override suspend fun readbackActivity(
            athleteId: String,
            apiKey: String,
            remoteActivityId: String,
        ): IntervalsReadbackResult = IntervalsReadbackResult.NotFound

        override suspend fun listActivities(
            athleteId: String,
            apiKey: String,
            oldest: String,
            newest: String,
            page: Int,
        ): IntervalsListResult = IntervalsListResult.Failed
    }

    // ------------------------------------------------------------------ harness

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: PhoenixDatabase
    private val secure = FakeSecureSettings()
    private val api = FakeIntervalsApi()
    private var now = 10_000L

    private fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createTestSchema(driver)
        db = PhoenixDatabase(driver)
    }

    private fun newStore() = IntervalsConnectionStore(
        db = db,
        secureSettings = secure,
        api = api,
        nowEpochMs = { now },
        rawScanDriver = driver,
    )

    private fun keyRef(profileId: String, generation: Long) =
        IntervalsConnectionStore.SECRET_KEY_PREFIX + profileId + "." + generation

    private fun installationId(): String =
        assertNotNull(secure.getStringOrNull(IntervalsConnectionStore.INSTALLATION_ID_KEY))

    private fun binding(
        generation: Long,
        profileId: String = PROFILE,
        installationId: String = installationId(),
        resolvedAthleteId: String = CANONICAL_ATHLETE,
    ) = IntervalsDispatchBinding(
        installationId = installationId,
        profileId = profileId,
        resolvedAthleteId = resolvedAthleteId,
        completionId = "completion-1",
        originalGeneration = generation,
    )

    /** Save + enable a valid connection; returns the connection generation (= 1). */
    private suspend fun connectedStore(
        store: IntervalsConnectionStore,
        profileId: String = PROFILE,
        apiKey: String = "fixture-key-1",
    ): Long {
        val saved = store.saveCredentials(profileId, "i42", apiKey)
        assertIs<IntervalsValidationResult.Valid>(saved)
        assertTrue(store.enable(profileId))
        return assertNotNull(store.status(profileId)).connectionGeneration
    }

    // ------------------------------------------------------------------ save/test never enable

    @Test
    fun saveCredentialsValidatesFirstAndNeverEnables() = runTest {
        setUp()
        val store = newStore()

        api.validateHandler = { _, _ -> IntervalsValidationResult.InvalidCredentials(null) }
        val rejected = store.saveCredentials(PROFILE, "i42", "bad-key")
        assertIs<IntervalsValidationResult.InvalidCredentials>(rejected)
        assertNull(db.phoenixDatabaseQueries.selectIntervalsStatus(PROFILE).executeAsOneOrNull())
        assertNull(secure.getStringOrNull(keyRef(PROFILE, 1)))

        api.validateHandler = { _, _ -> IntervalsValidationResult.Valid(CANONICAL_ATHLETE, null) }
        val saved = store.saveCredentials(PROFILE, "i42", "fixture-key-1")
        assertIs<IntervalsValidationResult.Valid>(saved)

        val row = assertNotNull(db.phoenixDatabaseQueries.selectIntervalsStatus(PROFILE).executeAsOneOrNull())
        assertEquals(0L, row.enabled, "save/test must never enable")
        assertEquals(0L, row.dispatch_auth_generation, "save/test must never authorize")
        assertEquals(0L, row.eligibility_epoch)
        assertEquals(CANONICAL_ATHLETE, row.resolved_athlete_id)
        assertEquals("fixture-key-1", secure.getStringOrNull(keyRef(PROFILE, row.connection_generation)))

        assertNull(store.status(PROFILE), "no usable connection before explicit enable")
        assertFalse(store.isDispatchAuthorized(binding(row.connection_generation)))
        assertFalse(store.resolve(PROFILE, now).eligible)
    }

    // ------------------------------------------------------------------ rotation ordering

    @Test
    fun rotationInvalidateHappensBeforeSecretWriteAndMetadataCommit() = runTest {
        setUp()
        val store = newStore()
        val generation1 = connectedStore(store)

        var invalidatedBeforeSecretWrite = false
        var metadataNotYetCommittedAtSecretWrite = false
        secure.onPutString = { key, _ ->
            if (key == keyRef(PROFILE, generation1 + 1)) {
                val row = assertNotNull(db.phoenixDatabaseQueries.selectIntervalsStatus(PROFILE).executeAsOneOrNull())
                invalidatedBeforeSecretWrite = row.dispatch_auth_generation == 0L && row.enabled == 0L
                metadataNotYetCommittedAtSecretWrite = row.connection_generation == generation1
            }
        }
        now += 1_000
        assertIs<IntervalsValidationResult.Valid>(store.saveCredentials(PROFILE, "i42", "fixture-key-2"))

        assertTrue(invalidatedBeforeSecretWrite, "old generation must be invalidated before the new secret write")
        assertTrue(metadataNotYetCommittedAtSecretWrite, "metadata commit must follow the secret write")

        val row = assertNotNull(db.phoenixDatabaseQueries.selectIntervalsStatus(PROFILE).executeAsOneOrNull())
        assertEquals(generation1 + 1, row.connection_generation)
        assertEquals("fixture-key-2", secure.getStringOrNull(keyRef(PROFILE, row.connection_generation)))
        assertNull(secure.getStringOrNull(keyRef(PROFILE, generation1)), "orphaned old secret cleaned after commit")
        // The old captured binding is now stale and can never dispatch.
        assertFalse(store.isDispatchAuthorized(binding(generation1)))
    }

    @Test
    fun secretWriteFailureAbortsWithoutMetadataCommitAndStaysDisabled() = runTest {
        setUp()
        val store = newStore()
        val generation1 = connectedStore(store)

        secure.failPutString = true
        val result = store.saveCredentials(PROFILE, "i42", "fixture-key-2")
        val rejected = assertIs<IntervalsValidationResult.Rejected>(result)
        assertTrue(rejected.detail != null && !rejected.detail.contains("fixture-key-2"))

        val row = assertNotNull(db.phoenixDatabaseQueries.selectIntervalsStatus(PROFILE).executeAsOneOrNull())
        assertEquals(generation1, row.connection_generation, "no metadata commit after a failed secret write")
        assertEquals(0L, row.dispatch_auth_generation, "old generation stays durably invalidated")
        assertEquals(0L, row.enabled)
        assertFalse(store.isDispatchAuthorized(binding(generation1)))
    }

    // ------------------------------------------------------------------ metadata-without-secret

    @Test
    fun metadataWithoutSecretNeverAuthorizes() = runTest {
        setUp()
        val store = newStore()
        val generation = connectedStore(store)

        secure.remove(keyRef(PROFILE, generation))

        assertFalse(store.isDispatchAuthorized(binding(generation)), "metadata-without-secret must never authorize")
        assertNull(store.status(PROFILE))
        assertFalse(store.resolve(PROFILE, now).eligible)
        assertNull(store.authorizedRequest(PROFILE) { athlete, key -> athlete to key })

        // Recovery fails the metadata closed as well.
        store.recoverForProfile(PROFILE)
        val row = assertNotNull(db.phoenixDatabaseQueries.selectIntervalsStatus(PROFILE).executeAsOneOrNull())
        assertEquals(0L, row.enabled)
        assertEquals(0L, row.dispatch_auth_generation)
    }

    // ------------------------------------------------------------------ disconnect

    @Test
    fun disconnectInvalidatesGenerationBeforeKeyDeletion() = runTest {
        setUp()
        val store = newStore()
        val generation = connectedStore(store)

        var invalidatedAtDeletionTime = false
        secure.onRemove = { key ->
            if (key == keyRef(PROFILE, generation)) {
                val row = assertNotNull(db.phoenixDatabaseQueries.selectIntervalsStatus(PROFILE).executeAsOneOrNull())
                invalidatedAtDeletionTime = row.dispatch_auth_generation == 0L && row.enabled == 0L
            }
        }
        store.disconnect(PROFILE)

        assertTrue(invalidatedAtDeletionTime, "generation must be durably invalidated before key deletion")
        assertNull(secure.getStringOrNull(keyRef(PROFILE, generation)))
        assertNull(store.status(PROFILE))
        assertFalse(store.isDispatchAuthorized(binding(generation)))
    }

    @Test
    fun disconnectKeepsDisabledWhenKeyDeletionFails() = runTest {
        setUp()
        val store = newStore()
        val generation = connectedStore(store)

        secure.failRemove = true
        store.disconnect(PROFILE)

        val row = assertNotNull(db.phoenixDatabaseQueries.selectIntervalsStatus(PROFILE).executeAsOneOrNull())
        assertEquals(0L, row.enabled, "deletion failure must never re-validate the connection")
        assertEquals(0L, row.dispatch_auth_generation)
        assertFalse(store.isDispatchAuthorized(binding(generation)))
        assertNotNull(secure.getStringOrNull(keyRef(PROFILE, generation)), "deletion failed, key still present")
        assertTrue(store.ownedKeyDeletionPending.value)

        // Retry through recovery once the secure store works again.
        secure.failRemove = false
        store.recoverForProfile(PROFILE)
        assertNull(secure.getStringOrNull(keyRef(PROFILE, generation)))
        assertFalse(store.ownedKeyDeletionPending.value)
    }

    @Test
    fun revokeForRestoreInvalidatesGenerationAndPropagatesKeyDeletionFailure() = runTest {
        setUp()
        val store = newStore()
        val generation = connectedStore(store)
        secure.putString(keyRef("stray-profile", 1L), "stray-key")

        secure.failRemove = true
        assertFails { store.revokeForRestore(PROFILE) }

        val row = assertNotNull(db.phoenixDatabaseQueries.selectIntervalsStatus(PROFILE).executeAsOneOrNull())
        assertEquals(0L, row.enabled, "restore revocation must durably disable before attempting key deletion")
        assertEquals(0L, row.dispatch_auth_generation)
        assertFalse(store.isDispatchAuthorized(binding(generation)))
        assertTrue(store.ownedKeyDeletionPending.value)

        // Orphan secret deletion during restore also fails closed when secure deletion throws.
        assertFails { store.cleanOrphanSecretsOrThrow() }
        assertTrue(store.ownedKeyDeletionPending.value)

        // Once secure storage succeeds, restore revocation and orphan cleanup complete cleanly.
        secure.failRemove = false
        store.revokeForRestore(PROFILE)
        store.cleanOrphanSecretsOrThrow()
        assertNull(secure.getStringOrNull(keyRef(PROFILE, generation)))
        assertNull(secure.getStringOrNull(keyRef("stray-profile", 1L)))
        assertFalse(store.ownedKeyDeletionPending.value)
    }

    @Test
    fun neverDeletesAnythingOutsideOwnedPrefix() = runTest {
        setUp()
        val store = newStore()
        connectedStore(store)
        secure.putString("portal_auth_token", "shared-auth-keychain-entry")

        store.disconnect(PROFILE)
        store.cleanOrphanSecrets()

        assertEquals("shared-auth-keychain-entry", secure.getStringOrNull("portal_auth_token"))
    }

    // ------------------------------------------------------------------ orphan cleanup

    @Test
    fun orphanSecretsAreCleanedOnRecovery() = runTest {
        setUp()
        val store = newStore()
        connectedStore(store, apiKey = "k1")
        store.saveCredentials(PROFILE, "i42", "k2")
        store.saveCredentials(PROFILE, "i42", "k3")
        // Strays: a future generation of a known profile and an unknown profile's key.
        secure.putString(keyRef(PROFILE, 99L), "stray-future")
        secure.putString(keyRef("profile-x", 1L), "stray-unknown-profile")
        secure.putString("portal_auth_token", "keep-me")

        store.recoverForProfile(PROFILE)
        store.cleanOrphanSecrets()

        val row = assertNotNull(db.phoenixDatabaseQueries.selectIntervalsStatus(PROFILE).executeAsOneOrNull())
        assertEquals("k3", secure.getStringOrNull(keyRef(PROFILE, row.connection_generation)))
        assertNull(secure.getStringOrNull(keyRef(PROFILE, 1L)))
        assertNull(secure.getStringOrNull(keyRef(PROFILE, 2L)))
        assertNull(secure.getStringOrNull(keyRef(PROFILE, 99L)), "future-generation stray cleaned")
        assertNull(secure.getStringOrNull(keyRef("profile-x", 1L)), "unknown-profile stray cleaned")
        assertEquals("keep-me", secure.getStringOrNull("portal_auth_token"))
    }

    // ------------------------------------------------------------------ pause / resume

    @Test
    fun pauseSuppressesEligibilityAndResumeNeverBackfills() = runTest {
        setUp()
        val store = newStore()
        connectedStore(store)

        val beforeEnable = now - 1
        val epoch = now
        assertTrue(store.resolve(PROFILE, epoch).eligible, "completions at/after the opt-in epoch are eligible")
        assertFalse(store.resolve(PROFILE, beforeEnable).eligible, "pre-opt-in completions stay ineligible forever")

        now += 5_000
        store.pause(PROFILE)
        val duringPause = now + 1
        assertFalse(store.resolve(PROFILE, duringPause).eligible, "completions while paused are ineligible")
        assertTrue(store.status(PROFILE)!!.paused)

        now += 5_000
        store.resume(PROFILE)
        assertFalse(store.status(PROFILE)!!.paused)
        assertTrue(store.resolve(PROFILE, now + 1).eligible, "future completions are eligible again")
        assertFalse(
            store.resolve(PROFILE, beforeEnable).eligible,
            "resume never backfills: pre-opt-in completions stay ineligible",
        )
    }

    @Test
    fun enableStartsEligibilityEpochAndOnlyFutureCompletionsQualify() = runTest {
        setUp()
        val store = newStore()
        assertIs<IntervalsValidationResult.Valid>(store.saveCredentials(PROFILE, "i42", "fixture-key-1"))

        now = 20_000L
        assertTrue(store.enable(PROFILE))
        assertFalse(store.resolve(PROFILE, 19_999L).eligible)
        assertTrue(store.resolve(PROFILE, 20_000L).eligible)

        // A repeated enable issues a fresh authorization generation but preserves the
        // provenance generation and the eligibility epoch (no stranded completions).
        val before = assertNotNull(store.status(PROFILE))
        now = 25_000L
        assertTrue(store.enable(PROFILE))
        val after = assertNotNull(store.status(PROFILE))
        assertEquals(before.connectionGeneration, after.connectionGeneration)
        assertTrue(after.dispatchAuthGeneration > before.dispatchAuthGeneration)
        assertEquals(before.eligibilityEpoch, after.eligibilityEpoch)
        assertTrue(store.resolve(PROFILE, 20_000L).eligible)
    }

    @Test
    fun enableRequiresFreshValidationAndRefusesOnInstallationMismatch() = runTest {
        setUp()
        val store = newStore()
        connectedStore(store)

        // Reconnect flow: fresh validation failing keeps/sets auth-required, never enables.
        api.validateHandler = { _, _ -> IntervalsValidationResult.InvalidCredentials(null) }
        assertFalse(store.enable(PROFILE))
        assertEquals(1L, assertNotNull(db.phoenixDatabaseQueries.selectIntervalsStatus(PROFILE).executeAsOneOrNull()).auth_required)

        api.validateHandler = { _, _ -> IntervalsValidationResult.Valid(CANONICAL_ATHLETE, null) }
        // Restored/reinstalled installation identity cannot re-enable anything.
        secure.putString(IntervalsConnectionStore.INSTALLATION_ID_KEY, "different-installation")
        assertFalse(store.enable(PROFILE))
    }

    // ------------------------------------------------------------------ profile deletion purge

    @Test
    fun profileDeletionPurgeRemovesOnlyThatProfilesPersonalPayloads() = runTest {
        setUp()
        val store = newStore()
        connectedStore(store, profileId = "profile-1", apiKey = "k1")
        connectedStore(store, profileId = "profile-2", apiKey = "k2")
        seedPayloadRows("profile-1", "i42")
        seedPayloadRows("profile-2", "i99")
        secure.putString("portal_auth_token", "keep-me")

        store.onProfilePendingCleanup("profile-1")

        val queries = db.phoenixDatabaseQueries
        assertNull(queries.selectIntervalsStatus("profile-1").executeAsOneOrNull())
        assertNull(queries.selectIntervalsJob("profile-1", "i42", "ext-1").executeAsOneOrNull())
        assertNull(queries.selectCompletionReceipt("profile-1", "ROUTINE", "c-1").executeAsOneOrNull())
        assertNull(queries.selectIntervalsExternalIdReservation("i42", "ext-1").executeAsOneOrNull())
        assertNull(queries.selectCompletionJournal("profile-1", "ROUTINE", "c-1").executeAsOneOrNull())

        // The other profile is untouched.
        assertNotNull(queries.selectIntervalsStatus("profile-2").executeAsOneOrNull())
        assertNotNull(queries.selectIntervalsJob("profile-2", "i99", "ext-1").executeAsOneOrNull())
        assertNotNull(queries.selectCompletionReceipt("profile-2", "ROUTINE", "c-1").executeAsOneOrNull())
        assertNotNull(queries.selectIntervalsExternalIdReservation("i99", "ext-1").executeAsOneOrNull())
        assertNotNull(secure.getStringOrNull(keyRef("profile-2", 1L)))
        assertEquals("keep-me", secure.getStringOrNull("portal_auth_token"))
        assertNull(secure.getStringOrNull(keyRef("profile-1", 1L)), "owned keys deleted with the profile")
    }

    @Test
    fun profileDeletionPurgeIsRetryableWhenKeyDeletionFails() = runTest {
        setUp()
        val store = newStore()
        connectedStore(store, profileId = "profile-1", apiKey = "k1")
        seedPayloadRows("profile-1", "i42")

        secure.failRemove = true
        assertFails { store.onProfilePendingCleanup("profile-1") }
        // Retried entry: rows must still be purgeable and the profile cannot authorize.
        assertNotNull(db.phoenixDatabaseQueries.selectIntervalsJob("profile-1", "i42", "ext-1").executeAsOneOrNull())

        secure.failRemove = false
        store.onProfilePendingCleanup("profile-1")
        assertNull(db.phoenixDatabaseQueries.selectIntervalsJob("profile-1", "i42", "ext-1").executeAsOneOrNull())
        assertNull(db.phoenixDatabaseQueries.selectIntervalsStatus("profile-1").executeAsOneOrNull())
    }

    // ------------------------------------------------------------------ truth table

    @Test
    fun isDispatchAuthorizedTruthTable() = runTest {
        setUp()
        val store = newStore()
        val generation = connectedStore(store)

        // Happy path.
        assertTrue(store.isDispatchAuthorized(binding(generation)))

        // Stale generation: a captured generation that is not the current one.
        assertFalse(store.isDispatchAuthorized(binding(generation + 1)), "future/replaced generation is stale")
        assertFalse(store.isDispatchAuthorized(binding(0L)), "zero generation is never authorized")
        // Rotation: the pre-rotation binding is stale after a fresh generation lands.
        store.saveCredentials(PROFILE, "i42", "fixture-key-2")
        store.enable(PROFILE)
        assertFalse(store.isDispatchAuthorized(binding(generation)), "stale generation after rotation")
        assertTrue(store.isDispatchAuthorized(binding(generation + 1)))

        // Secret missing.
        secure.remove(keyRef(PROFILE, generation + 1))
        assertFalse(store.isDispatchAuthorized(binding(generation + 1)), "secret missing")
        secure.putString(keyRef(PROFILE, generation + 1), "fixture-key-2")

        // Installation mismatch: binding captured under a different installation.
        assertFalse(
            store.isDispatchAuthorized(binding(generation + 1, installationId = "other-install")),
            "installation mismatch",
        )
        // Restored/reinstalled identity: the row's installation no longer matches.
        secure.putString(IntervalsConnectionStore.INSTALLATION_ID_KEY, "different-installation")
        assertFalse(store.isDispatchAuthorized(binding(generation + 1)), "restored installation mismatch")
        secure.putString(IntervalsConnectionStore.INSTALLATION_ID_KEY, installationIdAt(generation + 1))

        // Destination mismatch: no bulk rebind to a new athlete.
        assertFalse(
            store.isDispatchAuthorized(binding(generation + 1, resolvedAthleteId = "i99")),
            "destination mismatch",
        )

        // Paused.
        store.pause(PROFILE)
        assertFalse(store.isDispatchAuthorized(binding(generation + 1)), "paused")
        store.resume(PROFILE)
        assertTrue(store.isDispatchAuthorized(binding(generation + 1)))

        // Disabled after disconnect: authorization generation revoked.
        store.disconnect(PROFILE)
        assertFalse(store.isDispatchAuthorized(binding(generation + 1)), "disconnected/revoked")
    }

    /** Reads the installation id recorded in the metadata row for [generation]. */
    private fun installationIdAt(generation: Long): String {
        val row = assertNotNull(db.phoenixDatabaseQueries.selectIntervalsStatus(PROFILE).executeAsOneOrNull())
        assertEquals(generation, row.connection_generation)
        return row.installation_id
    }

    // ------------------------------------------------------------------ request-scoped key

    @Test
    fun authorizedRequestIsRequestScopedAndRefusesWhenUnavailable() = runTest {
        setUp()
        val store = newStore()
        val generation = connectedStore(store)

        val used = store.authorizedRequest(PROFILE) { athlete, key -> athlete to key }
        assertEquals(CANONICAL_ATHLETE to "fixture-key-1", used)

        secure.remove(keyRef(PROFILE, generation))
        assertNull(store.authorizedRequest(PROFILE) { athlete, key -> athlete to key })
    }

    // ------------------------------------------------------------------ helpers

    private fun seedPayloadRows(profileId: String, athleteId: String) {
        val queries = db.phoenixDatabaseQueries
        queries.insertCompletionJournalIfAbsent(
            profile_id = profileId,
            local_kind = "ROUTINE",
            completion_id = "c-1",
            finish_reason = "NORMAL_FINISH",
            routine_title = "Fixture",
            expected_session_count = 1L,
            expected_set_count = 1L,
            membership_digest = "digest",
            start_epoch_ms = 1L,
            start_local_iso = "2026-01-01T00:00:00",
            time_zone_id = "UTC",
            utc_offset_minutes = 0L,
            completed_epoch_ms = 2L,
            eligible = 1L,
            resolved_athlete_id = athleteId,
            connection_generation = 1L,
            destination_digest = "destination",
            created_at = 2L,
        )
        queries.insertCompletionReceiptIfAbsent(
            profile_id = profileId,
            local_kind = "ROUTINE",
            completion_id = "c-1",
            external_id = "ext-1",
            snapshot_version = 1L,
            membership_json = "[]",
            finish_reason = "NORMAL_FINISH",
            start_epoch_ms = 1L,
            start_local_iso = "2026-01-01T00:00:00",
            time_zone_id = "UTC",
            utc_offset_minutes = 0L,
            completed_epoch_ms = 2L,
            eligible = 1L,
            resolved_athlete_id = athleteId,
            connection_generation = 1L,
            export_payload = null,
            payload_digest = null,
            created_at = 2L,
        )
        queries.reserveIntervalsExternalId(
            resolved_athlete_id = athleteId,
            external_id = "ext-1",
            owner_profile_id = profileId,
            owner_local_kind = "ROUTINE",
            owner_completion_id = "c-1",
            created_at = 2L,
        )
        queries.insertIntervalsJobIfAbsent(
            profile_id = profileId,
            resolved_athlete_id = athleteId,
            external_id = "ext-1",
            receipt_profile_id = profileId,
            receipt_local_kind = "ROUTINE",
            receipt_completion_id = "c-1",
            payload_json = "{\"type\":\"WeightTraining\"}",
            payload_version = 1L,
            payload_digest = "digest",
            original_generation = 1L,
            dispatch_auth_generation = 0L,
            created_at = 2L,
        )
    }

    private companion object {
        const val PROFILE = "profile-1"
        const val CANONICAL_ATHLETE = "i42"
    }
}
