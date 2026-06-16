# Official Weight Handling Alignment Design

## Goal

Align Phoenix weight handling with the official Vitruvian app: the selected, stored, commanded, and primary displayed load is a per-cable value. Two-cable totals may be shown only as clearly labeled supplemental context and must not feed back into saved weights, personal records, recommendations, BLE commands, sync payloads, or routine configuration.

## Current Evidence

The official-app deobfuscation findings show a single scalar force value in kilograms flowing through set storage and BLE command encoding. Cable count is exercise metadata and does not branch load math. The only official-app doubling found is a display-only caption: "Total weight for 2 cables".

Phoenix already mostly matches this at the machine contract:

- `WorkoutParameters.weightPerCableKg` is the active workout load.
- BLE packet tests assert selected weight is written as the selected per-cable value.
- `WorkoutSession.weightPerCableKg` and `CompletedSet.actualWeightKg` store per-cable values.

Phoenix diverges in presentation and summary behavior:

- `WeightDisplayFormatter` multiplies by `cableCount`.
- History, home, exercise detail, set summary, and dashboard-style displays use `displayMultiplier` or `cableCount` to show total load as the main load.
- Tests such as `WeightDisplayFormatterTest`, `WeightDisplayRegressionTest`, and `WeightDisplayIntegrationTest` explicitly expect dual-cable display doubling.

## Display Contract

Phoenix will use these semantics:

1. **Canonical load:** `weightPerCableKg` is always kilograms per cable.
2. **Primary selected-load display:** setup screens, routine screens, workout HUD, set summary, history, PR displays, recent activity, and recommendations show per-cable load as the main value.
3. **Supplemental total display:** a helper line may show total two-cable load only when the text explicitly says "Total weight for 2 cables" or equivalent.
4. **Analytics volume/work:** true total volume and work calculations may still use physical cable count where the metric is explicitly total work, not selected load.
5. **Cable metadata:** `preferredCableCount`, `cableCount`, and `displayMultiplier` remain metadata for exercise context, telemetry summaries, and compatibility with existing persisted rows. They must not drive ordinary selected-load display.

## Architecture

Introduce clearer display APIs instead of overloading one formatter:

- Keep or refactor `WeightDisplayFormatter` so its default named operation formats per-cable load without cable multiplication.
- Move any total-load helper into an explicitly named method such as `toTwoCableTotalDisplayWeight` or `formatTwoCableTotalWeight`.
- Replace call sites that currently pass `cableCount` or `displayMultiplier` for ordinary load display with per-cable formatting.
- Leave BLE, database, routine config, sync DTOs, backup models, and weight recommendation data contracts unchanged.

This avoids a database migration and keeps the change local to presentation semantics plus tests.

## Components To Review

Primary display formatting:

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/WeightDisplayFormatter.kt`
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/util/WeightDisplayFormatterTest.kt`
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/util/WeightDisplayRegressionTest.kt`
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/util/WeightDisplayIntegrationTest.kt`

Saved workout and summary display:

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SetSummaryCard.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HistoryTab.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HomeScreen.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/DashboardComponents.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AnalyticsScreen.kt`

Live workout and setup surfaces:

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutHud.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/WeightAdjustmentControls.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/WeightStepper.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutSetupDialog.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SetReadyScreen.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineOverviewScreen.kt`

Accessory/rack behavior:

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ApplyEquipmentRackLoadUseCase.kt`
- Active-session rack snapshot and BLE adjustment code in `ActiveSessionEngine.kt`

## Data Flow

Selected load flow after the change:

1. User selects `N kg` in setup or adjustment UI.
2. UI labels it as `N kg/cable` or "Weight per cable".
3. Workout parameters store `weightPerCableKg = N`.
4. Rack counterweight logic adjusts the machine-facing per-cable value only when the selected rack item behavior requires it.
5. BLE receives the adjusted or selected per-cable value.
6. Session, set, PR, recommendation, backup, and sync records keep per-cable values.
7. History and summaries display the stored value as per-cable unless showing a separately labeled total helper.

## Error Handling And Edge Cases

- Invalid cable metadata must not cause main weights to double, zero out, or become negative. Primary display should ignore cable count.
- Legacy sessions with null `cableCount` or `displayMultiplier` should display their stored per-cable weight.
- Existing total-volume fields remain total metrics. If a row lacks `totalVolumeKg`, fallback volume may continue to use physical cable count because volume is a total work metric.
- Unified attachments such as bar or belt should not silently change the main selected weight. If useful, they may show an explicit total helper.
- Rack counterweights need careful review because current logic divides counterweight by display multiplier. The implementation plan must decide whether that multiplier is truly physical cable count or display-only metadata and rename/split it if needed.

## Testing Strategy

Update tests so failures first prove the current mismatch:

- Formatter tests should assert that `50 kg` displays as `50 kg` for both one-cable and two-cable metadata.
- A separate explicit-total helper test should assert that `50 kg/cable` can render `100 kg total for 2 cables`.
- Source guard tests should continue proving BLE and sync layers do not import display formatters.
- Set summary and history tests should verify primary load display uses per-cable values while total volume remains total.
- Rack-load tests should cover two-cable counterweight behavior so machine-facing load is correct and not driven by display-only semantics.
- BLE packet tests should remain unchanged and continue proving selected per-cable values are sent to the machine.

Recommended verification commands:

- `./gradlew.bat :shared:testAndroidHostTest --tests "com.devil.phoenixproject.presentation.util.WeightDisplaySourceGuardTest" --console=plain --rerun-tasks`
- `./gradlew.bat :shared:testAndroidHostTest --tests "com.devil.phoenixproject.presentation.components.WeightFeatureGuardTest" --console=plain --rerun-tasks`
- `./gradlew.bat :shared:testAndroidHostTest --console=plain --rerun-tasks`
- `./gradlew.bat :shared:testDebugUnitTest --console=plain --rerun-tasks` if Android unit-test wiring is available for common tests in this checkout.

## Non-Goals

- No BLE protocol redesign.
- No database migration unless implementation reveals a persisted field is incorrectly named or impossible to interpret safely.
- No change to exercise catalog cable metadata except where a test fixture needs explicit metadata.
- No automatic conversion of historical per-cable values.
- No new production dependency.

## Acceptance Criteria

- Main user-visible load values match the official app's per-cable convention.
- The app can still show an explicitly labeled two-cable total helper where useful.
- Stored/session/PR/recommendation/sync/backup contracts remain per-cable.
- BLE packet tests still prove machine commands are per-cable.
- Regression tests prevent cable-count metadata from re-entering ordinary load display.
- Total-volume/work metrics remain intentionally total and are documented as distinct from selected load.
