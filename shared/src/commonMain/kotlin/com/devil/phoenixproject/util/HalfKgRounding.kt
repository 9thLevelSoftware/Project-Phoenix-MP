package com.devil.phoenixproject.util

import kotlin.math.roundToInt

/**
 * Round to the nearest 0.5 kg increment.
 * Phoenix machines use 0.5 kg increments, so this keeps programmed weights on a valid step.
 * Ties follow [roundToInt] (toward positive infinity).
 */
fun Float.roundToHalfKg(): Float = (this * 2).roundToInt() / 2f
