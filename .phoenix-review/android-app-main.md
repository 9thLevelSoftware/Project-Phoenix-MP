# Android App Main Review

Scope reviewed:
- `androidApp/src/main/kotlin/com/devil/phoenixproject/MainActivity.kt`
- `androidApp/src/main/kotlin/com/devil/phoenixproject/VitruvianApp.kt`
- `androidApp/src/main/kotlin/com/devil/phoenixproject/auth/OAuthRedirectActivity.kt`
- `androidApp/src/main/kotlin/com/devil/phoenixproject/service/WorkoutForegroundService.kt`
- `androidApp/src/main/kotlin/com/devil/phoenixproject/ui/theme/AndroidTheme.kt`

## Summary

Findings: 10

Severity breakdown:
- Critical: 0
- High: 1
- Medium: 6
- Low: 3

Category breakdown:
- Bug: 2
- Stub: 1
- Error: 1
- Failure-point: 6

## `androidApp/src/main/kotlin/com/devil/phoenixproject/MainActivity.kt`

### Finding 1
- Category: bug
- Severity: medium
- Line numbers: 43-56
- Description: The cold-start locale pre-application explicitly skips `langCode == "en"`. The settings flow treats `"en"` as a real selectable language, not as "system default". On API 26-32, a user who selected English on a non-English device can cold-start into the device locale for the first frame because the stored English preference is ignored here. This also creates inconsistent semantics with `LocaleHelper.applyAppLocale()`, which applies any non-blank language code including `"en"`.
- Suggested fix direction: Treat every non-blank stored BCP-47 tag as an explicit app locale, including `"en"`. If the product needs a system-default option, persist a distinct blank/system value and handle that separately.

### Finding 2
- Category: failure-point
- Severity: low
- Line numbers: 50-55
- Description: The pre-API-33 locale branch mutates `resources.configuration` directly and updates only this activity's resources. This can leave application-context resources and other context wrappers temporarily out of sync during startup, especially because Koin singletons and shared code can resolve resources outside the activity.
- Suggested fix direction: Clone the configuration before mutation (`Configuration(resources.configuration)`) and centralize locale application so both activity and application/base contexts use the same locale source. Prefer the AndroidX/AppCompat per-app-locale path if available for API 26-32.

## `androidApp/src/main/kotlin/com/devil/phoenixproject/VitruvianApp.kt`

No findings in the reviewed file.

## `androidApp/src/main/kotlin/com/devil/phoenixproject/auth/OAuthRedirectActivity.kt`

### Finding 3
- Category: failure-point
- Severity: medium
- Line numbers: 45-57
- Description: The exported redirect activity forwards any non-null intent URI to `AndroidOAuthBridge.deliverCallback()` without validating that the URI has the expected scheme/host or that an OAuth flow is actually pending. The manifest filter handles normal browser redirects, but an explicit intent from another local app can still target this exported activity with arbitrary data. During a pending OAuth flow, that can complete the bridge with an unexpected URI and make the auth layer fail or consume the wrong callback.
- Suggested fix direction: Validate `data.scheme`, `data.host`, and any expected path before delivery; have the bridge expose/return whether a flow was pending; and rely on downstream state/PKCE validation as defense in depth. Log and cancel/ignore unexpected callback URIs instead of delivering them as successes.

### Finding 4
- Category: failure-point
- Severity: low
- Line numbers: 83-99
- Description: `routeBackToApp()` runs after every handled intent, including missing-data cancellation and arbitrary explicit launches. This lets any caller foreground `MainActivity` through the exported redirect activity even when no OAuth flow is active.
- Suggested fix direction: Route back only when a valid pending OAuth flow was completed or cancelled by this activity. If no flow is pending or the URI is invalid, finish silently after logging.

## `androidApp/src/main/kotlin/com/devil/phoenixproject/service/WorkoutForegroundService.kt`

### Finding 5
- Category: error
- Severity: high
- Line numbers: 91-101
- Description: `startWorkoutForeground()` calls `startForeground()` without catching runtime failures. On modern Android, foreground-service startup can throw `SecurityException` or service-type related runtime exceptions if `POST_NOTIFICATIONS`, Bluetooth/connected-device prerequisites, or manifest/runtime state are not what the service expects. Because this exception occurs inside the service process, the controller's `try/catch` around `startForegroundService()` will not catch it, so the app can crash during a workout-service sync.
- Suggested fix direction: Wrap `startForeground()` in a defensive `try/catch`, log the failure, reset `isForegroundActive`, and stop the service with `stopSelf()` if foreground startup fails. Also surface the failure to the workout controller/UI so it can degrade gracefully.

### Finding 6
- Category: failure-point
- Severity: medium
- Line numbers: 62-83
- Description: Unknown or missing non-null actions fall through the `when` without starting foreground mode or stopping the service. If the service is launched via `startForegroundService()` with a malformed action, Android can kill/crash it for not calling `startForeground()` in time; if launched with `startService()`, the service can remain started but idle.
- Suggested fix direction: Add an `else` branch that logs the unexpected action, calls `stopSelf(startId)`, and returns `START_NOT_STICKY`. Consider validating required extras before entering foreground mode.

### Finding 7
- Category: bug
- Severity: medium
- Line numbers: 217-235
- Description: `toNotificationState(previous)` only preserves previous `phase` and `workoutMode`; all nullable display fields are replaced with null whenever an extra is missing or encoded as the sentinel. The function signature suggests a merge with previous state, but partial sync intents would erase exercise, set, rep, or timer details from the notification.
- Suggested fix direction: Either require and validate that every sync intent contains a complete snapshot, or preserve previous nullable fields when an extra is absent. Use explicit sentinel handling only for fields that are intentionally cleared.

### Finding 8
- Category: failure-point
- Severity: medium
- Line numbers: 209-214
- Description: The notification `PendingIntent` launches `MainActivity` with only `FLAG_ACTIVITY_SINGLE_TOP`. If `MainActivity` exists in the task but is not the current top activity, tapping the workout notification can create another `MainActivity` instance rather than returning to the existing task/state.
- Suggested fix direction: Build the notification intent like a launcher/return-to-task intent (`ACTION_MAIN`, `CATEGORY_LAUNCHER`) and use an appropriate task-restoration flag such as `FLAG_ACTIVITY_CLEAR_TOP` or `FLAG_ACTIVITY_REORDER_TO_FRONT` with `SINGLE_TOP`, depending on the desired back-stack behavior.

## `androidApp/src/main/kotlin/com/devil/phoenixproject/ui/theme/AndroidTheme.kt`

### Finding 9
- Category: failure-point
- Severity: medium
- Line numbers: 31-36
- Description: The status-bar side effect assumes `view.context as Activity`. Compose views can be hosted by a `ContextWrapper`/themed context in tests, previews, dialogs, or future reuse sites, which would throw `ClassCastException` during composition.
- Suggested fix direction: Resolve the activity with a safe context-unwrapping helper (`Context.findActivity()` style), skip the side effect if no activity is available, and avoid unsafe casts in composables.

### Finding 10
- Category: stub
- Severity: low
- Line numbers: 17-28
- Description: This Android theme wrapper is deprecated because it collapses `ThemeMode.SYSTEM` into a concrete dark/light boolean before delegating to the shared theme. Although current app entry appears to use the shared `AppContent` path, keeping this wrapper callable leaves a known footgun for future Android UI code.
- Suggested fix direction: Remove the deprecated wrapper once callers are migrated, or replace it with an overload that accepts and forwards `ThemeMode` directly so system-theme semantics are preserved.
