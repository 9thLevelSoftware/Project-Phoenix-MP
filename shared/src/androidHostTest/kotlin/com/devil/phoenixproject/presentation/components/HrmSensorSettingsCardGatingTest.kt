package com.devil.phoenixproject.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.devil.phoenixproject.data.ble.ContactReportState
import com.devil.phoenixproject.data.ble.HrmBleManager
import com.devil.phoenixproject.data.ble.HrmCapabilities
import com.devil.phoenixproject.data.ble.HrmCapabilityState
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.WorkoutSession
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Capability-gated UI + copy contract tests (T27, T36) for the HRM settings card
 * and the session heart-rate card (SD2/SD6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HrmSensorSettingsCardGatingTest {
    @get:Rule
    val rule = createComposeRule()

    private fun managerWith(
        connectionState: ConnectionState = ConnectionState.Connected("Garmin Forerunner 245", "AA:BB:CC:DD:EE:FF"),
        caps: HrmCapabilities = HrmCapabilities(),
        battery: Int? = 87,
        rawEcgEnabled: Boolean = false,
    ): HrmBleManager {
        val manager = mockk<HrmBleManager>(relaxed = true)
        every { manager.connectionState } returns MutableStateFlow(connectionState)
        every { manager.scannedDevices } returns MutableStateFlow(emptyList())
        every { manager.batteryLevel } returns MutableStateFlow(battery)
        every { manager.hrMeasurement } returns MutableStateFlow(null)
        every { manager.capabilities } returns MutableStateFlow(caps)
        every { manager.rememberedAddress } returns MutableStateFlow(null)
        every { manager.rememberedName } returns MutableStateFlow(null)
        every { manager.isAutoReconnectEnabled } returns MutableStateFlow(true)
        every { manager.isRawEcgStorageEnabled } returns MutableStateFlow(rawEcgEnabled)
        every { manager.torsoPitchDeg } returns MutableStateFlow(null)
        every { manager.torsoRollDeg } returns MutableStateFlow(null)
        every { manager.dynamicAccMg } returns MutableStateFlow(null)
        return manager
    }

    private fun showCard(manager: HrmBleManager) {
        rule.setContent {
            MaterialTheme {
                HrmSensorSettingsCard(hrmBleManager = manager)
            }
        }
        rule.waitForIdle()
    }

    // ==================== T36 — PMD gating ====================

    @Test
    fun t36GarminWithoutPmdHidesEcgTorsoAndTareControls() {
        showCard(
            managerWith(
                caps = HrmCapabilities(
                    standardHr = HrmCapabilityState.CONFIRMED,
                    battery = HrmCapabilityState.ABSENT,
                    polarPmd = HrmCapabilityState.ABSENT,
                    contact = ContactReportState.NOT_REPORTED,
                ),
            ),
        )
        rule.onNodeWithText("Save raw ECG").assertDoesNotExist()
        rule.onNodeWithText("Torso Biomechanics (50 Hz ACC)").assertDoesNotExist()
        rule.onNodeWithText("Tare Posture").assertDoesNotExist()
        rule.onNodeWithText("Reset Tare").assertDoesNotExist()
    }

    @Test
    fun t36PolarWithPmdShowsEcgTorsoAndTareControls() {
        showCard(
            managerWith(
                connectionState = ConnectionState.Connected("Polar H10 12345678", "AA:BB:CC:DD:EE:FF"),
                caps = HrmCapabilities(
                    standardHr = HrmCapabilityState.CONFIRMED,
                    battery = HrmCapabilityState.CONFIRMED,
                    polarPmd = HrmCapabilityState.CONFIRMED,
                ),
            ),
        )
        rule.onNodeWithText("Save raw ECG").assertExists()
        rule.onNodeWithText("Torso Biomechanics (50 Hz ACC)").assertExists()
        rule.onNodeWithText("Tare Posture").assertExists()
        rule.onNodeWithText("Reset Tare").assertExists()
    }

    @Test
    fun t27EcgPreferenceSurfacesOnlyForPmdDevices() {
        // Non-PMD device: the raw-ECG preference never appears.
        showCard(
            managerWith(
                caps = HrmCapabilities(
                    standardHr = HrmCapabilityState.CONFIRMED,
                    polarPmd = HrmCapabilityState.ABSENT,
                ),
            ),
        )
        rule.onNodeWithText("Save raw ECG").assertDoesNotExist()
        rule.onNodeWithText("Currently: Off").assertDoesNotExist()
    }

    @Test
    fun t36EcgCopySeparatesDefaultExplanationFromToggleState() {
        showCard(
            managerWith(
                connectionState = ConnectionState.Connected("Polar H10 12345678", "AA:BB:CC:DD:EE:FF"),
                caps = HrmCapabilities(
                    standardHr = HrmCapabilityState.CONFIRMED,
                    polarPmd = HrmCapabilityState.CONFIRMED,
                ),
                rawEcgEnabled = true,
            ),
        )
        // "Off by default" is a DEFAULT explanation (SD6)…
        rule.onNodeWithText("Off by default.", substring = true).assertExists()
        // …and the live toggle state is shown distinctly from it.
        rule.onNodeWithText("Currently: On").assertExists()
    }

    // ==================== T36 — battery / HRV gating ====================

    @Test
    fun t36BatteryAbsentShowsUnavailableNeverAFabricatedValue() {
        showCard(
            managerWith(
                caps = HrmCapabilities(
                    standardHr = HrmCapabilityState.CONFIRMED,
                    battery = HrmCapabilityState.ABSENT,
                ),
                battery = null,
            ),
        )
        rule.onNodeWithText("Battery unavailable").assertExists()
        rule.onNodeWithText("Battery: 87%", substring = true).assertDoesNotExist()
    }

    @Test
    fun t36BatteryConfirmedShowsTheObservedValue() {
        showCard(
            managerWith(
                caps = HrmCapabilities(
                    standardHr = HrmCapabilityState.CONFIRMED,
                    battery = HrmCapabilityState.CONFIRMED,
                ),
                battery = 87,
            ),
        )
        rule.onNodeWithText("Battery: 87%").assertExists()
        rule.onNodeWithText("Battery unavailable").assertDoesNotExist()
    }

    @Test
    fun t36RrAbsentMarksHrvUnavailableNeverPromised() {
        showCard(
            managerWith(
                caps = HrmCapabilities(
                    standardHr = HrmCapabilityState.CONFIRMED,
                    rrIntervals = HrmCapabilityState.UNKNOWN,
                ),
            ),
        )
        rule.onNodeWithText("HRV unavailable").assertExists()
        rule.onNodeWithText("R-R intervals available · HRV (RMSSD)").assertDoesNotExist()
    }

    @Test
    fun t36RrConfirmedShowsHrvSection() {
        showCard(
            managerWith(
                caps = HrmCapabilities(
                    standardHr = HrmCapabilityState.CONFIRMED,
                    rrIntervals = HrmCapabilityState.CONFIRMED,
                ),
            ),
        )
        rule.onNodeWithText("R-R intervals available · HRV (RMSSD)").assertExists()
    }

    // ==================== T36 — device label ====================

    @Test
    fun t36ConnectedLabelUsesAdvertisedNameWithHeartRateSensorFallback() {
        showCard(
            managerWith(
                connectionState = ConnectionState.Connected("Garmin Forerunner 245", "AA:BB:CC:DD:EE:FF"),
            ),
        )
        rule.onNodeWithText("Device: Garmin Forerunner 245").assertExists()
        rule.onNodeWithText("Address: AA:BB:CC:DD:EE:FF").assertExists()

        showCard(
            managerWith(
                connectionState = ConnectionState.Connected("", "11:22:33:44:55:66"),
            ),
        )
        rule.onNodeWithText("Device: Heart Rate Sensor").assertExists()
    }

    // ==================== T36 — HR waiting vs failed ====================

    @Test
    fun t36WaitingForFirstSampleAndFailedHrStatesAreDistinct() {
        showCard(
            managerWith(
                caps = HrmCapabilities(standardHr = HrmCapabilityState.WAITING_FIRST_SAMPLE),
            ),
        )
        rule.onNodeWithText("Live HR: waiting for first sample...").assertExists()
        rule.onNodeWithText("Live HR: unavailable (heart rate sensor error)").assertDoesNotExist()

        showCard(
            managerWith(
                caps = HrmCapabilities(standardHr = HrmCapabilityState.FAILED),
            ),
        )
        rule.onNodeWithText("Live HR: unavailable (heart rate sensor error)").assertExists()
        rule.onNodeWithText("Live HR: waiting for first sample...").assertDoesNotExist()

        // Initial Unknown state is distinct from both.
        showCard(managerWith(caps = HrmCapabilities(standardHr = HrmCapabilityState.UNKNOWN)))
        rule.onNodeWithText("Live HR: unknown").assertExists()
    }

    // ==================== T36 — skin contact tri-state ====================

    @Test
    fun t36SkinContactUsesThreeStateWording() {
        showCard(
            managerWith(caps = HrmCapabilities(contact = ContactReportState.NOT_REPORTED)),
        )
        rule.onNodeWithText("Skin contact: not reported").assertExists()

        showCard(
            managerWith(caps = HrmCapabilities(contact = ContactReportState.REPORTED_DETECTED)),
        )
        rule.onNodeWithText("Skin contact detected").assertExists()

        showCard(
            managerWith(caps = HrmCapabilities(contact = ContactReportState.REPORTED_NOT_DETECTED)),
        )
        rule.onNodeWithText("Skin contact not detected").assertExists()
    }

    // ==================== T36 — copy contract (SD6) ====================

    @Test
    fun t36ScanCopyIsGenericWithNoStaticPolarH10AndNoSecureBleClaim() {
        showCard(
            managerWith(connectionState = ConnectionState.Scanning),
        )
        rule.onNodeWithText("Looking for nearby heart rate sensors...").assertExists()
        rule.onNodeWithText("Looking for nearby Polar devices...").assertDoesNotExist()
        rule.onNodeWithText("Polar H10", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("Scan for Heart Rate Sensors").assertExists()

        showCard(
            managerWith(connectionState = ConnectionState.Connecting),
        )
        rule.onNodeWithText("Connecting to heart rate sensor...").assertExists()
        rule.onNodeWithText("secure", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    // ==================== T33 copy — HRR unavailable ====================

    @Test
    fun t33HrrUnavailableCopyIsExactAndNeverBlamesRrIntervals() {
        val session = WorkoutSession(id = "hr-only-no-recovery", avgHeartRateBpm = 145, maxHeartRateBpm = 160, minHeartRateBpm = 130)
        val repository = mockk<WorkoutRepository>(relaxed = true)
        coEvery { repository.getSession(session.id) } returns session

        rule.setContent {
            MaterialTheme {
                SessionHeartRateCard(session = session, showCooldown = false, workoutRepository = repository)
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Heart-rate recovery unavailable · insufficient recovery data").assertExists()
        rule.onNodeWithText("RR intervals not supplied", substring = true, useUnmergedTree = true).assertDoesNotExist()
        // RR present in the session would still never be blamed; HRV stays honest too.
        rule.onNodeWithText("HRV unavailable").assertExists()
    }
}
