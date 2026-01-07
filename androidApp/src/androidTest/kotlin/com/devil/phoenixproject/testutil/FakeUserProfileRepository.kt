package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fake UserProfileRepository for testing.
 * Keeps profiles in memory with a default active profile.
 */
@Suppress("unused") // Test utility methods are available for future tests
class FakeUserProfileRepository : UserProfileRepository {
    private val profiles = mutableMapOf<String, UserProfile>()
    private val _activeProfile = MutableStateFlow<UserProfile?>(null)
    override val activeProfile: StateFlow<UserProfile?> = _activeProfile.asStateFlow()

    private val _allProfiles = MutableStateFlow<List<UserProfile>>(emptyList())
    override val allProfiles: StateFlow<List<UserProfile>> = _allProfiles.asStateFlow()

    fun reset() {
        profiles.clear()
        _activeProfile.value = null
        _allProfiles.value = emptyList()
    }

    private fun updateFlows() {
        _allProfiles.value = profiles.values.toList()
        _activeProfile.value = profiles.values.firstOrNull { it.isActive }
    }

    override suspend fun createProfile(name: String, colorIndex: Int): UserProfile {
        val id = generateUUID()
        val createdAt = currentTimeMillis()
        val profile = UserProfile(
            id = id,
            name = name,
            colorIndex = colorIndex,
            createdAt = createdAt,
            isActive = false
        )
        profiles[id] = profile
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
        val wasActive = _activeProfile.value?.id == id
        val removed = profiles.remove(id) != null
        if (wasActive) {
            profiles["default"]?.let { defaultProfile ->
                profiles["default"] = defaultProfile.copy(isActive = true)
            }
        }
        updateFlows()
        return removed
    }

    override suspend fun setActiveProfile(id: String) {
        profiles.keys.forEach { key ->
            profiles[key]?.let { profile ->
                profiles[key] = profile.copy(isActive = key == id)
            }
        }
        updateFlows()
    }

    override suspend fun refreshProfiles() {
        updateFlows()
    }

    override suspend fun ensureDefaultProfile() {
        if (!profiles.containsKey("default")) {
            val defaultProfile = UserProfile(
                id = "default",
                name = "Default",
                colorIndex = 0,
                createdAt = currentTimeMillis(),
                isActive = true
            )
            profiles["default"] = defaultProfile
        }
        updateFlows()
    }
}
