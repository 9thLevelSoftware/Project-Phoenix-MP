# Platform - Android (androidMain) review

Reviewed Android platform-specific files assigned for `shared/src/androidMain`. The task's file list is partially stale: 37 listed files exist at the listed paths, while 8 listed files were not present in `shared/src/androidMain`. I also checked the actual nearby Android replacements where obvious (`OAuth.android.kt`, `AndroidWorkoutServiceController.android.kt`, `AndroidAppHost.kt`) for scope drift.

## Scope discrepancies

- Missing from the assigned path list:
  - `shared/src/androidMain/kotlin/com/devil/phoenixproject/Greeting.kt`
  - `shared/src/androidMain/kotlin/com/devil/phoenixproject/App.kt`
  - `shared/src/androidMain/kotlin/com/devil/phoenixproject/data/auth/OAuthRedirectReceiver.kt`
  - `shared/src/androidMain/kotlin/com/devil/phoenixproject/data/local/LegacySchemaReconciliation.kt`
  - `shared/src/androidMain/kotlin/com/devil/phoenixproject/di/KoinInitAndroid.kt`
  - `shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/manager/AndroidWorkoutAudioPlayer.kt`
  - `shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/screen/HealthPermissionsScreen.android.kt`
  - `shared/src/androidMain/kotlin/com/devil/phoenixproject/ui/theme/Theme.android.kt`
- Additional actual androidMain files found but not in the assigned list include `AndroidAppHost.kt`, `data/auth/OAuth.android.kt`, `presentation/manager/AndroidWorkoutServiceController.android.kt`, and `presentation/screen/WorkoutTabPreviews.kt`.

## Findings

### 1. Stale/missing assigned Android source files
- File path: multiple assigned paths listed above
- Line number(s): N/A
- Category: failure-point
- Severity: medium
- Description: Eight files in the review assignment do not exist at their assigned `shared/src/androidMain` paths. Some appear to have been moved or renamed (`OAuth.android.kt` exists instead of `OAuthRedirectReceiver.kt`; `AndroidWorkoutServiceController.android.kt` exists instead of `AndroidWorkoutAudioPlayer.kt`), while others appear to live in common or app modules. This makes the platform review list unreliable and can hide unreviewed Android behavior.
- Suggested fix direction: Refresh the androidMain review manifest from the repository tree and map renamed/moved files explicitly so future review tasks are generated from actual source paths.

### 2. Encrypted preference migration can remove plaintext before encrypted data is durably written
- File path: `shared/src/androidMain/kotlin/com/devil/phoenixproject/di/PlatformModule.android.kt`
- Line number(s): 159-183
- Category: failure-point
- Severity: high
- Description: `migrateTokensToEncrypted` writes tokens to encrypted preferences with `editor.apply()` and then immediately removes the plaintext keys with another asynchronous `apply()`. Because `apply()` does not report write failure and is asynchronous, a process death or storage failure between the two operations can lose auth tokens permanently.
- Suggested fix direction: Use synchronous `commit()` for the encrypted write, verify success, then remove plaintext keys only after the encrypted store is confirmed. Consider leaving plaintext intact and retrying if encrypted persistence fails.

### 3. `callbackScheme` is ignored during Android OAuth launch
- File path: `shared/src/androidMain/kotlin/com/devil/phoenixproject/data/auth/OAuth.android.kt`
- Line number(s): 44-87, 100-118
- Category: failure-point
- Severity: medium
- Description: `OAuthLauncher.launch(authorizeUrl, callbackScheme)` accepts `callbackScheme` but never uses or validates it. A caller can pass the wrong scheme and the launcher will still open the browser and wait on the global bridge, making OAuth configuration mistakes hard to detect and increasing reliance on manifest-only routing.
- Suggested fix direction: Validate that `authorizeUrl` is configured for the expected callback scheme, or use the scheme to scope/verify callbacks delivered by `AndroidOAuthBridge` before completing the pending flow.

### 4. BLE permission result treats partial/missing result maps as fully granted
- File path: `shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/components/BlePermissionHandler.android.kt`
- Line number(s): 106-114, 126-128, 163-165
- Category: bug
- Severity: medium
- Description: The permission callback calculates `allGranted` with `permissions.values.all { it }`. If Android returns a map that omits one of the requested permissions, `all` can succeed for the subset and mark BLE as granted even though a required permission remains denied. This is especially risky because the requested set varies by SDK version.
- Suggested fix direction: Re-check `BlePermissions.getRequiredPermissions().all { ContextCompat.checkSelfPermission(...) == GRANTED }` in the callback, or require every requested permission key to be present and `true`.

### 5. Notification permission blocks BLE access even though it is not required for Bluetooth
- File path: `shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/components/BlePermissionHandler.android.kt`
- Line number(s): 33-56, 117-175
- Category: failure-point
- Severity: medium
- Description: `POST_NOTIFICATIONS` is included in `BlePermissions.getRequiredPermissions()` on Android 13+, and `RequireBlePermissions` blocks the whole app content unless it is granted. Users who deny notifications can be locked out of BLE scanning/connection even though Bluetooth itself only needs the Bluetooth permissions.
- Suggested fix direction: Separate hard BLE prerequisites from optional/workout-notification permission prompts. Gate BLE UI only on Bluetooth/location requirements and handle notifications as a non-blocking capability.

### 6. Compact number picker can crash or hang on invalid step/range
- File path: `shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/components/CompactNumberPicker.android.kt`
- Line number(s): 36-45, 80-82, 103-107, 222-224
- Category: bug
- Severity: high
- Description: The value list is generated with `while (current <= range.endInclusive) { current += step }` without validating `step`. A zero or negative step can produce an infinite loop during composition. An empty/invalid range produces `values.size - 1 == -1`, which is passed to `NumberPicker.maxValue` and can also make `coerceIn(values.indices)` operate on an empty range.
- Suggested fix direction: Require `step > 0`, handle empty ranges before creating `NumberPicker`, and disable controls or render a fallback state when no selectable values exist.

### 7. Compact number picker does not update displayed values when range/step/suffix changes
- File path: `shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/components/CompactNumberPicker.android.kt`
- Line number(s): 101-115, 165-214
- Category: bug
- Severity: medium
- Description: `displayedValues`, `minValue`, and `maxValue` are configured only in the `AndroidView` factory. The `update` block changes only `picker.value` and colors. If the range, step, or suffix changes after initial composition, the native `NumberPicker` can show stale labels and retain old bounds while `onValueChange` indexes into the new `values` list.
- Suggested fix direction: In `update`, clear/update `displayedValues`, min/max, wrap mode, and selected value whenever the computed values or suffix change. Key the AndroidView if a full native picker rebuild is safer.

### 8. Optional permission onboarding never detects pre-granted Health Connect permissions
- File path: `shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/components/OptionalPermissionsHandler.android.kt`
- Line number(s): 66-83
- Category: bug
- Severity: medium
- Description: `allAlreadyGranted` uses `micGranted && (!healthAvailable || false)`, which is always false when Health Connect is available. The comment says health permissions are checked asynchronously, but no async check happens before deciding to show onboarding. Users with permissions already granted can still be shown a redundant prompt on first launch.
- Suggested fix direction: Query Health Connect granted permissions before computing `allAlreadyGranted`, or remove the shortcut and let onboarding state be driven by an explicit permission-check state machine.

### 9. Android accessibility settings implementation is a hardcoded stub
- File path: `shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/util/PlatformAccessibilitySettings.android.kt`
- Line number(s): 6-7
- Category: stub
- Severity: medium
- Description: `rememberPlatformAccessibilitySettings()` always returns `PlatformAccessibilitySettings(boldTextEnabled = false)`. Any UI that adapts for Android bold text/accessibility font settings will never respond to the user's platform setting.
- Suggested fix direction: Read the relevant Android accessibility/font settings via `Configuration`/`Settings` where available and expose a state that updates on configuration changes. If Android cannot support a specific flag, document that explicitly rather than returning a silent constant.

### 10. Safe-word matching misses punctuation-adjacent words
- File path: `shared/src/androidMain/kotlin/com/devil/phoenixproject/domain/voice/SafeWordListener.android.kt`
- Line number(s): 189-194
- Category: bug
- Severity: medium
- Description: `matchesSafeWord` splits recognized speech only on whitespace and compares tokens literally. Common recognizer output such as `stop!`, `stop,`, or `"stop"` will not match a safe word of `stop`, creating an unreliable emergency-stop trigger.
- Suggested fix direction: Normalize recognized text with word-boundary matching or strip punctuation around tokens before comparison. Add tests for punctuation, casing, and multi-word transcripts.

### 11. MediaStore backup writes can report success when no bytes were written
- File path: `shared/src/androidMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.android.kt`
- Line number(s): 109-113, 279-288, 327-335
- Category: bug
- Severity: high
- Description: Several MediaStore save paths use `resolver.openOutputStream(uri)?.use { ... }` and then continue as success even when `openOutputStream` returns null. This can leave an empty MediaStore row and return a successful URI/path despite the backup not being written.
- Suggested fix direction: Treat a null output stream as an error. Throw or return `Result.failure` before deleting any temp file or reporting success.

### 12. Custom session backup path can report success when destination stream is null
- File path: `shared/src/androidMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.android.kt`
- Line number(s): 72-91
- Category: bug
- Severity: high
- Description: The custom-destination branch creates a temp file, creates a `DocumentFile`, then uses `openOutputStream(newFile.uri)?.use { ... }`. If the output stream is null, execution still deletes the temp file, logs success, and returns without writing backup contents.
- Suggested fix direction: Mirror `AndroidBackupDestinationResolver.writeFile` behavior: fail when `openOutputStream` is null, do not delete the temp file until copy succeeds, and fall back to default storage if the custom write cannot be opened.

### 13. Session backup temp file name is shared across all exports
- File path: `shared/src/androidMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.android.kt`
- Line number(s): 72-77
- Category: failure-point
- Severity: medium
- Description: Custom session backup writes always use `File(context.cacheDir, "session_backup_temp.json")`. Concurrent or overlapping backup operations can overwrite each other's staging file, producing mixed or wrong backup contents.
- Suggested fix direction: Use a unique temp file per operation (`createTempFile`, UUID, or the destination file name) and delete it in a `finally` block after successful copy or fallback.

### 14. Backup pruning silently swallows delete failures
- File path: `shared/src/androidMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.android.kt`
- Line number(s): 174-178, 185-192
- Category: failure-point
- Severity: low
- Description: MediaStore delete failures are swallowed with an empty catch, and pre-Q `File.delete()` results are ignored. If pruning fails due to permissions or provider errors, retention can silently grow without diagnostics.
- Suggested fix direction: Log failed deletions with URI/file name and return/aggregate failure information if callers need to surface backup retention errors.

### 15. File saver reports success when `openOutputStream` returns null
- File path: `shared/src/androidMain/kotlin/com/devil/phoenixproject/util/FilePicker.android.kt`
- Line number(s): 43-55
- Category: bug
- Severity: medium
- Description: `LaunchFileSaver` calls `openOutputStream(uri)?.use { ... }` and then invokes `onSaved(uri.toString())` even if the output stream is null and no content was written.
- Suggested fix direction: Check the output stream explicitly and call `onSaved(null)` or surface an error if it cannot be opened.

### 16. CSV importer underreports failed rows from database save errors
- File path: `shared/src/androidMain/kotlin/com/devil/phoenixproject/util/CsvImporter.android.kt`
- Line number(s): 65-88
- Category: bug
- Severity: medium
- Description: Non-unique exceptions while saving parsed sessions are appended to `importErrors`, but the returned `failed` count is set only to `parseErrors.size`. A CSV can have multiple database save failures while reporting `failed = 0` if parsing succeeded.
- Suggested fix direction: Track save failure count separately and return `failed = parseErrors.size + saveFailures`. Keep `skipped` limited to intentional duplicate skips.

### 17. DeviceInfo JSON is built without escaping Android build strings
- File path: `shared/src/androidMain/kotlin/com/devil/phoenixproject/util/DeviceInfo.android.kt`
- Line number(s): 83-95
- Category: bug
- Severity: medium
- Description: `toJson()` interpolates `Build.MANUFACTURER`, `Build.MODEL`, `Build.DEVICE`, `Build.VERSION.RELEASE`, and `Build.FINGERPRINT` directly into JSON string literals. OEM build strings are not guaranteed to exclude quotes, backslashes, or control characters, so diagnostics JSON can become invalid.
- Suggested fix direction: Use a JSON encoder or shared escaping helper instead of manual string interpolation.

### 18. Video player logs and displays full/partial video URLs
- File path: `shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/components/VideoPlayer.android.kt`
- Line number(s): 54-55, 102-105, 213-224
- Category: failure-point
- Severity: low
- Description: The player logs the full `videoUrl` on composition and media-item updates, and displays the first 50 characters of the URL in the error UI. If exercise videos ever use signed CDN URLs or URLs containing tokens/query identifiers, those values can leak into logs or screenshots.
- Suggested fix direction: Redact query strings/tokens in logs and error UI, or log only a stable content identifier/host.

### 19. DriverFactory diagnostic cursors are not closed on mid-query exceptions
- File path: `shared/src/androidMain/kotlin/com/devil/phoenixproject/data/local/DriverFactory.android.kt`
- Line number(s): 33-52
- Category: failure-point
- Severity: low
- Description: Diagnostic `Cursor` instances are manually closed after reads. If `moveToFirst`, `moveToNext`, or column access throws, the cursor can leak because the close calls are not in `use`/`finally` blocks.
- Suggested fix direction: Wrap each query cursor in `use { ... }` so resources are closed on all paths.

### 20. Health Connect workout inserts are not idempotent for repeated exports
- File path: `shared/src/androidMain/kotlin/com/devil/phoenixproject/data/integration/HealthIntegration.android.kt`
- Line number(s): 202-245
- Category: failure-point
- Severity: medium
- Description: Workout and calorie records are inserted with stable client record IDs derived from `data.externalId`, but the code always calls `insertRecords(records)` and does not handle duplicate-client-record failures as an idempotent success or update. Retrying an export after a partial success can fail instead of confirming the existing Health Connect records.
- Suggested fix direction: Treat duplicate client-record IDs as idempotent success when record contents match, or delete/update existing records before insert. Ensure workout and optional calorie record retries are handled independently.
