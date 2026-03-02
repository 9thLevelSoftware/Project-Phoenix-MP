package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.local.BadgeDefinitions
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GamificationManagerTest {

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
    fun `processPostSaveEvents emits PR and badge events while avoiding badge sound stacking`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = GamificationManager(
                gamificationRepository = fakeGamificationRepository,
                personalRecordRepository = fakePersonalRecordRepository,
                exerciseRepository = fakeExerciseRepository,
                hapticEvents = hapticEvents,
                scope = managerScope,
                gamificationEnabled = MutableStateFlow(true)
            )
            val badge = BadgeDefinitions.allBadges.first()
            fakeGamificationRepository.pendingBadges = mutableListOf(badge)
            fakeExerciseRepository.addExercise(
                Exercise(
                    id = "bench-1",
                    name = "Bench Press",
                    muscleGroup = "Chest",
                    equipment = "HANDLES"
                )
            )

            val prEvents = mutableListOf<com.devil.phoenixproject.domain.model.PRCelebrationEvent>()
            val badgeEvents = mutableListOf<List<com.devil.phoenixproject.domain.model.Badge>>()
            val emittedHaptics = mutableListOf<HapticEvent>()

            val prJob = launch { manager.prCelebrationEvent.collect { prEvents += it } }
            val badgeJob = launch { manager.badgeEarnedEvents.collect { badgeEvents += it } }
            val hapticJob = launch { hapticEvents.collect { emittedHaptics += it } }
            advanceUntilIdle()

            val hasCelebrationSound = manager.processPostSaveEvents(
                exerciseId = "bench-1",
                workingReps = 8,
                measuredWeightKg = 20f,
                programMode = ProgramMode.OldSchool,
                isJustLift = false,
                isEchoMode = false
            )
            advanceUntilIdle()

            assertTrue(hasCelebrationSound)
            assertEquals(1, fakePersonalRecordRepository.updateCalls.size)
            assertEquals(1, prEvents.size)
            assertEquals(1, badgeEvents.size)

            val prEvent = prEvents.single()
            assertEquals("Bench Press", prEvent.exerciseName)
            assertEquals(setOf(PRType.MAX_WEIGHT, PRType.MAX_VOLUME), prEvent.brokenPRTypes.toSet())

            val awardedBadges = badgeEvents.single()
            assertEquals(1, awardedBadges.size)
            assertEquals(badge.id, awardedBadges.single().id)
            assertTrue(emittedHaptics.isEmpty())

            prJob.cancel()
            badgeJob.cancel()
            hapticJob.cancel()
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `processPostSaveEvents emits badge haptic when no PR celebration occurs`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = GamificationManager(
                gamificationRepository = fakeGamificationRepository,
                personalRecordRepository = fakePersonalRecordRepository,
                exerciseRepository = fakeExerciseRepository,
                hapticEvents = hapticEvents,
                scope = managerScope,
                gamificationEnabled = MutableStateFlow(true)
            )
            val badge = BadgeDefinitions.allBadges.first()
            fakeGamificationRepository.pendingBadges = mutableListOf(badge)

            val prEvents = mutableListOf<com.devil.phoenixproject.domain.model.PRCelebrationEvent>()
            val badgeEvents = mutableListOf<List<com.devil.phoenixproject.domain.model.Badge>>()
            val emittedHaptics = mutableListOf<HapticEvent>()

            val prJob = launch { manager.prCelebrationEvent.collect { prEvents += it } }
            val badgeJob = launch { manager.badgeEarnedEvents.collect { badgeEvents += it } }
            val hapticJob = launch { hapticEvents.collect { emittedHaptics += it } }
            advanceUntilIdle()

            val hasCelebrationSound = manager.processPostSaveEvents(
                exerciseId = "bench-1",
                workingReps = 8,
                measuredWeightKg = 20f,
                programMode = ProgramMode.OldSchool,
                isJustLift = true,
                isEchoMode = false
            )
            advanceUntilIdle()

            assertFalse(hasCelebrationSound)
            assertEquals(0, fakePersonalRecordRepository.updateCalls.size)
            assertTrue(prEvents.isEmpty())
            assertEquals(1, badgeEvents.size)
            assertEquals(badge.id, badgeEvents.single().single().id)
            assertEquals(1, emittedHaptics.size)
            assertEquals(HapticEvent.BADGE_EARNED, emittedHaptics.first())

            prJob.cancel()
            badgeJob.cancel()
            hapticJob.cancel()
        } finally {
            managerScope.cancel()
        }
    }
}
