---
name: update-phoenix-version
description: Update the Project Phoenix app version across Android, iOS, tvOS, and the in-app Settings display. Use when Codex is asked to bump, set, verify, or release the Phoenix app version, including Android versionName/versionCode, Apple MARKETING_VERSION/CURRENT_PROJECT_VERSION, and shared Constants.APP_VERSION.
---

# Update Phoenix Version

## Overview

Update Phoenix app version metadata from the repo root using the bundled helper script. Keep the version shown in Settings aligned with Android, iOS, and tvOS release metadata.

## Version Surfaces

The app-version values to keep aligned are:

- `androidApp/build.gradle.kts`: Android `versionName`.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Constants.kt`: `Constants.APP_VERSION`, used by Android `DeviceInfo`, Settings display, and backup metadata.
- `iosApp/PhoenixApp/PhoenixApp.xcodeproj/project.pbxproj`: Debug `MARKETING_VERSION`.
- `iosApp/PhoenixApp/PhoenixApp.xcodeproj/project.pbxproj`: Release `MARKETING_VERSION`.
- `tvosApp/PhoenixTV.xcodeproj/project.pbxproj`: Debug and Release `MARKETING_VERSION`.

The helper can also update build-number fields near those values:

- `androidApp/build.gradle.kts`: default `versionCode = injectedVersionCode ?: ...` when `--android-code` is supplied.
- `iosApp/PhoenixApp/PhoenixApp.xcodeproj/project.pbxproj`: both `CURRENT_PROJECT_VERSION` entries when `--ios-build` is supplied.
- `tvosApp/PhoenixTV.xcodeproj/project.pbxproj`: both `CURRENT_PROJECT_VERSION` entries when `--tvos-build` is supplied. This is independent of `--ios-build`; neither option changes the other platform.

Use Python 3.10 or newer. The helper checks every expected field before writing any file; a missing or malformed TV target is an error, not a silently skipped version surface.

Do not edit `shared/src/commonMain/composeResources/*/strings.xml` for a version bump. `settings_version` is only the localized label template. Do not edit `androidApp/release/output-metadata.json`; it is generated release output.

## Quick Start

Read the checked-in version, then bump that. `release-all` checks Android, shared, iOS, and tvOS version parity before creating the release. Copying an older release into this skill writes a downgrade and leaves Settings on the wrong version.

Confirm these in the files before running. They are the live defaults:

- `versionName`, `Constants.APP_VERSION`, and all iOS/tvOS `MARKETING_VERSION` entries: `1.0.9`
- default Android `versionCode` (`injectedVersionCode ?: ...`): `13`

From the repository root, dry-run the bump first. `<version>` is the next numeric `x.y.z` after `1.0.9` (sample shape `1.2.3`, not a version to apply). `<android-code>` is the next code after `13` when the checked-in Play default must move. Omit `--ios-build` unless a local Xcode build number was requested; iOS CI replaces `CURRENT_PROJECT_VERSION` before release/TestFlight upload.

```powershell
python .agents/skills/update-phoenix-version/scripts/update_version.py --version <version> --android-code <android-code> --dry-run
```

Apply the same values after the replacements look right:

```powershell
python .agents/skills/update-phoenix-version/scripts/update_version.py --version <version> --android-code <android-code>
```

Verify the current tree without modifying files:

```powershell
python .agents/skills/update-phoenix-version/scripts/update_version.py --check --version 1.0.9 --android-code 13
```

## Workflow

1. Read the checked-in `versionName` (`1.0.9`) and default `versionCode` (`13`), then choose the bump from those values. Do not reuse an older release as the target.
2. Run the helper with `--dry-run`.
3. Run the helper without `--dry-run` after the replacements look right.
4. Run `--check` with the same values.
5. Search for stale app-version literals:

```powershell
rg -n "1\.0\.9|versionName|APP_VERSION|MARKETING_VERSION|CURRENT_PROJECT_VERSION" androidApp iosApp tvosApp shared .github docs
```

Replace the escaped old version with the actual previous version. Ignore test fixtures, historical docs, generated release metadata, and localized Settings label templates unless the user explicitly asks to update those too.

## Build Numbers

Pass `--android-code` when the default Android `versionCode` in `androidApp/build.gradle.kts` must be bumped. The Android release workflows can inject a monotonic Play version code with `-Pversion.code=...`, but the checked-in default should still be updated for release prep when requested.

Pass `--ios-build` when the checked-in Xcode project build number should change. The iOS CI workflows auto-increment `CURRENT_PROJECT_VERSION` from the date and GitHub run number before release/TestFlight upload, so do not invent an iOS build number unless the user requested one or release prep requires a local default.

## Verification

For a metadata-only version bump, run:

```powershell
python .agents/skills/update-phoenix-version/scripts/update_version.py --check --version <version> [--android-code <code>] [--ios-build <build>] [--tvos-build <build>]
git diff --check
```

If the user wants build verification too, use the repo's PowerShell-safe Gradle form:

```powershell
.\gradlew.bat --% :androidApp:testDebugUnitTest :shared:testAndroidHostTest :androidApp:assembleDebug -Pskip.supabase.check=true
```

Do not claim iOS release verification from Windows unless an explicit iOS build/test command was run in a macOS-capable environment.

## Standalone tvOS beta integration

- Read current values from the files, including the TV project; documentation literals are not release defaults. Keep a version already merged by another release PR instead of generating a duplicate bump PR.
- The standalone TV gate (`python3 .github/scripts/tvos-release.py preflight`) compares Android, shared, iOS and TV marketing versions, checks the Android default code exists, and checks both Apple projects' local build defaults agree. Android codes and Apple builds are independent numbering systems, not equal integers.
- `--tvos-build` changes only TV. If intentionally changing both checked-in Apple defaults, supply both `--ios-build` and `--tvos-build` with the requested common default. CI substitutes its own TV archive build number without modifying the phone project or the checked-in defaults.
- Extend with small targeted edits when integrating upstream changes to this skill; do not replace upstream documentation or version-helper behavior wholesale.
- Verify helper behavior with `python3 .agents/skills/update-phoenix-version/scripts/test_update_version.py`. All updates in these tests target temporary fixture repositories, never the current checkout.
- For unsigned builds, production/Phantom boundaries, signing prerequisites and upload checks, read `tvosApp/README.md`. `release-all` includes TV TestFlight upload unless explicitly skipped; the version helper itself does not dispatch workflows or upload builds.
