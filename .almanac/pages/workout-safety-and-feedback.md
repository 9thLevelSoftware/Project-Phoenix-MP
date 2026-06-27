---
title: Workout Safety And Feedback
summary: Workout safety and feedback combines settings-backed cue preferences, platform haptic or audio playback, and an optional on-device safe-word listener that stops the current set from the active workout screen.
topics: [systems, workouts, frontend, android, ios]
sources:
  - id: user-preferences
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/UserPreferences.kt
    note: Defines the workout cue and voice-stop preference fields stored for each user.
  - id: preferences-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt
    note: Defines how cue and voice-stop preferences are loaded and updated from persistent settings.
  - id: haptic-events
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt
    note: Defines the typed event surface for workout haptic and audio cues.
  - id: countdown-policy
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ExerciseCountdownCuePolicy.kt
    note: Defines when countdown ticks emit and how playback speed ramps during the last seconds.
  - id: active-session-engine
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt
    note: Emits cue events during rep completion, timers, warmup transitions, and velocity-threshold handling.
  - id: main-viewmodel
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt
    note: Exposes workout cue events from the workout coordinator to the shared UI.
  - id: active-workout-screen
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt
    note: Starts safe-word listening during active workouts and turns detections into stop-and-return-to-set-ready actions.
  - id: settings-tab
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt
    note: Defines user-facing voice-stop configuration and the three-detection calibration flow.
  - id: safe-word-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/voice/SafeWordDetectionManager.kt
    note: Defines the workout-scoped lifecycle gate for safe-word listening.
  - id: safe-word-interface
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/voice/SafeWordListener.kt
    note: Defines the cross-platform safe-word listener contract and its on-device-only assumption.
  - id: android-safe-word
    type: file
    path: shared/src/androidMain/kotlin/com/devil/phoenixproject/domain/voice/SafeWordListener.android.kt
    note: Defines Android speech recognition behavior, audio focus, and auto-restart rules.
  - id: ios-safe-word
    type: file
    path: shared/src/iosMain/kotlin/com/devil/phoenixproject/domain/voice/SafeWordListener.ios.kt
    note: Defines iOS speech recognition behavior, microphone authorization, and interruption recovery.
  - id: android-haptics
    type: file
    path: shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/components/HapticFeedbackEffect.android.kt
    note: Defines Android cue rendering, stream routing, and SoundPool or MediaPlayer fallback behavior.
  - id: ios-haptics
    type: file
    path: shared/src/iosMain/kotlin/com/devil/phoenixproject/presentation/components/HapticFeedbackEffect.ios.kt
    note: Defines iOS cue rendering, AVAudioSession setup, and haptic playback behavior.
status: active
verified: 2026-06-25
---
Phoenix treats workout safety and workout feedback as one runtime neighborhood, but the implementation is split across two paths. Shared workout managers emit typed `HapticEvent` values for rep, timer, velocity, completion, and celebration moments, while a separate safe-word stack can stop the current set hands-free from [[shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt|ActiveWorkoutScreen]] without ending the whole workout session [@haptic-events] [@main-viewmodel] [@active-workout-screen].

The cue path is preference-driven. `UserPreferences` stores `beepsEnabled`, `countdownBeepsEnabled`, `audioRepCountEnabled`, `repSoundEnabled`, `voiceStopEnabled`, `safeWord`, and `safeWordCalibrated`, and `PreferencesManager` persists those flags in the shared settings store that both workout and settings code read [@user-preferences] [@preferences-manager]. This makes feedback behavior a shared runtime contract rather than a screen-local toggle.

Cue emission starts in the workout engine, not in the UI. `ActiveSessionEngine` emits `HapticEvent` values for rep completion, final reps, warmup completion, warmup-to-working transitions, workout start or end, countdown ticks, rest-ending warnings, and velocity-threshold alerts, while `ExerciseCountdownCuePolicy` limits countdown ticks to distinct seconds inside the last ten seconds and increases playback speed as the timer approaches zero [@active-session-engine] [@countdown-policy]. `MainViewModel` then re-exposes the coordinator's `hapticEvents` flow to shared screens instead of translating those events into UI-specific callbacks [@main-viewmodel].

Platform cue playback is intentionally asymmetric underneath the shared event surface. Android renders the same `HapticEvent` flow through a `SoundPool` on `STREAM_MUSIC`, falls back to `MediaPlayer` when needed, and special-cases Fire OS because `SoundPool` volume is unreliable there [@android-haptics]. iOS renders the flow through UIKit haptic generators plus `AVAudioPlayer`, and it re-prepares cached players after foregrounding or AVAudioSession interruptions so workout cues recover after phone calls, Siri, or backgrounding [@ios-haptics].

Voice stop is gated harder than ordinary cues. `SafeWordDetectionManager.startForWorkout()` does nothing unless voice stop is enabled, a safe word exists, and calibration succeeded, then it creates a platform `SafeWordListener`, bridges its detections onto a stable shared flow, and starts listening only for the lifetime of the current active-workout screen [@safe-word-manager] [@active-workout-screen]. `ActiveWorkoutScreen` responds to each detection by calling `stopAndReturnToSetReady()`, so the safety action ends the current set and returns control to the set-ready state instead of disconnecting BLE or tearing down the whole workout flow [@active-workout-screen].

Calibration is part of the feature contract, not just setup UI. `SettingsTab` uppercases and stores the chosen phrase, marks the safe word uncalibrated when it changes, and requires three successful detections in `SafeWordCalibrationDialog` before `safeWordCalibrated` becomes true again [@settings-tab]. That is why a populated safe word can still be ignored during workouts if the user has not completed calibration after editing it [@settings-tab] [@safe-word-manager].

The speech-recognition layer is on-device on both platforms, but the failure modes differ. The shared `SafeWordListener` contract says both implementations avoid network dependence and auto-restart after recognition segments [@safe-word-interface]. Android uses `SpeechRecognizer` with `EXTRA_PREFER_OFFLINE`, transient audio focus, main-thread recognizer creation, and a restart loop after recoverable failures or end-of-speech [@android-safe-word]. iOS uses `SFSpeechRecognizer` with `requiresOnDeviceRecognition`, prompts separately for speech and microphone permission, and installs foreground or interruption observers so microphone capture can recover after the app resumes or the audio session is interrupted [@ios-safe-word].

Read [[workouts]] first when search lands here but the bug could still be in BLE state, live session orchestration, or routine flow rather than in cues or voice stop. Read [[workout-engine]] next when the question is about when cue events emit or why a stop returns to a particular workout state. Read [[platform-hosts]] when the symptom differs between Android and iOS because permission prompts, audio-session behavior, and native playback stacks all cross the host boundary even though the shared workout event surface does not.
