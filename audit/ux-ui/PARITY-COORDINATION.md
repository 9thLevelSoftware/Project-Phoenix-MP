# Phoenix Cross-Repo Parity Coordination

**Audit:** 2026-05-01 UX/UI audit
**Branches:** `feat/ux-ui-audit-2026-05-01` on both `phoenix-portal` and `Project-Phoenix-MP`
**Read alongside:** `audit/PLAN.md` (this repo) and the equivalent in the other repo.

---

## Why this document exists

Some findings can't be fixed in one repo without coordinating the other. The same DB row (e.g. `loadA: number`) renders with different visual semantics on phone vs web. The same constant (e.g. `ASYMMETRY_BALANCED_THRESHOLD`) is duplicated in three files across two repos.

This document is the **single source of truth for cross-cutting changes** that need synchronized landing in both repos. Identical copies live in `phoenix-portal/audit/` and `Project-Phoenix-MP/audit/`. Drift between the two copies is itself a parity bug — keep them in sync.

---

## §1 Velocity-zone color palette

**Severity:** CRITICAL
**Source:** `findings/04-a11y-parity.md` F-001, `findings/03-visual-brand.md` F-002

### Problem
The portal ships **three contradictory** velocity-zone color tables. None match mobile's canonical mapping. The portal's `lib/vbt.ts` doc-string explicitly claims it is "matching mobile app classification" — it is not.

| Source | EXPLOSIVE | FAST | MODERATE | SLOW | GRIND |
|--------|-----------|------|----------|------|-------|
| `phoenix-portal/src/lib/colors.ts:VELOCITY_ZONES` | `#ff5252` (red) | `#ffab00` (amber) | `#00e676` (green) | `#448aff` (blue) | `#7c4dff` (purple) |
| `phoenix-portal/src/lib/vbt.ts:SIMPLIFIED_ZONES` | `#3B82F6` (blue) | `#10B981` (green) | `#FF6B35` (ember) | `#F59E0B` (gold) | `#DC2626` (red) |
| `Project-Phoenix-MP/.../AccessibilityColors.kt:65-69` (canonical VBT) | `#06B6D4` (cyan) | `#22C55E` (green) | `#F59E0B` (amber) | `#F97316` (orange) | `#EF4444` (red) |

### Resolution
**Adopt mobile's `StandardPalette` as canonical.** Mobile is correct; portal is wrong in two ways.

### Portal-side action
| File | Change |
|------|--------|
| `phoenix-portal/src/lib/vbt.ts:SIMPLIFIED_ZONES` | Change colors to `{EXPLOSIVE: '#06B6D4', FAST: '#22C55E', MODERATE: '#F59E0B', SLOW: '#F97316', GRIND: '#EF4444'}`. Update doc-string to no longer be a lie. |
| `phoenix-portal/src/lib/colors.ts` | Delete `VELOCITY_ZONES` constant entirely (dead code; doesn't match anything anywhere). |
| Consumers | Audit `useVelocityZone*`, `VelocityChip`, `BiomechanicsHistory*`, `ZoneBadge` for any callers of either old palette. Migrate to `SIMPLIFIED_ZONES`. |
| WCAG | After flip, GRIND `#EF4444` on `#06060A` background = 5.36:1 — **passes** AA Normal. Old `#DC2626` was 4.19:1 — failed. So this fixes G-005 and the GRIND contrast issue simultaneously. |

### Mobile-side action
None. Mobile is canonical reference.

### Effort & sequence
- Effort: ≤ 1 hour (portal-only)
- Sequence: ship anytime; no mobile dependency
- Tracking: portal P-4 (Top fix-first list)

---

## §2 Cable A/B color identity

**Severity:** CRITICAL
**Source:** `findings/03-visual-brand.md` F-001

### Problem
The portal renders cable A in orange and cable B in blue. The mobile app renders cable A in **blue** and cable B in **orange**. Same DB column (`loadA` / `loadB`), inverted visual meaning. Users build intuition on one surface and mis-read the other.

| Field | Portal | Mobile (`DataColors.kt`) |
|-------|--------|--------------------------|
| Cable A load color | `#FF6B35` (ember) | `#3B82F6` (blue) |
| Cable B load color | `#6BA3F7` (sky blue) | `#F97316` (orange) |
| Position A color | (not visualized) | `#22C55E` (green) |
| Position B color | (not visualized) | `#A855F7` (violet) |

### Resolution
**Adopt portal's mapping as canonical** (A = ember, B = blue) — the portal cable A already aligns with brand-primary, so this is the natural choice.

### Mobile-side action
| File | Change |
|------|--------|
| `Project-Phoenix-MP/.../ui/theme/DataColors.kt:32-38` | Flip `LoadA` ↔ `LoadB`, `PositionA` ↔ `PositionB` such that A = orange (`#F97316` or after M-11 the canonical ember `#FF6B35`), B = blue (`#3B82F6`). Position colors follow same A/B identity. |
| Consumers | Audit ~10 chart consumers. Verify no hardcoded references to "blue = A". Update any inline `LoadA`/`LoadB` literal usages in `BiomechanicsHistoryCard`, `BalanceBar`, `EnhancedCablePositionBar`, `ForceCurveMiniGraph`, `ForceSparkline`, replay components. |

### Portal-side action
None. Portal is canonical reference.

### Caveat
After mobile's brand-Ember adoption (M-11), cable A on mobile should be `#FF6B35` (matching portal) — not `#F97316`. Confirm `LoadA` and `primary` are bound to the same color or very close (mobile's existing `DataColors.LoadB = #F97316` after the flip becomes `LoadA`; if you want stricter match, override to `#FF6B35`).

### Effort & sequence
- Effort: ≤ 2 hours (mobile-only)
- Sequence: can ship before or after portal §1; recommend after M-11 lands so the new `#FF6B35` is available; no portal dependency
- Tracking: mobile M-5 (Top fix-first list)

---

## §3 Per-cable weight convention

**Severity:** HIGH
**Source:** `findings/04-a11y-parity.md` F-004, `findings/01-mobile-static.md` F-008

### Problem
The Vitruvian machine has 2 cables. All weight values in the database are **per-cable** (range 0–220 kg). Mobile labels values as `"kg per cable"` and only `WeightStepper` discloses `"Total weight for 2 cables: X kg"`. Portal silently multiplies by 2 (`WEIGHT_MULTIPLIER` in `phoenix-portal/src/schemas/transforms.ts`) and renders the doubled value as plain `"123 kg"` with **no convention disclosure**. A user planning at "200 kg" on portal expects the machine to show 200 kg per cable; the machine shows 100 kg per cable. Silent halving.

This convention is documented in `Project-Phoenix-MP/CLAUDE.md` and parent `CLAUDE.md` but the user-facing portal disclosure is missing.

### Resolution
**Both surfaces fully disclose the convention with consistent affordances.**

### Portal-side action
| File | Change |
|------|--------|
| `phoenix-portal/src/lib/units.ts:formatWeight` | Add an optional `disclose: boolean = false` param. When `true`, render `"123 kg total"` with `<sup>i</sup>` tooltip-trigger. |
| `phoenix-portal/src/app/components/RoutineBuilder.tsx`, `CycleBuilder.tsx`, `WorkoutHistory.tsx`, `Analytics.tsx` weight cells | Add tooltip on first appearance per page: "Total weight (both cables) — your machine displays half this value per cable." |
| Routine/cycle editors | Render dual: `"100 kg total (50 kg per cable)"` |

### Mobile-side action
| File | Change |
|------|--------|
| `Project-Phoenix-MP/.../HomeScreen.kt:548`, `SetReadyScreen.kt:359`, `RoutineOverviewScreen.kt:615`, `SetSummaryCard.kt:193`, `RestTimerCard.kt:441` | Add `(i)` info icon next to "Weight per cable" labels that opens a tooltip/bottom sheet with the same disclosure copy as portal. |
| `SetReadyScreen` and `RoutineOverviewScreen` | Display total alongside per-cable using the existing `WeightStepper.kt:142-173` pattern. |
| First-launch onboarding | One-time modal explaining the per-cable convention. |

### Effort & sequence
- Effort: ≤ 2 hours per repo
- Sequence: independent; ship in any order
- Tracking: portal P-8, mobile M-18

---

## §4 Asymmetry threshold

**Severity:** HIGH
**Source:** `findings/04-a11y-parity.md` F-005

### Problem
The "BALANCED" threshold has **three different values** across the codebase:

| Source | Value | Used as |
|--------|-------|---------|
| `phoenix-portal/src/lib/biomechanics.ts:2 ASYMMETRY_THRESHOLD` | `10` | Threshold lines on `AsymmetryGauge` |
| `phoenix-portal/src/app/components/charts/AsymmetryGauge.tsx:44,52,170,294` | `<= 2` | "Balanced" label classification |
| `Project-Phoenix-MP/.../BalanceBar.kt:55-59` | `<10`/`<15` | Severity ladder |
| `Project-Phoenix-MP/.../BiomechanicsEngine.kt:113,516` | `0.02f` (= 2%) | Engine classification |

The parent `CLAUDE.md` and project `CLAUDE.md` both document **2%** as the canonical threshold.

### Resolution
**Single source of truth: `ASYMMETRY_BALANCED_THRESHOLD_PCT = 2`.** Both repos consume this constant.

### Portal-side action
| File | Change |
|------|--------|
| `phoenix-portal/src/lib/biomechanics.ts` | Export `ASYMMETRY_BALANCED_THRESHOLD_PCT = 2` (replacing the misnamed `ASYMMETRY_THRESHOLD = 10`). Add a `ASYMMETRY_FLAG_THRESHOLD_PCT = 10` if the threshold-line band is needed visually, but the *Balanced* classification must use 2. |
| `phoenix-portal/src/app/components/charts/AsymmetryGauge.tsx` | Replace inline `<= 2` magic number and the `=10` flag-line with the named constants. Verify chart visuals still convey the band. |

### Mobile-side action
| File | Change |
|------|--------|
| `Project-Phoenix-MP/.../BalanceBar.kt:55-59` | Update classification to `<2 = BALANCED, 2-10 = CAUTION, >10 = IMBALANCED`. Add a const matching portal's `ASYMMETRY_BALANCED_THRESHOLD_PCT = 2` in `domain/biomechanics` package, single source of truth on mobile. |
| `Project-Phoenix-MP/.../BiomechanicsEngine.kt:113,516` | Confirm uses 2% (already does); reference the named constant. |

### Caveat — user-visible classification change on mobile
Mobile `BalanceBar` currently classifies `<10%` as good. After this change, anything ≥ 2% renders as caution — which means **users may suddenly see more "caution" balance bars** for the same workouts. This is correct per the spec but may surprise users. Consider a one-time onboarding card: "We've tightened our balance precision to match the science."

### Effort & sequence
- Effort: ≤ 2 hours per repo
- Sequence: **must ship together** — if portal lands first, mobile users see different thresholds; if mobile lands first, portal users see different thresholds. Coordinate as a single coordinated release.
- Tracking: portal P-9, mobile M-19

---

## §5 Theme toggle (Light / Dark / System tri-mode)

**Severity:** MEDIUM (HIGH on mobile per `04-F-010`)
**Source:** `findings/04-a11y-parity.md` F-010

### Problem
Portal: no visible theme toggle (dark-only).
Mobile: `ThemeMode` enum supports `LIGHT / DARK / SYSTEM`, but `ThemeToggle.kt:27-32` cycles only Light↔Dark; SYSTEM is unreachable after the user picks one.

### Resolution
Both surfaces ship a 3-state Light/Dark/System toggle. SYSTEM defaults respect OS-level preference.

### Portal-side action
| File | Change |
|------|--------|
| `phoenix-portal/src/app/components/AppSidebar.tsx` (or Settings) | Add a `<ThemeToggle>` 3-state radio/segmented control. |
| `phoenix-portal/src/styles/theme.css` | Add a `:root.light` palette (or use `prefers-color-scheme: light`). **NOTE:** mobile light mode is currently mint-tinted off-brand (`03-F-006`); decide whether portal's light theme follows the same Phoenix-on-light approach or is intentionally absent. |

### Mobile-side action
| File | Change |
|------|--------|
| `Project-Phoenix-MP/.../ThemeToggle.kt:27-32` | Change cycle to LIGHT → DARK → SYSTEM → LIGHT. Or convert to a 3-button segmented control. |

### Caveat — light theme is currently off-brand on mobile
`03-F-006` flags mobile light mode as mint-washed and unrelated to Phoenix branding. The 3-state toggle exposes light mode to more users; either (a) drop mobile light mode entirely (recommended — workout product, low-light usage), or (b) redesign the light theme.

### Effort & sequence
- Effort: ≤ 2 hours per repo (toggle component); design spike for portal light-theme tokens (~half-day)
- Sequence: independent; ship in any order
- Tracking: portal P-H17, mobile M-14

---

## §6 RPG attributes surface (portal-only build)

**Severity:** HIGH
**Source:** `findings/04-a11y-parity.md` F-007

### Problem
Mobile renders `RpgAttributeCard` on `BadgesScreen` and elsewhere. The data flows portal-bound via the `rpg_attributes` table (mobile pushes computed RPG values via sync). Portal doesn't surface RPG attributes anywhere.

### Resolution
Portal builds an `<RpgAttributeCard>` component matching mobile's visual design.

### Portal-side action
Build new component, place on `Profile.tsx` and/or `Dashboard.tsx`. Match mobile's strength/power/stamina/consistency/mastery attribute layout (0-100 scale, gauge or bar).

### Mobile-side action
None.

### Effort & sequence
- Effort: ≥ 1 day (portal-only design + build)
- Sequence: portal-only, no mobile dependency
- Tracking: portal P-H15

---

## §7 Badges grid (portal-only build)

**Severity:** HIGH
**Source:** `findings/04-a11y-parity.md` F-007

### Problem
Mobile `BadgesScreen.kt` shows the full badge grid: locked + earned + secret-obscured + category filter + tier color from `badge.tier.colorHex`. Portal `Profile.tsx:534-720` Badges tab shows only earned badges as a flat list.

### Resolution
Portal builds `<BadgesGrid>` mirroring mobile.

### Portal-side action
Build component with: locked-badge previews (greyscale + lock icon), category filter chips, tier colors, secret-badge obscuration when `isSecret && !isEarned`.

### Mobile-side action
None.

### Effort & sequence
- Effort: ≥ 1 day (portal-only)
- Tracking: portal P-H16

---

## §8 ConsistencyCalendar (mobile-only build)

**Severity:** HIGH
**Source:** `findings/04-a11y-parity.md` F-008

### Problem
Portal `ConsistencyCalendar.tsx` renders a 12-week ember-intensity heatmap with computed current/longest streak. Mobile shows only a single streak number in `StreakWidget`.

### Resolution
Mobile builds `ConsistencyCalendar.kt` Composable mirroring portal's heatmap.

### Mobile-side action
Build new Composable using opacity-stepped ember intensity (`#FF6B35` after M-11 / `PhoenixOrangeDark` for now) at 40 / 70 / 100% on `Slate950` (or new Phoenix surface scale after M-12). Place above `StreakWidget` on `BadgesScreen` or inline on Home.

### Portal-side action
None. Reference implementation.

### Effort & sequence
- Effort: ≥ 1 day (mobile-only)
- Tracking: mobile M-H32

---

## §9 Brand palette unification (mobile adopts portal canonical)

**Severity:** CRITICAL (across 4 sub-findings)
**Source:** `findings/03-visual-brand.md` F-003 (typography), F-004 (Ember), F-005 (background), F-007 (radii), F-008 (depth language), F-009 (secondary token role), F-010 (tertiary), F-011 (Forge Green absent)

### Problem
Mobile and portal share three brand colors and a name; they don't share typography, surface scale, radii, depth language, secondary token role, or several brand colors. Aggregate effect: the two surfaces don't read as the same product.

### Resolution
**Mobile adopts portal canonical** for all of these. Mobile-side, multi-PR effort.

### Mobile-side action (sequenced sub-tasks)

| Sub-task | Files | Effort | Mobile finding |
|----------|-------|--------|----------------|
| **9a. Phoenix Ember `#FF6B35`** | `Color.kt`, `EulaScreen.kt:25`, `SplashScreen.kt:36`, `HomeScreen.kt:346`, `EnhancedMainScreen.kt:284-287` | ≤ 2 hr | M-11 |
| **9b. Surface scale (`#06060A → #141420`)** | `Color.kt`, `Theme.kt:33-48`, `expressiveCardColors()` consumers | ~ 4 hr | M-12 |
| **9c. Border radii (decision required, recommend hairline)** | `Shapes.kt`, `Material3Expressive.kt`, screen-level `RoundedCornerShape(*.dp)` literals | 1-2 days | M-H25 |
| **9d. Drop-shadow depth language** | `Material3Expressive.kt`, `expressiveCardElevation()` consumers | ~ 1 day | M-H26 |
| **9e. `secondary` token role** (mobile gold ↔ neutral; coordinate with portal §5 for aligned tokens) | `Theme.kt`, all consumers of `MaterialTheme.colorScheme.secondary` | 1-2 hr | M-H28 |
| **9f. `tertiary` to gold or Forge Green (drop teal)** | `Theme.kt:27` | 1 hr | M-H29 |
| **9g. Add Forge Green** (`#10B981`) | `Color.kt` (new const), bind to "complete/connected/PR-achieved" surfaces | 1 hr | M-H30 |
| **9h. Typography bundle** (Chakra Petch + Inter + JetBrains Mono) | `composeResources/font/`, `Type.kt`, HUD numerics | ~ 1.5 days | M-9 |

### Portal-side action
None. Portal is canonical.

### Effort & sequence
- Total mobile effort: ≈ 2-3 dev weeks (multi-PR)
- Sequence: 9a → 9g (color + brand) → 9b (surface) → 9c → 9d (depth + radii) → 9e + 9f (token role) → 9h (typography spike standalone)
- Each sub-PR should re-run accessibility audit + dark-mode visual regression

---

## §10 IA / navigation model alignment

**Severity:** HIGH
**Source:** `findings/04-a11y-parity.md` F-016

### Problem
Portal: sidebar with 3 groups, 11 destinations (Training / Social / Account).
Mobile: 4-tab bottom nav (Analytics / Workouts / Insights / Settings) — no Social/Community at all.

### Resolution
**Decision required.** This is an IA design decision, not a mechanical fix. Two options:
- **Option A:** Mobile gains Social/Community — 5-tab bottom nav (Analytics / Workouts / Social / Insights / Settings) or a hub-and-spoke "More" menu like portal mobile.
- **Option B:** Portal collapses to a 4-tab bottom nav on mobile breakpoint matching mobile-app tabs (already partially via `MobileBottomNav.tsx`).

Cross-reference `phoenix-portal/audit/findings/02-portal-static.md F-015` (mobile-bottom-nav buries 7 of 11 destinations under "More") which is the symptom of this same IA mismatch.

### Both repos
Run an IA workshop. Don't fix piecemeal; converge first, implement after.

### Effort & sequence
- Effort: ≥ 2 days (workshop) + 2-5 days per repo (implementation)
- Sequence: workshop → both repos coordinate landing
- Tracking: portal P-H7 (mobile-nav drops items at <900px), mobile M-H16 (Routines tab hides bottom-nav) — adjacent symptoms

---

## §11 Doc-rot inventory (cross-repo)

This isn't a parity-coordination item per se — it's a list of documentation lies across both repos that should be cleaned up alongside the audit response.

| Doc | Claim | Reality | Cleanup action |
|-----|-------|---------|----------------|
| Parent `CLAUDE.md` Styling | Background `#0D0D0D` | Portal `#06060A`, Mobile `Slate-900 #0F172A` | Update after §9b lands; canonical = `#06060A` |
| Parent `CLAUDE.md` Styling | Animations `flame-flicker`, `ember-rise`, `phoenix-glow` | **None exist anywhere.** Portal has only `signal-pulse`. Mobile has unnamed Compose springs. | Delete fictional references; document `signal-pulse`; introduce a 4-token motion vocabulary if pursued |
| Parent `CLAUDE.md` | Phoenix Ember `#FF6B35` | True for portal; mobile uses `#FF9149` | Becomes accurate after §9a lands |
| `phoenix-portal/src/lib/colors.ts:VELOCITY_ZONES` | Implicit canonical | Doesn't match anywhere | Delete in §1 |
| `phoenix-portal/src/lib/vbt.ts:SIMPLIFIED_ZONES` doc-string | "matching mobile app classification" | Colors are wrong | Fix in §1 |
| `phoenix-portal/src/app/components/FAQ.tsx:73-85` | 2 tiers (Ember $15 / Inferno $25) | 4 tiers (FREE / EMBER $5 / FLAME $15 / INFERNO $25) | Fix in portal P-3 |
| `phoenix-portal/src/app/components/Goals.tsx:357` | "Phoenix and Elite subscribers" | No such tiers | Fix in portal P-2 |
| `phoenix-portal/src/app/components/Profile.tsx:73-77` PLAN_LABELS | Missing FLAME entry | FLAME = most popular tier | Fix in portal P-1 |
| `Project-Phoenix-MP/CLAUDE.md` Daem0n covenant | `project_path="C:/.../AndroidStudioProjects/Project-Phoenix-MP"` | Actual `C:/.../AndroidStudioProjects/Phoenix App Monorepo/Project-Phoenix-MP` | Update path |

---

## Sequencing summary across both repos

```
Day 1 (Wave 1 — Stop the bleeding):
  PORTAL: P-1, P-2, P-3, P-10, P-18 + §1 (velocity-zone flip)
  MOBILE: M-1, M-2, M-13, M-21, M-22 + §2 (cable A/B flip)
  COORDINATED: §1 portal flip is independent of mobile (mobile is reference);
               §2 mobile flip is independent of portal (portal is reference);
               both can land same day.

Days 2-7 (Wave 2 — A11y + safety + mockup landings):
  PORTAL: P-5 (mockup M-03 implementation), P-6 (BottomSheet a11y), P-15, P-16, P-17
  MOBILE: M-3, M-6, M-7, M-29 (mockup M-01 — BLE state machine), M-4, M-27 (mockup M-02 — RoutineEditor)

Days 8-14 (Wave 3 — State coverage + brand discipline):
  PORTAL: P-7 (<QueryStateBoundary> + 30-file migration), P-11 (Biome rule)
  MOBILE: M-8 (font_scale fix), M-10 (LocalReducedMotion), M-16 (loading state), M-17 (offline state)
  COORDINATED: §4 asymmetry threshold — both repos ship in same release. Coordinate.

Days 15+ (Wave 4 — Brand unification + missing surfaces):
  PORTAL: P-12 (FREE-tier dashboard), P-H15 (RpgAttributeCard), P-H16 (BadgesGrid),
          §3 per-cable disclosure, §5 theme toggle if pursued
  MOBILE: §9 brand unification multi-PR (a → h), §8 ConsistencyCalendar, §3 per-cable parity disclosure,
          §5 theme toggle 3-state

Days 30+ (Wave 5 — IA workshop + workflow consolidation):
  COORDINATED: §10 IA workshop, then both repos implement converged IA
  MOBILE: M-30 (consolidate exercise-config UIs), M-31 (single ExitWorkoutDialog),
          M-25 (Routines bottom-nav), M-23 (Insights heading), M-24 (light-theme audit/drop)
```

---

## How to keep these copies in sync

This document exists in both repos. To prevent drift:

1. Treat `phoenix-portal/audit/PARITY-COORDINATION.md` and `Project-Phoenix-MP/audit/PARITY-COORDINATION.md` as a **shared spec**.
2. Any change to one **must** be applied to the other in the same PR (or as a back-to-back PR pair).
3. When a parity item is closed (e.g., §1 ships in portal), update the section status in both copies.
4. Periodically run a `diff` between the two copies in CI or as a manual cross-check.

A future improvement: hoist this file into a shared `phoenix-shared-spec/` git submodule referenced by both repos. Out of scope for this audit response.

---

**End of parity coordination document.**
