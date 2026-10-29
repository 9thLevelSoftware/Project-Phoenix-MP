package com.devil.phoenixproject.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.data.ble.PolarH10BleManager
import com.devil.phoenixproject.data.ble.PolarSessionSummary
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.WorkoutSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PolarSessionAnalyticsCardRuntimeTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun sessionWithoutPolarSummaryDoesNotReadSampleTables() {
        val session = WorkoutSession(id = "without-polar")
        val repository = mockk<WorkoutRepository>(relaxed = true)
        coEvery { repository.getSession(session.id) } returns session
        val manager = mockk<PolarH10BleManager>(relaxed = true)
        every { manager.connectionState } returns MutableStateFlow(ConnectionState.Connected("Polar", "sensor"))
        every { manager.isCooldownActive } returns MutableStateFlow(true)
        every { manager.cooldownSecondsRemaining } returns MutableStateFlow(240)
        every { manager.cooldownStartHeartRate } returns MutableStateFlow(100)
        every { manager.latestSessionSummary } returns MutableStateFlow(PolarSessionSummary(sessionId = "other", hrr60Bpm = 20))
        every { manager.hrMeasurement } returns MutableStateFlow(null)
        every { manager.activeCooldownSessionId } returns MutableStateFlow("other")
        every { manager.lastRecordedSessionId } returns MutableStateFlow("other")
        every { manager.currentRecordingSessionId } returns "other"
        every { manager.getSessionSummary(session.id) } returns null

        rule.setContent {
            MaterialTheme {
                PolarSessionAnalyticsCard(session = session, showCooldown = false, workoutRepository = repository, polarBleManager = manager)
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Cardiac & Biomechanics Summary").assertDoesNotExist()
        coVerify(exactly = 0) { repository.getPolarBeatSamplesBySession(any()) }
        coVerify(exactly = 0) { repository.getPolarEcgSegmentsBySession(any()) }
        coVerify(exactly = 0) { repository.getMetricSamplesBySession(any()) }
    }

    @Test
    fun historicalRecoveryOnlySessionShowsStoredRecoveryWithoutCooldownControls() {
        val session = WorkoutSession(id = "recovery-only", hrr60Bpm = 17)
        val repository = mockk<WorkoutRepository>(relaxed = true)
        coEvery { repository.getSession(session.id) } returns session

        rule.setContent {
            MaterialTheme {
                PolarSessionAnalyticsCard(session = session, showCooldown = false, workoutRepository = repository)
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("1-Min Recovery (HRR-60)").assertExists()
        rule.onNodeWithText("Start Cooldown").assertDoesNotExist()
        rule.onNodeWithText("Restart Cooldown").assertDoesNotExist()
    }
}
