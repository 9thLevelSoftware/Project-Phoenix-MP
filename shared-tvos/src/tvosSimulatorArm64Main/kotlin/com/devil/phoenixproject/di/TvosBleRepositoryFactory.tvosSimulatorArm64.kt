@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.KableBleRepository
import com.devil.phoenixproject.data.repository.PhantomBleConfig
import com.devil.phoenixproject.data.repository.PhantomBleRepository
import kotlin.native.Platform
import platform.Foundation.NSProcessInfo

private const val TV_PHANTOM_FIXTURE_ARGUMENT = "--phoenix-tv-phantom"

internal actual fun isTvosSimulatorFixtureEnabled(): Boolean =
    Platform.isDebugBinary && NSProcessInfo.processInfo.arguments.contains(TV_PHANTOM_FIXTURE_ARGUMENT)

internal actual fun createTvosBleRepository(): BleRepository =
    if (isTvosSimulatorFixtureEnabled()) {
        PhantomBleRepository(initialConfig = PhantomBleConfig(simulateParsedProgramReps = true))
    } else {
        KableBleRepository()
    }
