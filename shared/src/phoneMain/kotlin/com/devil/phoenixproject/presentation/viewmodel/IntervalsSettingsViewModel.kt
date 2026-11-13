package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.data.integration.intervals.IntervalsExportStatus
import com.devil.phoenixproject.data.integration.intervals.IntervalsReasonCode
import com.devil.phoenixproject.data.integration.intervals.IntervalsValidationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Wiring seam for the Intervals Settings screen (issue #1384, packet P5).
 *
 * The screen's ViewModel depends only on this small interface so it compiles and tests
 * standalone; the parent wires real implementations (P3 `IntervalsConnectionStore` /
 * P4 `IntervalsExportCoordinator` adapter) later. Status values are always scoped to the
 * active profile + destination — never global counts.
 */
interface IntervalsStatusSource {
    /** Nonsecret, profile/destination-scoped export status for the active profile. */
    fun status(): Flow<IntervalsExportStatus>

    /** False when the device secure store (EncryptedSharedPreferences / Keychain) is unusable. */
    fun secureStoreAvailable(): StateFlow<Boolean>

    /** False while the device is offline; queued jobs are held, not lost. */
    fun networkAvailable(): StateFlow<Boolean>

    /**
     * Active profile identity. The Settings draft must be dropped on profile transitions,
     * which can happen while no connection exists yet (status carries no profile then).
     */
    fun activeProfileId(): StateFlow<String?>

    /** Reason code of the most recent permanent failure, when one exists. */
    fun permanentFailureReason(): StateFlow<IntervalsReasonCode?>

    /**
     * Validates (nonmutating) and stores the credentials on this device only.
     * Never enables the export and never stores the key outside secure storage.
     */
    suspend fun saveCredentials(athleteId: String, apiKey: String): IntervalsValidationResult

    /** Nonmutating connection test. Testing NEVER enables the export. */
    suspend fun testConnection(athleteId: String, apiKey: String): IntervalsValidationResult

    /**
     * Explicit user opt-in. Only future eligible completions become eligible.
     * Returns false when the opt-in did NOT happen (no stored credential, secret loss,
     * installation mismatch, failed revalidation, or the platform storage gate) so the
     * caller can surface it instead of silently recomputing the phase.
     */
    suspend fun enable(): Boolean

    suspend fun pause()

    suspend fun resume()

    /** Durably invalidates the generation and deletes this app's owned key. */
    suspend fun disconnect()

    /**
     * Reconciliation-backed upload check for ambiguous outcomes. This is a READBACK —
     * it must never create a second activity ("upload again" is forbidden).
     */
    suspend fun checkUpload(): IntervalsCheckUploadResult
}

/** Outcome of a check-upload/status readback. */
sealed interface IntervalsCheckUploadResult {
    /** Reconciliation finished; the status flow carries the resolved counts. */
    data object Checked : IntervalsCheckUploadResult

    data class Failed(val reason: IntervalsReasonCode, val retryable: Boolean) : IntervalsCheckUploadResult
}

/** Screen phase vocabulary (signoff/prototype states, spec §5 P5 / §6). */
enum class IntervalsPhase {
    OFF,
    DRAFT,
    TESTING,
    TESTED_DISABLED,
    ENABLED,
    PAUSED,
    AUTH_REQUIRED,
    SECURE_STORE_UNAVAILABLE,
    OFFLINE_QUEUED,
    RETRY_EXHAUSTED,
    UNCERTAIN,
    DISCONNECT_CONFIRM,
}

/** Validation errors rendered next to the credential fields. */
enum class IntervalsFieldError {
    ATHLETE_ID_REQUIRED,
    ATHLETE_ID_INVALID,
    API_KEY_REQUIRED,
}

/** Operation-level errors (save/test/check), mapped to localized copy by the screen. */
enum class IntervalsOperationError {
    NETWORK,
    CREDENTIALS_REJECTED,
    VALIDATION_REJECTED,
    /** The explicit opt-in did not take effect (stored credential/storage problem). */
    ENABLE_FAILED,
}

/** Check-upload action progress. */
enum class IntervalsCheckState { IDLE, CHECKING, CHECKED, FAILED }

/**
 * Nonsecret UI state for the Intervals Settings screen. This state is safe to retain:
 * it never contains the API key. The ephemeral key draft lives in
 * [IntervalsSettingsViewModel.apiKeyDraft] and is cleared on navigation-away, profile
 * transition and after save — it never enters saved state.
 */
data class IntervalsSettingsUiState(
    val phase: IntervalsPhase = IntervalsPhase.OFF,
    val athleteIdDraft: String = "",
    val athleteIdError: IntervalsFieldError? = null,
    val apiKeyError: IntervalsFieldError? = null,
    val operationError: IntervalsOperationError? = null,
    val testedOk: Boolean = false,
    val isSaving: Boolean = false,
    val isTesting: Boolean = false,
    val saveSucceeded: Boolean = false,
    val checkState: IntervalsCheckState = IntervalsCheckState.IDLE,
    val checkFailureReason: IntervalsReasonCode? = null,
    val permanentFailureReason: IntervalsReasonCode? = null,
    val secureStoreAvailable: Boolean = true,
    // Profile/destination-scoped status (from IntervalsExportStatus — never global).
    val profileId: String? = null,
    val resolvedAthleteId: String? = null,
    val connected: Boolean = false,
    val paused: Boolean = false,
    val authRequired: Boolean = false,
    val queuedCount: Long = 0L,
    val sentCount: Long = 0L,
    val uncertainCount: Long = 0L,
    val authRequiredCount: Long = 0L,
    val permanentFailureCount: Long = 0L,
    val lastSentAt: Long? = null,
)

/**
 * ViewModel for `IntervalsSettingsScreen` (phoneMain only — no Intervals UI or scheduling
 * may live in commonMain, so nothing here can auto-schedule exports on non-phone targets).
 *
 * Not an androidx `ViewModel` on purpose: without DI-scoped ViewModel wiring this instance
 * is remembered in `NavGraph`, and [onNavigatedAway] is the single lifecycle exit that
 * clears the ephemeral key draft and cancels the collection scope.
 */
class IntervalsSettingsViewModel(
    private val statusSource: IntervalsStatusSource,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val local = MutableStateFlow(IntervalsSettingsUiState())

    /**
     * Ephemeral API-key draft. Never persisted, never logged, never part of
     * [uiState]; cleared on navigation-away, profile transition and after save.
     */
    private val _apiKeyDraft = MutableStateFlow("")
    val apiKeyDraft: StateFlow<String> = _apiKeyDraft.asStateFlow()

    private data class SourceSnapshot(
        val status: IntervalsExportStatus,
        val secureStoreAvailable: Boolean,
        val networkAvailable: Boolean,
        val activeProfileId: String?,
        val failureReason: IntervalsReasonCode?,
    )

    private val sourceSnapshot: StateFlow<SourceSnapshot> = combine(
        statusSource.status(),
        statusSource.secureStoreAvailable(),
        statusSource.networkAvailable(),
        statusSource.activeProfileId(),
        statusSource.permanentFailureReason(),
    ) { status, secure, online, profileId, reason ->
        SourceSnapshot(status, secure, online, profileId, reason)
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        SourceSnapshot(
            IntervalsExportStatus(connection = null, jobCounts = emptyList(), lastSentAt = null),
            secureStoreAvailable = true,
            networkAvailable = true,
            activeProfileId = null,
            failureReason = null,
        ),
    )

    private var lastSeenProfileId: String? = null
    private var profileTrackingStarted = false

    init {
        // Profile transitions drop the draft in their own collector — never from inside
        // the render `combine`: writing state from inside a combine transform re-enters
        // the transform and, worse, the write can clobber the user's in-flight input from
        // a stale snapshot. Here the fresh `local.value` is read at transition time.
        scope.launch {
            statusSource.activeProfileId().collect { profileId ->
                if (!profileTrackingStarted) {
                    profileTrackingStarted = true
                    lastSeenProfileId = profileId
                } else if (profileId != lastSeenProfileId) {
                    lastSeenProfileId = profileId
                    clearDrafts(local.value)
                }
            }
        }
    }

    val uiState: StateFlow<IntervalsSettingsUiState> = combine(local, _apiKeyDraft, sourceSnapshot) { current, keyDraft, source ->
        render(current, keyDraft, source)
    }.stateIn(scope, SharingStarted.Eagerly, IntervalsSettingsUiState())

    // ── Draft input ─────────────────────────────────────────────────────────

    fun onAthleteIdChange(value: String) {
        local.value = local.value.copy(
            athleteIdDraft = value,
            athleteIdError = null,
            testedOk = false,
            saveSucceeded = false,
        )
    }

    fun onApiKeyChange(value: String) {
        _apiKeyDraft.value = value
        local.value = local.value.copy(
            apiKeyError = null,
            testedOk = false,
            saveSucceeded = false,
        )
    }

    // ── Credential actions ──────────────────────────────────────────────────

    fun saveCredentials() {
        val snapshot = local.value
        val athleteId = snapshot.athleteIdDraft.trim()
        val apiKey = _apiKeyDraft.value
        val athleteError = validateAthleteId(athleteId)
        val keyError = if (apiKey.isBlank()) IntervalsFieldError.API_KEY_REQUIRED else null
        if (athleteError != null || keyError != null) {
            local.value = snapshot.copy(athleteIdError = athleteError, apiKeyError = keyError)
            return
        }
        local.value = snapshot.copy(isSaving = true, operationError = null)
        scope.launch {
            when (statusSource.saveCredentials(athleteId, apiKey)) {
                is IntervalsValidationResult.Valid -> {
                    // Draft is cleared after save — the secret never lingers in UI state.
                    _apiKeyDraft.value = ""
                    local.value = local.value.copy(
                        isSaving = false,
                        saveSucceeded = true,
                        testedOk = false,
                    )
                }
                is IntervalsValidationResult.InvalidCredentials -> {
                    local.value = local.value.copy(
                        isSaving = false,
                        operationError = IntervalsOperationError.CREDENTIALS_REJECTED,
                    )
                }
                is IntervalsValidationResult.Rejected -> {
                    local.value = local.value.copy(
                        isSaving = false,
                        operationError = IntervalsOperationError.VALIDATION_REJECTED,
                    )
                }
                is IntervalsValidationResult.NetworkFailure -> {
                    local.value = local.value.copy(
                        isSaving = false,
                        operationError = IntervalsOperationError.NETWORK,
                    )
                }
            }
        }
    }

    fun testConnection() {
        val snapshot = local.value
        val athleteId = snapshot.athleteIdDraft.trim()
        val apiKey = _apiKeyDraft.value
        val athleteError = validateAthleteId(athleteId)
        val keyError = if (apiKey.isBlank()) IntervalsFieldError.API_KEY_REQUIRED else null
        if (athleteError != null || keyError != null) {
            local.value = snapshot.copy(athleteIdError = athleteError, apiKeyError = keyError)
            return
        }
        local.value = snapshot.copy(isTesting = true, operationError = null)
        scope.launch {
            // Testing NEVER enables the export — only the explicit enable action does.
            when (statusSource.testConnection(athleteId, apiKey)) {
                is IntervalsValidationResult.Valid -> {
                    local.value = local.value.copy(isTesting = false, testedOk = true)
                }
                is IntervalsValidationResult.InvalidCredentials -> {
                    local.value = local.value.copy(
                        isTesting = false,
                        testedOk = false,
                        operationError = IntervalsOperationError.CREDENTIALS_REJECTED,
                    )
                }
                is IntervalsValidationResult.Rejected -> {
                    local.value = local.value.copy(
                        isTesting = false,
                        testedOk = false,
                        operationError = IntervalsOperationError.VALIDATION_REJECTED,
                    )
                }
                is IntervalsValidationResult.NetworkFailure -> {
                    local.value = local.value.copy(
                        isTesting = false,
                        testedOk = false,
                        operationError = IntervalsOperationError.NETWORK,
                    )
                }
            }
        }
    }

    /** Explicit user opt-in. Eligibility starts now; past completions stay ineligible. */
    fun enable() {
        scope.launch {
            val enabled = statusSource.enable()
            if (!enabled) {
                // The opt-in did not happen (no stored credential, secret loss,
                // installation mismatch, failed revalidation, or storage gate):
                // surface it instead of silently recomputing the phase as if nothing
                // happened.
                local.value = local.value.copy(operationError = IntervalsOperationError.ENABLE_FAILED)
            } else {
                local.value = local.value.copy(operationError = null)
            }
        }
    }

    fun pause() {
        scope.launch { statusSource.pause() }
    }

    fun resume() {
        scope.launch { statusSource.resume() }
    }

    // ── Disconnect (confirmation step first) ────────────────────────────────

    /** Step 1: enter the disconnect-confirmation state. */
    fun disconnect() {
        local.value = local.value.copy(phase = IntervalsPhase.DISCONNECT_CONFIRM)
    }

    fun cancelDisconnect() {
        local.value = local.value.copy(phase = IntervalsPhase.OFF) // recomputed from source
    }

    fun confirmDisconnect() {
        scope.launch {
            statusSource.disconnect()
            _apiKeyDraft.value = ""
            // Leave the confirmation state; the phase is recomputed from source status.
            // saveSucceeded must go too: the credential and key are deleted here, so the
            // saved-credential opt-in gate has to close (otherwise "Turn on sending"
            // stays enabled and always fails with ENABLE_FAILED).
            local.value = local.value.copy(
                phase = IntervalsPhase.OFF,
                athleteIdDraft = "",
                testedOk = false,
                saveSucceeded = false,
                operationError = null,
            )
        }
    }

    // ── Uncertain outcome: check, never re-upload ───────────────────────────

    fun checkUpload() {
        local.value = local.value.copy(checkState = IntervalsCheckState.CHECKING, checkFailureReason = null)
        scope.launch {
            when (val result = statusSource.checkUpload()) {
                IntervalsCheckUploadResult.Checked -> {
                    local.value = local.value.copy(checkState = IntervalsCheckState.CHECKED)
                }
                is IntervalsCheckUploadResult.Failed -> {
                    local.value = local.value.copy(
                        checkState = IntervalsCheckState.FAILED,
                        checkFailureReason = result.reason,
                    )
                }
            }
        }
    }

    /** Retry the status check itself — never a blind create/upload retry. */
    fun retryCheck() {
        checkUpload()
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /**
     * Navigation-away (back, pop, dispose) and profile transitions funnel through here:
     * the ephemeral drafts are dropped and the collection scope is cancelled.
     */
    fun onNavigatedAway() {
        clearDrafts(local.value)
        scope.cancel()
    }

    private fun clearDrafts(current: IntervalsSettingsUiState) {
        _apiKeyDraft.value = ""
        local.value = current.copy(
            athleteIdDraft = "",
            athleteIdError = null,
            apiKeyError = null,
            testedOk = false,
            saveSucceeded = false,
        )
    }

    // ── Derivation ──────────────────────────────────────────────────────────

    private fun render(
        current: IntervalsSettingsUiState,
        keyDraft: String,
        source: SourceSnapshot,
    ): IntervalsSettingsUiState {
        val connection = source.status.connection
        val jobCounts = source.status.jobCounts
        val queued = source.status.queuedCount
        val sent = source.status.sentCount
        val uncertain = source.status.uncertainCount
        val authRequiredCount = source.status.authRequiredCount
        val permanentFailed = source.status.permanentFailureCount
        val draftTouched = current.athleteIdDraft.isNotBlank() || keyDraft.isNotBlank() ||
            current.athleteIdError != null || current.apiKeyError != null

        // Precedence per signoff vocabulary (spec §5 P5). DISCONNECT_CONFIRM and TESTING
        // are user-initiated and win; then secure-store failure; then pre-connection
        // phases; then the connected-state machine.
        val phase = when {
            current.phase == IntervalsPhase.DISCONNECT_CONFIRM -> IntervalsPhase.DISCONNECT_CONFIRM
            current.isTesting -> IntervalsPhase.TESTING
            !source.secureStoreAvailable -> IntervalsPhase.SECURE_STORE_UNAVAILABLE
            connection == null -> when {
                current.testedOk -> IntervalsPhase.TESTED_DISABLED
                draftTouched -> IntervalsPhase.DRAFT
                else -> IntervalsPhase.OFF
            }
            connection.authRequired || authRequiredCount > 0L -> IntervalsPhase.AUTH_REQUIRED
            permanentFailed > 0L -> IntervalsPhase.RETRY_EXHAUSTED
            uncertain > 0L -> IntervalsPhase.UNCERTAIN
            connection.paused -> IntervalsPhase.PAUSED
            !source.networkAvailable && queued > 0L -> IntervalsPhase.OFFLINE_QUEUED
            else -> IntervalsPhase.ENABLED
        }

        return current.copy(
            phase = phase,
            secureStoreAvailable = source.secureStoreAvailable,
            permanentFailureReason = source.failureReason,
            profileId = connection?.profileId,
            resolvedAthleteId = connection?.resolvedAthleteId,
            connected = connection != null,
            paused = connection?.paused == true,
            authRequired = connection?.authRequired == true,
            queuedCount = queued,
            sentCount = sent,
            uncertainCount = uncertain,
            authRequiredCount = authRequiredCount,
            permanentFailureCount = permanentFailed,
            lastSentAt = source.status.lastSentAt,
        )
    }

    private fun validateAthleteId(raw: String): IntervalsFieldError? = when {
        raw.isEmpty() -> IntervalsFieldError.ATHLETE_ID_REQUIRED
        !ATHLETE_ID_PATTERN.matches(raw) -> IntervalsFieldError.ATHLETE_ID_INVALID
        else -> null
    }

    private companion object {
        /**
         * Conservative local preflight: Intervals athlete IDs are short alphanumeric
         * tokens (e.g. `i123456`). This is a local format check only — the canonical ID
         * is always resolved by the nonmutating validation call.
         */
        val ATHLETE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
    }
}

