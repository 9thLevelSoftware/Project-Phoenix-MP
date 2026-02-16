package com.devil.phoenixproject.di

import org.koin.core.module.Module
import org.koin.dsl.module

expect val platformModule: Module

val appModule = module {
    includes(dataModule, syncModule, domainModule, presentationModule)
}
