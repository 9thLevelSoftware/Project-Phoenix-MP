package com.phoenix.vendor.template

/**
 * Minimal registry used by bootstrap script when adding a new plugin.
 */
object PluginRegistry {
    private val plugins = mutableMapOf<String, VendorPlugin>()

    fun register(plugin: VendorPlugin) {
        plugins[plugin.id] = plugin
    }

    fun find(id: String): VendorPlugin? = plugins[id]

    fun all(): List<VendorPlugin> = plugins.values.toList()
}
