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
EXPECTED_COMMANDS = (
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
)
EXPECTED_MARKERS = ("xctest.passed", "phantom.connected", "simulator.screenshot")
EXPECTED_FIXTURE_SHA256 = "e180679548a2d96dbc59c51449edb3b99c19d3e3be82eca98c0707a21a64e78e"
EXPECTED_TEXTUAL_ARTIFACTS = (
    "toolchain.log", "build.log", "test.log", "app-state.log", "simulator.log", "screenshot.log", ".commands.jsonl",
)
EXPECTED_HARNESS_MARKER = b"phantom-harness-artifact-v1\n"
EXPECTED_PROPOSAL_MARKER = b"phantom-proposal-artifact-v1\n"
MAX_PROPOSAL_MARKER_BYTES = 128
INTERNAL_HARNESS_FILES = (
    ".phantom-harness",
    ".commands.jsonl",
    "run.json",
    "after.png",
    "xctest-attachment.png",
    "toolchain.log",
    "boot.log",
    "bootstatus.log",
    "terminate.log",
    "uninstall.log",
    "build.log",
    "test.log",
    "app-state.log",
    "simulator.log",
    "screenshot.log",
)
INTERNAL_LOG_FILES = {name for name in INTERNAL_HARNESS_FILES if name.endswith(".log") or name == ".commands.jsonl"}
INTERNAL_ARTIFACT_SET = ALLOWED_ARTIFACT_SET | {"proposal.patch", ".phantom-proposal"} | {
    f"{phase}/{name}" for phase in ("before", "after") for name in INTERNAL_HARNESS_FILES
}
PATCH_ALLOWED_PREFIXES = (
    "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/",
    "shared/src/commonMain/composeResources/",
    "iosApp/VitruvianPhoenix/VitruvianPhoenix/",
)
PATCH_RESOURCE_EXTENSIONS = {
    ".json", ".jpg", ".jpeg", ".gif", ".mp3", ".m4a", ".otf", ".properties", ".png",
    ".strings", ".stringsdict", ".svg", ".ttf", ".wav", ".webp", ".xml",
}
HOST_PATH_ROOTS = (
    "Users", "private", "tmp", "var", "home", "Volumes", "Applications", "System", "Library", "opt", "etc", "usr",
    "bin", "sbin", "dev", "root", "run", "proc", "sys",
)
HOST_PATH_ROOT_RE = re.compile(r"(?<![A-Za-z0-9_.])/(?:" + "|".join(HOST_PATH_ROOTS) + r")(?:/|$)")
PNG_HOST_PATH_ROOT_RE = re.compile(r"(?<![A-Za-z0-9_.:/-])/(?:" + "|".join(HOST_PATH_ROOTS) + r")(?:/|$)")
PNG_GENERIC_HOST_PATH_RE = re.compile(
    r"(?<![A-Za-z0-9_.:/-])/(?:[A-Za-z0-9._-]+/)+[A-Za-z0-9._-]+(?=$|[\s\"'<>])"
)
PATCH_HOST_PATH_RE = HOST_PATH_ROOT_RE
PATCH_CREDENTIAL_RE = re.compile(
    r"(?im)^[+\-]?\s*(?:export\s+)?(?:SUPABASE_ANON_KEY|API_TOKEN|TOKEN|SECRET|PASSWORD|PASSWD|"
    r"PRIVATE[_-]?KEY|CREDENTIAL|API[_-]?KEY|ANON[_-]?KEY|AUTHORIZATION)"
    r"\s*[:=]\s*['\"]?[A-Za-z0-9._~+/=-]{16,}"
)
MAX_INTERNAL_FILES = 128
MAX_INTERNAL_BYTES = 512 * 1024 * 1024
KNOWN_ARTIFACT_REFERENCES = ALLOWED_ARTIFACT_SET | {"before", "after", "proposal.patch"}
PUBLICATION_STAGING_NAME = ".publication-staging"
PUBLICATION_PRECHECK_NAME = ".publication-precheck"
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
        self.patch_sha = None
        self.patch_size = None


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


def path_inside_any_worktree(raw, roots):
    try:
        target = Path(raw).resolve(strict=True)
    except (OSError, RuntimeError, ValueError):
        raise ValueError
    return any(target == root or root in target.parents for root in roots)


def validate_external_path(raw, roots):
    normalized = normalize_absolute(raw)
    if path_inside_any_worktree(normalized, roots):
        raise ValueError
    return normalized


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
        if path_inside_any_worktree(normalized, worktree_paths(REPO_ROOT)):
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
        roots = worktree_paths(REPO_ROOT)
        request_path = validate_external_path(request_raw, roots)
        data = read_private_path(request_path, MAX_REQUEST_BYTES)
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
        patch_path = validate_external_path(patch_raw, roots)
        patch_data = read_private_path(patch_path, MAX_PATCH_BYTES - 1)
        if not patch_data:
            raise ValueError
        validate_proposal_patch(patch_data)
        return payload["ticket_id"], patch_path, patch_data
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
    digest = hashlib.sha256(data).hexdigest()
    STATE.patch_sha = digest
    STATE.patch_size = len(data)
    return path, digest


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


def check_interrupted():
    if STATE.interrupted is not None:
        raise PreviewInterrupted


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


def scan_artifact_tree(fd, relative="", allowed_paths=None, total_size=None, directories=None):
    contents = {}
    if allowed_paths is None:
        allowed_paths = ALLOWED_ARTIFACT_SET
    if total_size is None:
        total_size = [0]
    if directories is None:
        directories = []
    directories.append(relative)
    try:
        names = sorted(os.listdir(fd))
    except OSError:
        raise ValueError
    if len(names) > MAX_INTERNAL_FILES:
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
                contents.update(scan_artifact_tree(child, rel, allowed_paths, total_size, directories))
            finally:
                os.close(child)
        elif stat.S_ISREG(info.st_mode):
            if stat.S_IMODE(info.st_mode) != 0o600 or rel not in allowed_paths:
                raise ValueError
            data = read_artifact_file(fd, name, info)
            total_size[0] += len(data)
            if total_size[0] > MAX_INTERNAL_BYTES:
                raise ValueError
            contents[rel] = data
        else:
            raise ValueError
    return contents


def credential_detected(data):
    text = data.decode("utf-8", "replace")
    if re.search(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----", text, re.IGNORECASE):
        return True
    if re.search(r"\b(?:gh[pousr]|github_pat|glpat|xox[baprs]|sk|rk)[_-][A-Za-z0-9_./=-]{20,}\b", text, re.IGNORECASE):
        return True
    if re.search(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b", text):
        return True
    if re.search(r"\bBearer\s+[A-Za-z0-9._~+/=-]{20,}", text, re.IGNORECASE):
        return True
    # CoreSimulator uses bare token:<object-or-URL> fields for UIKit/BackBoard
    # bookkeeping. Require an explicit credential name and a substantive payload
    # before treating an assignment as secret material.
    if re.search(
        r"\b[A-Za-z0-9_.-]*(?:api[_-]?(?:key|token|secret)|access[_-]?key|anon[_-]?key|"
        r"client[_-]?(?:secret|token)|private[_-]?key|refresh[_-]?token)[A-Za-z0-9_.-]*"
        r"\s*[:=]\s*['\"]?[A-Za-z0-9._~+/=-]{20,}",
        text,
        re.IGNORECASE,
    ):
        return True
    if re.search(
        r"\b(?:token|secret|password|passwd|credential|authorization)\s*=\s*['\"]?[A-Za-z0-9._~+/=-]{20,}",
        text,
        re.IGNORECASE,
    ):
        return True
    return False


def host_path_detected(data):
    text = data.decode("utf-8", "replace")
    if HOST_PATH_ROOT_RE.search(text):
        return True
    if re.search(r"(?<![:/A-Za-z0-9_])/(?!/)[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*", text):
        return True
    return False


def png_host_path_detected(data):
    text = data.decode("utf-8", "replace")
    return bool(PNG_HOST_PATH_ROOT_RE.search(text) or PNG_GENERIC_HOST_PATH_RE.search(text))


def credential_or_host_path(data):
    return credential_detected(data) or host_path_detected(data)


def validate_patch_file_path(value):
    if (
        not isinstance(value, str)
        or not value
        or "\x00" in value
        or "\\" in value
        or value.startswith("/")
        or any(part in ("", ".", "..") for part in value.split("/"))
    ):
        raise ValueError
    if not any(value.startswith(prefix) for prefix in PATCH_ALLOWED_PREFIXES):
        raise ValueError
    components = [component.lower() for component in value.split("/")]
    basename = components[-1]
    if (
        any(component in {"config", "configs", "configuration", "profile", "profiles", "ci", "harness", "gradle", "build", "deriveddata", "xcuserdata", "pods"} for component in components)
        or any(word in basename for word in ("config", "harness", "runner"))
        or basename in {"build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradle.properties", "info.plist"}
    ):
        raise ValueError
    suffix = Path(value).suffix.lower()
    if value.startswith(PATCH_ALLOWED_PREFIXES[0]):
        if suffix != ".kt":
            raise ValueError
    elif value.startswith(PATCH_ALLOWED_PREFIXES[1]):
        if suffix not in PATCH_RESOURCE_EXTENSIONS:
            raise ValueError
    elif suffix != ".swift" and suffix not in PATCH_RESOURCE_EXTENSIONS:
        raise ValueError
    return value


def validate_patch_header_path(value, prefix):
    value = value.split("\t", 1)[0]
    if value == "/dev/null":
        return None
    if not value.startswith(prefix):
        raise ValueError
    return validate_patch_file_path(value[len(prefix):])


def patch_host_path_detected(text):
    for line in text.splitlines():
        if line.startswith(("--- ", "+++ ")) and line[4:].split("\t", 1)[0] == "/dev/null":
            continue
        if PATCH_HOST_PATH_RE.search(line):
            return True
    return False


def validate_proposal_patch(data):
    if not isinstance(data, bytes) or not data or len(data) > MAX_PATCH_BYTES or not data.endswith(b"\n"):
        raise ValueError
    try:
        text = data.decode("utf-8", "strict")
    except UnicodeError:
        raise ValueError
    if "\r" in text or credential_detected(data) or PATCH_CREDENTIAL_RE.search(text) or patch_host_path_detected(text):
        raise ValueError
    lines = text.splitlines()
    if not lines:
        raise ValueError
    diff_header = re.compile(r"^diff --git (\S+) (\S+)$")
    hunk_header = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(?: .*)?$")
    index_header = re.compile(r"^index [0-9a-fA-F]+\.\.[0-9a-fA-F]+(?: [0-9]{6})?$")
    mode_header = re.compile(r"^(?:old mode|new mode|new file mode|deleted file mode) [0-7]{6}$")
    similarity_header = re.compile(r"^(?:similarity|dissimilarity) index [0-9]{1,3}%$")
    paths = set()
    cursor = 0
    blocks = 0
    while cursor < len(lines):
        match = diff_header.fullmatch(lines[cursor])
        if match is None:
            raise ValueError
        left = validate_patch_header_path(match.group(1), "a/")
        right = validate_patch_header_path(match.group(2), "b/")
        if left:
            paths.add(left)
        if right:
            paths.add(right)
        cursor += 1
        old_path = None
        new_path = None
        old_header_seen = False
        new_header_seen = False
        hunks = 0
        while cursor < len(lines) and not lines[cursor].startswith("diff --git "):
            line = lines[cursor]
            if line.startswith("--- "):
                if old_header_seen or hunks:
                    raise ValueError
                old_path = validate_patch_header_path(line[4:], "a/")
                old_header_seen = True
                cursor += 1
                continue
            if line.startswith("+++ "):
                if not old_header_seen or new_header_seen or hunks:
                    raise ValueError
                new_path = validate_patch_header_path(line[4:], "b/")
                new_header_seen = True
                cursor += 1
                continue
            if line.startswith("@@ "):
                if not old_header_seen or not new_header_seen:
                    raise ValueError
                hunk = hunk_header.fullmatch(line)
                if hunk is None:
                    raise ValueError
                expected_old = int(hunk.group(2) or "1")
                expected_new = int(hunk.group(4) or "1")
                cursor += 1
                actual_old = 0
                actual_new = 0
                saw_line = False
                while cursor < len(lines):
                    content = lines[cursor]
                    if content == r"\ No newline at end of file":
                        if not saw_line:
                            raise ValueError
                        cursor += 1
                        continue
                    if not content or content[0] not in " +-":
                        break
                    saw_line = True
                    if content[0] in " -":
                        actual_old += 1
                    if content[0] in " +":
                        actual_new += 1
                    cursor += 1
                if actual_old != expected_old or actual_new != expected_new:
                    raise ValueError
                hunks += 1
                continue
            if not old_header_seen and (index_header.fullmatch(line) or mode_header.fullmatch(line) or similarity_header.fullmatch(line)):
                cursor += 1
                continue
            if not old_header_seen and line.startswith(("rename from ", "rename to ", "copy from ", "copy to ")):
                paths.add(validate_patch_file_path(line.split(" ", 2)[2]))
                cursor += 1
                continue
            raise ValueError
        if not old_header_seen or not new_header_seen or hunks == 0 or (old_path is None and new_path is None):
            raise ValueError
        if old_path is None:
            if new_path is None or right != new_path or (left is not None and left != new_path):
                raise ValueError
        elif new_path is None:
            if left != old_path or (right is not None and right != old_path):
                raise ValueError
        elif left != old_path or right != new_path:
            raise ValueError
        if old_path:
            paths.add(old_path)
        if new_path:
            paths.add(new_path)
        blocks += 1
    if not paths or blocks == 0:
        raise ValueError
    return paths


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


def exact_keys(value, required):
    if not isinstance(value, dict) or set(value) != set(required):
        raise ValueError


def strict_string(value, maximum=512):
    if not isinstance(value, str) or not value or len(value) > maximum or any(ord(char) < 0x20 or ord(char) == 0x7F for char in value):
        raise ValueError
    return value


def strict_relative(value):
    strict_string(value)
    if value.startswith("/") or "\\" in value or any(part in ("", ".", "..") for part in value.split("/")):
        raise ValueError
    return value


def validate_dimensions(value):
    exact_keys(value, {"width", "height"})
    if any(not exact_int(value[key]) or not 1 <= value[key] <= 100000 for key in ("width", "height")):
        raise ValueError
    return value


def validate_simulator(value):
    exact_keys(value, {"udid", "name", "runtime", "state"})
    if not isinstance(value["udid"], str) or not EXPECTED_UDID_RE.fullmatch(value["udid"]):
        raise ValueError
    for key in ("name", "runtime"):
        strict_string(value[key])
    if value["state"] not in ("Booted", "Shutdown"):
        raise ValueError
    return value


def validate_capture(value, expected_slug=None, expected_path=None):
    exact_keys(value, {"slug", "path", "sha256", "dimensions", "phase", "pair", "checkpoint", "fixtureId", "fixtureSha256", "simulator"})
    strict_string(value["slug"])
    strict_relative(value["path"])
    validate_hash(value["sha256"], 64)
    validate_dimensions(value["dimensions"])
    if value["phase"] != "after" or value["checkpoint"] != "phantom-connected" or value["pair"] != value["slug"]:
        raise ValueError
    if value["fixtureId"] != "just-lift-connected":
        raise ValueError
    validate_hash(value["fixtureSha256"], 64)
    validate_simulator(value["simulator"])
    if expected_slug is not None and value["slug"] != expected_slug:
        raise ValueError
    if expected_path is not None and value["path"] != expected_path:
        raise ValueError
    return value


def validate_identity(identity):
    exact_keys(identity, {"baseSha", "fixtureId", "fixtureSha256", "bundleId", "simulator", "commands", "markers"})
    validate_hash(identity["baseSha"], 40)
    if identity["fixtureId"] != "just-lift-connected" or identity["fixtureSha256"] != EXPECTED_FIXTURE_SHA256:
        raise ValueError
    validate_hash(identity["fixtureSha256"], 64)
    if identity["bundleId"] != "com.devil.phoenixproject.projectphoenix":
        raise ValueError
    validate_simulator(identity["simulator"])
    if identity["commands"] != [name for name, _ in EXPECTED_COMMANDS] or identity["markers"] != sorted(EXPECTED_MARKERS):
        raise ValueError


def run_identity(run):
    provenance = run["provenance"]
    return {
        "baseSha": provenance["baseSha"],
        "fixtureId": provenance["fixture"]["id"],
        "fixtureSha256": provenance["fixture"]["sha256"],
        "bundleId": provenance["bundleId"],
        "simulator": provenance["simulator"],
        "commands": [item["name"] for item in run["commands"]],
        "markers": sorted(run["semanticMarkers"]["observed"]),
    }


def validate_command_log(data, commands):
    try:
        lines = data.decode("utf-8", "strict").splitlines()
    except UnicodeError:
        raise ValueError
    if len(lines) != len(commands) or any(not line for line in lines):
        raise ValueError
    parsed = []
    for line in lines:
        parsed.append(parse_json_bytes(line.encode("utf-8")))
    if parsed != commands:
        raise ValueError


def validate_run_manifest(run, base_sha, phase_files=None):
    exact_keys(run, {"schemaVersion", "runId", "provenance", "commands", "semanticMarkers", "captures", "textualArtifacts"})
    if not exact_int(run["schemaVersion"]) or run["schemaVersion"] != 1:
        raise ValueError
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", run["runId"]):
        raise ValueError
    provenance = run["provenance"]
    exact_keys(provenance, {"baseSha", "fixture", "xcode", "sdk", "simulator", "bundleId"})
    validate_hash(provenance["baseSha"], 40)
    if provenance["baseSha"].lower() != base_sha.lower():
        raise ValueError
    fixture = provenance["fixture"]
    exact_keys(fixture, {"id", "sha256"})
    if fixture["id"] != "just-lift-connected" or fixture["sha256"] != EXPECTED_FIXTURE_SHA256:
        raise ValueError
    validate_hash(fixture["sha256"], 64)
    strict_string(provenance["xcode"])
    strict_string(provenance["sdk"])
    validate_simulator(provenance["simulator"])
    if provenance["bundleId"] != "com.devil.phoenixproject.projectphoenix":
        raise ValueError
    commands = run["commands"]
    if not isinstance(commands, list) or len(commands) != len(EXPECTED_COMMANDS):
        raise ValueError
    for item, (name, output) in zip(commands, EXPECTED_COMMANDS):
        required = {"name", "exitCode", "output"} | ({"resultBundle"} if name == "run-tests" else set())
        exact_keys(item, required)
        if item["name"] != name or item["output"] != output or not exact_int(item["exitCode"]) or item["exitCode"] != 0:
            raise ValueError
        if name == "run-tests":
            exact_keys(item["resultBundle"], {"basename", "status"})
            if item["resultBundle"] != {"basename": "test.xcresult", "status": "private-not-retained"}:
                raise ValueError
    markers = run["semanticMarkers"]
    exact_keys(markers, {"required", "observed"})
    if markers["required"] != list(EXPECTED_MARKERS) or markers["observed"] != list(EXPECTED_MARKERS):
        raise ValueError
    captures = run["captures"]
    if not isinstance(captures, list) or len(captures) != 2:
        raise ValueError
    validate_capture(captures[0], "simulator-after", "after.png")
    validate_capture(captures[1], "xctest-after", "xctest-attachment.png")
    if captures[0]["fixtureSha256"] != fixture["sha256"] or captures[1]["fixtureSha256"] != fixture["sha256"]:
        raise ValueError
    if captures[0]["simulator"] != provenance["simulator"] or captures[1]["simulator"] != provenance["simulator"]:
        raise ValueError
    textual = run["textualArtifacts"]
    if not isinstance(textual, list) or [item.get("path") if isinstance(item, dict) else None for item in textual] != list(EXPECTED_TEXTUAL_ARTIFACTS):
        raise ValueError
    for item in textual:
        exact_keys(item, {"path"})
        strict_relative(item["path"])
    if phase_files is not None:
        if phase_files.get(".phantom-harness") != EXPECTED_HARNESS_MARKER:
            raise ValueError
        for _, output in EXPECTED_COMMANDS:
            if output not in phase_files:
                raise ValueError
        for item in textual:
            if item["path"] not in phase_files:
                raise ValueError
        validate_command_log(phase_files[".commands.jsonl"], commands)
        for capture in captures:
            data = phase_files.get(capture["path"])
            if data is None or hashlib.sha256(data).hexdigest() != capture["sha256"]:
                raise ValueError
            dimensions = validate_png(data)
            if {"width": dimensions[0], "height": dimensions[1]} != capture["dimensions"]:
                raise ValueError
    return run_identity(run)


def validate_diff_summary(value):
    required = {"passed", "thresholdPassed", "dimensions", "width", "height", "changedPixels", "changedPixelRatio", "changedRatio", "meanChannelDelta", "maxChannelDelta", "maskTopPixels", "threshold", "inputs"}
    if not isinstance(value, dict) or set(value) != required:
        raise ValueError
    validate_dimensions(value["dimensions"])
    if value["width"] != value["dimensions"]["width"] or value["height"] != value["dimensions"]["height"]:
        raise ValueError
    for key in ("passed", "thresholdPassed"):
        if type(value[key]) is not bool:
            raise ValueError
    if value["passed"] != value["thresholdPassed"]:
        raise ValueError
    pixels = value["width"] * value["height"]
    if not exact_int(value["changedPixels"]) or not 0 <= value["changedPixels"] <= pixels:
        raise ValueError
    for key in ("changedPixelRatio", "changedRatio"):
        if type(value[key]) not in (int, float) or isinstance(value[key], bool) or not math.isfinite(float(value[key])) or not 0 <= float(value[key]) <= 1:
            raise ValueError
    if type(value["meanChannelDelta"]) not in (int, float) or isinstance(value["meanChannelDelta"], bool) or not math.isfinite(float(value["meanChannelDelta"])) or not 0 <= float(value["meanChannelDelta"]) <= 255:
        raise ValueError
    if not exact_int(value["maxChannelDelta"]) or not 0 <= value["maxChannelDelta"] <= 255:
        raise ValueError
    if not exact_int(value["maskTopPixels"]) or not 0 <= value["maskTopPixels"] <= value["height"]:
        raise ValueError
    if type(value["threshold"]) not in (int, float) or isinstance(value["threshold"], bool) or not math.isfinite(float(value["threshold"])) or not 0 <= float(value["threshold"]) <= 255:
        raise ValueError
    exact_keys(value["inputs"], {"before", "after"})
    if value["inputs"] != {"before": "xctest-attachment.png", "after": "xctest-attachment.png"}:
        raise ValueError


def validate_public_capture(value, expected_path="after.png"):
    exact_keys(value, {"path", "sha256", "dimensions"})
    if value["path"] != expected_path:
        raise ValueError
    validate_hash(value["sha256"], 64)
    validate_dimensions(value["dimensions"])
    return value


def validate_comparison(value):
    full_keys = {"identity", "beforeManifestSha256", "afterManifestSha256", "beforeCapture", "afterCapture", "diffJson", "diffImage", "summary"}
    renderer_keys = {"before", "after", "diffJson", "diffImage", "summary"}
    if not isinstance(value, dict):
        raise ValueError
    if set(value) == full_keys:
        validate_identity(value["identity"])
        validate_hash(value["beforeManifestSha256"], 64)
        validate_hash(value["afterManifestSha256"], 64)
        validate_capture(value["beforeCapture"], "simulator-after", "after.png")
        validate_capture(value["afterCapture"], "simulator-after", "after.png")
        comparison_mode = "contract"
    elif set(value) == renderer_keys:
        validate_public_capture(value["before"])
        validate_public_capture(value["after"])
        comparison_mode = "renderer"
    else:
        raise ValueError
    for key, path in (("diffJson", "comparison/diff.json"), ("diffImage", "comparison/diff.png")):
        descriptor = value[key]
        required = {"path", "sha256"} | ({"dimensions"} if key == "diffImage" else set())
        exact_keys(descriptor, required)
        if descriptor["path"] != path:
            raise ValueError
        validate_hash(descriptor["sha256"], 64)
        if key == "diffImage":
            validate_dimensions(descriptor["dimensions"])
    validate_diff_summary(value["summary"])
    return comparison_mode

def validate_manifest(manifest, base_sha, patch_sha, patch_size):
    exact_keys(manifest, {"schemaVersion", "status", "trustedInput", "fixture", "baseSha", "patch", "candidateKinds", "allowedChangedFiles", "actualChangedFiles", "worktree", "focusedChecks", "before", "after", "comparison", "evidence"})
    if not exact_int(manifest["schemaVersion"]) or manifest["schemaVersion"] != 1 or manifest["status"] != "passed" or manifest["trustedInput"] is not True or manifest["fixture"] != "just-lift-connected":
        raise ValueError
    validate_hash(manifest["baseSha"], 40)
    if manifest["baseSha"].lower() != base_sha.lower():
        raise ValueError
    patch = manifest["patch"]
    exact_keys(patch, {"path", "sha256", "size", "binary", "format"})
    validate_hash(patch["sha256"], 64)
    if patch["path"] != "proposal.patch" or patch["sha256"].lower() != patch_sha.lower() or patch["format"] != "exact-input" or type(patch["binary"]) is not bool:
        raise ValueError
    if not exact_int(patch["size"]) or not 0 <= patch["size"] <= MAX_PATCH_BYTES or patch["size"] != patch_size:
        raise ValueError
    for key in ("candidateKinds", "allowedChangedFiles", "actualChangedFiles"):
        value = manifest[key]
        if not isinstance(value, list) or not value or len(value) > 512 or any(not isinstance(item, str) for item in value):
            raise ValueError
        for item in value:
            strict_relative(item)
    if manifest["candidateKinds"] != sorted(manifest["candidateKinds"]):
        raise ValueError
    if manifest["allowedChangedFiles"] != manifest["actualChangedFiles"]:
        raise ValueError
    worktree = manifest["worktree"]
    exact_keys(worktree, {"baseSha", "headSha", "detached", "uncommitted", "statusEntryCount", "appliedDiffSha256"})
    validate_hash(worktree["baseSha"], 40)
    validate_hash(worktree["headSha"], 40)
    if worktree["baseSha"].lower() != base_sha.lower() or type(worktree["detached"]) is not bool or type(worktree["uncommitted"]) is not bool or not worktree["detached"]:
        raise ValueError
    if not exact_int(worktree["statusEntryCount"]) or worktree["statusEntryCount"] < 0:
        raise ValueError
    validate_hash(worktree["appliedDiffSha256"], 64)
    checks = manifest["focusedChecks"]
    if not isinstance(checks, list) or not checks:
        raise ValueError
    for check in checks:
        exact_keys(check, {"name", "passed"})
        strict_string(check["name"])
        if type(check["passed"]) is not bool or not check["passed"]:
            raise ValueError
    for key, ref in (("before", "before"), ("after", "after")):
        value = manifest[key]
        exact_keys(value, {"artifact", "manifestSha256", "identity"})
        if value["artifact"] != ref:
            raise ValueError
        validate_hash(value["manifestSha256"], 64)
        validate_identity(value["identity"])
    validate_comparison(manifest["comparison"])
    evidence = manifest["evidence"]
    exact_keys(evidence, {"proposalMarkdown", "summaryJson"})
    if evidence != {"proposalMarkdown": "proposal.md", "summaryJson": "evidence-summary.json"}:
        raise ValueError
    return manifest


def validate_evidence_summary(summary, base_sha, patch_sha):
    exact_keys(summary, {"schemaVersion", "status", "trustedInput", "fixture", "baseSha", "patchSha256", "changedFiles", "beforeAfterIdentity", "comparison", "artifacts"})
    if not exact_int(summary["schemaVersion"]) or summary["schemaVersion"] != 1 or summary["status"] != "passed" or summary["trustedInput"] is not True or summary["fixture"] != "just-lift-connected":
        raise ValueError
    validate_hash(summary["baseSha"], 40)
    if summary["baseSha"].lower() != base_sha.lower():
        raise ValueError
    validate_hash(summary["patchSha256"], 64)
    if summary["patchSha256"].lower() != patch_sha.lower():
        raise ValueError
    if not isinstance(summary["changedFiles"], list) or not summary["changedFiles"] or any(not isinstance(item, str) for item in summary["changedFiles"]):
        raise ValueError
    for item in summary["changedFiles"]:
        strict_relative(item)
    validate_identity(summary["beforeAfterIdentity"])
    validate_comparison(summary["comparison"])
    if summary["artifacts"] != ["before", "after", "proposal.patch", "proposal-manifest.json", "proposal.md", "comparison/diff.json", "comparison/diff.png"]:
        raise ValueError
    return summary


def validate_proposal_markdown(data, manifest):
    text = data.decode("utf-8", "strict")
    if credential_or_host_path(data) or "\\" in text:
        raise ValueError
    if not text.startswith("# Phantom proposal evidence\n") or "\n## Allowed changed files\n" not in text or "\n## Verification\n" not in text or "\nStatus: **passed**\n" not in text:
        raise ValueError
    lines = text.splitlines()
    try:
        start = lines.index("## Allowed changed files") + 1
        end = lines.index("## Verification")
    except ValueError:
        raise ValueError
    changed = []
    for line in lines[start:end]:
        if not line:
            continue
        match = re.fullmatch(r"- `([^`]+)`", line)
        if not match:
            raise ValueError
        changed.append(match.group(1))
    if changed != manifest["actualChangedFiles"]:
        raise ValueError
    allowed = KNOWN_ARTIFACT_REFERENCES | set(manifest["actualChangedFiles"])
    for token in re.findall(r"`([^`]+)`", text):
        if "/" in token or token in KNOWN_ARTIFACT_REFERENCES:
            if token not in allowed:
                raise ValueError
            strict_relative(token)
    for token in re.findall(r"(?<![A-Za-z0-9_.-])(?:comparison|before|after)/[A-Za-z0-9_.-]+", text):
        if token not in allowed:
            raise ValueError
        strict_relative(token)
    if re.search(r"(?:^|\s)(?:/|\.\.?/)", text):
        raise ValueError


def png_text_payload(kind, payload):
    import zlib

    if kind in (b"tEXt", b"zTXt"):
        separator = payload.find(b"\x00")
        if separator <= 0:
            raise ValueError
        text = payload[separator + 1:]
        if kind == b"tEXt":
            return text
        if not text or text[0] != 0:
            raise ValueError
        try:
            return zlib.decompress(text[1:])
        except zlib.error:
            raise ValueError
    if kind == b"iTXt":
        keyword_end = payload.find(b"\x00")
        if keyword_end <= 0 or len(payload) < keyword_end + 3:
            raise ValueError
        compressed = payload[keyword_end + 1]
        compression_method = payload[keyword_end + 2]
        if compressed not in (0, 1) or compression_method != 0:
            raise ValueError
        language_end = payload.find(b"\x00", keyword_end + 3)
        if language_end < 0:
            raise ValueError
        translated_end = payload.find(b"\x00", language_end + 1)
        if translated_end < 0:
            raise ValueError
        text = payload[translated_end + 1:]
        if compressed:
            try:
                text = zlib.decompress(text)
            except zlib.error:
                raise ValueError
        return text
    raise ValueError


def validate_png_text_chunk(kind, payload):
    text = png_text_payload(kind, payload)
    if credential_detected(text) or png_host_path_detected(text):
        raise ValueError


def validate_png(data):
    if len(data) < 33 or data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError
    cursor = 8
    seen_header = False
    seen_data = False
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
        if kind in (b"tEXt", b"iTXt", b"zTXt"):
            validate_png_text_chunk(kind, payload)
        if kind == b"IHDR":
            if cursor != 8 or seen_header or length != 13:
                raise ValueError
            width = int.from_bytes(payload[0:4], "big")
            height = int.from_bytes(payload[4:8], "big")
            if not 1 <= width <= 100000 or not 1 <= height <= 100000:
                raise ValueError
            seen_header = True
        elif kind == b"IDAT":
            if not seen_header or seen_end:
                raise ValueError
            seen_data = True
        elif kind == b"IEND":
            if length != 0 or not seen_header or not seen_data or seen_end or cursor + 12 != len(data):
                raise ValueError
            seen_end = True
        cursor += 12 + length
    if not seen_end:
        raise ValueError
    return width, height


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
            directories = []
            artifacts = scan_artifact_tree(root_fd, allowed_paths=INTERNAL_ARTIFACT_SET, directories=directories)
        finally:
            os.close(root_fd)
        if len(artifacts) > MAX_INTERNAL_FILES or set(artifacts) != INTERNAL_ARTIFACT_SET:
            raise ValueError
        proposal_marker = artifacts[".phantom-proposal"]
        if len(proposal_marker) > MAX_PROPOSAL_MARKER_BYTES or proposal_marker != EXPECTED_PROPOSAL_MARKER:
            raise ValueError
        if set(directories) != {"", "before", "after", "comparison"}:
            raise ValueError
        for name, data in artifacts.items():
            leaf = name.rsplit("/", 1)[-1]
            if name == "proposal.patch":
                # Patch content has its own unified-diff/path policy.  The generic
                # host-path scanner would misclassify XML closing tags such as
                # </string> while still protecting all public text artifacts.
                validate_proposal_patch(data)
            elif leaf in INTERNAL_LOG_FILES:
                # Xcode and CoreSimulator logs are private evidence. They may contain
                # normal absolute host paths, but never credentials.
                if credential_detected(data):
                    raise ValueError
            elif leaf.endswith(".png"):
                # PNGs are checked by validate_png below; never regex-scan arbitrary
                # compressed/pixel bytes as if they were text.
                continue
            elif credential_or_host_path(data):
                raise ValueError
        snapshot = read_private_path(str(STATE.private_dir / "patch.snapshot"), MAX_PATCH_BYTES)
        if artifacts["proposal.patch"] != snapshot:
            raise ValueError
        if hashlib.sha256(artifacts["proposal.patch"]).hexdigest() != STATE.patch_sha or len(artifacts["proposal.patch"]) != STATE.patch_size:
            raise ValueError

        png_dimensions = None
        json_values = {}
        for name, data in artifacts.items():
            if name == "comparison/diff.png":
                png_dimensions = validate_png(data)
            elif name == "proposal.md":
                data.decode("utf-8", "strict")
            elif name in {"evidence-summary.json", "proposal-manifest.json", "before/run.json", "after/run.json", "comparison/diff.json"}:
                json_values[name] = validate_json_artifact(data)

        before = json_values["before/run.json"]
        after = json_values["after/run.json"]
        before_files = {name: artifacts[f"before/{name}"] for name in INTERNAL_HARNESS_FILES}
        after_files = {name: artifacts[f"after/{name}"] for name in INTERNAL_HARNESS_FILES}
        before_identity = validate_run_manifest(before, STATE.base_sha, before_files)
        after_identity = validate_run_manifest(after, STATE.base_sha, after_files)
        if before_identity != after_identity:
            raise ValueError
        manifest = json_values["proposal-manifest.json"]
        validate_manifest(manifest, STATE.base_sha, STATE.patch_sha, STATE.patch_size)
        diff = json_values["comparison/diff.json"]
        comparison = manifest["comparison"]
        comparison_mode = validate_comparison(comparison)
        validate_diff_summary(diff)
        if diff["inputs"] != {"before": before["captures"][1]["path"], "after": after["captures"][1]["path"]}:
            raise ValueError
        summary = json_values["evidence-summary.json"]
        validate_evidence_summary(summary, STATE.base_sha, STATE.patch_sha)
        if hashlib.sha256(artifacts["before/run.json"]).hexdigest() != manifest["before"]["manifestSha256"] or hashlib.sha256(artifacts["after/run.json"]).hexdigest() != manifest["after"]["manifestSha256"]:
            raise ValueError
        if manifest["before"]["identity"] != before_identity or manifest["after"]["identity"] != after_identity:
            raise ValueError
        if summary["beforeAfterIdentity"] != before_identity or summary["comparison"] != comparison:
            raise ValueError
        if comparison_mode == "contract":
            if comparison["identity"] != before_identity:
                raise ValueError
            if comparison["beforeManifestSha256"] != manifest["before"]["manifestSha256"] or comparison["afterManifestSha256"] != manifest["after"]["manifestSha256"]:
                raise ValueError
            if comparison["beforeCapture"] != before["captures"][0] or comparison["afterCapture"] != after["captures"][0]:
                raise ValueError
        else:
            before_capture = {key: before["captures"][0][key] for key in ("path", "sha256", "dimensions")}
            after_capture = {key: after["captures"][0][key] for key in ("path", "sha256", "dimensions")}
            if comparison["before"] != before_capture or comparison["after"] != after_capture:
                raise ValueError
        if comparison["summary"] != diff:
            raise ValueError
        if comparison["diffJson"]["sha256"] != hashlib.sha256(artifacts["comparison/diff.json"]).hexdigest() or comparison["diffImage"]["sha256"] != hashlib.sha256(artifacts["comparison/diff.png"]).hexdigest():
            raise ValueError
        if comparison["summary"]["dimensions"] != comparison["diffImage"]["dimensions"] or tuple(comparison["diffImage"]["dimensions"][key] for key in ("width", "height")) != png_dimensions:
            raise ValueError
        if summary["changedFiles"] != manifest["actualChangedFiles"]:
            raise ValueError
        validate_proposal_markdown(artifacts["proposal.md"], manifest)
        return {name: artifacts[name] for name in ALLOWED_ARTIFACTS}
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


def validate_preview_result(value):
    exact_keys(value, {"schema_version", "status", "ticket_id", "fixture", "base_sha", "patch_sha256", "artifacts"})
    if value["schema_version"] != 1 or value["status"] != "passed" or value["fixture"] != "just-lift-connected":
        raise ValueError
    if not isinstance(value["ticket_id"], str) or not TICKET_RE.fullmatch(value["ticket_id"]):
        raise ValueError
    validate_hash(value["base_sha"], 40)
    validate_hash(value["patch_sha256"], 64)
    entries = value["artifacts"]
    if not isinstance(entries, list) or len(entries) != len(ALLOWED_ARTIFACTS):
        raise ValueError
    if [entry.get("path") if isinstance(entry, dict) else None for entry in entries] != list(ALLOWED_ARTIFACTS):
        raise ValueError
    for entry in entries:
        exact_keys(entry, {"path", "sha256"})
        if entry["path"] not in ALLOWED_ARTIFACT_SET:
            raise ValueError
        validate_hash(entry["sha256"], 64)
    if credential_or_host_path(json_result(value)):
        raise ValueError


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
    check_interrupted()
    destination = None
    staging = None
    dirs = {}
    try:
        if set(artifacts) != ALLOWED_ARTIFACT_SET:
            raise ValueError
        destination = STATE.result.current()
        if os.listdir(destination):
            raise ValueError
        staging = mkdir_result_dir(destination, PUBLICATION_STAGING_NAME)
        for directory in ("comparison", "before", "after"):
            dirs[directory] = mkdir_result_dir(staging, directory)
        entries = []
        for name in ALLOWED_ARTIFACTS:
            check_interrupted()
            if "/" in name:
                directory, leaf = name.split("/", 1)
                parent = dirs[directory]
            else:
                parent, leaf = staging, name
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
        validate_preview_result(result)
        create_result_file(staging, "preview-result.json", json_result(result))
        create_result_file(staging, PUBLICATION_PRECHECK_NAME, b"ready\n")
        time.sleep(0.05)
        check_interrupted()
        if set(os.listdir(staging)) != {"preview-result.json", PUBLICATION_PRECHECK_NAME, "proposal.md", "evidence-summary.json", "proposal-manifest.json", "comparison", "before", "after"}:
            raise ValueError
        current = STATE.result.current()
        try:
            if set(os.listdir(current)) != {PUBLICATION_STAGING_NAME}:
                raise ValueError
        finally:
            os.close(current)
        check_interrupted()
        os.unlink(PUBLICATION_PRECHECK_NAME, dir_fd=staging)
        check_interrupted()
        for name in ("comparison", "before", "after", "proposal.md", "evidence-summary.json", "proposal-manifest.json"):
            check_interrupted()
            os.rename(name, name, src_dir_fd=staging, dst_dir_fd=destination)
        check_interrupted()
        os.rename("preview-result.json", "preview-result.json", src_dir_fd=staging, dst_dir_fd=destination)
        os.rmdir(PUBLICATION_STAGING_NAME, dir_fd=destination)
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
        if staging is not None:
            try:
                os.close(staging)
            except OSError:
                pass
        if destination is not None:
            try:
                os.close(destination)
            except OSError:
                pass
    check_interrupted()
    STATE.published = True


def main_work(request_raw, result_raw):
    STATE.stage = "startup"
    STATE.result = validate_result_root(result_raw)
    STATE.private_dir = make_private_dir()
    STATE.stage = "validate-request"
    ticket, patch_raw, patch_data = parse_request(request_raw)
    check_interrupted()
    STATE.base_sha = current_head()
    patch_snapshot, patch_sha = snapshot_patch(patch_data)
    check_interrupted()
    if not EXPECTED_UDID_RE.fullmatch(os.environ.get("PHOENIX_HARNESS_UDID", "")):
        fail("renderer")
    env = child_environment()
    STATE.stage = "renderer"
    run_child([RENDERER, "render", STATE.private_dir / "proposal", "just-lift-connected", patch_snapshot], "renderer.log", env)
    check_interrupted()
    STATE.stage = "verify-before"
    run_child([RUNNER, "verify", STATE.private_dir / "proposal" / "before"], "before-verify.log", env)
    check_interrupted()
    STATE.stage = "verify-after"
    run_child([RUNNER, "verify", STATE.private_dir / "proposal" / "after"], "after-verify.log", env)
    check_interrupted()
    STATE.stage = "validate-artifacts"
    artifacts = validate_artifacts()
    check_interrupted()
    if current_head() != STATE.base_sha:
        fail("validate-artifacts")
    check_interrupted()
    STATE.stage = "publish"
    publish_success(artifacts, ticket, "just-lift-connected", STATE.base_sha, patch_sha)
    check_interrupted()


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
        if STATE.interrupted is not None:
            raise PreviewInterrupted
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
        if failure_stage is None and STATE.interrupted is not None:
            failure_stage = "interrupted"
            exit_code = 1
        if failure_stage is not None:
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
