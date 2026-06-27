package com.devil.phoenixproject.data.ble

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.util.BlePacketFactory
import com.devil.phoenixproject.util.rethrowIfCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Disco Mode easter egg - rapidly cycles LED colors on the Vitruvian machine.
 *
 * Self-contained module extracted from KableBleRepository (Phase 8).
 * Uses callback for command sending to avoid circular dependency.
 *
 * @param scope Coroutine scope for launching the color cycling job
 * @param sendCommand Callback to send BLE commands (typically KableBleRepository::sendWorkoutCommand)
 */
class DiscoMode(private val scope: CoroutineScope, private val sendCommand: suspend (ByteArray) -> Unit) {
    private val log = Logger.withTag("DiscoMode")

    private var discoJob: Job? = null
    private var lastColorSchemeIndex: Int = 0

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    /**
     * Start disco mode - rapidly cycles through LED color schemes.
     * No-op if already running.
     */
    fun start() {
        if (discoJob?.isActive == true) {
            log.d { "Disco mode already active" }
            return
        }

        log.i { "Starting DISCO MODE!" }
        _isActive.value = true

        discoJob = scope.launch {
            var colorIndex = 0
            val colorCount = 7 // Schemes 0-6 (excluding "None" at 7)
            val intervalMs = 300L

            try {
                while (isActive) {
                    try {
                        val command = BlePacketFactory.createColorSchemeCommand(colorIndex)
                        sendCommand(command)
                        colorIndex = (colorIndex + 1) % colorCount
                        delay(intervalMs)
                    } catch (e: Exception) {
                        e.rethrowIfCancellation()
                        log.w { "Disco mode error: ${e.message}" }
                        break
                    }
                }
            } finally {
                // F088: reflect reality if the cycling loop exits early (e.g. a
                // sendCommand failure broke the loop) — otherwise isActive stays
                // true and consumers show disco mode as running with no job.
                _isActive.value = false
                log.d { "Disco mode coroutine ended" }
            }
        }
    }

    /**
     * Stop disco mode and restore the last selected color scheme.
     */
    fun stop() {
        if (discoJob?.isActive != true && !_isActive.value) {
            return
        }

        log.i { "Stopping disco mode, restoring color scheme $lastColorSchemeIndex" }
        discoJob?.cancel()
        discoJob = null
        _isActive.value = false

        // Restore the user's color scheme
        scope.launch {
            try {
                val command = BlePacketFactory.createColorSchemeCommand(lastColorSchemeIndex)
                sendCommand(command)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                log.w { "Failed to restore color scheme: ${e.message}" }
            }
        }
    }

    /**
     * Terminal cleanup path. Unlike [stop], this does not send a restore command
     * because the repository is shutting down and its BLE scope may be cancelled.
     */
    fun shutdown() {
        discoJob?.cancel()
        discoJob = null
        _isActive.value = false
    }

    internal fun isJobActiveForTest(): Boolean = discoJob?.isActive == true

    /**
     * Update the stored color scheme index.
     * Called when user changes color in settings.
     */
    fun setLastColorSchemeIndex(index: Int) {
        lastColorSchemeIndex = index
    }
}
