package com.devil.phoenixproject.util

import kotlin.concurrent.Volatile
import kotlinx.atomicfu.atomic
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_group_create
import platform.darwin.dispatch_group_enter
import platform.darwin.dispatch_group_leave
import platform.darwin.dispatch_group_wait
import platform.darwin.dispatch_time

private const val INITIAL_PATH_TIMEOUT_NS = 200_000_000L // 200 ms

/**
 * tvOS connectivity checker backed by Network.framework's NWPathMonitor.
 *
 * The monitor observes system network path changes on a background dispatch queue
 * and updates [isConnected] atomically. On the very first [isOnline] call before
 * `NWPathMonitor` delivers its initial snapshot, it waits briefly (bounded at
 * 200 ms) on a dispatch group so cold-start sync triggers never observe a false
 * offline state; all subsequent reads are lock-free.
 */
actual class ConnectivityChecker {
    private val monitor = nw_path_monitor_create()
    private val initialPathGroup = dispatch_group_create()
    private val initialPathDelivered = atomic(false)

    @Volatile
    private var isConnected = false

    @Volatile
    private var initialPathResolved = false

    init {
        dispatch_group_enter(initialPathGroup)
        nw_path_monitor_set_update_handler(monitor) { path ->
            isConnected = nw_path_get_status(path) == nw_path_status_satisfied
            initialPathResolved = true
            if (initialPathDelivered.compareAndSet(expect = false, update = true)) {
                dispatch_group_leave(initialPathGroup)
            }
        }
        nw_path_monitor_set_queue(
            monitor,
            dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u),
        )
        nw_path_monitor_start(monitor)
    }

    actual fun isOnline(): Boolean {
        if (!initialPathResolved) {
            dispatch_group_wait(
                initialPathGroup,
                dispatch_time(DISPATCH_TIME_NOW, INITIAL_PATH_TIMEOUT_NS),
            )
        }
        return isConnected
    }
}
