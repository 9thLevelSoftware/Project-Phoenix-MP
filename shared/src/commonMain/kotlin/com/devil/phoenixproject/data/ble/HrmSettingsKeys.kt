package com.devil.phoenixproject.data.ble

import com.russhwolf.settings.Settings

/**
 * Settings-key adapter for HRM remembered-device identity and preferences (SD4).
 *
 * Two key generations exist:
 *  - Canonical (`hrm_*`): the only generation ordinary writes touch.
 *  - Legacy (`polar_h10_*`): read fallback + forget only; never deleted by
 *    ordinary writes and never overwritten after migration (kept purely so a
 *    downgraded build can still read the ORIGINAL values — later canonical-only
 *    edits are intentionally NOT synchronized to legacy).
 *
 * READ precedence (SD4):
 *  1. Canonical presence wins, INCLUDING a stored `false` (presence is checked
 *     with [Settings.hasKey], so a stored `false` is never mistaken for absent).
 *  2. If canonical is absent and legacy is present, the legacy value is used
 *     AND self-healed (written back to the canonical key once).
 *  3. If both generations are absent, defaults apply: auto-reconnect `true`,
 *     raw-ECG storage `false`, remembered name/address `null`. Only genuinely
 *     absent keys receive defaults — a stored legacy `false` stays `false`.
 *
 * WRITE (ordinary, incl. auto-reconnect / raw-ECG toggles): canonical only;
 * legacy keys are NEVER deleted.
 *
 * [forgetRememberedDevice] clears ONLY the remembered name and address in BOTH
 * generations; the auto-reconnect and raw-ECG preference copies are retained in
 * both generations.
 */
internal class HrmSettingsKeys(private val settings: Settings?) {

    companion object {
        // Canonical keys.
        const val KEY_HRM_REMEMBERED_ADDRESS = "hrm_remembered_address"
        const val KEY_HRM_REMEMBERED_NAME = "hrm_remembered_name"
        const val KEY_HRM_AUTO_RECONNECT_ENABLED = "hrm_auto_reconnect_enabled"
        const val KEY_HRM_RAW_ECG_STORAGE_ENABLED = "hrm_raw_ecg_storage_enabled"

        // Legacy keys — kept as constants for fallback reads and forget only.
        const val LEGACY_KEY_POLAR_H10_REMEMBERED_ADDRESS = "polar_h10_remembered_address"
        const val LEGACY_KEY_POLAR_H10_REMEMBERED_NAME = "polar_h10_remembered_name"
        const val LEGACY_KEY_POLAR_H10_AUTO_RECONNECT_ENABLED = "polar_h10_auto_reconnect_enabled"
        const val LEGACY_KEY_POLAR_H10_RAW_ECG_STORAGE_ENABLED = "polar_h10_raw_ecg_storage_enabled"
    }

    fun getRememberedAddress(): String? =
        readString(KEY_HRM_REMEMBERED_ADDRESS, LEGACY_KEY_POLAR_H10_REMEMBERED_ADDRESS)

    fun getRememberedName(): String? =
        readString(KEY_HRM_REMEMBERED_NAME, LEGACY_KEY_POLAR_H10_REMEMBERED_NAME)

    fun getAutoReconnectEnabled(): Boolean = readBoolean(
        KEY_HRM_AUTO_RECONNECT_ENABLED,
        LEGACY_KEY_POLAR_H10_AUTO_RECONNECT_ENABLED,
        default = true,
    )

    fun getRawEcgStorageEnabled(): Boolean = readBoolean(
        KEY_HRM_RAW_ECG_STORAGE_ENABLED,
        LEGACY_KEY_POLAR_H10_RAW_ECG_STORAGE_ENABLED,
        default = false,
    )

    /** Ordinary write: canonical only. Legacy copies are never deleted or updated. */
    fun rememberDevice(address: String, name: String) {
        val s = settings ?: return
        s.putString(KEY_HRM_REMEMBERED_ADDRESS, address)
        s.putString(KEY_HRM_REMEMBERED_NAME, name)
    }

    /** Ordinary write: canonical only. Never deletes the legacy key. */
    fun setAutoReconnectEnabled(enabled: Boolean) {
        settings?.putBoolean(KEY_HRM_AUTO_RECONNECT_ENABLED, enabled)
    }

    /** Ordinary write: canonical only. Never deletes the legacy key. */
    fun setRawEcgStorageEnabled(enabled: Boolean) {
        settings?.putBoolean(KEY_HRM_RAW_ECG_STORAGE_ENABLED, enabled)
    }

    /**
     * Forget clears only the remembered name and address in BOTH generations.
     * The auto-reconnect and raw-ECG storage preferences are retained in both.
     */
    fun forgetRememberedDevice() {
        val s = settings ?: return
        s.remove(KEY_HRM_REMEMBERED_ADDRESS)
        s.remove(KEY_HRM_REMEMBERED_NAME)
        s.remove(LEGACY_KEY_POLAR_H10_REMEMBERED_ADDRESS)
        s.remove(LEGACY_KEY_POLAR_H10_REMEMBERED_NAME)
    }

    private fun readString(canonical: String, legacy: String): String? {
        val s = settings ?: return null
        return when {
            // Canonical presence wins, even when the stored value is empty.
            s.hasKey(canonical) -> s.getStringOrNull(canonical)
            // Legacy fallback, self-healed into canonical (only when canonical absent).
            s.hasKey(legacy) -> s.getStringOrNull(legacy)?.also { value ->
                s.putString(canonical, value)
            }
            else -> null
        }
    }

    private fun readBoolean(canonical: String, legacy: String, default: Boolean): Boolean {
        val s = settings ?: return default
        return when {
            // Canonical presence wins INCLUDING a stored false.
            s.hasKey(canonical) -> s.getBoolean(canonical, default)
            // Legacy fallback, self-healed (a stored legacy false stays false).
            s.hasKey(legacy) -> s.getBoolean(legacy, default).also { value ->
                s.putBoolean(canonical, value)
            }
            else -> default
        }
    }
}
