# CI/CD & GitHub Workflows Review

Scope reviewed:
- `.github/workflows/android-playstore.yml`
- `.github/workflows/android-release-apk.yml`
- `.github/workflows/ci-tests.yml`
- `.github/workflows/ios-release-ipa.yml`
- `.github/workflows/ios-testflight-internal.yml`
- `.github/workflows/ios-testflight.yml`
- `.github/workflows/release-all.yml`
- `.github/FUNDING.yml`
- `.github/ISSUE_TEMPLATE/bug_report.yml`
- `.github/ISSUE_TEMPLATE/feature_request.yml`

Validation performed:
- Parsed all 10 YAML files successfully with PyYAML.
- Ran `go run github.com/rhysd/actionlint/cmd/actionlint@latest .github/workflows/*.yml`; it reported shellcheck warnings, including unquoted shell variables in release/signing scripts and unquoted `ARGS` expansion in `release-all.yml`.

## Summary

Findings count: 27

Severity breakdown:
- Critical: 0
- High: 4
- Medium: 18
- Low: 5

Category breakdown:
- Bug: 3
- Stub: 1
- Error: 5
- Failure-point: 18

---

## `.github/workflows/android-playstore.yml`

### Finding 1
- Category: failure-point
- Severity: medium
- Line numbers: 19-21, 178-188
- Description: The Play Store publishing workflow uses `cancel-in-progress: true`. Cancelling a release job that is already creating Google Play edits, building signed artifacts, or uploading to the beta track can leave a partially completed external release operation or make the next run race against state created by the cancelled run.
- Suggested fix direction: Do not cancel in-progress store-publishing runs. Use `cancel-in-progress: false`, include the release/tag/version in the concurrency group, or add explicit preflight/status checks before cancelling any run with external side effects.

### Finding 2
- Category: failure-point
- Severity: medium
- Line numbers: 168-177
- Description: The Health Connect permission verification logs every packaged `android.permission.health.*` permission but only asserts `READ_WEIGHT`. The app manifest declares `WRITE_EXERCISE`, `WRITE_TOTAL_CALORIES_BURNED`, and `READ_WEIGHT`, so a packaging regression could drop one of the write permissions while this workflow still passes.
- Suggested fix direction: Assert every required Health Connect permission explicitly, preferably by maintaining an expected-permission list in the workflow or a checked-in script.

### Finding 3
- Category: error
- Severity: medium
- Line numbers: 151-166
- Description: The decoded Android signing keystore is removed only after the Gradle command succeeds. If `bundleRelease` or `verifyReleaseCueResources` fails, `rm keystore.jks` is skipped and the signing material remains in the workspace for the rest of the job.
- Suggested fix direction: Create the keystore under `$RUNNER_TEMP` and register a shell `trap 'rm -f "$KEYSTORE_PATH"' EXIT` before decoding it.

---

## `.github/workflows/android-release-apk.yml`

### Finding 4
- Category: failure-point
- Severity: medium
- Line numbers: 14-16, 189-196
- Description: The release-asset workflow uses `cancel-in-progress: true` even though it uploads an APK asset to a GitHub Release. A cancellation during signing, renaming, or upload can leave the release missing the APK or with an asset from an older run if another run races it.
- Suggested fix direction: Avoid cancelling release-publishing jobs, or scope concurrency by release tag/version and perform idempotent asset replacement only after the build artifact has been fully produced.

### Finding 5
- Category: failure-point
- Severity: medium
- Line numbers: 53-67, 189-196
- Description: The workflow always attaches the APK to `gh release view`'s latest release instead of requiring an explicit tag or using a release event payload. A manual run after a newer draft/prerelease or an unintended latest release can upload the APK to the wrong release.
- Suggested fix direction: Add a required `releaseTag` workflow input, validate that it exists, and upload to that tag; alternatively trigger this workflow from `release.published` with `github.event.release.tag_name`.

### Finding 6
- Category: error
- Severity: medium
- Line numbers: 162-177
- Description: The decoded signing keystore is removed only after the Gradle command succeeds. If the build or resource verification fails, `keystore.jks` remains in the workspace for later steps or diagnostics.
- Suggested fix direction: Decode to `$RUNNER_TEMP/keystore.jks` and clean it with an `EXIT` trap so failure paths also remove the secret material.

### Finding 7
- Category: failure-point
- Severity: low
- Line numbers: 179-186
- Description: `APK_PATH=$(find ... | head -1)` does not verify that exactly one release APK exists before copying it. If no APK is produced, the failure is an opaque `cp` error; if multiple APKs are produced, the selected artifact is filesystem-order dependent.
- Suggested fix direction: Collect matching APKs into an array, fail with a clear message when the count is not exactly one, and then copy the selected path.

---

## `.github/workflows/ci-tests.yml`

### Finding 8
- Category: bug
- Severity: high
- Line numbers: 142-174
- Description: `ios-target-tests-compile` runs on `ubuntu-latest` while invoking `:shared:compileKotlinIosArm64` and `:shared:compileTestKotlinIosArm64`. The shared Gradle file defines a real `iosArm64` Kotlin/Native target with Apple frameworks, which normally requires a macOS/Xcode host; running this job on Ubuntu is likely to fail or to be skipped by Gradle in ways that do not actually validate the iOS target.
- Suggested fix direction: Move this job to a macOS runner, or replace it with a Linux-compatible metadata/common compilation check and keep iOS target compilation on macOS.

### Finding 9
- Category: failure-point
- Severity: medium
- Line numbers: 3-14, 142-148
- Description: The workflow triggers on pushes to `main`, `mvp`, `beta*`, and `feature/*`, but the iOS compile job only runs for workflow dispatch, pull requests, and `refs/heads/main`. Direct pushes to `mvp`, `beta*`, or feature branches can pass CI without any iOS-target compilation coverage.
- Suggested fix direction: Align the job condition with the workflow branch trigger, or document and enforce that iOS validation only happens through pull requests.

---

## `.github/workflows/ios-release-ipa.yml`

### Finding 10
- Category: failure-point
- Severity: medium
- Line numbers: 78-90
- Description: The build-number step uses `sed` to rewrite `CURRENT_PROJECT_VERSION` but only prints the first matching lines afterward. It does not verify that any replacement occurred or that all relevant build configurations now have the generated build number, so a project-file format change can silently leave a stale build number until App Store export/upload fails later.
- Suggested fix direction: Count replacements or parse the project after editing and fail immediately unless every expected `CURRENT_PROJECT_VERSION` matches `BUILD_NUM`.

### Finding 11
- Category: failure-point
- Severity: medium
- Line numbers: 166-167
- Description: The workflow hardcodes `/Applications/Xcode_26.2.app/Contents/Developer`. GitHub-hosted macOS images can change installed Xcode patch versions, and self-hosted runners may not use this exact path, causing the release to fail before archive.
- Suggested fix direction: Select Xcode via a configurable input/environment variable, use `xcode-select -p` with validation, or select the highest matching installed Xcode version dynamically.

### Finding 12
- Category: failure-point
- Severity: low
- Line numbers: 213-220
- Description: `IPA_PATH=$(find $RUNNER_TEMP/export -name "*.ipa" | head -1)` does not verify that exactly one IPA exists. Missing or multiple IPA outputs produce either an opaque copy failure or nondeterministic artifact selection.
- Suggested fix direction: Quote `$RUNNER_TEMP`, gather IPA matches into an array, and fail with a clear diagnostic unless there is exactly one IPA.

### Finding 13
- Category: error
- Severity: low
- Line numbers: 169-191, 232-234
- Description: The cleanup step deletes the temporary keychain, but the provisioning profile copied to `~/Library/MobileDevice/Provisioning Profiles` is not removed. On GitHub-hosted runners this is usually short-lived, but the workflow is unsafe if ever moved to a persistent/self-hosted macOS runner.
- Suggested fix direction: Track the copied provisioning profile path and remove it in the same `if: always()` cleanup step that deletes the keychain.

---

## `.github/workflows/ios-testflight-internal.yml`

### Finding 14
- Category: failure-point
- Severity: medium
- Line numbers: 62-75
- Description: The build-number step rewrites `CURRENT_PROJECT_VERSION` with `sed` but does not fail if no project settings were updated or only some configurations were changed. A stale/duplicate build number may only surface at TestFlight upload time.
- Suggested fix direction: Verify replacement counts and assert the final project file contains the generated build number for every expected configuration.

### Finding 15
- Category: failure-point
- Severity: medium
- Line numbers: 156-157
- Description: The workflow selects a hardcoded Xcode path, `/Applications/Xcode_26.2.app/Contents/Developer`. Runner image changes or self-hosted runner differences will break the pipeline even when a compatible Xcode is installed elsewhere.
- Suggested fix direction: Resolve the Xcode path dynamically or configure it through a workflow input/environment variable with a preflight check.

### Finding 16
- Category: error
- Severity: medium
- Line numbers: 210-212, 245-247
- Description: The App Store Connect private key is written to `~/.appstoreconnect/private_keys/AuthKey_${APPSTORE_API_KEY_ID}.p8`, but the cleanup step only deletes the signing keychain. The API private key remains on disk for the rest of the runner lifetime.
- Suggested fix direction: Write the API key under `$RUNNER_TEMP` when possible, or remove `~/.appstoreconnect/private_keys/AuthKey_${APPSTORE_API_KEY_ID}.p8` in an `if: always()` cleanup step.

---

## `.github/workflows/ios-testflight.yml`

### Finding 17
- Category: failure-point
- Severity: medium
- Line numbers: 62-76
- Description: The build-number step uses `sed` and then prints matching `CURRENT_PROJECT_VERSION` lines, but it does not assert that the generated build number was actually applied to all expected project settings. Duplicate/stale build numbers can escape this step and fail much later in App Store Connect.
- Suggested fix direction: Validate the edited project file and fail immediately if any expected build setting does not equal `BUILD_NUM`.

### Finding 18
- Category: failure-point
- Severity: medium
- Line numbers: 157-158
- Description: The workflow hardcodes `/Applications/Xcode_26.2.app/Contents/Developer`, making TestFlight release dependent on one exact runner image layout.
- Suggested fix direction: Discover/select Xcode dynamically or make the Xcode path configurable with a clear preflight error when missing.

### Finding 19
- Category: error
- Severity: medium
- Line numbers: 210-212, 605-607
- Description: The App Store Connect API private key is written into `~/.appstoreconnect/private_keys`, but the final cleanup only deletes the signing keychain. The API key file remains on disk until the runner is destroyed.
- Suggested fix direction: Store the key in `$RUNNER_TEMP` if supported by the upload tooling, or delete the generated `.p8` file in the `if: always()` cleanup step.

### Finding 20
- Category: failure-point
- Severity: medium
- Line numbers: 366-407
- Description: The build-processing polling loop does not capture or check HTTP status for the `builds` API requests. App Store Connect authentication, rate-limit, or server errors can be parsed as `NOT_FOUND`, causing the job to wait for the full timeout and report a misleading processing-timeout failure.
- Suggested fix direction: Use `curl -f` or capture `%{http_code}` for each poll, fail fast on non-2xx responses, and print the response body with secrets redacted.

### Finding 21
- Category: bug
- Severity: high
- Line numbers: 538-588
- Description: Failures to add the uploaded build to the requested TestFlight group or submit it for Beta App Review are logged as warnings and do not fail the workflow. The job can finish successfully even though the build was not distributed to testers or submitted for external review.
- Suggested fix direction: Treat group-assignment and beta-review submission failures as fatal for external TestFlight workflows, or add an explicit `internalOnly`/`allowDistributionWarnings` input that controls whether these failures may be ignored.

### Finding 22
- Category: failure-point
- Severity: low
- Line numbers: 590-603
- Description: The release-notes tracking tag push failure is swallowed with `|| echo`. If the tag is not pushed, future TestFlight release notes use the wrong baseline while the workflow still reports success.
- Suggested fix direction: Fail the workflow when tag creation/push fails, or persist the baseline another way and make the non-fatal behavior explicit in the workflow inputs.

---

## `.github/workflows/release-all.yml`

### Finding 23
- Category: bug
- Severity: high
- Line numbers: 40-78
- Description: `gh workflow run` is called without `--ref`, so child workflows are dispatched on the target workflow's default branch rather than necessarily using the same ref/branch/commit that triggered `release-all.yml`. A release-all run from a release branch can therefore publish artifacts built from a different revision.
- Suggested fix direction: Pass `--ref "${{ github.ref_name }}"` or a required release ref input to every `gh workflow run` call, and include that ref in the summary.

### Finding 24
- Category: failure-point
- Severity: high
- Line numbers: 40-89
- Description: The workflow only dispatches the platform workflows and writes a summary saying they were triggered. It does not wait for the child workflow runs, collect their conclusions, or fail when a child release fails, so `Release All Platforms` can be green while Android/iOS publication failed asynchronously.
- Suggested fix direction: Capture the dispatched run IDs and poll them to completion, or convert this into a reusable workflow/job dependency graph so the orchestrator reflects the real release outcome.

### Finding 25
- Category: failure-point
- Severity: medium
- Line numbers: 45-61
- Description: `versionCodeOverride` is interpolated directly into shell snippets before downstream validation, and the Android APK step builds an unquoted `ARGS` string expanded into `gh workflow run`. Actionlint/shellcheck flags this as word-splitting-prone; malformed manual input can break the shell command or pass unintended flags.
- Suggested fix direction: Validate the override as `^[0-9]+$` in `release-all.yml` before dispatching, use Bash arrays for both Android dispatches, and quote all expansions.

### Finding 26
- Category: failure-point
- Severity: medium
- Line numbers: 40-62
- Description: When no `versionCodeOverride` is provided, `release-all.yml` starts Android Play Store and Android Release APK workflows independently. Both derive their version from current Google Play max; depending on timing, the APK may build with a different version code than the Play Store AAB or both may assume the same next code without coordination.
- Suggested fix direction: Resolve the Android version code once in the orchestrator, pass the same validated override to both Android workflows, or make the APK workflow consume the Play Store workflow's selected version output.

---

## `.github/FUNDING.yml`

### Finding 27
- Category: stub
- Severity: low
- Line numbers: 3-15
- Description: The file still contains the generated placeholder funding entries/comments for every platform except Ko-fi. The unused null keys are not harmful YAML, but they are template residue and make the funding configuration look incomplete.
- Suggested fix direction: Remove unused placeholder keys and keep only configured funding providers, or fill in the intended accounts/URLs.

---

## `.github/ISSUE_TEMPLATE/bug_report.yml`

No findings. The issue form is valid YAML and includes required platform, OS, app version, device, bug description, reproduction steps, and duplicate-search checklist fields.

---

## `.github/ISSUE_TEMPLATE/feature_request.yml`

No findings. The issue form is valid YAML and captures platform, category, problem, proposed solution, priority, context, and duplicate-search checklist fields.
