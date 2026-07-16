package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.preferences.ProfilePreferencesCodec
import com.devil.phoenixproject.data.preferences.ProfilePreferencesValidator
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.ProfileContextUnavailableException
import com.devil.phoenixproject.data.repository.StaleProfileContextException
import com.devil.phoenixproject.data.repository.SubscriptionStatus
import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.CoreProfilePreferences
import com.devil.phoenixproject.domain.model.LedPreferences
import com.devil.phoenixproject.domain.model.ProfileLocalSafetyPreferences
import com.devil.phoenixproject.domain.model.ProfilePreferenceSection
import com.devil.phoenixproject.domain.model.ProfilePreferenceValidity
import com.devil.phoenixproject.domain.model.ProfileSectionMetadata
import com.devil.phoenixproject.domain.model.RackPreferences
import com.devil.phoenixproject.domain.model.UserProfilePreferences
import com.devil.phoenixproject.domain.model.VbtPreferences
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FakeUserProfileRepository : UserProfileRepository {
    private val mutex = Mutex()
    private val profiles = linkedMapOf<String, UserProfile>()
    private val preferenceFlows = mutableMapOf<String, MutableStateFlow<UserProfilePreferences>>()
    private val localSafety = mutableMapOf<String, ProfileLocalSafetyPreferences>()
    private var pendingTransition: PendingTransition? = null

    val pendingLocalCleanupProfileIds = linkedSetOf<String>()
    var failBeforeProfileDeletionCommit: Boolean = false
    var failLocalCleanupDeletes: Boolean = false

    data class UpdateProfileRequest(
        val profileId: String,
        val name: String,
        val colorIndex: Int,
    )

    data class CreateAndActivateRequest(val name: String, val colorIndex: Int)

    sealed interface PreferenceUpdateRequest {
        val profileId: String

        data class Core(
            override val profileId: String,
            val value: CoreProfilePreferences,
        ) : PreferenceUpdateRequest

        data class Rack(
            override val profileId: String,
            val value: RackPreferences,
        ) : PreferenceUpdateRequest

        data class Workout(
            override val profileId: String,
            val value: WorkoutPreferences,
        ) : PreferenceUpdateRequest

        data class Led(
            override val profileId: String,
            val value: LedPreferences,
        ) : PreferenceUpdateRequest

        data class Vbt(
            override val profileId: String,
            val value: VbtPreferences,
        ) : PreferenceUpdateRequest

        data class LocalSafety(
            override val profileId: String,
            val value: ProfileLocalSafetyPreferences,
        ) : PreferenceUpdateRequest
    }

    val updateProfileRequests = mutableListOf<UpdateProfileRequest>()
    val deleteActiveProfileRequests = mutableListOf<String>()
    val preferenceUpdateRequests = mutableListOf<PreferenceUpdateRequest>()
    var updateProfileFailure: Throwable? = null
    var deleteProfileFailure: Throwable? = null
    var deleteActiveProfileResultOverride: Boolean? = null
    var beforeUpdateProfileMutation: (suspend (UpdateProfileRequest) -> Unit)? = null
    var beforeDeleteActiveProfileMutation: (suspend (String) -> Unit)? = null
    var beforePreferenceUpdate: (suspend (PreferenceUpdateRequest) -> Unit)? = null
    var updateCoreFailure: Throwable? = null
    var updateRackFailure: Throwable? = null
    var updateWorkoutFailure: Throwable? = null
    var updateLedFailure: Throwable? = null
    var updateVbtFailure: Throwable? = null
    var updateLocalSafetyFailure: Throwable? = null

    val setActiveProfileRequests = mutableListOf<String>()
    val createAndActivateRequests = mutableListOf<CreateAndActivateRequest>()
    var reconcileActiveProfileContextRequests: Int = 0

    var setActiveProfileFailure: Throwable? = null
    var createAndActivateProfileFailure: Throwable? = null
    var reconcileActiveProfileContextFailure: Throwable? = null

    var beforeSetActiveProfile: (suspend (String) -> Unit)? = null
    var beforeCreateAndActivateProfile: (suspend (String, Int) -> Unit)? = null
    var beforeReconcileActiveProfileContext: (suspend () -> Unit)? = null

    fun resetRootProfileOperationControls() {
        setActiveProfileRequests.clear()
        createAndActivateRequests.clear()
        reconcileActiveProfileContextRequests = 0
        setActiveProfileFailure = null
        createAndActivateProfileFailure = null
        reconcileActiveProfileContextFailure = null
        beforeSetActiveProfile = null
        beforeCreateAndActivateProfile = null
        beforeReconcileActiveProfileContext = null
    }

    private val _activeProfile = MutableStateFlow<UserProfile?>(null)
    override val activeProfile: StateFlow<UserProfile?> = _activeProfile.asStateFlow()

    private val _allProfiles = MutableStateFlow<List<UserProfile>>(emptyList())
    override val allProfiles: StateFlow<List<UserProfile>> = _allProfiles.asStateFlow()

    private val _activeProfileContext = MutableStateFlow<ActiveProfileContext>(
        ActiveProfileContext.Switching(null),
    )
    override val activeProfileContext: StateFlow<ActiveProfileContext> =
        _activeProfileContext.asStateFlow()

    override fun observePreferences(profileId: String): Flow<UserProfilePreferences> =
        preferenceFlows[profileId]?.asStateFlow() ?: flow {
            error("Unknown profile preferences: $profileId")
        }

    fun seedReadyProfileForTest(
        profileId: String,
        name: String = profileId,
        colorIndex: Int = 0,
    ): UserProfile {
        seedProfileWithDefaultPreferences(profileId, name, colorIndex)
        emitReadyForTest(profileId)
        return profiles.getValue(profileId)
    }

    fun emitSwitchingForTest(targetProfileId: String?) {
        _activeProfileContext.value = ActiveProfileContext.Switching(targetProfileId)
    }

    fun emitReadyForTest(profileId: String) {
        require(profiles.containsKey(profileId)) { "Unknown profile: $profileId" }
        setActiveIdentityLocked(profileId)
        publishReady(profileId)
    }

    fun setActiveProfileForTest(
        id: String = DEFAULT_PROFILE_ID,
        subscriptionStatus: SubscriptionStatus = SubscriptionStatus.FREE,
        supabaseUserId: String? = null,
    ) {
        val updatedProfiles = profiles.mapValues { (profileId, profile) ->
            profile.copy(isActive = profileId == id)
        }
        profiles.clear()
        profiles.putAll(updatedProfiles)
        profiles[id] = UserProfile(
            id = id,
            name = "Default",
            colorIndex = 0,
            createdAt = currentTimeMillis(),
            isActive = true,
            supabaseUserId = supabaseUserId,
            subscriptionStatus = subscriptionStatus,
        )
        ensurePreferenceFlow(id)
        updateIdentityFlows()
        publishReady(id)
    }

    override suspend fun createProfile(name: String, colorIndex: Int): UserProfile =
        mutex.withLock {
            createProfileLocked(name, colorIndex)
        }

    override suspend fun createAndActivateProfile(
        name: String,
        colorIndex: Int,
    ): UserProfile {
        val request = CreateAndActivateRequest(name, colorIndex)
        createAndActivateRequests += request
        beforeCreateAndActivateProfile?.invoke(name, colorIndex)
        createAndActivateProfileFailure?.let { throw it }
        return mutex.withLock {
            val previous = _activeProfileContext.value as? ActiveProfileContext.Ready
                ?: throw ProfileContextUnavailableException()
            val trimmedName = name.trim()
            require(trimmedName.isNotEmpty()) { "Profile name must not be blank" }
            val profileId = generateUUID()
            _activeProfileContext.value = ActiveProfileContext.Switching(profileId)
            pendingTransition = PendingTransition(previous.profile.id, profileId)
            val profile = createProfileLocked(trimmedName, colorIndex, profileId)
            setActiveIdentityLocked(profile.id)
            publishReady(profile.id)
            pendingTransition = null
            requireNotNull(activeProfile.value)
        }
    }

    override suspend fun updateProfile(id: String, name: String, colorIndex: Int) {
        val request = UpdateProfileRequest(id, name, colorIndex)
        updateProfileRequests += request
        updateProfileFailure?.let { throw it }
        beforeUpdateProfileMutation?.invoke(request)
        mutex.withLock {
            val trimmedName = name.trim()
            require(trimmedName.isNotEmpty()) { "Profile name must not be blank" }
            profiles[id]?.let { existing ->
                profiles[id] = existing.copy(name = trimmedName, colorIndex = colorIndex)
                updateIdentityFlows()
                if ((activeProfileContext.value as? ActiveProfileContext.Ready)?.profile?.id == id) {
                    publishReady(id)
                }
            }
        }
    }

    override suspend fun deleteActiveProfile(expectedProfileId: String): Boolean {
        deleteActiveProfileRequests += expectedProfileId
        deleteProfileFailure?.let { throw it }
        beforeDeleteActiveProfileMutation?.invoke(expectedProfileId)
        deleteActiveProfileResultOverride?.let { return it }
        return mutex.withLock {
            val ready = _activeProfileContext.value as? ActiveProfileContext.Ready
                ?: throw ProfileContextUnavailableException()
            if (ready.profile.id != expectedProfileId) {
                throw StaleProfileContextException(expectedProfileId, ready.profile.id)
            }
            deleteProfileLocked(expectedProfileId, requireActive = true)
        }
    }

    override suspend fun deleteProfile(id: String): Boolean = mutex.withLock {
        deleteProfileLocked(id, requireActive = false)
    }

    private fun deleteProfileLocked(id: String, requireActive: Boolean): Boolean {
        if (id == DEFAULT_PROFILE_ID) return false
        val previous = _activeProfileContext.value as? ActiveProfileContext.Ready
            ?: throw ProfileContextUnavailableException()
        if (requireActive && previous.profile.id != id) {
            throw StaleProfileContextException(id, previous.profile.id)
        }
        profiles[id] ?: return false
        val wasActive = previous.profile.id == id
        val targetProfileId = if (requireActive || wasActive) DEFAULT_PROFILE_ID else previous.profile.id
        require(profiles.containsKey(targetProfileId)) { "Profile deletion target missing: $targetProfileId" }
        if (wasActive) {
            _activeProfileContext.value = ActiveProfileContext.Switching(targetProfileId)
        }

        val profileSnapshot = profiles.toMap()
        val preferenceSnapshot = preferenceFlows.toMap()
        val pendingCleanupSnapshot = pendingLocalCleanupProfileIds.toSet()
        try {
            pendingLocalCleanupProfileIds += id
            profiles.remove(id)
            preferenceFlows.remove(id)
            if (wasActive) {
                setActiveIdentityMapLocked(targetProfileId)
            }
            if (failBeforeProfileDeletionCommit) {
                failBeforeProfileDeletionCommit = false
                error("injected profile deletion failure")
            }
        } catch (failure: Throwable) {
            profiles.clear()
            profiles.putAll(profileSnapshot)
            preferenceFlows.clear()
            preferenceFlows.putAll(preferenceSnapshot)
            pendingLocalCleanupProfileIds.clear()
            pendingLocalCleanupProfileIds.addAll(pendingCleanupSnapshot)
            _activeProfileContext.value = previous
            throw failure
        }

        updateIdentityFlows()
        publishReady(targetProfileId)
        retryPendingLocalCleanupLocked(id)
        return true
    }

    override suspend fun setActiveProfile(id: String) {
        setActiveProfileRequests += id
        beforeSetActiveProfile?.invoke(id)
        setActiveProfileFailure?.let { throw it }
        mutex.withLock {
            require(profiles.containsKey(id)) { "Unknown profile: $id" }
            val previous = _activeProfileContext.value as? ActiveProfileContext.Ready
                ?: throw ProfileContextUnavailableException()
            _activeProfileContext.value = ActiveProfileContext.Switching(id)
            pendingTransition = PendingTransition(previous.profile.id, null)
            setActiveIdentityLocked(id)
            publishReady(id)
            pendingTransition = null
        }
    }

    override suspend fun refreshProfiles() {
        mutex.withLock {
            updateIdentityFlows()
            val readyId = (activeProfileContext.value as? ActiveProfileContext.Ready)?.profile?.id
            if (readyId != null && activeProfile.value?.id == readyId) publishReady(readyId)
        }
    }

    override suspend fun ensureDefaultProfile() {
        mutex.withLock {
            if (!profiles.containsKey(DEFAULT_PROFILE_ID)) {
                profiles[DEFAULT_PROFILE_ID] = UserProfile(
                    id = DEFAULT_PROFILE_ID,
                    name = "Default",
                    colorIndex = 0,
                    createdAt = currentTimeMillis(),
                    isActive = profiles.values.none { it.isActive },
                )
                ensurePreferenceFlow(DEFAULT_PROFILE_ID, legacyMigrationVersion = 0)
            }
            updateIdentityFlows()
        }
    }

    override suspend fun updateCore(profileId: String, value: CoreProfilePreferences) {
        val request = PreferenceUpdateRequest.Core(profileId, value)
        preferenceUpdateRequests += request
        beforePreferenceUpdate?.invoke(request)
        updateCoreFailure?.let { throw it }
        require(ProfilePreferencesValidator.core(value).isEmpty())
        mutateActiveProfile(profileId) { current, now ->
            current.copy(
                core = current.core.copy(
                    value = value,
                    validity = ProfilePreferenceValidity.Valid,
                    metadata = current.core.metadata.advanced(now),
                ),
            )
        }
    }

    override suspend fun mutateCore(
        profileId: String,
        transform: (CoreProfilePreferences) -> CoreProfilePreferences,
    ) {
        mutateActiveProfile(profileId) { current, now ->
            val value = transform(current.core.value)
            require(ProfilePreferencesValidator.core(value).isEmpty())
            current.copy(
                core = current.core.copy(
                    value = value,
                    validity = ProfilePreferenceValidity.Valid,
                    metadata = current.core.metadata.advanced(now),
                ),
            )
        }
    }

    override suspend fun updateRack(profileId: String, value: RackPreferences) {
        val request = PreferenceUpdateRequest.Rack(profileId, value)
        preferenceUpdateRequests += request
        beforePreferenceUpdate?.invoke(request)
        updateRackFailure?.let { throw it }
        require(ProfilePreferencesValidator.rack(value).isEmpty())
        mutateActiveProfile(profileId) { current, now ->
            current.copy(
                rack = current.rack.copy(
                    value = value,
                    raw = ProfilePreferencesCodec.encodeRack(value),
                    validity = ProfilePreferenceValidity.Valid,
                    metadata = current.rack.metadata.advanced(now),
                ),
            )
        }
    }

    override suspend fun updateWorkout(profileId: String, value: WorkoutPreferences) {
        val request = PreferenceUpdateRequest.Workout(profileId, value)
        preferenceUpdateRequests += request
        beforePreferenceUpdate?.invoke(request)
        updateWorkoutFailure?.let { throw it }
        require(ProfilePreferencesValidator.workout(value).isEmpty())
        mutateActiveProfile(profileId) { current, now ->
            current.copy(
                workout = current.workout.copy(
                    value = value,
                    raw = ProfilePreferencesCodec.encodeWorkout(value),
                    validity = ProfilePreferenceValidity.Valid,
                    metadata = current.workout.metadata.advanced(now),
                ),
            )
        }
    }

    override suspend fun updateLed(profileId: String, value: LedPreferences) {
        val request = PreferenceUpdateRequest.Led(profileId, value)
        preferenceUpdateRequests += request
        beforePreferenceUpdate?.invoke(request)
        updateLedFailure?.let { throw it }
        require(ProfilePreferencesValidator.led(value).isEmpty())
        mutateActiveProfile(profileId) { current, now ->
            current.copy(
                led = current.led.copy(
                    value = value,
                    raw = ProfilePreferencesCodec.encodeLed(value),
                    validity = ProfilePreferenceValidity.Valid,
                    metadata = current.led.metadata.advanced(now),
                ),
            )
        }
    }

    override suspend fun updateVbt(profileId: String, value: VbtPreferences) {
        val request = PreferenceUpdateRequest.Vbt(profileId, value)
        preferenceUpdateRequests += request
        beforePreferenceUpdate?.invoke(request)
        updateVbtFailure?.let { throw it }
        require(ProfilePreferencesValidator.vbt(value).isEmpty())
        mutateActiveProfile(profileId) { current, now ->
            current.copy(
                vbt = current.vbt.copy(
                    value = value,
                    raw = ProfilePreferencesCodec.encodeVbt(value),
                    validity = ProfilePreferenceValidity.Valid,
                    metadata = current.vbt.metadata.advanced(now),
                ),
            )
        }
    }

    override suspend fun updateLocalSafety(
        profileId: String,
        value: ProfileLocalSafetyPreferences,
    ) {
        val request = PreferenceUpdateRequest.LocalSafety(profileId, value)
        preferenceUpdateRequests += request
        beforePreferenceUpdate?.invoke(request)
        updateLocalSafetyFailure?.let { throw it }
        mutex.withLock {
            requireActiveProfileId(profileId)
            localSafety[profileId] = value
            publishReady(profileId)
        }
    }

    override suspend fun retryPendingLocalCleanup(profileId: String?) {
        mutex.withLock {
            retryPendingLocalCleanupLocked(profileId)
        }
    }

    private fun retryPendingLocalCleanupLocked(profileId: String?) {
        if (failLocalCleanupDeletes) return
        pendingLocalCleanupProfileIds
            .filter { profileId == null || it == profileId }
            .forEach { pendingId ->
                localSafety.remove(pendingId)
                pendingLocalCleanupProfileIds.remove(pendingId)
            }
    }

    override suspend fun recoverPendingProfileTransitionForStartup() {
        mutex.withLock {
            _activeProfileContext.value = ActiveProfileContext.Switching(null)
            recoverPendingTransitionLocked()
            _activeProfileContext.value = ActiveProfileContext.Switching(activeProfile.value?.id)
        }
    }

    override suspend fun reconcileActiveProfileContext() {
        reconcileActiveProfileContextRequests += 1
        beforeReconcileActiveProfileContext?.invoke()
        reconcileActiveProfileContextFailure?.let { throw it }
        mutex.withLock {
            _activeProfileContext.value = ActiveProfileContext.Switching(
                pendingTransition?.priorProfileId,
            )
            recoverPendingTransitionLocked()
            activeProfile.value?.id?.let(::publishReady)
        }
    }

    override suspend fun linkToSupabase(profileId: String, supabaseUserId: String) {
        mutex.withLock {
            profiles[profileId]?.let { profile ->
                profiles[profileId] = profile.copy(
                    supabaseUserId = supabaseUserId,
                    lastAuthAt = currentTimeMillis(),
                )
                updateIdentityFlows()
                if ((activeProfileContext.value as? ActiveProfileContext.Ready)?.profile?.id == profileId) {
                    publishReady(profileId)
                }
            }
        }
    }

    override suspend fun updateSubscriptionStatus(
        profileId: String,
        status: SubscriptionStatus,
        expiresAt: Long?,
    ) {
        mutex.withLock {
            profiles[profileId]?.let { profile ->
                profiles[profileId] = profile.copy(
                    subscriptionStatus = status,
                    subscriptionExpiresAt = expiresAt,
                )
                updateIdentityFlows()
                if ((activeProfileContext.value as? ActiveProfileContext.Ready)?.profile?.id == profileId) {
                    publishReady(profileId)
                }
            }
        }
    }

    override suspend fun getProfileBySupabaseId(supabaseUserId: String): UserProfile? =
        profiles.values.firstOrNull { it.supabaseUserId == supabaseUserId }

    override fun getActiveProfileSubscriptionStatus(): Flow<SubscriptionStatus> =
        flowOf(activeProfile.value?.subscriptionStatus ?: SubscriptionStatus.FREE)

    private fun createProfileLocked(
        name: String,
        colorIndex: Int,
        id: String = generateUUID(),
    ): UserProfile = seedProfileWithDefaultPreferences(
        profileId = id,
        name = name,
        colorIndex = colorIndex,
    )

    private fun seedProfileWithDefaultPreferences(
        profileId: String,
        name: String,
        colorIndex: Int,
    ): UserProfile {
        require(profileId.isNotBlank()) { "Profile ID must not be blank" }
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty()) { "Profile name must not be blank" }
        require(!profiles.containsKey(profileId)) { "Profile already exists: $profileId" }
        val profile = UserProfile(
            id = profileId,
            name = trimmedName,
            colorIndex = colorIndex,
            createdAt = currentTimeMillis(),
            isActive = false,
        )
        profiles[profile.id] = profile
        ensurePreferenceFlow(profile.id)
        localSafety.getOrPut(profile.id) { ProfileLocalSafetyPreferences() }
        updateIdentityFlows()
        return profile
    }

    private fun setActiveIdentityLocked(profileId: String) {
        setActiveIdentityMapLocked(profileId)
        updateIdentityFlows()
    }

    private fun setActiveIdentityMapLocked(profileId: String) {
        val updatedProfiles = profiles.mapValues { (id, profile) ->
            profile.copy(isActive = id == profileId)
        }
        profiles.clear()
        profiles.putAll(updatedProfiles)
    }

    private suspend fun mutateActiveProfile(
        profileId: String,
        update: (UserProfilePreferences, Long) -> UserProfilePreferences,
    ) {
        mutex.withLock {
            requireActiveProfileId(profileId)
            val flow = requirePreferenceFlow(profileId)
            flow.value = update(flow.value, currentTimeMillis())
            publishReady(profileId)
        }
    }

    private fun requireActiveProfileId(expectedProfileId: String) {
        val context = activeProfileContext.value as? ActiveProfileContext.Ready
            ?: throw ProfileContextUnavailableException()
        if (context.profile.id != expectedProfileId) {
            throw StaleProfileContextException(expectedProfileId, context.profile.id)
        }
    }

    private fun recoverPendingTransitionLocked() {
        pendingTransition?.let { pending ->
            pending.createdProfileId?.let { createdId ->
                profiles.remove(createdId)
                preferenceFlows.remove(createdId)
                localSafety.remove(createdId)
            }
            if (profiles.containsKey(pending.priorProfileId)) {
                setActiveIdentityLocked(pending.priorProfileId)
            } else if (profiles.containsKey(DEFAULT_PROFILE_ID)) {
                setActiveIdentityLocked(DEFAULT_PROFILE_ID)
            }
            pendingTransition = null
        }
        updateIdentityFlows()
    }

    private fun updateIdentityFlows() {
        _allProfiles.value = profiles.values.toList()
        _activeProfile.value = profiles.values.firstOrNull { it.isActive }
    }

    private fun publishReady(profileId: String) {
        val profile = profiles[profileId] ?: error("Unknown profile: $profileId")
        _activeProfileContext.value = ActiveProfileContext.Ready(
            profile = profile,
            preferences = requirePreferenceFlow(profileId).value,
            localSafety = localSafety[profileId] ?: ProfileLocalSafetyPreferences(),
        )
    }

    private fun ensurePreferenceFlow(
        profileId: String,
        legacyMigrationVersion: Int = 1,
    ): MutableStateFlow<UserProfilePreferences> =
        preferenceFlows.getOrPut(profileId) {
            MutableStateFlow(defaultPreferences(profileId, legacyMigrationVersion))
        }

    private fun requirePreferenceFlow(
        profileId: String,
    ): MutableStateFlow<UserProfilePreferences> = preferenceFlows[profileId]
        ?: error("Unknown profile preferences: $profileId")

    private fun defaultPreferences(
        profileId: String,
        legacyMigrationVersion: Int,
    ): UserProfilePreferences {
        val metadata = ProfileSectionMetadata(0L, 0L, 0L, true)
        return UserProfilePreferences(
            profileId = profileId,
            schemaVersion = 1,
            legacyMigrationVersion = legacyMigrationVersion,
            core = ProfilePreferenceSection(
                CoreProfilePreferences(),
                validity = ProfilePreferenceValidity.Valid,
                metadata = metadata,
            ),
            rack = ProfilePreferenceSection(
                RackPreferences(),
                ProfilePreferencesCodec.encodeRack(RackPreferences()),
                ProfilePreferenceValidity.Valid,
                metadata,
            ),
            workout = ProfilePreferenceSection(
                WorkoutPreferences(),
                ProfilePreferencesCodec.encodeWorkout(WorkoutPreferences()),
                ProfilePreferenceValidity.Valid,
                metadata,
            ),
            led = ProfilePreferenceSection(
                LedPreferences(),
                ProfilePreferencesCodec.encodeLed(LedPreferences()),
                ProfilePreferenceValidity.Valid,
                metadata,
            ),
            vbt = ProfilePreferenceSection(
                VbtPreferences(),
                ProfilePreferencesCodec.encodeVbt(VbtPreferences()),
                ProfilePreferenceValidity.Valid,
                metadata,
            ),
        )
    }

    private fun ProfileSectionMetadata.advanced(now: Long) = copy(
        updatedAt = now,
        localGeneration = localGeneration + 1,
        dirty = true,
    )

    private data class PendingTransition(
        val priorProfileId: String,
        val createdProfileId: String?,
    )

    private companion object {
        const val DEFAULT_PROFILE_ID = "default"
    }
}
