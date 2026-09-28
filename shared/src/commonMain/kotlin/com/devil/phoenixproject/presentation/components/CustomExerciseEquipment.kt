package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.domain.model.Exercise

/**
 * Issue #970: the single option table and token logic behind the custom-exercise
 * Equipment dropdown in `CreateExerciseDialog`.
 *
 * Contract (regression-tested in `CustomExerciseEquipmentTest`):
 * - The dialog offers exactly six accessories and stores their existing vocabulary
 *   tokens only: HANDLES (default), SHORT_BAR, BAR (Long Bar), ROPE, BELT,
 *   STRAPS (Ankle Strap). Long Bar is never stored as LONG_BAR/BARBELL and Ankle
 *   Strap is never stored as ANKLE_STRAP.
 * - Bodyweight mode hides the dropdown and saves BODYWEIGHT.
 * - A save without an explicit pick preserves unrecognized existing tokens
 *   (BARBELL, CABLE, comma lists, ...); they are never coerced to HANDLES.
 * - Labels here are the same display names `formatEquipmentCompact` (list subtitle)
 *   and the equipment filter chips use, so the picker can find what this dialog
 *   creates. Do not let the dialog invent a third set of names.
 */
data class CustomEquipmentPreselect(
    /** Resistance-type chip state: true = Cables (dropdown visible), false = Bodyweight. */
    val usesCables: Boolean,
    /**
     * Token written by a save-without-change in Cables mode: the listed option token,
     * or the preserved unrecognized token. BODYWEIGHT for bodyweight-classified rows.
     */
    val selectedToken: String,
    /** Label shown in the closed dropdown field for [selectedToken]. */
    val displayLabel: String,
    /** True when [selectedToken] is one of the six listed accessory options. */
    val isListedOption: Boolean,
)

private const val HANDLES_TOKEN = "HANDLES"
private const val BODYWEIGHT_TOKEN = "BODYWEIGHT"

/**
 * The signed-off accessory options in stable display order, `(token, label)`.
 * Tokens are the existing equipment vocabulary; labels match the list subtitle
 * (`formatEquipmentCompact`) and the exercise-picker filter chips exactly.
 */
fun customCableEquipmentOptions(): List<Pair<String, String>> = listOf(
    HANDLES_TOKEN to "Handles",
    "SHORT_BAR" to "Short Bar",
    "BAR" to "Long Bar",
    "ROPE" to "Rope",
    "BELT" to "Belt",
    "STRAPS" to "Ankle Strap",
)

/**
 * Token stored by the custom-exercise save path.
 *
 * - `usesCables == false` writes BODYWEIGHT unconditionally (no stale cable token).
 * - `usesCables == true` writes the selected listed token, or the preserved
 *   unrecognized token when the user never picked a listed accessory. A blank
 *   selection (nothing chosen yet) defaults to HANDLES.
 */
fun equipmentTokenForCustomSave(usesCables: Boolean, selectedOrPreservedToken: String): String =
    when {
        !usesCables -> BODYWEIGHT_TOKEN
        selectedOrPreservedToken.isBlank() -> HANDLES_TOKEN
        else -> selectedOrPreservedToken
    }

/**
 * Initial dialog state for create/edit.
 *
 * - Create (`existing == null`): Cables on, Handles selected.
 * - Edit bodyweight row (`existing.isBodyweight`, token outside the six options):
 *   Bodyweight on, dropdown hidden.
 * - Edit known accessory token: Cables on, that option selected.
 * - Edit unrecognized non-bodyweight token (BARBELL, CABLE, comma lists, ...):
 *   Cables on; the label falls back to the compact mapping when one exists, else the
 *   raw token; save-without-change writes the same string (see
 *   [equipmentTokenForCustomSave]).
 */
fun preselectCustomEquipment(existing: Exercise?): CustomEquipmentPreselect {
    if (existing == null) {
        return CustomEquipmentPreselect(
            usesCables = true,
            selectedToken = HANDLES_TOKEN,
            displayLabel = labelForCustomEquipmentToken(HANDLES_TOKEN),
            isListedOption = true,
        )
    }

    val rawToken = existing.equipment.trim()
    val listedOption = customCableEquipmentOptions()
        .firstOrNull { (token, _) -> token.equals(rawToken, ignoreCase = true) }

    return when {
        existing.isBodyweight && listedOption == null -> CustomEquipmentPreselect(
            usesCables = false,
            selectedToken = rawToken.ifBlank { BODYWEIGHT_TOKEN },
            displayLabel = labelForCustomEquipmentToken(rawToken.ifBlank { BODYWEIGHT_TOKEN }),
            isListedOption = false,
        )

        listedOption != null -> CustomEquipmentPreselect(
            usesCables = true,
            selectedToken = rawToken,
            displayLabel = listedOption.second,
            isListedOption = true,
        )

        else -> CustomEquipmentPreselect(
            usesCables = true,
            selectedToken = rawToken,
            displayLabel = labelForCustomEquipmentToken(rawToken),
            isListedOption = false,
        )
    }
}

/**
 * Display label for a stored equipment token: the listed option label when it is one
 * of the six, else the same compact mapping `formatEquipmentCompact` uses, else the
 * raw token. The raw fallback is mandatory: `formatEquipmentCompact` is private and
 * drops unknown tokens entirely, which would leave the field blank.
 */
fun labelForCustomEquipmentToken(token: String): String {
    val trimmed = token.trim()
    if (trimmed.isEmpty()) return trimmed

    customCableEquipmentOptions().firstOrNull { (optionToken, _) ->
        optionToken.equals(trimmed, ignoreCase = true)
    }?.let { (_, label) -> return label }

    val compact = trimmed
        .split(",")
        .map { it.trim().uppercase() }
        .filter { it !in COMPACT_EQUIPMENT_NOISE_TOKENS }
        .mapNotNull { COMPACT_EQUIPMENT_LABELS[it] }
        .distinct()
        .joinToString(", ")

    return compact.ifBlank { trimmed }
}

// Mirrors the private `formatEquipmentCompact` map in ExerciseRowContent.kt (see note I1
// in the implementation spec): duplicated on purpose so this helper stays pure and the
// dialog never invents a third set of equipment names.
private val COMPACT_EQUIPMENT_LABELS = mapOf(
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

private val COMPACT_EQUIPMENT_NOISE_TOKENS = setOf(
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
