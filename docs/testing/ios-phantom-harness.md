# iOS Phantom real-app harness

This runbook documents the completed simulator workflow for the real
`VitruvianPhoenix` app.  It is an operational harness, not a replacement for
physical Vitruvian validation and not a claim that generic BLE bugs,
enhancements, or motor behavior are already covered.

## Status and coverage boundary

The currently verified end-to-end case is:

| Item | Current contract |
| --- | --- |
| Fixture ID | `just-lift-connected` |
| XCTest target | `VitruvianPhoenixUITests` |
| XCTest class/method | `PhantomJustLiftFlowUITests` / `testHomeToJustLiftToPhantomConnected` |
| App bundle | `com.devil.phoenixproject.projectphoenix` |
| Phantom name | `Vee_PhantomSimulator` |
| Required evidence markers | `xctest.passed`, `phantom.connected`, `simulator.screenshot` |

The launch-fixture enum also defines `clean-eula`, but the real-app runner's
`case` command intentionally allowlists only `just-lift-connected` today.  A
future case recipe must add and test its fixture allowlist, semantic selectors,
required markers, and evidence contract before it is described as supported.
Do not present a future recipe as coverage for a generic bug or enhancement.

## Runner contract

The tracked runner is `.github/scripts/phantom-harness.sh`.  It accepts only
these commands and arguments:

```text
phantom-harness.sh preflight UDID
phantom-harness.sh case ARTIFACT_ROOT just-lift-connected
phantom-harness.sh verify ARTIFACT_ROOT
phantom-harness.sh compare BEFORE_ROOT AFTER_ROOT OUTPUT_ROOT
phantom-harness.sh clean ARTIFACT_ROOT
```

The runner rejects malformed simulator UDIDs, path traversal, symlinks in
controlled paths, unknown fixture IDs, credential-like arguments/environment
values, and artifact roots that are not caller-owned.  `case` requires
`PHOENIX_HARNESS_UDID` and validates that the requested available simulator
matches that exact UDID.  It does not discover a different simulator on the
caller's behalf.

### Safety gate and reset scope

`case` performs destructive simulator/app setup, so a local invocation must
explicitly opt in:

```bash
export PHOENIX_HARNESS_UDID="<SIMULATOR_UDID>"
export PHOENIX_HARNESS_ALLOW_DESTRUCTIVE=1
```

CI is the other accepted gate (`CI=true`).  Without either gate, validation
must fail before any simulator command runs.  The reset is deliberately
narrow: the runner boots the selected simulator and terminates/uninstalls the
fixed app bundle, but never erases the simulator and never targets another
UDID or bundle.

The runner records the build/test command results, creates a temporary local
Supabase fixture config inside the checkout at
`iosApp/VitruvianPhoenix/Config/Supabase.xcconfig` only when that ignored config
is absent, and removes that temporary file with its exit trap.  If a local
config already exists, it is left untouched.  The temporary config contains
only fixed non-secret placeholder values; it is not a way to supply a real
environment and must never contain a token, key, password, or other secret.

## Proposal/worktree contract

The `.github/scripts/phantom-proposal.sh` wrapper, once committed, must run
from a temporary worktree and must forward the runner's exact contract above.
The proposal wrapper is orchestration, not a second fixture API: it must not
edit the original checkout, broaden the fixture allowlist, bypass the local
destructive gate, or write evidence into tracked source directories.  The
artifact root is caller owned and should be outside the checkout.

Its `EXIT trap` (registered together with `HUP`, `INT`, and `TERM`) is the
cleanup guarantee for every render outcome.  The trap records a bounded failure
manifest when needed, removes the disposable worktree with `git worktree
remove --force`, prunes its metadata, and removes
the private temporary directory.  The caller-owned artifact root is retained
for review; only that root's validated evidence should be preserved.

For a manual proposal reproduction, the worktree lifecycle is:

```bash
REPO_ROOT="<ORIGINAL_CHECKOUT>"
PROPOSAL_WORKTREE="<PROPOSAL_WORKTREE>"
git -C "$REPO_ROOT" worktree add --detach "$PROPOSAL_WORKTREE" HEAD

cd "$PROPOSAL_WORKTREE"
# Run the preflight/case/verify commands from this document here.

git -C "$REPO_ROOT" worktree remove --force "$PROPOSAL_WORKTREE"
git -C "$REPO_ROOT" worktree prune
```

The cleanup commands are required even after a failed proposal.  Before and
after the proposal, inspect `git -C "$REPO_ROOT" status --short --branch` and
confirm that application/source files in the original checkout are unchanged.
Do not use `git reset --hard` or `git clean` against the original checkout as a
cleanup shortcut.  Worktree metadata is pruned only after the temporary
worktree has been removed.

## Local execution

Use a private, empty artifact root.  The placeholders below are intentionally
not live device identifiers or credentials:

```bash
export PHOENIX_HARNESS_UDID="<SIMULATOR_UDID>"
export ARTIFACT_ROOT="<ARTIFACT_ROOT>"

./.github/scripts/phantom-harness.sh preflight "$PHOENIX_HARNESS_UDID"

PHOENIX_HARNESS_ALLOW_DESTRUCTIVE=1 \
  PHOENIX_HARNESS_UDID="$PHOENIX_HARNESS_UDID" \
  ./.github/scripts/phantom-harness.sh case "$ARTIFACT_ROOT" just-lift-connected

./.github/scripts/phantom-harness.sh verify "$ARTIFACT_ROOT"
python3 ./.github/scripts/phantom-harness-verify.py "$ARTIFACT_ROOT"
```

The root must be empty before `case` starts.  It is created with mode `0700`;
regular evidence files are mode `0600`.  If
`iosApp/VitruvianPhoenix/Config/Supabase.xcconfig` is absent, the runner
creates a local ignored file temporarily with non-secret placeholder values
and removes it when the command exits.  Do not replace those placeholders
with a real value for this simulator-only run.

### Build, install, and XCTest

`case` records the same build and test operations below.  The app is built for
the simulator with signing disabled, and `xcodebuild test` installs/launches
the app as part of the XCTest destination; there is no separate unrecorded
`simctl install` step in the supported contract.

```bash
xcodebuild -project iosApp/VitruvianPhoenix/VitruvianPhoenix.xcodeproj \
  -scheme VitruvianPhoenix \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination "platform=iOS Simulator,id=$PHOENIX_HARNESS_UDID" \
  -derivedDataPath "$ARTIFACT_ROOT/derived-data" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
  -hideShellScriptEnvironment build

xcodebuild test \
  -project iosApp/VitruvianPhoenix/VitruvianPhoenix.xcodeproj \
  -scheme VitruvianPhoenix \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination "platform=iOS Simulator,id=$PHOENIX_HARNESS_UDID" \
  -derivedDataPath "$ARTIFACT_ROOT/derived-data" \
  -resultBundlePath "$ARTIFACT_ROOT/test.xcresult" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
  -hideShellScriptEnvironment \
  -only-testing:VitruvianPhoenixUITests/PhantomJustLiftFlowUITests/testHomeToJustLiftToPhantomConnected
```

The XCTest flow uses semantic selectors, not coordinates or blind delays:
`screen-home`, `Open Just Lift`, `screen-just-lift`,
`connection-status-disconnected`, and `connection-status-connected`.  The
connected state must expose a meaningful accessible label containing
“connected”, and the test retains a screenshot attachment.

## Evidence layout and retention policy

The artifact root is a disposable evidence packet, not a source tree.  A
successful run normally contains the following direct children (some logs or
the XCTest attachment can be absent when their producing command fails):

```text
<ARTIFACT_ROOT>/
├── .phantom-harness          # validation sentinel, 0600
├── .commands.jsonl           # recorded command results, 0600
├── run.json                  # schemaVersion 1 manifest, 0600
├── toolchain.log             # Xcode version
├── boot.log
├── bootstatus.log
├── terminate.log
├── uninstall.log
├── build.log
├── test.log
├── app-state.log
├── simulator.log
├── screenshot.log
├── after.png                 # simulator screenshot, 0600
├── xctest-attachment.png     # XCTest screenshot when discoverable, 0600
├── derived-data/             # Xcode derived data, generated and ignored
└── test.xcresult/            # XCTest result bundle and attachments, ignored
    └── Attachments/
```

The root and every generated directory are mode `0700`; regular files are
mode `0600`; symlinks are rejected.  The manifest records the base commit,
fixture ID and SHA-256, Xcode/SDK, simulator identity, command exit codes,
required/observed semantic markers, and SHA-256 hashes for captures.  The
verifier accepts only direct child capture paths, checks PNG structure and
hashes, enforces before/after identity when a pair is supplied, and scans
textual artifacts for secret-like content without echoing matches.

The repository policy ignores only generated harness state: named evidence
roots (which contain the runner's lower-case `derived-data/`, `*.xcresult`
bundles, and command journals), the two simulator-local override xcconfigs,
and ephemeral Phantom proposal worktrees.  It does not ignore an unrelated
`derived-data/` directory, result bundle, or `.commands.jsonl` file merely
because it has that name.  Existing non-harness ignores remain in place.
The tracked `.github/scripts/` runner/verifier/diff tools and this
`docs/testing/` runbook are not ignored.  Prefer an artifact root outside the
checkout; if a local run places it under the checkout, use one of the ignored
evidence roots and verify with `git status --short` before committing.

After evidence has been reviewed or copied to the approved run store, remove
only a validated artifact root with:

```bash
./.github/scripts/phantom-harness.sh clean "$ARTIFACT_ROOT"
```

`clean` requires the runner sentinel, a mode `0700` caller-owned root, and
non-symlink contents.  It refuses an arbitrary directory and never performs
repository-wide cleanup.

## Evidence verifier and visual diff

The verifier is the source of truth for a packet verdict.  It emits one bounded
JSON result and exits zero only when the packet passes:

```bash
python3 ./.github/scripts/phantom-harness-verify.py "$ARTIFACT_ROOT"
```

For a before/after comparison, both input roots must already be validated
`0700` artifact directories containing mode `0600` PNGs.  The supported
wrapper compiles the tracked CoreGraphics/ImageIO tool in a private temporary
directory, writes `diff.png` and `diff.json` with mode `0600`, then removes the
private compiler directory:

```bash
export BEFORE_ROOT="<BEFORE_ARTIFACT_ROOT>"
export AFTER_ROOT="<AFTER_ARTIFACT_ROOT>"
export DIFF_ROOT="<DIFF_ARTIFACT_ROOT>"
./.github/scripts/phantom-harness.sh compare "$BEFORE_ROOT" "$AFTER_ROOT" "$DIFF_ROOT"
python3 - "$DIFF_ROOT/diff.json" <<'PY'
import json
import sys
from pathlib import Path
print(json.loads(Path(sys.argv[1]).read_text())["passed"])
PY
```

The diff is visual evidence only.  A passing image comparison does not prove
BLE radio behavior, firmware compatibility, cable/motor safety, or a generic
product requirement.

## CI behavior and retention

`.github/workflows/ios-phantom-harness.yml` runs on `workflow_dispatch` and on
pull requests that touch the shared Phantom sources/tests, the real app/Xcode
project, high-risk Gradle/build or ignore policy files, harness scripts, or
this runbook/workflow.  Before provisioning the disposable simulator it runs
the shell harness tests, verifier unit tests, native image-diff tests, and
runner/proposal Bash syntax checks.  It uses a `macos-26` runner,
selects and validates an installed Xcode, sets up Java 17 and Gradle, creates
one disposable iPhone simulator, runs the focused simulator tests, runs the
real-app case with the CI destructive gate, verifies the evidence packet, and
deletes the simulator in an `always()` cleanup step.

The workflow packages the complete evidence root, including the hidden
`.phantom-harness` sentinel and `.commands.jsonl` journal, into
`phantom-evidence.tar` from inside the root so tar preserves dotfiles and file
modes.  It creates and uploads that archive on every outcome (`if: always()`)
as `ios-phantom-evidence-<run-id>-<run-attempt>` with a 14-day GitHub artifact
retention period; a missing archive is an upload error.  Concurrency is scoped
per ref and cancels an in-progress run.  The Gradle init script lives under
the runner's temporary directory with mode `0600`.  The harness's temporary
`Supabase.xcconfig`, when needed, is instead created inside the checkout at
the ignored path documented above, contains no secrets, and is removed by the
harness exit trap.  Neither temporary file is evidence or a secret channel.
A CI artifact is not a release artifact and must not be treated as permanent
retention.

To inspect a downloaded archive and re-run the verifier, preserve modes while
extracting it into a new private directory:

```bash
ARCHIVE="ios-phantom-evidence-<run-id>-<run-attempt>/phantom-evidence.tar"
EXTRACTED="<PRIVATE_EMPTY_DIRECTORY>"
mkdir -m 700 "$EXTRACTED"
tar -tvf "$ARCHIVE"
tar -xpf "$ARCHIVE" -C "$EXTRACTED"
python3 ./.github/scripts/phantom-harness-verify.py "$EXTRACTED"
```

The extracted root must still contain `.phantom-harness` and
`.commands.jsonl` when the producer reached those steps; do not verify a
manually copied subset of the packet.  Treat any tar path, mode, symlink, or
verifier failure as invalid evidence and discard the packet rather than
relaxing the verifier.

## Physical-device boundary

The Phantom implementation and this workflow are simulator-only.  The
simulator exercises the app's deterministic Phantom repository, launch fixture,
semantic UI flow, command/evidence assembly, and screenshot verification.  It
does not exercise CoreBluetooth radio transport or a physical machine.

Before any real Vitruvian test, a qualified operator must still validate the
physical device, radio/BLE behavior, firmware compatibility, load path, and
motor/weight safety.  The simulator result must never be used as permission to
operate a motor or to skip a physical-device safety review.

## Troubleshooting

### Java runtime or Gradle startup

CI pins Java 17 before invoking Gradle.  Locally, check the selected runtime
before debugging an iOS build:

```bash
java -version
./gradlew --version
```

If Gradle reports that Java is missing or `JAVA_HOME` is invalid, install/use
the repository-supported JDK, set `JAVA_HOME` to that JDK, and retry the
focused command.  Do not commit a local Gradle init script or Java path into
the proposal worktree.  A Java startup failure is an environment blocker, not
evidence that the Phantom case or app is broken.

### Missing Compose resources

The Xcode project first runs the simulator-specific Kotlin/Native resource and
framework tasks, then its `Process Resources` phase copies
`shared/build/processedResources/iosSimulatorArm64/main/composeResources` into
the app bundle.  If the build reports that processed Compose resources are
missing, inspect the first Gradle/Xcode failure, confirm the simulator SDK was
selected, and rerun the runner from a clean artifact root.  Do not hand-copy a
tracked source directory into the app bundle or commit generated
`compose-resources`/derived-data output.  A missing resource staging step is a
build failure, not a selector failure.

### Selector or semantic-flow failures

The current XCTest requires the exact semantic checkpoints and the localized
`Open Just Lift` button listed above.  If a selector times out, inspect
`test.log`, the simulator screenshot, and the app-state/simulator logs first.
Confirm that `PHOENIX_SIMULATOR_FIXTURE` is the allowlisted
`just-lift-connected` value and that the app was launched by the XCTest flow.
Do not “fix” a timeout by adding coordinates or a blind sleep.  If product UI
semantics change, update the selector contract and focused tests before adding
a new case; until then, report the current case as failing rather than
claiming broader coverage.

### Artifact or safety failures

An artifact permission, symlink, path, or secret-scan failure should be fixed
by creating a fresh private root and rerunning the case.  Do not relax modes,
disable the verifier, reuse an arbitrary directory, or bypass the destructive
gate.  If the requested UDID is unavailable, rerun preflight against an
available simulator and pass that exact value to `case`.

## No-secrets policy

Never place real environment values, access material, tokens, passwords, or
private keys in the repository, proposal worktree, evidence packet, issue
comment, or documentation.  Use the placeholder UDID/artifact-root forms in
this runbook.  The runner rejects credential-like input, sanitizes captured
text, scans the evidence packet, and removes a temporary placeholder config
when it created one.  Keep any pre-existing local config local, do not upload
it, and do not paste its contents into logs or screenshots.