package com.devil.phoenixproject.data.integration.intervals

import com.devil.phoenixproject.domain.model.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.util.encodeBase64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Isolated Intervals.icu transport (issue #1384 packet P4).
 *
 * Binding contracts (integration-model §6 'Client isolation / retry policy'):
 * - Own Ktor [HttpClient] factory; engine is injectable for MockEngine tests. This client
 *   never extends `PortalApiClient`, never inherits Supabase headers or portal token
 *   refresh, and never copies portal transient-write classification.
 * - HTTPS-only fixed origin `https://intervals.icu`. Any other host is structurally
 *   impossible (URLs are built from the fixed origin and user-controlled id segments are
 *   path-encoded), redirects are disabled (`followRedirects = false`), and any 3xx
 *   response is refused without following it.
 * - No body, HR or athlete-id logging: no Logging plugin is installed and every error
 *   detail carried in results is a fixed phrase or an HTTP status code. Response bodies
 *   are never copied into messages. No telemetry identifiers exist in this client.
 * - Strict DTO serialization only ([IntervalsWireDtos] allowlist). `explicitNulls = false`
 *   so unavailable HR fields are omitted independently; `encodeDefaults = true` so the
 *   frozen `type = "WeightTraining"` default is always on the wire.
 * - Basic auth (username `API_KEY`, password = user key) is built per request and
 *   discarded; the key is never stored on this object.
 * - NO automatic POST or auth retry (no HttpRetry plugin): one logical operation is one
 *   HTTP attempt. Timeout bounds come from [IntervalsTransportPolicy] (connect 10s,
 *   socket 20s, request 30s).
 *
 * Operations are exactly: nonmutating validation (GET athlete), create manual activity
 * (POST), activity readback (GET by id) and the documented activities listing used for
 * reconciliation. Nothing here mutates local state or consent: validation can never
 * enable the export.
 */

/** Fixed, allowlisted origin host for every Intervals.icu request. */
const val INTERVALS_API_HOST: String = "intervals.icu"

/**
 * Read side of the Intervals REST surface, narrow enough to fake in tests. Every
 * implementation must honor the transport rules documented on [IntervalsApiClient].
 */
interface IntervalsApi {
    /**
     * Nonmutating `GET /api/v1/athlete/{id}` resolving the canonical athlete identity.
     * NEVER enables the export or mutates consent.
     */
    suspend fun validateAthlete(athleteId: String, apiKey: String): IntervalsValidationResult

    /**
     * `POST /api/v1/athlete/{id}/activities/manual`. Exactly one HTTP attempt; the
     * frozen classification in [IntervalsCreateOutcome] applies. Never retried here.
     */
    suspend fun createManualActivity(
        athleteId: String,
        apiKey: String,
        activity: IntervalsManualActivity,
    ): IntervalsCreateOutcome

    /** `GET /api/v1/activity/{id}` readback used to confirm a reconciliation match. */
    suspend fun readbackActivity(
        athleteId: String,
        apiKey: String,
        remoteActivityId: String,
    ): IntervalsReadbackResult

    /**
     * One page of the documented activities listing
     * (`GET /api/v1/athlete/{id}/activities?oldest=&newest=&fields=id,external_id&limit=&page=`).
     * Page size is [IntervalsTransportPolicy.RECONCILIATION_PAGE_SIZE]; the caller enforces
     * the per-pass page cap. A full page reports `complete = false` because more pages may
     * exist (reaching the cap is an incomplete search, never evidence of absence).
     */
    suspend fun listActivities(
        athleteId: String,
        apiKey: String,
        oldest: String,
        newest: String,
        page: Int,
    ): IntervalsListResult
}

/** Result of a `GET /api/v1/activity/{id}` readback. Sanitized: no bodies, no ids in failures. */
sealed interface IntervalsReadbackResult {
    data class Found(val summary: IntervalsActivitySummaryDto) : IntervalsReadbackResult

    data object NotFound : IntervalsReadbackResult

    data class AuthRejected(val statusCode: Int) : IntervalsReadbackResult

    data object Failed : IntervalsReadbackResult
}

/** Result of one activities-listing page request. Sanitized: no bodies in failures. */
sealed interface IntervalsListResult {
    data class Page(
        val items: List<IntervalsActivityListItemDto>,
        /** True when this page completes the listing enumeration (page came back partial). */
        val complete: Boolean,
    ) : IntervalsListResult

    data class AuthRejected(val statusCode: Int) : IntervalsListResult

    data object Failed : IntervalsListResult
}

/**
 * Ktor-backed [IntervalsApi]. See the type-level contract above. The [HttpClient] lives
 * for the lifetime of this object (owned scope teardown calls [close]); it is never
 * rebuilt per request.
 */
class IntervalsApiClient(
    httpClientEngine: HttpClientEngine? = null,
    private val nowEpochMs: () -> Long = { currentTimeMillis() },
) : IntervalsApi {

    private val wireJson = Json {
        explicitNulls = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val http: HttpClient = createClient(httpClientEngine)

    private fun createClient(engine: HttpClientEngine?): HttpClient {
        val configure: HttpClientConfig<*>.() -> Unit = {
            // 3xx must surface as responses so they can be refused, not followed.
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = IntervalsTransportPolicy.CONNECT_TIMEOUT_MILLIS
                socketTimeoutMillis = IntervalsTransportPolicy.SOCKET_TIMEOUT_MILLIS
                requestTimeoutMillis = IntervalsTransportPolicy.REQUEST_TIMEOUT_MILLIS
            }
            // Deliberately absent: HttpRedirect, HttpRetry, Logging, auth plugins.
        }
        return if (engine != null) HttpClient(engine, configure) else HttpClient(configure)
    }

    /** Closes the owned engine. Called on owning scope teardown, never per request. */
    fun close() {
        http.close()
    }

    // ---------------------------------------------------------------- validation

    override suspend fun validateAthlete(athleteId: String, apiKey: String): IntervalsValidationResult {
        if (athleteId.isEmpty()) {
            return IntervalsValidationResult.Rejected(0, "empty athlete id rejected locally")
        }
        val response = try {
            http.get {
                intervalsUrl("api", "v1", "athlete", athleteId)
                applyBasicAuth(apiKey)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return IntervalsValidationResult.NetworkFailure(transportDetail(t))
        }
        return when {
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                IntervalsValidationResult.InvalidCredentials(null)

            response.status.value in 300..399 ->
                IntervalsValidationResult.Rejected(response.status.value, "redirect refused")

            response.status.isSuccess() -> {
                val dto = decodeOrNull<IntervalsAthleteDto>(response.bodyAsTextOrNull())
                IntervalsValidationResult.Valid(
                    canonicalAthleteId = dto?.id ?: athleteId,
                    displayName = dto?.name,
                )
            }

            else -> IntervalsValidationResult.Rejected(response.status.value, httpStatusDetail(response))
        }
    }

    // ---------------------------------------------------------------- create

    override suspend fun createManualActivity(
        athleteId: String,
        apiKey: String,
        activity: IntervalsManualActivity,
    ): IntervalsCreateOutcome {
        if (athleteId.isEmpty()) {
            return IntervalsCreateOutcome.ValidationRejected(0, "empty athlete id rejected locally")
        }
        val payload = try {
            wireJson.encodeToString(IntervalsManualActivity.serializer(), activity)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return IntervalsCreateOutcome.ValidationRejected(0, "payload serialization failed locally")
        }
        val response = try {
            http.post {
                intervalsUrl("api", "v1", "athlete", athleteId, "activities", "manual")
                applyBasicAuth(apiKey)
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return classifyPostFailure(t)
        }
        val statusCode = response.status.value
        return when {
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                IntervalsCreateOutcome.AuthRejected(statusCode)

            // Redirect refused. After a possible delivery the remote outcome is unknown,
            // so this is ambiguity, never a safe replay (conservative over duplicates).
            statusCode in 300..399 -> IntervalsCreateOutcome.Ambiguous

            // 429/5xx: provablyNotAccepted stays false unless the body proves rejection.
            // No observed Intervals.icu body shape proves rejection, so this is always the
            // conservative carrier and the dispatcher keeps UNCERTAIN semantics for it.
            response.status == HttpStatusCode.TooManyRequests || statusCode >= 500 ->
                IntervalsCreateOutcome.RateLimitedOrServerFailure(
                    statusCode = statusCode,
                    provablyNotAccepted = false,
                    retryAfterMillis = parseRetryAfterMillis(response.headers[HttpHeaders.RetryAfter]),
                )

            statusCode in 400..499 ->
                IntervalsCreateOutcome.ValidationRejected(statusCode, httpStatusDetail(response))

            response.status.isSuccess() -> {
                val dto = decodeOrNull<IntervalsActivitySummaryDto>(response.bodyAsTextOrNull())
                IntervalsCreateOutcome.Accepted(dto?.id)
            }

            else -> IntervalsCreateOutcome.RateLimitedOrServerFailure(
                statusCode = statusCode,
                provablyNotAccepted = false,
                retryAfterMillis = null,
            )
        }
    }

    // ---------------------------------------------------------------- readback

    override suspend fun readbackActivity(
        athleteId: String,
        apiKey: String,
        remoteActivityId: String,
    ): IntervalsReadbackResult {
        if (athleteId.isEmpty() || remoteActivityId.isEmpty()) {
            return IntervalsReadbackResult.Failed
        }
        val response = try {
            http.get {
                intervalsUrl("api", "v1", "activity", remoteActivityId)
                applyBasicAuth(apiKey)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return IntervalsReadbackResult.Failed
        }
        return when {
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                IntervalsReadbackResult.AuthRejected(response.status.value)

            response.status == HttpStatusCode.NotFound -> IntervalsReadbackResult.NotFound

            response.status.value in 300..399 -> IntervalsReadbackResult.Failed

            response.status.isSuccess() -> {
                val dto = decodeOrNull<IntervalsActivitySummaryDto>(response.bodyAsTextOrNull())
                if (dto != null) IntervalsReadbackResult.Found(dto) else IntervalsReadbackResult.Failed
            }

            else -> IntervalsReadbackResult.Failed
        }
    }

    // ---------------------------------------------------------------- listing

    override suspend fun listActivities(
        athleteId: String,
        apiKey: String,
        oldest: String,
        newest: String,
        page: Int,
    ): IntervalsListResult {
        if (athleteId.isEmpty() || page < 1) return IntervalsListResult.Failed
        val response = try {
            http.get {
                intervalsUrl("api", "v1", "athlete", athleteId, "activities")
                parameter("oldest", oldest)
                parameter("newest", newest)
                parameter("fields", "id,external_id")
                parameter("limit", IntervalsTransportPolicy.RECONCILIATION_PAGE_SIZE)
                parameter("page", page)
                applyBasicAuth(apiKey)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return IntervalsListResult.Failed
        }
        return when {
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                IntervalsListResult.AuthRejected(response.status.value)

            response.status.value in 300..399 -> IntervalsListResult.Failed

            response.status.isSuccess() -> {
                val items = decodeOrNull<List<IntervalsActivityListItemDto>>(response.bodyAsTextOrNull())
                if (items == null) {
                    IntervalsListResult.Failed
                } else {
                    IntervalsListResult.Page(
                        items = items,
                        complete = items.size < IntervalsTransportPolicy.RECONCILIATION_PAGE_SIZE,
                    )
                }
            }

            else -> IntervalsListResult.Failed
        }
    }

    // ---------------------------------------------------------------- internals

    /**
     * Fixed-origin URL construction. Id segments are path-encoded so a hostile literal id
     * cannot escape its segment; the id itself is never rewritten. The result is verified
     * against the allowlisted https origin before anything is sent.
     */
    private fun HttpRequestBuilder.intervalsUrl(vararg segments: String) {
        val path = "/" + segments.joinToString("/") { it.encodeURLPathSegment() }
        url("https://$INTERVALS_API_HOST$path")
        check(url.protocol == URLProtocol.HTTPS && url.host == INTERVALS_API_HOST) {
            "Intervals client refused a non-allowlisted origin"
        }
    }

    /** Request-scoped Basic auth: username `API_KEY`, password = user key. Never stored. */
    private fun HttpRequestBuilder.applyBasicAuth(apiKey: String) {
        val value = "Basic " + ("API_KEY:$apiKey").encodeBase64()
        header(HttpHeaders.Authorization, value)
    }

    private suspend fun HttpResponse.bodyAsTextOrNull(): String? = try {
        bodyAsText()
    } catch (t: Throwable) {
        // A cancelled body read must never degrade into a decoded result: swallowing the
        // cancellation here would let a torn read classify as `Accepted(null)`.
        if (t is CancellationException) throw t
        null
    }

    private inline fun <reified T : Any> decodeOrNull(text: String?): T? =
        text?.let { runCatching { wireJson.decodeFromString<T>(it) }.getOrNull() }

    /** Fixed phrase + status code only. Never a body, never a personal identifier. */
    private fun httpStatusDetail(response: HttpResponse): String = "HTTP ${response.status.value}"

    private fun transportDetail(t: Throwable): String = when {
        isPreDeliveryFailure(t) -> "connection not established"
        else -> "transport failure"
    }

    /**
     * `POST` transport classification per spec 4.4:
     * - failure provably before any request bytes could be delivered (connect phase:
     *   connect timeout/refused, DNS, unresolved address) is `RateLimitedOrServerFailure`
     *   with `provablyNotAccepted = true`, the safe-retry carrier;
     * - timeout / connection loss after possible delivery is [IntervalsCreateOutcome.Ambiguous],
     *   which is never automatically POSTed again.
     *
     * `CancellationException` is never classified here: it is rethrown by every call
     * site so `shutdown()`/structured cancellation stops the drain. The job stays
     * `IN_FLIGHT` under its lease and `expireIntervalsLeases` turns it
     * `UNCERTAIN`/`LEASE_EXPIRED` — ambiguity preserved without running past
     * cancellation.
     */
    private fun classifyPostFailure(t: Throwable): IntervalsCreateOutcome = when {
        isPreDeliveryFailure(t) -> IntervalsCreateOutcome.RateLimitedOrServerFailure(
            statusCode = 0,
            provablyNotAccepted = true,
            retryAfterMillis = null,
        )

        else -> IntervalsCreateOutcome.Ambiguous
    }

    private fun isPreDeliveryFailure(t: Throwable): Boolean {
        var current: Throwable? = t
        repeat(8) {
            val e = current ?: return false
            if (e is ConnectTimeoutException) return true
            if (e::class.simpleName in PRE_DELIVERY_FAILURE_NAMES) return true
            current = e.cause?.takeIf { it !== e }
        }
        return false
    }

    /** `Retry-After` in either RFC 9110 form: delta-seconds or IMF-fixdate. Invalid -> null. */
    internal fun parseRetryAfterMillis(value: String?): Long? {
        val trimmed = value?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        trimmed.toLongOrNull()?.let { seconds ->
            return if (seconds >= 0) seconds * 1_000L else null
        }
        val atMillis = parseHttpDateMillis(trimmed) ?: return null
        return (atMillis - nowEpochMs()).coerceAtLeast(0L)
    }

    private companion object {
        val PRE_DELIVERY_FAILURE_NAMES = setOf(
            "ConnectTimeoutException",
            "ConnectException",
            "UnknownHostException",
            "UnresolvedAddressException",
            "NoRouteToHostException",
        )
    }
}

// ---------------------------------------------------------------------------
// Deterministic date helpers. No locale, no device zone, no date library: wire
// strings must be reproducible across retries (spec 4.3).
// ---------------------------------------------------------------------------

/** Days since 1970-01-01 for a civil date (proleptic Gregorian). */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468L
}

/** Civil date for days since 1970-01-01 (proleptic Gregorian). */
private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
    val shifted = days + 719_468L
    val era = (if (shifted >= 0) shifted else shifted - 146_096L) / 146_097L
    val doe = shifted - era * 146_097L
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    return Triple((if (m <= 2) y + 1 else y).toInt(), m.toInt(), d.toInt())
}

/** UTC `yyyy-MM-dd` for an epoch-millis instant (reconciliation window bounds). */
internal fun intervalsUtcDateString(epochMillis: Long): String {
    val days = floorDiv(epochMillis, 86_400_000L)
    val (year, month, day) = civilFromDays(days)
    return year.toString().padStart(4, '0') + "-" +
        month.toString().padStart(2, '0') + "-" +
        day.toString().padStart(2, '0')
}

private fun floorDiv(value: Long, divisor: Long): Long {
    val quotient = value / divisor
    return if (value % divisor != 0L && (value < 0) != (divisor < 0)) quotient - 1 else quotient
}

/** Parses an IMF-fixdate (`Sun, 08 Nov 2026 14:00:00 GMT`) to epoch millis. Null if malformed. */
internal fun parseHttpDateMillis(text: String): Long? {
    val parts = text.trim().split(Regex("\\s+"))
    if (parts.size != 6) return null
    val (dayPart, monthPart, yearPart, timePart, zonePart) = listOf(parts[1], parts[2], parts[3], parts[4], parts[5])
    if (zonePart != "GMT") return null
    val day = dayPart.toIntOrNull() ?: return null
    val year = yearPart.toIntOrNull() ?: return null
    val month = HTTP_DATE_MONTHS[monthPart] ?: return null
    val time = timePart.split(":")
    if (time.size != 3) return null
    val hour = time[0].toIntOrNull() ?: return null
    val minute = time[1].toIntOrNull() ?: return null
    val second = time[2].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null
    val days = daysFromCivil(year, month, day)
    return days * 86_400_000L + hour * 3_600_000L + minute * 60_000L + second * 1_000L
}

private val HTTP_DATE_MONTHS = mapOf(
    "Jan" to 1, "Feb" to 2, "Mar" to 3, "Apr" to 4, "May" to 5, "Jun" to 6,
    "Jul" to 7, "Aug" to 8, "Sep" to 9, "Oct" to 10, "Nov" to 11, "Dec" to 12,
)

/** Path-segment encoding for literal ids: keeps the literal, blocks segment escape. */
private fun String.encodeURLPathSegment(): String = buildString {
    for (byte in this@encodeURLPathSegment.encodeToByteArray()) {
        val char = byte.toInt().toChar()
        val safe = char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
            char == '-' || char == '_' || char == '.' || char == '~'
        if (safe) {
            append(char)
        } else {
            append('%')
            append(HEX_DIGITS[(byte.toInt() shr 4) and 0xF])
            append(HEX_DIGITS[byte.toInt() and 0xF])
        }
    }
}

private const val HEX_DIGITS = "0123456789ABCDEF"
