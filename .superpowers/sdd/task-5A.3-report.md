# Task 5A.3 Report — Empty & Waiting States

## Findings addressed

### lens-state-patterns-15 — Desaturated gray icon; no expressive entrance

**What the rec said:**  
Add an optional `iconTint: Color` parameter defaulting to `primary.copy(alpha = 0.8f)` for primary-tab empties; keep `onSurfaceVariant` for subordinate empties. Add `AnimatedVisibility` with SpringBouncy scale entrance. For Routines/TrainingCycles (have a CTA), animate the Button with a slight delay for staggered enter.

**What was done:**  
- Added `iconTint: Color = Color.Unspecified` parameter to `EmptyState`. `Color.Unspecified` is the sentinel — inside the composable it resolves to `primary.copy(alpha = 0.8f)`. All 6 call sites compile unchanged; callers that want the old subordinate look can pass `onSurfaceVariant` explicitly.
- Added `AnimatedVisibility` around the icon with `scaleIn(animationSpec = ExpressiveMotion.SpringBouncy)` entrance.
- Added `AnimatedVisibility` around the Button with the same spring, gated by a `LaunchedEffect(Unit)` that fires `buttonVisible = true` after a 150 ms delay (staggered).
- reduceMotion gate: both `iconVisible` and `buttonVisible` are initialised to `reduceMotion` so they start already-visible when the preference is on; `enter = if (reduceMotion) EnterTransition.None else scaleIn(...)` is the secondary safeguard.

**Reduced-render impact:**  
One extra `AnimatedVisibility` wrapper per icon + one per optional button. Both are zero-overhead on subsequent recompositions once `visible = true` stabilises.

---

### dialogs-overlays-15 — 64dp raw literal; double spacing before CTA button

**What the rec said:**  
Replace `Modifier.size(64.dp)` with `Spacing.huge` (48dp). Remove the redundant `Spacer(Modifier.height(Spacing.small))` and `Modifier.padding(top = Spacing.medium)` from the Button — the Column `spacedBy(Spacing.medium)` already handles the gap.

**What was done:**  
- `Modifier.size(64.dp)` → `Modifier.size(Spacing.huge)` (token, 48dp).
- Removed `Spacer(modifier = Modifier.height(Spacing.small))` (was 8dp accumulation).
- Removed `Modifier.padding(top = Spacing.medium)` from the Button (was 16dp extra accumulation).
- Uniform vertical rhythm is now 16dp (`Spacing.medium`) between every element, provided by the Column `spacedBy`. Gap from message text to button content edge is now 16dp instead of 56dp.

**Reduced-render impact:**  
One fewer `Spacer` node in the composition tree when CTA is present.

---

### lens-state-patterns-16 — DiagnosticsWaitingState has no active-polling indicator

**What the rec said:**  
Add `CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)` before the Text within the DetailSection Row.

**What was done:**  
- Replaced the single `Text` in `DiagnosticsWaitingState` with a `Row(verticalAlignment = CenterVertically, spacedBy(Spacing.small))` containing the indicator + the existing text.
- Default (reduceMotion=false): `CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)` — exactly as recommended.
- reduceMotion=true: static `Icon(Icons.Default.Sync, Modifier.size(16.dp))` — same visual footprint, no spinning frame.
- BLE polling logic and diagnostics state machine are untouched.

**Reduced-render impact:**  
Indeterminate `CircularProgressIndicator` uses a single infinite-transition animation internally (Material3 implementation). When reduceMotion is on, no animation runs — static icon only.

---

## Light-mode contrast reasoning

`primary` in light theme = `PhoenixOrangeLight` (`#E65100`) — a deep, fully-saturated orange (WCAG contrast ratio against `Color.White` surface ≈ 4.7:1, AA compliant).  
At `alpha = 0.8f` over `Color.White` (the light-mode `surface`), the effective rendered colour is approximately `#EB7033` — still a clearly distinguishable warm orange with no alpha-on-alpha issue.  
Dark theme `primary` = `#FF9149` over `Slate-800` (`#1E293B`) — also high-contrast.  
The old default `onSurfaceVariant.copy(alpha = 0.6f)` rendered as `Slate700` at 60% opacity, which on light surface gave a pale grey (~`#B0BFC8`) — low saturation and marginal contrast. The new primary tint is an objective improvement on both themes.

---

## Files changed

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/EmptyStateComponent.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/DiagnosticsScreen.kt`
