package com.devil.phoenixproject.data.repository

/**
 * Convert a VBT assessment total 1RM (both cables combined, as shown on the machine)
 * to per-cable kg for [com.devil.phoenixproject.domain.model.Exercise.oneRepMaxKg] storage.
 */
internal fun assessmentTotalOneRmToPerCableKg(
    totalOneRepMaxKg: Float,
    physicalCableCount: Int,
): Float = totalOneRepMaxKg / physicalCableCount.coerceAtLeast(1).toFloat()
