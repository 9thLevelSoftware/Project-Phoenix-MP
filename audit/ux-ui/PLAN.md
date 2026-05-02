# Project Phoenix MP — UX/UI Audit Plan

**Branch:** `feat/ux-ui-audit-2026-05-01`
**Audit date:** 2026-05-01
**Scope:** All UX/UI findings affecting `Project-Phoenix-MP` (Compose Multiplatform mobile app). Cross-cutting items requiring coordinated changes in `phoenix-portal` are in `PARITY-COORDINATION.md` (read alongside this plan).
**Read-only audit deliverable.** No production source modified by the audit itself; this plan is the input to implementation.

---

## 0. Executive summary (mobile-focused)

The mobile app **passes every measured WCAG AA contrast test** on dark mode (lowest 5.36:1 — `SignalError` on `Slate950`). It already enforces ≥48dp touch targets via Compose `IconButton`/`Modifier.size(44.dp)`. Core workout flow (BLE → SetReady → ActiveWorkout → SetSummary → Rest → next set) is functional and respects the hard hardware constraints (no mid-set BLE pause, no mid-set exercise switch).

The damage is concentrated in **five areas**:

1. **BLE state communication is the most-confused surface in the product.** Three different copies for the connect pill across screens ("Connect" / "Connecting" / "Click to Connect"); "AUTO-START READY" banner shown while disconnected (false-positive readiness); `Connecting` stuck > 2 minutes with no timeout/retry; `ConnectionLostDialog` allows mid-workout dismiss with no recovery path; `ConnectionErrorDialog` tips don't adapt to the actual error cause. **Mockup M-01 consolidates these into a unified state machine** with three canonical pill states + cause-aware error branching + non-dismissible recovery dialog.
2. **Mobile a11y has 3 conformance failures** — Home tiles (4) + Add-Exercise FAB are flagged `NAF="true"` by UIAutomator (TalkBack effectively unusable on home navigation); Just Lift layout breaks at `font_scale=1.5` (weight stepper rows overlap, mode strip clips 3/6 modes with no scroll affordance — WCAG 1.4.4 / 1.4.10 fail); the app honors **no system reduced-motion** anywhere (springs, infinite pulses, Lottie all run unconditionally — WCAG 2.2.2 fail for the infinite alert pulse).
3. **RoutineEditor is a data-loss trap** — saves empty/unnamed routines with zero validation (phantom "Unnamed Routine" entries that silent-fail on launch); has no `BackHandler` and no unsaved-changes warning (5+ minutes of editing nuked by a single accidental back-tap). Compare to `CycleEditorScreen` which has UNDO snackbar + back-handler — same domain, inconsistent UX. **Mockup M-02 consolidates these** with empty-state copy + template suggestions + save validation + discard confirmation + UNDO snackbar parity.
4. **Brand identity drifts internally on mobile** — 4 distinct "Phoenix orange" hues coexist (`#FF9149`, `#FF6B00`, `#FF6B35`, `#F97316`); the home-screen primary CTAs are blue/purple via a generic `HomeButtonColors` palette unrelated to Phoenix branding; `EulaScreen.kt` redeclares its own private color tokens shadowing the theme. The mobile app uses **no custom typography** (`FontFamily.Default` everywhere) — Chakra Petch, Inter, JetBrains Mono are entirely absent on the surface where users actually train. This is the largest single brand-unification investment available.
5. **Cross-platform parity inversions** — Cable A/B colors are flipped vs portal (`DataColors.LoadA = #3B82F6` blue, portal cable A = ember orange); same data, swapped meaning. The Phoenix brand color (Ember `#FF6B35`) on portal becomes a desaturated peach `#FF9149` on mobile — the documented brand color is *only* correct on the crash screen (`App.kt:84`).

**Estimated effort for the mobile CRITICAL backlog (Section 2):** ≈ **8-12 dev days** for the immediate work; brand-unification (Wave 4) is an additional **2-3 weeks** because typography bundling, surface-scale alignment, and radii decision are coordinated mobile-side investments.

---

## 1. Audit yield (mobile)

| Source file | Findings | C / H / M / P |
|---|---|---|
| `findings/01-mobile-static.md` | 22 | 4 / 9 / 7 / 2 |
| `findings/06-mobile-live.md` | 30 | 5 / 9 / 10 / 6 |
| `findings/04-a11y-parity.md` (mobile-relevant rows) | ~10 | 1 / 4 / 4 / 1 |
| `findings/03-visual-brand.md` (mobile-relevant rows) | ~12 | 2 / 8 / 1 / 1 |
| **Mobile-affecting unique (after dedup with portal)** | **~62** | **9 / 24 / 18 / 10** |

Counts include parity items where the *mobile-side* fix is required.

---

## 2. Top fix-first list (mobile)

Each row links back to the source agent's finding ID. **Effort estimates are dev-only.**

| # | Severity | Title | Effort | Source | Notes |
|---|---|---|---|---|---|
| M-1 | CRITICAL | **"This Week\\'s Volume" literal escape character on Insights** (visible in dark + light + landscape + airplane — themewise invariant, source data) | ≤30 min | `06-F-004` | `SmartInsightsTab.kt` insight definitions or string resources. Add test asserting no `\` in user-facing insight strings |
| M-2 | CRITICAL | **Home tiles + Add-Exercise FAB `NAF="true"`** (TalkBack effectively unusable on home tab; 4 tiles + 1 FAB unlabeled) | ≤2 hr | `06-F-005` | Add `Modifier.semantics { contentDescription = "..." }` to each Button/Card wrapper for the home tiles; add `contentDescription = "Add exercise"` to Routines FAB. Run `accessibility_audit.py` to verify |
| M-3 | CRITICAL | **"AUTO-START READY" banner shown while BLE disconnected** (false-positive readiness — user grabs handles, nothing happens, blames app) | ≤2 hr | `06-F-001` | Bind banner visibility to `connectionState == Connected`. Disconnected/Connecting render dim grey rows with inline Connect button. **Covered by Mockup M-01 §D** |
| M-4 | CRITICAL | **RoutineEditor save validation + BackHandler discard guard** (combines empty-routine save + 5-min-editing-nuked-on-back-tap) | ≤2 hr | `01-F-002` + `01-F-003` | Disable Save when `name.isBlank() || exercises.isEmpty()`; track `hasUnsavedChanges = state != initialRoutine`; `BackHandler { showDiscardDialog }`. **Covered by Mockup M-02 §B + §C** |
| M-5 | CRITICAL | **Cable A/B colors inverted vs portal** (mobile A=blue B=orange; portal A=orange B=blue — same data, swapped meaning across every chart and replay) | ≤2 hr | `03-F-001` | **PARITY** — see `PARITY-COORDINATION.md §2`. Flip `DataColors.LoadA/LoadB/PositionA/PositionB`; audit ~10 chart consumers |
| M-6 | CRITICAL | **`ConnectionLostDialog` allows mid-workout dismiss with no recovery** (user can orphan into frozen UI) | ≤4 hr | `01-F-001` | Replace single Dismiss button with three explicit recovery actions (Reconnect & continue / End set & save reps / End workout). Make non-dismissible. **Covered by Mockup M-01 §B** |
| M-7 | CRITICAL | **Connect pill state inconsistency + Connecting stuck >2 min** (3 different copies; no timeout) | ≤4 hr | `06-F-002` | Single source of truth pill composable with 3 canonical states + 2 error sub-states. Use `BleConstants.SCAN_TIMEOUT_MS = 30000L` for Connecting timeout. **Covered by Mockup M-01 §A** |
| M-8 | CRITICAL | **Layout breaks at `font_scale=1.5` on Just Lift** (weight stepper overlaps, mode strip clips 3/6 modes — WCAG 2.2 AA 1.4.4 + 1.4.10 fail) | ≥1 day | `06-F-003` | Convert mode strip to horizontally-scrollable LazyRow with edge-fade. Convert weight stepper from stacked-line picker to single large numeric display + dedicated +/- buttons. Re-test at 1.0/1.30/1.5/1.8 |
| M-9 | CRITICAL | **Mobile uses `FontFamily.Default` for every text style** (Chakra Petch, Inter, JetBrains Mono entirely absent — live numerics jitter without tabular figures) | ~1.5 days | `03-F-003` + `03-F-012` | Bundle fonts to `composeResources/font/`. Build `PhoenixFontFamilies` object. Refactor `Type.kt` to use `display`/`body`/`data` per role. Wire HUD numerics (`WorkoutHud`, `RestTimerCard`, `SetSummaryCard`) to `data*` styles. **Largest single brand-unification investment.** |
| M-10 | HIGH | **Mobile no system reduced-motion anywhere** (springs, infinite pulses, Lottie all run unconditionally) | ≥1 day | `04-F-003` + `01-F-014` | Add `expect fun isReduceMotionEnabled(): Boolean` (Android: `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`; iOS: `UIAccessibility.isReduceMotionEnabled`). Provide `LocalReducedMotion` `CompositionLocal`. Wrap `springFor()` and `infiniteRepeatable()` |
| M-11 | HIGH | **Phoenix Ember orange drift on mobile** (4 distinct hues coexist; `PhoenixOrangeDark = #FF9149` ≠ canonical `#FF6B35`) | ≤2 hr | `01-F-004` + `03-F-004` + `06-F-016` | **PARITY** — adopt `#FF6B35`. Replace `PhoenixOrangeDark`. Sweep stale `FireOrange = #FF6B35` literals in `EulaScreen.kt:25`, `SplashScreen.kt:36`. Replace `Color(0xFFFF6B00)` in `HomeScreen.kt:346`. Update `EnhancedMainScreen.kt:284-287` gradient |
| M-12 | HIGH | **Background + surface scale diverge from portal** (mobile cobalt-tinted `Slate950 → Slate700`, 2-3× lighter than portal `#06060A → #141420`) | ~4 hr | `03-F-005` + `06-F-017` | Add `Phoenix0/1/2/3 = #06060A / #0a0a10 / #0e0e14 / #141420`. Rebuild `DarkColorScheme` to map Material container roles. Verify all dark-mode screens still pass AA |
| M-13 | HIGH | **`BalanceBar` text at 8sp/9sp** (below WCAG 12sp body floor) | ≤30 min | `04-F-009` | `BalanceBar.kt:154,163,175`. Lift to `MaterialTheme.typography.labelSmall` (11sp) without `.copy(fontSize = 8.sp)` override |
| M-14 | HIGH | **Theme toggle binary (SYSTEM enum exists, unreachable)** | ≤2 hr | `04-F-010` | `ThemeToggle.kt:27-32`. Convert to 3-state cycle (Light → Dark → System) |
| M-15 | HIGH | **Home screen primary CTAs are blue/purple via `HomeButtonColors`** (off-brand on the most-visible surface) | ≥1 day | `01-F-005` | Delete `HomeButtonColors.kt`. Refactor `AnimatedActionButton.kt:294-299` to use `MaterialTheme.colorScheme.primaryContainer`/`primary`. Validate visual hierarchy still reads with warmer palette |
| M-16 | HIGH | **No global "loading routines/PRs/sessions" state** (initial-load shows "No workouts yet" empty-state copy to existing users) | ~2 hr per screen | `01-F-006` | Add `isLoading: StateFlow<Boolean>` to `MainViewModel`. Render `ShimmerEffect` while loading; empty state only after `isLoading == false && data.isEmpty()` |
| M-17 | HIGH | **No offline / network-error state** for sync-dependent surfaces (badges, insights, videos, integrations) | ≥1 day | `01-F-007` | Add `NetworkState` flow at `MainViewModel`. Show inline banner "Offline — videos & insights unavailable" on `SmartInsightsTab`, `BadgesScreen`, `SetReadyScreen`. Replace silent video-load failure with explicit "Video unavailable offline" placeholder |
| M-18 | HIGH | **"Per-cable" weight unit shown without explanation** on every weight surface except `WeightStepper` | ≤2 hr | `01-F-008` | **PARITY** — see `PARITY-COORDINATION.md §3`. Add `(i)` info icon next to "Weight per cable" labels. Display total alongside per-cable on `SetReadyScreen` and `RoutineOverviewScreen` |
| M-19 | HIGH | **Asymmetry threshold 10/15 in `BalanceBar.kt` vs canonical 2%** | ≤2 hr | `04-F-005` | **PARITY** — see `PARITY-COORDINATION.md §4`. Update `BalanceBar.kt:55-59` to `<2 = good, 2-10 = caution, >10 = bad` |
| M-20 | HIGH | **Workout-mode segmented control on Just Lift truncates description + clips 3/6 modes** | ≤2 hr | `06-F-007` + `06-F-021` + `06-F-134` (G-134) | LazyRow with edge-fade. 2-3 line `Text` with overflow=Visible + per-mode info bottom sheet. Folded into M-8's redesign |
| M-21 | HIGH | **Workouts header rotates between "Workouts" and "Choose Your Workout"** (same destination, different label) | ≤30 min | `06-F-006` | Pick one (recommend "Workouts" to match bottom-tab label). Remove the conditional |
| M-22 | HIGH | **Analytics filter chip "All time" rendered as "All tim"** (clipped — credibility hit on paid surface) | ≤30 min | `06-F-008` | Fix chip min-width or content-padding |
| M-23 | HIGH | **Insights heading duplicated** (top-app-bar + Hero card both say "Smart Insights") | ≤1 hr | `06-F-009` | Drop the Hero card. App-bar canonical |
| M-24 | HIGH | **Light theme cards have <2:1 boundary contrast** (WCAG 1.4.11 fail) | ≥1 day | `06-F-010` | Audit light-theme tokens. Either lift card-stroke to ≥3:1 or drop light theme entirely (recommended — workout product, low-light usage). Light theme appears auto-derived |
| M-25 | HIGH | **Routines tab hides bottom-nav** (inconsistent with Insights/Settings/Analytics) | ≥1 day | `06-F-011` | Decide tabbed vs routed. Recommend routed only for `JustLift`/`ActiveWorkout`; nav bar visible everywhere else |
| M-26 | HIGH | **Routine row chevron mis-affordance** (looks "expand"; tap actually starts the routine) | ≤2 hr | `06-F-012` | Add explicit `Start` pill. Or split: tap row = expand, tap `Play` icon = start |
| M-27 | HIGH | **New Routine empty state is blank** (no copy, no template suggestions) | ≤4 hr | `06-F-013` | **Covered by Mockup M-02 §A.** Empty-state component with title, body, [+ Add exercise] + [⚡ Start from template] |
| M-28 | HIGH | **Right-edge "Open profiles" notch is a hidden affordance** (no visible label/icon) | ≥1 day | `06-F-014` | Either move profiles to Settings → Profiles, or replace notch with avatar/identity chip |
| M-29 | HIGH | **`ConnectionErrorDialog` tips don't adapt to actual error cause** (Bluetooth-disabled vs scan-timeout vs permission-denied — same bullet list) | ≥1 day | `01-F-012` | Branch on `BleErrorCause` enum. **Covered by Mockup M-01 §C** |
| M-30 | HIGH | **3 different exercise-config UIs** (BottomSheet vs Modal vs Dialog) with different scopes | ≥1 day | `01-F-010` | Consolidate to one `ExerciseConfigSheet` with `editScope: SCALAR_ONLY \| FULL` flag |
| M-31 | HIGH | **4 different exit-workout dialog patterns** (RoutineOverview / SetReady / ActiveWorkout / EnhancedMainScreen) | ≥1 day | `01-F-009` | Define single shared `ExitWorkoutDialog` composable. Always pop to `DailyRoutines.route` |

(Top-31 listed; Section 3 catalogs the rest.)

---

## 3. Severity-ranked findings catalog (mobile-only)

> Format: ID — title — finding-source. Full text in `findings/`.

### CRITICAL (9)

| ID | Title | Source |
|---|---|---|
| M-C1 | "This Week\\'s Volume" escape-char | `06-F-004` |
| M-C2 | Home tiles + FAB `NAF="true"` (TalkBack broken) | `06-F-005` |
| M-C3 | "AUTO-START READY" while disconnected | `06-F-001` |
| M-C4 | RoutineEditor data loss (no validation + no BackHandler) | `01-F-002` + `01-F-003` |
| M-C5 | Cable A/B color identity inverted | `03-F-001` (PARITY) |
| M-C6 | ConnectionLostDialog allows dismiss with no recovery | `01-F-001` |
| M-C7 | Connect pill state inconsistency + stuck Connecting | `06-F-002` |
| M-C8 | Layout breaks at `font_scale=1.5` on Just Lift | `06-F-003` |
| M-C9 | Typography absent (no Chakra Petch / Inter / JetBrains Mono) | `03-F-003` + `03-F-012` |

### HIGH (≈ 24)

| ID | Title | Source |
|---|---|---|
| M-H1 | No reduced-motion support | `04-F-003` + `01-F-014` |
| M-H2 | Phoenix Ember drift (4 hues coexist) | `01-F-004` + `03-F-004` + `06-F-016` (PARITY) |
| M-H3 | Background + surface scale diverge from portal | `03-F-005` + `06-F-017` (PARITY) |
| M-H4 | `BalanceBar` text 8sp/9sp | `04-F-009` |
| M-H5 | Theme toggle binary (SYSTEM unreachable) | `04-F-010` |
| M-H6 | Home CTAs blue/purple (off-brand) | `01-F-005` |
| M-H7 | No global loading state on routines/PRs/sessions | `01-F-006` |
| M-H8 | No offline UI for sync-dependent surfaces | `01-F-007` |
| M-H9 | Per-cable weight unit unexplained | `01-F-008` (PARITY) |
| M-H10 | Asymmetry threshold (mobile 10/15 vs canonical 2%) | `04-F-005` (PARITY) |
| M-H11 | Just Lift mode strip clips 3/6 modes | `06-F-007` + `06-F-021` |
| M-H12 | Workouts header rotates label | `06-F-006` |
| M-H13 | Analytics chip "All tim" clipped | `06-F-008` |
| M-H14 | Insights duplicate heading | `06-F-009` |
| M-H15 | Light theme card contrast <2:1 | `06-F-010` |
| M-H16 | Routines tab hides bottom-nav | `06-F-011` |
| M-H17 | Routine row chevron mis-affordance | `06-F-012` |
| M-H18 | New Routine empty state blank | `06-F-013` |
| M-H19 | "Open profiles" hidden affordance | `06-F-014` |
| M-H20 | ConnectionErrorDialog non-adaptive tips | `01-F-012` |
| M-H21 | 3 different exercise-config UIs | `01-F-010` |
| M-H22 | 4 different exit-workout dialog patterns | `01-F-009` |
| M-H23 | AutoDetectionSheet competes mid-rest | `01-F-011` |
| M-H24 | SetReady "Stop" X-icon ambiguous destination | `01-F-013` |
| M-H25 | Border radii diverge 2-5× from portal | `03-F-007` (PARITY) |
| M-H26 | Tonal elevation vs portal drop shadows | `03-F-008` + `03-F-016` (PARITY) |
| M-H27 | Card surface 3× lighter than portal | `03-F-016` (PARITY) |
| M-H28 | "Secondary" token = brand gold (vs portal neutral) | `03-F-009` (PARITY) |
| M-H29 | Tertiary = teal (not in Phoenix palette) | `03-F-010` (PARITY) |
| M-H30 | Forge Green (`#10B981`) absent on mobile | `03-F-011` (PARITY) |
| M-H31 | Charts lead with neutral blue (vs portal brand orange) | `03-F-015` (PARITY) |
| M-H32 | Mobile lacks ConsistencyCalendar (portal has 12-week heatmap) | `04-F-008` (PARITY) |

### MEDIUM (≈ 18 — see findings files for full text)

Highlights: EulaScreen redeclares brand colors as private constants [`01-F-015`]; no skip-vs-completed differentiation in RoutineOverview [`01-F-016`]; Just Lift default = 1 lb (0.453592 kg) odd decimal [`01-F-017`]; HomeScreen weekly compliance dot today/past indistinguishable [`01-F-018`]; BadgesScreen loading replaces grid (no skeleton) [`01-F-019`]; CycleEditor has UNDO; RoutineEditor lacks it [`01-F-020`]; mobile Cloud Sync settings buttons over-weighted [`06-F-023`]; toggle row hit area is Switch-only (labels not tappable) [`06-F-018`]; WorkoutSetupDialog Start CTA below the fold [`06-F-019`]; exercise-picker alphabetical strip ~24dp wide (borderline 2.5.8) [`06-F-020`]; achievements/badges 0% looks like loading shimmer [`06-F-022`]; "Issue 389 Demo Routine" dev fixture in seed list [`06-F-015`]; no retry CTA on failed BLE connect [`06-F-024`]; eyebrow style ad-hoc letterSpacing in 6+ components (no token) [`03-F-017`]; no `signal-panel` equivalent — 8+ ad-hoc Card variants [`03-F-018`]; missing 12dp/20dp spacing tokens [`03-F-021`]; mobile crash screen uses correct `#FF6B35` (the only correct usage on mobile) [`03-F-014`]; decorative "Project Phoenix" subtitle announced on every screen change to AT [`04-F-015`]; workout-mode iconography ad-hoc (no canonical Mode→Icon map) [`04-F-013`]; mobile light theme has mint-tinted background (off-brand) [`03-F-006`]; PR phases not surfaced on portal but exist on mobile [`04-F-007`]; mobile lacks TierBadge composable [`04-F-013` parity row 27]; mobile lacks paid-feature gating UI [`04-F-013` parity row 28]; mobile workout-mode order non-canonical [`04-F-013` parity row 22]; superset visual fidelity differs (mobile uses colored connector + SupersetHeader; portal uses border-only) [`04-F-013` parity row 30].

### POLISH (≈ 10)

`Brush.linearGradient`/`Brush.verticalGradient` allocated inline on every recompose in 4+ files [`01-F-021`]; AnalyticsScreen FAB obscures content on Compact width [`01-F-022`]; Settings donate-card heart yellow-on-yellow [`06-F-025`]; bottom-nav lozenge indicator deviates from M3 [`06-F-026`]; active-mode outline mustard-on-khaki [`06-F-027`]; reduced-motion doesn't suppress title swap (folded into M-12 fix) [`06-F-028`]; HomeScreen weekly dots barely visible [`06-F-029`]; Just Lift fire-particle background obscures label [`06-F-030`]; mobile lacks scan-line/grain texture [`03-F-020`]; iconography divergence Material vs Lucide [`03-F-019`]; AMRAP indicator text-only [`04-F-018`].

---

## 4. Cross-cutting items (require portal coordination)

See `PARITY-COORDINATION.md` for full coordination protocol. Summary table:

| # | Item | Mobile action | Portal action | Sequence |
|---|------|---------------|---------------|----------|
| §1 | Velocity-zone palette | Reference (mobile is canonical) | Flip portal `lib/vbt.ts:SIMPLIFIED_ZONES` | Portal-only |
| §2 | Cable A/B color identity | **Flip `DataColors.LoadA/B/PositionA/B`**; audit ~10 chart consumers | Reference (portal is canonical) | Mobile-only |
| §3 | Per-cable weight | Keep existing (already on `WeightStepper`); apply same dual-display pattern to `SetReadyScreen`, `RoutineOverviewScreen`, `RestTimerCard`, `SetSummaryCard` | Add disclosure tooltip | Independent |
| §4 | Asymmetry threshold | **Update `BalanceBar.kt:55-59`** to `<2 = good, 2-10 = caution, >10 = bad` | Use canonical `ASYMMETRY_BALANCED_THRESHOLD_PCT = 2` everywhere | Coordinated single PR per repo, ship together |
| §5 | Theme toggle (Light/Dark/System tri-mode) | **Convert `ThemeToggle.kt` to 3-state cycle** | Add toggle to AppLayout | Independent per repo |
| §6 | RPG attributes surface | Reference (mobile already has `RpgAttributeCard`; data flows portal-bound via `rpg_attributes`) | Build `<RpgAttributeCard>` portal component | Portal-only |
| §7 | Badges grid | Reference (mobile is the canonical layout) | Build `<BadgesGrid>` matching mobile | Portal-only |
| §8 | ConsistencyCalendar | **Build `ConsistencyCalendar.kt` Composable** mirroring portal's 12-week ember-intensity heatmap | Reference | Mobile-only |
| §9 | Brand palette unification (Ember `#FF6B35`, surface scale, radii, typography, drop-shadow depth) | **Mobile adopts portal canonical** (largest single brand-unification investment) | Canonical | Mobile-side, multi-PR. See `mockups/M-XX` placeholders if Wave-4 design specs are produced |

---

## 5. Recommended sequencing (mobile)

| Wave | Days | Items |
|------|------|-------|
| **W1 — Stop the bleeding** | ~1 day | M-1 (escape char), M-2 (NAF a11y), M-13 (BalanceBar 8sp), M-21 (header label rotation), M-22 (chip clip). 1 squashed PR or 5 small ones |
| **W2 — BLE + RoutineEditor (mockups land)** | 4-6 days | M-3 (banner gating, M-01 §D), M-6 (M-01 §B dialog), M-7 (M-01 §A pill), M-29 (M-01 §C error branching), M-4 (M-02 §B + §C save + back-handler), M-27 (M-02 §A empty state) |
| **W3 — A11y + state coverage** | 3-5 days | M-8 (font_scale layout fix), M-10 (reduced-motion CompositionLocal), M-16 (loading state), M-17 (offline state) |
| **W4 — Brand unification** | 2-3 weeks | M-9 (typography bundle — fonts, dataLarge style), M-11 (Phoenix Ember adoption), M-12 (surface scale), M-15 (drop HomeButtonColors), M-H25-29 (radii, depth, secondary, tertiary, Forge Green). Multi-PR; verify AA on every dark-mode screen |
| **W5 — Workout-flow consolidation + missing surfaces** | 1-2 weeks | M-30 (consolidate exercise-config UIs), M-31 (single ExitWorkoutDialog), M-25 (Routines bottom-nav), M-26 (routine row affordance), M-32 = M-H32 (build ConsistencyCalendar.kt), M-23 (Insights heading), M-24 (light theme audit) |
| **W6 — Polish backlog** | rolling | All MEDIUM + POLISH from §3, addressed during regular maintenance |

---

## 6. Mockup index

| Mockup | Covers | File | Lines |
|---|---|---|---|
| **M-01** | Mobile BLE state machine + recovery (single ConnectionPill, ConnectionLostDialog redesign, cause-aware ConnectionErrorDialog, AUTO-START gating) | `mockups/M-01-mobile-ble-state-machine.md` | 574 |
| **M-02** | Mobile RoutineEditor (empty state + template picker, save validation, back-handler discard guard, UNDO snackbar parity) | `mockups/M-02-mobile-routine-editor.md` | 419 |

(Portal mockup M-03 is in `phoenix-portal/audit/mockups/` — no mobile-side equivalent needed.)

---

## 7. Acceptance criteria (definition-of-done for the audit response)

The audit response is "complete" when:
- [ ] All CRITICAL items M-1 through M-9 are merged.
- [ ] All HIGH items M-10 through M-31 are either merged or have a tracked ticket with an owner.
- [ ] Mockup M-01 is implemented (BLE state machine — 4 findings consolidated).
- [ ] Mockup M-02 is implemented (RoutineEditor — 3 findings consolidated).
- [ ] `LocalReducedMotion` `CompositionLocal` is shipped and audited animation sites are gated.
- [ ] Cross-cutting items §2, §4, §8 (mobile-action items) are coordinated with portal per `PARITY-COORDINATION.md`.
- [ ] Doc-rot in mobile `CLAUDE.md` (Daem0n project_path) is fixed.
- [ ] `accessibility_audit.py` passes with zero `NAF="true"` regressions on home tab.
- [ ] WCAG AA contrast verified on all dark-mode screens after Wave-4 surface-scale rebuild.

---

## 8. Resources

- **`README.md`** — folder structure overview
- **`PARITY-COORDINATION.md`** — cross-cutting items + portal coordination
- **`findings/01-mobile-static.md`** — 22 findings, full text, code locations
- **`findings/06-mobile-live.md`** — 30 findings + 64 captured screenshots from Pixel 8 emulator walkthrough
- **`findings/03-visual-brand.md`** — palette/typography/spacing parity (cross-platform context)
- **`findings/04-a11y-parity.md`** — WCAG ratios + 20-row parity matrix (cross-platform context)
- **`mockups/M-01-mobile-ble-state-machine.md`** — 574-line BLE redesign covering 4 findings
- **`mockups/M-02-mobile-routine-editor.md`** — 419-line RoutineEditor redesign covering 3 findings
- **`screenshots/`** — 64 PNGs (splash → workout → font-scale → reduced-motion → light theme → landscape → airplane)

---

## 9. Out of scope (not addressed by this audit)

- BLE protocol / Nordic stack details
- Sync logic (`SyncManager`, `SyncTriggerManager`, `PortalApiClient`)
- iOS-specific rendering (no Mac available — iOS findings flagged as risks only)
- BLE-connected workout flows (`ActiveWorkoutScreen`, `SetReadyScreen`, `SetSummaryCard`, `RestTimerCard`, `RoutineCompleteScreen`) — not testable on emulator without real Vitruvian hardware. Recommend follow-up live walkthrough on a paired device
- TalkBack live walkthrough (recommended after M-2 ships)
- Performance benchmarking (jank, scroll perf, animation FPS)
- Gamification badge computation logic
- Brand voice / copy tone consistency

---

**End of plan.** This document is the definitive scope for the mobile UX/UI audit response. All claims trace back to `findings/` and `screenshots/` for verification.
