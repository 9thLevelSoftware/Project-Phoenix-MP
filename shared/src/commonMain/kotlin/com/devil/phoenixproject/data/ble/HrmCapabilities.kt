package com.devil.phoenixproject.data.ble

/**
 * Per-capability probe state for a connected heart rate sensor (SD2).
 *
 * State machine per capability:
 * `UNKNOWN` → `CHECKING` → `WAITING_FIRST_SAMPLE` (observation registered) →
 * `CONFIRMED` (first valid live sample) / `ABSENT` (authoritatively not exposed) /
 * `FAILED` (probe error).
 *
 * Rules enforced by [HrmBleManager]:
 * - Everything starts [UNKNOWN]; there is no Boolean "capability = true" default.
 * - An observation registration is NOT a sample: 0x2A37 observe success moves
 *   `standardHr` to [WAITING_FIRST_SAMPLE]; the first valid measurement moves it
 *   to [CONFIRMED].
 * - [FAILED] is never [ABSENT]: a transport/permission/probe error is not proof
 *   of absence. [ABSENT] only comes from authoritative service/characteristic
 *   enumeration after connection (e.g. the discovered services lack the Battery
 *   Service 0x180F / 0x2A19, or lack Polar PMD FB005C80).
 * - Probes that are not run stay [UNKNOWN].
 * - Missing battery/RR/PMD never suppresses standard heart rate.
 */
enum class HrmCapabilityState {
    UNKNOWN,
    CHECKING,
    WAITING_FIRST_SAMPLE,
    CONFIRMED,
    ABSENT,
    FAILED,
}

/**
 * Skin-contact status as reported by the sensor (SD2 tri-state).
 *
 * The Heart Rate Measurement flags (0x2A37) carry a contact-status field:
 * - `00b` / `01b`: contact sensor not supported → [NOT_REPORTED] (contact-safety
 *   heuristics in [HrmBleManager] still treat a positive HR as contact-OK).
 * - `10b`: contact supported, NOT detected → [REPORTED_NOT_DETECTED].
 * - `11b`: contact supported and detected → [REPORTED_DETECTED].
 */
enum class ContactReportState {
    NOT_REPORTED,
    REPORTED_DETECTED,
    REPORTED_NOT_DETECTED,
}

/**
 * Snapshot of all runtime capability probes for the current connection
 * generation. Everything starts [HrmCapabilityState.UNKNOWN] /
 * [ContactReportState.NOT_REPORTED]; all findings reset on disconnect, new
 * selection and reconnect (SD3).
 */
data class HrmCapabilities(
    val standardHr: HrmCapabilityState = HrmCapabilityState.UNKNOWN,
    val rrIntervals: HrmCapabilityState = HrmCapabilityState.UNKNOWN,
    val battery: HrmCapabilityState = HrmCapabilityState.UNKNOWN,
    val polarPmd: HrmCapabilityState = HrmCapabilityState.UNKNOWN,
    val contact: ContactReportState = ContactReportState.NOT_REPORTED,
)
