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
    val valid = mutableListOf<PreparedProfilePreferenceMutation>()
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

        val oneElement = encodePortalSyncPayload(preferencePayload(listOf(mutation)))
        val sectionBytes = oneElement.preferenceElementByteCount(
            oneElement.preferenceElementSpans.single(),
        )
        val reason = when {
            sectionBytes > MAX_PROFILE_PREFERENCE_SECTION_BYTES ->
                ProfilePreferenceSyncIssueReason.SECTION_TOO_LARGE
            oneElement.rawBytes.size > MAX_PROFILE_PREFERENCE_REQUEST_BYTES ->
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
        valid += mutation
    }

    val chunks = mutableListOf<ProfilePreferencePushChunk>()
    var current = mutableListOf<PreparedProfilePreferenceMutation>()
    fun emit() {
        if (current.isEmpty()) return
        val payload = preferencePayload(current)
        val encoded = encodePortalSyncPayload(payload)
        check(encoded.rawBytes.size <= MAX_PROFILE_PREFERENCE_REQUEST_BYTES) {
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
    }

    valid.forEach { mutation ->
        val candidate = current + mutation
        val bytes = encodePortalSyncPayload(preferencePayload(candidate)).rawBytes.size
        if (bytes > MAX_PROFILE_PREFERENCE_REQUEST_BYTES) emit()
        current += mutation
    }
    emit()

    check(
        chunks.all {
            encodePortalSyncPayload(it.payload).rawBytes.size <=
                MAX_PROFILE_PREFERENCE_REQUEST_BYTES
        },
    ) {
        "PROFILE_PREFERENCE_PLANNER_OVERSIZED_REQUEST"
    }
    return ProfilePreferencePushPlan(chunks = chunks, unsyncable = issues)
}
