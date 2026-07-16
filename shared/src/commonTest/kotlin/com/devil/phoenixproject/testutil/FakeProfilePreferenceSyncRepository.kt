package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.sync.CanonicalProfilePreferenceSection
import com.devil.phoenixproject.data.sync.ProfilePreferenceDirtySnapshot
import com.devil.phoenixproject.data.sync.ProfilePreferencePushOutcome
import com.devil.phoenixproject.data.sync.ProfilePreferenceSyncApplyReport
import com.devil.phoenixproject.data.sync.ProfilePreferenceSyncRepository

internal class FakeProfilePreferenceSyncRepository : ProfilePreferenceSyncRepository {
    var dirtySnapshot = ProfilePreferenceDirtySnapshot(emptyList(), emptyList())
    var snapshotCallCount = 0
    var snapshotFailure: Exception? = null
    var applyPushFailure: Exception? = null
    var applyPullFailure: Exception? = null
    var knownProfileIds: Set<String> = setOf("profile-a")
    var onApplyPulledSections: (() -> Unit)? = null
    var pullApplyCallCount = 0
    val appliedPushOutcomes = mutableListOf<List<ProfilePreferencePushOutcome>>()
    val pulledSectionCalls = mutableListOf<List<CanonicalProfilePreferenceSection>>()
    val appliedPulledSections = mutableListOf<List<CanonicalProfilePreferenceSection>>()

    override suspend fun snapshotDirtySections(): ProfilePreferenceDirtySnapshot {
        snapshotCallCount++
        snapshotFailure?.let { throw it }
        return dirtySnapshot
    }

    override suspend fun applyPushOutcomes(
        outcomes: List<ProfilePreferencePushOutcome>,
    ): ProfilePreferenceSyncApplyReport {
        applyPushFailure?.let { throw it }
        appliedPushOutcomes += outcomes
        return ProfilePreferenceSyncApplyReport(applied = outcomes.size)
    }

    override suspend fun applyPulledSections(
        sections: List<CanonicalProfilePreferenceSection>,
    ): ProfilePreferenceSyncApplyReport {
        pullApplyCallCount++
        pulledSectionCalls += sections
        applyPullFailure?.let { throw it }
        onApplyPulledSections?.invoke()
        val known = sections.filter { it.key.localProfileId in knownProfileIds }
        appliedPulledSections += known
        return ProfilePreferenceSyncApplyReport(
            applied = known.size,
            ignoredUnknownProfile = sections.size - known.size,
        )
    }
}
