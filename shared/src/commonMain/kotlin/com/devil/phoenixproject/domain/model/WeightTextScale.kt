package com.devil.phoenixproject.domain.model

/**
 * Issue #1367: global device-local weight-text-size preference.
 *
 * Selects how large weight values render on the safety-critical weight surfaces
 * (Set Ready / Routine Overview / Rest Timer sliders, workout HUD readout, WeightStepper).
 * The factor is the nominal app scale (1 / 1.5 / 2); the RENDERED size is additionally
 * capped against the OS font scale by the style helper
 * (`com.devil.phoenixproject.presentation.util.WeightTextScaling`), so this factor alone
 * is not the effective on-screen scale.
 *
 * This preference is global and device-local: it is deliberately absent from
 * `overlayProfile` and from the backup/portal codecs, so it is never synced or restored.
 */
enum class WeightTextScale(val preferenceFactor: Float) {
    /** Current/base weight typography. */
    STANDARD(1f),

    /** ~1.5x base weight text. */
    LARGE(1.5f),

    /** ~2x base weight text (meets the lower end of the reporter's 2-3x request). */
    EXTRA_LARGE(2f),
    ;

    companion object {
        /**
         * Normalize a persisted enum name. Missing/unknown/corrupt/empty values
         * load as [STANDARD] and never throw (issue #1367 SD6).
         */
        fun fromStorage(value: String?): WeightTextScale =
            entries.find { it.name == value } ?: STANDARD
    }
}
