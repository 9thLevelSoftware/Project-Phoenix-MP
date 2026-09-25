package com.devil.phoenixproject.di

import com.devil.phoenixproject.util.installIosDiagnostics

/**
 * Swift entrypoint for Koin initialization.
 * Annotated for Objective-C / Swift error bridging (try/catch in PhoenixAppEntry).
 */
@Throws(Throwable::class)
fun doInitKoin() {
    // Before Koin so init-time crashes are captured and init logs respect the release level.
    installIosDiagnostics()
    doInitKoinInternal()
}
