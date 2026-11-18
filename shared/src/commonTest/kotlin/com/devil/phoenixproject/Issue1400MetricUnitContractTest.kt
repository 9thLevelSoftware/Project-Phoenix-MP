package com.devil.phoenixproject

import com.devil.phoenixproject.data.ble.MonitorDataProcessor
import com.devil.phoenixproject.data.ble.MonitorPacket
import com.devil.phoenixproject.data.ble.parseMonitorPacket
import com.devil.phoenixproject.data.ble.parseRepPacket
import com.devil.phoenixproject.domain.assessment.AssessmentEngine
import com.devil.phoenixproject.domain.model.BiomechanicsVelocityZone
import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxEstimator
import com.devil.phoenixproject.domain.onerepmax.WorkoutVelocityPoint
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.util.CURRENT_BACKUP_VERSION
import com.devil.phoenixproject.util.FIRST_MM_SCALE_BACKUP_VERSION
import com.devil.phoenixproject.util.MetricSampleBackup
import com.devil.phoenixproject.util.WorkoutSessionBackup
import com.devil.phoenixproject.util.legacyMetricUnitScaleFor
import com.devil.phoenixproject.util.scaledToCurrentMetricUnits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #1400 F1 acceptance criteria for the metric-unit contract: raw firmware
 * position is mm and raw firmware velocity is mm/s (RCA section 5a). The legacy
 * /10 ingestion scaling is gone, so raw values pass through at face value and the
 * mm-labelled consumers are correct by construction.
 */
class Issue1400MetricUnitContractTest {

    // ========== AC1: parser and processor units ==========

    @Test
    fun `parser raw position 300 becomes 300f mm`() {
        val packet = monitorPacketBytes(posARaw = 300, posBRaw = -150)
        val parsed = assertNotNull(parseMonitorPacket(packet))
        assertEquals(300f, parsed.posA)
        assertEquals(-150f, parsed.posB)
    }

    @Test
    fun `processor raw velocity 700 seeds 700 mm per s`() {
        val processor = MonitorDataProcessor()
        val metric = assertNotNull(
            processor.process(MonitorPacket(ticks = 1, posA = 300f, posB = 0f, loadA = 20f, loadB = 0f, status = 0, firmwareVelA = 700, firmwareVelB = -250)),
        )
        assertEquals(700.0, metric.velocityA, 0.001)
        assertEquals(-250.0, metric.velocityB, 0.001)
    }

    @Test
    fun `rep packet ROM bounds scale to mm at parse and the rope sentinel is 3000f`() {
        val wire = repPacketBytes(rangeTopBits = floatBits(300.0f), rangeBottomBits = floatBits(0.0f))
        val parsed = assertNotNull(parseRepPacket(wire, hasOpcodePrefix = false, timestamp = 1L))
        // 300.0f wire = the published 3 m rope sentinel; x10 at parse = 3000 mm.
        assertEquals(3000.0f, parsed.rangeTop)
        assertEquals(0.0f, parsed.rangeBottom)

        val counter = RepCounterFromMachine()
        counter.seedRomBoundaries(parsed.rangeTop, parsed.rangeBottom)
        val ranges = counter.getRepRanges()
        assertEquals(null, ranges.maxPosA, "the unset sentinel must not seed ROM boundaries")

        val real = assertNotNull(parseRepPacket(repPacketBytes(floatBits(65.0f), floatBits(5.0f)), false, 1L))
        assertEquals(650.0f, real.rangeTop)
        assertEquals(50.0f, real.rangeBottom)
        val seeded = RepCounterFromMachine()
        seeded.seedRomBoundaries(real.rangeTop, real.rangeBottom)
        assertEquals(650.0f, seeded.getRepRanges().maxPosA)
        assertEquals(50.0f, seeded.getRepRanges().minPosA)
    }

    // ========== AC3: zones and velocity-1RM in true mm/s ==========

    @Test
    fun `MCV 700 mm per s classifies MODERATE not GRIND`() {
        assertEquals(BiomechanicsVelocityZone.MODERATE, BiomechanicsVelocityZone.fromMcv(700f))
        assertEquals(BiomechanicsVelocityZone.GRIND, BiomechanicsVelocityZone.fromMcv(200f))
    }

    @Test
    fun `velocity 1RM estimator receives m per s points in the reporter range`() {
        // Reporter-shaped magnitudes after the fix: light explosive bench ~700 mm/s,
        // RDL ~400 mm/s, heavier bench ~300 mm/s -> 0.3-1.3 m/s fit points (RCA section 5a).
        val points = listOf(
            WorkoutVelocityPoint(loadPerCableKg = 10f, mcvMmS = 700f, timestampMs = 1, workingReps = 5),
            WorkoutVelocityPoint(loadPerCableKg = 20f, mcvMmS = 520f, timestampMs = 2, workingReps = 5),
            WorkoutVelocityPoint(loadPerCableKg = 30f, mcvMmS = 300f, timestampMs = 3, workingReps = 5),
        )
        for (point in points) {
            val mps = point.mcvMmS / 1000f
            assertTrue(mps in 0.3f..1.3f, "reporter-shaped MCV ${point.mcvMmS} mm/s must fit 0.3-1.3 m/s, was $mps")
        }
        val result = VelocityOneRepMaxEstimator(AssessmentEngine()).estimate(points, mvtMs = 0.17f)
        assertNotNull(result, "reporter-shaped points must produce a velocity-1RM estimate")
        assertTrue(result.estimatedPerCableKg > 0f)
    }

    // ========== AC4: backup v8 scales v7-and-older exactly once ==========

    @Test
    fun `backup v7 import scales unit fields exactly once and v8 does not`() {
        val legacy = MetricSampleBackup(
            sessionId = "s",
            timestamp = 1,
            position = 35f,
            positionB = 36f,
            velocity = 70f,
            velocityB = 71f,
            load = 20.5f,
            power = 1400f,
        )
        val scaled = legacy.scaledToCurrentMetricUnits(backupVersion = 7)
        assertEquals(350f, scaled.position)
        assertEquals(360f, scaled.positionB)
        assertEquals(700f, scaled.velocity)
        assertEquals(710f, scaled.velocityB)
        assertEquals(14000f, scaled.power)
        assertEquals(20.5f, scaled.load, "loads must not scale")

        // Exactly-once is enforced by the envelope-version dispatch below plus the row
        // marker on the DB side (restored rows are written at metric_unit_version = 2, so
        // migration 65's WHERE metric_unit_version = 1 scaler can never touch them again);
        // this function itself is pure in the envelope version, not idempotent on output.

        val modern = legacy.scaledToCurrentMetricUnits(backupVersion = FIRST_MM_SCALE_BACKUP_VERSION)
        assertEquals(legacy, modern, "v8 imports must not scale")
        assertEquals(1f, legacyMetricUnitScaleFor(8))
        assertEquals(10f, legacyMetricUnitScaleFor(7))
        assertEquals(10f, legacyMetricUnitScaleFor(1))
        assertEquals(8, CURRENT_BACKUP_VERSION)
    }

    @Test
    fun `backup v7 session import scales avg MCV only`() {
        val session = WorkoutSessionBackup(
            id = "s",
            timestamp = 1,
            mode = "OldSchool",
            targetReps = 5,
            weightPerCableKg = 20f,
            progressionKg = 0f,
            duration = 1,
            totalReps = 5,
            warmupReps = 0,
            workingReps = 5,
            isJustLift = false,
            stopAtTop = false,
            avgMcvMmS = 45f,
            totalVelocityLossPercent = 25f,
            heaviestLiftKg = 20f,
        )
        val scaled = session.scaledToCurrentMetricUnits(backupVersion = 7)
        assertEquals(450f, scaled.avgMcvMmS)
        assertEquals(25f, scaled.totalVelocityLossPercent, "percentages must not scale")
        assertEquals(20f, scaled.heaviestLiftKg, "loads must not scale")
    }

    // ========== helpers ==========

    private fun monitorPacketBytes(posARaw: Int, posBRaw: Int): ByteArray {
        val data = ByteArray(26)
        putUInt16LE(data, 4, posARaw and 0xFFFF)
        putUInt16LE(data, 6, 700 and 0xFFFF)
        putUInt16LE(data, 8, 2000) // loadA 20.00 kg
        putUInt16LE(data, 10, posBRaw and 0xFFFF)
        putUInt16LE(data, 12, 0)
        putUInt16LE(data, 14, 0)
        return data
    }

    private fun repPacketBytes(rangeTopBits: Int, rangeBottomBits: Int): ByteArray {
        val data = ByteArray(24)
        putUInt32LE(data, 0, 1) // up
        putUInt32LE(data, 4, 1) // down
        putUInt32LE(data, 8, rangeTopBits)
        putUInt32LE(data, 12, rangeBottomBits)
        return data
    }

    private fun floatBits(value: Float): Int = value.toRawBits()

    private fun putUInt16LE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun putUInt32LE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        data[offset + 2] = ((value shr 16) and 0xFF).toByte()
        data[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
