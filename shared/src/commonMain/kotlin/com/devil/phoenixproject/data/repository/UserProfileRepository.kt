package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserProfile(
    val id: String,
    val name: String,
    val colorIndex: Int,
    val createdAt: Long,
    val isActive: Boolean
)

interface UserProfileRepository {
    val activeProfile: StateFlow<UserProfile?>
    val allProfiles: StateFlow<List<UserProfile>>

    suspend fun createProfile(name: String, colorIndex: Int): UserProfile
    suspend fun updateProfile(id: String, name: String, colorIndex: Int)
    suspend fun deleteProfile(id: String): Boolean
    suspend fun setActiveProfile(id: String)
    suspend fun refreshProfiles()
    suspend fun ensureDefaultProfile()
}

class SqlDelightUserProfileRepository(
    private val database: VitruvianDatabase
) : UserProfileRepository {

    private val queries = database.vitruvianDatabaseQueries

    private val _activeProfile = MutableStateFlow<UserProfile?>(null)
    override val activeProfile: StateFlow<UserProfile?> = _activeProfile.asStateFlow()

    private val _allProfiles = MutableStateFlow<List<UserProfile>>(emptyList())
    override val allProfiles: StateFlow<List<UserProfile>> = _allProfiles.asStateFlow()

    init {
        // Ensure default profile exists and refresh profiles on initialization
        ensureDefaultProfileSync()
    }

    private fun ensureDefaultProfileSync() {
        val count = queries.countProfiles().executeAsOne()
        if (count == 0L) {
            queries.insertProfile(
                id = "default",
                name = "Default",
                colorIndex = 0L,
                createdAt = currentTimeMillis(),
                isActive = 1L
            )
        }
        refreshProfilesSync()
    }

    private fun refreshProfilesSync() {
        val profiles = queries.getAllProfiles().executeAsList().map { it.toUserProfile() }
        _allProfiles.value = profiles
        _activeProfile.value = profiles.find { it.isActive }
    }

    override suspend fun refreshProfiles() {
        refreshProfilesSync()
    }

    override suspend fun ensureDefaultProfile() {
        ensureDefaultProfileSync()
    }

    override suspend fun createProfile(name: String, colorIndex: Int): UserProfile {
        val id = generateUUID()
        val createdAt = currentTimeMillis()
        queries.insertProfile(id, name, colorIndex.toLong(), createdAt, 0L)
        refreshProfilesSync()
        return UserProfile(id, name, colorIndex, createdAt, false)
    }

    override suspend fun updateProfile(id: String, name: String, colorIndex: Int) {
        queries.updateProfile(name, colorIndex.toLong(), id)
        refreshProfilesSync()
    }

    override suspend fun deleteProfile(id: String): Boolean {
        if (id == "default") return false
        val wasActive = _activeProfile.value?.id == id
        queries.deleteProfile(id)
        if (wasActive) {
            queries.setActiveProfile("default")
        }
        refreshProfilesSync()
        return true
    }

    override suspend fun setActiveProfile(id: String) {
        queries.setActiveProfile(id)
        refreshProfilesSync()
    }

    private fun com.devil.phoenixproject.database.UserProfile.toUserProfile(): UserProfile {
        return UserProfile(
            id = id,
            name = name,
            colorIndex = colorIndex.toInt(),
            createdAt = createdAt,
            isActive = isActive == 1L
        )
    }
}
