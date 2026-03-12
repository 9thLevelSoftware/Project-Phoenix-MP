package com.devil.phoenixproject.framework.protocol

import com.devil.phoenixproject.util.BleConstants
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
object VitruvianMachineProfile : MachineProfile {
    override val key: String = "vitruvian"
    override val displayName: String = "Vitruvian"

    private val fef3Uuid = Uuid.parse("0000fef3-0000-1000-8000-00805f9b34fb")

    override val serviceUuids: Set<Uuid> = setOf(
        BleConstants.NUS_SERVICE_UUID,
        fef3Uuid
    )

    override val nameMatchers: List<Regex> = listOf(
        Regex("^Vee_.*", RegexOption.IGNORE_CASE),
        Regex("^VIT.*", RegexOption.IGNORE_CASE),
        Regex("^Vitruvian.*", RegexOption.IGNORE_CASE)
    )

    override val advertisedDataHints: AdvertisedDataHints = AdvertisedDataHints(
        serviceUuidPrefixes = setOf("0000fef3"),
        serviceDataUuids = setOf(fef3Uuid)
    )

    override val capabilities: Set<MachineCapability> = setOf(
        MachineCapability.ProprietaryVersionRead,
        MachineCapability.RepsNotifications,
        MachineCapability.MonitorNotifications,
        MachineCapability.HeuristicPolling,
        MachineCapability.DiagnosticPolling
    )

    override fun match(advertisement: AdvertisedMachineData): ProfileMatchResult {
        val name = advertisement.name
        if (name != null && nameMatchers.any { it.matches(name) }) {
            return ProfileMatchResult(matches = true, reason = "name")
        }

        if (advertisement.serviceUuids.any { matchesServiceUuid(it) }) {
            return ProfileMatchResult(matches = true, reason = "service_uuid")
        }

        val hasServiceData = advertisedDataHints.serviceDataUuids.any { uuid ->
            val data = advertisement.serviceData[uuid]
            data != null && data.isNotEmpty()
        }
        if (hasServiceData) {
            return ProfileMatchResult(matches = true, reason = "service_data")
        }

        return ProfileMatchResult(matches = false)
    }

    override fun hasPreferredName(advertisedName: String?): Boolean {
        return advertisedName != null && (
            advertisedName.startsWith("Vee_", ignoreCase = true) ||
                advertisedName.startsWith("VIT", ignoreCase = true)
            )
    }

    override fun labelFor(advertisement: AdvertisedMachineData): String {
        return advertisement.name ?: "$displayName (${advertisement.identifier})"
    }

    private fun matchesServiceUuid(uuid: Uuid): Boolean {
        val uuidString = uuid.toString().lowercase()
        return uuid in serviceUuids || advertisedDataHints.serviceUuidPrefixes.any { uuidString.startsWith(it) }
    }
}
