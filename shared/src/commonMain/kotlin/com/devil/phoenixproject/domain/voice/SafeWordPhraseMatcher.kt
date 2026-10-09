package com.devil.phoenixproject.domain.voice

/**
 * Pure safe-word phrase match shared by the Android and iOS listeners.
 *
 * The configured phrase and the recognizer transcript are both split on
 * whitespace. Each token is reduced to lowercase letters and digits, so
 * case and attached punctuation ("Stop!", "stop.", "stop,") do not cause a
 * miss — a safety-critical false negative otherwise (audit F062).
 *
 * A match is a run of whole consecutive transcript tokens whose letters and
 * digits equal the phrase's. Matching whole tokens keeps "stop" from matching
 * "stopper" or "stopwatch". Comparing the run's joined text, not token by
 * token, means the recognizer's word split does not matter: "oh no" matches
 * "oh, no!" and "ohno", and "high five" matches "high-five". The previous
 * matcher accepted those single-token forms, and phrases already calibrated
 * against them must keep stopping the machine.
 */
internal object SafeWordPhraseMatcher {
    fun matches(safeWord: String, transcript: String): Boolean {
        val target = normalizeTokens(safeWord).joinToString("")
        if (target.isEmpty()) return false
        val heard = normalizeTokens(transcript)
        return heard.indices.any { start ->
            val run = StringBuilder()
            heard.subList(start, heard.size).any { token ->
                run.append(token)
                run.length == target.length && run.toString() == target
            }
        }
    }

    private fun normalizeTokens(text: String): List<String> =
        text.split(WHITESPACE)
            .map { token -> token.filter { it.isLetterOrDigit() }.lowercase() }
            .filter { it.isNotEmpty() }

    private val WHITESPACE = Regex("\\s+")
}
