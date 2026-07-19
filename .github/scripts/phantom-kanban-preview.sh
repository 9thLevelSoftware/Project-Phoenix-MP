#!/usr/bin/env bash
set -euo pipefail

# Ticket-facing, constrained preview wrapper.  The caller must provide an
# absolute request JSON file and an existing, caller-owned, empty 0700 result
# directory:
#
#   phantom-kanban-preview.sh REQUEST_JSON RESULT_ROOT
#
# The request is parsed as JSON by Python's standard library.  Candidate code is
# never sourced or evaluated; the existing proposal renderer owns disposable
# worktree creation and all candidate validation.

IFS=$'\n\t'
SCRIPT_DIR="${BASH_SOURCE[0]%/*}"
if [[ "$SCRIPT_DIR" == "${BASH_SOURCE[0]}" ]]; then SCRIPT_DIR="."; fi
if [[ "$SCRIPT_DIR" != /* ]]; then SCRIPT_DIR="$PWD/$SCRIPT_DIR"; fi
SCRIPT_DIR="$(cd "$SCRIPT_DIR" && pwd -P)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
RENDERER="$REPO_ROOT/.github/scripts/phantom-proposal.sh"
RUNNER="$REPO_ROOT/.github/scripts/phantom-harness.sh"
SYSTEM_PATH="/usr/bin:/bin:/usr/sbin:/sbin"
MAX_REQUEST_BYTES=$((1024 * 1024))
MAX_PATCH_BYTES=$((128 * 1024 * 1024))
EXPECTED_UDID_RE='^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$'

REQUEST_JSON=""
RESULT_ROOT=""
RESULT_READY=0
PRIVATE_DIR=""
ARTIFACT_DIR=""
TICKET_ID=""
FIXTURE=""
PATCH_FILE=""
BASE_SHA=""
PATCH_SHA256=""
STAGE="startup"
FAILURE_REASON="preview failed"

usage() {
    printf '%s\n' 'usage: phantom-kanban-preview.sh REQUEST_JSON RESULT_ROOT' >&2
    printf '%s\n' 'REQUEST_JSON must be an absolute caller-owned private JSON file; RESULT_ROOT must be an absolute caller-owned empty 0700 directory.' >&2
    exit 2
}

fail() {
    STAGE="$1"
    FAILURE_REASON="$2"
    exit 1
}

write_failure_result() {
    [[ "$RESULT_READY" -eq 1 ]] || return 0
    python3 - "$RESULT_ROOT" "$STAGE" "$FAILURE_REASON" <<'PY' || true
import json
import os
import shutil
import stat
import sys
import tempfile
from pathlib import Path

root = Path(sys.argv[1])
stage = sys.argv[2]
reason = sys.argv[3]
try:
    info = os.lstat(root)
except OSError:
    raise SystemExit(0)
if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
    raise SystemExit(0)
for child in list(root.iterdir()):
    try:
        child_info = os.lstat(child)
    except OSError:
        raise SystemExit(0)
    if child_info.st_uid != os.getuid() or stat.S_ISLNK(child_info.st_mode):
        raise SystemExit(0)
    if stat.S_ISDIR(child_info.st_mode):
        shutil.rmtree(child)
    elif stat.S_ISREG(child_info.st_mode):
        child.unlink()
    else:
        raise SystemExit(0)
result = {
    "schema_version": 1,
    "status": "failed",
    "stage": stage,
    "reason": reason,
}
fd, temporary = tempfile.mkstemp(prefix=".tmp-preview-result-", dir=str(root))
os.close(fd)
os.chmod(temporary, 0o600)
try:
    Path(temporary).write_text(json.dumps(result, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
    os.replace(temporary, root / "preview-result.json")
    os.chmod(root / "preview-result.json", 0o600)
finally:
    try:
        os.unlink(temporary)
    except FileNotFoundError:
        pass
PY
}

cleanup() {
    if [[ -n "$PRIVATE_DIR" ]]; then
        python3 - "$PRIVATE_DIR" <<'PY' || true
import os
import shutil
import stat
import sys
from pathlib import Path
path = Path(sys.argv[1])
try:
    info = os.lstat(path)
except OSError:
    raise SystemExit(0)
if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid():
    raise SystemExit(0)
shutil.rmtree(path)
PY
    fi
}

on_exit() {
    local rc=$?
    trap - EXIT
    if [[ "$rc" -ne 0 ]]; then
        write_failure_result
    fi
    cleanup
    exit "$rc"
}
trap on_exit EXIT

validate_result_root() {
    python3 - "$1" "$REPO_ROOT" <<'PY'
import os
import stat
import subprocess
import sys
from pathlib import Path

raw = sys.argv[1]
repo = Path(sys.argv[2]).resolve()
if not raw or "\x00" in raw or "\n" in raw or "\r" in raw or "\\" in raw or not os.path.isabs(raw) or os.path.normpath(raw) != raw:
    raise SystemExit(1)
path = Path(raw)
uid = os.getuid()
current = Path(path.anchor)
parts = path.parts[1:]
for index, part in enumerate(parts):
    if part in (".", "..", ""):
        raise SystemExit(1)
    current = current / part
    try:
        info = os.lstat(current)
    except OSError:
        raise SystemExit(1)
    trusted_alias = stat.S_ISLNK(info.st_mode) and str(current) in {"/tmp", "/var"} and os.path.realpath(current) in {"/private/tmp", "/private/var"}
    if stat.S_ISLNK(info.st_mode) and not trusted_alias:
        raise SystemExit(1)
    if index < len(parts) - 1 and not trusted_alias and not stat.S_ISDIR(info.st_mode):
        raise SystemExit(1)
    if index == len(parts) - 1 and (not stat.S_ISDIR(info.st_mode) or info.st_uid != uid):
        raise SystemExit(1)
    if index == len(parts) - 1 and stat.S_IMODE(info.st_mode) != 0o700:
        raise SystemExit(1)
if not parts:
    raise SystemExit(1)
try:
    worktree_output = subprocess.run(
        ["git", "-C", str(repo), "worktree", "list", "--porcelain"],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        env={"PATH": "/usr/bin:/bin:/usr/sbin:/sbin", "LC_ALL": "C", "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": "/dev/null"},
    ).stdout
except (OSError, subprocess.SubprocessError):
    raise SystemExit(1)
worktrees = [Path(line[9:]).resolve() for line in worktree_output.splitlines() if line.startswith("worktree ")]
resolved = path.resolve(strict=True)
if any(resolved == worktree or worktree in resolved.parents for worktree in worktrees):
    raise SystemExit(1)
if list(path.iterdir()):
    raise SystemExit(1)
PY
}

make_private_dir() {
    python3 - <<'PY'
import os
import tempfile
path = tempfile.mkdtemp(prefix="phantom-kanban-preview-", dir="/tmp")
os.chmod(path, 0o700)
for name in ("home", "tmp", "proposal"):
    child = os.path.join(path, name)
    os.mkdir(child, 0o700)
    os.chmod(child, 0o700)
print(path)
PY
}

parse_request() {
    local request_path="$1"
    local repo_path="${2-$REPO_ROOT}"
    local max_request="${3-$MAX_REQUEST_BYTES}"
    local max_patch="${4-$((MAX_PATCH_BYTES - 1))}"
    python3 - "$request_path" "$repo_path" "$max_request" "$max_patch" <<'PY'
import json
import os
import re
import stat
import subprocess
import sys
from pathlib import Path

request_raw, repo_raw, max_request_raw, max_patch_raw = sys.argv[1:]
max_request = int(max_request_raw)
max_patch = int(max_patch_raw)
repo = Path(repo_raw).resolve()


def reject_path_text(value):
    return (
        not isinstance(value, str)
        or not value
        or "\x00" in value
        or any(ord(char) < 0x20 or ord(char) == 0x7f for char in value)
        or "\\" in value
        or not os.path.isabs(value)
        or os.path.normpath(value) != value
        or any(part in ("", ".", "..") for part in value.split("/")[1:])
    )


def trusted_alias(path, info):
    return stat.S_ISLNK(info.st_mode) and str(path) in {"/tmp", "/var"} and os.path.realpath(path) in {"/private/tmp", "/private/var"}


def safe_components(raw, regular=False, private=False, max_bytes=None):
    if reject_path_text(raw):
        raise SystemExit(1)
    path = Path(raw)
    current = Path(path.anchor)
    parts = path.parts[1:]
    for index, part in enumerate(parts):
        current = current / part
        try:
            info = os.lstat(current)
        except OSError:
            raise SystemExit(1)
        alias = trusted_alias(current, info)
        if stat.S_ISLNK(info.st_mode) and not alias:
            raise SystemExit(1)
        if index < len(parts) - 1 and not alias and not stat.S_ISDIR(info.st_mode):
            raise SystemExit(1)
    try:
        info = os.lstat(path)
    except OSError:
        raise SystemExit(1)
    if info.st_uid != os.getuid():
        raise SystemExit(1)
    if regular and (stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode)):
        raise SystemExit(1)
    if private and stat.S_IMODE(info.st_mode) & 0o077:
        raise SystemExit(1)
    if max_bytes is not None and info.st_size > max_bytes:
        raise SystemExit(1)
    return path.resolve(strict=True)


def worktrees():
    try:
        output = subprocess.run(
            ["git", "-C", str(repo), "worktree", "list", "--porcelain"],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            env={"PATH": "/usr/bin:/bin:/usr/sbin:/sbin", "LC_ALL": "C", "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": "/dev/null"},
        ).stdout
    except (OSError, subprocess.SubprocessError):
        raise SystemExit(1)
    paths = []
    for line in output.splitlines():
        if line.startswith("worktree "):
            paths.append(Path(line[9:]).resolve())
    if not paths:
        raise SystemExit(1)
    return paths

def inside_any(path, roots):
    return any(path == root or root in path.parents for root in roots)


def no_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON object key")
        result[key] = value
    return result


request_path = safe_components(request_raw, regular=True, private=True, max_bytes=max_request)
try:
    payload = json.loads(request_path.read_text(encoding="utf-8"), object_pairs_hook=no_duplicate_keys)
except (OSError, UnicodeDecodeError, ValueError):
    raise SystemExit(1)
if not isinstance(payload, dict) or set(payload) != {"schema_version", "ticket_id", "fixture", "patch_file", "trusted_input", "expected"}:
    raise SystemExit(1)
if type(payload["schema_version"]) is not int or payload["schema_version"] != 1:
    raise SystemExit(1)
if not isinstance(payload["ticket_id"], str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,63}", payload["ticket_id"]):
    raise SystemExit(1)
if payload["fixture"] != "just-lift-connected":
    raise SystemExit(1)
if payload["trusted_input"] is not True:
    raise SystemExit(1)
expected = payload["expected"]
if not isinstance(expected, dict) or set(expected) != {"screen", "markers"}:
    raise SystemExit(1)
if expected["screen"] != "just-lift" or expected["markers"] != ["xctest.passed", "phantom.connected"]:
    raise SystemExit(1)
roots = worktrees()
patch = safe_components(payload["patch_file"], regular=True, private=True, max_bytes=max_patch)
if inside_any(patch, roots):
    raise SystemExit(1)
print("%s\t%s\t%s" % (payload["ticket_id"], payload["fixture"], str(patch)))
PY
}

valid_optional_env() {
    python3 - "$1" <<'PY'
import os
import sys
value = sys.argv[1]
if not value or "\x00" in value or "\n" in value or "\r" in value or "\\" in value or not os.path.isabs(value) or os.path.normpath(value) != value:
    raise SystemExit(1)
PY
}

hash_file() {
    python3 - "$1" <<'PY'
import hashlib
import sys
from pathlib import Path
digest = hashlib.sha256()
with Path(sys.argv[1]).open("rb") as stream:
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(block)
print(digest.hexdigest())
PY
}

validate_renderer_artifacts() {
    python3 - "$ARTIFACT_DIR" "$BASE_SHA" "$PATCH_FILE" "$PATCH_SHA256" "$MAX_PATCH_BYTES" <<'PY'
import hashlib
import json
import os
import stat
import sys
from pathlib import Path

root = Path(sys.argv[1])
base = sys.argv[2]
patch = Path(sys.argv[3])
expected_patch_sha = sys.argv[4]
max_patch = int(sys.argv[5])
allowed = (
    "proposal.md",
    "evidence-summary.json",
    "proposal-manifest.json",
    "comparison/diff.png",
    "comparison/diff.json",
    "before/run.json",
    "after/run.json",
)


def private_regular(path):
    info = os.lstat(path)
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600:
        raise SystemExit(1)


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()

if not __import__("re").fullmatch(r"[0-9a-fA-F]{40}", base):
    raise SystemExit(1)
for raw in allowed:
    parts = raw.split("/")
    current = root
    for part in parts[:-1]:
        current /= part
        info = os.lstat(current)
        if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
            raise SystemExit(1)
    private_regular(root / raw)
try:
    manifest = json.loads((root / "proposal-manifest.json").read_text(encoding="utf-8"))
except (OSError, UnicodeDecodeError, json.JSONDecodeError):
    raise SystemExit(1)
if not isinstance(manifest, dict) or manifest.get("schemaVersion") != 1 or manifest.get("status") != "passed" or manifest.get("fixture") != "just-lift-connected" or manifest.get("baseSha") != base:
    raise SystemExit(1)
try:
    patch_info = os.lstat(patch)
except OSError:
    raise SystemExit(1)
if stat.S_ISLNK(patch_info.st_mode) or not stat.S_ISREG(patch_info.st_mode) or patch_info.st_uid != os.getuid() or stat.S_IMODE(patch_info.st_mode) & 0o077 or patch_info.st_size >= max_patch:
    raise SystemExit(1)
if digest(patch) != expected_patch_sha:
    raise SystemExit(1)
PY
}

publish_success() {
    python3 - "$ARTIFACT_DIR" "$RESULT_ROOT" "$TICKET_ID" "$FIXTURE" "$BASE_SHA" "$PATCH_SHA256" <<'PY'
import hashlib
import json
import os
import shutil
import stat
import sys
import tempfile
from pathlib import Path

source = Path(sys.argv[1])
destination = Path(sys.argv[2])
ticket = sys.argv[3]
fixture = sys.argv[4]
base = sys.argv[5]
patch_sha = sys.argv[6]
paths = (
    "proposal.md",
    "evidence-summary.json",
    "proposal-manifest.json",
    "comparison/diff.png",
    "comparison/diff.json",
    "before/run.json",
    "after/run.json",
)


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def ensure_private_root(path):
    info = os.lstat(path)
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
        raise SystemExit(1)

ensure_private_root(destination)
if list(destination.iterdir()):
    raise SystemExit(1)
entries = []
for raw in paths:
    src = source / raw
    info = os.lstat(src)
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600:
        raise SystemExit(1)
    target = destination / raw
    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(target.parent, 0o700)
    fd, temporary = tempfile.mkstemp(prefix=".tmp-preview-copy-", dir=str(target.parent))
    os.close(fd)
    os.chmod(temporary, 0o600)
    try:
        with src.open("rb") as stream, open(temporary, "wb") as output:
            shutil.copyfileobj(stream, output, length=1024 * 1024)
        os.replace(temporary, target)
        os.chmod(target, 0o600)
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
    entries.append({"path": raw, "sha256": digest(target)})
result = {
    "schema_version": 1,
    "status": "passed",
    "ticket_id": ticket,
    "fixture": fixture,
    "base_sha": base,
    "patch_sha256": patch_sha,
    "artifacts": entries,
}
fd, temporary = tempfile.mkstemp(prefix=".tmp-preview-result-", dir=str(destination))
os.close(fd)
os.chmod(temporary, 0o600)
try:
    Path(temporary).write_text(json.dumps(result, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
    os.replace(temporary, destination / "preview-result.json")
    os.chmod(destination / "preview-result.json", 0o600)
finally:
    try:
        os.unlink(temporary)
    except FileNotFoundError:
        pass
PY
}

main() {
    [[ "$#" -eq 2 ]] || usage
    REQUEST_JSON="$1"
    RESULT_ROOT="$2"

    STAGE="validate-result-root"
    if ! validate_result_root "$RESULT_ROOT" >/dev/null 2>&1; then
        exit 1
    fi
    RESULT_READY=1

    STAGE="startup"
    if ! PRIVATE_DIR="$(make_private_dir)"; then
        fail startup "private workspace setup failed"
    fi
    ARTIFACT_DIR="$PRIVATE_DIR/proposal"

    STAGE="validate-request"
    local request_fields
    if ! request_fields="$(parse_request "$REQUEST_JSON" "$REPO_ROOT" "$MAX_REQUEST_BYTES" "$((MAX_PATCH_BYTES - 1))" 2>/dev/null)"; then
        fail validate-request "request validation failed"
    fi
    IFS=$'\t' read -r TICKET_ID FIXTURE PATCH_FILE <<< "$request_fields"
    if [[ -z "$TICKET_ID" || "$FIXTURE" != "just-lift-connected" || -z "$PATCH_FILE" ]]; then
        fail validate-request "request validation failed"
    fi

    if ! BASE_SHA="$(git -C "$REPO_ROOT" rev-parse --verify HEAD 2>/dev/null)" || [[ ! "$BASE_SHA" =~ ^[0-9a-fA-F]{40}$ ]]; then
        fail validate-request "request validation failed"
    fi
    if ! PATCH_SHA256="$(hash_file "$PATCH_FILE" 2>/dev/null)" || [[ ! "$PATCH_SHA256" =~ ^[0-9a-fA-F]{64}$ ]]; then
        fail validate-request "request validation failed"
    fi
    if [[ ! "${PHOENIX_HARNESS_UDID-}" =~ $EXPECTED_UDID_RE ]]; then
        fail renderer "renderer failed"
    fi

    local -a child_env=(
        "/usr/bin/env" "-i"
        "PATH=$SYSTEM_PATH"
        "HOME=$PRIVATE_DIR/home"
        "TMPDIR=$PRIVATE_DIR/tmp"
        "LC_ALL=C"
        "PHOENIX_PROPOSAL_TRUSTED_INPUT=1"
        "PHOENIX_HARNESS_ALLOW_DESTRUCTIVE=1"
        "PHOENIX_HARNESS_UDID=${PHOENIX_HARNESS_UDID}"
    )
    if [[ -n "${JAVA_HOME-}" ]] && valid_optional_env "$JAVA_HOME" >/dev/null 2>&1; then
        child_env+=("JAVA_HOME=$JAVA_HOME")
    fi
    if [[ -n "${DEVELOPER_DIR-}" ]] && valid_optional_env "$DEVELOPER_DIR" >/dev/null 2>&1; then
        child_env+=("DEVELOPER_DIR=$DEVELOPER_DIR")
    fi

    STAGE="renderer"
    if ! "${child_env[@]}" "$RENDERER" render "$ARTIFACT_DIR" "$FIXTURE" "$PATCH_FILE" >"$PRIVATE_DIR/renderer.log" 2>&1; then
        fail renderer "renderer failed"
    fi

    STAGE="verify-before"
    if ! "${child_env[@]}" "$RUNNER" verify "$ARTIFACT_DIR/before" >"$PRIVATE_DIR/before-verify.log" 2>&1; then
        fail verify-before "canonical verification failed"
    fi
    STAGE="verify-after"
    if ! "${child_env[@]}" "$RUNNER" verify "$ARTIFACT_DIR/after" >"$PRIVATE_DIR/after-verify.log" 2>&1; then
        fail verify-after "canonical verification failed"
    fi

    STAGE="validate-artifacts"
    if ! validate_renderer_artifacts; then
        fail validate-artifacts "artifact validation failed"
    fi
    local current_head
    if ! current_head="$(git -C "$REPO_ROOT" rev-parse --verify HEAD 2>/dev/null)" || [[ "$current_head" != "$BASE_SHA" ]]; then
        fail validate-artifacts "artifact validation failed"
    fi

    STAGE="publish"
    if ! publish_success; then
        fail publish "result publication failed"
    fi
}

main "$@"
