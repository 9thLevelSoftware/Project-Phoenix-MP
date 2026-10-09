package com.devil.phoenixproject.domain.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phrase matching for the voice emergency stop. Settings accept up to a
 * 40-character phrase, so the matcher has to treat whitespace as token
 * boundaries instead of deleting it and requiring one speech token.
 */
class SafeWordPhraseMatcherTest {
    @Test
    fun `single word matches only the exact token`() {
        assertTrue(SafeWordPhraseMatcher.matches("stop", "stop"))
        assertTrue(SafeWordPhraseMatcher.matches("stop", "please stop now"))
        assertFalse(SafeWordPhraseMatcher.matches("stop", "stopper"))
        assertFalse(SafeWordPhraseMatcher.matches("stop", "unstop"))
        assertFalse(SafeWordPhraseMatcher.matches("stop", "stopping"))
        assertFalse(SafeWordPhraseMatcher.matches("stop", "sto p"))
    }

    @Test
    fun `multi word phrase matches consecutive speech tokens`() {
        assertTrue(SafeWordPhraseMatcher.matches("oh no", "oh no"))
        assertTrue(SafeWordPhraseMatcher.matches("oh no", "please oh no now"))
        assertTrue(SafeWordPhraseMatcher.matches("oh  no", "oh no"))
        assertTrue(SafeWordPhraseMatcher.matches("please stop now", "ok please stop now thanks"))
        assertFalse(SafeWordPhraseMatcher.matches("oh no", "ohno"))
        assertFalse(SafeWordPhraseMatcher.matches("oh no", "oh"))
        assertFalse(SafeWordPhraseMatcher.matches("oh no", "no"))
    }

    @Test
    fun `multi word phrase does not match when the tokens are not consecutive`() {
        assertFalse(SafeWordPhraseMatcher.matches("oh no", "oh please no"))
        assertFalse(SafeWordPhraseMatcher.matches("oh no", "no oh"))
        assertFalse(SafeWordPhraseMatcher.matches("oh no", "oh wait no"))
        assertFalse(SafeWordPhraseMatcher.matches("please stop now", "please now stop"))
    }

    @Test
    fun `case and punctuation normalize like the previous per token filter`() {
        assertTrue(SafeWordPhraseMatcher.matches("Stop", "STOP"))
        assertTrue(SafeWordPhraseMatcher.matches("stop", "stop!"))
        assertTrue(SafeWordPhraseMatcher.matches("stop", "stop."))
        assertTrue(SafeWordPhraseMatcher.matches("stop", "stop, now"))
        assertTrue(SafeWordPhraseMatcher.matches("Oh No", "oh, no!"))
        assertTrue(SafeWordPhraseMatcher.matches("don't", "Don't!"))
        assertTrue(SafeWordPhraseMatcher.matches("dont", "don't"))
        assertTrue(SafeWordPhraseMatcher.matches("well-known", "well-known"))
        assertFalse(SafeWordPhraseMatcher.matches("well-known", "well known"))
        assertFalse(SafeWordPhraseMatcher.matches("", "stop"))
        assertFalse(SafeWordPhraseMatcher.matches("   ", "stop"))
        assertFalse(SafeWordPhraseMatcher.matches("!!!", "stop"))
        assertFalse(SafeWordPhraseMatcher.matches("stop", ""))
    }
}
