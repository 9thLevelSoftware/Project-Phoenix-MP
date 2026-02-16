# External Integrations

**Analysis Date:** 2026-02-13

## APIs & External Services

**Phoenix Portal Backend:**
- Railway-hosted backend - Authentication, sync, premium features
  - Base URL: `https://phoenix-portal-backend.up.railway.app`
  - Client: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt`
  - Auth: Token-based (bearer auth), stored via `tokenProvider()` callback
  - Endpoints:
    - `/api/auth/login` - Email/password authentication
    - `/api/auth/signup` - User registration
    - `/api/auth/me` - Current user profile
    - `/api/sync/status` - Sync status check
    - `/api/sync/push` - Push local changes to server
    - `/api/sync/pull` - Pull server changes to local
  - Timeout: 30s request, 10s connect

**GitHub Exercise Library:**
- Exercise data repository - Exercise metadata and videos
  - URL: `https://raw.githubusercontent.com/VitruvianFitness/exercise-library/main/exercise_dump.json`
  - Client: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/ExerciseImporter.kt`
  - Auth: None (public repository)
  - Purpose: Import/update exercise library with descriptions, muscle groups, videos

## Data Storage

**Databases:**
- SQLite (SQLDelight multiplatform)
  - Connection: Platform-specific drivers
    - Android: `shared/src/androidMain/kotlin/com/devil/phoenixproject/data/local/DriverFactory.android.kt`
    - iOS: `shared/src/iosMain/kotlin/com/devil/phoenixproject/data/local/DriverFactory.ios.kt`
  - Client: SQLDelight 2.2.1 (type-safe query API)
  - Schema: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq`
  - Schema Version: 11 (1 base + 10 migrations)
  - Database file: `vitruvian.db`
  - WAL mode enabled (iOS), foreign keys enforced (Android)
  - Resilient migration handling:
    - Android: Pre-flight column checks, duplicate column detection
    - iOS: 4-layer defense (no-op schema, health checks, backup exclusion, manual schema)

**File Storage:**
- Local filesystem only
  - Android: CSV exports via FileProvider (`${applicationId}.fileprovider`)
  - Paths defined in `androidApp/src/main/res/xml/file_paths.xml`

**Caching:**
- None (no dedicated caching layer)
- SQLite serves as local data store
- Multiplatform Settings 1.3.0 for key-value preferences

## Authentication & Identity

**Auth Provider:**
- Custom (Phoenix Portal Backend)
  - Implementation: JWT/Bearer token approach (inferred from `bearerAuth()` usage)
  - Token management: Injected via `tokenProvider()` callback in `PortalApiClient`
  - No local auth - requires server connection for login/signup
  - Premium subscription validation: HTTP 403 response on restricted endpoints

**RevenueCat (Disabled):**
- Premium subscription management (commented out in `shared/build.gradle.kts`)
  - Reason: iOS build failures (Issue #215)
  - Status: Not currently integrated

## Monitoring & Observability

**Error Tracking:**
- None detected (no Sentry, Crashlytics, or similar SDK found)

**Logs:**
- Kermit 2.0.8 - Multiplatform logging library
  - Platform adapters for Android (Logcat) and iOS (NSLog)
  - No remote log aggregation detected

**Connection Diagnostics:**
- Custom BLE connection logging
  - Table: `ConnectionLog` in `VitruvianDatabase.sq`
  - Fields: timestamp, eventType, level, deviceAddress, deviceName, message, details, metadata
  - Local storage only (no external service)

**Hardware Diagnostics:**
- Custom Vitruvian machine diagnostics
  - Table: `DiagnosticsHistory` in `VitruvianDatabase.sq`
  - Fields: runtimeSeconds, faultMask, temp1-8, containsFaults, timestamp
  - Tracks machine temperature and fault conditions
  - Local storage only

## CI/CD & Deployment

**Hosting:**
- Android: Google Play Store
- iOS: Apple App Store (via TestFlight)

**CI Pipeline:**
- GitHub Actions (`.github/workflows/`)
  - Runners: `ubuntu-latest` (Android/tests), macOS required for iOS builds
  - Java 17 (Zulu distribution) for Android builds
  - Gradle caching enabled (`~/.gradle/caches`, `~/.gradle/wrapper`, `~/.konan`)
  - Artifact retention: 7 days for APKs

**Build Triggers:**
- `ci-tests.yml`: Push to main/beta*/feature/*, PRs to main/beta*
- `android-release-apk.yml`: Manual or tag-based (inferred)
- `ios-testflight.yml`: Manual or tag-based (inferred)

**Deployment Automation:**
- Android Play Store deployment via GitHub Actions workflow
- iOS TestFlight deployment (internal + external tracks)
- APK versioning: `versionCode` overridable via `-Pversion.code=XXX` Gradle property

## Environment Configuration

**Required env vars:**
- None detected in code (no `.env` files, no `BuildConfig` environment vars)
- Configuration appears runtime-based:
  - Portal API base URL: Hardcoded with fallback (`DEFAULT_PORTAL_URL`)
  - Database paths: Platform-specific (Android Context, iOS Library directory)

**Secrets location:**
- Android debug keystore: `${user.home}/.android/debug.keystore` (standard Android)
- Production signing: Not visible in repository (likely GitHub Actions secrets)
- API tokens: Managed at runtime via `tokenProvider()` callback (stored in Settings/SharedPreferences)

## Webhooks & Callbacks

**Incoming:**
- None detected

**Outgoing:**
- None detected (API calls are client-initiated only)

## Hardware Integrations

**Vitruvian Trainer Machines:**
- Protocol: Bluetooth Low Energy (BLE)
  - Service UUID: `6e400001-b5a3-f393-e0a9-e50e24dcca9e` (Nordic UART Service)
  - TX Characteristic: `6e400002-b5a3-f393-e0a9-e50e24dcca9e` (write to device)
  - RX Characteristic: `6e400003-b5a3-f393-e0a9-e50e24dcca9e` (notifications from device)
  - Device name prefixes: `Vee_*` (V-Form Trainer), `VIT*` (Trainer+)
- BLE Library: Kable 0.40.2 (Kotlin Multiplatform)
- Implementation: Platform-specific (inferred from Kable architecture)
  - Android: Uses Android BLE stack
  - iOS: Uses CoreBluetooth
- Permissions:
  - Android: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION` (for BLE scanning)
  - iOS: NSBluetoothAlwaysUsageDescription (inferred, not visible in shared code)

**Workout Foreground Service:**
- Android: `WorkoutForegroundService` (foreground service type: `connectedDevice`)
  - Purpose: Maintain BLE connection during workouts
  - Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `WAKE_LOCK`

---

*Integration audit: 2026-02-13*
