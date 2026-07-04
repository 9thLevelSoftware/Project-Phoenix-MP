package com.devil.phoenixproject.domain.model

/**
 * Issue #333: user-facing tri-state for the BLE small-MTU compatibility path.
 * Resolution to an effective on/off happens in
 * `com.devil.phoenixproject.data.ble.BleCompatibilityMode`.
 */
enum class BleCompatibilitySetting {
    /** Enable automatically on affected devices (Pixel 6/7 family), off elsewhere. */
    AUTO,

    /** Force the small-MTU compatibility path on any device. */
    ON,

    /** Never use the compatibility path, even on affected devices. */
    OFF,
    ;

    companion object {
        fun fromStorage(value: String?): BleCompatibilitySetting =
            entries.find { it.name == value } ?: AUTO
    }
}
