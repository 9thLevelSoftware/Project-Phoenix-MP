package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SubscriptionStatus {
    FREE,
    ACTIVE,
    EXPIRED,
    GRACE_PERIOD;

    companion object {
        fun fromString(value: String?): SubscriptionStatus {
            return when (value?.lowercase()) {
                "active" -> ACTIVE
                "expired" -> EXPIRED
                "grace_period" -> GRACE_PERIOD
                else -> FREE
            }
        }
    }

    fun toDbString(): String = name.lowercase()
}

data class UserProfile(
    val id: String,
    val name: String,
    val colorIndex: Int,
    val createdAt: Long,
    val isActive: Boolean,
    // Subscription fields
    val supabaseUserId: String? = null,
    val subscriptionStatus: SubscriptionStatus = SubscriptionStatus.FREE,
    val subscriptionExpiresAt: Long? = null,
    val lastAuthAt: Long? = null
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

    // Subscription methods
    suspend fun linkToSupabase(profileId: String, supabaseUserId: String)
    suspend fun updateSubscriptionStatus(profileId: String, status: SubscriptionStatus, expiresAt: Long?)
    suspend fun getProfileBySupabaseId(supabaseUserId: String): UserProfile?
    fun getActiveProfileSubscriptionStatus(): Flow<SubscriptionStatus>
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
            isActive = isActive == 1L,
            supabaseUserId = supabase_user_id,
            subscriptionStatus = SubscriptionStatus.fromString(subscription_status),
            subscriptionExpiresAt = subscription_expires_at,
            lastAuthAt = last_auth_at
        )
    }

    // Subscription methods implementation
    override suspend fun linkToSupabase(profileId: String, supabaseUserId: String) {
        queries.linkProfileToSupabase(
            supabase_user_id = supabaseUserId,
            last_auth_at = currentTimeMillis(),
            id = profileId
        )
        refreshProfilesSync()
    }

    override suspend fun updateSubscriptionStatus(profileId: String, status: SubscriptionStatus, expiresAt: Long?) {
        queries.updateSubscriptionStatus(
            subscription_status = status.toDbString(),
            subscription_expires_at = expiresAt,
            id = profileId
        )
        refreshProfilesSync()
    }

    override suspend fun getProfileBySupabaseId(supabaseUserId: String): UserProfile? {
        return queries.getProfileBySupabaseId(supabaseUserId)
            .executeAsOneOrNull()
            ?.toUserProfile()
    }

    override fun getActiveProfileSubscriptionStatus(): Flow<SubscriptionStatus> {
        return kotlinx.coroutines.flow.flow {
            val result = queries.getActiveProfileSubscriptionStatus()
                .executeAsOneOrNull()
            emit(SubscriptionStatus.fromString(result?.subscription_status))
        }
    }
}
