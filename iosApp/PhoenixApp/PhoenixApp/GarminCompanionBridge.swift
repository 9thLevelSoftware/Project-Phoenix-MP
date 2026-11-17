//
//  GarminCompanionBridge.swift
//  PhoenixApp
//
//  Garmin watch-button controls (Project Phoenix issue #1391, packet B5).
//
//  Owns ALL native Garmin Connect IQ Mobile SDK interaction on iOS and implements
//  the Kotlin `GarminNativeBridge` seam (shared/iosMain IosGarminCompanionTransport).
//  Flow: GCM discovery/selection (showConnectIQDeviceSelection + the DEDICATED Garmin
//  callback scheme) then direct BLE via the SDK. Device events are registered before
//  status/send/app operations. Inbound messages are forwarded raw with native
//  device/app provenance from the SDK callbacks — never dictionary claims; the Kotlin
//  side runs the receive-status + production watch-app UUID checks BEFORE decoding.
//
//  The selected-device callback is handed to the Kotlin transport, which enforces the
//  binding signoff decision 3 acceptance policy: dedicated scheme only, matching an
//  outstanding PHONE-initiated single-use in-memory request expiring in <= 5 minutes,
//  devices confirmed via SDK device-status registration. `sourceApplication` is
//  advisory logging only (nil cross-team on iOS 13+).
//

import Foundation
import UIKit
import os.log
import shared
import ConnectIQ

/// Bridge between the Kotlin transport and the Garmin Connect IQ SDK.
///
/// Kotlin calls arrive on arbitrary dispatchers; SDK calls are funnelled onto the
/// main queue (the SDK's UI handoff with Garmin Connect Mobile must start from the
/// main thread). SDK delegate callbacks may arrive on any thread and are forwarded
/// straight to the Kotlin transport, which marshals onto the owner dispatcher.
final class GarminCompanionBridge: NSObject, GarminNativeBridge, IQDeviceEventDelegate, IQAppMessageDelegate, IQUIOverrideDelegate {

    static let shared = GarminCompanionBridge()

    /// The DEDICATED Garmin callback scheme GCM returns device-selection results on.
    /// Kept separate from the OAuth scheme (`com.devil.phoenixproject`). Must match
    /// `GARMIN_CALLBACK_URL_SCHEME` in shared/iosMain IosGarminCompanionTransport.kt.
    static let callbackUrlScheme = "com.devil.phoenixproject.garmin"

    private let logger = Logger(subsystem: "com.devil.phoenixproject", category: "GarminBridge")

    // State is only touched on the main queue (all entry points hop there) except for
    // the cached bridge registration itself; `nonisolated(unsafe)` documents that the
    // confinement is enforced by the queue hop, not the compiler.
    nonisolated(unsafe) private var devicesByRef: [String: IQDevice] = [:]
    nonisolated(unsafe) private var appsByKey: [String: IQApp] = [:]

    private override init() {
        super.init()
    }

    /// Installs the bridge into the Kotlin side (idempotent) and records the scheme.
    func install(urlScheme: String) {
        GarminCompanionBridgeRegistry.shared.registerBridge(bridge: self)
        logger.info("Garmin Connect IQ bridge installed (callback scheme \(urlScheme, privacy: .public))")
    }

    // MARK: - GarminNativeBridge (selectors pinned to the Kotlin-generated protocol)

    @objc(initializeSdkUrlScheme:)
    nonisolated func initializeSdk(urlScheme: String) {
        onMain { self.mainInitializeSdk(urlScheme: urlScheme) }
    }

    @objc(showDeviceSelection)
    nonisolated func showDeviceSelection() {
        onMain {
            // GCM discovery/selection: GCM returns the authorized devices by opening
            // our dedicated callback scheme, which PhoenixApp routes back to us.
            ConnectIQ.sharedInstance().showDeviceSelection()
        }
    }

    @objc(registerDeviceEventsDeviceRef:)
    nonisolated func registerDeviceEvents(deviceRef: String) {
        onMain {
            guard let device = self.device(for: deviceRef) else { return }
            ConnectIQ.sharedInstance().register(forDeviceEvents: device, delegate: self)
        }
    }

    @objc(unregisterDeviceEventsDeviceRef:)
    nonisolated func unregisterDeviceEvents(deviceRef: String) {
        onMain {
            guard let device = self.devicesByRef[deviceRef] else { return }
            ConnectIQ.sharedInstance().unregister(forDeviceEvents: device, delegate: self)
        }
    }

    @objc(unregisterAllDeviceEvents)
    nonisolated func unregisterAllDeviceEvents() {
        onMain {
            ConnectIQ.sharedInstance().unregister(forAllDeviceEvents: self)
        }
    }

    @objc(queryDeviceStatusDeviceRef:requestId:)
    nonisolated func queryDeviceStatus(deviceRef: String, requestId: Int64) {
        onMain {
            guard let device = self.device(for: deviceRef) else {
                self.reportDeviceStatus(requestId: requestId, deviceRef: deviceRef, status: "invalid")
                return
            }
            let status = ConnectIQ.sharedInstance().getDeviceStatus(device)
            self.reportDeviceStatus(requestId: requestId, deviceRef: deviceRef, status: Self.statusCode(status))
        }
    }

    @objc(registerAppMessagesDeviceRef:appUuid:)
    nonisolated func registerAppMessages(deviceRef: String, appUuid: String) {
        onMain {
            guard let device = self.device(for: deviceRef) else { return }
            let app = self.app(deviceRef: deviceRef, appUuid: appUuid, device: device)
            ConnectIQ.sharedInstance().registerForAppMessages(app, delegate: self)
        }
    }

    @objc(unregisterAppMessagesDeviceRef:appUuid:)
    nonisolated func unregisterAppMessages(deviceRef: String, appUuid: String) {
        onMain {
            let key = Self.appKey(deviceRef: deviceRef, appUuid: appUuid)
            guard let app = self.appsByKey[key] else { return }
            ConnectIQ.sharedInstance().unregisterForAppMessages(app, delegate: self)
        }
    }

    @objc(unregisterAllAppMessages)
    nonisolated func unregisterAllAppMessages() {
        onMain {
            ConnectIQ.sharedInstance().unregisterForAllAppMessages(self)
        }
    }

    @objc(queryAppInstalledDeviceRef:appUuid:requestId:)
    nonisolated func queryAppInstalled(deviceRef: String, appUuid: String, requestId: Int64) {
        onMain {
            guard let device = self.device(for: deviceRef) else {
                self.reportAppInstalled(requestId: requestId, installed: "unknown")
                return
            }
            let app = self.app(deviceRef: deviceRef, appUuid: appUuid, device: device)
            ConnectIQ.sharedInstance().getAppStatus(app) { appStatus in
                if let appStatus = appStatus {
                    self.reportAppInstalled(requestId: requestId, installed: appStatus.isInstalled ? "installed" : "not_installed")
                } else {
                    self.reportAppInstalled(requestId: requestId, installed: "unknown")
                }
            }
        }
    }

    @objc(sendDeviceRef:appUuid:message:requestId:)
    nonisolated func send(deviceRef: String, appUuid: String, message: [String: Any], requestId: Int64) {
        onMain {
            guard let device = self.device(for: deviceRef) else {
                self.reportSendResult(requestId: requestId, status: "failure_device_not_available")
                return
            }
            let app = self.app(deviceRef: deviceRef, appUuid: appUuid, device: device)
            ConnectIQ.sharedInstance().sendMessage(
                message as NSDictionary,
                to: app,
                progress: { _, _ in },
                completion: { result in
                    self.reportSendResult(requestId: requestId, status: Self.sendCode(result))
                }
            )
        }
    }

    @objc(shutdownSdk)
    nonisolated func shutdownSdk() {
        onMain {
            // The iOS SDK exposes no explicit shutdown; drop all native references and
            // unregister every listener (paired with each register performed above).
            ConnectIQ.sharedInstance().unregister(forAllDeviceEvents: self)
            ConnectIQ.sharedInstance().unregisterForAllAppMessages(self)
            self.devicesByRef.removeAll()
            self.appsByKey.removeAll()
        }
    }

    // MARK: - GCM device-selection callback (dedicated scheme only)

    /// Called by PhoenixApp for URLs on the DEDICATED Garmin scheme. The Kotlin
    /// transport is the authority on acceptance (signoff decision 3): it rejects
    /// unsolicited/expired/repeated callbacks and callbacks on any other scheme.
    func handleDeviceSelectionCallback(url: URL, sourceApplication: String?) {
        onMain {
            let scheme = url.scheme ?? ""
            guard scheme.caseInsensitiveCompare(GarminCompanionBridge.callbackUrlScheme) == .orderedSame else {
                self.logger.warning("Ignoring selection callback on non-Garmin scheme \(scheme, privacy: .public)")
                return
            }
            // sourceApplication is ADVISORY LOGGING ONLY (nil cross-team on iOS 13+).
            self.logger.info("Garmin selection callback received (sourceApplication=\(sourceApplication ?? "nil", privacy: .public))")
            let parsed = (ConnectIQ.sharedInstance().parseDeviceSelectionResponse(from: url) as? [IQDevice]) ?? []
            var infos: [GarminBridgeDeviceInfo] = []
            for device in parsed {
                let ref = device.uuid.uuidString
                self.devicesByRef[ref] = device
                infos.append(GarminBridgeDeviceInfo(
                    deviceRef: ref,
                    name: device.friendlyName.isEmpty ? device.modelName : device.friendlyName
                ))
            }
            GarminCompanionBridgeRegistry.shared.events?.onNativeDeviceSelectionParsed(
                devices: infos,
                urlScheme: scheme,
                sourceApplication: sourceApplication
            )
        }
    }

    // MARK: - IQDeviceEventDelegate

    func deviceStatusChanged(_ device: IQDevice!, status: IQDeviceStatus) {
        let ref = device.uuid.uuidString
        devicesByRef[ref] = device
        let name = device.friendlyName.isEmpty ? device.modelName : device.friendlyName
        GarminCompanionBridgeRegistry.shared.events?.onNativeDeviceStatusChanged(
            deviceRef: ref,
            name: name ?? "",
            status: Self.statusCode(status)
        )
    }

    func deviceCharacteristicsDiscovered(_ device: IQDevice!) {
        // Services/characteristics are ready; report the current status again so the
        // transport's confirmation flow sees a fully usable connection.
        let ref = device.uuid.uuidString
        GarminCompanionBridgeRegistry.shared.events?.onNativeDeviceStatusChanged(
            deviceRef: ref,
            name: device.friendlyName ?? "",
            status: "connected"
        )
    }

    // MARK: - IQAppMessageDelegate

    func receivedMessage(_ message: Any!, fromApp app: IQApp!) {
        // Forwarded RAW with native provenance; Kotlin checks receive/provenance
        // BEFORE decoding into a GarminInboundEnvelope. `message` is only ever
        // forwarded as a dictionary-shaped payload; non-dictionaries are dropped
        // by the transport's bounded decode.
        GarminCompanionBridgeRegistry.shared.events?.onNativeAppMessage(
            deviceRef: app.device.uuid.uuidString,
            appUuid: app.uuid.uuidString,
            message: Self.bridgedDictionary(message)
        )
    }

    // MARK: - IQUIOverrideDelegate

    func needsToInstallConnectMobile() {
        GarminCompanionBridgeRegistry.shared.events?.onNativeGcmRequired()
    }

    // MARK: - Helpers (main-queue confined)

    private func mainInitializeSdk(urlScheme: String) {
        ConnectIQ.sharedInstance().initialize(withUrlScheme: urlScheme, uiOverrideDelegate: self)
    }

    private func device(for deviceRef: String) -> IQDevice? {
        if let cached = devicesByRef[deviceRef] {
            return cached
        }
        guard let uuid = NSUUID(uuidString: deviceRef) else {
            logger.error("Not a native device reference: \(deviceRef, privacy: .public)")
            return nil
        }
        let device = IQDevice(deviceWithId: uuid as UUID, modelName: "Garmin", friendlyName: "Garmin")
        devicesByRef[deviceRef] = device
        return device
    }

    private func app(deviceRef: String, appUuid: String, device: IQDevice) -> IQApp {
        let key = Self.appKey(deviceRef: deviceRef, appUuid: appUuid)
        if let cached = appsByKey[key] {
            return cached
        }
        let appUuidObj = (NSUUID(uuidString: appUuid) ?? NSUUID()) as UUID
        let app = IQApp.app(withUUID: appUuidObj, storeUuid: appUuidObj, device: device)
        appsByKey[key] = app
        return app
    }

    private func reportDeviceStatus(requestId: Int64, deviceRef: String, status: String) {
        GarminCompanionBridgeRegistry.shared.events?.onNativeDeviceStatusResult(
            requestId: requestId,
            deviceRef: deviceRef,
            status: status
        )
    }

    private func reportSendResult(requestId: Int64, status: String) {
        GarminCompanionBridgeRegistry.shared.events?.onNativeSendResult(requestId: requestId, status: status)
    }

    private func reportAppInstalled(requestId: Int64, installed: String) {
        GarminCompanionBridgeRegistry.shared.events?.onNativeAppInstalledResult(requestId: requestId, installed: installed)
    }

    private func onMain(_ work: @escaping @Sendable () -> Void) {
        if Thread.isMainThread {
            work()
        } else {
            DispatchQueue.main.async(execute: work)
        }
    }

    private static func appKey(deviceRef: String, appUuid: String) -> String {
        "\(deviceRef)|\(appUuid)"
    }

    private static func statusCode(_ status: IQDeviceStatus) -> String {
        switch status {
        case IQDeviceStatus_Connected: return "connected"
        case IQDeviceStatus_NotConnected: return "not_connected"
        case IQDeviceStatus_NotFound: return "not_found"
        case IQDeviceStatus_BluetoothNotReady: return "bluetooth_not_ready"
        case IQDeviceStatus_InvalidDevice: return "invalid"
        default: return "invalid"
        }
    }

    private static func sendCode(_ result: IQSendMessageResult) -> String {
        switch result {
        case IQSendMessageResult_Success: return "success"
        case IQSendMessageResult_Failure_DeviceNotAvailable: return "failure_device_not_available"
        case IQSendMessageResult_Failure_AppNotFound: return "failure_app_not_found"
        case IQSendMessageResult_Failure_PromptNotDisplayed: return "failure_prompt_not_displayed"
        case IQSendMessageResult_Failure_AppAlreadyRunning: return "failure_app_already_running"
        case IQSendMessageResult_Failure_InternalError: return "failure_internal_error"
        case IQSendMessageResult_Failure_DeviceIsBusy: return "failure_device_is_busy"
        case IQSendMessageResult_Failure_UnsupportedType: return "failure_unsupported_type"
        case IQSendMessageResult_Failure_InsufficientMemory: return "failure_insufficient_memory"
        case IQSendMessageResult_Failure_Timeout: return "failure_timeout"
        case IQSendMessageResult_Failure_MaxRetries: return "failure_max_retries"
        default: return "failure_unknown"
        }
    }

    /// Bridges a native message object to a Kotlin `Map<String, Any?>`. The Kotlin
    /// transport re-bounds and validates it after the provenance checks; this only
    /// performs the Objective-C to Kotlin dictionary conversion.
    private static func bridgedDictionary(_ message: Any?) -> [String: Any] {
        guard let dictionary = message as? [String: Any] else {
            return [:]
        }
        return dictionary
    }
}
