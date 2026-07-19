#!/usr/bin/env bash
set -euo pipefail

# Secure real-app runner for the shared VitruvianPhoenix iOS scheme.  The
# runner deliberately keeps filesystem work in the Python standard library and
# uses only xcrun/xcodebuild for Apple tooling.

IFS=$'\n\t'
SCRIPT_DIR="${BASH_SOURCE[0]%/*}"
if [[ "$SCRIPT_DIR" == "${BASH_SOURCE[0]}" ]]; then SCRIPT_DIR="."; fi
if [[ "$SCRIPT_DIR" != /* ]]; then SCRIPT_DIR="$PWD/$SCRIPT_DIR"; fi
SCRIPT_DIR="$(cd "$SCRIPT_DIR" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
PROJECT_DIR="$REPO_ROOT/iosApp/VitruvianPhoenix"
PROJECT="$PROJECT_DIR/VitruvianPhoenix.xcodeproj"
SCHEME="VitruvianPhoenix"
BUNDLE_ID="com.devil.phoenixproject.projectphoenix"
TEST_CLASS="PhantomJustLiftFlowUITests"
TEST_METHOD="testHomeToJustLiftToPhantomConnected"
UITEST_TARGET="VitruvianPhoenixUITests"
FIXTURE_SOURCE="$REPO_ROOT/shared/src/iosSimulatorArm64Main/kotlin/com/devil/phoenixproject/fixture/SimulatorLaunchFixture.kt"
VERIFY_SCRIPT="$SCRIPT_DIR/phantom-harness-verify.py"
DIFF_SOURCE="$SCRIPT_DIR/phantom-image-diff.swift"
CONFIG_PATH="$PROJECT_DIR/Config/Supabase.xcconfig"
SENTINEL_NAME=".phantom-harness"
COMMANDS_NAME=".commands.jsonl"

ARTIFACT_DIR=""
CONFIG_CREATED=0
LAST_RC=0
COMMANDS_PATH=""
PRIVATE_DIR=""

fail() {
    printf '%s\n' "phantom-harness: $1" >&2
    exit 1
}

usage() {
    printf '%s\n' \
        'usage: phantom-harness.sh preflight UDID' \
        '       phantom-harness.sh case ARTIFACT_DIR just-lift-connected' \
        '       phantom-harness.sh verify ARTIFACT_DIR' \
        '       phantom-harness.sh compare BEFORE_DIR AFTER_DIR OUTPUT_DIR' \
        '       phantom-harness.sh clean ARTIFACT_DIR' >&2
    exit 2
}

reject_credentials() {
    python3 - "$@" <<'PY'
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
    "CI",
    "DEVELOPER_DIR",
    "GITHUB_ACTIONS",
    "GITHUB_SHA",
    "HOME",
    "LANG",
    "LC_ALL",
    "LC_CTYPE",
    "LOGNAME",
    "PATH",
    "PHOENIX_HARNESS_ALLOW_DESTRUCTIVE",
    "PHOENIX_HARNESS_UDID",
    "PWD",
    "SHELL",
    "SHLVL",
    "TMPDIR",
    "USER",
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
    if [[ $? -ne 0 ]]; then fail 'credential-like argument or environment value refused'; fi
}

# Print a normalized path only after checking every existing component with
# lstat.  Existing roots must belong to the caller.  A sticky system temp
# parent is the one deliberate exception for a newly-created root.
normalize_path() {
    local path="$1"
    local allow_missing="$2"
    python3 - "$path" "$allow_missing" <<'PY'
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
    trusted_alias = False
    if stat.S_ISLNK(info.st_mode):
        # macOS exposes /var and /tmp as trusted aliases for /private/var
        # and /private/tmp.  No caller-created symlink is accepted.
        if str(current) not in {"/var", "/tmp"} or os.path.realpath(current) not in {"/private/var", "/private/tmp"}:
            raise SystemExit(1)
        trusted_alias = True
    if not trusted_alias and index < len(parts) - 1 and not stat.S_ISDIR(info.st_mode):
        raise SystemExit(1)
    if not trusted_alias and index == len(parts) - 1 and not stat.S_ISDIR(info.st_mode):
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
    if (not trusted_parent_alias and stat.S_ISLNK(info.st_mode)) or (not trusted_parent_alias and not stat.S_ISDIR(info.st_mode)):
        raise SystemExit(1)
    ownership_info = os.stat(parent) if trusted_parent_alias else info
    if ownership_info.st_uid != uid and not (stat.S_IMODE(ownership_info.st_mode) & 0o1000 and stat.S_IMODE(ownership_info.st_mode) & 0o2):
        raise SystemExit(1)
print(str(path))
PY
}

prepare_artifact_root() {
    local path="$1"
    python3 - "$path" "$SENTINEL_NAME" "$COMMANDS_NAME" <<'PY'
import os
import stat
import sys
from pathlib import Path

root = Path(sys.argv[1])
sentinel = sys.argv[2]
commands = sys.argv[3]
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
children = list(root.iterdir())
if children:
    raise SystemExit(1)
os.chmod(root, 0o700)
for name in (sentinel, commands):
    path = root / name
    with path.open("xb") as stream:
        if name == sentinel:
            stream.write(b"phantom-harness-artifact-v1\n")
    os.chmod(path, 0o600)
PY
    if [[ $? -ne 0 ]]; then fail 'artifact root must be a caller-owned empty directory'; fi
}

validate_existing_root() {
    local path="$1"
    python3 - "$path" <<'PY'
import os
import stat
import sys
from pathlib import Path
path = Path(sys.argv[1])
try:
    info = os.lstat(path)
except OSError:
    raise SystemExit(1)
if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
    raise SystemExit(1)
for child in path.iterdir():
    try:
        child_info = os.lstat(child)
    except OSError:
        raise SystemExit(1)
    if stat.S_ISLNK(child_info.st_mode):
        raise SystemExit(1)
PY
    if [[ $? -ne 0 ]]; then fail 'artifact path is not a caller-owned directory'; fi
}

new_temp_file() {
    local directory="$1"
    python3 - "$directory" <<'PY'
import os
import sys
tmp_fd, tmp_name = __import__("tempfile").mkstemp(prefix=".tmp-", dir=sys.argv[1])
os.close(tmp_fd)
os.chmod(tmp_name, 0o600)
print(tmp_name)
PY
}

new_private_dir() {
    python3 - <<'PY'
import os
tmp_name = __import__("tempfile").mkdtemp(prefix="phantom-harness-", dir=os.environ.get("TMPDIR") or None)
os.chmod(tmp_name, 0o700)
print(tmp_name)
PY
}

cleanup_private_dir() {
    if [[ -z "$PRIVATE_DIR" ]]; then return 0; fi
    python3 - "$PRIVATE_DIR" <<'PY'
import shutil
import sys
shutil.rmtree(sys.argv[1], ignore_errors=True)
PY
}

ensure_direct_destination() {
    local root="$1"
    local raw="$2"
    python3 - "$root" "$raw" <<'PY'
import os
import re
import stat
import sys
from pathlib import Path
root = Path(sys.argv[1])
raw = sys.argv[2]
if not raw or "/" in raw or "\\" in raw or raw in (".", "..") or not re.fullmatch(r"[A-Za-z0-9._-]+", raw):
    raise SystemExit(1)
path = root / raw
try:
    info = os.lstat(path)
except FileNotFoundError:
    raise SystemExit(0)
except OSError:
    raise SystemExit(1)
if stat.S_ISLNK(info.st_mode) or stat.S_ISDIR(info.st_mode) or not stat.S_ISREG(info.st_mode):
    raise SystemExit(1)
PY
    if [[ $? -ne 0 ]]; then fail 'unsafe artifact output path'; fi
}

safe_replace_text() {
    local source="$1"
    local destination="$2"
    python3 - "$source" "$destination" <<'PY'
import os
import re
import stat
import sys
from pathlib import Path
source = Path(sys.argv[1])
destination = Path(sys.argv[2])
try:
    source_info = os.lstat(source)
except OSError:
    raise SystemExit(1)
try:
    destination_info = os.lstat(destination)
except FileNotFoundError:
    destination_info = None
except OSError:
    raise SystemExit(1)
if stat.S_ISLNK(source_info.st_mode) or not stat.S_ISREG(source_info.st_mode):
    raise SystemExit(1)
if destination_info is not None and (stat.S_ISLNK(destination_info.st_mode) or not stat.S_ISREG(destination_info.st_mode)):
    raise SystemExit(1)
try:
    data = source.read_bytes()[:8 * 1024 * 1024]
except OSError:
    raise SystemExit(1)
if len(source.read_bytes()) > len(data):
    data += b"\n[output truncated]\n"
try:
    text = data.decode("utf-8", "replace")
except Exception:
    text = "[non-text output omitted]\n"
patterns = (
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    re.compile(r"\b(?:gh[pousr]|github_pat|glpat|xox[baprs]|sk|rk)[_-][A-Za-z0-9_./=-]{20,}\b", re.IGNORECASE),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bBearer\s+[A-Za-z0-9._~+/=-]{20,}", re.IGNORECASE),
    re.compile(r"\b(?:api[_-]?key|access[_-]?key|secret|password|passwd|auth[_-]?token|anon[_-]?key)\s*[:=]\s*['\"]?[A-Za-z0-9._~+/=-]{16,}", re.IGNORECASE),
)
for pattern in patterns:
    text = pattern.sub("[REDACTED]", text)
tmp_fd, tmp_name = __import__("tempfile").mkstemp(prefix=".tmp-text-", dir=str(destination.parent))
os.close(tmp_fd)
os.chmod(tmp_name, 0o600)
try:
    Path(tmp_name).write_text(text, encoding="utf-8")
    os.replace(tmp_name, destination)
    os.chmod(destination, 0o600)
finally:
    try:
        os.unlink(tmp_name)
    except FileNotFoundError:
        pass
try:
    os.unlink(source)
except OSError:
    raise SystemExit(1)
PY
    if [[ $? -ne 0 ]]; then fail 'safe text capture failed'; fi
}

safe_replace_binary() {
    local source="$1"
    local destination="$2"
    python3 - "$source" "$destination" <<'PY'
import os
import stat
import sys
from pathlib import Path
source = Path(sys.argv[1])
destination = Path(sys.argv[2])
try:
    source_info = os.lstat(source)
except OSError:
    raise SystemExit(1)
try:
    destination_info = os.lstat(destination)
except FileNotFoundError:
    destination_info = None
except OSError:
    raise SystemExit(1)
if stat.S_ISLNK(source_info.st_mode) or not stat.S_ISREG(source_info.st_mode):
    raise SystemExit(1)
if destination_info is not None and (stat.S_ISLNK(destination_info.st_mode) or not stat.S_ISREG(destination_info.st_mode)):
    raise SystemExit(1)
tmp_fd, tmp_name = __import__("tempfile").mkstemp(prefix=".tmp-bin-", dir=str(destination.parent))
os.close(tmp_fd)
os.chmod(tmp_name, 0o600)
try:
    with source.open("rb") as src, open(tmp_name, "wb") as dst:
        while True:
            block = src.read(1024 * 1024)
            if not block:
                break
            dst.write(block)
    os.replace(tmp_name, destination)
    os.chmod(destination, 0o600)
finally:
    try:
        os.unlink(tmp_name)
    except FileNotFoundError:
        pass
try:
    os.unlink(source)
except OSError:
    raise SystemExit(1)
PY
    if [[ $? -ne 0 ]]; then fail 'safe binary capture failed'; fi
}

sanitize_existing_text() {
    local path="$1"
    local temporary
    temporary="$(new_temp_file "$ARTIFACT_DIR")"
    python3 - "$path" "$temporary" <<'PY'
import os
import stat
import sys
from pathlib import Path
source = Path(sys.argv[1])
target = Path(sys.argv[2])
info = os.lstat(source)
if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
    raise SystemExit(1)
data = source.read_bytes()
target.write_bytes(data)
os.chmod(target, 0o600)
PY
    safe_replace_text "$temporary" "$path"
}

execute_capture() {
    local output="$1"
    shift
    local raw
    raw="$(new_temp_file "$ARTIFACT_DIR")"
    set +e
    "$@" >"$raw" 2>&1
    LAST_RC=$?
    set -e
    safe_replace_text "$raw" "$output"
}

record_command() {
    local name="$1"
    local code="$2"
    local output="$3"
    local result_bundle="${4-}"
    python3 - "$COMMANDS_PATH" "$name" "$code" "$output" "$result_bundle" <<'PY'
import json
import os
import stat
import sys
from pathlib import Path
path = Path(sys.argv[1])
info = os.lstat(path)
if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600:
    raise SystemExit(1)
with path.open("a", encoding="utf-8") as stream:
    result = {"name": sys.argv[2], "exitCode": int(sys.argv[3]), "output": sys.argv[4]}
    if sys.argv[5]:
        result["resultBundlePath"] = sys.argv[5]
    stream.write(json.dumps(result, separators=(",", ":")) + "\n")
PY
    if [[ $? -ne 0 ]]; then fail 'command result could not be recorded'; fi
}

run_recorded() {
    local name="$1"
    local output="$2"
    shift 2
    execute_capture "$output" "$@"
    record_command "$name" "$LAST_RC" "${output##*/}"
}

run_recorded_result() {
    local name="$1"
    local output="$2"
    local result_bundle="$3"
    shift 3
    execute_capture "$output" "$@"
    record_command "$name" "$LAST_RC" "${output##*/}" "$result_bundle"
}

benign_simctl_failure() {
    local output="$1"
    python3 - "$output" <<'PY'
import re
import sys
from pathlib import Path
try:
    text = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace").lower()
except OSError:
    raise SystemExit(1)
patterns = (
    r"no such process",
    r"not running",
    r"not installed",
    r"application is not installed",
    r"found nothing to terminate",
    r"no such file or directory",
    r"already booted",
    r"current state:?\s*booted",
)
raise SystemExit(0 if any(re.search(pattern, text) for pattern in patterns) else 1)
PY
}

run_simctl_reset_command() {
    local name="$1"
    local output="$2"
    shift 2
    execute_capture "$output" "$@"
    if [[ "$LAST_RC" -ne 0 ]] && benign_simctl_failure "$output"; then LAST_RC=0; fi
    record_command "$name" "$LAST_RC" "${output##*/}"
}

write_text_file() {
    local path="$1"
    local text="$2"
    python3 - "$path" "$text" <<'PY'
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
tmp_fd, tmp_name = __import__("tempfile").mkstemp(prefix=".tmp-write-", dir=str(path.parent))
os.close(tmp_fd)
os.chmod(tmp_name, 0o600)
try:
    Path(tmp_name).write_text(sys.argv[2], encoding="utf-8")
    os.replace(tmp_name, path)
    os.chmod(path, 0o600)
finally:
    try:
        os.unlink(tmp_name)
    except FileNotFoundError:
        pass
PY
    if [[ $? -ne 0 ]]; then fail 'artifact metadata write failed'; fi
}

provision_placeholder_config() {
    python3 - "$CONFIG_PATH" <<'PY'
import os
import stat
import sys
from pathlib import Path
path = Path(sys.argv[1])
try:
    info = os.lstat(path)
except FileNotFoundError:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        os.write(fd, (
            "// Temporary non-secret simulator fixture configuration.\n"
            "SUPABASE_URL = https://placeholder.invalid\n"
            "SUPABASE_ANON_KEY = placeholder-anon-key\n"
        ).encode("utf-8"))
    finally:
        os.close(fd)
    os.chmod(path, 0o600)
    print("created")
    raise SystemExit(0)
except OSError:
    raise SystemExit(1)
if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
    raise SystemExit(1)
print("existing")
PY
}

cleanup_placeholder_config() {
    if [[ "$CONFIG_CREATED" -ne 1 ]]; then return 0; fi
    python3 - "$CONFIG_PATH" <<'PY'
import os
import stat
import sys
from pathlib import Path
path = Path(sys.argv[1])
try:
    info = os.lstat(path)
except FileNotFoundError:
    raise SystemExit(0)
except OSError:
    raise SystemExit(1)
if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
    raise SystemExit(1)
path.unlink()
PY
}

fixture_hash() {
    python3 - "$FIXTURE_SOURCE" <<'PY'
import hashlib
import sys
from pathlib import Path
path = Path(sys.argv[1])
digest = hashlib.sha256()
with path.open("rb") as stream:
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(block)
print(digest.hexdigest())
PY
}

base_sha() {
    python3 - "$REPO_ROOT" <<'PY'
import os
import re
import sys
from pathlib import Path
root = Path(sys.argv[1])
git = root / ".git"
try:
    content = git.read_text(encoding="utf-8").strip() if git.is_file() else None
    git_root = Path(content[5:]).resolve() if content and content.startswith("gitdir:") else git
    head = (git_root / "HEAD").read_text(encoding="utf-8").strip()
    if head.startswith("ref: "):
        ref = head[5:]
        ref_path = git_root / ref
        if ref_path.is_file():
            head = ref_path.read_text(encoding="utf-8").strip()
        else:
            packed = git_root / "packed-refs"
            for line in packed.read_text(encoding="utf-8").splitlines():
                parts = line.split(" ", 1)
                if len(parts) == 2 and parts[1].strip() == ref:
                    head = parts[0]
                    break
    if not re.fullmatch(r"[0-9a-fA-F]{40}", head):
        raise ValueError
    print(head.lower())
except Exception:
    print("0" * 40)
PY
}

run_id() {
    python3 - <<'PY'
import os
import time
print("run-%s-%s" % (time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()), os.getpid()))
PY
}

list_devices_json() {
    local result
    if ! result="$(xcrun simctl list devices -j 2>/dev/null)"; then fail 'simulator device inventory failed'; fi
    printf '%s' "$result"
}

device_info_from_json() {
    local json="$1"
    python3 - "$json" <<'PY'
import json
import sys
payload = json.loads(sys.argv[1])
target = sys.argv[2] if len(sys.argv) > 2 else None
found = None
for runtime, devices in payload.get("devices", {}).items():
    if not isinstance(devices, list):
        continue
    for device in devices:
        if not isinstance(device, dict):
            continue
        if target is None or device.get("udid") == target:
            if device.get("isAvailable") is False:
                continue
            found = {
                "udid": device.get("udid", ""),
                "name": device.get("name", ""),
                "runtime": runtime,
                "state": device.get("state", ""),
            }
            break
    if found is not None:
        break
if found is None or not found["udid"] or not found["name"] or not found["runtime"] or not found["state"]:
    raise SystemExit(1)
print(json.dumps(found, separators=(",", ":")))
PY
}

validate_udid() {
    local udid="$1"
    python3 - "$udid" <<'PY'
import re
import sys
if not re.fullmatch(r"[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}", sys.argv[1]):
    raise SystemExit(1)
PY
    if [[ $? -ne 0 ]]; then fail 'missing or malformed simulator UDID'; fi
}

preflight() {
    local udid="$1"
    validate_udid "$udid"
    local devices
    devices="$(list_devices_json)"
    local info
    if ! info="$(python3 - "$devices" "$udid" <<'PY'
import json
import sys
payload = json.loads(sys.argv[1])
target = sys.argv[2]
for runtime, devices in payload.get("devices", {}).items():
    for device in devices if isinstance(devices, list) else []:
        if isinstance(device, dict) and device.get("udid") == target and device.get("isAvailable") is not False:
            required = (device.get("name"), runtime, device.get("state"))
            if all(required):
                identity = {"udid": target, "name": required[0], "runtime": runtime, "state": required[2]}
                print(json.dumps(identity, separators=(",", ":")))
                raise SystemExit(0)
raise SystemExit(1)
PY
)"; then fail 'requested simulator UDID is unavailable'; fi
    printf '%s\n' "$info"
}

normalize_tree_modes() {
    local root="$1"
    python3 - "$root" <<'PY'
import os
import stat
import sys
from pathlib import Path
root = Path(sys.argv[1])
for current, dirs, files in os.walk(root, topdown=True, followlinks=False):
    current_path = Path(current)
    os.chmod(current_path, 0o700)
    for name in dirs:
        path = current_path / name
        info = os.lstat(path)
        if stat.S_ISLNK(info.st_mode):
            continue
        if stat.S_ISDIR(info.st_mode):
            os.chmod(path, 0o700)
    for name in files:
        path = current_path / name
        info = os.lstat(path)
        if stat.S_ISREG(info.st_mode):
            os.chmod(path, 0o600)
PY
}

copy_xctest_attachment() {
    local result_bundle="$1"
    local destination="$2"
    python3 - "$result_bundle" "$destination" <<'PY'
import os
import stat
import sys
from pathlib import Path
bundle = Path(sys.argv[1])
destination = Path(sys.argv[2])
try:
    info = os.lstat(bundle)
except OSError:
    raise SystemExit(1)
if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
    raise SystemExit(1)
found = []
for current, dirs, files in os.walk(bundle, topdown=True, followlinks=False):
    dirs[:] = sorted(dirs)
    for name in sorted(files):
        path = Path(current) / name
        try:
            child = os.lstat(path)
        except OSError:
            continue
        if stat.S_ISREG(child.st_mode) and path.suffix.lower() == ".png":
            found.append(path)
if not found:
    raise SystemExit(1)
source = found[0]
tmp_fd, tmp_name = __import__("tempfile").mkstemp(prefix=".tmp-attachment-", dir=str(destination.parent))
os.close(tmp_fd)
os.chmod(tmp_name, 0o600)
try:
    with source.open("rb") as src, open(tmp_name, "wb") as dst:
        for block in iter(lambda: src.read(1024 * 1024), b""):
            dst.write(block)
    os.replace(tmp_name, destination)
    os.chmod(destination, 0o600)
finally:
    try:
        os.unlink(tmp_name)
    except FileNotFoundError:
        pass
PY
}

write_manifest() {
    local device_json="$1"
    local xcode="$2"
    local sdk="$3"
    local base="$4"
    local fixture="$5"
    local fixture_sha="$6"
    local run="$7"
    local required_json="$8"
    local observed_json="$9"
    python3 - "$ARTIFACT_DIR" "$device_json" "$xcode" "$sdk" "$base" "$fixture" "$fixture_sha" "$run" "$required_json" "$observed_json" <<'PY'
import hashlib
import json
import os
import stat
import struct
import sys
import zlib
from pathlib import Path

root = Path(sys.argv[1])
device = json.loads(sys.argv[2])
xcode = sys.argv[3]
sdk = sys.argv[4]
base = sys.argv[5]
fixture = sys.argv[6]
fixture_sha = sys.argv[7]
run_id = sys.argv[8]
required = json.loads(sys.argv[9])
observed = json.loads(sys.argv[10])
commands = []
commands_path = root / ".commands.jsonl"
for line in commands_path.read_text(encoding="utf-8").splitlines():
    if line.strip():
        commands.append(json.loads(line))

def png_metadata(path):
    info = os.lstat(path)
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        return None
    data = path.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    dimensions = None
    if len(data) >= 33 and data[:8] == b"\x89PNG\r\n\x1a\n" and data[12:16] == b"IHDR":
        width, height = struct.unpack(">II", data[16:24])
        dimensions = {"width": width, "height": height}
    return digest, dimensions

captures = []
for slug, filename, phase, checkpoint in (
    ("simulator-after", "after.png", "after", "phantom-connected"),
    ("xctest-after", "xctest-attachment.png", "after", "phantom-connected"),
):
    path = root / filename
    if not path.exists():
        continue
    metadata = png_metadata(path)
    if metadata is None:
        continue
    digest, dimensions = metadata
    captures.append({
        "slug": slug,
        "path": filename,
        "sha256": digest,
        "dimensions": dimensions,
        "phase": phase,
        "pair": slug,
        "checkpoint": checkpoint,
        "fixtureId": fixture,
        "fixtureSha256": fixture_sha,
        "simulator": device,
    })

textual = []
for name in ("toolchain.log", "build.log", "test.log", "app-state.log", "simulator.log", "screenshot.log", ".commands.jsonl"):
    path = root / name
    try:
        info = os.lstat(path)
    except OSError:
        continue
    if stat.S_ISREG(info.st_mode):
        textual.append({"path": name})

manifest = {
    "schemaVersion": 1,
    "runId": run_id,
    "provenance": {
        "baseSha": base,
        "fixture": {"id": fixture, "sha256": fixture_sha},
        "xcode": xcode,
        "sdk": sdk,
        "simulator": device,
        "bundleId": "com.devil.phoenixproject.projectphoenix",
    },
    "commands": commands,
    "semanticMarkers": {"required": required, "observed": observed},
    "captures": captures,
    "textualArtifacts": textual,
}
manifest_path = root / "run.json"
tmp_fd, tmp_name = __import__("tempfile").mkstemp(prefix=".tmp-manifest-", dir=str(root))
os.close(tmp_fd)
os.chmod(tmp_name, 0o600)
try:
    Path(tmp_name).write_text(json.dumps(manifest, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp_name, manifest_path)
    os.chmod(manifest_path, 0o600)
finally:
    try:
        os.unlink(tmp_name)
    except FileNotFoundError:
        pass
PY
    if [[ $? -ne 0 ]]; then fail 'run manifest generation failed'; fi
}

verify_root() {
    local root="$1"
    validate_existing_root "$root"
    "$VERIFY_SCRIPT" "$root"
}

case_run() {
    local requested_root="$1"
    local fixture="$2"
    if [[ "$fixture" != "just-lift-connected" ]]; then fail 'fixture is not allowlisted'; fi
    local udid="${PHOENIX_HARNESS_UDID-}"
    if [[ -z "$udid" ]]; then fail 'case requires PHOENIX_HARNESS_UDID'; fi
    validate_udid "$udid"
    local normalized
    if ! normalized="$(normalize_path "$requested_root" 1)"; then fail 'artifact path is unsafe'; fi
    ARTIFACT_DIR="$normalized"
    prepare_artifact_root "$ARTIFACT_DIR"
    COMMANDS_PATH="$ARTIFACT_DIR/$COMMANDS_NAME"
    trap cleanup_placeholder_config EXIT

    local destructive=0
    if [[ "${PHOENIX_HARNESS_ALLOW_DESTRUCTIVE-}" == "1" || "${CI-}" == "true" ]]; then destructive=1; fi
    if [[ "$destructive" -ne 1 ]]; then fail 'destructive simulator reset requires explicit local gate or CI=true'; fi

    local devices
    devices="$(list_devices_json)"
    local device_json
    if ! device_json="$(python3 - "$devices" "$udid" <<'PY'
import json
import sys
payload = json.loads(sys.argv[1])
target = sys.argv[2]
for runtime, devices in payload.get("devices", {}).items():
    for device in devices if isinstance(devices, list) else []:
        if isinstance(device, dict) and device.get("udid") == target and device.get("isAvailable") is not False:
            required = (device.get("name"), runtime, device.get("state"))
            if all(required):
                identity = {"udid": target, "name": required[0], "runtime": runtime, "state": required[2]}
                print(json.dumps(identity, separators=(",", ":")))
                raise SystemExit(0)
raise SystemExit(1)
PY
)"; then fail 'requested simulator UDID is unavailable'; fi

    local base fixture_sha run
    base="$(base_sha)"
    fixture_sha="$(fixture_hash)"
    run="$(run_id)"
    local required_json='["xctest.passed","phantom.connected","simulator.screenshot"]'
    local observed_json='[]'
    local xcode='unknown'
    local sdk='unknown'

    # Boot and app-only reset.  No erase is ever issued, and every command is
    # bound to the exact caller-supplied UDID and fixed application bundle.
    ensure_direct_destination "$ARTIFACT_DIR" "toolchain.log"
    run_recorded "xcodebuild.version" "$ARTIFACT_DIR/toolchain.log" xcodebuild -version
    xcode="$(python3 - "$ARTIFACT_DIR/toolchain.log" <<'PY'
from pathlib import Path
import sys
for line in Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace").splitlines():
    if line.startswith("Xcode "):
        print(line)
        break
else:
    print("unknown")
PY
)"
    sdk="$(xcrun --sdk iphonesimulator --show-sdk-version 2>/dev/null || true)"
    if [[ -z "$sdk" ]]; then sdk='unknown'; fi

    ensure_direct_destination "$ARTIFACT_DIR" "boot.log"
    run_simctl_reset_command "simulator.boot" "$ARTIFACT_DIR/boot.log" xcrun simctl boot "$udid"
    ensure_direct_destination "$ARTIFACT_DIR" "bootstatus.log"
    run_recorded "simulator.bootstatus" "$ARTIFACT_DIR/bootstatus.log" xcrun simctl bootstatus "$udid" -b
    ensure_direct_destination "$ARTIFACT_DIR" "terminate.log"
    run_simctl_reset_command "simulator.terminate" "$ARTIFACT_DIR/terminate.log" xcrun simctl terminate "$udid" "$BUNDLE_ID"
    ensure_direct_destination "$ARTIFACT_DIR" "uninstall.log"
    run_simctl_reset_command "simulator.uninstall" "$ARTIFACT_DIR/uninstall.log" xcrun simctl uninstall "$udid" "$BUNDLE_ID"

    local config_state
    if ! config_state="$(provision_placeholder_config)"; then fail 'Supabase fixture configuration path is unsafe'; fi
    if [[ "$config_state" == "created" ]]; then CONFIG_CREATED=1; fi

    ensure_direct_destination "$ARTIFACT_DIR" "build.log"
    ensure_direct_destination "$ARTIFACT_DIR" "build-result.log"
    run_recorded "build" "$ARTIFACT_DIR/build.log" xcodebuild -project "$PROJECT" -scheme "$SCHEME" -configuration Debug -sdk iphonesimulator -destination "platform=iOS Simulator,id=$udid" -derivedDataPath "$ARTIFACT_DIR/derived-data" CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO -hideShellScriptEnvironment build

    local result_bundle="$ARTIFACT_DIR/test.xcresult"
    python3 - "$result_bundle" <<'PY'
import os
import stat
import sys
from pathlib import Path
path = Path(sys.argv[1])
try:
    info = os.lstat(path)
except FileNotFoundError:
    raise SystemExit(0)
except OSError:
    raise SystemExit(1)
if stat.S_ISLNK(info.st_mode) or stat.S_ISREG(info.st_mode) or not stat.S_ISDIR(info.st_mode):
    raise SystemExit(1)
PY
    if [[ $? -ne 0 ]]; then fail 'result bundle destination is unsafe'; fi
    ensure_direct_destination "$ARTIFACT_DIR" "test.log"
    run_recorded_result "run-tests" "$ARTIFACT_DIR/test.log" "test.xcresult" xcodebuild test -project "$PROJECT" -scheme "$SCHEME" -configuration Debug -sdk iphonesimulator -destination "platform=iOS Simulator,id=$udid" -derivedDataPath "$ARTIFACT_DIR/derived-data" -resultBundlePath "$result_bundle" CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO -hideShellScriptEnvironment -only-testing:"$UITEST_TARGET"/"$TEST_CLASS"/"$TEST_METHOD"

    # The semantic XCTest is the source of the connected marker; no coordinate
    # automation or blind delay is used here.
    if [[ "$LAST_RC" -eq 0 ]]; then
        observed_json='["xctest.passed","phantom.connected"]'
    fi

    ensure_direct_destination "$ARTIFACT_DIR" "app-state.log"
    run_recorded "simulator.app-state" "$ARTIFACT_DIR/app-state.log" xcrun simctl get_app_container "$udid" "$BUNDLE_ID" app
    if [[ "$LAST_RC" -eq 0 ]]; then
        if [[ "$observed_json" == '[]' ]]; then observed_json='["app.installed"]'; else observed_json='["xctest.passed","phantom.connected","app.installed"]'; fi
    fi

    ensure_direct_destination "$ARTIFACT_DIR" "simulator.log"
    run_recorded "simulator.logs" "$ARTIFACT_DIR/simulator.log" xcrun simctl spawn "$udid" log show --style compact --last 2m --predicate 'process == "VitruvianPhoenix"'
    ensure_direct_destination "$ARTIFACT_DIR" "screenshot.log"
    local screenshot_temp="$ARTIFACT_DIR/.tmp-screenshot"
    ensure_direct_destination "$ARTIFACT_DIR" ".tmp-screenshot"
    # ensure_direct_destination allows an existing regular file; use a fresh
    # Python-created path so an external producer never follows a symlink.
    screenshot_temp="$(new_temp_file "$ARTIFACT_DIR")"
    local screenshot_log="$ARTIFACT_DIR/screenshot.log"
    execute_capture "$screenshot_log" xcrun simctl io "$udid" screenshot "$screenshot_temp"
    local screenshot_rc="$LAST_RC"
    record_command "simulator.screenshot" "$screenshot_rc" "screenshot.log"
    if [[ "$screenshot_rc" -eq 0 ]]; then
        ensure_direct_destination "$ARTIFACT_DIR" "after.png"
        # Destination does not exist yet; replace helper requires an existing
        # regular file, so create it atomically as a private empty file first.
        python3 - "$ARTIFACT_DIR/after.png" <<'PY'
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
PY
        safe_replace_binary "$screenshot_temp" "$ARTIFACT_DIR/after.png"
        if [[ "$observed_json" == '[]' ]]; then observed_json='["simulator.screenshot"]'; else observed_json="${observed_json%]},\"simulator.screenshot\"]"; fi
    else
        python3 - "$screenshot_temp" <<'PY'
import os
import sys
try:
    os.unlink(sys.argv[1])
except OSError:
    pass
PY
    fi

    ensure_direct_destination "$ARTIFACT_DIR" "xctest-attachment.png"
    python3 - "$ARTIFACT_DIR/xctest-attachment.png" <<'PY'
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
PY
    if copy_xctest_attachment "$result_bundle" "$ARTIFACT_DIR/xctest-attachment.png"; then :; else
        python3 - "$ARTIFACT_DIR/xctest-attachment.png" <<'PY'
import os
import sys
try:
    os.unlink(sys.argv[1])
except OSError:
    pass
PY
    fi

    normalize_tree_modes "$ARTIFACT_DIR"
    local required='["xctest.passed","phantom.connected","simulator.screenshot"]'
    if [[ "$observed_json" == '[]' ]]; then observed_json='[]'; fi
    write_manifest "$device_json" "$xcode" "$sdk" "$base" "$fixture" "$fixture_sha" "$run" "$required" "$observed_json"
    verify_root "$ARTIFACT_DIR"
}

verify_case() {
    local requested_root="$1"
    local normalized
    if ! normalized="$(normalize_path "$requested_root" 0)"; then fail 'artifact path is unsafe'; fi
    verify_root "$normalized"
}

select_png() {
    local root="$1"
    shift
    python3 - "$root" "$@" <<'PY'
import os
import stat
import sys
from pathlib import Path
root = Path(sys.argv[1])
for name in sys.argv[2:]:
    if "/" in name or "\\" in name or name in (".", ".."):
        raise SystemExit(1)
    path = root / name
    try:
        info = os.lstat(path)
    except OSError:
        continue
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600:
        raise SystemExit(1)
    print(str(path))
    raise SystemExit(0)
raise SystemExit(1)
PY
}

compare_cases() {
    local before_raw="$1"
    local after_raw="$2"
    local output_raw="$3"
    local before after output
    if ! before="$(normalize_path "$before_raw" 0)"; then fail 'before artifact path is unsafe'; fi
    if ! after="$(normalize_path "$after_raw" 0)"; then fail 'after artifact path is unsafe'; fi
    if ! output="$(normalize_path "$output_raw" 1)"; then fail 'compare output path is unsafe'; fi
    validate_existing_root "$before"
    validate_existing_root "$after"
    if [[ ! -d "$output" ]]; then
        python3 - "$output" <<'PY'
import os
import sys
from pathlib import Path
Path(sys.argv[1]).mkdir(mode=0o700, parents=False)
os.chmod(sys.argv[1], 0o700)
PY
    else
        validate_existing_root "$output"
    fi
    local before_png after_png
    if ! before_png="$(select_png "$before" before.png screenshot.png capture.png after.png xctest-attachment.png)"; then fail 'before screenshot is missing or unsafe'; fi
    if ! after_png="$(select_png "$after" after.png screenshot.png capture.png xctest-attachment.png before.png)"; then fail 'after screenshot is missing or unsafe'; fi
    ensure_direct_destination "$output" "diff.png"
    ensure_direct_destination "$output" "diff.json"
    local private
    private="$(new_private_dir)"
    PRIVATE_DIR="$private"
    trap cleanup_private_dir EXIT
    local executable="$private/phantom-image-diff"
    local compile_log="$private/compile.log"
    python3 - "$compile_log" <<'PY'
import os
import sys
fd = os.open(sys.argv[1], os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
os.close(fd)
PY
    set +e
    xcrun swiftc -framework ImageIO -framework CoreGraphics "$DIFF_SOURCE" -o "$executable" >"$compile_log" 2>&1
    local compile_rc=$?
    set -e
    if [[ "$compile_rc" -ne 0 ]]; then fail 'screenshot diff tool compilation failed'; fi
    local diff_path="$output/.tmp-diff.png"
    local json_path="$output/.tmp-diff.json"
    ensure_direct_destination "$output" ".tmp-diff.png"
    ensure_direct_destination "$output" ".tmp-diff.json"
    set +e
    "$executable" --before "$before_png" --after "$after_png" --diff "$diff_path" --json "$json_path" --mask-top-pixels 0 --threshold 0 >"$private/run.log" 2>&1
    local diff_rc=$?
    set -e
    if [[ "$diff_rc" -ne 0 ]]; then fail 'screenshot comparison failed'; fi
    # Create validated destination placeholders and atomically replace them.
    python3 - "$output/diff.png" "$output/diff.json" <<'PY'
import os
import stat
import sys
from pathlib import Path
for raw in sys.argv[1:]:
    path = Path(raw)
    try:
        info = os.lstat(path)
    except FileNotFoundError:
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        os.close(fd)
        continue
    except OSError:
        raise SystemExit(1)
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        raise SystemExit(1)
PY
    safe_replace_binary "$diff_path" "$output/diff.png"
    safe_replace_text "$json_path" "$output/diff.json"
    normalize_tree_modes "$output"
    python3 - "$output/diff.json" <<'PY'
from pathlib import Path
import sys
print(Path(sys.argv[1]).read_text(encoding="utf-8"), end="")
PY
}

clean_case() {
    local requested_root="$1"
    local normalized
    if ! normalized="$(normalize_path "$requested_root" 0)"; then fail 'artifact path is unsafe'; fi
    python3 - "$normalized" "$SENTINEL_NAME" <<'PY'
import os
import shutil
import stat
import sys
from pathlib import Path
root = Path(sys.argv[1])
sentinel_name = sys.argv[2]
try:
    root_info = os.lstat(root)
except OSError:
    raise SystemExit(1)
if stat.S_ISLNK(root_info.st_mode) or not stat.S_ISDIR(root_info.st_mode) or stat.S_IMODE(root_info.st_mode) != 0o700 or root_info.st_uid != os.getuid():
    raise SystemExit(1)
manifest = root / "run.json"
sentinel = root / sentinel_name
for path in (manifest, sentinel):
    try:
        info = os.lstat(path)
    except OSError:
        raise SystemExit(1)
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600:
        raise SystemExit(1)
if sentinel.read_bytes() != b"phantom-harness-artifact-v1\n":
    raise SystemExit(1)
for current, dirs, files in os.walk(root, topdown=True, followlinks=False):
    for name in dirs + files:
        path = Path(current) / name
        try:
            info = os.lstat(path)
        except OSError:
            raise SystemExit(1)
        if stat.S_ISLNK(info.st_mode):
            raise SystemExit(1)
        if not stat.S_ISDIR(info.st_mode) and not stat.S_ISREG(info.st_mode):
            raise SystemExit(1)
shutil.rmtree(root)
PY
    if [[ $? -ne 0 ]]; then fail 'clean requires a validated direct Phantom artifact root'; fi
}

main() {
    reject_credentials "$@"
    case "${1-}" in
        preflight)
            [[ "$#" -eq 2 ]] || usage
            preflight "$2"
            ;;
        case)
            [[ "$#" -eq 3 ]] || usage
            case_run "$2" "$3"
            ;;
        verify)
            [[ "$#" -eq 2 ]] || usage
            verify_case "$2"
            ;;
        compare)
            [[ "$#" -eq 4 ]] || usage
            compare_cases "$2" "$3" "$4"
            ;;
        clean)
            [[ "$#" -eq 2 ]] || usage
            clean_case "$2"
            ;;
        *)
            usage
            ;;
    esac
}

main "$@"
