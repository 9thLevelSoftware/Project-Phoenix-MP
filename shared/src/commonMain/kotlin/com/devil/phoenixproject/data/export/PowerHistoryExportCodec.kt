package com.devil.phoenixproject.data.export

import com.devil.phoenixproject.domain.powerloss.PowerCable
import com.devil.phoenixproject.domain.powerloss.PowerLossAttemptRecord
import com.devil.phoenixproject.domain.powerloss.PowerLossSetEvidence
import com.devil.phoenixproject.domain.powerloss.PowerStopReason
import com.devil.phoenixproject.domain.powerloss.SampleQualityMarkers
import com.devil.phoenixproject.domain.powerloss.TargetMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Issue #1392 (packet P4) — power-history export v1 codec.
 *
 * Versioned, separately identified document (`documentType` + `version: 1`) over the
 * frozen evidence contract in `domain/powerloss/PowerLossEvidence.kt`:
 *
 *  - stable session/set IDs and per-attempt `(session, workingConcentric ordinal)` identity
 *    including pending/attempt-only rows (`completed: false`),
 *  - frozen target configuration + `metricVersion`,
 *  - reference-at-decision / trigger / winner facts and BOTH qualification facts (SD2),
 *  - explicit units (watts / millimeters / milliseconds / percent) and explicit
 *    null-vs-zero semantics: MISSING data serializes as JSON `null` (or an empty
 *    array where a list is absent) and is never fabricated as 0. A serialized `0`
 *    is a measured zero.
 *
 * [decode] is the import side of the roundtrip contract (P4 acceptance:
 * "power-history export roundtrips preserving null/zero/identity").
 */
internal object PowerHistoryExportCodec {

    internal const val DOCUMENT_TYPE: String = "power-history-export"
    internal const val VERSION: Int = 1

    private val json = Json { prettyPrint = true }

    /** One session's exported evidence (sets in export order). */
    internal data class SessionExport(
        val sessionId: String,
        val sets: List<PowerLossSetEvidence>,
    )

    /** Decoded power-history export v1 document. */
    internal data class Document(
        val version: Int,
        val profileId: String,
        val exportedAtMs: Long,
        val sessions: List<SessionExport>,
    )

    internal fun encode(profileId: String, exportedAtMs: Long, sessions: List<SessionExport>): String =
        json.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                put("documentType", JsonPrimitive(DOCUMENT_TYPE))
                put("version", JsonPrimitive(VERSION))
                put("units", buildJsonObject {
                    put("power", JsonPrimitive("watts"))
                    put("rom", JsonPrimitive("millimeters"))
                    put("duration", JsonPrimitive("milliseconds"))
                    put("lossPercent", JsonPrimitive("percent"))
                    put("timestamp", JsonPrimitive("epochMilliseconds"))
                })
                put(
                    "nullSemantics",
                    JsonPrimitive(
                        "Missing data is serialized as null (or an empty array) and never as 0; " +
                            "a serialized 0 is a measured zero.",
                    ),
                )
                put("profileId", JsonPrimitive(profileId))
                put("exportedAtMs", JsonPrimitive(exportedAtMs))
                put("sessions", buildJsonArray {
                    for (session in sessions) add(encodeSession(session))
                })
            },
        )

    internal fun decode(content: String): Document {
        val root = json.parseToJsonElement(content).jsonObject
        require(root.string("documentType") == DOCUMENT_TYPE) { "not a power-history export document" }
        val version = root.int("version")
        require(version == VERSION) { "unsupported power-history export version $version" }
        return Document(
            version = version,
            profileId = root.string("profileId"),
            exportedAtMs = root.long("exportedAtMs"),
            sessions = root.array("sessions").map { decodeSession(it.jsonObject) },
        )
    }

    private fun encodeSession(session: SessionExport): JsonElement = buildJsonObject {
        put("sessionId", JsonPrimitive(session.sessionId))
        put("sets", buildJsonArray { for (set in session.sets) add(encodeSet(set)) })
    }

    private fun decodeSession(obj: JsonObject): SessionExport = SessionExport(
        sessionId = obj.string("sessionId"),
        sets = obj.array("sets").map { decodeSet(it.jsonObject) },
    )

    private fun encodeSet(set: PowerLossSetEvidence): JsonElement = buildJsonObject {
        put("sessionId", JsonPrimitive(set.sessionId))
        put("setId", JsonPrimitive(set.setId))
        put("metricVersion", JsonPrimitive(set.metricVersion))
        put("targetConfig", buildJsonObject {
            put("targetMode", JsonPrimitive(set.targetMode.name))
            put("thresholdPercent", JsonPrimitive(set.thresholdPercent))
            put("maxWorkingReps", intOrNull(set.maxWorkingReps))
            put("requireTwoConsecutive", JsonPrimitive(set.requireTwoConsecutive))
            put("savedNominalReps", intOrNull(set.savedNominalReps))
            put("activeCables", buildJsonArray {
                for (cable in set.activeCables.sortedBy { it.name }) add(JsonPrimitive(cable.name))
            })
            put("activeCableProvenance", JsonPrimitive(set.activeCableProvenance))
        })
        put("reference", buildJsonObject {
            put("workingOrdinal", intOrNull(set.referenceWorkingOrdinal))
            put("mcpWatts", floatOrNull(set.referenceMcpWatts))
            put("romAMm", floatOrNull(set.referenceRomAMm))
            put("romBMm", floatOrNull(set.referenceRomBMm))
        })
        put("trigger", buildJsonObject {
            put("workingOrdinal", intOrNull(set.triggerWorkingOrdinal))
        })
        put("winner", buildJsonObject {
            put("reason", set.winnerReason?.let { JsonPrimitive(it.name) } ?: JsonNull)
        })
        put("qualification", buildJsonObject {
            put("shortRomObserved", JsonPrimitive(set.shortRomObserved))
            put("powerLossObserved", JsonPrimitive(set.powerLossObserved))
            put("lossPercentAtDecision", floatOrNull(set.lossPercentAtDecision))
        })
        put("attempts", buildJsonArray { for (attempt in set.attempts) add(encodeAttempt(attempt)) })
    }

    private fun decodeSet(obj: JsonObject): PowerLossSetEvidence {
        val target = obj.obj("targetConfig")
        val reference = obj.obj("reference")
        val trigger = obj.obj("trigger")
        val winner = obj.obj("winner")
        val qualification = obj.obj("qualification")
        return PowerLossSetEvidence(
            sessionId = obj.string("sessionId"),
            setId = obj.string("setId"),
            metricVersion = obj.int("metricVersion"),
            targetMode = TargetMode.fromPersisted(target.string("targetMode"))
                ?: error("unknown target mode '${target.string("targetMode")}'"),
            thresholdPercent = target.int("thresholdPercent"),
            maxWorkingReps = target.intOrNull("maxWorkingReps"),
            requireTwoConsecutive = target.bool("requireTwoConsecutive"),
            savedNominalReps = target.intOrNull("savedNominalReps"),
            activeCables = target.array("activeCables").map { element ->
                PowerCable.entries.firstOrNull { it.name == element.jsonPrimitive.content }
                    ?: error("unknown power cable '${element.jsonPrimitive.content}'")
            }.toSet(),
            activeCableProvenance = target.string("activeCableProvenance"),
            referenceWorkingOrdinal = reference.intOrNull("workingOrdinal"),
            referenceMcpWatts = reference.floatOrNull("mcpWatts"),
            referenceRomAMm = reference.floatOrNull("romAMm"),
            referenceRomBMm = reference.floatOrNull("romBMm"),
            triggerWorkingOrdinal = trigger.intOrNull("workingOrdinal"),
            winnerReason = winner.stringOrNull("reason")?.let { reason ->
                PowerStopReason.entries.firstOrNull { it.name == reason }
                    ?: error("unknown winner reason '$reason'")
            },
            shortRomObserved = qualification.bool("shortRomObserved"),
            powerLossObserved = qualification.bool("powerLossObserved"),
            lossPercentAtDecision = qualification.floatOrNull("lossPercentAtDecision"),
            attempts = obj.array("attempts").map { decodeAttempt(it.jsonObject) },
        )
    }

    private fun encodeAttempt(attempt: PowerLossAttemptRecord): JsonElement = buildJsonObject {
        put("workingConcentricOrdinal", JsonPrimitive(attempt.workingConcentricOrdinal))
        put("completed", JsonPrimitive(attempt.completed))
        put("legacyRepIndex", intOrNull(attempt.legacyRepIndex))
        put("mcpWatts", floatOrNull(attempt.mcpWatts))
        put("eccentricPowerWatts", floatOrNull(attempt.eccentricPowerWatts))
        put("concentricDurationMs", longOrNull(attempt.concentricDurationMs))
        put("eccentricDurationMs", longOrNull(attempt.eccentricDurationMs))
        put("lossPercentAtDecision", floatOrNull(attempt.lossPercentAtDecision))
        put("referenceWorkingOrdinal", intOrNull(attempt.referenceWorkingOrdinal))
        put("romAMm", floatOrNull(attempt.romAMm))
        put("romBMm", floatOrNull(attempt.romBMm))
        put("romAPercentOfReference", floatOrNull(attempt.romAPercentOfReference))
        put("romBPercentOfReference", floatOrNull(attempt.romBPercentOfReference))
        put("romTrustworthy", JsonPrimitive(attempt.romTrustworthy))
        put("quality", buildJsonObject {
            put("clamped", JsonPrimitive(attempt.quality.clamped))
            put("rejected", JsonPrimitive(attempt.quality.rejected))
            put("stale", JsonPrimitive(attempt.quality.stale))
            put("gapBefore", JsonPrimitive(attempt.quality.gapBefore))
        })
        put("shortRom", JsonPrimitive(attempt.shortRom))
        put("powerLossQualified", JsonPrimitive(attempt.powerLossQualified))
    }

    private fun decodeAttempt(obj: JsonObject): PowerLossAttemptRecord {
        val quality = obj.obj("quality")
        return PowerLossAttemptRecord(
            workingConcentricOrdinal = obj.int("workingConcentricOrdinal"),
            completed = obj.bool("completed"),
            legacyRepIndex = obj.intOrNull("legacyRepIndex"),
            mcpWatts = obj.floatOrNull("mcpWatts"),
            eccentricPowerWatts = obj.floatOrNull("eccentricPowerWatts"),
            concentricDurationMs = obj.longOrNull("concentricDurationMs"),
            eccentricDurationMs = obj.longOrNull("eccentricDurationMs"),
            lossPercentAtDecision = obj.floatOrNull("lossPercentAtDecision"),
            referenceWorkingOrdinal = obj.intOrNull("referenceWorkingOrdinal"),
            romAMm = obj.floatOrNull("romAMm"),
            romBMm = obj.floatOrNull("romBMm"),
            romAPercentOfReference = obj.floatOrNull("romAPercentOfReference"),
            romBPercentOfReference = obj.floatOrNull("romBPercentOfReference"),
            romTrustworthy = obj.bool("romTrustworthy"),
            quality = SampleQualityMarkers(
                clamped = quality.bool("clamped"),
                rejected = quality.bool("rejected"),
                stale = quality.bool("stale"),
                gapBefore = quality.bool("gapBefore"),
            ),
            shortRom = obj.bool("shortRom"),
            powerLossQualified = obj.bool("powerLossQualified"),
        )
    }

    private fun intOrNull(value: Int?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull
    private fun longOrNull(value: Long?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull

    /** Float widens to a JSON double; the Double→Float narrowing on decode is exact. */
    private fun floatOrNull(value: Float?): JsonElement = value?.let { JsonPrimitive(it.toDouble()) } ?: JsonNull

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
    private fun JsonObject.array(key: String): List<JsonElement> = (getValue(key) as JsonArray).toList()

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.stringOrNull(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.int(key: String): Int = string(key).toInt()
    private fun JsonObject.intOrNull(key: String): Int? = stringOrNull(key)?.toInt()
    private fun JsonObject.long(key: String): Long = string(key).toLong()
    private fun JsonObject.longOrNull(key: String): Long? = stringOrNull(key)?.toLong()
    private fun JsonObject.floatOrNull(key: String): Float? = stringOrNull(key)?.toDouble()?.toFloat()
    private fun JsonObject.bool(key: String): Boolean = string(key).toBooleanStrict()
}
