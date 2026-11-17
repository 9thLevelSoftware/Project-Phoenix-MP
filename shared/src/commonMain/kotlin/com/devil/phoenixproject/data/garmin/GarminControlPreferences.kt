package com.devil.phoenixproject.data.garmin

import com.russhwolf.settings.Settings

/**
 * Namespaced local opt-in/association store for Garmin watch controls (issue #1391 Phase 1).
 *
 * Separate local store — NEVER profile preferences, backups or auth/portal token keys.
 * Association is metadata, not a credential. Grants/epochs/confirmations are memory-only and
 * never persisted here. Forgetting/logout/reinstall clears association and must not restore
 * authority. Opt-in defaults OFF (signoff correction 7: hidden until a matched production
 * watch-app UUID/protocol is configured).
 *
 * FROZEN CONTRACT — packet B2 wires this; keys are fixed by integration-model.md §6.
 */
class GarminControlPreferences(private val settings: Settings) {

    var enabled: Boolean
        get() = settings.getBoolean(KEY_ENABLED, false)
        set(value) {
            settings.putBoolean(KEY_ENABLED, value)
        }

    /** SDK-native device reference of the one selected watch; null = none selected. */
    var selectedDeviceRef: String?
        get() = if (settings.hasKey(KEY_SELECTED_DEVICE_REF)) settings.getString(KEY_SELECTED_DEVICE_REF, "") else null
        set(value) {
            if (value == null) settings.remove(KEY_SELECTED_DEVICE_REF)
            else settings.putString(KEY_SELECTED_DEVICE_REF, value)
        }

    /** Production watch-app UUID that must match native provenance on every inbound message. */
    var watchAppUuid: String?
        get() = if (settings.hasKey(KEY_WATCH_APP_UUID)) settings.getString(KEY_WATCH_APP_UUID, "") else null
        set(value) {
            if (value == null) settings.remove(KEY_WATCH_APP_UUID)
            else settings.putString(KEY_WATCH_APP_UUID, value)
        }

    /** Forget association (Forget device / logout): clears selection and app UUID, keeps nothing authoritative. */
    fun forgetAssociation() {
        settings.remove(KEY_SELECTED_DEVICE_REF)
        settings.remove(KEY_WATCH_APP_UUID)
        settings.remove(KEY_ENABLED)
    }

    companion object {
        const val KEY_ENABLED = "garmin_controls_enabled"
        const val KEY_SELECTED_DEVICE_REF = "garmin_selected_device_ref"
        const val KEY_WATCH_APP_UUID = "garmin_watch_app_uuid"
    }
}
