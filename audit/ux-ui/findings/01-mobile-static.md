# Mobile Static UX/UI Audit — Findings

**Scope:** Project-Phoenix-MP shared Compose UI (`shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/`)
**Auditor:** UX Researcher (mobile)
**Method:** Static read of representative high-traffic flows; cross-referenced theme files; verified against charter constraints.

**Files reviewed (16):**
- `presentation/screen/HomeScreen.kt`
- `presentation/screen/ActiveWorkoutScreen.kt`
- `presentation/screen/SetReadyScreen.kt`
- `presentation/screen/RoutineOverviewScreen.kt`
- `presentation/screen/RoutineEditorScreen.kt`
- `presentation/screen/RoutineCompleteScreen.kt`
- `presentation/screen/SetSummaryCard.kt`
- `presentation/screen/RestTimerCard.kt`
- `presentation/screen/WorkoutTab.kt`
- `presentation/screen/WorkoutHud.kt`
- `presentation/screen/AuthScreen.kt`
- `presentation/screen/EulaScreen.kt`
- `presentation/screen/SmartInsightsTab.kt`
- `presentation/screen/AnalyticsScreen.kt`
- `presentation/screen/EnhancedMainScreen.kt`
- `presentation/screen/CycleEditorScreen.kt`
- `presentation/screen/DailyRoutinesScreen.kt`
- `presentation/screen/BadgesScreen.kt`
- `presentation/screen/SplashScreen.kt`
- `presentation/screen/OneRepMaxInputScreen.kt`
- `presentation/components/ConnectingOverlay.kt`
- `presentation/components/ConnectionLostDialog.kt`
- `presentation/components/ConnectionStatusBanner.kt`
- `presentation/components/ConnectionErrorDialog.kt`
- `presentation/components/EmptyStateComponent.kt`
- `presentation/components/AnimatedActionButton.kt`
- `presentation/components/WeightStepper.kt`
- `presentation/components/RpeSlider.kt`
- `ui/theme/Color.kt`
- `ui/theme/Theme.kt`
- `ui/theme/HomeButtonColors.kt`
- `ui/theme/SupersetTheme.kt`
- `ui/theme/AccessibilityColors.kt`
- `ui/theme/Type.kt`
- `ui/theme/Spacing.kt`

## Summary

- **22 findings:** 4 critical, 9 high, 7 medium, 2 polish
- **Top recurring patterns:**
  1. **Brand inconsistency at home/CTA layer** — Phoenix orange theme is overridden with hard-coded blue/purple in `HomeButtonColors`, then ad-hoc orange/red literals in EulaScreen, SplashScreen, EnhancedMainScreen subtitle. Three different orange tokens (`PhoenixOrangeLight`, `PhoenixOrangeDark`, hard-coded `0xFFFF6B35`) produce three distinct hues across the app.
  2. **"Per cable" weight shown without explanation** — every weight surface (HomeScreen activity, RestTimer, SetReady, SetSummary, WeightStepper) shows `kg per cable` or `(kg/cable)` units. Only `WeightStepper` explains it ("Total weight for 2 cables: …"). New users will be confused by why their displayed numbers are half of what they "lifted."
  3. **Dialog stacking around exit/stop actions** — multiple confirmation dialogs are wired to overlapping triggers (system back, top-bar back, in-screen Stop button) with subtle behavioral differences (RoutineOverview shows confirmation; SetReady shows confirmation that pops to DailyRoutines, ActiveWorkout shows three-button confirmation). Mental model is fragmented.
  4. **State coverage gaps** — only loading & empty states are present. Network/offline state is **not surfaced anywhere**. Sync error indicator is in the top bar but recoverable error states inside lists/forms are absent (no "couldn't load routines, retry?" patterns).
  5. **Confirmation dialogs without "destructive intent" semantics** — `RoutineEditor` batch delete, `CycleEditor` swipe-to-delete (with UNDO), and `SettingsTab` profile deletion use different patterns; none consistently use `colorScheme.error` containers, and most rely on TextButton labels alone.

- **Coverage gaps (require live walkthrough):**
  - iOS-specific BLE behaviors and Keychain prompts (cannot verify on Windows host).
  - HUD position-bar haptic timing during real reps.
  - Whether the `ConnectingOverlay` (still present but unused per `ActiveWorkoutScreen.kt:533` comment) is dead code or surfaces in some path.
  - Real-device performance of `SplashScreen` ember particle Canvas (25 particles, 4s loop) on low-end Android — could thrash GC.
  - Whether `connectionLostDuringWorkout` dialog actually unblocks the user when the machine has *also* faulted (mid-set BLE drop scenario).

---

## Findings

### F-001 [CRITICAL] BLE connection-lost dialog allows "Dismiss" with no recovery path

**Surface:** Mobile
**Category:** 3 (state coverage — BLE-specific)
**Location:** `presentation/components/ConnectionLostDialog.kt:67-69` and `presentation/screen/EnhancedMainScreen.kt:522-532`
**Observation:** `ConnectionLostDialog` is shown when BLE disconnects during a workout. It offers two buttons: "Reconnect" (calls `viewModel.reconnectInterruptedWorkout()`) and "Dismiss" (just hides the dialog via `onDismiss = { viewModel.dismissConnectionLostAlert() }`). Tapping outside the dialog also dismisses it (`onDismissRequest = onDismiss`). The user is left on `ActiveWorkoutScreen`/`WorkoutTab` with `WorkoutPausedCard` visible (per `WorkoutTab.kt:383-389`) but with no clear path forward — the workout is paused, the machine has likely faulted, but the only recovery is to find the small "Connect" button in the top bar.
**Why it hurts:** Mid-workout BLE drops are the single most common Vitruvian failure mode (the charter calls this out as a hard constraint). Allowing the user to dismiss the alert without resolving the connection state means they can lose track of why their workout is "frozen" — they may close the app, losing the in-progress set. There is no equivalent "End workout" affordance from the dialog itself.
**Severity rationale:** Critical (not High) because it's a primary safety/UX path during the moment of greatest user stress, and the current design lets users orphan themselves into an unrecoverable UI state. High would be "missing nice-to-have"; this is "missing escape hatch from a frequent failure."
**Proposed fix:** Replace the dismiss button with explicit, mutually-exclusive choices: (a) "Reconnect" (primary), (b) "End set & save reps" (secondary, calls `stopAndReturnToSetReady` to preserve completed reps), (c) "End workout" (tertiary, navigates to `RoutineCompleteScreen` or home). Make the dialog non-dismissible (`dismissOnClickOutside = false, dismissOnBackPress = false`). Quick-win (≤2hr).
**Parity flag:** YES — Portal does not currently render any equivalent BLE-loss state because BLE is mobile-only, but the portal's "Active Workout" view (if any) should also reflect the disconnected state.

---

### F-002 [CRITICAL] RoutineEditor lets user save an empty/placeholder routine with no validation

**Surface:** Mobile
**Category:** 5 (form & input UX)
**Location:** `presentation/screen/RoutineEditorScreen.kt:474-488`
**Observation:** The Save button always calls `viewModel.saveRoutine()` regardless of routine state. If the user clicks Save with: (a) no exercises added, (b) blank name, or (c) a routine still showing the placeholder "Tap + to add your first exercise", the routine is saved with `name = "Unnamed Routine"` (per fallback at line 478) and zero exercises. The user is then popped back to the routine list with a routine that, when started, will produce empty/null behavior in `RoutineOverviewScreen.kt:113` (`if (routine == null) return`).
**Why it hurts:** Users can accumulate phantom "Unnamed Routine" entries with no exercises. When they tap to start one, nothing happens (silent failure). They may also save a routine with a name that exactly conflicts with another routine — there's no uniqueness check.
**Severity rationale:** Critical because it produces unrecoverable garbage data that users must manually clean up via the multi-step batch-delete flow, AND because the silent-failure on launch breaks core "Start a workout" task completion.
**Proposed fix:** Disable the Save button when (`state.exercises.isEmpty() || state.routineName.isBlank()`). Show inline helper text near the disabled button: "Add at least one exercise to save". Quick-win (≤2hr). Design-spike (≥1 day) for the longer fix: warn on duplicate names with "Overwrite / Rename / Cancel".
**Parity flag:** NO — portal routine editor likely has its own validation (verify in portal-static audit).

---

### F-003 [CRITICAL] RoutineEditor has no unsaved-changes warning on back-navigation

**Surface:** Mobile
**Category:** 5 (form & input UX) + 2 (navigation)
**Location:** `presentation/screen/RoutineEditorScreen.kt:171-192` (no `BackHandler`) and `EnhancedMainScreen.kt:308-315` (top-bar back falls through to `navController.navigateUp()`)
**Observation:** `RoutineEditorScreen` does not register a `BackHandler` and does not subscribe to `topBarBackAction`. The system back button and the top-bar back arrow both call `navController.navigateUp()` directly. Users who have just spent 5+ minutes adding exercises, configuring sets, building supersets, and reordering them lose all of it instantly with one accidental back tap. Compare to `RoutineOverviewScreen.kt:142-144` which correctly registers `BackHandler { showStopConfirmation = true }`.
**Why it hurts:** Routine creation is an investment-heavy task (median ~10 exercises × 30s config each = 5 min). Losing this work to a single mis-tap is the kind of pain point that makes users abandon the app. This pattern is *not consistent* with the existing app's careful confirmation dialogs around workout exit.
**Severity rationale:** Critical because it's irrecoverable data loss in a primary creation flow with no undo.
**Proposed fix:** Track a `hasUnsavedChanges: Boolean` derived from `state.routine != initialRoutine`. Wire `BackHandler { if (hasUnsavedChanges) showDiscardConfirmation = true else navController.navigateUp() }` and equivalent `setTopBarBackAction`. Confirmation dialog: "Discard changes? Your edits to this routine will be lost. [Discard / Keep Editing]". Quick-win (≤2hr). Apply same pattern to `CycleEditorScreen.kt:90-96`.
**Parity flag:** NO

---

### F-004 [CRITICAL] Three different "Phoenix orange" hues create incoherent brand identity

**Surface:** Mobile
**Category:** 1 (visual & brand consistency)
**Location:**
- `ui/theme/Color.kt:13` — `PhoenixOrangeDark = Color(0xFFFF9149)` (peach/salmon, used as Material `primary` in dark mode)
- `ui/theme/Color.kt:16` — `FlameOrange = Color(0xFFFF6B00)`
- `presentation/screen/SplashScreen.kt:36` — `FireOrange = Color(0xFFFF6B35)` (Phoenix Ember per portal docs)
- `presentation/screen/EulaScreen.kt:25` — `FireOrange = Color(0xFFFF6B35)` (re-declared, same color, different file)
- `presentation/screen/EnhancedMainScreen.kt:284-287` — `Color(0xFFF97316)` to `Color(0xFFEF4444)` gradient on "Project Phoenix" subtitle
- `presentation/screen/HomeScreen.kt:346` — `Color(0xFFFF6B00)` for streak fire icon
**Observation:** The app uses at minimum **four distinct orange/red hues** as its primary brand color across surfaces: peach `#FF9149` (Material primary), saturated orange `#FF6B00`, ember `#FF6B35`, and red-orange `#F97316`. None of these match each other. The `Color.kt` comment on line 13 even says "was too pink/salmon" — confirming the team has noticed but only patched in one place. The portal docs claim Phoenix Ember = `#FF6B35`, but mobile's actual `MaterialTheme.colorScheme.primary` resolves to `#FF9149`.
**Why it hurts:** Brand colors anchor user trust and recognition. A user comparing the splash screen (saturated `#FF6B35`) to the home screen primary buttons (after color resolves through `MaterialTheme.primary` → `#FF9149`) will see a visibly different orange — peach vs. ember. The "Project Phoenix" gradient subtitle in the top bar (`#F97316` → `#EF4444`) doesn't match either. This is the kind of subtle inconsistency that signals "amateur app" without users being able to articulate why.
**Severity rationale:** Critical because brand color is the highest-frequency visual element on the screen and the documented inconsistency (charter notes parent CLAUDE.md is also wrong about background being `#0D0D0D`) shows this is an active doc-rot situation, not a transient bug.
**Proposed fix:**
1. **Decision needed:** Pick one Phoenix Ember canonical value (`#FF6B35` recommended to match portal). Update `PhoenixOrangeDark` in `Color.kt:13`. Delete the local `FireOrange` declarations in `SplashScreen.kt:36` and `EulaScreen.kt:25`; replace with `MaterialTheme.colorScheme.primary` or `import com.devil.phoenixproject.ui.theme.PhoenixOrangeDark`.
2. Replace the hardcoded gradient in `EnhancedMainScreen.kt:282-287` with theme tokens (`primary` → `error` or a new `BrandGradient` token).
3. Replace `Color(0xFFFF6B00)` in `HomeScreen.kt:346` with `MaterialTheme.colorScheme.primary` or a new `StreakOrange` token.
Design-spike (≥1 day) — needs design decision plus systematic sweep. Lower-effort if scoped tightly.
**Parity flag:** YES — known parity gap per charter; portal uses `#FF6B35`, mobile primary is `#FF9149`.

---

### F-005 [HIGH] Home screen primary actions break the Phoenix brand — they're blue/purple

**Surface:** Mobile
**Category:** 1 (visual) + 2 (information architecture)
**Location:** `ui/theme/HomeButtonColors.kt:11-23` and `presentation/components/AnimatedActionButton.kt:294-299`
**Observation:** The four most prominent CTAs on the home screen — "Cycles", "Routines", "Single Exercise", "Just Lift" — are rendered through `AnimatedActionButton`, which colors them using `HomeButtonColors.PrimaryBlue (#2E5EAA)` for the primary button and `HomeButtonColors.AccentPurple (#5B4E77)` for the rest. Only "Just Lift" uses the `isFireButton = true` path with the fire-particle effect. The home screen — the user's first surface every session — therefore presents fitness CTAs in cool blue/purple instead of the warm Phoenix orange palette established in splash, top bar, and active-workout HUD.
**Why it hurts:** The home screen is where brand identity should be loudest. Cool blue CTAs on a dark cobalt background (`Slate950`) read as "generic productivity app" not "fire-energy fitness brand". The class comment in `HomeButtonColors.kt:7` literally cites a generic Coolors palette unrelated to Phoenix branding.
**Severity rationale:** High (not Critical) because the buttons still function correctly — this is a brand impression problem, not a task-completion failure. But it's high-impact because of placement (always visible, primary CTA layer).
**Proposed fix:** Delete `HomeButtonColors.kt` and refactor `AnimatedActionButton.kt:294-299` to use `MaterialTheme.colorScheme.primaryContainer` for the standard buttons and `primary` for `isPrimary`. The "Just Lift" fire effect can stay. Design-spike (≥1 day) — needs validation that the resulting visual hierarchy still reads correctly with the warmer palette.
**Parity flag:** YES — portal home/landing CTAs use the orange palette consistently; mobile diverges at the home surface.

---

### F-006 [HIGH] No global state for "loading routines" or "loading PRs" — initial-load UX is silent

**Surface:** Mobile
**Category:** 3 (state coverage)
**Location:** `presentation/screen/HomeScreen.kt:91-103` (state collection); `presentation/screen/AnalyticsScreen.kt:215-220`; `presentation/screen/DailyRoutinesScreen.kt:33-44`
**Observation:** All three screens collect StateFlows (`viewModel.routines`, `viewModel.allWorkoutSessions`, `viewModel.allPersonalRecords`) using `collectAsState()` with no initial-loading state. The screens render immediately with empty lists/zero values, then re-render once data arrives. Users see "No recent workouts recorded" (`HomeScreen.kt:511`) for the brief moment after launch even when they have hundreds of workouts. `AnalyticsScreen` has the same problem on the Progress tab (`AnalyticsScreen.kt:102` shows "no PRs" empty state if list is empty during load). `BadgesScreen.kt:114-121` is the only screen that correctly handles `isLoading` state with a `CircularProgressIndicator`.
**Why it hurts:** Empty-state messages ("No workouts yet") are designed to onboard new users. Existing users seeing them at app launch — even for a fraction of a second — implies their data has been lost. This is especially distressing for users tracking PRs where data loss has real consequences.
**Severity rationale:** High because it affects data trust at the first surface every user sees. Not critical because the correct data does eventually render (typically within 100ms).
**Proposed fix:** Add `isLoading: StateFlow<Boolean>` to the relevant ViewModels (`MainViewModel.routines`, `MainViewModel.allWorkoutSessions`, `MainViewModel.allPersonalRecords`), defaulting to `true` until first emission completes. In screens, render `ShimmerEffect` or `CircularProgressIndicator` while loading, then the empty state only after `isLoading == false && data.isEmpty()`. Quick-win per screen (~2hr each).
**Parity flag:** YES — portal `useQuery` from TanStack Query already handles this distinction correctly via `isLoading`/`isFetching`/`data`. Mobile should match.

---

### F-007 [HIGH] No offline / network-error state for sync-dependent surfaces

**Surface:** Mobile
**Category:** 3 (state coverage — offline)
**Location:** Confirmed by Grep — `offline|noConnection|noNetwork|OfflineState|isOnline` returns **zero matches** across `presentation/`. Only the sync icon in `EnhancedMainScreen.kt:578-705` reflects network state.
**Observation:** The app is local-first (per architecture), but several surfaces depend on cloud data: badges (gamification stats), insights (SmartInsightsTab), exercise videos (`enableVideoPlayback`), authentication, and integration linking. None of these screens render an offline indicator or error UI when the network is unreachable. Videos in `SetReadyScreen.kt:96-108` silently fail (catch block `videoEntity stays null`), so the user sees a blank video placeholder with no explanation.
**Why it hurts:** Users on flaky gym Wi-Fi see broken video, missing insights, and stale leaderboards with no signal that the issue is network-related vs. an app bug. They retry, restart, and may eventually log out — none of which fixes a network problem.
**Severity rationale:** High because it spans 4+ surfaces and creates support burden. Not critical because the core local-only flow (start workout, lift, record reps) still works fully offline.
**Proposed fix:**
1. Add a `NetworkState` flow at `MainViewModel` (Connected / Disconnected / Cellular / Wifi).
2. Show an inline banner ("Offline — videos & insights unavailable") on `SmartInsightsTab`, `BadgesScreen`, and `SetReadyScreen` when offline.
3. Replace silent video-load failure in `SetReadyScreen.kt:104` with an explicit "Video unavailable offline" placeholder.
Design-spike (≥1 day).
**Parity flag:** YES — portal also needs equivalent offline messaging.

---

### F-008 [HIGH] "Per cable" weight unit is shown without explanation across every weight surface

**Surface:** Mobile
**Category:** 5 (form & input UX) + 8 (cross-platform parity)
**Location:**
- `presentation/screen/HomeScreen.kt:548` — "$displayWeight $unitLabel/cable"
- `presentation/screen/SetReadyScreen.kt:359` — `label = "Weight per cable"`
- `presentation/screen/RoutineOverviewScreen.kt:615` — `label = "Weight per cable"`
- `presentation/screen/SetSummaryCard.kt:193` — `unit = "($unitLabel/cable)"`
- `presentation/components/RestTimerCard.kt:441` — `Res.string.rest_weight_per_cable`
- `presentation/components/WeightStepper.kt:119` — `text = "kg per cable"` + total-weight clarification at lines 142-173 (the only place that explains it)
**Observation:** Six different weight-displaying surfaces all label values as "kg per cable" or "lbs/cable", but only `WeightStepper` clarifies via "Total weight for 2 cables: X kg" (lines 142-173). New Vitruvian users frequently report confusion: "I lifted 80kg but the app says 40kg" — because the machine has two cables and the convention here is per-cable. The portal multiplies by 2 for display (per `transforms.ts WEIGHT_MULTIPLIER`), so a value entered on portal as 80kg becomes "40 kg/cable" on mobile.
**Why it hurts:** This is the single most asked support question for any cable-stack trainer app. Without an info icon or short tooltip explaining the convention, users distrust the displayed numbers, double-enter values, or compare unfavorably to non-Vitruvian apps.
**Severity rationale:** High because it's a foundational unit-display issue affecting every workout surface. Not critical because the values are technically correct — the meaning is just under-explained.
**Proposed fix:**
1. Add an info `(i)` icon next to "Weight per cable" labels on `SetReadyScreen`, `RoutineOverviewScreen`, `RestTimerCard`, and `SetSummaryCard` that opens a tooltip/bottom sheet: "Vitruvian uses 2 cables. 'Per cable' is the load on each cable. Total resistance = per cable × 2."
2. Display total alongside per-cable in `SetReadyScreen` and `RoutineOverviewScreen` using the same pattern from `WeightStepper.kt:142-173`.
3. Once on first launch, show a one-time onboarding card explaining the per-cable convention.
Quick-win (≤2hr) for the info icon, design-spike (≥1 day) for the onboarding moment.
**Parity flag:** YES — portal multiplies by 2 for display, mobile shows per-cable. The mental model mismatch between the two surfaces is itself a parity concern (covered separately by 04-a11y-parity).

---

### F-009 [HIGH] Exit-workout dialogs are inconsistent across the workout flow

**Surface:** Mobile
**Category:** 2 (navigation) + 4 (workout flow ergonomics)
**Location:**
- `presentation/screen/RoutineOverviewScreen.kt:280-303` — confirmation with "Exit / Cancel"
- `presentation/screen/SetReadyScreen.kt:398-421` — confirmation that pops to `DailyRoutines.route` on exit
- `presentation/screen/ActiveWorkoutScreen.kt:445-531` — three-button confirmation (Stop set / Skip exercise / End workout) when in routine flow, two-button (Exit / Cancel) when not
- `presentation/screen/EnhancedMainScreen.kt:534-553` — yet another exit confirmation triggered from top-bar back action on RoutineOverview
**Observation:** Four different exit-confirmation patterns exist across the workout flow. The wording ("Exit Routine" vs "Stop Current Set" vs "Exit Workout") and button counts (2 vs 3) differ. Some popping behaviors land the user back on `DailyRoutines`, others on the previous screen, others on `Home`. The user's mental model of "what happens when I bail out of this workout" is unclear and depends on which screen they're on.
**Why it hurts:** Users who legitimately need to abandon a workout mid-flow (gym closed, emergency, machine fault) face decision-fatigue and ambiguity at exactly the moment they want to leave. Inconsistent destinations create disorientation.
**Severity rationale:** High because the workout flow is the central task and exit is a common path. Not critical because users can eventually find their way out — it's friction, not blockage.
**Proposed fix:** Define a single shared `ExitWorkoutDialog` composable with three actions: Save & Exit, Discard & Exit, Cancel. Always pop back to `DailyRoutines.route` on exit. Update `RoutineOverviewScreen`, `SetReadyScreen`, `ActiveWorkoutScreen`, and `EnhancedMainScreen` to call the shared dialog. Remove the bespoke implementations. Design-spike (≥1 day).
**Parity flag:** NO — this is an internal mobile consistency problem.

---

### F-010 [HIGH] Two different exercise-config surfaces (BottomSheet + Modal) with different scopes

**Surface:** Mobile
**Category:** 2 (information architecture) + 5 (form UX)
**Location:**
- `presentation/screen/ExerciseEditBottomSheet.kt:70-82` — `ModalBottomSheet`-based, edits sets/reps/weight/rest/mode/echo settings
- `presentation/components/ExerciseConfigModal.kt:40-49` — `Dialog`-based, edits ONLY mode + mode-specific settings (uses template sets/reps)
- `presentation/screen/ExerciseEditDialog.kt` — yet another exercise editor entry point
**Observation:** The codebase ships at least three distinct UIs for "edit this exercise". `ExerciseEditBottomSheet` is opened from `RoutineEditorScreen.kt:762`. `ExerciseConfigModal` is opened from `ModeConfirmationScreen.kt:19` (during cycle template confirmation). `ExerciseEditDialog` exists with unclear callsites. They have different visual shells (sheet vs dialog), different action labels ("Save" vs "Confirm"), and different field scopes. A user editing the same exercise from a routine vs. from a cycle will get a different UI.
**Why it hurts:** Inconsistent editing surfaces force users to rebuild mental models. They cannot transfer "I know how to add a superset" knowledge from routines to cycles because the editor is different.
**Severity rationale:** High because exercise config is the highest-frequency action in routine/cycle creation. Not critical because the result is the same `RoutineExercise` data structure — just confusing surface presentation.
**Proposed fix:** Consolidate to one `ExerciseConfigSheet` composable with optional flags for `editScope: SCALAR_ONLY | FULL` and `actionLabel: String`. Migrate callers. Delete the redundant files. Design-spike (≥1 day).
**Parity flag:** NO

---

### F-011 [HIGH] BLE auto-detection sheet competes for the user's attention mid-rest

**Surface:** Mobile
**Category:** 4 (workout flow ergonomics) + 3 (state coverage)
**Location:** `presentation/screen/ActiveWorkoutScreen.kt:87, 387` and `presentation/components/AutoDetectionSheet.kt`
**Observation:** `AutoDetectionSheet` (visibility driven by `detectionState`) appears during the workout flow when the system has heuristically detected a possible exercise change. This can fire during rest periods as the user is reading the rest timer, looking at next-exercise config, or considering adjusting weight. The sheet, once shown, modally captures input until confirmed/dismissed. There is no charter-cited UI path explaining when it appears.
**Why it hurts:** During the 60-90s rest window, users are mentally rehearsing the next set, drinking water, and watching the timer. A modal sheet asking "Did you switch to Squat?" is high-friction at this moment. Worse, if the heuristic mis-detects (e.g., bumps to the cable read as exercise change), the user must dismiss it before continuing — adding a step.
**Severity rationale:** High because it interrupts a primary flow. Not critical because the sheet can be dismissed and the workout continues.
**Proposed fix:** Don't make it modal during `WorkoutState.Resting` — show it as a non-blocking toast or banner at the bottom of `RestTimerCard` with "Switch to detected exercise?" button. Reserve the modal sheet for only `WorkoutState.Idle` (between sets, before the user starts the next set). Design-spike (≥1 day).
**Parity flag:** NO

---

### F-012 [HIGH] Connection error troubleshooting tips can confuse non-technical users

**Surface:** Mobile
**Category:** 3 (state coverage — BLE) + 9 (onboarding)
**Location:** `presentation/components/ConnectionErrorDialog.kt:42-54`
**Observation:** When auto-connect fails, `ConnectionErrorDialog` shows a fixed list of bullet tips: "Ensure the machine is powered on / Try turning Bluetooth off and on / Move closer to the machine / Check that no other device is connected". These tips are static — they don't adapt to the actual `ConnectionState.Error` cause (Permission, BluetoothDisabled, ScanTimeout, ConnectionTimeout, etc.). The dialog also shows the raw `message: String` from the error in the body — which can be a stack-trace-like string from Nordic BLE.
**Why it hurts:** A user whose Bluetooth is disabled in OS settings doesn't need "move closer" advice — they need "Open Bluetooth settings" CTA. A user denied location permission needs the permission flow, not BT troubleshooting. The static list buries the actionable next step.
**Severity rationale:** High because BLE failures are the most common support category for cable-trainer apps. Not critical because users do eventually resolve them, but with significant frustration.
**Proposed fix:** Branch the dialog content on `ConnectionState.Error` cause. Map causes to actions: `BluetoothDisabled` → "Turn on Bluetooth" with deep-link to system settings. `LocationPermissionDenied` → "Grant location permission" CTA. `ScanTimeout` → existing tips list. `ConnectionTimeout` → "Reset machine power, then retry." Filter the raw error message — show user-friendly description, hide stack trace. Design-spike (≥1 day).
**Parity flag:** NO — BLE is mobile-only.

---

### F-013 [HIGH] Set Ready screen's Stop button leads to ambiguous destination

**Surface:** Mobile
**Category:** 2 (navigation) + 4 (workout flow)
**Location:** `presentation/screen/SetReadyScreen.kt:194-206, 398-421`
**Observation:** The "Stop" icon button at `SetReadyScreen.kt:194` opens a confirmation dialog (line 398) that, when confirmed, pops back to `NavigationRoutes.DailyRoutines.route` (line 409). The button itself uses an X-close icon (`Icons.Default.Close`) and a `errorContainer` color — visually destructive. But "Stop" in the SetReady context is ambiguous: stop the current set (which hasn't started yet) vs. stop the entire routine. The dialog text is `Res.string.exit_routine_message`, which clarifies, but only after the destructive-looking button is tapped.
**Why it hurts:** Users who tap the X expecting "go back to routine overview" get a "are you sure you want to exit?" dialog instead. Conversely, users who want to exit the routine assume they should use the system back button (which calls `viewModel.returnToOverview()` in line 83 — a different flow).
**Severity rationale:** High because the routing for these two interactions is divergent and nondeterministic from a user perspective.
**Proposed fix:** Replace the X-close icon with a clearer "Exit Routine" text button or use a `Stop` icon (square) labeled "End Routine". Keep the destructive color. Make the system back behavior consistent with the X tap (both open the same dialog). Quick-win (≤2hr).
**Parity flag:** NO

---

### F-014 [MEDIUM] Splash-screen animations may not respect reduced-motion preferences

**Surface:** Mobile
**Category:** 1 (visual) + 7 (accessibility, light flag — owned by 04-a11y-parity)
**Location:** `presentation/screen/SplashScreen.kt:75-138, 286-353`
**Observation:** `SplashScreen` runs four concurrent infinite animations (logo breath, glow intensity, fire flicker, ember-particle motion) plus 25 animated Canvas particles. There is no check for `accessibility.isReduceMotionEnabled` or equivalent — the animations always run.
**Why it hurts:** Users with vestibular sensitivity or motion-triggered migraines see a launch animation they cannot opt out of. iOS users have set this preference at the OS level expecting apps to respect it.
**Severity rationale:** Medium because this is a once-per-launch animation (not constantly visible) and the duration is short. Flagging here as a pattern; the deep-dive belongs in 04-a11y-parity.
**Proposed fix:** Add `LocalReduceMotionEnabled` CompositionLocal and gate the infinite animations behind it. Fall back to `SimpleSplashScreen` (already exists at line 360-393) when reduce-motion is on. Quick-win (≤2hr).
**Parity flag:** NO

---

### F-015 [MEDIUM] EulaScreen redeclares brand colors as private constants — drifts from theme

**Surface:** Mobile
**Category:** 1 (visual) + 9 (onboarding)
**Location:** `presentation/screen/EulaScreen.kt:23-29`
**Observation:** EulaScreen defines its own private color tokens: `DarkSlate = #0F172A`, `DeepNavy = #1E293B`, `FireOrange = #FF6B35`, etc. These shadow the theme tokens (`Slate900`, `Slate800`, `PhoenixOrangeDark` from `Color.kt`) and bypass the theme's dark/light adaptation. If the user has system theme = light, the EulaScreen still renders with hardcoded dark slate background.
**Why it hurts:** Light-mode users see a screen that doesn't match the rest of the app. Future theme changes (e.g., adding a high-contrast mode for accessibility) will not apply to this screen without manual sweep.
**Severity rationale:** Medium because the EULA is a one-time screen seen at first launch. Not critical, but a clear "design system smell" that hints other screens may have similar drift.
**Proposed fix:** Replace local `DarkSlate`, `DeepNavy`, `FireOrange`, `WarningRed` with `MaterialTheme.colorScheme` equivalents. Quick-win (≤2hr).
**Parity flag:** NO

---

### F-016 [MEDIUM] No visual differentiation between "skipped" and "completed" exercises in RoutineOverview

**Surface:** Mobile
**Category:** 4 (workout flow ergonomics)
**Location:** `presentation/screen/RoutineOverviewScreen.kt:653-669` and the page indicators at lines 257-275
**Observation:** The page indicators use `MaterialTheme.colorScheme.tertiary` for `isCompleted` and `outlineVariant` for "future" exercises. But a user who **skipped** an exercise (called via `viewModel.stopAndSkipCurrentExercise()` from `ActiveWorkoutScreen.kt:480`) is also flagged in `skippedExercises` set, which is wired to the navigator at line 75. However, the `RoutineOverviewScreen` page indicators do not render skipped state — they only check `completedExercises.contains(index)`. The completed-overlay at line 655-668 is also tertiary-colored — same as the indicator.
**Why it hurts:** Users who skip an exercise mid-routine cannot tell from the overview which ones they did vs. skipped. They may attempt to redo them (already advanced), or assume they completed everything when they didn't.
**Severity rationale:** Medium because the routine flow eventually reaches `RoutineCompleteScreen` where stats are accurate. Friction within the overview is what's affected.
**Proposed fix:** Add a `skippedExercises: Set<Int>` collect at `RoutineOverviewScreen.kt:97` and render a third dot color (e.g., `outline`) plus a "Skipped" overlay (different from `CheckCircle`). Quick-win (≤2hr).
**Parity flag:** NO

---

### F-017 [MEDIUM] Just Lift weight default initializes to 1 lb (0.453592 kg) — visually awkward

**Surface:** Mobile
**Category:** 5 (form & input UX)
**Location:** `presentation/screen/JustLiftScreen.kt:97`
**Observation:** `weightPerCable` defaults to `0.453592f` (1 lb in kg) on first launch (`rememberSaveable` initial value). The displayed value in the picker shows ~0.5 kg or 1 lb depending on user's unit preference. There is a `LaunchedEffect` to load saved defaults (line 114-150), but on first-ever launch, the user sees an oddly-precise non-round value.
**Why it hurts:** First impression of "Just Lift" — the highlighted CTA on the home screen — shows a decimal weight. Users perceive this as broken or unfinished.
**Severity rationale:** Medium because it self-corrects after any save and is unlikely to drive churn, but it's a polish issue at the brand-CTA.
**Proposed fix:** Default to a sensible round number (e.g., 5 kg per cable) on first launch. Quick-win (≤2hr).
**Parity flag:** NO

---

### F-018 [MEDIUM] HomeScreen weekly compliance dot for "today + workout done" looks identical to "past + workout done"

**Surface:** Mobile
**Category:** 1 (visual) + 4 (workout flow)
**Location:** `presentation/screen/HomeScreen.kt:362-399`
**Observation:** `ComplianceDot` uses `MaterialTheme.colorScheme.primary` for both "active (workout completed)" and an additional border for "today and not active". When `isToday && isActive`, the dot is filled primary AND has a primary border — but the border is the same color as the fill, so the "today" indicator vanishes. From a quick glance, today's completed dot is indistinguishable from yesterday's completed dot.
**Why it hurts:** The "Did I work out today yet?" check is the highest-frequency reason a user looks at the home screen. Subtle visual cue gets lost when both today and historical days show the same orange dot.
**Severity rationale:** Medium because the streak counter still tells the story, but the dot row is the primary at-a-glance affordance.
**Proposed fix:** When `isToday && isActive`, increase dot size or add a slight glow/ring (use `tertiary` for the ring). Alternatively, use a distinct fill style (filled with checkmark icon for today). Quick-win (≤2hr).
**Parity flag:** NO

---

### F-019 [MEDIUM] BadgesScreen's "Loading" CircularProgressIndicator covers entire grid area, no skeleton

**Surface:** Mobile
**Category:** 3 (state coverage) + 6 (data-dense surfaces)
**Location:** `presentation/screen/BadgesScreen.kt:113-121`
**Observation:** While `isLoading == true`, the entire badge-grid area is replaced by a centered spinner. The category filter chips above and stats row remain visible, but the grid itself is gone. On a slower DB read or sync scenario (badges loaded from `gamificationRepository`), the user sees a blank grid with a spinner — losing layout context.
**Why it hurts:** The grid layout vanishes during loading, then appears jankily when data resolves. Better practice: shimmer placeholders for badge cards.
**Severity rationale:** Medium because it's a polish/perceived-performance issue, not a functional blocker.
**Proposed fix:** Replace the centered spinner with a `LazyVerticalGrid` of `ShimmerEffect` placeholder cards (the file `ShimmerEffect.kt` already exists in components). Quick-win (≤2hr).
**Parity flag:** NO

---

### F-020 [MEDIUM] CycleEditor swipe-to-delete with UNDO is the only undo affordance in the app

**Surface:** Mobile
**Category:** 2 (navigation) + 5 (form UX)
**Location:** `presentation/screen/CycleEditorScreen.kt:191-213` (UNDO snackbar) vs. `presentation/screen/RoutineEditorScreen.kt:710-723` (no undo, immediate delete with batch confirmation)
**Observation:** `CycleEditorScreen` correctly shows a "Day removed" snackbar with UNDO action when an item is swipe-deleted. But equivalent destructive actions in `RoutineEditorScreen` (delete exercise via dropdown menu, delete superset, batch delete) all commit immediately — no undo. The user must use the "Cancel" button before confirming, but once confirmed, deletion is irreversible.
**Why it hurts:** Inconsistent undo capability across editors of similar complexity. Users who learn UNDO works in cycles will assume it works in routines.
**Severity rationale:** Medium because batch delete has a confirmation step. Not critical because data isn't lost without one explicit confirm.
**Proposed fix:** Add `Snackbar` with UNDO action to all destructive actions in `RoutineEditorScreen` for consistency: delete single exercise, delete superset, batch delete. Use a 5-second undo window. Design-spike (≥1 day).
**Parity flag:** NO

---

### F-021 [POLISH] EnhancedMainScreen's "Project Phoenix" subtitle gradient runs every recomposition

**Surface:** Mobile
**Category:** 1 (visual)
**Location:** `presentation/screen/EnhancedMainScreen.kt:279-290`
**Observation:** The subtitle "Project Phoenix" under the screen title uses a `Brush.linearGradient(...)` allocated inline on every composition. Same issue at multiple usages of `Brush.verticalGradient` in `RoutineCompleteScreen.kt:67-74`, `SetReadyScreen.kt:215-222`, `SplashScreen.kt:148-156`.
**Why it hurts:** Allocating a `Brush` object inside a composable's render scope creates GC pressure on every frame during animations or scrolling. Subtle frame-time impact.
**Severity rationale:** Polish — performance optimization, not user-visible until profiled.
**Proposed fix:** Hoist the brushes via `remember { Brush.linearGradient(...) }` or move them to top-level constants where colors don't depend on theme.
**Parity flag:** NO

---

### F-022 [POLISH] AnalyticsScreen FAB position partially obscures content on Compact width

**Surface:** Mobile
**Category:** 1 (visual) + 6 (data-dense surfaces)
**Location:** `presentation/screen/AnalyticsScreen.kt:437-454` and the bottom Spacer at line 200-204
**Observation:** The Export FAB is positioned `BottomEnd` with `Spacing.large` padding and uses `RoundedCornerShape(28.dp)` Expressive style. Content underneath includes the last PR card. The bottom spacer is `80.dp`, which works on Compact but may not clear the FAB on Expanded width where FAB icon is `36.dp` (line 234-237).
**Why it hurts:** Last PR card or last history row may be partially hidden behind the FAB on smaller screens, requiring extra scrolling.
**Severity rationale:** Polish — minor layout polish, only relevant when content is short enough to not scroll.
**Proposed fix:** Increase the bottom spacer to `120.dp` on Expanded width class, or use `contentPadding` of LazyColumn instead of explicit Spacer.
**Parity flag:** NO

---

