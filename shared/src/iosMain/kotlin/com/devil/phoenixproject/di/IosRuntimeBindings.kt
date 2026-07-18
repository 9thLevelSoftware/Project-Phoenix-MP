package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.repository.BleRepository
import com.russhwolf.settings.Settings

/**
 * Compile-time iOS runtime split. Device builds retain hardware-backed implementations;
 * simulator builds deliberately avoid linking CoreBluetooth/Keychain code paths.
 */
internal expect object IosRuntimeBindings {
    fun createBleRepository(): BleRepository

    fun createSecureSettings(legacySettings: Settings): Settings
}
