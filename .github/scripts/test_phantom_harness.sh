#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNNER="$SCRIPT_DIR/phantom-harness.sh"
SOURCE_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
REAL_GIT="$(command -v git)"
OVERRIDE_WORKTREE=""
TMP_DIR="$(python3 - <<'PY'
import tempfile
print(tempfile.mkdtemp(prefix="phantom-harness-test-"))
PY
)"
CONFIG="$SCRIPT_DIR/../../iosApp/VitruvianPhoenix/Config/Supabase.xcconfig"
CONFIG_BACKUP="$TMP_DIR/Supabase.xcconfig.saved"
CONFIG_MOVED=0
restore_config() {
    if [[ "$CONFIG_MOVED" -eq 1 ]]; then
        python3 - "$CONFIG" "$CONFIG_BACKUP" <<'PY'
import shutil
import sys
from pathlib import Path
source = Path(sys.argv[2])
destination = Path(sys.argv[1])
if source.exists() and not destination.exists():
    shutil.move(str(source), str(destination))
PY
        CONFIG_MOVED=0
    fi
}
cleanup_all() {
    restore_config
    if [[ -n "$OVERRIDE_WORKTREE" ]]; then
        "$REAL_GIT" -C "$SOURCE_ROOT" worktree remove --force "$OVERRIDE_WORKTREE" >/dev/null 2>&1 || true
    fi
    python3 - "$TMP_DIR" <<'PY'
import shutil
import sys
shutil.rmtree(sys.argv[1], ignore_errors=True)
PY
}
trap cleanup_all EXIT

FAKE_BIN="$TMP_DIR/bin"
mkdir "$FAKE_BIN"
LOG="$TMP_DIR/fake-commands.log"
: > "$LOG"
chmod 600 "$LOG"
PATH_LOG="$TMP_DIR/xcode-private-paths.log"
: > "$PATH_LOG"
chmod 600 "$PATH_LOG"
EXPORT_LOG="$TMP_DIR/xcresult-export-paths.log"
: > "$EXPORT_LOG"
chmod 600 "$EXPORT_LOG"

cat > "$FAKE_BIN/xcrun" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf 'xcrun %s\n' "$*" >> "${PHANTOM_FAKE_LOG:?}"
if [[ "${1-}" == "--sdk" ]]; then
    if [[ "${3-}" == "--show-sdk-version" ]]; then
        printf '26.5\n'
        exit 0
    fi
    if [[ "${3-}" == "--show-sdk-path" ]]; then
        printf '/Applications/Xcode.app/SDKs/iPhoneSimulator.sdk\n'
        exit 0
    fi
fi
if [[ "${1-}" == "--version" ]]; then
    printf 'xcrun version 72.\n'
    exit 0
fi
if [[ "${1-}" == "xcresulttool" ]]; then
    shift
    if [[ "${1-}" != "export" || "${2-}" != "attachments" ]]; then
        printf 'unexpected fake xcresulttool invocation\n' >&2
        exit 2
    fi
    shift 2
    result=""
    output=""
    previous=""
    for arg in "$@"; do
        if [[ "$previous" == "--path" ]]; then result="$arg"; fi
        if [[ "$previous" == "--output-path" ]]; then output="$arg"; fi
        previous="$arg"
    done
    [[ -n "$result" && -n "$output" ]] || {
        printf 'missing fake xcresulttool paths\n' >&2
        exit 2
    }
    if [[ -n "${PHANTOM_FAKE_EXPORT_LOG-}" ]]; then
        printf 'export=%s\nresult=%s\n' "$output" "$result" >> "$PHANTOM_FAKE_EXPORT_LOG"
    fi
    if [[ "${PHANTOM_FAKE_XCRESULT_EXPORT_FAIL-}" == "1" ]]; then
        printf 'fake xcresulttool: export failed\n' >&2
        exit 23
    fi
    mkdir -p "$output"
    printf '{"attachments":[]}' > "$output/manifest.json"
    printf 'not a PNG\n' > "$output/000-invalid.png"
    if [[ "${PHANTOM_FAKE_NO_ATTACHMENT-}" == "1" ]]; then
        exit 0
    fi
    if [[ "${PHANTOM_FAKE_ONLY_INVALID_ATTACHMENTS-}" == "1" ]]; then
        python3 - "$output/../symlink-target.png" <<'PY'
import struct
import sys
import zlib
path = sys.argv[1]
def chunk(kind, payload):
    return len(payload).to_bytes(4, "big") + kind + payload + zlib.crc32(kind + payload).to_bytes(4, "big")
ihdr = struct.pack(">IIBBBBB", 2, 2, 8, 6, 0, 0, 0)
with open(path, "wb") as stream:
    stream.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", b"") + chunk(b"IEND", b""))
PY
        ln -s "$output/../symlink-target.png" "$output/001-symlink.png"
        exit 0
    fi
    python3 - "$output/001-valid.png" <<'PY'
import struct
import sys
import zlib
path = sys.argv[1]
def chunk(kind, payload):
    return len(payload).to_bytes(4, "big") + kind + payload + zlib.crc32(kind + payload).to_bytes(4, "big")
ihdr = struct.pack(">IIBBBBB", 2, 2, 8, 6, 0, 0, 0)
with open(path, "wb") as stream:
    stream.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", b"") + chunk(b"IEND", b""))
PY
    ln -s "$output/001-valid.png" "$output/002-symlink.png"
    exit 0
fi
if [[ "${1-}" != "simctl" ]]; then
    exec /usr/bin/xcrun "$@"
fi
shift
subcommand="${1-}"
shift || true
case "$subcommand" in
    list)
        if [[ "${1-}" == "devices" ]]; then
            printf '{"devices":{"com.apple.CoreSimulator.SimRuntime.iOS-26-5":[{"state":"Shutdown","isAvailable":true,"name":"iPhone 17 Pro","udid":"11111111-2222-3333-4444-555555555555"}]}}\n'
            exit 0
        fi
        ;;
    boot)
        : > "$PHANTOM_FAKE_BOOTED"
        exit 0
        ;;
    bootstatus)
        [[ -f "$PHANTOM_FAKE_BOOTED" ]]
        exit $?
        ;;
    terminate)
        printf 'No such process\n' >&2
        exit 149
        ;;
    uninstall)
        printf 'Application is not installed\n' >&2
        exit 149
        ;;
    get_app_container)
        printf '/tmp/fake-app-container\n'
        exit 0
        ;;
    spawn)
        printf 'VitruvianPhoenix phantom connected semantic checkpoint\n'
        exit 0
        ;;
    io)
        output="${@: -1}"
        python3 - "$output" <<'PY'
import struct
import sys
import zlib
path = sys.argv[1]
def chunk(kind, payload):
    return len(payload).to_bytes(4, "big") + kind + payload + zlib.crc32(kind + payload).to_bytes(4, "big")
ihdr = struct.pack(">IIBBBBB", 2, 2, 8, 6, 0, 0, 0)
with open(path, "wb") as stream:
    stream.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", b"") + chunk(b"IEND", b""))
PY
        exit 0
        ;;
esac
printf 'unexpected fake xcrun invocation\n' >&2
exit 2
SH
chmod 700 "$FAKE_BIN/xcrun"

cat > "$FAKE_BIN/xcodebuild" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf 'xcodebuild %s\n' "$*" >> "${PHANTOM_FAKE_LOG:?}"
if [[ -n "${PHANTOM_EXPECTED_JAVA_HOME-}" ]] && {
    [[ "${JAVA_HOME-}" != "$PHANTOM_EXPECTED_JAVA_HOME" ]] ||
    [[ ! -x "${JAVA_HOME-}/bin/java" ]]
}; then
    printf 'fake xcodebuild: expected exported JAVA_HOME was not usable\n' >&2
    exit 1
fi
if [[ "${1-}" == "-version" ]]; then
    printf 'Xcode 26.5\nBuild version 17F42\n'
    exit 0
fi
if [[ "${1-}" == "test" ]]; then
    result=""
    derived=""
    previous=""
    for arg in "$@"; do
        if [[ "$previous" == "-resultBundlePath" ]]; then result="$arg"; fi
        if [[ "$previous" == "-derivedDataPath" ]]; then derived="$arg"; fi
        previous="$arg"
    done
    if [[ -n "${PHANTOM_FAKE_PATH_LOG-}" ]]; then
        printf 'derived=%s\nresult=%s\n' "$derived" "$result" >> "$PHANTOM_FAKE_PATH_LOG"
    fi
    mkdir -p "$result/Data"
    printf 'hashed XCTest attachment payload\n' > "$result/Data/data.0"
    printf '{"attachments":[{"name":"Phantom Just Lift connected - Vee_PhantomSimulator"}]}' > "$result/manifest.json"
    printf 'Test Case -[PhantomJustLiftFlowUITests testHomeToJustLiftToPhantomConnected] passed\n'
    if [[ "${PHANTOM_FAKE_FAIL_TEST-}" == "1" ]]; then exit 17; fi
    exit 0
fi
if [[ "$*" == *"build"* ]]; then
    derived=""
    previous=""
    for arg in "$@"; do
        if [[ "$previous" == "-derivedDataPath" ]]; then derived="$arg"; fi
        previous="$arg"
    done
    if [[ -n "${PHANTOM_FAKE_PATH_LOG-}" ]]; then
        printf 'derived=%s\n' "$derived" >> "$PHANTOM_FAKE_PATH_LOG"
    fi
    mkdir -p "$derived/Products"
    ln -s "$derived/Products" "$derived/nested-link"
    printf 'Build Succeeded\n'
    exit 0
fi
printf 'unexpected fake xcodebuild invocation\n' >&2
exit 2
SH
chmod 700 "$FAKE_BIN/xcodebuild"

JAVA_HOME_VALID="$TMP_DIR/fake-jdk"
mkdir -p "$JAVA_HOME_VALID/bin"
cat > "$JAVA_HOME_VALID/bin/java" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1-}" == "-version" ]]; then
    printf 'openjdk version "21.0.8" 2026-01-20\n' >&2
    exit 0
fi
printf 'unexpected fake java invocation\n' >&2
exit 2
SH
chmod 700 "$JAVA_HOME_VALID/bin/java"
ln -s "$JAVA_HOME_VALID/bin/java" "$FAKE_BIN/java"
JAVA_HOME_VALID="$(python3 - "$JAVA_HOME_VALID" <<'PY'
from pathlib import Path
import sys
print(Path(sys.argv[1]).resolve())
PY
)"

NO_JAVA_BIN="$TMP_DIR/no-java-bin"
mkdir "$NO_JAVA_BIN"
python3 - "$NO_JAVA_BIN" "$FAKE_BIN/xcrun" "$FAKE_BIN/xcodebuild" "$(command -v python3)" "$(command -v bash)" <<'PY'
import os
import sys
from pathlib import Path
root = Path(sys.argv[1])
for source in sys.argv[2:]:
    Path(root / Path(source).name).symlink_to(source)
PY

run() {
    env -i \
        PATH="$FAKE_BIN:/usr/bin:/bin" \
        HOME="$TMP_DIR" \
        TMPDIR="$TMP_DIR" \
        PHANTOM_FAKE_LOG="$LOG" \
        PHANTOM_FAKE_BOOTED="$TMP_DIR/booted" \
        PHANTOM_FAKE_EXPORT_LOG="$EXPORT_LOG" \
        "$@"
}

run_no_java() {
    env -i \
        PATH="$NO_JAVA_BIN" \
        HOME="$TMP_DIR" \
        TMPDIR="$TMP_DIR" \
        PHANTOM_FAKE_LOG="$LOG" \
        PHANTOM_FAKE_BOOTED="$TMP_DIR/booted" \
        PHANTOM_FAKE_EXPORT_LOG="$EXPORT_LOG" \
        "$@"
}

fail() {
    printf 'test failure: %s\n' "$1" >&2
    exit 1
}

# Strict argument and fixture validation must happen before any simulator call.
if run "$RUNNER" preflight '' >/dev/null 2>&1; then fail 'missing UDID accepted'; fi
if run "$RUNNER" preflight not-a-udid >/dev/null 2>&1; then fail 'malformed UDID accepted'; fi
if run "$RUNNER" case "$TMP_DIR/unsafe" unknown >/dev/null 2>&1; then fail 'unknown fixture accepted'; fi
if [[ -s "$LOG" ]]; then fail 'validation invoked fake tools'; fi

# An explicit repository root is accepted only when it is the canonical current
# git worktree. Every invalid-root case must fail before the fake simulator is
# reached, while a valid disposable worktree must record the override exactly.
VALID_UDID=11111111-2222-3333-4444-555555555555
: > "$LOG"
MISMATCH_ROOT="$TMP_DIR/mismatched-root"
mkdir "$MISMATCH_ROOT"
if (cd "$SOURCE_ROOT" && run env PHOENIX_HARNESS_REPO_ROOT="$MISMATCH_ROOT" "$RUNNER" preflight "$VALID_UDID" >/dev/null 2>&1); then
    fail 'mismatched CWD/root override was accepted'
fi
PARENT_ROOT="$TMP_DIR/parent-root"
mkdir -p "$PARENT_ROOT/nested"
if (cd "$PARENT_ROOT/nested" && run env PHOENIX_HARNESS_REPO_ROOT="$PARENT_ROOT" "$RUNNER" preflight "$VALID_UDID" >/dev/null 2>&1); then
    fail 'parent repository-root override was accepted'
fi
NON_GIT_ROOT="$TMP_DIR/non-git-root"
mkdir "$NON_GIT_ROOT"
if (cd "$NON_GIT_ROOT" && run env PHOENIX_HARNESS_REPO_ROOT="$NON_GIT_ROOT" "$RUNNER" preflight "$VALID_UDID" >/dev/null 2>&1); then
    fail 'non-git repository-root override was accepted'
fi
SYMLINK_ROOT="$TMP_DIR/symlink-root"
ln -s "$SOURCE_ROOT" "$SYMLINK_ROOT"
if (cd "$SOURCE_ROOT" && run env PHOENIX_HARNESS_REPO_ROOT="$SYMLINK_ROOT" "$RUNNER" preflight "$VALID_UDID" >/dev/null 2>&1); then
    fail 'symlink repository-root override was accepted'
fi
FOREIGN_ROOT="$TMP_DIR/foreign-root"
mkdir "$FOREIGN_ROOT"
if (cd "$SOURCE_ROOT" && run env PHOENIX_HARNESS_REPO_ROOT="$FOREIGN_ROOT" "$RUNNER" preflight "$VALID_UDID" >/dev/null 2>&1); then
    fail 'foreign/outside repository-root override was accepted'
fi
[[ ! -s "$LOG" ]] || fail 'invalid repository-root override reached fake simulator commands'

OVERRIDE_WORKTREE="$TMP_DIR/override-worktree"
"$REAL_GIT" -C "$SOURCE_ROOT" worktree add --detach "$OVERRIDE_WORKTREE" HEAD >/dev/null
OVERRIDE_WORKTREE="$(cd "$OVERRIDE_WORKTREE" && pwd -P)"
if ! (cd "$OVERRIDE_WORKTREE" && run env PHOENIX_HARNESS_REPO_ROOT="$OVERRIDE_WORKTREE" "$RUNNER" preflight "$VALID_UDID" >/dev/null); then
    fail 'canonical current git worktree override was rejected'
fi
grep -F 'xcrun simctl list devices' "$LOG" >/dev/null || fail 'valid repository-root override did not reach preflight simulator inventory'
"$REAL_GIT" -C "$SOURCE_ROOT" worktree remove --force "$OVERRIDE_WORKTREE" >/dev/null
OVERRIDE_WORKTREE=""

# No override preserves script-derived original-root behavior.
: > "$LOG"
if ! (cd "$SOURCE_ROOT" && run "$RUNNER" preflight "$VALID_UDID" >/dev/null); then
    fail 'no-override preflight failed'
fi
grep -F 'xcrun simctl list devices' "$LOG" >/dev/null || fail 'no-override behavior did not use the original root'

# A local case must not reset/uninstall anything without the explicit gate.
GATED_ARTIFACT="$TMP_DIR/gated-artifact"
if run env PHOENIX_HARNESS_UDID=11111111-2222-3333-4444-555555555555 "$RUNNER" case "$GATED_ARTIFACT" just-lift-connected >/dev/null 2>&1; then
    fail 'destructive gate did not refuse local case'
fi
if grep -E 'terminate|uninstall|erase' "$LOG" >/dev/null 2>&1; then fail 'destructive command ran without gate'; fi

# Unsafe path and clean refusal checks do not reach fake tools.
if run "$RUNNER" verify "$TMP_DIR/../artifact" >/dev/null 2>&1; then fail 'path traversal accepted'; fi
if run env GITHUB_TOKEN=«redacted:ghp_…» "$RUNNER" preflight 11111111-2222-3333-4444-555555555555 >"$TMP_DIR/credential.out" 2>&1; then
    fail 'credential-bearing environment accepted'
fi
grep -F 'credential-like argument or environment value refused; remove the value and retry' "$TMP_DIR/credential.out" >/dev/null \
    || fail 'credential rejection did not emit a safe actionable error'
UNSAFE="$TMP_DIR/clean-refusal"
mkdir "$UNSAFE"
chmod 700 "$UNSAFE"
if run "$RUNNER" clean "$UNSAFE" >/dev/null 2>&1; then fail 'clean removed unvalidated root'; fi
[[ -d "$UNSAFE" ]] || fail 'clean refusal changed root'

# Exercise the complete synthetic seam without treating it as real-app evidence.
# The real case gate remains separate: this only proves secure command wiring and
# manifest assembly against fake Apple tools.
ARTIFACT="$TMP_DIR/artifact"
python3 - "$CONFIG" "$CONFIG_BACKUP" <<'PY'
import shutil
import sys
from pathlib import Path
config = Path(sys.argv[1])
backup = Path(sys.argv[2])
if config.exists():
    shutil.move(str(config), str(backup))
PY
CONFIG_MOVED=1
if ! run env -u JAVA_HOME \
    PHANTOM_EXPECTED_JAVA_HOME="$JAVA_HOME_VALID" \
    PHANTOM_FAKE_PATH_LOG="$PATH_LOG" \
    PHOENIX_HARNESS_UDID=11111111-2222-3333-4444-555555555555 \
    PHOENIX_HARNESS_ALLOW_DESTRUCTIVE=1 \
    "$RUNNER" case "$ARTIFACT" just-lift-connected >"$TMP_DIR/case.out" 2>&1; then
    python3 - "$TMP_DIR/case.out" "$LOG" "$ARTIFACT/build.log" <<'PY'
from pathlib import Path
import sys
for path in sys.argv[1:]:
    print("---", path)
    try:
        print(Path(path).read_text(), end="")
    except OSError as error:
        print(error)
PY
    fail 'synthetic case failed'
fi
grep -Fx 'phantom-harness: Java runtime available (major 21)' "$TMP_DIR/case.out" >/dev/null \
    || fail 'missing JAVA_HOME did not report Java availability'
if grep -F "$JAVA_HOME_VALID" "$TMP_DIR/case.out" >/dev/null 2>&1; then
    fail 'Java status leaked the runtime path'
fi
[[ ! -e "$CONFIG" ]] || fail 'temporary Supabase config was not removed'
python3 - "$ARTIFACT" <<'PY'
import json
import os
import stat
import sys
import zlib
from pathlib import Path
root = Path(sys.argv[1])
assert stat.S_IMODE(os.lstat(root).st_mode) == 0o700
manifest_path = root / "run.json"
assert stat.S_IMODE(os.lstat(manifest_path).st_mode) == 0o600
manifest = json.loads(manifest_path.read_text())
assert manifest["schemaVersion"] == 1
assert manifest["provenance"]["fixture"]["id"] == "just-lift-connected"
assert manifest["commands"] and all(item["exitCode"] == 0 for item in manifest["commands"])
result_commands = [item for item in manifest["commands"] if item["name"] == "run-tests"]
assert result_commands[0]["resultBundle"] == {"basename": "test.xcresult", "status": "private-not-retained"}
assert all("resultBundlePath" not in item for item in manifest["commands"])
assert manifest["captures"]
assert "phantom.connected" in manifest["semanticMarkers"]["observed"]
assert not (root / "derived-data").exists()
assert not (root / "test.xcresult").exists()
xctest_attachment = root / "xctest-attachment.png"
assert stat.S_IMODE(os.lstat(xctest_attachment).st_mode) == 0o600
data = xctest_attachment.read_bytes()
assert data[:8] == b"\x89PNG\r\n\x1a\n"
assert data[8:12] == b"\x00\x00\x00\x0d"
assert data[12:16] == b"IHDR"
assert zlib.crc32(data[12:29]) & 0xffffffff == int.from_bytes(data[29:33], "big")
assert int.from_bytes(data[16:20], "big") > 0
assert int.from_bytes(data[20:24], "big") > 0
PY
python3 - "$PATH_LOG" "$ARTIFACT" <<'PY'
import os
import sys
from pathlib import Path

paths = {}
for line in Path(sys.argv[1]).read_text().splitlines():
    key, value = line.split("=", 1)
    paths.setdefault(key, []).append(Path(value))
root = Path(sys.argv[2]).resolve()
assert paths["derived"] and paths["result"]
private_roots = set()
for path in paths["derived"] + paths["result"]:
    resolved = path.resolve()
    assert os.path.commonpath((str(root), str(resolved))) != str(root), (root, path)
    assert not path.exists(), path
    private_roots.add(path.parent)
for private_root in private_roots:
    assert not private_root.exists(), private_root
PY
python3 - "$EXPORT_LOG" "$ARTIFACT" <<'PY'
import os
import sys
from pathlib import Path
exports = []
for line in Path(sys.argv[1]).read_text().splitlines():
    if line.startswith("export="):
        exports.append(Path(line.split("=", 1)[1]))
assert len(exports) == 1, exports
assert not exports[0].exists(), exports[0]
assert os.path.commonpath((str(Path(sys.argv[2]).resolve()), str(exports[0].resolve()))) != str(Path(sys.argv[2]).resolve())
PY
grep -F 'xcrun xcresulttool export attachments --path ' "$LOG" >/dev/null \
    || fail 'XCTest attachment export command was not invoked'
run "$RUNNER" verify "$ARTIFACT" >/dev/null

# An invalid JAVA_HOME must fall back to the real java executable on PATH.
INVALID_JAVA_HOME="$TMP_DIR/invalid-jdk"
mkdir -p "$INVALID_JAVA_HOME/bin"
cat > "$INVALID_JAVA_HOME/bin/java" <<'SH'
#!/usr/bin/env bash
exit 97
SH
chmod 700 "$INVALID_JAVA_HOME/bin/java"
FALLBACK_ARTIFACT="$TMP_DIR/fallback-artifact"
if ! run env \
    JAVA_HOME="$INVALID_JAVA_HOME" \
    PHANTOM_EXPECTED_JAVA_HOME="$JAVA_HOME_VALID" \
    PHOENIX_HARNESS_UDID=11111111-2222-3333-4444-555555555555 \
    PHOENIX_HARNESS_ALLOW_DESTRUCTIVE=1 \
    "$RUNNER" case "$FALLBACK_ARTIFACT" just-lift-connected >"$TMP_DIR/fallback.out" 2>&1; then
    python3 - "$TMP_DIR/fallback.out" "$FALLBACK_ARTIFACT/build.log" <<'PY'
from pathlib import Path
import sys
for path in sys.argv[1:]:
    print("---", path)
    try:
        print(Path(path).read_text(), end="")
    except OSError as error:
        print(error)
PY
    fail 'invalid JAVA_HOME did not fall back to PATH java'
fi
grep -Fx 'phantom-harness: Java runtime available (major 21)' "$TMP_DIR/fallback.out" >/dev/null \
    || fail 'invalid JAVA_HOME fallback did not report Java availability'
run "$RUNNER" clean "$FALLBACK_ARTIFACT"

# A failed test still creates private Xcode trees in this seam.  The combined
# EXIT trap must remove both those trees and the temporary fixture config.
FAIL_ARTIFACT="$TMP_DIR/failure-artifact"
: > "$PATH_LOG"
if run env \
    PHANTOM_EXPECTED_JAVA_HOME="$JAVA_HOME_VALID" \
    PHANTOM_FAKE_PATH_LOG="$PATH_LOG" \
    PHANTOM_FAKE_FAIL_TEST=1 \
    PHOENIX_HARNESS_UDID=11111111-2222-3333-4444-555555555555 \
    PHOENIX_HARNESS_ALLOW_DESTRUCTIVE=1 \
    "$RUNNER" case "$FAIL_ARTIFACT" just-lift-connected >"$TMP_DIR/failure.out" 2>&1; then
    fail 'failed synthetic test was accepted'
fi
[[ ! -e "$CONFIG" ]] || fail 'temporary Supabase config survived failed case'
python3 - "$PATH_LOG" "$FAIL_ARTIFACT" <<'PY'
import os
import sys
from pathlib import Path

paths = {}
for line in Path(sys.argv[1]).read_text().splitlines():
    key, value = line.split("=", 1)
    paths.setdefault(key, []).append(Path(value))
root = Path(sys.argv[2]).resolve()
assert paths["derived"] and paths["result"]
private_roots = set()
for path in paths["derived"] + paths["result"]:
    resolved = path.resolve()
    assert os.path.commonpath((str(root), str(resolved))) != str(root), (root, path)
    assert not path.exists(), path
    private_roots.add(path.parent)
for private_root in private_roots:
    assert not private_root.exists(), private_root
PY

# A failed xcresulttool export must fail closed and remove its private export
# directory without leaving a passing manifest behind.
EXPORT_FAILURE_ARTIFACT="$TMP_DIR/export-failure-artifact"
: > "$EXPORT_LOG"
if run env \
    PHANTOM_EXPECTED_JAVA_HOME="$JAVA_HOME_VALID" \
    PHANTOM_FAKE_XCRESULT_EXPORT_FAIL=1 \
    PHOENIX_HARNESS_UDID=11111111-2222-3333-4444-555555555555 \
    PHOENIX_HARNESS_ALLOW_DESTRUCTIVE=1 \
    "$RUNNER" case "$EXPORT_FAILURE_ARTIFACT" just-lift-connected >"$TMP_DIR/export-failure.out" 2>&1; then
    fail 'failed xcresulttool export was accepted'
fi
grep -F 'XCTest attachment capture failed; passing evidence cannot be produced' "$TMP_DIR/export-failure.out" >/dev/null \
    || fail 'failed xcresulttool export failure was not actionable'
[[ ! -e "$EXPORT_FAILURE_ARTIFACT/run.json" ]] || fail 'failed xcresulttool export left a manifest'
python3 - "$EXPORT_LOG" <<'PY'
import sys
from pathlib import Path
exports = [Path(line.split("=", 1)[1]) for line in Path(sys.argv[1]).read_text().splitlines() if line.startswith("export=")]
assert len(exports) == 1, exports
assert not exports[0].exists(), exports[0]
PY

# A successful XCTest command without a copied attachment must fail closed and
# must not leave a passing manifest behind.
NO_ATTACHMENT_ARTIFACT="$TMP_DIR/no-attachment-artifact"
if run env \
    PHANTOM_EXPECTED_JAVA_HOME="$JAVA_HOME_VALID" \
    PHANTOM_FAKE_NO_ATTACHMENT=1 \
    PHOENIX_HARNESS_UDID=11111111-2222-3333-4444-555555555555 \
    PHOENIX_HARNESS_ALLOW_DESTRUCTIVE=1 \
    "$RUNNER" case "$NO_ATTACHMENT_ARTIFACT" just-lift-connected >"$TMP_DIR/no-attachment.out" 2>&1; then
    fail 'missing XCTest attachment was accepted'
fi
grep -F 'XCTest attachment capture failed; passing evidence cannot be produced' "$TMP_DIR/no-attachment.out" >/dev/null \
    || fail 'missing XCTest attachment failure was not actionable'
[[ ! -e "$NO_ATTACHMENT_ARTIFACT/run.json" ]] || fail 'missing XCTest attachment left a manifest'

# Invalid regular candidates and a symlink to a valid PNG must not produce
# passing evidence when no valid regular candidate remains.
ONLY_INVALID_ARTIFACT="$TMP_DIR/only-invalid-artifact"
if run env \
    PHANTOM_EXPECTED_JAVA_HOME="$JAVA_HOME_VALID" \
    PHANTOM_FAKE_ONLY_INVALID_ATTACHMENTS=1 \
    PHOENIX_HARNESS_UDID=11111111-2222-3333-4444-555555555555 \
    PHOENIX_HARNESS_ALLOW_DESTRUCTIVE=1 \
    "$RUNNER" case "$ONLY_INVALID_ARTIFACT" just-lift-connected >"$TMP_DIR/only-invalid.out" 2>&1; then
    fail 'only invalid XCTest attachment candidates were accepted'
fi
grep -F 'XCTest attachment capture failed; passing evidence cannot be produced' "$TMP_DIR/only-invalid.out" >/dev/null \
    || fail 'only invalid XCTest attachment failure was not actionable'
[[ ! -e "$ONLY_INVALID_ARTIFACT/run.json" ]] || fail 'only invalid XCTest attachment left a manifest'

# Neither JAVA_HOME nor PATH java may reach an Apple tool, and the failure must
# explain how to repair the missing runtime.
NO_JAVA_ARTIFACT="$TMP_DIR/no-java-artifact"
: > "$LOG"
if run_no_java /usr/bin/env \
    JAVA_HOME="$INVALID_JAVA_HOME" \
    PHOENIX_HARNESS_UDID=11111111-2222-3333-4444-555555555555 \
    PHOENIX_HARNESS_ALLOW_DESTRUCTIVE=1 \
    "$RUNNER" case "$NO_JAVA_ARTIFACT" just-lift-connected >"$TMP_DIR/no-java.out" 2>&1; then
    fail 'missing Java runtime was accepted'
fi
grep -Fx 'phantom-harness: unable to locate a usable Java runtime; set JAVA_HOME to a JDK home or ensure java is on PATH' "$TMP_DIR/no-java.out" >/dev/null \
    || fail 'missing Java runtime failure was not actionable'
[[ ! -s "$LOG" ]] || fail 'Apple tools ran without a Java runtime'

BEFORE="$TMP_DIR/before"
AFTER="$TMP_DIR/after"
OUTPUT="$TMP_DIR/compare"
mkdir "$BEFORE" "$AFTER"
chmod 700 "$BEFORE" "$AFTER"
python3 - "$BEFORE/after.png" "$BEFORE/xctest-attachment.png" "$AFTER/after.png" "$AFTER/xctest-attachment.png" <<'PY'
import struct
import sys
import zlib

def chunk(kind, payload):
    return len(payload).to_bytes(4, "big") + kind + payload + zlib.crc32(kind + payload).to_bytes(4, "big")

def write_png(path, pixels):
    width = height = 4
    rows = b"".join(b"\x00" + b"".join(pixels[row]) for row in range(height))
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    with open(path, "wb") as stream:
        stream.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))

home_screen = [
    [bytes((28, 28, 30, 255)), bytes((242, 242, 247, 255)), bytes((28, 28, 30, 255)), bytes((242, 242, 247, 255))],
    [bytes((28, 28, 30, 255)), bytes((242, 242, 247, 255)), bytes((28, 28, 30, 255)), bytes((242, 242, 247, 255))],
    [bytes((242, 242, 247, 255)), bytes((28, 28, 30, 255)), bytes((242, 242, 247, 255)), bytes((28, 28, 30, 255))],
    [bytes((242, 242, 247, 255)), bytes((28, 28, 30, 255)), bytes((242, 242, 247, 255)), bytes((28, 28, 30, 255))],
]
before_app = [[bytes((17, 71, 105, 255)) for _ in range(4)] for _ in range(4)]
after_app = [
    [bytes((12, 105, 76, 255)), bytes((245, 255, 245, 255)), bytes((12, 105, 76, 255)), bytes((245, 255, 245, 255))],
    [bytes((12, 105, 76, 255)), bytes((245, 255, 245, 255)), bytes((12, 105, 76, 255)), bytes((245, 255, 245, 255))],
    [bytes((245, 255, 245, 255)), bytes((12, 105, 76, 255)), bytes((245, 255, 245, 255)), bytes((12, 105, 76, 255))],
    [bytes((245, 255, 245, 255)), bytes((12, 105, 76, 255)), bytes((245, 255, 245, 255)), bytes((12, 105, 76, 255))],
]
write_png(sys.argv[1], home_screen)
write_png(sys.argv[2], before_app)
write_png(sys.argv[3], home_screen)
write_png(sys.argv[4], after_app)
PY
chmod 600 "$BEFORE/after.png" "$BEFORE/xctest-attachment.png" "$AFTER/after.png" "$AFTER/xctest-attachment.png"
run "$RUNNER" compare "$BEFORE" "$AFTER" "$OUTPUT" >/dev/null
python3 - "$OUTPUT/diff.json" <<'PY'
import json
import os
import stat
import sys
from pathlib import Path
root = Path(sys.argv[1]).parent
result = json.loads(Path(sys.argv[1]).read_text())
assert result["changedPixels"] == 16, f"verified XCTest capture was not selected: {result}"
assert result["inputs"] == {"before": "xctest-attachment.png", "after": "xctest-attachment.png"}, result
assert result["passed"] is False, result
for name in ("diff.png", "diff.json"):
    assert stat.S_IMODE(os.lstat(root / name).st_mode) == 0o600
PY

# An absent XCTest attachment must fall back to the simulator capture without
# allowing the decoy to become a priority when a verified attachment exists.
FALLBACK_AFTER="$TMP_DIR/fallback-after"
mkdir "$FALLBACK_AFTER"
chmod 700 "$FALLBACK_AFTER"
cp "$AFTER/after.png" "$FALLBACK_AFTER/after.png"
chmod 600 "$FALLBACK_AFTER/after.png"
FALLBACK_OUTPUT="$TMP_DIR/fallback-compare"
run "$RUNNER" compare "$BEFORE" "$FALLBACK_AFTER" "$FALLBACK_OUTPUT" >/dev/null
python3 - "$FALLBACK_OUTPUT/diff.json" <<'PY'
import json
import sys
from pathlib import Path
result = json.loads(Path(sys.argv[1]).read_text())
assert result["inputs"] == {"before": "xctest-attachment.png", "after": "after.png"}, result
assert result["passed"] is False, result
assert result["changedPixels"] == 16, result
PY

# A malformed regular attachment is ignored in favour of a valid fallback, but
# a symlink candidate remains a hard failure under the existing input contract.
INVALID_AFTER="$TMP_DIR/invalid-after"
mkdir "$INVALID_AFTER"
chmod 700 "$INVALID_AFTER"
cp "$AFTER/after.png" "$INVALID_AFTER/after.png"
: > "$INVALID_AFTER/xctest-attachment.png"
chmod 600 "$INVALID_AFTER/after.png" "$INVALID_AFTER/xctest-attachment.png"
INVALID_OUTPUT="$TMP_DIR/invalid-compare"
run "$RUNNER" compare "$BEFORE" "$INVALID_AFTER" "$INVALID_OUTPUT" >/dev/null
python3 - "$INVALID_OUTPUT/diff.json" <<'PY'
import json
import sys
from pathlib import Path
result = json.loads(Path(sys.argv[1]).read_text())
assert result["inputs"] == {"before": "xctest-attachment.png", "after": "after.png"}, result
assert result["changedPixels"] == 16, result
PY

SYMLINK_AFTER="$TMP_DIR/symlink-after"
mkdir "$SYMLINK_AFTER"
chmod 700 "$SYMLINK_AFTER"
cp "$AFTER/after.png" "$SYMLINK_AFTER/after.png"
ln -s "$AFTER/xctest-attachment.png" "$SYMLINK_AFTER/xctest-attachment.png"
chmod 600 "$SYMLINK_AFTER/after.png"
if run "$RUNNER" compare "$BEFORE" "$SYMLINK_AFTER" "$TMP_DIR/symlink-compare" >/dev/null 2>&1; then
    fail 'symlink XCTest attachment accepted by compare'
fi

SYMLINK_OUTPUT="$TMP_DIR/symlink-output"
mkdir "$SYMLINK_OUTPUT"
chmod 700 "$SYMLINK_OUTPUT"
python3 - "$SYMLINK_OUTPUT/diff.png" "$TMP_DIR/outside.png" <<'PY'
import os
import sys
from pathlib import Path
outside = Path(sys.argv[2])
outside.write_bytes(b"outside")
os.chmod(outside, 0o600)
Path(sys.argv[1]).symlink_to(outside)
PY
if run "$RUNNER" compare "$BEFORE" "$AFTER" "$SYMLINK_OUTPUT" >/dev/null 2>&1; then
    fail 'symlinked compare output accepted'
fi
python3 - "$TMP_DIR/outside.png" <<'PY'
from pathlib import Path
import sys
assert Path(sys.argv[1]).read_bytes() == b"outside"
PY

NESTED_INPUT="$TMP_DIR/nested-input"
mkdir "$NESTED_INPUT"
chmod 700 "$NESTED_INPUT"
mkdir "$BEFORE/nested"
chmod 700 "$BEFORE/nested"
ln -s "$TMP_DIR/outside.png" "$BEFORE/nested/escaped.png"
if run "$RUNNER" compare "$BEFORE" "$AFTER" "$NESTED_INPUT" >/dev/null 2>&1; then
    fail 'nested symlink in compare input accepted'
fi

run "$RUNNER" clean "$ARTIFACT"
[[ ! -e "$ARTIFACT" ]] || fail 'validated clean did not remove only artifact root'

printf 'phantom harness shell tests passed\n'
