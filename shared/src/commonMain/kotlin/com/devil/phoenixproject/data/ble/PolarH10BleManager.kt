package com.devil.phoenixproject.data.ble

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ConnectionLogRepository
import com.devil.phoenixproject.data.repository.LogEventType
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.data.repository.PolarTelemetryRepository
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.PolarHrMeasurement
import com.devil.phoenixproject.domain.model.PolarBeatSample
import com.devil.phoenixproject.domain.model.PolarEcgSegment
import com.devil.phoenixproject.domain.model.PolarRecovery
import com.devil.phoenixproject.domain.model.PolarPmdMeasurement
import com.devil.phoenixproject.domain.model.PolarRrBeat
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.util.BleConstants
import com.devil.phoenixproject.util.PolarAccFilter
import com.devil.phoenixproject.util.PolarTelemetryUtils
import com.devil.phoenixproject.util.rethrowIfCancellation
import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import com.russhwolf.settings.Settings
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/**
 * Summary metrics for a session recorded by Polar H10.
 */
data class PolarSessionSummary(
    val sessionId: String? = null,
    val avgHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val minHeartRateBpm: Int? = null,
    val avgRrIntervalMs: Float? = null,
    val rmssdMs: Float? = null,
    val hrr60Bpm: Int? = null,
    val hrr300Bpm: Int? = null,
    val maxTorsoPitchDeg: Float? = null,
    val maxTorsoRollDeg: Float? = null,
)

/**
 * Multiplatform BLE Manager for Polar H10 heart rate sensors.
 *
 * Implements independent connection lifecycle, GATT service discovery,
 * standard Heart Rate (0x180D) and Battery (0x180F) telemetry, and proprietary
 * Polar Measurement Data (PMD) streaming for ECG and Accelerometer (ACC).
 *
 * Designed to operate simultaneously alongside [KableBleConnectionManager]
 * without cross-peripheral interference.
 */
class PolarH10BleManager(
    private val settings: Settings? = null,
    private val telemetryRepository: PolarTelemetryRepository? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val timeSource: () -> Long = DEFAULT_WALL_CLOCK,
    monotonicTimeSource: (() -> Long)? = null,
    private val logRepo: ConnectionLogRepository = ConnectionLogRepository.instance,
    private val advertisements: () -> Flow<Advertisement> = { Scanner {}.advertisements },
    private val createPeripheral: (Advertisement) -> Peripheral = { Peripheral(it) },
    private val persistenceDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val log = Logger.withTag("PolarH10BleManager")

    companion object {
        private val monotonicOrigin = TimeSource.Monotonic.markNow()
        val DEFAULT_MONOTONIC_CLOCK: () -> Long = { monotonicOrigin.elapsedNow().inWholeMilliseconds }
        val DEFAULT_WALL_CLOCK: () -> Long = ::currentTimeMillis
        const val KEY_POLAR_REMEMBERED_ADDRESS = "polar_h10_remembered_address"
        const val KEY_POLAR_REMEMBERED_NAME = "polar_h10_remembered_name"
        const val KEY_POLAR_AUTO_RECONNECT = "polar_h10_auto_reconnect_enabled"
        const val CRITICAL_BATTERY_PERCENTAGE = 5
        const val ECG_RAIL_THRESHOLD_UV = 15_000
        const val KEY_RAW_ECG_STORAGE = "polar_h10_raw_ecg_storage_enabled"
        const val MAX_RAW_ECG_DURATION_MS = 300_000L
        const val RAW_ECG_RETENTION_DAYS = 30
        const val MAX_STORED_ECG_SEGMENTS = 36_000L
        private const val MAX_RAW_ECG_SAMPLES = 39_000
        private const val CONTACT_LOSS_DEBOUNCE_MS = 3_000L
    }

    private val monotonicClock: () -> Long = monotonicTimeSource
        ?: if (timeSource === DEFAULT_WALL_CLOCK) DEFAULT_MONOTONIC_CLOCK else timeSource
    private val wallClock: () -> Long = timeSource

    // ===== Remembered Device & Auto-Reconnect =====
    private val _rememberedAddress = MutableStateFlow<String?>(settings?.getStringOrNull(KEY_POLAR_REMEMBERED_ADDRESS))
    val rememberedAddress: StateFlow<String?> = _rememberedAddress.asStateFlow()

    private val _rememberedName = MutableStateFlow<String?>(settings?.getStringOrNull(KEY_POLAR_REMEMBERED_NAME))
    val rememberedName: StateFlow<String?> = _rememberedName.asStateFlow()

    private val _isAutoReconnectEnabled = MutableStateFlow<Boolean>(
        settings?.getBoolean(KEY_POLAR_AUTO_RECONNECT, defaultValue = true) ?: true,
    )
    val isAutoReconnectEnabled: StateFlow<Boolean> = _isAutoReconnectEnabled.asStateFlow()

    fun setAutoReconnectEnabled(enabled: Boolean) {
        settings?.putBoolean(KEY_POLAR_AUTO_RECONNECT, enabled)
        _isAutoReconnectEnabled.value = enabled
    }

    private val _isRawEcgStorageEnabled = MutableStateFlow(
        settings?.getBoolean(KEY_RAW_ECG_STORAGE, false) ?: false,
    )
    val isRawEcgStorageEnabled: StateFlow<Boolean> = _isRawEcgStorageEnabled.asStateFlow()

    fun setRawEcgStorageEnabled(enabled: Boolean) {
        settings?.putBoolean(KEY_RAW_ECG_STORAGE, enabled)
        sessionLock.withLock {
            _isRawEcgStorageEnabled.value = enabled
            if (!enabled) bufferedEcgSegments.clear()
        }
    }

    fun forgetRememberedDevice() {
        settings?.remove(KEY_POLAR_REMEMBERED_ADDRESS)
        settings?.remove(KEY_POLAR_REMEMBERED_NAME)
        _rememberedAddress.value = null
        _rememberedName.value = null
        reconnectJob?.cancel()
        reconnectJob = null
    }

    // ===== Torso Biomechanics Posture & Filtering =====
    private val accFilter = PolarAccFilter()

    private val _torsoPitchDeg = MutableStateFlow<Double?>(null)
    val torsoPitchDeg: StateFlow<Double?> = _torsoPitchDeg.asStateFlow()

    private val _torsoRollDeg = MutableStateFlow<Double?>(null)
    val torsoRollDeg: StateFlow<Double?> = _torsoRollDeg.asStateFlow()

    private val _dynamicAccMg = MutableStateFlow<Int?>(null)
    val dynamicAccMg: StateFlow<Int?> = _dynamicAccMg.asStateFlow()

    fun tareCurrentPosture() {
        accFilter.tareCurrentPosture()
    }

    fun resetPostureTare() {
        accFilter.tarePitchDeg = 0.0
        accFilter.tareRollDeg = 0.0
    }

    // ===== Session Recording & Recovery State =====
    internal data class BufferedBeatSample(
        val timestampMs: Long,
        val phase: String,
        val hrBpm: Long,
        val rrMs: Double,
        val rmssdMs: Double?,
        val torsoPitchDeg: Double?,
        val torsoRollDeg: Double?,
        val dynamicAccMg: Long?,
    )

    internal data class BufferedEcgSegment(
        val sensorTimestampNs: Long,
        val wallClockTimestampMs: Long,
        val phase: String,
        val samplesUvCsv: String,
    )

    private val sessionLock = reentrantLock()
    private var activeSessionId: String? = null
    private var recordedSessionId: String? = null
    private var currentPhase: String = "WARMUP"
    private var cooldownJob: Job? = null
    private val sessionHrs = mutableListOf<Int>()
    private val sessionRrs = mutableListOf<Double>()
    private val recentRrs = ArrayDeque<Double>()
    private val bufferedBeatSamples = mutableListOf<BufferedBeatSample>()
    private val bufferedEcgSegments = mutableListOf<BufferedEcgSegment>()
    private var maxTorsoPitch: Double? = null
    private var maxTorsoRoll: Double? = null
    private var endHeartRate: Int? = null
    private var hrr60: Int? = null
    private var hrr300: Int? = null
    private var cooldownStartTimestampMs: Long? = null
    private var lastBeatMonotonicMs: Long? = null
    private var lastHeartRateMonotonicMs: Long? = null
    private var frozenSessionSummary: PolarSessionSummary? = null
    private var rawEcgStartMs: Long? = null
    private var rawEcgSampleCount = 0
    private val persistenceMutex = Mutex()
    private data class PendingTelemetry(
        val sessionId: String,
        val beats: List<BufferedBeatSample> = emptyList(),
        val ecgs: List<BufferedEcgSegment> = emptyList(),
        val recovery: PolarSessionSummary? = null,
    )
    private val pendingTelemetry = mutableListOf<PendingTelemetry>()
    private data class DetachedRecording(
        val beats: List<BufferedBeatSample>,
        val ecgs: List<BufferedEcgSegment>,
        val summary: PolarSessionSummary?,
    )
    private val detachedRecordings = mutableMapOf<String, DetachedRecording>()
    private val _telemetryPersistenceError = MutableStateFlow<String?>(null)
    val telemetryPersistenceError: StateFlow<String?> = _telemetryPersistenceError.asStateFlow()

    init {
        if (telemetryRepository != null) scope.launch(persistenceDispatcher) {
            persistenceMutex.withLock {
                try {
                    telemetryRepository.pruneRawEcg()
                } catch (error: Exception) {
                    error.rethrowIfCancellation()
                    _telemetryPersistenceError.value = error.message
                    log.e(error) { "Failed to prune raw Polar ECG" }
                }
            }
        }
    }

    private val _isCooldownActive = MutableStateFlow(false)
    val isCooldownActive: StateFlow<Boolean> = _isCooldownActive.asStateFlow()

    private val _cooldownSecondsRemaining = MutableStateFlow(0)
    val cooldownSecondsRemaining: StateFlow<Int> = _cooldownSecondsRemaining.asStateFlow()

    private val _cooldownHrr60 = MutableStateFlow<Int?>(null)
    val cooldownHrr60: StateFlow<Int?> = _cooldownHrr60.asStateFlow()

    private val _cooldownHrr300 = MutableStateFlow<Int?>(null)
    val cooldownHrr300: StateFlow<Int?> = _cooldownHrr300.asStateFlow()

    private val _cooldownStartHeartRate = MutableStateFlow<Int?>(null)
    val cooldownStartHeartRate: StateFlow<Int?> = _cooldownStartHeartRate.asStateFlow()

    private val _activeCooldownSessionId = MutableStateFlow<String?>(null)
    val activeCooldownSessionId: StateFlow<String?> = _activeCooldownSessionId.asStateFlow()

    private val _lastRecordedSessionId = MutableStateFlow<String?>(null)
    val lastRecordedSessionId: StateFlow<String?> = _lastRecordedSessionId.asStateFlow()

    private val _latestSessionSummary = MutableStateFlow<PolarSessionSummary?>(null)
    val latestSessionSummary: StateFlow<PolarSessionSummary?> = _latestSessionSummary.asStateFlow()

    private val completedCooldownSessionIds = mutableSetOf<String>()
    private var completedCooldownSessionId: String? = null
    private val committedSessionIds = mutableSetOf<String>()
    private data class CooldownRequest(val sourceId: String, val forceRestart: Boolean)
    private var pendingCooldownRequest: CooldownRequest? = null

    val currentRecordingSessionId: String? get() = sessionLock.withLock { activeSessionId }

    fun startSessionRecording(sessionId: String, phase: String = "WARMUP"): Unit = sessionLock.withLock {
        if (_isCooldownActive.value) stopCooldown()
        pendingCooldownRequest = null
        committedSessionIds.remove(sessionId)
        recordedSessionId?.let { previousId ->
            if (activeSessionId != null || bufferedBeatSamples.isNotEmpty() || bufferedEcgSegments.isNotEmpty()) {
                detachedRecordings[previousId] = DetachedRecording(
                    bufferedBeatSamples.toList(), bufferedEcgSegments.toList(), getSessionSummary(previousId),
                )
            }
        }
        detachedRecordings.remove(sessionId)
        cooldownJob?.cancel()
        cooldownJob = null
        cooldownStartTimestampMs = null
        _isCooldownActive.value = false
        _cooldownSecondsRemaining.value = 0
        _cooldownHrr60.value = null
        _cooldownHrr300.value = null
        _cooldownStartHeartRate.value = null
        _activeCooldownSessionId.value = null
        completedCooldownSessionIds.remove(sessionId)
        completedCooldownSessionId = null
        activeSessionId = sessionId
        recordedSessionId = sessionId
        _lastRecordedSessionId.value = sessionId
        currentPhase = phase
        sessionLock.withLock {
            bufferedBeatSamples.clear()
            bufferedEcgSegments.clear()
            sessionHrs.clear()
            sessionRrs.clear()
            recentRrs.clear()
            maxTorsoPitch = null
            maxTorsoRoll = null
            frozenSessionSummary = null
            rawEcgStartMs = null
            rawEcgSampleCount = 0
        }
        lastBeatMonotonicMs = null
        lastHeartRateMonotonicMs = null
        endHeartRate = null
        hrr60 = null
        hrr300 = null
        _latestSessionSummary.value = null

        if (_connectionState.value is ConnectionState.Connected) {
            scope.launch { ensureRecordingTelemetryStreams() }
        } else if (_rememberedAddress.value != null && _isAutoReconnectEnabled.value) {
            log.i { "Workout recording started without active Polar connection; triggering auto-reconnect" }
            ensureConnectedOrReconnect()
        }
    }

    fun freezeSessionRecording(sessionId: String): PolarSessionSummary? = sessionLock.withLock {
        if (sessionId != recordedSessionId) return detachedRecordings[sessionId]?.summary
        if (frozenSessionSummary == null) frozenSessionSummary = getSessionSummary(sessionId)
        if (!_isCooldownActive.value) activeSessionId = null
        frozenSessionSummary
    }

    /**
     * Flush buffered telemetry records once the parent row is committed.
     * The returned job finishes after the queued IO transaction.
     */
    fun flushSessionTelemetry(sessionId: String, recordedSourceId: String = sessionId): Job? {
        var deferredCooldown: CooldownRequest? = null
        val (beatsToFlush, ecgsToFlush) = sessionLock.withLock {
            if (recordedSourceId != recordedSessionId) {
                val detached = detachedRecordings.remove(recordedSourceId) ?: return null
                committedSessionIds.add(sessionId)
                if (pendingCooldownRequest?.sourceId == recordedSourceId) pendingCooldownRequest = null
                return queueTelemetry(PendingTelemetry(sessionId, detached.beats, detached.ecgs))
            }
            committedSessionIds.add(sessionId)
            deferredCooldown = pendingCooldownRequest?.takeIf { it.sourceId == recordedSourceId }
            pendingCooldownRequest = null
            frozenSessionSummary = (frozenSessionSummary ?: getSessionSummary(recordedSourceId))?.copy(sessionId = sessionId)
            if (!_isCooldownActive.value) {
                activeSessionId = null
            } else {
                activeSessionId = sessionId
                _activeCooldownSessionId.value = sessionId
            }
            recordedSessionId = sessionId
            _lastRecordedSessionId.value = sessionId
            val beats = bufferedBeatSamples.toList()
            bufferedBeatSamples.clear()
            val ecgs = bufferedEcgSegments.toList()
            bufferedEcgSegments.clear()
            Pair(beats, ecgs)
        }
        _latestSessionSummary.value = getSessionSummary(sessionId)

        val flushJob = queueTelemetry(PendingTelemetry(sessionId, beatsToFlush, ecgsToFlush))
        deferredCooldown?.let { triggerCooldown(sessionId, it.forceRestart) }
        return flushJob
    }

    private fun queueTelemetry(batch: PendingTelemetry): Job? {
        val repository = telemetryRepository ?: return null
        sessionLock.withLock { pendingTelemetry.add(batch) }
        return scope.launch(persistenceDispatcher) {
            persistenceMutex.withLock {
                while (true) {
                    val pending = sessionLock.withLock { pendingTelemetry.firstOrNull() } ?: break
                    try {
                        repository.saveTelemetry(
                            sessionId = pending.sessionId,
                            beats = pending.beats.map { beat ->
                                PolarBeatSample(
                                    id = 0,
                                    sessionId = pending.sessionId,
                                    timestampMs = beat.timestampMs,
                                    phase = beat.phase,
                                    hrBpm = beat.hrBpm,
                                    rrMs = beat.rrMs,
                                    rmssdMs = beat.rmssdMs,
                                    torsoPitchDeg = beat.torsoPitchDeg,
                                    torsoRollDeg = beat.torsoRollDeg,
                                    dynamicAccMg = beat.dynamicAccMg,
                                )
                            },
                            ecgs = pending.ecgs.map { segment ->
                                PolarEcgSegment(
                                    id = 0,
                                    sessionId = pending.sessionId,
                                    sensorTimestampNs = segment.sensorTimestampNs,
                                    wallClockTimestampMs = segment.wallClockTimestampMs,
                                    phase = segment.phase,
                                    samplesUvCsv = segment.samplesUvCsv,
                                )
                            },
                            recovery = pending.recovery?.let { PolarRecovery(it.hrr60Bpm, it.hrr300Bpm) },
                        )
                        sessionLock.withLock { pendingTelemetry.remove(pending) }
                        _telemetryPersistenceError.value = null
                    } catch (error: Exception) {
                        error.rethrowIfCancellation()
                        _telemetryPersistenceError.value = error.message ?: "Polar telemetry could not be saved"
                        log.e(error) { "Failed to flush Polar telemetry for ${pending.sessionId}" }
                        break
                    }
                }
            }
        }
    }

    /**
     * Cleanly discards active recording buffers and resets session tracking if a set is cancelled.
     * If [activeSessionId] is already null (meaning the set recording was committed/flushed or ended),
     * this is a strict no-op so it never wipes [_lastRecordedSessionId], [sessionHrs], or a post-workout cooldown.
     */
    fun discardActiveRecording(): Unit = sessionLock.withLock {
        val targetSessionId = activeSessionId ?: return
        if (_isCooldownActive.value) return
        sessionLock.withLock {
            bufferedBeatSamples.clear()
            bufferedEcgSegments.clear()
            sessionHrs.clear()
            sessionRrs.clear()
            recentRrs.clear()
            maxTorsoPitch = null
            maxTorsoRoll = null
        }
        if (_lastRecordedSessionId.value == targetSessionId) {
            _lastRecordedSessionId.value = null
        }
        lastBeatMonotonicMs = null
        activeSessionId = null
        recordedSessionId = null
        completedCooldownSessionId = null
        currentPhase = "WARMUP"
        _latestSessionSummary.value = null
    }

    internal fun getBufferedBeatSamplesForTesting(): List<BufferedBeatSample> =
        sessionLock.withLock { bufferedBeatSamples.toList() }

    internal fun getBufferedEcgSegmentsForTesting(): List<BufferedEcgSegment> =
        sessionLock.withLock { bufferedEcgSegments.toList() }

    fun updateSessionPhase(phase: String): Unit = sessionLock.withLock {
        if (phase == "WORKING_SET" || phase == "WARMUP") {
            if (_isCooldownActive.value || currentPhase == "POST_WORKOUT_COOLDOWN") {
                stopCooldown()
            }
        }
        currentPhase = phase
    }

    fun cancelCooldown() {
        sessionLock.withLock { pendingCooldownRequest = null }
        if (_isCooldownActive.value) stopCooldown()
    }

    fun triggerCooldown(sessionId: String? = null, forceRestart: Boolean = false): Unit = sessionLock.withLock {
        if (!endSessionRecording(sessionId, forceRestart = forceRestart)) return
        if (_connectionState.value is ConnectionState.Connected) {
            scope.launch { ensureRecordingTelemetryStreams() }
        }
    }

    fun stopCooldown(): Unit = sessionLock.withLock {
        if (!_isCooldownActive.value) return
        val targetSessionId = activeSessionId ?: _activeCooldownSessionId.value
        cooldownJob?.cancel()
        cooldownJob = null
        val elapsedMs = cooldownStartTimestampMs?.let { monotonicClock() - it } ?: 0L
        cooldownStartTimestampMs = null
        val finalHr = _hrMeasurement.value?.hr?.takeIf {
            lastHeartRateMonotonicMs?.let { timestamp -> monotonicClock() - timestamp <= 3000L } == true
        }
        val endHr = endHeartRate ?: _cooldownStartHeartRate.value
        if (finalHr != null && endHr != null) {
            val drop = (endHr - finalHr).coerceAtLeast(0)
            if (hrr60 == null && elapsedMs >= 60_000L) {
                hrr60 = drop
                _cooldownHrr60.value = drop
            }
            if (hrr300 == null && elapsedMs >= 300_000L) {
                hrr300 = drop
                _cooldownHrr300.value = drop
            }
        }
        _isCooldownActive.value = false
        _cooldownSecondsRemaining.value = 0
        if (targetSessionId != null) {
            persistPolarSummary(targetSessionId)
            _latestSessionSummary.value = getSessionSummary(targetSessionId)
            completedCooldownSessionIds.add(targetSessionId)
            completedCooldownSessionId = targetSessionId
            flushSessionTelemetry(targetSessionId)
        }
        activeSessionId = null
        _activeCooldownSessionId.value = null
        scope.launch { stopUnusedTelemetryStreams() }
    }

    fun endSessionRecording(sessionId: String? = null, forceRestart: Boolean = false): Boolean = sessionLock.withLock {
        val knownRecordedId = _lastRecordedSessionId.value ?: recordedSessionId ?: completedCooldownSessionId
        val targetSessionId: String = sessionId ?: knownRecordedId ?: return false
        if (targetSessionId != knownRecordedId) return false
        if (telemetryRepository != null && targetSessionId !in committedSessionIds) {
            pendingCooldownRequest = CooldownRequest(targetSessionId, forceRestart)
            return false
        }
        if (!forceRestart && completedCooldownSessionIds.contains(targetSessionId)) {
            Logger.d { "endSessionRecording: Cooldown already completed for session $targetSessionId; skipping" }
            return false
        }
        if (!forceRestart && _isCooldownActive.value && _activeCooldownSessionId.value == targetSessionId) {
            Logger.d { "endSessionRecording: Cooldown already active for session $targetSessionId; skipping restart" }
            return false
        }
        if (forceRestart) {
            if (_isCooldownActive.value) stopCooldown()
            completedCooldownSessionIds.remove(targetSessionId)
        }
        sessionLock.withLock {
            if (frozenSessionSummary == null) frozenSessionSummary = getSessionSummary(targetSessionId)
        }
        recordedSessionId = targetSessionId
        activeSessionId = targetSessionId
        _lastRecordedSessionId.value = targetSessionId
        _activeCooldownSessionId.value = targetSessionId
        val lastHr = sessionLock.withLock { sessionHrs.lastOrNull() }
        endHeartRate = _hrMeasurement.value?.hr ?: lastHr
        _cooldownStartHeartRate.value = endHeartRate
        currentPhase = "POST_WORKOUT_COOLDOWN"
        _isCooldownActive.value = true
        _cooldownSecondsRemaining.value = 300
        _cooldownHrr60.value = null
        _cooldownHrr300.value = null
        hrr60 = null
        hrr300 = null
        _latestSessionSummary.value = getSessionSummary(targetSessionId)

        cooldownJob?.cancel()
        val cooldownStartMs = monotonicClock()
        cooldownStartTimestampMs = cooldownStartMs
        cooldownJob = scope.launch {
            while (isActive) {
                delay(1000L)
                sessionLock.withLock {
                    if (!_isCooldownActive.value || cooldownStartTimestampMs != cooldownStartMs) return@launch
                    if (peripheral != null && !_isConnected.value) {
                        cancelCooldown()
                        return@launch
                    }
                    checkPacketAbsenceTimeout(monotonicClock())
                    if (!_isCooldownActive.value) return@launch
                    val elapsedMs = monotonicClock() - cooldownStartMs
                    _cooldownSecondsRemaining.value = (300 - elapsedMs / 1000).toInt().coerceAtLeast(0)
                    val currentHr = _hrMeasurement.value?.hr
                    val startingHr = endHeartRate
                    if (elapsedMs >= 60_000L && hrr60 == null && currentHr != null && startingHr != null) {
                        hrr60 = (startingHr - currentHr).coerceAtLeast(0)
                        _cooldownHrr60.value = hrr60
                        persistPolarSummary(targetSessionId)
                    }
                    _latestSessionSummary.value = getSessionSummary(targetSessionId)
                    if (elapsedMs >= 300_000L) {
                        stopCooldown()
                        return@launch
                    }
                }
            }
        }
        true
    }

    private data class SessionSampleSnapshot(
        val hrs: List<Int>,
        val rrs: List<Double>,
        val maxPitch: Double?,
        val rollMax: Double?,
    )

    fun getSessionSummary(sessionId: String): PolarSessionSummary? = sessionLock.withLock {
        detachedRecordings[sessionId]?.let { return it.summary }
        sessionLock.withLock {
            frozenSessionSummary?.takeIf { it.sessionId == sessionId }?.let {
                return it.copy(hrr60Bpm = hrr60, hrr300Bpm = hrr300)
            }
        }
        if (sessionId != recordedSessionId) return null
        val snapshot = sessionLock.withLock {
            SessionSampleSnapshot(
                hrs = sessionHrs.toList(),
                rrs = sessionRrs.toList(),
                maxPitch = maxTorsoPitch,
                rollMax = maxTorsoRoll,
            )
        }
        val avgHr = if (snapshot.hrs.isNotEmpty()) snapshot.hrs.average().roundToInt() else null
        val maxHr = snapshot.hrs.maxOrNull()
        val minHr = snapshot.hrs.minOrNull()
        val validRrs = snapshot.rrs.filter { it in 250.0..2500.0 }
        val avgRr = if (validRrs.isNotEmpty()) validRrs.average().toFloat() else null
        val rmssd = PolarTelemetryUtils.calculateRmssdMs(snapshot.rrs)?.toFloat()
        return PolarSessionSummary(
            sessionId = sessionId,
            avgHeartRateBpm = avgHr,
            maxHeartRateBpm = maxHr,
            minHeartRateBpm = minHr,
            avgRrIntervalMs = avgRr,
            rmssdMs = rmssd,
            hrr60Bpm = hrr60,
            hrr300Bpm = hrr300,
            maxTorsoPitchDeg = snapshot.maxPitch?.toFloat(),
            maxTorsoRollDeg = snapshot.rollMax?.toFloat(),
        )
    }

    private fun persistPolarSummary(sessionId: String) {
        val summary = getSessionSummary(sessionId) ?: return
        queueTelemetry(PendingTelemetry(sessionId, recovery = summary))
    }

    // ===== Connection & Discovery State =====
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    internal fun setConnectedForTesting(connected: Boolean) {
        _isConnected.value = connected
        if (connected) {
            wasEverConnected = true
            isExplicitDisconnect = false
            _connectionState.value = ConnectionState.Connected("Polar H10", "00:11:22:33:44:55")
        } else {
            wasEverConnected = false
            _connectionState.value = ConnectionState.Disconnected
            cleanupSessionTelemetry()
        }
    }

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    // ===== Telemetry State & Streams =====
    private val _isContactDetected = MutableStateFlow(false)
    val isContactDetected: StateFlow<Boolean> = _isContactDetected.asStateFlow()

    fun isBatteryCritical(): Boolean =
        _batteryLevel.value != null && _batteryLevel.value!! <= CRITICAL_BATTERY_PERCENTAGE

    internal fun setContactDetectedForTesting(detected: Boolean) {
        _isContactDetected.value = detected
    }

    internal fun setBatteryLevelForTesting(level: Int?) {
        _batteryLevel.value = level
        if (isBatteryCritical()) {
            _isContactDetected.value = false
            clearCachedTelemetry()
            if (_isCooldownActive.value) {
                cancelCooldown()
            }
        }
    }

    internal fun setEcgStreamingForTesting(streaming: Boolean) {
        _isEcgStreaming.value = streaming
        if (!streaming) {
            _latestEcg.value = null
            checkAndTeardownPmdObservers()
        }
    }

    internal fun setAccStreamingForTesting(streaming: Boolean) {
        _isAccStreaming.value = streaming
        if (!streaming) {
            _latestAcc.value = null
            checkAndTeardownPmdObservers()
        }
    }

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    private val _hrMeasurement = MutableStateFlow<PolarHrMeasurement?>(null)
    val hrMeasurement: StateFlow<PolarHrMeasurement?> = _hrMeasurement.asStateFlow()

    private val _ecgStream = MutableSharedFlow<PolarPmdMeasurement.Ecg>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val ecgStream: SharedFlow<PolarPmdMeasurement.Ecg> = _ecgStream.asSharedFlow()

    private val _accStream = MutableSharedFlow<PolarPmdMeasurement.Acc>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val accStream: SharedFlow<PolarPmdMeasurement.Acc> = _accStream.asSharedFlow()

    private val _latestEcg = MutableStateFlow<PolarPmdMeasurement.Ecg?>(null)
    val latestEcg: StateFlow<PolarPmdMeasurement.Ecg?> = _latestEcg.asStateFlow()

    private val _latestAcc = MutableStateFlow<PolarPmdMeasurement.Acc?>(null)
    val latestAcc: StateFlow<PolarPmdMeasurement.Acc?> = _latestAcc.asStateFlow()

    private val _rrBeats = MutableSharedFlow<PolarRrBeat>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val rrBeats: SharedFlow<PolarRrBeat> = _rrBeats.asSharedFlow()

    // ===== Internal Connection References =====
    @Volatile
    private var peripheral: Peripheral? = null

    @Volatile
    private var wasEverConnected = false

    @Volatile
    private var isExplicitDisconnect = false

    internal val wasEverConnectedForTesting: Boolean get() = wasEverConnected
    internal val isExplicitDisconnectForTesting: Boolean get() = isExplicitDisconnect
    internal val isReconnectJobActiveForTesting: Boolean get() = reconnectJob?.isActive == true

    private var scanJob: Job? = null

    @Volatile
    private var reconnectJob: Job? = null
    private val advertisementLock = reentrantLock()
    private val discoveredAdvertisements = mutableMapOf<String, Advertisement>()

    private fun cachedAdvertisement(address: String): Advertisement? = advertisementLock.withLock {
        discoveredAdvertisements[address]
            ?: discoveredAdvertisements.values.firstOrNull { it.identifier.toString().equals(address, true) }
    }

    private var stateObserverJob: Job? = null
    private var hrObserveJob: Job? = null
    private var batteryObserveJob: Job? = null

    // ===== PMD Stream Control =====
    private val pmdMutex = Mutex()
    private val pmdCommandMutex = Mutex()
    private val telemetryLifecycleMutex = Mutex()

    private suspend fun ensureRecordingTelemetryStreams() = telemetryLifecycleMutex.withLock {
        if (!sessionLock.withLock { activeSessionId != null || _isCooldownActive.value }) return@withLock
        if (pmdControlPointJob?.isActive != true || pmdDataJob?.isActive != true) {
            _isEcgStreaming.value = false
            _isAccStreaming.value = false
        }
        if (!_isEcgStreaming.value) startEcgStream()
        if (!_isAccStreaming.value) startAccStream()
    }

    private suspend fun stopUnusedTelemetryStreams() = telemetryLifecycleMutex.withLock {
        if (sessionLock.withLock { activeSessionId != null || _isCooldownActive.value }) return@withLock
        stopEcgStream()
        stopAccStream()
    }
    private val pmdCpResponses = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var pmdControlPointJob: Job? = null
    private var pmdDataJob: Job? = null

    private val _isEcgStreaming = MutableStateFlow(false)
    val isEcgStreaming: StateFlow<Boolean> = _isEcgStreaming.asStateFlow()

    private val _isAccStreaming = MutableStateFlow(false)
    val isAccStreaming: StateFlow<Boolean> = _isAccStreaming.asStateFlow()

    private var previousContactDetected: Boolean? = null
    private var hasReceivedFirstEcgPacket = false
    private var hasReceivedFirstAccPacket = false

    private fun checkAndTeardownPmdObservers() {
        if (!_isEcgStreaming.value && !_isAccStreaming.value) {
            pmdControlPointJob?.cancel()
            pmdControlPointJob = null
            pmdDataJob?.cancel()
            pmdDataJob = null
        }
    }

    // ===== Chained RR Clock State =====
    private var lastBeatTimestampMs: Long? = null

    /**
     * Start scanning for Polar H10 devices (by name prefix or Heart Rate 0x180D UUID).
     * Automatically stops when [BleConstants.SCAN_TIMEOUT_MS] elapses.
     */
    suspend fun startScanning(): Result<Unit> {
        return try {
            log.i { "Starting BLE scan for Polar H10 devices" }
            logRepo.info(
                eventType = LogEventType.SCAN_START,
                message = "[HRM] Scanning for Polar H10 devices",
                tag = "HRM",
            )
            reconnectJob?.cancel()
            reconnectJob = null
            scanJob?.cancel()
            scanJob = null
            _scannedDevices.value = emptyList()
            advertisementLock.withLock { discoveredAdvertisements.clear() }

            _connectionState.value = ConnectionState.Scanning

            var currentDevices = emptyList<ScannedDevice>()

            scanJob = scope.launch {
                try {
                    withTimeoutOrNull(BleConstants.SCAN_TIMEOUT_MS) {
                        advertisements()
                            .filter { adv -> isPolarH10Advertisement(adv) }
                            .collect { adv ->
                                val identifier = adv.identifier.toString()
                                advertisementLock.withLock { discoveredAdvertisements[identifier] = adv }
                                val name = adv.name ?: "Polar H10"
                                val scanned = ScannedDevice(name = name, address = identifier, rssi = adv.rssi)

                                val updated = currentDevices.toMutableList()
                                val existingIndex = updated.indexOfFirst { it.address == identifier }
                                if (existingIndex >= 0) {
                                    updated[existingIndex] = scanned
                                } else {
                                    updated.add(scanned)
                                }
                                val sorted = updated.sortedByDescending { it.rssi }
                                currentDevices = sorted
                                _scannedDevices.value = sorted
                            }
                    }
                    if (_connectionState.value is ConnectionState.Scanning) {
                        log.i { "Polar scan timeout reached (${BleConstants.SCAN_TIMEOUT_MS}ms)" }
                        logRepo.info(
                            eventType = LogEventType.SCAN_STOP,
                            message = "[HRM] Polar H10 scan timeout reached (${BleConstants.SCAN_TIMEOUT_MS}ms)",
                            tag = "HRM",
                        )
                        _connectionState.value = ConnectionState.Disconnected
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.e(e) { "Scan error: ${e.message}" }
                    if (_connectionState.value is ConnectionState.Scanning) {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.e(e) { "Failed to start Polar scan: ${e.message}" }
            Result.failure(e)
        }
    }

    suspend fun stopScanning() {
        scanJob?.cancel()
        scanJob = null
        if (_connectionState.value is ConnectionState.Scanning) {
            logRepo.info(
                eventType = LogEventType.SCAN_STOP,
                message = "[HRM] Polar H10 scan stopped",
                tag = "HRM",
            )
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private class AutoReconnectMatchFound(val device: ScannedDevice) : CancellationException()

    /**
     * Triggers an immediate auto-reconnect attempt unless explicitly disconnected
     * to a remembered Polar sensor if not already connecting or reconnecting.
     */
    fun ensureConnectedOrReconnect() {
        if (isExplicitDisconnect) return
        if (_connectionState.value is ConnectionState.Connected || _connectionState.value is ConnectionState.Connecting) {
            return
        }
        if (reconnectJob?.isActive == true) {
            return
        }
        reconnectJob = scope.launch { autoReconnectIfRemembered() }
            .also { job -> job.invokeOnCompletion { if (reconnectJob === job) reconnectJob = null } }
    }

    /**
     * Auto-reconnect to the remembered Polar H10 if one was previously connected
     * and auto-reconnect is enabled.
     */
    suspend fun autoReconnectIfRemembered(timeoutMs: Long = 10000L): Result<Unit> {
        if (_connectionState.value is ConnectionState.Connected || _connectionState.value is ConnectionState.Connecting) {
            return Result.success(Unit)
        }
        if (!_isAutoReconnectEnabled.value) {
            return Result.success(Unit)
        }
        if (isExplicitDisconnect) {
            log.d { "Auto-reconnect suppressed due to explicit disconnect" }
            return Result.success(Unit)
        }
        val address = _rememberedAddress.value ?: return Result.success(Unit)
        val name = _rememberedName.value ?: "Polar H10"
        log.i { "Attempting auto-reconnect to remembered Polar H10: $name ($address)" }

        val cachedAdv = cachedAdvertisement(address)
        if (cachedAdv != null) {
            val connectResult = connect(ScannedDevice(name = name, address = address))
            if (connectResult.isSuccess) {
                return connectResult
            }
            log.w { "Direct connection with cached advertisement failed, falling back to targeted scan for $name ($address)" }
        }

        return try {
            val matchedDevice = withTimeoutOrNull(timeoutMs) {
                var found: ScannedDevice? = null
                try {
                    advertisements()
                        .filter { isPolarH10Advertisement(it) }
                        .collect { adv ->
                            val id = adv.identifier.toString()
                            advertisementLock.withLock { discoveredAdvertisements[id] = adv }
                            if (id.equals(address, ignoreCase = true) || (adv.name != null && adv.name == name)) {
                                found = ScannedDevice(name = adv.name ?: name, address = id, rssi = adv.rssi)
                                throw AutoReconnectMatchFound(found!!)
                            }
                        }
                } catch (e: AutoReconnectMatchFound) {
                    found = e.device
                }
                found
            }

            if (matchedDevice != null) {
                connect(matchedDevice)
            } else {
                log.w { "Timed out waiting for remembered Polar H10 ($address)" }
                Result.failure(IllegalStateException("Timed out waiting for remembered Polar H10"))
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.w { "Auto-reconnect to Polar H10 failed: ${e.message}" }
            Result.failure(e)
        }
    }

    /**
     * Connect to the selected Polar H10 device.
     */
    suspend fun connect(device: ScannedDevice): Result<Unit> {
        log.i { "Connecting to Polar H10: ${device.name} (${device.address})" }
        val currentJob = currentCoroutineContext()[Job]
        if (reconnectJob !== currentJob) {
            reconnectJob?.cancel()
        }

        val wasScanning = scanJob?.isActive == true || _connectionState.value is ConnectionState.Scanning
        stopScanning()
        if (wasScanning) {
            delay(150)
        }

        val advertisement = cachedAdvertisement(device.address)
            ?: return Result.failure(IllegalStateException("Polar device ${device.address} not found in scanned cache"))

        logRepo.info(
            eventType = LogEventType.CONNECT_START,
            message = "[HRM] Connecting to Polar H10: ${device.name}",
            deviceName = device.name,
            deviceAddress = device.address,
            tag = "HRM",
        )

        return try {
            cleanupExistingConnection()
            _connectionState.value = ConnectionState.Connecting
            isExplicitDisconnect = false
            wasEverConnected = false

            val p = createPeripheral(advertisement)
            peripheral = p

            stateObserverJob = scope.launch {
                p.state.collect { state ->
                    if (peripheral !== p) {
                        log.d { "Ignoring state change ($state) for stale peripheral $p" }
                        return@collect
                    }
                    log.d { "Polar H10 state: $state" }
                    when (state) {
                        is State.Connecting -> {
                            _isConnected.value = false
                            _connectionState.value = ConnectionState.Connecting
                        }
                        is State.Connected -> {
                            wasEverConnected = true
                            _isConnected.value = true
                            _connectionState.value = ConnectionState.Connected(
                                deviceName = device.name,
                                deviceAddress = device.address,
                            )
                            logRepo.info(
                                eventType = LogEventType.CONNECT_SUCCESS,
                                message = "[HRM] Connected to Polar H10: ${device.name}",
                                deviceName = device.name,
                                deviceAddress = device.address,
                                tag = "HRM",
                            )
                            if (_isAutoReconnectEnabled.value) {
                                settings?.putString(KEY_POLAR_REMEMBERED_ADDRESS, device.address)
                                settings?.putString(KEY_POLAR_REMEMBERED_NAME, device.name)
                                _rememberedAddress.value = device.address
                                _rememberedName.value = device.name
                            }
                            onDeviceConnected(p)
                        }
                        is State.Disconnecting -> {
                            _isConnected.value = false
                        }
                        is State.Disconnected -> {
                            if (!wasEverConnected) {
                                log.d { "Polar H10 initial state: Disconnected (awaiting connect() call)" }
                                return@collect
                            }
                            wasEverConnected = false
                            _isConnected.value = false
                            _connectionState.value = ConnectionState.Disconnected
                            logRepo.info(
                                eventType = LogEventType.DISCONNECT,
                                message = "[HRM] Polar H10 disconnected",
                                deviceName = device.name,
                                deviceAddress = device.address,
                                tag = "HRM",
                            )
                            cleanupSessionTelemetry()
                        }
                    }
                }
            }

            val connected = withTimeoutOrNull(BleConstants.CONNECTION_TIMEOUT_MS) {
                p.connect()
                p.state.first { it is State.Connected }
            }
            check(connected != null) { "Polar H10 connection timed out" }

            Result.success(Unit)
        } catch (e: Exception) {
            withContext(NonCancellable) {
                _isConnected.value = false
                cleanupExistingConnection()
                _connectionState.value = ConnectionState.Disconnected
            }
            e.rethrowIfCancellation()
            log.e(e) { "Failed to connect to Polar H10: ${e.message}" }
            logRepo.error(
                eventType = LogEventType.CONNECT_FAIL,
                message = "[HRM] Failed to connect: ${e.message}",
                deviceName = device.name,
                deviceAddress = device.address,
                details = e.stackTraceToString(),
                tag = "HRM",
            )
            _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun onDeviceConnected(p: Peripheral) {
        log.i { "Configuring Polar H10 connection parameters and services" }
        startWatchdog()

        // 1. Request MTU 232 if supported
        try {
            val mtu = p.requestMtuIfSupported(BleConstants.Polar.DESIRED_PMD_MTU)
            log.i { "Polar H10 MTU negotiated: $mtu (desired: ${BleConstants.Polar.DESIRED_PMD_MTU})" }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.w { "MTU negotiation error: ${e.message}" }
        }

        // 2. Read initial battery level (0x2A19)
        try {
            val batteryBytes = p.read(BleConstants.Polar.batteryLevelCharacteristic)
            val level = parsePolarBatteryLevel(batteryBytes)
            _batteryLevel.value = level
            log.i { "Polar H10 battery level: $level%" }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.w { "Could not read Polar battery level: ${e.message}" }
        }

        batteryObserveJob?.cancel()
        batteryObserveJob = scope.launch {
            try {
                p.observe(BleConstants.Polar.batteryLevelCharacteristic)
                    .catch { e ->
                        e.rethrowIfCancellation()
                        log.d { "Battery notification non-fatal: ${e.message}" }
                    }
                    .collect { bytes ->
                        if (p.state.value !is State.Connected) return@collect
                        parsePolarBatteryLevel(bytes)?.let { level ->
                            _batteryLevel.value = level
                            if (level <= CRITICAL_BATTERY_PERCENTAGE) {
                                log.w { "Polar H10 battery critical ($level%). Dropping telemetry." }
                                _isContactDetected.value = false
                                clearCachedTelemetry()
                                if (_isCooldownActive.value) {
                                    cancelCooldown()
                                }
                            }
                        }
                    }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
            }
        }

        // 3. Subscribe to HR/RR notifications (0x2A37)
        startHrObservation(p)

        // 4. Pre-subscribe to PMD Control Point & Data so observers are active and shared
        try {
            ensurePmdSubscribed(p)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.w { "Pre-subscribing PMD non-fatal (will retry on stream start): ${e.message}" }
        }

        if (activeSessionId != null) {
            scope.launch { ensureRecordingTelemetryStreams() }
        }
    }

    private fun startHrObservation(p: Peripheral) {
        hrObserveJob?.cancel()
        hrObserveJob = scope.launch {
            try {
                log.i { "Subscribing to Polar HR & RR notifications (0x2A37)" }
                logRepo.info(
                    eventType = LogEventType.NOTIFICATION,
                    message = "[HRM] Subscribed to Polar HR & RR notifications (0x2A37)",
                    tag = "HRM",
                )
                p.observe(BleConstants.Polar.hrMeasurementCharacteristic)
                    .catch { e ->
                        e.rethrowIfCancellation()
                        log.e { "Polar HR observation error: ${e.message}" }
                    }
                    .collect { data ->
                        if (p.state.value !is State.Connected) return@collect
                        val measurement = parsePolarHrMeasurement(data) ?: return@collect
                        if (!measurement.contactDetected || measurement.hr <= 0 || isBatteryCritical()) {
                            log.w { "Polar H10 strap removed, zero HR, or critical battery (contact=${measurement.contactDetected}, hr=${measurement.hr}, batt=${_batteryLevel.value}). Dropping telemetry." }
                            handleNoContact(measurement)
                            return@collect
                        }

                        if (previousContactDetected != true) {
                            previousContactDetected = true
                            logRepo.debug(
                                eventType = LogEventType.DIAGNOSTIC,
                                message = "[HRM] Contact detected (contact: false -> true, hr=${measurement.hr})",
                                tag = "HRM",
                            )
                        }

                        _isContactDetected.value = true
                        contactLossJob?.cancel()
                        contactLossJob = null
                        lastValidPacketTimestampMs = monotonicClock()
                        _hrMeasurement.value = measurement

                        processRrIntervals(measurement)
                    }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                log.e(e) { "Failed to maintain Polar HR observation: ${e.message}" }
            }
        }
    }

    private fun handleNoContact(measurement: PolarHrMeasurement): Unit = sessionLock.withLock {
        val hadContact = previousContactDetected == true
        if (previousContactDetected != false) {
            previousContactDetected = false
            if (!measurement.contactDetected) {
                logRepo.warning(
                    eventType = LogEventType.DIAGNOSTIC,
                    message = "[HRM] Poor skin contact detected (contact=${measurement.contactDetected}, hr=${measurement.hr})",
                    tag = "HRM",
                )
            }
            if (hadContact) {
                logRepo.debug(
                    eventType = LogEventType.DIAGNOSTIC,
                    message = "[HRM] Contact lost (contact: true -> false)",
                    tag = "HRM",
                )
            }
        }
        _isContactDetected.value = false
        clearCachedTelemetry()
        if (_isCooldownActive.value && contactLossJob?.isActive != true) contactLossJob = scope.launch {
            delay(CONTACT_LOSS_DEBOUNCE_MS)
            if (!_isContactDetected.value) cancelCooldown()
        }
    }

    /**
     * Reconstructs accurate R-wave beat timestamps by chaining RR intervals together,
     * eliminating packet jitter across connection intervals. Automatically re-anchors
     * if gaps or drift exceed 5 seconds.
     */
    internal fun processRrIntervals(measurement: PolarHrMeasurement): Unit = sessionLock.withLock {
        if (peripheral != null && peripheral?.state?.value !is State.Connected) {
            return
        }
        if (!measurement.contactDetected || measurement.hr <= 0 || isBatteryCritical()) {
            handleNoContact(measurement)
            return
        }
        if (previousContactDetected != true) {
            previousContactDetected = true
            logRepo.debug(
                eventType = LogEventType.DIAGNOSTIC,
                message = "[HRM] Contact detected (contact: false -> true, hr=${measurement.hr})",
                tag = "HRM",
            )
        }
        _isContactDetected.value = true
        contactLossJob?.cancel()
        contactLossJob = null
        lastValidPacketTimestampMs = monotonicClock()
        lastHeartRateMonotonicMs = monotonicClock()
        _hrMeasurement.value = measurement
        val rrs = measurement.rrIntervalsMs

        val nowMonoMs = monotonicClock()
        val nowWallMs = wallClock()
        if (_isCooldownActive.value) {
            val startTs = cooldownStartTimestampMs
            if (startTs != null && (nowMonoMs - startTs) <= 30_000L) {
                val peak = maxOf(endHeartRate ?: measurement.hr, measurement.hr)
                if (endHeartRate != peak) {
                    endHeartRate = peak
                    _cooldownStartHeartRate.value = peak
                }
            }
        }

        if (activeSessionId != null && frozenSessionSummary == null) sessionHrs.add(measurement.hr)
        if (rrs.isEmpty()) return
        val previousBeat = lastBeatTimestampMs
        val previousBeatMono = lastBeatMonotonicMs

        val startingTs: Long = if (previousBeat == null || previousBeatMono == null || (nowMonoMs - previousBeatMono) > 5000L || (nowMonoMs - previousBeatMono) < -5000L) {
            val totalRrDurationMs = rrs.sum().toLong()
            nowWallMs - totalRrDurationMs
        } else {
            previousBeat
        }

        var runningTs = startingTs
        for (rr in rrs) {
            runningTs += rr.toLong()
            val beat = PolarRrBeat(
                rrIntervalMs = rr,
                timestampMs = runningTs,
                hr = measurement.hr,
            )
            _rrBeats.tryEmit(beat)

            val currentSid = activeSessionId
            if (currentSid != null) {
                val currentRmssd = sessionLock.withLock {
                    if (frozenSessionSummary == null) {
                        sessionRrs.add(beat.rrIntervalMs)
                    }
                    recentRrs.addLast(beat.rrIntervalMs)
                    if (recentRrs.size > 30) recentRrs.removeFirst()
                    PolarTelemetryUtils.calculateRmssdMs(recentRrs.toList())
                }
                sessionLock.withLock {
                    bufferedBeatSamples.add(
                        BufferedBeatSample(
                            timestampMs = beat.timestampMs,
                            phase = currentPhase,
                            hrBpm = beat.hr.toLong(),
                            rrMs = beat.rrIntervalMs,
                            rmssdMs = currentRmssd,
                            torsoPitchDeg = _torsoPitchDeg.value,
                            torsoRollDeg = _torsoRollDeg.value,
                            dynamicAccMg = _dynamicAccMg.value?.toLong(),
                        )
                    )
                }
            }
        }

        lastBeatTimestampMs = runningTs
        lastBeatMonotonicMs = nowMonoMs
    }

    private suspend fun ensurePmdSubscribed(p: Peripheral): Boolean = pmdMutex.withLock {
        if (pmdControlPointJob?.isActive == true && pmdDataJob?.isActive == true) {
            return true
        }

        pmdControlPointJob?.cancel()
        pmdDataJob?.cancel()

        val cpSubscribed = CompletableDeferred<Unit>()
        val dataSubscribed = CompletableDeferred<Unit>()

        // 1. Subscribe to PMD Control Point (FB005C81)
        pmdControlPointJob = scope.launch {
            try {
                p.observe(BleConstants.Polar.pmdControlPointCharacteristic, onSubscription = {
                    log.i { "PMD Control Point (FB005C81) subscribed" }
                    cpSubscribed.complete(Unit)
                })
                    .collect { cpData ->
                        log.d { "PMD CP response: ${cpData.size} bytes: ${cpData.joinToString { (it.toInt() and 0xFF).toString(16) }}" }
                        pmdCpResponses.tryEmit(cpData)
                    }
            } catch (e: Exception) {
                if (!cpSubscribed.isCompleted) {
                    cpSubscribed.completeExceptionally(e)
                }
                e.rethrowIfCancellation()
                log.e(e) { "PMD CP observer failed: ${e.message}" }
            } finally {
                if (!cpSubscribed.isCompleted) cpSubscribed.completeExceptionally(
                    IllegalStateException("PMD control point subscription ended"),
                )
            }
        }

        // 2. Subscribe to PMD Data (FB005C82)
        pmdDataJob = scope.launch {
            try {
                p.observe(BleConstants.Polar.pmdDataCharacteristic, onSubscription = {
                    log.i { "PMD Data (FB005C82) subscribed" }
                    dataSubscribed.complete(Unit)
                })
                    .collect { data ->
                        if (p.state.value !is State.Connected) return@collect
                        val measurement = parsePolarPmdData(data) ?: return@collect
                        processPmdMeasurement(measurement)
                    }
            } catch (e: Exception) {
                if (!dataSubscribed.isCompleted) {
                    dataSubscribed.completeExceptionally(e)
                }
                e.rethrowIfCancellation()
                log.e(e) { "PMD Data observer failed: ${e.message}" }
            } finally {
                if (!dataSubscribed.isCompleted) dataSubscribed.completeExceptionally(
                    IllegalStateException("PMD data subscription ended"),
                )
            }
        }

        try {
            val ready = withTimeoutOrNull(BleConstants.GATT_OPERATION_TIMEOUT_MS) {
                cpSubscribed.await()
                dataSubscribed.await()
                true
            }
            check(ready == true) { "Polar PMD subscription timed out" }
        } catch (error: Exception) {
            pmdControlPointJob?.cancel()
            pmdDataJob?.cancel()
            pmdControlPointJob = null
            pmdDataJob = null
            throw error
        }
        true
    }

    internal fun processPmdMeasurement(measurement: PolarPmdMeasurement): Unit = sessionLock.withLock {
        if (peripheral != null && peripheral?.state?.value !is State.Connected) {
            return
        }
        if (!_isContactDetected.value || isBatteryCritical()) {
            log.w { "Polar H10 PMD packet discarded: zero contact or critical battery (contact=${_isContactDetected.value}, batt=${_batteryLevel.value})" }
            return
        }

        when (measurement) {
            is PolarPmdMeasurement.Ecg -> {
                if (!hasReceivedFirstEcgPacket) {
                    hasReceivedFirstEcgPacket = true
                    logRepo.debug(
                        eventType = LogEventType.DIAGNOSTIC,
                        message = "[HRM] ECG first packet received (130Hz)",
                        tag = "HRM",
                    )
                }
                val samples = measurement.samplesMicrovolts
                val isFlatlineOrZero = samples.isEmpty() ||
                    samples.all { it == 0 } ||
                    (samples.isNotEmpty() && samples.all { it == samples[0] })
                val isRailed = samples.any { abs(it) >= ECG_RAIL_THRESHOLD_UV }
                if (isFlatlineOrZero || isRailed) {
                    log.w { "Polar H10 ECG flatline/railing open-circuit noise detected (flatline=$isFlatlineOrZero, railed=$isRailed). Dropping transient frame." }
                    _latestEcg.value = null
                    return
                }
                lastValidPacketTimestampMs = monotonicClock()
                _latestEcg.value = measurement
                _ecgStream.tryEmit(measurement)
                val currentSid = activeSessionId
                if (currentSid != null && _isRawEcgStorageEnabled.value) {
                    val nowMs = wallClock()
                    sessionLock.withLock {
                        val startMs = rawEcgStartMs ?: monotonicClock().also { rawEcgStartMs = it }
                        if (monotonicClock() - startMs >= MAX_RAW_ECG_DURATION_MS) return@withLock
                        val retained = measurement.samplesMicrovolts.take(MAX_RAW_ECG_SAMPLES - rawEcgSampleCount)
                        if (retained.isEmpty()) return@withLock
                        rawEcgSampleCount += retained.size
                        bufferedEcgSegments.add(
                            BufferedEcgSegment(
                                sensorTimestampNs = measurement.sensorTimestampNs,
                                wallClockTimestampMs = nowMs,
                                phase = currentPhase,
                                samplesUvCsv = retained.joinToString(","),
                            )
                        )
                    }
                }
            }
            is PolarPmdMeasurement.Acc -> {
                if (!hasReceivedFirstAccPacket) {
                    hasReceivedFirstAccPacket = true
                    logRepo.debug(
                        eventType = LogEventType.DIAGNOSTIC,
                        message = "[HRM] ACC first packet received (50Hz)",
                        tag = "HRM",
                    )
                }
                if (measurement.samples.isEmpty()) return
                lastValidPacketTimestampMs = monotonicClock()
                _latestAcc.value = measurement
                _accStream.tryEmit(measurement)
                for (s in measurement.samples) {
                    val bio = accFilter.processSample(s.xMg, s.yMg, s.zMg)
                    _torsoPitchDeg.value = bio.pitchDeg
                    _torsoRollDeg.value = bio.rollDeg
                    _dynamicAccMg.value = bio.dynamicAccMg
                    if (activeSessionId != null && frozenSessionSummary == null) {
                        sessionLock.withLock {
                            if (maxTorsoPitch == null) {
                                maxTorsoPitch = bio.pitchDeg
                            } else {
                                val currentMax = maxTorsoPitch!!
                                if (bio.pitchDeg > currentMax) {
                                    maxTorsoPitch = bio.pitchDeg
                                } else if (currentMax <= 0.0 && abs(bio.pitchDeg) > abs(currentMax)) {
                                    maxTorsoPitch = bio.pitchDeg
                                }
                            }
                            if (maxTorsoRoll == null || abs(bio.rollDeg) > abs(maxTorsoRoll!!)) {
                                maxTorsoRoll = bio.rollDeg
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun sendPmdControlCommand(
        p: Peripheral,
        command: ByteArray,
        opCode: Byte,
        measurementType: Byte,
        timeoutMs: Long = 2000L,
    ): Result<Int> = pmdCommandMutex.withLock {
        ensurePmdSubscribed(p)
        // Dwell 50ms before writing to ensure Android GATT queue is clean
        delay(50L)

        val responseDeferred = CompletableDeferred<Int>()
        val responseListener = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pmdCpResponses
                .filter { resp ->
                    resp.size >= 4 &&
                    (resp[0].toInt() and 0xFF) == 0xF0 &&
                    resp[1] == opCode &&
                    resp[2] == measurementType
                }
                .first()
                .let { resp ->
                    val status = resp[3].toInt() and 0xFF
                    responseDeferred.complete(status)
                }
        }

        try {
            p.write(
                characteristic = BleConstants.Polar.pmdControlPointCharacteristic,
                data = command,
                writeType = WriteType.WithResponse,
            )
            val status = withTimeoutOrNull(timeoutMs) {
                responseDeferred.await()
            } ?: throw IllegalStateException("Polar PMD control point response timed out")
            if (status == 0) {
                logRepo.debug(
                    eventType = LogEventType.COMMAND_RESPONSE,
                    message = "[HRM] PMD CP response: op=0x${opCode.toString(16)}, type=0x${measurementType.toString(16)}, status=0",
                    tag = "HRM",
                )
            } else {
                logRepo.warning(
                    eventType = LogEventType.COMMAND_RESPONSE,
                    message = "[HRM] PMD CP non-zero status: op=0x${opCode.toString(16)}, type=0x${measurementType.toString(16)}, status=$status",
                    tag = "HRM",
                )
            }
            // Settle 150ms before releasing lock to ensure H10 PMD state machine is ready for next command
            delay(150L)
            Result.success(status)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logRepo.error(
                eventType = LogEventType.ERROR,
                message = "[HRM] PMD CP write failed: ${e.message}",
                details = e.stackTraceToString(),
                tag = "HRM",
            )
            Result.failure(e)
        } finally {
            responseListener.cancel()
        }
    }

    /**
     * Start the 130 Hz 14-bit ECG stream.
     * Ensures PMD Control Point and Data notifications are active before sending command.
     */
    suspend fun startEcgStream(): Result<Unit> {
        val p = peripheral ?: return Result.failure(IllegalStateException("Polar H10 not connected"))
        return try {
            log.i { "Starting Polar H10 ECG stream" }
            logRepo.info(
                eventType = LogEventType.COMMAND_SENT,
                message = "[HRM] Starting 130Hz ECG stream",
                tag = "HRM",
            )
            hasReceivedFirstEcgPacket = false
            val status = sendPmdControlCommand(
                p = p,
                command = BleConstants.Polar.PMD_START_ECG_CMD,
                opCode = 0x02,
                measurementType = 0x00,
            ).getOrThrow()
            if (status != 0) {
                return Result.failure(IllegalStateException("Polar H10 rejected ECG start: status $status"))
            }
            _isEcgStreaming.value = true

            // Watchdog: If zero ECG packets arrive within 1500ms, retry START_ECG command once
            scope.launch {
                delay(1500L)
                if (_isEcgStreaming.value && !hasReceivedFirstEcgPacket && _isConnected.value) {
                    log.w { "No ECG packets received within 1500ms of start command; re-sending START_ECG once" }
                    logRepo.warning(
                        eventType = LogEventType.DIAGNOSTIC,
                        message = "[HRM] ECG packet timeout (1500ms); re-transmitting START_ECG",
                        tag = "HRM",
                    )
                    val activePeripheral = peripheral
                    if (activePeripheral != null && _connectionState.value is ConnectionState.Connected) {
                        sendPmdControlCommand(
                            p = activePeripheral,
                            command = BleConstants.Polar.PMD_START_ECG_CMD,
                            opCode = 0x02,
                            measurementType = 0x00,
                        )
                    }
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.e(e) { "Failed to start ECG stream: ${e.message}" }
            logRepo.error(
                eventType = LogEventType.ERROR,
                message = "[HRM] Failed to start ECG stream: ${e.message}",
                details = e.stackTraceToString(),
                tag = "HRM",
            )
            Result.failure(e)
        }
    }

    /**
     * Stop the ECG stream.
     */
    suspend fun stopEcgStream(): Result<Unit> {
        val p = peripheral ?: return Result.failure(IllegalStateException("Polar H10 not connected"))
        return try {
            log.i { "Stopping Polar H10 ECG stream" }
            logRepo.info(
                eventType = LogEventType.COMMAND_SENT,
                message = "[HRM] Stopping 130Hz ECG stream",
                tag = "HRM",
            )
            sendPmdControlCommand(
                p = p,
                command = BleConstants.Polar.PMD_STOP_ECG_CMD,
                opCode = 0x03,
                measurementType = 0x00,
            ).getOrThrow().also { check(it == 0) { "Polar H10 rejected ECG stop: status $it" } }
            _isEcgStreaming.value = false
            _latestEcg.value = null
            checkAndTeardownPmdObservers()
            Result.success(Unit)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.e(e) { "Failed to stop ECG stream: ${e.message}" }
            logRepo.error(
                eventType = LogEventType.ERROR,
                message = "[HRM] Failed to stop ECG stream: ${e.message}",
                details = e.stackTraceToString(),
                tag = "HRM",
            )
            Result.failure(e)
        }
    }

    /**
     * Start the 50 Hz 16-bit ACC stream (±8G range).
     * Ensures PMD Control Point and Data notifications are active before sending command.
     * Automatically retries with ascending TLV order or 25Hz/2G fallback if range-first TLV is rejected.
     */
    suspend fun startAccStream(): Result<Unit> {
        val p = peripheral ?: return Result.failure(IllegalStateException("Polar H10 not connected"))
        return try {
            log.i { "Starting Polar H10 ACC stream" }
            logRepo.info(
                eventType = LogEventType.COMMAND_SENT,
                message = "[HRM] Starting 50Hz ACC stream",
                tag = "HRM",
            )
            hasReceivedFirstAccPacket = false
            // Try 1: standard command (range-first)
            var status = sendPmdControlCommand(
                p = p,
                command = BleConstants.Polar.PMD_START_ACC_CMD,
                opCode = 0x02,
                measurementType = 0x02,
            ).getOrThrow()

            if (status != 0) {
                log.w { "Starting ACC with range-first TLV returned status $status. Retrying with ascending TLV (50Hz, 16-bit, 8G)..." }
                status = sendPmdControlCommand(
                    p = p,
                    command = BleConstants.Polar.PMD_START_ACC_ASCENDING_CMD,
                    opCode = 0x02,
                    measurementType = 0x02,
                ).getOrThrow()
            }

            if (status != 0) {
                log.w { "Starting ACC with ascending TLV returned status $status. Retrying with 25Hz/2G fallback..." }
                status = sendPmdControlCommand(
                    p = p,
                    command = BleConstants.Polar.PMD_START_ACC_FALLBACK_25HZ_CMD,
                    opCode = 0x02,
                    measurementType = 0x02,
                ).getOrThrow()
            }

            if (status != 0) {
                log.e { "Polar H10 rejected ACC start command with status $status" }
                logRepo.error(
                    eventType = LogEventType.COMMAND_RESPONSE,
                    message = "[HRM] ACC start command rejected: status $status",
                    tag = "HRM",
                )
                return Result.failure(IllegalStateException("Polar H10 rejected ACC start command: status $status"))
            }

            _isAccStreaming.value = true
            Result.success(Unit)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.e(e) { "Failed to start ACC stream: ${e.message}" }
            logRepo.error(
                eventType = LogEventType.ERROR,
                message = "[HRM] Failed to start ACC stream: ${e.message}",
                details = e.stackTraceToString(),
                tag = "HRM",
            )
            Result.failure(e)
        }
    }

    /**
     * Stop the ACC stream.
     */
    suspend fun stopAccStream(): Result<Unit> {
        val p = peripheral ?: return Result.failure(IllegalStateException("Polar H10 not connected"))
        return try {
            log.i { "Stopping Polar H10 ACC stream" }
            logRepo.info(
                eventType = LogEventType.COMMAND_SENT,
                message = "[HRM] Stopping 50Hz ACC stream",
                tag = "HRM",
            )
            sendPmdControlCommand(
                p = p,
                command = BleConstants.Polar.PMD_STOP_ACC_CMD,
                opCode = 0x03,
                measurementType = 0x02,
            ).getOrThrow().also { check(it == 0) { "Polar H10 rejected ACC stop: status $it" } }
            _isAccStreaming.value = false
            _latestAcc.value = null
            accFilter.reset()
            _torsoPitchDeg.value = null
            _torsoRollDeg.value = null
            _dynamicAccMg.value = null
            checkAndTeardownPmdObservers()
            Result.success(Unit)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            log.e(e) { "Failed to stop ACC stream: ${e.message}" }
            logRepo.error(
                eventType = LogEventType.ERROR,
                message = "[HRM] Failed to stop ACC stream: ${e.message}",
                details = e.stackTraceToString(),
                tag = "HRM",
            )
            Result.failure(e)
        }
    }

    /**
     * Non-suspending helper to stop ECG and ACC streams asynchronously on the manager's scope.
     * Safe to invoke from [DisposableEffect.onDispose].
     */
    fun stopTelemetryStreamsAsync() {
        scope.launch { stopUnusedTelemetryStreams() }
    }

    fun releaseTelemetryPreviewAsync() = stopTelemetryStreamsAsync()

    // ===== Watchdog for Packet Absence (> 3s) =====
    private var watchdogJob: Job? = null
    private var contactLossJob: Job? = null
    private var lastValidPacketTimestampMs: Long = 0L

    internal fun checkPacketAbsenceTimeout(currentTimestampMs: Long = monotonicClock()): Unit = sessionLock.withLock {
        if (_isCooldownActive.value &&
            lastHeartRateMonotonicMs?.let { currentTimestampMs - it > 3000L } != false
        ) {
            _hrMeasurement.value = null
            cancelCooldown()
        }
        if (lastValidPacketTimestampMs > 0L && (currentTimestampMs - lastValidPacketTimestampMs) > 3000L) {
            if (_hrMeasurement.value != null || _latestEcg.value != null || _latestAcc.value != null || _torsoPitchDeg.value != null) {
                log.w { "Polar H10 packet absence timeout (>3s). Clearing telemetry streams." }
                logRepo.warning(
                    eventType = LogEventType.DIAGNOSTIC,
                    message = "[HRM] Stream packet absence timeout (>3s). Clearing telemetry.",
                    tag = "HRM",
                )
                clearCachedTelemetry()
                if (_isCooldownActive.value) {
                    log.i { "Terminating recovery cooldown due to packet absence timeout (>3s)" }
                    cancelCooldown()
                }
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        lastValidPacketTimestampMs = monotonicClock()
        watchdogJob = scope.launch {
            var lastStreamRetryMs = monotonicClock()
            while (isActive && _isConnected.value) {
                delay(500L)
                val nowMs = monotonicClock()
                checkPacketAbsenceTimeout(nowMs)
                if (nowMs - lastStreamRetryMs >= 5000L &&
                    sessionLock.withLock { activeSessionId != null || _isCooldownActive.value } &&
                    (!_isEcgStreaming.value || !_isAccStreaming.value ||
                        pmdControlPointJob?.isActive != true || pmdDataJob?.isActive != true)
                ) {
                    lastStreamRetryMs = nowMs
                    scope.launch { ensureRecordingTelemetryStreams() }
                }
            }
        }
    }

    /**
     * Disconnect from the Polar H10 device and clean up all active telemetry streams.
     */
    suspend fun disconnect() {
        _isConnected.value = false
        isExplicitDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null

        val p = peripheral
        if (p != null) {
            log.i { "Disconnecting Polar H10" }
            logRepo.info(
                eventType = LogEventType.DISCONNECT,
                message = "[HRM] Disconnecting Polar H10",
                tag = "HRM",
            )
            try {
                if (_isEcgStreaming.value) {
                    try { stopEcgStream() } catch (_: Exception) {}
                }
                if (_isAccStreaming.value) {
                    try { stopAccStream() } catch (_: Exception) {}
                }
                peripheral = null
                p.disconnect()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                log.w { "Error during Polar disconnect: ${e.message}" }
            }
            try {
                p.close()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                log.w { "Error during Polar close: ${e.message}" }
            }
        }
        cleanupExistingConnection()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun cleanupSessionTelemetry() {
        contactLossJob?.cancel()
        contactLossJob = null
        hrObserveJob?.cancel()
        hrObserveJob = null
        batteryObserveJob?.cancel()
        batteryObserveJob = null

        pmdControlPointJob?.cancel()
        pmdControlPointJob = null
        pmdDataJob?.cancel()
        pmdDataJob = null

        watchdogJob?.cancel()
        watchdogJob = null

        _isEcgStreaming.value = false
        _isAccStreaming.value = false

        _isConnected.value = false
        _batteryLevel.value = null
        clearCachedTelemetry(resetHeartRateClock = true)

        if (_isCooldownActive.value) {
            cancelCooldown()
        }
    }

    internal fun clearCachedTelemetry(resetHeartRateClock: Boolean = false): Unit = sessionLock.withLock {
        if (previousContactDetected == true) {
            previousContactDetected = false
            logRepo.debug(
                eventType = LogEventType.DIAGNOSTIC,
                message = "[HRM] Contact lost (telemetry cleared)",
                tag = "HRM",
            )
        }
        _isContactDetected.value = false
        lastBeatTimestampMs = null
        lastBeatMonotonicMs = null
        if (resetHeartRateClock) lastHeartRateMonotonicMs = null
        _hrMeasurement.value = null
        _latestEcg.value = null
        _latestAcc.value = null

        accFilter.reset()
        _torsoPitchDeg.value = null
        _torsoRollDeg.value = null
        _dynamicAccMg.value = null
    }

    private suspend fun cleanupExistingConnection() {
        stateObserverJob?.cancel()
        stateObserverJob = null
        cleanupSessionTelemetry()
        val p = peripheral
        peripheral = null
        if (p != null) {
            try {
                p.disconnect()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                log.w { "Cleanup disconnect error (non-fatal): ${e.message}" }
            }
            try {
                p.close()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                log.w { "Cleanup close error (non-fatal): ${e.message}" }
            }
        }
        wasEverConnected = false
    }

    fun shutdown() {
        _isConnected.value = false
        isExplicitDisconnect = true
        scanJob?.cancel()
        scanJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        stateObserverJob?.cancel()
        stateObserverJob = null
        val p = peripheral
        peripheral = null
        wasEverConnected = false
        cleanupSessionTelemetry()
        _connectionState.value = ConnectionState.Disconnected
        if (p != null) {
            scope.launch {
                try {
                    if (_isEcgStreaming.value) {
                        try { stopEcgStream() } catch (_: Exception) {}
                    }
                    if (_isAccStreaming.value) {
                        try { stopAccStream() } catch (_: Exception) {}
                    }
                    p.disconnect()
                } catch (_: Exception) {}
                try {
                    p.close()
                } catch (_: Exception) {}
            }
        }
    }

    internal fun isPolarH10Advertisement(adv: Advertisement): Boolean {
        val name = adv.name ?: return false
        return name.equals("Polar H10", ignoreCase = true) || name.startsWith("Polar H10 ", ignoreCase = true)
    }
}
