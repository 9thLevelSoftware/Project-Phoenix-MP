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
    ): UserProfile = mutex.withLock {
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

    override suspend fun updateProfile(id: String, name: String, colorIndex: Int) {
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

    override suspend fun deleteProfile(id: String): Boolean = mutex.withLock {
        if (id == DEFAULT_PROFILE_ID) return@withLock false
        val previous = _activeProfileContext.value as? ActiveProfileContext.Ready
            ?: throw ProfileContextUnavailableException()
        val removed = profiles[id] ?: return@withLock false
        val wasActive = removed.isActive
        if (wasActive) {
            _activeProfileContext.value = ActiveProfileContext.Switching(DEFAULT_PROFILE_ID)
        }
        profiles.remove(id)
        preferenceFlows.remove(id)
        if (wasActive) {
            if (!profiles.containsKey(DEFAULT_PROFILE_ID)) {
                profiles[DEFAULT_PROFILE_ID] = UserProfile(
                    id = DEFAULT_PROFILE_ID,
                    name = "Default",
                    colorIndex = 0,
                    createdAt = currentTimeMillis(),
                    isActive = false,
                )
                ensurePreferenceFlow(DEFAULT_PROFILE_ID)
            }
            setActiveIdentityLocked(DEFAULT_PROFILE_ID)
        } else {
            updateIdentityFlows()
        }
        publishReady(if (wasActive) DEFAULT_PROFILE_ID else previous.profile.id)
        true
    }

    override suspend fun setActiveProfile(id: String) {
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

    override suspend fun updateRack(profileId: String, value: RackPreferences) {
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
        mutex.withLock {
            requireActiveProfileId(profileId)
            localSafety[profileId] = value
            publishReady(profileId)
        }
    }

    override suspend fun retryPendingLocalCleanup(profileId: String?) {
        mutex.withLock {
            pendingLocalCleanupProfileIds
                .filter { profileId == null || it == profileId }
                .forEach { pendingId ->
                    localSafety.remove(pendingId)
                    pendingLocalCleanupProfileIds.remove(pendingId)
                }
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
    ): UserProfile {
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty()) { "Profile name must not be blank" }
        val profile = UserProfile(
            id = id,
            name = trimmedName,
            colorIndex = colorIndex,
            createdAt = currentTimeMillis(),
            isActive = false,
        )
        profiles[profile.id] = profile
        ensurePreferenceFlow(profile.id)
        updateIdentityFlows()
        return profile
    }

    private fun setActiveIdentityLocked(profileId: String) {
        val updatedProfiles = profiles.mapValues { (id, profile) ->
            profile.copy(isActive = id == profileId)
        }
        profiles.clear()
        profiles.putAll(updatedProfiles)
        updateIdentityFlows()
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
