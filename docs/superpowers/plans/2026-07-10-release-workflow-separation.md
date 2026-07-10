# Release Workflow Separation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Create releases from the version and commit on main, while allowing an existing release to receive rebuilt assets from main.

**Architecture:** The four platform workflows become reusable workflows with explicit inputs. The two orchestrators call them as dependent jobs, so release assets finish before Google Play and TestFlight publishing begins.

**Tech Stack:** GitHub Actions YAML, Bash, GitHub CLI, PowerShell.

## Global Constraints

- New releases read Android versionName and iOS MARKETING_VERSION from main; all values must match and the derived tag is v<version>.
- A new release fails if its tag already exists.
- APK/IPA uploads require the target release_tag; they never choose the latest release.
- Existing-release rebuilds delete only ProjectPhoenix-<tag>.apk and ProjectPhoenix-<tag>.ipa, then rebuild and publish from main.
- Publication starts only after both artifact builds succeed.

---

## File Structure

- Create: .github/scripts/test-release-workflows.ps1 — workflow contract regression test.
- Modify: .github/workflows/android-release-apk.yml and .github/workflows/ios-release-ipa.yml — reusable asset builds.
- Modify: .github/workflows/android-playstore.yml and .github/workflows/ios-testflight.yml — reusable store publishers.
- Modify: .github/workflows/release-all.yml — new-release orchestration.
- Modify: .github/workflows/release-all-existing.yml — existing-release rebuild orchestration.

### Task 1: Define release workflow contracts

**Files:**
- Create: .github/scripts/test-release-workflows.ps1
- Test: .github/scripts/test-release-workflows.ps1

**Interfaces:**
- Consumes: all six workflow files.
- Produces: a nonzero exit when any required workflow interface or dependency is missing.

- [ ] **Step 1: Write the failing test**

Create this script before changing YAML:

~~~powershell
$ErrorActionPreference = 'Stop'

function Assert-Contains([string] $Path, [string] $Pattern, [string] $Message) {
  if ((Get-Content -Raw $Path) -notmatch $Pattern) { throw "$Path: $Message" }
}

Assert-Contains '.github/workflows/android-release-apk.yml' '(?ms)workflow_call:.*source_ref:.*required: true.*release_tag:.*required: true' 'APK reusable inputs missing.'
Assert-Contains '.github/workflows/android-release-apk.yml' 'ref: .*inputs\.source_ref' 'APK checkout not explicit.'
Assert-Contains '.github/workflows/android-release-apk.yml' 'gh release view "\$\{\{ inputs\.release_tag \}\}"' 'APK target release not verified.'
Assert-Contains '.github/workflows/ios-release-ipa.yml' '(?ms)workflow_call:.*source_ref:.*required: true.*release_tag:.*required: true' 'IPA reusable inputs missing.'
Assert-Contains '.github/workflows/ios-release-ipa.yml' 'ref: .*inputs\.source_ref' 'IPA checkout not explicit.'
Assert-Contains '.github/workflows/ios-release-ipa.yml' 'gh release view "\$\{\{ inputs\.release_tag \}\}"' 'IPA target release not verified.'
Assert-Contains '.github/workflows/android-playstore.yml' '(?ms)workflow_call:.*source_ref:.*required: true' 'Play Store reusable source input missing.'
Assert-Contains '.github/workflows/ios-testflight.yml' '(?ms)workflow_call:.*source_ref:.*required: true' 'TestFlight reusable source input missing.'
Assert-Contains '.github/workflows/release-all.yml' 'MARKETING_VERSION' 'New-release workflow does not validate iOS version.'
Assert-Contains '.github/workflows/release-all.yml' 'uses: \./\.github/workflows/android-release-apk\.yml' 'New-release workflow does not call the APK workflow.'
Assert-Contains '.github/workflows/release-all.yml' 'needs: \[create-release, android-apk, ios-ipa\]' 'Store publication is not gated on both assets.'
Assert-Contains '.github/workflows/release-all-existing.yml' 'release_tag:' 'Existing-release workflow lacks tag input.'
Assert-Contains '.github/workflows/release-all-existing.yml' 'gh release delete-asset' 'Existing-release workflow does not delete assets.'
Assert-Contains '.github/workflows/release-all-existing.yml' 'source_ref: main' 'Existing-release workflow does not use main.'
Write-Output 'Release workflow contracts passed.'
~~~

- [ ] **Step 2: Run the test and verify it fails**

Run: pwsh -File .github/scripts/test-release-workflows.ps1

Expected: FAIL because the current workflows lack reusable interfaces and assets select the newest release.

- [ ] **Step 3: Commit the test**

~~~powershell
git add .github/scripts/test-release-workflows.ps1
git commit -m "test: define release workflow contracts"
~~~

### Task 2: Add reusable platform workflow interfaces

**Files:**
- Modify: .github/workflows/android-release-apk.yml:3-60,181-196
- Modify: .github/workflows/ios-release-ipa.yml:3-51,215-230
- Modify: .github/workflows/android-playstore.yml:3-30
- Modify: .github/workflows/ios-testflight.yml:3-31
- Test: .github/scripts/test-release-workflows.ps1

**Interfaces:**
- Consumes: source_ref, release_tag, and existing store/version inputs.
- Produces: four callable workflows with explicit build source; asset builders also target a specific GitHub release.

- [ ] **Step 1: Run the regression test before YAML changes**

Run: pwsh -File .github/scripts/test-release-workflows.ps1

Expected: FAIL at the first platform workflow assertion.

- [ ] **Step 2: Implement asset-builder inputs and targeting**

Add this callable input section to both APK and IPA workflows, retaining their manual triggers. The APK workflow also retains versionCodeOverride in both trigger input blocks.

~~~yaml
  workflow_call:
    inputs:
      source_ref:
        required: true
        type: string
      release_tag:
        required: true
        type: string
~~~

Replace each checkout and latest-release lookup with:

~~~yaml
- name: Checkout repository
  uses: actions/checkout@v6
  with:
    ref: ${{ inputs.source_ref || github.ref }}

- name: Verify target release
  env:
    GH_TOKEN: ${{ github.token }}
  run: gh release view "${{ inputs.release_tag }}" --repo "${{ github.repository }}"
~~~

Delete Get latest release. Replace every steps.latest_release.outputs.tag_name in the artifact filename and gh release upload command with inputs.release_tag.

- [ ] **Step 3: Implement store-publisher inputs and source checkout**

Add workflow_call.inputs.source_ref (required string) to both publisher workflows. Also add optional versionCodeOverride and submitForReview to Play Store, and optional fail_on_distribution_error to TestFlight. Use the same explicit checkout expression:

~~~yaml
- name: Checkout repository
  uses: actions/checkout@v6
  with:
    ref: ${{ inputs.source_ref || github.ref }}
~~~

Do not change signing, upload, or distribution behavior.

- [ ] **Step 4: Run the test and validate YAML**

Run:

~~~powershell
pwsh -File .github/scripts/test-release-workflows.ps1
git diff --check
actionlint .github/workflows/android-release-apk.yml .github/workflows/ios-release-ipa.yml .github/workflows/android-playstore.yml .github/workflows/ios-testflight.yml
~~~

Expected: the contract test reaches the orchestrator checks, whitespace validation passes, and actionlint exits 0. If unavailable, record that and continue with the other two passing commands.

- [ ] **Step 5: Commit**

~~~powershell
git add .github/workflows/android-release-apk.yml .github/workflows/ios-release-ipa.yml .github/workflows/android-playstore.yml .github/workflows/ios-testflight.yml
git commit -m "refactor: make platform release workflows reusable"
~~~

### Task 3: Implement Release All Platforms

**Files:**
- Modify: .github/workflows/release-all.yml:1-150
- Test: .github/scripts/test-release-workflows.ps1

**Interfaces:**
- Consumes: reusable workflow inputs from Task 2.
- Produces: a new GitHub release at main, artifacts from its tag, then store builds from the same tag.

- [ ] **Step 1: Run the regression test**

Run: pwsh -File .github/scripts/test-release-workflows.ps1

Expected: FAIL at a release-all.yml assertion.

- [ ] **Step 2: Replace dispatch-and-forget with dependent reusable jobs**

Remove version_override and all gh workflow run commands. Reject runs whose ref is not refs/heads/main. In create-release, check out main with fetch-depth: 0, then derive and validate the tag:

~~~bash
ANDROID_VERSION="$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)".*/\1/p' androidApp/build.gradle.kts | head -1)"
IOS_VERSIONS="$(sed -nE 's/^[[:space:]]*MARKETING_VERSION = ([^;]+);/\1/p' iosApp/VitruvianPhoenix/VitruvianPhoenix.xcodeproj/project.pbxproj | sort -u)"
if ! [[ "$ANDROID_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?$ ]]; then
  echo "Invalid Android versionName: '$ANDROID_VERSION'" >&2; exit 1
fi
if [ "$(printf '%s\n' "$IOS_VERSIONS" | sed '/^$/d' | wc -l)" -ne 1 ] || [ "$IOS_VERSIONS" != "$ANDROID_VERSION" ]; then
  echo "Android and iOS marketing versions must match." >&2; exit 1
fi
TAG="v$ANDROID_VERSION"
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "Release tag $TAG already exists. Bump the app version on main before releasing." >&2; exit 1
fi
echo "tag=$TAG" >> "$GITHUB_OUTPUT"
echo "sha=$(git rev-parse HEAD)" >> "$GITHUB_OUTPUT"
~~~

Create the release with gh release create, --generate-notes, the previous semantic tag only when it exists, and --target "${{ steps.version.outputs.sha }}".

Create android-apk and ios-ipa jobs that need create-release, use the local reusable asset workflows, pass both source_ref and release_tag as ${{ needs.create-release.outputs.tag }}, and use secrets: inherit. Create android-playstore and ios-testflight jobs with:

~~~yaml
needs: [create-release, android-apk, ios-ipa]
~~~

Pass the same release tag as each store workflow source_ref. Preserve skip inputs as job-level if conditions and replace the summary warning about non-waiting dispatches.

- [ ] **Step 3: Run test and commit**

Run:

~~~powershell
pwsh -File .github/scripts/test-release-workflows.ps1
git diff --check
actionlint .github/workflows/release-all.yml
~~~

Expected: only existing-release assertions remain failing; the other checks pass.

~~~powershell
git add .github/workflows/release-all.yml
git commit -m "feat: create releases from main app version"
~~~

### Task 4: Implement Release All (Existing)

**Files:**
- Modify: .github/workflows/release-all-existing.yml:1-90
- Test: .github/scripts/test-release-workflows.ps1

**Interfaces:**
- Consumes: required release_tag, reusable platform workflows, and existing skip/version inputs.
- Produces: replaced assets on that release and store submissions built from main.

- [ ] **Step 1: Run the regression test**

Run: pwsh -File .github/scripts/test-release-workflows.ps1

Expected: FAIL at an existing-release assertion.

- [ ] **Step 2: Add targeted asset cleanup and reusable jobs**

Add required string input release_tag. Add a prepare-release job that rejects non-main runs, verifies the supplied GitHub release, and runs:

~~~bash
TAG="${{ inputs.release_tag }}"
gh release view "$TAG" --repo "${{ github.repository }}" >/dev/null
for asset in "ProjectPhoenix-$TAG.apk" "ProjectPhoenix-$TAG.ipa"; do
  gh release delete-asset "$TAG" "$asset" --repo "${{ github.repository }}" --yes \
    && echo "Deleted $asset" \
    || echo "Asset $asset was absent; continuing."
done
~~~

Call the APK and IPA reusable workflows after prepare-release, each with source_ref: main and release_tag: ${{ inputs.release_tag }}. Call both store workflows after:

~~~yaml
needs: [prepare-release, android-apk, ios-ipa]
~~~

Pass source_ref: main, retain all current skip/version/submission inputs, and use secrets: inherit. State in the summary that assets were replaced on the selected release and store builds came from main.

- [ ] **Step 3: Run final verification and commit**

Run:

~~~powershell
pwsh -File .github/scripts/test-release-workflows.ps1
git diff --check
actionlint .github/workflows/release-all.yml .github/workflows/release-all-existing.yml .github/workflows/android-release-apk.yml .github/workflows/ios-release-ipa.yml .github/workflows/android-playstore.yml .github/workflows/ios-testflight.yml
~~~

Expected: contract test and whitespace validation exit 0; actionlint exits 0 when installed.

~~~powershell
git add .github/workflows/release-all-existing.yml .github/scripts/test-release-workflows.ps1
git commit -m "feat: rebuild existing release assets from main"
~~~

## Plan Self-Review

- Spec coverage: Tasks 2–4 implement all explicit refs, named release targeting, version/tag validation, scoped asset deletion, generated notes, and artifact-before-publication dependencies.
- Placeholder scan: no deferred or unspecified implementation remains.
- Interface consistency: asset calls use source_ref and release_tag; store calls use source_ref plus their existing publication inputs.
