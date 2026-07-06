# Task 3.2 Report — Extract shared routine-exercise detail text; normalize exercise-row typography

## What Was Done

### Pre-extraction diff result
Extracted lines 110–171 of `ExerciseRowInSuperset.kt` and lines 167–228 of `ExerciseRowWithConnector.kt` to temp files and ran `diff`. The only differences were **indentation** (the Connector file uses one extra level of nesting inside a Card > Row > Column chain). No behavioral drift. Logic is verbatim-identical. Proceeded per brief instructions ("formatting-only drift is fine to proceed through").

### Block uses no composable APIs
The detail-text block is pure string construction — no `stringResource(...)` calls. Extracted as a plain (non-`@Composable`) function.

### Files created
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/RoutineExerciseDetailText.kt`
  — `fun routineExerciseDetailText(exercise: RoutineExercise, weightUnit: WeightUnit, kgToDisplay: (Float, WeightUnit) -> Float): String`

### Files modified

**ExerciseRowInSuperset.kt**
- Removed unused `import androidx.compose.foundation.shape.RoundedCornerShape`
- Removed unused `import com.devil.phoenixproject.domain.model.ProgramMode`
- Typography: `titleSmall` → `titleMedium` for exercise name (brief: "name rises titleSmall→titleMedium")
- Replaced 63-line inline detail block with `val exerciseText = routineExerciseDetailText(exercise, weightUnit, kgToDisplay)`

**ExerciseRowWithConnector.kt**
- Removed unused `import androidx.compose.foundation.shape.RoundedCornerShape`
- Removed unused `import com.devil.phoenixproject.domain.model.ProgramMode`
- Typography: `FontWeight.Bold` → `FontWeight.Medium` for exercise name (brief: "name drops Bold→Medium")
- Typography: `bodyMedium` → `bodySmall` for detail text (brief: "detail drops bodyMedium→bodySmall")
- Replaced 66-line inline detail block with `val exerciseText = routineExerciseDetailText(exercise, weightUnit, kgToDisplay)`

**WeightStepper.kt** (lens-duplication-7 fix)
- Removed `import androidx.compose.foundation.shape.RoundedCornerShape` (now unused)
- `RoundedCornerShape(Spacing.medium)` × 2 (container background + border) → `MaterialTheme.shapes.medium`
- `RoundedCornerShape(Spacing.small)` (info row surface) → `MaterialTheme.shapes.extraSmall`
- Folded standalone `letterSpacing = 1.sp` Text parameter into `style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp)` — removes raw TextStyle override cited in the finding

### Design-system ratchet check
- No new `RoundedCornerShape(N.dp)` literals introduced
- No hardcoded colors introduced
- All typography changes use `MaterialTheme.typography.*` styles

## Verification Output

**Compile:** `./gradlew :androidApp:assembleDebug` → BUILD SUCCESSFUL in 43s  
**Tests:** `./gradlew :shared:testAndroidHostTest` → BUILD SUCCESSFUL in 14s (all 22 tasks passed)

## Concerns

None. The extraction was mechanical, the pre-condition (verbatim-identical logic) was confirmed, output strings are preserved byte-for-byte, and all four ratchet constraints are satisfied.

Phase 5 flag (per brief scope note): the two rows still differ legitimately in structure (drag handle, menu button, outer Row vs Card wrapper, selection checkbox padding). Full 2→1 merge is out of scope for this task.
