package com.devil.phoenixproject.domain.model

/**
 * Runtime-only bodyweight variant selection used to calculate effective load and volume.
 *
 * This intentionally does not change persisted workout schema; it carries the user's current
 * set selection through the active workout flow.
 */
data class BodyweightVariantOption(
    val label: String,
    val percentage: Float,
)
