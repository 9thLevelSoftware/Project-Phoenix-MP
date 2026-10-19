package com.devil.phoenixproject.data.local

/**
 * Legacy name and label cleanup previously copied in `MigrationManager` and
 * `DataBackupManager`.
 *
 * Both copies trimmed the raw value, dropped a blank value, dropped a
 * case-insensitive `"null"`, dropped a token with no letter or digit, and
 * returned the trimmed spelling. [sanitize] is that function.
 * [sanitizeEntityName], [sanitizeRoutineName], and [normalizeExerciseToken]
 * were the same wrappers in both classes, including the single garbage routine
 * name `"imported strength training session"`.
 *
 * Routine-session id cleanup stays on each caller. Backup drops every id that
 * starts with `legacy_session_`. Migration deletes a stored id only when it
 * equals `legacy_session_` plus that session's id, and that cleanup does not
 * run this filter.
 */
internal object LegacyLabelSanitizer {
    private val GARBAGE_ROUTINE_NAMES = setOf(
        "imported strength training session",
    )

    fun sanitize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.equals("null", ignoreCase = true)) return null
        if (!trimmed.any { it.isLetterOrDigit() }) return null
        return trimmed
    }

    fun sanitizeEntityName(raw: String?, fallback: String): String = sanitize(raw) ?: fallback

    fun sanitizeRoutineName(raw: String?): String? {
        val sanitized = sanitize(raw) ?: return null
        if (sanitized.lowercase().trim() in GARBAGE_ROUTINE_NAMES) return null
        return sanitized
    }

    fun normalizeExerciseToken(raw: String?): String? {
        val sanitized = sanitize(raw) ?: return null
        val collapsedWhitespace = sanitized
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
        return collapsedWhitespace.ifEmpty { null }
    }
}
