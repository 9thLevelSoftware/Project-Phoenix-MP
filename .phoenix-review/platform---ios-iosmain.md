# Platform - iOS (iosMain) Review

Reviewed scope: 42 files under `shared/src/iosMain/kotlin/com/devil/phoenixproject`.

Build verification attempted: `./gradlew :shared:compileKotlinIosSimulatorArm64 --no-daemon` from repo root, but the host has no Java runtime installed (`Unable to locate a Java Runtime`). Findings below are from source review.

Issue count summary: 28 total findings — severity counts: 1 critical, 4 high, 16 medium, 7 low; category counts: 11 bug, 4 error, 12 failure-point, 1 stub.

## Findings

### 1. Unterminated log strings break the iOS build
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/presentation/components/BlePermissionHandler.ios.kt`
- Line number(s): 48, 53, 58, 63, 68
- Category: error
- Severity: critical
- Description: Several `log.d` calls contain unterminated string literals such as `log.d { "Bluetooth authorization: *** }`. This is a Kotlin syntax error and prevents the iOS source set from compiling.
- Suggested fix direction: Restore valid quoted log messages or remove these debug statements; then run an iOS Kotlin compile task to verify the source set parses.

### 2. Haptic cleanup can deactivate the shared recording audio session
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/presentation/components/HapticFeedbackEffect.ios.kt`
- Line number(s): 410-415
- Category: bug
- Severity: high
- Description: `IosSoundManager.release()` always calls `AVAudioSession.sharedInstance().setActive(false, ...)`. `AVAudioSession` is process-wide; if `SafeWordListener` is actively using `.playAndRecord`, disposing/recomposing the haptic effect can deactivate the microphone session and break emergency safe-word listening.
- Suggested fix direction: Track whether this manager activated the session and only deactivate when safe, or avoid deactivating the process-wide session from this component. Coordinate audio-session ownership with `SafeWordListener`.

### 3. Video player marks media ready before AVPlayerItem is actually ready
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/presentation/components/VideoPlayer.ios.kt`
- Line number(s): 221-269
- Category: failure-point
- Severity: high
- Description: `LoopingPlayerView.load()` calls `avPlayer.play()` and immediately invokes `onReady()`. The only failure observer is `AVPlayerItemFailedToPlayToEndTimeNotification`, which does not reliably cover initial load failures, bad network URLs, unsupported formats, or stalled assets. The UI can hide the loader and show a blank player forever.
- Suggested fix direction: Observe `AVPlayerItem.status` / `AVPlayer.status` (KVO or equivalent Kotlin/Native binding), surface `.failed` errors, and call `onReady()` only after the item is ready to play.

### 4. Video player reloads and mutates Compose state on every update pass
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/presentation/components/VideoPlayer.ios.kt`
- Line number(s): 88-104
- Category: bug
- Severity: medium
- Description: The `UIKitView.update` block resets `isLoading`, clears errors, and calls `view.load()` on every Compose update even when `videoUrl` has not changed. Because the callbacks also write Compose state, unrelated recompositions can cause loading flicker and repeated player starts.
- Suggested fix direction: Keep the last loaded URL in Compose state or rely on the UIView guard before resetting UI state; only reload/reset when the URL changes.

### 5. Number picker can crash when the generated value list is empty
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/presentation/components/CompactNumberPicker.ios.kt`
- Line number(s): 152-179, 383-389
- Category: bug
- Severity: medium
- Description: Invalid inputs (`step <= 0f` or reversed range) produce `values = emptyList()`, but the scroll-settle effect later calls `centeredVisibleIndex.coerceIn(values.indices)` when `wasUserDragging` is true. `values.indices` is an empty range, so `coerceIn` can throw `IllegalArgumentException`.
- Suggested fix direction: Guard the scroll-settle path with `values.isNotEmpty()` before coercing or indexing, and disable wheel interaction entirely when no values are available.

### 6. Safe-word matching misses punctuation-attached words
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/domain/voice/SafeWordListener.ios.kt`
- Line number(s): 345-349
- Category: bug
- Severity: high
- Description: `matchesSafeWord()` splits only on whitespace and compares tokens exactly. Speech transcripts such as `"stop!"`, `"stop."`, or `"stop, now"` will not match a safe word of `stop`, which is a safety-critical false negative.
- Suggested fix direction: Normalize recognized text by stripping punctuation or tokenizing on non-letter/digit boundaries before comparison. Add tests for punctuation and casing.

### 7. Safe-word listener can enter an unbounded restart loop on non-retryable speech errors
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/domain/voice/SafeWordListener.ios.kt`
- Line number(s): 225, 337-341, 399-409
- Category: failure-point
- Severity: medium
- Description: Recognition is forced to on-device mode (`requiresOnDeviceRecognition = true`), and any recognition error schedules a restart after 500 ms. Devices/locales without on-device recognition support, denied runtime conditions, or persistent audio-session errors can loop indefinitely, consuming battery and log volume while never recovering.
- Suggested fix direction: Classify non-retryable `NSError` codes, add exponential backoff/rate limiting, and fall back or stop with a visible error when on-device recognition is unsupported.

### 8. Health onboarding does not request body-mass read permission
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/presentation/components/OptionalPermissionsHandler.ios.kt`
- Line number(s): 80-106
- Category: bug
- Severity: medium
- Description: The optional onboarding requests HealthKit share/write types only (`readTypes = null`). The iOS `HealthIntegration` later supports reading latest scale body weight and requests body-mass read permission in its own permission path. Users who complete onboarding may still lack the read permission needed for scale import.
- Suggested fix direction: Include the body-mass quantity type in `readTypes`, or route onboarding through `HealthIntegration.requestPermissions()` so the permission set stays centralized.

### 9. Body-weight read permission check reports true without verifying read access
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/data/integration/HealthIntegration.ios.kt`
- Line number(s): 119-123
- Category: failure-point
- Severity: medium
- Description: `hasBodyWeightReadPermission()` returns `true` whenever HealthKit and the body-mass type are available. HealthKit does not expose direct read authorization status, but this implementation can mislead UI/business logic into treating permission as granted even when the user denied read access.
- Suggested fix direction: Rename/reshape the API to represent “read capability unknown/available”, or perform a lightweight query and treat authorization errors as not granted.

### 10. HealthKit continuations can resume after cancellation
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/data/integration/HealthIntegration.ios.kt`
- Line number(s): 133-168, 190-203, 309-331
- Category: failure-point
- Severity: low
- Description: HealthKit callbacks resume `suspendCancellableCoroutine` continuations without checking whether the coroutine is still active. Cancellation handlers stop queries in one path, but callbacks can still arrive afterward; a late callback risks duplicate/late resume behavior.
- Suggested fix direction: Check `continuation.isActive` before resuming and make callback paths idempotent.

### 11. OAuth presentation falls back to a detached UIWindow
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/data/auth/OAuth.ios.kt`
- Line number(s): 135-138
- Category: failure-point
- Severity: medium
- Description: If no connected `UIWindowScene.keyWindow` is found, `presentationAnchorForWebAuthenticationSession()` returns a new unattached `UIWindow()`. `ASWebAuthenticationSession` expects a real presentation anchor; a detached window can fail to present or present incorrectly.
- Suggested fix direction: Locate the active foreground scene/window more carefully and fail the launch with a clear error when no real anchor exists instead of returning a dummy window.

### 12. OAuth random byte generator crashes for zero-length requests
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/data/auth/OAuth.ios.kt`
- Line number(s): 31-36
- Category: failure-point
- Severity: low
- Description: `generateSecureRandomBytes(0)` creates an empty `ByteArray` and then calls `pinned.addressOf(0)`, which is invalid for an empty array. Current callers may request positive sizes, but the helper itself is not boundary-safe.
- Suggested fix direction: Return `ByteArray(0)` immediately when `size == 0`, and reject negative sizes explicitly.

### 13. Keychain migration can overwrite newer secure credentials with stale legacy values
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/di/PlatformModule.ios.kt`
- Line number(s): 140-168
- Category: bug
- Severity: medium
- Description: `migrateTokensToKeychain()` copies every legacy `NSUserDefaults` portal key into Keychain without checking whether Keychain already contains a value. If the app has already authenticated into Keychain but stale legacy keys remain, this migration can replace valid tokens/user data with old values and then remove the legacy copy.
- Suggested fix direction: Only migrate keys that are absent from Keychain, or compare freshness for token/timestamp fields before overwriting.

### 14. Backup writer silently ignores file creation and write failures
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/BackupJsonWriter.ios.kt`
- Line number(s): 11-30
- Category: error
- Severity: high
- Description: `open()` ignores failures from directory creation, file creation, and `fileHandleForWritingAtPath`; `write()` then silently returns if UTF-8 conversion or `fileHandle` is null. Backup export code can believe it wrote a backup while the file is missing or incomplete.
- Suggested fix direction: Capture Foundation errors, throw/return failure when the directory/file/handle cannot be created, and fail writes when no handle is open.

### 15. Custom backup resolver allows path traversal through file names
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/BackupDestinationResolver.ios.kt`
- Line number(s): 119-144
- Category: failure-point
- Severity: medium
- Description: `writeFile()` builds `destPath` by concatenating the user-selected directory path with `fileName`. If any caller passes a name containing `/` or `..`, the write can escape the intended backup directory.
- Suggested fix direction: Normalize `fileName` to a basename, reject path separators and traversal segments, or use URL path-component APIs.

### 16. Backup resolver fallback misinterprets stored file URLs as paths
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/BackupDestinationResolver.ios.kt`
- Line number(s): 46-51
- Category: failure-point
- Severity: low
- Description: When bookmark data is missing, the fallback calls `NSURL.fileURLWithPath(destination.uri)`. `BackupLocationPicker` stores `url.absoluteString` when available (for example `file:///...`), so fallback recovery can create a literal path containing `file://` instead of the intended filesystem path.
- Suggested fix direction: Parse `destination.uri` as an `NSURL` when it is a URL string, and only use `fileURLWithPath` for plain absolute paths.

### 17. Session custom backup path resolves bookmarks without security-scope option
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.ios.kt`
- Line number(s): 152-200
- Category: bug
- Severity: medium
- Description: `writeSessionBackupFile()` duplicates custom-destination bookmark handling instead of using `BackupDestinationResolver`, and resolves bookmark data with `NSURLBookmarkResolutionWithoutUI` only. It then calls `startAccessingSecurityScopedResource()` on a URL that may not have been resolved with security-scope access, causing custom session backups to fail and fall back unnecessarily.
- Suggested fix direction: Reuse `destinationResolver.writeFile()` or include `NSURLBookmarkResolutionWithSecurityScope` consistently in this path.

### 18. Session backup temp file name is shared across all exports
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.ios.kt`
- Line number(s): 156-162
- Category: bug
- Severity: medium
- Description: Custom session backup writes use a fixed temp path, `${NSTemporaryDirectory()}session_backup_temp.json`. Concurrent or overlapping exports can overwrite each other's temp file, producing corrupted or mismatched backups.
- Suggested fix direction: Use a unique temp file name per export (timestamp/UUID) and clean it in a `finally` block.

### 19. Streaming backup source treats stream errors as EOF
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/BackupStreamSource.ios.kt`
- Line number(s): 35-38, 80-106
- Category: error
- Severity: medium
- Description: `open()` does not verify that the `NSInputStream` opened successfully, and `refill()` treats every `bytesRead <= 0` as end-of-file. `NSInputStream.read()` returns negative values on errors, so missing/denied/corrupt files can be imported as empty or truncated data without surfacing the real I/O failure.
- Suggested fix direction: Check `streamError`/status after open and after negative reads, and throw/report errors instead of marking EOF.

### 20. CSV exporter does not escape CSV fields correctly
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/CsvExporter.ios.kt`
- Line number(s): 37-45, 61-83, 102-125
- Category: bug
- Severity: medium
- Description: CSV rows are built by string interpolation. Exercise names are quoted but embedded quotes are not doubled, and other fields such as localized formatted weights/progression can contain commas but are not quoted. This can generate malformed CSV or shifted columns, especially in comma-decimal locales.
- Suggested fix direction: Use a small CSV escaping helper for every field (`"` -> `""`, quote fields containing comma/quote/newline) or a shared CSV writer.

### 21. CSV exporter returns success even when writing fails
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/CsvExporter.ios.kt`
- Line number(s): 173-191
- Category: error
- Severity: medium
- Description: `writeToTempFile()` ignores the Boolean returned by `NSString.writeToFile(...)` and passes `error = null`, then returns the path unconditionally. Export APIs can return `Result.success(path)` for a file that was not written.
- Suggested fix direction: Capture the return value and `NSError`, and throw/return failure when the write fails.

### 22. CSV importer undercounts save failures
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/CsvImporter.ios.kt`
- Line number(s): 67-89
- Category: bug
- Severity: medium
- Description: Non-unique exceptions from `workoutRepository.saveSession(session)` are appended to `importErrors`, but the returned `failed` count is always `parseErrors.size`. The result can contain save error messages while reporting zero additional failures.
- Suggested fix direction: Track save failures separately and set `failed = parseErrors.size + saveFailureCount`.

### 23. Health permission settings launcher is a no-op stub
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/HealthPermissionSettingsLauncher.ios.kt`
- Line number(s): 6-9
- Category: stub
- Severity: medium
- Description: `openSettings()` does nothing. After a user denies HealthKit permissions, the app may direct them to permission settings, but this implementation gives no path to Health/Settings and no feedback.
- Suggested fix direction: Open the app settings page if that is the best available iOS fallback, or show explicit user guidance for changing Health permissions in the Health app / Settings.

### 24. DeviceInfo JSON is manually assembled without escaping
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/DeviceInfo.ios.kt`
- Line number(s): 72-83
- Category: bug
- Severity: medium
- Description: `toJson()` interpolates bundle/device strings directly into JSON. Device names and bundle values can contain quotes, backslashes, or newlines, producing invalid JSON.
- Suggested fix direction: Use kotlinx.serialization or at least a shared JSON string escaping helper for all string values.

### 25. Connectivity checker reports online before the first NWPath update
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/ConnectivityChecker.ios.kt`
- Line number(s): 26-40
- Category: failure-point
- Severity: low
- Description: `isConnected` starts as `true` and is only corrected asynchronously by `NWPathMonitor`. Calls to `isOnline()` during startup can incorrectly allow network work while the device is offline.
- Suggested fix direction: Initialize as unknown/offline until the first callback, or expose an observable state with an initial pending value.

### 26. App settings opener ignores failure from the iOS open call
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/AppSettingsOpener.ios.kt`
- Line number(s): 7-9
- Category: failure-point
- Severity: low
- Description: `openAppSettings()` uses `UIApplication.openURL(url)` and ignores whether the URL can be opened or whether opening succeeds. Permission recovery UI cannot know if the action failed.
- Suggested fix direction: Use the modern `openURL:options:completionHandler:` API (or available Kotlin/Native equivalent), validate the URL, and log/surface failures.

### 27. Screen idle-timer API is not confined to the main thread
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/ScreenUtils.ios.kt`
- Line number(s): 5-6
- Category: failure-point
- Severity: low
- Description: `UIApplication.sharedApplication.setIdleTimerDisabled(enabled)` is a UIKit application mutation. If called from a background coroutine/thread, it can violate UIKit main-thread expectations.
- Suggested fix direction: Dispatch this call to the main queue or require callers to invoke it from the main thread.

### 28. Global iOS lock intentionally serializes unrelated lock objects
- File path: `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/Locking.ios.kt`
- Line number(s): 5-24
- Category: failure-point
- Severity: low
- Description: `withPlatformLock(lock, block)` ignores the supplied `lock` object and uses one global `NSRecursiveLock` for all callers. The comment documents this as a beta trade-off, but it can create unnecessary contention or hidden deadlock coupling between unrelated subsystems as more callers are added.
- Suggested fix direction: Replace with per-lock storage once Kotlin/Native weak-reference behavior allows it, or audit all callers and narrow the global lock's usage.

## Files reviewed with no additional issues found

- `shared/src/iosMain/kotlin/com/devil/phoenixproject/IosAppHost.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/MainViewController.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/Platform.ios.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/data/ble/BleExtensions.ios.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/data/local/DriverFactory.ios.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/di/KoinInitIos.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/domain/model/CurrentLanguage.ios.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/domain/model/PlatformUtils.ios.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/domain/model/UUIDGeneration.ios.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/domain/voice/IosSafeWordListenerFactory.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/presentation/components/BackHandler.ios.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/presentation/util/PlatformAccessibilitySettings.ios.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/presentation/util/PlatformInputMode.ios.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/ui/theme/DynamicColor.ios.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/ui/theme/Theme.ios.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/BackupLocationPicker.ios.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/ClipboardUtils.ios.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/FilePicker.ios.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/HealthPermissionRequester.ios.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/LocaleHelper.ios.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/UriContentReader.ios.kt`
