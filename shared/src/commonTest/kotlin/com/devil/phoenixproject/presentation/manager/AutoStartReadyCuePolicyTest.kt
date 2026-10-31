package com.devil.phoenixproject.presentation.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #1356: [AutoStartReadyCuePolicy] gates the Just Lift / Echo auto-start readiness
 * cue. The cue must fire only when every gate holds: Just Lift, Echo mode, auto-start
 * enabled, beeps enabled, and the start is auto-start-origin.
 */
class AutoStartReadyCuePolicyTest {

    @Test
    fun `all 32 boolean combinations match the conjunction of all five gates`() {
        val axes = listOf("isJustLift", "isEchoMode", "useAutoStart", "beepsEnabled", "autoStartReadyCue")
        val total = 1 shl axes.size
        for (mask in 0 until total) {
            val values = BooleanArray(axes.size) { bit -> mask and (1 shl bit) != 0 }
            val expected = values.all { it }
            val actual = AutoStartReadyCuePolicy.shouldEmit(
                isJustLift = values[0],
                isEchoMode = values[1],
                useAutoStart = values[2],
                beepsEnabled = values[3],
                autoStartReadyCue = values[4],
            )
            assertEquals(
                expected,
                actual,
                "Combination ${axes.zip(values.toList())} should emit=$expected",
            )
        }
        assertEquals(32, total, "Truth table must cover all 2^5 combinations")
    }

    @Test
    fun `all gates satisfied emits`() {
        assertTrue(
            AutoStartReadyCuePolicy.shouldEmit(
                isJustLift = true,
                isEchoMode = true,
                useAutoStart = true,
                beepsEnabled = true,
                autoStartReadyCue = true,
            ),
        )
    }

    @Test
    fun `non-Echo start never emits even when every other gate holds`() {
        assertFalse(
            AutoStartReadyCuePolicy.shouldEmit(
                isJustLift = true,
                isEchoMode = false,
                useAutoStart = true,
                beepsEnabled = true,
                autoStartReadyCue = true,
            ),
        )
    }

    @Test
    fun `manual start origin never emits even when every other gate holds`() {
        assertFalse(
            AutoStartReadyCuePolicy.shouldEmit(
                isJustLift = true,
                isEchoMode = true,
                useAutoStart = true,
                beepsEnabled = true,
                autoStartReadyCue = false,
            ),
        )
    }

    @Test
    fun `beeps disabled never emits even when every other gate holds`() {
        assertFalse(
            AutoStartReadyCuePolicy.shouldEmit(
                isJustLift = true,
                isEchoMode = true,
                useAutoStart = true,
                beepsEnabled = false,
                autoStartReadyCue = true,
            ),
        )
    }

    @Test
    fun `auto-start disabled never emits even when every other gate holds`() {
        assertFalse(
            AutoStartReadyCuePolicy.shouldEmit(
                isJustLift = true,
                isEchoMode = true,
                useAutoStart = false,
                beepsEnabled = true,
                autoStartReadyCue = true,
            ),
        )
    }

    @Test
    fun `non-Just-Lift start never emits even when every other gate holds`() {
        assertFalse(
            AutoStartReadyCuePolicy.shouldEmit(
                isJustLift = false,
                isEchoMode = true,
                useAutoStart = true,
                beepsEnabled = true,
                autoStartReadyCue = true,
            ),
        )
    }
}
