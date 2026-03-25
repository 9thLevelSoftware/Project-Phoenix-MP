package com.devil.phoenixproject.util

/**
 * Echo mode BLE control frame parameters.
 *
 * Field names match the official Vitruvian app's deobfuscated EchoForceConfig (Ek/C1516m)
 * and EchoPhase (Ek/C1517n) classes. The firmware interprets these at fixed byte offsets
 * within the 32-byte Echo command (0x4E).
 *
 * Byte layout (offsets within the Echo frame body, starting after header + rep bytes):
 *   0x06-0x07  spotter (short LE)              — always 0 in official app
 *   0x08-0x09  eccentricOverload (short LE)     — user-configured eccentric load %
 *   0x0A-0x0B  referenceMapBlend (short LE)     — force map blend factor (always 50)
 *   0x0C-0x0F  concentricDelayS (float LE)      — delay before concentric force (always 0.1s)
 *   0x10-0x13  concentricDurationSeconds (float) — 50.0 / velocity, controls concentric phase speed
 *   0x14-0x17  concentricMaxVelocity (float LE)  — max cable velocity in concentric phase (deg/s)
 *   0x18-0x1B  eccentricDurationSeconds (float)  — eccentric phase duration (always 0.0)
 *   0x1C-0x1F  eccentricMaxVelocity (float LE)   — max cable velocity in eccentric phase (-200.0)
 */
data class EchoParams(
    /** Eccentric overload percentage (0-150%). Firmware offset 0x08. */
    val eccentricOverload: Int,
    /** Reference map blend factor. Firmware offset 0x0A. Always 50 in official app. */
    val referenceMapBlend: Int,
    /** Concentric phase delay in seconds. Firmware offset 0x0C. Always 0.1 in official app. */
    val concentricDelayS: Float,
    /** Concentric phase duration = 50.0 / velocity. Firmware offset 0x10. */
    val concentricDurationSeconds: Float,
    /** Max cable velocity during concentric phase (deg/s). Firmware offset 0x14. */
    val concentricMaxVelocity: Float,
    /** Eccentric phase duration. Firmware offset 0x18. Always 0.0 in official app. */
    val eccentricDurationSeconds: Float,
    /** Max cable velocity during eccentric phase. Firmware offset 0x1C. -200.0 in official app. */
    val eccentricMaxVelocity: Float
)
