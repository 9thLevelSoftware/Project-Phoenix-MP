package com.devil.phoenixproject.data.integration.intervals

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Restricted wire DTOs for the Intervals.icu REST API (issue #1384).
 *
 * Allowlist only (integration-model §5): `type`, `external_id`, `name`, `description`,
 * `start_date`, `start_date_local`, `elapsed_time`, optional `average_heartrate` /
 * `max_heartrate`. `moving_time`, `kg_lifted`, work fields, streams and custom fields are
 * deliberately absent. No secret-bearing model is ever serialized here.
 *
 * The HTTP client must serialize with `explicitNulls = false` so unavailable HR fields are
 * omitted independently, never sent as null or zero.
 */

/**
 * Body for `POST /api/v1/athlete/{id}/activities/manual`.
 *
 * `external_id` is the literal routineSessionId (standalone session id otherwise) — never
 * namespaced, trimmed, re-cased or regenerated. `start_date` is UTC ISO8601; `start_date_local`
 * is offset-free local ISO8601 captured at admission. `elapsed_time` is a positive int32
 * seconds count (floor of captured duration; values outside 1..2147483647 are rejected before
 * serialization). `type` is always "WeightTraining".
 */
@Serializable
data class IntervalsManualActivity(
    @SerialName("type") val type: String = "WeightTraining",
    @SerialName("external_id") val externalId: String,
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("start_date_local") val startDateLocal: String,
    @SerialName("elapsed_time") val elapsedTimeSeconds: Int,
    @SerialName("average_heartrate") val averageHeartrate: Int? = null,
    @SerialName("max_heartrate") val maxHeartrate: Int? = null,
)

/**
 * Minimal athlete projection of `GET /api/v1/athlete/{id}` used only to resolve the
 * canonical athlete identity during nonmutating validation. Unknown fields are ignored;
 * no token or account data is retained.
 */
@Serializable
data class IntervalsAthleteDto(
    val id: String? = null,
    val name: String? = null,
)

/**
 * Projection of the created/returned activity from `POST .../activities/manual` and of
 * `GET /api/v1/activity/{id}` readback. Only identity fields matter locally.
 */
@Serializable
data class IntervalsActivitySummaryDto(
    val id: String? = null,
    @SerialName("external_id") val externalId: String? = null,
    @SerialName("externalId") val externalIdAlt: String? = null,
    val name: String? = null,
) {
    /** The API has been observed using both `external_id` and `externalId` shapes. */
    val resolvedExternalId: String?
        get() = externalId ?: externalIdAlt
}

/**
 * Item shape of the documented activities listing used for reconciliation
 * (`oldest`, `newest`, `fields` query parameters). Reaching the page cap is an
 * incomplete search, never evidence of absence.
 */
@Serializable
data class IntervalsActivityListItemDto(
    val id: String? = null,
    @SerialName("external_id") val externalId: String? = null,
    @SerialName("externalId") val externalIdAlt: String? = null,
) {
    val resolvedExternalId: String?
        get() = externalId ?: externalIdAlt
}
