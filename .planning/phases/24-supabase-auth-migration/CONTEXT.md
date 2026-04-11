# Phase 24 Context: Supabase Auth Migration

**Phase:** 24 — Supabase Auth Migration
**Repo:** Project-Phoenix-MP (mobile)
**Milestone:** v0.6.0 Portal Sync Compatibility
**Created:** 2026-03-02
**Status:** Decisions locked — ready for planning

---

## Scope Boundary

This phase migrates the mobile app's authentication from a Railway-hosted custom backend to Supabase GoTrue REST API. All changes are in the mobile codebase (Project-Phoenix-MP). No portal changes needed — portal already uses Supabase Auth natively.

**In scope:** GoTrue sign-in/sign-up, token refresh lifecycle, session persistence, Supabase config injection, UserProfile linking, FIX-01 duration unit fix.
**Out of scope:** Social auth (Google/Apple — stubs remain), Edge Functions, sync payload changes, portal schema changes.

---

## Decisions

### 1. Raw Ktor HTTP (No supabase-kt SDK)

**Decision:** Call Supabase GoTrue REST endpoints directly via Ktor HTTP client. Do NOT add the supabase-kt SDK.

**Rationale:** Per v0.6.0 architectural decision — supabase-kt has version conflicts with Kotlin 2.3.0 and Ktor 3.x. The existing PortalApiClient already uses raw Ktor. GoTrue REST API is simple (5 endpoints).

### 2. Config Injection via local.properties → BuildConfig → Koin

**Decision:** Store Supabase project URL and anon key in `local.properties`, inject via `buildConfigField` in androidApp's `build.gradle.kts`, pass to shared module via Koin `SupabaseConfig` data class.

**Rationale:** Follows Android best practice (secrets not in source). iOS will use equivalent mechanism (compile-time constant or Info.plist). The anon key is a public key (safe in client apps) but keeping it out of source control is still good hygiene.

**Implementation:**
- `local.properties`: `supabase.url=https://<project-ref>.supabase.co` and `supabase.anon.key=<anon-key>`
- `androidApp/build.gradle.kts`: `buildConfigField("String", "SUPABASE_URL", ...)` and `buildConfigField("String", "SUPABASE_ANON_KEY", ...)`
- Shared module: `data class SupabaseConfig(val url: String, val anonKey: String)`
- Android `platformModule`: provides `SupabaseConfig` from BuildConfig
- iOS `platformModule`: provides `SupabaseConfig` from compile-time values (placeholder for now)

### 3. Token Storage: Expand Existing PortalTokenStorage

**Decision:** Add `refresh_token`, `expires_at` (Unix seconds), and compute `user_id` from JWT `sub` claim. Keep using `com.russhwolf.settings` (SharedPreferences on Android, NSUserDefaults on iOS).

**Rationale:** PortalTokenStorage already works well. Adding 2 fields is simpler than replacing the entire storage layer. JWT parsing for `sub` claim is trivial (base64url decode middle segment, parse JSON).

**New storage keys:**
- `KEY_REFRESH_TOKEN = "portal_refresh_token"`
- `KEY_EXPIRES_AT = "portal_token_expires_at"` (Unix epoch seconds)

### 4. GoTrue DTOs: New File, Don't Pollute Existing

**Decision:** Create `GoTrueModels.kt` in the sync package for all Supabase GoTrue request/response data classes. Keep existing `PortalAuthResponse` and `PortalUser` as the app's internal auth models — GoTrue responses are mapped to them after deserialization.

**Rationale:** Clean separation between wire format (GoTrue API) and app models. The existing `PortalUser` and `AuthUser` interfaces are used throughout the app — changing them to match GoTrue format would require cascading changes everywhere.

### 5. Token Refresh Strategy: Proactive + Reactive

**Decision:** Two-layer refresh:
1. **Proactive:** Before every authenticated API call, check if `expires_at - 60s < now`. If so, refresh first.
2. **Reactive:** On HTTP 401, attempt one refresh and retry. If refresh fails, force re-login.

Both layers protected by a Mutex to prevent concurrent refresh token consumption (which would revoke all sessions).

**Rationale:** Supabase refresh tokens are single-use with a 10-second reuse window. Concurrent refresh calls beyond that window trigger security revocation. The Mutex ensures serialized refresh.

### 6. PortalApiClient: Dual-Mode During Migration

**Decision:** PortalApiClient gains GoTrue auth methods alongside existing Railway methods. The auth endpoints switch to GoTrue immediately. Sync endpoints continue pointing to Railway (until Phase 26 switches them to Edge Functions).

**Rationale:** Auth and sync are independent concerns. We can't switch sync endpoints until Edge Functions exist (Phase 25). Auth can switch now because Supabase Auth is standalone.

**Endpoint mapping after this phase:**
| Function | Before | After |
|----------|--------|-------|
| login | `POST /api/auth/login` (Railway) | `POST /auth/v1/token?grant_type=password` (Supabase) |
| signup | `POST /api/auth/signup` (Railway) | `POST /auth/v1/signup` (Supabase) |
| getMe | `GET /api/auth/me` (Railway) | `GET /auth/v1/user` (Supabase) |
| refresh | *(none)* | `POST /auth/v1/token?grant_type=refresh_token` (Supabase) |
| logout | *(client-only)* | `POST /auth/v1/logout` (Supabase) + client clear |
| pushChanges | `POST /api/sync/push` (Railway) | **unchanged** (Railway until Phase 26) |
| pullChanges | `POST /api/sync/pull` (Railway) | **unchanged** (Railway until Phase 26) |

### 7. FIX-01: estimateRoutineDuration Returns Seconds

**Decision:** Remove the `/ 60` at the end of `estimateRoutineDuration()`. Portal's `routines.estimated_duration` column expects seconds. Current code returns minutes.

**Rationale:** The function already computes in seconds internally, then divides by 60 at the end. Removing the division fixes the unit mismatch. The DTO field `estimatedDuration: Int` is unit-agnostic — portal interprets it as seconds.

### 8. UserProfile Supabase Linking

**Decision:** After successful GoTrue sign-in/sign-up, call `UserProfileRepository.linkToSupabase(profileId, supabaseUserId)` to store the Supabase UUID in the local profile. The `supabase_user_id` column already exists in the schema.

**Rationale:** Links local profile to Supabase identity for future cross-device scenarios. The DB column and repository methods already exist — just need to call them from the auth flow.

---

## Code Context (Existing Assets)

### Files to modify
- `shared/.../data/sync/PortalApiClient.kt` — Switch auth endpoints to GoTrue
- `shared/.../data/sync/PortalTokenStorage.kt` — Add refresh_token, expires_at
- `shared/.../data/repository/PortalAuthRepository.kt` — GoTrue flow + real token refresh
- `shared/.../di/SyncModule.kt` — Inject SupabaseConfig
- `shared/.../data/sync/SyncManager.kt` — Integrate token refresh before sync
- `shared/.../data/sync/PortalSyncAdapter.kt` — FIX-01 duration unit fix
- `androidApp/build.gradle.kts` — BuildConfig fields for Supabase config
- `local.properties` — Supabase URL and anon key

### Files to create
- `shared/.../data/sync/GoTrueModels.kt` — Supabase GoTrue DTOs
- `shared/.../data/sync/SupabaseConfig.kt` — Config data class

### Existing infrastructure (no changes needed)
- `AuthRepository.kt` interface — already defines correct contract
- `AuthScreen.kt` — already works with AuthRepository
- `PortalSyncDtos.kt` — sync DTOs unchanged in this phase
- `SyncTriggerManager.kt` — already checks auth state before sync
- `UserProfile` schema — `supabase_user_id` column already exists

### GoTrue REST Endpoints (Supabase)
| Endpoint | Method | Headers | Body |
|----------|--------|---------|------|
| `/auth/v1/signup` | POST | `apikey`, `Content-Type: application/json` | `{email, password, data?}` |
| `/auth/v1/token?grant_type=password` | POST | `apikey`, `Content-Type: application/json` | `{email, password}` |
| `/auth/v1/token?grant_type=refresh_token` | POST | `apikey`, `Content-Type: application/json` | `{refresh_token}` |
| `/auth/v1/user` | GET | `apikey`, `Authorization: Bearer <token>` | — |
| `/auth/v1/logout` | POST | `apikey`, `Authorization: Bearer <token>` | — |

### GoTrue Response Shape (sign-in/sign-up/refresh)
```json
{
  "access_token": "eyJhbGci...",
  "token_type": "bearer",
  "expires_in": 3600,
  "expires_at": 1740000000,
  "refresh_token": "v1:abc123...",
  "user": {
    "id": "uuid",
    "email": "user@example.com",
    "user_metadata": { "display_name": "..." },
    "app_metadata": { "provider": "email" },
    ...
  }
}
```

### GoTrue Error Shape
```json
{
  "error": "invalid_grant",
  "error_description": "Invalid login credentials",
  "error_code": "invalid_credentials"
}
```

---

## Deferred Ideas

- **Encrypted token storage**: Currently using plain SharedPreferences/NSUserDefaults. Could upgrade to EncryptedSharedPreferences (Android) / Keychain (iOS). Deferred to security hardening pass.
- **Social auth (Google/Apple)**: Stubs exist in AuthRepository. Requires Supabase OAuth flow with deep linking. Deferred to v0.7.0+.
- **MFA / AAL2**: Supabase supports it but not needed for workout sync app.

---

## Next Steps

1. **Plan 01** (Wave 1): GoTrue Auth Foundation — config, DTOs, API client, token storage, auth repository
2. **Plan 02** (Wave 2, depends on 01): Token Lifecycle & Session Persistence — refresh, mutex, 401 retry, app restart
3. **Plan 03** (Wave 1, independent): FIX-01 Duration Unit Fix
