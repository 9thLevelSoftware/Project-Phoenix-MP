# MVP Cloud Sync — Mobile App Plan

**Repo:** `Project-Phoenix-MP`
**Base Branch:** `new_ideas`
**MVP Branch:** `mvp/cloud-sync`
**Current Version:** 0.5.1 (versionCode 4)
**Target Version:** 0.6.0

---

## Table of Contents

1. [Branch Setup](#1-branch-setup)
2. [Environment & Credentials](#2-environment--credentials)
3. [Code Changes](#3-code-changes)
4. [ProGuard Fix](#4-proguard-fix)
5. [Version Bump](#5-version-bump)
6. [Build & Test](#6-build--test)
7. [Android Deployment](#7-android-deployment)
8. [iOS Status & Future Work](#8-ios-status--future-work)
9. [Post-Launch Monitoring](#9-post-launch-monitoring)

---

## 1. Branch Setup

```bash
cd C:/Users/dasbl/AndroidStudioProjects/Project-Phoenix-MP
git checkout new_ideas
git pull origin new_ideas
git checkout -b mvp/cloud-sync
```

**Why `new_ideas`:** The entire sync infrastructure (phases 23-28) lives on this branch — SyncManager, PortalApiClient, PortalTokenStorage, Edge Function DTOs, push/pull adapters, 20+ integration tests. Cherry-picking from `main` is not feasible due to deep coupling with gamification repositories and shared domain models.

---

## 2. Environment & Credentials

### 2.1 Supabase (Already Configured)

The Android app reads Supabase credentials from `local.properties` at build time and injects them as `BuildConfig` fields.

**Verify current setup:**

```properties
# File: local.properties (project root, git-ignored)
supabase.url=https://ilzlswmatadlnsuxatcv.supabase.co
supabase.anon.key=sb_publishable_UDrjasV6UJLm_IdIzGljoQ_YaRes4dQ
```

**How it works:**
1. `androidApp/build.gradle.kts` reads `local.properties` at build time
2. Values injected as `BuildConfig.SUPABASE_URL` and `BuildConfig.SUPABASE_ANON_KEY`
3. `VitruvianApp.kt` passes these to Koin as `SupabaseConfig` at app startup
4. `PortalApiClient` uses `SupabaseConfig` to construct Edge Function URLs

**Verify injection chain:**
- `androidApp/build.gradle.kts` lines 6-17 — reads and injects
- `androidApp/src/main/kotlin/.../VitruvianApp.kt` lines 44-48 — passes to Koin
- `shared/.../di/SyncModule.kt` — receives via `get<SupabaseConfig>()`

### 2.2 RevenueCat (Currently Disabled)

The RevenueCat KMP dependency is **commented out** in `shared/build.gradle.kts` (causes iOS build failures due to native SDK linking). Premium gating works via `SubscriptionManager` which reads from the local `subscriptions` table — populated by the portal's RevenueCat webhook writing to Supabase, then pulled to mobile via sync.

**No action needed for MVP.** Subscription state syncs through the existing push/pull pipeline. The mobile app doesn't need RevenueCat SDK directly — it reads subscription tier from the database.

### 2.3 Google Play Signing (For Release)

The CI pipeline injects signing credentials at build time. These are stored as GitHub repository secrets.

**Required GitHub Secrets** (already configured if you've released before):

| Secret | Description | How to Obtain |
|--------|-------------|---------------|
| `ANDROID_KEYSTORE_BASE64` | Your release .jks keystore, base64-encoded | `base64 -w 0 your-keystore.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password | From your keystore creation |
| `ANDROID_KEY_ALIAS` | Key alias within keystore | From your keystore creation |
| `ANDROID_KEY_PASSWORD` | Key password | From your keystore creation |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Service account JSON for Play Store API | [See Section 7.2](#72-google-play-service-account) |

### 2.4 Supabase Credentials for CI Builds

CI builds use placeholder Supabase credentials (build verification only — the app isn't functional in CI). For release builds that will be distributed:

**Option A (Current):** Release builds use the same `local.properties` mechanism. The CI workflow should inject real credentials via environment variables or Gradle properties.

**Option B (Recommended):** Add GitHub secrets for release Supabase config:
```yaml
# In android-playstore.yml, add to the Gradle build command:
-Psupabase.url=${{ secrets.SUPABASE_URL }}
-Psupabase.anon.key=${{ secrets.SUPABASE_ANON_KEY }}
```

Then update `androidApp/build.gradle.kts` to fall back to Gradle properties:
```kotlin
val supabaseUrl: String = localPropsMap["supabase.url"]
    ?: project.findProperty("supabase.url") as? String ?: ""
val supabaseAnonKey: String = localPropsMap["supabase.anon.key"]
    ?: project.findProperty("supabase.anon.key") as? String ?: ""
```

---

## 3. Code Changes

### 3.1 Enable LinkAccount Route in NavGraph

**File:** `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt`

**Action:** Uncomment lines 650-670 (the `composable(route = NavigationRoutes.LinkAccount.route)` block).

The route definition, enter/exit transitions, and `LinkAccountScreen` composable call are all present but commented out with the note:
```
// TODO: Uncomment when online account features are ready for public release
```

**What's already wired:**
- `NavigationRoutes.LinkAccount` is defined in `NavigationRoutes.kt` (line 45) as route `"link_account"`
- `NavGraph.kt` line 322 already passes `onNavigateToLinkAccount = { navController.navigate(NavigationRoutes.LinkAccount.route) }` to `SettingsTab`
- `LinkAccountScreen` composable exists and is complete
- `LinkAccountViewModel` is registered in `PresentationModule.kt` via Koin `factory`

### 3.2 Enable "Link Portal Account" Button in SettingsTab

**File:** `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt`

**Action:** Uncomment lines 1310-1351 (the `OutlinedButton` with sync icon and "Link Portal Account" text, plus the description text below it).

**What this provides:**
- A full-width outlined button with `Icons.Default.Sync` icon
- Text: "Link Portal Account"
- Subtitle: "Sync your workouts to the Phoenix Portal for cross-device access"
- `onClick` calls `onNavigateToLinkAccount()` which is already wired to `navController.navigate(NavigationRoutes.LinkAccount.route)`

### 3.3 (Optional) Add Sync Status Indicator

The `SyncTriggerManager` exposes `hasPersistentError: StateFlow<Boolean>` (true after 3+ consecutive sync failures) but nothing in the UI currently observes it.

**Recommended:** Add a small warning indicator in `SettingsTab` near the "Link Portal Account" button that shows when sync has persistent errors:

```kotlin
// After the Link Portal Account button, add:
val syncTriggerManager = koinInject<SyncTriggerManager>()
val hasSyncError by syncTriggerManager.hasPersistentError.collectAsState()

if (hasSyncError) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = "Sync Error",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Sync error — tap Link Portal Account to retry",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}
```

**Priority:** Nice-to-have. The LinkAccountScreen itself already shows detailed sync state (Syncing/Success/Error/NotPremium).

### 3.4 What NOT to Change

These are already complete and working — do not modify:

- **`LinkAccountScreen.kt`** — Full UI with login/signup tabs, sync status, "Sync Now" button, error display, loading states
- **`LinkAccountViewModel.kt`** — Complete state management: login, signup, logout, sync, clearError
- **`SyncModule.kt`** — All DI registrations: PortalTokenStorage, PortalApiClient, SyncRepository, SyncManager, SyncTriggerManager, AuthRepository, SubscriptionManager
- **`App.kt`** — `AppLifecycleObserver` already calls `syncTriggerManager.onAppForeground()` on every `ON_RESUME`
- **`ActiveSessionEngine.kt`** — Already calls `syncTriggerManager?.onWorkoutCompleted()` after workout save
- **`SyncManager.kt`** — Complete push/pull orchestration
- **`PortalApiClient.kt`** — Ktor HTTP client for GoTrue auth + Edge Functions

---

## 4. ProGuard Fix

**File:** `androidApp/proguard-rules.pro`

**Issue:** Ktor client classes are NOT covered by ProGuard keep rules. Release builds with R8 minification will strip Ktor's HTTP engine, causing "No suitable HttpClient engine found" crashes.

**Action:** Add the following rules:

```proguard
# Ktor HTTP Client (sync layer)
-keep class io.ktor.** { *; }
-keep class io.ktor.client.** { *; }
-keep class io.ktor.client.engine.** { *; }
-keep class io.ktor.client.plugins.** { *; }
-keep class io.ktor.serialization.** { *; }
-keep class io.ktor.utils.** { *; }
-dontwarn io.ktor.**
```

**Why this is critical:** Debug builds don't use ProGuard, so this issue only manifests in release APKs/AABs. It will crash on first sync attempt.

---

## 5. Version Bump

**File:** `androidApp/build.gradle.kts`

**Changes:**
```kotlin
// Update versionName
versionName = "0.6.0"  // was "0.5.1"

// versionCode auto-increments in CI via -Pversion.code=YYYYMMDDNNN
// For local testing, update manually:
versionCode = 5  // was 4
```

---

## 6. Build & Test

### 6.1 Local Build Verification

```bash
# Clean build
./gradlew clean

# Debug build (no ProGuard, no signing)
./gradlew :androidApp:assembleDebug

# Unit tests
./gradlew :androidApp:testDebugUnitTest

# Shared module tests (includes sync integration tests)
./gradlew :shared:testAndroidHostTest
```

### 6.2 Release Build Verification

```bash
# Release build (applies ProGuard rules — critical for catching Ktor stripping)
./gradlew :androidApp:assembleRelease \
    -Pandroid.injected.signing.store.file=$HOME/.android/debug.keystore \
    -Pandroid.injected.signing.store.password=android \
    -Pandroid.injected.signing.key.alias=androiddebugkey \
    -Pandroid.injected.signing.key.password=android
```

**Install and verify on device:**
```bash
adb install -r androidApp/build/outputs/apk/release/androidApp-release.apk
```

### 6.3 Manual Test Checklist

- [ ] Open app → Settings → "Link Portal Account" button is visible
- [ ] Tap "Link Portal Account" → navigates to LinkAccountScreen
- [ ] **Sign Up flow:** Enter display name, email, password → account created
- [ ] **Sign In flow:** Enter email, password → authenticated, shows user info
- [ ] **Sync Now:** Tap "Sync Now" → shows "Syncing..." → shows "Last synced: [timestamp]"
- [ ] **Background sync:** Close app → reopen → sync triggers automatically on resume
- [ ] **Workout sync:** Complete a workout → sync triggers within 5 minutes
- [ ] **Offline resilience:** Enable airplane mode → attempt sync → shows error gracefully
- [ ] **Logout:** Tap "Unlink Account" → returns to login/signup form
- [ ] **Release build:** Repeat above with release APK (catches ProGuard issues)

---

## 7. Android Deployment

### 7.1 Play Store Beta Release

**Trigger:** Manual via GitHub Actions → `android-playstore.yml`

**What happens:**
1. Builds signed AAB (Android App Bundle)
2. Version code auto-generated: `YYYYMMDDNNN` (date + run number)
3. Uploads to Google Play Console → **Open Testing (Beta) track**
4. Submits for review automatically

```bash
# Or trigger via CLI:
gh workflow run android-playstore.yml
```

### 7.2 Google Play Service Account (If Not Already Set Up)

If you haven't published before, you need a service account for automated uploads:

1. **Google Cloud Console** (https://console.cloud.google.com):
   - Select or create a project
   - Go to **IAM & Admin → Service Accounts**
   - Click **Create Service Account**
   - Name: `play-store-publisher`
   - Grant no roles at this step → Create
   - Click the service account → **Keys** tab → **Add Key → Create new key → JSON**
   - Download the JSON file

2. **Google Play Console** (https://play.google.com/console):
   - Go to **Settings → API Access**
   - Click **Link** to link your Google Cloud project
   - Find the service account → Click **Grant access**
   - Under **App permissions**, select your app
   - Under **Account permissions**, enable:
     - Release to production, exclude devices, and use Play App Signing
     - Release apps to testing tracks
     - Manage testing tracks and edit tester lists
   - Click **Invite user → Send invite**

3. **GitHub Secret:**
   - Base64-encode the JSON file: `base64 -w 0 service-account.json`
   - Add as `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` in GitHub repo settings

### 7.3 GitHub Release APK

For direct distribution outside Play Store (sideload testers):

```bash
# Create a git tag first
git tag v0.6.0
git push origin v0.6.0

# Trigger the release workflow
gh workflow run android-release-apk.yml
```

This builds a signed APK, renames it to `ProjectPhoenix-v0.6.0.apk`, and uploads it to the GitHub release.

---

## 8. iOS Status & Future Work

### 8.1 Current State

iOS sync is **not functional** — `PlatformModule.ios.kt` has empty stubs:

```kotlin
single {
    SupabaseConfig(
        url = "",       // TODO: Inject from iOS app
        anonKey = ""    // TODO: Inject from iOS app
    )
}
```

### 8.2 What's Needed for iOS Sync

1. **Inject credentials from iOS app:**

   **Option A — Info.plist (recommended):**
   ```kotlin
   // In PlatformModule.ios.kt:
   single {
       val bundle = NSBundle.mainBundle
       SupabaseConfig(
           url = bundle.objectForInfoDictionaryKey("SUPABASE_URL") as? String ?: "",
           anonKey = bundle.objectForInfoDictionaryKey("SUPABASE_ANON_KEY") as? String ?: ""
       )
   }
   ```

   Then add to `iosApp/.../Info.plist`:
   ```xml
   <key>SUPABASE_URL</key>
   <string>https://ilzlswmatadlnsuxatcv.supabase.co</string>
   <key>SUPABASE_ANON_KEY</key>
   <string>sb_publishable_UDrjasV6UJLm_IdIzGljoQ_YaRes4dQ</string>
   ```

   **Option B — xcconfig files (build-time, more secure for CI):**
   - Create `Config/Release.xcconfig` with the values
   - Reference in Xcode project build settings
   - Read via `Bundle.main.infoDictionary`

2. **Enable the same NavGraph/SettingsTab changes** (shared code, so they already apply to iOS)

3. **Test the Ktor Darwin engine** — iOS uses `ktor-client-darwin` (vs OkHttp on Android). Verify it resolves correctly in the shared framework.

### 8.3 iOS CI Pipeline

The `ios-testflight.yml` workflow handles:
- Building the shared KMP framework (`linkReleaseFrameworkIosArm64`)
- Copying Compose resources into the framework bundle
- Creating an XCFramework
- Xcode archive + export
- Upload to TestFlight with beta group assignment

**Required GitHub Secrets for iOS:**

| Secret | Description | How to Obtain |
|--------|-------------|---------------|
| `BUILD_CERTIFICATE_BASE64` | Distribution certificate (.p12), base64 | Export from Keychain Access on Mac |
| `P12_PASSWORD` | Certificate password | Set during export |
| `PROVISION_PROFILE_BASE64` | Provisioning profile, base64 | Download from Apple Developer portal |
| `KEYCHAIN_PASSWORD` | Temporary keychain password | Any string (used only during CI) |
| `TEAM_ID` | Apple Developer Team ID | Apple Developer portal → Membership |
| `PROVISIONING_PROFILE_NAME` | Profile specifier | Name shown in Apple Developer portal |
| `APPSTORE_API_KEY` | App Store Connect API key (.p8) | App Store Connect → Users → Keys |
| `APPSTORE_API_KEY_ID` | Key ID | Shown next to the API key |
| `APPSTORE_ISSUER_ID` | Issuer ID | App Store Connect → Users → Keys (top of page) |
| `TESTFLIGHT_GROUP_NAME` | Beta group name | Create in App Store Connect → TestFlight |

### 8.4 Recommendation

**Ship Android first.** iOS sync requires credential injection, Darwin engine testing, and TestFlight deployment — doable but adds scope. The sync infrastructure is cross-platform (shared Kotlin code), so iOS is a follow-up task, not a rewrite.

---

## 9. Post-Launch Monitoring

### 9.1 Sync Health Signals

- **Play Console crash reports:** Watch for Ktor/network exceptions in release builds
- **`SyncTriggerManager.hasPersistentError`:** 3+ consecutive failures triggers this flag. Currently only visible in `LinkAccountScreen` — consider adding Sentry/analytics event for this
- **Edge Function logs:** Check Supabase Dashboard → Edge Functions → Logs for `mobile-sync-push` and `mobile-sync-pull` error rates

### 9.2 Known Risks

| Risk | Mitigation |
|------|------------|
| ProGuard strips Ktor engine | Fixed in Phase 4 — test with release build before shipping |
| Token refresh fails silently | `PortalApiClient` handles 401 → re-auth flow, but test with expired token |
| Large sync payloads timeout | Ktor timeout is 30s request / 10s connect — monitor for users with 1000+ workouts |
| Gamification data missing on first sync | `SyncManager.pushLocalChanges()` calls `RpgAttributeEngine.computeProfile()` — if RPG data doesn't exist yet, the sync still succeeds (sends null RPG payload) |

### 9.3 Feature Flags

**There is no feature flag system in the app.** All features are compiled in. If you need to disable sync post-launch without a new release:

- **Quick fix:** The `SyncTriggerManager` throttle could be set to `Long.MAX_VALUE` via a server-side config pull
- **Better fix:** Add a simple remote config check in `SyncManager.sync()` that reads a flag from Supabase before proceeding
- **For MVP:** Not needed — if sync breaks, users simply don't link their account

---

## Summary of Changes

| File | Change | Type |
|------|--------|------|
| `NavGraph.kt` lines 650-670 | Uncomment LinkAccount route | Uncomment |
| `SettingsTab.kt` lines 1310-1351 | Uncomment "Link Portal Account" button | Uncomment |
| `proguard-rules.pro` | Add Ktor keep rules | Add ~7 lines |
| `androidApp/build.gradle.kts` | Bump version to 0.6.0 | Edit 2 lines |
| *(Optional)* `SettingsTab.kt` | Add sync error indicator | Add ~15 lines |
| *(Optional)* `androidApp/build.gradle.kts` | Fall back to Gradle properties for Supabase config | Edit 2 lines |

**Total mandatory code changes: ~10 lines of uncommenting + 7 lines of ProGuard rules + 2 lines of version bump.**
