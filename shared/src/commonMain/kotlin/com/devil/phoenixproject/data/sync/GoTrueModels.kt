package com.devil.phoenixproject.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// === GoTrue Auth Response (sign-in, sign-up, refresh) ===

@Serializable
data class GoTrueAuthResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("refresh_token") val refreshToken: String,
    val user: GoTrueUser,
)

@Serializable
data class GoTrueUser(
    val id: String,
    val email: String? = null,
    @SerialName("user_metadata") val userMetadata: JsonObject? = null,
) {
    /** Extract display_name from user_metadata if present */
    val displayName: String?
        get() = (userMetadata?.get("display_name") as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
}

// === GoTrue Sign-Up Request ===

@Serializable
data class GoTrueSignUpRequest(val email: String, val password: String, val data: GoTrueUserMetadata? = null)

@Serializable
data class GoTrueUserMetadata(@SerialName("display_name") val displayName: String? = null)

// === GoTrue Password Sign-In Request ===

@Serializable
data class GoTruePasswordRequest(val email: String, val password: String)

// === GoTrue Refresh Token Request ===

@Serializable
data class GoTrueRefreshRequest(@SerialName("refresh_token") val refreshToken: String)

// === GoTrue PKCE Code Exchange Request (OAuth) ===

@Serializable
data class GoTruePkceExchangeRequest(
    @SerialName("auth_code") val authCode: String,
    @SerialName("code_verifier") val codeVerifier: String,
)

// === GoTrue Error Response ===

@Serializable
data class GoTrueErrorResponse(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val msg: String? = null,
    val code: Int? = null,
) {
    val resolvedCode: String
        get() = errorCode ?: error ?: code?.toString() ?: "unknown"
    val resolvedMessage: String
        get() = errorDescription ?: msg ?: "Unknown authentication error"
}

// === GoTrueAuthResponse → PortalAuthResponse mapping ===

fun GoTrueAuthResponse.toPortalAuthResponse(): PortalAuthResponse = PortalAuthResponse(
    token = accessToken,
    user = PortalUser(
        id = user.id,
        email = user.email ?: "",
        displayName = user.displayName,
        isPremium = false,
    ),
)

// === Subscription Check DTO ===

/**
 * Minimal DTO for one row of the subscriptions table (`tier`, `status`).
 * [subscriptionEntitlement] derives both the premium flag and the active tier from these rows.
 */
@Serializable
data class SubscriptionCheckDto(
    val tier: String,
    val status: String,
)
