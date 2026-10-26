package com.devil.phoenixproject.di

import co.touchlab.kermit.Logger

/** Public Kotlin/Native entry point for the native SwiftUI tvOS host. */
@Throws(Throwable::class)
fun doInitTvosKoin() {
    Logger.i { "tvOS: ========== KOIN INITIALIZATION START ==========" }
    initKoin {}
    Logger.i { "tvOS: ========== KOIN INITIALIZATION SUCCESS ==========" }
}
