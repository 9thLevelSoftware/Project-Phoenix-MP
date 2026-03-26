package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.isIosPlatform
import com.devil.phoenixproject.data.integration.CsvImporter
import com.devil.phoenixproject.data.integration.CsvImportPreview
import com.devil.phoenixproject.data.integration.ExternalActivityRepository
import com.devil.phoenixproject.data.integration.HealthIntegration
import com.devil.phoenixproject.data.integration.IntegrationManager
import com.devil.phoenixproject.data.repository.SubscriptionStatus
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.domain.model.ExternalActivity
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.IntegrationStatus
import com.devil.phoenixproject.domain.model.WeightUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val log = Logger.withTag("IntegrationsViewModel")

data class IntegrationsUiState(
    val integrationStatuses: Map<IntegrationProvider, IntegrationStatus> = emptyMap(),
    val externalActivities: List<ExternalActivity> = emptyList(),
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val isSyncing: Boolean = false,
    val importPreview: CsvImportPreview? = null,
    val csvContent: String? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class IntegrationsViewModel(
    private val externalActivityRepo: ExternalActivityRepository,
    private val integrationManager: IntegrationManager,
    private val workoutRepository: WorkoutRepository,
    private val healthIntegration: HealthIntegration,
    private val userProfileRepository: UserProfileRepository,
    private val portalTokenStorage: PortalTokenStorage
) : ViewModel() {

    /** Resolved active profile ID, reactive to profile switches. */
    val activeProfileId: StateFlow<String> = userProfileRepository.activeProfile
        .map { it?.id ?: "default" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "default")

    /**
     * Whether the current user has an active paid subscription.
     * Combines local profile subscription status with portal premium status
     * so that either source being paid is sufficient.
     */
    val isPaidUser: StateFlow<Boolean> = combine(
        userProfileRepository.activeProfile,
        portalTokenStorage.currentUser
    ) { profile, portalUser ->
        val localPaid = profile?.subscriptionStatus == SubscriptionStatus.ACTIVE
        val portalPaid = portalUser?.isPremium == true
        localPaid || portalPaid
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _uiState = MutableStateFlow(IntegrationsUiState())
    val uiState: StateFlow<IntegrationsUiState> = _uiState.asStateFlow()

    private val _healthPermissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val healthPermissionRequests: SharedFlow<Unit> = _healthPermissionRequests.asSharedFlow()

    init {
        viewModelScope.launch {
            // Observe external activities reactively, switching when profile changes
            activeProfileId.flatMapLatest { profileId ->
                externalActivityRepo.getAll(profileId = profileId)
            }.collect { activities ->
                _uiState.value = _uiState.value.copy(externalActivities = activities)
            }
        }

        viewModelScope.launch {
            // Observe all integration statuses reactively, switching when profile changes
            activeProfileId.flatMapLatest { profileId ->
                externalActivityRepo.getAllIntegrationStatuses(profileId = profileId)
            }.collect { statuses ->
                val statusMap = statuses.associateBy { it.provider }
                _uiState.value = _uiState.value.copy(integrationStatuses = statusMap)
            }
        }
    }

    // ─── CSV Export ───────────────────────────────────────────────────────────

    /**
     * Load all sessions for the active profile and generate a Strong-compatible CSV string,
     * storing the result in [IntegrationsUiState.csvContent].
     */
    fun exportCsv(weightUnit: WeightUnit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExporting = true, errorMessage = null)
            try {
                val sessions = workoutRepository.getAllSessions(activeProfileId.value).first()
                val csv = com.devil.phoenixproject.data.integration.CsvExporter.generateStrongCsv(sessions, weightUnit)
                _uiState.value = _uiState.value.copy(
                    csvContent = csv,
                    successMessage = "Exported ${sessions.size} session(s) to CSV"
                )
                log.d { "CSV export complete: ${sessions.size} sessions" }
            } catch (e: Exception) {
                log.e(e) { "CSV export failed" }
                _uiState.value = _uiState.value.copy(errorMessage = "Export failed: ${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isExporting = false)
            }
        }
    }

    /** Clear the generated CSV content from state (e.g. after the share sheet is dismissed). */
    fun clearCsvContent() {
        _uiState.value = _uiState.value.copy(csvContent = null)
    }

    // ─── CSV Import ───────────────────────────────────────────────────────────

    /**
     * Parse [content] and store a [CsvImportPreview] for the user to review
     * before committing to the database. Uses the active profile and subscription state.
     */
    fun previewCsvImport(
        content: String,
        weightUnit: WeightUnit
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImporting = true, errorMessage = null)
            try {
                val preview = CsvImporter.parse(content, weightUnit, activeProfileId.value, isPaidUser.value)
                _uiState.value = _uiState.value.copy(importPreview = preview)
                if (preview.errors.isNotEmpty()) {
                    log.w { "CSV preview has ${preview.errors.size} error(s): ${preview.errors.first()}" }
                }
            } catch (e: Exception) {
                log.e(e) { "CSV preview failed" }
                _uiState.value = _uiState.value.copy(errorMessage = "Import parse failed: ${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isImporting = false)
            }
        }
    }

    /**
     * Commit the previewed activities to the database.
     * No-op when there is no current preview.
     */
    fun confirmImport() {
        val preview = _uiState.value.importPreview ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImporting = true, errorMessage = null)
            try {
                externalActivityRepo.upsertActivities(preview.activities)
                _uiState.value = _uiState.value.copy(
                    importPreview = null,
                    successMessage = "Imported ${preview.activities.size} workout(s)"
                )
                log.i { "CSV import committed: ${preview.activities.size} activities" }
            } catch (e: Exception) {
                log.e(e) { "CSV import commit failed" }
                _uiState.value = _uiState.value.copy(errorMessage = "Import failed: ${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isImporting = false)
            }
        }
    }

    /** Discard the current import preview without writing to the database. */
    fun cancelImport() {
        _uiState.value = _uiState.value.copy(importPreview = null)
    }

    // ─── Provider Lifecycle ───────────────────────────────────────────────────

    /**
     * Connect [provider] using [apiKey]. Updates integration status reactively
     * via the repository flow once the operation completes.
     */
    fun connectProvider(
        provider: IntegrationProvider,
        apiKey: String
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true, errorMessage = null)
            try {
                integrationManager.connectProvider(provider, apiKey, activeProfileId.value, isPaidUser.value)
                    .onSuccess { activities ->
                        _uiState.value = _uiState.value.copy(
                            successMessage = "Connected to ${provider.displayName}: ${activities.size} activities imported"
                        )
                        log.i { "Connected ${provider.key}: ${activities.size} activities" }
                    }
                    .onFailure { error ->
                        _uiState.value = _uiState.value.copy(
                            errorMessage = "Connect failed: ${error.message}"
                        )
                        log.w { "Connect to ${provider.key} failed: ${error.message}" }
                    }
            } catch (e: Exception) {
                log.e(e) { "Unexpected error connecting ${provider.key}" }
                _uiState.value = _uiState.value.copy(errorMessage = "Connect failed: ${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isSyncing = false)
            }
        }
    }

    /**
     * Sync [provider] (uses stored portal token — no API key needed).
     */
    fun syncProvider(provider: IntegrationProvider) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true, errorMessage = null)
            try {
                integrationManager.syncProvider(provider, activeProfileId.value, isPaidUser.value)
                    .onSuccess { activities ->
                        _uiState.value = _uiState.value.copy(
                            successMessage = "Synced ${provider.displayName}: ${activities.size} new activities"
                        )
                        log.i { "Synced ${provider.key}: ${activities.size} activities" }
                    }
                    .onFailure { error ->
                        _uiState.value = _uiState.value.copy(
                            errorMessage = "Sync failed: ${error.message}"
                        )
                        log.w { "Sync ${provider.key} failed: ${error.message}" }
                    }
            } catch (e: Exception) {
                log.e(e) { "Unexpected error syncing ${provider.key}" }
                _uiState.value = _uiState.value.copy(errorMessage = "Sync failed: ${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isSyncing = false)
            }
        }
    }

    /**
     * Disconnect [provider] and remove all associated local activities.
     */
    fun disconnectProvider(provider: IntegrationProvider) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true, errorMessage = null)
            try {
                integrationManager.disconnectProvider(provider, activeProfileId.value)
                    .onSuccess {
                        _uiState.value = _uiState.value.copy(
                            successMessage = "Disconnected from ${provider.displayName}"
                        )
                        log.i { "Disconnected ${provider.key}" }
                    }
                    .onFailure { error ->
                        _uiState.value = _uiState.value.copy(
                            errorMessage = "Disconnect failed: ${error.message}"
                        )
                        log.w { "Disconnect ${provider.key} failed: ${error.message}" }
                    }
            } catch (e: Exception) {
                log.e(e) { "Unexpected error disconnecting ${provider.key}" }
                _uiState.value = _uiState.value.copy(errorMessage = "Disconnect failed: ${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isSyncing = false)
            }
        }
    }

    // ─── Health Integration ───────────────────────────────────────────────────

    /**
     * Toggle the platform health integration (Health Connect / HealthKit).
     *
     * When [enable] is true:
     * 1. Checks if the platform health API is available.
     * 2. Requests (or verifies) permissions.
     * 3. Updates the appropriate provider status in the repository.
     *
     * When [enable] is false:
     * - Marks the provider as DISCONNECTED locally (no platform revocation needed).
     */
    fun toggleHealthIntegration(enable: Boolean) {
        viewModelScope.launch {
            val profileId = activeProfileId.value
            val provider = platformHealthProvider()
            _uiState.value = _uiState.value.copy(errorMessage = null)
            try {
                if (!enable) {
                    externalActivityRepo.updateIntegrationStatus(
                        provider = provider,
                        status = com.devil.phoenixproject.domain.model.ConnectionStatus.DISCONNECTED,
                        profileId = profileId
                    )
                    _uiState.value = _uiState.value.copy(
                        successMessage = "Health integration disabled"
                    )
                    log.i { "Health integration disabled for ${provider.key}" }
                    return@launch
                }

                val available = healthIntegration.isAvailable()
                if (!available) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Health app is not available on this device"
                    )
                    return@launch
                }

                if (healthIntegration.hasPermissions()) {
                    markHealthIntegrationConnected(provider, profileId)
                    return@launch
                }

                if (!isIosPlatform) {
                    _healthPermissionRequests.emit(Unit)
                    log.d { "Requested Android Health Connect permission launcher for ${provider.key}" }
                } else {
                    val granted = healthIntegration.requestPermissions()
                    applyHealthPermissionResult(provider, profileId, granted)
                }
            } catch (e: Exception) {
                log.e(e) { "Unexpected error toggling health integration" }
                _uiState.value = _uiState.value.copy(errorMessage = "Health toggle failed: ${e.message}")
            }
        }
    }

    fun onHealthPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            val profileId = activeProfileId.value
            val provider = platformHealthProvider()
            applyHealthPermissionResult(provider, profileId, granted)
        }
    }

    // ─── Utility ─────────────────────────────────────────────────────────────

    /** Clear both error and success messages. */
    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null)
    }

    private suspend fun applyHealthPermissionResult(
        provider: IntegrationProvider,
        profileId: String,
        granted: Boolean
    ) {
        if (granted && healthIntegration.hasPermissions()) {
            markHealthIntegrationConnected(provider, profileId)
        } else {
            externalActivityRepo.updateIntegrationStatus(
                provider = provider,
                status = com.devil.phoenixproject.domain.model.ConnectionStatus.ERROR,
                profileId = profileId,
                errorMessage = "Permission not granted"
            )
            _uiState.value = _uiState.value.copy(
                errorMessage = "Health permissions were not granted"
            )
            log.w { "Health permissions not granted for ${provider.key}" }
        }
    }

    private suspend fun markHealthIntegrationConnected(
        provider: IntegrationProvider,
        profileId: String
    ) {
        externalActivityRepo.updateIntegrationStatus(
            provider = provider,
            status = com.devil.phoenixproject.domain.model.ConnectionStatus.CONNECTED,
            profileId = profileId
        )
        _uiState.value = _uiState.value.copy(
            successMessage = "Health integration enabled"
        )
        log.i { "Health integration enabled for ${provider.key}" }
    }

    /**
     * Returns the appropriate [IntegrationProvider] for the current platform's health API.
     * iOS → APPLE_HEALTH; Android → GOOGLE_HEALTH.
     *
     * Uses the project-wide [isIosPlatform] expect/actual boolean defined in Platform.kt.
     */
    private fun platformHealthProvider(): IntegrationProvider =
        if (isIosPlatform) IntegrationProvider.APPLE_HEALTH else IntegrationProvider.GOOGLE_HEALTH
}
