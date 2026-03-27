package com.devil.phoenixproject.util

import kotlin.concurrent.Volatile
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_get_global_queue

/**
 * iOS connectivity checker backed by Network.framework's NWPathMonitor.
 *
 * The monitor observes system network path changes on a background dispatch queue
 * and updates [isConnected] atomically. This avoids blocking callers with synchronous
 * reachability checks while giving a reasonably current connectivity signal.
 *
 * The monitor starts immediately on construction and runs for the lifetime of this instance.
 * In practice the instance is singleton-scoped via Koin, so it matches the app lifecycle.
 */
actual class ConnectivityChecker {
    private val monitor = nw_path_monitor_create()

    @Volatile
    private var isConnected = true

    init {
        nw_path_monitor_set_update_handler(monitor) { path ->
            isConnected = nw_path_get_status(path) == nw_path_status_satisfied
        }
        nw_path_monitor_set_queue(
            monitor,
            dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u),
        )
        nw_path_monitor_start(monitor)
    }

    actual fun isOnline(): Boolean = isConnected
}
