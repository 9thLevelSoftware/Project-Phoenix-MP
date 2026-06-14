package com.devil.phoenixproject.domain.model

/**
 * Returns the BCP-47 language subtag of the active app locale, lowercased
 * (e.g. `"en"`, `"it"`, `"de"`). Returns `""` when the language cannot be
 * determined.
 *
 * This is a multiplatform-friendly alternative to reading
 * `LocalConfiguration.current.locales`, which is not exposed in the
 * Compose Multiplatform iOS klib (CMP 1.10.3) and would not compile for
 * `iosArm64`. We delegate to platform-native APIs:
 *
 *  - Android: `java.util.Locale.getDefault().language`
 *  - iOS: `NSUserDefaults.standardUserDefaults` first entry of `AppleLanguages`
 *    (the user-selected preferred language list, which is exactly the
 *    Italian iPhone setting from issue #540).
 *
 * The returned value is the *language* subtag only — no region, no script.
 * Tests for the Italian NBSP branch check `equals("it", ignoreCase = true)`.
 */
expect fun currentLanguageCode(): String
