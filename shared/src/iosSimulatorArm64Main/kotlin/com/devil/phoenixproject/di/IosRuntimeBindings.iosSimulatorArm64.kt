package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.PhantomBleRepository
import com.russhwolf.settings.Settings
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * Simulator runtime bindings. No persistent credential store or hardware transport implementation is
 * referenced from this source set, so simulator binaries cannot accidentally touch hardware or
 * persisted credentials.
 */
internal actual object IosRuntimeBindings {
    actual fun createBleRepository(): BleRepository = PhantomBleRepository()

    actual fun createSecureSettings(legacySettings: Settings): Settings = InMemorySecureSettings()
}

/** Process-local Settings implementation used only by the iOS simulator target. */
private class InMemorySecureSettings : Settings {
    private val lock = reentrantLock()
    private val values = mutableMapOf<String, Any>()

    override val keys: Set<String>
        get() = lock.withLock { values.keys.toSet() }

    override val size: Int
        get() = lock.withLock { values.size }

    override fun clear() {
        lock.withLock { values.clear() }
    }

    override fun remove(key: String) {
        lock.withLock { values.remove(key) }
    }

    override fun hasKey(key: String): Boolean = lock.withLock { values.containsKey(key) }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        lock.withLock { values[key] as? Boolean ?: defaultValue }

    override fun getBooleanOrNull(key: String): Boolean? =
        lock.withLock { values[key] as? Boolean }

    override fun getDouble(key: String, defaultValue: Double): Double =
        lock.withLock { values[key] as? Double ?: defaultValue }

    override fun getDoubleOrNull(key: String): Double? =
        lock.withLock { values[key] as? Double }

    override fun getFloat(key: String, defaultValue: Float): Float =
        lock.withLock { values[key] as? Float ?: defaultValue }

    override fun getFloatOrNull(key: String): Float? =
        lock.withLock { values[key] as? Float }

    override fun getInt(key: String, defaultValue: Int): Int =
        lock.withLock { values[key] as? Int ?: defaultValue }

    override fun getIntOrNull(key: String): Int? =
        lock.withLock { values[key] as? Int }

    override fun getLong(key: String, defaultValue: Long): Long =
        lock.withLock { values[key] as? Long ?: defaultValue }

    override fun getLongOrNull(key: String): Long? =
        lock.withLock { values[key] as? Long }

    override fun getString(key: String, defaultValue: String): String =
        lock.withLock { values[key] as? String ?: defaultValue }

    override fun getStringOrNull(key: String): String? =
        lock.withLock { values[key] as? String }

    override fun putBoolean(key: String, value: Boolean) {
        lock.withLock { values[key] = value }
    }

    override fun putDouble(key: String, value: Double) {
        lock.withLock { values[key] = value }
    }

    override fun putFloat(key: String, value: Float) {
        lock.withLock { values[key] = value }
    }

    override fun putInt(key: String, value: Int) {
        lock.withLock { values[key] = value }
    }

    override fun putLong(key: String, value: Long) {
        lock.withLock { values[key] = value }
    }

    override fun putString(key: String, value: String) {
        lock.withLock { values[key] = value }
    }
}
