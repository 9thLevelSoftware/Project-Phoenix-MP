package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.Badge
import com.devil.phoenixproject.domain.model.PRCelebrationEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Issue #1403: production-used screen queue/presentation state seam for the
 * PR-to-badge celebration handoff hosted by `ActiveWorkoutScreen`.
 *
 * Owns exactly the logic the screen used to inline:
 * - PR celebration state fed from the PR celebration flow.
 * - A queued badge batch fed from the badge earned flow.
 * - Presentation gating: the badge dialog shows only when the queue is non-empty
 *   and no PR celebration is up, so the PR dialog always leads and badges stay
 *   queued behind it (no stacked dialogs, no double sounds).
 * - Dismissal handoff: dismissing the PR dialog reveals the queued badge dialog;
 *   badge-dialog celebration/dismissal persists exactly the dismissed badge IDs
 *   once per queued batch, with the active profile id ("default" when there is
 *   no active profile), then empties the queue.
 *
 * State mirrors the screen's Compose state ([mutableStateOf]) so reads recompose.
 */
class BadgeCelebrationQueueState(
    private val gamificationRepository: GamificationRepository,
    private val userProfileRepository: UserProfileRepository,
    private val scope: CoroutineScope,
) {

    /** PR celebration being shown, if any. The PR dialog renders while this is non-null. */
    var prCelebrationEvent by mutableStateOf<PRCelebrationEvent?>(null)
        private set

    /** Earned badges queued behind the PR dialog. */
    var queuedBadges by mutableStateOf<List<Badge>>(emptyList())
        private set

    /** The badge dialog shows only when the queue is non-empty and no PR dialog is up. */
    val badgeDialogVisible: Boolean
        get() = queuedBadges.isNotEmpty() && prCelebrationEvent == null

    /** Collects the two celebration flows into this queue (the screen's collectors). */
    fun collectCelebrationEvents(
        prEvents: Flow<PRCelebrationEvent>,
        badgeEvents: Flow<List<Badge>>,
    ) {
        scope.launch { prEvents.collect { event -> prCelebrationEvent = event } }
        scope.launch { badgeEvents.collect { badges -> queuedBadges = badges } }
    }

    /** PR dialog dismissal: clears the PR event so the queued badge dialog is revealed. */
    fun onPrDialogDismissed() {
        prCelebrationEvent = null
    }

    /**
     * Badge dialog celebration handoff — [BatchedBadgeCelebrationDialog]'s
     * `onMarkAllCelebrated`, which the dialog invokes with the dismissed badge IDs
     * before its `onDismiss`. Persists exactly once per queued batch: a call with
     * an already-cleared queue is a no-op, so duplicate dismissal callbacks can
     * never double-persist.
     */
    fun onBadgeCelebrationDismissed(badgeIds: List<String>) {
        if (queuedBadges.isEmpty()) return
        queuedBadges = emptyList()
        scope.launch {
            val profileId = userProfileRepository.activeProfile.value?.id ?: "default"
            gamificationRepository.markBadgesCelebrated(badgeIds, profileId)
        }
    }

    /** Badge dialog dismissal — [BatchedBadgeCelebrationDialog]'s `onDismiss`: empties the queue. */
    fun onBadgeDialogDismissed() {
        queuedBadges = emptyList()
    }
}
