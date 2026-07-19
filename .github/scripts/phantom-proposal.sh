#!/usr/bin/env bash
set -euo pipefail

# Render a disposable, evidence-backed Phantom proposal.  This script never
# applies a candidate patch to the checkout from which it was invoked: the
# candidate is applied to a detached temporary worktree and all evidence is
# retained under the caller-owned artifact root.

IFS=$'\n\t'
SCRIPT_DIR="${BASH_SOURCE[0]%/*}"
if [[ "$SCRIPT_DIR" == "${BASH_SOURCE[0]}" ]]; then SCRIPT_DIR="."; fi
if [[ "$SCRIPT_DIR" != /* ]]; then SCRIPT_DIR="$PWD/$SCRIPT_DIR"; fi
SCRIPT_DIR="$(cd "$SCRIPT_DIR" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RUNNER="$REPO_ROOT/.github/scripts/phantom-harness.sh"
SENTINEL_NAME=".phantom-proposal"
SENTINEL_CONTENT=$'phantom-proposal-artifact-v1\n'

ARTIFACT_DIR=""
PATCH_FILE=""
BASE_SHA=""
PATCH_SHA256=""
PRIVATE_DIR=""
WORKTREE=""
STAGE="startup"
FAILURE_REASON="proposal did not complete"
WORKTREE_CREATED=0
ROOT_READY=0

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
    re.compile(r"\b(?:api[_-]?key|access[_-]?key|secret|password|passwd|auth[_-]?token)\s*[:=]\s*['\"]?[A-Za-z0-9._~+/=-]{16,}", re.IGNORECASE),
)
allowed_env = {
    "CI", "DEVELOPER_DIR", "GITHUB_ACTIONS", "GITHUB_SHA", "HOME", "LANG",
    "LC_ALL", "LC_CTYPE", "LOGNAME", "PATH", "PHOENIX_HARNESS_ALLOW_DESTRUCTIVE",
    "PHOENIX_HARNESS_UDID", "PWD", "SHELL", "SHLVL", "TMPDIR", "USER",
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
        fail 'credential-like argument or environment value refused'
    fi
}

# Normalize only paths whose existing components are caller-owned and not
# symlinks.  /tmp and /var are the macOS trusted aliases used by tempfile.
normalize_path() {
    local raw="$1"
    local allow_missing="$2"
    if ! python3 - "$raw" "$allow_missing" <<'PY'
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
missing = False
parts = path.parts[1:]
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
    if not trusted_alias and not stat.S_ISDIR(info.st_mode):
        raise SystemExit(1)
    if index == len(parts) - 1 and info.st_uid != uid:
        raise SystemExit(1)
if missing:
    parent = current if current.exists() else current.parent
    try:
        info = os.lstat(parent)
    except OSError:
        raise SystemExit(1)
    trusted_parent_alias = stat.S_ISLNK(info.st_mode) and str(parent) in {"/var", "/tmp"} and os.path.realpath(parent) in {"/private/var", "/private/tmp"}
    if stat.S_ISLNK(info.st_mode) and not trusted_parent_alias:
        raise SystemExit(1)
    if not trusted_parent_alias and not stat.S_ISDIR(info.st_mode):
        raise SystemExit(1)
    owner = os.stat(parent) if trusted_parent_alias else info
    if owner.st_uid != uid and not (stat.S_IMODE(owner.st_mode) & 0o1000 and stat.S_IMODE(owner.st_mode) & 0o2):
        raise SystemExit(1)
print(str(path))
PY
    then
        return 1
    fi
}

normalize_file_path() {
    local raw="$1"
    if ! python3 - "$raw" <<'PY'
import os
import stat
import sys
from pathlib import Path
raw = sys.argv[1]
if not raw or "\x00" in raw or "\n" in raw or "\r" in raw or "\\" in raw or any(part in (".", "..") for part in raw.split("/") ):
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
    if index < len(parts) - 1 and (not trusted_alias and not stat.S_ISDIR(info.st_mode)):
        raise SystemExit(1)
    if index == len(parts) - 1 and (not stat.S_ISREG(info.st_mode) or info.st_uid != uid):
        raise SystemExit(1)
print(str(path))
PY
    then
        return 1
    fi
}

validate_regular_file() {
    local path="$1"
    local private_mode="$2"
    if ! python3 - "$path" "$private_mode" <<'PY'
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
    then
        return 1
    fi
}

prepare_root() {
    local root="$1"
    if ! python3 - "$root" "$SENTINEL_NAME" "$SENTINEL_CONTENT" <<'PY'
import os
import stat
import sys
from pathlib import Path
root = Path(sys.argv[1])
sentinel = root / sys.argv[2]
content = sys.argv[3].encode("utf-8")
uid = os.getuid()
try:
    info = os.lstat(root)
except FileNotFoundError:
    root.mkdir(mode=0o700, parents=False)
    info = os.lstat(root)
except OSError:
    raise SystemExit(1)
if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode) or info.st_uid != uid:
    raise SystemExit(1)
if list(root.iterdir()):
    raise SystemExit(1)
os.chmod(root, 0o700)
fd = os.open(str(sentinel), os.O_CREAT | os.O_EXCL | os.O_WRONLY | getattr(os, "O_NOFOLLOW", 0), 0o600)
try:
    os.write(fd, content)
finally:
    os.close(fd)
os.chmod(sentinel, 0o600)
PY
    then
        return 1
    fi
}

create_empty_dir() {
    local path="$1"
    if ! python3 - "$path" <<'PY'
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
if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid():
    raise SystemExit(1)
if list(path.iterdir()):
    raise SystemExit(1)
os.chmod(path, 0o700)
PY
    then
        return 1
    fi
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
    if ! python3 - "$destination" "$text" <<'PY'
import os
import stat
import sys
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
fd, temporary = __import__("tempfile").mkstemp(prefix=".tmp-write-", dir=str(path.parent))
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
    then
        return 1
    fi
}

safe_copy() {
    local source="$1"
    local destination="$2"
    if ! python3 - "$source" "$destination" <<'PY'
import os
import stat
import sys
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
fd, temporary = __import__("tempfile").mkstemp(prefix=".tmp-copy-", dir=str(destination.parent))
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
    then
        return 1
    fi
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

quiet_command() {
    local command_log="$PRIVATE_DIR/command.log"
    : > "$command_log"
    chmod 600 "$command_log"
    set +e
    "$@" >"$command_log" 2>&1
    local rc=$?
    set -e
    return "$rc"
}

quiet_shell_command() {
    local command_log="$PRIVATE_DIR/command.log"
    : > "$command_log"
    chmod 600 "$command_log"
    set +e
    bash -c "$1" >"$command_log" 2>&1
    local rc=$?
    set -e
    return "$rc"
}

create_patch_metadata() {
    local input="$1"
    local snapshot="$2"
    local metadata="$3"
    if ! python3 - "$input" "$snapshot" "$metadata" <<'PY'
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
info = os.lstat(source)
if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077:
    raise SystemExit(1)
raw = source.read_bytes()
if not raw or len(raw) > 128 * 1024 * 1024:
    raise SystemExit(1)
secret_patterns = (
    re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    re.compile(rb"\b(?:gh[pousr]|github_pat|glpat|xox[baprs]|sk|rk)[_-][A-Za-z0-9_./=-]{20,}\b", re.I),
    re.compile(rb"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(rb"\bBearer\s+[A-Za-z0-9._~+/=-]{20,}", re.I),
    re.compile(rb"\b(?:api[_-]?key|access[_-]?key|secret|password|passwd|auth[_-]?token)\s*[:=]\s*['\"]?[A-Za-z0-9._~+/=-]{16,}", re.I),
)
if any(pattern.search(raw) for pattern in secret_patterns):
    raise SystemExit(1)


def clean_path(value, prefix=None):
    if value == "/dev/null":
        return None
    if value.startswith('"') or value.endswith('"') or "\\" in value or "\x00" in value:
        raise SystemExit(1)
    value = value.split("\t", 1)[0].strip()
    if prefix and value.startswith(prefix):
        value = value[len(prefix):]
    elif prefix:
        raise SystemExit(1)
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
        value = clean_path(line[4:].split("\t", 1)[0].strip(), "a/")
        if value:
            paths.add(value)
    elif line.startswith("+++ "):
        value = clean_path(line[4:].split("\t", 1)[0].strip(), "b/")
        if value:
            paths.add(value)
    elif line.startswith("rename from ") or line.startswith("rename to ") or line.startswith("copy from ") or line.startswith("copy to "):
        value = clean_path(line.split(" ", 2)[2].strip())
        if value:
            paths.add(value)

if not paths:
    raise SystemExit(1)

# Candidate proposals may touch app source, UI resources, Xcode project files,
# shared source, documentation, and CI/harness code.  Configuration, signing,
# profile, secret, and generated paths are never proposal inputs.
allowed_prefixes = ("iosApp/", "shared/", "androidApp/", "docs/", ".github/")
allowed_files = {
    "README.md", "iOS_INSTALL.md", "ANDROID_INSTALL.md", "AGENTS.md",
    "CLAUDE.md", "build.gradle.kts", "settings.gradle.kts",
}
protected = {
    ".github/scripts/phantom-proposal.sh",
    ".github/scripts/test_phantom_proposal.sh",
    ".github/scripts/phantom-harness-verify.py",
}
secret_words = re.compile(r"(?:secret|credential|password|passwd|token|private[_-]?key|access[_-]?key|api[_-]?key)", re.I)
for path in paths:
    if path in protected or (path not in allowed_files and not path.startswith(allowed_prefixes)):
        raise SystemExit(1)
    components = path.split("/")
    lower_components = {component.lower() for component in components}
    basename = components[-1].lower()
    if any(component in {".git", "config", "configs", "configuration", "profile", "profiles", "certificates", "certificate", "provisioning", "deriveddata", "xcuserdata", "pods", "build", ".build"} for component in lower_components):
        raise SystemExit(1)
    if secret_words.search(path) or basename.endswith((".p12", ".pfx", ".pem", ".key", ".crt", ".cer", ".der", ".mobileprovision", ".provisionprofile", ".xcconfig", ".xcuserstate", ".xcarchive", ".xcresult")):
        raise SystemExit(1)
    if any(marker in basename for marker in (".generated.", ".gen.", ".derived.", "generated_")):
        raise SystemExit(1)

# Preserve the exact caller bytes for both application and evidence.  The
# renderer never reconstructs a patch from text, so binary patches survive.
fd, temporary = os.open(str(snapshot), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), str(snapshot)
os.write(fd, raw)
os.close(fd)
os.chmod(snapshot, 0o600)
meta = {
    "sha256": hashlib.sha256(raw).hexdigest(),
    "size": len(raw),
    "paths": sorted(paths),
    "binary": b"GIT binary patch" in raw,
    "fullIndex": any(re.fullmatch(rb"index [0-9a-fA-F]{40}[.][.][0-9a-fA-F]{40}(?: [0-9]+)?", line) for line in raw.splitlines()),
}
fd = os.open(str(metadata), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
os.write(fd, (json.dumps(meta, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8"))
os.close(fd)
os.chmod(metadata, 0o600)
PY
    then
        return 1
    fi
}

create_empty_file() {
    local path="$1"
    if ! python3 - "$path" <<'PY'
import os
import stat
import sys
from pathlib import Path
path = Path(sys.argv[1])
try:
    info = os.lstat(path)
except FileNotFoundError:
    fd = os.open(str(path), os.O_CREAT | os.O_EXCL | os.O_WRONLY | getattr(os, "O_NOFOLLOW", 0), 0o600)
    os.close(fd)
    raise SystemExit(0)
except OSError:
    raise SystemExit(1)
if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
    raise SystemExit(1)
PY
    then
        return 1
    fi
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
    if ! python3 - "$root" "$expected_json" "$status_file" "$actual_json" <<'PY'
import json
import os
import re
import stat
import sys
from pathlib import Path
root = Path(sys.argv[1])
expected = set(json.load(open(sys.argv[2], encoding="utf-8"))["paths"])
lines = Path(sys.argv[3]).read_text(encoding="utf-8", errors="strict").splitlines()
allowed_prefixes = ("iosApp/", "shared/", "androidApp/", "docs/", ".github/")
allowed_files = {"README.md", "iOS_INSTALL.md", "ANDROID_INSTALL.md", "AGENTS.md", "CLAUDE.md", "build.gradle.kts", "settings.gradle.kts"}
actual = set()
for line in lines:
    if not line.strip():
        continue
    if len(line) < 3:
        raise SystemExit(1)
    code = line[:2]
    value = line[3:]
    if code == "!!":
        raise SystemExit(1)
    values = value.split(" -> ", 1) if " -> " in value else [value]
    for path in values:
        path = path.strip()
        if path.startswith('"') or path.endswith('"') or not path or path.startswith("/") or "\\" in path or any(part in ("", ".", "..") for part in path.split("/")):
            raise SystemExit(1)
        if path not in allowed_files and not path.startswith(allowed_prefixes):
            raise SystemExit(1)
        if code == "??" and path not in expected:
            raise SystemExit(1)
        actual.add(path)
# New source files are intentionally untracked until a proposal is committed;
# all other untracked files are generated artifacts and are refused.
if any(line.startswith("??") and line[3:].strip() not in expected for line in lines):
    raise SystemExit(1)
if not actual:
    raise SystemExit(1)
if not actual.issubset(expected):
    raise SystemExit(1)
if not expected.issubset(actual):
    raise SystemExit(1)
for path in actual:
    current = root
    for component in path.split("/"):
        current = current / component
        try:
            info = os.lstat(current)
        except OSError:
            # Deleted paths have no terminal inode; their parent components
            # must still be safe.
            if current == root / path:
                break
            raise SystemExit(1)
        if stat.S_ISLNK(info.st_mode):
            raise SystemExit(1)
        if current != root / path and not stat.S_ISDIR(info.st_mode):
            raise SystemExit(1)
        if current == root / path and not (stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode)):
            raise SystemExit(1)
# Store only names, never status output or patch text.
Path(sys.argv[4]).write_text(json.dumps(sorted(actual), separators=(",", ":")) + "\n", encoding="utf-8")
os.chmod(sys.argv[4], 0o600)
PY
    then
        return 1
    fi
}

run_focused_checks() {
    local root="$1"
    local base="$2"
    local metadata="$3"
    local results="$4"
    local changed_file_list="$PRIVATE_DIR/changed-files.list"
    read_meta_paths "$metadata" > "$changed_file_list"
    chmod 600 "$changed_file_list"
    local check_names='["git.diff.check"]'
    if ! quiet_command git -C "$root" diff --check "$base" --; then
        return 1
    fi
    local path
    while IFS= read -r path; do
        [[ -n "$path" ]] || continue
        local candidate="$root/$path"
        if [[ "$path" == *.sh && -f "$candidate" ]]; then
            if ! quiet_command bash -n "$candidate"; then return 1; fi
            check_names="${check_names%]},\"bash -n:${path}\"]"
        elif [[ "$path" == *.py && -f "$candidate" ]]; then
            if ! python3 - "$candidate" <<'PY' >"$PRIVATE_DIR/command.log" 2>&1
import sys
from pathlib import Path
compile(Path(sys.argv[1]).read_bytes(), sys.argv[1], "exec")
PY
            then
                return 1
            fi
            chmod 600 "$PRIVATE_DIR/command.log"
            check_names="${check_names%]},\"python-compile:${path}\"]"
        fi
    done < "$changed_file_list"
    # The command names are informational and contain only allowlisted paths.
    if ! python3 - "$results" "$check_names" <<'PY'
import json
import os
import sys
from pathlib import Path
path = Path(sys.argv[1])
raw = sys.argv[2]
items = json.loads(raw)
path.write_text(json.dumps([{"name": item, "passed": True} for item in items], separators=(",", ":")) + "\n", encoding="utf-8")
os.chmod(path, 0o600)
PY
    then
        return 1
    fi
}

validate_identity_pair() {
    local before="$1"
    local after="$2"
    local destination="$3"
    if ! python3 - "$before/run.json" "$after/run.json" "$destination" <<'PY'
import hashlib
import json
import os
import stat
import sys
from pathlib import Path


def load(path):
    info = os.lstat(path)
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600:
        raise SystemExit(1)
    manifest = json.loads(Path(path).read_text(encoding="utf-8"))
    provenance = manifest.get("provenance")
    if not isinstance(provenance, dict):
        raise SystemExit(1)
    fixture = provenance.get("fixture")
    if not isinstance(fixture, dict):
        raise SystemExit(1)
    fixture_id = fixture.get("id")
    fixture_sha = fixture.get("sha256")
    simulator = provenance.get("simulator")
    if not isinstance(fixture_id, str) or fixture_id != "just-lift-connected":
        raise SystemExit(1)
    if not isinstance(fixture_sha, str) or len(fixture_sha) != 64 or any(ch not in "0123456789abcdefABCDEF" for ch in fixture_sha):
        raise SystemExit(1)
    if not isinstance(simulator, (dict, str)) or not simulator:
        raise SystemExit(1)
    captures = manifest.get("captures")
    if not isinstance(captures, list) or not captures:
        raise SystemExit(1)
    checkpoints = set()
    for capture in captures:
        if not isinstance(capture, dict):
            raise SystemExit(1)
        checkpoint = capture.get("checkpoint")
        if not isinstance(checkpoint, str) or not checkpoint:
            raise SystemExit(1)
        checkpoints.add(checkpoint)
    if checkpoints != {"phantom-connected"}:
        raise SystemExit(1)
    identity = {
        "fixtureId": fixture_id,
        "fixtureSha256": fixture_sha.lower(),
        "checkpoint": "phantom-connected",
        "simulator": simulator,
    }
    return identity, hashlib.sha256(Path(path).read_bytes()).hexdigest()

before_identity, before_hash = load(sys.argv[1])
after_identity, after_hash = load(sys.argv[2])
if before_identity != after_identity:
    raise SystemExit(1)
result = {
    "identity": before_identity,
    "beforeManifestSha256": before_hash,
    "afterManifestSha256": after_hash,
}
Path(sys.argv[3]).write_text(json.dumps(result, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
os.chmod(sys.argv[3], 0o600)
PY
    then
        return 1
    fi
}

write_success_outputs() {
    local root="$1"
    local metadata="$2"
    local checks="$3"
    local identities="$4"
    local actual_files="$5"
    local status_file="$6"
    local base="$7"
    local head="$8"
    local applied_diff="$9"
    if ! python3 - "$root" "$metadata" "$checks" "$identities" "$actual_files" "$status_file" "$base" "$head" "$applied_diff" <<'PY'
import hashlib
import json
import os
import stat
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
applied_diff = sys.argv[9]

def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()

def write_atomic(path, data):
    path = Path(path)
    try:
        info = os.lstat(path)
    except FileNotFoundError:
        info = None
    if info is not None and (stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode)):
        raise SystemExit(1)
    fd, temporary = __import__("tempfile").mkstemp(prefix=".tmp-proposal-", dir=str(path.parent))
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

diff_path = root / "comparison" / "diff.json"
diff_image_path = root / "comparison" / "diff.png"
for path in (diff_path, diff_image_path):
    try:
        info = os.lstat(path)
    except OSError:
        raise SystemExit(1)
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600:
        raise SystemExit(1)
try:
    diff_raw = json.loads(diff_path.read_text(encoding="utf-8"))
except Exception:
    raise SystemExit(1)
diff_summary = {
    key: diff_raw[key]
    for key in ("passed", "thresholdPassed", "dimensions", "changedPixels", "changedPixelRatio", "meanChannelDelta", "maxChannelDelta", "threshold")
    if key in diff_raw
}
proposal = {
    "schemaVersion": 1,
    "status": "passed",
    "fixture": metadata.get("fixture", "just-lift-connected"),
    "baseSha": base,
    "patch": {
        "path": "proposal.patch",
        "sha256": metadata["sha256"],
        "size": metadata["size"],
        "binary": metadata["binary"],
        "fullIndex": metadata["fullIndex"],
        "format": "exact-input-with-binary-support",
    },
    "allowedChangedFiles": metadata["paths"],
    "actualChangedFiles": actual_files,
    "worktree": {
        "baseSha": base,
        "headSha": head,
        "detached": True,
        "uncommitted": bool(status_lines),
        "statusEntryCount": len(status_lines),
        "appliedDiffSha256": applied_diff,
    },
    "focusedChecks": checks,
    "before": {
        "artifact": "before",
        "manifestSha256": identities["beforeManifestSha256"],
        "identity": identities["identity"],
    },
    "after": {
        "artifact": "after",
        "manifestSha256": identities["afterManifestSha256"],
        "identity": identities["identity"],
    },
    "comparison": {
        "diffJson": "comparison/diff.json",
        "diffImage": "comparison/diff.png",
        "summary": diff_summary,
    },
    "evidence": {
        "proposalMarkdown": "proposal.md",
        "summaryJson": "evidence-summary.json",
    },
}
write_atomic(root / "proposal-manifest.json", json.dumps(proposal, indent=2, sort_keys=True) + "\n")
summary = {
    "schemaVersion": 1,
    "status": "passed",
    "fixture": proposal["fixture"],
    "baseSha": base,
    "patchSha256": metadata["sha256"],
    "changedFiles": actual_files,
    "beforeAfterIdentity": identities["identity"],
    "comparison": diff_summary,
    "artifacts": ["before", "after", "proposal.patch", "proposal-manifest.json", "proposal.md", "comparison/diff.json", "comparison/diff.png"],
}
write_atomic(root / "evidence-summary.json", json.dumps(summary, indent=2, sort_keys=True) + "\n")
lines = [
    "# Phantom proposal evidence",
    "",
    "Status: **passed**",
    "",
    "This proposal was rendered from the real Phoenix app in a disposable detached worktree.",
    "",
    f"- Fixture: `{proposal['fixture']}`",
    f"- Verified base SHA: `{base}`",
    f"- Proposal patch SHA-256: `{metadata['sha256']}`",
    f"- Before/after identity: fixture `{identities['identity']['fixtureId']}`, checkpoint `{identities['identity']['checkpoint']}`",
    f"- Image comparison: `{diff_summary.get('changedPixels', 'unknown')}` changed pixels; passed=`{diff_summary.get('passed', False)}`",
    "",
    "## Allowed changed files",
]
lines.extend(f"- `{path}`" for path in actual_files)
lines.extend(["", "## Verification", "", "- Baseline harness case: verified", "- Candidate harness case: verified", "- Committed image comparison: verified", "- Temporary worktree: cleaned after rendering", ""])
write_atomic(root / "proposal.md", "\n".join(lines))
PY
    then
        return 1
    fi
}

write_failure_manifest() {
    [[ "$ROOT_READY" -eq 1 ]] || return 0
    python3 - "$ARTIFACT_DIR" "$SENTINEL_NAME" "$BASE_SHA" "$PATCH_SHA256" "$STAGE" "$FAILURE_REASON" <<'PY' || true
import json
import os
import shutil
import stat
import sys
from pathlib import Path
root = Path(sys.argv[1])
sentinel_name = sys.argv[2]
base = sys.argv[3]
patch_sha = sys.argv[4]
stage = sys.argv[5]
reason = sys.argv[6]
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
    if stat.S_ISDIR(child_info.st_mode) and not stat.S_ISLNK(child_info.st_mode):
        shutil.rmtree(child)
    else:
        child.unlink()
manifest = {
    "schemaVersion": 1,
    "status": "failed",
    "failure": {"stage": stage, "reason": reason},
}
if base:
    manifest["baseSha"] = base
if patch_sha:
    manifest["patchSha256"] = patch_sha
path = root / "proposal-manifest.json"
fd, temporary = __import__("tempfile").mkstemp(prefix=".tmp-failure-", dir=str(root))
os.close(fd)
os.chmod(temporary, 0o600)
try:
    Path(temporary).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temporary, path)
    os.chmod(path, 0o600)
finally:
    try:
        os.unlink(temporary)
    except FileNotFoundError:
        pass
PY
}

cleanup_worktree() {
    if [[ "$WORKTREE_CREATED" -ne 1 || -z "$WORKTREE" ]]; then return 0; fi
    git -C "$REPO_ROOT" worktree remove --force "$WORKTREE" >/dev/null 2>&1 || true
    git -C "$REPO_ROOT" worktree prune >/dev/null 2>&1 || true
    python3 - "$WORKTREE" "$PRIVATE_DIR" <<'PY' || true
import os
import shutil
import stat
import sys
from pathlib import Path
path = Path(sys.argv[1])
parent = Path(sys.argv[2])
try:
    path.relative_to(parent)
except ValueError:
    raise SystemExit(0)
try:
    info = os.lstat(path)
except OSError:
    raise SystemExit(0)
if info.st_uid != os.getuid():
    raise SystemExit(0)
if stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode):
    shutil.rmtree(path)
elif stat.S_ISLNK(info.st_mode) or stat.S_ISREG(info.st_mode):
    path.unlink()
PY
    WORKTREE_CREATED=0
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

on_exit() {
    local rc=$?
    trap - EXIT HUP INT TERM
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
trap on_exit EXIT HUP INT TERM

render() {
    local requested_artifact="$1"
    local fixture="$2"
    local requested_patch="$3"
    [[ "$fixture" == "just-lift-connected" ]] || fail 'fixture is not allowlisted'
    STAGE="validate paths"
    if ! ARTIFACT_DIR="$(normalize_path "$requested_artifact" 1)"; then fail 'artifact path is unsafe'; fi
    if ! prepare_root "$ARTIFACT_DIR"; then fail 'artifact root must be a caller-owned empty directory'; fi
    ROOT_READY=1
    if ! PATCH_FILE="$(normalize_file_path "$requested_patch")"; then fail 'patch path is unsafe'; fi
    if ! validate_regular_file "$RUNNER" 0; then fail 'committed harness runner is unavailable'; fi
    if ! validate_regular_file "$PATCH_FILE" 1; then fail 'patch must be a caller-owned private regular file'; fi

    PRIVATE_DIR="$(new_private_dir)"
    STAGE="verify committed base"
    if ! BASE_SHA="$(git -C "$REPO_ROOT" rev-parse --verify HEAD 2>"$PRIVATE_DIR/command.log")"; then fail 'base commit could not be verified'; fi
    if [[ ! "$BASE_SHA" =~ ^[0-9a-fA-F]{40}$ ]]; then fail 'base commit is malformed'; fi
    if ! git -C "$REPO_ROOT" status --porcelain --untracked-files=no >"$PRIVATE_DIR/original-status" 2>&1; then fail 'original harness status could not be verified'; fi
    chmod 600 "$PRIVATE_DIR/original-status"
    if [[ -s "$PRIVATE_DIR/original-status" ]]; then fail 'original harness worktree has tracked changes'; fi
    if ! git -C "$REPO_ROOT" ls-files --error-unmatch -- .github/scripts/phantom-harness.sh >"$PRIVATE_DIR/runner-tracked" 2>&1; then fail 'harness runner is not committed'; fi
    chmod 600 "$PRIVATE_DIR/runner-tracked"
    if ! git -C "$REPO_ROOT" diff --quiet HEAD -- .github/scripts/phantom-harness.sh >"$PRIVATE_DIR/runner-diff" 2>&1; then fail 'harness runner has uncommitted changes'; fi
    chmod 600 "$PRIVATE_DIR/runner-diff"

    STAGE="validate patch"
    local patch_snapshot="$PRIVATE_DIR/proposal.patch"
    local patch_metadata="$PRIVATE_DIR/patch-meta.json"
    if ! create_patch_metadata "$PATCH_FILE" "$patch_snapshot" "$patch_metadata"; then fail 'patch is invalid or modifies a protected path'; fi
    PATCH_SHA256="$(python3 - "$patch_metadata" <<'PY'
import json
import sys
print(json.load(open(sys.argv[1], encoding="utf-8"))["sha256"])
PY
)"
    if ! create_empty_file "$ARTIFACT_DIR/proposal.patch"; then fail 'proposal patch destination is unsafe'; fi
    if ! safe_copy "$patch_snapshot" "$ARTIFACT_DIR/proposal.patch"; then fail 'proposal patch could not be recorded'; fi

    STAGE="capture baseline"
    if ! create_empty_dir "$ARTIFACT_DIR/before"; then fail 'baseline artifact destination is unsafe'; fi
    if ! (cd "$REPO_ROOT" && quiet_command "$RUNNER" case "$ARTIFACT_DIR/before" "$fixture"); then fail 'baseline harness case failed'; fi
    if ! quiet_command "$RUNNER" verify "$ARTIFACT_DIR/before"; then fail 'baseline evidence verification failed'; fi

    STAGE="create disposable worktree"
    WORKTREE="$PRIVATE_DIR/worktree"
    if ! git -C "$REPO_ROOT" worktree add --detach "$WORKTREE" "$BASE_SHA" >"$PRIVATE_DIR/worktree-create.log" 2>&1; then fail 'disposable worktree could not be created'; fi
    chmod 600 "$PRIVATE_DIR/worktree-create.log"
    WORKTREE_CREATED=1
    if ! normalize_path "$WORKTREE" 0 >/dev/null; then fail 'disposable worktree path is unsafe'; fi
    local worktree_head
    if ! worktree_head="$(git -C "$WORKTREE" rev-parse --verify HEAD 2>"$PRIVATE_DIR/command.log")"; then fail 'disposable worktree head could not be verified'; fi
    if [[ "$worktree_head" != "$BASE_SHA" ]]; then fail 'disposable worktree base does not match verified SHA'; fi
    if ! git -C "$WORKTREE" status --porcelain --untracked-files=all >"$PRIVATE_DIR/pre-apply-status" 2>&1; then fail 'disposable worktree status could not be verified'; fi
    chmod 600 "$PRIVATE_DIR/pre-apply-status"
    if [[ -s "$PRIVATE_DIR/pre-apply-status" ]]; then fail 'disposable worktree was not clean'; fi

    STAGE="apply disposable patch"
    if ! quiet_command git -C "$WORKTREE" apply --check --binary --whitespace=nowarn "$patch_snapshot"; then fail 'patch does not apply cleanly'; fi
    if ! quiet_command git -C "$WORKTREE" apply --binary --whitespace=nowarn "$patch_snapshot"; then fail 'patch application failed'; fi
    local status_after_patch="$PRIVATE_DIR/status-after-patch"
    local actual_files="$PRIVATE_DIR/actual-files.json"
    if ! status_paths_and_validate "$WORKTREE" "$patch_metadata" "$status_after_patch" "$actual_files"; then fail 'patch created an unsafe or unexpected worktree change'; fi
    local applied_diff="$PRIVATE_DIR/applied.patch"
    if ! git -C "$WORKTREE" diff --binary --full-index "$BASE_SHA" -- >"$applied_diff" 2>&1; then fail 'applied worktree diff could not be recorded'; fi
    chmod 600 "$applied_diff"
    local applied_diff_sha
    applied_diff_sha="$(sha256_file "$applied_diff")"

    STAGE="run focused checks"
    local focused_checks="$PRIVATE_DIR/focused-checks.json"
    if ! run_focused_checks "$WORKTREE" "$BASE_SHA" "$patch_metadata" "$focused_checks"; then fail 'focused patch checks failed'; fi
    if ! status_paths_and_validate "$WORKTREE" "$patch_metadata" "$status_after_patch" "$actual_files"; then fail 'focused checks created an unsafe worktree artifact'; fi

    STAGE="capture candidate"
    if ! create_empty_dir "$ARTIFACT_DIR/after"; then fail 'candidate artifact destination is unsafe'; fi
    local candidate_runner="$WORKTREE/.github/scripts/phantom-harness.sh"
    if ! validate_regular_file "$candidate_runner" 0; then fail 'candidate harness runner is unavailable'; fi
    if ! (cd "$WORKTREE" && quiet_command "$candidate_runner" case "$ARTIFACT_DIR/after" "$fixture"); then fail 'candidate harness case failed'; fi
    if ! quiet_command "$RUNNER" verify "$ARTIFACT_DIR/after"; then fail 'candidate evidence verification failed'; fi
    if ! status_paths_and_validate "$WORKTREE" "$patch_metadata" "$status_after_patch" "$actual_files"; then fail 'candidate run created an unsafe or unexpected worktree artifact'; fi

    STAGE="compare screenshots"
    if ! create_empty_dir "$ARTIFACT_DIR/comparison"; then fail 'comparison destination is unsafe'; fi
    if ! quiet_command "$RUNNER" compare "$ARTIFACT_DIR/before" "$ARTIFACT_DIR/after" "$ARTIFACT_DIR/comparison"; then fail 'committed screenshot comparison failed'; fi
    if ! validate_regular_file "$ARTIFACT_DIR/comparison/diff.json" 1 || ! validate_regular_file "$ARTIFACT_DIR/comparison/diff.png" 1; then fail 'screenshot comparison outputs are unsafe'; fi
    local identities="$PRIVATE_DIR/identities.json"
    if ! validate_identity_pair "$ARTIFACT_DIR/before" "$ARTIFACT_DIR/after" "$identities"; then fail 'before and after evidence identities do not match'; fi

    STAGE="write proposal evidence"
    if ! write_success_outputs "$ARTIFACT_DIR" "$patch_metadata" "$focused_checks" "$identities" "$actual_files" "$status_after_patch" "$BASE_SHA" "$worktree_head" "$applied_diff_sha"; then fail 'proposal evidence could not be written'; fi
    if ! quiet_command "$RUNNER" verify "$ARTIFACT_DIR/before"; then fail 'baseline evidence changed during rendering'; fi
    # Verify the proposal packet's own safety properties without echoing it.
    if ! python3 - "$ARTIFACT_DIR" <<'PY'
import json
import os
import stat
import sys
from pathlib import Path
root = Path(sys.argv[1])
if stat.S_IMODE(os.lstat(root).st_mode) != 0o700:
    raise SystemExit(1)
for path in (root / ".phantom-proposal", root / "proposal.patch", root / "proposal.md", root / "proposal-manifest.json", root / "evidence-summary.json", root / "comparison" / "diff.json", root / "comparison" / "diff.png"):
    info = os.lstat(path)
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600:
        raise SystemExit(1)
manifest = json.loads((root / "proposal-manifest.json").read_text(encoding="utf-8"))
if manifest.get("status") != "passed" or manifest.get("patch", {}).get("sha256") != __import__("hashlib").sha256((root / "proposal.patch").read_bytes()).hexdigest():
    raise SystemExit(1)
PY
    then
        fail 'proposal packet safety verification failed'
    fi
    STAGE="complete"
    printf '%s\n' "phantom-proposal: rendered evidence at $ARTIFACT_DIR"
}

main() {
    reject_credentials "$@"
    [[ "$#" -eq 4 && "$1" == "render" ]] || usage
    render "$2" "$3" "$4"
}

main "$@"
