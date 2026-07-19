#!/usr/bin/env bash
set -euo pipefail

# Render a trusted-input-only, evidence-backed Phantom proposal.  Candidate
# bytes are applied only to a detached temporary worktree; the original
# checkout is treated as an immutable observation point and is checked before
# and after every operation that can execute repository code.

IFS=$'\n\t'
SCRIPT_DIR="${BASH_SOURCE[0]%/*}"
if [[ "$SCRIPT_DIR" == "${BASH_SOURCE[0]}" ]]; then SCRIPT_DIR="."; fi
if [[ "$SCRIPT_DIR" != /* ]]; then SCRIPT_DIR="$PWD/$SCRIPT_DIR"; fi
SCRIPT_DIR="$(cd "$SCRIPT_DIR" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RUNNER="$REPO_ROOT/.github/scripts/phantom-harness.sh"
VERIFY_SCRIPT="$REPO_ROOT/.github/scripts/phantom-harness-verify.py"
FIXTURE_SOURCE="$REPO_ROOT/shared/src/iosSimulatorArm64Main/kotlin/com/devil/phoenixproject/fixture/SimulatorLaunchFixture.kt"
EXPECTED_FIXTURE_SHA256="e180679548a2d96dbc59c51449edb3b99c19d3e3be82eca98c0707a21a64e78e"
EXPECTED_BUNDLE_ID="com.devil.phoenixproject.projectphoenix"
SENTINEL_NAME=".phantom-proposal"
SENTINEL_CONTENT=$'phantom-proposal-artifact-v1\n'
SYSTEM_PATH="/usr/bin:/bin:/usr/sbin:/sbin"
MAX_PATCH_BYTES=$((128 * 1024 * 1024))
MAX_CHILD_OUTPUT_BYTES=$((16 * 1024 * 1024))
MAX_PRIVATE_FILE_BYTES=$((128 * 1024 * 1024))
MAX_PRIVATE_TOTAL_BYTES=$((1024 * 1024 * 1024))
MAX_PRIVATE_FILES=20000
CHILD_TIMEOUT_SECONDS=1800
VERIFY_TIMEOUT_SECONDS=300

ARTIFACT_DIR=""
PATCH_FILE=""
BASE_SHA=""
PATCH_SHA256=""
FIXTURE_SHA256=""
PRIVATE_DIR=""
WORKTREE=""
WORKTREE_REGISTERED=0
ROOT_READY=0
STAGE="startup"
FAILURE_REASON="proposal did not complete"
ORIGINAL_STATUS_FILE=""
ORIGINAL_HEAD=""
EXPECTED_UDID=""
PATCH_KIND=""
ACTIVE_CHILD_PID=""
ACTIVE_CHILD_GROUP_FILE=""

fail() {
    FAILURE_REASON="$1"
    exit 1
}

usage() {
    printf '%s\n' 'usage: phantom-proposal.sh render ARTIFACT_DIR just-lift-connected PATCH_FILE' >&2
    exit 2
}

reject_credentials() {
    if ! python3 - "$@" <<'PY'
import os
import re
import sys

name_pattern = re.compile(
    r"(?:TOKEN|SECRET|PASSWORD|PASSWD|PRIVATE[_-]?KEY|CREDENTIAL|API[_-]?KEY|ANON[_-]?KEY|AUTHORIZATION)",
    re.IGNORECASE,
)
value_patterns = (
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    re.compile(r"\b(?:gh[pousr]|github_pat|glpat|xox[baprs]|sk|rk)[_-][A-Za-z0-9_./=-]{20,}\b", re.IGNORECASE),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bBearer\s+[A-Za-z0-9._~+/=-]{20,}", re.IGNORECASE),
    re.compile(r"\b(?:api[_-]?key|access[_-]?key|secret|password|passwd|auth[_-]?token|anon[_-]?key)\s*[:=]\s*['\"]?[A-Za-z0-9._~+/=-]{16,}", re.IGNORECASE),
)
allowed_env = {
    "CI", "DEVELOPER_DIR", "GITHUB_ACTIONS", "GITHUB_SHA", "HOME", "LANG",
    "LC_ALL", "LC_CTYPE", "LOGNAME", "PATH", "PHOENIX_HARNESS_ALLOW_DESTRUCTIVE",
    "PHOENIX_HARNESS_UDID", "PHOENIX_PROPOSAL_TRUSTED_INPUT", "PWD", "SHELL",
    "SHLVL", "TMPDIR", "USER", "PHOENIX_PROPOSAL_TIMEOUT_SECONDS",
}
for argument in sys.argv[1:]:
    if "\x00" in argument or any(pattern.search(argument) for pattern in value_patterns):
        raise SystemExit(1)
for key, value in os.environ.items():
    if key == "PHOENIX_SIMULATOR_FIXTURE" and value != "just-lift-connected":
        raise SystemExit(1)
    if key not in allowed_env and name_pattern.search(key) and value:
        raise SystemExit(1)
    if key not in {"PATH", "PWD", "OLDPWD", "SHLVL"} and any(pattern.search(value) for pattern in value_patterns):
        raise SystemExit(1)
PY
    then
        fail 'credential-like argument or environment value refused; remove the value and retry'
    fi
}

normalize_path() {
    local raw="$1"
    local allow_missing="$2"
    python3 - "$raw" "$allow_missing" <<'PY'
import os
import stat
import sys
from pathlib import Path
raw = sys.argv[1]
allow_missing = sys.argv[2] == "1"
if not raw or "\x00" in raw or "\n" in raw or "\r" in raw or "\\" in raw:
    raise SystemExit(1)
if any(part in (".", "..") for part in raw.split("/")):
    raise SystemExit(1)
path = Path(raw)
if not path.is_absolute():
    path = Path.cwd() / path
path = Path(os.path.normpath(str(path)))
if str(path) == "/":
    raise SystemExit(1)
uid = os.getuid()
current = Path(path.anchor)
parts = path.parts[1:]
missing = False
for index, part in enumerate(parts):
    current = current / part
    try:
        info = os.lstat(current)
    except FileNotFoundError:
        missing = True
        if not allow_missing:
            raise SystemExit(1)
        break
    except OSError:
        raise SystemExit(1)
    trusted_alias = stat.S_ISLNK(info.st_mode) and str(current) in {"/var", "/tmp"} and os.path.realpath(current) in {"/private/var", "/private/tmp"}
    if stat.S_ISLNK(info.st_mode) and not trusted_alias:
        raise SystemExit(1)
    if index < len(parts) - 1 and not trusted_alias and not stat.S_ISDIR(info.st_mode):
        raise SystemExit(1)
    if index == len(parts) - 1 and not trusted_alias and not stat.S_ISDIR(info.st_mode):
        raise SystemExit(1)
    if index == len(parts) - 1 and info.st_uid != uid:
        raise SystemExit(1)
if missing:
    parent = current if current.exists() else current.parent
    try:
        info = os.lstat(parent)
    except OSError:
        raise SystemExit(1)
    trusted_parent = stat.S_ISLNK(info.st_mode) and str(parent) in {"/var", "/tmp"} and os.path.realpath(parent) in {"/private/var", "/private/tmp"}
    if stat.S_ISLNK(info.st_mode) and not trusted_parent:
        raise SystemExit(1)
    if not trusted_parent and not stat.S_ISDIR(info.st_mode):
        raise SystemExit(1)
    owner = os.stat(parent) if trusted_parent else info
    mode = stat.S_IMODE(owner.st_mode)
    if owner.st_uid != uid and not (mode & 0o1000 and mode & 0o2):
        raise SystemExit(1)
print(str(path))
PY
}

normalize_file_path() {
    local raw="$1"
    python3 - "$raw" <<'PY'
import os
import stat
import sys
from pathlib import Path
raw = sys.argv[1]
if not raw or "\x00" in raw or "\n" in raw or "\r" in raw or "\\" in raw:
    raise SystemExit(1)
if any(part in (".", "..") for part in raw.split("/")):
    raise SystemExit(1)
path = Path(raw)
if not path.is_absolute():
    path = Path.cwd() / path
path = Path(os.path.normpath(str(path)))
if str(path) == "/":
    raise SystemExit(1)
uid = os.getuid()
current = Path(path.anchor)
parts = path.parts[1:]
for index, part in enumerate(parts):
    current = current / part
    try:
        info = os.lstat(current)
    except OSError:
        raise SystemExit(1)
    trusted_alias = stat.S_ISLNK(info.st_mode) and str(current) in {"/var", "/tmp"} and os.path.realpath(current) in {"/private/var", "/private/tmp"}
    if stat.S_ISLNK(info.st_mode) and not trusted_alias:
        raise SystemExit(1)
    if index < len(parts) - 1 and not trusted_alias and not stat.S_ISDIR(info.st_mode):
        raise SystemExit(1)
    if index == len(parts) - 1 and (not stat.S_ISREG(info.st_mode) or info.st_uid != uid):
        raise SystemExit(1)
print(str(path))
PY
}

validate_regular_file() {
    local path="$1"
    local private_mode="$2"
    python3 - "$path" "$private_mode" <<'PY'
import os
import stat
import sys
from pathlib import Path
path = Path(sys.argv[1])
private_mode = sys.argv[2] == "1"
try:
    info = os.lstat(path)
except OSError:
    raise SystemExit(1)
if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
    raise SystemExit(1)
if private_mode and stat.S_IMODE(info.st_mode) & 0o077:
    raise SystemExit(1)
PY
}

prepare_root() {
    local root="$1"
    python3 - "$root" "$SENTINEL_NAME" "$SENTINEL_CONTENT" <<'PY'
import os
import stat
import sys
from pathlib import Path
root = Path(sys.argv[1])
sentinel = root / sys.argv[2]
try:
    info = os.lstat(root)
except FileNotFoundError:
    root.mkdir(mode=0o700, parents=False)
    info = os.lstat(root)
except OSError:
    raise SystemExit(1)
if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid():
    raise SystemExit(1)
if list(root.iterdir()):
    raise SystemExit(1)
os.chmod(root, 0o700)
fd = os.open(str(sentinel), os.O_CREAT | os.O_EXCL | os.O_WRONLY | getattr(os, "O_NOFOLLOW", 0), 0o600)
try:
    os.write(fd, sys.argv[3].encode("utf-8"))
finally:
    os.close(fd)
os.chmod(sentinel, 0o600)
PY
}

create_empty_dir() {
    local path="$1"
    python3 - "$path" <<'PY'
import os
import stat
import sys
from pathlib import Path
path = Path(sys.argv[1])
try:
    info = os.lstat(path)
except FileNotFoundError:
    path.mkdir(mode=0o700, parents=False)
    os.chmod(path, 0o700)
    raise SystemExit(0)
except OSError:
    raise SystemExit(1)
if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or list(path.iterdir()):
    raise SystemExit(1)
os.chmod(path, 0o700)
PY
}

new_private_dir() {
    python3 - <<'PY'
import os
import tempfile
path = tempfile.mkdtemp(prefix="phantom-proposal-", dir=os.environ.get("TMPDIR") or None)
os.chmod(path, 0o700)
print(path)
PY
}

safe_write_text() {
    local destination="$1"
    local text="$2"
    python3 - "$destination" "$text" <<'PY'
import os
import stat
import sys
import tempfile
from pathlib import Path
path = Path(sys.argv[1])
try:
    info = os.lstat(path)
except FileNotFoundError:
    info = None
except OSError:
    raise SystemExit(1)
if info is not None and (stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode)):
    raise SystemExit(1)
fd, temporary = tempfile.mkstemp(prefix=".tmp-write-", dir=str(path.parent))
os.close(fd)
os.chmod(temporary, 0o600)
try:
    Path(temporary).write_text(sys.argv[2], encoding="utf-8")
    os.replace(temporary, path)
    os.chmod(path, 0o600)
finally:
    try:
        os.unlink(temporary)
    except FileNotFoundError:
        pass
PY
}

safe_copy() {
    local source="$1"
    local destination="$2"
    python3 - "$source" "$destination" <<'PY'
import os
import stat
import sys
import tempfile
from pathlib import Path
source = Path(sys.argv[1])
destination = Path(sys.argv[2])
source_info = os.lstat(source)
if stat.S_ISLNK(source_info.st_mode) or not stat.S_ISREG(source_info.st_mode):
    raise SystemExit(1)
try:
    destination_info = os.lstat(destination)
except FileNotFoundError:
    destination_info = None
if destination_info is not None and (stat.S_ISLNK(destination_info.st_mode) or not stat.S_ISREG(destination_info.st_mode)):
    raise SystemExit(1)
fd, temporary = tempfile.mkstemp(prefix=".tmp-copy-", dir=str(destination.parent))
os.close(fd)
os.chmod(temporary, 0o600)
try:
    with source.open("rb") as src, open(temporary, "wb") as dst:
        for block in iter(lambda: src.read(1024 * 1024), b""):
            dst.write(block)
    os.replace(temporary, destination)
    os.chmod(destination, 0o600)
finally:
    try:
        os.unlink(temporary)
    except FileNotFoundError:
        pass
PY
}

sha256_file() {
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

record_original_state() {
    if ! BASE_SHA="$(git -C "$REPO_ROOT" rev-parse --verify HEAD 2>"$PRIVATE_DIR/base-sha.log")"; then
        fail 'base commit could not be verified'
    fi
    [[ "$BASE_SHA" =~ ^[0-9a-fA-F]{40}$ ]] || fail 'base commit is malformed'
    ORIGINAL_HEAD="$BASE_SHA"
    ORIGINAL_STATUS_FILE="$PRIVATE_DIR/original-status"
    # The original integrity boundary covers tracked and unignored source
    # changes.  Ignore ordinary local build/simulator/config artifacts; they
    # are not source mutations and are expected in a developer checkout.
    if ! git -C "$REPO_ROOT" status --porcelain=v2 --untracked-files=all --ignored=no >"$ORIGINAL_STATUS_FILE" 2>&1; then
        fail 'original harness status could not be verified'
    fi
    chmod 600 "$ORIGINAL_STATUS_FILE"
    if [[ -s "$ORIGINAL_STATUS_FILE" ]]; then
        fail 'original harness worktree is not exactly clean'
    fi
}

assert_original_unchanged() {
    local label="$1"
    local head_file="$PRIVATE_DIR/head-$label"
    local status_file="$PRIVATE_DIR/status-$label"
    local current_head
    if ! current_head="$(git -C "$REPO_ROOT" rev-parse --verify HEAD 2>"$PRIVATE_DIR/head-command.log")"; then
        fail "original HEAD could not be verified $label"
    fi
    if [[ "$current_head" != "$ORIGINAL_HEAD" ]]; then
        fail "original HEAD changed $label"
    fi
    if ! git -C "$REPO_ROOT" status --porcelain=v2 --untracked-files=all --ignored=no >"$status_file" 2>&1; then
        fail "original worktree status could not be verified $label"
    fi
    chmod 600 "$status_file"
    if ! cmp -s "$ORIGINAL_STATUS_FILE" "$status_file"; then
        fail "original worktree changed $label"
    fi
    : > "$head_file"
    chmod 600 "$head_file"
}

create_patch_metadata() {
    local input="$1"
    local snapshot="$2"
    local metadata="$3"
    python3 - "$input" "$snapshot" "$metadata" "$MAX_PATCH_BYTES" <<'PY'
import hashlib
import json
import os
import re
import stat
import sys
from pathlib import Path
source = Path(sys.argv[1])
snapshot = Path(sys.argv[2])
metadata = Path(sys.argv[3])
max_bytes = int(sys.argv[4])
info = os.lstat(source)
if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077:
    raise SystemExit(1)
raw = source.read_bytes()
if not raw or len(raw) > max_bytes:
    raise SystemExit(1)
secret_patterns = (
    re.compile(rb"(?i)\b(?:SUPABASE_ANON_KEY|API_TOKEN)\s*[:=][^\r\n]{1,4096}"),
    re.compile(rb"(?im)^\s*\+?\s*(?:export\s+)?(?:SUPABASE_ANON_KEY|API_TOKEN)\s*[:=][^\r\n]*$"),
    re.compile(rb"(?im)^\s*\+?\s*\b(?:TOKEN|SECRET|PASSWORD|PASSWD|PRIVATE[_-]?KEY|CREDENTIAL|API[_-]?KEY|ANON[_-]?KEY)\s*[:=][^\r\n]{1,4096}"),
    re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    re.compile(rb"\b(?:gh[pousr]|github_pat|glpat|xox[baprs]|sk|rk)[_-][A-Za-z0-9_./=-]{20,}\b", re.I),
    re.compile(rb"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(rb"\bBearer\s+[A-Za-z0-9._~+/=-]{20,}\b", re.I),
)
if any(pattern.search(raw) for pattern in secret_patterns):
    raise SystemExit(1)
if re.search(rb"(?i)<\s*!?doctype\s+html|<\s*html\b|XCUIApplication|simctl\s+|puppeteer|playwright", raw):
    raise SystemExit(1)

allowed_prefixes = (
    "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/",
    "shared/src/commonMain/composeResources/",
    "iosApp/VitruvianPhoenix/VitruvianPhoenix/",
)
resource_extensions = {
    ".json", ".jpg", ".jpeg", ".gif", ".mp3", ".m4a", ".otf", ".properties", ".png",
    ".strings", ".stringsdict", ".svg", ".ttf", ".wav", ".webp", ".xml",
}

def clean_path(value, prefix=None):
    if value == "/dev/null":
        return None
    if "\x00" in value or "\\" in value or value.startswith('"') or value.endswith('"'):
        raise SystemExit(1)
    value = value.split("\t", 1)[0].strip()
    if prefix:
        if not value.startswith(prefix):
            raise SystemExit(1)
        value = value[len(prefix):]
    if not value or value.startswith("/") or any(part in ("", ".", "..") for part in value.split("/")):
        raise SystemExit(1)
    return value

paths = set()
for raw_line in raw.splitlines():
    try:
        line = raw_line.decode("utf-8")
    except UnicodeDecodeError:
        raise SystemExit(1)
    if line.startswith("diff --git "):
        pieces = line.split(" ")
        if len(pieces) != 4:
            raise SystemExit(1)
        left = clean_path(pieces[2], "a/")
        right = clean_path(pieces[3], "b/")
        if left:
            paths.add(left)
        if right:
            paths.add(right)
    elif line.startswith("--- "):
        value = clean_path(line[4:].strip(), "a/") if line[4:].strip() != "/dev/null" else None
        if value:
            paths.add(value)
    elif line.startswith("+++ "):
        value = clean_path(line[4:].strip(), "b/") if line[4:].strip() != "/dev/null" else None
        if value:
            paths.add(value)
    elif line.startswith(("rename from ", "rename to ", "copy from ", "copy to ")):
        value = clean_path(line.split(" ", 2)[2].strip())
        if value:
            paths.add(value)
if not paths:
    raise SystemExit(1)

kinds = set()
for path in paths:
    if not path.startswith(allowed_prefixes):
        raise SystemExit(1)
    components = [component.lower() for component in path.split("/")]
    basename = components[-1]
    if any(component in {"config", "configs", "configuration", "profile", "profiles", "ci", "harness", "gradle", "build", "deriveddata", "xcuserdata", "pods"} for component in components) or any(word in basename for word in ("config", "harness", "runner")):
        raise SystemExit(1)
    if basename in {"build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradle.properties", "info.plist"}:
        raise SystemExit(1)
    suffix = Path(path).suffix.lower()
    if path.startswith(allowed_prefixes[0]):
        if suffix != ".kt":
            raise SystemExit(1)
        kinds.add("kotlin")
    elif suffix == ".swift":
        kinds.add("swift")
    elif suffix not in resource_extensions:
        raise SystemExit(1)
    else:
        kinds.add("resource")

snapshot.write_bytes(raw)
os.chmod(snapshot, 0o600)
meta = {
    "sha256": hashlib.sha256(raw).hexdigest(),
    "size": len(raw),
    "paths": sorted(paths),
    "kinds": sorted(kinds),
    "requiresKotlinCompile": bool({"kotlin", "resource"} & kinds),
    "binary": b"GIT binary patch" in raw,
}
metadata.write_text(json.dumps(meta, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
os.chmod(metadata, 0o600)
PY
}

read_meta_paths() {
    python3 - "$1" <<'PY'
import json
import sys
for path in json.load(open(sys.argv[1], encoding="utf-8"))["paths"]:
    print(path)
PY
}

status_paths_and_validate() {
    local root="$1"
    local expected_json="$2"
    local status_file="$3"
    local actual_json="$4"
    if ! git -C "$root" status --porcelain=v1 --untracked-files=all --ignored=matching >"$status_file" 2>&1; then
        return 1
    fi
    chmod 600 "$status_file"
    python3 - "$root" "$expected_json" "$status_file" "$actual_json" <<'PY'
import json
import os
import stat
import sys
from pathlib import Path
root = Path(sys.argv[1])
expected = set(json.load(open(sys.argv[2], encoding="utf-8"))["paths"])
lines = Path(sys.argv[3]).read_text(encoding="utf-8").splitlines()
actual = set()
for line in lines:
    if not line.strip() or line.startswith("!!"):
        raise SystemExit(1)
    if len(line) < 3:
        raise SystemExit(1)
    code = line[:2]
    values = line[3:].split(" -> ", 1) if " -> " in line[3:] else [line[3:]]
    for path in values:
        path = path.strip()
        if not path or path.startswith("/") or "\\" in path or any(part in ("", ".", "..") for part in path.split("/")):
            raise SystemExit(1)
        if path not in expected:
            raise SystemExit(1)
        actual.add(path)
if actual != expected:
    raise SystemExit(1)
for path in actual:
    current = root
    parts = path.split("/")
    for index, component in enumerate(parts):
        current = current / component
        info = os.lstat(current)
        if stat.S_ISLNK(info.st_mode):
            raise SystemExit(1)
        if index < len(parts) - 1 and not stat.S_ISDIR(info.st_mode):
            raise SystemExit(1)
        if index == len(parts) - 1 and not (stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode)):
            raise SystemExit(1)
Path(sys.argv[4]).write_text(json.dumps(sorted(actual), separators=(",", ":")) + "\n", encoding="utf-8")
os.chmod(sys.argv[4], 0o600)
PY
}

check_tree_bounds() {
    local root="$1"
    local excluded="${2-}"
    python3 - "$root" "$excluded" "$MAX_PRIVATE_FILE_BYTES" "$MAX_PRIVATE_TOTAL_BYTES" "$MAX_PRIVATE_FILES" <<'PY'
import os
import stat
import sys
from pathlib import Path
root = Path(sys.argv[1]).resolve()
excluded = Path(sys.argv[2]).resolve() if sys.argv[2] else None
max_file = int(sys.argv[3])
max_total = int(sys.argv[4])
max_files = int(sys.argv[5])
total = 0
files = 0
for current, dirs, names in os.walk(root, topdown=True, followlinks=False):
    current_path = Path(current).resolve()
    if excluded and (current_path == excluded or excluded in current_path.parents):
        dirs[:] = []
        continue
    safe_dirs = []
    for name in dirs:
        path = Path(current) / name
        info = os.lstat(path)
        if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
            raise SystemExit(1)
        safe_dirs.append(name)
    dirs[:] = safe_dirs
    for name in names:
        path = Path(current) / name
        info = os.lstat(path)
        if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
            raise SystemExit(1)
        files += 1
        if files > max_files or info.st_size > max_file:
            raise SystemExit(1)
        total += info.st_size
        if total > max_total:
            raise SystemExit(1)
PY
}

bounded_command() {
    local log="$1"
    local timeout="$2"
    local cwd="$3"
    shift 3
    python3 - "$log" "$timeout" "$cwd" "$MAX_CHILD_OUTPUT_BYTES" -- "$@" <<'PY'
import os
import resource
import selectors
import signal
import subprocess
import sys
import time
from pathlib import Path
log = Path(sys.argv[1])
timeout = int(sys.argv[2])
cwd = sys.argv[3]
max_output = int(sys.argv[4])
separator = sys.argv.index("--")
command = sys.argv[separator + 1:]
if not command:
    raise SystemExit(125)

def limit_resources():
    for name, value in (("RLIMIT_CORE", 0), ("RLIMIT_FSIZE", max_output * 8), ("RLIMIT_NOFILE", 256)):
        limit = getattr(resource, name, None)
        if limit is None:
            continue
        try:
            resource.setrlimit(limit, (value, value))
        except (OSError, ValueError):
            pass

log.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
os.chmod(log.parent, 0o700)
written = 0
proc = subprocess.Popen(command, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True, preexec_fn=limit_resources)
pid_file = Path(str(log) + ".child-pid")
pid_file.write_text(str(proc.pid) + "\n", encoding="ascii")
os.chmod(pid_file, 0o600)
interrupted = {"code": None}
def forward_signal(signum, _frame):
    interrupted["code"] = 128 + signum
    try:
        os.killpg(proc.pid, signum)
    except OSError:
        pass
for signum in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
    signal.signal(signum, forward_signal)
selector = selectors.DefaultSelector()
assert proc.stdout is not None
selector.register(proc.stdout, selectors.EVENT_READ)
deadline = time.monotonic() + timeout
result = None
with log.open("wb") as stream:
    while selector.get_map():
        remaining = deadline - time.monotonic()
        if remaining <= 0 and result is None:
            result = 124
            try:
                os.killpg(proc.pid, signal.SIGTERM)
            except OSError:
                pass
        events = selector.select(min(0.2, max(0.0, remaining)))
        if not events:
            if interrupted["code"] is not None:
                result = interrupted["code"]
                break
            if proc.poll() is not None:
                break
            continue
        for key, _ in events:
            block = key.fileobj.read1(65536)
            if not block:
                selector.unregister(key.fileobj)
                continue
            if written + len(block) > max_output:
                result = 125
                try:
                    os.killpg(proc.pid, signal.SIGTERM)
                except OSError:
                    pass
                block = block[:max_output - written]
            if block:
                stream.write(block)
                written += len(block)
            if result is not None:
                break
        if result is not None:
            break
        if interrupted["code"] is not None:
            result = interrupted["code"]
            break
    if result is not None:
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except OSError:
                pass
            proc.wait()
        selector.close()
        raise SystemExit(result)
    result = proc.wait()
selector.close()
raise SystemExit(result)
PY
}

run_child() {
    local log="$1"
    local timeout="$2"
    local cwd="$3"
    shift 3
    set +e
    ACTIVE_CHILD_GROUP_FILE="$log.child-pid"
    bounded_command "$log" "$timeout" "$cwd" "$@" &
    ACTIVE_CHILD_PID=$!
    wait "$ACTIVE_CHILD_PID"
    local rc=$?
    ACTIVE_CHILD_PID=""
    ACTIVE_CHILD_GROUP_FILE=""
    set -e
    return "$rc"
}

bootstrap_java_runtime() {
    local configured_home="${JAVA_HOME-}"
    local java_executable=""
    local java_major=""

    java_version_major() {
        local executable="$1"
        local version_output=""
        local version_rc=0
        [[ -x "$executable" ]] || return 1
        set +e
        version_output="$("$executable" -version 2>&1)"
        version_rc=$?
        set -e
        [[ "$version_rc" -eq 0 ]] || return 1
        printf '%s\n' "$version_output" | python3 -c 'import re, sys
text = sys.stdin.read()
match = re.search(r"version\s+\"([^\"]+)\"", text)
if not match:
    raise SystemExit(1)
version = match.group(1)
parts = version.split(".")
major = parts[1] if parts[0] == "1" and len(parts) > 1 else parts[0]
if not re.fullmatch(r"[0-9]+", major):
    raise SystemExit(1)
print(major)'
    }

    if [[ -n "$configured_home" ]] && java_major="$(java_version_major "$configured_home/bin/java" 2>/dev/null)"; then
        export JAVA_HOME="$configured_home"
        return 0
    fi

    java_executable="$(command -v java 2>/dev/null || true)"
    if [[ -z "$java_executable" ]] || ! java_major="$(java_version_major "$java_executable" 2>/dev/null)"; then
        fail 'unable to locate a usable Java runtime; set JAVA_HOME to a JDK home or ensure java is on PATH'
    fi

    local discovered_home=""
    if discovered_home="$(python3 - "$java_executable" <<'PY'
import os
import sys
from pathlib import Path

candidate = Path(sys.argv[1])
try:
    resolved = candidate.resolve(strict=True)
    if not resolved.is_file() or not os.access(resolved, os.X_OK) or resolved.parent.name != "bin":
        raise OSError
    home = resolved.parent.parent
    home_java_resolved = (home / "bin" / "java").resolve(strict=True)
    if not home_java_resolved.is_file() or not os.access(home_java_resolved, os.X_OK):
        raise OSError
except (OSError, RuntimeError, ValueError):
    raise SystemExit(1)
print(home)
PY
)"; then
        export JAVA_HOME="$discovered_home"
    else
        fail 'unable to locate a usable Java runtime; set JAVA_HOME to a JDK home or ensure java is on PATH'
    fi
}

build_child_env() {
    [[ -n "${JAVA_HOME-}" ]] || fail 'unable to locate a usable Java runtime; set JAVA_HOME to a JDK home or ensure java is on PATH'
    CHILD_ENV=(env -i "PATH=$SYSTEM_PATH" "JAVA_HOME=$JAVA_HOME" "HOME=$PRIVATE_DIR/home" "TMPDIR=$PRIVATE_DIR/tmp" "GRADLE_USER_HOME=$PRIVATE_DIR/gradle-user-home" LANG=C LC_ALL=C "PHOENIX_HARNESS_UDID=$EXPECTED_UDID" "PHOENIX_SIMULATOR_FIXTURE=just-lift-connected")
    if [[ "${PHOENIX_HARNESS_ALLOW_DESTRUCTIVE-}" == "1" ]]; then CHILD_ENV+=(PHOENIX_HARNESS_ALLOW_DESTRUCTIVE=1); fi
    if [[ "${CI-}" == "true" ]]; then CHILD_ENV+=(CI=true); fi
    if [[ -n "${DEVELOPER_DIR-}" ]]; then CHILD_ENV+=("DEVELOPER_DIR=$DEVELOPER_DIR"); fi
}

run_harness() {
    local root="$1"
    local artifact="$2"
    local label="$3"
    if ! run_child "$PRIVATE_DIR/$label-run.log" "$CHILD_TIMEOUT_SECONDS" "$root" "${CHILD_ENV[@]}" "$RUNNER" case "$artifact" just-lift-connected; then
        return 1
    fi
    check_tree_bounds "$PRIVATE_DIR" "$WORKTREE"
    check_tree_bounds "$ARTIFACT_DIR"
}

run_verify() {
    local artifact="$1"
    local label="$2"
    run_child "$PRIVATE_DIR/$label-verify.log" "$VERIFY_TIMEOUT_SECONDS" "$REPO_ROOT" "${CHILD_ENV[@]}" "$RUNNER" verify "$artifact"
}

run_focused_checks() {
    local root="$1"
    local metadata="$2"
    local results="$3"
    local changed="$PRIVATE_DIR/changed-files.list"
    read_meta_paths "$metadata" > "$changed"
    chmod 600 "$changed"
    local names='["git.diff.check"]'
    if ! run_child "$PRIVATE_DIR/focused-command.log" "$VERIFY_TIMEOUT_SECONDS" "$root" "${CHILD_ENV[@]}" git -C "$root" diff --check "$BASE_SHA" --; then
        return 1
    fi
    python3 - "$results" "$names" <<'PY'
import json
import os
import sys
from pathlib import Path
path = Path(sys.argv[1])
items = json.loads(sys.argv[2])
path.write_text(json.dumps([{"name": item, "passed": True} for item in items], separators=(",", ":")) + "\n", encoding="utf-8")
os.chmod(path, 0o600)
PY
}

run_kotlin_compile() {
    local metadata="$1"
    local requires
    requires="$(python3 - "$metadata" <<'PY'
import json
import sys
print("1" if json.load(open(sys.argv[1], encoding="utf-8"))["requiresKotlinCompile"] else "0")
PY
)"
    [[ "$requires" == "1" ]] || return 0
    validate_regular_file "$WORKTREE/gradlew" 0 || return 1
    run_child "$PRIVATE_DIR/kotlin-compile.log" "$CHILD_TIMEOUT_SECONDS" "$WORKTREE" "${CHILD_ENV[@]}" bash "$WORKTREE/gradlew" :shared:compileKotlinIosSimulatorArm64 --no-daemon --console=plain
    check_tree_bounds "$PRIVATE_DIR" "$WORKTREE"
}

validate_identity_pair() {
    local before="$1"
    local after="$2"
    local destination="$3"
    python3 - "$before/run.json" "$after/run.json" "$destination" "$BASE_SHA" "$EXPECTED_FIXTURE_SHA256" "$EXPECTED_BUNDLE_ID" "$EXPECTED_UDID" <<'PY'
import hashlib
import json
import math
import os
import stat
import struct
import sys
import zlib
from pathlib import Path
before_path = Path(sys.argv[1])
after_path = Path(sys.argv[2])
destination = Path(sys.argv[3])
base = sys.argv[4]
fixture_sha = sys.argv[5]
bundle_id = sys.argv[6]
udid = sys.argv[7]
canonical_commands = [
    ("xcodebuild.version", "toolchain.log"),
    ("simulator.boot", "boot.log"),
    ("simulator.bootstatus", "bootstatus.log"),
    ("simulator.terminate", "terminate.log"),
    ("simulator.uninstall", "uninstall.log"),
    ("build", "build.log"),
    ("run-tests", "test.log"),
    ("simulator.app-state", "app-state.log"),
    ("simulator.logs", "simulator.log"),
    ("simulator.screenshot", "screenshot.log"),
]
canonical_markers = {"xctest.passed", "phantom.connected", "simulator.screenshot"}

def regular(path):
    info = os.lstat(path)
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600:
        raise SystemExit(1)

def png(path):
    regular(path)
    data = path.read_bytes()
    if len(data) < 33 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR" or int.from_bytes(data[8:12], "big") != 13:
        raise SystemExit(1)
    payload = data[16:29]
    if zlib.crc32(b"IHDR" + payload) & 0xffffffff != int.from_bytes(data[29:33], "big"):
        raise SystemExit(1)
    width, height = struct.unpack(">II", payload[:8])
    if width < 1 or height < 1 or width > 100000 or height > 100000:
        raise SystemExit(1)
    return {"width": width, "height": height, "sha256": hashlib.sha256(data).hexdigest()}

def load(run_path):
    regular(run_path)
    manifest = json.loads(run_path.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 1 or not isinstance(manifest.get("runId"), str) or not manifest["runId"].strip():
        raise SystemExit(1)
    provenance = manifest.get("provenance")
    if not isinstance(provenance, dict) or provenance.get("baseSha") != base:
        raise SystemExit(1)
    fixture = provenance.get("fixture")
    if fixture != {"id": "just-lift-connected", "sha256": fixture_sha}:
        raise SystemExit(1)
    if provenance.get("bundleId") != bundle_id:
        raise SystemExit(1)
    simulator = provenance.get("simulator")
    if not isinstance(simulator, dict) or simulator.get("udid") != udid or simulator.get("state") not in {"Booted", "Shutdown"}:
        raise SystemExit(1)
    commands = manifest.get("commands")
    if not isinstance(commands, list) or len(commands) != len(canonical_commands):
        raise SystemExit(1)
    for item, (name, output) in zip(commands, canonical_commands):
        if not isinstance(item, dict) or item.get("name") != name or item.get("output") != output or item.get("exitCode") != 0:
            raise SystemExit(1)
        if name == "run-tests" and item.get("resultBundle", {}).get("basename") != "test.xcresult":
            raise SystemExit(1)
    markers = manifest.get("semanticMarkers")
    if not isinstance(markers, dict) or set(markers.get("required", [])) != canonical_markers or set(markers.get("observed", [])) != canonical_markers:
        raise SystemExit(1)
    captures = manifest.get("captures")
    if not isinstance(captures, list):
        raise SystemExit(1)
    by_slug = {capture.get("slug"): capture for capture in captures if isinstance(capture, dict)}
    selected = {}
    for slug, filename in (("simulator-after", "after.png"), ("xctest-after", "xctest-attachment.png")):
        capture = by_slug.get(slug)
        if not isinstance(capture, dict) or capture.get("path") != filename or capture.get("phase") != "after" or capture.get("checkpoint") != "phantom-connected":
            raise SystemExit(1)
        if capture.get("fixtureId") != "just-lift-connected" or capture.get("fixtureSha256") != fixture_sha or capture.get("simulator") != simulator:
            raise SystemExit(1)
        details = png(run_path.parent / filename)
        if capture.get("sha256", "").lower() != details["sha256"]:
            raise SystemExit(1)
        selected[slug] = {"path": filename, "sha256": details["sha256"], "dimensions": {"width": details["width"], "height": details["height"]}}
    identity = {"baseSha": base, "fixtureId": "just-lift-connected", "fixtureSha256": fixture_sha, "bundleId": bundle_id, "simulator": simulator, "commands": [name for name, _ in canonical_commands], "markers": sorted(canonical_markers)}
    return {"identity": identity, "simulatorCapture": selected["simulator-after"], "xctestCapture": selected["xctest-after"], "manifestSha256": hashlib.sha256(run_path.read_bytes()).hexdigest()}

before = load(before_path)
after = load(after_path)
if before["identity"] != after["identity"]:
    raise SystemExit(1)
result = {"identity": before["identity"], "beforeManifestSha256": before["manifestSha256"], "afterManifestSha256": after["manifestSha256"], "beforeCapture": before["simulatorCapture"], "afterCapture": after["simulatorCapture"]}
destination.write_text(json.dumps(result, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
os.chmod(destination, 0o600)
PY
}

validate_comparison() {
    local before="$1"
    local after="$2"
    local comparison="$3"
    local identities="$4"
    python3 - "$before/run.json" "$after/run.json" "$comparison/diff.json" "$comparison/diff.png" "$identities" "$PRIVATE_DIR/comparison-meta.json" <<'PY'
import hashlib
import json
import math
import os
import stat
import struct
import sys
import zlib
from pathlib import Path
before_manifest = Path(sys.argv[1])
after_manifest = Path(sys.argv[2])
diff_json = Path(sys.argv[3])
diff_png = Path(sys.argv[4])
identities = json.loads(Path(sys.argv[5]).read_text(encoding="utf-8"))
destination = Path(sys.argv[6])

def regular(path):
    info = os.lstat(path)
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600:
        raise SystemExit(1)

def png(path):
    regular(path)
    data = path.read_bytes()
    if len(data) < 33 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR" or int.from_bytes(data[8:12], "big") != 13:
        raise SystemExit(1)
    payload = data[16:29]
    if zlib.crc32(b"IHDR" + payload) & 0xffffffff != int.from_bytes(data[29:33], "big"):
        raise SystemExit(1)
    width, height = struct.unpack(">II", payload[:8])
    if width < 1 or height < 1 or width > 100000 or height > 100000:
        raise SystemExit(1)
    return {"width": width, "height": height, "sha256": hashlib.sha256(data).hexdigest()}

regular(diff_json)
regular(diff_png)
try:
    diff = json.loads(diff_json.read_text(encoding="utf-8"))
except Exception:
    raise SystemExit(1)
if not isinstance(diff, dict) or not isinstance(diff.get("dimensions"), dict):
    raise SystemExit(1)
dimensions = diff["dimensions"]
if dimensions.get("width") != identities["beforeCapture"]["dimensions"]["width"] or dimensions.get("height") != identities["beforeCapture"]["dimensions"]["height"] or dimensions != identities["afterCapture"]["dimensions"]:
    raise SystemExit(1)
png_details = png(diff_png)
if {"width": png_details["width"], "height": png_details["height"]} != dimensions:
    raise SystemExit(1)
if not isinstance(diff.get("passed"), bool) or not isinstance(diff.get("thresholdPassed"), bool) or diff["passed"] != diff["thresholdPassed"]:
    raise SystemExit(1)
for key in ("changedPixelRatio", "meanChannelDelta", "threshold"):
    if key in diff and (not isinstance(diff[key], (int, float)) or isinstance(diff[key], bool) or not math.isfinite(float(diff[key]))):
        raise SystemExit(1)
if "changedPixelRatio" in diff and not 0 <= float(diff["changedPixelRatio"]) <= 1:
    raise SystemExit(1)
if "changedPixels" in diff and (not isinstance(diff["changedPixels"], int) or isinstance(diff["changedPixels"], bool) or diff["changedPixels"] < 0):
    raise SystemExit(1)
result = {
    "before": identities["beforeCapture"],
    "after": identities["afterCapture"],
    "diffJson": {"path": "comparison/diff.json", "sha256": hashlib.sha256(diff_json.read_bytes()).hexdigest()},
    "diffImage": {"path": "comparison/diff.png", "sha256": png_details["sha256"], "dimensions": dimensions},
    "summary": diff,
}
destination.write_text(json.dumps(result, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
os.chmod(destination, 0o600)
PY
}

write_success_outputs() {
    local metadata="$1"
    local checks="$2"
    local identities="$3"
    local actual_files="$4"
    local status_file="$5"
    local head="$6"
    local applied_diff_sha="$7"
    local comparison="$8"
    python3 - "$ARTIFACT_DIR" "$metadata" "$checks" "$identities" "$actual_files" "$status_file" "$BASE_SHA" "$head" "$applied_diff_sha" "$comparison" <<'PY'
import hashlib
import json
import os
import stat
import tempfile
import sys
from pathlib import Path
root = Path(sys.argv[1])
metadata = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
checks = json.loads(Path(sys.argv[3]).read_text(encoding="utf-8"))
identities = json.loads(Path(sys.argv[4]).read_text(encoding="utf-8"))
actual_files = json.loads(Path(sys.argv[5]).read_text(encoding="utf-8"))
status_lines = [line for line in Path(sys.argv[6]).read_text(encoding="utf-8").splitlines() if line.strip()]
base = sys.argv[7]
head = sys.argv[8]
applied_diff_sha = sys.argv[9]
comparison = json.loads(Path(sys.argv[10]).read_text(encoding="utf-8"))

def atomic(path, data):
    path = Path(path)
    try:
        info = os.lstat(path)
    except FileNotFoundError:
        info = None
    if info is not None and (stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode)):
        raise SystemExit(1)
    fd, temporary = tempfile.mkstemp(prefix=".tmp-proposal-", dir=str(path.parent))
    os.close(fd)
    os.chmod(temporary, 0o600)
    try:
        Path(temporary).write_text(data, encoding="utf-8")
        os.replace(temporary, path)
        os.chmod(path, 0o600)
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass

proposal = {
    "schemaVersion": 1,
    "status": "passed",
    "trustedInput": True,
    "fixture": "just-lift-connected",
    "baseSha": base,
    "patch": {"path": "proposal.patch", "sha256": metadata["sha256"], "size": metadata["size"], "binary": metadata["binary"], "format": "exact-input"},
    "candidateKinds": metadata["kinds"],
    "allowedChangedFiles": metadata["paths"],
    "actualChangedFiles": actual_files,
    "worktree": {"baseSha": base, "headSha": head, "detached": True, "uncommitted": bool(status_lines), "statusEntryCount": len(status_lines), "appliedDiffSha256": applied_diff_sha},
    "focusedChecks": checks,
    "before": {"artifact": "before", "manifestSha256": identities["beforeManifestSha256"], "identity": identities["identity"]},
    "after": {"artifact": "after", "manifestSha256": identities["afterManifestSha256"], "identity": identities["identity"]},
    "comparison": comparison,
    "evidence": {"proposalMarkdown": "proposal.md", "summaryJson": "evidence-summary.json"},
}
atomic(root / "proposal-manifest.json", json.dumps(proposal, indent=2, sort_keys=True) + "\n")
summary = {"schemaVersion": 1, "status": "passed", "trustedInput": True, "fixture": proposal["fixture"], "baseSha": base, "patchSha256": metadata["sha256"], "changedFiles": actual_files, "beforeAfterIdentity": identities["identity"], "comparison": comparison, "artifacts": ["before", "after", "proposal.patch", "proposal-manifest.json", "proposal.md", "comparison/diff.json", "comparison/diff.png"]}
atomic(root / "evidence-summary.json", json.dumps(summary, indent=2, sort_keys=True) + "\n")
lines = [
    "# Phantom proposal evidence", "", "Status: **passed**", "",
    "This proposal was rendered from the real Phoenix app in a disposable detached worktree using trusted candidate input.", "",
    f"- Fixture: `{proposal['fixture']}`", f"- Verified base SHA: `{base}`", f"- Proposal patch SHA-256: `{metadata['sha256']}`",
    "", "## Allowed changed files", "",
]
lines.extend(f"- `{path}`" for path in actual_files)
lines.extend(["", "## Verification", "", "- Baseline canonical harness case: verified", "- Candidate canonical harness case: verified", "- Kotlin/resource compile gate when required: verified", "- Bound comparison metadata: verified", "- Temporary worktree: cleaned after rendering", ""])
atomic(root / "proposal.md", "\n".join(lines))
PY
}

write_failure_manifest() {
    [[ "$ROOT_READY" -eq 1 ]] || return 0
    python3 - "$ARTIFACT_DIR" "$SENTINEL_NAME" "$BASE_SHA" "$PATCH_SHA256" "$STAGE" "$FAILURE_REASON" <<'PY' || true
import json
import os
import shutil
import stat
import sys
import tempfile
from pathlib import Path
root = Path(sys.argv[1])
sentinel_name, base, patch_sha, stage, reason = sys.argv[2:]
try:
    info = os.lstat(root)
except OSError:
    raise SystemExit(0)
if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
    raise SystemExit(0)
for child in list(root.iterdir()):
    if child.name == sentinel_name:
        continue
    try:
        child_info = os.lstat(child)
    except OSError:
        continue
    if child_info.st_uid != os.getuid() or stat.S_ISLNK(child_info.st_mode):
        continue
    if stat.S_ISDIR(child_info.st_mode):
        shutil.rmtree(child)
    elif stat.S_ISREG(child_info.st_mode):
        child.unlink()
manifest = {"schemaVersion": 1, "status": "failed", "failure": {"stage": stage, "reason": reason}}
if base: manifest["baseSha"] = base
if patch_sha: manifest["patchSha256"] = patch_sha
fd, temporary = tempfile.mkstemp(prefix=".tmp-failure-", dir=str(root))
os.close(fd)
os.chmod(temporary, 0o600)
try:
    Path(temporary).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temporary, root / "proposal-manifest.json")
    os.chmod(root / "proposal-manifest.json", 0o600)
finally:
    try:
        os.unlink(temporary)
    except FileNotFoundError:
        pass
PY
}

cleanup_worktree() {
    if [[ "$WORKTREE_REGISTERED" -ne 1 || -z "$WORKTREE" ]]; then return 0; fi
    git -C "$REPO_ROOT" worktree remove --force "$WORKTREE" >/dev/null 2>&1 || true
    git -C "$REPO_ROOT" worktree prune >/dev/null 2>&1 || true
    python3 - "$WORKTREE" "$PRIVATE_DIR" <<'PY' || true
import os
import shutil
import stat
import sys
from pathlib import Path
path = Path(sys.argv[1])
private = Path(sys.argv[2]).resolve()
try:
    path.resolve().relative_to(private)
except ValueError:
    raise SystemExit(0)
try:
    info = os.lstat(path)
except OSError:
    raise SystemExit(0)
if info.st_uid != os.getuid() or stat.S_ISLNK(info.st_mode):
    raise SystemExit(0)
if stat.S_ISDIR(info.st_mode):
    shutil.rmtree(path)
elif stat.S_ISREG(info.st_mode):
    path.unlink()
PY
    WORKTREE_REGISTERED=0
}

cleanup_private() {
    [[ -n "$PRIVATE_DIR" ]] || return 0
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
if info.st_uid != os.getuid() or stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
    raise SystemExit(0)
shutil.rmtree(path)
PY
    PRIVATE_DIR=""
}

kill_active_child() {
    if [[ -n "$ACTIVE_CHILD_GROUP_FILE" && -f "$ACTIVE_CHILD_GROUP_FILE" ]]; then
        local child_group=""
        if IFS= read -r child_group < "$ACTIVE_CHILD_GROUP_FILE" && [[ "$child_group" =~ ^[0-9]+$ ]] && [[ "$child_group" -gt 0 ]]; then
            kill -TERM "-$child_group" >/dev/null 2>&1 || true
            sleep 0.1
            kill -KILL "-$child_group" >/dev/null 2>&1 || true
        fi
    fi
    if [[ -n "$ACTIVE_CHILD_PID" ]]; then
        kill -TERM "$ACTIVE_CHILD_PID" >/dev/null 2>&1 || true
        sleep 0.1
        kill -KILL "$ACTIVE_CHILD_PID" >/dev/null 2>&1 || true
    fi
}

on_exit() {
    local rc=$?
    trap - EXIT HUP INT TERM
    if [[ -n "$ACTIVE_CHILD_PID" ]]; then
        kill_active_child
        wait "$ACTIVE_CHILD_PID" >/dev/null 2>&1 || true
        ACTIVE_CHILD_PID=""
        ACTIVE_CHILD_GROUP_FILE=""
    fi
    if [[ "$rc" -ne 0 ]]; then
        write_failure_manifest
    fi
    cleanup_worktree
    cleanup_private
    if [[ "$rc" -ne 0 ]]; then
        printf '%s\n' "phantom-proposal: $FAILURE_REASON" >&2
    fi
    exit "$rc"
}

on_signal() {
    local signal="$1"
    local code="$2"
    if [[ -n "$ACTIVE_CHILD_PID" ]]; then
        kill_active_child
    fi
    STAGE="signal"
    FAILURE_REASON="proposal interrupted by $signal; evidence was not produced"
    exit "$code"
}

trap on_exit EXIT
trap 'on_signal HUP 129' HUP
trap 'on_signal INT 130' INT
trap 'on_signal TERM 143' TERM

render() {
    local requested_artifact="$1"
    local fixture="$2"
    local requested_patch="$3"
    [[ "$fixture" == "just-lift-connected" ]] || fail 'fixture is not allowlisted'
    [[ "${PHOENIX_PROPOSAL_TRUSTED_INPUT-}" == "1" ]] || fail 'trusted proposal rendering requires PHOENIX_PROPOSAL_TRUSTED_INPUT=1; review and explicitly approve the candidate input'
    EXPECTED_UDID="${PHOENIX_HARNESS_UDID-}"
    [[ "$EXPECTED_UDID" =~ ^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$ ]] || fail 'proposal rendering requires a valid PHOENIX_HARNESS_UDID'
    [[ "${PHOENIX_HARNESS_ALLOW_DESTRUCTIVE-}" == "1" || "${CI-}" == "true" ]] || fail 'proposal rendering requires PHOENIX_HARNESS_ALLOW_DESTRUCTIVE=1 or CI=true'
    if [[ -n "${PHOENIX_PROPOSAL_TIMEOUT_SECONDS-}" ]]; then
        [[ "$PHOENIX_PROPOSAL_TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] || fail 'PHOENIX_PROPOSAL_TIMEOUT_SECONDS must be a positive integer'
        (( PHOENIX_PROPOSAL_TIMEOUT_SECONDS > 0 && PHOENIX_PROPOSAL_TIMEOUT_SECONDS <= 1800 )) || fail 'PHOENIX_PROPOSAL_TIMEOUT_SECONDS must be between 1 and 1800 seconds'
        CHILD_TIMEOUT_SECONDS="$PHOENIX_PROPOSAL_TIMEOUT_SECONDS"
    fi

    STAGE="validate paths"
    if ! ARTIFACT_DIR="$(normalize_path "$requested_artifact" 1)"; then fail 'artifact path is unsafe'; fi
    if ! python3 - "$REPO_ROOT" "$ARTIFACT_DIR" <<'PY'
import os
import sys
from pathlib import Path
repo = Path(sys.argv[1]).resolve()
artifact = Path(sys.argv[2]).resolve()
try:
    artifact.relative_to(repo)
except ValueError:
    raise SystemExit(0)
raise SystemExit(1)
PY
    then
        fail 'artifact root must be outside the original repository'
    fi
    if ! prepare_root "$ARTIFACT_DIR"; then fail 'artifact root must be a caller-owned empty directory'; fi
    ROOT_READY=1
    if ! PATCH_FILE="$(normalize_file_path "$requested_patch")"; then fail 'patch path is unsafe'; fi
    validate_regular_file "$RUNNER" 0 || fail 'committed harness runner is unavailable'
    validate_regular_file "$VERIFY_SCRIPT" 0 || fail 'committed harness verifier is unavailable'
    validate_regular_file "$FIXTURE_SOURCE" 0 || fail 'canonical fixture source is unavailable'
    validate_regular_file "$PATCH_FILE" 1 || fail 'patch must be a caller-owned private regular file'

    PRIVATE_DIR="$(new_private_dir)"
    create_empty_dir "$PRIVATE_DIR/tmp" || fail 'proposal private TMPDIR could not be created'
    create_empty_dir "$PRIVATE_DIR/home" || fail 'proposal private HOME could not be created'
    create_empty_dir "$PRIVATE_DIR/gradle-user-home" || fail 'proposal private Gradle storage could not be created'
    STAGE="verify committed base"
    record_original_state
    if ! FIXTURE_SHA256="$(sha256_file "$FIXTURE_SOURCE")"; then fail 'canonical fixture hash could not be calculated'; fi
    [[ "$FIXTURE_SHA256" == "$EXPECTED_FIXTURE_SHA256" ]] || fail 'canonical fixture source hash is not allowlisted'
    STAGE="resolve Java runtime"
    bootstrap_java_runtime
    build_child_env

    STAGE="validate patch"
    local patch_snapshot="$PRIVATE_DIR/proposal.patch"
    local patch_metadata="$PRIVATE_DIR/patch-meta.json"
    if ! create_patch_metadata "$PATCH_FILE" "$patch_snapshot" "$patch_metadata"; then fail 'patch is invalid, contains protected content, or modifies a non-render path'; fi
    PATCH_SHA256="$(python3 - "$patch_metadata" <<'PY'
import json
import sys
print(json.load(open(sys.argv[1], encoding="utf-8"))["sha256"])
PY
)"
    create_empty_file "$ARTIFACT_DIR/proposal.patch" || fail 'proposal patch destination is unsafe'
    safe_copy "$patch_snapshot" "$ARTIFACT_DIR/proposal.patch" || fail 'proposal patch could not be recorded'

    STAGE="capture baseline"
    create_empty_dir "$ARTIFACT_DIR/before" || fail 'baseline artifact destination is unsafe'
    if ! run_harness "$REPO_ROOT" "$ARTIFACT_DIR/before" baseline; then fail 'baseline harness case failed or exceeded its bounds'; fi
    if ! run_verify "$ARTIFACT_DIR/before" baseline; then fail 'baseline canonical harness verification failed'; fi
    assert_original_unchanged "after-baseline"

    STAGE="create disposable worktree"
    WORKTREE="$PRIVATE_DIR/worktree"
    normalize_path "$PRIVATE_DIR" 0 >/dev/null || fail 'proposal private storage path is unsafe'
    WORKTREE_REGISTERED=1
    if ! git -C "$REPO_ROOT" worktree add --detach "$WORKTREE" "$BASE_SHA" >"$PRIVATE_DIR/worktree-create.log" 2>&1; then
        fail 'disposable worktree could not be created; partial metadata was cleaned'
    fi
    chmod 600 "$PRIVATE_DIR/worktree-create.log"
    local worktree_head
    if ! worktree_head="$(git -C "$WORKTREE" rev-parse --verify HEAD 2>"$PRIVATE_DIR/worktree-head.log")"; then fail 'disposable worktree head could not be verified'; fi
    [[ "$worktree_head" == "$BASE_SHA" ]] || fail 'disposable worktree base does not match verified SHA'
    if ! git -C "$WORKTREE" status --porcelain=v1 --untracked-files=all --ignored=matching >"$PRIVATE_DIR/pre-apply-status" 2>&1; then fail 'disposable worktree status could not be verified'; fi
    chmod 600 "$PRIVATE_DIR/pre-apply-status"
    [[ ! -s "$PRIVATE_DIR/pre-apply-status" ]] || fail 'disposable worktree was not clean'

    STAGE="apply disposable patch"
    if ! git -C "$WORKTREE" apply --check --binary --whitespace=nowarn "$patch_snapshot" >"$PRIVATE_DIR/apply-check.log" 2>&1; then fail 'patch does not apply cleanly'; fi
    chmod 600 "$PRIVATE_DIR/apply-check.log"
    if ! git -C "$WORKTREE" apply --binary --whitespace=nowarn "$patch_snapshot" >"$PRIVATE_DIR/apply.log" 2>&1; then fail 'patch application failed'; fi
    chmod 600 "$PRIVATE_DIR/apply.log"
    local status_after_patch="$PRIVATE_DIR/status-after-patch"
    local actual_files="$PRIVATE_DIR/actual-files.json"
    status_paths_and_validate "$WORKTREE" "$patch_metadata" "$status_after_patch" "$actual_files" || fail 'patch created an unsafe or unexpected worktree change'
    local applied_diff="$PRIVATE_DIR/applied.patch"
    if ! git -C "$WORKTREE" diff --binary --full-index "$BASE_SHA" -- >"$applied_diff" 2>&1; then fail 'applied worktree diff could not be recorded'; fi
    chmod 600 "$applied_diff"
    local applied_diff_sha
    applied_diff_sha="$(sha256_file "$applied_diff")"

    STAGE="run focused checks"
    local focused_checks="$PRIVATE_DIR/focused-checks.json"
    run_focused_checks "$WORKTREE" "$patch_metadata" "$focused_checks" || fail 'focused patch checks failed'
    run_kotlin_compile "$patch_metadata" || fail 'candidate Kotlin/resource compilation failed or exceeded its bounds'
    if [[ "$(python3 - "$patch_metadata" <<'PY'
import json
import sys
print("1" if json.load(open(sys.argv[1], encoding="utf-8"))["requiresKotlinCompile"] else "0")
PY
)" == "1" ]]; then
        python3 - "$focused_checks" <<'PY'
import json
import os
import sys
from pathlib import Path
path = Path(sys.argv[1])
items = json.loads(path.read_text(encoding="utf-8"))
items.append({"name": "shared.compileKotlinIosSimulatorArm64", "passed": True})
path.write_text(json.dumps(items, separators=(",", ":")) + "\n", encoding="utf-8")
os.chmod(path, 0o600)
PY
    fi
    status_paths_and_validate "$WORKTREE" "$patch_metadata" "$status_after_patch" "$actual_files" || fail 'candidate compile created an unsafe worktree artifact'

    STAGE="capture candidate"
    create_empty_dir "$ARTIFACT_DIR/after" || fail 'candidate artifact destination is unsafe'
    local candidate_runner="$WORKTREE/.github/scripts/phantom-harness.sh"
    validate_regular_file "$candidate_runner" 0 || fail 'candidate harness runner is unavailable'
    if ! run_harness "$WORKTREE" "$ARTIFACT_DIR/after" candidate; then fail 'candidate harness case failed or exceeded its bounds'; fi
    if ! run_verify "$ARTIFACT_DIR/after" candidate; then fail 'candidate canonical harness verification failed'; fi
    status_paths_and_validate "$WORKTREE" "$patch_metadata" "$status_after_patch" "$actual_files" || fail 'candidate run created an unsafe or unexpected worktree artifact'
    assert_original_unchanged "after-candidate"

    STAGE="compare screenshots"
    create_empty_dir "$ARTIFACT_DIR/comparison" || fail 'comparison destination is unsafe'
    if ! run_child "$PRIVATE_DIR/compare.log" "$CHILD_TIMEOUT_SECONDS" "$REPO_ROOT" "${CHILD_ENV[@]}" "$RUNNER" compare "$ARTIFACT_DIR/before" "$ARTIFACT_DIR/after" "$ARTIFACT_DIR/comparison"; then fail 'committed screenshot comparison failed or exceeded its bounds'; fi
    check_tree_bounds "$ARTIFACT_DIR"
    local identities="$PRIVATE_DIR/identities.json"
    validate_identity_pair "$ARTIFACT_DIR/before" "$ARTIFACT_DIR/after" "$identities" || fail 'before and after evidence identities do not match the canonical contract'
    validate_comparison "$ARTIFACT_DIR/before" "$ARTIFACT_DIR/after" "$ARTIFACT_DIR/comparison" "$identities" || fail 'screenshot comparison outputs are invalid or not bound to captures'
    assert_original_unchanged "before-final-output"

    STAGE="write proposal evidence"
    local worktree_status="$PRIVATE_DIR/final-worktree-status"
    status_paths_and_validate "$WORKTREE" "$patch_metadata" "$status_after_patch" "$actual_files" || fail 'final candidate worktree changed unexpectedly'
    printf '%s\n' "$(git -C "$WORKTREE" status --porcelain=v1 --untracked-files=all)" >"$worktree_status"
    chmod 600 "$worktree_status"
    write_success_outputs "$patch_metadata" "$focused_checks" "$identities" "$actual_files" "$worktree_status" "$worktree_head" "$applied_diff_sha" "$PRIVATE_DIR/comparison-meta.json" || fail 'proposal evidence could not be written'
    assert_original_unchanged "after-final-output"
    STAGE="complete"
    printf '%s\n' "phantom-proposal: rendered evidence at $ARTIFACT_DIR"
}

create_empty_file() {
    local path="$1"
    python3 - "$path" <<'PY'
import os
import stat
import sys
from pathlib import Path
path = Path(sys.argv[1])
try:
    info = os.lstat(path)
except FileNotFoundError:
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    os.close(fd)
    raise SystemExit(0)
except OSError:
    raise SystemExit(1)
if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
    raise SystemExit(1)
os.chmod(path, 0o600)
PY
}

main() {
    reject_credentials "$@"
    [[ "$#" -eq 4 && "$1" == "render" ]] || usage
    render "$2" "$3" "$4"
}

main "$@"
