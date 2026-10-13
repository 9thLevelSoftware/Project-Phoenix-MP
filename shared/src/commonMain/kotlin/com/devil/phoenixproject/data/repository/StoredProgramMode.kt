package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.ProgramMode

/**
 * Stored workout-mode strings and eccentric-load percentages shared by
 * [SqlDelightWorkoutRepository] and [SqlDelightSyncRepository].
 *
 * Clamp and nearest-match warnings stay with the workout repository: it passes
 * [mapEccentricLoadFromDb]'s callbacks. The sync repository leaves them unset.
 */
internal fun parseProgramMode(modeStr: String): ProgramMode = when {
    modeStr.startsWith("Program:") -> {
        val programModeName = modeStr.removePrefix("Program:")
        when (programModeName) {
            "OldSchool" -> ProgramMode.OldSchool
            "Pump" -> ProgramMode.Pump
            "TUT" -> ProgramMode.TUT
            "TUTBeast" -> ProgramMode.TUTBeast
            "EccentricOnly" -> ProgramMode.EccentricOnly
            "Echo" -> ProgramMode.Echo
            else -> ProgramMode.OldSchool
        }
    }

    modeStr == "Echo" || modeStr.startsWith("Echo") -> {
        ProgramMode.Echo
    }

    // Issue #7 Fix: Handle unprefixed legacy mode strings
    modeStr == "Pump" -> ProgramMode.Pump

    modeStr == "TUT" -> ProgramMode.TUT

    modeStr == "TUTBeast" -> ProgramMode.TUTBeast

    modeStr == "EccentricOnly" -> ProgramMode.EccentricOnly

    modeStr == "OldSchool" -> ProgramMode.OldSchool

    else -> ProgramMode.OldSchool
}

internal fun mapEccentricLoadFromDb(
    dbValue: Long,
    onClamped: ((original: Long, safeValue: Int) -> Unit)? = null,
    onNonStandard: ((safeValue: Int) -> Unit)? = null,
): EccentricLoad {
    // Defensive clamping: Machine hardware limit is 150% eccentric load
    // Values > 150% can cause machine faults (yellow light)
    val safeValue = dbValue.toInt().coerceIn(0, 150)
    if (dbValue.toInt() != safeValue) {
        onClamped?.invoke(dbValue, safeValue)
    }

    return when (safeValue) {
        0 -> EccentricLoad.LOAD_0

        50 -> EccentricLoad.LOAD_50

        75 -> EccentricLoad.LOAD_75

        100 -> EccentricLoad.LOAD_100

        110 -> EccentricLoad.LOAD_110

        120 -> EccentricLoad.LOAD_120

        130 -> EccentricLoad.LOAD_130

        140 -> EccentricLoad.LOAD_140

        150 -> EccentricLoad.LOAD_150

        else -> {
            // Value is within 0-150 but not a standard enum value - find closest match
            onNonStandard?.invoke(safeValue)
            EccentricLoad.entries.minByOrNull { kotlin.math.abs(it.percentage - safeValue) }
                ?: EccentricLoad.LOAD_100
        }
    }
}
