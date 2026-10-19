package com.devil.phoenixproject.data.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LegacyLabelSanitizerTest {

    @Test
    fun sanitizeDropsBlankNullAndPunctuationOnlyTokens() {
        assertNull(LegacyLabelSanitizer.sanitize(null))
        assertNull(LegacyLabelSanitizer.sanitize(""))
        assertNull(LegacyLabelSanitizer.sanitize("   "))
        assertNull(LegacyLabelSanitizer.sanitize("\t\n"))
        assertNull(LegacyLabelSanitizer.sanitize("null"))
        assertNull(LegacyLabelSanitizer.sanitize("NULL"))
        assertNull(LegacyLabelSanitizer.sanitize(" Null "))
        assertNull(LegacyLabelSanitizer.sanitize("nUlL"))
        assertNull(LegacyLabelSanitizer.sanitize(":"))
        assertNull(LegacyLabelSanitizer.sanitize("--"))
        assertNull(LegacyLabelSanitizer.sanitize("..."))
        assertNull(LegacyLabelSanitizer.sanitize("()"))
        assertNull(LegacyLabelSanitizer.sanitize("—"))
    }

    @Test
    fun sanitizeKeepsTrimmedSpellingWhenAnyLetterOrDigitRemains() {
        assertEquals("Bench Press", LegacyLabelSanitizer.sanitize("  Bench Press  "))
        assertEquals("nullify", LegacyLabelSanitizer.sanitize("nullify"))
        assertEquals("null set", LegacyLabelSanitizer.sanitize(" null set "))
        assertEquals("12.5", LegacyLabelSanitizer.sanitize(" 12.5 "))
        assertEquals("a:b", LegacyLabelSanitizer.sanitize(" a:b "))
        assertEquals("0", LegacyLabelSanitizer.sanitize("0"))
        assertEquals("Ω", LegacyLabelSanitizer.sanitize(" Ω "))
    }

    @Test
    fun sanitizeLeavesRoutineSessionIdsUntouched() {
        // Session-id policy stays on the callers. This helper only trims.
        assertEquals(
            "legacy_session_session-1",
            LegacyLabelSanitizer.sanitize(" legacy_session_session-1 "),
        )
        assertEquals(
            "LEGACY_SESSION_OTHER",
            LegacyLabelSanitizer.sanitize("LEGACY_SESSION_OTHER"),
        )
    }

    @Test
    fun entityNameUsesTheFallbackOnlyWhenTheLabelIsMissing() {
        assertEquals("Unnamed Routine", LegacyLabelSanitizer.sanitizeEntityName(null, "Unnamed Routine"))
        assertEquals("Unnamed Routine", LegacyLabelSanitizer.sanitizeEntityName(" null ", "Unnamed Routine"))
        assertEquals("Unnamed Routine", LegacyLabelSanitizer.sanitizeEntityName("--", "Unnamed Routine"))
        assertEquals("Unnamed Cycle", LegacyLabelSanitizer.sanitizeEntityName("   ", "Unnamed Cycle"))
        assertEquals("Push Day", LegacyLabelSanitizer.sanitizeEntityName("  Push Day  ", "Unnamed Routine"))
        // The fallback is returned as given. It is not run through sanitize.
        assertEquals("null", LegacyLabelSanitizer.sanitizeEntityName(":", "null"))
    }

    @Test
    fun routineNameDropsTheImportPlaceholderAndKeepsOtherSpelling() {
        assertNull(LegacyLabelSanitizer.sanitizeRoutineName(null))
        assertNull(LegacyLabelSanitizer.sanitizeRoutineName("null"))
        assertNull(LegacyLabelSanitizer.sanitizeRoutineName(":"))
        assertNull(LegacyLabelSanitizer.sanitizeRoutineName("Imported Strength Training Session"))
        assertNull(LegacyLabelSanitizer.sanitizeRoutineName("  IMPORTED STRENGTH TRAINING SESSION  "))
        assertEquals(
            "Imported Strength Training Session!",
            LegacyLabelSanitizer.sanitizeRoutineName(" Imported Strength Training Session! "),
        )
        assertEquals(
            "imported  strength training session",
            LegacyLabelSanitizer.sanitizeRoutineName("  imported  strength training session  "),
        )
        assertEquals("Upper Day", LegacyLabelSanitizer.sanitizeRoutineName(" Upper Day "))
    }

    @Test
    fun exerciseTokenLowercasesAndCollapsesWhitespaceAfterSanitize() {
        assertNull(LegacyLabelSanitizer.normalizeExerciseToken(null))
        assertNull(LegacyLabelSanitizer.normalizeExerciseToken("NULL"))
        assertNull(LegacyLabelSanitizer.normalizeExerciseToken(":"))
        assertNull(LegacyLabelSanitizer.normalizeExerciseToken("   "))
        assertEquals("bench press", LegacyLabelSanitizer.normalizeExerciseToken("  Bench   Press "))
        assertEquals("a b", LegacyLabelSanitizer.normalizeExerciseToken("A\t\nB"))
        assertEquals(
            "imported strength training session",
            LegacyLabelSanitizer.normalizeExerciseToken("imported  strength training session"),
        )
        assertEquals("ω", LegacyLabelSanitizer.normalizeExerciseToken(" Ω "))
    }
}
