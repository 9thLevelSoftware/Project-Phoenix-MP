package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.util.rethrowIfCancellation
import com.juul.kable.Advertisement
import com.juul.kable.Scanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ref-counted BLE scanner coordinator.
 *
 * On Apple/CoreBluetooth (`CentralManager.Default`), independent `Scanner {}.advertisements`
 * collections share a singleton `CBCentralManager` and call `central.stopScan()` in `.onCompletion`.
 * Keeping a single upstream scan stream alive while `subscribers.isNotEmpty()` prevents Trainer
 * and HRM scans from aborting each other, and `NonCancellable` `1 -> 0` teardown awaits
 * `cancelAndJoin()` so `stopScan()` completes before a new scan starts.
 */
class BleScannerCoordinator internal constructor(
    private val scope: CoroutineScope,
    private val upstreamAdvertisements: () -> Flow<Advertisement>,
) {
    constructor() : this(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        upstreamAdvertisements = { Scanner {}.advertisements },
    )

    private sealed interface ScanEvent {
        data class Item(val advertisement: Advertisement) : ScanEvent
        data class Failure(val error: Throwable) : ScanEvent
        data object Completed : ScanEvent
    }

    private val mutex = Mutex()
    private val subscribers = mutableMapOf<Long, Channel<ScanEvent>>()
    private var nextSubscriberId = 0L
    private var upstreamJob: Job? = null

    fun advertisements(): Flow<Advertisement> = flow {
        var id = 0L
        val channel = Channel<ScanEvent>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val jobToStart = mutex.withLock {
            id = nextSubscriberId++
            subscribers[id] = channel
            val current = upstreamJob
            if (current == null || current.isCompleted) {
                launchUpstream().also { upstreamJob = it }
            } else {
                null
            }
        }
        jobToStart?.start()

        try {
            for (event in channel) {
                when (event) {
                    is ScanEvent.Item -> emit(event.advertisement)
                    is ScanEvent.Failure -> throw event.error
                    ScanEvent.Completed -> break
                }
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    subscribers.remove(id)
                    channel.close()
                    if (subscribers.isEmpty()) {
                        val job = upstreamJob
                        upstreamJob = null
                        withTimeoutOrNull(1_000L) {
                            job?.cancelAndJoin()
                        }
                    }
                }
            }
        }
    }

    private fun launchUpstream(): Job {
        lateinit var launched: Job
        launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                upstreamAdvertisements().collect { advertisement ->
                    mutex.withLock {
                        if (upstreamJob === launched) {
                            subscribers.values.forEach { it.trySend(ScanEvent.Item(advertisement)) }
                        }
                    }
                }
                mutex.withLock {
                    if (upstreamJob === launched) {
                        upstreamJob = null
                        subscribers.values.forEach { it.trySend(ScanEvent.Completed) }
                    }
                }
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                mutex.withLock {
                    if (upstreamJob === launched) {
                        upstreamJob = null
                        subscribers.values.forEach { it.trySend(ScanEvent.Failure(error)) }
                    }
                }
            }
        }
        return launched
    }

    fun shutdown() {
        scope.coroutineContext[Job]?.cancel()
    }
}
