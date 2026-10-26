package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.PolarAccSample
import com.devil.phoenixproject.domain.model.PolarHrMeasurement
import com.devil.phoenixproject.domain.model.PolarPmdMeasurement
import com.devil.phoenixproject.domain.model.PolarRrBeat
import com.devil.phoenixproject.util.BleConstants
import com.juul.kable.Advertisement
import com.juul.kable.Identifier
import com.juul.kable.ManufacturerData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalUuidApi::class)
class PolarH10BleManagerTest {

    private class FakeAdvertisement(
        override val name: String? = null,
        override val uuids: List<Uuid> = emptyList(),
        override val rssi: Int = -60,
    ) : Advertisement {
        override val peripheralName: String? get() = name
        override val identifier: Identifier get() = "00:11:22:33:44:55"
        override val isConnectable: Boolean get() = true
        override val txPower: Int? get() = null
        override val manufacturerData: ManufacturerData? get() = null
        override fun serviceData(uuid: Uuid): ByteArray? = null
        override fun manufacturerData(companyIdentifierCode: Int): ByteArray? = null
    }

    // ==================== Advertisement Filter Tests ====================

    @Test
    fun `isPolarH10Advertisement matches Polar H10 device names case-insensitively`() {
        val manager = PolarH10BleManager()

        assertTrue(manager.isPolarH10Advertisement(FakeAdvertisement(name = "Polar H10 12345678")))
        assertTrue(manager.isPolarH10Advertisement(FakeAdvertisement(name = "polar h10 9999")))
        assertTrue(manager.isPolarH10Advertisement(FakeAdvertisement(name = "POLAR H10")))

        assertFalse(manager.isPolarH10Advertisement(FakeAdvertisement(name = "Vee_Trainer")))
        assertFalse(manager.isPolarH10Advertisement(FakeAdvertisement(name = "VIT-200")))
        assertFalse(manager.isPolarH10Advertisement(FakeAdvertisement(name = null)))
    }

    @Test
    fun `isPolarH10Advertisement matches standard Heart Rate service UUID 0x180D`() {
        val manager = PolarH10BleManager()

        val hrUuid = BleConstants.Polar.HR_SERVICE_UUID
        assertTrue(manager.isPolarH10Advertisement(FakeAdvertisement(name = null, uuids = listOf(hrUuid))))

        val nusUuid = BleConstants.NUS_SERVICE_UUID
        assertFalse(manager.isPolarH10Advertisement(FakeAdvertisement(name = "Unknown", uuids = listOf(nusUuid))))
    }

    // ==================== Initial State Tests ====================

    @Test
    fun `initial states are correctly disconnected and empty`() {
        val manager = PolarH10BleManager()

        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
        assertTrue(manager.scannedDevices.value.isEmpty())
        assertNull(manager.batteryLevel.value)
        assertNull(manager.hrMeasurement.value)
        assertNull(manager.latestEcg.value)
        assertNull(manager.latestAcc.value)
    }

    // ==================== Chained RR-Interval Clock Tests ====================

    @Test
    fun `chained RR clock accurately calculates timestamps across consecutive packets`() {
        var simulatedTime = 100_000L
        val testScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val manager = PolarH10BleManager(scope = testScope, timeSource = { simulatedTime })

        val collectedBeats = mutableListOf<PolarRrBeat>()
        val collectJob = testScope.launch {
            manager.rrBeats.collect { collectedBeats.add(it) }
        }

        // Packet 1 at t = 100,000 ms with two RR intervals: 800.0 ms and 850.0 ms
        // total duration = 1650 ms
        // anchor = 100,000 - 1650 = 98,350 ms
        // Beat 1: 98,350 + 800 = 99,150 ms
        // Beat 2: 99,150 + 850 = 100,000 ms
        manager.processRrIntervals(
            PolarHrMeasurement(
                hr = 72,
                contactDetected = true,
                rrIntervalsMs = listOf(800.0, 850.0),
            ),
        )

        assertEquals(2, collectedBeats.size)
        assertEquals(800.0, collectedBeats[0].rrIntervalMs)
        assertEquals(99_150L, collectedBeats[0].timestampMs)
        assertEquals(72, collectedBeats[0].hr)

        assertEquals(850.0, collectedBeats[1].rrIntervalMs)
        assertEquals(100_000L, collectedBeats[1].timestampMs)
        assertEquals(72, collectedBeats[1].hr)

        // Packet 2 arrives at t = 100,920 ms (packet transport delayed by 20ms jitter)
        // Previous beat was at 100,000 ms.
        // Beat 3 RR = 900.0 ms -> timestamp = 100,000 + 900 = 100,900 ms (jitter eliminated!)
        simulatedTime = 100_920L
        manager.processRrIntervals(
            PolarHrMeasurement(
                hr = 70,
                contactDetected = true,
                rrIntervalsMs = listOf(900.0),
            ),
        )

        assertEquals(3, collectedBeats.size)
        assertEquals(900.0, collectedBeats[2].rrIntervalMs)
        assertEquals(100_900L, collectedBeats[2].timestampMs)
        assertEquals(70, collectedBeats[2].hr)

        collectJob.cancel()
        testScope.cancel()
    }

    @Test
    fun `chained RR clock re-anchors when packet gap exceeds 5 seconds`() {
        var simulatedTime = 50_000L
        val testScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val manager = PolarH10BleManager(scope = testScope, timeSource = { simulatedTime })

        val collectedBeats = mutableListOf<PolarRrBeat>()
        val collectJob = testScope.launch {
            manager.rrBeats.collect { collectedBeats.add(it) }
        }

        // Initial beat at t = 50,000 ms
        manager.processRrIntervals(
            PolarHrMeasurement(
                hr = 60,
                contactDetected = true,
                rrIntervalsMs = listOf(1000.0),
            ),
        )
        assertEquals(1, collectedBeats.size)
        assertEquals(50_000L, collectedBeats[0].timestampMs)

        // Long silence / disconnection gap of 15 seconds: packet arrives at t = 65,000 ms
        simulatedTime = 65_000L
        manager.processRrIntervals(
            PolarHrMeasurement(
                hr = 75,
                contactDetected = true,
                rrIntervalsMs = listOf(800.0),
            ),
        )

        // Since gap (65,000 - 50,000 = 15,000 ms) > 5,000 ms, clock re-anchors to 65,000 ms
        assertEquals(2, collectedBeats.size)
        assertEquals(65_000L, collectedBeats[1].timestampMs)

        collectJob.cancel()
        testScope.cancel()
    }

    @Test
    fun `processRrIntervals ignores empty RR lists without modifying clock`() = kotlinx.coroutines.test.runTest {
        val manager = PolarH10BleManager()
        val beats = mutableListOf<com.devil.phoenixproject.domain.model.PolarRrBeat>()
        val job = launch { manager.rrBeats.collect { beats.add(it) } }
        manager.processRrIntervals(PolarHrMeasurement(hr = 80, contactDetected = true, rrIntervalsMs = emptyList()))
        assertTrue(beats.isEmpty())
        job.cancel()
    }

    // ==================== Session Recording & Cooldown Tests ====================

    @Test
    fun `session recording and cooldown lifecycle updates state flows properly`() {
        var simulatedTime = 100_000L
        val testScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val manager = PolarH10BleManager(scope = testScope, timeSource = { simulatedTime })

        assertFalse(manager.isCooldownActive.value)
        assertEquals(0, manager.cooldownSecondsRemaining.value)

        // Start session recording
        manager.startSessionRecording("session-123", "WARMUP")
        assertEquals("session-123", manager.currentRecordingSessionId)

        // Feed some beats
        manager.processRrIntervals(
            PolarHrMeasurement(
                hr = 150,
                contactDetected = true,
                rrIntervalsMs = listOf(800.0),
            )
        )

        val activeSummary = manager.getSessionSummary("session-123")
        assertEquals(150, activeSummary?.avgHeartRateBpm)

        // Trigger cooldown
        manager.triggerCooldown("session-123")
        assertTrue(manager.isCooldownActive.value)
        assertEquals(300, manager.cooldownSecondsRemaining.value)
        assertEquals(150, manager.cooldownStartHeartRate.value)
        assertEquals("session-123", manager.activeCooldownSessionId.value)

        // Stop cooldown early
        manager.stopCooldown()
        assertFalse(manager.isCooldownActive.value)
        assertEquals(0, manager.cooldownSecondsRemaining.value)

        testScope.cancel()
    }

    @Test
    fun `concurrent getSessionSummary reading while HR callbacks append is thread-safe`() {
        val testScope = CoroutineScope(Dispatchers.Default + Job())
        val manager = PolarH10BleManager(scope = testScope, timeSource = { 100_000L })
        manager.startSessionRecording("session-stress", "WORKING_SET")

        var exceptionThrown: Throwable? = null
        val writeJob = testScope.launch {
            for (i in 1..500) {
                manager.processRrIntervals(
                    PolarHrMeasurement(
                        hr = 120 + (i % 30),
                        contactDetected = true,
                        rrIntervalsMs = listOf(800.0 + (i % 50)),
                    )
                )
            }
        }

        val readJob = testScope.launch {
            try {
                for (i in 1..500) {
                    val summary = manager.getSessionSummary("session-stress")
                    if (summary?.avgHeartRateBpm != null) {
                        assertTrue(summary.avgHeartRateBpm >= 120)
                    }
                }
            } catch (e: Throwable) {
                exceptionThrown = e
            }
        }

        kotlinx.coroutines.runBlocking {
            writeJob.join()
            readJob.join()
        }

        assertNull(exceptionThrown, "Concurrent reading and writing should not throw ConcurrentModificationException: ${exceptionThrown?.message}")
        val finalSummary = manager.getSessionSummary("session-stress")
        assertTrue(finalSummary != null && finalSummary.avgHeartRateBpm != null)
        testScope.cancel()
    }

    @Test
    fun `routine intermediate sets update phase to REST while final completion triggers cooldown`() {
        var simulatedTime = 100_000L
        val testScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val manager = PolarH10BleManager(scope = testScope, timeSource = { simulatedTime })

        // 1. Start routine set 1
        manager.startSessionRecording("routine-session-1", "WARMUP")
        manager.processRrIntervals(PolarHrMeasurement(hr = 145, contactDetected = true, rrIntervalsMs = listOf(800.0)))
        manager.updateSessionPhase("WORKING_SET")

        // 2. Set 1 finishes -> transitions to REST, NOT cooldown
        manager.updateSessionPhase("REST")
        assertFalse(manager.isCooldownActive.value, "Intermediate set summary must NOT activate cooldown")
        assertEquals(0, manager.cooldownSecondsRemaining.value)

        // 3. Set 2 starts -> transitions to WORKING_SET
        manager.updateSessionPhase("WORKING_SET")
        assertFalse(manager.isCooldownActive.value)

        // 4. Routine complete -> triggers cooldown
        manager.endSessionRecording("routine-session-1")
        assertTrue(manager.isCooldownActive.value, "Final routine completion must activate cooldown")
        assertEquals(300, manager.cooldownSecondsRemaining.value)

        // 5. User adds an exercise on the fly (resuming routine) -> WORKING_SET cleanly cancels cooldown
        manager.updateSessionPhase("WORKING_SET")
        assertFalse(manager.isCooldownActive.value, "Resuming routine with added exercise must cancel cooldown")
        assertEquals(0, manager.cooldownSecondsRemaining.value)

        // 6. Routine completes again -> cooldown starts again
        manager.endSessionRecording("routine-session-1")
        assertTrue(manager.isCooldownActive.value, "Re-completing routine must reactivate cooldown")
        assertEquals(300, manager.cooldownSecondsRemaining.value)

        testScope.cancel()
    }

    @Test
    fun `completed cooldown guards against wiping start HR unless forceRestart is true`() {
        var simulatedTime = 100_000L
        val testScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val manager = PolarH10BleManager(scope = testScope, timeSource = { simulatedTime })

        manager.startSessionRecording("session-guard", "WORKING_SET")
        manager.processRrIntervals(PolarHrMeasurement(hr = 160, contactDetected = true, rrIntervalsMs = listOf(700.0)))

        // Trigger cooldown and stop/complete it
        manager.triggerCooldown("session-guard")
        assertEquals(160, manager.cooldownStartHeartRate.value)
        manager.stopCooldown()
        assertFalse(manager.isCooldownActive.value)

        // Re-entering screen triggers cooldown for same session with current HR lower
        manager.processRrIntervals(PolarHrMeasurement(hr = 72, contactDetected = true, rrIntervalsMs = listOf(850.0)))
        manager.triggerCooldown("session-guard") // without forceRestart
        assertFalse(manager.isCooldownActive.value, "Should not restart completed cooldown without forceRestart")
        assertEquals(160, manager.cooldownStartHeartRate.value, "Should not wipe cooldown start HR")

        // Explicit tap on Restart Cooldown
        manager.triggerCooldown("session-guard", forceRestart = true)
        assertTrue(manager.isCooldownActive.value, "Explicit restart must reactivate cooldown")
        assertEquals(72, manager.cooldownStartHeartRate.value, "Restart re-anchors to current HR")

        testScope.cancel()
    }

    @Test
    fun `cooldown anchors peak HR during first 30 seconds`() = runTest {
        var simulatedTime = 100_000L
        val manager = PolarH10BleManager(scope = this, timeSource = { simulatedTime })

        manager.startSessionRecording("session-peak", "WORKING_SET")
        manager.processRrIntervals(PolarHrMeasurement(hr = 140, contactDetected = true, rrIntervalsMs = listOf(800.0)))

        // End session recording (start HR = 140)
        manager.endSessionRecording("session-peak")
        assertEquals(140, manager.cooldownStartHeartRate.value)

        // 10 seconds in, HR peaks at 155
        simulatedTime += 10_000L
        manager.processRrIntervals(PolarHrMeasurement(hr = 155, contactDetected = true, rrIntervalsMs = listOf(750.0)))
        testScheduler.advanceTimeBy(1000L)
        assertEquals(155, manager.cooldownStartHeartRate.value, "Peak HR should be anchored within first 30 seconds")

        // At 65 seconds, HR drops to 120 -> HRR60 measured from peak (155 - 120 = 35)
        simulatedTime += 55_000L
        manager.processRrIntervals(PolarHrMeasurement(hr = 120, contactDetected = true, rrIntervalsMs = listOf(850.0)))
        testScheduler.advanceTimeBy(1000L)
        assertEquals(35, manager.cooldownHrr60.value, "HRR60 drop should be measured from peak HR (155 - 120 = 35)")

        manager.stopCooldown()
    }

    // ==================== Signal Validity & Connection Gate Tests ====================

    @Test
    fun `isConnected state flow tracks connection and disconnection properly`() {
        val manager = PolarH10BleManager()
        assertFalse(manager.isConnected.value, "Initial state must be not connected")

        manager.setConnectedForTesting(true)
        assertTrue(manager.isConnected.value, "State must be connected after successful connect")
        assertTrue(manager.connectionState.value is ConnectionState.Connected)

        manager.setConnectedForTesting(false)
        assertFalse(manager.isConnected.value, "State must be disconnected after disconnect")
        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
    }

    @Test
    fun `strap removal or zero HR immediately clears cached telemetry and drops samples`() {
        var simulatedTime = 100_000L
        val testScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val manager = PolarH10BleManager(scope = testScope, timeSource = { simulatedTime })

        val collectedBeats = mutableListOf<PolarRrBeat>()
        val collectJob = testScope.launch {
            manager.rrBeats.collect { collectedBeats.add(it) }
        }

        // 1. Valid telemetry packet arrives
        manager.processRrIntervals(
            PolarHrMeasurement(
                hr = 135,
                contactDetected = true,
                rrIntervalsMs = listOf(800.0),
            )
        )
        assertEquals(1, collectedBeats.size)
        assertEquals(135, manager.hrMeasurement.value?.hr)

        // 2. User removes strap (contactDetected = false, hr = 0)
        simulatedTime += 1000L
        manager.processRrIntervals(
            PolarHrMeasurement(
                hr = 0,
                contactDetected = false,
                rrIntervalsMs = listOf(800.0),
            )
        )

        // No new beat emitted, cached states cleared to null
        assertEquals(1, collectedBeats.size, "Should not emit beats when strap is removed")
        assertNull(manager.hrMeasurement.value, "hrMeasurement should be cleared upon strap removal")

        collectJob.cancel()
        testScope.cancel()
    }

    @Test
    fun `strap removal or disconnection during cooldown immediately terminates recovery window`() {
        var simulatedTime = 100_000L
        val testScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val manager = PolarH10BleManager(scope = testScope, timeSource = { simulatedTime })

        manager.startSessionRecording("session-recovery", "WORKING_SET")
        manager.processRrIntervals(PolarHrMeasurement(hr = 150, contactDetected = true, rrIntervalsMs = listOf(800.0)))
        manager.triggerCooldown("session-recovery")

        assertTrue(manager.isCooldownActive.value, "Cooldown must be active")
        assertEquals(300, manager.cooldownSecondsRemaining.value)

        // User unhooks strap during recovery cooldown
        manager.processRrIntervals(PolarHrMeasurement(hr = 0, contactDetected = false, rrIntervalsMs = emptyList()))

        assertFalse(manager.isCooldownActive.value, "Cooldown must be terminated upon strap removal")
        assertEquals(0, manager.cooldownSecondsRemaining.value)

        // Reactivate cooldown and verify disconnection terminates it
        manager.startSessionRecording("session-recovery", "WORKING_SET")
        manager.triggerCooldown("session-recovery", forceRestart = true)
        assertTrue(manager.isCooldownActive.value)

        manager.setConnectedForTesting(false)
        assertFalse(manager.isCooldownActive.value, "Cooldown must be terminated upon disconnection")
        assertEquals(0, manager.cooldownSecondsRemaining.value)

        testScope.cancel()
    }

    @Test
    fun `packet absence timeout exceeding 3 seconds clears telemetry and terminates cooldown`() {
        var simulatedTime = 100_000L
        val testScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val manager = PolarH10BleManager(scope = testScope, timeSource = { simulatedTime })

        manager.startSessionRecording("session-timeout", "WORKING_SET")
        manager.processRrIntervals(PolarHrMeasurement(hr = 140, contactDetected = true, rrIntervalsMs = listOf(800.0)))
        manager.triggerCooldown("session-timeout")

        assertTrue(manager.isCooldownActive.value)
        assertEquals(140, manager.hrMeasurement.value?.hr)

        // Advance time by 4 seconds (gap > 3 seconds)
        simulatedTime += 4000L
        manager.checkPacketAbsenceTimeout(simulatedTime)

        assertNull(manager.hrMeasurement.value, "Telemetry state must be cleared after >3s absence")
        assertFalse(manager.isCooldownActive.value, "Cooldown must be terminated after >3s packet absence")
        assertEquals(0, manager.cooldownSecondsRemaining.value)

        testScope.cancel()
    }

    // ==================== Zero-Contact Telemetry & Noise Suppression Tests ====================

    @Test
    fun `lost electrode contact drops all incoming PMD ECG and ACC packets and emits zero telemetry`() {
        val testScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val manager = PolarH10BleManager(scope = testScope)

        // 1. Establish initial good skin contact
        manager.processRrIntervals(PolarHrMeasurement(hr = 120, contactDetected = true, rrIntervalsMs = listOf(800.0)))
        assertTrue(manager.isContactDetected.value, "Contact should be true with good strap contact")

        // 2. Deliver valid ECG & ACC
        manager.processPmdMeasurement(
            PolarPmdMeasurement.Ecg(sensorTimestampNs = 1_000_000L, samplesMicrovolts = listOf(100, 200, 300))
        )
        assertEquals(listOf(100, 200, 300), manager.latestEcg.value?.samplesMicrovolts)

        manager.processPmdMeasurement(
            PolarPmdMeasurement.Acc(sensorTimestampNs = 1_000_000L, samples = listOf(PolarAccSample(10, 20, 1000)))
        )
        assertNotNull(manager.latestAcc.value)
        assertNotNull(manager.torsoPitchDeg.value)

        // 3. User unclips pod / loses skin contact (contactDetected = false, hr = 0)
        manager.processRrIntervals(PolarHrMeasurement(hr = 0, contactDetected = false, rrIntervalsMs = emptyList()))
        assertFalse(manager.isContactDetected.value, "Contact state must drop to false")
        assertNull(manager.hrMeasurement.value, "HR must be cleared to null on contact loss")
        assertNull(manager.latestEcg.value, "ECG must be cleared to null on contact loss")
        assertNull(manager.latestAcc.value, "ACC must be cleared to null on contact loss")
        assertNull(manager.torsoPitchDeg.value, "Posture must be cleared to null on contact loss")
        assertNull(manager.torsoRollDeg.value)
        assertNull(manager.dynamicAccMg.value)

        // 4. PMD packets continue streaming while strap is unclipped - verify they are completely dropped
        manager.processPmdMeasurement(
            PolarPmdMeasurement.Ecg(sensorTimestampNs = 2_000_000L, samplesMicrovolts = listOf(150, 250, 350))
        )
        manager.processPmdMeasurement(
            PolarPmdMeasurement.Acc(sensorTimestampNs = 2_000_000L, samples = listOf(PolarAccSample(30, 40, 990)))
        )

        assertNull(manager.latestEcg.value, "ECG packets must be discarded while contact is lost")
        assertNull(manager.latestAcc.value, "ACC packets must be discarded while contact is lost")
        assertNull(manager.torsoPitchDeg.value, "Posture must remain null while contact is lost")
        assertNull(manager.dynamicAccMg.value)

        testScope.cancel()
    }

    @Test
    fun `ECG railing beyond physiological threshold drops transient frame without revoking contact`() {
        val testScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val manager = PolarH10BleManager(scope = testScope)

        // Initial valid contact
        manager.processRrIntervals(PolarHrMeasurement(hr = 70, contactDetected = true, rrIntervalsMs = listOf(850.0)))
        assertTrue(manager.isContactDetected.value)
        assertEquals(70, manager.hrMeasurement.value?.hr)

        // Deliver railed open-circuit noise (>= 15,000 uV)
        manager.processPmdMeasurement(
            PolarPmdMeasurement.Ecg(
                sensorTimestampNs = 3_000_000L,
                samplesMicrovolts = listOf(100, 16_000, 200),
            )
        )

        // Frame is dropped, but skin contact is NOT revoked
        assertNull(manager.latestEcg.value, "Railed ECG frame must be discarded")
        assertTrue(manager.isContactDetected.value, "Contact state must be preserved during transient ECG railing")
        assertEquals(70, manager.hrMeasurement.value?.hr, "HR telemetry must not be cleared by transient ECG railing")

        // Next valid frame arrives and is processed normally
        manager.processPmdMeasurement(
            PolarPmdMeasurement.Ecg(
                sensorTimestampNs = 3_500_000L,
                samplesMicrovolts = listOf(150, 250, 350),
            )
        )
        assertEquals(listOf(150, 250, 350), manager.latestEcg.value?.samplesMicrovolts)

        testScope.cancel()
    }

    @Test
    fun `ECG flatline drops transient frame without revoking contact`() {
        val testScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val manager = PolarH10BleManager(scope = testScope)

        // Initial valid contact
        manager.processRrIntervals(PolarHrMeasurement(hr = 75, contactDetected = true, rrIntervalsMs = listOf(800.0)))
        assertTrue(manager.isContactDetected.value)

        // Deliver zero flatline
        manager.processPmdMeasurement(
            PolarPmdMeasurement.Ecg(
                sensorTimestampNs = 4_000_000L,
                samplesMicrovolts = listOf(0, 0, 0, 0),
            )
        )

        assertNull(manager.latestEcg.value, "Flatline ECG packet must be dropped")
        assertTrue(manager.isContactDetected.value, "Contact state must be preserved during transient flatline")

        // Next valid frame arrives and is processed normally
        manager.processPmdMeasurement(
            PolarPmdMeasurement.Ecg(
                sensorTimestampNs = 4_500_000L,
                samplesMicrovolts = listOf(200, 300, 400),
            )
        )
        assertEquals(listOf(200, 300, 400), manager.latestEcg.value?.samplesMicrovolts)

        testScope.cancel()
    }

    @Test
    fun `post-workout HR recovery cooldown triggers immediately and resolves session from lastRecordedSessionId`() {
        var simulatedTime = 100_000L
        val testScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val manager = PolarH10BleManager(scope = testScope, timeSource = { simulatedTime })

        // 1. Session completes recording for session-post-workout
        manager.startSessionRecording("session-post-workout", "WORKING_SET")
        assertEquals("session-post-workout", manager.lastRecordedSessionId.value)

        manager.processRrIntervals(PolarHrMeasurement(hr = 160, contactDetected = true, rrIntervalsMs = listOf(800.0)))
        assertEquals(160, manager.hrMeasurement.value?.hr)

        // Telemetry is flushed when set committed
        manager.flushSessionTelemetry("session-post-workout")
        assertEquals("session-post-workout", manager.lastRecordedSessionId.value)

        // 2. User clicks "Start Cooldown" on summary card without explicitly providing ID
        manager.triggerCooldown()

        assertTrue(manager.isCooldownActive.value, "Cooldown must become active immediately on click")
        assertEquals(300, manager.cooldownSecondsRemaining.value)
        assertEquals("session-post-workout", manager.activeCooldownSessionId.value)
        assertEquals(160, manager.cooldownStartHeartRate.value)

        // Cooldown summary is available
        val summary = manager.getSessionSummary("session-post-workout")
        assertNotNull(summary)
        assertEquals("session-post-workout", summary.sessionId)
        assertEquals(160, summary.avgHeartRateBpm)

        testScope.cancel()
    }

    @Test
    fun `critical battery level triggers contact loss and suppresses all telemetry`() {
        val testScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val manager = PolarH10BleManager(scope = testScope)

        manager.processRrIntervals(PolarHrMeasurement(hr = 80, contactDetected = true, rrIntervalsMs = listOf(750.0)))
        assertTrue(manager.isContactDetected.value)

        // Battery drops to critical level (4% <= 5%)
        manager.setBatteryLevelForTesting(4)
        assertTrue(manager.isBatteryCritical(), "Battery must be flagged critical at 4%")
        assertFalse(manager.isContactDetected.value, "Critical battery must revoke contact validation")
        assertNull(manager.hrMeasurement.value, "Telemetry must be cleared upon critical battery")

        // Attempting to deliver subsequent PMD and HR packets
        manager.processPmdMeasurement(
            PolarPmdMeasurement.Ecg(sensorTimestampNs = 5_000_000L, samplesMicrovolts = listOf(120, 220))
        )
        manager.processRrIntervals(PolarHrMeasurement(hr = 80, contactDetected = true, rrIntervalsMs = listOf(750.0)))

        assertNull(manager.latestEcg.value, "ECG must not emit with critical battery")
        assertNull(manager.hrMeasurement.value, "HR must not emit with critical battery")
        assertFalse(manager.isContactDetected.value)

        testScope.cancel()
    }

    @Test
    fun `recordedSessionId isolation rejects mismatches and cooldown end clears state`() {
        val testScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val manager = PolarH10BleManager(scope = testScope, timeSource = { 100_000L })

        // Without starting recording, all actions should reject
        manager.triggerCooldown("session-unrecorded")
        assertFalse(manager.isCooldownActive.value)
        manager.endSessionRecording("session-unrecorded")
        assertFalse(manager.isCooldownActive.value)
        assertNull(manager.getSessionSummary("session-unrecorded"))

        // Start recording session-A
        manager.startSessionRecording("session-A", "WORKING_SET")
        manager.processRrIntervals(PolarHrMeasurement(hr = 140, contactDetected = true, rrIntervalsMs = listOf(800.0)))

        // session-B should be rejected
        assertNull(manager.getSessionSummary("session-B"))
        manager.triggerCooldown("session-B")
        assertFalse(manager.isCooldownActive.value)
        manager.endSessionRecording("session-B")
        assertFalse(manager.isCooldownActive.value)

        // session-A should succeed
        val summaryA = manager.getSessionSummary("session-A")
        assertEquals(140, summaryA?.avgHeartRateBpm)

        manager.triggerCooldown("session-A")
        assertTrue(manager.isCooldownActive.value)
        assertEquals("session-A", manager.activeCooldownSessionId.value)

        // Ending cooldown clears state
        manager.stopCooldown()
        assertFalse(manager.isCooldownActive.value)
        assertNull(manager.activeCooldownSessionId.value)
        assertNull(manager.currentRecordingSessionId)

        // Once cooldown ended, further queries reject
        assertNull(manager.getSessionSummary("session-A"))

        testScope.cancel()
    }

    @Test
    fun `standard HR packets with ECG OFF report isContactDetected true and emit HR and RR telemetry`() {
        val testScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val manager = PolarH10BleManager(scope = testScope, timeSource = { 100_000L })

        assertFalse(manager.isContactDetected.value)
        assertFalse(manager.isEcgStreaming.value)

        val collectedBeats = mutableListOf<PolarRrBeat>()
        val job = testScope.launch {
            manager.rrBeats.collect { collectedBeats.add(it) }
        }

        val measurement = PolarHrMeasurement(hr = 135, contactDetected = true, rrIntervalsMs = listOf(820.0, 830.0))
        manager.processRrIntervals(measurement)

        assertTrue(manager.isContactDetected.value)
        assertEquals(135, manager.hrMeasurement.value?.hr)
        assertEquals(2, collectedBeats.size)
        assertEquals(820.0, collectedBeats[0].rrIntervalMs)
        assertEquals(830.0, collectedBeats[1].rrIntervalMs)

        job.cancel()
        testScope.cancel()
    }

    @Test
    fun `turning ECG ON and then OFF does not break isContactDetected or stop HR or ACC telemetry`() {
        val testScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val manager = PolarH10BleManager(scope = testScope, timeSource = { 100_000L })

        manager.processRrIntervals(PolarHrMeasurement(hr = 120, contactDetected = true, rrIntervalsMs = listOf(800.0)))
        assertTrue(manager.isContactDetected.value)

        manager.setEcgStreamingForTesting(true)
        assertTrue(manager.isEcgStreaming.value)
        assertTrue(manager.isContactDetected.value)

        manager.setEcgStreamingForTesting(false)
        assertFalse(manager.isEcgStreaming.value)
        assertTrue(manager.isContactDetected.value)

        manager.processRrIntervals(PolarHrMeasurement(hr = 125, contactDetected = true, rrIntervalsMs = listOf(790.0)))
        assertTrue(manager.isContactDetected.value)
        assertEquals(125, manager.hrMeasurement.value?.hr)

        val accMeasurement = PolarPmdMeasurement.Acc(
            sensorTimestampNs = 123456789L,
            samples = listOf(PolarAccSample(xMg = 100, yMg = 200, zMg = 980)),
        )
        manager.processPmdMeasurement(accMeasurement)
        assertEquals(accMeasurement, manager.latestAcc.value)
        assertNotNull(manager.torsoPitchDeg.value)

        testScope.cancel()
    }

    @Test
    fun `starting ACC alone with ECG OFF emits ACC samples without requiring ECG`() {
        val testScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val manager = PolarH10BleManager(scope = testScope, timeSource = { 100_000L })

        assertFalse(manager.isEcgStreaming.value)

        manager.processRrIntervals(PolarHrMeasurement(hr = 130, contactDetected = true, rrIntervalsMs = listOf(800.0)))
        assertTrue(manager.isContactDetected.value)

        manager.setAccStreamingForTesting(true)
        assertTrue(manager.isAccStreaming.value)
        assertFalse(manager.isEcgStreaming.value)

        val collectedAcc = mutableListOf<PolarPmdMeasurement.Acc>()
        val job = testScope.launch {
            manager.accStream.collect { collectedAcc.add(it) }
        }

        val accMeasurement = PolarPmdMeasurement.Acc(
            sensorTimestampNs = 987654321L,
            samples = listOf(
                PolarAccSample(xMg = 150, yMg = 250, zMg = 950),
                PolarAccSample(xMg = 155, yMg = 255, zMg = 945),
            ),
        )
        manager.processPmdMeasurement(accMeasurement)

        assertEquals(1, collectedAcc.size)
        assertEquals(accMeasurement, manager.latestAcc.value)
        assertNotNull(manager.torsoPitchDeg.value)
        assertNotNull(manager.torsoRollDeg.value)
        assertNotNull(manager.dynamicAccMg.value)

        job.cancel()
        testScope.cancel()
    }
}


