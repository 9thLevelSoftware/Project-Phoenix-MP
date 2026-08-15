package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class SqlDelightActiveWorkoutRuntimeRepository internal constructor(
    database: VitruvianDatabase,
    private val nowEpochMs: () -> Long,
    private val codec: ActiveWorkoutRuntimeJsonCodec,
) : ActiveWorkoutRuntimeRepository {
    private val queries = database.vitruvianDatabaseQueries

    constructor(
        database: VitruvianDatabase,
        nowEpochMs: () -> Long = ::currentTimeMillis,
    ) : this(database, nowEpochMs, StrictActiveWorkoutRuntimeJsonCodec)

    override suspend fun load(
        profileId: String,
        routineSessionId: String,
    ): ActiveWorkoutRuntimeLoadResult = withContext(Dispatchers.IO) {
        val row = queries.selectActiveWorkoutRuntime(profileId, routineSessionId).executeAsOneOrNull()
            ?: return@withContext ActiveWorkoutRuntimeLoadResult.Missing
        decode(row.document_version, row.runtime_json)
    }

    override suspend fun replace(
        profileId: String,
        routineSessionId: String,
        document: ActiveWorkoutRuntimeDocument,
    ) = withContext(Dispatchers.IO) {
        require(profileId == document.profileId)
        require(routineSessionId == document.routineSessionId)
        queries.replaceActiveWorkoutRuntime(
            profile_id = profileId,
            routine_session_id = routineSessionId,
            document_version = document.version.toLong(),
            runtime_json = codec.encode(document),
            updated_at_epoch_ms = nowEpochMs(),
        )
        Unit
    }

    override suspend fun delete(profileId: String, routineSessionId: String) = withContext(Dispatchers.IO) {
        queries.deleteActiveWorkoutRuntime(profileId, routineSessionId)
        Unit
    }

    private fun decode(storedVersion: Long, payload: String): ActiveWorkoutRuntimeLoadResult {
        val parsed = try {
            codec.parseObject(payload)
                ?: return ActiveWorkoutRuntimeLoadResult.Rejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return ActiveWorkoutRuntimeLoadResult.Rejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
        }

        val payloadVersion = try {
            codec.version(parsed)
                ?: return ActiveWorkoutRuntimeLoadResult.Rejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return ActiveWorkoutRuntimeLoadResult.Rejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
        }

        if (
            storedVersion != ActiveWorkoutRuntimeDocument.CURRENT_VERSION.toLong() ||
            payloadVersion != ActiveWorkoutRuntimeDocument.CURRENT_VERSION
        ) {
            return ActiveWorkoutRuntimeLoadResult.Rejected(ActiveWorkoutRuntimeRejection.UNSUPPORTED_VERSION)
        }

        return try {
            ActiveWorkoutRuntimeLoadResult.Loaded(codec.decode(payload))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ActiveWorkoutRuntimeLoadResult.Rejected(ActiveWorkoutRuntimeRejection.CORRUPT_JSON)
        }
    }
}

internal interface ActiveWorkoutRuntimeJsonCodec {
    fun encode(document: ActiveWorkoutRuntimeDocument): String
    fun parseObject(payload: String): JsonObject?
    fun version(document: JsonObject): Int?
    fun decode(payload: String): ActiveWorkoutRuntimeDocument
}

private object StrictActiveWorkoutRuntimeJsonCodec : ActiveWorkoutRuntimeJsonCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        encodeDefaults = true
        explicitNulls = true
    }

    override fun encode(document: ActiveWorkoutRuntimeDocument): String = json.encodeToString(document)

    override fun parseObject(payload: String): JsonObject? = json.parseToJsonElement(payload) as? JsonObject

    override fun version(document: JsonObject): Int? = document["version"]?.jsonPrimitive?.intOrNull

    override fun decode(payload: String): ActiveWorkoutRuntimeDocument = json.decodeFromString(payload)
}
