# Profile release-readiness evidence — validated 2026-07-13

## Verdict

- Execution status: **DONE**.
- Release verdict: **READY**.
- Automated gates and TalkBack-validated code revision: **PASS** at `361677c22e24e8c0997f519b55920197e5f0d146`.
- Offline schema 42 → 43 named-snapshot replay: **PASS**.
- Manual matrix: **eight rows pass**. Row 2 now has direct TalkBack focus, click, long-click, resulting-screen, and app haptic-request evidence.
- Fresh no-trainer runtime substitution: **PASS**, 20 tests, 0 failures, 0 errors, 0 skipped.
- Fresh release-boundary verification: **PASS**, with seven forbidden markers absent from three release manifests and 705 entries in one release APK.
- Branch-introduced Gradle formatting violation: **FIXED** by follow-up commit `31a4e761febe5faa5a8af04542d7cbca53091219`; `spotlessKotlinGradleCheck` passes. The repository-wide `spotlessCheck` still exposes the known 192-file Kotlin-formatting baseline; it is not reported as passing.

The previously missing TalkBack proof was completed on the current branch APK. TalkBack visibly focused the fourth bottom item and spoke `Profile, Tab, 4 of 5`; its focused node exposed both `ACTION_CLICK` and `ACTION_LONG_CLICK` plus the hints `Press Alt + ENTER to Profile` and `Press Alt + Shift + ENTER to Open profile switcher`. TalkBack then executed both actions on that same focused node: normal activation opened Profile, and long activation opened the Profiles bottom sheet. Vibrator-manager history independently records the app UID requesting and completing `HEAVY_CLICK`. A physical Android device and compatible trainer were unavailable, so personally heard/felt output and live trainer telemetry remain unavailable rather than inferred.

## Scope and provenance

- Working branch: `codex/profile-readiness-fixtures`.
- Pre-report validation revision: `361677c22e24e8c0997f519b55920197e5f0d146` (`docs: correct profile readiness gate evidence`).
- Acceptance-code HEAD: `8103281babd6484a3c6aa1471c672dbfeff7a842` (`fix: use localized Insights navigation semantics`).
- Post-review automation HEAD: `31a4e761febe5faa5a8af04542d7cbca53091219` (`test: enforce profile QA release boundary`). Its production-source delta is documentation-only KDoc; the remaining changes add a shared seven-marker inventory, a hermetic unit contract, a release-output verifier, and Gradle-line-ending normalization.
- Earlier matrix HEAD used for regression-unaffected rows 4–7: `a9028cade2cbfbeacf405a746e40c4f6e44bdb22`.
- The app-code delta from `a9028ca` to `8103281b` is limited to localized Insights bottom-navigation semantics plus its contract test; rows 4–7 do not exercise that delta. Rows 1, 3, and 8 were rerun with the `8103281b` APK. Row 2 was rerun with the current-branch `361677c2` APK.
- TalkBack APK package: `com.devil.phoenixproject.debug`, version code `5`, version name `0.9.5-DEBUG`.
- Acceptance-code evidence root: `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\.phoenix-review\task4\final-transcripts`.
- Earlier regression-unaffected matrix root: `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\.phoenix-review\profile-readiness\task4-final\matrix`.
- Current TalkBack APK build/install provenance root: `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\.phoenix-review\profile-readiness\talkback-followup` (`build-provenance.txt` and `install-and-launch.txt`).
- Current TalkBack acceptance root: `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\.phoenix-review\profile-readiness\talkback-host-followup\host-critical-2`.
- Fresh current-revision automated-gate root: `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\.worktrees\profile-readiness\build\phoenix-review\profile-readiness\automated-gates-361677c2`.

All emulator validation was local and offline. Airplane mode was enabled and Wi-Fi was disabled before install/launch. The upgrade replay also had mobile data disabled before install. On the fresh matrix, airplane mode already prevented connectivity during install; all mobile-data keys were explicitly set to `0` and the active network was `none` before first launch.

No Supabase CLI/MCP/Dashboard/remote action was performed.

The final TalkBack run used the current-branch debug APK, length `40,998,548` bytes and SHA-256 `2fd45d02b07374f2be6ecaa48a2cb90ab05fa7bd2ff95c8cf8393a603f8625c2`. The raw build capture records branch, full HEAD, command, status, bytes, and hash in `talkback-followup/build-provenance.txt`; `install-and-launch.txt` records install success, package version, and disabled network radios. TalkBack was version `16.0.0.738667889` on the disposable API-36 AVD. The device remained in airplane mode with Wi-Fi, mobile data, and Bluetooth disabled.

## Acceptance-code HEAD APK build

The definitive build command was:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :androidApp:assembleDebug --rerun-tasks --console=plain
```

It ran at acceptance-code HEAD `8103281b`, exited `0`, and reported `BUILD SUCCESSFUL in 2m 14s` with 74 tasks executed. The resulting APK is `androidApp-debug-8103281b.apk`, length `40,998,548` bytes, SHA-256:

```text
d150cc8deeefee313cbecbaa847f60d5187360943b1a33266d0a2eb8b2f9a072
```

Complete command metadata, standard output, standard error, exit capture, APK badging, and hashes are in `build.metadata.txt`, `build.stdout.txt`, `build.stderr.txt`, `build.exit.txt`, `build.apk-badging.txt`, and `build.sha256.txt`. Two earlier wrapper attempts failed before Gradle because the wrapper lacked `JAVA_HOME` and then `ANDROID_HOME`; their complete captures remain as `build-attempt1-harness.*` and `build-attempt2-env.*`. They are not presented as build attempts that compiled source.

Key hashes from `build.sha256.txt`:

| Artifact | SHA-256 |
| --- | --- |
| Acceptance-code APK | `d150cc8deeefee313cbecbaa847f60d5187360943b1a33266d0a2eb8b2f9a072` |
| Build stdout | `4aa48b85448e7607e8487d8f699389f7de75f51c7a12e6faeb80e6c3d5974f79` |
| Build stderr | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| Build exit capture | `271e34725eb16539a3eae5218bdde30854e4e91faa5e83662370fcb04ddc2813` |

## Actual named-snapshot replay and upgrade

The immutable source AVD was never launched or mutated. The first disposable clone copied the original `phoenix-schema42-v1` snapshot byte-for-byte and attempted:

```text
-snapshot phoenix-schema42-v1 -force-snapshot-load -no-snapshot-save
```

The emulator rejected that copied snapshot with `different AVD configuration`, deleted only the disposable clone's copied tag, and exited. The failed attempt, exact command, output, bounded ADB checks, cleanup, and unchanged source hashes are preserved under `snapshot-attempt1/`.

The fallback used a second disposable clone. It cold-booted with snapshot loading and saving disabled, stayed offline, and reproduced the immutable stopped app data exactly:

| Artifact | Immutable and pre-save SHA-256 |
| --- | --- |
| Schema-42 database | `1fe5af46d8b87e0c9eed56e1ad568f7a18f3659d388fcdf0be389d9d309dffc3` |
| Legacy preferences XML | `a985f45159b1d383992210ce0464e7f06a7de598ba20a2633f6ce0f3cedc2cf3` |

The fallback then saved the exact tag with `adb -s emulator-5560 emu avd snapshot save phoenix-schema42-v1`, shut down, and relaunched with:

```text
-snapshot phoenix-schema42-v1 -force-snapshot-load -no-snapshot-save
```

The emulator reported `Successfully loaded snapshot 'phoenix-schema42-v1' using 3887 ms`; ADB was online and `boot_completed=1` on the first poll. The database and XML still matched the immutable hashes before install. The saved snapshot's state payload files remained unchanged across replay; the emulator rewrote only `snapshot.pb` metadata during load, recorded explicitly in `named-snapshot-replay/snapshot-replay-integrity.txt`.

The acceptance-code APK was installed with `adb install -r`. Install output was `Success`, `firstInstallTime` remained `2026-07-12 20:18:39`, and the database/XML remained byte-identical before first launch. The first migration-gate poll observed the completion flag. Stopped inspection then showed:

```text
user_version=43
profile_count=1
preference_count=1
```

The one existing Default profile had one `schema_version=1`, `legacy_migration_version=1` row containing the legacy 82.5 kg core values, Fixture Vest rack, workout document, LED scheme, and enabled VBT document. Evidence is in `named-snapshot-replay/install-r.*`, `postinstall-prelaunch-hashes.txt`, `migration-gate-result.txt`, `postmigration-before-new-profile-query.txt`, and the captured stopped DB/XML files.

Migration idempotence is also retained in the earlier regression-unaffected upgrade evidence under `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\.phoenix-review\profile-readiness\task4-final\upgrade`: `normalized-first.txt` and `normalized-second.txt` have the same normalized projection SHA-256, `3b33672d6ac7606bc34a840ee9cd352a49ff33efff099b5d65f2a19b59dc04a1`, and the complete `UserProfilePreferences` row compares equal between `postmigration-first.db` and `postmigration-second.db`. The only intervening app-code change was the Insights content description; it did not touch migration or persistence.

### New product-default profile before any seed

After the upgraded app reached a Ready Home context, the run opened the Profile switcher and created `freshdefaults` through the app UI. Repository-backed stopped inspection found one new active profile, ID `18dfe175-1169-48a8-97c9-44c8835dd55c`, with all product defaults and no inherited fixture values:

| Section | New-profile persisted value |
| --- | --- |
| Schema/migration | `schema_version=1`, `legacy_migration_version=1` |
| Core | `0.0`, `LB`, increment `-1.0` |
| Equipment rack | `{"version":1,"items":[]}` |
| Workout | `{"version":1}` |
| LED | scheme `0`, `{"version":1}` |
| VBT | enabled `1`, `{"version":1}` |
| Per-section metadata | updated/generation/server revision `0`; dirty `1` |
| Local safety | safe word, calibrated, adult-confirmed, and adult-prompted keys all absent |

The query also proves absence of Fixture Vest, the legacy single-exercise document, and legacy VBT content. The UI remained Ready with `freshdefaults` active. Evidence is `new-profile-defaults/ui-new-profile-ready.xml`, `ui-new-profile-profile-top.xml`, `ui-defaults-sweep-*.xml`, `new-profile-defaults-query.txt`, and `after-create.db`; the query's final assertion is `all_product_defaults_pass=True`.

## Fresh acceptance-code 320dp seeded matrix

The acceptance-code APK was installed on a wiped Pixel 6 API-36 clone at 1080 × 2400 with density override 540, exactly 320dp wide. Before first launch, airplane mode was `1`, Wi-Fi was disabled, `mobile_data`, `mobile_data1`, and `mobile_data2` were `0`, and the active network was `none`.

Before and after the seed, the exact icon-only bottom order was:

```text
Analytics → Insights → Home → Profile → Settings
```

The debug-only broadcast produced `ProfileQaSeed: PROFILE_QA_SEED_OK` on the first poll. Evidence is `matrix-8103281b/preseed-home.xml`, `postseed-home.xml`, `seed-broadcast.*`, `seed-poll.txt`, and `seed-result.txt`.

```powershell
adb -s emulator-5562 shell am broadcast -a com.devil.phoenixproject.QA_SEED_PROFILE -p com.devil.phoenixproject.debug
```

The run switched A → B and opened B's rack management surface. It displayed `QA Assistance B`, `Assistance - Counterweight`, `16.53 lb`, `Enabled`. It then switched B → A, opened A's rack, and displayed `QA Weighted Vest A`, `Weighted vest - Added resistance`, `10 kg`, `Enabled`.

The complete post-B A sweep restored all inspected A values: 82.5 kg body weight, kg unit, 2.5 kg increment, 15s/7s/45s timing, top rep timing, audio/countdown/auto-start/motion/scaling choices, Green LED scheme, enabled VBT with 20%/Estimated 1RM, verbal/vulgar/dominatrix state, and local safety `Phoenix Alpha`, calibrated, adults-only confirmed. The stopped database and preferences XML independently preserve the full A/B documents and safety keys.

Evidence: `matrix-8103281b/profile-b-rack-route.xml`, `restored-a-rack-route.xml`, `restored-a-sweep-1.xml` through `restored-a-sweep-10.xml`, `restored-a-sweep-combined.txt`, `seeded-final-db-query.txt`, `seeded-final-preferences.xml`, and `seeded-final-vitruvian.db`.

## Manual acceptance matrix

| # | Result | Evidence and observation |
| --- | --- | --- |
| 1 | **PASS** | Acceptance-code fresh 320dp captures render exactly Analytics → Insights → Home → Profile → Settings, with Home selected and no clipping. Evidence: `matrix-8103281b/preseed-home.xml` and `postseed-home.xml`. |
| 2 | **PASS** | With TalkBack 16.0 and touch exploration enabled, a real host `Alt+Right` chord reached Android's hardware keyboard path and produced TalkBack focus events. `05-profile-focused.png` visibly frames the fourth tab; `05-profile-focus-logcat.txt` identifies the focused node as `Profile`, `clickable`, `longClickable`, with `ACTION_CLICK` and `ACTION_LONG_CLICK`, speaks `Profile, Tab, 4 of 5`, and announces both action hints. This emulator's host path did not forward the Alt modifier for `Alt+Enter`, so the action chords were delivered through Android's AOSP `uinput` utility as a temporary `KEYBOARD | EXTERNAL` USB keyboard. `12-uinput-alt-enter-logcat.txt` records `CLICK_CURRENT`, successful `ACTION_CLICK`, and `TYPE_VIEW_CLICKED`; `12-profile-opened.png` shows Profile. `13-uinput-alt-shift-enter-logcat.txt` records `LONG_CLICK_CURRENT` and successful `ACTION_LONG_CLICK`; `13-profile-switcher.png` shows the Profiles sheet. `13-vibrator-after.txt` adds an app-UID `HEAVY_CLICK` absent before the action. Full method and file index: `host-critical-2/RESULTS.md`. |
| 3 | **PASS** | Acceptance-code A → B showed B's counterweight rack; B → A showed A's weighted vest and restored the complete A core, rack, workout, LED, VBT/verbal, and local-safety state. Evidence: the fresh rack routes, ten-file A sweep, final DB query, and preferences XML listed above. |
| 4 | **PASS** | Regression-unaffected matrix evidence shows A selecting `Bench Press (Bar)` with an empty/no-estimate state, B retaining `Bench Press (Bench)` with its own 77-per-cable velocity result, three highlights, and five history rows, then A restoring Bar without B metrics. Correct evidence: `a-restored-bench2.xml`, `profile-b-selection-isolation.xml`, `profile-a-selection-restored.xml`, `profile-a-history.xml`, `profile-b-history.xml`, `history-counts.txt`, and `insights-db.txt`. |
| 5 | **PASS** | Regression-unaffected evidence covers rename, Default's absent Delete action, guarded destructive confirmation, deletion, and reassignment of B's sessions/PRs/assessment/velocity estimate to Default. Evidence: `profile-renamed.xml`, `default-profile-top.xml`, `delete-dialog-b.xml`, `switcher-after-delete.xml`, and `postdelete-reassignment.txt`. |
| 6 | **PASS** | Profile exposes management entries for Equipment Rack and Achievements, while Settings omits both and retains its global groups. Set Ready's existing workout-scoped rack selection/management access is intentionally preserved; this row does not claim Profile is the only rack route. Evidence: `equipment-rack-route.xml`, `achievements-route.xml`, `profile-a-top.xml`, `profile-a-mid.xml`, and `settings-*.xml`. |
| 7 | **PASS** | Home edge gestures and Just Lift expose no profile selector. B's VBT is off while historical assessment, velocity, and five-session information remain visible. Live no-trainer behavior is covered by the 20-test runtime suite below. Evidence: `home-edge-gestures.xml`, `just-lift.xml`, `profile-b-vbt.xml`, `profile-b-top.xml`, and `profile-b-history.xml`. |
| 8 | **PASS** | The actual named tag was force-replayed offline, the acceptance-code APK upgraded via `install -r`, the migration gate completed with one normalized row, Ready rendered, and a UI-created second profile persisted every product default without inherited legacy/safety values. Evidence: `snapshot-attempt1/`, `named-snapshot-replay/`, and `new-profile-defaults/`. |

No `Switch Profile` or `Profiles` content selector appeared in Home or Just Lift. The Profiles root sheet was directly confirmed from a TalkBack long action on the focused Profile tab.

## Runtime substitution and hardware bounds

No compatible Vitruvian trainer was present. The no-trainer substitution command was freshly rerun at pre-report revision `361677c2`:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*VerbalEncouragementPreferenceCascadeTest" --tests "*SafeWordDetectionManagerTest" --tests "*AdultModePresentationTest" --tests "*VbtEnabledRuntimeTest" --rerun-tasks --console=plain
```

It exited `0` and reported `BUILD SUCCESSFUL in 4m 10s`. Fresh XML totals are:

| Suite | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `VerbalEncouragementPreferenceCascadeTest` | 11 | 0 | 0 | 0 |
| `SafeWordDetectionManagerTest` | 2 | 0 | 0 | 0 |
| `AdultModePresentationTest` | 3 | 0 | 0 | 0 |
| `VbtEnabledRuntimeTest` | 4 | 0 | 0 | 0 |
| **Total** | **20** | **0** | **0** | **0** |

The fresh command, complete native output, XML totals, and exit are in `automated-gates-361677c2/runtime-substitution-transcript.txt` and `runtime-substitution-native-output.txt`. The earlier acceptance-code streams, metadata, copied XML, totals, and hashes remain under `runtime-20-tests/`.

No physical Android device was connected. A person hearing the label and physically feeling the haptic remain **UNAVAILABLE** claims. The emulator evidence does prove TalkBack's exact speech output, the resulting click/long-click app states, and the app-originated `HEAVY_CLICK` request. Live trainer telemetry is likewise **UNAVAILABLE** and remains covered by the reviewed no-trainer substitution suite.

## Fresh current-revision automated release gates

All required and focused non-interactive gates below were freshly run at pre-report revision `361677c2`. Test rows overlap by design, so their counts are reported independently rather than summed.

| Gate | Fresh result |
| --- | --- |
| New QA contracts | 18 tests; 0 failures, errors, or skips |
| Repository regressions | 38 tests; 0 failures, errors, or skips |
| Profile/navigation suite | 32 tests; 0 failures, errors, or skips |
| Profile screen/preferences/runtime | 109 tests; 0 failures, errors, or skips |
| Settings/profile persistence | 158 tests; 0 failures, errors, or skips |
| Ownership/navigation | 34 tests; 0 failures, errors, or skips |
| SQLDelight generation/migration/manifest | **PASS**; schema 43, 389 columns, 46 tables |
| Schema/migration tests | 49 tests; 0 failures, errors, or skips |
| Sync/transport/privacy | 206 tests; 0 failures, errors, or skips |
| Repository race/migration | 57 tests; 0 failures, errors, or skips |
| Koin graph | 1 test; 0 failures, errors, or skips |
| Full shared Android-host suite | 2,822 tests; 0 failures, errors, or skips; no skipped Profile suites |
| Android shared-main and iOS main/test compilation | **PASS** |
| Android debug unit, lint, debug APK, release APK | 49 tests with no failures/errors/skips; lint and both packages **PASS** |
| Standalone Profile navigation contract | 8 tests; 0 failures, errors, or skips |
| No-trainer runtime substitution | 20 tests; 0 failures, errors, or skips |

Preflight also passed its 256-source inventory, and the local-only/privacy audit confirmed the tracked handoff, complete DI, sole profile-switcher ownership in `ProfileSwitcherViewModel`, absent legacy selectors and service-role material, and zero portal targets.

Commit `31a4e761` adds `verifyQaReleaseBoundary`, an explicit Gradle verification task that depends on `assembleRelease`, fails when the release APK or merged manifests are absent, and consumes the same tracked seven-marker inventory as the hermetic unit contract. The final isolated command was:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' '-Pversion.code=999999' :androidApp:verifyQaReleaseBoundary --rerun-tasks --console=plain
```

It rebuilt the release app and exited `0`. The independent release audit scanned the same seven forbidden markers across three release manifests and 705 entries in one release APK, finding zero leaks. The unsigned APK was 17,055,838 bytes, SHA-256 `c757472f734daf6c9188553106cd5f7c63cec0a21a5559cc5fd97e2443e0519a`, with verified `versionCode=999999`. The combined pinned build's debug APK was 40,998,548 bytes, SHA-256 `60a59ee17b1693d2ce4c274c33179e7fa9c0e97e40490594bd53fe6b93d9eef8`, also with `versionCode=999999`; it is distinct from the version-code-5 APK used for TalkBack acceptance above.

The first orchestration script had a stale expected count of 17 new QA tests and stopped after Gradle correctly passed 18; the run resumed with the assertion corrected in memory. Its outer one-hour ceiling later expired with exit `124` only after Android unit, lint, both packages, and the independent release scan had logged **PASS**, while entering full Spotless. No required product gate was lost: Spotless and `verifyQaReleaseBoundary` were then run as isolated commands, producing their definitive exits.

Evidence is under `automated-gates-361677c2/`: `task5-361677c2-fresh-transcript.txt`, `task5-361677c2-resumed-transcript.txt`, `verify-qa-release-boundary-transcript.txt`, `profile-navigation-contract-transcript.txt`, `runtime-substitution-transcript.txt`, `runtime-substitution-native-output.txt`, and `final-audit.txt`. The boundary transcript records the exact command and exit `0`; the resumed harness transcript independently records `RELEASE QA AUDIT PASS manifests=3 apks=1 entries=705 leaks=0`.

## Spotless result and non-blocking baseline provenance

The historical Task 4 non-mutating command was:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' spotlessCheck --console=plain
```

It ran at exact acceptance HEAD, exited `1`, and reported `BUILD FAILED in 15s`. The first failing task was `spotlessKotlinGradleCheck`; its sole reported file was `androidApp/build.gradle.kts`, with LF endings in source lines **333–338**:

```text
333:     testImplementation(libs.koin.test)
334:     testImplementation(libs.koin.test.junit4)
335:     testImplementation(libs.ktor.client.mock)
336:     testImplementation(libs.multiplatform.settings)
337:     testImplementation(libs.multiplatform.settings.test)
338: }
```

Full Task 4 stdout, stderr, exit, metadata, the numbered source excerpt, and hashes are under `spotless/`. `spotless.stderr.txt` SHA-256 is `6dcbcad769728ef4b7e875d94c2e672bfa9346f25d956bd8de237fc35c04347e`; the exit capture SHA-256 is `f1b2f662800122bed0ff255693df89c4487fbdcf453d3524a42d4ec20c3d9c04`. No `spotlessApply` command ran during Task 4, and that run left source state clean.

The earlier wording called this violation pre-existing. That was inaccurate: the readiness range added three test dependencies with LF endings to a CRLF working-tree tail, producing the mixed six-line hunk. Follow-up commit `31a4e761` normalized `androidApp/build.gradle.kts`; the fresh command below passes:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :spotlessKotlinGradleCheck --rerun-tasks --console=plain
```

The fresh `:spotlessKotlinGradleCheck --rerun-tasks` exits `0`. Full `spotlessCheck --rerun-tasks` proceeds beyond that corrected Gradle task but exits `1`; the current inventory is 192 Kotlin files. It remains a real failed command and is not relabeled as passing. The prior branch-protection audit listed Unit Tests, Lint Check, iOS Schema Sync Check, iOS Test Target Compile, and Build Android as required check contexts; it did not list full `spotlessCheck`. This is narrower than claiming no repository automation invokes Spotless: the repository also contains a Security Hygiene workflow job and local agent-quality scripts. The formatting baseline is tracked as separate process debt rather than a blocker in the audited branch-protection set. Fresh exits and the inventory are recorded in `automated-gates-361677c2/spotless-kotlin-gradle-check-transcript.txt`, `spotless-check-transcript.txt`, and `final-audit.txt`. A historical diagnostic `spotlessApply` briefly materialized the mechanical rewrite; all unrelated formatter-only edits were reverted before commit rather than mixing a 192-file style migration into this feature branch.

## Cleanup and immutability

All disposable AVDs were shut down and removed only after absolute-path checks kept deletion inside `C:\Users\dasbl\.android\avd`. Final ADB inventory was empty. The immutable source snapshot hashes before and after all replay/matrix work are identical:

| Source payload | SHA-256 |
| --- | --- |
| `hardware.ini` | `d2f55bbb9fc06476293c2460b9e743bd40ba0fd70e7bc13ec9a3155c10f03938` |
| `ram.bin` | `eabce3b62cacaa5feff842e6e26484759db4a4ec1f41c31998cf952b4b2aeda9` |
| `screenshot.png` | `8a9f899b79141dcbc9a39a2e66ba2d8710a3aae5ac46db3242d0d2084a6fc480` |
| `snapshot.pb` | `53c714141ddad2efcae4884cec0d1a02e87ec498b5693ff86f8063f6e10228a9` |
| `textures.bin` | `9da9d94e2e86016384b8b9647e4af8f70fa25381ccc4c9366c8be5ffc8777b5b` |

Evidence: `immutable-source-before.sha256.txt`, `immutable-source-after.sha256.txt`, `immutable-source-compare.txt`, the two replay cleanup files, `matrix-8103281b/clone-delete-audit.txt`, and the final TalkBack run's `15-delete-audit.txt`, `15-avds-after.txt`, `15-adb-after.txt`, and `15-immutable-after-sha256.txt`.

Before this final validation update, `git status --short` was empty at branch HEAD `361677c22e24e8c0997f519b55920197e5f0d146`. Historical acceptance-code runtime and Spotless commands temporarily detached the already-isolated worktree at `8103281b`, left source state clean, and restored the branch. The fresh current-revision automation left the index clean, passed worktree/range whitespace checks, changed no source file, and saw only this in-progress tracked Markdown update. External automated transcripts, TalkBack transcripts, screenshots, AOSP input event files, and the rewritten `.superpowers/sdd/task-4-report.md` remain ignored evidence.
