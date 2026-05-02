# 06 — Mobile Live Walkthrough Findings

**Date:** 2026-05-01
**Tester surface:** `com.devil.phoenixproject.debug` debug APK installed on Android emulator (Pixel_8_Pro, Android 14, 1080x2400 @ 420 dpi).
**Method:** Empirical walkthrough using `adb shell input tap`, UIAutomator XML dumps to locate Compose-rendered clickable parents, screencap evidence in `_audit/screenshots/mobile/01-...64-...png`.
**Constraint compliance:** No source modifications. No BLE pause/disconnect proposals. No mid-set exercise-switch proposals. Per-cable weight convention preserved in all proposed fixes.

---

## Severity tally

| Tier | Count |
|------|-------|
| CRITICAL | 5 |
| HIGH | 9 |
| MEDIUM | 10 |
| POLISH | 6 |
| **Total** | **30** |

---

## CRITICAL findings

### F-001 [CRITICAL] "AUTO-START READY" banner is shown while BLE is disconnected

**Surface:** Mobile
**Category:** 3 (state coverage — BLE)
**Location:** `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt` (auto-start status banner) — header still reads "Connect" while banner reads "AUTO-START READY".
**Observation:** On Just Lift entry with the machine fully disconnected (header pill = "Connect", solid blue, never animated), the screen shows a green "AUTO-START READY · Grab handles to start" banner with a green status dot (screenshots `05-just-lift.png`, `09-ble-connection-fail.png`). The banner state does not reconcile with the connection state.
**Why it hurts:** A user grabbing the handles will get nothing — the machine is not paired. They will believe the app is broken or the machine is broken. For a community rescue project where pairing already requires patience, an immediate false-positive readiness signal will burn first-time-user trust.
**Severity rationale:** This is CRITICAL not HIGH because it actively blocks the primary use case (start a workout) and gives an affirmatively wrong status — not just missing information. It violates Charter Hard Constraint #3 spirit (state coverage for BLE Connecting/Connected/Lost/Fault) by skipping the disconnected case.
**Proposed fix (quick-win, ≤2 hr):** Bind banner visibility to `connectionState == Connected`. When disconnected, replace with a dim "Connect Vitruvian to enable auto-start" affordance that links to the connect flow. Keep auto-start logic intact — only gate the visual.
**Parity flag:** YES — portal does not show readiness without a paired machine; mobile should match.

---

### F-002 [CRITICAL] Top-right Connect button copy/state rotates between "Connect", "Connecting", and "Click to Connect"

**Surface:** Mobile
**Category:** 3 (state coverage), 1 (visual consistency)
**Location:** Header pill across `HomeScreen.kt`, `JustLiftScreen.kt`, `SmartInsightsTab.kt`, `RoutinesTab.kt`, etc. (rendered as a `Button` in the top app-bar slot).
**Observation:** Same disconnected state shows three different labels across screens captured in the same run: `01-splash-or-first.png` "Connect", `09-ble-connection-fail.png` "Connecting" (after a tap, never resolves), `30-routines.png` "Connect", `60-light-theme.png` "Click to Connect", `62-landscape.png` "Click to Connect". The wording, length, and pill width all change.
**Why it hurts:** Users learn affordances by silhouette; a header that changes width and copy on every navigation forces re-reading on every screen. "Connecting" without timeout/retry is also stale state — the screen never updated after the failed attempt at 9:22 → 9:25.
**Severity rationale:** CRITICAL because (a) it materially harms learnability of the most important control in the app, (b) "Connecting" stuck for >2 minutes is a state-machine bug visible to the user (false busy state).
**Proposed fix (quick-win, ≤2 hr):** Single source of truth for the header pill. Three states only: `Connect` (idle), `Connecting…` (with timeout → `Retry`), `Connected` (with battery/signal). Use the same string constant on every screen.
**Parity flag:** YES — portal has a single connection chip; mobile should match.

---

### F-003 [CRITICAL] Layout breaks at `font_scale = 1.5` on Just Lift (weight stepper rows overlap)

**Surface:** Mobile
**Category:** 7 (a11y — dynamic type)
**Location:** `JustLiftScreen.kt` Weight per Cable stepper composable.
**Observation:** Screenshot `58-justlift-fontscale.png`. With Android system `font_scale=1.5`, the three-line picker "43.5 lbs / 44 lbs / 44.5 lbs" collapses into overlapping rows; the underline strikethrough crosses the active value; lbs glyphs sit on top of each other. At 1.30 (`54-fontscale-130.png`) the home tile labels and 4-tile grid still fit; at 1.5 the workout-mode strip clips ("Old / Pump / Echo" only — TUT/TUTBeast/EccentricOnly off-screen, no horizontal scroll affordance).
**Why it hurts:** Users with low-vision settings cannot read the active weight at a glance. They are also locked out of 50% of workout modes because there is no scroll affordance to reach them. This is a WCAG 2.2 AA failure (1.4.4 Resize Text + 1.4.10 Reflow).
**Severity rationale:** CRITICAL because (a) it causes WCAG AA failure on the primary workout configuration screen, (b) the affected users (vision-impaired) are explicitly the ones who set font_scale ≥1.5, (c) a workout mode being unreachable is a functional loss not a polish issue.
**Proposed fix (design-spike, ≥1 day):** Convert mode strip to horizontally-scrollable LazyRow with edge-fade indicators. Convert weight stepper from stacked-line picker to single large numeric display with dedicated +/- buttons and an explicit "tap to type" entry. Re-test at 1.0/1.30/1.5/1.8.
**Parity flag:** NO (portal handles dynamic type via browser zoom — different mechanism).

---

### F-004 [CRITICAL] Recurring `\'s` escape-character bug ("This Week\'s Volume") appears in dark *and* light theme

**Surface:** Mobile
**Category:** 1 (visual/copy)
**Location:** `SmartInsightsTab.kt` insight card titles (also visible on Insights summary, all themes).
**Observation:** Screenshots `18-insights.png`, `19-insights-scroll.png`, `61-light-theme-insights.png`, `62-landscape.png`, `63-airplane-mode.png` all show `This Week\'s Volume` rendered with literal backslash. Bug persists in light theme, dark theme, portrait, landscape, airplane mode.
**Why it hurts:** This is the first card a user sees on Insights tab. A literal `\'` indicates "a developer didn't test this" — it lowers perceived quality of the entire Insights surface and makes the recommendations themselves feel untrusted.
**Severity rationale:** CRITICAL because it affects the headline title of the headline card on a primary tab and the bug is themewise invariant (so it's source data, not a render path).
**Proposed fix (quick-win, ≤30 min):** Find the string in resources / sealed-class insight definitions and replace `This Week\'s Volume` with `This Week's Volume`. Add an instrumented test asserting no `\` in user-facing insight strings.
**Parity flag:** YES — the portal Insights surface uses the apostrophe correctly; this is a mobile-only typo.

---

### F-005 [CRITICAL] Multiple primary CTAs are flagged `NAF="true"` (Not Accessibility Friendly) in UIAutomator

**Surface:** Mobile
**Category:** 7 (a11y — screen-reader labels)
**Location:** UIAutomator dump of Routines tab + Home tab + JustLift screen.
**Observation:**
- Add Exercise FAB on Routines tab at bounds `[651,2656][1288,2852]` → `NAF="true"`, no `content-desc`.
- All four home-screen tiles (Cycles, Single Exercise, Routines, Just Lift) at `[42,2012][658,2264]`, `[686,2012][1302,2264]`, `[42,2292][658,2544]`, `[686,2292][1302,2544]` → `NAF="true"`, only the inner TextView has the label and that TextView is `clickable=false`.
- Right-edge profile chevron has `content-desc="Open profiles"` but no visible text label (different sub-issue, see F-014).
**Why it hurts:** TalkBack users either cannot focus these elements or hear them announce "unlabeled button". The four home tiles are the primary navigation of the app — TalkBack users effectively cannot use the app.
**Severity rationale:** CRITICAL because (a) Android assigns `NAF="true"` only when the framework cannot derive a label, (b) this affects 100% of the home-tab navigation, (c) the Cycles FAB has the proper `content-desc="Create Cycle"` so the pattern is achievable — the inconsistency is the bug.
**Proposed fix (quick-win, ≤2 hr):** Add `Modifier.semantics { contentDescription = "Routines" }` (etc.) to each `Button`/`Card` wrapper for the home tiles. Add `contentDescription = "Add exercise"` to the Routines FAB. Run `accessibility_audit.py` to verify no `NAF="true"` regressions.
**Parity flag:** NO (portal a11y is separate audit).

---

## HIGH findings

### F-006 [HIGH] Header title rotates between "Workouts" and "Choose Your Workout" on the same Home screen

**Surface:** Mobile
**Category:** 2 (information architecture)
**Location:** `HomeScreen.kt` top-bar title binding.
**Observation:** `01-splash-or-first.png` and `04-home.png` show title `Workouts`. `60-light-theme.png` (after a theme toggle, same destination) shows `Choose Your Workout`. The bottom-tab still reads `Workouts`. Both titles exist in the codebase and are emitted under different conditions.
**Why it hurts:** Users navigating with the bottom-tab `Workouts` see the screen title change to "Choose Your Workout" — they will wonder if they navigated somewhere else. Especially confusing under voice control ("go to Workouts" → screen says different name).
**Severity rationale:** HIGH because navigation taxonomy must be stable; the bottom-tab label and the screen title should be identical.
**Proposed fix (quick-win, ≤30 min):** Pick one: `Workouts` (matches bottom-tab) or `Choose Your Workout` (more descriptive — but then rename tab). Remove the conditional. Keep `Project Phoenix` eyebrow consistent.
**Parity flag:** YES — portal sidebar uses `Workouts`; mobile should match.

---

### F-007 [HIGH] Workout-mode segmented control truncates "Constant resistance throughout the…" description

**Surface:** Mobile
**Category:** 4 (workout flow ergonomics)
**Location:** `JustLiftScreen.kt` mode segmented control + helper text.
**Observation:** Screenshots `05-just-lift.png`, `07b-mode-echo.png`, `07c-mode-pump.png`. The helper line below the segmented control reads `Constant resistance throughout the…` and is hard-clipped — there is no overflow, no expand, no scroll. Each mode has its own description but only the head of the line is ever visible.
**Why it hurts:** First-time users have no way to understand what the modes mean. The whole point of having six modes (Old/Pump/Echo/TUT/TUTBeast/EccentricOnly) is meaningful choice, and the explainer text is unreachable.
**Severity rationale:** HIGH (not CRITICAL) because the user can still pick a mode and lift, but mode selection is a guess.
**Proposed fix (quick-win, ≤2 hr):** Replace the truncated single-line subtitle with a 2–3 line `Text` (overflow=Visible) and an info icon that opens a per-mode bottom sheet with full description + animation/diagram.
**Parity flag:** YES — portal CycleBuilder has full mode descriptions; mobile is the gap.

---

### F-008 [HIGH] Analytics filter chip "All time" is clipped to "All tim"

**Surface:** Mobile
**Category:** 1 (visual / 6 data-dense)
**Location:** `AnalyticsScreen.kt` time-range chip row.
**Observation:** Screenshot `12-analytics.png`, `15-analytics-progress.png`. The right-most chip in the time-range selector is rendered as `All tim` — last `e` is clipped at the chip's right edge.
**Why it hurts:** Looks like a compile-time string truncation. Lowers trust in analytics numbers ("if they can't get a label right, can I trust the numbers?"). Affects every Analytics view.
**Severity rationale:** HIGH because Analytics is a paid-tier feature (EMBER+) and a label clip on a paid surface is a credibility hit.
**Proposed fix (quick-win, ≤30 min):** Fix chip min-width or content-padding. Likely the chip horizontalPadding is too aggressive for the longest label `All time`. Add an instrumented test that snapshots all chip texts at scale 1.0.
**Parity flag:** YES — portal labels render fully; mobile is the gap.

---

### F-009 [HIGH] Header is duplicated on Insights screen ("Smart Insights" title appears twice)

**Surface:** Mobile
**Category:** 2 (IA) / 1 (visual)
**Location:** `SmartInsightsTab.kt` — both the top-app-bar title and the Hero/Header card title read `Smart Insights`.
**Observation:** Screenshot `18-insights.png`. The top-app-bar shows `Insights / Project Phoenix`. Immediately below, an H1 `Smart Insights / Personalized training recommendations` repeats it. `61-light-theme-insights.png` shows the same.
**Why it hurts:** Wastes ~120 dp of vertical space — the user sees roughly half a card less above-the-fold than they should. Reads as "the team didn't decide which header is canonical."
**Severity rationale:** HIGH because it's persistent and reduces information density on a data-dense surface.
**Proposed fix (quick-win, ≤1 hr):** Either remove the top-app-bar title (let the Hero be the H1) or remove the Hero (let the app-bar be canonical and bring `Personalized training recommendations` down as a subtitle on the app-bar). Recommendation: keep app-bar canonical, drop Hero.
**Parity flag:** YES — portal Insights uses one heading.

---

### F-010 [HIGH] Light theme has acceptable contrast on text but cards use low-contrast pastel against white background

**Surface:** Mobile
**Category:** 1 (visual) / 7 (a11y — contrast)
**Location:** Light theme renderer in `Color.kt` / `Theme.kt`.
**Observation:** Screenshots `60-light-theme.png` (Home) and `61-light-theme-insights.png` (Insights). Cards on Insights are a faint lavender-pink against the off-white app background. Eyeball estimate <2:1 contrast for the card boundary — text inside is fine, but the *card structure* is hard to perceive. Home tiles (`Cycles`, `Routines`) use a dusty purple that nearly disappears on tilt.
**Why it hurts:** Card hierarchy is the primary affordance for "this is tappable / this is a discrete unit". Losing card structure makes Insights look like a wall of text. WCAG 2.2 SC 1.4.11 Non-Text Contrast requires 3:1 for UI component boundaries.
**Severity rationale:** HIGH because (a) light theme is opt-in but we ship it, (b) the parent `CLAUDE.md` documents Phoenix palette only for dark — light theme appears to be auto-derived without a designed pass.
**Proposed fix (design-spike, ≥1 day):** Audit light-theme color tokens; raise card-stroke or card-shadow until ≥3:1 against background. Use the existing `AccessibilityColors.kt` if one exists; otherwise add a high-contrast mode toggle in Settings.
**Parity flag:** YES — portal has a defined light theme; mobile light needs equivalent care.

---

### F-011 [HIGH] Header in Routines tab does not show bottom-tab bar separation on dark gradient — back button is the only return

**Surface:** Mobile
**Category:** 2 (navigation) / 1 (visual)
**Location:** `RoutinesTab.kt` (no bottom nav visible until scrolled to top — see `30-routines.png`).
**Observation:** Screenshot `30-routines.png`. Routines tab is shown without the bottom navigation bar (Workouts/Insights/Analytics/Settings). Only the back-arrow returns the user. Other secondary screens (`Single Exercise`, `Cycles`) similarly hide the bottom bar. Users tap the bottom-tab `Workouts` to enter, then see the bar disappear.
**Why it hurts:** Loses persistent navigation. A user who entered Routines via Home tile can't jump to Insights without going back to Home first. Inconsistent — `Insights`, `Settings`, `Analytics` keep the bar.
**Severity rationale:** HIGH because it breaks Material 3 navigation expectation (persistent bottom bar) and creates uneven navigation depth.
**Proposed fix (design-spike, ≥1 day):** Decide if Routines/Cycles/SingleExercise/JustLift are `tabbed` or `routed` destinations, then apply consistently. Recommend routed (full-screen) only for `JustLift` and `ActiveWorkout`; keep nav bar visible everywhere else.
**Parity flag:** NO.

---

### F-012 [HIGH] Routine card has a tappable affordance area that's >50% of the row height but visually only the chevron looks tappable

**Surface:** Mobile
**Category:** 5 (form/UX) / 7 (a11y — touch targets)
**Location:** Routine row composable in `RoutinesTab.kt`.
**Observation:** Screenshot `30-routines.png`. Card "Issue 389 Demo Routine — 4 exercises • 17 min" has a large outlined chevron `v` on the right (suggests "expand"). The whole row is clickable (UIAutomator confirms parent bounds `[42,381][1252,604]`). But the visual weight of the chevron makes users tap *just* the chevron — which works but isn't obvious.
**Why it hurts:** Discoverability — first-time users will tap the title, get expansion, and not know if/how to start the routine. The chevron also looks like "expand" not "start", but tapping starts the routine flow.
**Severity rationale:** HIGH because routine playback is the second most important task (after `JustLift`).
**Proposed fix (quick-win, ≤2 hr):** Add an explicit `Start` pill on the right and move the chevron to a separate `Expand` action above or below. Or split: tap row = expand, tap a `Play` icon = start.
**Parity flag:** YES — portal has explicit Start/Edit/Delete actions on each routine card.

---

### F-013 [HIGH] No empty state on `New Routine` when no exercises selected — just a blank canvas with an `Add Exercise` FAB

**Surface:** Mobile
**Category:** 3 (state coverage — empty)
**Location:** `RoutineEditorScreen.kt` initial state.
**Observation:** Screenshot `36-new-routine.png`. After tapping "+" on Routines, the screen is title `New Routine` with empty body and an orange `Add Exercise` FAB. There is no copy explaining what to do, no placeholder ("Routines link multiple exercises into a session…"), no template suggestions.
**Why it hurts:** First-time users (and any user creating a fresh routine after a year off) hit a nothing-screen and have to guess. The FAB is also `NAF="true"` (see F-005).
**Severity rationale:** HIGH because Routine creation is a paid-tier flow and users abandoning the empty screen never reach the value.
**Proposed fix (quick-win, ≤4 hr):** Add an empty-state component: title `Build your routine`, body `Tap Add Exercise to choose your first movement. We'll keep them in order.`, secondary `Or pick a starter template` linking to a template picker (matching CycleEditor's templates flow).
**Parity flag:** YES — portal RoutineEditor has copy and template suggestions.

---

### F-014 [HIGH] "Open profiles" right-edge tab is a hidden affordance — no visible label or icon affordance

**Surface:** Mobile
**Category:** 2 (IA) / 7 (a11y — discoverability)
**Location:** Right-edge tab visible across screens at `[1248,1064][1320,1192]`.
**Observation:** Multiple screenshots show a small blue rounded tab on the right edge with a thin chevron pointing inward. UIAutomator confirms `content-desc="Open profiles"` and `clickable=true`. There is no visible text or recognized icon — the affordance is the bare blue notch.
**Why it hurts:** Profiles is non-trivial functionality (multi-user) and it's hidden behind a notch that looks like decoration. Even sighted, motor-skilled users won't discover it.
**Severity rationale:** HIGH because (a) discoverability of a feature that exists is low-cost to fix, (b) the affordance reads as a UI bug rather than an interactive element.
**Proposed fix (design-spike, ≥1 day):** Either move profiles into a normal entry point (Settings → Profiles) and remove the notch, or replace the notch with a recognizable avatar/identity chip (initials in a circle).
**Parity flag:** NO (mobile-only multi-profile pattern).

---

## MEDIUM findings

### F-015 [MEDIUM] "Issue 389 Demo Routine" appears in default Routines list — looks like dev fixture leaking into release surface

**Surface:** Mobile
**Category:** 1 (brand) / 9 (first-run)
**Location:** Routines tab seed data — likely `RoutinesTab.kt` or seeding query.
**Observation:** Screenshot `30-routines.png`. First and only routine on a fresh debug install is named `Issue 389 Demo Routine` — clearly a developer test fixture (issue tracker reference).
**Why it hurts:** First-run polish — a non-developer user sees `Issue 389` and assumes the app is broken or in beta.
**Severity rationale:** MEDIUM because it's debug-build-only (`com.devil.phoenixproject.debug`); confirm it doesn't ship in release. If release: HIGH.
**Proposed fix (quick-win, ≤30 min):** Either rename the dev fixture (`Sample Push Day`) or gate it behind `BuildConfig.DEBUG`. Verify the release build has clean default state.
**Parity flag:** NO.

---

### F-016 [MEDIUM] Phoenix orange used in mobile is `#FF9149` (peach) — does not match portal's `#FF6B35` (saturated)

**Surface:** Mobile
**Category:** 1 (brand) / 8 (parity)
**Location:** `Color.kt` — `PhoenixOrangeDark = #FF9149`.
**Observation:** Bottom-nav active tab, "Project Phoenix" eyebrow, FAB, and confirmation buttons all use a peachy orange. Portal uses a saturated red-orange `#FF6B35`. Visible in `01-splash-or-first.png`, `27-badges.png`, `30-routines.png`, `50-mode-confirmation.png`.
**Why it hurts:** Brand inconsistency. A user who used the portal first and then opened the mobile app will perceive them as different products.
**Severity rationale:** MEDIUM (not HIGH) because the colors are in the same family — recognizable as orange — but they're verifiably different swatches.
**Proposed fix (design-spike, ≥1 day):** Decide on the canonical Phoenix Ember (recommend portal's `#FF6B35` since it's the marketing-page color), then ensure mobile dark and light themes both use it. Re-verify legibility on dark gradient (`#020617`) — `#FF6B35` on `#020617` should be ≥4.5:1.
**Parity flag:** YES — Charter calls this out as known.

---

### F-017 [MEDIUM] App background `#020617` (cobalt) doesn't match portal's `#06060a` (near-black)

**Surface:** Mobile
**Category:** 1 (brand) / 8 (parity)
**Location:** `Theme.kt` `Slate950`.
**Observation:** Mobile background has a clear blue cast, especially visible in `30-routines.png` and `40-cycles.png` — the dark gradient reads navy. Portal is near-black (`#06060a`).
**Why it hurts:** Same parity argument as F-016. Side-by-side the apps look like cousins, not the same product.
**Severity rationale:** MEDIUM — same family, recognizable as dark theme, but verifiably distinct.
**Proposed fix (quick-win, ≤2 hr):** Replace `Slate950` with `#06060a` (or define `PhoenixBlack`). Ripple to gradient base. Verify in dark and the new value still gives card surfaces enough lift.
**Parity flag:** YES — Charter calls this out.

---

### F-018 [MEDIUM] Toggle row chevron + label hit areas don't match — text appears tappable but isn't

**Surface:** Mobile
**Category:** 5 (form/UX) / 7 (a11y — touch targets)
**Location:** `JustLiftScreen.kt` settings toggles ("Rep Count Timing", "Stall Detection", "Stop at Top") — `05-just-lift.png`, `50-mode-confirmation.png`.
**Observation:** Each row has a label, a sub-label, and a Switch. The text is positioned to look like the *whole row* toggles — but UIAutomator confirms only the Switch hit area (~160dp) reacts. Tap on label = no-op.
**Why it hurts:** Discoverable affordance lies. Users tap the label, nothing happens, they think the app is frozen, they tap harder. Frustrating on a frequently-used screen.
**Severity rationale:** MEDIUM because the user can still operate the toggle, just less efficiently.
**Proposed fix (quick-win, ≤2 hr):** Wrap each row in `Modifier.toggleable(checked, onCheckedChange)` so the entire row is the hit target. This is the Material 3 spec.
**Parity flag:** NO.

---

### F-019 [MEDIUM] Configure Exercise modal lists toggles in non-priority order; the "Start Workout" CTA is below-the-fold

**Surface:** Mobile
**Category:** 4 (workout flow) / 5 (form UX)
**Location:** `WorkoutSetupDialog.kt` / `ExerciseEditBottomSheet.kt`.
**Observation:** Screenshot `50-mode-confirmation.png`, `51-config-scroll.png`, `52-config-scroll2.png`. The modal opens with image, "Set Mode" segment (Reps/Duration), then a stack of toggles. `Start Workout` button is only visible after the user taps the modal once — at default open it's below the fold. The "Stop at Top" toggle is below "Rep Count Timing" which is below "Stall Detection" — none of which are the most-likely-changed setting.
**Why it hurts:** Adds friction to the most common path: open exercise → start workout. Users will scroll, hunt for Start, and lose confidence in the flow.
**Severity rationale:** MEDIUM because Start *is* reachable, just hidden.
**Proposed fix (quick-win, ≤2 hr):** Pin `Start Workout` to a sticky footer always-visible. Reorder toggles by frequency-of-change (Stall Detection often, Stop at Top rarely). Move per-set rest time into a chip row at the top.
**Parity flag:** NO.

---

### F-020 [MEDIUM] Exercise picker has alphabetical index strip on the right but it's only ~20dp wide — hard to hit a precise letter

**Surface:** Mobile
**Category:** 7 (a11y — touch targets) / 6 (data-dense)
**Location:** `ExercisesTab.kt` / `SingleExerciseScreen.kt`.
**Observation:** Screenshot `49-exercise-detail.png`. The right-edge index ribbon (`1 A B C D ... W`) is ~24dp wide. WCAG 2.2 SC 2.5.8 minimum target size is 24x24 CSS pixels — borderline. Each letter is ~30dp tall by ~20dp wide.
**Why it hurts:** Mistaps. Users with thumb-typing on a Pixel 8 Pro will land on the wrong letter.
**Severity rationale:** MEDIUM (close to WCAG min, not over the line).
**Proposed fix (design-spike, ≥1 day):** Either widen the strip to 32dp, or convert to a swipe gesture with a large floating "current letter" preview while finger is down (iOS-style ContactsApp). Latter gives motor-impaired users tolerance.
**Parity flag:** NO.

---

### F-021 [MEDIUM] "Workout Mode" segmented control has only 3 visible modes (Old/Pump/Echo) and no horizontal-scroll affordance

**Surface:** Mobile
**Category:** 4 (workout flow) / 2 (IA)
**Location:** `JustLiftScreen.kt` mode segmented control.
**Observation:** Screenshots `05-just-lift.png` show only Old/Pump/Echo. The other 3 modes (TUT, TUTBeast, EccentricOnly) are accessible via swipe (`07e-mode-swipe.png` confirms swipe works) but there's no visual cue (no fade, no `…`, no "+3 modes" indicator).
**Why it hurts:** Users believe the app supports 3 modes. Half the workout-mode capability is invisible.
**Severity rationale:** MEDIUM because the modes *are* reachable; the discoverability is the issue.
**Proposed fix (quick-win, ≤2 hr):** Add edge-fade gradient on the right indicating more content. Or add a pager-dot row below. Or convert to a `LazyRow` with snap behavior and a "+ N modes" pill on the right.
**Parity flag:** YES — portal exposes all 6 modes plainly in CycleBuilder.

---

### F-022 [MEDIUM] Achievements/Badges tab uses dimmed gold ribbons with 0% — looks broken at zero state

**Surface:** Mobile
**Category:** 3 (empty state) / 6 (data-dense)
**Location:** `BadgesScreen.kt`.
**Observation:** Screenshot `27-badges.png`, `28-badges-scroll.png`. Each badge shows a circle with the Phoenix flame icon at low opacity, label, and `0%`. No badges are earned (fresh install). Visually it looks like a network-loading shimmer that never resolved.
**Why it hurts:** Zero state confuses with loading state. User might wait for "actual badges" to load.
**Severity rationale:** MEDIUM because the data is correct, just the visual treatment is ambiguous.
**Proposed fix (quick-win, ≤2 hr):** For 0% badges, render them with a clear lock icon + "Locked" subtitle. For partial-progress badges (1-99%), use the current ring. Differentiate locked-vs-loading explicitly.
**Parity flag:** YES — portal Badges shows a lock icon for unearned.

---

### F-023 [MEDIUM] Settings → Cloud Sync block has two large primary-orange-bordered buttons stacked vertically, looks overweighted vs other Settings rows

**Surface:** Mobile
**Category:** 1 (visual) / 2 (IA)
**Location:** `SettingsTab.kt`.
**Observation:** Screenshot `21-settings.png`. The Cloud Sync card has `Link Portal Account` and `Integrations` as full-width orange-outlined CTAs, while "Like My Work?" above and "Weight Unit" below are subtler list rows. The visual weight makes Cloud Sync look like the center of gravity.
**Why it hurts:** Misleads attention. A user opening Settings expects a list; this looks like a marketing CTA card.
**Severity rationale:** MEDIUM because it's stylistic, not functional.
**Proposed fix (quick-win, ≤1 hr):** Convert the two CTAs to standard Settings rows with chevron-right (matching the rest of Settings). Keep the donate card distinct.
**Parity flag:** NO.

---

### F-024 [MEDIUM] No retry CTA on failed BLE connect — "Connecting" pill stays stale indefinitely

**Surface:** Mobile
**Category:** 3 (state coverage — error)
**Location:** Connection state machine — likely `BleManager`/header pill binding.
**Observation:** `08-connect-tapped.png` → `09-ble-connection-fail.png` → `10-ble-after-15s.png`: tapping `Connect`, then no machine present, the header reads `Connecting` and stays there. After 15s, still `Connecting`. After full minute, still `Connecting`. No timeout, no error toast, no retry button.
**Why it hurts:** Users tap, wait, see no progress, no error. They guess. They re-tap, re-tap. Battery drains. Eventually they kill the app. This is the most common BLE scenario for a community-rescue user (machine off, machine elsewhere, etc.) and it's silent.
**Severity rationale:** MEDIUM (would be HIGH if it didn't recover after backgrounding — confirmed it does eventually flip back to `Connect` after force-stop). The constant `Constants.kt:39` (`BLE_SCAN_TIMEOUT_MS = 30000L`) and `BleConstants.kt:134` (`SCAN_TIMEOUT_MS = 30000L`) define a **30-second** scan timeout — but the visible state doesn't reflect *any* timeout. (Audit-time correction: an earlier draft of this finding said "10s"; the actual canonical value is 30s, verified post-hoc against source.)
**Proposed fix (quick-win, ≤4 hr):** After 30s of no advertise (or sooner — the team should decide whether 30s is the right user-perceived ceiling), transition pill to `No machine found · Retry` with a tappable retry. Add `Connection Logs` link as a "see why" for power users (the screen exists per `ConnectionLogsScreen.kt`).
**Parity flag:** NO (BLE is mobile-only).

---

## POLISH findings

### F-025 [POLISH] Heart icon on "Like My Work?" Settings card uses a yellow-on-yellow stroke that looks like a sticker

**Surface:** Mobile
**Category:** 1 (visual)
**Location:** `SettingsTab.kt` donation card.
**Observation:** `21-settings.png`. The yellow heart in a yellow circle has a distracting low-contrast feel.
**Why it hurts:** Minor — but the donate card is the eyebrow of Settings.
**Proposed fix:** Use a higher-contrast heart fill or a gold-on-dark variant.
**Parity flag:** NO.

---

### F-026 [POLISH] Bottom-nav has a "lozenge" highlight pill behind the active icon that doesn't match Material 3 expressive standard

**Surface:** Mobile
**Category:** 1 (brand)
**Location:** Bottom nav.
**Observation:** `01-splash-or-first.png` and many others. Active tab has a peach lozenge under the icon — it's nicely visible but the lozenge bleeds outside the conventional 64dp tab footprint. Looks slightly hand-rolled.
**Why it hurts:** Polish — Material 3 navigation pattern is well-known; deviating costs recognizability.
**Proposed fix:** Constrain pill width to icon-bounds or switch to standard M3 navigation indicator.
**Parity flag:** NO.

---

### F-027 [POLISH] "Workout Mode" outline ring around active mode is hard to see (mustard on khaki)

**Surface:** Mobile
**Category:** 1 (visual)
**Location:** `JustLiftScreen.kt` mode segment.
**Observation:** `05-just-lift.png`, `07d-mode-old.png`. Active mode `Old` has a yellow outline on a similar mustard fill — selection state is barely visible.
**Why it hurts:** Mode is the most important per-workout setting; selection feedback should be unambiguous.
**Proposed fix:** Use Phoenix Ember (`#FF6B35`) outline + bold weight on active label.
**Parity flag:** NO.

---

### F-028 [POLISH] Animation duration scale = 0 (reduced motion) doesn't suppress the rotating "Workouts"/"Choose Your Workout" title swap

**Surface:** Mobile
**Category:** 7 (a11y — motion preferences)
**Location:** Title binding.
**Observation:** `59-reduced-motion.png` confirms the title still swaps (related to F-006 root cause). With prefers-reduced-motion, surfaces that *change content* (not just animate) should ideally show one canonical label.
**Why it hurts:** Minor — vestibular users get content jitter where they expect static.
**Proposed fix:** Tied to F-006 fix.
**Parity flag:** NO.

---

### F-029 [POLISH] Days-of-week calendar strip on Home dot indicators are barely visible

**Surface:** Mobile
**Category:** 1 (visual) / 6 (data-dense)
**Location:** `HomeScreen.kt` weekly dots.
**Observation:** `01-splash-or-first.png`. Friday `F` has an orange dot indicating today, but the other six dots are nearly invisible at default brightness.
**Why it hurts:** Calendar is meant to give weekly-rhythm feedback; if dots are invisible, the cell is decoration.
**Proposed fix:** Increase dot opacity/diameter for non-today days. Use filled dot for completed workouts.
**Parity flag:** YES — portal ConsistencyCalendar uses bolder dots.

---

### F-030 [POLISH] "Just Lift" home tile uses a fire-particle background that makes the label hard to read

**Surface:** Mobile
**Category:** 1 (visual) / 7 (a11y — contrast)
**Location:** `HomeScreen.kt` Just Lift tile.
**Observation:** `01-splash-or-first.png`, `60-light-theme.png`. The `Just Lift` tile has a fire-particle effect that overlaps the white label "Just Lift". Contrast varies as particles animate; in some frames the L's are nearly lost.
**Why it hurts:** Reduces readability of the most common tile.
**Proposed fix:** Add a subtle gradient scrim behind the text (Material `Image` overlay alpha ~0.4).
**Parity flag:** NO.

---

## Notes on un-tested surfaces

- **TalkBack live test:** Not executed — F-005 is sourced from UIAutomator's `NAF` flag which is the same signal TalkBack uses. Recommend a follow-up TalkBack pass after F-005 is fixed.
- **Active workout (BLE-connected) flows** (`ActiveWorkoutScreen`, `SetReadyScreen`, `SetSummaryCard`, `RestTimerCard`, `RoutineCompleteScreen`): not testable on emulator without real Vitruvian hardware. These need on-device live testing.
- **Auth/Onboarding (`AuthScreen`, `EulaScreen`, `LinkAccountScreen`, `AssessmentWizardScreen`)**: only first-run AssessmentWizard (`02-strength-assessment.png`) and 1RM input (`03-1rm-input.png`) were captured; full auth flow needs a fresh install.
- **iOS divergence:** No Mac available; iOS findings excluded per Charter.

---

## Evidence index

All screenshots in `_audit/screenshots/mobile/` (64 PNGs, ~3-7 MB each):

| Range | Surface |
|-------|---------|
| 01-04 | Splash, Home, Strength Assessment, 1RM input |
| 05-08 | Just Lift (default, scrolled, side drawer, Connect tapped) |
| 07b-07g | Workout-mode strip variants (Echo, Pump, Old, swipe, longpress, tap) |
| 09-11 | BLE connection failure states |
| 12-17 | Analytics (default, scroll, bottom, progress, scroll, history) |
| 18-20 | Insights (default, scroll x2) |
| 21-26 | Settings (default + 5 scroll positions) |
| 27-29 | Achievements/Badges + detail |
| 30-38 | Routines, RoutineEditor, exercise picker, kebab, new routine |
| 39-44 | Home/Cycles/CycleEditor/templates |
| 45-53 | Single exercise picker → exercise detail → mode confirmation → config scrolls → back |
| 54-58 | font_scale 1.30 + 1.5 (home/insights/justlift) |
| 59 | Reduced motion (animator_duration_scale 0) |
| 60-61 | Light theme (home + insights) |
| 62 | Landscape (insights) |
| 63-64 | Airplane-mode insights |

---

## Reset trail (post-audit)

- `font_scale` → 1.0 ✓
- `animator_duration_scale` → 1 ✓
- `accelerometer_rotation` → 1 ✓
- `user_rotation` → 0 ✓
- `cmd uimode night` → yes (dark) ✓
- Network → restored (`svc data enable && svc wifi enable`) ✓

End of file.
