package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.util.BleConstants
import com.juul.kable.Advertisement
import kotlin.uuid.ExperimentalUuidApi

/**
 * Vendor-family hint for display and analytics only. Classification is display
 * metadata, not a security boundary: after connection the actual Heart Rate
 * service (0x2A37 observation path) is authoritative (SD5).
 */
enum class HrmFamily { POLAR, GARMIN, GENERIC_HRM }

/** Why an advertisement was accepted as a heart-rate sensor. */
enum class HrmMatchReason { SERVICE_UUID, NAME_ALLOWLIST }

/**
 * Result of classifying a scanned BLE advertisement as a heart-rate monitor.
 *
 * @property family vendor-family hint (display metadata only)
 * @property displayName advertised device name, or [GENERIC_HRM_DISPLAY_NAME] when
 *   the advertiser does not broadcast a name
 * @property matchReason how the advertisement was accepted
 */
data class HrmAdvertisementClass(
    val family: HrmFamily,
    val displayName: String,
    val matchReason: HrmMatchReason,
)

/** Display name for accepted advertisers that broadcast no usable name (SD5). */
const val GENERIC_HRM_DISPLAY_NAME: String = "Heart Rate Sensor"

/**
 * Bounded, documented HRM name allow-list for advertisers whose scan response
 * carries no service UUIDs (accepted purely by name). Every entry is an explicit
 * device family — never a vendor wildcard. A `Garmin*` or `Polar*` prefix rule is
 * deliberately forbidden: non-HR Garmin/Polar products (e.g. NUS-only trainers,
 * bike computers) must stay out of the sensor picker.
 *
 * Entries match case-insensitively as: exact name, or name with a device-id
 * suffix separated by a space/hyphen/underscore (e.g. `HRM-Dual 41234`).
 *
 * - `Polar H10` — existing Phoenix behavior preserved verbatim (matches
 *   `Polar H10` exactly or `Polar H10 <serial>`); the only Polar strap Phoenix
 *   historically supported by name.
 * - `HRM-Dual` — Garmin HRM-Dual chest strap (standard BLE HR broadcast).
 * - `HRM-Pro` / `HRM-Pro Plus` — Garmin HRM-Pro family straps.
 * - `HRM-Tri` / `HRM-Swim` — Garmin triathlon/swim straps with BLE broadcast.
 * - `HRM 200` / `HRM 600` — Garmin HRM 200/600 straps.
 *
 * Garmin watches (Forerunner/Fenix/Venu/…) are intentionally NOT listed: they
 * broadcast the standard Heart Rate Service (0x180D) in Broadcast Heart Rate
 * mode and are accepted via [HrmMatchReason.SERVICE_UUID].
 */
internal val KNOWN_HRM_NAME_ALLOWLIST: List<String> = listOf(
    "Polar H10",
    "HRM-Dual",
    "HRM-Pro",
    "HRM-Pro Plus",
    "HRM-Tri",
    "HRM-Swim",
    "HRM 200",
    "HRM 600",
)

/**
 * Classify a scanned advertisement as a heart-rate sensor (SD5).
 *
 * Acceptance rules:
 * 1. [Advertisement.uuids] contains the Bluetooth SIG standard Heart Rate
 *    Service UUID (0x180D) — accepted even with `name == null`. This is a
 *    standard, vendor-neutral service: any compliant broadcaster (Garmin watch
 *    or strap in broadcast mode, Wahoo, Polar, etc.) qualifies.
 * 2. Otherwise the advertised name matches [KNOWN_HRM_NAME_ALLOWLIST]
 *    (scan-response-only advertisements carrying no UUIDs).
 * 3. Everything else — including NUS-only machine trainers — is rejected
 *    (`null`).
 *
 * Post-connection validation of the actual HR service is authoritative; this
 * function only gates the scan list.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun classifyHrmAdvertisement(adv: Advertisement): HrmAdvertisementClass? {
    val name = adv.name?.trim()?.takeIf { it.isNotEmpty() }
    val hasHrService = adv.uuids.any { it == BleConstants.Hrm.HR_SERVICE_UUID }
    return when {
        hasHrService -> HrmAdvertisementClass(
            family = hrmFamilyForName(name),
            displayName = name ?: GENERIC_HRM_DISPLAY_NAME,
            matchReason = HrmMatchReason.SERVICE_UUID,
        )
        name != null && isKnownHrmName(name) -> HrmAdvertisementClass(
            family = hrmFamilyForName(name),
            displayName = name,
            matchReason = HrmMatchReason.NAME_ALLOWLIST,
        )
        else -> null
    }
}

/**
 * Bounded family mapping for display only. Falls back to [HrmFamily.GENERIC_HRM]
 * for any accepted name outside the known families (identity ≠ capability, SD5).
 */
internal fun hrmFamilyForName(name: String?): HrmFamily {
    if (name == null) return HrmFamily.GENERIC_HRM
    if (isPolarH10Name(name)) return HrmFamily.POLAR
    val lower = name.lowercase()
    val garminStrapFamilies = listOf("hrm-dual", "hrm-pro", "hrm-tri", "hrm-swim", "hrm 200", "hrm 600")
    if (garminStrapFamilies.any { matchesBoundedName(lower, it) }) return HrmFamily.GARMIN
    return HrmFamily.GENERIC_HRM
}

/** Existing Polar H10 name matching preserved verbatim (SD5). */
internal fun isPolarH10Name(name: String): Boolean =
    name.equals("Polar H10", ignoreCase = true) || name.startsWith("Polar H10 ", ignoreCase = true)

internal fun isKnownHrmName(name: String): Boolean {
    if (isPolarH10Name(name)) return true // preserved verbatim: space-suffix rule only (SD5)
    val lower = name.lowercase()
    return KNOWN_HRM_NAME_ALLOWLIST.any { it != "Polar H10" && matchesBoundedName(lower, it.lowercase()) }
}

/**
 * Case-insensitive bounded match: exact, or the allow-listed family followed by
 * a device-id suffix separator (space/hyphen/underscore). No prefix wildcards.
 */
private fun matchesBoundedName(lowerName: String, lowerEntry: String): Boolean =
    lowerName == lowerEntry ||
        lowerName.startsWith("$lowerEntry ") ||
        lowerName.startsWith("$lowerEntry-") ||
        lowerName.startsWith("${lowerEntry}_")
