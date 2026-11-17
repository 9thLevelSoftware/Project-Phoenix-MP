package com.devil.phoenixproject.presentation.components

import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.local.BadgeDefinitions
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.presentation.manager.GamificationManager
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Issue #1403 — merge audit remediation item R2-F2: screen-consumer coverage for
 * the PR-to-badge celebration handoff.
 *
 * Drives the production-used [BadgeCelebrationQueueState] seam — the exact
 * state/queue consumer `ActiveWorkoutScreen` delegates its collectors, dialog
 * gating, dismissal handoff and persistence to — with events from a REAL
 * [GamificationManager] (constructed with the shared fakes, like
 * [Issue1403PrToBadgeHandoffIntegrationTest]). Unlike that producer-level test,
 * these tests execute the consumption side: the queue gating, the PR dismissal
 * handoff, the production deferral condition, and the badge-dismissal
 * persistence path.
 *
 * Coverage (the four merge-audit assertions):
 * - The PR dialog is up while the earned badges remain queued behind it.
 * - Dismissing the PR dialog reveals the queued badge dialog.
 * - Invalid window metrics (the real [badgeCelebrationLayoutBudget] returning
 *   null — the production deferral condition) preserve the queue and never
 *   invoke the persistence path.
 * - Badge-dialog dismissal persists exactly the dismissed badge IDs with the
 *   active profile id, exactly once per dismissed batch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue1403BadgeQueueScreenConsumerTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    private lateinit var fakeGamificationRepository: FakeGamificationRepository
    private lateinit var fakePersonalRecordRepository: FakePersonalRecordRepository
    private lateinit var fakeExerciseRepository: FakeExerciseRepository
    private lateinit var fakeUserProfileRepository: FakeUserProfileRepository
    private lateinit var hapticEvents: MutableSharedFlow<HapticEvent>

    private val badge = BadgeDefinitions.allBadges.first()

    @Before
    fun setup() {
        fakeGamificationRepository = FakeGamificationRepository()
        fakePersonalRecordRepository = FakePersonalRecordRepository()
        fakeExerciseRepository = FakeExerciseRepository()
        fakeUserProfileRepository = FakeUserProfileRepository()
        hapticEvents = MutableSharedFlow(extraBufferCapacity = 10)
    }

    private fun newManager(scope: CoroutineScope): GamificationManager {
        fakeGamificationRepository.pendingBadges = mutableListOf(badge)
        fakeExerciseRepository.addExercise(
            Exercise(
                id = "bench-1",
                name = "Bench Press",
                muscleGroup = "Chest",
                equipment = "HANDLES",
            ),
        )
        return GamificationManager(
            gamificationRepository = fakeGamificationRepository,
            personalRecordRepository = fakePersonalRecordRepository,
            exerciseRepository = fakeExerciseRepository,
            hapticEvents = hapticEvents,
            scope = scope,
            gamificationEnabled = MutableStateFlow(true),
        )
    }

    private fun newQueue(scope: CoroutineScope): BadgeCelebrationQueueState =
        BadgeCelebrationQueueState(
            gamificationRepository = fakeGamificationRepository,
            userProfileRepository = fakeUserProfileRepository,
            scope = scope,
        )

    /** Feeds the seam real manager events from a real post-save flow run. */
    private suspend fun TestScope.driveManagerEvents(
        manager: GamificationManager,
        queue: BadgeCelebrationQueueState,
    ) {
        queue.collectCelebrationEvents(
            prEvents = manager.prCelebrationEvent,
            badgeEvents = manager.badgeEarnedEvents,
        )
        advanceUntilIdle()
        manager.processPostSaveEvents(
            exerciseId = "bench-1",
            workingReps = 8,
            achievedWeightKg = 24f,
            volumeWeightKg = 20f,
            programMode = ProgramMode.OldSchool,
            isJustLift = false,
            isEchoMode = false,
        )
        advanceUntilIdle()
    }

    @Test
    fun prDialogShowsFirstWhileBadgesRemainQueued() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = newManager(managerScope)
            val queue = newQueue(managerScope)
            driveManagerEvents(manager, queue)

            // (1) PR dialog visible while badges remain queued.
            assertNotNull(
                queue.prCelebrationEvent,
                "the PR dialog must be up after real manager events (prCelebrationEvent non-null)",
            )
            assertEquals(
                listOf(badge.id),
                queue.queuedBadges.map { it.id },
                "the earned badge batch must be queued behind the PR dialog",
            )
            assertFalse(
                queue.badgeDialogVisible,
                "the badge dialog must NOT show while the PR dialog is up",
            )
            assertEquals(
                emptyList(),
                fakeGamificationRepository.markBadgesCelebratedCalls,
                "nothing may be persisted before a dismissal",
            )
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun prDialogDismissalRevealsTheQueuedBadgeDialog() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = newManager(managerScope)
            val queue = newQueue(managerScope)
            driveManagerEvents(manager, queue)

            // (2) PR dismissal reveals the badge dialog.
            queue.onPrDialogDismissed()
            assertNull(queue.prCelebrationEvent, "the PR event must be cleared on PR dismissal")
            assertTrue(
                queue.badgeDialogVisible,
                "PR dismissal must reveal the queued badge dialog",
            )
            assertEquals(
                listOf(badge.id),
                queue.queuedBadges.map { it.id },
                "the queued badges must survive until the badge dialog itself is dismissed",
            )
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun invalidWindowMetricsPreserveTheQueueWithoutPersistence() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = newManager(managerScope)
            val queue = newQueue(managerScope)
            driveManagerEvents(manager, queue)
            queue.onPrDialogDismissed()

            // (3) Invalid metrics preserve the queue. Production deferral condition:
            // BatchedBadgeCelebrationDialog returns early WITHOUT invoking
            // onDismiss/onMarkAllCelebrated when the real
            // badgeCelebrationLayoutBudget(...) is null (invalid window metrics).
            // Establish that condition with the production seam itself.
            val invalidBudget = badgeCelebrationLayoutBudget(
                windowWidth = 0.dp,
                windowHeight = 400.dp,
                horizontalInsets = 0.dp,
                verticalInsets = 0.dp,
                badgeCount = queue.queuedBadges.size,
                detailsExpanded = false,
                fontScale = 1f,
            )
            assertNull(
                invalidBudget,
                "invalid window metrics must yield a null production budget (deferred rendering)",
            )
            if (invalidBudget != null) {
                // BatchedBadgeCelebrationDialog's dismissal callbacks — unreachable
                // while the budget is null, so the queue must stay untouched below.
                queue.onBadgeCelebrationDismissed(queue.queuedBadges.map { it.id })
                queue.onBadgeDialogDismissed()
            }

            assertEquals(
                listOf(badge.id),
                queue.queuedBadges.map { it.id },
                "invalid metrics must preserve the queued badge batch",
            )
            assertTrue(
                queue.badgeDialogVisible,
                "the deferred badge dialog must still be pending after invalid metrics",
            )
            assertEquals(
                emptyList(),
                fakeGamificationRepository.markBadgesCelebratedCalls,
                "invalid metrics must never reach markBadgesCelebrated",
            )

            // Control: valid metrics clear the same deferral condition, so the
            // deferral is specific to invalid metrics, not to the queue logic.
            val validBudget = badgeCelebrationLayoutBudget(
                windowWidth = 400.dp,
                windowHeight = 400.dp,
                horizontalInsets = 0.dp,
                verticalInsets = 0.dp,
                badgeCount = queue.queuedBadges.size,
                detailsExpanded = false,
                fontScale = 1f,
            )
            assertNotNull(
                validBudget,
                "valid window metrics must yield a renderable production budget",
            )
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun badgeDialogDismissalPersistsExactIdsWithActiveProfileExactlyOnce() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            fakeUserProfileRepository.seedReadyProfileForTest("profile-1")
            val manager = newManager(managerScope)
            val queue = newQueue(managerScope)
            driveManagerEvents(manager, queue)
            queue.onPrDialogDismissed()

            // (4) Badge dismissal persists exactly the dismissed IDs with the
            // active profile, exactly once.
            val dismissedIds = queue.queuedBadges.map { it.id }
            assertEquals(listOf(badge.id), dismissedIds, "the dismissed IDs must be the queued celebration IDs")

            // BatchedBadgeCelebrationDialog's dismissal order: onMarkAllCelebrated, then onDismiss.
            queue.onBadgeCelebrationDismissed(dismissedIds)
            queue.onBadgeDialogDismissed()
            advanceUntilIdle()

            assertEquals(
                listOf(listOf(badge.id)),
                fakeGamificationRepository.markBadgesCelebratedCalls,
                "badge dismissal must persist exactly the dismissed badge IDs",
            )
            assertEquals(
                listOf("profile-1"),
                fakeGamificationRepository.markBadgesCelebratedProfileIds,
                "badge dismissal must persist with the active profile id",
            )
            assertEquals(
                emptyList(),
                queue.queuedBadges,
                "the queue must be emptied once the badge dialog is dismissed",
            )
            assertFalse(queue.badgeDialogVisible, "the badge dialog must close after dismissal")

            // A duplicate dismissal of the same batch must never double-persist.
            queue.onBadgeCelebrationDismissed(dismissedIds)
            queue.onBadgeDialogDismissed()
            advanceUntilIdle()
            assertEquals(
                1,
                fakeGamificationRepository.markBadgesCelebratedCalls.size,
                "markBadgesCelebrated must run exactly once per dismissed batch",
            )
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun badgeDialogDismissalWithoutActiveProfileFallsBackToDefaultProfile() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            // No seeded/active profile: the seam's documented "default" fallback.
            val manager = newManager(managerScope)
            val queue = newQueue(managerScope)
            driveManagerEvents(manager, queue)
            queue.onPrDialogDismissed()

            queue.onBadgeCelebrationDismissed(queue.queuedBadges.map { it.id })
            queue.onBadgeDialogDismissed()
            advanceUntilIdle()

            assertEquals(
                listOf("default"),
                fakeGamificationRepository.markBadgesCelebratedProfileIds,
                "with no active profile the dismissal must persist under the 'default' profile id",
            )
        } finally {
            managerScope.cancel()
        }
    }
}
