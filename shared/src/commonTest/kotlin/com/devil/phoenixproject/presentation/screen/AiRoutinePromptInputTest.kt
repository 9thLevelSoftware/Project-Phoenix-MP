package com.devil.phoenixproject.presentation.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #1223 FE-C behavioral tests for the prompt-screen input rules (pure,
 * non-composable): the 1000-char prompt cap, the optional 10–120-minute field,
 * the leading-duration hint, and the "Generate disabled until valid input" rule.
 */
class AiRoutinePromptInputTest {

    @Test
    fun leadingDurationInThePromptMayFillTheMinutesField() {
        assertEquals(35, parseLeadingDurationMinutes("35-min upper body workout avoiding shoulders"))
        assertEquals(45, parseLeadingDurationMinutes("45 minutes of chest and back"))
        assertEquals(120, parseLeadingDurationMinutes("120 minute full body"))
        assertEquals(10, parseLeadingDurationMinutes("  10m row intervals"))

        // No duration hint, out-of-range hints, and non-duration numbers do not fill.
        assertNull(parseLeadingDurationMinutes("upper body with supersets"))
        assertNull(parseLeadingDurationMinutes("5 sets of bench press"))
        assertNull(parseLeadingDurationMinutes("300-minute marathon"))
        assertNull(parseLeadingDurationMinutes("5-min warmup"))
    }

    @Test
    fun minutesFieldIsValidatedAgainstThe10To120Range() {
        assertEquals(10, validAiRoutineTargetMinutes("10"))
        assertEquals(120, validAiRoutineTargetMinutes(" 120 "))
        assertEquals(35, validAiRoutineTargetMinutes("35"))
        assertNull(validAiRoutineTargetMinutes("9"))
        assertNull(validAiRoutineTargetMinutes("121"))
        assertNull(validAiRoutineTargetMinutes("abc"))
        assertNull(validAiRoutineTargetMinutes(""))

        // The field is optional: blank means no target at all.
        assertNull(aiRoutineTargetMinutesOrNull(""))
        assertNull(aiRoutineTargetMinutesOrNull("   "))
        assertEquals(30, aiRoutineTargetMinutesOrNull("30"))
    }

    @Test
    fun generateIsDisabledUntilTheInputIsValid() {
        assertTrue(isValidAiRoutineInput("35-min upper body", ""))
        assertTrue(isValidAiRoutineInput("arm day", "45"))
        assertFalse(isValidAiRoutineInput("", "45"), "blank prompt")
        assertFalse(isValidAiRoutineInput("   ", "45"), "whitespace-only prompt")
        assertFalse(isValidAiRoutineInput("arm day", "7"), "invalid minutes")
        assertFalse(isValidAiRoutineInput("arm day", "999"), "invalid minutes")
    }

    @Test
    fun promptIsCappedAtTheServerLimitOf1000Chars() {
        val atLimit = "x".repeat(AI_ROUTINE_MAX_PROMPT_CHARS)
        assertTrue(isValidAiRoutineInput(atLimit, ""))
        assertFalse(isValidAiRoutineInput(atLimit + "x", ""), "1001 chars must be rejected")
    }
}
