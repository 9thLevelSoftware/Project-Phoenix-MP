package com.devil.phoenixproject.domain.model

/**
 * The cycle wizard accepts a human-facing load, while persisted baselines are
 * always kilograms per cable.  Keep the conversion at that boundary.
 */
fun Exercise.oneRepMaxInputToPerCableKg(inputKg: Float): Float? =
    displayMultiplier?.let { inputKg / it }

/** Convert a canonical per-cable baseline back to the wizard's total-load input. */
fun Exercise.perCableKgToOneRepMaxInput(perCableKg: Float): Float? =
    displayMultiplier?.let { perCableKg * it }