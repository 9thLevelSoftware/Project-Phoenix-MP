---
name: update-phoenix-version
description: Update the Project Phoenix mobile app version across Android, iOS, and the in-app Settings display. Use when Codex is asked to bump, set, verify, or release the Phoenix app version, including Android versionName/versionCode, iOS MARKETING_VERSION/CURRENT_PROJECT_VERSION, and shared Constants.APP_VERSION.
---

# Update Phoenix Version

## Overview

Update Phoenix app version metadata from the repo root using the bundled helper script. Keep the version shown in Settings aligned with Android and iOS release metadata.

## Version Surfaces

The four app-version values to keep aligned are:

- `androidApp/build.gradle.kts`: Android `versionName`.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Constants.kt`: `Constants.APP_VERSION`, used by Android `DeviceInfo`, Settings display, and backup metadata.
- `iosApp/VitruvianPhoenix/VitruvianPhoenix.xcodeproj/project.pbxproj`: Debug `MARKETING_VERSION`.
- `iosApp/VitruvianPhoenix/VitruvianPhoenix.xcodeproj/project.pbxproj`: Release `MARKETING_VERSION`.

The helper can also update build-number fields near those values:

- `androidApp/build.gradle.kts`: default `versionCode = injectedVersionCode ?: ...` when `--android-code` is supplied.
- `iosApp/VitruvianPhoenix/VitruvianPhoenix.xcodeproj/project.pbxproj`: both `CURRENT_PROJECT_VERSION` entries when `--ios-build` is supplied.

Do not edit `shared/src/commonMain/composeResources/*/strings.xml` for a version bump. `settings_version` is only the localized label template. Do not edit `androidApp/release/output-metadata.json`; it is generated release output.

## Quick Start

From the repository root:

```powershell
python .agents/skills/update-phoenix-version/scripts/update_version.py --version 0.10.0 --android-code 6 --ios-build 2026052501
```

Use `--dry-run` first when reviewing a proposed bump:

```powershell
python .agents/skills/update-phoenix-version/scripts/update_version.py --version 0.10.0 --android-code 6 --ios-build 2026052501 --dry-run
```

Verify current alignment without modifying files:

```powershell
python .agents/skills/update-phoenix-version/scripts/update_version.py --check --version 0.10.0 --android-code 6 --ios-build 2026052501
```

## Workflow

1. Inspect the requested app version and any requested build numbers.
2. Run the helper with `--dry-run`.
3. Run the helper without `--dry-run` after the replacements look right.
4. Run `--check` with the same values.
5. Search for stale app-version literals:

```powershell
rg -n "0\.9\.0|versionName|APP_VERSION|MARKETING_VERSION|CURRENT_PROJECT_VERSION" androidApp iosApp shared .github docs
```

Replace the escaped old version with the actual previous version. Ignore test fixtures, historical docs, generated release metadata, and localized Settings label templates unless the user explicitly asks to update those too.

## Build Numbers

Pass `--android-code` when the default Android `versionCode` in `androidApp/build.gradle.kts` must be bumped. The Android release workflows can inject a monotonic Play version code with `-Pversion.code=...`, but the checked-in default should still be updated for release prep when requested.

Pass `--ios-build` when the checked-in Xcode project build number should change. The iOS CI workflows auto-increment `CURRENT_PROJECT_VERSION` from the date and GitHub run number before release/TestFlight upload, so do not invent an iOS build number unless the user requested one or release prep requires a local default.

## Verification

For a metadata-only version bump, run:

```powershell
python .agents/skills/update-phoenix-version/scripts/update_version.py --check --version <version> [--android-code <code>] [--ios-build <build>]
git diff --check
```

If the user wants build verification too, use the repo's PowerShell-safe Gradle form:

```powershell
.\gradlew.bat --% :androidApp:testDebugUnitTest :shared:testAndroidHostTest :androidApp:assembleDebug -Pskip.supabase.check=true
```

Do not claim iOS release verification from Windows unless an explicit iOS build/test command was run in a macOS-capable environment.
