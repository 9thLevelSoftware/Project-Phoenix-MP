# Issue #1360: rest controls investigation

Status: **not reproduced locally; root cause and iPhone fix remain unverified**.

## Reported build

- [Issue #1360](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/1360) contains separate reports for `+30s` and Pause. Verify them independently.
- The supplied App Store Connect screenshot identifies both submissions as **1.0.7 (2026102094), iPhone 16 Pro, iOS 27.0.1**.
- Local tags `testflight/2026102094` and `v1.0.7` resolve to `d22f4e06`.
- Investigation checkout: `fa36e449` on `update/version-and-dependencies`, with concurrent, unrelated issue #1357 audio changes preserved.

## Findings

The released UI wires both controls through `RestTimerCard`, `WorkoutTab`, the
`workoutActions` adapter, `ActiveWorkoutScreen`, `MainViewModel`, and
`DefaultWorkoutSessionManager` into `ActiveSessionEngine`. No missing callback
or static overlapping pointer handler was found. The rest card relies on
`WorkoutTab` for vertical scrolling.

The live persisted-rest controls require the same execution/profile/plan
authority as the running countdown. The normal completion path retains its
execution lease during rest. Rest mutations persist the new runtime document
before publishing the updated countdown or pause state. A rejected authority
check or failed repository write can therefore leave the display unchanged;
there is no device evidence identifying either as the cause of this report.

The relevant button wiring, timer mutation logic, runtime repository, and rest
deadline calculator have no behavioral repair between the reported tag and
the investigation checkout. The later phone source-set move and Polar strip
do not establish a cause for the older build. No corresponding timer feature
was found in the sibling portal's source, Edge Functions, or migrations.

## Local verification, 2026-10-29

Added behavioral coverage:

- `Issue1360RestTimerControlsTest`: four tests use the real session manager and
  timer engine with repository/BLE fakes. They cover a TUT rest before the next
  set and before the next exercise. `+30s` persists and survives subsequent
  ticks; Pause holds the countdown and Resume restarts it. Controls do not
  advance navigation or start another set.
- `Issue1360RestTimerUiTest`: two Robolectric Compose tests send actual touch
  input through the rest card and production action adapter in a short,
  scrolling viewport. They verify displayed extensions, Pause/Resume, and
  extension while paused. The observable UI state sink is deliberately
  separate from the engine fixture; this is not a full iOS app test.

The first targeted attempt did not execute assertions: another Gradle build
replaced the shared test output and the runner could not load the new class.
The overlapping full run subsequently compiled and executed both new classes:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest :androidApp:testDebugUnitTest --continue
```

The completed run's log and JUnit XML were inspected: **BUILD SUCCESSFUL**;
shared suite **5,447 tests, 9 skipped, 0 failures/errors**; Android app suite
**26 tests, 0 skipped, 0 failures/errors**. All six new tests ran and passed.
Successful checks were not repeated. Existing compiler/deprecation warnings
remain. This run includes the concurrent audio work; it is not isolated proof
of that work or of native iOS behavior.

No production fix was made for #1360. Passing host tests do not invalidate the
TestFlight reports or establish that the reported build works on an iPhone.

## Remaining device evidence

On the reported build, or a clearly identified newer TestFlight build:

1. Use a TUT routine with at least two sets and a following exercise. Record
   whether the app stayed foregrounded and whether the workout was resumed.
2. During a running rest, tap `+30s` once. Record the displayed value before
   the tap, immediately afterward, and after several countdown ticks.
3. In a separate rest, tap Pause, wait at least five seconds, then Resume.
   Record the button label, paused indicator, and displayed time at each step.
4. Repeat before a transition to another exercise. Record whether scrolling,
   Reset, and Skip Rest respond; those observations help separate touch
   delivery from timer mutation rejection.
5. Preserve a screen recording and exported Connection Logs with the exact
   app build. Existing logs do not expose every persisted-rest early return,
   so an absent log entry alone cannot prove a tap was lost.

If the failure reproduces, the next diagnostic patch should record a
correlated UI receipt, engine receipt, persistence attempt, and terminal
publication/rejection for each control. Include only action, timer path,
remaining seconds, pause state, and authority booleans; omit user identifiers,
exercise names, serialized documents, and raw exception messages. Avoid
per-tick logging. Preserve persistence, cancellation, and ownership behavior.
Connection logging defaults to enabled and has a bounded buffer; an explicitly
opt-in trace would need its own default-off control.
