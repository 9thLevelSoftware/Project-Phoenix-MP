# Accessibility & Cross-Platform Parity Audit — Findings

**Auditor:** ArchitectUX (read-only)
**Date:** 2026-05-01
**Scope:** Charter categories 7 (Accessibility WCAG 2.2 AA) and 8 (Cross-Platform Parity)
**Surfaces:** `phoenix-portal/` (React 19 + Tailwind v4) and `Project-Phoenix-MP/` (Compose Multiplatform)

---

## Executive summary

Both surfaces clear most WCAG 2.2 AA contrast bars on dark mode, but the portal has **3 contrast failures** (disabled text, sidebar group label, GRIND-zone red as text) and **structural a11y gaps** in custom dialogs (`BottomSheet.tsx` is a non-modal `motion.div` with no focus trap, no ESC-to-close, and no `role="dialog"`). Mobile passes every contrast pair tested but **does not honor system reduced-motion** — every spring/scale/infinite-rotate animation runs unconditionally, which is a WCAG 2.3.3 (AAA) goal but a documented user-impact problem with no opt-out.

Cross-platform parity has **one CRITICAL gap and several HIGH ones**. The portal ships **three contradictory velocity-zone color systems** (`lib/colors.ts:VELOCITY_ZONES`, `lib/vbt.ts:SIMPLIFIED_ZONES`, `AsymmetryGauge` ad-hoc) and *none* of them match the mobile canonical mapping in `AccessibilityColors.kt:StandardPalette`. Mobile says "kg per cable" + "Total weight for 2 cables: X kg" on the WeightStepper; the portal silently doubles the value and labels it "kg" with no convention disclosure. The asymmetry "BALANCED" threshold has *three* values in code (mobile BalanceBar 10%, portal `ASYMMETRY_THRESHOLD = 10`, portal AsymmetryGauge `<= 2`). IA differs sharply: mobile is a 4-tab bottom nav (Analytics / Workouts / Insights / Settings) while the portal is a 3-group sidebar (Training / Social / Account, 11 destinations). Tier badges, mode labels, and PR phases largely match.

**Counts:** 6 contrast/a11y failures (3 portal contrast + 1 portal dialog + 1 mobile motion + 1 mobile font-size), 12 parity gaps (1 CRITICAL, 5 HIGH, 4 MEDIUM, 2 POLISH). Top 3 a11y issues: portal disabled-foreground text, portal `BottomSheet` not a modal, mobile no reduced-motion. Top 3 parity issues: velocity-zone colors fully diverge, "per-cable" convention invisible on portal, asymmetry threshold contradictions.

---

## A. WCAG 2.2 AA Contrast Audit

Computed via standard formula `(L1 + 0.05) / (L2 + 0.05)` where `L = 0.2126·R + 0.7152·G + 0.0722·B` after sRGB linearization.

### Portal pairs (dark theme, `theme.css`)

| # | Pair | Ratio | AA Normal (≥4.5) | AA Large (≥3) | Recommendation |
|---|------|------:|:------------------:|:----------------:|----------------|
| 1 | `--foreground #e0e0e8` on `--background #06060a` | **15.41:1** | PASS | PASS | None |
| 2 | `--muted-foreground #a0a0ac` on `--background #06060a` | **7.82:1** | PASS | PASS | None |
| 3 | `--muted-foreground #a0a0ac` on `--card #0a0a10` | **7.63:1** | PASS | PASS | None |
| 4 | `--primary-foreground #06060a` on `--primary #FF6B35` | **7.13:1** | PASS | PASS | None |
| 5 | `--accent-foreground #06060a` on `--accent #F59E0B` | **9.42:1** | PASS | PASS | None |
| 6 | `--destructive-foreground #FFF` on `--destructive #ff5252` | **3.19:1** | **FAIL** | PASS | Darken destructive bg to `#dc2626` (≈4.83:1) OR change destructive-foreground to `#06060a` (4.5:1+ tested at body size). |
| 7 | `--success-foreground #06060a` on `--success #00e676` | **12.12:1** | PASS | PASS | None |
| 8 | Sidebar active `#FF6B35` on `rgba(255,107,53,0.12)` over `#0a0a10` | **6.12:1** | PASS | PASS | None |
| 9 | `--disabled-foreground #5a5a66` on `--background #06060a` | **2.98:1** | **FAIL** | **FAIL** | If used for inert UI text (not body), this is fine for 1.4.3 *only* on disabled controls (which 1.4.3 exempts). If used in placeholders or hints, raise to ≥`#7a7a86` (≈4.55:1). |
| 10 | `--sidebar-group-label #4a4a56` on `--sidebar (~#06060a)` | **2.32:1** | **FAIL** | **FAIL** | Group labels are decorative section headers but `<SidebarGroupLabel>` renders real text. Lift to ≥`#7a7a86` (≈4.55:1). |

### Portal velocity-zone colored text on `--background #06060a`

Only relevant if zone label is rendered as same-color text (which `ZoneBadge.tsx` does — `color: zone.color`).

| Zone (Simplified) | Color | Ratio | AA Normal | AA Large | Recommendation |
|---|---|---:|:---:|:---:|---|
| GRIND | `#DC2626` | **4.19:1** | **FAIL** | PASS | Lift to `#EF4444` (5.36:1) OR render label text in `--foreground` and reserve color for the dot. |
| SLOW | `#F59E0B` | 9.42:1 | PASS | PASS | None |
| MODERATE | `#FF6B35` | 7.13:1 | PASS | PASS | None |
| FAST | `#10B981` | 7.97:1 | PASS | PASS | None |
| EXPLOSIVE | `#3B82F6` | 5.50:1 | PASS | PASS | None |

### Mobile pairs (dark mode, `Color.kt`)

| # | Pair | Ratio | AA Normal | AA Large | Recommendation |
|---|------|------:|:---:|:---:|---|
| 1 | OnSurfaceDark Slate200 `#E2E8F0` on SurfaceDimDark Slate950 `#020617` | **16.36:1** | PASS | PASS | None |
| 2 | OnSurfaceVariantDark Slate400 `#94A3B8` on Slate900 `#0F172A` | **6.96:1** | PASS | PASS | None |
| 3 | OnPrimaryContainerDark `#FFDBCF` on PrimaryContainerDark `#702300` | **8.44:1** | PASS | PASS | None |
| 4 | PhoenixOrangeDark `#FF9149` on Slate950 `#020617` | **9.03:1** | PASS | PASS | None |
| 5 | EmberYellowDark `#E2C446` on Slate900 `#0F172A` | **10.39:1** | PASS | PASS | None |
| 6 | AshBlueDark `#6ED2FF` on Slate900 `#0F172A` | **10.48:1** | PASS | PASS | None |
| 7 | SignalSuccess `#22C55E` on Slate950 `#020617` | **8.85:1** | PASS | PASS | None |
| 8 | SignalWarning `#F59E0B` on Slate950 `#020617` | **9.39:1** | PASS | PASS | None |
| 9 | SignalError `#EF4444` on Slate950 `#020617` | **5.36:1** | PASS | PASS | None |
| 10 | Slate400 `#94A3B8` on Slate800 `#1E293B` | **5.71:1** | PASS | PASS | None |

**Mobile is clean on all sampled tokens.** All 5 velocity-zone colors clear AA Normal on Slate950.

---

## B. Touch targets & motor accessibility

### Portal
- `theme.css:548-565` enforces `min-height/width: 44px` on coarse pointers for `button, [role=button], a[href], select, [type=checkbox], [type=radio], [data-slot=button]` plus icon-only `:has(> svg:only-child)`. **Confirmed: not overridden** by any component-level `h-* w-*` audit (spot-checked Dashboard, Analytics, BottomSheet, AppSidebar). 
- The sidebar trigger `<SidebarTrigger>` is decorated as ghost link inside header — verify physical hit area on coarse pointer in live audit; CSS rule covers it via `[data-slot=button]`.
- `BottomSheet.tsx:122-128` close button is `<Button size="sm" variant="ghost">` — coarse pointer rule lifts it to 44×44, fine pointer leaves it visually small (~32px). Acceptable but borderline.

### Mobile
- `WeightStepper.kt:95, 132` plus/minus buttons explicitly `Modifier.size(44.dp)`. Compose default min-target is 48dp; both clear AA's 44pt minimum.
- `RpeSlider.kt:275` `RpeQuickSelect` cells are `Modifier.size(44.dp)` — passes.
- `EnhancedMainScreen.kt:333-369` icon-only `IconButton` for sync/connection/back — Compose `IconButton` default is 48×48. Passes.
- `CompactNumberPicker.kt` is a native platform picker (Android `NumberPicker`, iOS `UIPickerView`) — both meet platform a11y by default.
- **Potential gap:** `BalanceBar.kt:51-188` is a passive Canvas with no clickable region — fine, but the L/R labels at `fontSize = 8.sp` (line 154, 163) and percentage at `9.sp` (line 175) are below WCAG-recommended 12sp minimum for body text and won't scale meaningfully (they're already too small for the 200% scale recommendation).

---

## C. Screen reader / semantics gaps

### Portal — passing patterns
- `AsymmetryGauge.tsx:118-122, 380-407` ships `role="img" aria-label="…"` on every chart variant and an `<table className="sr-only">` data table mirror — exemplary.
- `ConsistencyCalendar.tsx:177` calendar gets `aria-label="Workout consistency calendar heatmap"`.
- `AppSidebar.tsx:243` `NavLink` has `aria-label={item.label}` (icon-only collapsed-state coverage).
- `SkipToContent.tsx` provides `<a href="#main-content">` skip link; `AppLayout.tsx:75-81` wraps `<motion.main id="main-content">` to anchor it. Solid.
- All Radix dialogs (`AlertDialog` in `DeleteConfirmDialog.tsx`, the dropdown in `AppSidebar`) supply built-in focus trap, ESC, and focus restoration.

### Portal — gaps
- **`BottomSheet.tsx:1-148` is the major gap.** It's a custom `motion.div` with `role` unset, no `aria-modal`, no focus trap, no return-focus on close, no ESC handler. Body scroll is locked manually but keyboard users can tab through the page underneath.
- `BottomSheet.tsx:107-110` drag handle has `cursor-grab` but no `role="slider"`, no keyboard equivalent for snap-point control. Drag-only interaction is inaccessible.
- `Dashboard.tsx:395` icon-only welcome flame inside an unlabeled circle — flame icon decorative, but the welcome heading carries the meaning, so this is OK.

### Mobile — passing patterns
- `EmptyStateComponent.kt:51` provides `contentDescription = stringResource(Res.string.cd_empty_state)` on the centered icon. Good.
- `WeightStepper.kt:99, 136` icon buttons supply `cd_decrease_weight` / `cd_increase_weight` from string resources.
- `EnhancedMainScreen.kt:319, 387, 414, 469` back/analytics/workouts/settings have `stringResource(...)` content descriptions.
- `BadgesScreen.kt:362, 365, 379` filter chip icons are `contentDescription = null` because the chip label provides the text — correct ARIA.

### Mobile — gaps
- `RpeSlider.kt:75-78, 152-154, 195-197, 285-288` emoji is rendered with no `contentDescription` and the surrounding `Surface { Text }` row is a clickable Surface that announces its label and value but the emoji adds visual mood that screen readers cannot perceive. Acceptable since the label/RiR text are present.
- `BadgesScreen.kt:215, 256, 287` decorative icons inside `StreakWidget` use `contentDescription = null` — correct, but the surrounding text relies on visual layout to bind ("3", "Day Streak"). A screen reader will read "3 Day Streak Workouts 12 Badges 18" without grouping. Wrap each stat column in `Modifier.semantics(mergeDescendants = true) { contentDescription = "…"  }` to compose a meaningful sentence.
- `EnhancedMainScreen.kt:281-290` "Project Phoenix" subtitle is decorative orange-to-red gradient text with no `Modifier.semantics { invisibleToUser() }` or equivalent — it'll be announced on every screen change, redundantly.
- `BalanceBar.kt:151-168` `"L"` and `"R"` Text labels at `fontSize = 8.sp` are not announced as part of any group; the percentage value at `9.sp` is the only screen-reader-accessible piece of asymmetry info on this passive widget. Add `Modifier.semantics { contentDescription = "Cable balance: $asymmetryPercent% toward $dominantSide side" }` on the parent Row.
- `AnimatedRepCounter.kt`, `IconAnimation.kt` (referenced from HomeScreen) — animated marquees/counters need `liveRegion = LiveRegionMode.Polite` for AT to know they're updating; not seen in spot-checks.

---

## D. Motion & dynamic type

### Portal
- `theme.css:524-545` global `@media (prefers-reduced-motion: reduce)` collapses `animation-duration` and `transition-duration` to `0.01ms` and zeroes out `animate-pulse/bounce/ping/signal-pulse`.
- `AppLayout.tsx:51` `<MotionConfig reducedMotion="user">` short-circuits Framer Motion when the user prefers reduced motion. Two-layer coverage — strong.
- Body type uses `var(--font-size: 16px)` base + `clamp()` for h1-h3 (28.4-37.9px fluid). Static body, label, button, input at `1rem` — OK for browser zoom up to 200%.
- **Gaps**:
  - `Dashboard.tsx`-style usage of fixed `text-xs` (12px) for muted hints is below the 16px base; if the user's browser font is shrunk, those drop quickly. Consider `text-sm` minimum for any user-facing copy.
  - `BalanceBar`-equivalent UIs (none on portal) and chart axis tick labels at `fontSize: 10` (visx defaults in `AsymmetryGauge.tsx:230, 246`) are rasterized SVG and won't scale with browser zoom; OK because screen readers get the `<table className="sr-only">`.

### Mobile
- **No reduced-motion support anywhere in `commonMain` (zero matches for `prefers-reduced-motion`, `reducedMotion`, etc.)**. The following always-on animations affect users with vestibular sensitivities:
  - `BadgesScreen.kt:170-174` streak widget `scale 0.95 ↔ 1.0` spring loop.
  - `BadgesScreen.kt:396-401` badge card scale spring.
  - `ExpressiveComponents.kt:42-49` press-scale `0.95` low-bouncy spring (applied to all `ExpressiveCard`).
  - `EnhancedMainScreen.kt:6-11` imports infinite-repeat `animateFloat` with linear easing — likely a ConnectionStatusIndicator pulse. Always on.
  - `BalanceBar.kt:67-76` infinite-repeat alert pulse runs even when `showAlert = false` (the transition is created unconditionally per the inline comment, animated value just unused — wasted CPU but no AT impact).
  - `PRCelebrationAnimation.kt`, `LottieAnimation.kt` — Lottie playback has no reduced-motion gate.
  - Compose has no `LocalAccessibilityManager` toggle for reduced motion in `commonMain`. The fix is platform-specific:
    - Android: read `Settings.Global.TRANSITION_ANIMATION_SCALE` / `ANIMATOR_DURATION_SCALE` and skip in `actual fun`.
    - iOS: `UIAccessibility.isReduceMotionEnabled` bridged through expect/actual.
- **Dynamic type / font scaling**:
  - `Type.kt` uses `.sp` units throughout, which DO scale with system font size. Body sizes (16-18sp) are appropriate.
  - Custom overrides at `RpeSlider.kt:153` (`32.sp`), `:196` (`28.sp / 22.sp`), `:286` (`16.sp`), `:291` (`10.sp`), and `BalanceBar.kt:154, 163` (`8.sp`), `:175` (`9.sp`) bypass the typography token system. The 8/9/10 sp sizes are below the WCAG-recommended 12sp floor and break Material 3's "minimum body text" guidance.
  - `EnhancedMainScreen.kt:281` "Project Phoenix" eyebrow uses `MaterialTheme.typography.labelSmall.copy(...)` which is 11sp — borderline.

---

## E. Cross-Platform Parity Matrix

| # | Concept | Portal treatment | Mobile treatment | Match? | Severity | Fix |
|---|---|---|---|---|---|---|
| 1 | Workout mode labels | `transforms.ts:12-20` `workoutModeMap` outputs `Old School / Echo / Pump / TUT / TUT Beast / Eccentric Only`. Maps `CLASSIC` → `Old School` (legacy). | `Models.kt:160-165` `ProgramMode` displayNames identical strings; `toSyncString()` lines up. | YES (labels) | n/a | None |
| 2 | Workout mode order | Portal does not impose an order in the map; per-component decisions vary. | `ModeConfirmationScreen.kt`, `ModeSelector.kt` enumerate `ProgramMode.values()` (declaration order: OldSchool, Pump, TUT, TUTBeast, EccentricOnly, Echo). | PARTIAL — Echo is last on mobile, but several portal tests/components order alphabetically or by display logic. | MEDIUM | Define a canonical sort order (suggest: Old School → Pump → TUT → TUT Beast → Eccentric Only → Echo) and apply in both code paths. |
| 3 | Workout mode iconography | Portal has no per-mode icon — text labels and tier-tinted badges only. | Mobile uses Material symbols ad-hoc per screen (`HomeScreen.kt:24-28` imports `FitnessCenter`, `LocalFireDepartment`, `Loop`, `SelfImprovement`); no canonical mode→icon map. | NO | MEDIUM | Establish a canonical icon mapping in shared docs and emit identical icons on both surfaces. |
| 4 | Weight display convention | `transforms.ts:6-9, 93` silently multiplies per-cable weight ×2; output rendered as `123 kg` with no convention disclosure (`lib/units.ts:26`). | `WeightStepper.kt:118-119` shows `123` then `"kg per cable"`; `WeightStepper.kt:142-172` also surfaces `"Total weight for 2 cables: 246 kg"`. | NO | **HIGH** | Add a tooltip/affordance on portal weight labels: "Total weight (both cables) — your machine shows 123 kg per cable." Keep the ×2 for portal display, but disclose. |
| 5 | Asymmetry BALANCED threshold | Two values in code: `lib/biomechanics.ts:2 ASYMMETRY_THRESHOLD = 10` (used by `AsymmetryGauge.tsx:90, 147` for the `±10%` threshold-line band) AND inline `<= 2` in `AsymmetryGauge.tsx:44, 52, 170, 294` for "Balanced" classification. | `BalanceBar.kt:55-59` uses 10/15% ascending severity (`<10` good, `<15` caution, else bad). `BiomechanicsEngine.kt:113, 516` uses `0.02` (2%). | NO — *three* simultaneous truths | **HIGH** | Per `CLAUDE.md`, the canonical BALANCED threshold is **2%**. Export a shared constant on the portal (`ASYMMETRY_BALANCED_THRESHOLD = 2`), use it in `AsymmetryGauge` and any future BalanceBar-equivalent. Mobile `BalanceBar` should classify `<2% = balanced`, `2-10% = caution`, `>10% = imbalanced` to match VBT literature. |
| 6 | Asymmetry indicator visual | Portal: bar chart per-rep with green/red bars and a 50/50 split summary card. `role="img"`, table mirror. | Mobile `BalanceBar.kt`: Canvas with center tick + lateral indicator extending toward dominant side. No semantic SR text. | NO | MEDIUM | Mobile BalanceBar should add `Modifier.semantics { contentDescription = … }` so AT users get parity with portal's `role="img"`. |
| 7 | Velocity zone colors | THREE conflicting palettes: `colors.ts:VELOCITY_ZONES` {explosive=red, fast=amber, moderate=green, slow=blue, grind=purple}; `vbt.ts:SIMPLIFIED_ZONES` {GRIND=`#DC2626`, SLOW=`#F59E0B`, MODERATE=`#FF6B35`, FAST=`#10B981`, EXPLOSIVE=`#3B82F6`}; `MANN_ZONES` overlaps with neither cleanly. | `AccessibilityColors.kt:65-69`: EXPLOSIVE=cyan `#06B6D4`, FAST=green `#22C55E`, MODERATE=amber `#F59E0B`, SLOW=orange `#F97316`, GRIND=red `#EF4444`. | NO | **CRITICAL** | The portal `SIMPLIFIED_ZONES` doc-string says "matching mobile app classification" but the colors are wrong. Pick mobile's `StandardPalette` as truth, codify as a single shared spec, retire portal's `colors.ts:VELOCITY_ZONES` (deprecated/unused-by-spec), and update `vbt.ts:SIMPLIFIED_ZONES` colors to {EXPLOSIVE: cyan, FAST: green, MODERATE: amber, SLOW: orange, GRIND: red}. |
| 8 | Velocity zone labels | `vbt.ts:31-69` Title-Case (`"Grind"`, `"Slow"`, `"Moderate"`, `"Fast"`, `"Explosive"`). | `velocityZoneLabel()` reads from string resources `zone_explosive` etc. — likely Title Case as well. | YES (labels) | n/a | Verify localized strings render Title Case in non-English locales. |
| 9 | Velocity zone ranges | `vbt.ts:28-69`: GRIND <0.25, SLOW 0.25-0.5, MODERATE 0.5-0.75, FAST 0.75-1.0, EXPLOSIVE ≥1.0. | `BiomechanicsVelocityZone` (per CLAUDE.md): identical thresholds. | YES | n/a | None |
| 10 | Personal record phases | `transforms.ts` accepts `record.unit` and `record.value`; phase enum is `COMBINED / CONCENTRIC / ECCENTRIC` per CLAUDE.md but portal doesn't visually distinguish them in `Dashboard.tsx:110-117 formatPersonalRecordValue`. | Mobile `PersonalRecord` schema includes `WorkoutPhase`; `PRIndicator.kt`, `PRCelebrationAnimation.kt` reference it. | PARTIAL — same enum, portal hides it | MEDIUM | Portal should surface phase as a small badge next to PR (e.g., "Eccentric"). |
| 11 | Badges (name/icon/description) | `Profile.tsx:696-714` renders `badge.badge_name`, `badge.badge_description`, `badge.badge_tier` from the synced row. Order: server-returned (likely `earned_at desc`). Locked/secret badges not visible. | `BadgesScreen.kt:128-141` shows full grid (locked + earned) with category filter chips, secret badges shown as obscured cards (`isSecret = badge.isSecret && !isEarned`), tier color from `badge.tier.colorHex`. | NO | **HIGH** | Portal is *much* less informative than mobile. Add: locked-badge previews, category filter, tier color, secret-badge obscuration. Otherwise the portal feels feature-poor compared with mobile and users with portal-first workflows lose discoverability. |
| 12 | RPG attributes | `Profile.tsx` has no equivalent `RpgAttributeCard` — portal does not surface strength/power/stamina/consistency/mastery. | `RpgAttributeCard.kt` rendered on `BadgesScreen` and elsewhere — primary RPG surface. | NO | **HIGH** | Portal must add an RPG card on Profile/Dashboard. The data exists in `rpg_attributes` and is synced. |
| 13 | Subscription tier names | `TierBadge.tsx:17-22` `Free / Ember / Flame / Inferno` (Title Case). Order in TIER_STYLES map: `FREE → EMBER → FLAME → INFERNO`. | Mobile only references tier strings in `SyncManager.kt`, `EulaScreen.kt` etc. — no in-app tier badge component found. | PARTIAL — labels match, mobile lacks visual badge | MEDIUM | Mobile should ship a small `TierBadge` Composable on Settings/Profile mirroring portal styling. |
| 14 | Subscription gating affordance | `SubscribedRoute.tsx`, `SubscriptionGate.tsx`, `UpgradePrompt.tsx` provide route-guard + paywall components. | `SubscriptionTier` field is read but no "you need EMBER to unlock this" UI seen in spot-check. | NO | MEDIUM | Mobile needs a parity gating affordance for paid features. |
| 15 | Empty state — "no workouts yet" | `Dashboard.tsx:382-475` zero-session welcome view: flame icon in gradient circle, "Welcome to Phoenix Portal" heading, three feature-preview cards. CTA is implicit ("complete your first workout in the mobile app"). | `EmptyStateComponent.kt` generic empty state with FitnessCenter icon, title, message, optional action button. Used on routine/cycle empty lists. | PARTIAL — different illustrations and tone | MEDIUM | Align tone: mobile empty state is utilitarian, portal welcome is warmer. Standardize on Phoenix Flame icon + warm copy across both surfaces. |
| 16 | Routines & cycles — superset visual | `RoutineBuilder.tsx:144-155` groups by `supersetId`, paints with `supersetColor`. AMRAP rendered in compact label (`107: ${exercise.sets} sets • AMRAP • ${loadLabel} • ${exercise.mode}`). | `RoutineEditorScreen.kt:65-76` uses `SupersetTheme` for colored connector + `SupersetHeader.kt:51-60` for header with rename/copy/delete dropdown. Drag handle present. | PARTIAL — same data model, different visualization fidelity | MEDIUM | Portal should adopt the colored-connector visual mobile uses; current portal grouping by supersetColor border is less scannable. |
| 17 | AMRAP indication | `RoutineBuilder.tsx:107` inline label `"AMRAP"` in summary string. | `RoutineEditorScreen.kt` references `routine.exercises` but no spot-checked AMRAP indicator beyond text. | PARTIAL | POLISH | Add a small infinity-symbol or "AMRAP" badge on both surfaces to make it scannable. |
| 18 | Progress / consistency calendar | `ConsistencyCalendar.tsx:25-30` ember-tinted heatmap, opacity-stepped {0=#1A1A2E, 1=ember@40%, 2=ember@70%, 3+=ember@100%}; legend implied by intensity gradient. | No equivalent calendar component found on mobile (no `ConsistencyCalendar.kt`). Mobile shows streak in `StreakWidget` only (single number). | NO | **HIGH** | Mobile should add a heatmap/calendar component to match. Without it, returning users lose a "are I on track?" view that the portal provides. |
| 19 | IA / navigation model | Sidebar with 3 groups: Training (Dashboard, Workouts, Analytics, Routines, Cycles), Social (Community, Challenges, Leaderboard), Account (Profile, Integrations, Subscription). 11 destinations. | Bottom nav with 4 tabs: Analytics, Workouts (Home), Insights, Settings. Routines/Cycles live under Workouts subroutes; Profile is in TopAppBar profile sidepanel; no Social/Community tab. | NO | **HIGH** | Different mental models confuse users who toggle between surfaces. Decide: does mobile gain Social/Community tabs (matching portal), or does portal collapse Training/Social/Account into a 4-tab bottom nav on small screens (already has `MobileBottomNav.tsx` — verify parity)? Either way, names and grouping should converge. |
| 20 | Theme toggle | `ThemeToggle.kt:24-46` cycles **Light ↔ Dark only** (SYSTEM falls back to LIGHT). | Portal has no equivalent prominent theme toggle visible in `AppSidebar.tsx` or `AppLayout.tsx`; relies on dark default only. | PARTIAL — neither side meets the "light/dark/system tri-mode" expectation | MEDIUM | Per ArchitectUX charter requirement (light/dark/system), both surfaces should ship a 3-state toggle. Mobile already has the SYSTEM enum, just needs to include it in the toggle cycle. Portal needs to expose theme switching. |

---

## F. Findings (per charter F-### schema)

### F-001 [CRITICAL] Velocity zone colors completely diverge between surfaces

**Surface:** Both
**Category:** 8 (Cross-platform parity)
**Location:** `phoenix-portal/src/lib/colors.ts:58-64` (VELOCITY_ZONES) + `phoenix-portal/src/lib/vbt.ts:28-69` (SIMPLIFIED_ZONES) vs `Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/AccessibilityColors.kt:65-69`
**Observation:** The portal ships *two* contradictory color tables for the same simplified zone enum, and *neither* matches mobile's canonical mapping. `colors.ts:VELOCITY_ZONES` says `explosive=red(#ff5252), fast=amber, moderate=green, slow=blue, grind=purple`. `vbt.ts:SIMPLIFIED_ZONES` says `GRIND=#DC2626, SLOW=#F59E0B, MODERATE=#FF6B35 (ember), FAST=#10B981, EXPLOSIVE=#3B82F6`. Mobile says `EXPLOSIVE=#06B6D4 cyan, FAST=#22C55E, MODERATE=#F59E0B, SLOW=#F97316, GRIND=#EF4444`. The portal's `vbt.ts` doc-string explicitly claims it is "matching mobile app classification" — it is not.
**Why it hurts:** A user looking at a workout summary on mobile sees a *red* GRIND chip and on the portal sees the same set rendered with… also red, but EXPLOSIVE is *blue* on portal vs *cyan* on mobile, and SLOW is *orange* on mobile vs *gold* on portal. This silently teaches users an incorrect mental model. Worse, two of the three portal palettes are dead code or selectively used, so a single user can see different colors for the same zone in different parts of the portal.
**Severity rationale:** CRITICAL because (a) it violates the parity-critical contract documented in `CLAUDE.md`, (b) color is the primary classification cue (with text), and (c) the inconsistency exists *within* the portal itself, not just across surfaces.
**Proposed fix:** Adopt mobile's `StandardPalette` as the single source of truth. Update `phoenix-portal/src/lib/vbt.ts:SIMPLIFIED_ZONES` colors to `{EXPLOSIVE: '#06B6D4', FAST: '#22C55E', MODERATE: '#F59E0B', SLOW: '#F97316', GRIND: '#EF4444'}`. Delete `colors.ts:VELOCITY_ZONES` (it doesn't match anything). **Quick-win** (≤1hr).
**Parity flag:** YES

### F-002 [CRITICAL] Portal `BottomSheet` is not an accessible modal

**Surface:** Portal
**Category:** 7 (Accessibility)
**Location:** `phoenix-portal/src/app/components/BottomSheet.tsx:80-147`
**Observation:** The component renders a `motion.div` with no `role="dialog"`, no `aria-modal="true"`, no focus trap, no return-focus on close, and no ESC handler. Body scroll is locked manually. Drag handle is `cursor-grab` only — keyboard users cannot move between snap points.
**Why it hurts:** Keyboard-only and screen-reader users tabbing while the sheet is "open" reach the page below. AT software cannot detect the modal context. ESC does not close, against expected behavior. Drag-to-snap is mouse-only.
**Severity rationale:** CRITICAL because dialogs/sheets that interrupt main content flow are required to be modal per WCAG 2.4.3 (focus order) and 4.1.2 (name/role/value).
**Proposed fix:** Replace with Radix `<Dialog>` (already used elsewhere) wrapping `motion.div` for the slide animation, OR add `useFocusTrap` + ESC listener + `role="dialog" aria-modal="true"` + return-focus refs. **Design-spike (≥1 day)** because of the snap-point + drag interaction.
**Parity flag:** NO (mobile uses Compose `ModalBottomSheet` which handles this natively — divergence is portal-only, not mobile)

### F-003 [HIGH] Mobile does not honor system reduced-motion

**Surface:** Mobile
**Category:** 7 (Accessibility)
**Location:** `Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ExpressiveComponents.kt:42-49`, `BadgesScreen.kt:170-174, 396-401`, `BalanceBar.kt:67-76`, `EnhancedMainScreen.kt:6-11` (and many more — full grep for `infiniteRepeatable`, `animateFloatAsState`, Lottie usage).
**Observation:** No code path in `commonMain` queries the system reduced-motion setting. Spring animations, infinite-repeat pulses, scale presses, and Lottie playbacks all run unconditionally. Charter says portal handles this via `<MotionConfig reducedMotion="user">` + `@media (prefers-reduced-motion)`; mobile has no equivalent.
**Why it hurts:** Users with vestibular disorders, motion sensitivity, or cognitive disabilities see continuous motion (badge spring, streak scale, alert pulse). Compose has no built-in equivalent to `prefers-reduced-motion`, so this requires platform code.
**Severity rationale:** HIGH (not CRITICAL) because WCAG 2.3.3 *Animations from Interactions* is AAA, but WCAG 2.2.2 *Pause, Stop, Hide* (auto-updating) is AA — and the infinite alert pulse meets the AA bar.
**Proposed fix:** Add `expect fun isReduceMotionEnabled(): Boolean` in `commonMain`, with `actual` for Android (`Settings.Global.ANIMATOR_DURATION_SCALE == 0f`) and iOS (`UIAccessibility.isReduceMotionEnabled`). Wrap all infinite/looping animations with a ternary that uses `snap()` instead of `tween/spring` when reduced motion is on. Add `LocalReducedMotion` `CompositionLocal` mirroring Material's pattern. **Design-spike (≥1 day)** because of the cross-platform contract and audit of every animation site.
**Parity flag:** YES

### F-004 [HIGH] Per-cable weight convention invisible to portal users

**Surface:** Portal
**Category:** 8 (Cross-platform parity) + 5 (Form & input UX)
**Location:** `phoenix-portal/src/lib/units.ts:18-27 formatWeight`, `phoenix-portal/src/schemas/transforms.ts:6-9 WEIGHT_MULTIPLIER`
**Observation:** Portal silently multiplies the per-cable DB value by 2 and renders `"123 kg"` with no disclosure. Users training on the machine see `61.5 kg per cable` on the screen and `123 kg` on portal — the math is right but the convention is opaque. Mobile `WeightStepper.kt:118-119, 142-172` *explicitly* labels weight as `"kg per cable"` AND surfaces `"Total weight for 2 cables: X kg"`.
**Why it hurts:** A user planning a routine on portal at "200 kg" expects to set the machine to 200 kg per cable. The machine maxes at 110 kg per cable. Their plan silently halves itself. The `CLAUDE.md` parity-critical section calls this out, yet the portal-side fix (UX disclosure) is missing.
**Severity rationale:** HIGH because this is a documented parity-critical contract that affects workout planning. Not CRITICAL because the values are mathematically correct on each side; only the *convention* is hidden.
**Proposed fix:** Add a tooltip/footnote on portal weight surfaces: `"Total weight (both cables) — your machine displays half this value per cable."` On routine/cycle editors, render both: `"100 kg total (50 kg per cable)"`. **Quick-win** (≤2hr).
**Parity flag:** YES

### F-005 [HIGH] Asymmetry threshold has three contradictory values across the codebase

**Surface:** Both
**Category:** 8
**Location:** `phoenix-portal/src/lib/biomechanics.ts:2 ASYMMETRY_THRESHOLD = 10`, `phoenix-portal/src/app/components/charts/AsymmetryGauge.tsx:44, 52, 170, 294 <= 2`, `Project-Phoenix-MP/.../BalanceBar.kt:55-59 <10/<15`, mobile `BiomechanicsEngine.kt:113 0.02f` and `:516 < 2f` (per `08-sync-parity.md` previous audit).
**Observation:** Three different "balanced" thresholds coexist. Portal `AsymmetryGauge` uses `<= 2` for the "Balanced" label but uses `ASYMMETRY_THRESHOLD = 10` for the threshold lines. Mobile `BalanceBar` uses 10/15. CLAUDE.md says canonical is 2%.
**Why it hurts:** A user sees "Balanced" on portal at ≤2% but on mobile the same rep would render with a green (acceptable) BalanceBar at 5% asymmetry. Two surfaces "agree" on imbalance differently.
**Severity rationale:** HIGH because the canonical value is documented but not enforced.
**Proposed fix:** Export shared constant `ASYMMETRY_BALANCED_THRESHOLD_PCT = 2` from `phoenix-portal/src/lib/biomechanics.ts`. Use it everywhere on portal (replace inline `<= 2` and the `=10` flag-line). On mobile, update `BalanceBar.kt:55-59` to `<2 = good, 2-10 = caution, >10 = bad` and ensure `BiomechanicsEngine.kt` uses 2% on a single scale. **Quick-win** (≤2hr) for portal; mobile change requires `legion:advise` because it changes user-visible classification.
**Parity flag:** YES

### F-006 [HIGH] Portal `--destructive-foreground` on `--destructive` fails AA Normal contrast

**Surface:** Portal
**Category:** 7
**Location:** `phoenix-portal/src/styles/theme.css:42-43`
**Observation:** `--destructive: #ff5252` with `--destructive-foreground: #FFFFFF` yields **3.19:1**, below AA Normal (4.5:1). Used on `<Button variant="destructive">` and the Logout dropdown item (`AppSidebar.tsx:329` — though that uses `text-destructive` on the dropdown bg, not on a destructive bg).
**Why it hurts:** Button labels for destructive actions (Delete routine, Delete cycle, Logout in dialog) become hard to read for low-vision users.
**Severity rationale:** HIGH because destructive actions warrant the strongest contrast.
**Proposed fix:** Either darken `--destructive` to `#dc2626` (~4.83:1) or change `--destructive-foreground` to `#06060a` (4.5:1+). Quick-win.
**Parity flag:** NO (mobile `SignalError #EF4444` on Slate950 is 5.36:1, passes)

### F-007 [HIGH] Portal lacks badges-grid (locked + secret) and RPG attributes surfaces

**Surface:** Portal
**Category:** 8 + 6 (Data-dense surfaces)
**Location:** `phoenix-portal/src/app/components/Profile.tsx:534-720` (Badges tab) — only earned badges shown; no `RpgAttributeCard` equivalent anywhere on portal.
**Observation:** Mobile `BadgesScreen.kt` shows the full badge grid with locked/earned states, secret-badge obscuration, category filter, tier color, and an `RpgAttributeCard` rendered above. Portal's Profile Badges tab shows only earned badges as a flat list and never surfaces RPG attributes.
**Why it hurts:** A portal-first user has *no way* to see what badges exist (and therefore what to chase) or their RPG class progression. They see only retroactive achievements. The mobile app is feature-superior on a feature where portal should at minimum mirror.
**Severity rationale:** HIGH — feature gap reduces portal's utility for engagement loops.
**Proposed fix:** Build `<BadgesGrid>` portal component mirroring mobile (locked/secret/earned variants, tier colors, category filter). Add `<RpgAttributeCard>` to Profile and/or Dashboard. **Design-spike (≥1 day).**
**Parity flag:** YES

### F-008 [HIGH] Portal lacks consistency calendar/heatmap on mobile? (reverse-parity)

**Surface:** Mobile
**Category:** 8
**Location:** `phoenix-portal/src/app/components/ConsistencyCalendar.tsx` exists; no `ConsistencyCalendar.kt` in mobile components.
**Observation:** Portal has a 12-week ember-intensity heatmap with computed current/longest streak. Mobile shows only a single streak number in `StreakWidget` (`BadgesScreen.kt:222`).
**Why it hurts:** Mobile users miss the spatial "where am I trending?" view that the portal user gets.
**Severity rationale:** HIGH — losing a core engagement surface on the *primary* (authoritative) device.
**Proposed fix:** Build `ConsistencyCalendar.kt` Composable using same opacity-stepped ember intensity (`#FF9149` at 40/70/100% on Slate950). Place above `StreakWidget` on `BadgesScreen` or inline on Home. **Design-spike (≥1 day).**
**Parity flag:** YES

### F-009 [HIGH] Mobile `BalanceBar` text labels at 8sp/9sp violate dynamic-type minimums

**Surface:** Mobile
**Category:** 7
**Location:** `Project-Phoenix-MP/.../BalanceBar.kt:154, 163, 175`
**Observation:** `"L"` and `"R"` labels at `fontSize = 8.sp`, percentage at `fontSize = 9.sp`. Below WCAG-recommended 12sp body floor and below Material 3 minimum (10sp for `labelSmall` token).
**Why it hurts:** Users with system font scaled to default see microscopic side-channel labels; users with 200% scale see them readable but layout wraps awkwardly.
**Severity rationale:** HIGH because this widget is the primary form-feedback channel on `ActiveWorkoutScreen` and the value text *is* the data.
**Proposed fix:** Lift to `MaterialTheme.typography.labelSmall` (11sp) without `.copy(fontSize = 8.sp)` override; allow Material's typography token to govern. If the visual "feels too big" the answer is wider/shorter bar, not smaller text. **Quick-win (≤30 min).**
**Parity flag:** NO

### F-010 [HIGH] Mobile theme toggle is binary (light↔dark), missing SYSTEM mode in cycle

**Surface:** Mobile
**Category:** 7 + 1 (Visual design)
**Location:** `Project-Phoenix-MP/.../ThemeToggle.kt:24-46`
**Observation:** `ThemeMode` enum supports `LIGHT / DARK / SYSTEM`, but `ThemeToggle.kt:27-32` toggles only between Light↔Dark, with `SYSTEM` falling back to LIGHT. Per ArchitectUX charter requirement, theme controls should support light/dark/system tri-mode.
**Why it hurts:** Users who want OS-level theme respect cannot opt back into SYSTEM after they've manually picked one. They have to clear app data or change platform.
**Severity rationale:** HIGH because this is a baseline accessibility expectation in 2026.
**Proposed fix:** Convert `ThemeToggle` to a 3-state toggle (radio-group or popup menu) cycling LIGHT → DARK → SYSTEM. Or add a separate "Use system theme" toggle in Settings. **Quick-win (≤2hr).** Portal also lacks a visible theme toggle — consider this on portal too as MEDIUM.
**Parity flag:** YES (portal also lacks the toggle)

### F-011 [MEDIUM] Portal `--disabled-foreground #5a5a66` on `--background` fails AA Normal (2.98:1)

**Surface:** Portal
**Category:** 7
**Location:** `phoenix-portal/src/styles/theme.css:39`
**Observation:** Disabled-foreground 2.98:1 on background. WCAG 1.4.3 *exempts* truly disabled controls from contrast minimums, but if `--disabled-foreground` is used for *placeholders* or *hint text* (which are not disabled controls), it fails.
**Why it hurts:** Depends on usage — need to audit every place the token is referenced. If only on `[disabled]` controls, this is compliant; otherwise it's a 1.4.3 fail.
**Severity rationale:** MEDIUM until usage audit confirms.
**Proposed fix:** Audit `--disabled-foreground` usages. For non-disabled-control usages, lift to ≥`#7a7a86` (≈4.55:1). **Quick-win (≤2hr).**
**Parity flag:** NO

### F-012 [MEDIUM] Portal `--sidebar-group-label #4a4a56` fails AA at 2.32:1

**Surface:** Portal
**Category:** 7
**Location:** `phoenix-portal/src/styles/theme.css:74` and rendered via `AppSidebar.tsx:217 className="eyebrow text-muted-foreground"` (overrides) — but `[data-sidebar="group-label"]` selector at `theme.css:307` forces `var(--sidebar-group-label)`.
**Observation:** Group labels ("Training", "Social", "Account") render at `#4a4a56` on near-black sidebar — 2.32:1.
**Why it hurts:** Group labels guide IA scanning; low contrast makes them ambient and easy to miss.
**Severity rationale:** MEDIUM — they're decorative section headers, not interactive, but they convey IA grouping that aids navigation.
**Proposed fix:** Lift to `#7a7a86` (≈4.55:1) or use existing `--muted-foreground #a0a0ac` (7.82:1). **Quick-win.**
**Parity flag:** NO

### F-013 [MEDIUM] Mobile workout-mode iconography is inconsistent / undefined

**Surface:** Both
**Category:** 8
**Location:** Various `HomeScreen.kt`, `ModeSelector.kt`, `ModeConfirmationScreen.kt` use Material icons ad-hoc; no canonical Mode→Icon map.
**Observation:** Portal has no per-mode icon. Mobile uses `FitnessCenter`, `LocalFireDepartment`, `Loop`, `SelfImprovement` etc. without a single source of truth.
**Why it hurts:** Inconsistent visual cues for the same mode (Echo, TUT, etc.) across screens and surfaces.
**Severity rationale:** MEDIUM — affects scanability but not blocking.
**Proposed fix:** Define `ProgramMode → IconRes` map in shared spec, ship matching SVG/icons on portal. **Design-spike (≥1 day).**
**Parity flag:** YES

### F-014 [MEDIUM] Portal `BottomSheet` drag interaction has no keyboard equivalent

**Surface:** Portal
**Category:** 7
**Location:** `phoenix-portal/src/app/components/BottomSheet.tsx:92-104` drag/snap implementation.
**Observation:** Snap-point selection is mouse/touch drag only. Keyboard users cannot move between 30/60/90% snaps.
**Why it hurts:** Inaccessible for keyboard-only users (already covered partially by F-002 but worth a dedicated finding on the input modality).
**Severity rationale:** MEDIUM, downstream of F-002.
**Proposed fix:** Add ↑/↓ arrow handlers when sheet has focus to step between snap points. **Quick-win (≤2hr) once F-002 is resolved.**
**Parity flag:** NO

### F-015 [MEDIUM] Mobile decorative gradient subtitle "Project Phoenix" is announced on every screen

**Surface:** Mobile
**Category:** 7
**Location:** `Project-Phoenix-MP/.../EnhancedMainScreen.kt:279-290`
**Observation:** TopAppBar always renders a "Project Phoenix" subtitle in a Brush.linearGradient text style. No `Modifier.semantics { invisibleToUser() }` is applied. Screen readers announce it on every screen change.
**Why it hurts:** Screen-reader users hear "Project Phoenix" repeated dozens of times per session.
**Severity rationale:** MEDIUM — annoyance, not blocking.
**Proposed fix:** Add `Modifier.clearAndSetSemantics {}` (decorative) on the subtitle Text. **Quick-win (≤30 min).**
**Parity flag:** NO

### F-016 [MEDIUM] IA / navigation models fully diverge between surfaces

**Surface:** Both
**Category:** 8 + 2 (IA & navigation)
**Location:** `phoenix-portal/src/app/components/AppSidebar.tsx:63-90` (3 groups, 11 items) vs `Project-Phoenix-MP/.../EnhancedMainScreen.kt:381-491` (4-tab bottom nav).
**Observation:** Portal: Training (Dashboard, Workouts, Analytics, Routines, Cycles), Social (Community, Challenges, Leaderboard), Account (Profile, Integrations, Subscription). Mobile: Analytics, Workouts/Home, Insights, Settings — no Social/Community at all.
**Why it hurts:** A user toggling between surfaces has no shared mental model. Where's "Routines" on mobile? Under Workouts. Where's "Community" on mobile? Doesn't exist. Where's "Insights" on portal? Implicitly under Analytics.
**Severity rationale:** MEDIUM — both work, but confusion compounds over a session.
**Proposed fix:** Run an IA workshop. Either (a) collapse portal to a 4-pane (Train / Insights / Community / Profile) on mobile breakpoint matching mobile-app tabs, or (b) add Social/Community to mobile bottom nav (5-tab) and make "Insights" subordinate to Analytics. **Design-spike (≥2 days).**
**Parity flag:** YES

### F-017 [MEDIUM] Portal Title-Case zone labels vs mobile localized strings — verify multi-locale parity

**Surface:** Both
**Category:** 8
**Location:** `vbt.ts:31-69` hardcoded English labels vs mobile `velocityZoneLabel()` reading `Res.string.zone_*`.
**Observation:** Portal labels are hardcoded English; mobile uses string resources for i18n. If/when mobile ships in non-English locales, labels will diverge.
**Why it hurts:** Spanish-speaking user on mobile sees "Explosivo" on the same workout that the portal renders as "Explosive".
**Severity rationale:** MEDIUM — only matters when localization ships.
**Proposed fix:** Wire portal i18n (i.e., `react-i18next`) and source zone labels through translations. **Design-spike (≥1 day) tied to localization milestone.**
**Parity flag:** YES (latent)

### F-018 [POLISH] AMRAP indicator is a text label only — no visual badge on either side

**Surface:** Both
**Category:** 8 + 4 (Workout flow ergonomics)
**Location:** `RoutineBuilder.tsx:107` vs Mobile `RoutineEditorScreen.kt` (no spot-checked AMRAP visual).
**Observation:** Portal: `"AMRAP"` text in summary line. Mobile: same.
**Why it hurts:** Text-only AMRAP indicators are scannable only at full read; a small infinity-symbol badge would be glanceable.
**Severity rationale:** POLISH — works as-is.
**Proposed fix:** Add a small infinity-symbol or "∞ AMRAP" pill on both surfaces. **Quick-win.**
**Parity flag:** YES

### F-019 [POLISH] Empty-state tone differs between surfaces

**Surface:** Both
**Category:** 8 + 3 (State coverage)
**Location:** `phoenix-portal/.../Dashboard.tsx:382-475` welcome view vs `Project-Phoenix-MP/.../EmptyStateComponent.kt`.
**Observation:** Portal has a warm, branded "Welcome to Phoenix Portal" with feature-preview cards. Mobile has a generic FitnessCenter icon + title + message + optional action button.
**Why it hurts:** Onboarding-time tone is brand-impacting. Mobile feels utilitarian by comparison.
**Severity rationale:** POLISH.
**Proposed fix:** Replace mobile `EmptyStateComponent` icon default to Phoenix flame; warm up message copy; standardize across surfaces. **Quick-win (≤2hr).**
**Parity flag:** YES

---

## Summary counts

| Tier | Count | Findings |
|---|---|---|
| CRITICAL | 2 | F-001, F-002 |
| HIGH | 8 | F-003, F-004, F-005, F-006, F-007, F-008, F-009, F-010 |
| MEDIUM | 7 | F-011, F-012, F-013, F-014, F-015, F-016, F-017 |
| POLISH | 2 | F-018, F-019 |

**WCAG 2.2 AA contrast failures:** 3 portal (F-006 destructive, F-011 disabled, F-012 sidebar-label) + 1 portal zone-text (F-001 GRIND red) — counted under F-001/F-006/F-011/F-012.
**Mobile contrast:** 0 failures on sampled tokens.

## Files referenced (absolute paths)

- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/phoenix-portal/src/styles/theme.css`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/phoenix-portal/src/lib/colors.ts`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/phoenix-portal/src/lib/vbt.ts`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/phoenix-portal/src/lib/units.ts`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/phoenix-portal/src/lib/biomechanics.ts`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/phoenix-portal/src/schemas/transforms.ts`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/phoenix-portal/src/app/components/AppSidebar.tsx`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/phoenix-portal/src/app/routes/AppLayout.tsx`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/phoenix-portal/src/app/components/Dashboard.tsx`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/phoenix-portal/src/app/components/BottomSheet.tsx`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/phoenix-portal/src/app/components/DeleteConfirmDialog.tsx`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/phoenix-portal/src/app/components/SkipToContent.tsx`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/phoenix-portal/src/app/components/Profile.tsx`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/phoenix-portal/src/app/components/TierBadge.tsx`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/phoenix-portal/src/app/components/RoutineBuilder.tsx`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/phoenix-portal/src/app/components/ConsistencyCalendar.tsx`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/phoenix-portal/src/app/components/charts/AsymmetryGauge.tsx`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/phoenix-portal/src/app/components/ui/ZoneBadge.tsx`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Color.kt`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/AccessibilityColors.kt`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Type.kt`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HomeScreen.kt`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/BadgesScreen.kt`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ModeConfirmationScreen.kt`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/WeightStepper.kt`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/RpeSlider.kt`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/BalanceBar.kt`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/BiomechanicsHistoryCard.kt`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/CompactNumberPicker.kt`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/EmptyStateComponent.kt`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ExpressiveComponents.kt`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/SupersetHeader.kt`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ThemeToggle.kt`
- `C:/Users/dasbl/AndroidStudioProjects/Phoenix App Monorepo/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt`
