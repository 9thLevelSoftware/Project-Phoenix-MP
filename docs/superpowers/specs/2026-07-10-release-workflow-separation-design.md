# Release Workflow Separation Design

## Goal

Make the two release orchestrators unambiguous: a new release is created from
the version declared on `main`, while an existing release can receive rebuilt
artifacts without changing its tag or release notes.

## Decision

The platform workflows will be reusable workflows with explicit inputs rather
than asynchronous dispatches that must be discovered and polled. The
orchestrators will call them as dependent jobs, so a failed APK or IPA build
prevents Play Store and TestFlight publication.

## Workflow Contracts

### Reusable platform workflows

`android-release-apk.yml` and `ios-release-ipa.yml` will support
`workflow_call` with required `source_ref` and `release_tag` inputs. They will
check out `source_ref`, verify that `release_tag` identifies a GitHub release,
and upload to that exact release. They will no longer discover the newest
release with `gh release view` without a tag.

Their manual-dispatch forms will retain direct use, with a required
`release_tag` input. A manual run builds the ref selected in the GitHub Actions
UI and uploads only to that explicitly named release.

`android-playstore.yml` and `ios-testflight.yml` will support `workflow_call`
with a required `source_ref` input. They will check out that ref before
building and publishing. Their manual-dispatch behavior continues to use the
ref selected in the GitHub Actions UI.

### Release All Platforms

This workflow must be started from `main` and checks out `main` explicitly. It
reads `androidApp/build.gradle.kts` `versionName` and the iOS
`MARKETING_VERSION` values, rejects malformed or mismatched values, and derives
the release tag as `v<version>`.

It fails before creating anything if the derived tag already exists. Otherwise
it creates a GitHub release at the exact checked-out `main` commit and uses
generated release notes, with the preceding semantic-version tag as the notes
start point when one exists.

After creation, the APK and IPA reusable workflows run in parallel with both
`source_ref` and `release_tag` set to the new tag. The Play Store and TestFlight
reusable workflows run only after both artifact jobs complete successfully and
also use the new tag as `source_ref`.

### Release All (Existing)

This workflow must be started from `main` and has a required `release_tag`
input. It verifies that the named GitHub release exists, then deletes only
`ProjectPhoenix-<release_tag>.apk` and `ProjectPhoenix-<release_tag>.ipa` from
that release. A missing asset is reported but does not fail the cleanup step.
The release, tag, and release notes are never deleted or recreated.

It then calls the APK and IPA reusable workflows with `source_ref: main` and
the supplied `release_tag`. When both succeed, it calls the Play Store and
TestFlight reusable workflows with `source_ref: main`.

## Failure Rules

- A new-release run fails if it is not running from `main`, the Android and iOS
  marketing versions differ, the version is invalid, or the derived tag exists.
- An existing-release run fails if it is not running from `main` or its supplied
  release tag does not name a GitHub release.
- A failure in either artifact workflow blocks both store-publication jobs.
- Artifact uploads never fall back to the newest release.

## Verification

Automated configuration tests will assert the reusable-workflow inputs, exact
checkout refs, explicit release-target behavior, asset deletion scope, and job
dependencies. `actionlint` will validate every changed workflow when available.
