package com.devil.phoenixproject.data.repository

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.preferences.ProfileLocalSafetyStore
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.CoreProfilePreferences
import com.devil.phoenixproject.domain.model.LedPreferences
import com.devil.phoenixproject.domain.model.ProfileLocalSafetyPreferences
import com.devil.phoenixproject.domain.model.RackPreferences
import com.devil.phoenixproject.domain.model.UserProfilePreferences
import com.devil.phoenixproject.domain.model.VbtPreferences
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.domain.premium.RpgAttributeEngine
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SubscriptionStatus {
    FREE,
    ACTIVE,
    EXPIRED,
    GRACE_PERIOD,
    ;

    companion object {
        fun fromString(value: String?): SubscriptionStatus = when (value?.lowercase()) {
            "active" -> ACTIVE
            "expired" -> EXPIRED
            "grace_period" -> GRACE_PERIOD
            else -> FREE
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
    val supabaseUserId: String? = null,
    val subscriptionStatus: SubscriptionStatus = SubscriptionStatus.FREE,
    val subscriptionExpiresAt: Long? = null,
    val lastAuthAt: Long? = null,
)

sealed interface ActiveProfileContext {
    data class Switching(val targetProfileId: String?) : ActiveProfileContext

    data class Ready(
        val profile: UserProfile,
        val preferences: UserProfilePreferences,
        val localSafety: ProfileLocalSafetyPreferences,
    ) : ActiveProfileContext
}

class ProfileContextUnavailableException : IllegalStateException(
    "Active profile context is switching",
)

class StaleProfileContextException(
    expectedProfileId: String,
    activeProfileId: String,
) : IllegalStateException(
    "Profile changed from $expectedProfileId to $activeProfileId before the update completed",
)

class ProfileContextRecoveryException(cause: Throwable) : IllegalStateException(
    "Could not reconcile the active profile context",
    cause,
)

interface UserProfileRepository {
    val activeProfile: StateFlow<UserProfile?>
    val allProfiles: StateFlow<List<UserProfile>>
    val activeProfileContext: StateFlow<ActiveProfileContext>

    fun observePreferences(profileId: String): Flow<UserProfilePreferences>
    suspend fun createProfile(name: String, colorIndex: Int): UserProfile
    suspend fun createAndActivateProfile(name: String, colorIndex: Int): UserProfile
    suspend fun updateProfile(id: String, name: String, colorIndex: Int)
    suspend fun deleteProfile(id: String): Boolean
    suspend fun setActiveProfile(id: String)
    suspend fun refreshProfiles()
    suspend fun ensureDefaultProfile()
    suspend fun updateCore(profileId: String, value: CoreProfilePreferences)
    /** Atomically transforms the latest active core section after validating [profileId]. */
    suspend fun mutateCore(
        profileId: String,
        transform: (CoreProfilePreferences) -> CoreProfilePreferences,
    )
    suspend fun updateRack(profileId: String, value: RackPreferences)
    suspend fun updateWorkout(profileId: String, value: WorkoutPreferences)
    suspend fun updateLed(profileId: String, value: LedPreferences)
    suspend fun updateVbt(profileId: String, value: VbtPreferences)
    suspend fun updateLocalSafety(profileId: String, value: ProfileLocalSafetyPreferences)
    suspend fun retryPendingLocalCleanup(profileId: String? = null)
    suspend fun recoverPendingProfileTransitionForStartup()
    suspend fun reconcileActiveProfileContext()

    suspend fun linkToSupabase(profileId: String, supabaseUserId: String)
    suspend fun updateSubscriptionStatus(
        profileId: String,
        status: SubscriptionStatus,
        expiresAt: Long?,
    )
    suspend fun getProfileBySupabaseId(supabaseUserId: String): UserProfile?
    fun getActiveProfileSubscriptionStatus(): Flow<SubscriptionStatus>
}

class SqlDelightUserProfileRepository(
    private val database: VitruvianDatabase,
    private val profilePreferencesRepository: ProfilePreferencesRepository,
    private val profileLocalSafetyStore: ProfileLocalSafetyStore,
    private val gamificationRepository: GamificationRepository,
    private val profileScopedDataMerger: ProfileScopedDataMerger = ProfileScopedDataMerger(database),
    private val beforeProfileDeletionCommit: () -> Unit = {},
) : UserProfileRepository {
    private val queries = database.vitruvianDatabaseQueries
    private val profileContextMutex = Mutex()
    private val profileCleanupMutex = Mutex()

    private val _activeProfile = MutableStateFlow<UserProfile?>(null)
    override val activeProfile: StateFlow<UserProfile?> = _activeProfile.asStateFlow()

    private val _allProfiles = MutableStateFlow<List<UserProfile>>(emptyList())
    override val allProfiles: StateFlow<List<UserProfile>> = _allProfiles.asStateFlow()

    private val _activeProfileContext = MutableStateFlow<ActiveProfileContext>(
        ActiveProfileContext.Switching(null),
    )
    override val activeProfileContext: StateFlow<ActiveProfileContext> =
        _activeProfileContext.asStateFlow()

    init {
        ensureDefaultProfileSync()
        _activeProfileContext.value = ActiveProfileContext.Switching(activeProfile.value?.id)
    }

    override fun observePreferences(profileId: String): Flow<UserProfilePreferences> =
        profilePreferencesRepository.observe(profileId)

    override suspend fun createProfile(name: String, colorIndex: Int): UserProfile =
        profileContextMutex.withLock {
            val trimmedName = name.trim()
            require(trimmedName.isNotEmpty()) { "Profile name must not be blank" }
            val id = generateUUID()
            val createdAt = currentTimeMillis()
            database.transaction {
                queries.insertProfile(id, trimmedName, colorIndex.toLong(), createdAt, 0L)
                queries.insertDefaultProfilePreferences(id, 1L)
            }
            refreshProfilesSync()
            requireNotNull(allProfiles.value.firstOrNull { it.id == id }) {
                "Created profile missing: $id"
            }
        }

    override suspend fun createAndActivateProfile(
        name: String,
        colorIndex: Int,
    ): UserProfile = profileContextMutex.withLock {
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty()) { "Profile name must not be blank" }
        val id = generateUUID()
        val createdAt = currentTimeMillis()
        withProfileContextTransition(id) { previous ->
            database.transaction {
                queries.insertProfile(id, trimmedName, colorIndex.toLong(), createdAt, 0L)
                queries.insertDefaultProfilePreferences(id, 1L)
                queries.enqueueProfileContextRecovery(previous.profile.id, id, createdAt)
                queries.setActiveProfile(id)
            }
            refreshProfilesSync()
            publishReadyContext(id)
            val created = activeProfile.value ?: error("Activated profile missing: $id")
            queries.clearPendingProfileContextRecovery()
            created
        }
    }

    override suspend fun updateProfile(id: String, name: String, colorIndex: Int) {
        profileContextMutex.withLock {
            val trimmedName = name.trim()
            require(trimmedName.isNotEmpty()) { "Profile name must not be blank" }
            queries.updateProfile(trimmedName, colorIndex.toLong(), id)
            refreshProfilesSync()
            if (activeProfile.value?.id == id &&
                _activeProfileContext.value is ActiveProfileContext.Ready
            ) {
                publishReadyContext(id)
            }
        }
    }

    override suspend fun deleteProfile(id: String): Boolean = profileContextMutex.withLock {
        if (id == DEFAULT_PROFILE_ID) return@withLock false

        val previous = _activeProfileContext.value as? ActiveProfileContext.Ready
            ?: throw ProfileContextUnavailableException()
        if (queries.getProfileById(id).executeAsOneOrNull() == null) return@withLock false

        val wasActive = previous.profile.id == id
        val targetProfileId = if (wasActive) DEFAULT_PROFILE_ID else previous.profile.id
        requireNotNull(queries.getProfileById(targetProfileId).executeAsOneOrNull()) {
            "Profile deletion target missing: $targetProfileId"
        }
        if (wasActive) {
            _activeProfileContext.value = ActiveProfileContext.Switching(targetProfileId)
        }

        try {
            Logger.i { "PROFILE_DELETE: Reassigning data from profile '$id' to '$targetProfileId'" }
            database.transaction {
                queries.enqueueProfileLocalCleanup(id, currentTimeMillis())
                profileScopedDataMerger.mergeForProfileDeletion(id, targetProfileId)
                queries.reassignRoutineGroupProfile(targetProfileId, id)
                queries.reassignRoutineProfile(targetProfileId, id)
                queries.reassignSessionProfile(targetProfileId, id)
                queries.reassignTrainingCycleProfile(targetProfileId, id)
                queries.reassignStreakProfile(targetProfileId, id)
                queries.deleteGamificationStatsByProfile(id)
                queries.deleteGamificationStatsByProfile(targetProfileId)
                queries.deleteRpgAttributesByProfile(id)
                queries.deleteRpgAttributesByProfile(targetProfileId)
                queries.reassignAssessmentResultProfile(targetProfileId, id)
                queries.reassignVelocityOneRepMaxProfile(targetProfileId, id)
                queries.reassignProgressionProfile(targetProfileId, id)
                queries.deleteIntegrationStatusByProfile(id)
                queries.deleteIntegrationSyncCursorByProfile(id)
                queries.deleteProfilePreferences(id)
                queries.deleteProfile(id)
                if (wasActive) queries.setActiveProfile(targetProfileId)
                beforeProfileDeletionCommit()
            }
        } catch (failure: Throwable) {
            _activeProfileContext.value = previous
            throw failure
        }

        try {
            refreshProfilesSync()
            publishReadyContext(targetProfileId)
        } catch (failure: Throwable) {
            _activeProfileContext.value = ActiveProfileContext.Switching(targetProfileId)
            runCatching {
                reconcileActiveProfileContextLocked(publishReady = true)
            }.getOrElse { recoveryFailure ->
                failure.addSuppressed(recoveryFailure)
                throw ProfileContextRecoveryException(failure)
            }
            if (failure is CancellationException) throw failure
        }

        retryPendingLocalCleanup(id)

        try {
            gamificationRepository.updateStats(targetProfileId)
            val rpgInput = gamificationRepository.getRpgInput(targetProfileId)
            gamificationRepository.saveRpgProfile(
                RpgAttributeEngine.computeProfile(rpgInput),
                targetProfileId,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.e(error) {
                "PROFILE_DELETE: Gamification recompute failed for profile '$targetProfileId' after deleting '$id'"
            }
        }

        true
    }

    override suspend fun setActiveProfile(id: String) {
        profileContextMutex.withLock {
            require(allProfiles.value.any { it.id == id }) { "Unknown profile: $id" }
            withProfileContextTransition(id) { previous ->
                database.transaction {
                    queries.enqueueProfileContextRecovery(
                        prior_profile_id = previous.profile.id,
                        created_profile_id = null,
                        enqueued_at = currentTimeMillis(),
                    )
                    queries.setActiveProfile(id)
                }
                refreshProfilesSync()
                publishReadyContext(id)
                queries.clearPendingProfileContextRecovery()
            }
        }
    }

    override suspend fun refreshProfiles() {
        profileContextMutex.withLock {
            refreshProfilesSync()
            val ready = _activeProfileContext.value as? ActiveProfileContext.Ready
            if (ready != null && activeProfile.value?.id == ready.profile.id) {
                publishReadyContext(ready.profile.id)
            }
        }
    }

    override suspend fun ensureDefaultProfile() {
        profileContextMutex.withLock {
            ensureDefaultProfileSync()
            val ready = _activeProfileContext.value as? ActiveProfileContext.Ready
            if (ready != null && activeProfile.value?.id == ready.profile.id) {
                publishReadyContext(ready.profile.id)
            }
        }
    }

    override suspend fun updateCore(profileId: String, value: CoreProfilePreferences) =
        mutateActiveProfile(profileId) {
            profilePreferencesRepository.updateCore(profileId, value, currentTimeMillis())
        }

    override suspend fun mutateCore(
        profileId: String,
        transform: (CoreProfilePreferences) -> CoreProfilePreferences,
    ) = mutateActiveProfile(profileId) { context ->
        profilePreferencesRepository.updateCore(
            profileId,
            transform(context.preferences.core.value),
            currentTimeMillis(),
        )
    }

    override suspend fun updateRack(profileId: String, value: RackPreferences) =
        mutateActiveProfile(profileId) {
            profilePreferencesRepository.updateRack(profileId, value, currentTimeMillis())
        }

    override suspend fun updateWorkout(profileId: String, value: WorkoutPreferences) =
        mutateActiveProfile(profileId) {
            profilePreferencesRepository.updateWorkout(profileId, value, currentTimeMillis())
        }

    override suspend fun updateLed(profileId: String, value: LedPreferences) =
        mutateActiveProfile(profileId) {
            profilePreferencesRepository.updateLed(profileId, value, currentTimeMillis())
        }

    override suspend fun updateVbt(profileId: String, value: VbtPreferences) =
        mutateActiveProfile(profileId) {
            profilePreferencesRepository.updateVbt(profileId, value, currentTimeMillis())
        }

    override suspend fun updateLocalSafety(
        profileId: String,
        value: ProfileLocalSafetyPreferences,
    ) = mutateActiveProfile(profileId) {
        profileLocalSafetyStore.write(profileId, value)
    }

    override suspend fun retryPendingLocalCleanup(profileId: String?) {
        profileCleanupMutex.withLock {
            queries.selectPendingProfileLocalCleanup().executeAsList()
                .asSequence()
                .filter { profileId == null || it.profile_id == profileId }
                .forEach { pending ->
                    try {
                        profileLocalSafetyStore.delete(pending.profile_id)
                        queries.dequeueProfileLocalCleanup(pending.profile_id)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Logger.w(error) {
                            "Profile local cleanup remains queued profile=${pending.profile_id}"
                        }
                    }
                }
        }
    }

    override suspend fun recoverPendingProfileTransitionForStartup() {
        profileContextMutex.withLock {
            _activeProfileContext.value = ActiveProfileContext.Switching(null)
            try {
                reconcileActiveProfileContextLocked(publishReady = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw ProfileContextRecoveryException(error)
            }
        }
    }

    override suspend fun reconcileActiveProfileContext() {
        profileContextMutex.withLock {
            _activeProfileContext.value = ActiveProfileContext.Switching(
                queries.selectPendingProfileContextRecovery()
                    .executeAsOneOrNull()
                    ?.prior_profile_id,
            )
            try {
                reconcileActiveProfileContextLocked(publishReady = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw ProfileContextRecoveryException(error)
            }
        }
    }

    override suspend fun linkToSupabase(profileId: String, supabaseUserId: String) {
        profileContextMutex.withLock {
            queries.linkProfileToSupabase(
                supabase_user_id = supabaseUserId,
                last_auth_at = currentTimeMillis(),
                id = profileId,
            )
            refreshProfilesSync()
            republishReadyIdentityIfActive(profileId)
        }
    }

    override suspend fun updateSubscriptionStatus(
        profileId: String,
        status: SubscriptionStatus,
        expiresAt: Long?,
    ) {
        profileContextMutex.withLock {
            queries.updateSubscriptionStatus(
                subscription_status = status.toDbString(),
                subscription_expires_at = expiresAt,
                id = profileId,
            )
            refreshProfilesSync()
            republishReadyIdentityIfActive(profileId)
        }
    }

    override suspend fun getProfileBySupabaseId(supabaseUserId: String): UserProfile? =
        queries.getProfileBySupabaseId(supabaseUserId)
            .executeAsOneOrNull()
            ?.toUserProfile()

    override fun getActiveProfileSubscriptionStatus(): Flow<SubscriptionStatus> = flow {
        val result = queries.getActiveProfileSubscriptionStatus().executeAsOneOrNull()
        emit(SubscriptionStatus.fromString(result?.subscription_status))
    }

    private suspend fun <T> withProfileContextTransition(
        targetProfileId: String?,
        operation: suspend (previous: ActiveProfileContext.Ready) -> T,
    ): T {
        val previous = _activeProfileContext.value as? ActiveProfileContext.Ready
            ?: throw ProfileContextUnavailableException()
        _activeProfileContext.value = ActiveProfileContext.Switching(targetProfileId)
        return try {
            operation(previous)
        } catch (failure: Throwable) {
            _activeProfileContext.value = ActiveProfileContext.Switching(previous.profile.id)
            runCatching {
                reconcileActiveProfileContextLocked(publishReady = true)
            }.getOrElse { recoveryFailure ->
                failure.addSuppressed(recoveryFailure)
                throw ProfileContextRecoveryException(failure)
            }
            throw failure
        }
    }

    private suspend fun mutateActiveProfile(
        expectedProfileId: String,
        write: suspend (ActiveProfileContext.Ready) -> Unit,
    ) {
        profileContextMutex.withLock {
            val context = _activeProfileContext.value as? ActiveProfileContext.Ready
                ?: throw ProfileContextUnavailableException()
            if (context.profile.id != expectedProfileId) {
                throw StaleProfileContextException(expectedProfileId, context.profile.id)
            }
            write(context)
            publishReadyContext(expectedProfileId)
        }
    }

    private suspend fun reconcileActiveProfileContextLocked(publishReady: Boolean) {
        val pending = queries.selectPendingProfileContextRecovery().executeAsOneOrNull()
        pending?.let { transition ->
            database.transaction {
                val priorId = queries.getProfileById(transition.prior_profile_id)
                    .executeAsOneOrNull()
                    ?.id
                    ?: DEFAULT_PROFILE_ID
                queries.setActiveProfile(priorId)
                transition.created_profile_id?.let { failedCreatedId ->
                    queries.deleteProfilePreferences(failedCreatedId)
                    queries.deleteProfile(failedCreatedId)
                }
            }
        }
        refreshProfilesSync()
        val actualActiveId = queries.getActiveProfile().executeAsOneOrNull()?.id
            ?: error("No active profile after context reconciliation")
        if (publishReady) {
            publishReadyContext(actualActiveId)
        } else {
            _activeProfileContext.value = ActiveProfileContext.Switching(actualActiveId)
        }
        if (pending != null) {
            try {
                queries.clearPendingProfileContextRecovery()
            } catch (failure: Throwable) {
                _activeProfileContext.value = ActiveProfileContext.Switching(actualActiveId)
                throw failure
            }
        }
    }

    private suspend fun publishReadyContext(profileId: String) {
        val profile = allProfiles.value.firstOrNull { it.id == profileId }
            ?: error("Active profile missing from identity flow: $profileId")
        val preferences = profilePreferencesRepository.get(profileId)
        val localSafety = profileLocalSafetyStore.read(profileId)
        _activeProfileContext.value = ActiveProfileContext.Ready(
            profile = profile,
            preferences = preferences,
            localSafety = localSafety,
        )
    }

    private suspend fun republishReadyIdentityIfActive(profileId: String) {
        val ready = _activeProfileContext.value as? ActiveProfileContext.Ready
        if (ready?.profile?.id == profileId) publishReadyContext(profileId)
    }

    private fun ensureDefaultProfileSync() {
        if (queries.countProfiles().executeAsOne() == 0L) {
            queries.insertProfile(
                id = DEFAULT_PROFILE_ID,
                name = "Default",
                colorIndex = 0L,
                createdAt = currentTimeMillis(),
                isActive = 1L,
            )
        }
        refreshProfilesSync()
        if (activeProfile.value == null && allProfiles.value.isNotEmpty()) {
            val targetId = allProfiles.value.firstOrNull { it.id == DEFAULT_PROFILE_ID }?.id
                ?: allProfiles.value.first().id
            queries.setActiveProfile(targetId)
            refreshProfilesSync()
        }
    }

    private fun refreshProfilesSync() {
        val profiles = queries.getAllProfiles().executeAsList().map { it.toUserProfile() }
        _allProfiles.value = profiles
        _activeProfile.value = profiles.find { it.isActive }
    }

    private fun com.devil.phoenixproject.database.UserProfile.toUserProfile() = UserProfile(
        id = id,
        name = name,
        colorIndex = colorIndex.toInt(),
        createdAt = createdAt,
        isActive = isActive == 1L,
        supabaseUserId = supabase_user_id,
        subscriptionStatus = SubscriptionStatus.fromString(subscription_status),
        subscriptionExpiresAt = subscription_expires_at,
        lastAuthAt = last_auth_at,
    )

    private companion object {
        const val DEFAULT_PROFILE_ID = "default"
    }
}
