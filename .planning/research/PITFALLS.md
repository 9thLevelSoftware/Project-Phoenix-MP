# Pitfalls Research

**Domain:** Adding ghost racing, RPG gamification, readiness heuristics, WCAG accessibility, HUD customization, and security hardening to an existing complex KMP fitness app
**Researched:** 2026-02-27
**Confidence:** HIGH (codebase analysis + verified external sources)

---

## Critical Pitfalls

Mistakes that cause crashes, data loss, regressions in the existing 2600L ActiveSessionEngine, or require structural rewrites.

---

### Pitfall 1: Ghost Racing State Desynchronizes from Live Workout State

**What goes wrong:**
Ghost racing compares the current live workout (driven by `ActiveSessionEngine` BLE metrics at 10-20 Hz) against a stored session (loaded from `MetricSample` or `RepBiomechanics` DB rows). The two data streams have fundamentally different time bases: live data arrives in absolute wall-clock timestamps from BLE hardware; stored data has stored timestamps from a past session. Naive replay maps stored timestamps to "elapsed from session start," but the live session starts the clock at workout begin — not at the first BLE packet. Any offset between these references causes the ghost to run ahead of or behind the live athlete from the first frame, and the delta never recovers.

**Why it happens:**
Developers assume both streams share the same epoch because both use `currentTimeMillis()`. They don't. The live session's elapsed time is `currentTimeMillis() - sessionStartMs`, where `sessionStartMs` is set when the user hits Start. The ghost's elapsed time is `sampleTimestamp - ghostSessionStartMs`. If the ghost session was started at a slightly different workout phase (e.g., machine was already pulled before Start was pressed), the offset is baked in. Additionally, BLE timestamps from the Vitruvian hardware have their own clock domain — `MetricSample.timestamp` is the Android-side receive timestamp, not a hardware timestamp, so jitter is present.

**How to avoid:**
- Align both streams on **rep number, not timestamp**. Use rep-boundary events as synchronization anchors. At rep N, compute the ghost's position as the interpolated value from its rep N start to rep N end proportional to the live rep's fractional completion.
- Provide a **configurable alignment delay** (0–3 seconds, defaulting to 0) to handle cases where the user grabs handles before the ghost session started.
- Store ghost sessions by **rep index** (which rep within a set), not by timestamp, so alignment is structural rather than arithmetic.
- If timestamp-based replay is used anyway, normalize both streams to elapsed-from-first-sample, not elapsed-from-session-start.

**Warning signs:**
- Ghost delta bar immediately shows max lead or max lag from rep 1.
- The ghost "completes" the set while the live athlete is still on rep 3.
- Rep-aligned UIs (dual progress bars) show inconsistent advancement rates.

**Phase to address:** Ghost racing implementation phase. Define the synchronization contract before writing any UI code.

---

### Pitfall 2: Ghost Racing Adds a Third Real-Time Data Consumer to an Already-Loaded Main Thread

**What goes wrong:**
`ActiveSessionEngine` already drives BLE metric collection, BiomechanicsEngine computation, RepQualityScorer, rep boundary detection, auto-stop heuristics, LED feedback, exercise signature extraction — all on `scope.launch` coroutines. Ghost racing adds a comparator that must run every BLE tick to update the velocity delta display. If this comparator does DB reads inline (to look up the ghost's metric at this elapsed time), it stalls the BLE processing hot path.

**Why it happens:**
The ghost session needs its per-sample data accessible at real-time rates. Developers reach for `VitruvianDatabase.metricSampleQueries.selectBySession(sessionId)` inside the BLE callback. SQLDelight queries are synchronous by default in commonMain. A DB read inside `handleMonitorMetric()` — which already runs BiomechanicsEngine — blocks the collection coroutine.

**How to avoid:**
- Pre-load the entire ghost session's `MetricSample` list into memory at workout start. At 60 seconds of data at 20 Hz, this is ~1200 rows. At 4 floats per row, this is ~19KB — negligible.
- Use a binary search (or pre-indexed array) to find the ghost's interpolated metric for a given elapsed time. No DB I/O during the active session.
- Keep the ghost comparator as a **pure function** that takes the pre-loaded list and current elapsed time and returns the delta. No coroutines, no suspension, no flows in the hot path.
- Add the ghost comparator as a step in the existing `handleMonitorMetric()` chain, not as an additional `scope.launch` collector.

**Warning signs:**
- BLE metric processing time (measured between consecutive `handleMonitorMetric()` invocations) increases when ghost racing is active.
- Rep count lags or missed reps correlate with ghost overlay visibility.
- Logcat shows SQLDelight "running on main thread" warnings.

**Phase to address:** Ghost racing implementation phase, specifically the data loading strategy.

---

### Pitfall 3: RPG Attributes Computed on Every DB Read, Not on Invalidation Events

**What goes wrong:**
RPG attributes (Power, Endurance, Velocity, Consistency, Balance — or similar 5-stat model) are derived values from historical workout data. If they are recomputed by querying the full workout history every time the RPG card composable collects from a repository Flow, the computation runs on every recomposition trigger, on every database observation, and on every navigation event. In a codebase that already has 6 Flow collectors active during a session (BLE metrics, handle state, deload events, rep events, heuristic data, routine state), adding another global Flow for RPG stats that recomputes on every write to `WorkoutSession` creates quadratic overhead at the data layer.

**Why it happens:**
Repository Flows in SQLDelight emit on any write to the observed table. `WorkoutSession` is written every time a set completes. The RPG attribute computation reads not just `WorkoutSession` but also `RepBiomechanics`, `PersonalRecord`, and `GamificationStats`. Each of these tables emits independently. A single set completion triggers 4 separate emissions, each recomputing all 5 attributes from scratch.

**How to avoid:**
- **Compute RPG attributes once per post-session event**, not continuously. Trigger recomputation in `GamificationManager.processPostSaveEvents()` after session save — the same place XP and badges are awarded.
- Cache the computed attributes in a dedicated column set or a serialized JSON blob in `GamificationStats`. This singleton row (ID=1) already exists; add 5 attribute columns to it via a schema migration.
- Expose attributes as a `StateFlow<RpgAttributes>` in the repository, not as a reactive query Flow. Update it only when `processPostSaveEvents` runs.
- Do **not** derive RPG stats from a live reactive query on multiple tables. This creates an invisible performance trap that worsens with workout history size.

**Warning signs:**
- AnalyticsScreen or profile screen takes >300ms to open when workout history is large.
- SQLite query log shows repeated multi-table reads within milliseconds of each other.
- GamificationStats is read and written in rapid succession after each set completion.

**Phase to address:** RPG attributes domain design phase, before any schema changes.

---

### Pitfall 4: GamificationStats Singleton Row (ID=1) Schema Bloat Breaking the Pattern

**What goes wrong:**
`GamificationStats` uses a hardcoded ID=1 singleton row, pre-fetched with `selectGamificationStats()` and upserted. This pattern works for a small set of scalar aggregate stats (XP, streak days, total workouts). Adding 5 RPG attribute scores, a character class string, attribute timestamps, and confidence levels to this same row turns a clean singleton into a fat document row. When `upsertGamificationStats` is called, it must supply ALL columns, including the new RPG ones — any caller that was written before the migration will silently zero out the new columns unless every call site is updated simultaneously.

**Why it happens:**
The upsert pattern for a singleton row requires knowing all column values at write time. Adding new columns means every `upsertGamificationStats()` call site must be updated to pass the new values, or the upsert will overwrite them with defaults/nulls. In a 2600L `ActiveSessionEngine` with multiple call paths that save partial stats updates, it is easy to miss one.

**How to avoid:**
- **Do not add RPG attributes to `GamificationStats`**. Create a separate `RpgAttributes` table with a similar singleton row pattern (ID=1). This isolates the write paths completely — RPG attributes are only written by the RPG computation function, not by workout session save paths.
- If adding columns to `GamificationStats` is unavoidable (e.g., for aggregate inputs), add them as `DEFAULT NULL` so existing upsert callers are not affected. Write a migration that adds the columns with nullable defaults.
- Add a characterization test that verifies `GamificationStats` after a workout session save: the test should assert that all existing fields (XP, streak, etc.) are preserved and the new fields have expected post-computation values.
- Daem0n warning #155 applies: iOS `DriverFactory.ios.kt` uses a no-op schema pattern (Layer 1: No-Op Schema). Adding new columns to `GamificationStats` means the iOS schema sync layer must be updated manually, or iOS users will experience missing columns on fresh install.

**Warning signs:**
- After adding RPG columns, existing integration tests that check gamification stats start failing with wrong XP values (a sign that a call site is zeroing the new columns on upsert).
- iOS builds fail to read RPG attributes (null where values expected) after first workout.
- `updateStats()` in `GamificationRepository` resets RPG attribute columns on each workout session.

**Phase to address:** RPG domain design phase — schema decision must precede any implementation.

---

### Pitfall 5: FeatureGate Missing Enum Entries Causes Compile-Time Silence, Runtime Gate Bypass

**What goes wrong:**
`FeatureGate.Feature` currently has 12 entries covering existing features. The milestone adds at least 3 new gated features: `CV_FORM_CHECK` (Phoenix tier), `RPG_ATTRIBUTES` (Phoenix tier), and `READINESS_BRIEFING` (Elite tier). If these are not added to the enum AND to `phoenixFeatures`/`eliteFeatures` sets before the UI code that calls `FeatureGate.isEnabled()`, callers will fail to compile — which is the correct behavior. The risk is the inverse: UI code that skips the gate entirely because the enum entry doesn't exist yet, implementing the feature as unconditionally visible and shipping it ungated.

**Why it happens:**
During rapid iteration across many files, developers wire up the composable first and add the gate later. In a large branch (51K lines added), a composable that shows without a gate check can be missed in PR review because it looks "feature complete." The Board of Directors conditions explicitly require tier gating for CV Form Check (Phoenix), RPG (Phoenix), and Readiness (Elite) — missing a gate is a Board condition failure.

**How to avoid:**
- Add all new `Feature` enum entries AND their tier set membership (`phoenixFeatures` or `eliteFeatures`) as the **first commit** of the milestone, before any UI code is written.
- Add a unit test that asserts: for each Feature in `Feature.values()`, `isEnabled(feature, FREE)` returns false. This prevents any new feature from accidentally defaulting to unrestricted access.
- The existing `FeatureGateTest.kt` should be extended to cover all 3 new features with explicit tier checks.
- Make the FeatureGate check the entry point of each new composable, not an afterthought.

**Warning signs:**
- New composable is visible to FREE tier users in testing.
- `FeatureGate.isEnabled()` is called with a feature not yet in the enum (compilation error — good, catch it).
- iOS upgrade prompt includes Form Check in the Phoenix tier marketing copy (Board condition: it should not, since iOS CV is deferred).

**Phase to address:** Feature gate extension phase — must be Phase 1 of the milestone.

---

### Pitfall 6: WCAG Accessibility Retrofit Breaks Color-Coded Indicators Without Visual Fallback

**What goes wrong:**
The app uses color as the sole semantic indicator in 4 critical UI components: velocity zone color coding (4 zones: OFF/Green/Blue/Red at 5/30/60 mm/s thresholds), the L/R balance bar (green/yellow/red by asymmetry severity), the readiness card (green/amber/red traffic light), and the form score display. These are primary workout feedback channels. Adding `contentDescription` alone is insufficient for WCAG 1.4.1 (Use of Color): screen readers can describe colors, but colorblind users who do not use TalkBack cannot distinguish the zones.

**Why it happens:**
Developers conflate accessibility for blind users (TalkBack/contentDescription) with accessibility for colorblind users (WCAG 1.4.1 pattern redundancy). Adding a contentDescription to a green circle satisfies TalkBack but does nothing for a protanope who cannot distinguish red from green balance bar states. The Board of Directors condition specifically calls out WCAG color-blind fallbacks for velocity zones, balance bar, and readiness card — meaning color-blind users must be able to distinguish states without relying on hue.

**How to avoid:**
- Add **secondary indicators** (shape, icon, pattern, or label) alongside color for every color-coded state:
  - Velocity zones: add a zone label ("SLOW", "MODERATE", "FAST", "EXPLOSIVE") as text or as a textual icon overlay. Do not rely only on background color.
  - Balance bar: add a numeric percentage alongside the color. "L 58% / R 42%" is readable regardless of color perception.
  - Readiness card: add an icon (circle, triangle, exclamation) or text ("READY", "CAUTION", "REST") alongside the traffic-light color.
  - Form score: the 0-100 numeric value is already color-independent; ensure it is always visible alongside any color indicator.
- Use `Modifier.semantics { contentDescription = "..." }` for TalkBack, but treat it as additive — not a substitute for visual redundancy.
- Test with Android's "Simulate color space: Monochromacy" developer option to verify no state is indistinguishable in grayscale.

**Warning signs:**
- All color-coded states look identical in a grayscale screenshot.
- TalkBack reads "Green" without any contextual meaning ("Green: you are in the moderate velocity zone" is better).
- Board condition review finds color-coded states with no text or shape fallback.

**Phase to address:** WCAG retrofit phase. Apply to all existing color-coded components first, then carry the pattern forward into new composables (readiness card, RPG attribute bars).

---

### Pitfall 7: Compose Semantics Applied to Canvas Drawing Composables Are Ignored by TalkBack

**What goes wrong:**
`ForceCurveMiniGraph`, `BalanceBar`, and the ghost racing dual progress bar are custom Canvas composables. `Modifier.semantics` applied to a Canvas node is passed to the accessibility tree, but the Canvas's internal drawing primitives are invisible to TalkBack. A `Box` with `drawBehind` or a `Canvas` composable with `Modifier.semantics { contentDescription = "..." }` will be read by TalkBack once as the whole component, but sub-elements drawn with `drawLine`, `drawArc`, or `drawRect` are invisible to the accessibility tree.

**Why it happens:**
Developers apply `Modifier.semantics` to the Canvas `Modifier` parameter expecting it to expose internal drawing state. TalkBack sees only the root node. The velocity bar, the ghost delta indicator, and the asymmetry visualization are all drawn inside Canvas — adding semantics to the Canvas wrapper will not make individual drawn elements focusable.

**How to avoid:**
- Apply `Modifier.semantics { contentDescription = "Balance bar: 58% left, 42% right. Moderate asymmetry." }` to the wrapper Box or Column around the Canvas — not inside the Canvas modifier. The description should encode the semantic state, not the visual representation.
- For the ghost racing velocity delta bar, describe the state in words: "Ghost delta: ahead by 12 millimeters per second" not "Blue bar, medium length."
- Do not attempt to make Canvas sub-elements individually focusable — instead, compose accessible text labels adjacent to Canvas components and use `Modifier.semantics { clearAndSetSemantics { } }` on decorative Canvas elements to suppress them from the accessibility tree entirely.

**Warning signs:**
- TalkBack reads "Unlabeled image" or skips Canvas components entirely during navigation.
- Accessibility scanner reports Canvas elements with no contentDescription.
- `clearAndSetSemantics` is missing on decorative Canvas drawings, causing TalkBack to land focus on an element with no meaningful description.

**Phase to address:** WCAG retrofit phase, specifically during Canvas component audit.

---

### Pitfall 8: HUD Page Customization Persisted as Ordered Index List Breaks When Page Count Changes

**What goes wrong:**
The current `WorkoutHud` has a fixed 3-page `HorizontalPager` (Execution | Instruction | Stats). Making this configurable means persisting the user's preferred page order and visibility. The naive approach persists a list of page indices: `[0, 2, 1]` means "Execution, Stats, Instruction." When a new page is added in a future update (e.g., Ghost Racing page), all stored index lists are silently invalid — index 1 now refers to a different page than when the preference was saved.

**Why it happens:**
Developers reach for integer index lists because `rememberPagerState(pageCount = { ... })` works with integers. Persisting indices is natural. It is also fragile: any reordering of the compile-time page order corrupts stored preferences for existing users.

**How to avoid:**
- Persist page visibility/order as a **list of stable string keys** (`"EXECUTION"`, `"INSTRUCTION"`, `"STATS"`, `"GHOST_RACING"`), not integer indices.
- At runtime, map the stored key list to the current set of available pages. Pages not in the stored list default to their canonical position. Unknown keys in the stored list are silently dropped.
- Add a migration step: if the stored preference is null/empty/invalid, reset to the canonical default order. Never crash on an unrecognized page key.
- Store in the existing `PreferencesManager` or the `UserPreferences` data class (already used by `ActiveSessionEngine` for audio rep count preference, etc.) — do not create a new storage mechanism.
- The `pagerState.currentPage` should be driven by the mapping of stored keys to positions, not by persisting the raw `Int`.

**Warning signs:**
- After a version update that adds a new HUD page, users' customized page order is wrong but no error is thrown.
- A user's "hide Stats page" preference silently hides the wrong page after a minor reorder.
- Pager shows a blank page for users with corrupted index preferences.

**Phase to address:** HUD customization design phase, before any persistence code is written.

---

### Pitfall 9: Android Backup Exclusion Rules Require Separate XML for API 30 and API 31+

**What goes wrong:**
Android 12 (API 31) introduced a new backup rules format using `android:dataExtractionRules` pointing to a new XML schema. Devices running Android 11 (API 30) and below use the old `android:fullBackupContent` format. If only one format is provided, backup behavior is undefined or silently wrong on the other API level. This app targets a wide range: the manifest has no `targetSdkVersion` override but the `tools:targetApi="36"` attribute. Providing only `fullBackupContent` leaves API 31+ devices with uncontrolled backup. Providing only `dataExtractionRules` leaves API 30 devices backing up the SQLite database, BLE cached preferences, and subscription status data.

**Why it happens:**
The Android docs present both formats but most blog posts only show one. The distinction is easy to miss. The Board of Directors condition is framed as "android:allowBackup exclusion rules for sensitive DB data" — satisfying this requires **both** XML files: one for pre-API-31 devices and one for API 31+. Providing only `fullBackupContent` and calling it done passes a code review but fails on a Pixel 6 running Android 12.

**How to avoid:**
- Create **two** XML files in `res/xml/`:
  - `backup_rules.xml` (API 31+ format, `dataExtractionRules` schema) — excludes the SQLite database directory and any subscription SharedPreferences.
  - `backup_rules_legacy.xml` (API 30 and below format, `fullBackupContent` schema) — same exclusions in the old syntax.
- In `AndroidManifest.xml`, set BOTH attributes on the `<application>` element:
  ```xml
  android:fullBackupContent="@xml/backup_rules_legacy"
  android:dataExtractionRules="@xml/backup_rules"
  ```
- Exclude the following domains at minimum:
  - `database` domain: the SQLite file (VitruvianDatabase) — contains workout history, biomechanics, RepBiomechanics, subscription status.
  - `sharedpref` domain: any SharedPreferences files that contain subscription tier or session tokens.
- Do NOT set `android:allowBackup="false"` as a blanket solution — this also prevents device-to-device transfers of user settings and exercise library customizations, which users expect to be preserved.
- Test backup behavior with `adb backup -apk -shared -all com.devil.phoenixproject` and verify that the database file is not included in the backup archive.

**Warning signs:**
- Backup archive (inspected with `android-backup-extractor`) contains `vitruvian_database.db`.
- After restore-from-backup on a new device, the user appears to have a subscription when they do not (or vice versa).
- Lint warning `MissingBackupPolicy` fires (API 31+ format missing).

**Phase to address:** Security hardening phase, as a Board condition. Both XML files must exist before release.

---

### Pitfall 10: Readiness Heuristic Overconfidence with Sparse Local Data

**What goes wrong:**
The pre-workout readiness briefing computes a readiness score from local workout history: recent volume, rest days, time-of-day pattern, and potentially velocity trend from `AssessmentResult`. On a fresh install, or for a user with fewer than 3–5 sessions, the heuristic has no meaningful baseline. Computing a score from 1 session and displaying "You are 82% ready to train — high readiness!" is not just inaccurate, it is actively misleading. Users may ignore fatigue signals because the app told them they were ready.

**Why it happens:**
Heuristics that look good in unit tests (with 30 synthetic sessions as input) fail silently in production with real users who have 2 sessions in the database. The minimum session count for meaningful plateau detection in `SmartSuggestionsEngine` is 5 (`PLATEAU_MIN_SESSIONS = 5`). The weekly volume analysis needs at least 7 days of data. Without a data sufficiency guard, the readiness function computes a score from a single data point and returns a high-confidence number.

**How to avoid:**
- Add a **data sufficiency gate** to the readiness computation: if fewer than N sessions exist in the past 14 days (recommend N=3), return a `ReadinessResult.InsufficientData` state rather than a numeric score.
- In the UI, display "Not enough workout history for a readiness estimate — keep training to unlock this feature" for `InsufficientData`.
- Set confidence intervals that widen explicitly as history shrinks. Do not return a single number; return a range ("Moderate to High readiness") and note the basis ("Based on 4 sessions in the past 14 days").
- Apply the same UTC vs. local time fix that `SmartSuggestionsEngine.classifyTimeWindow()` needs: readiness time-of-day analysis should use local time, not UTC. A 6 AM user who trains in UTC+5 should not be categorized as a midnight trainer.
- Match the "advisory only" framing from the Board conditions: the UI must make clear this is a suggestion, not a physiological measurement.

**Warning signs:**
- Unit tests for readiness use 30-session fixture data but the production smoke test uses a fresh install.
- Readiness shows a high confidence score immediately after first workout.
- Time-of-day preference detection shows users as preferring midnight (UTC bug inherited from SmartSuggestionsEngine).

**Phase to address:** Readiness briefing implementation phase. Add the data sufficiency guard before the composable is wired up.

---

### Pitfall 11: versionName Discrepancy Creates Confusion Between Code Identity and User-Facing Version

**What goes wrong:**
The app's `versionName` in `build.gradle` is currently `"0.4.0"` while the actual feature set is at v0.5.0 (and the milestone targets v0.5.1). This means: the About screen shows an incorrect version, crash reports from Crashlytics/Firebase are attributed to the wrong build, testers cannot correlate bug reports to the code, and Play Store releases cannot be gated on the correct version increment. During rapid iteration across 350 changed files and 51K lines added, an incorrect versionName in production creates permanent audit trail corruption.

**Why it happens:**
versionName is a manual field. During the v0.4.x → v0.5.0 transition, the field was not updated alongside the feature work. The discrepancy compounds with each milestone: if it is not corrected now, v0.5.1 ships as "0.4.0" and the entire v0.5.x series is phantom.

**How to avoid:**
- Update `versionName` to `"0.5.1"` and `versionCode` to the correct increment as the **first change** in the milestone's first phase.
- Establish a convention: versionName updates happen in the same commit that bumps the `STATE.md` milestone marker. The two must be atomic.
- Verify: `./gradlew :androidApp:assembleDebug` then `aapt dump badging androidApp/build/outputs/apk/debug/androidApp-debug.apk | grep version` should return the expected values.
- The iOS `Info.plist` `CFBundleShortVersionString` must also be updated in sync — iOS and Android versions diverging is a separate audit risk.
- Add a CI lint step or a unit test that reads the versionName from BuildConfig and fails if it does not match the expected milestone string.

**Warning signs:**
- Crash reports in Sentry or Firebase attribute crashes to "0.4.0" for code that is clearly v0.5.x.
- The Play Store "What's new" section for the release lists v0.5.x features but the build metadata says 0.4.0.
- Testers report "the version in Settings → About shows 0.4.0 but you said this is v0.5.1."

**Phase to address:** Version alignment phase — must be the very first phase of the milestone.

---

### Pitfall 12: SmartSuggestions UTC Bug Silently Corrupts Time-Window Classification for All Users

**What goes wrong:**
`SmartSuggestionsEngine.classifyTimeWindow()` uses `currentTimeMillis()` which returns UTC milliseconds, then maps to "morning," "afternoon," or "evening" using hour-of-day arithmetic. For users in UTC+2 or UTC+8, the classification is shifted by 2–8 hours. A user who consistently trains at 7 AM local time (morning) is classified as a late-night or late-afternoon trainer. All downstream suggestions derived from time-of-day preference are therefore wrong for every user outside UTC.

**Why it happens:**
`currentTimeMillis()` is the standard time source in this codebase and is correct for elapsed-time arithmetic. It is incorrect for "what hour of day is it locally?" computations. In KMP commonMain, there is no `java.util.TimeZone` — the platform-specific API must be used. The bug predates the current milestone but is listed as an explicit Board condition fix.

**How to avoid:**
- Fix `classifyTimeWindow()` to use the local hour, not the UTC hour. In KMP commonMain, this requires an `expect fun localHourOfDay(): Int` backed by:
  - Android: `Calendar.getInstance().get(Calendar.HOUR_OF_DAY)` (uses device time zone)
  - iOS: `NSCalendar.currentCalendar.component(NSCalendarUnitHour, fromDate: NSDate())`
- The existing `SmartSuggestionsEngineTest.kt` must be updated: test cases that pass `nowMs` for classification need to account for local timezone offset, or the test must mock `localHourOfDay()` directly.
- Audit `analyzeBalance()` and `computeWeeklyVolume()` for the same pattern — they use `timestamp in weekStart..nowMs` for elapsed arithmetic, which is UTC-correct. Only hour-of-day classification is affected.
- Do not "fix" by adding a hardcoded timezone offset — that just moves the error to a different user group.

**Warning signs:**
- `classifyTimeWindow` returns "EVENING" for a 7 AM workout in a UTC+8 timezone.
- The suggestion "You tend to train in the morning — consider compound lifts first" is shown to afternoon trainers.
- Unit tests pass because they use `nowMs` in UTC, but real-device behavior differs.

**Phase to address:** Board condition fixes phase. This is a fast, isolated fix with high impact on suggestion accuracy.

---

## Technical Debt Patterns

Shortcuts that seem reasonable but create long-term problems.

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Adding RPG attributes to existing `GamificationStats` singleton row | No new table or migration | Every `upsertGamificationStats()` call site must be updated; iOS DriverFactory sync required; write paths interfere | Never — create a separate `RpgAttributes` table |
| Computing RPG attributes from live reactive DB Flows | Always current | CPU proportional to workout history size; quadratic re-emission on every set completion | Never in session path; acceptable on app launch/post-session only |
| Persisting HUD page order as integer indices | Trivial to implement | Corrupts user preferences on any page add/reorder | Never — use stable string keys |
| Providing only one backup rules XML format | Simpler code | Silent backup leakage on the uncovered API range (30 or 31+) | Never — both formats are required |
| Shipping readiness score with no data sufficiency guard | Feature appears complete | Displays misleading high-confidence scores from 1–2 sessions | Never — InsufficientData state is mandatory |
| Applying `contentDescription` only (no visual redundancy) for color-coded indicators | Satisfies TalkBack audit | Does not satisfy WCAG 1.4.1 for sighted colorblind users | Acceptable only for decorative/supplemental elements, not primary indicators |
| Leaving `versionName` at "0.4.0" during v0.5.1 milestone | No work required | Permanent crash report audit trail corruption; Play Store release confusion | Never |

---

## Integration Gotchas

Common mistakes when connecting to internal subsystems.

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| Ghost racing + `ActiveSessionEngine` BLE hot path | DB query for ghost metric inside `handleMonitorMetric()` | Pre-load ghost session into memory at workout start; pure function lookup in hot path |
| Ghost racing + rep counting | Synchronize ghost on wall-clock timestamp | Synchronize on rep index; use fractional rep completion for interpolation |
| RPG attributes + `GamificationManager.processPostSaveEvents()` | Trigger RPG recomputation from a reactive DB Flow | Compute RPG attributes as a post-save step in `processPostSaveEvents()`; write to `RpgAttributes` singleton |
| WCAG + Canvas composables (`ForceCurveMiniGraph`, `BalanceBar`) | Apply `Modifier.semantics` inside Canvas drawing code | Apply semantics to the wrapper composable; use `clearAndSetSemantics` on decorative canvas elements |
| HUD customization + `rememberPagerState` | Persist page index integers | Persist stable string page keys; map to indices at runtime |
| Backup exclusion + Android API range | Provide only `fullBackupContent` OR `dataExtractionRules` | Provide both; test on API 30 emulator and API 33+ device |
| Readiness heuristic + `SmartSuggestionsEngine` UTC bug | Reuse UTC-based time classification | Add `expect fun localHourOfDay(): Int` and use it for time-of-day classification |
| New FeatureGate features + iOS upgrade prompts | Include all features in iOS marketing copy | Suppress `CV_FORM_CHECK` from iOS Phoenix tier upgrade prompts (CV is Android-only until v0.6.0) |
| New SQLDelight schema migration + iOS DriverFactory | Forget to update the iOS no-op schema version | After every migration bump, update the target version in `DriverFactory.ios.kt` Layer 1 no-op schema |

---

## Performance Traps

Patterns that work at small scale but fail as usage grows.

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| Reactive RPG attribute query on `WorkoutSession` + `RepBiomechanics` | AnalyticsScreen slow to open; GamificationStats written then immediately re-read | Compute post-save only; cache in `RpgAttributes` singleton | When workout history exceeds ~100 sessions |
| Ghost racing DB read per BLE tick (20 Hz) | BLE processing latency increases; rep count lags | Pre-load ghost into memory array at session start | Immediately — even 1 DB read per tick at 20 Hz is 20 queries/second |
| MediaPipe + ghost racing comparator on same dispatcher | BLE metric processing stalls; thermal throttling compounds | Separate dispatchers for MediaPipe, ghost comparator, and BLE metrics | On mid-range devices with <4 performance cores |
| Readiness heuristic querying full workout history on every screen visit | Profile/readiness screen slow to open | Cache readiness result; invalidate only on new session save | When history exceeds ~50 sessions |
| HUD customization storing page order as `MutableState` without persistence | User's page preference resets on every app restart | Persist to `PreferencesManager`; load at `WorkoutCoordinator` init | Immediately after first app restart |

---

## Security Mistakes

Domain-specific security issues beyond general web security.

| Mistake | Risk | Prevention |
|---------|------|------------|
| `android:allowBackup="true"` with no exclusion rules | SQLite database (including subscription status, workout history, biomechanics data) restored to a different device or extracted via ADB backup | Provide both `fullBackupContent` and `dataExtractionRules` XML, excluding the database domain |
| Client-side-only subscription tier enforcement with no server validation | User can modify SQLite tier column to `ELITE` with a rooted device | Known limitation per Board conditions; flag for v0.6.0 Supabase auth integration; add tamper detection log entry when tier column changes without a subscription event |
| Camera permission rationale omits on-device-only guarantee | Users deny camera permission based on privacy concerns; Board condition unmet | Update `shouldShowRequestPermissionRationale` rationale text to explicitly state "all processing is on-device, no video is transmitted or stored" |
| Ghost racing stores full session velocity data client-side without access control | No immediate risk (local-only); future risk when portal sync is added | Store ghost sessions as immutable records; add a `ghostEnabled` flag per session to prevent accidental sync in v0.6.0 |

---

## UX Pitfalls

Common user experience mistakes in this domain.

| Pitfall | User Impact | Better Approach |
|---------|-------------|-----------------|
| Ghost racing shows delta in raw mm/s with no context | Users don't understand "12 mm/s ahead" — this is a domain-specific metric even Vitruvian users may not grasp | Show as "Rep pace: ahead" / "Rep pace: behind" with optional numeric detail for advanced users |
| RPG character class changes every session based on current stat leader | User's class identity feels unstable and arbitrary | Compute class from trailing 30-day averages; add a minimum session count before class is assigned |
| Readiness briefing shown as a blocking modal before workout | Friction at the most time-sensitive moment (user is ready to train) | Display as a dismissible card on the SetReady screen, not a modal gate |
| HUD customization accessible only through buried Settings | Users with HUD density complaints (Board condition) never find the setting | Add a "Customize HUD" long-press gesture or a visible edit icon on the HUD page indicator dots |
| WCAG color fallbacks use text labels that overlap with metric numbers | Cluttered HUD during workout | Use abbreviations (S/M/F/E for velocity zones) or position labels in unused screen space; test with actual workout screen density |

---

## "Looks Done But Isn't" Checklist

Things that appear complete but are missing critical pieces.

- [ ] **Ghost racing:** Ghost session pre-loaded into memory at workout start — verify no DB query occurs during `handleMonitorMetric()`
- [ ] **Ghost racing:** Synchronization uses rep index, not wall-clock timestamp — verify ghost does not lead/lag from rep 1
- [ ] **RPG attributes:** `FeatureGate.Feature.RPG_ATTRIBUTES` added to enum AND to `phoenixFeatures` set — verify `isEnabled(RPG_ATTRIBUTES, FREE)` returns false
- [ ] **RPG attributes:** Separate `RpgAttributes` table or dedicated columns that do NOT conflict with `GamificationStats` upsert paths — verify `updateStats()` does not zero RPG columns
- [ ] **RPG attributes:** iOS `DriverFactory.ios.kt` no-op schema version bumped to match new migration number — verify iOS builds complete after schema bump
- [ ] **WCAG:** All color-coded indicators (velocity zones, balance bar, readiness, form score) have a secondary non-color indicator — verify in grayscale mode
- [ ] **WCAG:** Canvas composables (`ForceCurveMiniGraph`, `BalanceBar`, ghost delta bar) have wrapper-level semantics with state-encoded contentDescription — verify TalkBack reads meaningful descriptions
- [ ] **Backup exclusion:** Two XML files exist (`backup_rules.xml` and `backup_rules_legacy.xml`) with both `dataExtractionRules` and `fullBackupContent` attributes in manifest — verify with `adb backup` that database is excluded
- [ ] **HUD customization:** Page order stored as stable string keys, not integer indices — verify preference survives a HUD page addition without corruption
- [ ] **Readiness briefing:** `InsufficientData` state returned and displayed when fewer than 3 sessions exist in the past 14 days — verify on fresh install
- [ ] **Readiness briefing:** Time-of-day classification uses local hour (UTC bug fixed) — verify in UTC+8 emulator
- [ ] **versionName:** `build.gradle` reflects `"0.5.1"` — verify with `aapt dump badging` on the debug APK
- [ ] **Camera rationale:** Permission rationale string explicitly states on-device processing — verify the rationale dialog text matches the Board condition requirement
- [ ] **iOS marketing:** iOS Phoenix upgrade prompt does NOT mention Form Check — verify in iOS build that CV feature is absent from upgrade copy
- [ ] **FeatureGate:** `CV_FORM_CHECK`, `RPG_ATTRIBUTES`, `READINESS_BRIEFING` all return false for `FREE` tier — verify with unit tests

---

## Recovery Strategies

When pitfalls occur despite prevention, how to recover.

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| Ghost racing desync on timestamp | MEDIUM | Switch synchronization to rep-index model; no DB migration required, purely algorithmic change |
| Ghost racing DB reads in hot path causing missed reps | MEDIUM | Extract ghost metric lookup to a pre-loaded list; refactor `handleMonitorMetric()` to accept a ghost state parameter |
| RPG attributes added to `GamificationStats`, causing upsert conflicts | HIGH | Write a migration that moves RPG columns to a new `RpgAttributes` table; update all upsert call sites; update iOS DriverFactory |
| FeatureGate missing enum entry — feature shipped ungated | HIGH | Force update with tier enforcement fix; requires Play Store expedited release; add audit log of feature access |
| WCAG color-only indicators — Board condition failed | MEDIUM | Add secondary indicators as a targeted composable update; no architecture change required |
| Backup exclusion partial (only one API range) | MEDIUM | Add missing XML file and manifest attribute in a patch release; test coverage with two emulators |
| HUD page preferences corrupted by index-based storage | HIGH | Provide a migration that detects corrupted preferences (e.g., out-of-range index) and resets to canonical default; add telemetry to detect frequency |
| Readiness showing overconfident score with sparse data | LOW | Add `InsufficientData` guard behind a feature flag; ship in a patch release |
| SmartSuggestions UTC bug causes wrong time-window classification | MEDIUM | Replace `classifyTimeWindow` with local-hour implementation; update unit tests; rebuild suggestions |
| versionName stuck at 0.4.0 in production | LOW | Update and ship new build; minor friction for internal tooling only |

---

## Pitfall-to-Phase Mapping

How roadmap phases should address these pitfalls.

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| Ghost racing timestamp desync | Ghost racing design phase (before any code) | Unit test: assert ghost delta is zero when live elapsed = ghost elapsed for same rep index |
| Ghost racing DB reads in hot path | Ghost racing data loading phase | Integration test: measure `handleMonitorMetric()` latency with and without ghost active; must be within 5ms |
| RPG attributes recomputation storm | RPG domain design phase (schema first) | Integration test: `processPostSaveEvents()` triggers exactly one RPG computation; reactive flows not triggered by workout session writes |
| GamificationStats singleton schema bloat | RPG domain design phase (schema first) | Characterization test: `upsertGamificationStats()` on existing record preserves all prior values |
| FeatureGate missing enum entries | Feature gate extension phase (Phase 1 of milestone) | Unit test: `Feature.values().forEach { assertFalse(FeatureGate.isEnabled(it, FREE)) }` |
| WCAG color-only indicators | WCAG retrofit phase | Manual test: Monochromacy mode; all states distinguishable; TalkBack reads meaningful descriptions |
| WCAG Canvas semantics | WCAG retrofit phase | TalkBack navigation test: Canvas components read semantic state, not "Unlabeled image" |
| Android backup partial exclusion | Security hardening phase | `adb backup` extraction; database file absent; test on API 30 and API 33 emulators |
| HUD page index-based persistence | HUD customization design phase (before any code) | Unit test: adding a page key to the page registry does not invalidate stored preferences from the prior version |
| Readiness overconfidence with sparse data | Readiness briefing implementation phase | Integration test: with 1 session in the past 14 days, readiness returns `InsufficientData`; with 3 sessions, returns a score |
| UTC bug in time-window classification | Board condition fixes phase | Unit test: `classifyTimeWindow` with nowMs at 7:00 AM UTC+8 (23:00 UTC previous day) returns MORNING, not EVENING |
| versionName discrepancy | Version alignment phase (Phase 1 of milestone) | `aapt dump badging` output contains `versionName='0.5.1'` |
| Camera rationale text | Board condition fixes phase | Manual test: deny permission flow shows updated rationale text with on-device guarantee |
| iOS Form Check in upgrade prompts | iOS suppression phase | iOS build review: Phoenix upgrade prompt does not reference Form Check or camera |
| iOS DriverFactory schema version | Post-schema-migration step (every migration) | iOS build completes; app launches on simulator without migration crash |

---

## Sources

- Codebase analysis: `ActiveSessionEngine.kt` (L1-400), `FeatureGate.kt`, `WorkoutHud.kt` (L1-180), `GamificationRepository.kt`, `SmartSuggestionsEngine.kt`, `DriverFactory.ios.kt`, `AndroidManifest.xml`, `VitruvianDatabase.sq`
- [Android Auto Backup official docs](https://developer.android.com/identity/data/autobackup)
- [Android backup security recommendations](https://developer.android.com/privacy-and-security/risks/backup-best-practices)
- [Setting up Backup Rules in Android — Medium](https://medium.com/@vikasacsoni9211/setting-up-backup-rules-in-android-why-auto-backup-matters-and-where-it-bites-83b0ca6b0ad3)
- [Jetpack Compose Accessibility — Android Developers codelab](https://developer.android.com/codelabs/jetpack-compose-accessibility)
- [Semantics in Jetpack Compose — Android Developers](https://developer.android.com/develop/ui/compose/accessibility/semantics)
- [Mastering Accessibility in Jetpack Compose (2025) — Medium](https://medium.com/@sharmapraveen91/mastering-accessibility-in-jetpack-compose-ui-the-ultimate-guide-for-2025-825e419ab359)
- [Readiness Score validity 2025 — De Gruyter](https://www.degruyterbrill.com/document/doi/10.1515/teb-2025-0001/html)
- [Garmin Training Readiness accuracy analysis](https://the5krunner.com/2023/08/02/garmin-training-readiness-not-accurate-heres-why/)
- [SQLDelight migrations docs (2.0.2)](https://sqldelight.github.io/sqldelight/2.0.2/multiplatform_sqlite/migrations/)
- [SQLDelight iOS NativeSqliteDriver issue #5007](https://github.com/cashapp/sqldelight/issues/5007)
- [MediaPipe PoseLandmarker first-load latency issue #5171](https://github.com/google-ai-edge/mediapipe/issues/5171)
- [StateFlow and SharedFlow — Android Developers](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow)
- Board of Directors review conditions (2026-02-27): WCAG color-blind, allowBackup, HUD density, versionName, UTC bug, camera rationale, iOS marketing suppression, asset verification

---

*Pitfalls research for: v0.5.1 Board Polish & Premium UI (ghost racing, RPG gamification, readiness heuristics, WCAG, HUD customization, security hardening)*
*Researched: 2026-02-27*
