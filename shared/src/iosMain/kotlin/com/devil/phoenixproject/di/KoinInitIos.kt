package com.devil.phoenixproject.di

/**
 * Swift entrypoint for Koin initialization.
 * Annotated for Objective-C / Swift error bridging (try/catch in VitruvianPhoenixApp).
 */
@Throws(Throwable::class)
fun doInitKoin() {
    doInitKoinInternal()
}
