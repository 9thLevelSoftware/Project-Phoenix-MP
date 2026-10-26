package com.devil.phoenixproject.di

import com.devil.phoenixproject.util.installTvosDiagnostics
import co.touchlab.kermit.Logger

/** Public Kotlin/Native entry point for the native SwiftUI tvOS host. */
@Throws(Throwable::class)
fun doInitTvosKoin() = doInitTvosKoin { initKoin {} }

internal fun doInitTvosKoin(initialize: () -> Unit) {
    installTvosDiagnostics()
    Logger.i { "tvOS: ========== KOIN INITIALIZATION START ==========" }
    initialize()
    Logger.i { "tvOS: ========== KOIN INITIALIZATION SUCCESS ==========" }
}
