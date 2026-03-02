package com.devil.phoenixproject.data.ble

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hardware Validation Test Suite
 *
 * Captures and validates the 3 BLE protocol discrepancies identified in the
 * comparison review (Phase 1) that require real hardware verification:
 *
 *   1a. Packet Layout: Official (28-byte) vs Phoenix/Parent (18-byte) interpretation
 *   1b. Force Units: Percentage (0-100%) vs kg (raw/100)
 *   1c. Velocity Source: Firmware-provided vs client-calculated EMA
 *
 * HOW TO USE:
 * 1. Enable raw packet logging by calling BlePacketCapture.enable() in a debug build
 * 2. Connect to trainer and perform a few reps at a KNOWN weight (e.g., 50kg)
 * 3. Copy captured hex strings into the CAPTURED_PACKETS list below
 * 4. Run this test suite: ./gradlew :shared:testDebugUnitTest --tests "*.HardwareValidationTest"
 * 5. Review output to determine which interpretation matches reality
 *
 * See also: BlePacketCapture.kt for the runtime capture utility.
 */
class HardwareValidationTest {

    // =========================================================================
    // PASTE CAPTURED PACKETS HERE
    // =========================================================================
    // Each entry: hex string from BlePacketCapture log, known weight in kg, description
    // Example: CapturedSample("0A001400E803...1200", 50.0f, "50kg static hold mid-cable")
    //
    // Capture at least:
    //   - 3-5 packets during a STATIC HOLD at a known weight (validates force/load units)
    //   - 5-10 packets during SLOW MOVEMENT (validates velocity field presence)
    //   - 3-5 packets at REST with cable retracted (validates zero/idle state)

    data class CapturedSample(
        val hex: String,
        val knownWeightKg: Float,
        val description: String
    )

    private val CAPTURED_PACKETS: List<CapturedSample> = listOf(
        // TODO: Replace with real captured packets from BlePacketCapture
        // CapturedSample("0A001400E803...", 50.0f, "50kg static hold"),
    )

    // =========================================================================
    // INTERPRETATION A: Official App (28-byte Sample format)
    // =========================================================================
    // [posA_s16/10, velA_s16/10, forceA_s16/100, posB_s16/10, velB_s16/10, forceB_s16/100, time_i32, status_s16]
    // 6 shorts (12 bytes) + 1 int (4 bytes) + 1 short (2 bytes) = 18 bytes minimum

    data class OfficialInterpretation(
        val posA: Float,        // mm (short / 10.0)
        val velA: Float,        // mm/s (short / 10.0)
        val forceA: Float,      // percentage 0-100 (short / 100.0)
        val posB: Float,        // mm (short / 10.0)
        val velB: Float,        // mm/s (short / 10.0)
        val forceB: Float,      // percentage 0-100 (short / 100.0)
        val timeMs: Int,        // firmware timestamp (4 bytes)
        val status: Int?        // status flags (2 bytes, optional)
    )

    private fun parseAsOfficial(data: ByteArray): OfficialInterpretation? {
        if (data.size < 12) return null // Minimum: 6 shorts
        return OfficialInterpretation(
            posA   = getInt16LE(data, 0) / 10.0f,
            velA   = getInt16LE(data, 2) / 10.0f,
            forceA = getInt16LE(data, 4) / 100.0f,
            posB   = getInt16LE(data, 6) / 10.0f,
            velB   = getInt16LE(data, 8) / 10.0f,
            forceB = getInt16LE(data, 10) / 100.0f,
            timeMs = if (data.size >= 16) getInt32LE(data, 12) else 0,
            status = if (data.size >= 18) getUInt16LE(data, 16) else null
        )
    }

    // =========================================================================
    // INTERPRETATION B: Phoenix/Parent (18-byte Monitor format)
    // =========================================================================
    // [ticksLo_u16, ticksHi_u16, posA_s16/10, (skip 2), loadA_u16/100, posB_s16/10, (skip 2), loadB_u16/100, status_u16]
    // This is what parseMonitorPacket() currently does.

    data class PhoenixInterpretation(
        val ticksLow: Int,      // unsigned 16-bit
        val ticksHigh: Int,     // unsigned 16-bit
        val ticks: Int,         // combined 32-bit tick counter
        val posA: Float,        // mm (signed short / 10.0)
        val skippedA: Int,      // bytes 6-7 (velocity in official interpretation)
        val loadA: Float,       // kg (unsigned short / 100.0)
        val posB: Float,        // mm (signed short / 10.0)
        val skippedB: Int,      // bytes 12-13 (velocity in official interpretation)
        val loadB: Float,       // kg (unsigned short / 100.0)
        val status: Int?        // status flags (optional)
    )

    private fun parseAsPhoenix(data: ByteArray): PhoenixInterpretation? {
        if (data.size < 16) return null
        val ticksLow = getUInt16LE(data, 0)
        val ticksHigh = getUInt16LE(data, 2)
        return PhoenixInterpretation(
            ticksLow  = ticksLow,
            ticksHigh = ticksHigh,
            ticks     = ticksLow + (ticksHigh shl 16),
            posA      = getInt16LE(data, 4) / 10.0f,
            skippedA  = getInt16LE(data, 6),      // What Phoenix skips (potential velA)
            loadA     = getUInt16LE(data, 8) / 100.0f,
            posB      = getInt16LE(data, 10) / 10.0f,
            skippedB  = getInt16LE(data, 12),     // What Phoenix skips (potential velB)
            loadB     = getUInt16LE(data, 14) / 100.0f,
            status    = if (data.size >= 18) getUInt16LE(data, 16) else null
        )
    }

    // =========================================================================
    // Helper: hex string to ByteArray
    // =========================================================================

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace(":", "")
        require(clean.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.toHexDump(): String = joinToString(" ") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
    }

    // =========================================================================
    // TEST: Dual Interpretation of Captured Packets
    // =========================================================================

    @Test
    fun `dual interpretation comparison - populate CAPTURED_PACKETS to run`() {
        if (CAPTURED_PACKETS.isEmpty()) {
            println("""
                |========================================================
                | HARDWARE VALIDATION: No captured packets yet
                |========================================================
                | To use this test:
                | 1. Add BlePacketCapture.startCapture() to your debug build
                | 2. Connect to trainer, perform reps at known weight
                | 3. Copy hex strings into CAPTURED_PACKETS list above
                | 4. Re-run this test
                |========================================================
            """.trimMargin())
            return
        }

        println("=" .repeat(80))
        println("HARDWARE VALIDATION: Dual Packet Interpretation")
        println("=" .repeat(80))

        for ((index, sample) in CAPTURED_PACKETS.withIndex()) {
            val data = hexToBytes(sample.hex)
            val official = parseAsOfficial(data)
            val phoenix = parseAsPhoenix(data)

            println("\n--- Packet #$index: ${sample.description} (${data.size} bytes) ---")
            println("Raw: ${data.toHexDump()}")
            println("Known weight: ${sample.knownWeightKg} kg")

            if (official != null) {
                println("\n  OFFICIAL interpretation (28-byte Sample):")
                println("    posA=${official.posA}mm  velA=${official.velA}mm/s  forceA=${official.forceA}%")
                println("    posB=${official.posB}mm  velB=${official.velB}mm/s  forceB=${official.forceB}%")
                println("    time=${official.timeMs}ms  status=${official.status?.let { "0x${it.toString(16).padStart(4, '0').uppercase()}" }}")

                // Force-as-percentage check: does forceA map to known weight?
                val forceAsKgFromPercent = official.forceA / 100.0f * 200.0f // Assuming 200kg max V-Form
                println("    -> If force is %: ${official.forceA}% of 200kg = ${forceAsKgFromPercent}kg (expected ~${sample.knownWeightKg}kg)")
            }

            if (phoenix != null) {
                println("\n  PHOENIX interpretation (18-byte Monitor):")
                println("    ticks=${phoenix.ticks} (lo=${phoenix.ticksLow} hi=${phoenix.ticksHigh})")
                println("    posA=${phoenix.posA}mm  loadA=${phoenix.loadA}kg")
                println("    posB=${phoenix.posB}mm  loadB=${phoenix.loadB}kg")
                println("    skippedA=${phoenix.skippedA} (raw)  skippedB=${phoenix.skippedB} (raw)")
                println("    status=${phoenix.status?.let { "0x${it.toString(16).padStart(4, '0').uppercase()}" }}")

                // Load-as-kg check: does loadA match known weight?
                println("    -> If load is kg: ${phoenix.loadA}kg (expected ~${sample.knownWeightKg}kg)")

                // What if skipped bytes are velocity?
                val velFromSkippedA = phoenix.skippedA / 10.0f
                val velFromSkippedB = phoenix.skippedB / 10.0f
                println("    -> If skipped bytes are velocity: velA=${velFromSkippedA}mm/s  velB=${velFromSkippedB}mm/s")
            }

            println("\n  VERDICT CHECKLIST:")
            println("    [ ] Does official.forceA (${official?.forceA}%) make sense as percentage?")
            println("    [ ] Does phoenix.loadA (${phoenix?.loadA}kg) match known weight (${sample.knownWeightKg}kg)?")
            println("    [ ] Does official.posA (${official?.posA}mm) match phoenix.posA (${phoenix?.posA}mm)?")
            println("    [ ] Are skipped bytes non-zero during movement? (velA=${phoenix?.skippedA}, velB=${phoenix?.skippedB})")
            println("    [ ] Does official.timeMs (${official?.timeMs}) look like a timestamp?")
            println("    [ ] Do phoenix.ticks (${phoenix?.ticks}) look like a counter?")
        }

        println("\n" + "=" .repeat(80))
        println("Review output above. The interpretation where force/load matches")
        println("the known weight is correct. Non-zero skipped bytes during movement")
        println("confirm firmware velocity is present.")
        println("=" .repeat(80))
    }

    // =========================================================================
    // TEST 1a: Packet Layout - Position field alignment
    // =========================================================================

    @Test
    fun `1a - packet layout - positions should be plausible in at least one interpretation`() {
        if (CAPTURED_PACKETS.isEmpty()) return

        for (sample in CAPTURED_PACKETS) {
            val data = hexToBytes(sample.hex)
            val official = parseAsOfficial(data)!!
            val phoenix = parseAsPhoenix(data)!!

            // In at least one interpretation, positions should be in valid range (-1000 to 1000 mm)
            val officialPosValid = official.posA in -1000f..1000f && official.posB in -1000f..1000f
            val phoenixPosValid = phoenix.posA in -1000f..1000f && phoenix.posB in -1000f..1000f

            assertTrue(
                officialPosValid || phoenixPosValid,
                "Packet '${sample.description}': Neither interpretation has plausible positions. " +
                "Official: posA=${official.posA}, posB=${official.posB}; " +
                "Phoenix: posA=${phoenix.posA}, posB=${phoenix.posB}"
            )

            // Note: In OFFICIAL layout, bytes 0-1 are posA. In PHOENIX, bytes 0-1 are ticksLow.
            // If official.posA is always a small number AND phoenix.ticksLow is incrementing,
            // that's evidence FOR the Phoenix interpretation (it's a counter, not position).
        }
    }

    // =========================================================================
    // TEST 1b: Force Units - Compare against known weight
    // =========================================================================

    @Test
    fun `1b - force units - determine if field represents percentage or kg`() {
        val staticHolds = CAPTURED_PACKETS.filter { it.knownWeightKg > 0 }
        if (staticHolds.isEmpty()) {
            println("No static hold packets with known weight. Capture packets while holding a known weight.")
            return
        }

        var percentageMatchCount = 0
        var kgMatchCount = 0
        val tolerance = 0.15f // 15% tolerance for BLE noise

        for (sample in staticHolds) {
            val data = hexToBytes(sample.hex)
            val official = parseAsOfficial(data)!!
            val phoenix = parseAsPhoenix(data)!!

            // Hypothesis A: Official is correct, field is percentage
            // forceA% of 200kg max should approximate known weight
            val vFormMaxKg = 200.0f
            val trainerPlusMaxKg = 220.0f
            val kgFromPercent200 = official.forceA / 100.0f * vFormMaxKg
            val kgFromPercent220 = official.forceA / 100.0f * trainerPlusMaxKg

            val percentMatch200 = abs(kgFromPercent200 - sample.knownWeightKg) / sample.knownWeightKg < tolerance
            val percentMatch220 = abs(kgFromPercent220 - sample.knownWeightKg) / sample.knownWeightKg < tolerance

            // Hypothesis B: Phoenix is correct, field is kg directly
            val kgMatch = abs(phoenix.loadA - sample.knownWeightKg) / sample.knownWeightKg < tolerance

            if (percentMatch200 || percentMatch220) percentageMatchCount++
            if (kgMatch) kgMatchCount++

            println("Sample '${sample.description}':")
            println("  Official forceA=${official.forceA}% -> ${kgFromPercent200}kg (200max) or ${kgFromPercent220}kg (220max)")
            println("  Phoenix loadA=${phoenix.loadA}kg")
            println("  Known weight: ${sample.knownWeightKg}kg")
            println("  Percentage match: ${percentMatch200 || percentMatch220}, Kg match: $kgMatch")
        }

        println("\nSUMMARY: Percentage interpretation matched $percentageMatchCount/${staticHolds.size}")
        println("         Kg interpretation matched $kgMatchCount/${staticHolds.size}")

        if (kgMatchCount > percentageMatchCount) {
            println("RESULT: Force field appears to be KG (Phoenix interpretation correct)")
        } else if (percentageMatchCount > kgMatchCount) {
            println("RESULT: Force field appears to be PERCENTAGE (Official interpretation correct)")
        } else {
            println("RESULT: INCONCLUSIVE - need more samples at different weights")
        }
    }

    // =========================================================================
    // TEST 1c: Velocity Source - Check if skipped bytes contain velocity
    // =========================================================================

    @Test
    fun `1c - velocity source - skipped bytes should be non-zero during movement`() {
        if (CAPTURED_PACKETS.isEmpty()) return

        var nonZeroSkippedCount = 0
        var movementSampleCount = 0

        for (sample in CAPTURED_PACKETS) {
            val data = hexToBytes(sample.hex)
            val phoenix = parseAsPhoenix(data)!!

            // During movement, if bytes 6-7 and 12-13 contain firmware velocity,
            // they should be non-zero
            val hasSkippedData = phoenix.skippedA != 0 || phoenix.skippedB != 0
            if (hasSkippedData) nonZeroSkippedCount++

            // Try to identify movement packets (crude: non-zero position)
            if (abs(phoenix.posA) > 5.0f || abs(phoenix.posB) > 5.0f) {
                movementSampleCount++
            }

            if (hasSkippedData) {
                val velA = phoenix.skippedA / 10.0f
                val velB = phoenix.skippedB / 10.0f
                println("Packet '${sample.description}': skipped bytes -> velA=${velA}mm/s, velB=${velB}mm/s")
                // Sanity: velocity should be reasonable (-500 to +500 mm/s for cable exercises)
                assertTrue(
                    abs(velA) < 500f && abs(velB) < 500f,
                    "Implausible velocity from skipped bytes: velA=$velA, velB=$velB"
                )
            }
        }

        println("\nVelocity source summary:")
        println("  Packets with non-zero skipped bytes: $nonZeroSkippedCount/${CAPTURED_PACKETS.size}")
        println("  Movement packets (pos > 5mm): $movementSampleCount/${CAPTURED_PACKETS.size}")

        if (nonZeroSkippedCount > 0) {
            println("RESULT: Skipped bytes contain data - likely firmware velocity")
            println("        Recommend reading firmware velocity instead of client-side EMA")
        } else if (movementSampleCount > 0) {
            println("RESULT: Movement detected but skipped bytes are zero - firmware may not provide velocity")
        } else {
            println("RESULT: No movement packets captured - re-test with cable movement")
        }
    }

    // =========================================================================
    // TEST: Tick Counter vs Position in bytes 0-3
    // =========================================================================

    @Test
    fun `1a - supplemental - bytes 0-3 should be counter OR position but not both`() {
        if (CAPTURED_PACKETS.size < 3) {
            println("Need 3+ sequential packets to test counter behavior. Skipping.")
            return
        }

        // If bytes 0-3 are a tick counter (Phoenix), they should INCREASE monotonically
        // If bytes 0-1 are posA (Official), they should correlate with cable position
        val ticks = CAPTURED_PACKETS.map { parseAsPhoenix(hexToBytes(it.hex))!!.ticks }
        val officialPositions = CAPTURED_PACKETS.map { parseAsOfficial(hexToBytes(it.hex))!!.posA }

        // Check if ticks are monotonically increasing
        var ticksMonotonic = true
        for (i in 1 until ticks.size) {
            if (ticks[i] <= ticks[i - 1]) {
                ticksMonotonic = false
                break
            }
        }

        // Check if official positions stay in a plausible range
        val positionsPlausible = officialPositions.all { it in -1000f..1000f }

        println("Tick counter test (bytes 0-3):")
        println("  Values: ${ticks.take(5)}")
        println("  Monotonically increasing: $ticksMonotonic")
        println("\nPosition test (bytes 0-1 as posA):")
        println("  Values: ${officialPositions.take(5)}")
        println("  All in plausible range: $positionsPlausible")

        if (ticksMonotonic && !positionsPlausible) {
            println("RESULT: Bytes 0-3 are a tick counter (Phoenix interpretation)")
        } else if (positionsPlausible && !ticksMonotonic) {
            println("RESULT: Bytes 0-1 are position (Official interpretation)")
        } else {
            println("RESULT: AMBIGUOUS - both interpretations plausible. Need more diverse packets.")
        }
    }

    // =========================================================================
    // TEST: Validate parseMonitorPacket() against known good packet
    // =========================================================================

    @Test
    fun `existing parseMonitorPacket produces valid output for synthetic packet`() {
        // Synthetic 18-byte packet with known values
        // Ticks: 1000 (0xE803) lo=0x03E8, hi=0x0000
        // PosA: 150.0mm -> raw 1500 = 0xDC05
        // Gap: 0x0000
        // LoadA: 50.00kg -> raw 5000 = 0x8813
        // PosB: 148.0mm -> raw 1480 = 0xC805
        // Gap: 0x0000
        // LoadB: 50.00kg -> raw 5000 = 0x8813
        // Status: 0x0000
        val data = byteArrayOf(
            0xE8.toByte(), 0x03,  // ticksLo = 1000
            0x00, 0x00,            // ticksHi = 0
            0xDC.toByte(), 0x05,  // posA raw = 1500 -> 150.0mm
            0x00, 0x00,            // gap (or velA in official)
            0x88.toByte(), 0x13,  // loadA raw = 5000 -> 50.0kg
            0xC8.toByte(), 0x05,  // posB raw = 1480 -> 148.0mm
            0x00, 0x00,            // gap (or velB in official)
            0x88.toByte(), 0x13,  // loadB raw = 5000 -> 50.0kg
            0x00, 0x00             // status = 0
        )

        val packet = parseMonitorPacket(data)
        assertNotNull(packet, "parseMonitorPacket should handle 18-byte packet")
        assertEquals(1000, packet.ticks)
        assertEquals(150.0f, packet.posA, 0.1f)
        assertEquals(50.0f, packet.loadA, 0.01f)
        assertEquals(148.0f, packet.posB, 0.1f)
        assertEquals(50.0f, packet.loadB, 0.01f)
        assertEquals(0, packet.status)
    }

    @Test
    fun `parseMonitorPacket rejects packets shorter than 16 bytes`() {
        val tooShort = ByteArray(15)
        val result = parseMonitorPacket(tooShort)
        assertNull(result)
    }

    private fun assertNull(value: Any?) {
        assertEquals(null, value)
    }
}
