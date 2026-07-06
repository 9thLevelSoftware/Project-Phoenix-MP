# Task 4A.3 Report: ExerciseEditBottomSheet — Sets & Reps to Top

**Finding:** `routines-1` (high) — "A user mid-workout opening this sheet to tweak reps must scroll past ~600dp of configuration UI to reach the primary control."

**Recommendation applied:** "Reorder sections so Sets & Reps is the first content below the header (the most-edited fields go first). Secondary configuration (mode, echo, stall detection, scaling) can live below."

---

## Final Section Order (scrollable Column, lines ~309–714)

| # | Block | Condition | Line (post-edit) | Notes |
|---|-------|-----------|-------------------|-------|
| 1 | Video Player | `if (enableVideoPlayback)` | 309 | Unchanged |
| 2 | Personal Record Display | `currentExercisePR?.let` | 330 | Unchanged |
| 3 | **SetModeToggle** | `if (showCableOnlyExerciseControls)` | 377 | **Moved up** — was #9 |
| 4 | **SetsConfiguration** | unconditional | 386 | **Moved up** — was #15 |
| 5 | **Per Set Rest Time toggle** | unconditional | 405 | **Moved up** — was #10 |
| 6 | **Single rest time picker** | `if (!perSetRestTime)` | 434 | **Moved up** — was #16 |
| 7 | EquipmentRackSelectionCard | unconditional | 462 | Was #3 |
| 8 | WeightConfigurationCard | `if (showCableOnlyExerciseControls)` | 473 | Was #4 |
| 9 | ModeSelector | `if (showCableOnlyExerciseControls)` | 492 | Was #5 |
| 10 | TUT Beast toggle | `if (isTutMode)` | 500 | Was #6 |
| 11 | Echo Mode options | `if (isEchoMode)` | 531 | Was #7 |
| 12 | Weight Change Per Rep | `if (showCableOnlyExerciseControls && !isEchoMode)` | 543 | Was #8 |
| 13 | Stall Detection + Rep Count Timing | `if (showCableOnlyExerciseControls)` | 579 | Was #11+12 (shared outer if preserved) |
| 14 | Stop at Top | `if (shouldShowStopAtTopToggle(...))` | 659 | Was #13 |
| 15 | Warmup Sets | `if (showCableOnlyExerciseControls)` | 700 | Was #14 |

---

## Placement Rationale

### SetModeToggle placed immediately above SetsConfiguration (position 3)

SetModeToggle switches between REPS and DURATION display modes. `SetsConfiguration` renders its columns differently depending on the current `setMode` — duration exercises show a time picker rather than a rep counter. A user arriving at the sheet to adjust sets needs to see (and potentially change) the mode first, otherwise the set rows render in an unexpected format. Keeping SetModeToggle directly above SetsConfiguration respects this functional coupling while still placing both in the primary zone per the finding's recommendation.

Alternative considered: leave SetModeToggle in secondary config (after position 6). Rejected because it would create a confusing experience: the user edits sets, then scrolls down and changes the mode, then scrolls back up to re-edit. The tight functional coupling outweighs keeping the block "pure secondary".

### Per Set Rest Time toggle + single-rest picker as an adjacent pair (positions 5–6)

These two controls are logically inseparable: the single-rest picker (`if (!perSetRestTime)`) only makes sense in the context of the Per Set Rest toggle. Placing them immediately after SetsConfiguration creates a coherent "Sets & Rest" primary zone — the user configures sets, then rest — before moving on to secondary equipment and mode settings.

### Secondary config demoted below the primary zone

EquipmentRack, WeightConfig (PR scaling), ModeSelector, TUT/Echo options, Weight Change Per Rep, Stall/RepTiming, StopAtTop, and WarmupSets remain below. These are configuration items users set once per exercise and rarely change mid-workout — consistent with the finding's "secondary configuration" framing. The WarmupSets block (was #14, now #15) drops one position relative to original; it already appeared near the bottom and remains there.

---

## Diff Verification

Pure moves only. Every block was copied verbatim — no parameter changes, no conditional guard changes, no state/callback changes. The only new text is placement-rationale comments on the moved blocks (three comment lines above SetModeToggle, one above SetsConfiguration, one above Per Set Rest, one above single-rest picker).

---

## Test Results

- `./gradlew :androidApp:assembleDebug --rerun-tasks` → **BUILD SUCCESSFUL** (64 tasks, 40s)
- `./gradlew :shared:testAndroidHostTest --rerun-tasks` → **BUILD SUCCESSFUL** (22 tasks, 50s)
- `./gradlew :androidApp:testDebugUnitTest --rerun-tasks` → **BUILD SUCCESSFUL** (49 tasks, 34s)
- **Total tests executed: 2,327** (2,296 androidHostTest + 31 androidApp unit tests)
- All passed, no failures
