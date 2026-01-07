# Sync Phase 2: Mobile Sync Foundation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Create the mobile-side infrastructure for syncing with the Phoenix Portal, including API client, sync manager, and account linking UI.

**Architecture:** Ktor-based PortalApiClient for HTTP communication, SyncManager to orchestrate sync operations with offline queue, SecureTokenStorage for JWT persistence, and Compose UI for account linking. Follows existing KMP patterns.

**Tech Stack:** Kotlin Multiplatform, Ktor Client, Kotlinx Serialization, multiplatform-settings, Koin DI, Jetpack Compose

**Reference:** Design doc at `docs/plans/2026-01-04-mobile-portal-sync-design.md`

---

## Task 1: Create Sync DTOs (Mobile Side)

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncModels.kt`

**Step 1: Create the sync models file**

Create DTOs matching the backend models:

```kotlin
package com.devil.phoenixproject.data.sync

import kotlinx.serialization.Serializable

// === Push Request/Response ===

@Serializable
data class SyncPushRequest(
    val deviceId: String,
    val deviceName: String? = null,
    val platform: String,
    val lastSync: Long,
    val sessions: List<WorkoutSessionSyncDto> = emptyList(),
    val records: List<PersonalRecordSyncDto> = emptyList(),
    val routines: List<RoutineSyncDto> = emptyList(),
    val exercises: List<CustomExerciseSyncDto> = emptyList(),
    val badges: List<EarnedBadgeSyncDto> = emptyList(),
    val gamificationStats: GamificationStatsSyncDto? = null
)

@Serializable
data class SyncPushResponse(
    val syncTime: Long,
    val idMappings: IdMappings
)

@Serializable
data class IdMappings(
    val sessions: Map<String, String> = emptyMap(),
    val records: Map<String, String> = emptyMap(),
    val routines: Map<String, String> = emptyMap(),
    val exercises: Map<String, String> = emptyMap(),
    val badges: Map<String, String> = emptyMap()
)

// === Pull Request/Response ===

@Serializable
data class SyncPullRequest(
    val deviceId: String,
    val lastSync: Long
)

@Serializable
data class SyncPullResponse(
    val syncTime: Long,
    val sessions: List<WorkoutSessionSyncDto> = emptyList(),
    val records: List<PersonalRecordSyncDto> = emptyList(),
    val routines: List<RoutineSyncDto> = emptyList(),
    val exercises: List<CustomExerciseSyncDto> = emptyList(),
    val badges: List<EarnedBadgeSyncDto> = emptyList(),
    val gamificationStats: GamificationStatsSyncDto? = null
)

// === Status Response ===

@Serializable
data class SyncStatusResponse(
    val lastSync: Long?,
    val pendingChanges: Int,
    val subscriptionStatus: String,
    val subscriptionExpiresAt: String?
)

// === Auth DTOs ===

@Serializable
data class PortalLoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class PortalAuthResponse(
    val token: String,
    val user: PortalUser
)

@Serializable
data class PortalUser(
    val id: String,
    val email: String,
    val displayName: String?,
    val isPremium: Boolean
)

// === Entity DTOs ===

@Serializable
data class WorkoutSessionSyncDto(
    val clientId: String,
    val serverId: String? = null,
    val timestamp: Long,
    val mode: String,
    val targetReps: Int,
    val weightPerCableKg: Float,
    val duration: Int = 0,
    val totalReps: Int = 0,
    val exerciseId: String? = null,
    val exerciseName: String? = null,
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class PersonalRecordSyncDto(
    val clientId: String,
    val serverId: String? = null,
    val exerciseId: String,
    val exerciseName: String,
    val weight: Float,
    val reps: Int,
    val oneRepMax: Float,
    val achievedAt: Long,
    val workoutMode: String,
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class RoutineSyncDto(
    val clientId: String,
    val serverId: String? = null,
    val name: String,
    val description: String = "",
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class CustomExerciseSyncDto(
    val clientId: String,
    val serverId: String? = null,
    val name: String,
    val muscleGroup: String,
    val equipment: String,
    val defaultCableConfig: String,
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class EarnedBadgeSyncDto(
    val clientId: String,
    val serverId: String? = null,
    val badgeId: String,
    val earnedAt: Long,
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class GamificationStatsSyncDto(
    val clientId: String,
    val totalWorkouts: Int = 0,
    val totalReps: Int = 0,
    val totalVolumeKg: Int = 0,
    val longestStreak: Int = 0,
    val currentStreak: Int = 0,
    val updatedAt: Long
)

// === Error Response ===

@Serializable
data class PortalErrorResponse(
    val error: String
)
```

**Step 2: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncModels.kt
git commit -m "feat(sync): add mobile-side sync DTOs"
```

---

## Task 2: Create PortalApiClient

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt`

**Step 1: Create the Portal API client**

```kotlin
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
        // TODO: Update to production URL when deployed
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
```

**Step 2: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt
git commit -m "feat(sync): add PortalApiClient for backend communication"
```

---

## Task 3: Create Secure Token Storage

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalTokenStorage.kt`

**Step 1: Create token storage using multiplatform-settings**

```kotlin
package com.devil.phoenixproject.data.sync

import com.russhwolf.settings.Settings
import com.russhwolf.settings.get
import com.russhwolf.settings.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PortalTokenStorage(private val settings: Settings) {

    companion object {
        private const val KEY_TOKEN = "portal_auth_token"
        private const val KEY_USER_ID = "portal_user_id"
        private const val KEY_USER_EMAIL = "portal_user_email"
        private const val KEY_USER_NAME = "portal_user_display_name"
        private const val KEY_IS_PREMIUM = "portal_user_is_premium"
        private const val KEY_LAST_SYNC = "portal_last_sync_timestamp"
        private const val KEY_DEVICE_ID = "portal_device_id"
    }

    private val _isAuthenticated = MutableStateFlow(hasToken())
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _currentUser = MutableStateFlow(loadUser())
    val currentUser: StateFlow<PortalUser?> = _currentUser.asStateFlow()

    fun saveAuth(response: PortalAuthResponse) {
        settings[KEY_TOKEN] = response.token
        settings[KEY_USER_ID] = response.user.id
        settings[KEY_USER_EMAIL] = response.user.email
        settings[KEY_USER_NAME] = response.user.displayName
        settings[KEY_IS_PREMIUM] = response.user.isPremium

        _isAuthenticated.value = true
        _currentUser.value = response.user
    }

    fun getToken(): String? = settings[KEY_TOKEN]

    fun hasToken(): Boolean = settings.getStringOrNull(KEY_TOKEN) != null

    fun getDeviceId(): String {
        val existing: String? = settings[KEY_DEVICE_ID]
        if (existing != null) return existing

        val newId = generateDeviceId()
        settings[KEY_DEVICE_ID] = newId
        return newId
    }

    fun getLastSyncTimestamp(): Long = settings[KEY_LAST_SYNC, 0L]

    fun setLastSyncTimestamp(timestamp: Long) {
        settings[KEY_LAST_SYNC] = timestamp
    }

    fun updatePremiumStatus(isPremium: Boolean) {
        settings[KEY_IS_PREMIUM] = isPremium
        _currentUser.value = _currentUser.value?.copy(isPremium = isPremium)
    }

    fun clearAuth() {
        settings.remove(KEY_TOKEN)
        settings.remove(KEY_USER_ID)
        settings.remove(KEY_USER_EMAIL)
        settings.remove(KEY_USER_NAME)
        settings.remove(KEY_IS_PREMIUM)
        // Keep device ID and last sync for re-auth

        _isAuthenticated.value = false
        _currentUser.value = null
    }

    private fun loadUser(): PortalUser? {
        val id: String = settings[KEY_USER_ID] ?: return null
        val email: String = settings[KEY_USER_EMAIL] ?: return null
        val displayName: String? = settings[KEY_USER_NAME]
        val isPremium: Boolean = settings[KEY_IS_PREMIUM, false]

        return PortalUser(id, email, displayName, isPremium)
    }

    private fun generateDeviceId(): String {
        // Generate a stable device identifier
        return java.util.UUID.randomUUID().toString()
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalTokenStorage.kt
git commit -m "feat(sync): add secure token storage for portal credentials"
```

---

## Task 4: Create SyncManager

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt`

**Step 1: Create the sync manager**

```kotlin
package com.devil.phoenixproject.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class SyncState {
    object Idle : SyncState()
    object Syncing : SyncState()
    data class Success(val syncTime: Long) : SyncState()
    data class Error(val message: String) : SyncState()
    object NotAuthenticated : SyncState()
    object NotPremium : SyncState()
}

class SyncManager(
    private val apiClient: PortalApiClient,
    private val tokenStorage: PortalTokenStorage
) {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _lastSyncTime = MutableStateFlow(tokenStorage.getLastSyncTimestamp())
    val lastSyncTime: StateFlow<Long> = _lastSyncTime.asStateFlow()

    val isAuthenticated: StateFlow<Boolean> = tokenStorage.isAuthenticated
    val currentUser: StateFlow<PortalUser?> = tokenStorage.currentUser

    // === Authentication ===

    suspend fun login(email: String, password: String): Result<PortalUser> {
        return apiClient.login(email, password).map { response ->
            tokenStorage.saveAuth(response)
            response.user
        }
    }

    suspend fun signup(email: String, password: String, displayName: String): Result<PortalUser> {
        return apiClient.signup(email, password, displayName).map { response ->
            tokenStorage.saveAuth(response)
            response.user
        }
    }

    fun logout() {
        tokenStorage.clearAuth()
        _syncState.value = SyncState.NotAuthenticated
    }

    // === Sync Operations ===

    suspend fun sync(): Result<Long> {
        if (!tokenStorage.hasToken()) {
            _syncState.value = SyncState.NotAuthenticated
            return Result.failure(PortalApiException("Not authenticated"))
        }

        _syncState.value = SyncState.Syncing

        // First check status
        val statusResult = apiClient.getSyncStatus()
        if (statusResult.isFailure) {
            val error = statusResult.exceptionOrNull()
            if (error is PortalApiException && error.statusCode == 403) {
                _syncState.value = SyncState.NotPremium
            } else {
                _syncState.value = SyncState.Error(error?.message ?: "Unknown error")
            }
            return Result.failure(error ?: Exception("Status check failed"))
        }

        // Push local changes
        val pushResult = pushLocalChanges()
        if (pushResult.isFailure) {
            _syncState.value = SyncState.Error(pushResult.exceptionOrNull()?.message ?: "Push failed")
            return Result.failure(pushResult.exceptionOrNull() ?: Exception("Push failed"))
        }

        // Pull remote changes
        val pullResult = pullRemoteChanges()
        if (pullResult.isFailure) {
            _syncState.value = SyncState.Error(pullResult.exceptionOrNull()?.message ?: "Pull failed")
            return Result.failure(pullResult.exceptionOrNull() ?: Exception("Pull failed"))
        }

        val syncTime = pullResult.getOrThrow()
        tokenStorage.setLastSyncTimestamp(syncTime)
        _lastSyncTime.value = syncTime
        _syncState.value = SyncState.Success(syncTime)

        return Result.success(syncTime)
    }

    suspend fun checkStatus(): Result<SyncStatusResponse> {
        if (!tokenStorage.hasToken()) {
            return Result.failure(PortalApiException("Not authenticated"))
        }
        return apiClient.getSyncStatus()
    }

    // === Private Helpers ===

    private suspend fun pushLocalChanges(): Result<SyncPushResponse> {
        val deviceId = tokenStorage.getDeviceId()
        val lastSync = tokenStorage.getLastSyncTimestamp()
        val platform = getPlatform()

        // TODO: Gather local changes from repositories
        val request = SyncPushRequest(
            deviceId = deviceId,
            deviceName = getDeviceName(),
            platform = platform,
            lastSync = lastSync,
            sessions = emptyList(), // TODO: Get from WorkoutRepository
            records = emptyList(),  // TODO: Get from PersonalRecordRepository
            routines = emptyList(), // TODO: Get from RoutineRepository
            exercises = emptyList(), // TODO: Get from ExerciseRepository (custom only)
            badges = emptyList(),   // TODO: Get from GamificationRepository
            gamificationStats = null // TODO: Get from GamificationRepository
        )

        return apiClient.pushChanges(request)
    }

    private suspend fun pullRemoteChanges(): Result<Long> {
        val deviceId = tokenStorage.getDeviceId()
        val lastSync = tokenStorage.getLastSyncTimestamp()

        val request = SyncPullRequest(
            deviceId = deviceId,
            lastSync = lastSync
        )

        return apiClient.pullChanges(request).map { response ->
            // TODO: Merge pulled data into local repositories
            // - WorkoutRepository.mergeFromSync(response.sessions)
            // - PersonalRecordRepository.mergeFromSync(response.records)
            // etc.

            response.syncTime
        }
    }

    private fun getPlatform(): String {
        // Will be "android" or "ios" based on actual platform
        return "android" // TODO: Make platform-aware
    }

    private fun getDeviceName(): String? {
        // TODO: Get actual device name from platform
        return null
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt
git commit -m "feat(sync): add SyncManager for orchestrating sync operations"
```

---

## Task 5: Add Sync Components to Koin DI

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/AppModule.kt`

**Step 1: Add imports and sync bindings**

Add at top of file with other imports:
```kotlin
import com.devil.phoenixproject.data.sync.PortalApiClient
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.data.sync.SyncManager
```

Add in `commonModule` after other repository bindings (around line 65):
```kotlin
// Portal Sync
single { PortalTokenStorage(get()) }
single {
    PortalApiClient(
        tokenProvider = { get<PortalTokenStorage>().getToken() }
    )
}
single { SyncManager(get(), get()) }
```

**Step 2: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/di/AppModule.kt
git commit -m "feat(sync): wire sync components into Koin DI"
```

---

## Task 6: Create Link Account ViewModel

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/sync/LinkAccountViewModel.kt`

**Step 1: Create the ViewModel**

```kotlin
package com.devil.phoenixproject.ui.sync

import com.devil.phoenixproject.data.sync.PortalUser
import com.devil.phoenixproject.data.sync.SyncManager
import com.devil.phoenixproject.data.sync.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class LinkAccountUiState {
    object Initial : LinkAccountUiState()
    object Loading : LinkAccountUiState()
    data class Success(val user: PortalUser) : LinkAccountUiState()
    data class Error(val message: String) : LinkAccountUiState()
}

class LinkAccountViewModel(
    private val syncManager: SyncManager
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _uiState = MutableStateFlow<LinkAccountUiState>(LinkAccountUiState.Initial)
    val uiState: StateFlow<LinkAccountUiState> = _uiState.asStateFlow()

    val isAuthenticated: StateFlow<Boolean> = syncManager.isAuthenticated
    val currentUser: StateFlow<PortalUser?> = syncManager.currentUser
    val syncState: StateFlow<SyncState> = syncManager.syncState
    val lastSyncTime: StateFlow<Long> = syncManager.lastSyncTime

    fun login(email: String, password: String) {
        scope.launch {
            _uiState.value = LinkAccountUiState.Loading

            syncManager.login(email, password)
                .onSuccess { user ->
                    _uiState.value = LinkAccountUiState.Success(user)
                }
                .onFailure { error ->
                    _uiState.value = LinkAccountUiState.Error(
                        error.message ?: "Login failed"
                    )
                }
        }
    }

    fun signup(email: String, password: String, displayName: String) {
        scope.launch {
            _uiState.value = LinkAccountUiState.Loading

            syncManager.signup(email, password, displayName)
                .onSuccess { user ->
                    _uiState.value = LinkAccountUiState.Success(user)
                }
                .onFailure { error ->
                    _uiState.value = LinkAccountUiState.Error(
                        error.message ?: "Signup failed"
                    )
                }
        }
    }

    fun logout() {
        syncManager.logout()
        _uiState.value = LinkAccountUiState.Initial
    }

    fun sync() {
        scope.launch {
            syncManager.sync()
        }
    }

    fun clearError() {
        if (_uiState.value is LinkAccountUiState.Error) {
            _uiState.value = LinkAccountUiState.Initial
        }
    }
}
```

**Step 2: Add to Koin in AppModule.kt**

Add import:
```kotlin
import com.devil.phoenixproject.ui.sync.LinkAccountViewModel
```

Add in `commonModule`:
```kotlin
factory { LinkAccountViewModel(get()) }
```

**Step 3: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/sync/LinkAccountViewModel.kt
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/di/AppModule.kt
git commit -m "feat(sync): add LinkAccountViewModel for account linking UI"
```

---

## Task 7: Create Link Account Composable Screen

**Files:**
- Create: `androidApp/src/main/java/com/devil/phoenixproject/android/ui/sync/LinkAccountScreen.kt`

**Step 1: Create the Compose screen**

```kotlin
package com.devil.phoenixproject.android.ui.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.sync.SyncState
import com.devil.phoenixproject.ui.sync.LinkAccountUiState
import com.devil.phoenixproject.ui.sync.LinkAccountViewModel
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun LinkAccountScreen(
    viewModel: LinkAccountViewModel = koinInject(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val isAuthenticated by viewModel.isAuthenticated.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Phoenix Portal",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Sync your workouts across devices",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (isAuthenticated && currentUser != null) {
            // Logged in state
            LinkedAccountContent(
                user = currentUser!!,
                syncState = syncState,
                lastSyncTime = lastSyncTime,
                onSync = { viewModel.sync() },
                onLogout = { viewModel.logout() }
            )
        } else {
            // Login/Signup form
            LoginSignupForm(
                uiState = uiState,
                onLogin = { email, password -> viewModel.login(email, password) },
                onSignup = { email, password, name -> viewModel.signup(email, password, name) },
                onClearError = { viewModel.clearError() }
            )
        }
    }
}

@Composable
private fun LinkedAccountContent(
    user: com.devil.phoenixproject.data.sync.PortalUser,
    syncState: SyncState,
    lastSyncTime: Long,
    onSync: () -> Unit,
    onLogout: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Linked Account",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(text = user.email)
            user.displayName?.let { name ->
                Text(
                    text = name,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (user.isPremium) {
                Spacer(modifier = Modifier.height(8.dp))
                AssistChip(
                    onClick = {},
                    label = { Text("Premium") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Sync status
            when (syncState) {
                is SyncState.Syncing -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Syncing...")
                }
                is SyncState.Success -> {
                    Text("Last synced: ${formatTimestamp(syncState.syncTime)}")
                }
                is SyncState.Error -> {
                    Text(
                        text = "Sync error: ${syncState.message}",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                is SyncState.NotPremium -> {
                    Text(
                        text = "Premium subscription required for sync",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {
                    if (lastSyncTime > 0) {
                        Text("Last synced: ${formatTimestamp(lastSyncTime)}")
                    } else {
                        Text("Never synced")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(onClick = onLogout) {
                    Text("Unlink Account")
                }

                Button(
                    onClick = onSync,
                    enabled = syncState !is SyncState.Syncing
                ) {
                    Text("Sync Now")
                }
            }
        }
    }
}

@Composable
private fun LoginSignupForm(
    uiState: LinkAccountUiState,
    onLogin: (String, String) -> Unit,
    onSignup: (String, String, String) -> Unit,
    onClearError: () -> Unit
) {
    var isSignupMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Tab row for Login/Signup
            TabRow(
                selectedTabIndex = if (isSignupMode) 1 else 0
            ) {
                Tab(
                    selected = !isSignupMode,
                    onClick = { isSignupMode = false; onClearError() }
                ) {
                    Text("Login", modifier = Modifier.padding(16.dp))
                }
                Tab(
                    selected = isSignupMode,
                    onClick = { isSignupMode = true; onClearError() }
                ) {
                    Text("Sign Up", modifier = Modifier.padding(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isSignupMode) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password"
                        )
                    }
                }
            )

            // Error message
            if (uiState is LinkAccountUiState.Error) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (isSignupMode) {
                        onSignup(email, password, displayName)
                    } else {
                        onLogin(email, password)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = email.isNotBlank() && password.isNotBlank() &&
                        (!isSignupMode || displayName.isNotBlank()) &&
                        uiState !is LinkAccountUiState.Loading
            ) {
                if (uiState is LinkAccountUiState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (isSignupMode) "Create Account" else "Login")
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    if (timestamp == 0L) return "Never"
    val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
```

**Step 2: Verify compilation**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add androidApp/src/main/java/com/devil/phoenixproject/android/ui/sync/LinkAccountScreen.kt
git commit -m "feat(sync): add LinkAccountScreen Compose UI"
```

---

## Task 8: Add Link Account to Settings Navigation

**Files:**
- Modify: Find the settings screen and add navigation to LinkAccountScreen

**Step 1: Find where settings are defined**

Look for settings screen in `androidApp/src/main/java/` to add a "Link Portal Account" button.

**Step 2: Add navigation entry**

Add a settings item that navigates to LinkAccountScreen. The exact implementation depends on the existing navigation structure.

**Step 3: Verify compilation**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add .
git commit -m "feat(sync): add Link Account option to settings"
```

---

## Task 9: Build and Test

**Step 1: Build full Android app**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 2: Install and test on device/emulator**

Run: `./gradlew :androidApp:installDebug`

Test flow:
1. Open Settings
2. Find "Link Portal Account" option
3. Test login with portal credentials
4. Verify account linking works
5. Test sync button

**Step 3: Final commit if any fixes needed**

```bash
git add .
git commit -m "feat(sync): Phase 2 complete - mobile sync foundation"
```

---

## Summary

Phase 2 creates the mobile-side sync infrastructure:

| Component | Purpose |
|-----------|---------|
| SyncModels.kt | DTOs for API communication |
| PortalApiClient.kt | Ktor HTTP client for portal backend |
| PortalTokenStorage.kt | Secure JWT storage |
| SyncManager.kt | Sync orchestration with state management |
| LinkAccountViewModel.kt | UI state management |
| LinkAccountScreen.kt | Compose UI for login/sync |

**Next Phase:** Phase 3 will hook workout completion to auto-sync and add background periodic sync.
