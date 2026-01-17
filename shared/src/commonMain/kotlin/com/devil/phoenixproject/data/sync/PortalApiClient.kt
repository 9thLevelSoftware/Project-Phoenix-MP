package com.devil.phoenixproject.data.sync

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class PortalApiClient(
    private val baseUrl: String = DEFAULT_PORTAL_URL,
    private val tokenProvider: () -> String?
) {
    companion object {
        const val DEFAULT_PORTAL_URL = "https://phoenix-portal-backend.railway.app"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
        defaultRequest {
            contentType(ContentType.Application.Json)
        }
    }

    // === Auth Endpoints ===

    suspend fun login(email: String, password: String): Result<PortalAuthResponse> {
        return try {
            val response = httpClient.post("$baseUrl/api/auth/login") {
                setBody(PortalLoginRequest(email, password))
            }
            handleResponse(response)
        } catch (e: Exception) {
            Result.failure(PortalApiException("Login failed: ${e.message}", e))
        }
    }

    suspend fun signup(email: String, password: String, displayName: String): Result<PortalAuthResponse> {
        return try {
            val response = httpClient.post("$baseUrl/api/auth/signup") {
                setBody(mapOf("email" to email, "password" to password, "displayName" to displayName))
            }
            handleResponse(response)
        } catch (e: Exception) {
            Result.failure(PortalApiException("Signup failed: ${e.message}", e))
        }
    }

    suspend fun getMe(): Result<PortalUser> {
        return authenticatedRequest {
            httpClient.get("$baseUrl/api/auth/me") {
                bearerAuth(it)
            }
        }
    }

    // === Sync Endpoints ===

    suspend fun getSyncStatus(): Result<SyncStatusResponse> {
        return authenticatedRequest {
            httpClient.get("$baseUrl/api/sync/status") {
                bearerAuth(it)
            }
        }
    }

    suspend fun pushChanges(request: SyncPushRequest): Result<SyncPushResponse> {
        return authenticatedRequest {
            httpClient.post("$baseUrl/api/sync/push") {
                bearerAuth(it)
                setBody(request)
            }
        }
    }

    suspend fun pullChanges(request: SyncPullRequest): Result<SyncPullResponse> {
        return authenticatedRequest {
            httpClient.post("$baseUrl/api/sync/pull") {
                bearerAuth(it)
                setBody(request)
            }
        }
    }

    // === Private Helpers ===

    private suspend inline fun <reified T> authenticatedRequest(
        block: (token: String) -> HttpResponse
    ): Result<T> {
        val token = tokenProvider() ?: return Result.failure(
            PortalApiException("Not authenticated - no token available")
        )
        return try {
            val response = block(token)
            handleResponse(response)
        } catch (e: Exception) {
            Result.failure(PortalApiException("Request failed: ${e.message}", e))
        }
    }

    private suspend inline fun <reified T> handleResponse(response: HttpResponse): Result<T> {
        return when (response.status) {
            HttpStatusCode.OK, HttpStatusCode.Created -> {
                Result.success(response.body<T>())
            }
            HttpStatusCode.Unauthorized -> {
                Result.failure(PortalApiException("Unauthorized - please log in again", null, 401))
            }
            HttpStatusCode.Forbidden -> {
                Result.failure(PortalApiException("Premium subscription required", null, 403))
            }
            else -> {
                val error = try {
                    response.body<PortalErrorResponse>().error
                } catch (e: Exception) {
                    "Unknown error"
                }
                Result.failure(PortalApiException(error, null, response.status.value))
            }
        }
    }
}

class PortalApiException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null
) : Exception(message, cause)
