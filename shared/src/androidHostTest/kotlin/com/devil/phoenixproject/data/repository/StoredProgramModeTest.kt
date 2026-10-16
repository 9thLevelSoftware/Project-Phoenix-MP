package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.ProgramMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the stored program-mode and eccentric-load mapping shared by the workout
 * and sync repositories. Returned values match the previous private copies.
 */
class StoredProgramModeTest {

    @Test
    fun parseProgramMode_acceptsPrefixedAndLegacyStoredNames() {
        val cases = listOf(
            "Program:OldSchool" to ProgramMode.OldSchool,
            "Program:Pump" to ProgramMode.Pump,
            "Program:TUT" to ProgramMode.TUT,
            "Program:TUTBeast" to ProgramMode.TUTBeast,
            "Program:EccentricOnly" to ProgramMode.EccentricOnly,
            "Program:Echo" to ProgramMode.Echo,
            "OldSchool" to ProgramMode.OldSchool,
            "Pump" to ProgramMode.Pump,
            "TUT" to ProgramMode.TUT,
            "TUTBeast" to ProgramMode.TUTBeast,
            "EccentricOnly" to ProgramMode.EccentricOnly,
            "Echo" to ProgramMode.Echo,
        )

        for ((stored, expected) in cases) {
            assertEquals(expected, parseProgramMode(stored), stored)
        }
    }

    @Test
    fun parseProgramMode_echoPrefixMatchesOnlyOutsideProgramPrefix() {
        assertEquals(ProgramMode.Echo, parseProgramMode("EchoLevel"))
        assertEquals(ProgramMode.Echo, parseProgramMode("Echoes"))
        assertEquals(ProgramMode.OldSchool, parseProgramMode("Program:EchoLevel"))
        assertEquals(ProgramMode.OldSchool, parseProgramMode("Program:Echoes"))
    }

    @Test
    fun parseProgramMode_unknownAndWireFormatsFallBackToOldSchool() {
        val unknown = listOf(
            "",
            "Program:",
            "Program:Unknown",
            "Program:pump",
            "Program: OldSchool",
            "program:Pump",
            "echo",
            "ECHO",
            "PUMP",
            "OLD_SCHOOL",
            "TUT_BEAST",
            "ECCENTRIC_ONLY",
            "TUTBeastly",
            "PumpExtra",
            " OldSchool",
            "Ech",
        )

        for (stored in unknown) {
            assertEquals(ProgramMode.OldSchool, parseProgramMode(stored), stored)
        }
    }

    @Test
    fun mapEccentricLoadFromDb_returnsExactStoredPercentages() {
        val cases = listOf(
            0L to EccentricLoad.LOAD_0,
            50L to EccentricLoad.LOAD_50,
            75L to EccentricLoad.LOAD_75,
            100L to EccentricLoad.LOAD_100,
            110L to EccentricLoad.LOAD_110,
            120L to EccentricLoad.LOAD_120,
            130L to EccentricLoad.LOAD_130,
            140L to EccentricLoad.LOAD_140,
            150L to EccentricLoad.LOAD_150,
        )

        for ((stored, expected) in cases) {
            val clamped = mutableListOf<Pair<Long, Int>>()
            val nonStandard = mutableListOf<Int>()
            val load = mapEccentricLoadFromDb(
                stored,
                onClamped = { original, safe -> clamped += original to safe },
                onNonStandard = { nonStandard += it },
            )
            assertEquals(expected, load, stored.toString())
            assertTrue(clamped.isEmpty(), stored.toString())
            assertTrue(nonStandard.isEmpty(), stored.toString())
        }
    }

    @Test
    fun mapEccentricLoadFromDb_clampsOutsideHardwareRangeBeforeMatching() {
        assertMapped(
            stored = -1L,
            expected = EccentricLoad.LOAD_0,
            clamped = -1L to 0,
        )
        assertMapped(
            stored = 151L,
            expected = EccentricLoad.LOAD_150,
            clamped = 151L to 150,
        )
        assertMapped(
            stored = 200L,
            expected = EccentricLoad.LOAD_150,
            clamped = 200L to 150,
        )
        assertMapped(
            stored = Int.MAX_VALUE.toLong(),
            expected = EccentricLoad.LOAD_150,
            clamped = Int.MAX_VALUE.toLong() to 150,
        )
        assertMapped(
            stored = Int.MIN_VALUE.toLong(),
            expected = EccentricLoad.LOAD_0,
            clamped = Int.MIN_VALUE.toLong() to 0,
        )
        // Low 32 bits are 0, so toInt() is already in range and no clamp is reported.
        assertMapped(
            stored = Long.MIN_VALUE,
            expected = EccentricLoad.LOAD_0,
        )
        // Low 32 bits are -1, which clamps to 0%.
        assertMapped(
            stored = Long.MAX_VALUE,
            expected = EccentricLoad.LOAD_0,
            clamped = Long.MAX_VALUE to 0,
        )
        // High bits are discarded by toInt(); 100 in the low bits is an exact match.
        assertMapped(
            stored = (1L shl 32) + 100L,
            expected = EccentricLoad.LOAD_100,
        )
        assertMapped(
            stored = (1L shl 32) + 200L,
            expected = EccentricLoad.LOAD_150,
            clamped = ((1L shl 32) + 200L) to 150,
        )
    }

    @Test
    fun mapEccentricLoadFromDb_nearestMatchKeepsEarlierTie() {
        val cases = listOf(
            1 to EccentricLoad.LOAD_0,
            24 to EccentricLoad.LOAD_0,
            25 to EccentricLoad.LOAD_0,
            26 to EccentricLoad.LOAD_50,
            62 to EccentricLoad.LOAD_50,
            63 to EccentricLoad.LOAD_75,
            87 to EccentricLoad.LOAD_75,
            88 to EccentricLoad.LOAD_100,
            105 to EccentricLoad.LOAD_100,
            106 to EccentricLoad.LOAD_110,
            115 to EccentricLoad.LOAD_110,
            116 to EccentricLoad.LOAD_120,
            125 to EccentricLoad.LOAD_120,
            126 to EccentricLoad.LOAD_130,
            135 to EccentricLoad.LOAD_130,
            136 to EccentricLoad.LOAD_140,
            145 to EccentricLoad.LOAD_140,
            146 to EccentricLoad.LOAD_150,
            149 to EccentricLoad.LOAD_150,
        )

        for ((stored, expected) in cases) {
            assertMapped(
                stored = stored.toLong(),
                expected = expected,
                nonStandard = stored,
            )
        }
    }

    @Test
    fun mapEccentricLoadFromDb_everyInRangeValueIsTheFirstNearestEntry() {
        for (stored in 0..150) {
            val actual = mapEccentricLoadFromDb(stored.toLong())
            val distance = abs(actual.percentage - stored)
            val closerOrEarlier = EccentricLoad.entries.filter { candidate ->
                val candidateDistance = abs(candidate.percentage - stored)
                candidateDistance < distance ||
                    (candidateDistance == distance && candidate.ordinal < actual.ordinal)
            }
            assertTrue(
                closerOrEarlier.isEmpty(),
                "stored $stored mapped to $actual ahead of $closerOrEarlier",
            )
        }
    }

    @Test
    fun mapEccentricLoadFromDb_omittedCallbacksMatchTheSilentSyncPath() {
        assertEquals(EccentricLoad.LOAD_100, mapEccentricLoadFromDb(100L))
        assertEquals(EccentricLoad.LOAD_150, mapEccentricLoadFromDb(200L))
        assertEquals(EccentricLoad.LOAD_0, mapEccentricLoadFromDb(25L))
    }

    private fun assertMapped(
        stored: Long,
        expected: EccentricLoad,
        clamped: Pair<Long, Int>? = null,
        nonStandard: Int? = null,
    ) {
        val clampedEvents = mutableListOf<Pair<Long, Int>>()
        val nonStandardEvents = mutableListOf<Int>()
        val load = mapEccentricLoadFromDb(
            stored,
            onClamped = { original, safe -> clampedEvents += original to safe },
            onNonStandard = { nonStandardEvents += it },
        )

        assertEquals(expected, load, stored.toString())
        assertEquals(listOfNotNull(clamped), clampedEvents, stored.toString())
        assertEquals(listOfNotNull(nonStandard), nonStandardEvents, stored.toString())
    }
}
