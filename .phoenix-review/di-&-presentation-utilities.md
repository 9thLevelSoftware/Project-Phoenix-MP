# DI & Presentation Utilities Review

Scope: reviewed the assigned DI and presentation utility files. Three assigned DI file paths do not exist in the repository; related DI module files were inspected for context only and were not modified.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/di/KoinModules.kt

### Finding 1
- Category: error
- Severity: medium
- Line numbers: N/A (file missing)
- Description: The assigned file `KoinModules.kt` does not exist under `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/`. The current DI files are `AppModule.kt`, `DataModule.kt`, `DomainModule.kt`, `PresentationModule.kt`, `SyncModule.kt`, `Qualifiers.kt`, and `KoinInit.kt`.
- Suggested fix direction: Update the review/build inventory to the current DI module filenames, or restore this file if external code/tests still expect it.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/di/KoinHelper.kt

### Finding 2
- Category: error
- Severity: medium
- Line numbers: N/A (file missing)
- Description: The assigned file `KoinHelper.kt` does not exist anywhere in the repository. If this file previously contained safe Koin accessors or test/bootstrap helpers, those safeguards are not present at this path.
- Suggested fix direction: Remove the stale path from task/configuration inventories or add a replacement helper with the intended responsibilities and tests.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/di/KoinInit.kt

### Finding 3
- Category: failure-point
- Severity: medium
- Line numbers: 9-12, 22-33
- Description: `initKoin()` always calls `startKoin` without checking whether a Koin application is already running. A second call from iOS bootstrap, tests, previews, or repeated app initialization will throw `KoinApplicationAlreadyStartedException` and crash the caller instead of behaving idempotently.
- Suggested fix direction: Add a guarded initializer (`runCatching { KoinPlatform.getKoin() }`, a synchronized started flag, or explicit `stopKoin()` in test-only paths) and document the intended repeated-call behavior.

### Finding 4
- Category: failure-point
- Severity: medium
- Line numbers: 42-52
- Description: `runMigrations()` catches all `Exception`s, logs them, and reports success to the caller. This can leave iOS running against unmigrated or partially repaired data while the UI has no signal that startup migration failed.
- Suggested fix direction: Return a success/failure result or rethrow critical migration failures after logging. If best-effort behavior is intentional, narrow the catch to known recoverable cases and surface a user-visible degraded-state signal.

### Finding 5
- Category: bug
- Severity: low
- Line numbers: 37-40
- Description: The Swift interop comment says to call `KoinKt.runMigrations()`, but this top-level function is declared in `KoinInit.kt`; Kotlin/Native exports top-level declarations using the source file name, so the symbol is expected to be `KoinInitKt.runMigrations()` unless another wrapper exists. This can mislead iOS integration and cause migrations not to be wired.
- Suggested fix direction: Correct the comment and/or add an iOS wrapper next to `doInitKoin()` with a stable exported name.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/di/KoinUtils.kt

### Finding 6
- Category: error
- Severity: medium
- Line numbers: N/A (file missing)
- Description: The assigned file `KoinUtils.kt` does not exist anywhere in the repository. Any review coverage expected for utility functions in this file cannot be performed.
- Suggested fix direction: Update stale inventories to the current DI files or restore the utility file if callers still depend on it.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/DpadSliderSemantics.kt

### Finding 7
- Category: bug
- Severity: medium
- Line numbers: 55-60
- Description: For discrete sliders (`steps > 0`), D-pad left/right adds one interval to the current float value instead of moving between the defined discrete tick indexes. If the current value is off-grid due to a drag, rounding, restored state, or a caller-provided value, remote input can land on another off-grid value rather than the nearest valid slider step.
- Suggested fix direction: For `steps > 0`, compute the ordered tick values from `valueRange` and `steps`, snap the current value to the nearest tick, then move to the adjacent tick. Keep the explicit-step path for continuous sliders.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/PlatformAccessibilitySettings.kt

### Finding 8
- Category: failure-point
- Severity: medium
- Line numbers: 6-13
- Description: The common API exposes `boldTextEnabled`, but platform behavior is asymmetric: the Android actual implementation currently returns `PlatformAccessibilitySettings(boldTextEnabled = false)` unconditionally, while iOS observes the real bold-text setting. Any common layout logic that depends on `LocalPlatformAccessibilitySettings.current.boldTextEnabled` will ignore Android users who enable bold/system high-legibility text.
- Suggested fix direction: Implement Android detection for the relevant system setting/configuration where available, observe configuration changes, or explicitly rename/document the setting as iOS-only so common layout code does not assume cross-platform coverage.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/PlatformInputMode.kt

No findings.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/TestTags.kt

No findings. Checked for duplicate tag string constants; none found.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/WeightDisplayFormatter.kt

### Finding 9
- Category: failure-point
- Severity: low
- Line numbers: 52-56
- Description: `formatNumeric()` decides whether to drop decimals using exact float modulo (`display % 1f == 0f`) and then converts to `Int`. Values that are mathematically whole but represented as `54.999996f` will display as `55.0` instead of `55`, and non-finite/out-of-range values would be converted to misleading integer text rather than rejected.
- Suggested fix direction: Use finite-value checks and epsilon-based whole-number detection before integer formatting, e.g. round to one decimal first, compare within tolerance, and avoid `toInt()` for non-finite values.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/WindowSizeClass.kt

### Finding 10
- Category: bug
- Severity: low
- Line numbers: 41-45
- Description: `isTablet` returns true for every non-compact width, but the enum documentation explicitly includes “phones in landscape” in the Medium width class. This latent helper will classify landscape phones as tablets if used by future layouts.
- Suggested fix direction: Rename the helper to describe width class (`isAtLeastMediumWidth`) or require additional device/height/diagonal information before calling a device a tablet.

## Summary

- Findings count: 10
- Severity breakdown:
  - critical: 0
  - high: 0
  - medium: 7
  - low: 3
- Files with findings: 8
- Files with no findings: 2
- Notes: No code changes were made. The report includes missing assigned files as review errors because those paths could not be inspected.
