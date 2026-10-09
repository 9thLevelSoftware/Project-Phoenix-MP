package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Calls the real iOS [currentLanguageCode] actual. The simulator (and the
 * device) always has at least one `AppleLanguages` entry, so a null bridge
 * or a failed cast would show up here as an empty string.
 */
class CurrentLanguageIosTest {
    @Test
    fun currentLanguageCodeReturnsNonEmptyLanguageSubtag() {
        val code = currentLanguageCode()
        assertTrue(
            code.isNotEmpty(),
            "AppleLanguages should yield a non-empty language code, got '$code'",
        )
        assertTrue(
            code.none { it == '-' || it == '_' },
            "expected the language subtag only, got '$code'",
        )
    }
}
