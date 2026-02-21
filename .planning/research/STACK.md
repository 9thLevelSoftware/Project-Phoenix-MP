# Technology Stack: v0.5.0 Premium Mobile Additions

**Project:** Project Phoenix MP - Premium Mobile Features
**Researched:** 2026-02-20
**Scope:** New library additions for CV pose estimation (MediaPipe), CameraX integration, biomechanics persistence, ghost racing/RPG/readiness UI composables
**Overall Confidence:** HIGH

## Existing Stack (DO NOT Change)

| Technology | Version | Notes |
|------------|---------|-------|
| Kotlin | 2.3.0 | Current in libs.versions.toml |
| Compose Multiplatform | 1.10.0 | Unified @Preview, Hot Reload |
| AGP | 9.0.1 | Current |
| Koin | 4.1.1 | Feature-scoped modules |
| SQLDelight | 2.2.1 | Schema v15 |
| Coroutines | 1.10.2 | Current |
| compileSdk | 36 | Current |
| minSdk | 26 | Current |

## Recommended Stack Additions

### 1. MediaPipe Pose Landmarker (CV Form Checking)

| Technology | Version | Artifact | Purpose | Confidence |
|------------|---------|----------|---------|------------|
| MediaPipe Tasks Vision | 0.10.32 | `com.google.mediapipe:tasks-vision` | On-device 33-landmark pose estimation | HIGH |

**Why MediaPipe over ML Kit Pose:**
- MediaPipe Pose Landmarker provides 33 3D landmarks with world coordinates (hip-centered origin), ML Kit only provides 2D image coordinates
- MediaPipe's world coordinates enable biomechanical angle calculation without camera calibration
- Three model tiers (Lite/Full/Heavy) allow tuning accuracy vs. performance per device
- MediaPipe is Google's actively maintained replacement for ML Kit Vision (ML Kit Pose is beta and uses MediaPipe under the hood)
- The RepDetect project (fitness rep counting with MediaPipe Pose) validates this exact use case

**Model Selection:** Use `pose_landmarker_lite.task` for real-time LIVE_STREAM mode during workouts. The Lite model uses MobileNetV2-class architecture optimized for on-device inference at 30fps. The Full/Heavy models can be offered as user settings if accuracy improvements are needed.

**Running Mode:** LIVE_STREAM for real-time camera feed processing. Returns results asynchronously via callback, compatible with coroutine-based architecture via `callbackFlow`.

**Configuration Defaults:**
- `min_pose_detection_confidence`: 0.5 (default, tune higher for noisy gym environments)
- `min_tracking_confidence`: 0.5 (default)
- `min_pose_presence_confidence`: 0.5 (default)
- `num_poses`: 1 (single user)
- `output_segmentation_masks`: false (not needed for form checking)

**KMP Integration Pattern:**
MediaPipe is Android-only. Use `expect/actual` in the existing pattern:

```kotlin
// commonMain: Interface for pose estimation (platform-agnostic)
expect class PoseEstimator {
    fun isAvailable(): Boolean
    // Returns landmarks as domain model, not MediaPipe types
}

// androidMain: Actual implementation wrapping MediaPipe
actual class PoseEstimator(private val context: Context) {
    actual fun isAvailable(): Boolean = true
    // Uses PoseLandmarker internally
}

// iosMain: Stub that returns unavailable
actual class PoseEstimator {
    actual fun isAvailable(): Boolean = false
}
```

**Where It Goes:** `shared/build.gradle.kts` in `androidMain` dependencies (NOT commonMain). MediaPipe is an Android AAR library.

### 2. CameraX with Compose Support (Camera Pipeline)

| Technology | Version | Artifact | Purpose | Confidence |
|------------|---------|----------|---------|------------|
| CameraX Core | 1.5.3 | `androidx.camera:camera-core` | Camera abstraction layer | HIGH |
| CameraX Camera2 | 1.5.3 | `androidx.camera:camera-camera2` | Camera2 implementation | HIGH |
| CameraX Lifecycle | 1.5.3 | `androidx.camera:camera-lifecycle` | Lifecycle-aware camera binding | HIGH |
| CameraX Compose | 1.5.3 | `androidx.camera:camera-compose` | CameraXViewfinder composable | HIGH |

**Why CameraX 1.5.3:**
- Stable release (January 28, 2026) -- not alpha/beta
- Native `camera-compose` artifact with `CameraXViewfinder` composable (no AndroidView wrapper needed)
- `CameraXViewfinder` handles Surface lifecycle, rotation, scaling automatically
- `ImageAnalysis` use case integrates directly with MediaPipe's LIVE_STREAM mode
- Backpressure strategy `STRATEGY_KEEP_ONLY_LATEST` prevents frame queue buildup during ML inference

**What We Do NOT Need:**
- `camera-view`: Only for XML-based PreviewView. We use `camera-compose` instead
- `camera-extensions`: HDR/Night mode, irrelevant for pose estimation
- `camera-video`: No video recording needed
- `camera-effects`: Overlays are done in Compose Canvas, not camera effects pipeline

**Pipeline Architecture:**
```
CameraX Preview --> CameraXViewfinder (Compose)
CameraX ImageAnalysis --> MediaPipe PoseLandmarker (LIVE_STREAM)
                       --> Landmark results via callback
                       --> Compose Canvas overlay (skeleton + form warnings)
```

**Key Configuration:**
- `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST` -- drops frames when ML is busy
- `OUTPUT_IMAGE_FORMAT_RGBA_8888` -- matches MediaPipe's expected input
- Front camera default (user facing for form checking)
- 640x480 target resolution for ImageAnalysis (balance speed vs. accuracy)

**Where It Goes:** `shared/build.gradle.kts` in `androidMain` dependencies. CameraX is Android-only.

### 3. Permission Handling (Camera Access)

| Technology | Version | Artifact | Purpose | Confidence |
|------------|---------|----------|---------|------------|
| Accompanist Permissions | 0.37.3 | `com.google.accompanist:accompanist-permissions` | Compose-idiomatic runtime permissions | MEDIUM |

**Why Accompanist over ActivityResultContracts:**
- `rememberPermissionState()` integrates naturally with Compose recomposition
- Declarative permission UI -- check status, show rationale, launch request in composable flow
- Project already uses Compose for all UI; mixing imperative Activity result APIs is inconsistent
- Still experimental (`@OptIn(ExperimentalPermissionsApi::class)`) but widely used and stable in practice

**Alternative Considered:** Raw `ActivityResultContracts.RequestPermission()` via `rememberLauncherForActivityResult()`. This works but requires more boilerplate for rationale dialogs and state management. If Accompanist causes issues with Compose Multiplatform 1.10.0, fall back to this.

**Permissions Required:**
- `android.permission.CAMERA` -- runtime permission for pose estimation camera feed
- No storage permissions needed (model bundled in assets, no photo capture)

**Where It Goes:** `shared/build.gradle.kts` in `androidMain` dependencies OR `androidApp/build.gradle.kts`. Recommend androidApp since permissions are UI-layer concerns.

### 4. Biomechanics Persistence -- NO New Libraries Needed

**Verdict: Zero new dependencies.** The existing stack handles everything:

| Need | Existing Solution |
|------|-------------------|
| Per-rep VBT data storage | New columns on RepMetric table via SQLDelight migration 15.sqm |
| Force curve persistence | JSON-encoded FloatArray in TEXT columns (existing pattern from RepMetric) |
| Asymmetry data | New columns on RepMetric table |
| Set-level biomechanics summary | New BiomechanicsSetResult table or columns on WorkoutSession |
| Data serialization | Manual JSON serialization (existing pattern, see RepMetricRepository) |

**Migration Strategy:**
- Add `15.sqm` migration file with ALTER TABLE statements
- Add new columns to RepMetric: `mcvMmS REAL`, `peakVelocityMmS REAL`, `velocityZone TEXT`, `velocityLossPercent REAL`, `normalizedForceCurve TEXT` (JSON FloatArray), `stickingPointPct REAL`, `strengthProfile TEXT`, `asymmetryPercent REAL`, `dominantSide TEXT`, `avgLoadA REAL`, `avgLoadB REAL`
- Increment database version to 16
- Follow existing pattern: nullable columns for backward compatibility, JSON for arrays

**Why NOT a separate table:**
RepMetric already stores per-rep data with the same sessionId/repNumber granularity. Adding biomechanics columns to RepMetric keeps queries simple (single JOIN) and matches the existing pattern. A separate BiomechanicsRepMetric table would duplicate the sessionId/repNumber foreign key pattern and require extra JOINs.

### 5. Ghost Racing / RPG / Readiness Briefing UI -- NO New Libraries Needed

**Verdict: Zero new dependencies.** All UI components use existing Compose Multiplatform stack.

| Feature | Compose Solution | Existing Dependency |
|---------|-----------------|---------------------|
| Ghost Racing overlay | `Canvas` composable with `drawLine`/`drawCircle` for race progress | compose.foundation |
| Ghost Racing animation | `InfiniteTransition` / `animateFloatAsState` for running indicators | compose.animation (included in compose.foundation) |
| RPG Attribute Card | `Card` + `Column` + `LinearProgressIndicator` for stat bars | compose.material3 |
| RPG Radar Chart | Custom `Canvas` with `drawPath` for hexagonal/pentagonal chart | compose.foundation |
| Readiness Briefing | `LazyColumn` with `Card` items, `CircularProgressIndicator` for scores | compose.material3 |
| Skeleton overlay | `Canvas` composable over `CameraXViewfinder` using `Box` stacking | compose.foundation |
| Lottie animations | Compottie (already in project) for celebration effects | compottie 2.0.2 |

**Stub Data Pattern:**
All three composables (ghost racing, RPG, readiness) will accept domain model data classes as parameters. Until the portal backend ships in v0.6.0, hardcoded preview data drives the UI. This is the same pattern used for `@Preview` composables throughout the project.

```kotlin
// Data classes in commonMain (stub-friendly)
data class GhostRaceState(
    val myProgress: Float,        // 0.0 - 1.0
    val ghostProgress: Float,
    val exerciseName: String,
    val ghostLabel: String         // "Your PR" or "Community Avg"
)

data class RpgAttributeCard(
    val strength: Int,    // 1-100
    val endurance: Int,
    val power: Int,
    val consistency: Int,
    val technique: Int
)

data class ReadinessBriefing(
    val overallScore: Int,        // 0-100
    val fatigueLevel: String,
    val suggestedExercises: List<String>,
    val streakDays: Int,
    val lastSessionSummary: String
)
```

## Alternatives Considered

| Category | Recommended | Alternative | Why Not |
|----------|-------------|-------------|---------|
| Pose Estimation | MediaPipe Pose Landmarker | ML Kit Pose Detection | ML Kit uses MediaPipe internally, provides only 2D landmarks. MediaPipe gives 3D world coordinates essential for biomechanical angle calculation. |
| Pose Estimation | MediaPipe Pose Landmarker | TensorFlow Lite PoseNet | Older model, lower accuracy, no built-in hand/foot landmarks. MediaPipe is Google's recommended replacement. |
| Camera | CameraX 1.5.3 camera-compose | Camera2 API directly | Camera2 is low-level, requires extensive lifecycle management. CameraX abstracts this with compose-native support. |
| Camera Compose | camera-compose CameraXViewfinder | AndroidView wrapping PreviewView | AndroidView is a workaround. camera-compose 1.5.3 is stable and purpose-built. |
| Permissions | Accompanist 0.37.3 | ActivityResultContracts | Works but requires more boilerplate. Accompanist is idiomatic Compose. Experimental but stable in practice. |
| Biomechanics DB | ALTER TABLE on RepMetric | New BiomechanicsRepMetric table | Extra JOINs, duplicated foreign keys. Same granularity as RepMetric. |
| Force Curve Storage | JSON TEXT column | BLOB column | JSON is human-readable, debuggable, and follows existing project pattern for FloatArray storage. |
| Charts (RPG radar) | Custom Canvas composable | Vico Charts (already in project) | Vico is for standard line/bar charts. Radar/hexagonal charts need custom drawing. Canvas is trivial for this. |
| Ghost Racing Anim | Compose animation APIs | Compottie Lottie | Overkill for simple progress bar animation. Compose APIs are sufficient. Reserve Compottie for celebration effects. |

## Libraries Explicitly NOT Adding

| Library | Why Skip |
|---------|----------|
| **ML Kit Pose Detection** | Uses MediaPipe internally. Going directly to MediaPipe avoids the wrapper and gives access to 3D world coordinates. |
| **TensorFlow Lite** | MediaPipe bundles TFLite internally. No need for separate dependency. |
| **ARCore** | 3D pose in world space is NOT needed. MediaPipe's image-plane landmarks + world coordinates are sufficient for joint angle calculation. ARCore would add complexity (ARSession, depth sensing) with no benefit. |
| **OpenCV** | Not needed. MediaPipe handles all image processing internally. Joint angle calculation is basic trigonometry on landmark coordinates. |
| **kotlinx-serialization for DB** | Project uses manual JSON serialization for FloatArray columns. This is a documented decision (see Models.kt key decisions). Don't change it. |
| **Room** | Project uses SQLDelight for multiplatform. Adding Room would create two competing DB frameworks. |
| **Accompanist Navigation Animation** | Project uses Compose Multiplatform navigation, not AndroidX Navigation Compose. Accompanist nav animations are incompatible. |
| **camera-mlkit-vision** | CameraX ML Kit integration artifact. Not needed since we use MediaPipe directly, not through ML Kit. |
| **camera-effects** | For camera-pipeline overlays (watermarks, etc.). Our skeleton overlay is Compose Canvas on top of the viewfinder, not a camera effect. |

## Installation

### Version Catalog Additions (`gradle/libs.versions.toml`)

```toml
[versions]
# ... existing versions ...
mediapipe = "0.10.32"
camerax = "1.5.3"
accompanist-permissions = "0.37.3"

[libraries]
# ... existing libraries ...

# MediaPipe Pose Estimation (Android only)
mediapipe-tasks-vision = { module = "com.google.mediapipe:tasks-vision", version.ref = "mediapipe" }

# CameraX (Android only - for CV camera pipeline)
camerax-core = { module = "androidx.camera:camera-core", version.ref = "camerax" }
camerax-camera2 = { module = "androidx.camera:camera-camera2", version.ref = "camerax" }
camerax-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "camerax" }
camerax-compose = { module = "androidx.camera:camera-compose", version.ref = "camerax" }

# Permissions (Android only - for camera runtime permission)
accompanist-permissions = { module = "com.google.accompanist:accompanist-permissions", version.ref = "accompanist-permissions" }
```

### Shared Module (`shared/build.gradle.kts`)

```kotlin
val androidMain by getting {
    dependencies {
        // ... existing dependencies ...

        // MediaPipe Pose Estimation (CV form checking)
        implementation(libs.mediapipe.tasks.vision)

        // CameraX (camera pipeline for pose estimation)
        implementation(libs.camerax.core)
        implementation(libs.camerax.camera2)
        implementation(libs.camerax.lifecycle)
        implementation(libs.camerax.compose)
    }
}
```

### Android App Module (`androidApp/build.gradle.kts`)

```kotlin
dependencies {
    // ... existing dependencies ...

    // Permissions for camera access
    implementation(libs.accompanist.permissions)
}
```

### Model Asset

Download `pose_landmarker_lite.task` from Google's MediaPipe model repository and place in:
```
shared/src/androidMain/assets/pose_landmarker_lite.task
```

Alternatively, use the `download_models.gradle.kts` pattern to fetch at build time (avoids large binary in git).

### AndroidManifest.xml

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
```

Note: `required="false"` because the app works without a camera (camera is only for optional CV form checking).

## Integration Points with Existing Architecture

### Koin DI Module

New dependencies slot into the existing 4-module DI structure:

```kotlin
// di/modules/DomainModule.kt (existing)
val domainModule = module {
    // ... existing engines ...

    // New: CV pose estimation
    single { PoseEstimator(androidContext()) }  // expect/actual
    single { FormRulesEngine() }                // Pure commonMain
    single { FormScorer() }                     // Pure commonMain
}
```

### Data Flow for CV Form Checking

```
CameraX ImageAnalysis
    --> PoseEstimator (androidMain, wraps MediaPipe)
    --> PoseLandmarks (commonMain domain model, 33 landmarks with x,y,z,visibility)
    --> FormRulesEngine (commonMain, exercise-specific angle thresholds)
    --> FormFeedback (commonMain domain model: warnings, score, corrections)
    --> Compose UI overlay (skeleton + warning badges)
```

### FeatureGate Integration

New features need Feature enum entries:

```kotlin
enum class Feature {
    // ... existing features ...

    // v0.5.0 additions
    CV_FORM_CHECK,      // Elite tier
    GHOST_RACING,       // Elite tier
    RPG_ATTRIBUTES,     // Phoenix tier
    READINESS_BRIEFING  // Elite tier
}
```

### Database Schema Integration

Biomechanics columns added to RepMetric via migration 15.sqm. The BiomechanicsEngine already produces `VelocityResult`, `ForceCurveResult`, `AsymmetryResult` -- these map directly to new RepMetric columns.

## ProGuard / R8 Considerations

MediaPipe uses native libraries (.so files) and TFLite models. Add to `proguard-rules.pro`:

```
# MediaPipe
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
```

CameraX is already part of AndroidX and handles ProGuard automatically.

## APK Size Impact

| Addition | Estimated Size | Notes |
|----------|---------------|-------|
| MediaPipe tasks-vision AAR | ~3-5 MB | Includes TFLite runtime |
| MediaPipe native .so libs | ~5-8 MB (per ABI) | arm64-v8a primarily |
| pose_landmarker_lite.task | ~5 MB | Model file in assets |
| CameraX libraries | ~2-3 MB | Shared with other AndroidX |
| Accompanist Permissions | ~100 KB | Minimal |
| **Total estimated increase** | **~15-20 MB** | With ABI splits: ~10-13 MB |

Recommend enabling ABI splits in `androidApp/build.gradle.kts` if not already configured to avoid shipping all native architectures:

```kotlin
android {
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
}
```

## Sources

### MediaPipe
- [MediaPipe Pose Landmarker Android Guide](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker/android) - Official setup, API, landmarks (HIGH confidence)
- [MediaPipe Pose Landmarker Overview](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker) - Model variants, configuration options (HIGH confidence)
- [MediaPipe Releases](https://github.com/google-ai-edge/mediapipe/releases) - v0.10.32 latest (HIGH confidence)
- [MediaPipe Android Setup Guide](https://ai.google.dev/edge/mediapipe/solutions/setup_android) - Google Maven distribution (HIGH confidence)
- [MediaPipe Samples - Pose Landmarker](https://github.com/google-ai-edge/mediapipe-samples/blob/main/examples/pose_landmarker/android/app/build.gradle) - Reference implementation (HIGH confidence)
- [RepDetect - MediaPipe Pose for Exercise](https://github.com/giaongo/RepDetect) - Fitness app using MediaPipe Pose (MEDIUM confidence, community project)
- [MediaPiper KMP Project](https://github.com/2BAB/mediapiper) - KMP expect/actual pattern for MediaPipe (MEDIUM confidence)

### CameraX
- [CameraX Release Notes](https://developer.android.com/jetpack/androidx/releases/camera) - v1.5.3 stable, camera-compose stable (HIGH confidence)
- [CameraX Compose-Native Stable Guide](https://proandroiddev.com/goodbye-androidview-camerax-goes-full-compose-4d21ca234c4e) - CameraXViewfinder patterns (MEDIUM confidence)
- [CameraX 1.5 Announcement](https://android-developers.googleblog.com/2025/11/introducing-camerax-15-powerful-video.html) - Feature overview (HIGH confidence)
- [AI Vision: CameraX + MediaPipe + Compose](https://www.droidcon.com/2025/01/24/ai-vision-on-android-camerax-imageanalysis-mediapipe-compose/) - Integration pattern (MEDIUM confidence)

### Permissions
- [Accompanist Permissions](https://google.github.io/accompanist/permissions/) - Official docs, v0.37.3 (HIGH confidence)

### Compose Animation / Canvas
- [Compose Animation Guide](https://developer.android.com/develop/ui/compose/animation/introduction) - InfiniteTransition, animateAsState (HIGH confidence)
- [Compose Canvas Graphics](https://developer.android.com/develop/ui/compose/graphics/draw/overview) - drawLine, drawCircle, drawPath (HIGH confidence)
- [Pose Detection with Compose Canvas Overlay](https://pradyotprksh4.medium.com/pose-detection-in-android-with-ml-kit-jetpack-compose-real-time-pose-skeleton-ab9553f96587) - Skeleton overlay pattern (MEDIUM confidence)

### SQLDelight Migrations
- [SQLDelight Migrations Guide](https://sqldelight.github.io/sqldelight/2.0.2/multiplatform_sqlite/migrations/) - ALTER TABLE pattern (HIGH confidence)
