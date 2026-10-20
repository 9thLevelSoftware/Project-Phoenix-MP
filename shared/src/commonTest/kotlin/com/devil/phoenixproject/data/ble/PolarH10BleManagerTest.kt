package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.PolarHrMeasurement
import com.devil.phoenixproject.domain.model.PolarRrBeat
import com.devil.phoenixproject.util.BleConstants
import com.juul.kable.Advertisement
import com.juul.kable.Identifier
import com.juul.kable.ManufacturerData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertNull(manager.rrClockTimestampMs.value)
        assertNull(manager.latestEcg.value)
        assertNull(manager.latestAcc.value)
        assertFalse(manager.isEcgActive)
        assertFalse(manager.isAccActive)
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
        assertEquals(100_000L, manager.rrClockTimestampMs.value)

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
        assertEquals(100_900L, manager.rrClockTimestampMs.value)

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
        assertEquals(65_000L, manager.rrClockTimestampMs.value)

        collectJob.cancel()
        testScope.cancel()
    }

    @Test
    fun `processRrIntervals ignores empty RR lists without modifying clock`() {
        val manager = PolarH10BleManager()
        manager.processRrIntervals(PolarHrMeasurement(hr = 80, contactDetected = true, rrIntervalsMs = emptyList()))
        assertNull(manager.rrClockTimestampMs.value)
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
}


