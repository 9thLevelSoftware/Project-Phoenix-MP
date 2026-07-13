# Profile release-readiness evidence — 2026-07-12

## Verdict

- Execution status: **DONE_WITH_CONCERNS**.
- Offline Profile acceptance: **READY** — all 8 required manual rows passed on local API-36 emulators.
- Schema 42 → 43 replay: **PASS**.
- Reviewed no-trainer runtime substitution: **PASS**, 20 tests, 0 failures, 0 errors, 0 skipped.
- Concern: `spotlessCheck` reproduces one pre-existing Kotlin line-ending violation in `androidApp/build.gradle.kts`; it is not reported as passing and was not changed in this evidence-only task.
- Availability limits: no physical Android device and no compatible trainer were present. Audible TalkBack output, felt tactile feedback, and live trainer telemetry are therefore recorded as **UNAVAILABLE**, not inferred from the emulator.

The manual release boundary is ready on the reviewed fixture. The overall execution status retains concerns because the repository-wide Spotless baseline is red and hardware-only observations were unavailable.

## Scope and provenance

- Working branch: `codex/profile-readiness-fixtures`.
- Acceptance HEAD: `a9028cade2cbfbeacf405a746e40c4f6e44bdb22` (`fix: center Home in bottom navigation`).
- Schema-42 fixture source: `ac84d9bb8e156002833ad526bf324a8f12710da0`.
- Materialized fixture commit: `0c9bf3e4e87133a825c8b82aaaa333a3464d53b8` (`test: materialize schema 42 fixture`).
- Package: `com.devil.phoenixproject.debug`, version code `5`, version name `0.9.5-DEBUG`.
- Both APKs used signer SHA-256 `2e32fe247c669eb21d3dad50ee9c223180b7f370bd333d3fb95177d4e247e30c`.
- Local evidence root: `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\.phoenix-review\profile-readiness\task4-final`.

All emulator work was local and offline. Airplane mode was enabled, Wi-Fi disabled, and mobile data disabled before installing or launching the APKs. The QA seed remained inside the debug-only boundary.

No Supabase CLI/MCP/Dashboard/remote action was performed.

## Fixture and evidence hashes

| Artifact | SHA-256 |
| --- | --- |
| Schema-42 APK | `00bc1365c1c038d93a8872410881ea749c1317a23fa3917420d8646f36727eac` |
| Current debug APK built at the acceptance HEAD | `194e677f96cd0ebefdca92acaedd5f0938dc6aa475c73c3d352aa388b64fa1ca` |
| Tracked/device legacy preferences XML | `a985f45159b1d383992210ce0464e7f06a7de598ba20a2633f6ce0f3cedc2cf3` |
| Schema-42 database before upgrade | `1fe5af46d8b87e0c9eed56e1ad568f7a18f3659d388fcdf0be389d9d309dffc3` |
| Seeded matrix database | `5b3bde4844e77ed05c37ec0dcaef2d9ccc75b6d884506ec4ea0826d9aeef1221` |
| Seeded matrix preferences XML | `c434d425c0a694d02dcdb15c59d763193a459c916a45682f6c54c7707c5c1c9e` |
| Seed marker log | `3924e9d0338941a37bc500270bf59bda8ce733eba317c14c7dbb58eceb3d4f18` |
| Post-delete database | `b75e005a876778e7e69bf691864c1f56c8e18dccb7cbba75ecb8686254d759cf` |
| TalkBack Profile action node | `50885addc2b54eb2456b76440ec6469e9da37be844d7a8d32f1ebcd6151b10fa` |
| Vibrator service capture | `a4e901d56aaf3104170540c03ebd87af33fbcd50ad383ac2743302ab87d41f34` |

The final immutable `phoenix-schema42-v1` snapshot payload hashes were:

- `hardware.ini`: `d2f55bbb9fc06476293c2460b9e743bd40ba0fd70e7bc13ec9a3155c10f03938`
- `ram.bin`: `eabce3b62cacaa5feff842e6e26484759db4a4ec1f41c31998cf952b4b2aeda9`
- `screenshot.png`: `8a9f899b79141dcbc9a39a2e66ba2d8710a3aae5ac46db3242d0d2084a6fc480`
- `snapshot.pb`: `53c714141ddad2efcae4884cec0d1a02e87ec498b5693ff86f8063f6e10228a9`
- `textures.bin`: `9da9d94e2e86016384b8b9647e4af8f70fa25381ccc4c9366c8be5ffc8777b5b`

These hashes were unchanged after both disposable clones were removed.

## Build and emulator inventory

The exact current APK was built with:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :androidApp:assembleDebug --rerun-tasks --console=plain
```

Result: `BUILD SUCCESSFUL` in 4m 07s; 74 tasks executed.

Both disposable AVDs used the Google Pixel 6 profile, Android API 36, Google Play x86_64 image, and Android Emulator 36.3.10. The upgrade clone used 1080 × 2400 pixels at 420 dpi. The wiped matrix clone used 1080 × 2400 pixels with density overridden to 540 dpi, yielding exactly `1080 × 160 / 540 = 320dp` width.

Upgrade AVD network state before install/launch: airplane `1`, Wi-Fi disabled, mobile data `0`. Matrix AVD network state before install/launch: airplane `1`, Wi-Fi disabled, mobile data `0`, and the package path was absent.

## Schema 42 → 43 upgrade replay

A disposable clone of the immutable schema-42 AVD was cold-booted with snapshot writes disabled. Before upgrade it had `PRAGMA user_version=42`, one existing profile, and no `UserProfilePreferences` table. Its stopped database and XML matched the immutable fixture hashes above.

The current APK was installed without uninstalling or clearing data:

```powershell
adb -s emulator-5560 install -r C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\.phoenix-review\profile-readiness\task4-final\upgrade\current-debug-a9028ca.apk
```

`adb install -r` returned success, preserved `firstInstallTime`, and left the database/XML hashes unchanged before first launch. The first bounded poll observed:

```xml
<boolean name="profile_preferences_legacy_migration_complete_v1" value="true" />
```

Stopped inspection after launch showed:

```text
user_version=43
profile_count=1
preference_count=1
default|1|1|82.5|KG|2.5|fixture-vest|1|1|0|2|fixture-vest|3|1|1|35|ESTIMATED_1RM|1
```

This is exactly one normalized preferences row for the one existing profile, with `schema_version=1`, `legacy_migration_version=1`, the legacy core/rack/workout/LED/VBT values, the single-exercise rack reference, and migrated VBT forced enabled. The UI reached a Ready Home context with the workout chooser.

After a second app launch, the entire preferences row remained equal and the normalized projection hash remained `3b33672d6ac7606bc34a840ee9cd352a49ff33efff099b5d65f2a19b59dc04a1`. The completion flag remained true. Different whole-database hashes reflect unrelated onboarding state; the complete migrated row and normalized projection were unchanged.

Evidence: `upgrade/preupgrade-inspection.txt`, `upgrade/adb-install-r.txt`, `upgrade/completion-flag-first.txt`, `upgrade/normalized-first.txt`, `upgrade/normalized-second.txt`, `upgrade/postmigration-first.db`, `upgrade/postmigration-second.db`, and `upgrade/ready-home.xml`.

## Populated Profile fixture

On the separate wiped 320dp AVD, the debug seed action was invoked only after onboarding and a pre-seed navigation capture:

```powershell
adb -s emulator-5562 shell am broadcast -a com.devil.phoenixproject.QA_SEED_PROFILE -p com.devil.phoenixproject.debug
```

The post-clear logcat contained the exact success marker `ProfileQaSeed: PROFILE_QA_SEED_OK`.

Database inspection found exactly five completed sessions for each QA profile at fixed timestamps `1700100000000` through `1700500000000`, loads 30–50 kg/cable, and 38 rep metrics per profile. Each profile had MAX_WEIGHT 50 × 3 and MAX_VOLUME 40 × 12 records, producing the three visible highlights. Each had one assessment and a newer passing velocity estimate of 77 kg/cable, MVT 0.30 m/s, R² 0.97, three distinct loads, and fixed `computedAt=4102444800000`.

The complete visible preference contrast was exercised:

- Profile A: 82.5 kg, 2.5 increment, added-resistance weighted vest, 15s/7s/45s timers, top timing, Green LED, VBT enabled with 20% loss and Estimated 1RM scaling, and calibrated/adult-confirmed `Phoenix Alpha` safety state.
- Profile B: stored 165 kg displayed in pounds, 5 increment, counterweight rack, 5s/3s/90s timers, bottom timing, Pink LED/disco, VBT disabled with retained 35%/Max Volume history preferences, and uncalibrated/adult-disabled `Phoenix Bravo` safety state.

A → B changed every inspected section; B → A restored A, including rack, LED, VBT, and local safety values.

## Manual acceptance matrix

| # | Result | Evidence and observation |
| --- | --- | --- |
| 1 | **PASS** | Fresh 320dp launch rendered exactly five icon-only roots in the order Analytics → Smart Insights → Home → Profile → Settings with no clipping. Home was selected, had localized `Home` semantics, and displayed `Choose Your Workout` plus workout entry buttons. Evidence: `matrix/preseed-home.xml`. |
| 2 | **PASS** | Profile tap navigated; returning and switching restored state. Long press kept Profile navigation state and opened the sole Profiles root sheet. For app UID 10217, vibrator service recorded TOUCH `performHapticFeedback` with `Prebaked=HEAVY_CLICK`, including a completed 42ms event. With TalkBack enabled and touch exploration active, the Profile node exposed label `Profile`, `clickable=true`, and `long-clickable=true`. Evidence: `matrix/profile-switcher.xml`, `matrix/vibrator-after-2.txt`, `matrix/talkback-profile-actions-node.xml`, and `matrix/talkback-swipe-focus.png`. |
| 3 | **PASS** | Deterministic A/B profiles carried distinct core, rack, workout, audio/auto-start/scaling, LED, VBT/verbal, and local-safety values. A → B swapped all inspected values; B → A restored A. Evidence: `matrix/profile-a-prefs*.xml`, `matrix/profile-b-*.xml`, `matrix/profile-a-restored-led.xml`, `matrix/safety-xml.txt`, and `matrix/seeded.db`. |
| 4 | **PASS** | A was changed to `Bench Press (Bar)` and showed the deterministic empty/no-estimate state; B retained seeded `Bench Press (Bench)` and its full velocity/highlight/history state; returning to A restored Bar without B metrics. Seeded profiles showed velocity 77 kg precedence, all three PR highlights, and five history rows. Evidence: `matrix/profile-a-selection-top2.xml`, `matrix/profile-b-selection-top.xml`, `matrix/profile-a-selection-restored.xml`, `matrix/profile-a-history.xml`, `matrix/profile-b-history.xml`, `matrix/history-counts.txt`, and `matrix/insights-db.txt`. |
| 5 | **PASS** | Active A was renamed to `QA A Renamed`. Default exposed no Delete action. Active B showed a named destructive confirmation stating workouts, routines, records, badges, assessments, and progression would move to Default. After confirmation B was absent, A remained, and B's 5 sessions/2 PRs/1 assessment/1 velocity estimate were assigned to Default. Evidence: `matrix/profile-renamed.xml`, `matrix/default-profile-top.xml`, `matrix/delete-dialog-b.xml`, `matrix/switcher-after-delete.xml`, and `matrix/postdelete-reassignment.txt`. |
| 6 | **PASS** | Equipment Rack and Achievements opened from Profile, and the Profile ready-list ordering was preserved. Equipment Rack showed A's enabled 10 kg added-resistance weighted vest. Settings contained only the ordered global groups Like My Work?, Cloud Sync, Appearance, Language, Video Behavior, Data Management, Developer Tools, App Info; it contained no Profile rack, Achievements, or profile selector. Evidence: `matrix/equipment-rack-route.xml`, `matrix/achievements-route.xml`, `matrix/profile-a-top.xml`, `matrix/profile-a-mid.xml`, and `matrix/settings-*.xml`. |
| 7 | **PASS** | Home edge gestures and Just Lift exposed no profile selector. B's VBT control was off while historical velocity/assessment values and five-session history remained visible. Live enable/disable behavior used the reviewed no-trainer runtime substitution below. Evidence: `matrix/home-edge-gestures.xml`, `matrix/just-lift.xml`, `matrix/profile-b-vbt.xml`, `matrix/profile-b-top.xml`, and `matrix/profile-b-history.xml`. |
| 8 | **PASS** | The immutable schema-42 clone was upgraded offline with `adb install -r`; the awaited gate completed, one existing profile received one normalized preferences row, VBT was forced enabled, the Ready context rendered, and the complete row was unchanged on a second launch. A separately wiped pre-seed launch also confirmed the product-default Home baseline. Evidence: the upgrade files listed above and `matrix/preseed-home.xml`. |

No `Switch Profile`/`Profiles` content selector appeared in Home or Just Lift; the Profiles sheet remained the sole root selector opened by Profile long press.

## Accessibility and hardware bounds

The installed TalkBack service was `com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService`. While enabled, accessibility state reported touch exploration and service double-tap handling. A swipe-navigation screenshot captured a visible TalkBack focus rectangle. Automated ADB gestures did not yield a stable screenshot of TalkBack's action menu itself, so the required separate click/long-click exposure is grounded in the live accessibility node (`clickable=true`, `long-clickable=true`) rather than a claimed menu screenshot.

No physical Android device was connected. Audible spoken-label confirmation and felt tactile feedback are **UNAVAILABLE**. The emulator vibrator-service event proves the app requested the expected haptic effect, not that a person physically felt it.

No compatible Vitruvian trainer was connected. Live trainer telemetry is **UNAVAILABLE**. The reviewed runtime substitution was:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*VerbalEncouragementPreferenceCascadeTest" --tests "*SafeWordDetectionManagerTest" --tests "*AdultModePresentationTest" --tests "*VbtEnabledRuntimeTest" --rerun-tasks --console=plain
```

Exact XML totals:

| Suite | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `VerbalEncouragementPreferenceCascadeTest` | 11 | 0 | 0 | 0 |
| `SafeWordDetectionManagerTest` | 2 | 0 | 0 | 0 |
| `AdultModePresentationTest` | 3 | 0 | 0 | 0 |
| `VbtEnabledRuntimeTest` | 4 | 0 | 0 | 0 |
| **Total** | **20** | **0** | **0** | **0** |

Gradle result: `BUILD SUCCESSFUL` in 2m 50s; 27 tasks executed.

## Formatting, cleanliness, and cleanup

The non-mutating formatting gate was run as required:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' spotlessCheck --console=plain
```

Actual result: **FAILED** in 1m 16s. The sole reproduced violation was `androidApp/build.gradle.kts` lines 330–335, where the existing final dependency/closing lines have the wrong line ending. `spotlessCheck` did not change HEAD or worktree state. No formatting apply command was run and this evidence task did not alter the baseline file.

Both disposable AVD clones were shut down and removed after resolved-path checks. Final `adb devices -l` was empty, and `emulator -list-avds` listed only the immutable `phoenix-schema42-api36` source. The immutable source payload hashes still matched. The external `.phoenix-review` evidence was retained.

Before this report was added, `git status --short` was empty at the acceptance HEAD. Final report staging and commit are limited to this Markdown file; `git diff --check` and the clean post-commit status are recorded in the task handoff.
