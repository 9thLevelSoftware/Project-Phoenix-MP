package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.PolarBeatSample
import com.devil.phoenixproject.domain.model.PolarEcgSegment
import com.devil.phoenixproject.domain.model.PolarRecovery
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class SqlDelightPolarTelemetryRepository(
    private val db: PhoenixDatabase,
    private val wallClock: () -> Long = ::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PolarTelemetryRepository {
    companion object {
        const val RAW_ECG_RETENTION_DAYS = 30
        const val MAX_STORED_ECG_SEGMENTS = 36_000L
    }

    private val queries = db.phoenixDatabaseQueries

    override suspend fun saveTelemetry(
        sessionId: String,
        beats: List<PolarBeatSample>,
        ecgs: List<PolarEcgSegment>,
        recovery: PolarRecovery?,
    ) {
        withContext(dispatcher) {
            db.transaction {
                if (queries.selectSessionById(sessionId).executeAsOneOrNull() == null) return@transaction
                beats.forEach { beat ->
                    queries.insertPolarBeatSample(
                        sessionId = sessionId,
                        timestampMs = beat.timestampMs,
                        phase = beat.phase,
                        hrBpm = beat.hrBpm,
                        rrMs = beat.rrMs,
                        rmssdMs = beat.rmssdMs,
                        torsoPitchDeg = beat.torsoPitchDeg,
                        torsoRollDeg = beat.torsoRollDeg,
                        dynamicAccMg = beat.dynamicAccMg,
                    )
                }
                ecgs.forEach { segment ->
                    queries.insertPolarEcgSegment(
                        sessionId = sessionId,
                        sensorTimestampNs = segment.sensorTimestampNs,
                        wallClockTimestampMs = segment.wallClockTimestampMs,
                        phase = segment.phase,
                        samplesUvCsv = segment.samplesUvCsv,
                    )
                }
                recovery?.let {
                    queries.updateSessionPolarRecovery(
                        id = sessionId,
                        hrr60Bpm = it.hrr60Bpm?.toLong(),
                        hrr300Bpm = it.hrr300Bpm?.toLong(),
                    )
                }
                pruneRawEcgInTransaction()
            }
        }
    }

    override suspend fun pruneRawEcg() {
        withContext(dispatcher) {
            db.transaction {
                pruneRawEcgInTransaction()
            }
        }
    }

    private fun pruneRawEcgInTransaction() {
        queries.deleteExpiredPolarEcgSegments(wallClock() - RAW_ECG_RETENTION_DAYS * 86_400_000L)
        queries.trimPolarEcgSegments(MAX_STORED_ECG_SEGMENTS)
    }
}
