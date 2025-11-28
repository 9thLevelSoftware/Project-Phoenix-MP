package com.devil.phoenixproject.di

import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration

fun initKoin(appDeclaration: KoinAppDeclaration = {}) = startKoin {
    appDeclaration()
    modules(commonModule, platformModule)
}

/**
 * Helper function for iOS that doesn't require lambda parameter.
 * Call this from Swift: KoinKt.doInitKoin()
 */
fun doInitKoin() = initKoin {}
