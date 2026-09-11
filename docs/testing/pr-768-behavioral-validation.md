# PR #768 behavioral validation

## 1. Incident summary
The completion-ownership fix passes six new behavioral tests. Four of these fail against the pre-fix ActiveSessionEngine. Validation is PARTIAL, not full acceptance: a seventh lifecycle-boundary regression fails on the PR head and is explicitly ignored pending a production fix; Phantom Android stalls at WARMUP 0 / 3 before completion.

## 2. Timeline
- Source under test: e1aae5db61b9638a8bf951a6cee4f9c5ea94289d, PR #768.
- 2026-09-10: added and executed host tests; ran pre-fix negative control and restored source.
- Android sandbox log at 16:01:17.685: `onDestroy identity=241312515 changingConfigurations=true`; 16:01:17.712: `onCreate identity=260786382 saved=true`, same PID 5016.
- Full host suite completed with 3749 tests discovered, zero failures/errors, one explicit skip.

## 3. Impact scope
This is a tests-only change. No production source, DI replacement, or emulator stimulus is shipped. No merge or issue closure is authorized by these results. New coverage checks engine completion, not physical BLE/firmware behavior.

## 4. Investigation steps and results
- New `JustLiftCompletionBehaviorTest` uses existing DWSMTestHarness and polling-gated handle events. FakeBleRepository now tracks monitor polling state; a handle event cannot be delivered through the new stimulus method if polling is stopped. Legacy direct injection remains available to other tests.
- Three consecutive handle-started sets: distinct leases, exactly one teardown/re-arm per set, active polling, zero reconnect/disconnect calls. Completion is injected at the engine boundary after three reps, not generated from ROM telemetry.
- Timed summary expiry: summary rep count retained, Idle/rest timer publication, no extra teardown or polling stop, next handle-start succeeds.
- Unlimited summary: retained after 60 seconds; handles start a distinct successor.
- Grab during timed summary: successor lease/state survives old summary expiry; its completion re-arms normally.
- Failed teardown: no start/re-arm until successful explicit recovery, then successor starts. This does not claim unsafe automatic recovery from a failed machine reset.
- Invalidated teardown: stale cleanup does not stop polling or publish a summary after reset. This test does not exhaust all late-callback interleavings with a concurrently active successor.
- Lifecycle regression: active lease disappears when `prepareForJustLift()` is called after a handle start. Executed before adding @Ignore; failure was `IllegalArgumentException: Required value was null` at the post-call lease assertion. It is preserved as an explicitly skipped regression, not reported as passing Activity recovery coverage.
- Negative control: temporarily ran pre-fix ActiveSessionEngine with new tests. Four failures: timed summary (2 stops vs 1), stale cleanup (1 polling stop vs 0), failed teardown recovery (3 stops vs 2), three consecutive sets (2 stops vs 1). Restored PR source afterward; git diff contains no production change.

## 5. Root cause / evidence limits
High confidence that the completion ownership changes address redundant teardown in the tested engine paths. The live-preparation guard is insufficient in the tested handle-start flow: `coordinator.workoutJob?.isActive` does not preserve the lease at the time of re-preparation. Exact production remedy requires follow-up; do not replace this condition blindly without tracing job ownership.
Phantom's warmup stall root cause is NOT established. No claim is made that it is an app regression rather than simulation/packet freshness behavior.

## 6. Contributing factors
Original regression coverage asserted call counts only; direct handle injection could bypass stopped polling. The task's Android Phantom source path does not exist in this branch. Only the iOS simulator implementation exists; a sandbox-only portable copy plus DI/stimulus/lifecycle logging was used for Android. An Activity config change is not process death, and preserving the UI is weaker than proving rep progression/persistence.

## 7. Remediation / remaining work
Follow-up Kanban t_0d544d04 owns the production lifecycle fix, enabling the ignored test, and finishing Phantom rep/completion/re-arm/second-set validation. Process-death recovery is unverified. Timed/unlimited tests refer to summary display modes; a separate duration-limited workout termination was not exercised. Full summary load/volume calculations are not covered here.

## 8. Verification
Run:

    JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
    ./gradlew :shared:testAndroidHostTest -Pskip.supabase.check=true

Local result: 3749 discovered, 0 failures, 0 errors, 1 skipped (the known lifecycle regression). Focused class: 7 discovered, 6 passed, 1 skipped. Existing compiler deprecation warnings remain.

Android result: debug build/install, Phantom connection, handle-grab/countdown, active telemetry, actual Activity recreation with active warmup UI preserved. Screenshot `09-recreated-active.png` shows WARMUP 0 / 3. ROM/working reps, completion, re-arm, second set, and absence of RESET_FOR_NEW_WORKOUT between sets are NOT verified.

The emulator used the base PR production SHA plus documented sandbox-only changes, not an unmodified release binary. The subsequent commit contains only tests/documentation. Sandbox patch/APK SHA256 and boundaries are recorded in `run.json`. No physical Bluetooth, load safety, or firmware validation is implied.
