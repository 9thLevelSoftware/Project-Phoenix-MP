@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.data.repository.HrmTelemetryRepository
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.HrmBeatSample
import com.devil.phoenixproject.domain.model.HrMeasurement
import com.devil.phoenixproject.domain.model.PolarEcgSegment
import com.devil.phoenixproject.domain.model.PolarRecovery
import com.devil.phoenixproject.util.BleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * SD1 HRR/HRV semantics (T28, T32–T35): HRR is computed from recovery HR and is
 * NEVER gated on RR emission; HRV/RMSSD requires enough valid RR intervals.
 */
class HrmHrrHrvSemanticsTest {

    private class CapturingHrmTelemetryRepository : HrmTelemetryRepository {
        val recoveryRecords = mutableListOf<PolarRecovery?>()
        val beatCounts = mutableListOf<Int>()
        override suspend fun saveTelemetry(
            sessionId: String,
            beats: List<HrmBeatSample>,
            ecgs: List<PolarEcgSegment>,
            recovery: PolarRecovery?,
        ) {
            recoveryRecords += recovery
            beatCounts += beats.size
        }

        override suspend fun pruneRawEcg() {}
    }

    // ==================== T28 — Garmin-style session HR ====================

    @Test
    fun `T28 Garmin-style connection populates session HR and finished session has avg max min`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val advertisement = CompositionAdvertisement(
            name = "Garmin Forerunner 245",
            uuids = listOf(BleConstants.Hrm.HR_SERVICE_UUID),
        )
        val fake = CompositionGattFake(advertisement, dispatcher)
        // Garmin-style: standard HR + battery, NO Polar PMD service.
        fake.profileOverride = listOf(
            fakeDiscoveredService(BleConstants.Hrm.HR_SERVICE_UUID, listOf(BleConstants.Hrm.HR_MEASUREMENT_UUID)),
            fakeDiscoveredService(BleConstants.Hrm.BATTERY_SERVICE_UUID, listOf(BleConstants.Hrm.BATTERY_LEVEL_UUID)),
        )
        val manager = HrmBleManager(
            scope = backgroundScope,
            advertisements = { flowOf(advertisement) },
            createPeripheral = { fake },
        )
        manager.startScanning()
        runCurrent()
        assertTrue(manager.connect(ScannedDevice("Garmin Forerunner 245", advertisement.identifier.toString())).isSuccess)
        advanceTimeBy(100)
        runCurrent()
        assertTrue(manager.connectionState.value is ConnectionState.Connected)
        assertEquals(HrmCapabilityState.ABSENT, manager.capabilities.value.polarPmd)

        manager.startSessionRecording("t28-garmin", "WORKING_SET")
        for (hr in listOf(145, 155, 135)) {
            backgroundScope.launch {
                fake.notify(
                    BleConstants.Hrm.hrMeasurementCharacteristic,
                    byteArrayOf(0x06, hr.toByte()), // contact detected, uint8 HR
                )
            }
            advanceTimeBy(50)
            runCurrent()
        }

        // The live HR source consumed for MetricSample.heartRateBpm
        // (ActiveSessionEngine maps manager.hrMeasurement.hr -> MetricSample.heartRateBpm,
        // unchanged by this work) is populated from a non-Polar connection.
        assertEquals(135, manager.hrMeasurement.value?.hr)

        // Finished session has avg/max/min HR.
        val summary = manager.freezeSessionRecording("t28-garmin")
        assertNotNull(summary)
        assertEquals(145, summary.avgHeartRateBpm)
        assertEquals(155, summary.maxHeartRateBpm)
        assertEquals(135, summary.minHeartRateBpm)
        manager.shutdown()
    }

    // ==================== T32 — HR-only source, HRR populated ====================

    @Test
    fun `T32 HR-only source with valid recovery populates HRR 60s and 300s, HRR never depends on RR`() = runTest {
        var simulatedTime = 100_000L
        val repo = CapturingHrmTelemetryRepository()
        val manager = HrmBleManager(
            scope = this,
            timeSource = { simulatedTime },
            telemetryRepository = repo,
            persistenceDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        // Workout: HR-only measurements — RR intervals are NEVER emitted.
        manager.startSessionRecording("t32-hr-only", "WORKING_SET")
        manager.processRrIntervals(HrMeasurement(hr = 150, contactDetected = true, rrIntervalsMs = emptyList()))
        manager.processRrIntervals(HrMeasurement(hr = 160, contactDetected = true, rrIntervalsMs = emptyList()))
        manager.flushSessionTelemetry("t32-hr-only")?.join()
        manager.triggerCooldown("t32-hr-only")
        assertTrue(manager.isCooldownActive.value)
        assertEquals(160, manager.cooldownStartHeartRate.value)

        // Valid recovery: fresh HR samples at 65s and 305s (HR-only again).
        simulatedTime += 65_000L
        manager.processRrIntervals(HrMeasurement(hr = 130, contactDetected = true, rrIntervalsMs = emptyList()))
        testScheduler.advanceTimeBy(1000L)
        assertEquals(30, manager.cooldownHrr60.value, "HRR-60 computed from recovery HR alone")

        simulatedTime += 240_000L
        manager.processRrIntervals(HrMeasurement(hr = 110, contactDetected = true, rrIntervalsMs = emptyList()))
        testScheduler.advanceTimeBy(1000L)
        assertFalse(manager.isCooldownActive.value, "cooldown completes at 300s")
        assertEquals(50, manager.cooldownHrr300.value, "HRR-300 computed from recovery HR alone")

        val summary = manager.getSessionSummary("t32-hr-only")
        assertNotNull(summary)
        assertEquals(30, summary.hrr60Bpm)
        assertEquals(50, summary.hrr300Bpm)
        // Avg/max/min recorded from the HR-only workout.
        assertEquals(155, summary.avgHeartRateBpm)
        assertEquals(160, summary.maxHeartRateBpm)
        assertEquals(150, summary.minHeartRateBpm)
        // No RR ever emitted -> no fabricated HRV/RR values.
        assertNull(summary.rmssdMs)
        assertNull(summary.avgRrIntervalMs)
        // HRR persisted to telemetry storage independent of RR emission.
        assertTrue(
            repo.recoveryRecords.any { it?.hrr60Bpm == 30 && it?.hrr300Bpm == 50 },
            "HRR must persist without any RR interval emission",
        )
    }

    // ==================== T33 — RR present but insufficient recovery ====================

    @Test
    fun `T33 RR present but insufficient recovery data leaves hrr null`() = runTest {
        var simulatedTime = 100_000L
        val manager = HrmBleManager(scope = this, timeSource = { simulatedTime })

        // RR intervals ARE emitted this session.
        manager.startSessionRecording("t33-rr", "WORKING_SET")
        manager.processRrIntervals(HrMeasurement(hr = 150, contactDetected = true, rrIntervalsMs = listOf(800.0)))
        manager.endSessionRecording("t33-rr")
        assertTrue(manager.isCooldownActive.value)

        // Recovery data is insufficient: only 20 seconds of recovery (< 60s).
        simulatedTime += 20_000L
        manager.processRrIntervals(HrMeasurement(hr = 140, contactDetected = true, rrIntervalsMs = listOf(820.0)))
        testScheduler.advanceTimeBy(1000L)
        manager.stopCooldown()

        assertNull(manager.cooldownHrr60.value)
        assertNull(manager.cooldownHrr300.value)
        val summary = manager.getSessionSummary("t33-rr")
        assertNotNull(summary)
        assertNull(summary.hrr60Bpm, "insufficient recovery data must leave HRR null")
        assertNull(summary.hrr300Bpm)
    }

    // ==================== T34 — missing/stale recovery samples ====================

    @Test
    fun `T34 missing recovery samples terminate recovery with null HRR and no fabricated values`() = runTest {
        var simulatedTime = 100_000L
        val manager = HrmBleManager(scope = this, timeSource = { simulatedTime })

        manager.startSessionRecording("t34-missing", "WORKING_SET")
        manager.processRrIntervals(HrMeasurement(hr = 150, contactDetected = true, rrIntervalsMs = listOf(800.0)))
        manager.endSessionRecording("t34-missing")
        assertTrue(manager.isCooldownActive.value)

        // No recovery samples arrive at all -> packet-absence timeout ends recovery.
        simulatedTime += 65_000L
        manager.checkPacketAbsenceTimeout(simulatedTime)
        assertFalse(manager.isCooldownActive.value)
        assertNull(manager.cooldownHrr60.value)
        assertNull(manager.cooldownHrr300.value)
        val summary = manager.getSessionSummary("t34-missing")
        assertNotNull(summary)
        assertNull(summary.hrr60Bpm, "missing recovery samples must never fabricate HRR")
        assertNull(summary.hrr300Bpm)
    }

    @Test
    fun `T34 stale recovery sample cannot produce a fabricated HRR drop`() = runTest {
        var simulatedTime = 100_000L
        val manager = HrmBleManager(scope = this, timeSource = { simulatedTime })

        manager.startSessionRecording("t34-stale", "WORKING_SET")
        manager.processRrIntervals(HrMeasurement(hr = 150, contactDetected = true, rrIntervalsMs = listOf(800.0)))
        manager.endSessionRecording("t34-stale")

        // One early recovery sample (at 10s) then nothing: by the time recovery is
        // stopped at 60s the only sample is stale (>3s old) and must not be used.
        simulatedTime += 10_000L
        manager.processRrIntervals(HrMeasurement(hr = 145, contactDetected = true, rrIntervalsMs = listOf(810.0)))
        simulatedTime += 50_000L
        manager.stopCooldown()

        assertNull(manager.cooldownHrr60.value, "stale recovery sample must not fabricate HRR-60")
        assertNull(manager.cooldownHrr300.value)
        val summary = manager.getSessionSummary("t34-stale")
        assertNotNull(summary)
        assertNull(summary.hrr60Bpm)
        assertNull(summary.hrr300Bpm)
    }

    // ==================== T35 — HRV requires enough valid RR ====================

    @Test
    fun `T35 RMSSD requires enough valid RR intervals, a single RR is insufficient`() = runTest {
        var simulatedTime = 100_000L
        val manager = HrmBleManager(scope = this, timeSource = { simulatedTime })

        // Exactly ONE RR interval emitted — RR present, HRV still unavailable.
        manager.startSessionRecording("t35-single-rr", "WORKING_SET")
        manager.processRrIntervals(HrMeasurement(hr = 70, contactDetected = true, rrIntervalsMs = listOf(800.0)))
        val singleRrSummary = manager.getSessionSummary("t35-single-rr")
        assertNotNull(singleRrSummary)
        assertNull(singleRrSummary.rmssdMs, "one RR interval is not enough for RMSSD")
        assertEquals(800.0f, singleRrSummary.avgRrIntervalMs)

        // Enough valid RR intervals -> RMSSD computed.
        manager.startSessionRecording("t35-many-rr", "WORKING_SET")
        for (rr in listOf(800.0, 810.0, 795.0, 805.0, 800.0)) {
            manager.processRrIntervals(HrMeasurement(hr = 70, contactDetected = true, rrIntervalsMs = listOf(rr)))
        }
        val manyRrSummary = manager.getSessionSummary("t35-many-rr")
        assertNotNull(manyRrSummary)
        assertNotNull(manyRrSummary.rmssdMs, "5 valid RR intervals yield RMSSD")
    }

    @Test
    fun `T35 HRR 60s and 300s persist independently of RR emission`() = runTest {
        var simulatedTime = 100_000L
        val repo = CapturingHrmTelemetryRepository()
        val manager = HrmBleManager(
            scope = this,
            timeSource = { simulatedTime },
            telemetryRepository = repo,
            persistenceDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        // HR-only end to end: no RR anywhere (workout or recovery).
        manager.startSessionRecording("t35-hrr", "WORKING_SET")
        manager.processRrIntervals(HrMeasurement(hr = 155, contactDetected = true, rrIntervalsMs = emptyList()))
        manager.flushSessionTelemetry("t35-hrr")?.join()
        manager.triggerCooldown("t35-hrr")

        simulatedTime += 65_000L
        manager.processRrIntervals(HrMeasurement(hr = 125, contactDetected = true, rrIntervalsMs = emptyList()))
        testScheduler.advanceTimeBy(1000L)
        assertEquals(30, manager.cooldownHrr60.value)

        simulatedTime += 240_000L
        manager.processRrIntervals(HrMeasurement(hr = 105, contactDetected = true, rrIntervalsMs = emptyList()))
        testScheduler.advanceTimeBy(1000L)
        assertEquals(50, manager.cooldownHrr300.value)

        val summary = manager.getSessionSummary("t35-hrr")
        assertNotNull(summary)
        assertEquals(30, summary.hrr60Bpm)
        assertEquals(50, summary.hrr300Bpm)
        assertNull(summary.rmssdMs, "HRV stays null without RR emission")
        // Persistence is verified through the real saveTelemetry path.
        assertTrue(
            repo.recoveryRecords.any { it?.hrr60Bpm == 30 && it?.hrr300Bpm == 50 },
            "60s/300s HRR persistence must work with zero RR emission",
        )
    }
}
