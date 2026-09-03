package com.devil.phoenixproject.di

/**
 * Swift entrypoint for Koin initialization.
 * Annotated for Objective-C / Swift error bridging (try/catch in PhoenixAppEntry).
 */
@Throws(Throwable::class)
fun doInitKoin() {
    doInitKoinInternal()
}
