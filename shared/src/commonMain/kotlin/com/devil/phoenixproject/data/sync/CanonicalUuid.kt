package com.devil.phoenixproject.data.sync

/**
 * Portal-sync UUID gate previously copied in `SyncManager` and `SqlDelightSyncRepository`.
 *
 * The two copies were the same pattern: a full 8-4-4-4-12 hex id, ASCII
 * case-insensitive, anchored so a prefix such as `cycle_routine_` does not match.
 * Version and variant nibbles are not restricted. The nil UUID matches; it is the
 * empty-list sentinel the pull request sends.
 *
 * [matches] is the check both callers use. A match is not rewritten: push and
 * known-id lists keep the stored spelling, including uppercase. [canonicalOrNull]
 * is the lowercase spelling of a match for a caller that needs one form. It does
 * not trim.
 *
 * Routine identity keeps its own equivalent hex check. That check's canonicalize
 * step leaves opaque ids unchanged, and this gate must reject them, so the two
 * were not folded into one function.
 */
internal object CanonicalUuid {
    private val PATTERN = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        RegexOption.IGNORE_CASE,
    )

    fun matches(value: String): Boolean = PATTERN.matches(value)

    /** Lowercase spelling when [value] matches; null otherwise. */
    fun canonicalOrNull(value: String): String? = if (matches(value)) value.lowercase() else null
}
