#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RENDERER="$SCRIPT_DIR/phantom-proposal.sh"
VERIFY="$SCRIPT_DIR/phantom-harness-verify.py"
TMP_DIR="$(python3 - <<'PY'
import tempfile
print(tempfile.mkdtemp(prefix="phantom-proposal-test-"))
PY
)"
cleanup() {
    python3 - "$TMP_DIR" <<'PY'
import shutil
import sys
shutil.rmtree(sys.argv[1], ignore_errors=True)
PY
}
trap cleanup EXIT

fail() {
    printf 'test failure: %s\n' "$1" >&2
    exit 1
}

[[ -x "$RENDERER" ]] || fail 'proposal renderer is missing or not executable'

REAL_GIT="$(command -v git)"
FAKE_REPO="$TMP_DIR/fake-repo"
FAKE_BIN="$TMP_DIR/bin"
LOG="$TMP_DIR/fake-git.log"
mkdir -p "$FAKE_REPO/.github/scripts" "$FAKE_REPO/iosApp/Sources" "$FAKE_BIN"
: > "$LOG"
chmod 600 "$LOG"

python3 - "$FAKE_REPO/.github/scripts/phantom-harness.sh" "$VERIFY" <<'PY'
import json
import os
import stat
import struct
import sys
import zlib
from pathlib import Path

runner = Path(sys.argv[1])
verifier = Path(sys.argv[2])
runner.write_text(r'''#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
case "${1-}" in
    case)
        artifact="$2"
        fixture="$3"
        [[ "$fixture" == "just-lift-connected" ]] || exit 1
        [[ "${FAKE_HARNESS_FAIL_BASELINE-0}" != 1 || "$PWD" != "${FAKE_ORIGINAL_ROOT:?}" ]] || exit 1
        mkdir -p "$artifact"
        chmod 700 "$artifact"
        python3 - "$artifact" <<'PY2'
import hashlib
import json
import os
import struct
import sys
import zlib
from pathlib import Path
root = Path(sys.argv[1])
source = (Path.cwd() / "iosApp/Sources/Proposal.swift").read_text(encoding="utf-8")
color = bytes((255, 255, 255, 255)) if "candidate" not in source else bytes((255, 0, 0, 255))
def chunk(kind, payload):
    return len(payload).to_bytes(4, "big") + kind + payload + zlib.crc32(kind + payload).to_bytes(4, "big")
rows = b"".join(b"\x00" + color for _ in range(2))
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 2, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")
(root / "after.png").write_bytes(png)
os.chmod(root / "after.png", 0o600)
fixture_sha = "0" * 64
simulator = {"udid": "11111111-2222-3333-4444-555555555555", "name": "Fake iPhone", "runtime": "iOS-26-5", "state": "Booted"}
manifest = {
    "schemaVersion": 1,
    "runId": "fake-run",
    "provenance": {"baseSha": "0" * 40, "fixture": {"id": "just-lift-connected", "sha256": fixture_sha}, "xcode": "Fake Xcode", "sdk": "Fake SDK", "simulator": simulator, "bundleId": "com.devil.phoenixproject.projectphoenix"},
    "commands": [{"name": "fake-case", "exitCode": 0}],
    "semanticMarkers": {"required": ["xctest.passed", "phantom.connected", "simulator.screenshot"], "observed": ["xctest.passed", "phantom.connected", "simulator.screenshot"]},
    "captures": [{"slug": "simulator-after", "path": "after.png", "sha256": hashlib.sha256(png).hexdigest(), "phase": "after", "pair": "simulator", "checkpoint": "phantom-connected", "fixtureId": "just-lift-connected", "fixtureSha256": fixture_sha, "simulator": simulator}],
}
(root / "run.json").write_text(json.dumps(manifest, sort_keys=True) + "\n", encoding="utf-8")
os.chmod(root / "run.json", 0o600)
(root / ".phantom-harness").write_text("phantom-harness-artifact-v1\n", encoding="utf-8")
os.chmod(root / ".phantom-harness", 0o600)
PY2
        ;;
    verify)
        exec python3 "$ROOT/phantom-harness-verify.py" "$2"
        ;;
    compare)
        output="$4"
        mkdir -p "$output"
        chmod 700 "$output"
        python3 - "$output" <<'PY2'
import json
import os
import struct
import sys
import zlib
from pathlib import Path
root = Path(sys.argv[1])
def chunk(kind, payload):
    return len(payload).to_bytes(4, "big") + kind + payload + zlib.crc32(kind + payload).to_bytes(4, "big")
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00\xff")) + chunk(b"IEND", b"")
(root / "diff.png").write_bytes(png)
os.chmod(root / "diff.png", 0o600)
(root / "diff.json").write_text(json.dumps({"passed": True, "thresholdPassed": True, "changedPixels": 1, "changedPixelRatio": 0.5, "dimensions": {"width": 1, "height": 1}}) + "\n", encoding="utf-8")
os.chmod(root / "diff.json", 0o600)
PY2
        ;;
    *) exit 2 ;;
esac
''')
os.chmod(runner, 0o700)
# The candidate worktree needs the committed verifier beside the fake runner.
destination = runner.parent / verifier.name
destination.write_bytes(verifier.read_bytes())
os.chmod(destination, 0o600)
PY
python3 - "$FAKE_REPO/iosApp/Sources/Proposal.swift" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
path.write_text("// baseline\n", encoding="utf-8")
PY

FAKE_RENDERER="$FAKE_REPO/.github/scripts/phantom-proposal.sh"
cp "$RENDERER" "$FAKE_RENDERER"
chmod 700 "$FAKE_RENDERER"

# A wrapper counts every git invocation while delegating to the real git binary.
python3 - "$FAKE_BIN/git" "$REAL_GIT" "$LOG" <<'PY'
import os
import stat
import sys
from pathlib import Path
path = Path(sys.argv[1])
real_git = sys.argv[2]
log = sys.argv[3]
path.write_text(f'''#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$PWD|$*" >> {log!r}
exec {real_git!r} "$@"
''', encoding="utf-8")
os.chmod(path, 0o700)
PY

PATH="$FAKE_BIN:/usr/bin:/bin" "$REAL_GIT" -C "$FAKE_REPO" init -q
PATH="$FAKE_BIN:/usr/bin:/bin" "$REAL_GIT" -C "$FAKE_REPO" config user.email test@example.invalid
PATH="$FAKE_BIN:/usr/bin:/bin" "$REAL_GIT" -C "$FAKE_REPO" config user.name "Phantom Test"
PATH="$FAKE_BIN:/usr/bin:/bin" "$REAL_GIT" -C "$FAKE_REPO" add .
PATH="$FAKE_BIN:/usr/bin:/bin" "$REAL_GIT" -C "$FAKE_REPO" commit -q -m base
BASE_STATUS="$TMP_DIR/base-status"
PATH="$FAKE_BIN:/usr/bin:/bin" "$REAL_GIT" -C "$FAKE_REPO" status --porcelain > "$BASE_STATUS"

PATCH="$TMP_DIR/candidate.patch"
python3 - "$PATCH" <<'PY'
import os
import sys
from pathlib import Path
patch = Path(sys.argv[1])
patch.write_bytes(b'''diff --git a/iosApp/Sources/Proposal.swift b/iosApp/Sources/Proposal.swift
index 66b7c5a..d6fb2ab 100644
--- a/iosApp/Sources/Proposal.swift
+++ b/iosApp/Sources/Proposal.swift
@@ -1 +1 @@
-// baseline
+// candidate
''')
os.chmod(patch, 0o600)
PY

run_renderer() {
    env -i PATH="$FAKE_BIN:/usr/bin:/bin" HOME="$TMP_DIR" TMPDIR="$TMP_DIR" FAKE_ORIGINAL_ROOT="$FAKE_REPO" "$@"
}

# Invalid paths are refused before the baseline runner or worktree creation.
INVALID_PATCH="$TMP_DIR/invalid.patch"
python3 - "$INVALID_PATCH" <<'PY'
import os
import sys
from pathlib import Path
path = Path(sys.argv[1])
path.write_bytes(b'''diff --git a/iosApp/Config/Supabase.xcconfig b/iosApp/Config/Supabase.xcconfig
--- a/iosApp/Config/Supabase.xcconfig
+++ b/iosApp/Config/Supabase.xcconfig
@@ -1 +1 @@
-a
+b
''')
os.chmod(path, 0o600)
PY
INVALID_ARTIFACT="$TMP_DIR/invalid-artifact"
if run_renderer "$FAKE_RENDERER" render "$INVALID_ARTIFACT" just-lift-connected "$INVALID_PATCH" >"$TMP_DIR/invalid.out" 2>"$TMP_DIR/invalid.err"; then
    fail 'protected config patch was accepted'
fi
python3 - "$INVALID_ARTIFACT" <<'PY'
import json
import os
import stat
import sys
from pathlib import Path
root = Path(sys.argv[1])
manifest = json.loads((root / "proposal-manifest.json").read_text())
assert manifest["status"] == "failed"
assert stat.S_IMODE(os.lstat(root).st_mode) == 0o700
assert stat.S_IMODE(os.lstat(root / ".phantom-proposal").st_mode) == 0o600
assert not (root / "proposal.patch").exists()
PY
if grep -E 'Supabase|secret|password|Bearer' "$TMP_DIR/invalid.err" >/dev/null 2>&1; then
    fail 'invalid patch failure leaked path or secret-like text'
fi
if grep -E 'worktree add|case ' "$LOG" >/dev/null 2>&1; then
    fail 'invalid patch reached baseline or worktree'
fi

# A baseline failure stops before a disposable worktree is created.
BASELINE_ARTIFACT="$TMP_DIR/baseline-failure"
if run_renderer env FAKE_HARNESS_FAIL_BASELINE=1 "$FAKE_RENDERER" render "$BASELINE_ARTIFACT" just-lift-connected "$PATCH" >"$TMP_DIR/baseline.out" 2>"$TMP_DIR/baseline.err"; then
    fail 'baseline failure was accepted'
fi
python3 - "$BASELINE_ARTIFACT" <<'PY'
import json
import sys
from pathlib import Path
manifest = json.loads((Path(sys.argv[1]) / "proposal-manifest.json").read_text())
assert manifest["status"] == "failed"
assert manifest["failure"]["stage"] == "capture baseline"
PY
if grep -E 'worktree add' "$LOG" >/dev/null 2>&1; then
    # The only worktree operation should not have happened during this run.
    fail 'baseline failure created a worktree'
fi

# Successful candidate: the patch is visible to the disposable case only, the
# packet carries exact patch/hash evidence, and the temporary worktree is gone.
SUCCESS_ARTIFACT="$TMP_DIR/success"
run_renderer "$FAKE_RENDERER" render "$SUCCESS_ARTIFACT" just-lift-connected "$PATCH" >"$TMP_DIR/success.out"
python3 - "$SUCCESS_ARTIFACT" "$PATCH" "$FAKE_REPO" <<'PY'
import hashlib
import json
import os
import stat
import sys
from pathlib import Path
root = Path(sys.argv[1])
patch = Path(sys.argv[2])
repo = Path(sys.argv[3])
manifest = json.loads((root / "proposal-manifest.json").read_text())
assert manifest["status"] == "passed", "status"
assert manifest["baseSha"] == manifest["worktree"]["baseSha"] == manifest["worktree"]["headSha"], "sha"
assert manifest["worktree"]["detached"] is True, "detached"
assert manifest["patch"]["sha256"] == hashlib.sha256(patch.read_bytes()).hexdigest(), "patch hash"
assert (root / "proposal.patch").read_bytes() == patch.read_bytes(), "patch bytes"
assert manifest["actualChangedFiles"] == ["iosApp/Sources/Proposal.swift"], "files"
assert manifest["before"]["identity"] == manifest["after"]["identity"], "identity"
assert json.loads((root / "comparison/diff.json").read_text())["passed"] is True, "diff"
assert (root / "comparison/diff.png").is_file(), "diff image"
for path in (root / ".phantom-proposal", root / "proposal.patch", root / "proposal.md", root / "proposal-manifest.json", root / "evidence-summary.json", root / "comparison/diff.json", root / "comparison/diff.png"):
    assert stat.S_IMODE(os.lstat(path).st_mode) == 0o600, path
assert stat.S_IMODE(os.lstat(root).st_mode) == 0o700
assert (repo / "iosApp/Sources/Proposal.swift").read_text() == "// baseline\n"
PY
if [[ -n "$(PATH="$FAKE_BIN:/usr/bin:/bin" "$REAL_GIT" -C "$FAKE_REPO" status --porcelain)" ]]; then
    fail 'original repository status changed'
fi
for leaked in "$TMP_DIR"/phantom-proposal-*; do
    [[ -e "$leaked" ]] || continue
    fail 'temporary proposal worktree leaked'
done
if ! grep -E 'worktree add --detach' "$LOG" >/dev/null 2>&1 || ! grep -E 'worktree remove --force' "$LOG" >/dev/null 2>&1; then
    fail 'worktree lifecycle was not recorded'
fi

# An apply failure also cleans the disposable worktree and leaves only a safe
# failure manifest; no candidate source reaches the original checkout.
BAD_APPLY="$TMP_DIR/bad-apply.patch"
python3 - "$BAD_APPLY" <<'PY'
import os
import sys
from pathlib import Path
path = Path(sys.argv[1])
path.write_bytes(b'''diff --git a/iosApp/Sources/Proposal.swift b/iosApp/Sources/Proposal.swift
--- a/iosApp/Sources/Proposal.swift
+++ b/iosApp/Sources/Proposal.swift
@@ -1 +1 @@
-not-the-base
+candidate
''')
os.chmod(path, 0o600)
PY
BAD_ARTIFACT="$TMP_DIR/bad-apply"
if run_renderer "$FAKE_RENDERER" render "$BAD_ARTIFACT" just-lift-connected "$BAD_APPLY" >"$TMP_DIR/bad.out" 2>"$TMP_DIR/bad.err"; then
    fail 'bad patch application was accepted'
fi
python3 - "$BAD_ARTIFACT" "$FAKE_REPO" <<'PY'
import json
import sys
from pathlib import Path
root = Path(sys.argv[1])
manifest = json.loads((root / "proposal-manifest.json").read_text())
assert manifest["status"] == "failed"
assert manifest["failure"]["stage"] == "apply disposable patch"
assert list(root.iterdir()) == [root / ".phantom-proposal", root / "proposal-manifest.json"]
assert (Path(sys.argv[2]) / "iosApp/Sources/Proposal.swift").read_text() == "// baseline\n"
PY

printf 'phantom proposal shell tests passed\n'
