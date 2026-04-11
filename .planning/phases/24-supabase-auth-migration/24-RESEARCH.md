# Phase 24 Research: Supabase Auth Migration

**Phase:** 24 — Supabase Auth Migration
**Created:** 2026-03-02
**Status:** Complete

---

## Research Questions

### Q1: Supabase GoTrue REST API Endpoints

**Answer:** Supabase Auth (GoTrue) exposes a REST API at `https://<project-ref>.supabase.co/auth/v1/`. All endpoints require an `apikey` header with the project's anon (public) key.

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/auth/v1/signup` | POST | Create account (email/password) |
| `/auth/v1/token?grant_type=password` | POST | Sign in (email/password) |
| `/auth/v1/token?grant_type=refresh_token` | POST | Refresh access token |
| `/auth/v1/user` | GET | Get current user (requires Bearer token) |
| `/auth/v1/logout` | POST | Sign out (requires Bearer token) |

**Sign-up response** (email confirmation disabled): Returns full session with `access_token`, `refresh_token`, `expires_in`, `expires_at`, and `user` object. If email confirmation is enabled, only returns `user` (no session tokens).

**Sign-in response**: Same shape as sign-up with session. Returns `access_token`, `refresh_token`, `expires_in` (seconds), `expires_at` (Unix epoch seconds), and `user`.

**Refresh response**: Same shape. New `access_token` + new `refresh_token` (old one is consumed).

**Error format**: `{"error": "invalid_grant", "error_description": "Invalid login credentials", "error_code": "invalid_credentials"}`. Parse defensively — some endpoints use `code`/`msg` instead.

### Q2: JWT Structure and Claims

**Answer:** The `access_token` is a standard JWT. Decode the payload (base64url middle segment) to get:

```json
{
  "sub": "fbdf5a53-161e-4460-98ad-0e39408d8689",  // user UUID = auth.uid()
  "exp": 1740003600,      // expiry (Unix seconds)
  "iat": 1740000000,      // issued at
  "email": "user@example.com",
  "role": "authenticated",
  "aal": "aal1",
  "session_id": "uuid",
  "is_anonymous": false
}
```

The `sub` claim is the user's UUID — this is the same value as `auth.uid()` in RLS policies and the `user.id` field in the response. Safe to extract locally without a network call.

### Q3: Refresh Token Behavior

**Answer:** Critical gotchas for mobile:

1. **Single-use**: Each refresh token can only be used once. Using it returns a new pair (access + refresh).
2. **10-second reuse window**: If you retry within 10 seconds of first use (e.g., network error), Supabase returns the same active session.
3. **Reuse outside window = revocation**: Using a consumed token after the 10-second window triggers a security response — ALL sessions for that user are revoked.
4. **Implication**: MUST serialize refresh calls with a Mutex. Two concurrent coroutines trying to refresh simultaneously would cause the second call to use an already-consumed token.
5. **Refresh tokens don't expire by time** — only by use or explicit logout.

### Q4: Native Mobile Client Considerations

**Answer:**
- No cookies needed. Supabase auth for native apps is fully header-based.
- The `apikey` header is required on ALL requests (including public endpoints). It is the anon key.
- Authenticated endpoints need both `apikey` AND `Authorization: Bearer <access_token>`.
- No `Origin` header needed from mobile clients (no CORS applies to native HTTP calls).
- Clock skew: Use `expires_at` from server response as reference. Add 60-second buffer.
- Background/kill: Persist refresh_token to storage. On cold start, check if access_token expired, refresh if needed.

### Q5: Rate Limits

**Answer:**
| Endpoint | Limit |
|----------|-------|
| `/signup` (email sends) | 2 emails/hour (project-wide) |
| `/token?grant_type=password` | 1800/hour per IP |
| `/token?grant_type=refresh_token` | 1800/hour per IP |

Rate-limited requests return HTTP 429 with `{"error":"over_request_rate_limit"}`. The 1800/hour limit is generous for single-user mobile use.

### Q6: Existing Codebase State

**Answer:** Comprehensive investigation reveals:

**Already exists (ready to modify):**
- `PortalApiClient.kt` — Ktor HTTP client with Railway endpoints, Bearer auth
- `PortalTokenStorage.kt` — SharedPreferences/NSUserDefaults storage (token, userId, email, displayName, isPremium, lastSync, deviceId)
- `PortalAuthRepository.kt` — Implements `AuthRepository` interface, delegates to PortalApiClient
- `SyncModule.kt` — Koin DI module wiring auth + sync components
- `AuthScreen.kt` — Compose Multiplatform UI for sign-in/sign-up
- `UserProfile.supabase_user_id` — DB column already exists
- `linkToSupabase()` / `getProfileBySupabaseId()` — Repository methods already exist

**Current flow:** AuthScreen → AuthRepository → PortalApiClient → Railway `/api/auth/login` → PortalTokenStorage saves token

**Target flow:** AuthScreen → AuthRepository → PortalApiClient → Supabase `/auth/v1/token` → PortalTokenStorage saves access_token + refresh_token + expires_at

**Key gap:** No refresh_token storage, no expires_at tracking, no actual token refresh logic. `refreshSession()` currently just calls `getMe()` to check validity — doesn't refresh.

### Q7: FIX-01 Duration Unit Analysis

**Answer:** `PortalSyncAdapter.estimateRoutineDuration()`:
```kotlin
private fun estimateRoutineDuration(routine: Routine): Int {
    return routine.exercises.sumOf { ex ->
        val setsTime = ex.sets * 120  // 2 min per set (in seconds)
        val restTime = ex.setRestSeconds.sum().takeIf { it > 0 }
            ?: (ex.sets * 60)  // default 60s rest
        setsTime + restTime
    } / 60  // <-- BUG: converts to minutes, portal expects seconds
}
```

Fix: Remove `/ 60`. Portal's `routines.estimated_duration` column stores seconds.

### Q8: Supabase Config Injection Options

**Answer:** Best approach for KMP:
1. `local.properties` stores `supabase.url` and `supabase.anon.key`
2. `androidApp/build.gradle.kts` reads them and creates `buildConfigField` entries
3. Shared module defines `data class SupabaseConfig(val url: String, val anonKey: String)`
4. Android `platformModule` provides `SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)`
5. iOS `platformModule` provides `SupabaseConfig` from compile-time constants

This follows the existing pattern where platform-specific values flow through Koin.

---

## Key Findings

1. **Minimal UI changes**: AuthScreen works through AuthRepository interface — no changes needed
2. **Clean migration path**: Replace Railway endpoints in PortalApiClient, expand PortalTokenStorage, update PortalAuthRepository
3. **Critical safety**: Mutex on token refresh is non-negotiable — concurrent refresh = session revocation
4. **Supabase user.id = auth.uid()**: The `sub` JWT claim matches the UUID used in RLS policies
5. **Email confirmation**: If enabled on the Supabase project, sign-up won't return a session. Need to handle null session state gracefully.
6. **Sync endpoints unchanged**: Railway sync endpoints stay until Phase 26 (Edge Functions)

---

*Research complete: 2026-03-02*
