package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.data.local.BadgeDefinitions
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.presentation.manager.GamificationManager
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Issue #1403 — merge audit remediation item 3: PR-to-badge queue handoff through
 * the real screen state entry point.
 *
 * This is INTEGRATION evidence, not a direct dialog test: it drives the real
 * [GamificationManager] event flows (the exact objects `MainViewModel` exposes and
 * `ActiveWorkoutScreen` collects to build its celebration queue), asserts the
 * PR-before-badge ordering that the screen's queue rule depends on, and pins the
 * screen wiring that routes the dialog's dismissal IDs into
 * `gamificationRepository.markBadgesCelebrated`. Direct dialog rendering/ID
 * behavior is covered separately by
 * [Issue1403BadgeDialogUnboundedWidthRuntimeTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue1403PrToBadgeHandoffIntegrationTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    private lateinit var fakeGamificationRepository: FakeGamificationRepository
    private lateinit var fakePersonalRecordRepository: FakePersonalRecordRepository
    private lateinit var fakeExerciseRepository: FakeExerciseRepository
    private lateinit var hapticEvents: MutableSharedFlow<HapticEvent>

    @Before
    fun setup() {
        fakeGamificationRepository = FakeGamificationRepository()
        fakePersonalRecordRepository = FakePersonalRecordRepository()
        fakeExerciseRepository = FakeExerciseRepository()
        hapticEvents = MutableSharedFlow(extraBufferCapacity = 10)
    }

    @Test
    fun prEventPrecedesBadgeQueue_atTheRealScreenStateEntryPoint() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = GamificationManager(
                gamificationRepository = fakeGamificationRepository,
                personalRecordRepository = fakePersonalRecordRepository,
                exerciseRepository = fakeExerciseRepository,
                hapticEvents = hapticEvents,
                scope = managerScope,
                gamificationEnabled = MutableStateFlow(true),
            )
            val badge = BadgeDefinitions.allBadges.first()
            fakeGamificationRepository.pendingBadges = mutableListOf(badge)
            fakeExerciseRepository.addExercise(
                Exercise(
                    id = "bench-1",
                    name = "Bench Press",
                    muscleGroup = "Chest",
                    equipment = "HANDLES",
                ),
            )

            // One ordered event log across both flows — the screen collects both
            // and its queue rule (PR dialog first, badges queued behind it) depends
            // on the PR event arriving before the badge batch.
            val ordered = mutableListOf<String>()
            val badgeIds = mutableListOf<List<String>>()
            val prJob = launch { manager.prCelebrationEvent.collect { ordered += "pr" } }
            val badgeJob = launch {
                manager.badgeEarnedEvents.collect { badges ->
                    ordered += "badges"
                    badgeIds += badges.map { it.id }
                }
            }
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

            assertTrue(ordered.isNotEmpty(), "the entry point must emit celebration events")
            val prIndex = ordered.indexOf("pr")
            val badgesIndex = ordered.indexOf("badges")
            assertTrue(prIndex >= 0, "a PR event must reach the screen's PR queue")
            assertTrue(badgesIndex >= 0, "a badge batch must reach the screen's badge queue")
            assertTrue(
                prIndex < badgesIndex,
                "PR must be queued before badges so the screen shows the PR dialog first " +
                    "(ordered=$ordered)",
            )
            assertTrue(
                badgeIds == listOf(listOf(badge.id)),
                "the badge queue must carry the exact celebration IDs, got $badgeIds",
            )

            prJob.cancel()
            badgeJob.cancel()
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun screenWiring_routesDismissalIdsToCelebrationPersistence() {
        val screen = readProjectFile(
            "src/phoneMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt",
        )
        assertNotNull(screen, "Could not read ActiveWorkoutScreen.kt for the handoff wiring check.")
        val src = screen!!

        val prCollectIdx = src.indexOf("viewModel.prCelebrationEvent.collect")
        val badgeCollectIdx = src.indexOf("viewModel.badgeEarnedEvents.collect")
        val gateIdx = src.indexOf("if (earnedBadges.isNotEmpty() && prCelebrationEvent == null)")
        val dialogIdx = src.indexOf("BatchedBadgeCelebrationDialog(")
        val callbackIdx = src.indexOf("onMarkAllCelebrated = { badgeIds ->")
        val persistIdx = src.indexOf("gamificationRepository.markBadgesCelebrated(badgeIds, profileId)")

        assertTrue(prCollectIdx >= 0, "the screen must collect the PR celebration flow")
        assertTrue(badgeCollectIdx >= 0, "the screen must collect the badge earned flow")
        assertTrue(
            gateIdx > badgeCollectIdx,
            "the badge queue must be gated after collection",
        )
        assertTrue(
            src.contains("prCelebrationEvent == null"),
            "badges must stay queued while a PR celebration is showing (PR-to-badge order)",
        )
        assertTrue(
            dialogIdx > gateIdx,
            "the batched dialog must be gated behind the queue rule",
        )
        assertTrue(
            callbackIdx > dialogIdx && persistIdx > callbackIdx,
            "the dialog's dismissal IDs must route to markBadgesCelebrated(badgeIds, profileId)",
        )
    }
}
