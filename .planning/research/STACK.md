# Technology Stack: v0.5.1 Board Polish & Premium UI

**Project:** Project Phoenix MP - v0.5.1 Board Polish & Premium UI
**Researched:** 2026-02-27
**Scope:** Stack additions for WCAG accessibility, Android backup exclusion, HUD customization, ghost racing UI, RPG attributes, readiness heuristic, camera permission rationale, SmartSuggestions local time fix
**Overall Confidence:** HIGH

---

## Existing Stack (Validated — DO NOT Change)

| Technology | Version | Notes |
|------------|---------|-------|
| Kotlin | 2.3.0 | |
| Compose Multiplatform | 1.10.0 | |
| AGP | 9.0.1 | |
| Koin | 4.1.1 | |
| SQLDelight | 2.2.1 | Schema v16 (RepBiomechanics added) |
| Coroutines | 1.10.2 | |
| kotlinx-datetime | 0.7.1 | Already in catalog — used for UTC fix |
| multiplatform-settings | 1.3.0 | Already in catalog — used for HUD prefs |
| compose-bom | 2025.12.01 | Maps to compose-ui 1.10.4 |
| accompanist-permissions | 0.37.3 | Already in catalog — camera rationale |
| compileSdk / targetSdk | 36 | |
| minSdk | 26 | |

---

## Recommended Stack Additions for v0.5.1

### Summary Verdict: Zero New Runtime Library Dependencies

All eight v0.5.1 features are implementable with the current dependency set. What IS needed:

1. **New test dependency**: `compose-ui:ui-test-junit4-accessibility` (via existing BOM, no version pin needed)
2. **New XML resources**: `backup_rules.xml` + `data_extraction_rules.xml` in `res/xml/`
3. **No new production runtime libraries**

---

## Feature-by-Feature Stack Analysis

### 1. WCAG AA Color-Blind Accessibility

**Stack verdict: No new dependencies. Existing Compose + testing frameworks cover this.**

#### Production Implementation

The accessibility changes are pure code changes to existing Compose composables — no new library needed:

- **Pattern/shape redundancy**: Add secondary visual indicators alongside color-coded elements (velocity zone bars, balance bar, readiness card). Compose `Canvas` with `drawLine` / `drawRect` / `drawPath` shapes already available via `compose.foundation`.
- **contentDescription semantics**: Add `semantics { contentDescription = "..." }` to color-coded indicators. Already in Compose core via `androidx.compose.ui.semantics`.
- **Material3 color roles**: Material3's `MaterialTheme.colorScheme` uses semantic color tokens (`error`, `onError`, `surface`, `onSurface`) that pass WCAG AA automatically when used in the right role pairs. No extra library needed.

**Targets in this project:**
- `VelocityZoneBar` — color-only velocity zone indicator
- `BalanceBar` — color-only asymmetry severity
- `ReadinessCard` (new) — readiness score color coding
- RPG attribute bars — if using color-coded tiers

**Pattern:** Add shape cues (e.g., warning triangle icon from `compose-material-icons-extended`, already a dependency) alongside color. Do NOT rely on color alone per WCAG 1.4.1.

#### Test-Time Tooling (NEW test dependency)

| Artifact | Via | Purpose | Confidence |
|----------|-----|---------|------------|
| `compose-ui:ui-test-junit4-accessibility` | Existing compose-bom 2025.12.01 | Automated accessibility checks in instrumented tests | HIGH |

```toml
# libs.versions.toml — add to [libraries]
compose-ui-test-accessibility = { module = "androidx.compose.ui:ui-test-junit4-accessibility" }
# No version needed — resolved by compose-bom
```

```kotlin
// androidApp/build.gradle.kts — add to androidTestImplementation
androidTestImplementation(libs.compose.ui.test.accessibility)
```

**Usage pattern:**
```kotlin
@Test
fun velocityZoneBarMeetsColorContrast() {
    composeTestRule.enableAccessibilityChecks()
    composeTestRule.setContent { VelocityZoneBar(zone = VelocityZone.OPTIMAL, ...) }
    composeTestRule.onRoot().tryPerformAccessibilityChecks()
}
```

Available automated checks: color contrast (4.5:1 normal text, 3:1 large text), touch target size, missing content descriptions, traversal order.

**Android Studio Compose UI Check** (no dependency): In Android Studio, open a `@Preview`, click the accessibility icon. Shows color vision deficiency simulations (Protanopia, Deuteranopia, Tritanopia) and contrast violations. Zero-cost development-time check.

---

### 2. Android Backup Exclusion Rules

**Stack verdict: No new dependencies. Pure XML resource files + AndroidManifest attributes.**

Two XML files are required to cover all API levels (minSdk 26 to targetSdk 36):

#### File 1: `res/xml/backup_rules.xml` (Android 11 / API 30 and lower)

```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <exclude domain="database" path="VitruvianDatabase.db"/>
    <exclude domain="database" path="VitruvianDatabase.db-shm"/>
    <exclude domain="database" path="VitruvianDatabase.db-wal"/>
</full-backup-content>
```

#### File 2: `res/xml/data_extraction_rules.xml` (Android 12+ / API 31+)

```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="database" path="VitruvianDatabase.db"/>
        <exclude domain="database" path="VitruvianDatabase.db-shm"/>
        <exclude domain="database" path="VitruvianDatabase.db-wal"/>
    </cloud-backup>
    <device-transfer>
        <exclude domain="database" path="VitruvianDatabase.db"/>
        <exclude domain="database" path="VitruvianDatabase.db-shm"/>
        <exclude domain="database" path="VitruvianDatabase.db-wal"/>
    </device-transfer>
</data-extraction-rules>
```

#### AndroidManifest.xml changes

```xml
<application
    android:allowBackup="true"
    android:fullBackupContent="@xml/backup_rules"
    android:dataExtractionRules="@xml/data_extraction_rules"
    ... >
```

`android:allowBackup="true"` stays — we want backup, but want to exclude the SQLite DB file. Keeping `allowBackup="true"` allows shared preferences (settings, HUD config) to be backed up while excluding the workout data DB.

**Important:** `android:fullBackupContent` is required for API 30 and below even when targeting API 31+. Both attributes must coexist. The `tools:ignore="GoogleBackupTransport"` lint suppression is not needed since we're providing proper rules.

---

### 3. HUD Page Customization (User-Configurable Metric Visibility)

**Stack verdict: No new dependencies. Use existing `multiplatform-settings` 1.3.0 already in the project.**

`multiplatform-settings` (com.russhwolf:multiplatform-settings) is already declared in `libs.versions.toml` at 1.3.0 and wired into the project. This is the correct tool for HUD preferences.

**Why multiplatform-settings over DataStore:**
- Already in the project — zero incremental cost
- commonMain-compatible — preferences can be defined and read in shared module
- `multiplatform-settings-coroutines` provides `StateFlow` integration for reactive UI updates
- DataStore is Android-only for the Preferences variant; the KMP DataStore alpha exists but adds complexity that multiplatform-settings already solves

**Storage pattern for HUD page ordering/visibility:**

```kotlin
// commonMain — HudPreferencesRepository
class HudPreferencesRepository(private val settings: Settings) {

    // Store ordered list of enabled metric IDs as comma-separated string
    fun getVisibleMetrics(): List<HudMetric> {
        val raw = settings.getStringOrNull(KEY_HUD_METRICS)
            ?: DEFAULT_METRICS.joinToString(",") { it.name }
        return raw.split(",").mapNotNull { HudMetric.fromName(it) }
    }

    fun setVisibleMetrics(metrics: List<HudMetric>) {
        settings[KEY_HUD_METRICS] = metrics.joinToString(",") { it.name }
    }

    // StateFlow for reactive UI (requires multiplatform-settings-coroutines)
    fun visibleMetricsFlow(scope: CoroutineScope): StateFlow<List<HudMetric>> =
        settings.getStringOrNullStateFlow(scope, KEY_HUD_METRICS)
            .map { raw -> /* parse */ }
            .stateIn(scope, SharingStarted.Eagerly, DEFAULT_METRICS)

    companion object {
        private const val KEY_HUD_METRICS = "hud_visible_metrics"
        val DEFAULT_METRICS = listOf(HudMetric.VELOCITY, HudMetric.LOAD, HudMetric.REPS, ...)
    }
}
```

**Reorder UI:** Use existing `reorderable` 3.0.0 library (already in catalog) for drag-to-reorder metric list in HUD settings screen.

**Koin wiring:** Add `HudPreferencesRepository` to the existing `data` module, injecting `Settings` which is already provided via Koin in the existing setup.

---

### 4. Ghost Racing Overlay (Dual Progress Bars, Real-Time Comparison)

**Stack verdict: No new dependencies. Compose Canvas + `animateFloatAsState` from existing `compose.animation`.**

The ghost racing UI requires:
- Two overlapping horizontal progress bars (current session vs. personal best)
- Animated progress as rep count advances
- Delta label (e.g., "+3 reps ahead")

**Implementation approach — custom Canvas composable:**

```kotlin
// Renders two stacked bars: ghost (dimmed) + current (solid)
@Composable
fun GhostRaceBar(
    currentProgress: Float,   // 0f..1f
    ghostProgress: Float,     // 0f..1f (personal best pace)
    modifier: Modifier = Modifier
) {
    val animatedCurrent by animateFloatAsState(
        targetValue = currentProgress,
        animationSpec = tween(durationMillis = 300),
        label = "currentProgress"
    )
    val animatedGhost by animateFloatAsState(
        targetValue = ghostProgress,
        animationSpec = tween(durationMillis = 300),
        label = "ghostProgress"
    )

    Canvas(modifier = modifier) {
        // Ghost bar (semi-transparent, drawn first so current overlaps)
        drawRect(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            size = size.copy(width = size.width * animatedGhost)
        )
        // Current bar (solid)
        drawRect(
            color = MaterialTheme.colorScheme.primary,
            size = size.copy(width = size.width * animatedCurrent)
        )
    }
}
```

**Why Canvas over two LinearProgressIndicators:**
`LinearProgressIndicator` uses Material3 track styling and cannot be layered at arbitrary Z-order without clip issues. Canvas gives pixel-precise control for the ghost (dimmed/transparent) visual effect. The dual-bar reading is the entire point — Canvas is the right primitive.

**Color-blind note:** Ghost bar uses opacity/alpha difference (0.5 vs 1.0), not a different hue. This is inherently color-blind safe. Add a text delta label (`+3 reps`) as the primary semantic indicator.

---

### 5. RPG Attribute Computation Engine

**Stack verdict: No new dependencies. Pure commonMain Kotlin math.**

The RPG attribute engine is a pure computation — 5 stats (Strength, Endurance, Power, Consistency, Technique) derived from existing persisted data (RepBiomechanics, WorkoutSession, PersonalRecord tables).

**No library needed because:**
- Input: existing SQLDelight query results (already typed Kotlin objects)
- Computation: weighted averages, normalization, min/max scaling — pure arithmetic
- Output: `RpgAttributes` data class with Int fields (1-100 scale)
- Character class classification: if-else or `when` branching on composite score

**Engine location:** `shared/src/commonMain/kotlin/.../domain/engine/RpgAttributeEngine.kt`

```kotlin
// Pure function, no dependencies
data class RpgAttributes(
    val strength: Int,       // 1-100, derived from max loads / 1RM estimates
    val endurance: Int,      // 1-100, derived from session volume / set count
    val power: Int,          // 1-100, derived from MCV velocity measurements
    val consistency: Int,    // 1-100, derived from workout streak + session frequency
    val technique: Int       // 1-100, derived from form scores + asymmetry penalties
)

class RpgAttributeEngine {
    fun compute(
        recentSessions: List<WorkoutSession>,
        repBiomechanics: List<RepBiomechanicsRecord>,
        personalRecords: List<PersonalRecord>,
        formScores: List<FormScoreRecord>,
        streakDays: Int
    ): RpgAttributes { ... }
}
```

**Character class:** Derived from which attribute is highest (Strength → Warrior, Endurance → Ranger, Power → Berserker, Technique → Monk, Consistency → Guardian). No external data needed.

---

### 6. Pre-Workout Readiness Heuristic

**Stack verdict: No new dependencies. Pure commonMain logic using existing `kotlinx-datetime` 0.7.1.**

The readiness briefing is a heuristic using locally persisted data:
- Days since last workout (from WorkoutSession timestamps)
- Volume from last 3-7 sessions (from MetricSample / WorkoutSession)
- Gamification streak data (already persisted)

**Heuristic engine:** Pure function in commonMain, similar pattern to `SmartSuggestionsEngine` (stateless, injectable clock for testing).

```kotlin
class ReadinessEngine(private val clock: Clock = Clock.System) {
    fun computeReadiness(
        recentSessions: List<WorkoutSession>,
        streakDays: Int,
        lastSessionTimestamp: Long
    ): ReadinessBriefing {
        val daysSinceLast = computeDaysSince(lastSessionTimestamp, clock)
        val recentVolume = computeRecentVolume(recentSessions)
        // advisory logic...
    }

    private fun computeDaysSince(epochMs: Long, clock: Clock): Int {
        val now = clock.now()
        val then = Instant.fromEpochMilliseconds(epochMs)
        return (now - then).inWholeDays.toInt()
    }
}
```

**No new library** — `kotlinx.datetime.Clock` and `Instant` are already available via the existing `kotlinx-datetime` 0.7.1 dependency.

---

### 7. Camera Permission Rationale UI (On-Device Guarantee)

**Stack verdict: No new dependencies. `accompanist-permissions` 0.37.3 already in the project.**

Accompanist permissions is already declared in `libs.versions.toml` at 0.37.3. This covers the camera rationale dialog.

**Implementation pattern:**
```kotlin
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun FormCheckPermissionGate(onPermissionGranted: @Composable () -> Unit) {
    val cameraPermissionState = rememberPermissionState(
        android.Manifest.permission.CAMERA
    )

    when {
        cameraPermissionState.status.isGranted -> onPermissionGranted()

        cameraPermissionState.status.shouldShowRationale -> {
            // User denied once — show rationale explaining on-device-only processing
            CameraRationaleDialog(
                onConfirm = { cameraPermissionState.launchPermissionRequest() },
                onDismiss = { /* skip form check */ }
            )
        }

        else -> {
            // First time or permanent denial
            LaunchedEffect(Unit) {
                cameraPermissionState.launchPermissionRequest()
            }
        }
    }
}

@Composable
private fun CameraRationaleDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        title = { Text("Camera Access") },
        text = { Text(
            "Form Check uses your camera to analyze exercise form. " +
            "All processing happens on your device — no video is uploaded or stored."
        )},
        confirmButton = { TextButton(onClick = onConfirm) { Text("Allow") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not Now") } },
        onDismissRequest = onDismiss
    )
}
```

**Why Accompanist over raw `ActivityResultContracts`:** `shouldShowRationale` from `PermissionStatus` is Compose-state-aware. Raw `ActivityResultContracts` requires manual `shouldShowRequestPermissionRationale()` calls outside of Compose. Accompanist wraps this correctly in a `State` that triggers recomposition automatically.

---

### 8. SmartSuggestions UTC Time Fix

**Stack verdict: No new dependencies. `kotlinx-datetime` 0.7.1 already provides the fix API.**

**The bug:** `classifyTimeWindow` in `SmartSuggestionsEngine` calls `Clock.System.now()` (returns UTC `Instant`) and reads `.hour` directly, assuming UTC = local time. This mis-classifies time windows for users outside UTC.

**The fix — `kotlinx-datetime` 0.7.1 API:**
```kotlin
// BEFORE (broken — uses UTC hour):
val hour = Clock.System.now().toLocalDateTime(TimeZone.UTC).hour

// AFTER (correct — uses device local time):
val hour = Clock.System.now()
    .toLocalDateTime(TimeZone.currentSystemDefault())
    .hour
```

`TimeZone.currentSystemDefault()` is available in `kotlinx-datetime` 0.7.1 (already in the project). It reads the device's configured timezone on Android (via `java.util.TimeZone.getDefault()`) and on iOS (via `NSTimeZone.localTimeZone`). This is the correct KMP-idiomatic fix — no platform-specific code needed.

**Why NOT `java.time.ZoneId.systemDefault()` or `java.util.Calendar`:**
Those are JVM-only and would break commonMain compilation. `TimeZone.currentSystemDefault()` is the KMP-correct equivalent.

---

## Full Dependency Delta for v0.5.1

### New Production Dependencies: None

### New Test Dependencies: 1 artifact (via existing BOM)

```toml
# gradle/libs.versions.toml — add to [libraries] section
compose-ui-test-accessibility = { module = "androidx.compose.ui:ui-test-junit4-accessibility" }
```

```kotlin
// androidApp/build.gradle.kts — add to dependencies
androidTestImplementation(libs.compose.ui.test.accessibility)
```

### New Resource Files: 2 XML files

```
androidApp/src/main/res/xml/backup_rules.xml         # API 30 and below backup rules
androidApp/src/main/res/xml/data_extraction_rules.xml # API 31+ backup rules
```

### AndroidManifest.xml Changes: 2 new attributes

```xml
<application
    android:fullBackupContent="@xml/backup_rules"
    android:dataExtractionRules="@xml/data_extraction_rules"
    ... >
```

### versionName Fix (No Library Change)

```kotlin
// androidApp/build.gradle.kts
defaultConfig {
    versionName = "0.5.1"   // was "0.4.0" — update to reflect actual shipped version
}
```

---

## Existing Libraries Doing New Work in v0.5.1

| Library | Version | New v0.5.1 Usage |
|---------|---------|-----------------|
| `kotlinx-datetime` | 0.7.1 | `TimeZone.currentSystemDefault()` for UTC fix; `Clock` injection in ReadinessEngine |
| `multiplatform-settings` | 1.3.0 | HUD metric visibility/order persistence in `HudPreferencesRepository` |
| `multiplatform-settings-coroutines` | 1.3.0 | `StateFlow` for reactive HUD preference updates |
| `reorderable` | 3.0.0 | Drag-to-reorder in HUD customization settings screen |
| `accompanist-permissions` | 0.37.3 | Camera permission rationale dialog (`shouldShowRationale` state) |
| `compose.animation` | via BOM | `animateFloatAsState` for ghost racing bar animation |
| `compose.foundation` (Canvas) | via BOM | Ghost racing dual-bar, RPG radar chart, pattern/shape accessibility cues |
| `compose-material-icons-extended` | via BOM | Warning/info icons for color-blind-safe redundant visual cues |
| `compose.material3` | via BOM | `AlertDialog` for camera rationale; `LinearProgressIndicator` for RPG stat bars |

---

## What NOT to Add

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| **Jetpack DataStore (Preferences)** | Android-only. Project is KMP. `multiplatform-settings` is already in the project and KMP-compatible. | `multiplatform-settings` 1.3.0 (already present) |
| **AccessibilityNodeInfoCompat / ViewCompat** | These are View-system accessibility APIs. Compose uses semantics modifiers instead. | `Modifier.semantics { }` from `compose.ui` |
| **AndroidX Test Accessibility Library (old)** | Pre-Compose 1.8 approach using `AccessibilityChecks.enable()` in Espresso. | `compose-ui:ui-test-junit4-accessibility` via BOM |
| **Deque Axe Android** | Third-party library with separate integration overhead. The `ui-test-junit4-accessibility` (ATF-based) provides equivalent automated checks with zero additional dependency. | `ui-test-junit4-accessibility` |
| **Custom color-blind simulation library** | Android Studio's Compose UI Check renders color vision deficiency simulations natively in previews. | Android Studio built-in (zero cost) |
| **java.time or java.util.TimeZone** | JVM-only, breaks commonMain compilation. | `kotlinx.datetime.TimeZone.currentSystemDefault()` |
| **RevenueCat** | Out of scope for v0.5.1 — blocked on auth migration per PROJECT.md. | Not applicable this milestone |
| **WorkManager** | Not needed for readiness heuristic — it computes on ViewModel initialization from local DB data, no background scheduling needed. | Direct ViewModel call |

---

## Version Compatibility Notes

| Package | Current Version | Compose BOM | Notes |
|---------|----------------|-------------|-------|
| compose-bom | 2025.12.01 | — | Maps compose-ui to 1.10.4 |
| ui-test-junit4-accessibility | via BOM (1.10.4) | 2025.12.01 | Available since Compose 1.8.0 stable. No separate version pin needed. |
| accompanist-permissions | 0.37.3 | Compose 1.7 | Tested against Compose 1.7. Compose 1.10.x is backward compatible. Watch for recomposition edge cases. |
| multiplatform-settings-coroutines | 1.3.0 | — | Separate artifact from `multiplatform-settings`; must match version |

**Accompanist 0.37.3 + Compose 1.10 note:** Accompanist 0.37.x targets Compose 1.7. In practice it functions correctly with 1.10.x (no API surface changes in the permissions module). If a runtime crash occurs on the permissions composable, upgrade accompanist to 0.38.x if released, or fall back to `rememberLauncherForActivityResult` with manual `shouldShowRequestPermissionRationale()`.

---

## Sources

- [Android Developers — Auto Backup Configuration](https://developer.android.com/identity/data/autobackup) — fullBackupContent / dataExtractionRules XML format verified (HIGH confidence)
- [Android Developers — Backup Security Best Practices](https://developer.android.com/privacy-and-security/risks/backup-best-practices) — sensitive data exclusion guidance (HIGH confidence)
- [Android Developers — Compose Accessibility Testing](https://developer.android.com/develop/ui/compose/accessibility/testing) — `enableAccessibilityChecks()`, `ui-test-junit4-accessibility` artifact (HIGH confidence)
- [Compose BOM to Library Mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping) — BOM 2025.12.01 maps to compose-ui 1.10.4; latest BOM 2026.02.01 also 1.10.4 (HIGH confidence)
- [kotlinx-datetime API — currentSystemDefault](https://kotlinlang.org/api/kotlinx-datetime/kotlinx-datetime/kotlinx.datetime/-time-zone/-companion/current-system-default.html) — KMP-correct local timezone API (HIGH confidence)
- [Accompanist Permissions](https://google.github.io/accompanist/permissions/) — `shouldShowRationale`, `rememberPermissionState` API (HIGH confidence)
- [Accompanist GitHub Releases](https://github.com/google/accompanist/releases) — Latest stable is 0.37.3 (April 28, 2024), targets Compose 1.7 (HIGH confidence)
- [multiplatform-settings GitHub](https://github.com/russhwolf/multiplatform-settings) — Version 1.3.0, coroutines module for StateFlow (HIGH confidence)
- [Compose Canvas Animation — Android Developers](https://medium.com/androiddevelopers/custom-canvas-animations-in-jetpack-compose-e7767e349339) — Custom Canvas with `animateFloatAsState` pattern (MEDIUM confidence)
- [WCAG 1.4.1 — Use of Color](https://www.w3.org/WAI/WCAG21/Understanding/use-of-color.html) — Color must not be the only visual means (HIGH confidence — W3C standard)

---

*Stack research for: v0.5.1 Board Polish & Premium UI — Project Phoenix MP*
*Researched: 2026-02-27*
