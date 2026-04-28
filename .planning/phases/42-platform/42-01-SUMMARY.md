---
status: complete
agent: engineering-senior-developer
wave: 1
---

# Plan 42-01 Summary: BackupDestination Model & Platform Pickers

## Status: Complete

## What Changed
- **BackupDestination.kt** (NEW): Sealed class with `Default` and `Custom(uri, displayName, bookmarkData?)` variants. `@Serializable` with `@SerialName` discriminators. `toJson()`/`fromJson()` with graceful fallback to Default on corrupt data.
- **BackupLocationPicker.kt** (NEW): `expect class` with `@Composable LaunchDirectoryPicker` and `rememberBackupLocationPicker()` function. Follows same pattern as `FilePicker.kt`.
- **BackupLocationPicker.android.kt** (NEW): `OpenDocumentTree` + `takePersistableUriPermission` + `DocumentFile.fromTreeUri` for display name.
- **BackupLocationPicker.ios.kt** (NEW): `UIDocumentPickerViewController` with `UTTypeFolder`, bookmark creation via `bookmarkDataWithOptions(NSURLBookmarkCreationMinimalBookmark)`, Base64 encoding. Delegate extracted to separate class.
- **PreferencesManager.kt** (MODIFIED): Added `KEY_BACKUP_DESTINATION`, `setBackupDestination()` interface+impl, `loadPreferences()` read with fromJson fallback.
- **UserPreferences.kt** (MODIFIED): Added `backupDestination` field (default = Default).
- **libs.versions.toml** (MODIFIED): Added `androidx-documentfile` version + library.
- **shared/build.gradle.kts** (MODIFIED): Added `androidx-documentfile` dependency to androidMain.

## Decisions
1. `bookmarkData` as `String?` (Base64) not `ByteArray` — kotlinx.serialization handles strings cleanly in JSON
2. `toJson()` as extension function in companion — keeps sealed class public surface minimal
3. No Koin module changes — picker is Composable-instantiated, not injected
4. `fromJson()` accepts nullable string — extra safety at call site
5. Added `androidx.documentfile:documentfile:1.1.0` explicitly — not transitively available

## Verification
All 14 verification commands passed. `./gradlew :androidApp:assembleDebug` BUILD SUCCESSFUL.

## Files Created
- shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupDestination.kt
- shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupLocationPicker.kt
- shared/src/androidMain/kotlin/com/devil/phoenixproject/util/BackupLocationPicker.android.kt
- shared/src/iosMain/kotlin/com/devil/phoenixproject/util/BackupLocationPicker.ios.kt

## Files Modified
- shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt
- shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/UserPreferences.kt
- gradle/libs.versions.toml
- shared/build.gradle.kts
