package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.KableBleRepository
import com.devil.phoenixproject.data.repository.simulator.SimulatorBleRepository

/**
 * Vendor plugin protocol adapters supported by Phoenix.
 */
enum class ProtocolAdapter {
    Kable,
    Simulator
}

/**
 * Logical machine profile that a vendor plugin targets.
 */
enum class MachineProfile {
    VitruvianTrainerPlus,
    VitruvianSimulator
}

/**
 * Optional visual and program assets owned by a vendor plugin.
 */
data class VendorAssets(
    val assetBundlePath: String? = null,
    val templateIds: List<String> = emptyList()
)

/**
 * Vendor plugin metadata and runtime adapter selection.
 */
data class VendorPlugin(
    val id: String,
    val displayName: String,
    val machineProfile: MachineProfile,
    val protocolAdapter: ProtocolAdapter,
    val assets: VendorAssets? = null
)

/**
 * Resolved vendor selection context used by DI.
 */
data class VendorPluginContext(
    val requestedPluginId: String,
    val selectedPlugin: VendorPlugin,
    val usedFallback: Boolean
)

object VendorPluginRegistry {
    val defaultPlugin = VendorPlugin(
        id = "vitruvian",
        displayName = "Vitruvian",
        machineProfile = MachineProfile.VitruvianTrainerPlus,
        protocolAdapter = ProtocolAdapter.Kable
    )

    val simulatorPlugin = VendorPlugin(
        id = "vitruvian-simulator",
        displayName = "Vitruvian Simulator",
        machineProfile = MachineProfile.VitruvianSimulator,
        protocolAdapter = ProtocolAdapter.Simulator,
        assets = VendorAssets(templateIds = listOf("simulator-default"))
    )

    private val pluginsById = listOf(defaultPlugin, simulatorPlugin).associateBy { it.id }

    fun resolve(pluginId: String): VendorPluginContext {
        val plugin = pluginsById[pluginId]
        return if (plugin != null) {
            VendorPluginContext(
                requestedPluginId = pluginId,
                selectedPlugin = plugin,
                usedFallback = false
            )
        } else {
            VendorPluginContext(
                requestedPluginId = pluginId,
                selectedPlugin = defaultPlugin,
                usedFallback = true
            )
        }
    }

    fun createBleRepository(context: VendorPluginContext): BleRepository {
        return when (context.selectedPlugin.protocolAdapter) {
            ProtocolAdapter.Kable -> KableBleRepository()
            ProtocolAdapter.Simulator -> SimulatorBleRepository()
        }
    }
}
