package com.juul.kable

import kotlinx.coroutines.CoroutineScope
import platform.CoreBluetooth.CBPeripheral

@Deprecated(
    message = "Replaced with `Peripheral` builder function (not a CoroutineScope extension function).",
    replaceWith = ReplaceWith("Peripheral(cbPeripheral, builderAction)"),
    level = DeprecationLevel.ERROR,
)
actual fun CoroutineScope.peripheral(
    advertisement: Advertisement,
    builderAction: PeripheralBuilderAction,
): Peripheral = Peripheral(advertisement, builderAction)

@Deprecated(
    message = "Replaced with `Peripheral` builder function (not a CoroutineScope extension function).",
    replaceWith = ReplaceWith("Peripheral(cbPeripheral, builderAction)"),
    level = DeprecationLevel.ERROR,
)
fun CoroutineScope.peripheral(
    identifier: Identifier,
    builderAction: PeripheralBuilderAction = {},
): Peripheral = Peripheral(identifier, builderAction)

@Deprecated(
    message = "Replaced with `Peripheral` builder function (not a CoroutineScope extension function).",
    replaceWith = ReplaceWith("Peripheral(cbPeripheral, builderAction)"),
    level = DeprecationLevel.ERROR,
)
fun CoroutineScope.peripheral(
    cbPeripheral: CBPeripheral,
    builderAction: PeripheralBuilderAction,
): CoreBluetoothPeripheral = Peripheral(cbPeripheral, builderAction)
