package com.devil.phoenixproject.data.sync

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Fixed generation disclaimer, server constant (architecture.md "API contract").
 * It is the ONLY text the mapper writes into a generated routine's description:
 * the raw prompt is health-adjacent and must never sync as routine text.
 */
const val AI_ROUTINE_DRAFT_DISCLAIMER =
    "This is a training draft, not medical advice. Review and edit before saving."

// === POST /functions/v1/generate-routine wire models ===
// Kept in a dedicated file (not on the sync DTOs): generation is a one-shot
// function call, not part of the mobile-sync contract, and none of these
// responses are persisted.

/** `{ "exerciseId": string, "estimated1RmKg": number }` — opt-in aggregate load context. */
@Serializable
data class GenerateRoutineLoadContextItem(
    val exerciseId: String,
    val estimated1RmKg: Float,
)

/**
 * Request body (architecture.md "API contract"). `kind` is always `"routine"` in
 * v1; `"program"` stays a server-side 400 `program_generation_not_enabled`.
 */
@Serializable
data class GenerateRoutineRequest(
    val prompt: String,
    val kind: String = "routine",
    val targetMinutes: Int? = null,
    val includeLoadContext: Boolean = false,
    val loadContext: List<GenerateRoutineLoadContextItem> = emptyList(),
)

/**
 * Accepts the `supersetGroup` wire value as a JSON string OR a JSON number and
 * normalizes both to a nullable string key. The field is an opaque grouping key:
 * null means standalone, groups of one are flattened (server-side and again in
 * [com.devil.phoenixproject.domain.usecase.GeneratedDraftMapper]).
 */
internal object SupersetGroupSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("SupersetGroup", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val input = decoder as? JsonDecoder ?: return decoder.decodeString().takeIf { it.isNotBlank() }
        return when (val element = input.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value)
        }
    }
}

/**
 * One generated exercise row (BE-A `validateGeneratedDraft` contract).
 *
 * Per-exercise `name` is nullable with a default: the architecture wire example
 * omits it, the validator contract includes it. The mapper re-resolves every id
 * through [com.devil.phoenixproject.data.repository.ExerciseRepository] and never
 * trusts this name as an identity.
 */
@Serializable
data class GeneratedRoutineExercise(
    val exerciseId: String,
    val name: String? = null,
    val sets: Int,
    val reps: Int,
    val restSeconds: Int,
    val percentOfOneRm: Int,
    val mode: String,
    val echoLevel: String? = null,
    val eccentricLoad: String? = null,
    @Serializable(with = SupersetGroupSerializer::class)
    val supersetGroup: String? = null,
)

/** The `draft` object of a 200 response. Never persisted; mapped to an unsaved Routine. */
@Serializable
data class GeneratedRoutineDraft(
    val name: String,
    val targetMinutes: Int? = null,
    val avoidedMuscles: List<String> = emptyList(),
    val unmetConstraints: List<String> = emptyList(),
    val exercises: List<GeneratedRoutineExercise> = emptyList(),
)

/** 200 body: `{ kind, remainingToday, disclaimer, draft }`. */
@Serializable
data class GenerateRoutineResponse(
    val kind: String = "routine",
    val remainingToday: Int = 0,
    val disclaimer: String = AI_ROUTINE_DRAFT_DISCLAIMER,
    val draft: GeneratedRoutineDraft,
)

/**
 * Error body shared by the generate-routine failure statuses:
 * `{ error, message, requiredTier, currentTier, retryAfterSeconds }`.
 * All fields optional so a proxy/CDN body still decodes.
 */
@Serializable
data class GenerateRoutineErrorBody(
    val error: String? = null,
    val message: String? = null,
    val requiredTier: String? = null,
    val currentTier: String? = null,
    val retryAfterSeconds: Int? = null,
)

/**
 * User-facing copy bucket for generate-routine failures (§3 BE-C taxonomy).
 * The screen owns the localized strings; raw provider/server error text is
 * never shown.
 */
enum class GenerateRoutineErrorKind {
    /** 402 `subscription_required` — "upgrade to Flame" copy. */
    NOT_SUBSCRIBED,

    /** 403 `feature_disabled` and 404 (function not deployed) — "not available right now" copy. */
    NOT_AVAILABLE,

    /** 422 `generation_invalid` — "could not build a valid workout" copy. */
    INVALID_DRAFT,

    /** 429 `rate_limit_exceeded` — "limit reached" copy with retryAfterSeconds timing. */
    RATE_LIMITED,

    /** 503 `subscription_unavailable` / `rate_limit_unavailable` / `model_unavailable` — "temporarily unavailable" copy. */
    TEMPORARILY_UNAVAILABLE,

    /** 401 — bad or missing user JWT; sign-in copy. */
    UNAUTHORIZED,

    /** 400 `invalid_request` / `program_generation_not_enabled` — retryable prompt fixup copy. */
    INVALID_REQUEST,
}

/** Typed failure of [PortalApiClient.generateRoutine]; carries the copy bucket, never raw provider text. */
class GenerateRoutineException(
    val kind: GenerateRoutineErrorKind,
    message: String,
    val serverError: String? = null,
    val statusCode: Int? = null,
    val retryAfterSeconds: Int? = null,
    val requiredTier: String? = null,
    val currentTier: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Maps one generate-routine failure response to its copy bucket.
 *
 * The structured body `{ error, message, requiredTier, currentTier, retryAfterSeconds }`
 * is parsed BEFORE any fall back to [classifyByStatusCode]: the shared classifier
 * collapses 402/403 into one permanent bucket, and the phone needs distinct copy
 * ("upgrade to Flame" vs "not available right now"). [classifyByStatusCode] only
 * decides retryability for statuses outside the generate-routine taxonomy.
 */
internal fun generateRoutineFailureFor(
    statusCode: Int,
    errorBody: GenerateRoutineErrorBody?,
    retryAfterHeaderSeconds: Int? = null,
): GenerateRoutineException {
    val serverError = errorBody?.error?.takeIf { it.isNotBlank() }
    val message = errorBody?.message?.takeIf { it.isNotBlank() }
    val retryAfterSeconds = errorBody?.retryAfterSeconds ?: retryAfterHeaderSeconds
    val kind = when (statusCode) {
        400 -> GenerateRoutineErrorKind.INVALID_REQUEST
        401 -> GenerateRoutineErrorKind.UNAUTHORIZED
        402 -> GenerateRoutineErrorKind.NOT_SUBSCRIBED
        // A 404 here is the function-not-deployed case; it gets the same copy as
        // feature_disabled (integration-model.md §6).
        403, 404 -> GenerateRoutineErrorKind.NOT_AVAILABLE
        422 -> GenerateRoutineErrorKind.INVALID_DRAFT
        429 -> GenerateRoutineErrorKind.RATE_LIMITED
        503 -> GenerateRoutineErrorKind.TEMPORARILY_UNAVAILABLE
        else -> {
            val classified = classifyByStatusCode(
                statusCode,
                message ?: serverError ?: "Generate routine failed",
            )
            if (classified.isRetryable) {
                GenerateRoutineErrorKind.TEMPORARILY_UNAVAILABLE
            } else {
                GenerateRoutineErrorKind.NOT_AVAILABLE
            }
        }
    }
    return GenerateRoutineException(
        kind = kind,
        message = message ?: serverError ?: "Generate routine failed with HTTP $statusCode",
        serverError = serverError,
        statusCode = statusCode,
        retryAfterSeconds = retryAfterSeconds,
        requiredTier = errorBody?.requiredTier,
        currentTier = errorBody?.currentTier,
    )
}

/**
 * Parses one `generate-routine` HTTP response. Nothing here is persisted: the
 * caller maps the draft into an unsaved in-memory Routine and the user's Save is
 * the first write.
 */
internal suspend fun parseGenerateRoutineHttpResponse(
    response: HttpResponse,
): Result<GenerateRoutineResponse> {
    val statusCode = response.status.value
    if (response.status.isSuccess()) {
        return try {
            Result.success(response.body<GenerateRoutineResponse>())
        } catch (e: Exception) {
            Result.failure(
                GenerateRoutineException(
                    kind = GenerateRoutineErrorKind.INVALID_DRAFT,
                    message = "Generate routine response was not a usable draft",
                    statusCode = statusCode,
                    cause = e,
                ),
            )
        }
    }
    val errorBody = try {
        response.body<GenerateRoutineErrorBody>()
    } catch (_: Exception) {
        // Unparseable body (e.g. an HTML 404 page from a proxy): no structured fields.
        null
    }
    val retryAfterHeaderSeconds = response.headers["Retry-After"]?.trim()?.toIntOrNull()
    return Result.failure(generateRoutineFailureFor(statusCode, errorBody, retryAfterHeaderSeconds))
}
