package com.devil.phoenixproject.ui.theme

enum class NightSample {
    YES,
    NO,
    UNDEFINED,
}

/** Android `Configuration.UI_MODE_NIGHT_YES` — kept as a hex so common code needs no Android import. */
const val UI_MODE_NIGHT_YES_MASK = 0x20

/** Android `Configuration.UI_MODE_NIGHT_NO`. */
const val UI_MODE_NIGHT_NO_MASK = 0x10

fun nightSampleFromMask(nightMask: Int): NightSample = when (nightMask) {
    UI_MODE_NIGHT_YES_MASK -> NightSample.YES
    UI_MODE_NIGHT_NO_MASK -> NightSample.NO
    else -> NightSample.UNDEFINED
}

fun resolveSystemDark(
    previous: Boolean,
    applicationNight: NightSample,
    activityNight: NightSample,
    composeNight: Boolean,
): Boolean = when {
    applicationNight == NightSample.YES -> true
    applicationNight == NightSample.NO -> false
    activityNight == NightSample.YES -> true
    activityNight == NightSample.NO -> if (composeNight) previous else false
    else -> previous
}

fun resolveUseDarkColors(themeMode: ThemeMode, systemDark: Boolean): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
