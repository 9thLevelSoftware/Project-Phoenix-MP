# Utilities Part 3 Review

Scope reviewed: UI utilities, clipboard, coroutine cancellation, locale, locking, running averages, screen/settings helpers, and URI/file helper expect declarations.

Files reviewed:
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/ClipboardUtils.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/ColorScheme.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/CoroutineCancellation.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/FilePicker.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/HealthPermissionRequester.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/HealthPermissionSettingsLauncher.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/KmpUtils.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/LocaleHelper.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/LocalizedDecimal.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Locking.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/RGBColor.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/RunningAverage.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/ScreenUtils.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/UriContentReader.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/AppSettingsOpener.kt`

Summary:
- Findings: 8
- Severity breakdown: critical 0, high 0, medium 5, low 3
- Category breakdown: bug 3, stub 1, error 0, failure-point 4
- TODO/FIXME/HACK markers in assigned files: none found

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/ClipboardUtils.kt`

No findings.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/ColorScheme.kt`

No findings.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/CoroutineCancellation.kt`

No findings.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/FilePicker.kt`

No findings.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/HealthPermissionRequester.kt`

### Finding 1
- Category: failure-point
- Severity: low
- Line numbers: 8-13
- Description: The common declaration still documents iOS as a no-op (`iOS currently no-ops because HealthKit is not wired up yet`), but the current iOS actual implementation injects `HealthIntegration` and calls `requestPermissions()`. This stale contract is a low-level integration hazard: callers and future reviewers can incorrectly assume iOS permission requests never run, add duplicate platform workarounds, or skip handling the real asynchronous iOS permission result.
- Suggested fix direction: Update the common KDoc to describe the current platform contract rather than historical implementation status. If platform support can vary, document the expected callback semantics for success, denial, cancellation, and unsupported platforms.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/HealthPermissionSettingsLauncher.kt`

### Finding 2
- Category: stub
- Severity: medium
- Line numbers: 6-13
- Description: The common API promises a bridge for opening Phoenix's health-permissions settings, and the KDoc specifically says recovery requires sending the user to the Health Connect app-permissions screen. The API returns `Unit` and has no success/failure callback, so callers cannot tell when the platform implementation cannot open anything. This matters because the iOS actual is currently a no-op and Android settings intents can also fail if the target package/activity is unavailable, leaving the user in the same recovery state with no fallback UI.
- Suggested fix direction: Make the contract report whether settings were actually launched, for example `openSettings(): Boolean` or `openSettings(onResult: (Boolean) -> Unit)`, and require unsupported platforms to return/report `false` so the caller can show manual instructions.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/KmpUtils.kt`

### Finding 3
- Category: bug
- Severity: medium
- Line numbers: 232-236
- Description: `formatFloat()` builds the decimal part from `rounded - intPart`, but `toLong()` truncates negative values toward zero. Negative values with decimal places therefore format with a negative fractional component, e.g. the current algorithm formats `-1.23` with two decimals as `-1.-23` and `-0.25` as `0.-25`. Any UI using this helper for signed deltas, percentages, velocities, or corrections can display malformed numbers.
- Suggested fix direction: Format the absolute fractional component separately from the sign, or use a locale-stable multiplatform formatter that rounds the whole scaled integer first and then reconstructs sign, integer, and padded fractional digits from absolute values. Add tests for negative values such as `-1.23`, `-5.5`, and `-0.25`.

### Finding 4
- Category: bug
- Severity: medium
- Line numbers: 245, 283
- Description: `formatDouble()` downcasts every `Double` to `Float` before formatting. This loses precision for normal `Double` values and turns large finite doubles outside the `Float` range into infinities, which then hit the non-finite guard and display as zero. The `Double.format()` extension exposes this behavior broadly, so any future Double-valued analytics/statistics display can silently show rounded or completely wrong values.
- Suggested fix direction: Implement `formatDouble()` using Double arithmetic end-to-end and a Double finite check rather than delegating through Float. Add regression tests with a high-precision value and a finite value greater than `Float.MAX_VALUE` to prevent silent zeroing.

### Finding 5
- Category: failure-point
- Severity: low
- Line numbers: 159-168
- Description: `formatRelativeTimestamp()` does not handle future timestamps. When `timestamp > now`, `diffMs` and `diffMinutes` become negative and the first branch (`diffMinutes < 1`) returns `Just now`. Clock skew, imported external activity data, scheduled records, or server-provided timestamps can therefore be mislabeled instead of shown as future or invalid.
- Suggested fix direction: Add an explicit future-time branch before the existing past-time thresholds, such as returning `In Xm`, `In Xh`, or falling back to an absolute date for any negative difference beyond a small clock-skew tolerance.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/LocaleHelper.kt`

No findings.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/LocalizedDecimal.kt`

### Finding 6
- Category: bug
- Severity: medium
- Line numbers: 26, 52-84
- Description: The parser treats a lone `.` as a decimal separator unconditionally. That makes pasted/grouped German, Italian, Spanish, or other European values such as `1.000 kg` parse as `1.0` instead of `1000.0`, even though the same helper already treats `1,000` as thousands when a lone comma has exactly three trailing digits. This asymmetry can silently shrink imported or pasted numeric input by three orders of magnitude.
- Suggested fix direction: Apply the same grouping heuristic to lone dots, or make the parser locale-aware so `.` can be treated as a group separator in decimal-comma locales. Add tests for `1.000`, `1.000 kg`, and `1.000,5` to lock in the intended behavior.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Locking.kt`

### Finding 7
- Category: failure-point
- Severity: low
- Line numbers: 3-7
- Description: The common contract and KDoc imply that the `lock` argument controls the critical section and that Native may fall back to direct execution. Current platform behavior is not that simple: Android uses the supplied monitor, while iOS uses a single global lock and ignores the supplied `lock`. Callers that assume two different lock objects allow independent progress will behave differently across platforms, potentially causing unexpected serialization/jank on iOS or insufficient documentation around lock ordering.
- Suggested fix direction: Update the common KDoc to state the exact cross-platform semantics and limitations. If per-object locking is required by callers, introduce a platform abstraction that guarantees per-lock isolation on every target; otherwise rename/document this as a global/native-compatible serialization helper.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/RGBColor.kt`

No findings.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/RunningAverage.kt`

### Finding 8
- Category: failure-point
- Severity: medium
- Line numbers: 20-30
- Description: `add()` accepts non-finite `Float` values without validation. A single `NaN`, `Infinity`, or `-Infinity` poisons `sum`, and every later `average()` returns a non-finite value until `reset()` is called. The primary caller found in `RepQualityScorer` uses this accumulator for ROM and velocity baselines, so corrupt sensor data can degrade every subsequent rep score in the set.
- Suggested fix direction: Reject or ignore non-finite samples in `add()`, and decide whether they should increment `count`. Add tests covering `NaN`/infinite input and the intended recovery behavior after a bad sample.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/ScreenUtils.kt`

No findings.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/UriContentReader.kt`

No findings.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/AppSettingsOpener.kt`

No findings.
