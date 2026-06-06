# Issue #478 — System Default Theme Implementation Plan

> For Hermes: implement this plan as its own PR. Use subagent-driven development with at most 3 concurrent agents. Each implementation task should be bounded, TDD-first, and independently reviewed before moving to the next task.

GitHub issue: https://github.com/9thlevelsoftware/Project-Phoenix-MP/issues/478
Branch: `feat/478-system-default-theme`
PR scope: theme settings UI and platform theme-following behavior only.

## Goal

Replace the current binary Dark Mode setting with explicit Light, Dark, and System default choices, and ensure System default follows Android/iOS OS theme changes.

## Current state verified in repo

The domain model is mostly already present:

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Theme.kt`
  - `ThemeMode { SYSTEM, LIGHT, DARK }` exists.
  - `VitruvianTheme(themeMode, dynamicColorEnabled, content)` already maps SYSTEM to `isSystemInDarkTheme()`.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ThemeViewModel.kt`
  - persists `ThemeMode.name` in settings key `theme_mode`.
  - defaults to SYSTEM when missing/corrupt.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/App.kt`
  - observes theme mode and passes it into `VitruvianTheme`.

The missing pieces are presentation and platform plumbing:

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt`
  - still renders a two-state Dark Mode switch.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt`
  - currently collapses theme state to boolean behavior when wiring Settings.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ThemeToggle.kt`
  - binary Light/Dark toggle; SYSTEM is not represented.
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/ui/theme/Theme.ios.kt`
  - needs verification/update so persisted `ThemeMode` is honored, not just a raw boolean.
- `iosApp/VitruvianPhoenix/VitruvianPhoenix/ContentView.swift`
  - likely needs trait/colorScheme forwarding or host refresh for live iOS system-theme changes.

## Architecture

Keep `ThemeMode` as the single source of truth. Do not introduce another boolean. The Settings UI should edit the enum directly. Platform-specific code should only provide “what is the system currently doing?” when `ThemeMode.SYSTEM` is selected.

Persisted values remain the existing enum strings:

- `SYSTEM`
- `LIGHT`
- `DARK`

This makes migration automatic for users who already have LIGHT/DARK stored.

## Product decisions for v1

- Settings gets the full three-state selector.
- Toolbar quick toggle should either:
  - be removed from primary UI, or
  - cycle `SYSTEM -> LIGHT -> DARK -> SYSTEM` with a clear icon/content description.
- Do not add cloud sync for theme mode in this PR.
- Do not redesign the whole Settings screen.

## Tasks

### Task 1 — Add/confirm ThemeMode tests

Objective: lock down persistence behavior before changing UI.

Files:

- Test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/ui/theme/ThemeModeTest.kt`
- Test/modify: existing `ThemeViewModelTest` if present under `shared/src/androidHostTest` or `shared/src/commonTest`.

Steps:

1. Write tests that assert enum names are stable: SYSTEM, LIGHT, DARK.
2. Write/extend ThemeViewModel tests:
   - missing setting defaults to SYSTEM.
   - SYSTEM persists/restores.
   - LIGHT persists/restores.
   - DARK persists/restores.
   - invalid setting falls back to SYSTEM.
3. Run targeted tests:
   - `./gradlew :shared:allTests --tests '*Theme*'`
4. Confirm tests fail only if current coverage is missing or behavior is wrong.
5. Implement only missing test fixtures/helpers if needed.
6. Re-run targeted tests.

Expected result: tests pass without changing production model behavior, because enum/settings support mostly exists.

### Task 2 — Replace SettingsTab boolean API with ThemeMode API

Objective: make Settings edit the actual enum.

Files:

- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt`
- Modify: any previews/tests that construct `SettingsTab`.

Steps:

1. Write a UI/host test or preview assertion showing Settings renders three choices: System, Light, Dark.
2. Change `SettingsTab` parameters:
   - `darkModeEnabled: Boolean` -> `themeMode: ThemeMode`
   - `onDarkModeChange: (Boolean) -> Unit` -> `onThemeModeChange: (ThemeMode) -> Unit`
3. Replace the Dark Mode `Switch` row with a segmented button or radio row.
4. In `NavGraph.kt`, pass through the app’s `themeMode` directly.
5. Remove boolean coercion like `themeMode == ThemeMode.DARK` from Settings wiring.
6. Update previews and compile.

Verification:

- `./gradlew :shared:compileKotlinMetadata`
- targeted Settings/Theme tests.

### Task 3 — Update quick theme toggle behavior

Objective: prevent the toolbar toggle from destroying SYSTEM selection.

Files:

- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ThemeToggle.kt`
- Modify callers if signature changes.

Preferred implementation:

- Change API to accept `themeMode: ThemeMode` and `onThemeModeChange: (ThemeMode) -> Unit`.
- On click, cycle: SYSTEM -> LIGHT -> DARK -> SYSTEM.
- Use an “auto/system” icon for SYSTEM.
- Content description includes current and next mode.

Alternative:

- Remove/hide quick toggle and rely on Settings only.

Tests:

- click from SYSTEM emits LIGHT.
- click from LIGHT emits DARK.
- click from DARK emits SYSTEM.

### Task 4 — Android system-theme live updates

Objective: verify SYSTEM mode follows OS changes.

Files:

- Inspect/modify: `androidApp/src/main/AndroidManifest.xml`
- Inspect: Android main activity file under `androidApp/src/main`.

Steps:

1. Inspect MainActivity configChanges.
2. If Activity is recreated on `uiMode`, ensure current theme state is restored from `ThemeViewModel` and Compose updates.
3. If Activity handles `uiMode` itself, ensure Compose reads `LocalConfiguration.current` or `isSystemInDarkTheme()` inside composition so recomposition happens.
4. Add a comment/test helper if necessary.

Manual verification is required because OS theme changes are platform runtime behavior.

### Task 5 — iOS system-theme live updates

Objective: ensure iOS uses the same theme selection model.

Files:

- Modify/inspect: `shared/src/iosMain/kotlin/com/devil/phoenixproject/ui/theme/Theme.ios.kt`
- Modify/inspect: `shared/src/iosMain/kotlin/com/devil/phoenixproject/IosAppHost.kt`
- Modify/inspect: `shared/src/iosMain/kotlin/com/devil/phoenixproject/MainViewController.kt`
- Modify/inspect: `iosApp/VitruvianPhoenix/VitruvianPhoenix/ContentView.swift`

Steps:

1. Verify iOS shared entrypoint receives or loads `ThemeMode`.
2. If iOS wrapper accepts only `darkTheme: Boolean`, change it to enum mode or route through common `VitruvianTheme(themeMode = ...)`.
3. In SwiftUI host, observe `@Environment(\.colorScheme)` or trait changes and trigger Compose host refresh only when mode is SYSTEM.
4. Keep LIGHT/DARK forced modes independent of OS changes.

Verification:

- `./gradlew :shared:assembleXCFramework`
- manual iOS theme toggle test.

### Task 6 — Strings and accessibility

Objective: ship localized labels and content descriptions.

Files:

- Modify all `shared/src/commonMain/composeResources/values*/strings.xml`.

Add keys similar to:

- `settings_theme_mode`
- `settings_theme_mode_description`
- `settings_theme_system`
- `settings_theme_light`
- `settings_theme_dark`
- `cd_theme_mode_system`
- `cd_theme_mode_light`
- `cd_theme_mode_dark`

For non-English files, use English fallback if project convention allows untranslated new strings.

## Verification commands

Run from repo root:

```bash
./gradlew :shared:compileKotlinMetadata
./gradlew :shared:allTests --tests '*Theme*'
./gradlew :androidApp:assembleDebug
./gradlew :shared:assembleXCFramework
```

## Manual QA

Android:

1. Set app theme to Light; switch OS dark mode on/off; app remains Light.
2. Set app theme to Dark; switch OS dark mode on/off; app remains Dark.
3. Set app theme to System; switch OS dark mode on/off; app follows OS.
4. Restart app after each mode; selection persists.

IOS:

1. Repeat Light/Dark/System behavior.
2. Change OS appearance while app is backgrounded and foregrounded.
3. Verify no initial crash/blank Compose host.

## Risks

- iOS live theme changes may need a host refresh rather than pure Compose recomposition.
- Binary toolbar toggle may be confusing if converted to three-state cycling.
- Existing users must not lose LIGHT/DARK preference.

## Acceptance criteria

- Settings exposes Light, Dark, System default.
- All three options persist and survive restart.
- System mode tracks OS theme changes on Android and iOS.
- Existing binary-dark users migrate to equivalent LIGHT/DARK setting.
- No unrelated Settings behavior changes.
