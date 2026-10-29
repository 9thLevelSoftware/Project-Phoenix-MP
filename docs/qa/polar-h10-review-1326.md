# PR 1326 review resolution

Reviewed source: `afbbcbace207b977c4f0f523b40c7bc270ac0a3c` from `StuGotz/Project-Phoenix-MP`, branch `feature/polar-h10-telemetry`.

This records local remediation of the October 29 owner review and remaining earlier feedback. It is not a hosted CI or device-test receipt.

| Finding | Resolution |
| --- | --- |
| 1. Connection timeout | Timeout reaches cleanup and an error state; external cancellation still propagates. |
| 2. PMD subscription failure | Readiness deferreds complete exceptionally; PMD setup failure does not kill the connection observer. |
| 3. Recovery contaminates set summary | Freeze the set at snapshot/manual stop; recovery writes only HRR columns. |
| 4. Early cancellation mislabels HRR | Persist only elapsed windows, using fresh HR observations. |
| 5. Main-thread database flush | Dedicated telemetry repository runs transactional saves on its IO dispatcher; callers await the queued work without blocking the UI thread. |
| 6. Queries for non-Polar sessions | Check summary columns before querying beat, ECG or metric rows. |
| 7. Interrupted recovery loses rows | Detach and queue captured rows before cancellation or the next recording; debounce contact loss. |
| 8. Screen disposal stops recovery streams | Recording/cooldown owns streams; releasing a preview cannot stop an active owner. |
| 9. History hides recovery | Stored HRR renders independently of cooldown controls; grouped history selects and labels a recovery-bearing or latest sensor set. |
| 10. Disconnected trainer yields blank completion | Completion remains visible when the trainer disconnects. |
| 11. iOS concurrent scanner interference | Explicit physical-device release gate in `polar-h10-release.md` and `CLAUDE.md`; unverified on hardware. |
| 12. Protocol and storage policy | Install command response listener before write; fail missing/rejected ACKs; require an H10 name; parse all compressed ACC blocks; raw ECG defaults off with capture, age and storage caps. |
| 13. Manual-stop summary missing | Capture the recording's Polar summary at Stop and remap its raw rows to the saved session ID. |
| 14. Backup ID conflicts | Identical rows count as skipped; differing or cross-session ID collisions report conflicts without replacing local rows. |
| 15. Misleading backup test | Name and assertions explicitly distinguish retained summaries from excluded raw rows. |
| 16. Explicit disconnect overridden | Automatic reconnection respects the disconnect flag; screen entry no longer initiates reconnection. |
| 17. Shared-state races | Lock advertisement access and recording state; capture nullable heart-rate values before use. |
| 18. Global state on unrelated cards | Live connection and cooldown state require the matching session; read-only history cannot expose unrelated live state. |
| 19. Clinical labels | Replace risk classifications with observed values and neutral wording. |

Earlier feedback also addressed: remove dead coordinator-ID fallbacks and duplicated cooldown guards; move storage ownership and row mapping into repositories/domain models; remove shipped testing stream toggles, screen-entry stream startup, stale migration-58 comments and unused imports; correct five schema-60 QA markers. Polar data remains local plus file backup only, documented in the release notes.

Additional review coverage checks delayed commits across recordings, frozen summaries, transient contact loss with the watchdog running, HR-only signal loss while PMD continues, missing-parent batches after session deletion, and raw-ECG retention boundaries.

## Validation

- Initial Android unit run: 26 tests, zero failures, errors or skips. This includes the persisted-file harness contract that failed on the PR head.
- Initial Android debug assembly completed.
- Initial shared test compilation failed on two invalid Compose assertion imports; those imports were removed.
- Initial lint failed inside its IntelliJ PSI analysis of the new Compose test while source edits were in progress. The frozen-source retry passed.
- Shared full run: 5,441 cases, 5,429 passing, three failing and nine skipped. The failures were outdated expectations: a resumed set must own a new session ID, and disconnected completion content now shares the connected-content branch. After correcting those tests, the exact three failed cases passed their targeted rerun; successful tests were not repeated. Combined result: 5,432 passed, nine skipped, no outstanding failures.
- `:androidApp:lintDebug` passed; after the two test files changed, the affected `:shared:lintAnalyzeAndroidHostTest` also passed.
- Updated `:androidApp:assembleDebug` passed. The local build uses `-Pskip.supabase.check=true` and does not establish a live sync/provider gate.
- Schema manifest validation passed: 681 columns across 60 tables. The full shared run includes schema parity, Polar manager/parser/persistence/repository tests, backup completeness, manual-stop regression and Compose history regressions.
- `git diff --check` passed before the final test-expectation-only corrections.
- Physical Android/iOS BLE, native iOS SQLite, macOS/iOS compilation and hosted CI remain external release gates.

Commands used (PowerShell quotes are required around the Gradle project property):

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug --continue
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest :androidApp:lintDebug :androidApp:assembleDebug --continue
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests '*PolarH10BleManagerTest.routine intermediate sets update phase to REST while final completion triggers cooldown' --tests '*Issue687WorkoutStartGateUiTest.recovery actions remain available while disconnected' --tests '*Issue687WorkoutStartGateUiTest.recovery actions remain available while reconnecting' :shared:lintAnalyzeAndroidHostTest --continue
```

The first attempt with an unquoted property stopped during configuration before compiling or testing. Logs are in the local temporary directory: `phoenix-pr1326-validation.log`, `phoenix-pr1326-validation-final.log`, and `phoenix-pr1326-targeted-recheck.log`.

Publication details and hosted checks are recorded on the PR. This report does not establish review approval, merge or deployment.
