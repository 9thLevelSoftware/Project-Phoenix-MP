package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class MachineSafetyHazardDocument(
    val version: Int = CURRENT_VERSION,
    val generation: Long,
    val trainerAddress: String,
    val trainerName: String? = null,
    val sessionId: String,
    val executionId: Long? = null,
    val profileId: String? = null,
    val workoutKind: MachineSafetyWorkoutKind,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val phase: MachineSafetyPhase,
    val lastConnectResult: MachineSafetyConnectResult = MachineSafetyConnectResult.NONE,
    val lastWriteResult: MachineSafetyWriteResult = MachineSafetyWriteResult.NONE,
    val recoveryAttemptToken: Long = 0L,
    val physicalRelease: MachineSafetyPhysicalRelease = MachineSafetyPhysicalRelease.UNKNOWN,
) {
    init {
        require(version == CURRENT_VERSION)
        require(generation > 0L)
        require(trainerAddress.isNotBlank())
        require(sessionId.isNotBlank())
        require(recoveryAttemptToken >= 0L)
        require(physicalRelease == MachineSafetyPhysicalRelease.UNKNOWN)
    }

    companion object { const val CURRENT_VERSION = 1 }
}

enum class MachineSafetyWorkoutKind { JUST_LIFT, ROUTINE, UNKNOWN }
enum class MachineSafetyPhase { UNRESOLVED, CONNECTING, RELEASE_REQUEST_FAILED, RELEASE_REQUEST_SENT }
enum class MachineSafetyConnectResult { NONE, MATCHING_READY, WRONG_TRAINER, TIMEOUT, FAILED, CANCELLED }
enum class MachineSafetyWriteResult { NONE, NOT_CONNECTED, TRANSPORT_ACK, TRANSPORT_FAIL, DISCONNECTED_DURING_WRITE, CANCELLED, STALE_GENERATION }
@Serializable
enum class MachineSafetyPhysicalRelease { UNKNOWN }

interface MachineSafetyHazardRepository {
    suspend fun load(trainerAddress: String): MachineSafetyLoadResult
    suspend fun loadAll(): List<MachineSafetyLoadResult>
    suspend fun replace(document: MachineSafetyHazardDocument)
    suspend fun deleteIfGenerationMatches(trainerAddress: String, generation: Long): Boolean
}

sealed interface MachineSafetyLoadResult {
    data object Missing : MachineSafetyLoadResult
    data class Loaded(val document: MachineSafetyHazardDocument) : MachineSafetyLoadResult
    data class Rejected(val reason: MachineSafetyRejection, val trainerAddress: String?) : MachineSafetyLoadResult
}
enum class MachineSafetyRejection { CORRUPT_JSON, UNSUPPORTED_VERSION, TRUNCATED, IDENTITY_BLANK }

class SqlDelightMachineSafetyHazardRepository internal constructor(
    database: PhoenixDatabase,
    private val nowEpochMs: () -> Long,
) : MachineSafetyHazardRepository {
    private val queries = database.phoenixDatabaseQueries

    constructor(database: PhoenixDatabase) : this(database, ::currentTimeMillis)

    override suspend fun load(trainerAddress: String): MachineSafetyLoadResult = withContext(Dispatchers.IO) {
        queries.selectMachineSafetyHazard(trainerAddress).executeAsOneOrNull()?.let(::decode)
            ?: MachineSafetyLoadResult.Missing
    }

    override suspend fun loadAll(): List<MachineSafetyLoadResult> = withContext(Dispatchers.IO) {
        queries.selectMachineSafetyHazards().executeAsList().map(::decode)
    }

    override suspend fun replace(document: MachineSafetyHazardDocument) = withContext(Dispatchers.IO) {
        queries.replaceMachineSafetyHazard(
            trainer_address = document.trainerAddress,
            generation = document.generation,
            document_version = document.version.toLong(),
            hazard_json = Json.encodeToString(document),
            updated_at_epoch_ms = nowEpochMs(),
        )
    }

    override suspend fun deleteIfGenerationMatches(trainerAddress: String, generation: Long): Boolean = withContext(Dispatchers.IO) {
        queries.deleteMachineSafetyHazardIfGenerationMatches(trainerAddress, generation).executeAsOne() > 0
    }

    private fun decode(row: com.devil.phoenixproject.database.MachineSafetyHazard): MachineSafetyLoadResult {
        if (row.trainer_address.isBlank()) return MachineSafetyLoadResult.Rejected(MachineSafetyRejection.IDENTITY_BLANK, null)
        if (row.document_version != MachineSafetyHazardDocument.CURRENT_VERSION.toLong()) {
            return MachineSafetyLoadResult.Rejected(MachineSafetyRejection.UNSUPPORTED_VERSION, row.trainer_address)
        }
        return try {
            MachineSafetyLoadResult.Loaded(Json.decodeFromString(row.hazard_json))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            MachineSafetyLoadResult.Rejected(MachineSafetyRejection.CORRUPT_JSON, row.trainer_address)
        }
    }
}
