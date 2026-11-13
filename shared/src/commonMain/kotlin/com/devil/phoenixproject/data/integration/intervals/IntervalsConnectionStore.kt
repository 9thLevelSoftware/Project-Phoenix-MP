package com.devil.phoenixproject.data.integration.intervals

import co.touchlab.kermit.Logger
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.database.PhoenixDatabaseQueries
import com.devil.phoenixproject.database.SelectIntervalsStatus
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Profile-lifecycle seam used by profile deletion's retryable local cleanup
 * (`PendingProfileLocalCleanup` drain). Implementations are IDEMPOTENT: the call runs
 * under existing cleanup recovery and is retried until it returns without throwing.
 */
fun interface IntervalsProfileLifecycle {
    /**
     * Revokes the profile's dispatch authorization/generation, deletes the app's OWN
     * secure keys (never the shared auth Keychain), and purges the profile's Intervals
     * personal payloads. Throws on failure so the pending-cleanup entry is retried.
     */
    suspend fun onProfilePendingCleanup(profileId: String)
}

/**
 * Best-effort resolution of the DI-registered [IntervalsProfileLifecycle]. Returns null
 * outside a Koin app or when the Intervals module is not loaded (tests pass explicit
 * fakes through the repository constructor instead). Uses the multiplatform Koin
 * context API — `GlobalContext` is JVM-only.
 */
fun resolveIntervalsProfileLifecycleOrNull(): IntervalsProfileLifecycle? = runCatching {
    org.koin.mp.KoinPlatformTools.defaultContext().getOrNull()?.getOrNull<IntervalsProfileLifecycle>()
}.getOrNull()

/**
 * Best-effort resolution of the DI-registered [IntervalsConnectionStore], for callers
 * outside the integration DI graph. Returns null outside a Koin app or when the
 * Intervals module is not loaded.
 */
fun resolveIntervalsConnectionStoreOrNull(): IntervalsConnectionStore? = runCatching {
    org.koin.mp.KoinPlatformTools.defaultContext().getOrNull()?.getOrNull<IntervalsConnectionStore>()
}.getOrNull()

/**
 * Fail-closed resolution of [IntervalsConnectionStore] for backup restore revocation.
 * Returns null ONLY when Koin is not started (unit tests) or [IntervalsConnectionStore]
 * has no definition registered; any instantiation/keystore failure (e.g. encrypted
 * storage throwing [SecurityException]) propagates so restore aborts fail-closed.
 */
fun resolveIntervalsConnectionStoreForRestore(): IntervalsConnectionStore? {
    val koin = org.koin.mp.KoinPlatformTools.defaultContext().getOrNull() ?: return null
    return try {
        koin.get<IntervalsConnectionStore>()
    } catch (_: org.koin.core.error.NoDefinitionFoundException) {
        null
    } catch (_: org.koin.core.error.ClosedScopeException) {
        null
    }
}

/**
 * Platform proof gate for integration storage (integration-model §6 fail-closed seam).
 * iOS binds this to the live database family's backup-exclusion verification
 * (`IosIntegrationBackupExclusionGate`); Android has no such exclusion requirement and
 * binds a passing gate. False must fail the integration closed — the connection store
 * consults it immediately BEFORE storing any integration data.
 */
fun interface IntegrationStorageGate {
    /** True when integration data may be stored on this platform right now. */
    fun permitted(): Boolean
}

/**
 * Secure connection/consent/generation store for the Intervals.icu export
 * (issue #1384, packet P3; spec §4.5 and integration-model §6 are binding).
 *
 * Storage split:
 * - **Metadata** lives only in the SQL `IntervalsConnection` row (nonsecret).
 * - **Secret** lives ONLY behind the named secure `Settings` binding
 *   (`SecureSettingsQualifier`: EncryptedSharedPreferences on Android, Keychain on iOS),
 *   under versioned per-profile/generation keys `intervals.apiKey.{profileId}.{generation}`.
 *   The metadata row stores the reference, never the key. There is NO plaintext
 *   fallback: any secure-store failure leaves the export disabled with a safe
 *   explanation.
 *
 * Generations (§4.5):
 * - `connection_generation` is the credential/destination identity generation. It is a
 *   fresh token per rotation / destination mutation (every [saveCredentials] commit).
 *   Jobs capture it as `originalGeneration` before async work and keep it forever as
 *   provenance; a binding whose captured generation is no longer the current one is
 *   stale and never dispatches.
 * - `dispatch_auth_generation` is the authorization generation: `0` means revoked
 *   (durably by `invalidateIntervalsGeneration`), and every explicit consent
 *   ([enable]) issues a fresh token ("a new authorization generation") while leaving
 *   job provenance untouched.
 *
 * Mutation order (§4.5): durably invalidate the old generation -> write AND read back
 * the new secret -> commit validated metadata. Orphan secrets are cleaned on recovery;
 * metadata-without-secret can NEVER authorize a send (authorization re-resolves the
 * secret without logging it). Disconnect invalidates the generation durably BEFORE key
 * deletion and stays disabled even when deletion fails and is retried. Only the keys
 * this store owns (`intervals.*`) are ever deleted; the shared auth Keychain is never
 * cleared.
 *
 * Installation authorization: a nonsecret installation id (generated on first use and
 * kept in the secure store beside the secrets) is recorded in the connection row's
 * `installation_id`. Reinstall / restore / secret loss therefore fails closed:
 * the metadata no longer matches the installation (or resolves no secret) and the
 * connection is disabled until revalidation + fresh opt-in ([saveCredentials] +
 * [enable]).
 */
class IntervalsConnectionStore(
    private val db: PhoenixDatabase,
    private val secureSettings: Settings,
    private val api: IntervalsApi,
    private val nowEpochMs: () -> Long = { currentTimeMillis() },
    /**
     * The `SqlDriver` backing [db]. The frozen named query set never returns the row's
     * `secret_key_ref` column, so precise orphan/revocation decisions read it back with
     * a read-only scan (same precedent as `SqlDelightIntervalsOutboxRepository`'s
     * `rawScanDriver`). Without it, recovery falls back to the derived reference, which
     * is conservative about deletion.
     */
    private val rawScanDriver: SqlDriver? = null,
    /**
     * Platform backup-exclusion gate (integration-model §6 fail-closed seam): consulted
     * immediately BEFORE storing any integration data. iOS verifies the live database
     * family's backup exclusion and returns false when it cannot prove it
     * ([IosIntegrationBackupExclusionGate]); Android has no such exclusion and passes.
     * False fails the integration closed — no write happens and a safe explanation is
     * returned instead of a silent partial state.
     */
    private val integrationStoragePermitted: () -> Boolean = { true },
) : IntervalsConnectionReader, IntervalsCredentialSource, CompletionEligibilityResolver, IntervalsProfileLifecycle {

    private val queries: PhoenixDatabaseQueries get() = db.phoenixDatabaseQueries

    private val _secureStoreAvailable = MutableStateFlow(true)

    /** False once the secure store throws; never falls back to plaintext storage. */
    val secureStoreAvailable: StateFlow<Boolean> = _secureStoreAvailable.asStateFlow()

    private val _ownedKeyDeletionPending = MutableStateFlow(false)

    /** True while owned-key deletion is failing/retrying; the connection stays disabled. */
    val ownedKeyDeletionPending: StateFlow<Boolean> = _ownedKeyDeletionPending.asStateFlow()

    // ------------------------------------------------------------------ identity

    /**
     * The current installation id (nonsecret). Generated once, written + read back in
     * the secure store so a reinstall/restore that loses the secure store also loses
     * this id and must revalidate. Null while the secure store is unusable.
     */
    fun currentInstallationIdOrNull(): String? = try {
        val existing = secureSettings.getStringOrNull(INSTALLATION_ID_KEY)?.takeIf { it.isNotBlank() }
        if (existing != null) {
            _secureStoreAvailable.value = true
            existing
        } else {
            val fresh = generateUUID()
            secureSettings.putString(INSTALLATION_ID_KEY, fresh)
            val readBack = secureSettings.getStringOrNull(INSTALLATION_ID_KEY)
            _secureStoreAvailable.value = readBack == fresh
            readBack?.takeIf { it == fresh }
        }
    } catch (t: Throwable) {
        Logger.w(t) { "Intervals: secure store unavailable while resolving installation id" }
        _secureStoreAvailable.value = false
        null
    }

    /** Versioned, per-profile/generation secret reference. The row stores this ref only. */
    private fun secretKeyRef(profileId: String, generation: Long): String =
        SECRET_KEY_PREFIX + profileId + "." + generation

    private fun installationMatches(row: SelectIntervalsStatus): Boolean {
        val current = currentInstallationIdOrNull() ?: return false
        return row.installation_id.isNotBlank() && row.installation_id == current
    }

    /** Never logs the value. Returns null when missing or the store throws. */
    private fun readSecretOrNull(profileId: String, generation: Long): String? = try {
        val value = secureSettings.getStringOrNull(secretKeyRef(profileId, generation))
        _secureStoreAvailable.value = true
        value?.takeIf { it.isNotEmpty() }
    } catch (t: Throwable) {
        Logger.w(t) { "Intervals: secure store unavailable while resolving credential" }
        _secureStoreAvailable.value = false
        null
    }

    // ------------------------------------------------------------------ read side

    /**
     * Nonsecret status of the profile's USABLE connection, or null when no usable
     * connection exists: never saved/enabled, disabled, disconnected, secret lost, or
     * installation mismatch (the metadata row may still exist for provenance and
     * dedupe). The secret is never read into the result and never logged.
     */
    override suspend fun status(profileId: String): IntervalsConnectionStatus? {
        val row = queries.selectIntervalsStatus(profileId).executeAsOneOrNull() ?: return null
        if (row.enabled != 1L) return null
        if (!installationMatches(row)) return null
        if (readSecretOrNull(row.profile_id, row.connection_generation) == null) return null
        return row.toConnectionStatus()
    }

    /**
     * True only when this exact captured binding may dispatch right now. Checks,
     * fail-closed in order: live connection, enabled, not paused, not auth-required,
     * current authorization generation, binding generation current (a binding from a
     * replaced/rotated generation is stale), same destination (never a bulk rebind to a
     * new athlete), installation authorized, and the referenced secret still resolves
     * (metadata-without-secret can never authorize sends).
     */
    override suspend fun isDispatchAuthorized(binding: IntervalsDispatchBinding): Boolean {
        val row = queries.selectIntervalsStatus(binding.profileId).executeAsOneOrNull() ?: return false
        return isDispatchAuthorized(row, binding)
    }

    private fun isDispatchAuthorized(row: SelectIntervalsStatus, binding: IntervalsDispatchBinding): Boolean {
        if (row.enabled != 1L || row.paused == 1L || row.auth_required == 1L) return false
        // Authorization generation current: 0 is the revoked marker.
        if (row.dispatch_auth_generation == 0L) return false
        // Generation current: the captured provenance generation must be the current
        // connection generation. Zero or replaced/future generations are stale.
        if (binding.originalGeneration <= 0L || binding.originalGeneration != row.connection_generation) return false
        if (binding.profileId != row.profile_id) return false
        if (binding.resolvedAthleteId != row.resolved_athlete_id) return false
        if (binding.installationId.isEmpty() || !installationMatches(row)) return false
        if (binding.installationId != row.installation_id) return false
        // Metadata-without-secret never authorizes. Verified without logging the secret.
        return readSecretOrNull(row.profile_id, row.connection_generation) != null
    }

    /** Completion-time eligibility capture (future completions only; see [enable]). */
    override fun resolve(profileId: String, atEpochMs: Long): CompletionEligibility {
        val row = queries.selectIntervalsStatus(profileId).executeAsOneOrNull()
            ?: return CompletionEligibility.Ineligible
        val eligible = row.enabled == 1L &&
            row.paused == 0L &&
            row.dispatch_auth_generation != 0L &&
            row.eligibility_epoch > 0L &&
            atEpochMs >= row.eligibility_epoch &&
            installationMatches(row) &&
            readSecretOrNull(row.profile_id, row.connection_generation) != null
        return CompletionEligibility(
            installationId = row.installation_id,
            eligible = eligible,
            resolvedAthleteId = row.resolved_athlete_id,
            connectionGeneration = row.connection_generation,
        )
    }

    /**
     * Request-scoped credential fetch. The key exists only for the duration of [block]
     * and is never stored on this object, logged, or written anywhere else.
     */
    suspend fun <T> authorizedRequest(profileId: String, block: (athleteId: String, apiKey: String) -> T): T? {
        val row = queries.selectIntervalsStatus(profileId).executeAsOneOrNull() ?: return null
        if (row.enabled != 1L || !installationMatches(row)) return null
        val apiKey = readSecretOrNull(row.profile_id, row.connection_generation) ?: return null
        return block(row.resolved_athlete_id, apiKey)
    }

    /** [IntervalsCredentialSource] seam for the export coordinator. */
    override suspend fun apiKey(profileId: String): String? {
        val row = queries.selectIntervalsStatus(profileId).executeAsOneOrNull() ?: return null
        if (row.enabled != 1L || !installationMatches(row)) return null
        return readSecretOrNull(row.profile_id, row.connection_generation)
    }

    // ------------------------------------------------------------------ save / test

    /**
     * Nonmutating connection test (`GET /api/v1/athlete/{id}`). Testing NEVER enables
     * the export and mutates nothing.
     */
    suspend fun testConnection(athleteId: String, apiKey: String): IntervalsValidationResult =
        api.validateAthlete(athleteId, apiKey)

    /**
     * Validates nonmutating first and NEVER enables. On validation success the mutation
     * order is strict (spec §4.5): durably invalidate the old generation -> write and
     * read back the new secret -> commit validated metadata (disabled: save/test never
     * enable, only the explicit [enable] action does). Orphan secrets of the profile
     * are cleaned after the commit. A secure-store failure leaves the connection
     * disabled with a safe explanation and no plaintext fallback.
     */
    suspend fun saveCredentials(
        profileId: String,
        athleteId: String,
        apiKey: String,
    ): IntervalsValidationResult {
        if (profileId.isBlank() || athleteId.isBlank() || apiKey.isBlank()) {
            return IntervalsValidationResult.Rejected(0, "missing credential input")
        }
        // Integration storage fail-closed (integration-model §6): never store integration
        // data while the platform cannot prove backup exclusion.
        if (!integrationStoragePermitted()) {
            return IntervalsValidationResult.Rejected(
                0,
                "device backup-exclusion verification failed; Intervals sync stays off",
            )
        }
        val validation = api.validateAthlete(athleteId, apiKey)
        if (validation !is IntervalsValidationResult.Valid) return validation

        val now = nowEpochMs()
        val existing = queries.selectIntervalsStatus(profileId).executeAsOneOrNull()

        // 1) Durably invalidate the old generation BEFORE any secret write.
        if (existing != null) {
            queries.invalidateIntervalsGeneration(
                now = now,
                profile_id = profileId,
                connection_generation = existing.connection_generation,
            )
        }

        // 2) Fresh generation token for this rotation / destination mutation.
        val generation = (existing?.connection_generation ?: 0L) + 1L
        val ref = secretKeyRef(profileId, generation)

        // 3) Write AND read back the new secret before any metadata commit.
        val readBack = try {
            secureSettings.putString(ref, apiKey)
            secureSettings.getStringOrNull(ref)
        } catch (t: Throwable) {
            Logger.w(t) { "Intervals: secure credential write failed; export stays disabled" }
            null
        }
        if (readBack != apiKey) {
            _secureStoreAvailable.value = false
            return IntervalsValidationResult.Rejected(0, "secure credential storage unavailable")
        }
        _secureStoreAvailable.value = true

        val installationId = currentInstallationIdOrNull()
            ?: return IntervalsValidationResult.Rejected(0, "secure credential storage unavailable")

        // 4) Commit validated metadata — disabled, never authorized by a save.
        try {
            db.transaction {
                if (existing == null) {
                    queries.insertIntervalsConnectionIfAbsent(
                        profile_id = profileId,
                        resolved_athlete_id = validation.canonicalAthleteId,
                        connection_generation = generation,
                        dispatch_auth_generation = 0L,
                        installation_id = installationId,
                        secret_key_ref = ref,
                        enabled = 0L,
                        paused = 0L,
                        auth_required = 0L,
                        validated_at = now,
                        enabled_at = null,
                        paused_at = null,
                        consent_version = 0L,
                        disclosure_version = 0L,
                        eligibility_epoch = 0L,
                        updated_at = now,
                    )
                }
                queries.upsertIntervalsConnection(
                    profile_id = profileId,
                    resolved_athlete_id = validation.canonicalAthleteId,
                    connection_generation = generation,
                    dispatch_auth_generation = 0L,
                    installation_id = installationId,
                    secret_key_ref = ref,
                    enabled = 0L,
                    paused = 0L,
                    auth_required = 0L,
                    validated_at = now,
                    enabled_at = null,
                    paused_at = null,
                    consent_version = 0L,
                    disclosure_version = 0L,
                    eligibility_epoch = 0L,
                    updated_at = now,
                )
                // Readback must show the committed generation, or nothing was durable.
                val committed = queries.selectIntervalsStatus(profileId).executeAsOneOrNull()
                check(committed != null && committed.connection_generation == generation) {
                    "Intervals connection metadata commit failed readback"
                }
            }
        } catch (t: Throwable) {
            Logger.e(t) { "Intervals: connection metadata commit failed; export stays disabled" }
            return IntervalsValidationResult.Rejected(0, "connection metadata write failed")
        }

        // 5) Orphan-secret cleanup for this profile (best effort; recovery retries).
        runCatching { cleanOwnedSecretsOrThrow(profileId, keepRef = ref) }
            .onSuccess { _ownedKeyDeletionPending.value = false }
            .onFailure { _ownedKeyDeletionPending.value = true }
        return validation
    }

    // ------------------------------------------------------------------ consent

    /**
     * Explicit user consent (spec §4.5): requires a fresh nonmutating validation of the
     * STORED credential, then enables with a new eligibility epoch (future completions
     * only) and a fresh authorization generation. Same-destination paused jobs resume;
     * a different athlete can never receive them. Never succeeds on secret loss,
     * installation mismatch or validation failure.
     */
    suspend fun enable(profileId: String): Boolean {
        // Integration storage fail-closed (integration-model §6): an enable that cannot
        // prove backup exclusion must not authorize anything.
        if (!integrationStoragePermitted()) return false
        val row = queries.selectIntervalsStatus(profileId).executeAsOneOrNull() ?: return false
        val apiKey = readSecretOrNull(row.profile_id, row.connection_generation) ?: return false
        if (!installationMatches(row)) return false

        val validation = api.validateAthlete(row.resolved_athlete_id, apiKey)
        when (validation) {
            is IntervalsValidationResult.Valid -> Unit
            is IntervalsValidationResult.InvalidCredentials -> {
                markAuthRequired(profileId, row)
                return false
            }
            // Server rejections and network failures mutate nothing: no consent without
            // a fresh successful validation.
            else -> return false
        }

        val now = nowEpochMs()
        val wasEnabled = row.enabled == 1L
        // A new authorization generation; provenance (connection_generation) is untouched.
        val authGeneration = maxOf(row.connection_generation, row.dispatch_auth_generation) + 1L
        // The eligibility epoch starts at the FIRST opt-in and never moves backwards;
        // a repeated enable does not strand completions already admitted as eligible.
        val eligibilityEpoch = if (wasEnabled && row.eligibility_epoch > 0L) {
            row.eligibility_epoch
        } else {
            now
        }
        queries.upsertIntervalsConnection(
            profile_id = row.profile_id,
            resolved_athlete_id = row.resolved_athlete_id,
            connection_generation = row.connection_generation,
            dispatch_auth_generation = authGeneration,
            installation_id = row.installation_id,
            secret_key_ref = secretKeyRef(row.profile_id, row.connection_generation),
            enabled = 1L,
            paused = 0L,
            auth_required = 0L,
            validated_at = now,
            enabled_at = row.enabled_at ?: now,
            paused_at = null,
            consent_version = CURRENT_CONSENT_VERSION.toLong(),
            disclosure_version = CURRENT_DISCLOSURE_VERSION.toLong(),
            eligibility_epoch = eligibilityEpoch,
            updated_at = now,
        )
        // Resume only same-destination eligible jobs (integration-model §6).
        queries.resumeIntervalsProfileJobs(
            now = now,
            profile_id = row.profile_id,
            resolved_athlete_id = row.resolved_athlete_id,
            max_attempts = IntervalsTransportPolicy.MAX_CREATE_ATTEMPTS.toLong(),
        )
        // Same-destination provenance re-stamp at this explicit fresh-consent enable:
        // jobs queued under a replaced connection generation (credential rotation)
        // re-bind to the current generation instead of being permanently stranded by
        // the originalGeneration CAS. A different athlete is never rebound; attempts,
        // lifetime counters and job state are untouched.
        queries.restampIntervalsProfileJobs(
            connection_generation = row.connection_generation,
            now = now,
            profile_id = row.profile_id,
            resolved_athlete_id = row.resolved_athlete_id,
        )
        return true
    }

    /**
     * Pause (integration-model §6): already eligible jobs stay PAUSED for the unchanged
     * destination and completions while paused are ineligible — resume never backfills.
     * Ambiguity is preserved: UNCERTAIN / PERMANENT_FAILURE rows are untouched.
     */
    suspend fun pause(profileId: String) {
        val row = queries.selectIntervalsStatus(profileId).executeAsOneOrNull() ?: return
        if (row.enabled != 1L || row.paused == 1L) return
        val now = nowEpochMs()
        queries.upsertIntervalsConnection(
            profile_id = row.profile_id,
            resolved_athlete_id = row.resolved_athlete_id,
            connection_generation = row.connection_generation,
            dispatch_auth_generation = row.dispatch_auth_generation,
            installation_id = row.installation_id,
            secret_key_ref = secretKeyRef(row.profile_id, row.connection_generation),
            enabled = 1L,
            paused = 1L,
            auth_required = row.auth_required,
            validated_at = row.validated_at,
            enabled_at = row.enabled_at,
            paused_at = now,
            consent_version = row.consent_version,
            disclosure_version = row.disclosure_version,
            eligibility_epoch = row.eligibility_epoch,
            updated_at = now,
        )
        queries.pauseIntervalsProfileJobs(now = now, profile_id = row.profile_id)
    }

    /**
     * Resume: only same-destination authorized jobs resume
     * (`resumeIntervalsProfileJobs`); completions admitted while paused stay ineligible.
     * An auth-required connection needs the reconnect flow (fresh validation + explicit
     * [enable]) and cannot be resumed away.
     */
    suspend fun resume(profileId: String) {
        val row = queries.selectIntervalsStatus(profileId).executeAsOneOrNull() ?: return
        if (row.enabled != 1L || row.paused != 1L || row.auth_required == 1L) return
        val now = nowEpochMs()
        queries.upsertIntervalsConnection(
            profile_id = row.profile_id,
            resolved_athlete_id = row.resolved_athlete_id,
            connection_generation = row.connection_generation,
            dispatch_auth_generation = row.dispatch_auth_generation,
            installation_id = row.installation_id,
            secret_key_ref = secretKeyRef(row.profile_id, row.connection_generation),
            enabled = 1L,
            paused = 0L,
            auth_required = 0L,
            validated_at = row.validated_at,
            enabled_at = row.enabled_at,
            paused_at = null,
            consent_version = row.consent_version,
            disclosure_version = row.disclosure_version,
            eligibility_epoch = row.eligibility_epoch,
            updated_at = now,
        )
        queries.resumeIntervalsProfileJobs(
            now = now,
            profile_id = row.profile_id,
            resolved_athlete_id = row.resolved_athlete_id,
            max_attempts = IntervalsTransportPolicy.MAX_CREATE_ATTEMPTS.toLong(),
        )
    }

    // ------------------------------------------------------------------ disconnect / restore

    /**
     * Disconnect (integration-model §6): the generation is durably invalidated BEFORE
     * any key deletion; the connection stays disabled even when key deletion fails and
     * is retried. Remote activities are never touched. Only this app's OWN keys are
     * deleted — the shared auth Keychain is never cleared.
     */
    suspend fun disconnect(profileId: String) {
        val row = queries.selectIntervalsStatus(profileId).executeAsOneOrNull() ?: return
        queries.invalidateIntervalsGeneration(
            now = nowEpochMs(),
            profile_id = row.profile_id,
            connection_generation = row.connection_generation,
        )
        try {
            // Revocation: delete ALL owned keys (keepRef = null).
            cleanOwnedSecretsOrThrow(profileId, keepRef = null)
            _ownedKeyDeletionPending.value = false
        } catch (t: Throwable) {
            // Already disabled durably above; deletion retried by recovery/cleanup.
            Logger.w(t) { "Intervals: owned key deletion failed; connection remains disabled" }
            _ownedKeyDeletionPending.value = true
        }
    }

    /**
     * Restore/import revocation (spec §4.5): dispatch authorization and the owned
     * secret are revoked BEFORE any row replacement, so a restore that later fails
     * still cannot dispatch. Strict: invalidates the SQL generation durably first,
     * then deletes owned secrets and rethrows any secure-store failure so restore
     * aborts fail-closed instead of proceeding with unrevoked credentials. Idempotent.
     */
    suspend fun revokeForRestore(profileId: String) {
        val row = queries.selectIntervalsStatus(profileId).executeAsOneOrNull()
        if (row != null) {
            queries.invalidateIntervalsGeneration(
                now = nowEpochMs(),
                profile_id = row.profile_id,
                connection_generation = row.connection_generation,
            )
        }
        try {
            cleanOwnedSecretsOrThrow(profileId, keepRef = null)
            _ownedKeyDeletionPending.value = false
        } catch (t: Throwable) {
            _ownedKeyDeletionPending.value = true
            throw t
        }
    }

    // ------------------------------------------------------------------ recovery

    /**
     * Startup/profile recovery: secret loss / installation mismatch fails closed
     * (durable invalidation) and orphan secrets are cleaned. Idempotent.
     */
    suspend fun recoverForProfile(profileId: String) {
        val row = queries.selectIntervalsStatus(profileId).executeAsOneOrNull()
        if (row != null) {
            val secretOk = readSecretOrNull(row.profile_id, row.connection_generation) != null
            if (!secretOk || !installationMatches(row)) {
                queries.invalidateIntervalsGeneration(
                    now = nowEpochMs(),
                    profile_id = row.profile_id,
                    connection_generation = row.connection_generation,
                )
            }
        }
        runCatching { cleanOwnedSecretsOrThrow(profileId, keepRef = keepRefFor(profileId)) }
            .onSuccess { _ownedKeyDeletionPending.value = false }
            .onFailure { _ownedKeyDeletionPending.value = true }
        // Global orphan-secret sweep (documented contract): keys written in the crash
        // window between secret write and metadata commit have no row referencing them
        // and are only reachable here. Idempotent, prefix-scoped to this store's own keys.
        runCatching { cleanOrphanSecrets() }
    }

    /**
     * Global orphan-secret sweep: every `intervals.apiKey.{profileId}.{generation}` key
     * that is not the committed metadata reference is deleted (crash windows between
     * secret write and metadata commit included). Only keys under this store's own
     * prefix are ever considered.
     */
    suspend fun cleanOrphanSecrets() {
        runCatching { cleanOrphanSecretsOrThrow() }
    }

    /**
     * Strict orphan-secret sweep for fail-closed restore revocation: throws if reading
     * or deleting any owned orphan key in [secureSettings] fails.
     */
    suspend fun cleanOrphanSecretsOrThrow() {
        // Snapshot: removal during iteration over a live key view would break.
        val keys = try {
            secureSettings.keys.toList()
        } catch (t: Throwable) {
            _ownedKeyDeletionPending.value = true
            throw t
        }
        for (key in keys) {
            if (!key.startsWith(SECRET_KEY_PREFIX)) continue
            val remainder = key.substring(SECRET_KEY_PREFIX.length)
            val cut = remainder.lastIndexOf('.')
            if (cut <= 0 || cut == remainder.length - 1) continue
            val profileId = remainder.substring(0, cut)
            if (remainder.substring(cut + 1).toLongOrNull() == null) continue
            if (key == keepRefFor(profileId)) continue
            try {
                secureSettings.remove(key)
            } catch (t: Throwable) {
                _ownedKeyDeletionPending.value = true
                throw t
            }
        }
    }

    /** [IntervalsProfileLifecycle] hook for profile deletion's retryable cleanup. */
    override suspend fun onProfilePendingCleanup(profileId: String) {
        val row = queries.selectIntervalsStatus(profileId).executeAsOneOrNull()
        if (row != null) {
            queries.invalidateIntervalsGeneration(
                now = nowEpochMs(),
                profile_id = row.profile_id,
                connection_generation = row.connection_generation,
            )
        }
        // Strict here: a throw keeps the pending-cleanup entry queued for retry.
        cleanOwnedSecretsOrThrow(profileId, keepRef = null)
        db.transaction {
            queries.deleteIntervalsProfileJobs(profileId)
            queries.deleteIntervalsProfileReceipts(profileId)
            queries.deleteIntervalsProfileReservations(profileId)
            // Explicit child cleanup (repo convention: never rely on ON DELETE CASCADE —
            // foreign_keys may be off). Child replay payloads hold full session/set/rep
            // JSON and must not survive the journal delete.
            queries.deleteIntervalsProfileJournalChildren(profileId)
            queries.deleteIntervalsProfileJournal(profileId)
            queries.deleteIntervalsProfileState(profileId)
        }
        _ownedKeyDeletionPending.value = false
    }

    /**
     * Deletes every owned `intervals.apiKey.{profileId}.*` key except [keepRef] (the
     * reference that must survive, or null to delete all owned keys — the revocation
     * paths). Strict: throws on secure-store failure. Never touches any key outside
     * this store's own prefix (the shared auth Keychain is never cleared).
     *
     * The profile id is parsed at the LAST dot (same grammar as [cleanOrphanSecrets])
     * and compared exactly, so profile `abc` can never match profile `abc.def`'s keys.
     */
    private fun cleanOwnedSecretsOrThrow(profileId: String, keepRef: String?) {
        // Snapshot: some Settings backends return a live key view that breaks when
        // entries are removed during iteration.
        for (key in secureSettings.keys.toList()) {
            if (!key.startsWith(SECRET_KEY_PREFIX)) continue
            val remainder = key.substring(SECRET_KEY_PREFIX.length)
            val cut = remainder.lastIndexOf('.')
            if (cut <= 0 || cut == remainder.length - 1) continue
            if (remainder.substring(0, cut) != profileId) continue
            if (remainder.substring(cut + 1).toLongOrNull() == null) continue
            if (key == keepRef) continue
            secureSettings.remove(key)
        }
    }

    /**
     * The reference that recovery must keep for [profileId]. With [rawScanDriver] the
     * committed `secret_key_ref` is authoritative: a NULL committed reference
     * (revoked/invalidated) keeps nothing. Without a readable scan the derived
     * current-generation reference is kept, which is conservative about deletion.
     */
    private fun keepRefFor(profileId: String): String? {
        val row = queries.selectIntervalsStatus(profileId).executeAsOneOrNull()
        val derived = row?.let { secretKeyRef(it.profile_id, it.connection_generation) }
        val driver = rawScanDriver ?: return derived
        val scan = runCatching {
            val ref = driver.executeQuery(
                null,
                "SELECT secret_key_ref FROM IntervalsConnection WHERE profile_id = ?",
                { cursor ->
                    QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
                },
                1,
            ) {
                bindString(0, profileId)
            }.value
            true to ref
        }.getOrNull()
        if (scan != null && scan.first) return scan.second
        return derived
    }

    // ------------------------------------------------------------------ internals

    private fun markAuthRequired(profileId: String, row: SelectIntervalsStatus) {
        val now = nowEpochMs()
        queries.upsertIntervalsConnection(
            profile_id = row.profile_id,
            resolved_athlete_id = row.resolved_athlete_id,
            connection_generation = row.connection_generation,
            dispatch_auth_generation = row.dispatch_auth_generation,
            installation_id = row.installation_id,
            secret_key_ref = secretKeyRef(row.profile_id, row.connection_generation),
            enabled = row.enabled,
            paused = row.paused,
            auth_required = 1L,
            validated_at = row.validated_at,
            enabled_at = row.enabled_at,
            paused_at = row.paused_at,
            consent_version = row.consent_version,
            disclosure_version = row.disclosure_version,
            eligibility_epoch = row.eligibility_epoch,
            updated_at = now,
        )
    }

    private fun SelectIntervalsStatus.toConnectionStatus(): IntervalsConnectionStatus = IntervalsConnectionStatus(
        profileId = profile_id,
        resolvedAthleteId = resolved_athlete_id,
        connectionGeneration = connection_generation,
        dispatchAuthGeneration = dispatch_auth_generation,
        installationId = installation_id,
        enabled = enabled == 1L,
        paused = paused == 1L,
        authRequired = auth_required == 1L,
        validatedAt = validated_at,
        enabledAt = enabled_at,
        pausedAt = paused_at,
        consentVersion = consent_version.toInt(),
        disclosureVersion = disclosure_version.toInt(),
        eligibilityEpoch = eligibility_epoch,
        updatedAt = updated_at,
    )

    companion object {
        /** Owned secret-key prefix. Only keys under this prefix are ever deleted. */
        const val SECRET_KEY_PREFIX: String = "intervals.apiKey."

        /** Owned non-secret installation id (secure-store backed, never in metadata alone). */
        const val INSTALLATION_ID_KEY: String = "intervals.installationId"

        const val CURRENT_CONSENT_VERSION: Int = 1
        const val CURRENT_DISCLOSURE_VERSION: Int = 1
    }
}
