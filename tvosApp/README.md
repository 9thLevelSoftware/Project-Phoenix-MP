# Phoenix TV beta tooling

Standalone tvOS application, not phone mirroring. Project/scheme: `PhoenixTV`;
bundle: `com.devil.phoenixproject.projectphoenix`; minimum tvOS: 17.0.
The #1288 beta targets the 1.0.8 release intent, but tooling derives version
values from the checkout rather than pinning them to that intent.

## Scope and acceptance

These scripts implement a release path; they are not evidence that the TV app
has passed native builds, signing, upload, or real-device BLE tests. The parent
owns native runs and integration of the already-merged version PR #1349.
Do not create another version bump or modify phone release workflows.

`.github/workflows/tvos-build.yml` runs unsigned native builds and tests on
relevant pull requests or manual invocation, without signing or cloud secrets.
The separate `.github/workflows/tvos-testflight.yml` is manual/reusable only:
no push, PR, schedule or `release-all` triggers, and upload defaults to false.
Its signed path remains gated on independent review of the exact source SHA;
unsigned CI success is not a TestFlight delivery claim.

## Offline tooling checks

Use Python 3.10+ (CI pins 3.12). Apple's system Python 3.9 cannot execute the
existing version helper's `Path.write_text(newline=...)` write path. On the
canonical development Mac, `/opt/homebrew/bin/python3` is a suitable interpreter.
Run from the repository root:

```sh
python3 .agents/skills/update-phoenix-version/scripts/test_update_version.py
python3 .github/scripts/tvos-tooling-tests.py
bash -n .github/scripts/tvos-framework.sh
shellcheck .github/scripts/tvos-framework.sh
actionlint .github/workflows/tvos-testflight.yml
plutil -lint tvosApp/PhoenixTV.xcodeproj/project.pbxproj tvosApp/PhoenixTV/Info.plist
xmllint --noout tvosApp/PhoenixTV.xcodeproj/xcshareddata/xcschemes/PhoenixTV.xcscheme
python3 .github/scripts/tvos-release.py preflight
```

Tests use temporary repositories, fake command executables and synthetic
provisioning/build metadata. They never invoke Gradle, xcodebuild, signing,
upload or a network API. These are tooling tests, not passing native app tests.

`preflight` is offline and read-only. It fails closed unless Android
`versionName`, shared `Constants.APP_VERSION`, and both iOS/TV marketing
versions agree. Both Apple projects' checked-in build defaults must also agree;
Android's separate default `versionCode` must exist but is not numerically
compared with Apple's build number. There is no hardcoded release-version gate.
An unintegrated checkout with phone/shared 1.0.7 and TV 1.0.8 intentionally fails.

## Unsigned local native builds and UI tests (parent-owned execution)

Prerequisites: Apple Silicon for simulator builds, selected Xcode with a tvOS
SDK/runtime, Java 17, and Python 3.10+. Resource/SQLDelight generation is supplied
by the existing `shared-tvos` Gradle dependencies. No local cloud or signing
credentials are needed. Do not run competing native jobs in this worktree.

```sh
# Optional: put DerivedData on the external SSD rather than the internal disk.
export TVOS_DERIVED_DATA=/Volumes/Untitled/PhoenixBuilds/tvos-debug
python3 .github/scripts/tvos-release.py local build

# Exact UDID from xcrun simctl list devices available; never use "booted".
python3 .github/scripts/tvos-release.py local test \
  --simulator <TV-SIMULATOR-UDID> \
  --result-bundle /Volumes/Untitled/PhoenixBuilds/tvos-tests-new-run.xcresult

# Unsigned device-target build (not an installable/signing verification artifact).
python3 .github/scripts/tvos-release.py local build \
  --sdk appletvos --configuration Release \
  --derived-data /Volumes/Untitled/PhoenixBuilds/tvos-device-release
```

Use a fresh dedicated local simulator for reproducible EULA/database state;
the local runner never erases or deletes your simulators. CI creates and deletes
only its own fresh Apple TV 4K simulator and fails if no TV runtime is installed.
A new result-bundle path is required for every test run to preserve old evidence.

The runner disables code signing, supplies an explicit blank cloud xcconfig,
and checks the copied exercise catalog. A local parity mismatch is printed as
a warning so native development can precede parent version integration. This
exception exists only for unsigned local work: release parity has no bypass.
Xcode/Gradle output is suppressed to prevent accidentally printing injected
configuration; the exact subprocess exit code is retained. Native UI results
are in the requested `.xcresult`, not inferred from a build or a screenshot.

The Xcode build phase calls `.github/scripts/tvos-framework.sh` exactly once.
It selects:

| SDK | Debug task | Release task |
| --- | --- | --- |
| appletvsimulator | `:shared-tvos:linkDebugFrameworkTvosSimulatorArm64` | `:shared-tvos:linkReleaseFrameworkTvosSimulatorArm64` |
| appletvos | `:shared-tvos:linkDebugFrameworkTvosArm64` | `:shared-tvos:linkReleaseFrameworkTvosArm64` |

One Gradle worker, no parallel Gradle execution, and one Xcode job prevent
concurrent native compiler processes. The generated `PhoenixTvCore.framework`
is copied into `$(BUILT_PRODUCTS_DIR)/KotlinFrameworks`, matching the live
project's framework search path. Never copy Debug into a Release archive or
fall back to a stale framework after a compiler failure.

## Real telemetry versus deterministic simulator fixtures

Device framework composition uses `KableBleRepository` and the vendored
Kable/CoreBluetooth implementation with the shared Phoenix workout core.
The simulator factory uses Phantom only when both `Platform.isDebugBinary`
and the explicit `--phoenix-tv-phantom` launch argument are present. The native
UI tests own that argument for their fixture test; the normal test does not
supply it. There is no automatic fake device or telemetry fallback.

The release gate checks these source boundaries and rejects recognizable
Phantom transport markers in the archived device executable. These are defense
in depth, not a proof that arbitrary code cannot synthesize telemetry. An
independent review of the production factory, shared-core wiring and safety
behavior remains mandatory, as do parent-owned native checks. Release device
and debug simulator paths must not be conflated in an acceptance report.

The existing Xcode Resources phase copies
`shared/src/commonMain/composeResources/files/exercises.json` into the app root
as `exercises.json`, where the TV loader expects it. Local and archive tooling
compare the packaged bytes with the source catalog; success cannot be claimed
if the file is absent or stale. Do not package the static framework's resources
inside the framework: static linking would discard them.

## TV privacy manifest and packaged-bundle checks

`PhoenixTV/PrivacyInfo.xcprivacy` is a resource of the **PhoenixTV application**
target. Xcode must copy it to `PhoenixTV.app/PrivacyInfo.xcprivacy`; placing it
only in the source tree or `PhoenixTvCore.framework` is insufficient because
that Kotlin framework is statically linked, not embedded as a resource bundle.
The phone manifest was compared, not used as evidence of TV feature support.

The required-reason declarations are deliberately limited to:

| Category / Apple reason | TV/shared usage and permitted purpose |
| --- | --- |
| UserDefaults / `CA92.1` | `PlatformModule.tvos.kt` binds `NSUserDefaultsSettings` to standard app defaults, migrates the app's legacy auth settings, and reads/writes its installation marker. `LocaleHelper.tvos.kt` writes the app-local language override. Apple permits reading/writing information accessible only to this app, not deliberate access to other apps' or system-owned defaults. |
| FileTimestamp / `C617.1` | `IosDatabaseFileOperations.kt` in `tvosMain` reads `NSFileSize` through `attributesOfItemAtPath` for database/sidecar recovery and fingerprint checks inside the app container. Apple's reason explicitly covers timestamps, **size, or other metadata**, not just dates. `UriContentReader.tvos.kt` also contains bounded local-file size reads; this does not establish a TV document-picker feature. |
| SystemBootTime / `35F9.1` | `PlatformUtils.tvos.kt` supplies elapsed time for shared workout/rest timers; `TvAppBridge.kt` uses `TimeSource.Monotonic` for elapsed watchdog intervals. This is conservative coverage of elapsed-event timing, not a claim that `NSDate` requires a reason or that the inspected Debug static library directly imports `mach_absolute_time`. Apple permits in-app elapsed intervals/timers; raw boot-time information must not be sent off-device. |

No available-volume/disk-capacity API was found in the inspected TV/shared
sources; file size and SQLite `freelist_count` are not free-disk-space queries.
Do not add a DiskSpace declaration without a matching API usage and reason.
TV `HealthIntegration` returns unavailable, and no native speech API use was
found in the TV sources. This manifest does not assert phone HealthKit/speech
capabilities or a blanket "no data collected" policy for cloud-enabled TV builds.
Privacy data disclosures and third-party SDK obligations need their own review.

Official Apple references (including the precise restrictions on each reason):

- [Required-reason API categories and approved reasons](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype)
- [Describing use of required reason API](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api)
- [Privacy manifest files and target resource membership](https://developer.apple.com/documentation/bundleresources/privacy-manifest-files)

`verify_app(..., production=True)` checks the archived app's manifest before
export. The same check runs with `production=False` for an unsigned local
bundle. It parses XML or binary plists and rejects missing/unreadable manifests,
invalid shapes, missing/duplicate categories, and unexpected or duplicate reason
codes against the reviewed TV policy. Update the policy and tests together when
actual API usage changes; this is not a general-purpose Apple schema validator.

The parent-owned `local()` runner also calls `verify_privacy_manifest(app)`
after building and checking the exercise resource. To validate an existing
local bundle independently and read-only, run from the repository root (supply
its real path):

```sh
python3 -c 'import runpy, sys; from pathlib import Path; m = runpy.run_path(".github/scripts/tvos-release.py"); v = m["read_versions"](m["ROOT"]); m["verify_app"](Path(sys.argv[1]), v, v["apple_build"], production=False); print("Local bundle identity, catalog and privacy validated")' /absolute/path/to/PhoenixTV.app
plutil -lint tvosApp/PhoenixTV/PrivacyInfo.xcprivacy tvosApp/PhoenixTV.xcodeproj/project.pbxproj
python3 .github/scripts/tvos-tooling-tests.py
```

For a build-number override, pass the expected override to `verify_app` rather
than the checked-in `apple_build`. Offline fixtures and PBX resource membership
are not native packaging proof: rebuild and inspect the actual app, archive,
and exported IPA. App Store Connect processing/acceptance remains an unverified
external gate; this change is neither a submission nor release approval.

## Signed archive / TestFlight workflow

Do not execute until independent review and beta acceptance are complete.
For both manual and reusable calls, provide:

- `source_sha`: full lowercase commit SHA. It must equal the selected workflow
  revision (`github.sha`) and the checked-out HEAD. No arbitrary branch refs.
- `review_pr`: a PR in this repository with a current-head `APPROVED` review from
  an independent OWNER/MEMBER/COLLABORATOR, not its author, **or** the automated
  reviewer attestation described below. Any unresolved changes-requested review
  blocks. The exact merged commit is also accepted; unrelated later commits are not.
- `review_accepted: true`: explicit independent review/beta acceptance. A boolean
  alone cannot bypass the checked review evidence.
- `upload`: false for archive/export only; true opts into upload and verification.

### Automated review without a human approval gate

The independent downstream reviewer may share the repository owner's GitHub
identity; GitHub does not allow that account to approve its own PR. After a
separate spec/safety review, that reviewer (never the implementation worker)
posts a durable PR comment naming its task/run, exact release SHA, review
findings and evidence. It then records a commit status on that **exact SHA**:
context `tvOS independent review`, state `success`, description
`Independent tvOS review accepted`, and `target_url` equal to the PR comment URL.
Only a status created by the repository owner is trusted; the latest status for
that context wins, so pending/failure revokes acceptance. A merge-SHA release
requires a fresh attestation on that merge SHA. The workflow reads every status
page and rechecks before signing/upload; it has read-only status permissions.

This is the trusted reviewer's auditable attestation, not cryptographic proof
of reviewer independence. The task graph enforces separate implementation and
review runs. Do not manufacture this receipt in an implementation run, ask for
unnecessary human approval, or treat it as a substitute for required exact-head CI.

A reusable caller must check out/run the same reviewed revision. Pass the named
secrets explicitly or use `secrets: inherit` from an authorized caller. The
workflow uses read-only repository/PR permissions, pins third-party action
commits, and never creates tags, commits, pushes, merges, or changes issue state.
Review is rechecked before signing and again before upload.

Gates run serially: acceptance/static checks -> fresh simulator native UI tests
-> signed Release `generic/platform=tvOS` archive/export -> optional upload.
The archive uses the phone's Xcode 26.2 / macos-26-intel convention; upload uses
the separate Xcode 16.4 / macos-15 convention. This reuse is not proof that the
TV upload path has been exercised. Missing runner SDKs/runtimes fail the job.

Repository secrets required (values must never be pasted into logs or docs):

| Purpose | Names |
| --- | --- |
| Shared distribution certificate | `BUILD_CERTIFICATE_BASE64`, `P12_PASSWORD`, `KEYCHAIN_PASSWORD`, `TEAM_ID` |
| TV-only App Store profile | `TVOS_PROVISION_PROFILE_BASE64`, `TVOS_PROVISIONING_PROFILE_NAME` |
| Shared public client cloud config | `SUPABASE_URL`, `SUPABASE_ANON_KEY` |
| Shared ASC API credentials/app | `APPSTORE_API_KEY`, `APPSTORE_API_KEY_ID`, `APPSTORE_ISSUER_ID`, `APP_APPLE_ID` |

Phone `PROVISION_PROFILE_BASE64` / `PROVISIONING_PROFILE_NAME` are never used.
The profile must name this exact team/bundle, list tvOS, be unexpired, disable
`get-task-allow`, and contain neither development/ad-hoc devices nor enterprise
provisioning. Provisioning profiles and ASC setup are externally managed; this
workflow does not create/repair them. Missing configuration blocks release.

Supabase values are written to a private mode-0600 temporary xcconfig, with URL
slashes escaped for Xcode. The app plist must contain the exact expected values
after building; unresolved substitutions or `https:` truncation are errors.
Only public anon/publishable client keys are allowed, never a service-role or
secret key. Client keys necessarily ship in the app, so server-side RLS remains
mandatory. No phone/local config file is rewritten. Certificate/profile/API key
material is temporary; signing uses an ephemeral keychain, restores the prior
search list and deletes only its own installed profile. Sensitive command
output is not printed or uploaded as a build log.

CI overrides only the TV archive build number (UTC date + run number + a padded
run-attempt suffix); it does not edit checked-in version defaults. The archive
and exported IPA are signed manually with the TV profile. IPA metadata records
the reviewed source SHA, platform, bundle, marketing version and build. Only the
IPA and release manifest are retained for one day, not keychains, configs,
profiles, DerivedData or archive build logs. Simulator results are separate and
use the blank/offline cloud configuration.

Upload uses `altool --type appletvos`. Exit zero alone is insufficient: the
script queries ASC for the exact app, TV_OS platform, marketing version and
build until processing is VALID, refreshing its short-lived token each query.
An invalid build, API error, ambiguous response or processing timeout fails.
The workflow intentionally does not mutate tester groups, beta-review requests,
release notes, app records or export-compliance records. Those delivery details
are separate accepted release operations; upload success is not a claim that
external testers received the build.

## Remaining release evidence

Before a real dispatch, the parent must integrate the existing version PR,
obtain independent review acceptance, execute the native simulator/device
build/test paths, validate release app assets/icons, and confirm the TV profile
and shared ASC configuration are provisioned. Real Apple TV BLE behavior and
remote/safety interaction remain hardware beta checks, not tooling-test claims.
See `.scratch/release-tooling-report.md` for this implementation's actual command
exit codes and deliberately unexecuted stages.
