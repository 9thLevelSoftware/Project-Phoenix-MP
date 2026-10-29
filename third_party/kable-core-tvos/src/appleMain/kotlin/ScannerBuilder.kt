package com.juul.kable

import com.juul.kable.logs.Logging
import com.juul.kable.logs.LoggingBuilder
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBCentralManagerScanOptionSolicitedServiceUUIDsKey
import kotlin.uuid.Uuid

actual class ScannerBuilder {

    @Deprecated(
        message = "Use filters(FiltersBuilder.() -> Unit)",
        replaceWith = ReplaceWith("filters { }"),
        level = DeprecationLevel.HIDDEN,
    )
    actual var filters: List<Filter>? = null

    private var filterPredicates: List<FilterPredicate> = emptyList()

    actual fun filters(builderAction: FiltersBuilder.() -> Unit) {
        filterPredicates = FiltersBuilder().apply(builderAction).build()
    }

    /**
     * Specifies whether the scan should run without duplicate filtering. This corresponds to
     * Core Bluetooth's [CBCentralManagerScanOptionAllowDuplicatesKey] scanning option.
     */
    var allowDuplicateKeys: Boolean? = true

    /**
     * Causes the scanner to scan for peripherals soliciting any of the services contained in the
     * array. This corresponds to Core Bluetooth's [CBCentralManagerScanOptionSolicitedServiceUUIDsKey]
     * scanning option.
     */
    var solicitedServiceUuids: List<Uuid>? = null

    private var logging: Logging = Logging()

    actual fun logging(init: LoggingBuilder) {
        logging = Logging().apply(init)
    }

    internal actual fun build(): PlatformScanner {
        val options = mutableMapOf<String, Any>()
        allowDuplicateKeys?.also {
            options[CBCentralManagerScanOptionAllowDuplicatesKey] = it
        }
        solicitedServiceUuids?.also { uuids ->
            options[CBCentralManagerScanOptionSolicitedServiceUUIDsKey] = uuids.map(Uuid::toCBUUID)
        }

        return CentralManagerCoreBluetoothScanner(
            central = CentralManager.Default,
            filters = filterPredicates,
            options = options.toMap(),
            logging = logging,
        )
    }
}
