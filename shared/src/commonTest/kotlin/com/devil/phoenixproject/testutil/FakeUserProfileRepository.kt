package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.SubscriptionStatus
import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

class FakeUserProfileRepository : UserProfileRepository {
    private val profiles = mutableMapOf<String, UserProfile>()
    private val _activeProfile = MutableStateFlow<UserProfile?>(null)
    override val activeProfile: StateFlow<UserProfile?> = _activeProfile.asStateFlow()

    private val _allProfiles = MutableStateFlow<List<UserProfile>>(emptyList())
    override val allProfiles: StateFlow<List<UserProfile>> = _allProfiles.asStateFlow()

    private fun updateFlows() {
        _allProfiles.value = profiles.values.toList()
        _activeProfile.value = profiles.values.firstOrNull { it.isActive }
    }

    fun setActiveProfileForTest(
        id: String = "default",
        subscriptionStatus: SubscriptionStatus = SubscriptionStatus.FREE,
        supabaseUserId: String? = null,
    ) {
        profiles[id] = UserProfile(
            id = id,
            name = "Default",
            colorIndex = 0,
            createdAt = currentTimeMillis(),
            isActive = true,
            supabaseUserId = supabaseUserId,
            subscriptionStatus = subscriptionStatus,
        )
        updateFlows()
    }

    override suspend fun createProfile(name: String, colorIndex: Int): UserProfile {
        val profile = UserProfile(
            id = generateUUID(),
            name = name,
            colorIndex = colorIndex,
            createdAt = currentTimeMillis(),
            isActive = false,
        )
        profiles[profile.id] = profile
        updateFlows()
        return profile
    }

    override suspend fun updateProfile(id: String, name: String, colorIndex: Int) {
        profiles[id]?.let { existing ->
            profiles[id] = existing.copy(name = name, colorIndex = colorIndex)
            updateFlows()
        }
    }

    override suspend fun deleteProfile(id: String): Boolean {
        if (id == "default") return false
        val wasRemoved = profiles.remove(id) != null
        if (_activeProfile.value?.id == id) {
            profiles["default"] = profiles["default"]?.copy(isActive = true) ?: UserProfile(
                id = "default",
                name = "Default",
                colorIndex = 0,
                createdAt = currentTimeMillis(),
                isActive = true,
            )
        }
        updateFlows()
        return wasRemoved
    }

    override suspend fun setActiveProfile(id: String) {
        val updatedProfiles = profiles.mapValues { (key, profile) ->
            profile.copy(isActive = key == id)
        }
        profiles.clear()
        profiles.putAll(updatedProfiles)
        updateFlows()
    }

    override suspend fun refreshProfiles() {
        updateFlows()
    }

    override suspend fun ensureDefaultProfile() {
        if (!profiles.containsKey("default")) {
            profiles["default"] = UserProfile(
                id = "default",
                name = "Default",
                colorIndex = 0,
                createdAt = currentTimeMillis(),
                isActive = true,
            )
        }
        updateFlows()
    }

    override suspend fun linkToSupabase(profileId: String, supabaseUserId: String) {
        profiles[profileId]?.let { profile ->
            profiles[profileId] = profile.copy(
                supabaseUserId = supabaseUserId,
                lastAuthAt = currentTimeMillis(),
            )
            updateFlows()
        }
    }

    override suspend fun updateSubscriptionStatus(profileId: String, status: SubscriptionStatus, expiresAt: Long?) {
        profiles[profileId]?.let { profile ->
            profiles[profileId] = profile.copy(
                subscriptionStatus = status,
                subscriptionExpiresAt = expiresAt,
            )
            updateFlows()
        }
    }

    override suspend fun getProfileBySupabaseId(supabaseUserId: String): UserProfile? = profiles.values.firstOrNull {
        it.supabaseUserId == supabaseUserId
    }

    override fun getActiveProfileSubscriptionStatus(): Flow<SubscriptionStatus> = flowOf(activeProfile.value?.subscriptionStatus ?: SubscriptionStatus.FREE)
}
