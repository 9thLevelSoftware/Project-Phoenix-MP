package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.auth.sha256
import kotlinx.coroutines.flow.StateFlow

enum class ProfileRecoveryKind {
    PROFILE_DATA,
    LEGACY_BASELINE,
}

data class ProfileRecoveryCounts(
    val tableCounts: Map<String, Long>,
    val cloudOriginRowCount: Long = 0,
) {
    val totalRows: Long get() = tableCounts.values.sum()
}

data class PendingProfileRecoveryGroup(
    val recoveryId: String,
    val kind: ProfileRecoveryKind,
    val sourceKey: String,
    val sourceProfileId: String?,
    val sourceProfileName: String,
    val ownerUserId: String?,
    val counts: ProfileRecoveryCounts,
    val discoveredAt: Long,
)

sealed interface ProfileRecoveryResolution {
    data object Resolved : ProfileRecoveryResolution
    data object Busy : ProfileRecoveryResolution
    data object RelinkRequired : ProfileRecoveryResolution
    data object VerificationFailed : ProfileRecoveryResolution
    data class AccountMismatch(
        val expectedOwnerUserId: String,
        val signedInOwnerUserId: String,
    ) : ProfileRecoveryResolution
    data object Missing : ProfileRecoveryResolution
}

interface ProfileRecoveryRepository {
    val pendingRecoveries: StateFlow<List<PendingProfileRecoveryGroup>>
    suspend fun refresh()
    suspend fun keepWithDefault(
        recoveryId: String,
        signedInOwnerUserId: String?,
    ): ProfileRecoveryResolution
    suspend fun moveToProfile(
        recoveryId: String,
        targetProfileId: String,
        signedInOwnerUserId: String?,
    ): ProfileRecoveryResolution
}

data class ProfileRecoverySourceSnapshot(
    val sourceProfileId: String?,
    val workoutSessionIds: List<String>,
    val routineIds: List<String>,
    val cycleIds: List<String>,
    val personalRecordIds: List<String>,
    val proofWorkoutSessionIds: List<String>,
    val proofRoutineIds: List<String>,
    val proofCycleIds: List<String>,
    val proofPersonalRecordIds: List<String>,
) {
    val distinctProofCount: Int
        get() = proofWorkoutSessionIds.distinct().size +
            proofRoutineIds.distinct().size +
            proofCycleIds.distinct().size +
            proofPersonalRecordIds.distinct().size
}

data class ProfileRecoverySourceVerification(
    val verified: Boolean,
    val authenticatedOwnerUserId: String?,
    val verifiedProofCount: Int,
)

/** Authenticated, read-only cloud proof for a legacy group whose owner snapshot is absent. */
interface ProfileRecoverySourceVerifier {
    suspend fun verify(source: ProfileRecoverySourceSnapshot): ProfileRecoverySourceVerification
}

sealed interface ProfileRecoveryOwnerDecision {
    data object LocalOnly : ProfileRecoveryOwnerDecision
    data object RelinkRequired : ProfileRecoveryOwnerDecision
    data class QueueForKnownOwner(val ownerUserId: String) : ProfileRecoveryOwnerDecision
    data class AccountMismatch(
        val expectedOwnerUserId: String,
        val signedInOwnerUserId: String,
    ) : ProfileRecoveryOwnerDecision
}

fun decideProfileRecoveryOwner(
    persistedOwnerUserId: String?,
    signedInOwnerUserId: String?,
    cloudOriginRowCount: Long,
): ProfileRecoveryOwnerDecision = when {
    persistedOwnerUserId != null &&
        signedInOwnerUserId != null &&
        persistedOwnerUserId != signedInOwnerUserId -> ProfileRecoveryOwnerDecision.AccountMismatch(
        expectedOwnerUserId = persistedOwnerUserId,
        signedInOwnerUserId = signedInOwnerUserId,
    )
    persistedOwnerUserId != null -> ProfileRecoveryOwnerDecision.QueueForKnownOwner(persistedOwnerUserId)
    cloudOriginRowCount > 0L -> ProfileRecoveryOwnerDecision.RelinkRequired
    else -> ProfileRecoveryOwnerDecision.LocalOnly
}

data class OwnershipTransferMutation(
    val mutationId: String,
    val ownerUserId: String,
    val sourceProfileId: String?,
    val targetProfileId: String,
    val workoutSessionIds: List<String>,
    val routineIds: List<String>,
    val cycleIds: List<String>,
    val personalRecordIds: List<String>,
    /** Local queue timestamp for observability and causal ordering; never serialized to portal. */
    val createdAt: Long = 0L,
)

interface OwnershipTransferRepository {
    suspend fun pendingForOwner(ownerUserId: String): List<OwnershipTransferMutation>
    suspend fun pendingAll(): List<OwnershipTransferMutation> = emptyList()
    suspend fun acknowledge(
        ownerUserId: String,
        mutationIds: Set<String>,
        acknowledgedAt: Long,
    ): Int
}

data class OwnershipEvent(
    val mutationId: String,
    val sourceProfileId: String?,
    val targetProfileId: String,
    val targetProfileName: String,
    val targetProfileColorIndex: Int,
    val workoutSessionIds: List<String>,
    val routineIds: List<String>,
    val cycleIds: List<String>,
    val personalRecordIds: List<String>,
    val transferredAt: Long,
)

data class OwnershipEventApplySummary(
    val appliedCount: Int,
    val alreadyAppliedCount: Int,
)

interface OwnershipEventApplier {
    suspend fun applyRemoteEvents(
        ownerUserId: String,
        events: List<OwnershipEvent>,
    ): OwnershipEventApplySummary
}

enum class OwnershipEntityType {
    WORKOUT,
    ROUTINE,
    CYCLE,
    PERSONAL_RECORD,
}

/** Retained claim lookup used by pull and restore before materializing an entity locally. */
interface LocalOwnershipClaimLookup {
    fun targetProfileId(
        ownerUserId: String,
        entityType: OwnershipEntityType,
        entityId: String,
    ): String?
}

class SqlDelightLocalOwnershipClaimLookup(
    database: com.devil.phoenixproject.database.PhoenixDatabase,
) : LocalOwnershipClaimLookup {
    private val queries = database.phoenixDatabaseQueries

    override fun targetProfileId(
        ownerUserId: String,
        entityType: OwnershipEntityType,
        entityId: String,
    ): String? = queries.selectLocalOwnershipClaim(ownerUserId, entityType.name, entityId)
        .executeAsOneOrNull()
        ?.target_profile_id
}

class OwnershipEventConflictException(
    ownerUserId: String,
    mutationId: String,
) : IllegalStateException(
    "Ownership event body conflicts with an already applied event for owner=$ownerUserId mutation=$mutationId",
)

internal fun OwnershipEvent.canonicalBody(): String = buildString {
    appendCanonicalField("source", sourceProfileId.orEmpty())
    appendCanonicalField("target", targetProfileId)
    appendCanonicalField("targetName", targetProfileName)
    appendCanonicalField("targetColor", targetProfileColorIndex.toString())
    appendCanonicalIds("workouts", workoutSessionIds)
    appendCanonicalIds("routines", routineIds)
    appendCanonicalIds("cycles", cycleIds)
    appendCanonicalIds("personalRecords", personalRecordIds)
    appendCanonicalField("transferredAt", transferredAt.toString())
}

internal fun OwnershipEvent.canonicalBodyHash(): String = sha256(
    canonicalBody().encodeToByteArray(),
).joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

internal fun validateOwnershipEventReplay(
    ownerUserId: String,
    mutationId: String,
    persistedCanonicalBodyHash: String,
    event: OwnershipEvent,
) {
    if (persistedCanonicalBodyHash != event.canonicalBodyHash()) {
        throw OwnershipEventConflictException(ownerUserId, mutationId)
    }
}

private fun StringBuilder.appendCanonicalIds(name: String, values: List<String>) {
    val canonical = values.asSequence()
        .onEach { require(it.isNotBlank()) { "$name contains a blank id" } }
        .distinct()
        .sorted()
        .toList()
    appendCanonicalField(name, canonical.joinToString("\u001e"))
}

private fun StringBuilder.appendCanonicalField(name: String, value: String) {
    append(name.length).append(':').append(name)
    append(value.length).append(':').append(value)
}
