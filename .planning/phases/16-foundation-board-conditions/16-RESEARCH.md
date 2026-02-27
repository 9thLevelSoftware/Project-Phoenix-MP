# Phase 16: Foundation & Board Conditions - Research

**Researched:** 2026-02-27
**Domain:** Android manifest configuration, KMP time handling, FeatureGate extension, MediaPipe error handling, permission UX
**Confidence:** HIGH

## Summary

Phase 16 addresses seven Board of Directors conditions spanning versioning, timezone correctness, backup security, camera permission UX, iOS feature suppression, asset error handling, and premium feature gating. These are independent, well-scoped fixes touching different parts of the codebase with minimal interaction between them.

The codebase is mature with established patterns for each area: `FeatureGate` already has a clean enum/set architecture that just needs new entries, `SmartSuggestionsEngine.classifyTimeWindow()` has a clear UTC bug that needs a local-time fix using the already-imported `kotlinx-datetime` library, Android backup exclusion requires two new XML files plus manifest attributes, and `PoseLandmarkerHelper.setupPoseLandmarker()` needs a try-catch with user-facing error propagation.

**Primary recommendation:** Treat each BOARD requirement as a largely independent task. The only ordering dependency is that BOARD-09 (FeatureGate entries) should be completed first since later phases depend on those feature enum values existing.

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| BOARD-01 | SmartSuggestions classifyTimeWindow() uses local time instead of UTC | See "UTC Time Bug Fix" pattern -- kotlinx-datetime already in project, `TimeZone.currentSystemDefault()` pattern used elsewhere in KmpUtils |
| BOARD-03 | Android backup exclusion for VitruvianDatabase and sensitive preferences | See "Android Backup Exclusion" pattern -- two XML files needed, manifest attributes for both API 30 and 31+ |
| BOARD-05 | Camera permission dialog shows custom rationale text | See "Camera Permission Rationale" pattern -- existing `BlePermissionHandler` provides the established project pattern |
| BOARD-06 | iOS PHOENIX tier upgrade prompts do not mention Form Check | See "iOS Form Check Suppression" pattern -- PaywallScreen features list needs platform-conditional filtering |
| BOARD-07 | versionName reflects actual app version (not hardcoded 0.4.0) | See "Version Number Fix" pattern -- three locations need updating: build.gradle.kts, Constants.kt, SettingsTab.kt |
| BOARD-08 | PoseLandmarkerHelper gracefully handles missing asset | See "PoseLandmarkerHelper Error Handling" pattern -- wrap setupPoseLandmarker in try-catch, propagate to listener |
| BOARD-09 | FeatureGate.Feature enum includes CV_FORM_CHECK, RPG_ATTRIBUTES, GHOST_RACING, READINESS_BRIEFING | See "FeatureGate Extension" pattern -- add to enum, assign to correct tier sets, update tests |
</phase_requirements>

## Standard Stack

### Core (Already in Project)
| Library | Version | Purpose | Relevant To |
|---------|---------|---------|-------------|
| kotlinx-datetime | (in libs.versions.toml) | KMP local time/timezone conversion | BOARD-01 UTC fix |
| multiplatform-settings | (in libs.versions.toml) | SharedPreferences abstraction | BOARD-03 backup exclusion (need to know pref file name) |
| MediaPipe Tasks Vision | (in shared module) | Pose landmark detection | BOARD-08 error handling |
| Compose Material3 | (via BOM) | UI components | BOARD-05 rationale dialog |
| ActivityResultContracts | (via activity-compose) | Permission handling | BOARD-05 camera permission |

### No New Libraries Required
This phase requires zero new dependencies. All changes use existing project libraries.

## Architecture Patterns

### Pattern 1: UTC Time Bug Fix (BOARD-01)

**What:** `SmartSuggestionsEngine.classifyTimeWindow()` extracts hour-of-day from a UTC epoch timestamp using modular arithmetic, which gives UTC hour, not local hour. A user in UTC+10 training at 6pm local (8am UTC) gets classified as MORNING instead of EVENING.

**Current bug (line 255-263 of SmartSuggestionsEngine.kt):**
```kotlin
private fun classifyTimeWindow(timestampMs: Long): TimeWindow {
    val hourOfDay = ((timestampMs % ONE_DAY_MS) / ONE_HOUR_MS).toInt()
    // BUG: This computes UTC hour, not local hour
    return when (hourOfDay) { ... }
}
```

**Fix:** Use `kotlinx-datetime` to convert to local time, following the pattern already used in `KmpUtils.formatTimestamp()` and `KmpLocalDate.fromTimestamp()`:
```kotlin
import kotlinx.datetime.*
import kotlin.time.Instant

// Change visibility from private to internal for testability
internal fun classifyTimeWindow(timestampMs: Long): TimeWindow {
    val instant = Instant.fromEpochMilliseconds(timestampMs)
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val hourOfDay = localDateTime.hour
    return when (hourOfDay) {
        in 5..6 -> TimeWindow.EARLY_MORNING
        in 7..9 -> TimeWindow.MORNING
        in 10..14 -> TimeWindow.AFTERNOON
        in 15..19 -> TimeWindow.EVENING
        else -> TimeWindow.NIGHT
    }
}
```

**Testing consideration:** The existing test uses `baseTime + N * ONE_HOUR_MS` which implicitly assumes UTC. Tests must be updated to account for local timezone. One approach: make `classifyTimeWindow` accept an optional `TimeZone` parameter (default `TimeZone.currentSystemDefault()`) for test injection. Alternatively, make it `internal` and test with known timezone offsets.

**Confidence:** HIGH -- the bug is clear, the fix pattern is established in the same project.

### Pattern 2: FeatureGate Extension (BOARD-09)

**What:** Add four new `Feature` enum entries with correct tier assignments.

**Current state:** FeatureGate has 12 features split between Phoenix (6) and Elite (6 + all Phoenix). The new features need tier assignments per the requirements:

| Feature | Tier | Rationale |
|---------|------|-----------|
| CV_FORM_CHECK | PHOENIX | Phoenix+ per CV-01 |
| RPG_ATTRIBUTES | PHOENIX | Phoenix+ per RPG-03 |
| GHOST_RACING | PHOENIX | Phoenix+ per GHOST-01 |
| READINESS_BRIEFING | ELITE | Elite per BRIEF-02 |

**Implementation:**
```kotlin
enum class Feature {
    // Existing Phoenix tier features
    FORCE_CURVES, PER_REP_METRICS, VBT_METRICS, PORTAL_SYNC,
    LED_BIOFEEDBACK, REP_QUALITY_SCORE,

    // New Phoenix tier features (v0.5.1)
    CV_FORM_CHECK,
    RPG_ATTRIBUTES,
    GHOST_RACING,

    // Existing Elite tier features
    ASYMMETRY_ANALYSIS, AUTO_REGULATION, SMART_SUGGESTIONS,
    WORKOUT_REPLAY, STRENGTH_ASSESSMENT, PORTAL_ADVANCED_ANALYTICS,

    // New Elite tier feature (v0.5.1)
    READINESS_BRIEFING
}

private val phoenixFeatures = setOf(
    // ... existing 6 ...
    Feature.CV_FORM_CHECK,
    Feature.RPG_ATTRIBUTES,
    Feature.GHOST_RACING
)
// eliteFeatures already includes all phoenixFeatures + elite-only
// Just add READINESS_BRIEFING to the elite-only set
```

**Test updates required:**
- `FeatureGateTest` iterates `Feature.entries` -- existing `FREE tier has no premium features` test auto-covers new entries
- `ELITE tier has all features enabled` also auto-covers via `entries`
- `PHOENIX tier does not have elite-only features` needs READINESS_BRIEFING added to eliteOnlyFeatures list
- Add explicit test: `PHOENIX tier has new v0.5.1 phoenix features`
- Add explicit test: `READINESS_BRIEFING is elite-only`

**Confidence:** HIGH -- straightforward enum extension with established pattern.

### Pattern 3: Version Number Fix (BOARD-07)

**What:** Three locations contain hardcoded "0.4.0" that must all be updated to "0.5.1":

1. **`androidApp/build.gradle.kts` line 17:** `versionName = "0.4.0"` -- the canonical Android versionName
2. **`shared/.../util/Constants.kt` line 8:** `const val APP_VERSION = "0.4.0"` -- used by DeviceInfo.appVersionName on both platforms
3. **`shared/.../presentation/screen/SettingsTab.kt` line 1381:** `Text("Version: 0.4.0", ...)` -- hardcoded UI text

**Better approach for SettingsTab:** Instead of hardcoding, reference `DeviceInfo.appVersionName`:
```kotlin
Text("Version: ${DeviceInfo.appVersionName}", color = MaterialTheme.colorScheme.onSurface)
```

This ensures the Settings screen always reflects the actual version from Constants. The DeviceInfo expect/actual already exists and is accessible from commonMain.

**Also update versionCode:** Currently `versionCode = 3`. For v0.5.1, increment to 4 (or whatever the project convention is).

**Confidence:** HIGH -- simple string replacement plus one import addition.

### Pattern 4: Android Backup Exclusion (BOARD-03)

**What:** Create two XML files and update AndroidManifest.xml to exclude `vitruvian.db` and `vitruvian_preferences.xml` from Android Auto Backup.

**Database file:** `vitruvian.db` (from `DriverFactory.android.kt` line 15: `DATABASE_NAME = "vitruvian.db"`)
**Preferences file:** `vitruvian_preferences.xml` (from `PlatformModule.android.kt` line 23: `getSharedPreferences("vitruvian_preferences", ...)`)

**File 1: `androidApp/src/main/res/xml/backup_rules.xml`** (API 30 and below):
```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <exclude domain="database" path="vitruvian.db" />
    <exclude domain="sharedpref" path="vitruvian_preferences.xml" />
</full-backup-content>
```

**File 2: `androidApp/src/main/res/xml/data_extraction_rules.xml`** (API 31+):
```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="database" path="vitruvian.db" />
        <exclude domain="sharedpref" path="vitruvian_preferences.xml" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="database" path="vitruvian.db" />
        <exclude domain="sharedpref" path="vitruvian_preferences.xml" />
    </device-transfer>
</data-extraction-rules>
```

**Manifest changes (AndroidManifest.xml):**
```xml
<application
    android:allowBackup="true"
    android:fullBackupContent="@xml/backup_rules"
    android:dataExtractionRules="@xml/data_extraction_rules"
    ...>
```

Note: Keep `android:allowBackup="true"` so other non-sensitive data can still be backed up. The exclusion rules specifically target the database and preferences.

**Why exclude device-transfer too:** The database contains workout history and the preferences contain auth tokens (`portal_auth_token` in PortalTokenStorage). Both should be excluded from cloud AND device-to-device transfer to prevent leaking user data to a new device controlled by someone else.

**Confidence:** HIGH -- well-documented Android API, straightforward XML configuration.

### Pattern 5: Camera Permission Rationale (BOARD-05)

**What:** When the user enables Form Check, show a custom rationale explaining that camera data stays on-device before requesting camera permission.

**Current state:** `FormCheckOverlay.android.kt` requests camera permission with `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())` but has NO rationale dialog. It just fires `permissionLauncher.launch(Manifest.permission.CAMERA)` immediately.

**Project pattern:** `BlePermissionHandler.android.kt` shows a full-screen rationale with icon, title, body text, and button. Follow this same pattern for camera.

**Implementation approach:**
```kotlin
// In FormCheckOverlay.android.kt, before launching permission request:
// 1. Check if permission already granted -> proceed
// 2. Check shouldShowRequestPermissionRationale -> show dialog first
// 3. Neither -> request permission directly

// Rationale text per BOARD-05:
"Form Check uses your camera to analyze exercise form in real-time. " +
"All processing happens entirely on your device - no video or images " +
"are ever uploaded, stored, or shared."
```

**Key considerations:**
- `shouldShowRequestPermissionRationale` requires Activity context -- get it via `LocalContext.current as? Activity` or use Accompanist (not in project, avoid adding dependency)
- The current approach of requesting on first composition is aggressive. Better: show rationale first, then request on user action
- Must handle the "permanently denied" case (direct to Settings)

**Confidence:** HIGH -- the BLE permission handler provides a proven pattern to follow.

### Pattern 6: PoseLandmarkerHelper Error Handling (BOARD-08)

**What:** `PoseLandmarkerHelper.setupPoseLandmarker()` throws `RuntimeException` if the model asset file is missing. Currently, `FormCheckOverlay.android.kt` catches this but only logs it -- no user-facing message.

**Current state (FormCheckOverlay.android.kt lines 137-143):**
```kotlin
LaunchedEffect(Unit) {
    try {
        poseLandmarkerHelper.setupPoseLandmarker()
    } catch (e: Exception) {
        co.touchlab.kermit.Logger.e("FormCheckOverlay") { "Failed to init PoseLandmarker: ${e.message}" }
    }
}
```

**Fix approach -- two layers:**

1. **PoseLandmarkerHelper.setupPoseLandmarker():** Wrap the `PoseLandmarker.createFromOptions()` call in try-catch and call `listener.onError()` with a user-friendly message:
```kotlin
fun setupPoseLandmarker() {
    try {
        // ... existing setup code ...
        poseLandmarker = PoseLandmarker.createFromOptions(context, options)
    } catch (e: Exception) {
        listener.onError(
            "Form Check model not available. Please reinstall the app to restore this feature."
        )
    }
}
```

2. **FormCheckOverlay.android.kt:** Add an error state that displays a user-facing message box instead of just logging:
```kotlin
var errorMessage by remember { mutableStateOf<String?>(null) }

// In PoseLandmarkerListener.onError:
override fun onError(error: String) {
    errorMessage = error
}

// In render:
if (errorMessage != null) {
    Box(modifier = modifier.size(160.dp, 120.dp)...) {
        Text(text = errorMessage!!, style = ...)
    }
}
```

**Confidence:** HIGH -- clear error path, straightforward fix.

### Pattern 7: iOS Form Check Suppression (BOARD-06)

**What:** iOS PHOENIX tier upgrade prompts must not mention Form Check since iOS CV is deferred to v0.6.0+.

**Current state:** The `PaywallScreen.kt` has a generic feature list that is NOT tier-specific and does NOT mention Form Check at all (it lists: AI-Powered Routine Generation, Cloud Sync, Community Library, Health Integrations, Advanced Analytics, Priority Support). However, the `PremiumFeatureGate` component and the `LockedFeatureOverlay` are generic wrappers that accept `featureName` as a parameter -- so any caller could pass "Form Check" as the feature name.

**Key insight:** The actual suppression needs to happen at the call sites where Form Check gating is applied. When Phase 19 adds the Form Check toggle (CV-01), the iOS path should show "Coming soon" instead of an upgrade prompt. The iOS `FormCheckOverlay.ios.kt` is already a no-op stub.

**Implementation options:**
1. Add a platform-detection utility (`expect fun isIos(): Boolean`) and use it in the Form Check toggle to show different messages
2. Use the existing `FormCheckOverlay` expect/actual pattern -- iOS actual already renders nothing; add a "coming soon" message in Phase 19
3. Add a `FormCheckAvailability` helper that returns platform-specific availability status

**For Phase 16 scope:** The most impactful action is ensuring the PaywallScreen and any tier-specific feature lists do NOT list "Form Check" when displayed on iOS. Since the current PaywallScreen doesn't mention it, this is already partially satisfied. The planner should:
- Audit all places that list tier features for upgrade prompts
- Ensure any new feature listing from v0.5.1 is platform-conditional
- Prepare the infrastructure (e.g., an `expect fun isIosPlatform(): Boolean` or check existing KMP platform detection)

**Existing platform detection:** Check if `Platform` expect/actual already exists in the project.

**Confidence:** MEDIUM -- the current PaywallScreen doesn't list Form Check, but other screens (GamificationTab, future feature lists) may. Need to audit all callsites.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Local time from epoch | Manual timezone offset math | `kotlinx-datetime` `Instant.toLocalDateTime(TimeZone.currentSystemDefault())` | DST, timezone database, leap seconds |
| Android backup exclusion | Custom backup agent | `fullBackupContent` + `dataExtractionRules` XML | Android framework handles all edge cases |
| Permission rationale flow | Custom permission tracking | `ActivityResultContracts.RequestPermission` + `shouldShowRequestPermissionRationale` | Handles all Android version differences |
| Platform detection | Build flavor checks | KMP `expect/actual` | Already used throughout codebase |

## Common Pitfalls

### Pitfall 1: Forgetting Both Backup XML Files
**What goes wrong:** App targets API 36 (compileSdk) but only provides `dataExtractionRules`. Devices running API 30 and below silently ignore it and backup everything.
**Why it happens:** Developer only tests on API 31+ emulator.
**How to avoid:** Always provide BOTH `fullBackupContent` (API 23-30) AND `dataExtractionRules` (API 31+). Both manifest attributes must be present.
**Warning signs:** Backup exclusion tests pass on API 31+ but fail on API 30.

### Pitfall 2: SmartSuggestions Test Timezone Sensitivity
**What goes wrong:** Tests pass in one timezone but fail in another because `classifyTimeWindow` now uses local time.
**Why it happens:** CI server runs in UTC, developer machine in UTC+10.
**How to avoid:** Inject `TimeZone` parameter into `classifyTimeWindow` with default `TimeZone.currentSystemDefault()`. Tests pass explicit timezone.
**Warning signs:** Tests flake on CI but pass locally (or vice versa).

### Pitfall 3: FeatureGate Test Assertions on Feature.entries Size
**What goes wrong:** Tests that assert specific feature counts break when new features are added.
**Why it happens:** `Feature.entries.forEach` tests are size-agnostic (good), but any hardcoded counts are brittle.
**How to avoid:** Test behavior (which features are in which tier) not counts. The existing tests already do this correctly.
**Warning signs:** None -- existing tests are well-structured.

### Pitfall 4: Version String Inconsistency
**What goes wrong:** `build.gradle.kts` says "0.5.1" but `Constants.kt` still says "0.4.0", so `DeviceInfo.appVersionName` reports wrong version.
**Why it happens:** Three separate locations store the version string.
**How to avoid:** Update all three locations atomically. Consider making SettingsTab read from `DeviceInfo` instead of hardcoding.
**Warning signs:** Settings screen shows different version than About screen or Play Store.

### Pitfall 5: Camera Permission on API 33+ Photo Picker
**What goes wrong:** On Android 14+, `CAMERA` permission dialog looks different and the rationale timing changes.
**Why it happens:** Google changed permission UX behavior in recent API levels.
**How to avoid:** Always check `shouldShowRequestPermissionRationale` before deciding whether to show custom rationale. The standard `ActivityResultContracts.RequestPermission` handles API differences.
**Warning signs:** Rationale dialog never appears or appears at wrong time.

### Pitfall 6: PoseLandmarkerHelper Crash on Missing Asset in Release Build
**What goes wrong:** The .task file is present in debug assets but missing in release after ProGuard/R8.
**Why it happens:** R8 can strip or rename assets if not properly excluded.
**How to avoid:** The existing `proguard-rules.pro` should already handle this (CV-11 was completed in v0.5.0). But the graceful error handling in BOARD-08 acts as a safety net regardless.
**Warning signs:** Crash reports from release builds only.

## Code Examples

### Example 1: classifyTimeWindow Fix
```kotlin
// Source: Project pattern from KmpUtils.kt lines 61-64
import kotlinx.datetime.*
import kotlin.time.Instant

internal fun classifyTimeWindow(
    timestampMs: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault()
): TimeWindow {
    val instant = Instant.fromEpochMilliseconds(timestampMs)
    val localDateTime = instant.toLocalDateTime(timeZone)
    val hourOfDay = localDateTime.hour
    return when (hourOfDay) {
        in 5..6 -> TimeWindow.EARLY_MORNING
        in 7..9 -> TimeWindow.MORNING
        in 10..14 -> TimeWindow.AFTERNOON
        in 15..19 -> TimeWindow.EVENING
        else -> TimeWindow.NIGHT
    }
}
```

### Example 2: FeatureGate New Entries
```kotlin
// Add to Feature enum
CV_FORM_CHECK,      // Phoenix tier
RPG_ATTRIBUTES,     // Phoenix tier
GHOST_RACING,       // Phoenix tier
READINESS_BRIEFING, // Elite tier

// Update phoenixFeatures set
private val phoenixFeatures = setOf(
    Feature.FORCE_CURVES, Feature.PER_REP_METRICS, Feature.VBT_METRICS,
    Feature.PORTAL_SYNC, Feature.LED_BIOFEEDBACK, Feature.REP_QUALITY_SCORE,
    Feature.CV_FORM_CHECK, Feature.RPG_ATTRIBUTES, Feature.GHOST_RACING
)

// Update eliteFeatures (already includes phoenixFeatures)
private val eliteFeatures = phoenixFeatures + setOf(
    Feature.ASYMMETRY_ANALYSIS, Feature.AUTO_REGULATION, Feature.SMART_SUGGESTIONS,
    Feature.WORKOUT_REPLAY, Feature.STRENGTH_ASSESSMENT, Feature.PORTAL_ADVANCED_ANALYTICS,
    Feature.READINESS_BRIEFING
)
```

### Example 3: Backup Exclusion Manifest
```xml
<!-- AndroidManifest.xml application element -->
<application
    android:name=".VitruvianApp"
    android:allowBackup="true"
    android:fullBackupContent="@xml/backup_rules"
    android:dataExtractionRules="@xml/data_extraction_rules"
    ...>
```

### Example 4: Camera Permission Rationale Pattern
```kotlin
// Following BlePermissionHandler.android.kt pattern
@Composable
private fun CameraPermissionRationale(
    onGrantPermission: () -> Unit
) {
    Column(
        modifier = Modifier.size(160.dp, 120.dp).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Camera needed for Form Check.\nAll processing stays on your device.",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center
        )
        TextButton(onClick = onGrantPermission) {
            Text("Allow Camera")
        }
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `fullBackupContent` only | Both `fullBackupContent` AND `dataExtractionRules` | Android 12 (API 31), Oct 2021 | Must provide both XMLs for cross-API-level coverage |
| Manual epoch modular arithmetic for time | `kotlinx-datetime` Instant/LocalDateTime | kotlinx-datetime 0.4+ (2023) | Handles DST, timezone DB, all edge cases |
| Accompanist permissions library | Built-in `rememberLauncherForActivityResult` | Compose 1.6+ (2024) | No extra dependency needed; Accompanist permissions deprecated |

**Deprecated/outdated:**
- **Accompanist Permissions:** Deprecated in favor of built-in Compose permission APIs. This project correctly uses `ActivityResultContracts` directly.
- **android:allowBackup="false":** Overly aggressive -- better to use targeted exclusion rules to allow non-sensitive data backup.

## Open Questions

1. **iOS Platform Detection in commonMain**
   - What we know: The project uses `expect/actual` for platform-specific code. `DeviceInfo` is an expect/actual object.
   - What's unclear: Whether a simple `isIosPlatform()` expect/actual already exists, or if BOARD-06 needs to create one.
   - Recommendation: Check for existing platform detection in Models.kt or PlatformUtils.kt. If none exists, add `expect val isIosPlatform: Boolean` with `actual val isIosPlatform = false` (Android) and `actual val isIosPlatform = true` (iOS).

2. **versionCode Strategy**
   - What we know: Current versionCode is 3, can be overridden via `-Pversion.code=XXX` Gradle property.
   - What's unclear: Whether versionCode should be bumped to 4 now or left for CI to handle at release time.
   - Recommendation: Bump to 4 as part of BOARD-07 since the versionName is changing. CI can still override.

3. **Scope of BOARD-06 (iOS Form Check Suppression)**
   - What we know: PaywallScreen doesn't mention Form Check. PremiumFeatureGate is generic.
   - What's unclear: Whether there are other screens that list tier-specific features where Form Check could appear.
   - Recommendation: Audit NavGraph.kt, GamificationTab, and AccountScreen during implementation. If none mention Form Check, BOARD-06 may be primarily about establishing the platform-conditional pattern for Phase 19 to use.

## Sources

### Primary (HIGH confidence)
- Project source code: `SmartSuggestionsEngine.kt`, `FeatureGate.kt`, `PoseLandmarkerHelper.kt`, `FormCheckOverlay.android.kt`, `BlePermissionHandler.android.kt`, `Constants.kt`, `DeviceInfo.android.kt`, `PlatformModule.android.kt`, `DriverFactory.android.kt`, `AndroidManifest.xml`
- [Android Auto Backup official docs](https://developer.android.com/identity/data/autobackup) - fullBackupContent and dataExtractionRules XML syntax
- [Android permissions best practices](https://developer.android.com/training/permissions/requesting) - shouldShowRequestPermissionRationale flow

### Secondary (MEDIUM confidence)
- [DataExtractionRules lint check](https://googlesamples.github.io/android-custom-lint-rules/checks/DataExtractionRules.md.html) - lint will warn if missing
- [FullBackupContent lint check](https://googlesamples.github.io/android-custom-lint-rules/checks/FullBackupContent.md.html) - lint will warn if invalid
- [Jetpack Compose permissions guide](https://composables.com/blog/permissions) - modern permission handling patterns

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - all libraries already in project, zero new dependencies
- Architecture: HIGH - clear bugs with clear fixes, established project patterns for every change
- Pitfalls: HIGH - well-known Android patterns, common gotchas documented in official docs

**Research date:** 2026-02-27
**Valid until:** 2026-03-27 (stable domain, no moving targets)
