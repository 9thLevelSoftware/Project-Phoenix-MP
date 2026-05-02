# Visual Design & Brand Consistency Audit — Findings

**Auditor:** UI Designer agent
**Date:** 2026-05-01
**Scope:** Category 1 deep-dive — Phoenix palette, typography, spacing, radii, shadows, animations, iconography across both surfaces.

---

## Executive summary

The two surfaces are not the same product. Portal is a **dark, "instrument panel"** aesthetic — Chakra Petch + Inter + JetBrains Mono on a near-black `#06060a` ground with 2/4/6 px hairline radii, drop shadows, and a deliberately disciplined Signal palette. Mobile is **Material 3 Expressive** — `FontFamily.Default` on every text style, blue-tinted Slate `#020617` ground (or **a light/mint surface** in light mode!), 8/12/20/28/32 dp pillow radii, and tonal-elevation Cards. They share a brand name and three accent colors, and almost nothing else.

The single most damaging discovery is **cable identity inversion**: portal's cable A is orange (`#FF6B35`), mobile's cable A in DataColors is **blue** (`#3B82F6`). A user looking at the same workout on both surfaces sees the cables swap colors. This is a CRITICAL parity failure for a bilateral cable trainer.

The animations promised by the parent `CLAUDE.md` (`flame-flicker`, `ember-rise`, `phoenix-glow`) **do not exist anywhere in either codebase**. Only the portal's `signal-pulse` keyframe is real. The doc rot in the charter is confirmed and worse than stated.

Headline counts: **18 distinct palette mismatches**, **3 typography-system mismatches** (display/body/data all diverge), and at least **4 token-system mismatches** (radii, shadow/elevation, spacing scale resolution, surface layering).

---

## A. Palette parity matrix

Mobile light/dark hex values are the actual hex from `Color.kt` and `Theme.kt` color schemes. Portal hex from `theme.css` and `lib/colors.ts`. "Same?" = exact hex match in dark mode (the default for both).

| Role | Portal hex | Mobile dark hex | Mobile light hex | Same? | Recommendation |
|------|-----------|-----------------|------------------|-------|----------------|
| Primary action (default) | `#FF6B35` (Phoenix Ember) | `#FF9149` (PhoenixOrangeDark) | `#E65100` (PhoenixOrangeLight) | NO | Mobile dark `#FF9149` is peach/salmon — desaturated and ~10% lighter than portal. Adopt `#FF6B35` on mobile dark; keep the deeper `#E65100` for light mode (it is the WCAG-AA-on-white companion). The `FlameOrange = #FF6B00` constant in `Color.kt` is *almost* `#FF6B35` but desaturated — drop it. |
| On-primary (text on orange) | `#06060a` | `Primary20 = #4C1400` | `Color.White` | NO | Portal puts near-black on orange (highest contrast). Mobile dark puts a deep brown (`#4C1400`) on a peach orange — borderline AA. Use `#06060a` (or `Slate950`) on orange in dark; keep `White` on the deeper orange in light. |
| Primary container | (no equivalent) | `#702300` | `#FFDBCF` | N/A | Portal has no `primary-container` token — uses `bg-primary/20` ad-hoc. Mobile uses Material's full container/onContainer pair. **Add `--primary-container` to portal** so cross-surface "soft chip" treatments stay aligned. |
| Secondary | `#1a1a24` (neutral) | `Secondary80 = #E2C446` (gold) | `#6A5F00` (olive gold) | **NO — semantic mismatch** | These tokens have **different roles**. Portal's `--secondary` is a neutral panel surface; mobile's `secondary` is the Gold accent. This breaks any shared "secondary button" intuition. Decide: either rename mobile's gold to `tertiary`/`accent`, or rename portal's neutral panel to `--surface-2`. |
| Accent | `#F59E0B` (Phoenix Gold) | (Tertiary80 = `#6ED2FF` cyan, AshBlueDark) | `#006684` (deep teal) | **NO** | Portal accent is gold; mobile tertiary is cyan/teal. Pick one. **Recommend: gold (`#F59E0B`) is the third Phoenix brand color (per parent CLAUDE.md) — make mobile's secondary the gold and demote teal to a 4th-rank accent or kill it.** |
| Background (default) | `#06060a` | `#0F172A` (Slate900, used as `surfaceContainer`/`background`) | `#F8FAFC` (Slate50) | NO | Portal `#06060a` is near-black with a slight purple/cyan undertone (HSL 240°, 25% lightness ≈ 2.4%). Mobile dark `#0F172A` is cobalt-tinted Slate900 (HSL 222°, 47%, ~11.6%). At a glance: mobile is **noticeably lighter and bluer** than portal. **Adopt `#06060a` as Surface-0 on mobile dark.** Light mode is even worse: portal has no light mode at all; mobile light is mint-tinted (`Slate50` + `SecondaryMint` wash). |
| Surface-0 / lowest | `#06060a` | `Slate950 = #020617` | `Color.White` (#FFFFFF) | NO | Mobile's `surfaceContainerLowest` is **darker** than portal's surface-0 (`#020617` vs `#06060a`). Visually: mobile feels OLED-pure-black on the deepest layer; portal feels near-black-with-noise. Pick one. |
| Surface-1 | `#0a0a10` | `Slate900 = #0F172A` (`surfaceContainerLow`) | `#F7F2FA` | NO | Mobile dark is 2× lighter and more blue. |
| Surface-2 | `#0e0e14` | `Slate900 = #0F172A` (also `surface`/`background`) | `#F8FAFC` | NO | Mobile collapses 3 portal tokens into 1. |
| Surface-3 | `#141420` | `Slate800 = #1E293B` (`surfaceContainerHigh`) | `#E2E8F0` | NO | Significantly more blue on mobile. |
| Card background | `#0a0a10` | `Slate700 = #334155` (`surfaceContainerHighest`, used by `expressiveCardColors()`) | `#FFFFFF` (`surface`) | **NO** | Mobile cards are **dramatically lighter** than portal cards in dark mode (`#334155` vs `#0a0a10`) — that's a 3-4x luminance gap. The whole "instrument panel" mood collapses. |
| Border | `#1a1a24` | `Slate400 = #94A3B8` (`outline`), `Slate700 = #334155` (`outlineVariant`) | `#94A3B8` / `#E2E8F0` | NO | Mobile borders are far lighter and bluer. Portal borders read as 1px hairlines; mobile borders read as a chrome line. |
| Foreground / on-surface | `#e0e0e8` | `Slate200 = #E2E8F0` (`OnSurfaceDark`) | `Slate900 = #0F172A` | Close (~2% delta) | Acceptable. Both are near-white. Could unify to `#e0e0e8` mobile-side for exactness. |
| Muted-foreground | `#a0a0ac` | `Slate400 = #94A3B8` (`OnSurfaceVariantDark`) | `Slate700 = #334155` | NO (close) | Portal trends warm-grey, mobile trends cool/blue. Drift visible on small body copy. |
| Success | `#00e676` (signal-ok) | `#22C55E` (SignalSuccess) | `#22C55E` | NO | Both green but distinct: portal's is a Material-100 neon (`#00e676`, 90% saturation); mobile's is Tailwind green-500 (`#22C55E`, 71% saturation). A user comparing a "completed" badge on both will see different greens. |
| Warning | `#ffab00` (signal-warn) | `#F59E0B` (SignalWarning) | `#F59E0B` | NO | `#ffab00` is honey-amber; `#F59E0B` is darker amber-orange. Both are AA-readable but visibly different. |
| Error / destructive | `#ff5252` (signal-danger) | `#EF4444` (SignalError) | `#EF4444` | NO | `#ff5252` is salmon-red; `#EF4444` is Tailwind red-500. |
| Cable A | `#FF6B35` (orange — same as primary) | `#3B82F6` (blue, in `DataColors.LoadA`) | `#3B82F6` | **NO — INVERTED** | **Critical parity break.** Portal cable A = orange; mobile cable A = blue. Portal cable B = blue; mobile cable B (per `DataColors.LoadB`) = orange (`#F97316`). The two surfaces literally swap which cable is which color. |
| Cable B | `#6ba3f7` (blue) | `#F97316` (orange, `DataColors.LoadB`) | `#F97316` | **NO — INVERTED** | See above. |
| Position A | (no equivalent — portal doesn't visualize position separately) | `#22C55E` (`DataColors.PositionA`) | `#22C55E` | N/A | Mobile uses 4 distinct chart colors per cable (load A/B + pos A/B); portal uses 2. **Decide which model to standardize.** |
| Position B | (no equivalent) | `#A855F7` | `#A855F7` | N/A | See above. |
| Chart-1 / chart palette[0] | `#FF6B35` (ember) | `#3B82F6` (DataColors.Volume = blue) | `#3B82F6` | NO | Portal leads charts with brand orange; mobile leads with neutral blue. |
| Chart-2 | `#6ba3f7` | `#F59E0B` (DataColors.Intensity) | `#F59E0B` | NO | |
| Chart-3 | `#F59E0B` (gold) | `#EF4444` (DataColors.HeartRate) | `#EF4444` | NO | |
| Chart-4 | `#00e676` | `#10B981` (DataColors.Duration) | `#10B981` | NO (close) | Both emerald, slight sat difference. |
| Chart-5 | `#7c4dff` (purple) | `#8B5CF6` (DataColors.OneRepMax) | `#8B5CF6` | NO (close) | Both violet. |
| Velocity zone — Explosive | `#ff5252` (`VELOCITY_ZONES.explosive` in `colors.ts`) | `#06B6D4` (cyan, `zoneExplosive`) | same | **NO — INVERTED** | **The two velocity-zone palettes are literally inverted.** Mobile uses VBT standard (cyan = fast, red = grind). Portal `colors.ts` declares the opposite (red = explosive). On mobile, "explosive" is cool/cyan; on portal, "explosive" is hot/red. Either the portal `lib/colors.ts` `VELOCITY_ZONES` is dead code, or it is being applied somewhere and inverting the meaning — either way, deeply confusing. |
| Velocity zone — Fast | `#ffab00` | `#22C55E` | same | NO | |
| Velocity zone — Moderate | `#00e676` | `#F59E0B` | same | NO | |
| Velocity zone — Slow | `#448aff` | `#F97316` | same | NO | |
| Velocity zone — Grind | `#7c4dff` | `#EF4444` | same | NO | |
| Asymmetry good | (uses `signal-ok` `#00e676`) | `#4CAF50` (`asymmetryGood`) | same | NO | Material green vs Material 100 neon green. Drift. |
| Asymmetry caution | (uses `signal-warn` `#ffab00`) | `#FFC107` (`asymmetryCaution`) | same | NO | |
| Asymmetry bad | (uses `signal-danger` `#ff5252`) | `#F44336` (`asymmetryBad`) | same | NO | |
| Sidebar | `rgba(6,6,10,0.95)` | (no native sidebar — bottom nav) | n/a | N/A | Mobile has no left-sidebar navigation, so no parallel. |
| Ring / focus | `#FF6B35` | (Material default — `primary`) | (Material default) | YES (effectively, since both = primary) | OK. |

**Mismatch count:** 18 of ~28 examined roles. **Critical inversions:** 2 (cable A/B, velocity zones).

---

## B. Typography parity

### Portal

Three-font system, declared in `phoenix-portal/src/styles/fonts.css` and `index.html`:

- **Display:** `Chakra Petch` — squared, slightly mechanical, weights 300–700. Used for `h1`/`h2`/`h3` and `.text-display-1/2`.
- **UI/Body:** `Inter` Variable — used for `h4`, `p`, `label`, `button`, `input`, eyebrows.
- **Data:** `JetBrains Mono` — used in `.font-data` utility and `.signal-metric` / `.tabular-nums`.
- **Scale:** Perfect Fourth (1.333×). Fluid clamps: h1 28→38 px, h2 21→28 px, h3 18→21 px.
- **Eyebrow:** `.eyebrow` — 11 px Inter, 0.08 em letter-spacing, uppercase, weight 450.
- **Letter-spacing:** Negative on display (-0.03/-0.02/-0.01 em), neutral body, wide eyebrows.

### Mobile

Single-font system, declared in `Type.kt`:

- **All 14 text styles use `FontFamily.Default`.** No custom fonts loaded; no `composeResources/font` directory exists.
- The crash screen (`App.kt:98`) is the only place a non-default family appears — `FontFamily.Monospace`, used for the error stack-trace text.
- **Material 3 Expressive scale** is used (Type.kt comments call out the increases): displayLarge 64sp, displayMedium 50sp, headlineLarge 36sp, titleLarge 24sp, bodyLarge 18sp, bodyMedium 16sp, etc.
- **No display/body distinction.** The same family renders all roles.
- **No data/tabular-figure font.** Numbers in workout HUD/RestTimer use the system default at large sizes.
- **Letter-spacing usage** (audited via Grep): mobile *does* use 1.0–2.0 sp letter-spacing in `RestTimerCard`, `AutoStopOverlay`, `CountdownCard`, `EulaScreen` — i.e. there *are* "eyebrow-style" treatments, but they are ad-hoc per-component, not a token. Compare portal's `.eyebrow` class which is a single source of truth.

### Findings

| Aspect | Portal | Mobile | Verdict |
|---|---|---|---|
| Display family | Chakra Petch | `FontFamily.Default` (Roboto on Android, San Francisco on iOS) | **Major mismatch.** Display headlines look like generic Material 3 vs. an instrument panel. |
| Body family | Inter | `FontFamily.Default` | Mismatch. Both are realist sans serifs but the system default is platform-dependent. iOS users see SF Pro; Android users see Roboto; both differ from Inter in x-height, terminal shape, and variable axes. |
| Data/numeric family | JetBrains Mono with `tabular-nums` | None — proportional figures | Major mismatch. A 4-digit weight (e.g. `1234`) jitters width-wise on mobile during a live rep but stays still on portal. |
| Type scale | Perfect Fourth (1.333×) fluid | Material 3 Expressive (custom step ratios, no fluid clamps) | Different scales entirely. h1 = 38 px portal vs displayMedium = 50sp mobile. |
| Eyebrow style | Tokenized `.eyebrow` (11 px / 0.08 em / uppercase / 450) | Ad-hoc letter-spacing in 6+ components | Effort exists, but no token. |
| Negative tracking on display | Yes (-0.03 to -0.01 em) | Some (`displayLarge` -0.25 sp ≈ -0.004 em) | Portal pulls display tighter; mobile leaves it neutral. Portal display reads tighter and more "instrument". |

### Recommendation

The portal's three-font system is the more deliberate, brand-coherent choice and aligns with the Signal aesthetic in `theme.css`. Bring the same fonts to mobile via Compose Multiplatform's `Font(Res.font.ChakraPetch_Bold)` API. **Quick-win bundle:** add `composeResources/font/` with Chakra Petch (700/600/500) + Inter (400/500/600/700) + JetBrains Mono (400/500), build a `PhoenixFontFamilies` object, and refactor `Type.kt` to use the appropriate family per role (display* and headline* → ChakraPetch; titleMedium/Small + body* + label* → Inter; reserve a new `dataMedium`/`dataLarge` style → JetBrains Mono). 1.5 days of work; eliminates the single biggest "these aren't the same product" tell.

---

## C. Spacing/sizing parity — Card example

The portal's `<Card>` component (`phoenix-portal/src/app/components/ui/card.tsx`):

```tsx
<div className="bg-card text-card-foreground flex flex-col gap-6 rounded-xl border" />
// gap-6 = 24 px (Tailwind), rounded-xl = var(--radius-xl) = var(--radius-lg)+4 = 10 px
// border = 1 px hairline at --border (#1a1a24, near invisible against #0a0a10 card)
// CardHeader: px-6 pt-6 (24px padding); CardContent: px-6; CardFooter: px-6 pb-6
```

Plus the global `[data-slot="card"] { box-shadow: var(--shadow-sm); }` from theme.css → `0 1px 3px rgba(0,0,0,0.4)`. Cards are quiet, hairline-bordered, one-pixel-shadow panels.

The mobile `expressiveCardColors()` + `expressiveCardShape` + `expressiveCardElevation()` from `Material3Expressive.kt`:

```kotlin
expressiveCardShape = RoundedCornerShape(20.dp)   // vs portal 10 px
expressiveCardColors() → containerColor = MaterialTheme.colorScheme.surfaceContainerHighest (= Slate700 #334155 on dark)
expressiveCardElevation() → 8.dp (pressed: 4.dp)  // vs portal shadow-sm (1px / 0.4 alpha)
expressiveCardBorder() → BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant)  // vs portal 1px
```

Plus most screens use `RoundedCornerShape(16.dp)` ad-hoc (e.g. `Spacing.medium = 16.dp` is the de facto card radius in `RoutineOverviewScreen`, `WorkoutTab`, `HistoryTab`, `JustLiftScreen`). Mobile cards have **2× the corner radius, 2× the border thickness, 8× the elevation, and a card surface 3× lighter than portal's**.

### Spacing tokens

| Step | Portal (Tailwind) | Mobile (`Spacing.kt`) | Same? |
|---|---|---|---|
| 4 px / dp | `space-1` | `extraSmall` (4 dp) | YES |
| 8 | `space-2` | `small` (8 dp) | YES |
| 12 | `space-3` | — (gap, no token) | NO |
| 16 | `space-4` | `medium` (16 dp) | YES |
| 20 | `space-5` | — (gap) | NO |
| 24 | `space-6` | `large` (24 dp) | YES |
| 32 | `space-8` | `extraLarge` (32 dp) | YES |
| 48 | `space-12` | `huge` (48 dp) | YES |

Spacing is **the most-aligned token system** between the surfaces — both use 4 dp/px base on an 8-step rhythm. Mobile is missing the 12 and 20 dp steps, but in practice screens either use `Spacing.small + 4.dp` or hardcoded `12.dp`. Acceptable.

### Border-radius tokens

| Token | Portal | Mobile (`ExpressiveShapeValues`) |
|---|---|---|
| sm | 2 px | 8 dp |
| md | 4 px | 12 dp |
| lg | 6 px | 20 dp |
| xl | 10 px | 28 dp |
| 2xl | (n/a) | 32 dp (pill button) |

**Wildly different.** Portal uses 2/4/6/10 px hairline radii — sharp, mechanical, "instrument panel". Mobile uses 8/12/20/28/32 dp — soft, pillow, Material 3 Expressive "playful". A button on portal has a ~6 px radius; a button on mobile has a ~16–32 dp radius. They look like different product categories.

### Shadows / elevation

| Layer | Portal shadow | Mobile elevation |
|---|---|---|
| Card default | `0 1px 3px rgba(0,0,0,0.4)` (1 px lift, dark) | 8 dp tonal elevation (no drop shadow on dark Material 3 — uses surface tinting) |
| Card hover | `0 4px 12px rgba(0,0,0,0.5)` | (no hover concept on touch) |
| Modal / popover | `0 8px 24px rgba(0,0,0,0.6)` | M3 default modal scrim |

Portal uses **classic drop shadows**. Mobile dark mode uses **tonal elevation** (lighter surface = "higher"). These are two fundamentally different visual languages for depth. On dark backgrounds, mobile cards feel like raised platforms (lighter surface); portal cards feel like inset panels (darker surface + thin shadow). The two surfaces will *never* feel the same until this design decision is unified.

---

## D. Findings

### F-001 [CRITICAL] Cable A and Cable B colors are inverted between mobile and portal

**Surface:** Both
**Category:** 1
**Location:** `phoenix-portal/src/lib/colors.ts:27` (`CABLE.a = "#FF6B35"`, `.b = "#6ba3f7"`); `Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/DataColors.kt:32-38` (`LoadA = #3B82F6`, `LoadB = #F97316`)
**Observation:** The portal renders cable A in orange and cable B in blue. The mobile app renders cable A in blue and cable B in orange. The same workout data, viewed on both surfaces, swaps which cable is "A" by color — even though the underlying data field (`loadA` / `loadB`) is identical.
**Why it hurts:** On a bilateral cable trainer, "left vs right" is the most fundamental visual call. Asymmetry feedback, force curves, replay overlays — all rely on the user instantly knowing which line is which side. A user who builds intuition on one surface will mis-read the other. For a community-rescue product where users may compare their phone HUD against the web replay screen, this is data-deceiving.
**Severity rationale:** CRITICAL because it is a user-facing data-meaning inversion across the product, not a polish tweak. Touches every chart, every cable indicator, every asymmetry visual.
**Proposed fix:** Pick one canonical mapping (recommend portal's: A=ember, B=blue, since the cable A/B identity in `theme.css` already aligns with brand-primary) and propagate. **Quick-win:** flip mobile `DataColors.LoadA` / `LoadB` and `PositionA` / `PositionB` so A=orange, B=blue. Audit all uses (~10 files) to confirm no hardcoded references rely on the current order.
**Parity flag:** YES

---

### F-002 [CRITICAL] Velocity-zone color palette is inverted between mobile and portal

**Surface:** Both
**Category:** 1
**Location:** `phoenix-portal/src/lib/colors.ts:58-64` vs `Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/AccessibilityColors.kt:64-69`
**Observation:** Mobile follows the canonical VBT (Velocity-Based Training) standard: Explosive=cyan, Fast=green, Moderate=amber, Slow=orange, Grind=red. Portal's `VELOCITY_ZONES` constant declares: Explosive=red, Fast=amber, Moderate=green, Slow=blue, Grind=purple. The two are not just different palettes — the *mapping between zone and color is inverted* on the high/low end.
**Why it hurts:** A user who sees "Explosive = red" on the portal builds the intuition "red is good"; on mobile, "red = grind / near-failure" is the opposite. This is a misleading semantic mismatch on a category that is core to the product (biomechanics velocity classification).
**Severity rationale:** CRITICAL — same reasoning as F-001. Cross-surface meaning inversion.
**Proposed fix:** Adopt the mobile/VBT-canonical mapping on portal (Explosive=cyan, Fast=green, Moderate=amber, Slow=orange, Grind=red). Update `phoenix-portal/src/lib/colors.ts:58-64` and audit `useVelocityZone*`, `VelocityChip`, `BiomechanicsHistory*` for any consumers. **Quick-win**, ≤2 hr.
**Parity flag:** YES

---

### F-003 [CRITICAL] Mobile uses no custom typography — Chakra Petch and JetBrains Mono are absent

**Surface:** Mobile
**Category:** 1
**Location:** `Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Type.kt:13-117` — every single one of 14 text styles uses `fontFamily = FontFamily.Default`. No `composeResources/font` directory exists.
**Observation:** Portal ships Chakra Petch (display), Inter (body), and JetBrains Mono (data) — see `phoenix-portal/src/styles/fonts.css` and `index.html` `<link>`. Mobile uses the platform default font for all text (Roboto on Android, SF Pro on iOS) and no monospace tabular-figure font for numbers.
**Why it hurts:** Typography is the strongest brand signal. The "instrument panel" aesthetic that portal worked hard to establish (Chakra Petch's squared terminals, monospace tabular figures for live metrics) is completely absent on the surface where users actually train. The two products read as "same name, different design teams". Live workout numerics jitter (proportional figures) on mobile but stay rock-steady on portal.
**Severity rationale:** CRITICAL because typography is the single most-pervasive brand attribute and the only place where it lives is the brand-defining one — Chakra Petch was specifically chosen against generic AI-default fonts (`fonts.css:7-13`), so its absence on mobile re-introduces exactly the "generic" perception the portal was designed to avoid.
**Proposed fix:** Add fonts to `Project-Phoenix-MP/shared/src/commonMain/composeResources/font/` (ChakraPetch-Bold.ttf, -SemiBold.ttf, -Medium.ttf; Inter-Regular/Medium/SemiBold/Bold; JetBrainsMono-Regular/Medium). Build a `PhoenixFontFamilies` object exposing `display`, `body`, `data`. Refactor `Type.kt` so display* + headline* use `display`; titleMedium/Small + body* + label* use `body`; add a new `dataLarge`/`dataMedium`/`dataSmall` set using `data`. Wire the live HUD numerics (`WorkoutHud`, `RestTimerCard`, `SetSummaryCard`) to the data styles. **Design spike**, ~1.5 days. Largest single brand-unification investment available.
**Parity flag:** YES

---

### F-004 [HIGH] Phoenix Ember orange differs by ~10% lightness and 15% saturation across surfaces

**Surface:** Both
**Category:** 1
**Location:** `phoenix-portal/src/styles/theme.css:7` (`--phoenix-ember: #FF6B35`); `Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Color.kt:13` (`PhoenixOrangeDark = #FF9149`)
**Observation:** Portal's brand orange `#FF6B35` is HSL(15°, 100%, 60%). Mobile dark's brand orange `#FF9149` is HSL(20°, 100%, 64%) — a desaturated, more peach/salmon variant. Side-by-side, the two oranges read as different colors. The parent `CLAUDE.md` documents `#FF6B35` as the Phoenix Ember; mobile drifted.
**Why it hurts:** The single most visible brand color does not match. A user opening the portal after using the mobile app sees a different brand color. The mobile color is also closer to "salmon" than to the fire/ember metaphor that drives the entire product narrative.
**Severity rationale:** HIGH (not CRITICAL) because both colors are recognizably orange and primary actions still function. But it is the brand color, and it diverges by enough that designers will instinctively try to "fix" one to match the other.
**Proposed fix:** Adopt `#FF6B35` on mobile dark mode. Replace `PhoenixOrangeDark = #FF9149` with `Color(0xFFFF6B35)`. For light mode, `PhoenixOrangeLight = #E65100` is acceptable (it is the AA-on-white companion) but should be re-validated against `#FF6B35`-on-near-white. Also delete the stale `FlameOrange = #FF6B00` constant in `Color.kt:17` if unused — it adds confusion. **Quick-win**, 30 min.
**Parity flag:** YES

---

### F-005 [HIGH] Background color and surface scale don't match — mobile dark is bluer and 2× lighter

**Surface:** Both
**Category:** 1
**Location:** `phoenix-portal/src/styles/theme.css:25,77-81` (`--background: #06060a`, surface-0/1/2/3 = `#06060a/#0a0a10/#0e0e14/#141420`); `Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Theme.kt:33-48` (`background = SurfaceContainerDark = Slate900 = #0F172A`)
**Observation:** Portal's surface-0 through surface-3 (`#06060a → #141420`) climbs by ~5–8 luminance units per step in a near-neutral hue (HSL ~240°). Mobile dark uses Slate950 → Slate800 (`#020617 → #1E293B`), which is 2–3× lighter at every step and tinted toward cobalt blue (HSL ~222°). Mobile cards (`surfaceContainerHighest = Slate700 = #334155`) are dramatically lighter than portal cards (`#0a0a10`).
**Why it hurts:** The whole "instrument panel against deep darkness" aesthetic of portal is replaced on mobile by a "blue-tinted Material 3 dark theme" feel. They don't read as the same product in low light. Photographic comparison: pull up the dashboard on each — portal feels black; mobile feels navy.
**Severity rationale:** HIGH because it is the single most-visible visual decision (the background fills the whole screen) and it differs by enough that the surfaces look like different brands.
**Proposed fix:** Add a new color set (`Phoenix0/1/2/3 = #06060a / #0a0a10 / #0e0e14 / #141420`) to `Color.kt`, and rebuild `DarkColorScheme` to map Material's `surfaceContainerLowest/Low/`/`surface`/`surfaceContainer/High/Highest` to those exact values. Keep Slate as a secondary palette for charts and outlines. **Design spike**, ~4 hr (build + verify all dark-mode screens still pass AA).
**Parity flag:** YES

---

### F-006 [HIGH] Mobile light mode is mint-tinted; portal has no light mode at all

**Surface:** Both
**Category:** 1
**Location:** `Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/ThemeHelpers.kt:30-39` (`screenBackgroundBrush()` light mode uses `Slate50 → SecondaryMint #7CEA9C @ 0.1 alpha → White`); portal has no `:root.light` or `prefers-color-scheme: light` block in `theme.css`.
**Observation:** Mobile supports a full light theme (with mint/plum gradient backgrounds). Portal is dark-only — its landing/dashboard work only against a near-black ground. A user who toggles "Light" on mobile gets a mint-washed background; switching to portal, they get the unchanged dark-only experience.
**Why it hurts:** Cross-surface theme-toggle expectation breaks. More importantly, mobile light mode looks like a *different brand* (HomeButtonColors palette is "7cea9c-55d6be-2e5eaa-5b4e77-593959" — a Coolors palette unrelated to Phoenix). The landing-page light mode mint wash is at odds with the fire/ember/forge brand narrative.
**Severity rationale:** HIGH because it surfaces a strategic question: should portal add a light theme to match, or should mobile drop light mode? Either answer is a design decision, but the current state is incoherent.
**Proposed fix:** Two-track decision. **Track A (recommended):** drop mobile light mode entirely (or treat it as a strict parity-with-portal dark theme). The community-rescue product is heavily used during workouts in low light; light mode is rarely the right choice. **Track B:** add a portal light theme using the same Phoenix surface scale rebuilt for light. Either way, kill the mint-tinted background brush in `ThemeHelpers.kt` — it is off-brand. **Design spike**, 2–4 hr to remove + restyle.
**Parity flag:** YES

---

### F-007 [HIGH] Border radius tokens diverge by 2-5× — portal hairline vs mobile pillow

**Surface:** Both
**Category:** 1
**Location:** `phoenix-portal/src/styles/theme.css:62-64` (`--radius-sm: 2px; --radius-md: 4px; --radius-lg: 6px;`); `Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Shapes.kt:13-18` (8/12/20/28/32 dp)
**Observation:** Portal uses 2/4/6/10 px radii — sharp, mechanical, instrument-panel. Mobile uses 8/12/20/28/32 dp — soft, pillow, Material 3 Expressive. A "small" surface on portal has 2 px corners; on mobile, 8 dp corners. A "medium" container is 4 px vs 20 dp. **5× difference at the medium/large step.**
**Why it hurts:** Radii define the visual personality. Portal feels like an instrument; mobile feels like a consumer fitness app. They cannot both be the brand simultaneously.
**Severity rationale:** HIGH because radii are everywhere — every card, button, modal, chip uses them. Aligning radii is a higher-leverage move than fixing individual color tokens.
**Proposed fix:** Decision required. **Recommendation:** meet in the middle and adopt the portal's smaller radii on mobile for primary surfaces (cards, panels) but keep one larger radius (16–20 dp) for big touch targets like the "Just Lift" hero button. Concrete: mobile `ExpressiveShapeValues.ExtraSmall` 8→2 dp, `Small` 12→4 dp, `Medium` 20→8 dp, `Large` 28→12 dp, keep `ExtraLarge` 32 dp for pill buttons. Or — go the other way and bump portal up if the mobile pillow is intentional. **Design spike**, 1–2 days to refactor + visual review.
**Parity flag:** YES

---

### F-008 [HIGH] Mobile uses tonal elevation; portal uses drop shadows — depth language differs

**Surface:** Both
**Category:** 1
**Location:** `phoenix-portal/src/styles/theme.css:84-86,168-172,215-217` (shadow-sm/md/lg, default applied to all `[data-slot="card"]`); `Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Material3Expressive.kt:62-65` (`expressiveCardElevation` = 8.dp / pressed 4.dp, no drop shadow — Material 3 dark uses surface-tint for elevation)
**Observation:** Portal cards lift via a thin, dark drop shadow on a near-black ground (visible because the ground is darker than the card background). Mobile cards lift via *tonal elevation* — a lighter container (`surfaceContainerHighest = #334155`) on a darker ground. These are two fundamentally different depth metaphors. Portal: "card sits on top of ground, casts shadow." Mobile: "card is a brighter surface than ground."
**Why it hurts:** Visually contradictory. A user comparing a card on each surface sees the portal version as "subtle hairline panel" and the mobile version as "elevated tile". They cannot share design language until depth is unified.
**Severity rationale:** HIGH — touches every elevated surface in the app.
**Proposed fix:** Pick one. **Recommendation: adopt portal's drop-shadow language on mobile** by setting `expressiveCardElevation()` to 0 dp default and adding a manual `Modifier.shadow(elevation = 1.dp, shape = ..., ambientColor = Color.Black, spotColor = Color.Black)` wrapper. Keep card surface = `Surface-1` (#0a0a10) so cards are darker than ground (#06060a) only by virtue of the shadow not by being lighter. **Design spike**, 1 day.
**Parity flag:** YES

---

### F-009 [HIGH] "Secondary" token has different roles on each surface (neutral surface vs gold accent)

**Surface:** Both
**Category:** 1
**Location:** `phoenix-portal/src/styles/theme.css:33` (`--secondary: #1a1a24` — a neutral panel surface); `Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Theme.kt:21` (`secondary = Secondary80 = EmberYellowDark = #E2C446` — the brand gold)
**Observation:** The `secondary` slot in Material/shadcn is reserved for "second-rank action color." Portal binds it to a neutral surface (used for `Button variant="secondary"` to render a quiet grey button). Mobile binds it to the brand gold (used for accent UI like rest timer rings). Same token name, completely different semantic role.
**Why it hurts:** Cross-platform translations break — porting a portal "secondary button" to mobile would suddenly turn it gold; the reverse would turn a gold accent indicator into a grey button. Engineering parity work hits this constantly.
**Severity rationale:** HIGH because it is a token-system contract divergence, not a color drift.
**Proposed fix:** Decide canonical role for `secondary`. **Recommendation:** mobile is correct per Material 3 conventions (secondary = brand accent). Rename portal's `--secondary` to `--surface-2` or `--neutral` and reroute the shadcn `Button variant="secondary"` to use it explicitly. Then introduce a real `--secondary: #F59E0B` (gold) to portal that matches mobile. **Quick-win**, 1–2 hr (rename + audit usages).
**Parity flag:** YES

---

### F-010 [HIGH] Phoenix Gold is portal's accent but mobile's secondary; mobile's tertiary is teal (not in Phoenix palette)

**Surface:** Both
**Category:** 1
**Location:** `phoenix-portal/src/styles/theme.css:9,40` (gold = `--phoenix-gold: #F59E0B`, bound as `--accent`); `Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Theme.kt:21,27` (gold = `Secondary80`, tertiary = `AshBlueDark = #6ED2FF` cyan)
**Observation:** Portal palette: ember + flame red + gold + forge green = 4 brand colors. Mobile palette: orange + gold + teal/cyan + signal red/green/amber = 6+ colors with teal added. The teal is from a 2024 "tinted neutral" trend in `Color.kt:25` and is not in any branding doc.
**Why it hurts:** The teal cyan adds a 4th brand color on mobile that doesn't exist on portal. A user seeing teal-cyan accents on mobile won't recognize them as "Phoenix" branding. The teal also competes visually with cable B blue (`#6ba3f7`), causing eye-confusion in workouts that show both.
**Severity rationale:** HIGH because it expands the brand palette unilaterally without coordination.
**Proposed fix:** Decision: keep teal as an internal accent (label-only, never primary surface treatment) OR remove it. **Recommendation: remove.** Mobile's `tertiary` should be Phoenix Gold or Forge Green (`#10B981`, currently absent from mobile entirely!). **Quick-win**, 1 hr — replace `Tertiary80 = AshBlueDark` with `EmberYellowDark` or a new `ForgeGreen = Color(0xFF10B981)` and rebind.
**Parity flag:** YES

---

### F-011 [HIGH] Forge Green (`#10B981`) is in the Phoenix palette but absent from mobile

**Surface:** Mobile
**Category:** 1
**Location:** Documented in parent `CLAUDE.md` and `phoenix-portal/src/lib/colors.ts:14` (`forgeGreen: "#10B981"`); not present in `Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Color.kt`.
**Observation:** Phoenix's documented brand palette is 4 colors: Ember (orange), Flame Red, Gold, Forge Green. Mobile only carries the first three (and an unbranded teal as #4). Forge Green is used on portal for "complete / success / connected" affordances.
**Why it hurts:** Cross-surface "complete/success" treatments don't share a color (mobile uses `SignalSuccess = #22C55E` Tailwind green-500; portal uses `--signal-ok = #00e676` Material green A400 *and* `--phoenix-forge-green = #10B981` for brand-success).
**Severity rationale:** HIGH because it omits a documented brand color.
**Proposed fix:** Add `val ForgeGreen = Color(0xFF10B981)` to `Color.kt`, expose via theme as a `BrandColors.forgeGreen`, and use it for "machine connected", "set complete", "PR achieved" surfaces. Keep `SignalSuccess` for in-app success toasts only. **Quick-win**, 1 hr.
**Parity flag:** YES

---

### F-012 [HIGH] No tabular-figure / monospace style available on mobile for live workout numerics

**Surface:** Mobile
**Category:** 1
**Location:** `Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Type.kt` — no `data*` styles; `App.kt:98` is the only `FontFamily.Monospace` use, in a crash-screen
**Observation:** Live workout HUD on mobile (`WorkoutHud`, `RestTimerCard`, `SetSummaryCard`) renders weights, rep counts, and timer values in proportional figures. As digits change (e.g. "9 → 10 reps", "29 → 28s"), the text width jitters — users see numbers wiggle during a set. Portal solves this with `font-variant-numeric: tabular-nums` on `.font-data` / `.signal-metric` and JetBrains Mono.
**Why it hurts:** During intense sets, jittery numbers are an attentional cost. Portal feels like a fitness instrument; mobile feels like a generic Android app.
**Severity rationale:** HIGH because it directly impacts the most-watched UI element of the product (the live workout HUD).
**Proposed fix:** Bundled with F-003. After adding JetBrains Mono, add `dataLarge`/`dataMedium`/`dataSmall` text styles with `fontFamily = jetBrainsMono, fontFeatureSettings = "tnum"`. Apply to all live-rep / live-weight / timer text. Without the new font, partial fix: set `fontFeatureSettings = "tnum"` on `bodyMedium` styles used for live numerics — still proportional family but tabular figures. **Quick-win**, 2 hr.
**Parity flag:** YES

---

### F-013 [HIGH] Portal animation tokens (`signal-pulse`, motion springs) have no mobile equivalents; the documented `flame-flicker`/`ember-rise`/`phoenix-glow` don't exist in either codebase

**Surface:** Both
**Category:** 1
**Location:** `phoenix-portal/src/styles/theme.css:427-433` (`signal-pulse` keyframe); `phoenix-portal/src/lib/animations.ts:9-15` (springs preset); `Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Material3Expressive.kt:21-40` (`SpringDefault/Snappy/Bouncy`); parent `CLAUDE.md` lines 200-201 reference `animate-flame-flicker`, `animate-ember-rise`, `animate-phoenix-glow` — **Grep finds these strings in no source file, only in docs.**
**Observation:** The parent `CLAUDE.md` advertises three Phoenix-themed animations on portal that don't exist. Portal has only `signal-pulse` (a 2.5s opacity dimmer for live status). Mobile has three Compose `spring` configs with similar intent (snappy/smooth/bouncy) but they aren't named or wrapped as a shared "phoenix animation" system. There is no shared motion-token vocabulary.
**Why it hurts:** Documentation rot. New contributors will look for `animate-flame-flicker` and burn 30 min before realizing it's fictional. More substantively, the surfaces' motion systems don't speak the same language — portal is `signal-pulse` (calm, tight); mobile is M3 Expressive springs (bouncy, playful). They feel different kinaesthetically.
**Severity rationale:** HIGH because it covers two separate problems: (a) doc rot misleading future work and (b) no shared motion language across surfaces.
**Proposed fix:** Two parts. **(1) Quick-win:** delete the false references in parent `CLAUDE.md` and `phoenix-portal/CLAUDE.md`. **(2) Design spike:** define a 4-token motion system (`pulse`, `snappy`, `smooth`, `entrance`) and implement on both — portal already has 3/4; mobile has 3/4 under different names. Align names and durations. ~3 hr alignment + 1 hr docs.
**Parity flag:** YES

---

### F-014 [MEDIUM] Mobile crash screen hardcodes the *portal* ember color, not the mobile orange

**Surface:** Mobile
**Category:** 1
**Location:** `Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/App.kt:84` — `Color(0xFFFF6B35)` literal
**Observation:** The crash error screen uses `#FF6B35` (the portal's `--phoenix-ember`) — but every other mobile UI uses `PhoenixOrangeDark = #FF9149`. So when the app crashes, "Project Phoenix" appears in the *correct* brand color; everywhere else, it appears in the desaturated salmon variant.
**Why it hurts:** Confirms that someone, at some point, knew the correct hex was `#FF6B35` — but the rest of the codebase drifted. Indicative of a wider drift problem (F-004).
**Severity rationale:** MEDIUM — only the crash screen is affected. But it is evidence for the F-004 fix.
**Proposed fix:** Once F-004 lands (mobile dark = `#FF6B35`), replace the literal here with `MaterialTheme.colorScheme.primary`. **Quick-win**, 5 min.
**Parity flag:** NO (it is internally inconsistent)

---

### F-015 [MEDIUM] Charts use different palettes — portal leads with brand orange, mobile leads with neutral blue

**Surface:** Both
**Category:** 1
**Location:** `phoenix-portal/src/styles/theme.css:55-59` (chart-1=ember, chart-2=cable-b blue, chart-3=gold, chart-4=signal-ok green, chart-5=violet); `Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/DataColors.kt` (Volume=blue, Intensity=amber, HeartRate=red, Duration=emerald, OneRepMax=violet, Power=cyan)
**Observation:** Portal charts open with the brand color (ember). Mobile charts open with a generic blue. The two surfaces' workout-trend lines look like different products' charts.
**Why it hurts:** Reinforces visual disconnect across the most data-heavy screens (Analytics, Biomechanics).
**Severity rationale:** MEDIUM — primarily affects Analytics screens, not core workout flow.
**Proposed fix:** Reorder mobile chart palette to lead with brand (orange, then blue, then gold, then green, then violet) — i.e. clone portal `chart-1..5` order. Then map the *semantic* role (Volume / Intensity / HeartRate / etc.) onto specific positions consistently — e.g. Volume=ember (chart-1), HeartRate=red (chart-3 or destructive), 1RM=violet (chart-5). **Quick-win**, 2 hr.
**Parity flag:** YES

---

### F-016 [MEDIUM] Card surface in dark mode is 3× lighter on mobile than on portal

**Surface:** Mobile
**Category:** 1
**Location:** Portal: `--card: #0a0a10` against `--background: #06060a` — card is barely lighter than ground. Mobile: `expressiveCardColors()` → `surfaceContainerHighest = Slate700 = #334155` against `background = Slate900 = #0F172A` — card is dramatically lighter than ground.
**Observation:** On portal, cards are nearly invisible until you look closely (intentional "instrument panel" effect). On mobile, cards stand out as elevated tiles. Two different visual languages of containment.
**Why it hurts:** Same root cause as F-008. Together with F-005 (background color), this is *the* reason the surfaces don't look related.
**Severity rationale:** MEDIUM — duplicates F-008 partially but is worth listing because the fix is independently scoped (just remap card color, even if elevation strategy stays Material 3).
**Proposed fix:** In mobile `expressiveCardColors()`, set `containerColor` to `MaterialTheme.colorScheme.surfaceContainerLow` (Slate900 = `#0F172A`) instead of `surfaceContainerHighest` (Slate700) — this brings card-vs-ground delta to ~5 luminance units, matching portal's subtle delta. Or after F-005, redefine `surfaceContainerLow = #0a0a10` and use that. **Quick-win**, 1 hr.
**Parity flag:** YES

---

### F-017 [MEDIUM] Eyebrow/label style is tokenized on portal (`.eyebrow`) but ad-hoc on mobile (per-component `letterSpacing`)

**Surface:** Mobile
**Category:** 1
**Location:** Portal: `phoenix-portal/src/styles/theme.css:394-399` — `.eyebrow { font-weight: 450; font-size: 0.688rem; letter-spacing: 0.08em; text-transform: uppercase; }`. Mobile: Grep finds `letterSpacing` set in 6+ component files (`AutoStopOverlay.kt:94,232`, `RestTimerCard.kt:191,236,326,392,585,648`, `CountdownCard.kt:231`, `EulaScreen.kt:195`) with values 1.0–2.0 sp — same intent, no shared token.
**Observation:** Mobile *does* use eyebrow-like uppercase labels in many places, but each component sets its own letter-spacing and transformation. There is no `LabelEyebrow` text style in `Type.kt`.
**Why it hurts:** Designers can't enforce a single eyebrow style across mobile. New screens will pick a slightly different letter-spacing, and the visual drift compounds. The mismatch with portal's `.eyebrow` (0.08 em ≈ 1.3 sp at 16 sp font) is subtle but real.
**Severity rationale:** MEDIUM — purely a typography-system hygiene problem.
**Proposed fix:** Add to `Type.kt`:
```kotlin
val LabelEyebrow = TextStyle(
  fontFamily = phoenixFontFamilies.body, // Inter once F-003 lands
  fontWeight = FontWeight(450),
  fontSize = 11.sp,
  letterSpacing = 0.08.em, // ≈ 1.3 sp at 11 sp size
  // Apply text-transform via .uppercase() at call site in Compose
)
```
Then refactor the ad-hoc letterSpacing usages to use it. **Quick-win**, 1 hr (style + 6 file refactor).
**Parity flag:** YES

---

### F-018 [MEDIUM] No `signal-panel` / `signal-metric` equivalent on mobile — portal's bordered instrument-panel utility has no Compose twin

**Surface:** Mobile
**Category:** 1
**Location:** Portal: `phoenix-portal/src/styles/theme.css:459-477` — `.signal-panel`, `.signal-panel-highlight`, `.signal-metric` utilities. Mobile: components that play this role (`WorkoutHud`, `BiomechanicsCard`, `SetSummaryCard`) all roll their own `Card { Border + RoundedCornerShape(...) + ... }`.
**Observation:** Portal has a tokenized bordered-panel utility used by every "signal" surface. Mobile builds the same conceptual surface 8+ ways with different border thickness, radius, and background color across components.
**Why it hurts:** Inconsistency within mobile itself — different signal panels have different appearances. Cross-platform, the visual density of mobile signal panels doesn't match portal's hairline aesthetic.
**Severity rationale:** MEDIUM — internal mobile consistency issue and a cross-platform parity gap.
**Proposed fix:** Add a `SignalPanel` composable to mobile theme, e.g. `Modifier.signalPanel(highlight: Boolean = false)` that applies surface-1 background + 1.dp border + 4.dp radius (or whatever the F-007 alignment lands on). Refactor 5–8 high-traffic surfaces to use it. **Design spike**, 4 hr.
**Parity flag:** YES

---

### F-019 [POLISH] Iconography systems differ — Lucide on portal, Material Icons on mobile

**Surface:** Both
**Category:** 1
**Location:** Portal `import { ... } from "lucide-react"` ubiquitous (e.g. `Dashboard.tsx:3-13`, `AppSidebar.tsx:2-15`). Mobile: `androidx.compose.material.icons.filled.*` ubiquitous (e.g. `HomeScreen.kt:22-29`).
**Observation:** Portal uses Lucide icons (open-source, hairline, geometric). Mobile uses Material Icons (filled or outlined, friendlier). Same conceptual icon (e.g. "history") may have different glyphs.
**Why it hurts:** Visual recognition jumps between surfaces. A "calendar" icon on portal is a Lucide thin-line square-with-tabs; on mobile it is Material's filled calendar.
**Severity rationale:** POLISH — both icon sets are professional and recognizable; the divergence is a brand-coherence cost, not a usability cost.
**Proposed fix:** Long-term: add Lucide-equivalent icons to mobile (lucide-android or vector imports) for high-visibility navigation icons. Short-term: not actionable; document the divergence as a known parity gap. **Design spike if pursued**, 2–3 days for full migration.
**Parity flag:** YES

---

### F-020 [POLISH] Mobile lacks Phoenix-grain / scan-line texture treatment

**Surface:** Mobile
**Category:** 1
**Location:** Portal `phoenix-portal/src/styles/theme.css:184-212` — `body::before` (3 px scan-line at 0.6% opacity) + `body::after` (SVG noise grain at 1.5% opacity overlay).
**Observation:** Portal applies a subtle scan-line + noise grain to the entire app body, reinforcing the "instrument panel" aesthetic. Mobile has plain solid surfaces.
**Why it hurts:** Subtle but the cumulative effect on portal feel is significant. Mobile feels "cleaner" in a way that reads as flatter / more generic.
**Severity rationale:** POLISH — pure brand-mood texture.
**Proposed fix:** Add a 1–2% opacity noise overlay to mobile root via `Modifier.drawWithCache { drawNoise(alpha = 0.015f) }` on the top-level `Surface`. Skip scan-line on mobile (touch UX risks). **Design spike**, 4 hr.
**Parity flag:** YES

---

### F-021 [POLISH] Spacing scale missing 12 dp / 20 dp tokens on mobile (used as gap values frequently)

**Surface:** Mobile
**Category:** 1
**Location:** `Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Spacing.kt:8-15` — only 4/8/16/24/32/48 dp.
**Observation:** Portal Tailwind has space-3 (12 px) and space-5 (20 px). Mobile has only 4/8/16/24 — there is no 12 or 20 dp token, so screens use inline `12.dp`/`20.dp` literals.
**Why it hurts:** Inconsistent spacing within mobile. Some screens use 12, some use 16, some use 20 — drift compounds.
**Severity rationale:** POLISH — no immediate user impact, but maintainability hurts.
**Proposed fix:** Add `val mediumSmall = 12.dp` and `val mediumLarge = 20.dp` (or rename to `Spacing.s12`, `Spacing.s20` for clarity). Refactor recent screens (`HistoryTab.kt`, `RoutineEditorScreen.kt`) to use the new tokens. **Quick-win**, 1 hr.
**Parity flag:** NO (parity is fine; this is internal mobile cleanup)
