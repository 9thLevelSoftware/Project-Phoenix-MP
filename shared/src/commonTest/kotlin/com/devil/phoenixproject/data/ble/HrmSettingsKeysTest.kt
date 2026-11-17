package com.devil.phoenixproject.data.ble

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SD4 settings-key migration matrix (issue #1359, packet P2) against a real
 * [MapSettings] store — no mocking. Covers T18–T25 of the spec test plan.
 */
class HrmSettingsKeysTest {

    // T18: legacy name/address read into state + self-heal written to canonical.
    @Test
    fun `T18 legacy remembered name and address are read into state and self-healed to canonical`() {
        val settings = MapSettings()
        settings.putString(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_REMEMBERED_ADDRESS, "24:AC:AC:0E:5B:59")
        settings.putString(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_REMEMBERED_NAME, "Legacy Strap")

        val manager = HrmBleManager(settings = settings)

        assertEquals("24:AC:AC:0E:5B:59", manager.rememberedAddress.value)
        assertEquals("Legacy Strap", manager.rememberedName.value)
        // Self-heal write-back: canonical keys now hold the migrated values.
        assertEquals(
            "24:AC:AC:0E:5B:59",
            settings.getStringOrNull(HrmSettingsKeys.KEY_HRM_REMEMBERED_ADDRESS),
        )
        assertEquals(
            "Legacy Strap",
            settings.getStringOrNull(HrmSettingsKeys.KEY_HRM_REMEMBERED_NAME),
        )
        // Read path never deletes the legacy copies.
        assertTrue(settings.hasKey(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_REMEMBERED_ADDRESS))
        assertTrue(settings.hasKey(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_REMEMBERED_NAME))
    }

    // T19: canonical presence wins over legacy INCLUDING a stored false.
    @Test
    fun `T19 canonical presence wins over legacy including a stored false`() {
        val settings = MapSettings()
        settings.putBoolean(HrmSettingsKeys.KEY_HRM_AUTO_RECONNECT_ENABLED, false)
        settings.putBoolean(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_AUTO_RECONNECT_ENABLED, true)
        settings.putBoolean(HrmSettingsKeys.KEY_HRM_RAW_ECG_STORAGE_ENABLED, true)
        settings.putBoolean(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_RAW_ECG_STORAGE_ENABLED, false)
        settings.putString(HrmSettingsKeys.KEY_HRM_REMEMBERED_NAME, "Canonical Name")
        settings.putString(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_REMEMBERED_NAME, "Legacy Name")

        val keys = HrmSettingsKeys(settings)

        // Canonical false beats legacy true.
        assertFalse(keys.getAutoReconnectEnabled())
        // Canonical true beats legacy false.
        assertTrue(keys.getRawEcgStorageEnabled())
        // Canonical name beats legacy name.
        assertEquals("Canonical Name", keys.getRememberedName())
        // Legacy copies are untouched by reads.
        assertTrue(settings.getBoolean(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_AUTO_RECONNECT_ENABLED, false))
        assertFalse(settings.getBoolean(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_RAW_ECG_STORAGE_ENABLED, true))
        assertEquals("Legacy Name", settings.getStringOrNull(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_REMEMBERED_NAME))
    }

    // T20: legacy auto_reconnect false stays false (never reset to the default).
    @Test
    fun `T20 legacy stored auto-reconnect false stays false and is not reset to default`() {
        val settings = MapSettings()
        settings.putBoolean(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_AUTO_RECONNECT_ENABLED, false)

        val manager = HrmBleManager(settings = settings)

        assertFalse(manager.isAutoReconnectEnabled.value)
        // Self-heal preserves the false — it is NOT upgraded to the default true.
        assertFalse(settings.getBoolean(HrmSettingsKeys.KEY_HRM_AUTO_RECONNECT_ENABLED, true))
    }

    // T21: both generations absent -> defaults (reconnect true, ECG false).
    @Test
    fun `T21 absent keys in both generations fall back to defaults`() {
        val settings = MapSettings()
        val keys = HrmSettingsKeys(settings)

        assertTrue(keys.getAutoReconnectEnabled())
        assertFalse(keys.getRawEcgStorageEnabled())
        assertNull(keys.getRememberedAddress())
        assertNull(keys.getRememberedName())
        // Genuinely absent keys receive defaults WITHOUT any self-heal writes.
        assertTrue(settings.keys.isEmpty())
    }

    // T22: ordinary write is canonical-only; legacy copies retained (never deleted).
    @Test
    fun `T22 ordinary writes are canonical-only and never delete or update legacy copies`() {
        val settings = MapSettings()
        settings.putString(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_REMEMBERED_ADDRESS, "AA:AA:AA:AA:AA:AA")
        settings.putString(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_REMEMBERED_NAME, "Legacy Name")
        settings.putBoolean(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_AUTO_RECONNECT_ENABLED, true)
        settings.putBoolean(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_RAW_ECG_STORAGE_ENABLED, true)

        val keys = HrmSettingsKeys(settings)
        keys.setAutoReconnectEnabled(false)
        keys.setRawEcgStorageEnabled(true)
        keys.rememberDevice("BB:BB:BB:BB:BB:BB", "New Name")

        // Canonical keys carry the new values.
        assertFalse(settings.getBoolean(HrmSettingsKeys.KEY_HRM_AUTO_RECONNECT_ENABLED, true))
        assertTrue(settings.getBoolean(HrmSettingsKeys.KEY_HRM_RAW_ECG_STORAGE_ENABLED, false))
        assertEquals("BB:BB:BB:BB:BB:BB", settings.getStringOrNull(HrmSettingsKeys.KEY_HRM_REMEMBERED_ADDRESS))
        assertEquals("New Name", settings.getStringOrNull(HrmSettingsKeys.KEY_HRM_REMEMBERED_NAME))
        // Legacy copies retained verbatim — never deleted, never overwritten.
        assertEquals("AA:AA:AA:AA:AA:AA", settings.getStringOrNull(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_REMEMBERED_ADDRESS))
        assertEquals("Legacy Name", settings.getStringOrNull(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_REMEMBERED_NAME))
        assertTrue(settings.getBoolean(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_AUTO_RECONNECT_ENABLED, false))
        assertTrue(settings.getBoolean(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_RAW_ECG_STORAGE_ENABLED, false))
    }

    // T23: Forget clears name/address in BOTH generations and nothing else.
    @Test
    fun `T23 forget clears remembered name and address in both generations and nothing else`() {
        val settings = MapSettings()
        settings.putString(HrmSettingsKeys.KEY_HRM_REMEMBERED_ADDRESS, "CC:CC:CC:CC:CC:CC")
        settings.putString(HrmSettingsKeys.KEY_HRM_REMEMBERED_NAME, "Canonical Name")
        settings.putString(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_REMEMBERED_ADDRESS, "AA:AA:AA:AA:AA:AA")
        settings.putString(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_REMEMBERED_NAME, "Legacy Name")
        settings.putBoolean(HrmSettingsKeys.KEY_HRM_AUTO_RECONNECT_ENABLED, true)
        settings.putBoolean(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_AUTO_RECONNECT_ENABLED, true)
        settings.putBoolean(HrmSettingsKeys.KEY_HRM_RAW_ECG_STORAGE_ENABLED, true)
        settings.putBoolean(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_RAW_ECG_STORAGE_ENABLED, true)

        val manager = HrmBleManager(settings = settings)
        manager.forgetRememberedDevice()

        // Name/address removed in BOTH generations.
        assertFalse(settings.hasKey(HrmSettingsKeys.KEY_HRM_REMEMBERED_ADDRESS))
        assertFalse(settings.hasKey(HrmSettingsKeys.KEY_HRM_REMEMBERED_NAME))
        assertFalse(settings.hasKey(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_REMEMBERED_ADDRESS))
        assertFalse(settings.hasKey(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_REMEMBERED_NAME))
        // Nothing else cleared: preference keys survive in both generations.
        assertTrue(settings.hasKey(HrmSettingsKeys.KEY_HRM_AUTO_RECONNECT_ENABLED))
        assertTrue(settings.hasKey(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_AUTO_RECONNECT_ENABLED))
        assertTrue(settings.hasKey(HrmSettingsKeys.KEY_HRM_RAW_ECG_STORAGE_ENABLED))
        assertTrue(settings.hasKey(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_RAW_ECG_STORAGE_ENABLED))
        // Manager state mirrors the forgotten identity.
        assertNull(manager.rememberedAddress.value)
        assertNull(manager.rememberedName.value)
    }

    // T24: Forget retains the reconnect + ECG preference copies in both generations.
    @Test
    fun `T24 forget retains auto-reconnect and raw-ECG preference values in both generations`() {
        val settings = MapSettings()
        settings.putString(HrmSettingsKeys.KEY_HRM_REMEMBERED_ADDRESS, "CC:CC:CC:CC:CC:CC")
        settings.putString(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_REMEMBERED_ADDRESS, "AA:AA:AA:AA:AA:AA")
        settings.putBoolean(HrmSettingsKeys.KEY_HRM_AUTO_RECONNECT_ENABLED, false)
        settings.putBoolean(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_AUTO_RECONNECT_ENABLED, true)
        settings.putBoolean(HrmSettingsKeys.KEY_HRM_RAW_ECG_STORAGE_ENABLED, true)
        settings.putBoolean(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_RAW_ECG_STORAGE_ENABLED, false)

        val manager = HrmBleManager(settings = settings)
        manager.forgetRememberedDevice()

        // Exact preference values survive the forget, in both generations.
        assertFalse(settings.getBoolean(HrmSettingsKeys.KEY_HRM_AUTO_RECONNECT_ENABLED, true))
        assertTrue(settings.getBoolean(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_AUTO_RECONNECT_ENABLED, false))
        assertTrue(settings.getBoolean(HrmSettingsKeys.KEY_HRM_RAW_ECG_STORAGE_ENABLED, false))
        assertFalse(settings.getBoolean(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_RAW_ECG_STORAGE_ENABLED, true))
    }

    // T25: process-restart persistence — new manager over the same store sees
    // canonical values, with no legacy re-read regression after migration.
    @Test
    fun `T25 process restart after migration reads canonical values with no legacy re-read regression`() {
        val settings = MapSettings()
        settings.putString(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_REMEMBERED_ADDRESS, "24:AC:AC:0E:5B:59")
        settings.putString(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_REMEMBERED_NAME, "Legacy Name")
        settings.putBoolean(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_AUTO_RECONNECT_ENABLED, false)

        // First manager performs the legacy -> canonical migration on read.
        val first = HrmBleManager(settings = settings)
        assertEquals("Legacy Name", first.rememberedName.value)
        assertFalse(first.isAutoReconnectEnabled.value)

        // A canonical-only edit between "restarts" (legacy intentionally not synced).
        settings.putString(HrmSettingsKeys.KEY_HRM_REMEMBERED_NAME, "Canonical Updated")

        // Simulated process restart: a NEW manager over the SAME underlying store.
        val second = HrmBleManager(settings = settings)
        assertEquals("Canonical Updated", second.rememberedName.value)
        assertEquals("24:AC:AC:0E:5B:59", second.rememberedAddress.value)
        assertFalse(second.isAutoReconnectEnabled.value)
        // Legacy copy still holds the original value for downgrade readability.
        assertEquals("Legacy Name", settings.getStringOrNull(HrmSettingsKeys.LEGACY_KEY_POLAR_H10_REMEMBERED_NAME))
    }
}
