package com.devil.phoenixproject.data.ble

/**
 * Specific BLE exception types matching official Vitruvian app implementation
 * These provide granular error handling for different BLE failure scenarios
 */

/**
 * Thrown when Bluetooth is disabled on the device
 * User needs to enable Bluetooth in system settings
 */
class BluetoothDisabledException(message: String = "Bluetooth is disabled. Please enable Bluetooth and try again.") : Exception(message)

/**
 * General Bluetooth/BLE exception for errors that don't fit other categories
 * Base exception for BLE-related errors
 */
class BluetoothException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when an established connection is unexpectedly lost
 * Usually indicates device went out of range or powered off
 */
class ConnectionLostException(message: String = "Connection to device was lost", cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when a connection attempt is rejected by the device
 * Device may be busy, paired to another device, or refusing connections
 */
class ConnectionRejectedException(message: String = "Connection was rejected by device", cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when a GATT operation (read/write/notify) is rejected
 * Characteristic may not support the operation or device is in wrong state
 */
class GattRequestRejectedException(message: String, val gattStatus: Int? = null, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when a GATT operation fails with a specific status code
 * Includes the GATT status code for detailed error analysis
 */
class GattStatusException(message: String, val gattStatus: Int, cause: Throwable? = null) : Exception("$message (GATT status: $gattStatus)", cause)

/**
 * Thrown when attempting an operation before the device is ready
 * Device may still be initializing, discovering services, or setting up MTU
 */
class NotReadyException(message: String = "Device is not ready for this operation. Please wait for initialization to complete.") : Exception(message)

/**
 * Thrown when BLE scanning fails to start or encounters an error
 * May be due to permissions, Bluetooth state, or hardware issues
 */
class ScanFailedException(message: String, val errorCode: Int? = null, cause: Throwable? = null) : Exception(message, cause)
