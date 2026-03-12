package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.framework.protocol.AutoStopUiState as FrameworkAutoStopUiState
import com.devil.phoenixproject.framework.protocol.BleRepository as FrameworkBleRepository
import com.devil.phoenixproject.framework.protocol.HandleDetection as FrameworkHandleDetection
import com.devil.phoenixproject.framework.protocol.HandleState as FrameworkHandleState
import com.devil.phoenixproject.framework.protocol.ReconnectionRequest as FrameworkReconnectionRequest
import com.devil.phoenixproject.framework.protocol.RepNotification as FrameworkRepNotification
import com.devil.phoenixproject.framework.protocol.ScannedDevice as FrameworkScannedDevice

/**
 * Backward-compatible aliases. New reusable contracts live in
 * `com.devil.phoenixproject.framework.protocol`.
 */
typealias ScannedDevice = FrameworkScannedDevice
typealias HandleDetection = FrameworkHandleDetection
typealias AutoStopUiState = FrameworkAutoStopUiState
typealias HandleState = FrameworkHandleState
typealias RepNotification = FrameworkRepNotification
typealias ReconnectionRequest = FrameworkReconnectionRequest

typealias BleRepository = FrameworkBleRepository
