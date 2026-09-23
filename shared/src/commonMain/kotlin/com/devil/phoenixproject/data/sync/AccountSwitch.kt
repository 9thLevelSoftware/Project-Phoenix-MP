package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.data.repository.ProfileAccountLinkReceipt
import com.devil.phoenixproject.util.withPlatformLock

/**
 * How the user wants pre-switch local rows treated after signing in as a different
 * portal account (PR 11 / KD-7).
 */
enum class AccountSwitchChoice {
    /** "Upload workouts not yet synced" — keep and upload rows that never reached another account. */
    UPLOAD_NEVER_SYNCED,

    /** "Don't upload existing data" — leave every pre-switch row on this phone and in the old account. */
    EXCLUDE_ALL_EXISTING,
}

/** Entity types stored in `SyncExcludedEntity`. */
object SyncExcludedEntityTypes {
    const val WORKOUT = "WORKOUT"
    const val ROUTINE = "ROUTINE"
    const val CYCLE = "CYCLE"
    const val CUSTOM_EXERCISE = "CUSTOM_EXERCISE"
    const val PERSONAL_RECORD = "PERSONAL_RECORD"
    const val ASSESSMENT = "ASSESSMENT"
    const val EXERCISE_SIGNATURE = "EXERCISE_SIGNATURE"
    const val EXTERNAL_ACTIVITY = "EXTERNAL_ACTIVITY"
    const val EARNED_BADGE = "EARNED_BADGE"

    val ALL = listOf(
        WORKOUT,
        ROUTINE,
        CYCLE,
        CUSTOM_EXERCISE,
        PERSONAL_RECORD,
        ASSESSMENT,
        EXERCISE_SIGNATURE,
        EXTERNAL_ACTIVITY,
        EARNED_BADGE,
    )

    /**
     * Provenance rows in the same table: `(portal_user_id, reached(type), id)` records that
     * the row reached that portal account (it was pulled from it, or it was already on it
     * when the device switched away). Never read by [SyncExclusionFilter]; only the
     * account-switch classification uses them, so a row that belongs to the account being
     * switched BACK to is never excluded from it (codex #859).
     */
    fun reached(entityType: String): String = "REACHED_$entityType"
}

/**
 * A different portal account than the one this device's rows already belong to.
 * Produced by [detectAccountMismatch] and carried into [SyncState.AccountMismatch].
 */
data class AccountMismatchCandidate(
    val previousUserId: String,
    val previousUserLabel: String,
    val newUserId: String,
    val newUserLabel: String,
)

/**
 * Detects whether [newUserId] is a different portal account than the one this
 * device's rows already belong to (PR 11).
 *
 * A mismatch exists when [lastSyncedPortalUserId], or any profile's linked
 * `supabase_user_id`, names a different user. Profiles that were never linked
 * only matter through [lastSyncedPortalUserId] (acceptance case 1).
 */
internal fun detectAccountMismatch(
    newUserId: String,
    newUserLabel: String,
    lastSyncedPortalUserId: String?,
    lastSyncedPortalUserLabel: String?,
    profileOwners: List<Pair<String, String>>,
): AccountMismatchCandidate? {
    if (newUserId.isBlank()) return null
    val previousFromPush = lastSyncedPortalUserId?.takeIf { it.isNotBlank() && it != newUserId }
    val previousFromProfile = profileOwners.firstOrNull { (_, owner) ->
        owner.isNotBlank() && owner != newUserId
    }?.second
    val previousUserId = previousFromPush ?: previousFromProfile ?: return null
    val previousUserLabel = when {
        previousFromPush != null ->
            lastSyncedPortalUserLabel?.takeIf { it.isNotBlank() } ?: previousUserId
        else -> previousUserId
    }
    return AccountMismatchCandidate(
        previousUserId = previousUserId,
        previousUserLabel = previousUserLabel,
        newUserId = newUserId,
        newUserLabel = newUserLabel,
    )
}

/**
 * Outcome of committing one authenticated identity (PR 11).
 *
 * On a mismatch the token is saved so the user is signed in and can answer the
 * account-switch dialog, but no profile is relinked — that is the dialog's job.
 */
internal sealed class PortalIdentityCommitOutcome {
    data class Committed(val receipt: ProfileAccountLinkReceipt) : PortalIdentityCommitOutcome()
    data class AccountMismatchDetected(val mismatch: AccountMismatchCandidate) : PortalIdentityCommitOutcome()
}

/**
 * Hand-off slot for a mismatch detected by [com.devil.phoenixproject.data.repository.PortalAuthRepository]
 * (email and OAuth sign-in), which cannot reach [SyncManager] directly.
 * [SyncManager.adoptPendingAccountMismatch] drains it into [SyncState.AccountMismatch].
 *
 * One instance is shared by the two through Koin (`SyncModule`). It is deliberately not
 * a process-wide `object`: two independent [SyncManager]s (a second DI graph, or tests)
 * must never adopt each other's mismatch.
 */
class PendingAccountMismatch {
    private val lock = Any()
    private var candidate: AccountMismatchCandidate? = null

    internal fun publish(value: AccountMismatchCandidate) {
        withPlatformLock(lock) { candidate = value }
    }

    internal fun take(): AccountMismatchCandidate? = withPlatformLock(lock) {
        val current = candidate
        candidate = null
        current
    }

    internal fun peek(): AccountMismatchCandidate? = withPlatformLock(lock) { candidate }
}

/**
 * Exclusion set for one portal user (PR 11). Applied to every push payload so a
 * pre-switch row is never uploaded into an account that does not own it.
 */
data class SyncExclusionFilter(
    val workouts: Set<String> = emptySet(),
    val routines: Set<String> = emptySet(),
    val cycles: Set<String> = emptySet(),
    val customExercises: Set<String> = emptySet(),
    val personalRecords: Set<String> = emptySet(),
    val assessments: Set<String> = emptySet(),
    val exerciseSignatures: Set<String> = emptySet(),
    val externalActivities: Set<String> = emptySet(),
    val earnedBadges: Set<String> = emptySet(),
) {
    fun excludesWorkout(sessionId: String, portalSessionId: String? = null): Boolean =
        sessionId in workouts || (portalSessionId != null && portalSessionId in workouts)

    fun excludesRoutine(routineId: String): Boolean = routineId in routines

    fun excludesCycle(cycleId: String): Boolean = cycleId in cycles

    fun excludesCustomExercise(exerciseId: String): Boolean = exerciseId in customExercises

    fun excludesPersonalRecord(recordId: String): Boolean = recordId in personalRecords

    fun excludesAssessment(assessmentId: String): Boolean = assessmentId in assessments

    fun excludesExerciseSignature(signatureId: String): Boolean = signatureId in exerciseSignatures

    fun excludesExternalActivity(activityId: String): Boolean = activityId in externalActivities

    fun excludesEarnedBadge(badgeId: String): Boolean = badgeId in earnedBadges

    companion object {
        val EMPTY = SyncExclusionFilter()

        suspend fun load(syncRepository: com.devil.phoenixproject.data.repository.SyncRepository, portalUserId: String): SyncExclusionFilter {
            if (portalUserId.isBlank()) return EMPTY
            return SyncExclusionFilter(
                workouts = syncRepository.getSyncExcludedEntityIds(portalUserId, SyncExcludedEntityTypes.WORKOUT),
                routines = syncRepository.getSyncExcludedEntityIds(portalUserId, SyncExcludedEntityTypes.ROUTINE),
                cycles = syncRepository.getSyncExcludedEntityIds(portalUserId, SyncExcludedEntityTypes.CYCLE),
                customExercises = syncRepository.getSyncExcludedEntityIds(portalUserId, SyncExcludedEntityTypes.CUSTOM_EXERCISE),
                personalRecords = syncRepository.getSyncExcludedEntityIds(portalUserId, SyncExcludedEntityTypes.PERSONAL_RECORD),
                assessments = syncRepository.getSyncExcludedEntityIds(portalUserId, SyncExcludedEntityTypes.ASSESSMENT),
                exerciseSignatures = syncRepository.getSyncExcludedEntityIds(portalUserId, SyncExcludedEntityTypes.EXERCISE_SIGNATURE),
                externalActivities = syncRepository.getSyncExcludedEntityIds(portalUserId, SyncExcludedEntityTypes.EXTERNAL_ACTIVITY),
                earnedBadges = syncRepository.getSyncExcludedEntityIds(portalUserId, SyncExcludedEntityTypes.EARNED_BADGE),
            )
        }
    }
}

/**
 * PR 11 / KD-7: whether a local workout session never reached any portal account.
 *
 * A session reached a portal account when it was pulled from one ([portalOrigin] = 1) or
 * a push of it was acknowledged ([syncedSyncGeneration] > 0). [updatedAt] alone is not a
 * sync marker: a local edit such as a Just Lift exercise tag writes it, and the routine-group
 * repair re-arm nulls it on rows that already reached the old account. It is kept only as
 * the legacy signal for rows stamped before migration 49 added the generation columns: such
 * a row has generation 0 but a stamp at or below the old account's [boundary].
 */
internal fun isNeverSyncedSession(
    portalOrigin: Long,
    syncedSyncGeneration: Long,
    updatedAt: Long?,
    boundary: Long,
): Boolean = portalOrigin == 0L &&
    syncedSyncGeneration == 0L &&
    (updatedAt == null || updatedAt > boundary)

/** UI-facing [SyncState] for this mismatch. */
internal fun AccountMismatchCandidate.toSyncState(): SyncState.AccountMismatch =
    SyncState.AccountMismatch(
        previousUserId = previousUserId,
        previousUserLabel = previousUserLabel,
        newUserId = newUserId,
        newUserLabel = newUserLabel,
    )

/**
 * Parses the timestamp embedded in a `custom_<ms>` exercise id. Returns null when
 * the id does not carry one (conservative: treated as already synced).
 */
internal fun customExerciseIdTimestamp(exerciseId: String): Long? {
    if (!exerciseId.startsWith("custom_")) return null
    return exerciseId.removePrefix("custom_").toLongOrNull()
}
