package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.ble.BleScannerCoordinator
import com.devil.phoenixproject.data.repository.BleRepository

/** Platform target supplies production BLE except for the explicit simulator-only debug fixture. */
internal expect fun createTvosBleRepository(scannerCoordinator: BleScannerCoordinator): BleRepository

/** True only when a Debug tvOS Simulator process was launched with the explicit fixture argument. */
internal expect fun isTvosSimulatorFixtureEnabled(): Boolean
