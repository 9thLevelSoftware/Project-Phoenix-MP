# Issue #448 — Equipment Rack Implementation Plan

> For Hermes: implement this plan as its own PR. Use subagent-driven development with at most 3 concurrent agents. This is the largest enhancement and may include schema/sync changes; do not bundle it with other issues.

GitHub issue: https://github.com/9thlevelsoftware/Project-Phoenix-MP/issues/448
Branch: `feat/448-equipment-rack`
PR scope: equipment rack definitions, active workout equipment adjustments, UI, persistence, and tests.

## Goal

Allow users to define accessory equipment with weights and account for those weights during workouts, including bars, weighted vests, cable retractors/counterweights, and similar items.

## Current state verified in repo

Relevant files:

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Exercise.kt`
  - `Exercise.equipment` is a comma-separated string.
  - helper logic exists for cable accessories and display multipliers.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt`
  - `WorkoutParameters.weightPerCableKg` is central machine-facing load.
  - `WorkoutSession` stores session history.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt`
  - receives and applies workout parameters.
- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq`
  - stores `WorkoutSession` and related history/sync fields.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SetReadyScreen.kt`
  - natural surface for active equipment adjustment before a set.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt`
  - natural display surface during workouts.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt`
  - natural entry point for managing rack items.

## Product interpretation and safety stance

Accessory semantics are ambiguous. A weighted vest, bar, or ankle weight affects the user’s body/external load but usually should not change the trainer’s commanded cable resistance. A counterweight/retractor might reduce effective resistance and may need to subtract from cable command.

V1 should be explicit and conservative:

- Rack item behavior options:
  - `ADDED_RESISTANCE`: affects displayed/logged effective load, not machine command.
  - `COUNTERWEIGHT`: subtracts from machine command, safely clamped.
  - `DISPLAY_ONLY`: tracked for notes/display only.
- Default behavior for new items: `ADDED_RESISTANCE` or `DISPLAY_ONLY`, not counterweight.
- Any counterweight effect must be opt-in and visible before workout start.
- Machine-facing changes apply only before a set, not mid-rep.

## Architecture

Add local equipment rack model and repository, then integrate active rack selection into workout parameters.

New model:

```kotlin
@Serializable
data class RackItem(
    val id: String,
    val name: String,
    val category: RackItemCategory,
    val weightKg: Float,
    val behavior: RackItemBehavior,
    val enabled: Boolean = true,
)

enum class RackItemCategory { VEST, BAR, HANDLE, ROPE, STRAP, RETRACTOR, PLATE, OTHER }
enum class RackItemBehavior { ADDED_RESISTANCE, COUNTERWEIGHT, DISPLAY_ONLY }

data class ActiveRackSelection(
    val itemIds: List<String> = emptyList(),
)
```

Persistence options:

- Rack definitions: JSON in multiplatform Settings. No SQL schema needed for definitions.
- Session history: recommended SQL columns on `WorkoutSession` so analytics/history preserve effective load context.

Recommended WorkoutSession additions:

- `externalAddedLoadKg REAL NOT NULL DEFAULT 0`
- `counterweightKg REAL NOT NULL DEFAULT 0`
- `rackItemsJson TEXT NOT NULL DEFAULT '[]'`

## Effective load model

Keep these values distinct:

- programmed per-cable load: existing `WorkoutParameters.weightPerCableKg`.
- external added load: vest/bar/etc for display/logging.
- counterweight: subtracts from machine command.
- machine command load: clamped cable resistance sent to trainer.
- effective displayed load: user-facing total/effective load.

Formula v1:

- `machineWeightPerCableKg = clamp(programmedWeightPerCableKg - counterweightKgPerCable)`
- `displayEffectiveLoadKg = displayedProgrammedLoad + externalAddedLoadKg - counterweightKg`

Exact display multiplier/per-cable handling must use existing `displayMultiplier` conventions in `Exercise.kt`.

## Tasks

### Task 1 — Add equipment rack domain model tests

Objective: define serializable rack item behavior.

Files:

- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/EquipmentRack.kt`
- Create test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/model/EquipmentRackTest.kt`

Tests:

- rack item JSON round-trip.
- active selection preserves item order.
- invalid/negative weight is rejected or clamped according to chosen policy.
- default behavior is safe.

### Task 2 — Add rack repository with settings persistence

Objective: store rack definitions locally without DB migration.

Files:

- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/EquipmentRackRepository.kt`
- Create test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/repository/EquipmentRackRepositoryTest.kt`
- Modify DI module to register repository.

Tests:

- missing settings returns empty list.
- corrupt JSON returns empty list and does not crash.
- save/load list.
- add/update/delete item.

### Task 3 — Add load calculation use case

Objective: centralize machine/display load math.

Files:

- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ApplyEquipmentRackLoadUseCase.kt`
- Create test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/ApplyEquipmentRackLoadUseCaseTest.kt`

Tests:

1. Added resistance changes display effective load only.
2. Display-only changes neither machine command nor effective load unless UI chooses to show note.
3. Counterweight subtracts from machine command.
4. Multiple items sum correctly.
5. Machine command clamps to min/max.
6. Per-cable/dual-cable display multiplier is applied consistently.

### Task 4 — Extend WorkoutParameters

Objective: pass active rack adjustment through workout state.

Files:

- Modify: `Models.kt`
- Modify manager/update call sites as compile requires.

Add fields:

- `externalAddedLoadKg: Float = 0f`
- `counterweightKg: Float = 0f`
- optionally `activeRackItemIds: List<String> = emptyList()` if serialization/copy impact is acceptable.

Compile after adding defaults to identify call sites.

### Task 5 — Integrate machine command safely

Objective: ensure counterweight affects trainer command only at safe boundaries.

Files:

- Modify: `ActiveSessionEngine.kt`
- Modify BLE command construction path if separate from `WorkoutParameters`.

Steps:

1. Identify where `weightPerCableKg` is sent to BLE at set start.
2. Apply `ApplyEquipmentRackLoadUseCase` there, not throughout UI state.
3. Keep raw/programmed value in UI state.
4. Ensure live edits during an active set do not immediately send new BLE load mid-rep.

### Task 6 — Persist rack context in session history

Objective: preserve analytics/history correctness.

Files:

- Modify: `VitruvianDatabase.sq`
- Add migration under `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/`
- Modify repository mapping code.
- Modify sync DTOs if sessions sync.
- Modify schema/migration tests.

Steps:

1. Find current latest migration number.
2. Add next migration with three `ALTER TABLE WorkoutSession ADD COLUMN ... DEFAULT ...` statements.
3. Update insert/select/merge queries in `.sq` file.
4. Update generated mapping call sites after SQLDelight regeneration.
5. Update sync models only if WorkoutSession sync includes all columns.

Verification:

```bash
./gradlew :shared:generateCommonMainVitruvianDatabaseInterface
./gradlew :shared:allTests --tests '*Schema*' --tests '*Migration*'
```

If schema impact is too large for v1, explicitly defer session-history persistence and keep only settings/UI. Recommended PR should include it if analytics matter.

### Task 7 — Add Equipment Rack management screen

Objective: user can manage rack definitions.

Files:

- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EquipmentRackScreen.kt`
- Modify navigation routes.
- Modify `SettingsTab.kt` to link to screen.
- Add strings/resources.

Screen features v1:

- list rack items.
- add/edit/delete item.
- name, category, weight, behavior, enabled.
- explain behavior choices clearly.

### Task 8 — Add active rack selection UI

Objective: select items for current workout/set.

Files:

- Modify: `SetReadyScreen.kt`
- Modify: `ActiveWorkoutScreen.kt` or workout HUD for read-only display.
- Possibly create: `ActiveRackSelectionSheet.kt` and `RackAdjustmentChip.kt`.

Behavior:

- Before set: user can open selection sheet and select rack items.
- During active set: show adjustment read-only or disable edits.
- UI displays:
  - programmed load.
  - added resistance.
  - counterweight.
  - effective displayed load.

### Task 9 — Add settings and strings

Files:

- Modify all `shared/src/commonMain/composeResources/values*/strings.xml`.

Add keys:

- `equipment_rack`
- `equipment_rack_description`
- `equipment_rack_add_item`
- `equipment_rack_item_weight`
- `equipment_rack_behavior_added_resistance`
- `equipment_rack_behavior_counterweight`
- `equipment_rack_behavior_display_only`
- `equipment_rack_active_items`
- `equipment_rack_effective_load`

### Task 10 — Integration tests

Tests:

- selecting rack item updates workout parameters.
- counterweight command is clamped.
- session saved with rack context.
- normal workout with no rack items behaves exactly as before.

## Verification commands

```bash
./gradlew :shared:compileKotlinMetadata
./gradlew :shared:allTests --tests '*EquipmentRack*' --tests '*RackLoad*'
./gradlew :shared:allTests --tests '*Schema*' --tests '*Migration*'
./gradlew :androidApp:assembleDebug
./gradlew :shared:assembleXCFramework
```

## Manual QA

1. Add weighted vest item +5kg as Added Resistance.
2. Select it before a set; verify display effective load increases but machine command does not unexpectedly jump.
3. Add cable retractor/counterweight item -2kg as Counterweight.
4. Select it; verify displayed machine command/effective load clearly reflect subtraction.
5. Start workout; verify no mid-set adjustment occurs.
6. Save session; verify history shows rack context if schema implemented.
7. Delete rack item; verify old sessions remain readable.
8. Upgrade from old database; verify migration succeeds.

## Risks

- Accessory semantics are ambiguous; wrong machine command can be unsafe.
- Schema/sync changes are broad and need careful migration testing.
- Per-cable vs total load display is easy to get wrong.
- Settings JSON storage has practical size limits; rack should remain small.

## Acceptance criteria

- User can create and manage weighted rack items.
- User can select active rack items before a workout/set.
- App clearly shows programmed load, rack adjustment, and effective load.
- Counterweight behavior is explicit, opt-in, and safely clamped.
- No-rack workouts behave exactly as before.
- Session history/sync remains compatible after migration if schema fields are included.
