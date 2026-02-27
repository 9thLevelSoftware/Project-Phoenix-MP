# Project Research Summary

**Project:** Project Phoenix MP — v0.5.1 Board Polish & Premium UI
**Domain:** KMP connected fitness app — premium UI features, gamification, accessibility hardening
**Researched:** 2026-02-27
**Confidence:** HIGH

## Executive Summary

v0.5.1 is a targeted polish milestone that activates six premium features already stubbed in v0.5.0 (ghost racing, RPG attributes, pre-workout readiness, WCAG accessibility, HUD customization, CV form check UX) and closes nine Board of Directors compliance conditions (versionName, UTC bug, backup exclusion, camera rationale, iOS suppression, asset fallback). The critical research finding is that every feature is implementable using the existing dependency set — zero new production runtime libraries are required. The entire milestone is local-only and avoids any Supabase/portal dependency, which is reserved for v0.6.0. This radically lowers integration risk: there are no external API rate limits, no auth flows, and no network failure modes to account for.

The recommended technical approach follows three patterns already established in the codebase: stateless pure-function domain engines (following `SmartSuggestionsEngine`), `CompositionLocal` for cross-cutting theme concerns (color-blind mode), and JSON-serialized config blobs in `PreferencesManager` (following `SingleExerciseDefaults`). All new premium features (Ghost Racing, RPG Attributes, Readiness Briefing) belong in `domain/premium/` as Kotlin `object` singletons with no Koin registration. The single most complex integration is ghost racing, because it must overlay live workout state with pre-loaded historical session data during an active BLE session — and this must be built last, after all other features are stable.

The top risk is implementation ordering. Ghost racing touches `ActiveSessionEngine` (2600L), `WorkoutCoordinator`, and `WorkoutHud` simultaneously. If built first, it creates a large unstable surface while other features are still being added. Four other features (WCAG, HUD customization, RPG attributes, readiness briefing) are self-contained and can be built in sequence with no cross-feature conflicts. Board condition fixes (versionName, UTC bug, backup rules, camera rationale) are isolated one-file changes that must land in Phase 1 to unblock Board review at any point during the milestone.

---

## Key Findings

### Recommended Stack

The existing stack requires no production runtime additions for v0.5.1. The full feature set — ghost racing UI, RPG computation, readiness ACWR heuristic, WCAG palette swaps, HUD preference persistence, camera permission rationale, UTC bug fix, and Android backup exclusion — is achievable with Kotlin 2.3.0, Compose Multiplatform 1.10.0, `kotlinx-datetime` 0.7.1, `multiplatform-settings` 1.3.0, `accompanist-permissions` 0.37.3, and the existing Compose BOM (2025.12.01 / compose-ui 1.10.4). One new test-only dependency is needed: `androidx.compose.ui:ui-test-junit4-accessibility` (pulled via existing BOM, no version pin required).

**Core technologies and their v0.5.1 roles:**
- `kotlinx-datetime` 0.7.1: `TimeZone.currentSystemDefault()` for the UTC fix in `SmartSuggestionsEngine`; `Clock` injection in `ReadinessBriefingEngine` for testable time-dependent computation
- `multiplatform-settings` 1.3.0 + coroutines module: HUD page config persistence as JSON blob; `StateFlow` for reactive HUD preference updates — already in project, zero incremental cost
- `accompanist-permissions` 0.37.3: Camera permission rationale dialog with `shouldShowRationale` Compose state — already in project
- `reorderable` 3.0.0: Drag-to-reorder in HUD settings if metric reordering is in scope (already in catalog)
- `compose.foundation` Canvas + `animateFloatAsState`: Ghost racing dual progress bar; RPG attribute visualization
- `compose-material-icons-extended`: Warning/info icons for color-blind-safe secondary visual cues
- `compose-ui:ui-test-junit4-accessibility` (NEW, test-only): ATF-based automated WCAG contrast and touch target checks in instrumented tests

**What NOT to add:** DataStore (Android-only; `multiplatform-settings` already solves this KMP-wide), WorkManager (readiness computes on ViewModel init, no background scheduling needed), RevenueCat (blocked on v0.6.0 auth migration), Axe Android (ATF via BOM provides equivalent automated checks at zero additional cost), `java.time` or `java.util.TimeZone` (JVM-only, breaks commonMain compilation).

### Expected Features

**Must have — table stakes (P1, required for Board approval):**
- Ghost racing overlay: rep-by-rep velocity comparison vs. personal best session, dual animated progress bars, numeric delta label. Zwift HoloReplay is the reference implementation; local-only via `RepBiomechanics` + `RepMetric` queries (no schema changes needed). PHOENIX tier minimum; ELITE gets sample-level 50Hz position overlay.
- RPG attribute card: 5 attributes (Strength, Power, Stamina, Consistency, Mastery) auto-derived from existing persisted data (1RM from `AssessmentResult`, MCV from `RepBiomechanics`, volume/streaks from `GamificationStats`, quality scores from `RepMetric`). 5 auto-assigned character classes. Pure KMP commonMain math — no server dependency. PHOENIX tier.
- Pre-workout readiness briefing: ACWR (Acute:Chronic Workload Ratio) using 7-day/28-day rolling session volume. Green/Yellow/Red advisory with bootstrap states for sparse data history. Always shows override. ELITE tier.
- WCAG AA secondary differentiators: Text labels or icons alongside all color-coded indicators (velocity zones, balance bar, readiness card, rep quality chip). Compose semantics on Canvas wrapper elements. Not palette-replacement-only. All tiers.
- Color-blind mode toggle: Deuteranopia-safe MaterialTheme ColorScheme (blue/orange/teal replaces green/red palette). In-app toggle in Settings, implemented via `LocalColorBlindMode` CompositionLocal. All tiers.
- Board conditions: UTC timezone fix in `SmartSuggestionsEngine.classifyTimeWindow()`, `allowBackup` exclusion XML (both API 30 format and API 31+ format required), camera permission rationale text with on-device processing guarantee, `versionName = "0.5.1"` bump, iOS Form Check upgrade prompt suppression, asset verification fallback.

**Should have — competitive differentiators (P2):**
- HUD page presets: Three preset pages (Essential 4 metrics, Biomechanics 5 metrics, Full with ghost + readiness overlay). Horizontal swipe, dynamic `visiblePages` list drives `rememberPagerState`. Page order stored as stable string keys, not integer indices.
- Per-metric HUD toggles: On/off per non-essential metric in Settings → HUD Preferences. Toggle removes metric from all pages. No drag-and-drop in v0.5.1.
- CV Form Check UX: Toggle UI, real-time warning display, form score persistence, iOS stub UI.

**Defer to v0.6.0 — all require Supabase/portal:**
- Ghost racing against portal session data (Supabase RPC for best-matching session)
- RPG attributes with server-validated XP via Edge Functions
- Full readiness with HRV/sleep from wearable API (WHOOP, Oura, Garmin)
- Form data sync to portal

**Anti-features to reject:** Ghost racing against other users' sessions (physically meaningless — cable position reflects individual limb length/machine calibration), readiness score blocking workouts (advisory only per Board condition), animated avatar ghost overlay (frame drop risk on mid-range Android), RPG stat manual override (destroys data integrity), full WCAG AAA (7:1 contrast unachievable on dark workout HUD without overhaul), per-exercise HUD layouts (v0.7+ request).

### Architecture Approach

All six new features integrate cleanly into the existing three-layer clean architecture (Presentation / Domain / Data) without modifying the BLE data hot path. Three of five new domain engines follow the exact `SmartSuggestionsEngine` stateless object pattern: pure `fun compute(inputs): Output`, no DI, no mutable state, injectable clock for time-dependent functions. User preferences (HUD config, color-blind mode) follow the existing `SingleExerciseDefaults` pattern: `@Serializable` data class stored as JSON blob via `SettingsPreferencesManager`. Ghost racing is the only feature that modifies the active workout lifecycle — it adds one `StateFlow<GhostRaceState?>` to `WorkoutCoordinator` and pre-loads historical session data at workout start. Five of six feature areas require zero schema migrations.

**Major new components:**
1. `domain/premium/GhostRacingEngine` — stateless object; pre-loaded ghost session data; pure rep-index comparison during BLE hot path; no DB I/O during active session
2. `domain/premium/RpgAttributeEngine` — stateless object; reads from `GamificationStats` + `PersonalRecord` + `WorkoutSession` via `combine()`; computes on profile screen load, never during a session
3. `domain/premium/ReadinessBriefingEngine` — stateless object; reuses `SmartSuggestionsRepository.getSessionSummariesSince()`; one-shot pre-session computation
4. `ui/theme/AccessibilityColors` + `LocalColorBlindMode` CompositionLocal — color-blind palette injected at theme root, consumed by `BalanceBar`, velocity HUD, and new composables without parameter threading
5. `domain/model/HudPageConfig` + `HudCustomizationSheet` — serializable page visibility config; dynamic `visiblePages` list drives `rememberPagerState(pageCount = { visiblePages.size })`
6. New SQL named query: `selectBestSessionForExercise` in `VitruvianDatabase.sq` — only schema file addition needed (no new tables, no migration required)

### Critical Pitfalls

1. **Ghost racing timestamp desync** — Naive wall-clock replay causes ghost to lead or lag the live athlete from rep 1 because live BLE timestamps and stored session timestamps have different epoch references (different session start times, BLE jitter). Prevention: synchronize on rep index as the structural anchor, not wall-clock elapsed time. Pre-load ghost as a `List<GhostRepData>` indexed by rep number; look up by index during `handleMonitorMetric()`; zero DB I/O in the BLE hot path.

2. **Ghost racing DB reads in BLE hot path** — `ActiveSessionEngine.handleMonitorMetric()` already runs BiomechanicsEngine and RepQualityScorer per tick at 10-20 Hz. Any inline SQLDelight query for ghost data at this rate equals 200+ queries/second, immediate BLE processing stall, and missed reps. Prevention: pre-load the entire ghost session (~1200 rows, ~19KB) into memory at workout start. Pure function lookup only during the active session. No coroutines, no suspension in the hot path.

3. **RPG attributes causing reactive DB recomputation storm** — SQLDelight Flows emit on any write to observed tables. If RPG attributes are computed from a live reactive `combine()` on `WorkoutSession` + `RepBiomechanics` + `PersonalRecord` + `GamificationStats`, a single set completion triggers 4 separate emissions each recomputing all 5 attributes from scratch. Prevention: compute RPG attributes as a one-shot post-session step in `GamificationManager.processPostSaveEvents()`, not from a continuous reactive query.

4. **GamificationStats singleton schema bloat via upsert path conflicts** — Adding RPG attribute columns to the existing `GamificationStats` singleton row means every `upsertGamificationStats()` call site in `ActiveSessionEngine` must be updated simultaneously or new columns are silently zeroed on each set completion. Prevention: create a separate `RpgAttributes` singleton table (ID=1) with isolated write paths that only `processPostSaveEvents()` touches. Never mix session save paths with RPG computation paths.

5. **FeatureGate enum entries missing before UI composables ship** — Composables wired up before `Feature.RPG_ATTRIBUTES`, `Feature.READINESS_BRIEFING`, `Feature.CV_FORM_CHECK` are added to the enum and their tier sets will silently bypass tier enforcement. Board conditions explicitly require tier gating for all three. Prevention: add all new `Feature` enum entries AND tier set membership as the very first commit of the milestone, before any composable work. Extend `FeatureGateTest.kt` to assert `isEnabled(feature, FREE) == false` for every new entry.

6. **Android backup exclusion requiring two separate XML formats** — Providing only `fullBackupContent` (API 30) or only `dataExtractionRules` (API 31+) leaves the uncovered API range silently backing up the SQLite database, including subscription status data. Both XML files are mandatory. Prevention: create both `backup_rules.xml` and `backup_rules_legacy.xml` in `res/xml/`, reference both attributes in `AndroidManifest.xml`, test with `adb backup` on both API 30 and API 33 emulators.

7. **HUD page order stored as integer indices** — Persisting `[0, 2, 1]` is fragile; any future page addition or reordering corrupts stored user preferences silently. Prevention: persist as stable string keys (`"EXECUTION"`, `"STATS"`, `"INSTRUCTION"`); map to integer indices at runtime; drop unknown keys gracefully on deserialize.

---

## Implications for Roadmap

Based on architecture analysis, feature dependencies, and pitfall prevention requirements, six phases are recommended. The ordering is driven by three hard constraints: (a) Board condition fixes must land early to unblock review at any time during the milestone, (b) FeatureGate enum entries must exist before any composable references them, (c) ghost racing must be built last because it is the only feature that modifies the active workout lifecycle.

### Phase 1: Foundation — Version, Gates, and Board Conditions

**Rationale:** These changes are prerequisites for everything else. `versionName` must be correct before any release candidate can be tagged. FeatureGate enum entries must exist before any composable references them (compilation error if they don't, but the risk is the inverse: a composable wired up without a gate check). Board condition fixes are isolated one-file changes with zero risk of breaking other features. Landing them first means Board review can begin at any time during the milestone without waiting for premium features.

**Delivers:** Correct `versionName = "0.5.1"` in `build.gradle.kts`, all three new `Feature` enum entries (`CV_FORM_CHECK`, `RPG_ATTRIBUTES`, `READINESS_BRIEFING`) with tier set membership, `SmartSuggestionsEngine.classifyTimeWindow()` UTC fix using `TimeZone.currentSystemDefault()`, both Android backup exclusion XML files (`backup_rules.xml` + `backup_rules_legacy.xml`) with both `AndroidManifest.xml` attributes, camera permission rationale text update (on-device processing guarantee), iOS Form Check upgrade prompt suppression.

**Addresses:** All P1 Board compliance conditions; pitfall prevention for FeatureGate (Pitfall 5), backup exclusion (Pitfall 6), UTC bug (PITFALLS Pitfall 12), versionName (PITFALLS Pitfall 11).

**Avoids:** Ungated features shipping to wrong tier, backup data leakage on either API range, wrong time-window classification for non-UTC users, versionName audit trail corruption.

**Research flag:** Standard patterns. All changes are isolated, fully specified in STACK.md and PITFALLS.md. No deeper research needed.

---

### Phase 2: WCAG Accessibility and Color-Blind Mode

**Rationale:** WCAG is a Board condition and a cross-cutting concern that affects composables built in all later phases. Establishing `AccessibilityColors.kt` and `LocalColorBlindMode` CompositionLocal now means all new composables (readiness card, ghost overlay, RPG card) can consume the correct pattern from the start rather than retrofitting it after build. This is also the lowest regression risk phase — no domain logic changes, no schema changes, no active workout lifecycle involvement.

**Delivers:** `ui/theme/AccessibilityColors.kt` (deuteranopia-safe palette: blue/orange/teal replacing green/red), `LocalColorBlindMode` CompositionLocal injected at theme root, `colorBlindModeEnabled: Boolean` in `UserPreferences` + `PreferencesManager` + `SettingsPreferencesManager`, secondary non-color indicators on all existing color-coded components (`BalanceBar` numeric percentage, velocity zone text labels, rep quality chip letter grade), Canvas wrapper semantics (`contentDescription` on wrapper Box with state-encoded descriptions; `clearAndSetSemantics` on decorative canvas elements), Color-blind mode toggle in Settings under Display.

**Uses:** `compose.foundation` Canvas, `compose-material-icons-extended` (warning/info icons), `compose.material3` ColorScheme, `compose-ui:ui-test-junit4-accessibility` (new test dependency for ATF automated checks).

**Implements:** CompositionLocal for cross-cutting theme values (Architecture Pattern 5); secondary-differentiator pattern per WCAG G111 technique.

**Avoids:** Color-only indicators failing Board WCAG review (Pitfall 6), Canvas semantics misapplied inside drawing code instead of wrapper composable (Pitfall 7).

**Research flag:** Standard patterns. WCAG G111 technique is canonical; Compose CompositionLocal is well-documented; `AccessibilityColors` palette fully specified in ARCHITECTURE.md. No deeper research needed.

---

### Phase 3: HUD Customization

**Rationale:** HUD customization modifies `WorkoutHud.kt` — the same file ghost racing will also touch in Phase 6. Completing HUD customization first stabilizes the pager structure (the dynamic `visiblePages` list approach must be in place before ghost racing adds its overlay slot to `ExecutionPage`). Building HUD customization second also lets it consume the `LocalColorBlindMode` pattern established in Phase 2.

**Delivers:** `HudPageConfig` serializable model (`showInstructionPage`, `showStatsPage` booleans; `ExecutionPage` always visible), `HudCustomizationSheet` composable (bottom sheet or Settings entry), dynamic `visiblePages` list in `WorkoutHud.kt` (`buildList { EXECUTION always; INSTRUCTION if enabled; STATS if enabled }`), page order persisted as stable string keys (not integer indices), per-metric toggles in Settings → HUD Preferences, `pagerState.pageCount` driven by `visiblePages.size`.

**Uses:** `multiplatform-settings` 1.3.0 + coroutines module for reactive HUD preference `StateFlow`; existing `PreferencesManager` JSON blob pattern (identical to `SingleExerciseDefaults`).

**Implements:** Preferences for user-configurable device state (Architecture Pattern 3); stable string key persistence to prevent Pitfall 7.

**Avoids:** Integer index persistence corrupting user preferences after any future page addition (Pitfall 7), HUD customization inaccessible from the workout screen (add long-press gesture on page dots or a visible edit icon).

**Research flag:** Standard patterns. `PreferencesManager` JSON blob pattern is already proven in the codebase. String-key persistence design fully specified in PITFALLS.md. No deeper research needed.

---

### Phase 4: Pre-Workout Readiness Briefing

**Rationale:** Readiness briefing is the simplest of the three new domain engines: it reuses `SmartSuggestionsRepository.getSessionSummariesSince()` with no new repository, computes pre-session with no active workout interaction, and renders as a dismissible card rather than an overlay. The ACWR formula is well-documented from sports science literature. The bootstrap states (insufficient data guard) must be designed and unit-tested before the composable is wired up — the `InsufficientData` state is not optional.

**Delivers:** `ReadinessBriefingEngine` stateless object (ACWR from 7-day/28-day rolling volume ratio; `InsufficientData` returned when fewer than 3 sessions in past 14 days), `ReadinessBriefingModels` (`ReadinessBriefing`, `ReadinessLevel` enum with Green/Yellow/Red and `InsufficientData`), `ReadinessBriefingCard` composable with WCAG-compliant secondary indicators (icon + text label alongside traffic-light color), `FeatureGate.READINESS_BRIEFING` (Elite tier) enforced at the data-fetch call site. Bootstrap states: `< 7 days: InsufficientData`, `8–14 days: Early estimate`, `15–27 days: Limited history`, `28+ days: Full ACWR`.

**Uses:** `kotlinx-datetime` 0.7.1 (`Clock.System` injection for testable time computation using `TimeZone.currentSystemDefault()`); `SmartSuggestionsRepository` (existing, no change required); `GamificationRepository.getGamificationStats()` for streak data.

**Implements:** Stateless domain engine pattern (Architecture Pattern 1); single upstream feature gate (Architecture Pattern 2).

**Avoids:** Overconfident readiness score with sparse data displaying misleading high-confidence numbers (Pitfall 10 — `InsufficientData` guard is mandatory and must be verified on fresh install), UTC bug inherited in time-of-day classification (use `TimeZone.currentSystemDefault()` throughout, same fix as Phase 1).

**Research flag:** ACWR threshold values (Green 0.8–1.3, Yellow 0.5–0.8 or 1.3–1.5, Red outside) come from group-level sports science studies and will need per-user calibration post-launch. The formula shape is sound for v0.5.1; flag scaling parameters for v0.5.2 adjustment based on real-user data. The readiness card must be labeled "Estimated from training volume" — not "HRV-based" or "physiological" — per Board condition framing.

---

### Phase 5: RPG Attribute Engine and Card

**Rationale:** RPG attributes require a schema design decision before any implementation begins: dedicated `RpgAttributes` singleton table vs. columns added to `GamificationStats`. The research is unambiguous — a separate table is mandatory to prevent upsert path conflicts. Once the schema decision is locked, the computation engine is a pure function reading from three existing repositories via `combine()`. Building RPG after Readiness means the stateless engine pattern is already freshly exercised and the team is in the right mode for this kind of work.

**Delivers:** `RpgAttributeEngine` stateless object (5-attribute formula: Strength from `PersonalRecord.oneRepMax`, Power from `WorkoutSession.avgMcvMmS`, Stamina from `GamificationStats` volume, Consistency from streak data, Balance from `WorkoutSession.avgAsymmetryPercent`; 5 auto-assigned character classes from dominant attribute), `RpgModels` (`RpgAttributes`, `CharacterClass` enum), `RpgAttributeCard` composable (Phoenix tier, rendered on profile/analytics screen), `FeatureGate.RPG_ATTRIBUTES` (Phoenix tier) enforced at the `combine()` data-fetch call site, characterization test verifying `upsertGamificationStats()` does not affect RPG columns. If schema storage is chosen: schema migration with iOS `DriverFactory.ios.kt` `CURRENT_SCHEMA_VERSION` increment.

**Uses:** `GamificationRepository`, `PersonalRecordRepository`, `WorkoutRepository` (all existing); `combine()` from `kotlinx-coroutines`; pure Kotlin math for normalization. No Koin registration needed (stateless object).

**Implements:** Stateless domain engine pattern (Architecture Pattern 1); post-session trigger via `GamificationManager.processPostSaveEvents()` to avoid reactive recomputation storm (Pitfall 3).

**Avoids:** Reactive DB Flow recomputation storm on every set completion (Pitfall 3), `GamificationStats` singleton upsert path conflicts (Pitfall 4), iOS schema version mismatch if migration is added (Daem0n warning #155 — increment `CURRENT_SCHEMA_VERSION` in `DriverFactory.ios.kt` whenever any `.sqm` file is added).

**Research flag:** Attribute normalization scaling constants (what raw 1RM or MCV value constitutes 100/100 Strength or 100/100 Power for this user base?) need calibration against real user data. Ship with conservative scales; expose scaling constants as configurable data (not hardcoded), adjust in v0.5.2 patch. Do not surface normalization math to users — show the attribute level and a "based on your estimated 1RM" tooltip only.

---

### Phase 6: Ghost Racing Engine, Overlay, and Workout Integration

**Rationale:** Ghost racing is the most complex feature in the milestone: it modifies `WorkoutCoordinator` (shared state bus for the entire workout flow), `ActiveSessionEngine` (pre-loads historical data at workout start), and `WorkoutHud` (adds overlay slot to `ExecutionPage`). It must be built last because: (a) `WorkoutHud.kt` must be stable after Phase 3 HUD customization before this phase touches it again, (b) `WorkoutCoordinator` changes carry regression risk for the entire workout flow, and (c) all other features should be in a reviewable and mergeable state before the highest-risk component is modified. The synchronization contract (rep-index alignment, not wall-clock) must be defined and unit-tested before any UI code is written.

**Delivers:** `GhostRacingEngine` stateless object (rep-index comparison, fractional completion interpolation, AHEAD/BEHIND/TIED verdict), `GhostRacingModels` (`GhostRaceState`, `GhostRepData`), `GhostRaceOverlay` composable (dual animated `animateFloatAsState` progress bars, numeric delta label; color-blind-safe via opacity/alpha differential not hue; WCAG semantics on wrapper Box), `WorkoutCoordinator.ghostRaceState: MutableStateFlow<GhostRaceState?>`, pre-session ghost data loading in `ActiveSessionEngine.start()`, `selectBestSessionForExercise` named query in `VitruvianDatabase.sq`, `FeatureGate.GHOST_RACING` (Phoenix/Elite tier), overlay conditionally rendered on `ExecutionPage` when `ghostRaceState != null`.

**Uses:** `RepMetricRepository.getRepMetricsBySession()` for ghost data pre-load; `WorkoutRepository` + new `selectBestSessionForExercise` query for best-session lookup; `compose.foundation` Canvas + `animateFloatAsState` for smooth animation; WCAG-compliant semantics from Phase 2 infrastructure.

**Implements:** WorkoutCoordinator as state conduit (Architecture Pattern 4); stateless domain engine (Architecture Pattern 1); single upstream feature gate (Architecture Pattern 2). Ghost overlay rendered on `ExecutionPage` as a conditionally visible composable — NOT as a new pager page requiring swipe navigation during active lifting (Anti-Pattern 5 explicitly avoided).

**Avoids:** Ghost timestamp desync causing permanent lead/lag from rep 1 (Pitfall 1 — rep-index synchronization is the design contract, verified by unit test before any UI code), DB reads in BLE hot path causing missed reps (Pitfall 2 — full pre-load at session start, pure function lookup only during session; measure `handleMonitorMetric()` latency with ghost active, must be < 5ms overhead), ghost as a fourth pager page that users cannot reach while actively lifting (Anti-Pattern 5).

**Research flag:** This phase requires integration testing on mid-range Android devices before merge. Measure `handleMonitorMetric()` latency with ghost racing active under realistic conditions. The ELITE tier sample-level 50Hz position overlay adds significant complexity on top of the PHOENIX rep-level implementation — evaluate shipping PHOENIX tier only in v0.5.1 and deferring ELITE sample-level overlay to v0.5.2 if device performance is borderline.

---

### Phase Ordering Rationale

The six-phase sequence is driven by three hard constraints:

1. **Board conditions first:** Board review can be triggered at any point during the milestone. Phase 1 makes the app Board-reviewable immediately (correct versionName, backup rules, UTC fix, camera rationale). Phases 2–6 incrementally add premium features without blocking or delaying review.
2. **FeatureGate before composables:** Phase 1 adds all three new Feature enum entries with tier set membership. No composable in Phases 2–6 references a non-existent Feature enum value — the compilation error catches it if someone tries. More importantly, no composable can be accidentally merged without a gate check because the test suite verifies every Feature value is locked to at least one tier.
3. **Ghost racing last:** `WorkoutHud.kt` must be stable (Phase 3 complete) before ghost racing adds its overlay slot. `WorkoutCoordinator` changes carry the highest regression risk of any single change in this milestone. Ghost racing should be the last feature merged so it can be reverted without affecting the four completed features below it if a blocking issue is found near release.

Additional grouping rationale:
- Phase 2 (WCAG) establishes `AccessibilityColors` and `LocalColorBlindMode` that Phases 3–6 all consume — building the infrastructure before the consumers ensures new composables get the right pattern from day one.
- Phase 3 (HUD) stabilizes `WorkoutHud.kt` before Phase 6 reopens it for the ghost overlay slot.
- Phases 4 and 5 (Readiness, RPG) are independent of each other and can be parallelized if engineering capacity allows — both follow the identical stateless engine pattern and neither touches the active workout lifecycle.

### Research Flags

Phases likely needing validation or post-launch calibration:
- **Phase 4 (Readiness):** ACWR threshold values come from population-level sports science studies. Flag for post-v0.5.1 per-user calibration. The `InsufficientData` guard must be manually verified on a fresh install before release — the unit test suite alone is insufficient because fixture data in tests typically has 30+ sessions.
- **Phase 5 (RPG):** Attribute normalization scaling parameters need real-user data distribution to calibrate. Ship with conservative scaling constants exposed as configurable values; adjust in v0.5.2 patch based on field data.
- **Phase 6 (Ghost Racing):** Integration testing on mid-range Android devices (min SDK 26) is required before merge. `handleMonitorMetric()` latency budget must be measured. ELITE tier 50Hz overlay complexity should be evaluated during implementation — if it adds unacceptable latency, defer ELITE to v0.5.2 and ship PHOENIX rep-level only.

Phases with standard patterns where deeper research is not needed:
- **Phase 1 (Board conditions):** All changes are isolated, fully specified in STACK.md and PITFALLS.md. UTC fix, backup XML structure, and versionName are all fully resolved.
- **Phase 2 (WCAG):** WCAG G111 technique is canonical; Compose CompositionLocal is well-documented; `AccessibilityColors` palette fully specified in ARCHITECTURE.md.
- **Phase 3 (HUD customization):** Follows existing `SingleExerciseDefaults` JSON blob pattern exactly. String-key persistence design fully specified in PITFALLS.md.

---

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | Zero new production dependencies. All decisions verified against current `libs.versions.toml` and official docs. Single new test dependency resolves via existing BOM with no version risk. |
| Features | HIGH | Ghost racing (Zwift HoloReplay reference), RPG (INFITNITE/FitDM competitor analysis), ACWR readiness (Garmin/TritonWear implementations) all have well-documented precedent. Local-only implementations are straightforward given existing schema v16. |
| Architecture | HIGH | Based on direct codebase inspection, not inference. All patterns are already proven in the codebase (`SmartSuggestionsEngine`, `SingleExerciseDefaults`, `FeatureGate`). Component boundaries and data flows fully specified. Five of six feature areas require zero schema migrations. |
| Pitfalls | HIGH | 12 pitfalls identified from codebase analysis and verified external sources. All critical pitfalls (ghost timing desync, RPG recomputation storm, FeatureGate gaps) have specific actionable prevention strategies and verification test criteria. |

**Overall confidence:** HIGH

### Gaps to Address

- **RPG attribute normalization scales:** The computation formulas are sound but the scaling constants (what raw 1RM or MCV value maps to 100/100) require real user data distribution to calibrate. Expose as configurable constants, not hardcoded values, so they can be adjusted in a v0.5.2 patch without schema changes.
- **Ghost racing ELITE tier (50Hz sample-level position overlay):** The PHOENIX tier (rep-level delta) is fully specified and low-risk. The ELITE tier adds per-sample position interpolation at BLE sample rate on top of the existing hot path. Evaluate during Phase 6 implementation — if it adds unacceptable latency on mid-range devices (SDK 26 minimum), defer ELITE to v0.5.2 and ship PHOENIX only.
- **Accompanist 0.37.3 compatibility with Compose 1.10.x:** Accompanist targets Compose 1.7. The permissions module is expected to work with 1.10.x (no API changes in the permissions surface), but if a runtime crash occurs on the permissions composable during Phase 1 camera rationale implementation, the fallback is `rememberLauncherForActivityResult` with manual `shouldShowRequestPermissionRationale()` — this is well-documented and the fallback is straightforward.
- **iOS HUD customization pager equivalent:** Research covers the Android `HorizontalPager` structure. iOS SwiftUI equivalent for the HUD preset pages (tab-based or swipe-based) is not specified in this research. Flag for iOS-specific design if the milestone scope includes iOS HUD customization beyond a stub.

---

## Sources

### Primary (HIGH confidence)
- `shared/.../sqldelight/.../VitruvianDatabase.sq` — complete schema v16 inspection; all tables, columns, and existing query capabilities verified
- `shared/.../presentation/screen/WorkoutHud.kt` — HUD pager structure, ExecutionPage slot pattern
- `shared/.../domain/premium/SmartSuggestionsEngine.kt` — stateless engine pattern to follow exactly
- `shared/.../domain/premium/FeatureGate.kt` — tier gate system, Feature enum, `phoenixFeatures` and `eliteFeatures` sets
- `shared/.../data/preferences/PreferencesManager.kt` — JSON blob preference pattern (`SingleExerciseDefaults`, `JustLiftDefaults`)
- `shared/.../iosMain/.../DriverFactory.ios.kt` — 4-layer defense, `CURRENT_SCHEMA_VERSION = 16L`
- [Android Developers — Auto Backup Configuration](https://developer.android.com/identity/data/autobackup) — `fullBackupContent` / `dataExtractionRules` XML format and dual-attribute requirement
- [Android Developers — Compose Accessibility Testing](https://developer.android.com/develop/ui/compose/accessibility/testing) — `enableAccessibilityChecks()`, `ui-test-junit4-accessibility` artifact
- [kotlinx-datetime API — currentSystemDefault](https://kotlinlang.org/api/kotlinx-datetime/kotlinx-datetime/kotlinx.datetime/-time-zone/-companion/current-system-default.html) — KMP-correct local timezone API
- [WCAG 1.4.1: Use of Color](https://www.w3.org/WAI/WCAG21/Understanding/use-of-color.html) — primary standard; secondary differentiator requirement
- [WCAG G111: Using Color and Pattern](https://www.w3.org/TR/WCAG20-TECHS/G111.html) — canonical implementation technique for color-blind compliance

### Secondary (MEDIUM confidence)
- [Zwift HoloReplay Feature Overview](https://zwiftinsider.com/holoreplay/) — ghost racing architecture; time-indexed replay model; personal-best-only design rationale
- [Garmin Acute:Chronic Load Ratio](https://support.garmin.com/en-US/?faq=C6iHdy0SS05RkoSVbFz066) — ACWR thresholds: Green 0.8–1.3, Yellow 0.5–0.8 / 1.3–1.5, Red outside
- [TritonWear Readiness Score Methodology](https://support.tritonwear.com/how-readiness-score-is-calculated) — volume-only ACWR without wearable; bootstrap state design with sparse data
- [Accompanist Permissions](https://google.github.io/accompanist/permissions/) — `shouldShowRationale`, `rememberPermissionState` API; 0.37.3 confirmed latest stable
- [multiplatform-settings GitHub](https://github.com/russhwolf/multiplatform-settings) — version 1.3.0, coroutines module for StateFlow integration
- [Compose BOM to Library Mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping) — BOM 2025.12.01 maps to compose-ui 1.10.4; ATF artifact resolves via BOM

### Tertiary (MEDIUM-LOW confidence)
- [Readiness/Recovery Scores 2025 Evaluation — De Gruyter](https://www.degruyterbrill.com/document/doi/10.1515/teb-2025-0001/html) — academic review of ACWR validity; confirms ±20% error margin; validates advisory-only framing as correct approach
- [INFITNITE Fitness Fantasy RPG](https://fitnessfantasyrpg.com/) — auto-assigned character class from workout history; confirms v0.5.1 differentiator claim vs. user-selected classes in competitors
- [FitDM Character Classes](https://fitdm.io/classes) — competitor analysis; user-selected class confirmed as weaker approach than auto-assignment from biomechanics

---

*Research completed: 2026-02-27*
*Ready for roadmap: yes*
