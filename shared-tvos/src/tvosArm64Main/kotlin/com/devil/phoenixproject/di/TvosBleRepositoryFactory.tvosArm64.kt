package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.ble.BleScannerCoordinator
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.KableBleRepository

internal actual fun createTvosBleRepository(scannerCoordinator: BleScannerCoordinator): BleRepository =
    KableBleRepository(scannerCoordinator)

internal actual fun isTvosSimulatorFixtureEnabled(): Boolean = false
