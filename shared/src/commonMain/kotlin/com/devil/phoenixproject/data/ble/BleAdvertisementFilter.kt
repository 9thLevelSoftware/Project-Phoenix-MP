package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.util.BleConstants

/**
 * Fail-closed BLE advertisement identity (D-12 / FP-2).
 *
 * Connectable names are `Vee_` (V-Form) or `VIT` (Trainer+), ignore-case.
 * Generic `Vitruvian*` / `Phoenix*` names, empty names, nameless NUS, and
 * FEF3-only advertisers are not connectable. Unnamed NUS/FEF3 may still be
 * listed as visible-only and cannot [mayConnect] until a connectable name is
 * observed, or the identifier matches the last successful connect (opt-in).
 */
object BleAdvertisementFilter {
    const val FEF3_UUID_STRING = "0000fef3-0000-1000-8000-00805f9b34fb"
    const val FEF3_UUID_PREFIX = "0000fef3"

    /**
     * GATT-connectable iff the advertised name is a V-Form or Trainer+ prefix.
     * `Vitruvian` starts with `VIT` and is **not** a Trainer+ advertisement.
     */
    fun isConnectableName(name: String?): Boolean {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return false
        if (n.startsWith("Vee_", ignoreCase = true)) return true
        if (!n.startsWith("VIT", ignoreCase = true)) return false
        return !n.startsWith("Vitruvian", ignoreCase = true)
    }

    fun hasTrainerServiceUuid(serviceUuidStrings: Collection<String>): Boolean = serviceUuidStrings.any { uuid ->
        val s = uuid.lowercase()
        s.startsWith(FEF3_UUID_PREFIX) ||
            s == BleConstants.NUS_SERVICE_UUID_STRING.lowercase()
    }

    /**
     * Nameless NUS / FEF3 advertisers may appear in the scan list but cannot
     * be auto-bound. Named non-trainer devices are not visible-only.
     */
    fun isVisibleOnlyCandidate(
        name: String?,
        serviceUuidStrings: Collection<String>,
        hasFef3ServiceData: Boolean,
    ): Boolean {
        if (!name.isNullOrBlank()) return false
        return hasTrainerServiceUuid(serviceUuidStrings) || hasFef3ServiceData
    }

    fun shouldListDuringScan(
        name: String?,
        serviceUuidStrings: Collection<String>,
        hasFef3ServiceData: Boolean,
    ): Boolean = isConnectableName(name) ||
        isVisibleOnlyCandidate(name, serviceUuidStrings, hasFef3ServiceData)

    /**
     * Both the caller's scanned label and the stored advertisement must be
     * independently admissible. This prevents a stale connectable UI label from
     * authorizing a non-connectable advertisement for the same identifier.
     * Unnamed stored advertisements additionally require visible-only NUS/FEF3
     * evidence before the last-successful-identifier opt-in can apply.
     */
    fun mayConnectWithAdvertisementIdentity(
        scannedName: String?,
        advertisedName: String?,
        identifier: String?,
        lastSuccessfulIdentifier: String? = null,
        storedAdvertisementIsVisibleOnly: Boolean = false,
    ): Boolean {
        val scannedAllowed = mayConnect(
            name = scannedName,
            identifier = identifier,
            lastSuccessfulIdentifier = lastSuccessfulIdentifier,
        )
        val advertisedAllowed = if (advertisedName.isNullOrBlank()) {
            storedAdvertisementIsVisibleOnly && mayConnect(
                name = advertisedName,
                identifier = identifier,
                lastSuccessfulIdentifier = lastSuccessfulIdentifier,
            )
        } else {
            mayConnect(
                name = advertisedName,
                identifier = identifier,
                lastSuccessfulIdentifier = lastSuccessfulIdentifier,
            )
        }
        return scannedAllowed && advertisedAllowed
    }

    /**
     * [connect] re-check: a live name must be connectable. The last successful
     * identifier is an opt-in only for unnamed advertisements represented by the
     * manager's generated `Trainer (<identifier>)` placeholder.
     */
    fun mayConnect(
        name: String?,
        identifier: String? = null,
        lastSuccessfulIdentifier: String? = null,
    ): Boolean {
        if (isConnectableName(name)) return true
        val normalizedName = name?.trim().orEmpty()
        val isUnnamedPlaceholder = normalizedName.isEmpty() ||
            (normalizedName.startsWith("Trainer (", ignoreCase = true) && normalizedName.endsWith(")"))
        if (!isUnnamedPlaceholder) return false
        val id = identifier?.takeIf { it.isNotBlank() } ?: return false
        val last = lastSuccessfulIdentifier?.takeIf { it.isNotBlank() } ?: return false
        return id == last
    }
}
