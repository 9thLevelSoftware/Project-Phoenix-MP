# Task 3.11 Report — LoadingIndicator size convention

## Component created
`shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/LoadingIndicator.kt`

`enum class LoadingIndicatorSize(val dp: Dp, val stroke: Dp)` with three tiers:
- **Small** — 16 dp / 2 dp stroke — in-button / inline-row
- **Medium** — 24 dp / 2.5 dp stroke — content-area (card bodies, list sections)
- **Large** — 48 dp / 4 dp stroke — full-screen or overlay blocking states

`fun LoadingIndicator(size, modifier, color)` wraps `CircularProgressIndicator`; color defaults to `ProgressIndicatorDefaults.circularColor`.

---

## Site inventory (21 converted + 1 exempt)

| # | File | Line | Old size | Context | Verdict | Notes |
|---|------|------|----------|---------|---------|-------|
| 1 | `components/BiomechanicsHistoryCard.kt` | 293 | 24 dp / stroke 2 dp | Box fillMaxWidth centered, card body | **Medium** | — |
| 2 | `components/ConnectingOverlay.kt` | 49 | 48 dp | Dialog fillMaxSize overlay, Card center | **Large** | — |
| 3 | `components/exercisepicker/GroupedExerciseList.kt` | 255 | default | Column centered, "Loading exercises..." | **Medium** | bare default |
| 4 | `navigation/NavGraph.kt` | 548 | default | Box fillMaxSize center | **Large** | bare default |
| 5 | `screen/AssessmentWizardScreen.kt` | 883 | 48 dp | Box fillMaxSize center (SavingContent overlay) | **Large** | color=primary preserved |
| 6 | `screen/AuthScreen.kt` | 271 | 20 dp / stroke 2 dp | Inside Button, sign-in form | **Small** | — |
| 7 | `screen/BadgesScreen.kt` | 119 | default | Box fillMaxSize center | **Large** | bare default |
| 8 | `screen/IntegrationsScreen.kt` | 202 | 16 dp / stroke 2 dp | Inside Button confirmButton (import dialog) | **Small** | — |
| 9 | `screen/IntegrationsScreen.kt` | 336 | 14 dp / stroke 2 dp | Inside OutlinedButton (HealthConnect sync) | **Small** | — |
| 10 | `screen/IntegrationsScreen.kt` | 387 | 14 dp / stroke 2 dp | Inside OutlinedButton (Hevy sync) | **Small** | — |
| 11 | `screen/IntegrationsScreen.kt` | 454 | 14 dp / stroke 2 dp | Inside OutlinedButton (Liftosaur sync) | **Small** | — |
| 12 | `screen/IntegrationsScreen.kt` | 564 | 14 dp / stroke 2 dp | Inside OutlinedButton (Export CSV) | **Small** | — |
| 13 | `screen/IntegrationsScreen.kt` | 585 | 14 dp / stroke 2 dp | Inside OutlinedButton (Import CSV) | **Small** | — |
| 14 | `screen/LinkAccountScreen.kt` | 390 | 24 dp | Inside Button (login) | **Small** | color=onPrimary preserved |
| 15 | `screen/LinkAccountScreen.kt` | 430 | 18 dp / stroke 2 dp | Inside OutlinedButton (Google OAuth) | **Small** | — |
| 16 | `screen/LinkAccountScreen.kt` | 445 | 18 dp / stroke 2 dp | Inside OutlinedButton (Apple OAuth) | **Small** | — |
| 17 | `screen/SettingsTab.kt` | 3177 | default | Row inline with Text "please_wait" (dialog) | **Medium** | bare default |
| 18 | `screen/SingleExerciseScreen.kt` | 333 | default | Box fillMaxSize center | **Large** | bare default |
| 19 | `screen/SmartInsightsTab.kt` | 182 | default | Box fillMaxSize center | **Large** | bare default |
| 20 | `screen/WorkoutTab.kt` | 1425 | 24 dp | Row inline with Text (Scanning state) | **Medium** | — |
| 21 | `screen/WorkoutTab.kt` | 1442 | 24 dp | Row inline with Text (Connecting state) | **Medium** | — |

**EXEMPT (not touched):**
- `components/ReadinessBriefingCard.kt:113` — determinate `progress = { result.score / 100f }`, `fillMaxSize` — data display gauge, not a spinner.

---

## Delta vs brief's 21-site list

No additions, no removals. All 21 sites from the brief are present on this branch. Count matches exactly.

---

## Verification

- `./gradlew :androidApp:assembleDebug` — **BUILD SUCCESSFUL** (45 s)
- `./gradlew :shared:testAndroidHostTest --rerun-tasks` — **BUILD SUCCESSFUL** — **2,296 tests executed, 0 failures, 0 errors**
