package com.devil.phoenixproject.domain.voice

/**
 * Pure safe-word phrase match shared by the Android and iOS listeners.
 *
 * The configured phrase and the recognizer transcript are both split on
 * whitespace. Each token is reduced to lowercase letters and digits, so
 * case and attached punctuation ("Stop!", "stop.", "stop,") do not cause a
 * miss — a safety-critical false negative otherwise (audit F062).
 *
 * A match is an exact consecutive run of those tokens. A single-word phrase
 * matches only that token ("stop" matches inside "please stop now", and does
 * not match "stopper"). A multi-word phrase matches the same way: "oh no"
 * matches the tokens "oh" then "no", and does not match "oh please no" or a
 * single token "ohno".
 */
internal object SafeWordPhraseMatcher {
    fun matches(safeWord: String, transcript: String): Boolean {
        val phrase = normalizeTokens(safeWord)
        if (phrase.isEmpty()) return false
        val heard = normalizeTokens(transcript)
        if (heard.size < phrase.size) return false
        return heard.windowed(phrase.size).any { window -> window == phrase }
    }

    private fun normalizeTokens(text: String): List<String> =
        text.split(WHITESPACE)
            .map { token -> token.filter { it.isLetterOrDigit() }.lowercase() }
            .filter { it.isNotEmpty() }

    private val WHITESPACE = Regex("\\s+")
}
