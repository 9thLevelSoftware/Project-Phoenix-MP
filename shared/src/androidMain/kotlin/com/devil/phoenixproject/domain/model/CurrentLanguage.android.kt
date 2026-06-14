package com.devil.phoenixproject.domain.model

import java.util.Locale

/**
 * Android actual for [currentLanguageCode]. Returns the JVM `Locale`'s
 * lowercased language code, e.g. `"en"` / `"it"`. Returns `""` if the
 * default locale is `ROOT` or otherwise has no language component.
 */
actual fun currentLanguageCode(): String =
    Locale.getDefault().language.orEmpty()
