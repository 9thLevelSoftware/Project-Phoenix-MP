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
 * Issue #1403 — merge audit remediation: PRODUCER-LEVEL integration evidence for
 * the PR-to-badge celebration handoff.
 *
 * This test is producer-level only, NOT screen runtime coverage: it drives the
 * real [GamificationManager] event flows (the exact objects `MainViewModel`
 * exposes and the screen collects) and asserts the PR-before-badge emission
 * order the screen's queue rule depends on. It does not execute
 * `ActiveWorkoutScreen`'s collectors or state gating, dismiss either dialog, or
 * verify persistence through a screen callback. Screen-consumer behavior — queue
 * gating, PR-dismissal handoff, invalid-metrics deferral, and dismissal
 * persistence — is covered by [Issue1403BadgeQueueScreenConsumerTest], which
 * drives the production-used [BadgeCelebrationQueueState] seam. Direct dialog
 * rendering/ID behavior is covered by
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
    fun prEventPrecedesBadgeQueue_atTheManagerFlowProducerLevel() = runTest {
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

            // One ordered event log across both producer flows. The screen's
            // queue rule (PR dialog first, badges queued behind it) DEPENDS on
            // the PR event being emitted before the badge batch; this test pins
            // that producer-side ordering only.
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

            assertTrue(ordered.isNotEmpty(), "the manager must emit celebration events")
            val prIndex = ordered.indexOf("pr")
            val badgesIndex = ordered.indexOf("badges")
            assertTrue(prIndex >= 0, "a PR event must be emitted on the PR celebration flow")
            assertTrue(badgesIndex >= 0, "a badge batch must be emitted on the badge earned flow")
            assertTrue(
                prIndex < badgesIndex,
                "the producer must emit the PR event before the badge batch so the " +
                    "screen's queue rule can show the PR dialog first (ordered=$ordered)",
            )
            assertTrue(
                badgeIds == listOf(listOf(badge.id)),
                "the badge flow must carry the exact celebration IDs, got $badgeIds",
            )

            prJob.cancel()
            badgeJob.cancel()
        } finally {
            managerScope.cancel()
        }
    }

    /**
     * Static source pin — NOT runtime coverage. After the Issue #1403 seam
     * extraction, the queue gating and dismissal-to-persistence wiring live in
     * [BadgeCelebrationQueueState]; this asserts only that the production source
     * declares that wiring and that the screen delegates to the seam. Runtime
     * screen-consumer behavior is covered by
     * [Issue1403BadgeQueueScreenConsumerTest].
     */
    @Test
    fun screenWiring_routesDismissalIdsToCelebrationPersistence() {
        val seam = readProjectFile(
            "src/phoneMain/kotlin/com/devil/phoenixproject/presentation/components/BadgeCelebrationQueueState.kt",
        )
        assertNotNull(seam, "Could not read BadgeCelebrationQueueState.kt for the handoff wiring check.")
        val seamSrc = seam!!

        val prCollectIdx = seamSrc.indexOf("prEvents.collect")
        val badgeCollectIdx = seamSrc.indexOf("badgeEvents.collect")
        val gateIdx = seamSrc.indexOf("queuedBadges.isNotEmpty() && prCelebrationEvent == null")
        val persistIdx = seamSrc.indexOf("gamificationRepository.markBadgesCelebrated(badgeIds, profileId)")

        assertTrue(prCollectIdx >= 0, "the seam must collect the PR celebration flow")
        assertTrue(badgeCollectIdx >= 0, "the seam must collect the badge earned flow")
        assertTrue(
            gateIdx >= 0,
            "the badge dialog must be gated behind the PR event (badges stay queued while a PR celebration is showing)",
        )
        assertTrue(
            persistIdx > gateIdx,
            "the dismissal IDs must route to markBadgesCelebrated(badgeIds, profileId)",
        )

        val screen = readProjectFile(
            "src/phoneMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt",
        )
        assertNotNull(screen, "Could not read ActiveWorkoutScreen.kt for the seam wiring check.")
        val screenSrc = screen!!

        assertTrue(
            screenSrc.contains("BadgeCelebrationQueueState("),
            "the screen must construct the celebration queue seam",
        )
        assertTrue(
            screenSrc.contains("celebrationQueue.collectCelebrationEvents("),
            "the screen must feed the seam the manager celebration flows",
        )
        assertTrue(
            screenSrc.contains("celebrationQueue.badgeDialogVisible"),
            "the screen must gate the badge dialog on the seam state",
        )
        assertTrue(
            screenSrc.contains("celebrationQueue.onPrDialogDismissed()"),
            "the screen must route PR dialog dismissal into the seam",
        )
        assertTrue(
            screenSrc.contains("celebrationQueue.onBadgeCelebrationDismissed(badgeIds)"),
            "the screen must route the dialog's dismissal IDs into the seam's persistence handoff",
        )
    }
}
