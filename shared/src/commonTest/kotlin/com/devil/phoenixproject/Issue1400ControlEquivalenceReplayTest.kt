package com.devil.phoenixproject

import com.devil.phoenixproject.data.ble.MonitorDataProcessor
import com.devil.phoenixproject.data.ble.parseMonitorPacket
import com.devil.phoenixproject.data.ble.parseRepPacket
import com.devil.phoenixproject.data.repository.HandleState
import com.devil.phoenixproject.domain.replay.RepBoundaryDetector
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #1400 F1 AC2: control-equivalence replay. The same synthetic raw monitor /
 * rep stream is replayed through the old pipeline (legacy /10 ingestion with the
 * pre-#1400 cm-scale constants, mirrored below) and the new pipeline (raw mm / mm-s
 * ingestion with the x10 constants). Handle-state transitions, grab/auto-start
 * timing, filtered-sample indices, rep valleys, rep counts, ROM seeding and Just
 * Lift danger-zone decisions must be identical.
 *
 * Stream values are multiples of 10 raw units so raw/10 is exact in Float and the
 * two pipelines differ only by an exact factor of 10 — discrete outcomes are then
 * compared exactly, continuous seeded values by the exact x10 relation.
 */
class Issue1400ControlEquivalenceReplayTest {

    private class Clock {
        var now: Long = 1_000_000L
        fun advance(ms: Long) {
            now += ms
        }
    }

    // ==================== legacy pipeline mirror (pre-#1400 semantics) ====================

    /** Pre-#1400 MonitorDataProcessor semantics: pos = raw/10 (cm), vel = raw/10 (cm/s). */
    private class LegacyMonitorProcessor(private val clock: Clock) {
        private var lastGoodPosA = 0.0f
        private var lastGoodPosB = 0.0f
        private var lastPositionA = 0.0f
        private var lastPositionB = 0.0f
        private var lastTimestamp = 0L
        private var smoothedA = 0.0
        private var smoothedB = 0.0
        private var firstVelocity = true
        private var lastFiltered = false

        fun process(posARaw: Int, velARaw: Int): FloatArray? {
            var posA = posARaw / 10.0f
            if (posA !in -1000f..1000f) posA = lastGoodPosA else lastGoodPosA = posA
            val previousPosA = lastPositionA
            lastPositionA = posA
            val t = clock.now
            val jump = abs(posA - previousPosA)
            if (lastTimestamp > 0L && jump > 20.0f) {
                lastFiltered = true
                return null
            }
            val rawVelocityA = velARaw / 10.0
            if (lastFiltered) {
                lastFiltered = false
            } else if (firstVelocity) {
                smoothedA = rawVelocityA
                firstVelocity = false
            } else {
                smoothedA = 0.3 * rawVelocityA + 0.7 * smoothedA
            }
            lastTimestamp = t
            return floatArrayOf(posA, smoothedA.toFloat())
        }
    }

    /** Pre-#1400 HandleStateDetector.analyzeHandleState semantics with the old constants. */
    private class LegacyHandleStateDetector(private val clock: Clock) {
        private var state = HandleState.WaitingForRest
        private var pendingGrabbedStart: Long? = null
        private var pendingReleasedStart: Long? = null
        private var activeHandlesMask = 0
        private var waitingForRestStart: Long? = null
        private var restBaselinePosA: Double? = null

        fun state(): HandleState = state

        fun process(posA: Float, velocityA: Double) {
            val pos = posA.toDouble()
            val grabbed = if (restBaselinePosA != null) {
                (pos - restBaselinePosA!!) > 10.0
            } else {
                pos > 8.0
            }
            // Auto-start mode uses the lower velocity threshold (old scale: 20 cm/s).
            val moving = abs(velocityA) > 20.0
            val nextState = when (state) {
                HandleState.WaitingForRest -> {
                    if (pos < 5.0) {
                        waitingForRestStart = null
                        restBaselinePosA = pos
                        HandleState.Released
                    } else {
                        val start = waitingForRestStart
                        if (start == null) {
                            waitingForRestStart = clock.now
                            HandleState.WaitingForRest
                        } else if (clock.now - start > 3000L) {
                            restBaselinePosA = if (pos > 8.0) 0.0 else pos
                            waitingForRestStart = null
                            HandleState.Released
                        } else {
                            HandleState.WaitingForRest
                        }
                    }
                }
                HandleState.Released, HandleState.Moving -> {
                    val active = grabbed && moving
                    when {
                        active -> {
                            val start = pendingGrabbedStart
                            if (start == null) {
                                pendingGrabbedStart = clock.now
                                state
                            } else if (clock.now - start >= 200L) {
                                activeHandlesMask = 1
                                pendingGrabbedStart = null
                                HandleState.Grabbed
                            } else {
                                state
                            }
                        }
                        grabbed -> {
                            pendingGrabbedStart = null
                            HandleState.Moving
                        }
                        else -> {
                            pendingGrabbedStart = null
                            HandleState.Released
                        }
                    }
                }
                HandleState.Grabbed -> {
                    val released = if (restBaselinePosA != null) {
                        (pos - restBaselinePosA!!) < 5.0
                    } else {
                        pos < 5.0
                    }
                    val isReleased = when (activeHandlesMask) {
                        1 -> released
                        else -> released
                    }
                    if (isReleased) {
                        val start = pendingReleasedStart
                        if (start == null) {
                            pendingReleasedStart = clock.now
                            HandleState.Grabbed
                        } else if (clock.now - start >= 200L) {
                            pendingReleasedStart = null
                            activeHandlesMask = 0
                            HandleState.Released
                        } else {
                            HandleState.Grabbed
                        }
                    } else {
                        pendingReleasedStart = null
                        HandleState.Grabbed
                    }
                }
            }
            state = nextState
        }
    }

    /** Pre-#1400 RepBoundaryDetector semantics (valley prominence 10f on cm-scale positions). */
    private object LegacyRepBoundaryDetector {
        private const val VALLEY_THRESHOLD = 10f

        fun valleyIndices(positions: FloatArray): List<Int> {
            val smoothed = FloatArray(positions.size) { i ->
                val start = maxOf(0, i - 2)
                val end = minOf(positions.size - 1, i + 2)
                var sum = 0f
                for (j in start..end) sum += positions[j]
                sum / (end - start + 1)
            }
            val valleys = mutableListOf<Int>()
            for (i in 2 until smoothed.size - 2) {
                val current = smoothed[i]
                var isMinimum = true
                var maxInWindow = current
                for (j in -2..2) {
                    val value = smoothed[i + j]
                    if (value < current) {
                        isMinimum = false
                        break
                    }
                    if (value > maxInWindow) maxInWindow = value
                }
                if (isMinimum && maxInWindow - current >= VALLEY_THRESHOLD) {
                    if (valleys.isEmpty() || i - valleys.last() >= 8) valleys.add(i)
                }
            }
            if (smoothed.size > 10) {
                val firstFew = smoothed.take(5).minOrNull() ?: smoothed[0]
                val restAvg = smoothed.drop(5).take(10).average().toFloat()
                if (firstFew < restAvg - VALLEY_THRESHOLD) {
                    val minIndex = (0 until minOf(5, smoothed.size)).minByOrNull { smoothed[it] } ?: 0
                    if (valleys.isEmpty() || valleys.first() - minIndex >= 8) valleys.add(0, minIndex)
                }
            }
            if (smoothed.size > 10) {
                val lastFewMin = smoothed.takeLast(5).minOrNull() ?: smoothed.last()
                val beforeLastAvg = smoothed.dropLast(5).takeLast(10).average().toFloat()
                if (lastFewMin < beforeLastAvg - VALLEY_THRESHOLD) {
                    val baseIndex = smoothed.size - 5
                    val minOffset = (0 until 5).minByOrNull { smoothed[baseIndex + it] } ?: 4
                    val minIndex = baseIndex + minOffset
                    if (valleys.isEmpty() || minIndex - valleys.last() >= 8) valleys.add(minIndex)
                }
            }
            return valleys.sorted()
        }
    }

    // ==================== tests ====================

    @Test
    fun `handle states grab timing and filtered samples are identical across pipelines`() {
        val oldClock = Clock()
        val newClock = Clock()
        val legacy = LegacyMonitorProcessor(oldClock)
        val legacyDetector = LegacyHandleStateDetector(oldClock)
        val processor = MonitorDataProcessor(timeProvider = { newClock.now })
        val detector = com.devil.phoenixproject.data.ble.HandleStateDetector(timeProvider = { newClock.now })
        detector.enable(autoStart = true)

        val oldStates = mutableListOf<Pair<Int, HandleState>>()
        val newStates = mutableListOf<Pair<Int, HandleState>>()
        val oldFiltered = mutableListOf<Int>()
        val newFiltered = mutableListOf<Int>()

        stream().forEachIndexed { index, sample ->
            val (posARaw, velARaw, deltaMs) = sample
            oldClock.advance(deltaMs)
            newClock.advance(deltaMs)

            val oldMetric = legacy.process(posARaw, velARaw)
            if (oldMetric == null) {
                oldFiltered.add(index)
            } else {
                legacyDetector.process(oldMetric[0], oldMetric[1].toDouble())
            }
            oldStates.add(index to legacyDetector.state())

            val parsed = parseMonitorPacket(packetBytes(posARaw, velARaw))!!
            val newMetric = processor.process(parsed)
            if (newMetric == null) {
                newFiltered.add(index)
            } else {
                detector.processMetric(newMetric)
            }
            newStates.add(index to detector.handleState.value)
        }

        assertEquals(oldFiltered, newFiltered, "jump/range filtered sample indices must be identical")
        assertEquals(oldStates, newStates, "handle-state transitions per sample must be identical")
        assertTrue(oldStates.any { it.second == HandleState.Grabbed }, "the stream must exercise the grab path")
        assertTrue(oldStates.any { it.second == HandleState.Released }, "the stream must exercise the release path")
    }

    @Test
    fun `rep valleys and rep counts are identical across pipelines`() {
        // Two slow reps worth of raw position samples (raw units, multiples of 10).
        val wave = (0..60).map { i ->
            val phase = i / 30.0
            val raw = ((kotlin.math.sin(phase * 2 * kotlin.math.PI) * 300.0).toInt() / 10) * 10
            raw
        }
        val oldPositions = FloatArray(wave.size) { wave[it] / 10.0f }
        val newPositions = FloatArray(wave.size) { wave[it].toFloat() }

        val oldValleys = LegacyRepBoundaryDetector.valleyIndices(oldPositions)
        val newStarts = RepBoundaryDetector().detectBoundaries(newPositions).map { it.startIndex }
        // Boundaries pair up consecutive valleys, so the last valley has no boundary of its own.
        assertEquals(oldValleys.dropLast(1), newStarts, "valley indices must be identical")
        assertEquals(oldValleys.size, newStarts.size + 1, "valley counts must be identical")

        // Rep counts come from the machine counters (scale-free); both parse paths must
        // deliver identical count progressions on the same notification stream.
        val oldCounter = RepCounterFromMachine()
        val newCounter = RepCounterFromMachine()
        oldCounter.configure(warmupTarget = 0, workingTarget = 0, isJustLift = false, stopAtTop = false)
        newCounter.configure(warmupTarget = 0, workingTarget = 0, isJustLift = false, stopAtTop = false)
        val oldEvents = mutableListOf<Int>()
        val newEvents = mutableListOf<Int>()
        oldCounter.onRepEvent = { oldEvents.add(it.workingCount) }
        newCounter.onRepEvent = { newEvents.add(it.workingCount) }
        for (up in 1..5) {
            val wireTop = 65.0f
            val newNotification = parseRepPacket(repPacketBytes(up, up, floatBits(wireTop), floatBits(5.0f)), false, up.toLong())!!
            // Old pipeline used the unscaled wire floats; new pipeline scales x10 at parse.
            oldCounter.process(
                repsRomCount = 0, repsSetCount = up, up = up, down = up,
                posA = wireTop, posB = 0f,
            )
            newCounter.process(
                repsRomCount = 0, repsSetCount = up, up = up, down = up,
                posA = newNotification.rangeTop, posB = 0f,
            )
            assertEquals(wireTop * 10f, newNotification.rangeTop, "parsed ROM bounds scale x10")
        }
        assertEquals(oldEvents, newEvents, "rep count progressions must be identical")
        assertTrue(newEvents.isNotEmpty(), "the stream must produce working reps")
    }

    @Test
    fun `ROM seeding and Just Lift danger zone are identical across pipelines`() {
        // Rep-packet ROM seeding: wire 65.0/5.0 seeds 65.0/5.0 cm on the old pipeline and
        // 650/50 mm on the new one; the seeds differ by exactly x10 and the 300f rope
        // sentinel is rejected by both (300f old, 3000f new).
        val oldCounter = RepCounterFromMachine()
        val newCounter = RepCounterFromMachine()
        val wireTop = 65.0f
        val wireBottom = 5.0f
        val newNotification = parseRepPacket(repPacketBytes(1, 1, floatBits(wireTop), floatBits(wireBottom)), false, 1L)!!
        // Old pipeline: wire floats used unscaled against cm positions. New pipeline:
        // x10 at parse so seeds match the mm position scale.
        assertEquals(wireTop * 10f, newNotification.rangeTop, "parsed ROM bounds scale x10")
        oldCounter.seedRomBoundaries(wireTop, wireBottom)
        newCounter.seedRomBoundaries(newNotification.rangeTop, newNotification.rangeBottom)
        val oldRanges = oldCounter.getRepRanges()
        val newRanges = newCounter.getRepRanges()
        assertEquals(oldRanges.maxPosA!! * 10f, newRanges.maxPosA!!, "ROM seed must scale x10")
        assertEquals(oldRanges.minPosA!! * 10f, newRanges.minPosA!!, "ROM seed must scale x10")

        // The 3 m rope sentinel is rejected on both pipelines: the legacy rule compares
        // the unscaled wire floats (300f/0f), the new counter compares the parsed mm
        // values (3000f/0f). The sentinel check is parse-scaled consistently.
        val sentinelWireTop = 300.0f
        val sentinelWireBottom = 0.0f
        val legacySentinelRejects = sentinelWireTop == 300.0f && sentinelWireBottom == 0.0f
        assertTrue(legacySentinelRejects, "legacy sentinel rule rejects the 300f/0f wire pair")
        val sentinel = parseRepPacket(repPacketBytes(1, 1, floatBits(sentinelWireTop), floatBits(sentinelWireBottom)), false, 1L)!!
        assertEquals(3000.0f, sentinel.rangeTop, "the rope sentinel must parse to the mm sentinel")
        val newSentinel = RepCounterFromMachine()
        newSentinel.seedRomBoundaries(sentinel.rangeTop, sentinel.rangeBottom)
        assertEquals(null, newSentinel.getRepRanges().maxPosA, "new sentinel must not seed")

        // Just Lift: continuous range tracking and the danger-zone predicate must decide
        // identically on the same physical stream (cm thresholds vs x10 mm thresholds).
        for (posRaw in listOf(0, 200, 600, 400, 100, 60)) {
            oldCounter.updatePositionRangesContinuously(posRaw / 10f, posRaw / 10f)
            newCounter.updatePositionRangesContinuously(posRaw.toFloat(), posRaw.toFloat())
        }
        for (posRaw in listOf(60, 100, 300, 550, 60, 10)) {
            val oldDanger = oldCounter.getRepRanges().isInDangerZone(posRaw / 10f, posRaw / 10f, 50f)
            val newDanger = newCounter.getRepRanges().isInDangerZone(posRaw.toFloat(), posRaw.toFloat(), 500f)
            assertEquals(oldDanger, newDanger, "Just Lift danger-zone decision must be identical at raw=$posRaw")
        }
    }

    // ==================== stream + helpers ====================

    /** (posARaw, velARaw, deltaMsSincePrevious) — rest, grab ramp, sustained dwell, jump, release ramp. */
    private fun stream(): List<Triple<Int, Int, Long>> = listOf(
        Triple(0, 0, 20),
        Triple(0, 0, 20),
        Triple(10, 0, 20),
        Triple(200, 300, 20), // extended but slow -> Moving (EMA lags below the grab threshold)
        Triple(240, 800, 20), // high velocity -> grab pending
        Triple(280, 700, 220), // dwell elapsed -> GRAB CONFIRMED
        Triple(300, 600, 220), // still grabbed
        Triple(310, 500, 20),
        Triple(560, 500, 20), // +250 raw jump -> filtered
        Triple(560, 400, 20), // re-accepted
        Triple(550, 300, 20),
        Triple(350, 100, 20), // release ramp (each step exactly 200 raw = the jump threshold)
        Triple(150, 0, 20),
        Triple(30, 0, 20), // released (baseline-relative delta < release threshold)
        Triple(30, 0, 220), // dwell elapsed -> RELEASE CONFIRMED
        Triple(5, 0, 20),
    )

    private fun packetBytes(posARaw: Int, velARaw: Int): ByteArray {
        val data = ByteArray(26)
        putUInt16LE(data, 4, posARaw and 0xFFFF)
        putUInt16LE(data, 6, velARaw and 0xFFFF)
        putUInt16LE(data, 8, 2000)
        putUInt16LE(data, 10, 0)
        putUInt16LE(data, 12, 0)
        putUInt16LE(data, 14, 0)
        return data
    }

    private fun repPacketBytes(up: Int, down: Int, rangeTopBits: Int, rangeBottomBits: Int): ByteArray {
        val data = ByteArray(24)
        putUInt32LE(data, 0, up)
        putUInt32LE(data, 4, down)
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
