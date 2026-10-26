package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.KableBleRepository

internal actual fun createTvosBleRepository(): BleRepository = KableBleRepository()

internal actual fun isTvosSimulatorFixtureEnabled(): Boolean = false
