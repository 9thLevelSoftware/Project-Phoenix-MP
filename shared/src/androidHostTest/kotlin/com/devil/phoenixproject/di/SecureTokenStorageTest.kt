package com.devil.phoenixproject.di

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests the production Android token migration, [migrateTokensToEncrypted] (#869: this file
 * used to test a hand-copied algorithm). An in-memory [SharedPreferences] stands in for the
 * plaintext and EncryptedSharedPreferences stores; the Keystore-backed encryption itself
 * needs an instrumented test, and iOS KeychainSettings needs macOS.
 */
class SecureTokenStorageTest {

    @Test
    fun portalKeysMoveToEncryptedStorageAndLeavePlaintext() {
        val plain = InMemorySharedPreferences().apply {
            edit()
                .putString("portal_auth_token", "jwt")
                .putString("portal_refresh_token", "refresh")
                .putLong("portal_token_expires_at", 42L)
                .putBoolean("portal_user_is_premium", true)
                .putString("units", "kg")
                .commit()
        }
        val encrypted = InMemorySharedPreferences()

        migrateTokensToEncrypted(plain, encrypted)

        assertThat(encrypted.getString("portal_auth_token", null)).isEqualTo("jwt")
        assertThat(encrypted.getString("portal_refresh_token", null)).isEqualTo("refresh")
        assertThat(encrypted.getLong("portal_token_expires_at", 0L)).isEqualTo(42L)
        assertThat(encrypted.getBoolean("portal_user_is_premium", false)).isTrue()
        PORTAL_KEYS.forEach { key -> assertThat(plain.contains(key)).isFalse() }
        assertThat(plain.getString("units", null)).isEqualTo("kg")
        assertThat(encrypted.contains("units")).isFalse()
    }

    @Test
    fun anExistingEncryptedValueIsNeverOverwritten() {
        val plain = InMemorySharedPreferences().apply {
            edit().putString("portal_auth_token", "stale-plaintext").commit()
        }
        val encrypted = InMemorySharedPreferences().apply {
            edit().putString("portal_auth_token", "current").commit()
        }

        migrateTokensToEncrypted(plain, encrypted)

        assertThat(encrypted.getString("portal_auth_token", null)).isEqualTo("current")
        assertThat(plain.contains("portal_auth_token")).isFalse()
    }

    @Test
    fun aFailedEncryptedWriteKeepsThePlaintextTokensForTheNextLaunch() {
        val plain = InMemorySharedPreferences().apply {
            edit().putString("portal_auth_token", "jwt").putString("portal_user_id", "u1").commit()
        }
        val encrypted = InMemorySharedPreferences(commitSucceeds = false)

        migrateTokensToEncrypted(plain, encrypted)

        assertThat(plain.getString("portal_auth_token", null)).isEqualTo("jwt")
        assertThat(plain.getString("portal_user_id", null)).isEqualTo("u1")
        assertThat(encrypted.contains("portal_auth_token")).isFalse()
    }

    @Test
    fun migrationIsIdempotentAndANoOpWithoutPortalKeys() {
        val plain = InMemorySharedPreferences().apply {
            edit().putString("portal_device_id", "device").commit()
        }
        val encrypted = InMemorySharedPreferences()

        migrateTokensToEncrypted(plain, encrypted)
        migrateTokensToEncrypted(plain, encrypted)

        assertThat(encrypted.getString("portal_device_id", null)).isEqualTo("device")
        assertThat(encrypted.all).hasSize(1)

        val untouched = InMemorySharedPreferences().apply { edit().putString("units", "lb").commit() }
        migrateTokensToEncrypted(untouched, InMemorySharedPreferences())
        assertThat(untouched.getString("units", null)).isEqualTo("lb")
    }

    /** Map-backed [SharedPreferences]; [commitSucceeds] = false makes every commit a no-op failure. */
    private class InMemorySharedPreferences(private val commitSucceeds: Boolean = true) : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            values[key] as? Set<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = key in values
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val puts = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { puts[key] = values?.toSet() }
            override fun putInt(key: String, value: Int) = apply { puts[key] = value }
            override fun putLong(key: String, value: Long) = apply { puts[key] = value }
            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
            override fun remove(key: String) = apply { removals += key }
            override fun clear() = apply { clear = true }

            override fun commit(): Boolean {
                if (!commitSucceeds) return false
                if (clear) values.clear()
                removals.forEach(values::remove)
                values.putAll(puts)
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
