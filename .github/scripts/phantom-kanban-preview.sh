#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="${BASH_SOURCE[0]%/*}"
if [[ "$SCRIPT_DIR" == "${BASH_SOURCE[0]}" ]]; then SCRIPT_DIR="."; fi
if [[ "$SCRIPT_DIR" != /* ]]; then SCRIPT_DIR="$PWD/$SCRIPT_DIR"; fi
SCRIPT_DIR="$(cd "$SCRIPT_DIR" && pwd -P)"
export PHOENIX_PREVIEW_SCRIPT_DIR="$SCRIPT_DIR"
exec /usr/bin/python3 - "$@" <<'PY'
import hashlib
import json
import math
import os
import re
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
from pathlib import Path

SCRIPT_DIR = Path(os.environ.get("PHOENIX_PREVIEW_SCRIPT_DIR", "")).resolve()
REPO_ROOT = (SCRIPT_DIR / "../..").resolve()
RENDERER = REPO_ROOT / ".github/scripts/phantom-proposal.sh"
RUNNER = REPO_ROOT / ".github/scripts/phantom-harness.sh"
SYSTEM_PATH = "/usr/bin:/bin:/usr/sbin:/sbin"
MAX_REQUEST_BYTES = 1024 * 1024
MAX_PATCH_BYTES = 128 * 1024 * 1024
MAX_ARTIFACT_BYTES = 128 * 1024 * 1024
MAX_JSON_NODES = 4096
MAX_JSON_DEPTH = 32
EXPECTED_UDID_RE = re.compile(r"[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
SHA1_RE = re.compile(r"[0-9a-fA-F]{40}")
SHA256_RE = re.compile(r"[0-9a-fA-F]{64}")
TICKET_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")
ALLOWED_ARTIFACTS = (
    "proposal.md",
    "evidence-summary.json",
    "proposal-manifest.json",
    "comparison/diff.png",
    "comparison/diff.json",
    "before/run.json",
    "after/run.json",
)
ALLOWED_ARTIFACT_SET = set(ALLOWED_ARTIFACTS)
REASONS = {
    "validate-request": "request validation failed",
    "startup": "preview failed",
    "renderer": "renderer failed",
    "verify-before": "canonical verification failed",
    "verify-after": "canonical verification failed",
    "validate-artifacts": "artifact validation failed",
    "publish": "result publication failed",
    "interrupted": "preview interrupted",
}
NOFOLLOW = getattr(os, "O_NOFOLLOW", 0)
O_DIRECTORY = getattr(os, "O_DIRECTORY", 0)


class PreviewFailure(Exception):
    def __init__(self, stage):
        super().__init__()
        self.stage = stage


class PreviewInterrupted(Exception):
    pass


class State:
    def __init__(self):
        self.stage = "startup"
        self.private_dir = None
        self.result = None
        self.active = None
        self.interrupted = None
        self.failure_written = False
        self.published = False


STATE = State()


def fail(stage):
    raise PreviewFailure(stage)


def safe_name(name):
    if not isinstance(name, str) or not name or name in (".", "..") or "/" in name or "\\" in name:
        fail("validate-artifacts")


def exact_int(value):
    return type(value) is int


def safe_relative(value, allow_empty=False):
    if not isinstance(value, str) or (not allow_empty and not value) or "\x00" in value or "\\" in value:
        fail("validate-artifacts")
    if value.startswith("/") or any(part in ("", ".", "..") for part in value.split("/")):
        fail("validate-artifacts")
    if any(ord(char) < 0x20 or ord(char) == 0x7F for char in value):
        fail("validate-artifacts")
    if len(value) > 512:
        fail("validate-artifacts")
    return value


def normalize_absolute(raw):
    if not isinstance(raw, str) or not raw or "\x00" in raw or "\n" in raw or "\r" in raw or "\\" in raw:
        raise ValueError
    if not os.path.isabs(raw) or os.path.normpath(raw) != raw:
        raise ValueError
    if any(part in ("", ".", "..") for part in raw.split("/")[1:]):
        raise ValueError
    return raw


def canonical_components(raw):
    raw = normalize_absolute(raw)
    if raw == "/":
        raise ValueError
    parts = list(Path(raw).parts[1:])
    if parts and parts[0] == "tmp":
        parts[0:1] = ["private", "tmp"]
    elif parts and parts[0] == "var":
        parts[0:1] = ["private", "var"]
    return parts


def open_directory_path(raw):
    parts = canonical_components(raw)
    current = os.open("/", os.O_RDONLY | O_DIRECTORY | NOFOLLOW)
    try:
        for part in parts:
            safe_name(part)
            next_fd = os.open(part, os.O_RDONLY | O_DIRECTORY | NOFOLLOW, dir_fd=current)
            os.close(current)
            current = next_fd
        return current
    except Exception:
        try:
            os.close(current)
        except OSError:
            pass
        raise


def open_file_path(raw, flags=os.O_RDONLY):
    parts = canonical_components(raw)
    if not parts:
        raise ValueError
    parent = os.open("/", os.O_RDONLY | O_DIRECTORY | NOFOLLOW)
    try:
        for part in parts[:-1]:
            safe_name(part)
            next_fd = os.open(part, os.O_RDONLY | O_DIRECTORY | NOFOLLOW, dir_fd=parent)
            os.close(parent)
            parent = next_fd
        safe_name(parts[-1])
        fd = os.open(parts[-1], flags | NOFOLLOW, dir_fd=parent)
        return parent, fd
    except Exception:
        try:
            os.close(parent)
        except OSError:
            pass
        raise


def file_bytes_from_fd(fd, limit):
    info = os.fstat(fd)
    if info.st_size > limit:
        raise ValueError
    chunks = []
    remaining = info.st_size
    while remaining:
        block = os.read(fd, min(1024 * 1024, remaining))
        if not block:
            raise ValueError
        chunks.append(block)
        remaining -= len(block)
    if os.fstat(fd).st_size != info.st_size:
        raise ValueError
    return b"".join(chunks), info


def same_file_info(left, right):
    return (
        left.st_dev,
        left.st_ino,
        left.st_mode,
        left.st_uid,
        left.st_size,
    ) == (
        right.st_dev,
        right.st_ino,
        right.st_mode,
        right.st_uid,
        right.st_size,
    )


def read_private_path(raw, limit, require_private=True):
    parent_fd, fd = open_file_path(raw, os.O_RDONLY)
    try:
        before = os.fstat(fd)
        if stat.S_ISLNK(before.st_mode) or not stat.S_ISREG(before.st_mode) or before.st_uid != os.getuid():
            raise ValueError
        if require_private and stat.S_IMODE(before.st_mode) & 0o077:
            raise ValueError
        data, opened = file_bytes_from_fd(fd, limit)
        after = os.fstat(fd)
        if not same_file_info(before, opened) or not same_file_info(before, after):
            raise ValueError
        return data
    finally:
        os.close(fd)
        os.close(parent_fd)


def path_inside_worktree(raw, repo):
    try:
        target = Path(raw).resolve(strict=True)
        output = subprocess.run(
            ["git", "-C", str(repo), "worktree", "list", "--porcelain"],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            env={"PATH": SYSTEM_PATH, "LC_ALL": "C", "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": "/dev/null"},
        ).stdout
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError):
        raise ValueError
    for line in output.splitlines():
        if not line.startswith("worktree "):
            continue
        root = Path(line[9:]).resolve()
        if target == root or root in target.parents:
            return True
    return False


def no_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError
        result[key] = value
    return result


def parse_json_bytes(data):
    return json.loads(data.decode("utf-8"), object_pairs_hook=no_duplicate_keys)


def worktree_paths(repo):
    try:
        output = subprocess.run(
            ["git", "-C", str(repo), "worktree", "list", "--porcelain"],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            env={"PATH": SYSTEM_PATH, "LC_ALL": "C", "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": "/dev/null"},
        ).stdout
    except (OSError, subprocess.SubprocessError):
        raise ValueError
    roots = []
    for line in output.splitlines():
        if line.startswith("worktree "):
            roots.append(Path(line[9:]).resolve())
    if not roots:
        raise ValueError
    return roots


def make_private_dir():
    path = Path(tempfile.mkdtemp(prefix="phantom-kanban-preview-", dir="/tmp"))
    os.chmod(path, 0o700)
    info = os.lstat(path)
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
        raise ValueError
    for name in ("home", "tmp", "proposal"):
        os.mkdir(path / name, mode=0o700)
        os.chmod(path / name, 0o700)
    return path


def cleanup_private():
    path = STATE.private_dir
    if path is None:
        return
    try:
        info = os.lstat(path)
        if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid():
            return
        shutil.rmtree(path)
    except BaseException:
        return


def make_private_file(path, data, mode=0o600):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | NOFOLLOW, mode)
    try:
        view = memoryview(data)
        while view:
            written = os.write(fd, view)
            view = view[written:]
        os.fchmod(fd, mode)
        os.fsync(fd)
    finally:
        os.close(fd)


def validate_result_root(raw):
    try:
        normalized = normalize_absolute(raw)
        if path_inside_worktree(normalized, REPO_ROOT):
            raise ValueError
        fd = open_directory_path(normalized)
        info = os.fstat(fd)
        if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700 or list(os.listdir(fd)):
            os.close(fd)
            raise ValueError
        return ResultDirectory(normalized, fd, info)
    except (OSError, ValueError, RuntimeError):
        fail("startup")


class ResultDirectory:
    def __init__(self, path, fd, info):
        self.path = path
        self.fd = fd
        self.identity = (info.st_dev, info.st_ino)

    def close(self):
        if self.fd is not None:
            try:
                os.close(self.fd)
            except OSError:
                pass
            self.fd = None

    def current(self):
        fd = open_directory_path(self.path)
        try:
            info = os.fstat(fd)
            if (info.st_dev, info.st_ino) != self.identity or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
                raise ValueError
            return fd
        except Exception:
            os.close(fd)
            raise


def parse_request(request_raw):
    try:
        data = read_private_path(request_raw, MAX_REQUEST_BYTES)
        payload = parse_json_bytes(data)
        if not isinstance(payload, dict) or set(payload) != {"schema_version", "ticket_id", "fixture", "patch_file", "trusted_input", "expected"}:
            raise ValueError
        if not exact_int(payload["schema_version"]) or payload["schema_version"] != 1:
            raise ValueError
        if not isinstance(payload["ticket_id"], str) or not TICKET_RE.fullmatch(payload["ticket_id"]):
            raise ValueError
        if payload["fixture"] != "just-lift-connected" or payload["trusted_input"] is not True:
            raise ValueError
        expected = payload["expected"]
        if not isinstance(expected, dict) or set(expected) != {"screen", "markers"}:
            raise ValueError
        if expected["screen"] != "just-lift" or expected["markers"] != ["xctest.passed", "phantom.connected"]:
            raise ValueError
        patch_raw = payload["patch_file"]
        normalize_absolute(patch_raw)
        if path_inside_worktree(patch_raw, REPO_ROOT):
            raise ValueError
        patch_data = read_private_path(patch_raw, MAX_PATCH_BYTES - 1)
        if not patch_data:
            raise ValueError
        return payload["ticket_id"], patch_raw, patch_data
    except (OSError, UnicodeError, ValueError, json.JSONDecodeError, RuntimeError):
        fail("validate-request")


def current_head():
    try:
        output = subprocess.run(
            ["git", "-C", str(REPO_ROOT), "rev-parse", "--verify", "HEAD"],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            env={"PATH": SYSTEM_PATH, "LC_ALL": "C", "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": "/dev/null"},
        ).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        fail("validate-request")
    if not SHA1_RE.fullmatch(output):
        fail("validate-request")
    return output.lower()


def snapshot_patch(data):
    path = STATE.private_dir / "patch.snapshot"
    make_private_file(path, data, 0o400)
    return path, hashlib.sha256(data).hexdigest()


def valid_optional_env(value):
    if not isinstance(value, str) or not value or "\x00" in value or "\n" in value or "\r" in value or "\\" in value:
        return False
    if not os.path.isabs(value) or os.path.normpath(value) != value:
        return False
    try:
        fd = open_directory_path(value)
        info = os.fstat(fd)
        os.close(fd)
        return info.st_uid == os.getuid()
    except (OSError, ValueError):
        return False


def child_environment():
    env = {
        "PATH": SYSTEM_PATH,
        "HOME": str(STATE.private_dir / "home"),
        "TMPDIR": str(STATE.private_dir / "tmp"),
        "LC_ALL": "C",
        "PHOENIX_PROPOSAL_TRUSTED_INPUT": "1",
        "PHOENIX_HARNESS_ALLOW_DESTRUCTIVE": "1",
        "PHOENIX_HARNESS_UDID": os.environ["PHOENIX_HARNESS_UDID"],
    }
    for name in ("JAVA_HOME", "DEVELOPER_DIR"):
        value = os.environ.get(name)
        if value and valid_optional_env(value):
            env[name] = value
    return env


def kill_active(signum):
    proc = STATE.active
    if proc is None:
        return
    try:
        if proc.poll() is None:
            os.killpg(proc.pid, signum)
    except OSError:
        pass


def on_signal(signum, _frame):
    if STATE.interrupted is None:
        STATE.interrupted = signum
        kill_active(signum)
    else:
        kill_active(signal.SIGKILL)


def run_child(command, log_name, env):
    if STATE.interrupted is not None:
        raise PreviewInterrupted
    log_path = STATE.private_dir / log_name
    try:
        log_fd = os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | NOFOLLOW, 0o600)
        os.fchmod(log_fd, 0o600)
        stream = os.fdopen(log_fd, "wb", closefd=True)
        proc = subprocess.Popen(
            [str(value) for value in command],
            cwd=str(REPO_ROOT),
            env=env,
            stdin=subprocess.DEVNULL,
            stdout=stream,
            stderr=subprocess.STDOUT,
            start_new_session=True,
            close_fds=True,
        )
    except (OSError, ValueError):
        try:
            stream.close()
        except UnboundLocalError:
            pass
        raise PreviewFailure(STATE.stage)
    STATE.active = proc
    rc = None
    try:
        while True:
            rc = proc.poll()
            if rc is not None:
                break
            if STATE.interrupted is not None:
                kill_active(signal.SIGTERM)
                try:
                    rc = proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    kill_active(signal.SIGKILL)
                    rc = proc.wait()
                break
            time.sleep(0.02)
    finally:
        if STATE.interrupted is not None and proc.poll() is None:
            kill_active(signal.SIGKILL)
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
        STATE.active = None
        stream.close()
    if STATE.interrupted is not None:
        raise PreviewInterrupted
    if rc != 0:
        raise PreviewFailure(STATE.stage)


def artifact_child_environment():
    return child_environment()


def lstat_at(directory_fd, name):
    return os.stat(name, dir_fd=directory_fd, follow_symlinks=False)


def read_artifact_file(directory_fd, name, info):
    fd = os.open(name, os.O_RDONLY | NOFOLLOW, dir_fd=directory_fd)
    try:
        opened = os.fstat(fd)
        if stat.S_ISLNK(opened.st_mode) or not stat.S_ISREG(opened.st_mode) or opened.st_uid != os.getuid() or stat.S_IMODE(opened.st_mode) != 0o600 or not same_file_info(info, opened):
            raise ValueError
        data, _ = file_bytes_from_fd(fd, MAX_ARTIFACT_BYTES)
        after = os.fstat(fd)
        if not same_file_info(info, after) or not same_file_info(info, lstat_at(directory_fd, name)):
            raise ValueError
        return data
    finally:
        os.close(fd)


def scan_artifact_tree(fd, relative=""):
    contents = {}
    try:
        names = sorted(os.listdir(fd))
    except OSError:
        raise ValueError
    for name in names:
        safe_name(name)
        info = lstat_at(fd, name)
        if info.st_uid != os.getuid() or stat.S_ISLNK(info.st_mode):
            raise ValueError
        rel = f"{relative}/{name}" if relative else name
        if stat.S_ISDIR(info.st_mode):
            if stat.S_IMODE(info.st_mode) != 0o700:
                raise ValueError
            child = os.open(name, os.O_RDONLY | O_DIRECTORY | NOFOLLOW, dir_fd=fd)
            try:
                contents.update(scan_artifact_tree(child, rel))
            finally:
                os.close(child)
        elif stat.S_ISREG(info.st_mode):
            if stat.S_IMODE(info.st_mode) != 0o600 or rel not in ALLOWED_ARTIFACT_SET:
                raise ValueError
            contents[rel] = read_artifact_file(fd, name, info)
        else:
            raise ValueError
    return contents


def credential_or_host_path(data):
    text = data.decode("utf-8", "replace")
    if re.search(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----", text, re.IGNORECASE):
        return True
    if re.search(r"\b(?:gh[pousr]|github_pat|glpat|xox[baprs]|sk|rk)[_-][A-Za-z0-9_./=-]{20,}\b", text, re.IGNORECASE):
        return True
    if re.search(r"\bAKIA[0-9A-Z]{16}\b", text):
        return True
    if re.search(r"\bBearer\s+[A-Za-z0-9._~+/=-]{20,}", text, re.IGNORECASE):
        return True
    if re.search(r"\b[A-Za-z0-9_.-]*(?:token|secret|password|passwd|private[_-]?key|credential|api[_-]?key|api[_-]?token|access[_-]?key|authorization|anon[_-]?key)[A-Za-z0-9_.-]*\s*[:=]\s*['\"]?[A-Za-z0-9._~+/=-]{8,}", text, re.IGNORECASE):
        return True
    if re.search(r"/(?:Users|private|tmp|var|home|Volumes|Applications|System|Library|opt|etc|usr)(?:/|$)", text):
        return True
    if re.search(r"(?<![:/A-Za-z0-9_])/(?!/)[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*", text):
        return True
    return False


def bounded_walk(value, depth=0, seen=None):
    if seen is None:
        seen = [0]
    seen[0] += 1
    if seen[0] > MAX_JSON_NODES or depth > MAX_JSON_DEPTH:
        raise ValueError
    if isinstance(value, dict):
        if len(value) > MAX_JSON_NODES:
            raise ValueError
        for key, item in value.items():
            if not isinstance(key, str) or len(key) > 256:
                raise ValueError
            bounded_walk(item, depth + 1, seen)
    elif isinstance(value, list):
        if len(value) > MAX_JSON_NODES:
            raise ValueError
        for item in value:
            bounded_walk(item, depth + 1, seen)
    elif isinstance(value, str):
        if len(value) > MAX_ARTIFACT_BYTES or credential_or_host_path(value.encode("utf-8")):
            raise ValueError
    elif value is not None and not isinstance(value, (str, int, float, bool)):
        raise ValueError
    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError


def validate_json_artifact(data, require_object=True):
    if credential_or_host_path(data):
        raise ValueError
    value = parse_json_bytes(data)
    if require_object and type(value) is not dict:
        raise ValueError
    bounded_walk(value)
    return value


def validate_ref_field(value):
    safe_relative(value)
    if value.startswith("/"):
        raise ValueError


def validate_hash(value, length):
    if not isinstance(value, str) or not (SHA1_RE if length == 40 else SHA256_RE).fullmatch(value):
        raise ValueError


def validate_identity(identity):
    allowed = {"baseSha", "fixtureId", "fixtureSha256", "bundleId", "simulator", "commands", "markers"}
    if not isinstance(identity, dict) or set(identity) - allowed:
        raise ValueError
    if "baseSha" in identity:
        validate_hash(identity["baseSha"], 40)
    if "fixtureSha256" in identity:
        validate_hash(identity["fixtureSha256"], 64)
    for key in ("fixtureId", "bundleId"):
        if key in identity:
            if not isinstance(identity[key], str):
                raise ValueError
            safe_relative(identity[key])
    if "simulator" in identity:
        simulator = identity["simulator"]
        if not isinstance(simulator, dict) or set(simulator) - {"udid", "name", "runtime", "state"}:
            raise ValueError
        if "udid" in simulator and (not isinstance(simulator["udid"], str) or not EXPECTED_UDID_RE.fullmatch(simulator["udid"])):
            raise ValueError
        for key in ("name", "runtime", "state"):
            if key in simulator and not isinstance(simulator[key], str):
                raise ValueError
    for key in ("commands", "markers"):
        if key in identity:
            value = identity[key]
            if not isinstance(value, list) or len(value) > 64 or any(not isinstance(item, str) for item in value):
                raise ValueError


def validate_capture(value):
    if not isinstance(value, dict) or set(value) - {"path", "sha256", "dimensions"}:
        raise ValueError
    if "path" in value:
        validate_ref_field(value["path"])
    if "sha256" in value:
        validate_hash(value["sha256"], 64)
    if "dimensions" in value:
        dimensions = value["dimensions"]
        if not isinstance(dimensions, dict) or set(dimensions) - {"width", "height"}:
            raise ValueError
        for key in ("width", "height"):
            if key in dimensions and (not exact_int(dimensions[key]) or not 1 <= dimensions[key] <= 100000):
                raise ValueError


def validate_manifest(manifest, base_sha):
    known = {
        "schemaVersion", "status", "trustedInput", "fixture", "baseSha", "patch", "candidateKinds",
        "allowedChangedFiles", "actualChangedFiles", "worktree", "focusedChecks", "before", "after",
        "comparison", "evidence",
    }
    if not isinstance(manifest, dict) or set(manifest) - known:
        raise ValueError
    if not exact_int(manifest.get("schemaVersion")) or manifest["schemaVersion"] != 1:
        raise ValueError
    if manifest.get("status") != "passed" or manifest.get("fixture") != "just-lift-connected":
        raise ValueError
    if "trustedInput" in manifest and manifest["trustedInput"] is not True:
        raise ValueError
    if "baseSha" not in manifest:
        raise ValueError
    validate_hash(manifest["baseSha"], 40)
    if manifest["baseSha"].lower() != base_sha.lower():
        raise ValueError
    if "patch" in manifest:
        patch = manifest["patch"]
        if not isinstance(patch, dict) or set(patch) - {"path", "sha256", "size", "binary", "format"}:
            raise ValueError
        for key in ("path", "sha256", "size", "binary", "format"):
            if key not in patch:
                raise ValueError
        if patch["path"] != "proposal.patch":
            validate_ref_field(patch["path"])
        validate_hash(patch["sha256"], 64)
        if not exact_int(patch["size"]) or patch["size"] < 0 or patch["size"] > MAX_PATCH_BYTES:
            raise ValueError
        if patch["binary"] is not True and patch["binary"] is not False:
            raise ValueError
        if patch["format"] != "exact-input":
            raise ValueError
    for key in ("candidateKinds", "allowedChangedFiles", "actualChangedFiles"):
        if key in manifest:
            value = manifest[key]
            if not isinstance(value, list) or len(value) > 512:
                raise ValueError
            for item in value:
                safe_relative(item)
    if "worktree" in manifest:
        worktree = manifest["worktree"]
        if not isinstance(worktree, dict) or set(worktree) - {"baseSha", "headSha", "detached", "uncommitted", "statusEntryCount", "appliedDiffSha256"}:
            raise ValueError
        if "baseSha" in worktree:
            validate_hash(worktree["baseSha"], 40)
        if "headSha" in worktree:
            validate_hash(worktree["headSha"], 40)
        for key in ("detached", "uncommitted"):
            if key in worktree and type(worktree[key]) is not bool:
                raise ValueError
        if "statusEntryCount" in worktree and (not exact_int(worktree["statusEntryCount"]) or worktree["statusEntryCount"] < 0):
            raise ValueError
        if "appliedDiffSha256" in worktree:
            validate_hash(worktree["appliedDiffSha256"], 64)
    if "focusedChecks" in manifest:
        checks = manifest["focusedChecks"]
        if not isinstance(checks, list) or len(checks) > 64:
            raise ValueError
        for check in checks:
            if not isinstance(check, dict) or set(check) - {"name", "passed"} or not isinstance(check.get("name"), str) or type(check.get("passed")) is not bool:
                raise ValueError
    for key in ("before", "after"):
        if key in manifest:
            value = manifest[key]
            if not isinstance(value, dict) or set(value) - {"artifact", "manifestSha256", "identity"}:
                raise ValueError
            if "artifact" in value:
                validate_ref_field(value["artifact"])
            if "manifestSha256" in value:
                validate_hash(value["manifestSha256"], 64)
            if "identity" in value:
                validate_identity(value["identity"])
    if "evidence" in manifest:
        evidence = manifest["evidence"]
        if not isinstance(evidence, dict) or set(evidence) - {"proposalMarkdown", "summaryJson"}:
            raise ValueError
        for value in evidence.values():
            validate_ref_field(value)
    if "comparison" in manifest:
        comparison = manifest["comparison"]
        if not isinstance(comparison, dict) or set(comparison) - {"before", "after", "diffJson", "diffImage", "summary"}:
            raise ValueError
        for key in ("diffJson", "diffImage"):
            if key in comparison:
                value = comparison[key]
                if not isinstance(value, dict) or set(value) - {"path", "sha256", "dimensions"}:
                    raise ValueError
                if "path" in value:
                    validate_ref_field(value["path"])
                if "sha256" in value:
                    validate_hash(value["sha256"], 64)
        for key in ("before", "after"):
            if key in comparison:
                validate_capture(comparison[key])
        if "summary" in comparison:
            summary = comparison["summary"]
            if not isinstance(summary, dict) or set(summary) - {"dimensions", "width", "height", "passed", "thresholdPassed", "changedPixels", "changedPixelRatio", "changedRatio", "meanChannelDelta", "maxChannelDelta", "maskTopPixels", "threshold"}:
                raise ValueError
            if "dimensions" in summary:
                validate_capture({"dimensions": summary["dimensions"]})
            for key in ("width", "height"):
                if key in summary and (not exact_int(summary[key]) or not 1 <= summary[key] <= 100000):
                    raise ValueError
            for key in ("passed", "thresholdPassed"):
                if key in summary and type(summary[key]) is not bool:
                    raise ValueError
            for key in ("changedPixelRatio", "changedRatio", "meanChannelDelta", "maxChannelDelta", "threshold"):
                if key in summary and (type(summary[key]) not in (int, float) or isinstance(summary[key], bool) or not math.isfinite(float(summary[key]))):
                    raise ValueError
            if "changedPixels" in summary and (not exact_int(summary["changedPixels"]) or summary["changedPixels"] < 0):
                raise ValueError
            if "maskTopPixels" in summary and (not exact_int(summary["maskTopPixels"]) or summary["maskTopPixels"] < 0):
                raise ValueError
    bounded_walk(manifest)


def validate_png(data):
    if len(data) < 33 or data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError
    cursor = 8
    seen_header = False
    seen_end = False
    while cursor < len(data):
        if cursor + 12 > len(data):
            raise ValueError
        length = int.from_bytes(data[cursor:cursor + 4], "big")
        if length > MAX_ARTIFACT_BYTES or cursor + 12 + length > len(data):
            raise ValueError
        kind = data[cursor + 4:cursor + 8]
        payload = data[cursor + 8:cursor + 8 + length]
        checksum = int.from_bytes(data[cursor + 8 + length:cursor + 12 + length], "big")
        if zlib_crc(kind + payload) != checksum:
            raise ValueError
        if kind == b"IHDR":
            if seen_header or length != 13:
                raise ValueError
            width = int.from_bytes(payload[0:4], "big")
            height = int.from_bytes(payload[4:8], "big")
            if not 1 <= width <= 100000 or not 1 <= height <= 100000:
                raise ValueError
            seen_header = True
        elif kind == b"IEND":
            if length != 0 or not seen_header or seen_end or cursor + 12 != len(data):
                raise ValueError
            seen_end = True
        cursor += 12 + length
    if not seen_end:
        raise ValueError


def zlib_crc(data):
    import zlib
    return zlib.crc32(data) & 0xFFFFFFFF


def validate_artifacts():
    try:
        root_fd = open_directory_path(str(STATE.private_dir / "proposal"))
        try:
            info = os.fstat(root_fd)
            if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
                raise ValueError
            artifacts = scan_artifact_tree(root_fd)
        finally:
            os.close(root_fd)
        if set(artifacts) != ALLOWED_ARTIFACT_SET:
            raise ValueError
        for name, data in artifacts.items():
            if name == "comparison/diff.png":
                if credential_or_host_path(data):
                    raise ValueError
                validate_png(data)
            elif name == "proposal.md":
                data.decode("utf-8", "strict")
                if credential_or_host_path(data):
                    raise ValueError
            else:
                value = validate_json_artifact(data)
                if name == "proposal-manifest.json":
                    validate_manifest(value, STATE.base_sha)
        return artifacts
    except (OSError, UnicodeError, ValueError, json.JSONDecodeError, RuntimeError):
        fail("validate-artifacts")


def safe_remove_entry(directory_fd, name):
    info = lstat_at(directory_fd, name)
    if stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode):
        child = os.open(name, os.O_RDONLY | O_DIRECTORY | NOFOLLOW, dir_fd=directory_fd)
        try:
            for child_name in os.listdir(child):
                safe_name(child_name)
                safe_remove_entry(child, child_name)
        finally:
            os.close(child)
        os.rmdir(name, dir_fd=directory_fd)
    elif stat.S_ISREG(info.st_mode) or stat.S_ISLNK(info.st_mode):
        os.unlink(name, dir_fd=directory_fd)
    else:
        raise ValueError


def clean_result_root(directory_fd):
    for name in os.listdir(directory_fd):
        safe_name(name)
        safe_remove_entry(directory_fd, name)


def create_result_file(directory_fd, name, data):
    safe_name(name)
    fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | NOFOLLOW, 0o600, dir_fd=directory_fd)
    try:
        view = memoryview(data)
        while view:
            written = os.write(fd, view)
            view = view[written:]
        os.fchmod(fd, 0o600)
        os.fsync(fd)
    finally:
        os.close(fd)


def mkdir_result_dir(directory_fd, name):
    safe_name(name)
    os.mkdir(name, mode=0o700, dir_fd=directory_fd)
    fd = os.open(name, os.O_RDONLY | O_DIRECTORY | NOFOLLOW, dir_fd=directory_fd)
    info = os.fstat(fd)
    if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
        os.close(fd)
        raise ValueError
    return fd


def json_result(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


def write_failure_result(stage):
    if STATE.result is None or STATE.failure_written:
        return
    STATE.failure_written = True
    try:
        current = os.dup(STATE.result.fd)
        info = os.fstat(current)
        if (info.st_dev, info.st_ino) != STATE.result.identity or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
            raise ValueError
        try:
            clean_result_root(current)
            payload = {"schema_version": 1, "status": "failed", "stage": stage, "reason": REASONS.get(stage, "preview failed")}
            create_result_file(current, "preview-result.json", json_result(payload))
        finally:
            os.close(current)
    except BaseException:
        return


def publish_success(artifacts, ticket, fixture, base_sha, patch_sha):
    if STATE.interrupted is not None:
        raise PreviewInterrupted
    destination = None
    dirs = {}
    try:
        destination = STATE.result.current()
        if os.listdir(destination):
            raise ValueError
        for directory in ("comparison", "before", "after"):
            dirs[directory] = mkdir_result_dir(destination, directory)
        entries = []
        for name in ALLOWED_ARTIFACTS:
            if STATE.interrupted is not None:
                raise PreviewInterrupted
            if "/" in name:
                directory, leaf = name.split("/", 1)
                parent = dirs[directory]
            else:
                parent, leaf = destination, name
            create_result_file(parent, leaf, artifacts[name])
            entries.append({"path": name, "sha256": hashlib.sha256(artifacts[name]).hexdigest()})
        result = {
            "schema_version": 1,
            "status": "passed",
            "ticket_id": ticket,
            "fixture": fixture,
            "base_sha": base_sha.lower(),
            "patch_sha256": patch_sha,
            "artifacts": entries,
        }
        create_result_file(destination, "preview-result.json", json_result(result))
        if STATE.interrupted is not None:
            raise PreviewInterrupted
        current = STATE.result.current()  # Detect a result-root replacement during publication.
        os.close(current)
    except PreviewInterrupted:
        raise
    except (OSError, ValueError, RuntimeError):
        raise PreviewFailure("publish")
    finally:
        for fd in dirs.values():
            try:
                os.close(fd)
            except OSError:
                pass
        if destination is not None:
            try:
                os.close(destination)
            except OSError:
                pass
    if STATE.interrupted is not None:
        raise PreviewInterrupted
    STATE.published = True


def main_work(request_raw, result_raw):
    STATE.stage = "startup"
    STATE.result = validate_result_root(result_raw)
    STATE.private_dir = make_private_dir()
    STATE.stage = "validate-request"
    ticket, patch_raw, patch_data = parse_request(request_raw)
    STATE.base_sha = current_head()
    patch_snapshot, patch_sha = snapshot_patch(patch_data)
    if not EXPECTED_UDID_RE.fullmatch(os.environ.get("PHOENIX_HARNESS_UDID", "")):
        fail("renderer")
    env = child_environment()
    STATE.stage = "renderer"
    run_child([RENDERER, "render", STATE.private_dir / "proposal", "just-lift-connected", patch_snapshot], "renderer.log", env)
    if STATE.interrupted is not None:
        raise PreviewInterrupted
    STATE.stage = "verify-before"
    run_child([RUNNER, "verify", STATE.private_dir / "proposal" / "before"], "before-verify.log", env)
    STATE.stage = "verify-after"
    run_child([RUNNER, "verify", STATE.private_dir / "proposal" / "after"], "after-verify.log", env)
    STATE.stage = "validate-artifacts"
    artifacts = validate_artifacts()
    if current_head() != STATE.base_sha:
        fail("validate-artifacts")
    STATE.stage = "publish"
    publish_success(artifacts, ticket, "just-lift-connected", STATE.base_sha, patch_sha)


def main():
    if len(sys.argv) != 3:
        sys.stderr.write("phantom-kanban-preview: usage error\n")
        return 2
    for signum in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, on_signal)
    exit_code = 0
    failure_stage = None
    try:
        main_work(sys.argv[1], sys.argv[2])
    except PreviewInterrupted:
        failure_stage = "interrupted"
        exit_code = 1
    except PreviewFailure as error:
        failure_stage = error.stage
        exit_code = 1
    except (BaseException,):
        failure_stage = STATE.stage if STATE.stage in REASONS else "startup"
        exit_code = 1
    finally:
        if failure_stage is not None and not STATE.published:
            write_failure_result(failure_stage)
        if STATE.active is not None:
            kill_active(signal.SIGKILL)
            try:
                STATE.active.wait(timeout=5)
            except (OSError, subprocess.TimeoutExpired):
                pass
            STATE.active = None
        cleanup_private()
        if STATE.result is not None:
            STATE.result.close()
    if exit_code:
        sys.stderr.write("phantom-kanban-preview: preview failed\n")
    return exit_code


raise SystemExit(main())
PY
