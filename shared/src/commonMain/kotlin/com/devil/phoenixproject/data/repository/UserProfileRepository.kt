package com.devil.phoenixproject.data.repository

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.preferences.ProfileLocalSafetyStore
import com.devil.phoenixproject.database.PhoenixDatabase
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

/**
 * A profile switch was refused because a workout session went live while the switch
 * waited for the profile mutation barrier (FP-6, codex 4081312853).
 */
class ProfileSwitchBlockedDuringWorkoutException :
    IllegalStateException("A workout session is live; the profile cannot change now")

class ProfileContextUnavailableException :
    IllegalStateException(
        "Active profile context is switching",
    )

class StaleProfileContextException(
    expectedProfileId: String,
    activeProfileId: String,
) : IllegalStateException(
    "Profile changed from $expectedProfileId to $activeProfileId before the update completed",
)

class ProfileContextRecoveryException(cause: Throwable) :
    IllegalStateException(
        "Could not reconcile the active profile context",
        cause,
    )

class ProfileOwnershipMismatchException(
    sourceOwnerUserId: String,
    targetOwnerUserId: String,
) : IllegalStateException(
    "Cannot move profile data between different accounts: source=$sourceOwnerUserId target=$targetOwnerUserId",
)

class ProfileAccountBindingException(
    profileId: String,
    currentOwnerUserId: String,
    requestedOwnerUserId: String,
) : IllegalStateException(
    "Profile $profileId is already linked to account $currentOwnerUserId and cannot be linked to " +
        "$requestedOwnerUserId. Switch to or create an unlinked profile before signing in.",
)

data class ProfileAccountLinkReceipt(
    val profileId: String,
    val ownerUserId: String,
    val linkedAt: Long,
    val previousOwnerUserId: String?,
    val previousLastAuthAt: Long?,
)

class ProfileAccountLinkRollbackException(profileId: String) : IllegalStateException(
    "Profile $profileId changed after the account link and cannot be rolled back safely",
)

interface UserProfileRepository {
    val activeProfile: StateFlow<UserProfile?>
    val allProfiles: StateFlow<List<UserProfile>>
    val activeProfileContext: StateFlow<ActiveProfileContext>

    fun observePreferences(profileId: String): Flow<UserProfilePreferences>
    suspend fun createProfile(name: String, colorIndex: Int): UserProfile
    /**
     * [blockedByLiveSession] is re-checked AFTER the profile mutation barrier is acquired:
     * a workout can start while the switch waits for sync/auth to release it. A true
     * result aborts with [ProfileSwitchBlockedDuringWorkoutException] and changes nothing.
     */
    suspend fun createAndActivateProfile(
        name: String,
        colorIndex: Int,
        blockedByLiveSession: () -> Boolean = { false },
    ): UserProfile
    suspend fun updateProfile(id: String, name: String, colorIndex: Int)
    /** Deleting the ACTIVE profile is refused while [blockedByLiveSession] (see [createAndActivateProfile]). */
    suspend fun deleteProfile(id: String, blockedByLiveSession: () -> Boolean = { false }): Boolean
    suspend fun deleteActiveProfile(
        expectedProfileId: String,
        blockedByLiveSession: () -> Boolean = { false },
    ): Boolean
    /** See [createAndActivateProfile] for [blockedByLiveSession]. */
    suspend fun setActiveProfile(id: String, blockedByLiveSession: () -> Boolean = { false })
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

    /** Atomically transforms the latest active workout section after validating [profileId]. */
    suspend fun mutateWorkout(
        profileId: String,
        transform: (WorkoutPreferences) -> WorkoutPreferences,
    )
    suspend fun updateLed(profileId: String, value: LedPreferences)
    suspend fun updateVbt(profileId: String, value: VbtPreferences)
    suspend fun updateLocalSafety(profileId: String, value: ProfileLocalSafetyPreferences)
    suspend fun retryPendingLocalCleanup(profileId: String? = null)
    suspend fun recoverPendingProfileTransitionForStartup()
    suspend fun reconcileActiveProfileContext()

    suspend fun linkToSupabase(profileId: String, supabaseUserId: String)

    /**
     * Links a profile while the caller already owns [ProfileMutationBarrier].
     *
     * This path still serializes the repository profile context. Callers must not invoke it unless
     * they hold the shared barrier for the complete auth/profile transition.
     */
    suspend fun linkToSupabaseUnderProfileMutationBarrier(
        profileId: String,
        supabaseUserId: String,
    ): ProfileAccountLinkReceipt

    /** Rolls back only the exact link represented by [receipt]; caller must hold the shared barrier. */
    suspend fun rollbackSupabaseLinkUnderProfileMutationBarrier(receipt: ProfileAccountLinkReceipt)
    suspend fun updateSubscriptionStatus(
        profileId: String,
        status: SubscriptionStatus,
        expiresAt: Long?,
    )
    suspend fun getProfileBySupabaseId(supabaseUserId: String): UserProfile?
    fun getActiveProfileSubscriptionStatus(): Flow<SubscriptionStatus>
}

class SqlDelightUserProfileRepository(
    private val database: PhoenixDatabase,
    private val profilePreferencesRepository: ProfilePreferencesRepository,
    private val profileLocalSafetyStore: ProfileLocalSafetyStore,
    private val gamificationRepository: GamificationRepository,
    private val profileScopedDataMerger: ProfileScopedDataMerger = ProfileScopedDataMerger(database),
    private val profileMutationBarrier: ProfileMutationBarrier = ProfileMutationBarrier(),
    private val beforeProfileDeletionCommit: () -> Unit = {},
) : UserProfileRepository {
    private val queries = database.phoenixDatabaseQueries
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

    override fun observePreferences(profileId: String): Flow<UserProfilePreferences> = profilePreferencesRepository.observe(profileId)

    override suspend fun createProfile(name: String, colorIndex: Int): UserProfile = withProfileMutation {
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
        blockedByLiveSession: () -> Boolean,
    ): UserProfile = withProfileMutation {
        requireNoLiveWorkoutSession(blockedByLiveSession)
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
        withProfileMutation {
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

    override suspend fun deleteProfile(id: String, blockedByLiveSession: () -> Boolean): Boolean = withProfileMutation {
        deleteProfileLocked(id, requireActive = false, blockedByLiveSession)
    }

    override suspend fun deleteActiveProfile(
        expectedProfileId: String,
        blockedByLiveSession: () -> Boolean,
    ): Boolean = withProfileMutation {
        val ready = _activeProfileContext.value as? ActiveProfileContext.Ready
            ?: throw ProfileContextUnavailableException()
        if (ready.profile.id != expectedProfileId) {
            throw StaleProfileContextException(expectedProfileId, ready.profile.id)
        }
        deleteProfileLocked(expectedProfileId, requireActive = true, blockedByLiveSession)
    }

    private suspend fun deleteProfileLocked(
        id: String,
        requireActive: Boolean,
        blockedByLiveSession: () -> Boolean,
    ): Boolean {
        if (id == DEFAULT_PROFILE_ID) return false

        val previous = _activeProfileContext.value as? ActiveProfileContext.Ready
            ?: throw ProfileContextUnavailableException()
        if (requireActive && previous.profile.id != id) {
            throw StaleProfileContextException(id, previous.profile.id)
        }
        val sourceProfile = queries.getProfileById(id).executeAsOneOrNull() ?: return false

        val wasActive = previous.profile.id == id
        // Removing the active profile moves the running workout's lease onto a deleted id
        // (codex 4082092571). Checked here, under the barrier, like switch and create.
        if (wasActive) requireNoLiveWorkoutSession(blockedByLiveSession)
        val targetProfileId = if (requireActive || wasActive) DEFAULT_PROFILE_ID else previous.profile.id
        val targetProfile = requireNotNull(queries.getProfileById(targetProfileId).executeAsOneOrNull()) {
            "Profile deletion target missing: $targetProfileId"
        }
        val ownerUserId = sourceProfile.supabase_user_id
        if (ownerUserId != null &&
            targetProfile.supabase_user_id != null &&
            targetProfile.supabase_user_id != ownerUserId
        ) {
            throw ProfileOwnershipMismatchException(ownerUserId, targetProfile.supabase_user_id)
        }
        if (wasActive) {
            _activeProfileContext.value = ActiveProfileContext.Switching(targetProfileId)
        }

        try {
            Logger.i { "PROFILE_DELETE: Reassigning data from profile '$id' to '$targetProfileId'" }
            database.transaction {
                val ownershipTransfer = ownerUserId?.let { owner ->
                    OwnershipTransferMutation(
                        mutationId = generateUUID(),
                        ownerUserId = owner,
                        sourceProfileId = id,
                        targetProfileId = targetProfileId,
                        workoutSessionIds = queries.selectPortalSessionIdsByProfile(id).executeAsList(),
                        routineIds = queries.selectAllRoutineIdsByProfile(id).executeAsList(),
                        cycleIds = queries.selectAllRetainedCycleIdsByProfile(id).executeAsList(),
                        personalRecordIds = queries.selectAllPersonalRecordUuidsByProfile(id)
                            .executeAsList()
                            .filterNotNull(),
                    )
                }
                if (ownerUserId != null && targetProfile.supabase_user_id == null) {
                    queries.linkProfileToSupabase(ownerUserId, currentTimeMillis(), targetProfileId)
                }
                queries.enqueueProfileLocalCleanup(id, currentTimeMillis())
                queries.deleteActiveWorkoutRuntimeByProfile(id)
                profileScopedDataMerger.mergeForProfileDeletion(id, targetProfileId)
                queries.reassignRoutineGroupProfile(targetProfileId, id)
                queries.reassignRoutineProfile(targetProfileId, id)
                queries.reassignSessionProfile(targetProfileId, id)
                queries.reassignTrainingCycleProfile(targetProfileId, id)
                queries.adoptCycleSyncStateProfile(targetProfileId, id)
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
                ownershipTransfer?.takeIf { it.hasNamedEntities() }?.let { transfer ->
                    queries.insertOwnershipTransferOutbox(
                        mutationId = transfer.mutationId,
                        ownerUserId = transfer.ownerUserId,
                        sourceProfileId = transfer.sourceProfileId,
                        targetProfileId = transfer.targetProfileId,
                        workoutSessionIdsJson = encodeOwnershipIds(transfer.workoutSessionIds),
                        routineIdsJson = encodeOwnershipIds(transfer.routineIds),
                        cycleIdsJson = encodeOwnershipIds(transfer.cycleIds),
                        personalRecordIdsJson = encodeOwnershipIds(transfer.personalRecordIds),
                        createdAt = nextOwnershipTransferCreatedAt(transfer.ownerUserId),
                    )
                }
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
            val recoveryFailure = withContext(NonCancellable) {
                runCatching {
                    reconcileActiveProfileContextLocked(publishReady = true)
                }.exceptionOrNull()
            }
            if (recoveryFailure != null) {
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

        return true
    }

    private fun OwnershipTransferMutation.hasNamedEntities(): Boolean =
        workoutSessionIds.isNotEmpty() || routineIds.isNotEmpty() || cycleIds.isNotEmpty() ||
            personalRecordIds.isNotEmpty()

    /** Keeps chained X -> source -> target transfers in a stable account-local order. */
    private fun nextOwnershipTransferCreatedAt(ownerUserId: String): Long {
        val now = currentTimeMillis()
        val latest = queries.selectAllOwnershipTransfers().executeAsList()
            .asSequence()
            .filter { it.owner_user_id == ownerUserId }
            .maxOfOrNull { it.created_at }
            ?: return now
        return maxOf(now, latest + 1L)
    }

    override suspend fun setActiveProfile(id: String, blockedByLiveSession: () -> Boolean) {
        withProfileMutation {
            // Checked under the barrier: a workout may have started while this waited.
            requireNoLiveWorkoutSession(blockedByLiveSession)
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

    override suspend fun updateCore(profileId: String, value: CoreProfilePreferences) = mutateActiveProfile(profileId) {
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

    override suspend fun updateRack(profileId: String, value: RackPreferences) = mutateActiveProfile(profileId) {
        profilePreferencesRepository.updateRack(profileId, value, currentTimeMillis())
    }

    override suspend fun updateWorkout(profileId: String, value: WorkoutPreferences) = mutateActiveProfile(profileId) {
        profilePreferencesRepository.updateWorkout(profileId, value, currentTimeMillis())
    }

    override suspend fun mutateWorkout(
        profileId: String,
        transform: (WorkoutPreferences) -> WorkoutPreferences,
    ) = mutateActiveProfile(profileId) { context ->
        profilePreferencesRepository.updateWorkout(
            profileId,
            transform(context.preferences.workout.value),
            currentTimeMillis(),
        )
    }

    override suspend fun updateLed(profileId: String, value: LedPreferences) = mutateActiveProfile(profileId) {
        profilePreferencesRepository.updateLed(profileId, value, currentTimeMillis())
    }

    override suspend fun updateVbt(profileId: String, value: VbtPreferences) = mutateActiveProfile(profileId) {
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
        withProfileMutation {
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
        withProfileMutation {
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
        withProfileMutation {
            linkToSupabaseLocked(profileId, supabaseUserId)
        }
    }

    override suspend fun linkToSupabaseUnderProfileMutationBarrier(
        profileId: String,
        supabaseUserId: String,
    ): ProfileAccountLinkReceipt = profileContextMutex.withLock {
        linkToSupabaseLocked(profileId, supabaseUserId)
    }

    override suspend fun rollbackSupabaseLinkUnderProfileMutationBarrier(
        receipt: ProfileAccountLinkReceipt,
    ) {
        profileContextMutex.withLock {
            val profile = queries.getProfileById(receipt.profileId).executeAsOneOrNull()
                ?: throw ProfileAccountLinkRollbackException(receipt.profileId)
            if (profile.supabase_user_id != receipt.ownerUserId || profile.last_auth_at != receipt.linkedAt) {
                throw ProfileAccountLinkRollbackException(receipt.profileId)
            }
            queries.linkProfileToSupabase(
                supabase_user_id = receipt.previousOwnerUserId,
                last_auth_at = receipt.previousLastAuthAt,
                id = receipt.profileId,
            )
            refreshProfilesSync()
            republishReadyIdentityIfActive(receipt.profileId)
        }
    }

    private fun linkToSupabaseLocked(
        profileId: String,
        supabaseUserId: String,
    ): ProfileAccountLinkReceipt {
        val profile = queries.getProfileById(profileId).executeAsOneOrNull()
            ?: error("Profile does not exist: $profileId")
        profile.supabase_user_id?.let { currentOwnerUserId ->
            if (currentOwnerUserId != supabaseUserId) {
                throw ProfileAccountBindingException(
                    profileId = profileId,
                    currentOwnerUserId = currentOwnerUserId,
                    requestedOwnerUserId = supabaseUserId,
                )
            }
        }
        val linkedAt = currentTimeMillis()
        queries.linkProfileToSupabase(
            supabase_user_id = supabaseUserId,
            last_auth_at = linkedAt,
            id = profileId,
        )
        refreshProfilesSync()
        republishReadyIdentityIfActive(profileId)
        return ProfileAccountLinkReceipt(
            profileId = profileId,
            ownerUserId = supabaseUserId,
            linkedAt = linkedAt,
            previousOwnerUserId = profile.supabase_user_id,
            previousLastAuthAt = profile.last_auth_at,
        )
    }

    override suspend fun updateSubscriptionStatus(
        profileId: String,
        status: SubscriptionStatus,
        expiresAt: Long?,
    ) {
        withProfileMutation {
            queries.updateSubscriptionStatus(
                subscription_status = status.toDbString(),
                subscription_expires_at = expiresAt,
                id = profileId,
            )
            refreshProfilesSync()
            republishReadyIdentityIfActive(profileId)
        }
    }

    override suspend fun getProfileBySupabaseId(supabaseUserId: String): UserProfile? = queries.getProfileBySupabaseId(supabaseUserId)
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
        withProfileMutation {
            val context = _activeProfileContext.value as? ActiveProfileContext.Ready
                ?: throw ProfileContextUnavailableException()
            if (context.profile.id != expectedProfileId) {
                throw StaleProfileContextException(expectedProfileId, context.profile.id)
            }
            write(context)
            publishReadyContext(expectedProfileId)
        }
    }

    /**
     * FP-6 guard for any mutation that changes, removes or reassigns the ACTIVE
     * profile: switch, create-and-activate, active-profile deletion and (PR 20)
     * merge/delete. Call it inside [withProfileMutation], after the barrier is held,
     * because a workout can start while the mutation waits for sync/auth.
     */
    private fun requireNoLiveWorkoutSession(blockedByLiveSession: () -> Boolean) {
        if (blockedByLiveSession()) throw ProfileSwitchBlockedDuringWorkoutException()
    }

    /** Lock order is shared barrier first, then the repository context mutex. */
    private suspend fun <T> withProfileMutation(block: suspend () -> T): T =
        profileMutationBarrier.withExclusive {
            profileContextMutex.withLock { block() }
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

    private fun republishReadyIdentityIfActive(profileId: String) {
        val ready = _activeProfileContext.value as? ActiveProfileContext.Ready
        if (ready?.profile?.id != profileId) return
        val refreshedProfile = allProfiles.value.firstOrNull { it.id == profileId }
            ?: error("Active profile missing from identity flow: $profileId")
        _activeProfileContext.value = ready.copy(profile = refreshedProfile)
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
