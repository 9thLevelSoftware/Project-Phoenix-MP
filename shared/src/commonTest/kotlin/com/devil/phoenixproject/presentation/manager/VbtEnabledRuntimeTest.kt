package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class VbtEnabledRuntimeTest {

    @Test
    fun disabledVbtKeepsBiomechanicsAndPersistenceButSuppressesLiveInterpretation() = runTest {
        val harness = DWSMTestHarness(this)
        val events = mutableListOf<HapticEvent>()
        val eventJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.hapticEvents.toList(events)
        }

        try {
            advanceUntilIdle()
            harness.setActiveProfilePreferences(
                UserPreferences(
                    vbtEnabled = false,
                    velocityLossThresholdPercent = 20,
                    autoEndOnVelocityLoss = true,
                ),
            )
            advanceUntilIdle()

            harness.coordinator._repCount.value = RepCount(
                workingReps = 3,
                totalReps = 3,
                isWarmupComplete = true,
            )
            harness.coordinator._workoutState.value = WorkoutState.Active
            harness.coordinator.currentSessionId = "disabled-vbt-session"

            processRep(harness, repNumber = 1, velocityMmS = 100.0)
            processRep(harness, repNumber = 2, velocityMmS = 70.0)
            processRep(harness, repNumber = 3, velocityMmS = 60.0)
            advanceUntilIdle()

            val latest = assertNotNull(harness.coordinator.biomechanicsEngine.latestRepResult.value)
            assertTrue(latest.velocity.shouldStopSet)
            assertEquals(
                3,
                harness.coordinator.biomechanicsEngine.getSetSummary()?.repResults?.size,
                "The VBT master must not gate raw biomechanics capture.",
            )
            assertTrue(
                events.none { it is HapticEvent.VELOCITY_THRESHOLD_REACHED },
                "Disabled VBT must suppress velocity-threshold feedback.",
            )
            assertTrue(
                events.none { it is HapticEvent.VERBAL_ENCOURAGEMENT },
                "Disabled VBT must suppress verbal feedback.",
            )
            assertIs<WorkoutState.Active>(
                harness.coordinator.workoutState.value,
                "Disabled VBT must not auto-end the active set.",
            )

            harness.activeSessionEngine.handleSetCompletion()
            advanceUntilIdle()

            assertEquals(
                3,
                harness.fakeBiomechanicsRepo.savedBiomechanics["disabled-vbt-session"]?.size,
                "Completing a disabled-VBT set must still persist every biomechanics result.",
            )
        } finally {
            eventJob.cancel()
            harness.cleanup()
        }
    }

    @Test
    fun reEnablingRestoresFeedbackAndAutoEndWithoutChangingSubordinateConfiguration() = runTest {
        val harness = DWSMTestHarness(this)
        val events = mutableListOf<HapticEvent>()
        val eventJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.hapticEvents.toList(events)
        }

        try {
            advanceUntilIdle()
            harness.setActiveProfilePreferences(
                UserPreferences(
                    vbtEnabled = false,
                    velocityLossThresholdPercent = 20,
                    autoEndOnVelocityLoss = true,
                ),
            )
            advanceUntilIdle()
            harness.coordinator._repCount.value = RepCount(isWarmupComplete = true)
            harness.coordinator._workoutState.value = WorkoutState.Active
            harness.coordinator.currentSessionId = "re-enabled-vbt-session"

            processRep(harness, repNumber = 1, velocityMmS = 100.0)
            processRep(harness, repNumber = 2, velocityMmS = 70.0)
            assertTrue(events.none { it is HapticEvent.VELOCITY_THRESHOLD_REACHED })

            harness.settingsManager.setVbtEnabled(true)
            advanceUntilIdle()

            assertEquals(20f, harness.coordinator.biomechanicsEngine.currentVelocityLossThresholdPercent)
            assertTrue(harness.coordinator.autoEndOnVelocityLoss)

            processRep(harness, repNumber = 3, velocityMmS = 60.0)
            assertTrue(events.any { it is HapticEvent.VELOCITY_THRESHOLD_REACHED })
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)

            processRep(harness, repNumber = 4, velocityMmS = 50.0)
            advanceUntilIdle()
            assertTrue(
                harness.coordinator.workoutState.value !is WorkoutState.Active,
                "Two enabled threshold reps must restore the existing auto-end behavior.",
            )
        } finally {
            eventJob.cancel()
            harness.cleanup()
        }
    }

    @Test
    fun readyProfileSwitchPublishesOnlyCoherentVbtTriples() = runTest {
        val harness = DWSMTestHarness(this)
        val profileA = VbtRuntimeSettings(
            enabled = false,
            velocityLossThresholdPercent = 15f,
            autoEndOnVelocityLoss = true,
        )
        val profileB = VbtRuntimeSettings(
            enabled = true,
            velocityLossThresholdPercent = 35f,
            autoEndOnVelocityLoss = false,
        )

        try {
            advanceUntilIdle()
            harness.setActiveProfilePreferences(
                UserPreferences(
                    vbtEnabled = profileA.enabled,
                    velocityLossThresholdPercent = profileA.velocityLossThresholdPercent.toInt(),
                    autoEndOnVelocityLoss = profileA.autoEndOnVelocityLoss,
                ),
            )
            advanceUntilIdle()

            harness.fakeUserProfileRepo.setActiveProfileForTest(id = "profile-b")
            harness.setActiveProfilePreferences(
                UserPreferences(
                    vbtEnabled = profileB.enabled,
                    velocityLossThresholdPercent = profileB.velocityLossThresholdPercent.toInt(),
                    autoEndOnVelocityLoss = profileB.autoEndOnVelocityLoss,
                ),
            )
            advanceUntilIdle()
            harness.fakeUserProfileRepo.setActiveProfileForTest(id = "default")
            advanceUntilIdle()
            assertEquals(profileA, harness.coordinator.vbtRuntimeSettings.value)

            val observed = mutableListOf<VbtRuntimeSettings>()
            val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
                harness.coordinator.vbtRuntimeSettings.toList(observed)
            }
            harness.fakeUserProfileRepo.setActiveProfile("profile-b")
            advanceUntilIdle()

            assertEquals(profileB, harness.coordinator.vbtRuntimeSettings.value)
            assertTrue(
                observed.all { it == profileA || it == profileB },
                "A profile switch must never expose an enabled/threshold/auto-end mix.",
            )
            collector.cancel()
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun localAdultConfirmationNeutralizesRuntimeFeedbackWithoutMutatingProfileIntent() = runTest {
        val harness = DWSMTestHarness(this)
        val events = mutableListOf<HapticEvent>()
        val eventJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.hapticEvents.toList(events)
        }

        try {
            advanceUntilIdle()
            harness.setActiveProfilePreferences(
                UserPreferences(
                    vbtEnabled = true,
                    velocityLossThresholdPercent = 20,
                    beepsEnabled = true,
                    verbalEncouragementEnabled = true,
                    vulgarModeEnabled = true,
                    dominatrixModeUnlocked = true,
                    dominatrixModeActive = true,
                    adultsOnlyConfirmed = false,
                ),
            )
            advanceUntilIdle()
            harness.coordinator._repCount.value = RepCount(isWarmupComplete = true)
            harness.coordinator._workoutState.value = WorkoutState.Active

            processRep(harness, repNumber = 1, velocityMmS = 100.0)
            processRep(harness, repNumber = 2, velocityMmS = 70.0)

            val verbal = events.filterIsInstance<HapticEvent.VERBAL_ENCOURAGEMENT>().single()
            assertEquals(false, verbal.vulgarMode)
            assertEquals(false, verbal.dominatrixMode)

            val ready = assertIs<ActiveProfileContext.Ready>(
                harness.fakeUserProfileRepo.activeProfileContext.value,
            )
            assertTrue(ready.preferences.vbt.value.vulgarModeEnabled)
            assertTrue(ready.preferences.vbt.value.dominatrixModeActive)
        } finally {
            eventJob.cancel()
            harness.cleanup()
        }
    }

    private suspend fun processRep(
        harness: DWSMTestHarness,
        repNumber: Int,
        velocityMmS: Double,
    ) {
        val metrics = List(4) { index ->
            WorkoutMetric(
                timestamp = repNumber * 1_000L + index,
                loadA = 20f,
                loadB = 20f,
                positionA = index * 50f,
                positionB = index * 50f,
                velocityA = velocityMmS,
                velocityB = velocityMmS,
            )
        }
        harness.coordinator.biomechanicsEngine.processRep(
            repNumber = repNumber,
            concentricMetrics = metrics,
            allRepMetrics = metrics,
            timestamp = repNumber * 1_000L,
        )
        harness.activeSessionEngine.evaluateLatestVbtResult()
    }
}

class VbtUiWiringTest {

    @Test
    fun activeWorkoutThreadsTheProfileMasterThroughEveryComposeLayer() {
        val state = source("WorkoutUiState.kt")
        val screen = source("ActiveWorkoutScreen.kt")
        val tab = source("WorkoutTab.kt")
        val hud = source("WorkoutHud.kt")

        assertTrue(state.contains("val vbtEnabled: Boolean = true"))
        val rememberBlock = screen.substringAfter("val workoutUiState = remember(")
            .substringBefore(") {\n        WorkoutUiState(")
        assertTrue(rememberBlock.contains("userPreferences.vbtEnabled"))
        assertTrue(screen.contains("vbtEnabled = userPreferences.vbtEnabled,"))

        val outerTab = tab.substringAfter("fun WorkoutTab(\n    state: WorkoutUiState")
            .substringBefore("@Suppress(\"SENSELESS_COMPARISON\")")
        assertTrue(outerTab.contains("vbtEnabled = state.vbtEnabled,"))
        val innerTab = tab.substringAfter("@Suppress(\"SENSELESS_COMPARISON\")")
        assertTrue(innerTab.contains("vbtEnabled: Boolean = true"))
        assertTrue(innerTab.contains("vbtEnabled = vbtEnabled,"))

        assertTrue(hud.contains("vbtEnabled: Boolean = true"))
        assertTrue(hud.contains("vbtEnabled = vbtEnabled,"))
    }

    @Test
    fun disabledUiHidesOnlyLiveInterpretationAndLeavesHistoryAndRawVelocityVisible() {
        val hud = source("WorkoutHud.kt")
        val history = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/BiomechanicsHistoryCard.kt",
        )
        assertNotNull(history)

        assertVbtStatsStructure(hud.substringAfter("private fun StatsPage("))
        assertTrue(!hud.contains("if (vbtEnabled && latestBiomechanicsResult != null)"))
        assertTrue(!history.contains("vbtEnabled"), "Historical biomechanics must remain ungated.")
    }

    @Test
    fun statsSourceCheckerRejectsRawMetricsInsideMasterGateAndEscapedRepsLeft() {
        val malformed = """
            val zColor = if (vbtEnabled) { activeColor } else { neutralColor }
            StatColumn(label = "MCV")
            if (vbtEnabled) {
                StatColumn(label = "Zone")
                StatColumn(label = "Peak")
            }
            val vloss = result.velocityLossPercent
            if (vbtEnabled && vloss != null) {
                VelocityLossIndicator()
            }
            StatColumn(label = "Est. Reps Left")
        """.trimIndent()

        assertFailsWith<AssertionError> { assertVbtStatsStructure(malformed) }
    }

    private fun assertVbtStatsStructure(statsSource: String) {
        assertTrue(statsSource.contains("val zColor = if (vbtEnabled)"))

        val zoneGate = blocksFor(statsSource, "if (vbtEnabled) {")
            .singleOrNull { it.contains("label = \"Zone\"") }
        assertNotNull(zoneGate, "Zone must have one explicit VBT gate.")
        assertTrue(zoneGate.contains("label = \"Zone\""), "Zone must be inside its VBT gate.")
        assertTrue(!zoneGate.contains("label = \"MCV\""), "Raw MCV must remain outside the VBT gate.")
        assertTrue(!zoneGate.contains("label = \"Peak\""), "Raw Peak must remain outside the VBT gate.")

        val lossHeader = "if (vbtEnabled && vloss != null) {"
        val lossGateStart = statsSource.indexOf(lossHeader)
        assertTrue(lossGateStart >= 0, "Velocity-loss interpretation must have a master gate.")
        val lossGate = blockFor(statsSource, lossHeader)
        assertTrue(lossGate.contains("VelocityLossIndicator("), "Velocity loss must stay inside its gate.")
        assertTrue(lossGate.contains("label = \"Est. Reps Left\""), "Estimated reps must stay inside the loss gate.")
        assertTrue(!lossGate.contains("label = \"MCV\""), "Raw MCV must not move into the loss gate.")
        assertTrue(!lossGate.contains("label = \"Peak\""), "Raw Peak must not move into the loss gate.")

        val mcvIndex = statsSource.indexOf("label = \"MCV\"")
        val peakIndex = statsSource.indexOf("label = \"Peak\"")
        assertTrue(mcvIndex in 0 until lossGateStart, "Raw MCV must render before the loss gate.")
        assertTrue(peakIndex in 0 until lossGateStart, "Raw Peak must render before the loss gate.")
    }

    private fun blockFor(source: String, header: String): String {
        val headerStart = source.indexOf(header)
        assertTrue(headerStart >= 0, "Missing source gate: $header")
        return blockFor(source, header, headerStart)
    }

    private fun blocksFor(source: String, header: String): List<String> {
        val blocks = mutableListOf<String>()
        var searchFrom = 0
        while (true) {
            val headerStart = source.indexOf(header, startIndex = searchFrom)
            if (headerStart < 0) return blocks
            val block = blockFor(source, header, headerStart)
            blocks += block
            searchFrom = headerStart + block.length
        }
    }

    private fun blockFor(source: String, header: String, headerStart: Int): String {
        val openingBrace = source.indexOf('{', startIndex = headerStart)
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(headerStart, index + 1)
                }
            }
        }
        throw AssertionError("Unclosed source gate: $header")
    }

    private fun source(fileName: String): String {
        val source = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/$fileName",
        )
        assertNotNull(source, "Could not locate $fileName")
        return source
    }
}
