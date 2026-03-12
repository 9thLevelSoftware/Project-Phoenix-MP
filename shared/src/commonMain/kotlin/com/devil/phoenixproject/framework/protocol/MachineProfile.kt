package com.devil.phoenixproject.framework.protocol

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Contract for machine-specific BLE discovery behavior and device metadata.
 */
@OptIn(ExperimentalUuidApi::class)
interface MachineProfile {
    val key: String
    val displayName: String
    val serviceUuids: Set<Uuid>
    val nameMatchers: List<Regex>
    val advertisedDataHints: AdvertisedDataHints
    val capabilities: Set<MachineCapability>

    fun match(advertisement: AdvertisedMachineData): ProfileMatchResult
    fun hasPreferredName(advertisedName: String?): Boolean
    fun labelFor(advertisement: AdvertisedMachineData): String
}

@OptIn(ExperimentalUuidApi::class)
data class AdvertisedMachineData(
    val name: String?,
    val identifier: String,
    val rssi: Int,
    val serviceUuids: List<Uuid>,
    val serviceData: Map<Uuid, ByteArray>
)

@OptIn(ExperimentalUuidApi::class)
data class AdvertisedDataHints(
    val serviceUuidPrefixes: Set<String> = emptySet(),
    val serviceDataUuids: Set<Uuid> = emptySet()
)

enum class MachineCapability {
    ProprietaryVersionRead,
    RepsNotifications,
    MonitorNotifications,
    HeuristicPolling,
    DiagnosticPolling
}

data class ProfileMatchResult(
    val matches: Boolean,
    val reason: String? = null
)
