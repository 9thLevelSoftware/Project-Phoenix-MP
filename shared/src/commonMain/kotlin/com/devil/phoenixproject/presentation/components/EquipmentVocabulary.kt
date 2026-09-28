package com.devil.phoenixproject.presentation.components

/**
 * Single source of truth for the equipment vocabulary (issues #883 / #970).
 *
 * Every consumer of equipment names must read them from here so the three surfaces
 * can never drift apart:
 * - the custom-exercise Equipment dropdown ([customCableEquipmentOptions]),
 * - the exercise-list subtitle ([compactEquipmentLabel]),
 * - the exercise-picker filter chips ([getEquipmentDatabaseValues]).
 *
 * Adding a new equipment token is a one-edit change to this file.
 */
internal object EquipmentVocabulary {
    /**
     * Stored equipment token -> display label. Alias tokens share a label so legacy
     * custom rows restored from old backups still render and filter by name.
     */
    val TOKEN_LABELS: Map<String, String> = mapOf(
        "BAR" to "Long Bar",
        "LONG_BAR" to "Long Bar",
        "BARBELL" to "Long Bar",
        "SHORT_BAR" to "Short Bar",
        "BENCH" to "Bench",
        "HANDLES" to "Handles",
        "SINGLE_HANDLE" to "Handles",
        "BOTH_HANDLES" to "Handles",
        "STRAPS" to "Ankle Strap",
        "ANKLE_STRAP" to "Ankle Strap",
        "BELT" to "Belt",
        "ROPE" to "Rope",
        "BODYWEIGHT" to "Bodyweight",
    )

    /** Tokens dropped from display: cable noise and empties carry no accessory meaning. */
    val NOISE_TOKENS: Set<String> = setOf(
        "BLACK_CABLES",
        "RED_CABLES",
        "GREY_CABLES",
        "CABLES",
        "CABLE",
        "NULL",
        "",
        "PUMP_HANDLES",
        "DUMBBELLS",
    )

    /**
     * Display name -> stored tokens for exercise-picker filtering. Derived from
     * [TOKEN_LABELS] (aliases included) plus the catalogue-only "Cable" chip, which
     * matches CABLE rows even though CABLE is display noise. Consumers treat the
     * lists as sets, so token order is not significant.
     */
    val DISPLAY_NAME_TOKENS: Map<String, List<String>> =
        TOKEN_LABELS.entries.groupBy({ it.value }, { it.key }) + mapOf("Cable" to listOf("CABLE"))
}

/**
 * Compact display form of a raw equipment string: comma-separated tokens mapped to
 * labels, noise and unknown tokens dropped, aliases collapsed. Returns "" when
 * nothing displayable remains — callers supply their own fallback.
 */
internal fun compactEquipmentLabel(rawEquipment: String): String =
    rawEquipment
        .split(",")
        .map { it.trim().uppercase() }
        .filter { it !in EquipmentVocabulary.NOISE_TOKENS }
        .mapNotNull { EquipmentVocabulary.TOKEN_LABELS[it] }
        .distinct()
        .joinToString(", ")
