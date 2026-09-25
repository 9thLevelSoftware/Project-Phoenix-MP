package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class SqlDelightActiveWorkoutRuntimeRepository internal constructor(
    database: PhoenixDatabase,
    private val nowEpochMs: () -> Long,
    private val codec: ActiveWorkoutRuntimeJsonCodec,
) : ActiveWorkoutRuntimeRepository {
    private val queries = database.phoenixDatabaseQueries

    constructor(
        database: PhoenixDatabase,
        nowEpochMs: () -> Long = ::currentTimeMillis,
    ) : this(database, nowEpochMs, StrictActiveWorkoutRuntimeJsonCodec)

    override suspend fun discover(
        profileId: String,
        routineId: String,
    ): ActiveWorkoutRuntimeDiscoveryResult = withContext(Dispatchers.IO) {
        queries.selectActiveWorkoutRuntimesByProfile(profileId).executeAsList().forEach { row ->
            val loadResult = decode(row.document_version, row.runtime_json, row.updated_at_epoch_ms)
            val matchesSelectedRoutine = when (loadResult) {
                ActiveWorkoutRuntimeLoadResult.Missing -> false

                is ActiveWorkoutRuntimeLoadResult.Loaded ->
                    loadResult.document.profileId == profileId && loadResult.document.routineId == routineId

                is ActiveWorkoutRuntimeLoadResult.Rejected ->
                    loadResult.attribution?.profileId == profileId &&
                        loadResult.attribution.routineId == routineId
            }
            if (matchesSelectedRoutine) {
                return@withContext ActiveWorkoutRuntimeDiscoveryResult.Found(
                    lookupKey = ActiveWorkoutRuntimeLookupKey(
                        profileId = row.profile_id,
                        routineSessionId = row.routine_session_id,
                    ),
                    loadResult = loadResult,
                )
            }
        }
        ActiveWorkoutRuntimeDiscoveryResult.Missing
    }

    override suspend fun load(
        profileId: String,
        routineSessionId: String,
    ): ActiveWorkoutRuntimeLoadResult = withContext(Dispatchers.IO) {
        val row = queries.selectActiveWorkoutRuntime(profileId, routineSessionId).executeAsOneOrNull()
            ?: return@withContext ActiveWorkoutRuntimeLoadResult.Missing
        decode(row.document_version, row.runtime_json, row.updated_at_epoch_ms)
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

    override suspend fun deleteIfRevisionMatches(
        profileId: String,
        routineSessionId: String,
        expectedRevision: ActiveWorkoutRuntimeRowRevision,
    ): Boolean = withContext(Dispatchers.IO) {
        var deleted = false
        queries.transaction {
            val row = queries.selectActiveWorkoutRuntime(profileId, routineSessionId).executeAsOneOrNull()
            if (row != null) {
                val currentRevision = ActiveWorkoutRuntimeRowRevision(
                    documentVersion = row.document_version,
                    updatedAtEpochMs = row.updated_at_epoch_ms,
                    encodedPayloadIdentity = row.runtime_json,
                )
                if (currentRevision == expectedRevision) {
                    queries.deleteActiveWorkoutRuntime(profileId, routineSessionId)
                    deleted = true
                }
            }
        }
        deleted
    }

    private fun decode(
        storedVersion: Long,
        payload: String,
        updatedAtEpochMs: Long,
    ): ActiveWorkoutRuntimeLoadResult {
        val rowRevision = ActiveWorkoutRuntimeRowRevision(
            documentVersion = storedVersion,
            updatedAtEpochMs = updatedAtEpochMs,
            encodedPayloadIdentity = payload,
        )
        val parsed = try {
            codec.parseObject(payload)
                ?: return ActiveWorkoutRuntimeLoadResult.Rejected(
                    ActiveWorkoutRuntimeRejection.CORRUPT_JSON,
                    rowRevision,
                )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return ActiveWorkoutRuntimeLoadResult.Rejected(
                ActiveWorkoutRuntimeRejection.CORRUPT_JSON,
                rowRevision,
            )
        }
        val attribution = parsed.attributionEnvelopeOrNull()

        val payloadVersion = try {
            codec.version(parsed)
                ?: return ActiveWorkoutRuntimeLoadResult.Rejected(
                    ActiveWorkoutRuntimeRejection.CORRUPT_JSON,
                    rowRevision,
                    attribution,
                )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return ActiveWorkoutRuntimeLoadResult.Rejected(
                ActiveWorkoutRuntimeRejection.CORRUPT_JSON,
                rowRevision,
                attribution,
            )
        }

        if (
            storedVersion != ActiveWorkoutRuntimeDocument.CURRENT_VERSION.toLong() ||
            payloadVersion != ActiveWorkoutRuntimeDocument.CURRENT_VERSION
        ) {
            return ActiveWorkoutRuntimeLoadResult.Rejected(
                ActiveWorkoutRuntimeRejection.UNSUPPORTED_VERSION,
                rowRevision,
                attribution,
            )
        }

        return try {
            ActiveWorkoutRuntimeLoadResult.Loaded(codec.decode(payload), rowRevision)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ActiveWorkoutRuntimeLoadResult.Rejected(
                ActiveWorkoutRuntimeRejection.CORRUPT_JSON,
                rowRevision,
                attribution,
            )
        }
    }

    private fun JsonObject.attributionEnvelopeOrNull(): ActiveWorkoutRuntimeAttributionEnvelope? {
        val profileId = requiredNonBlankString("profileId") ?: return null
        val routineId = requiredNonBlankString("routineId") ?: return null
        return ActiveWorkoutRuntimeAttributionEnvelope(
            profileId = profileId,
            routineId = routineId,
            routineSessionId = optionalNonBlankString("routineSessionId"),
            routineExerciseId = optionalNonBlankString("routineExerciseId"),
            sourceExerciseIndex = optionalNonNegativeInt("sourceExerciseIndex"),
            sourceSetIndex = optionalNonNegativeInt("sourceSetIndex"),
        )
    }

    private fun JsonObject.requiredNonBlankString(name: String): String? = (get(name) as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content
        ?.takeIf(String::isNotBlank)

    private fun JsonObject.optionalNonBlankString(name: String): String? = requiredNonBlankString(name)

    private fun JsonObject.optionalNonNegativeInt(name: String): Int? = (get(name) as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.intOrNull
        ?.takeIf { it >= 0 }
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
