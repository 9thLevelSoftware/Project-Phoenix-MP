---
title: Theme Mode
summary: Phoenix theme selection is a shared three-state enum persisted in multiplatform settings and propagated through common Compose so System mode survives both Android and iOS host boundaries.
topics: [systems, frontend, android, ios]
sources:
  - id: theme-model
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Theme.kt
    note: Defines ThemeMode and the shared theme entry point that maps SYSTEM to the platform dark-theme signal.
  - id: theme-vm
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ThemeViewModel.kt
    note: Persists theme mode and dynamic color preferences in multiplatform settings.
  - id: app-content
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/App.kt
    note: Collects theme state and passes it into the shared theme and main screen.
  - id: settings-tab
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt
    note: Shows that Settings edits ThemeMode directly through a segmented selector.
  - id: theme-toggle
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ThemeToggle.kt
    note: Shows the quick toggle cycles SYSTEM, LIGHT, and DARK instead of collapsing back to a boolean.
  - id: theme-ui-guard
    type: file
    path: shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/theme/ThemeModeUiContractGuardTest.kt
    note: Guards against regressions that would coerce ThemeMode back into boolean dark-mode wiring.
  - id: ios-theme-wrapper
    type: file
    path: shared/src/iosMain/kotlin/com/devil/phoenixproject/ui/theme/Theme.ios.kt
    note: Shows the legacy boolean iOS wrapper is deprecated because it cannot represent ThemeMode.SYSTEM.
  - id: android-theme-wrapper
    type: file
    path: androidApp/src/main/kotlin/com/devil/phoenixproject/ui/theme/AndroidTheme.kt
    note: Shows the legacy boolean Android wrapper is deprecated because it coerces ThemeMode.SYSTEM into light or dark.
  - id: ios-content
    type: file
    path: iosApp/VitruvianPhoenix/VitruvianPhoenix/ContentView.swift
    note: Shows SwiftUI only hosts the shared Compose controller and does not own separate theme preference state.
  - id: theme-plan
    type: file
    path: docs/enhancement-plans/478-system-default-theme.md
    note: Captures the design intent for replacing the older dark-mode boolean with an explicit System or Light or Dark choice.
status: active
verified: 2026-06-22
---
Theme selection in [[project-phoenix]] is a shared runtime setting, not a platform-host preference. `ThemeMode` is a three-state enum with `SYSTEM`, `LIGHT`, and `DARK`, and the shared `VitruvianTheme` maps `SYSTEM` to `isSystemInDarkTheme()` so the app can follow OS appearance changes without a separate Android or iOS settings model [@theme-model].

## Source of truth

`ThemeViewModel` owns theme preference state. It loads `theme_mode` from multiplatform `Settings`, falls back to `ThemeMode.SYSTEM` when the key is missing or invalid, and persists the enum name back to the same key on every change [@theme-vm]. This means existing saved values are the enum strings themselves rather than an app-specific migration format [@theme-vm].

Dynamic color is a separate preference. The same view model stores `dynamic_color_enabled` independently from `theme_mode`, and the shared theme function only attempts `platformDynamicColorScheme` when that flag is enabled [@theme-vm] [@theme-model]. Theme mode answers dark versus light selection; dynamic color answers palette source.

## Shared propagation path

`AppContent` is the point where theme state becomes runtime UI state. It collects `themeMode` and `dynamicColorEnabled` from `ThemeViewModel`, passes both into `VitruvianTheme`, and also passes `themeMode` plus `onThemeModeChange` into `EnhancedMainScreen` so shared settings and toolbar affordances mutate the same source of truth [@app-content].

The shared theme function is the load-bearing API. The older Android and iOS overloads that accept only `darkTheme: Boolean` are both marked deprecated because converting a platform signal into a boolean destroys `ThemeMode.SYSTEM` as a persisted user choice before the shared theme sees it [@android-theme-wrapper] [@ios-theme-wrapper]. Future theming work should stay on the common `VitruvianTheme(themeMode = ..., dynamicColorEnabled = ...)` path even when the change starts from a host-specific file.

## UI contract

Settings edits the enum directly. `SettingsTab` renders a segmented selector with System, Light, and Dark labels instead of a dark-mode switch, so the shared UI can preserve the third state rather than coercing it to "not dark" [@settings-tab] [@theme-plan].

The compact toolbar toggle is also three-state. `ThemeToggle` cycles `SYSTEM -> LIGHT -> DARK -> SYSTEM`, which makes the shortcut reversible without losing the user's ability to return to OS-following behavior from the header itself [@theme-toggle].

This contract is guarded by tests that read source files directly. `ThemeModeUiContractGuardTest` fails if `SettingsTab` or `NavGraph` revert to boolean `darkModeEnabled` plumbing, and also checks that the shared theme continues to map `ThemeMode.SYSTEM` through `isSystemInDarkTheme()` [@theme-ui-guard].

## Host boundary

Theme preference does not live in SwiftUI. `ContentView.swift` only embeds `MainViewController()`, so iOS does not maintain a second copy of theme state outside the shared Compose tree [@ios-content]. The practical rule is that host changes may affect how system appearance is observed, but not where the preference is stored.

Read [[app-architecture]] first when the question is still about shared runtime ownership rather than theming itself. Read [[platform-hosts]] next when the symptom is platform-specific, especially if Android and iOS disagree about system-theme updates or about which theme wrapper is still being called.
