package com.devil.phoenixproject.ui.theme

enum class NightSample {
    YES,
    NO,
    UNDEFINED,
}

/** Android Configuration.UI_MODE_NIGHT_* mapped without an Android import. */
fun nightSampleFromMask(nightMask: Int): NightSample = when (nightMask) {
    0x20 -> NightSample.YES
    0x10 -> NightSample.NO
    else -> NightSample.UNDEFINED
}

fun resolveSystemDark(
    previous: Boolean,
    applicationNight: NightSample,
    activityNight: NightSample,
    composeNight: Boolean,
): Boolean {
    when (applicationNight) {
        NightSample.YES -> return true
        NightSample.NO -> return false
        NightSample.UNDEFINED -> Unit
    }
    return when (activityNight) {
        NightSample.YES -> true
        NightSample.NO -> if (composeNight) previous else false
        NightSample.UNDEFINED -> previous
    }
}

fun resolveUseDarkColors(themeMode: ThemeMode, systemDark: Boolean): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
