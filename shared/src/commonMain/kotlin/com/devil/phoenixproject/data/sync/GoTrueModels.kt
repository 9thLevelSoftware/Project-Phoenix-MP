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
    val user: GoTrueUser
)

@Serializable
data class GoTrueUser(
    val id: String,
    val aud: String = "authenticated",
    val role: String = "authenticated",
    val email: String? = null,
    val phone: String? = null,
    @SerialName("email_confirmed_at") val emailConfirmedAt: String? = null,
    @SerialName("confirmed_at") val confirmedAt: String? = null,
    @SerialName("last_sign_in_at") val lastSignInAt: String? = null,
    @SerialName("app_metadata") val appMetadata: JsonObject? = null,
    @SerialName("user_metadata") val userMetadata: JsonObject? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("is_anonymous") val isAnonymous: Boolean = false
) {
    /** Extract display_name from user_metadata if present */
    val displayName: String?
        get() = userMetadata?.get("display_name")?.toString()?.trim('"')
}

// === GoTrue Sign-Up Request ===

@Serializable
data class GoTrueSignUpRequest(
    val email: String,
    val password: String,
    val data: GoTrueUserMetadata? = null
)

@Serializable
data class GoTrueUserMetadata(
    @SerialName("display_name") val displayName: String? = null
)

// === GoTrue Password Sign-In Request ===

@Serializable
data class GoTruePasswordRequest(
    val email: String,
    val password: String
)

// === GoTrue Refresh Token Request ===

@Serializable
data class GoTrueRefreshRequest(
    @SerialName("refresh_token") val refreshToken: String
)

// === GoTrue Error Response ===

@Serializable
data class GoTrueErrorResponse(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val msg: String? = null,
    val code: Int? = null
) {
    val resolvedCode: String
        get() = errorCode ?: error ?: code?.toString() ?: "unknown"
    val resolvedMessage: String
        get() = errorDescription ?: msg ?: "Unknown authentication error"
}

// === GoTrueAuthResponse → PortalAuthResponse mapping ===

fun GoTrueAuthResponse.toPortalAuthResponse(): PortalAuthResponse {
    return PortalAuthResponse(
        token = accessToken,
        user = PortalUser(
            id = user.id,
            email = user.email ?: "",
            displayName = user.displayName,
            isPremium = false
        )
    )
}
