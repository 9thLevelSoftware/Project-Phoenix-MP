# Data - Auth review

Scope reviewed for task t_56038fae: OAuth implementation, PKCE flow, token storage, and auth repository.

Repository note: the 11 file names in the task body are not present at the listed `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/auth/*.kt` paths. The auth implementation appears to have been consolidated/renamed. I reviewed the matching implementation files that currently exist:

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/auth/OAuth.kt`
- `shared/src/androidMain/kotlin/com/devil/phoenixproject/data/auth/OAuth.android.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/data/auth/OAuth.ios.kt`
- `androidApp/src/main/kotlin/com/devil/phoenixproject/auth/OAuthRedirectActivity.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/AuthRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/PortalAuthRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalTokenStorage.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt` auth/token-refresh portions
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/GoTrueModels.kt`
- platform DI wiring for secure settings on Android/iOS

## Findings

### 1. `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/PortalAuthRepository.kt`

- Category: bug
- Severity: high
- Line numbers: 191-196
- Description: `refreshSession()` only clears auth when the exception message contains case-sensitive substrings `"Unauthorized"` or `"invalid"`. GoTrue/Portal failures can arrive as `PortalApiException` with `statusCode` 401/403 and messages such as `"Invalid Refresh Token"`, `"JWT expired"`, or other localized/error-body strings. Those will bypass this check, leaving the expired access token, refresh token, and user in `PortalTokenStorage`; because `hasToken()` and `currentUser` remain true, the app can continue reporting an authenticated state after a permanently failed refresh.
- Suggested fix direction: classify refresh failures by structured status (`PortalApiException.statusCode == 401 || statusCode == 403`) and/or case-insensitive error codes, not brittle message text. Clear auth and emit a session-expired event for permanent auth failures; preserve state only for clearly transient failures.

### 2. `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/PortalAuthRepository.kt` and `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalTokenStorage.kt`

- Category: bug
- Severity: high
- Line numbers: `PortalAuthRepository.kt` 176-179; `PortalTokenStorage.kt` 132-147 and 231-245
- Description: Auth state mutations are not serialized. `signOut()` calls the remote logout and then clears local storage, while a concurrent sign-in, OAuth exchange, restore, or token refresh can still call `saveGoTrueAuth()` afterward and re-populate tokens/user after the user has signed out. The storage writes themselves are multi-key and unguarded, so observers can also see partial combinations during overlapping mutations.
- Suggested fix direction: protect all auth mutation paths with a shared `Mutex`/single-threaded auth actor, or use a session generation/cancellation token so stale refresh/login completions cannot write after sign-out. Keep token/user/expiry writes and flow updates in one serialized critical section.

### 3. `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt`

- Category: failure-point
- Severity: medium
- Line numbers: 574-584 and 608-619
- Description: `ensureValidToken()` and `forceRefresh()` compute `isRecoverable`, but then call `clearAuthWithEvent(...)` regardless of that value. A transient network timeout, DNS failure, or server outage during refresh will clear local auth and force re-login even though the code labels the error recoverable.
- Suggested fix direction: only clear stored auth for non-recoverable auth failures such as 401/403/revoked refresh token. For recoverable refresh failures, emit `RefreshFailed(isRecoverable = true)` without deleting tokens, and let the caller retry/back off or show an offline state.

### 4. `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/auth/OAuth.android.kt`

- Category: failure-point
- Severity: medium
- Line numbers: 70-83 and 121-151
- Description: The Android abandonment watcher treats any app activity resume after the app was stopped as OAuth cancellation if no callback arrives within a fixed 750 ms grace window. It does not tie the stop/resume to the originating activity or distinguish browser/task transitions, multi-window resumes, process/task reordering, or slow redirect delivery. A valid OAuth flow can therefore be cancelled before the deep link is delivered on slower devices/browsers or unusual task transitions.
- Suggested fix direction: track the host activity that launched OAuth and only apply abandonment logic to the expected return path; consider a longer/device-tested grace period, browser/custom-tab callbacks where available, or an explicit timeout/cancel signal rather than treating every resume as dismissal.

### 5. `shared/src/iosMain/kotlin/com/devil/phoenixproject/data/auth/OAuth.ios.kt`

- Category: failure-point
- Severity: medium
- Line numbers: 135-138
- Description: `PresentationContextProvider` returns the first connected `UIWindowScene` or creates a new `UIWindow()` when none is found. In multi-scene/iPad, background, or cold-start edge cases, the first scene may not be foreground active/key, and a newly-created unattached window is not a valid presentation anchor. `ASWebAuthenticationSession` can fail to start or present behind the wrong scene.
- Suggested fix direction: select a foreground-active `UIWindowScene` and its key window, falling back only to an existing visible window. If no presentation anchor exists, fail with a clear error instead of returning a detached `UIWindow()`.

### 6. `shared/src/iosMain/kotlin/com/devil/phoenixproject/data/auth/OAuth.ios.kt`

- Category: bug
- Severity: medium
- Line numbers: 50-54 and 106-113
- Description: iOS `OAuthLauncher.launch()` allows overlapping launches. A second call overwrites the shared `session` and `presentationProvider` properties while the first coroutine/session may still be active. Unlike Android's `AndroidOAuthBridge.beginFlow()`, there is no cancellation or rejection of the previous flow, so rapid double taps or parallel Google/Apple sign-in requests can produce orphaned sessions or resume the wrong caller.
- Suggested fix direction: guard `launch()` with a mutex/in-flight flag. Either reject a second launch with a clear failure or cancel the existing `ASWebAuthenticationSession` before starting a new one, matching Android's single-flight behavior.

### 7. `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalTokenStorage.kt`

- Category: failure-point
- Severity: medium
- Line numbers: 52 and 69-72
- Description: The class documentation says tokens must be stored in secure settings, but the constructor accepts any `Settings` instance and `verifyStorageIntegrity()` only verifies read/write behavior. A future DI/test/wiring mistake can pass plaintext `Settings` and still pass verification, silently storing JWTs and refresh tokens unencrypted.
- Suggested fix direction: make the constructor accept a distinct secure-storage wrapper/type (for example an expect/actual `SecureTokenStorage` or qualified provider object), keep direct construction internal, or add platform-specific verification/marker checks so plaintext settings cannot satisfy the token-storage dependency in production.

### 8. `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/GoTrueModels.kt`

- Category: bug
- Severity: low
- Line numbers: 35-38
- Description: `GoTrueUser.displayName` reads `user_metadata["display_name"]` via `JsonElement.toString().trim('"')`. That returns JSON text, not decoded content, so names with escaped quotes, unicode escapes, or non-string values can display incorrectly (for example `Jane \"JJ\" Doe` rather than `Jane "JJ" Doe`).
- Suggested fix direction: read `jsonPrimitive.contentOrNull` after checking the element is a primitive string, and ignore or safely stringify non-string metadata.

### 9. `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalTokenStorage.kt`

- Category: failure-point
- Severity: low
- Line numbers: 64, 179-183, and 231-240
- Description: `clearAuthInternal()` removes tokens, user fields, subscription tier, and last-sync timestamp, but it does not clear `portal_phase_pr_backfill_checkpoint_*` keys. If the same local profile is linked to a different portal account after logout, stale checkpoints can survive and cause the backfill process to skip historical data for the new account/profile combination.
- Suggested fix direction: either clear auth-scoped checkpoint keys on logout/account switch, or include the Supabase user id/account id in checkpoint keys so checkpoints cannot bleed across accounts.

### 10. `shared/src/androidMain/kotlin/com/devil/phoenixproject/di/PlatformModule.android.kt` and `shared/src/iosMain/kotlin/com/devil/phoenixproject/di/PlatformModule.ios.kt`

- Category: failure-point
- Severity: low
- Line numbers: Android 147-157; iOS 42-52
- Description: The one-time plaintext-to-secure-storage migration key lists omit `portal_user_subscription_tier` and all `portal_phase_pr_backfill_checkpoint_*` keys, even though `PortalTokenStorage` persists those values. Existing users upgrading from plaintext storage can lose tier state or leave auth/sync-adjacent account metadata behind in plaintext legacy storage.
- Suggested fix direction: add every key written by `PortalTokenStorage` to the migration strategy. For prefix keys, enumerate matching legacy keys rather than relying on a fixed list.

## Per assigned file status

The exact task-listed files below were not present in the current repository. Their corresponding code was reviewed in the consolidated files noted above.

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/auth/OAuthConfig.kt`: not present; provider config reviewed in `OAuth.kt`.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/auth/OAuthLoginRequest.kt`: not present; OAuth authorize/exchange request construction reviewed in `PortalAuthRepository.kt` and `GoTrueModels.kt`.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/auth/OAuthPkce.kt`: not present; PKCE implementation reviewed in `OAuth.kt`.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/auth/OAuthSession.kt`: not present; session state reviewed in `AuthRepository.kt`, `PortalAuthRepository.kt`, and `PortalTokenStorage.kt`.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/auth/OAuthSessionManager.kt`: not present; session restore/refresh reviewed in `PortalAuthRepository.kt` and `PortalApiClient.kt`.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/auth/OAuthTokens.kt`: not present; token persistence reviewed in `PortalTokenStorage.kt`.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/auth/OAuthUser.kt`: not present; user models reviewed in `AuthRepository.kt`, `SyncModels.kt`, and `GoTrueModels.kt`.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/auth/OAuthUserManager.kt`: not present; user/profile linking reviewed in `PortalAuthRepository.kt`.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/auth/PortalAuthRepository.kt`: not present at that path; actual file reviewed at `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/PortalAuthRepository.kt`.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/auth/PortalAuthRepositoryImpl.kt`: not present; implementation is the concrete `PortalAuthRepository` class in `data/repository`.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/auth/SecureTokenStorage.kt`: not present; secure token storage reviewed in `PortalTokenStorage.kt` plus platform secure-settings DI.

## Severity breakdown

- Critical: 0
- High: 2
- Medium: 5
- Low: 3
- Total findings: 10
