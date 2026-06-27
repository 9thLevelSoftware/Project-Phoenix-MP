# Presentation - Workout Managers Review

Scope reviewed:
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveWorkoutManager.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DwsWorkoutSetupManager.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/MotionStartDetector.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinator.kt`

Review focus: workout lifecycle managers for race conditions, state corruption, lifecycle bugs, stubs, errors, and failure points.

Summary:
- Findings: 8
- Severity breakdown: high 3, medium 5, low 0, critical 0
- Stubs/TODOs in present assigned files: none found
- Note: two assigned files are not present at the requested paths in the repository, so they could not be code-reviewed directly.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveWorkoutManager.kt

### Finding 1
- Category: error
- Severity: high
- Line numbers: N/A - file missing
- Description: The assigned file does not exist at the requested path. Repository search found no `ActiveWorkoutManager` class or file. The closest current manager names include `ActiveSessionEngine.kt` and `DefaultWorkoutSessionManager.kt`, but substituting those would be a scope change from the assigned review. This creates a review coverage gap and likely indicates stale task metadata or a renamed lifecycle manager.
- Suggested fix direction: Update the review assignment and any documentation/build references to the current file name, or restore the expected file if it was deleted accidentally. Re-run this review against the intended replacement file once confirmed.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DwsWorkoutSetupManager.kt

### Finding 2
- Category: error
- Severity: high
- Line numbers: N/A - file missing
- Description: The assigned file does not exist at the requested path. Repository search found no `DwsWorkoutSetupManager` class or file. This prevents direct review of the DWS workout setup lifecycle code requested by the task and may hide setup-specific bugs if the implementation has been moved elsewhere.
- Suggested fix direction: Update the assignment to the current setup manager file, or restore the missing file if deletion was unintended. Review the confirmed replacement file before considering the DWS setup lifecycle covered.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/MotionStartDetector.kt

### Finding 3
- Category: bug
- Severity: medium
- Line numbers: 39-68
- Description: `holdStartMs` and `hasTriggered` are plain mutable fields, while `onMetricReceived` is a public suspend function that can be called concurrently. If two metric producer coroutines feed the detector at the same time, both can observe `hasTriggered == false`, both can compute elapsed time, and duplicate or out-of-order `Started`, `CountdownTick`, or `Cancelled` events can be emitted. The detector is documented as reusable, but it is only safe under single-caller confinement, which is not enforced by the type.
- Suggested fix direction: Either enforce single-thread/single-coroutine ownership at the API boundary or protect detector state with a `Mutex`/actor-style channel. Document the confinement guarantee in the class KDoc if that is the intended contract.

### Finding 4
- Category: failure-point
- Severity: medium
- Line numbers: 36, 59, 62, 66
- Description: The event bus uses `MutableSharedFlow(extraBufferCapacity = 1)` with the default suspending overflow behavior, and `onMetricReceived` emits a `CountdownTick` for every above-threshold sample. A slow collector can backpressure `emit`, which in turn suspends metric ingestion. During a workout countdown, this can make motion-start detection lag behind the live BLE stream and can also stall the collector that is feeding metrics into the detector.
- Suggested fix direction: Use conflated/non-blocking progress delivery for countdown ticks, such as a `MutableStateFlow` for hold progress plus a separate one-shot event for `Started`, or use `tryEmit`/`DROP_OLDEST` for progress events so metric processing is never blocked by UI/event consumers.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinator.kt

### Finding 5
- Category: bug
- Severity: high
- Line numbers: 235-247
- Description: `setActiveRackSelection` reuses the previous `_currentRackLoadAdjustment` whenever `precomputedAdjustment` is null, then mirrors that adjustment into `_workoutParameters`. If callers change the active rack IDs without passing a freshly computed adjustment, the new `activeRackItemIds` can be paired with stale `externalAddedLoadKg` and `counterweightKg` from a prior selection. This can corrupt workout parameters and cause incorrect effective load display, persistence, or machine-facing calculations.
- Suggested fix direction: Treat `precomputedAdjustment == null` as an explicit reset/recompute path rather than reusing stale state, or require callers to always provide an adjustment. Consider making rack IDs, resolved JSON, adjustment, and mirrored workout parameter fields a single immutable update object.

### Finding 6
- Category: failure-point
- Severity: medium
- Line numbers: 228-248, 250-259
- Description: Rack selection state is split across multiple independently updated fields: `_activeRackItemIds`, `_currentRackLoadAdjustment`, `currentRackItemsJson`, and `_workoutParameters`. Collectors and persistence code can observe intermediate combinations, such as new rack IDs with old JSON or old adjustment, because each field is updated separately and `currentRackItemsJson` is a plain var. This is a state corruption risk during rapid setup changes or routine transitions.
- Suggested fix direction: Model rack selection as a single immutable state value exposed through one `StateFlow`, and derive the mirrored `WorkoutParameters` values from that state in one atomic update. If separate fields remain, protect updates and reads with a mutex and make `currentRackItemsJson` state-flow-backed or volatile.

### Finding 7
- Category: failure-point
- Severity: medium
- Line numbers: 45-48
- Description: The coordinator's haptic flow is configured with `extraBufferCapacity = 32` and `BufferOverflow.SUSPEND`. Haptic events are emitted from workout/rep-processing paths elsewhere in the manager layer. If the UI collector is absent, paused, or slow long enough to fill the buffer, future haptic emits can suspend active workout logic. Haptic feedback should not be able to backpressure rep counting or set lifecycle transitions.
- Suggested fix direction: Use `DROP_OLDEST` or `DROP_LATEST` with non-blocking `tryEmit` for haptic feedback, or isolate haptic emission in a fire-and-forget child coroutine that cannot stall workout state transitions.

### Finding 8
- Category: failure-point
- Severity: medium
- Line numbers: 340-355, 431-440
- Description: Several session-lifecycle fields are plain mutable vars (`currentSessionId`, `workoutStartTime`, `routineStartTime`, `currentRoutineSessionId`, `routineAccumulatedCalories`, `bodyweightCompletionVariantOverride`, `previousExerciseWasBodyweight`, and job references). These are read and written by multiple managers/coroutines in the workout flow, but most are neither `StateFlow`-backed nor marked `@Volatile` nor guarded by a lock. This can cause stale reads, lost updates, or mismatched lifecycle state across active session, routine flow, and completion handling.
- Suggested fix direction: Move session lifecycle state into immutable state holders updated through `MutableStateFlow.update`, or guard multi-field updates with a coroutine `Mutex`. At minimum, mark cross-dispatcher scalar flags/IDs as volatile and centralize ownership so only one manager mutates them.
