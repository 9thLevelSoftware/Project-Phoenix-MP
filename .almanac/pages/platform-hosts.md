---
title: Platform Hosts
summary: Android and iOS share the same Compose core but differ in boot order, secure storage, native auth and health adapters, permission handling, and workout background behavior.
topics: [systems, android, ios, auth, workouts, sync]
sources:
  - id: main-activity
    type: file
    path: androidApp/src/main/kotlin/com/devil/phoenixproject/MainActivity.kt
    note: Shows Android boot behavior, locale pre-application, and BLE permission gating.
  - id: android-platform
    type: file
    path: shared/src/androidMain/kotlin/com/devil/phoenixproject/di/PlatformModule.android.kt
    note: Defines Android secure storage, BLE, health, backup, and foreground service bindings.
  - id: ios-app
    type: file
    path: iosApp/VitruvianPhoenix/VitruvianPhoenix/VitruvianPhoenixApp.swift
    note: Shows iOS boot sequence and migration timing.
  - id: ios-platform
    type: file
    path: shared/src/iosMain/kotlin/com/devil/phoenixproject/di/PlatformModule.ios.kt
    note: Defines iOS secure storage, Supabase config loading, and native service bindings.
  - id: ios-content
    type: file
    path: iosApp/VitruvianPhoenix/VitruvianPhoenix/ContentView.swift
    note: Shows that SwiftUI only hosts the shared Compose view controller.
  - id: android-safe-word
    type: file
    path: shared/src/androidMain/kotlin/com/devil/phoenixproject/domain/voice/SafeWordListener.android.kt
    note: Shows Android safe-word recognition uses offline SpeechRecognizer, transient audio focus, and auto-restart behavior.
  - id: ios-safe-word
    type: file
    path: shared/src/iosMain/kotlin/com/devil/phoenixproject/domain/voice/SafeWordListener.ios.kt
    note: Shows iOS safe-word recognition uses on-device SFSpeechRecognizer, microphone permission, and interruption recovery.
  - id: android-haptics
    type: file
    path: shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/components/HapticFeedbackEffect.android.kt
    note: Shows Android workout cue playback uses SoundPool on STREAM_MUSIC with MediaPlayer fallback.
  - id: ios-haptics
    type: file
    path: shared/src/iosMain/kotlin/com/devil/phoenixproject/presentation/components/HapticFeedbackEffect.ios.kt
    note: Shows iOS workout cue playback uses AVAudioSession, AVAudioPlayer, and lifecycle recovery after interruptions.
  - id: android-health-permissions
    type: file
    path: shared/src/androidMain/kotlin/com/devil/phoenixproject/util/HealthPermissionRequester.android.kt
    note: Shows Android health authorization uses the Health Connect Activity Result contract.
  - id: ios-health-permissions
    type: file
    path: shared/src/iosMain/kotlin/com/devil/phoenixproject/util/HealthPermissionRequester.ios.kt
    note: Shows iOS health authorization calls HealthIntegration.requestPermissions() directly through a Compose-side effect.
  - id: android-health-settings
    type: file
    path: shared/src/androidMain/kotlin/com/devil/phoenixproject/util/HealthPermissionSettingsLauncher.android.kt
    note: Shows Android alone has an explicit health-permission settings recovery path after prompt suppression.
status: active
verified: 2026-06-27
---
Android and iOS are thin hosts around the shared Compose core, but they still define boot order, secure token storage, native auth and health adapters, cue and voice integrations, and workout background behavior. Future work that only reads shared code can miss real platform constraints here even when the feature logic itself lives in common Kotlin [@main-activity] [@android-platform] [@ios-app] [@ios-platform].

Android applies the stored locale before `setContent {}` runs. `MainActivity.applyStoredLocaleBeforeComposition()` reads `vitruvian_preferences` directly and updates the platform locale so non-English users do not see an English first frame during cold start [@main-activity].

Android gates the shared UI behind `RequireBlePermissions { AndroidAppHost() }`, sets `STREAM_MUSIC` as the volume control stream, and enables edge-to-edge layout in the native activity [@main-activity]. Workout background continuity is Android-only in this repo because the platform module binds `WorkoutServiceController` to `AndroidWorkoutServiceController` [@android-platform].

Android secure storage is strict. The platform module stores ordinary settings in plaintext `SharedPreferences`, but portal auth tokens live in `EncryptedSharedPreferences`. If encrypted storage cannot be created, the code throws instead of silently falling back to plaintext token storage [@android-platform]. A one-time migration moves existing portal keys out of plaintext preferences into the encrypted store [@android-platform].

The platform modules also own native adapters that shared auth and health code depend on. Both actual modules register an `OAuthLauncher` plus a concrete `HealthIntegration` and `HealthWorkoutWriter` into Koin, so browser-based auth handoff and health-store permission behavior cross the host boundary even though the higher-level sync and integration flows live in common code [@android-platform] [@ios-platform].

Health permission flow is another real host asymmetry. Android launches the Health Connect permission contract through `rememberLauncherForActivityResult` and may need an explicit settings handoff when Health Connect suppresses repeated prompts after revocation, while iOS launches `healthIntegration.requestPermissions()` directly from the Compose side because HealthKit authorization does not require an Activity Result bridge [@android-health-permissions] [@android-health-settings] [@ios-health-permissions].

The same host boundary applies to [[workout-safety-and-feedback]]. Android workout cues route through `SoundPool` on `STREAM_MUSIC` with `MediaPlayer` fallback, and Android voice stop uses offline `SpeechRecognizer` plus transient audio focus [@android-haptics] [@android-safe-word]. iOS workout cues route through `AVAudioSession`, `AVAudioPlayer`, and UIKit haptics, while iOS voice stop depends on on-device `SFSpeechRecognizer`, separate speech and microphone permission flow, and interruption recovery after foreground or audio-session changes [@ios-haptics] [@ios-safe-word].

iOS boot starts in Swift, not Kotlin. `VitruvianPhoenixApp` initializes Koin through `KoinInitIosKt.doInitKoin()`, runs migrations immediately afterward through `KoinInitKt.runMigrations()`, and then loads a SwiftUI `ContentView` that only wraps the shared Compose `UIViewController` [@ios-app] [@ios-content].

iOS secure storage uses two stores with different roles. General preferences stay in `NSUserDefaultsSettings`, while portal auth tokens are migrated into `KeychainSettings` under `com.devil.phoenixproject.auth` [@ios-platform]. Unlike Android, failed Keychain migration logs an error but does not crash the app, so re-authentication is the fallback path [@ios-platform].

iOS also loads [[supabase]] configuration differently. The platform module reads `SUPABASE_URL` and `SUPABASE_ANON_KEY` from the app bundle and fails fast if they are missing, which makes Xcode config wiring part of the runtime contract for auth and sync [@ios-platform].

The most important cross-platform asymmetry after boot is workout background behavior. Android binds a real workout foreground service controller; iOS binds `NoOpWorkoutServiceController`, so any feature that assumes a long-lived background workout service is Android-specific unless the shared layer explicitly handles the no-op case [@android-platform] [@ios-platform].

Theme preference itself is not one of those host-owned asymmetries. SwiftUI only hosts the shared Compose controller, and the shared app entrypoint owns theme selection, so platform-specific theme bugs usually come from system-appearance observation or from accidentally calling deprecated boolean theme wrappers rather than from duplicated preference storage [@ios-content].

Read [[getting-started]] first when the platform symptom is still too broad to tell whether the next page should be [[workouts]], [[sync]], or [[app-architecture]].

Read [[frontend]] first when the visible disagreement is still in screen structure, shared route ownership, or Compose-state projection and you have not yet proved the root cause is in a native adapter.

Read [[workouts]] when the platform difference affects BLE, session continuity, or workout foreground behavior. Read [[workout-safety-and-feedback]] when the platform difference is in cue playback, voice stop, microphone permission, or interruption recovery. Read [[auth]] when the platform difference affects OAuth launch, callback handling, secure token storage, or auth recovery before a session is usable. Read [[sync]] when the platform difference affects Supabase configuration, app-foreground refresh, or remote behavior after login already succeeded. Read [[health-platform-integration]] when the difference is in Health Connect, HealthKit, body-weight import, or workout export behavior. Pair this page with [[portal-sync-transport]] when the problem begins after login succeeds and the remaining question is trigger timing, retry, or authenticated request flow, with [[theme-mode]] when the problem is in system-theme following or theme wrapper selection, and with [[app-architecture]] when you need the shared-to-native boundary in one read.
