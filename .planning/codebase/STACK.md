# Technology Stack

**Analysis Date:** 2026-02-13

## Languages

**Primary:**
- Kotlin 2.3.0 - Cross-platform business logic and Android app
- Swift - iOS application layer (iosApp)

**Secondary:**
- None

## Runtime

**Environment:**
- Android: JVM 17 (compileSdk 36, minSdk 26, targetSdk 36)
- iOS: Native (iosArm64 target only - physical devices)
- Gradle: 9.1.0
- Kotlin/Native for iOS framework compilation

**Package Manager:**
- Gradle 9.1.0 with Kotlin DSL
- Lockfile: Not detected (using version catalogs in `gradle/libs.versions.toml`)

## Frameworks

**Core:**
- Kotlin Multiplatform 2.3.0 - Shared business logic across platforms
- Compose Multiplatform 1.10.0 - Declarative UI (Android + shared module)
- Android Gradle Plugin 9.0.0 - Android build system
- Jetpack Compose BOM 2025.12.01 - Android UI components

**Testing:**
- JUnit 4.13.2 - Unit test framework
- MockK 1.14.7 - Kotlin mocking library
- Turbine 1.2.1 - Flow testing utilities
- Google Truth 1.4.5 - Fluent assertion library
- Kotlinx Coroutines Test 1.10.2 - Coroutine testing
- Koin Test 4.1.1 - DI testing
- Espresso 3.6.1 - Android instrumented tests
- Multiplatform Settings Test 1.3.0 - Settings mocking

**Build/Dev:**
- SQLDelight Gradle Plugin 2.2.1 - Database schema validation
- Kotlin Serialization Plugin 2.3.0 - JSON serialization codegen
- Kotlin Compose Plugin 2.3.0 - Compose compiler

## Key Dependencies

**Critical:**
- Koin 4.1.1 - Dependency injection (multiplatform, replaces Hilt for KMP)
- SQLDelight 2.2.1 - Type-safe multiplatform database (replaces Room)
- Kotlinx Coroutines 1.10.2 - Async/reactive programming
- Kotlinx Serialization 1.9.0 - JSON serialization/deserialization
- Kotlinx DateTime 0.7.1 - Multiplatform date/time handling
- Kable 0.40.2 - Bluetooth Low Energy multiplatform library

**Infrastructure:**
- Ktor Client 3.3.3 - HTTP client (Coil network layer + API calls)
- Coil 3.3.0 - Multiplatform image loading
- Multiplatform Settings 1.3.0 - SharedPreferences/UserDefaults abstraction
- Kermit 2.0.8 - Multiplatform logging
- Compottie 2.0.2 - Multiplatform Lottie animations

**Android-Only:**
- Vico Charts 2.3.6 - Charting library (Android-specific implementation)
- Media3 ExoPlayer 1.6.0 - HLS video playback (workout tutorial videos)
- AndroidX Core KTX 1.17.0 - Kotlin extensions for Android framework
- AndroidX Lifecycle 2.10.0 - ViewModel and lifecycle management
- AndroidX Navigation Compose 2.9.1 - Navigation for Compose

**iOS-Only:**
- SQLDelight Native Driver 2.2.1 - iOS SQLite driver
- Ktor Darwin Client 3.3.3 - iOS HTTP engine

**Premium (Disabled):**
- RevenueCat 2.2.15+17.25.0 - Commented out in `shared/build.gradle.kts` (Issue #215, iOS build failures)

## Configuration

**Environment:**
- Configuration via `gradle.properties` (build settings, JVM args)
- Local SDK path in `local.properties` (gitignored)
- No `.env` files detected (environment vars likely handled via build config or runtime settings)
- Database version: 11 (SQLDelight schema version in `shared/build.gradle.kts`)

**Build:**
- `build.gradle.kts` - Root project configuration with plugin aliases
- `androidApp/build.gradle.kts` - Android app module (APK versioning, ProGuard, signing)
- `shared/build.gradle.kts` - KMP shared module (source sets, platform drivers)
- `gradle/libs.versions.toml` - Version catalog for dependency management
- `androidApp/proguard-rules.pro` - Code obfuscation/optimization for release builds

**CI/CD:**
- GitHub Actions workflows in `.github/workflows/`:
  - `ci-tests.yml` - Unit tests, lint, Android instrumentation, iOS compile checks
  - `android-release-apk.yml` - APK build and release
  - `android-playstore.yml` - Play Store deployment
  - `ios-release-ipa.yml` - iOS IPA build
  - `ios-testflight.yml` - TestFlight deployment
  - `ios-testflight-internal.yml` - Internal TestFlight builds

## Platform Requirements

**Development:**
- JDK 17+ (JVM target for Android compilation)
- Gradle 9.1.0+
- Android SDK 36 (compileSdk)
- For iOS builds: macOS with Xcode (iosArm64 target)
- Gradle JVM args: `-Xmx8g` (8GB heap for Kotlin/Native DevirtualizationAnalysis)

**Production:**
- Android: Min SDK 26 (Android 8.0 Oreo), Target SDK 36
- iOS: iosArm64 (physical devices only, no simulator builds)
- BLE hardware requirement: `android.hardware.bluetooth_le` required
- Deployment targets:
  - Google Play Store (via GitHub Actions)
  - Apple TestFlight (via GitHub Actions)

---

*Stack analysis: 2026-02-13*
