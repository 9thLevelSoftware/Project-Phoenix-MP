package com.devil.phoenixproject.data.sync

internal const val MAX_PROFILE_PREFERENCE_SECTION_BYTES = 256 * 1024
internal const val MAX_PROFILE_PREFERENCE_REQUEST_BYTES = 512 * 1024

internal data class ProfilePreferencePushChunk(
    val payload: PortalSyncPayload,
    val ledger: Map<ProfilePreferenceSectionKey, Long>,
)

internal data class ProfilePreferencePushPlan(
    val chunks: List<ProfilePreferencePushChunk>,
    val unsyncable: List<ProfilePreferenceSyncIssue>,
)

internal fun planProfilePreferencePushChunks(
    basePayload: PortalSyncPayload,
    mutations: List<PreparedProfilePreferenceMutation>,
): ProfilePreferencePushPlan {
    fun preferencePayload(items: List<PreparedProfilePreferenceMutation>) = PortalSyncPayload(
        deviceId = basePayload.deviceId,
        platform = basePayload.platform,
        lastSync = basePayload.lastSync,
        profilePreferenceSections = items.map { it.wire },
    )

    val sorted = mutations.sortedWith(
        compareBy<PreparedProfilePreferenceMutation>(
            { it.key.localProfileId },
            { it.key.section.ordinal },
            { it.sentLocalGeneration },
        ),
    )
    val duplicateKeys = sorted.groupingBy { it.key }
        .eachCount()
        .filterValues { it > 1 }
        .keys
    val valid = mutableListOf<ValidPreferenceMutation>()
    val issues = mutableListOf<ProfilePreferenceSyncIssue>()

    sorted.forEach { mutation ->
        if (mutation.key in duplicateKeys) {
            issues += ProfilePreferenceSyncIssue(
                key = mutation.key,
                localGeneration = mutation.sentLocalGeneration,
                reason = ProfilePreferenceSyncIssueReason.DUPLICATE_SECTION.name,
            )
            return@forEach
        }

        val oneElementPayload = preferencePayload(listOf(mutation))
        val oneElement = encodePortalSyncPayload(oneElementPayload)
        val sectionBytes = oneElement.preferenceElementByteCount(
            oneElement.preferenceElementSpans.single(),
        )
        val oneElementBytes = oneElement.rawBytes.size
        val reason = when {
            sectionBytes > MAX_PROFILE_PREFERENCE_SECTION_BYTES ->
                ProfilePreferenceSyncIssueReason.SECTION_TOO_LARGE
            oneElementBytes > MAX_PROFILE_PREFERENCE_REQUEST_BYTES ->
                ProfilePreferenceSyncIssueReason.REQUEST_TOO_LARGE
            else -> null
        }
        if (reason != null) {
            issues += ProfilePreferenceSyncIssue(
                key = mutation.key,
                localGeneration = mutation.sentLocalGeneration,
                reason = reason.name,
            )
            return@forEach
        }
        valid += ValidPreferenceMutation(
            mutation = mutation,
            singlePayload = oneElementPayload,
            singleByteCount = oneElementBytes,
        )
    }

    val chunks = mutableListOf<ProfilePreferencePushChunk>()
    var current = mutableListOf<PreparedProfilePreferenceMutation>()
    var currentPayload: PortalSyncPayload? = null
    var currentByteCount = 0
    fun emit() {
        if (current.isEmpty()) return
        val payload = checkNotNull(currentPayload)
        check(currentByteCount <= MAX_PROFILE_PREFERENCE_REQUEST_BYTES) {
            "PROFILE_PREFERENCE_PLANNER_OVERSIZED_REQUEST"
        }
        val ledger = current.associate { it.key to it.sentLocalGeneration }
        check(
            ledger.size == current.size &&
                ledger.size == payload.profilePreferenceSections.orEmpty().size,
        ) {
            "PROFILE_PREFERENCE_LEDGER_CARDINALITY_MISMATCH"
        }
        chunks += ProfilePreferencePushChunk(
            payload = payload,
            ledger = ledger,
        )
        current = mutableListOf()
        currentPayload = null
        currentByteCount = 0
    }

    valid.forEach { item ->
        if (current.isEmpty()) {
            current += item.mutation
            currentPayload = item.singlePayload
            currentByteCount = item.singleByteCount
        } else {
            val candidate = current + item.mutation
            val candidatePayload = preferencePayload(candidate)
            val candidateBytes = encodePortalSyncPayload(candidatePayload).rawBytes.size
            if (candidateBytes > MAX_PROFILE_PREFERENCE_REQUEST_BYTES) {
                emit()
                current += item.mutation
                currentPayload = item.singlePayload
                currentByteCount = item.singleByteCount
            } else {
                current += item.mutation
                currentPayload = candidatePayload
                currentByteCount = candidateBytes
            }
        }
    }
    emit()

    return ProfilePreferencePushPlan(chunks = chunks, unsyncable = issues)
}

private class ValidPreferenceMutation(
    val mutation: PreparedProfilePreferenceMutation,
    val singlePayload: PortalSyncPayload,
    val singleByteCount: Int,
)
