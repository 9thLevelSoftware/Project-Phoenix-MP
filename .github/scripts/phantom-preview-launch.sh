#!/usr/bin/env bash
set -euo pipefail

# Persistent human-inspection launcher for the real Phoenix iOS app.  This is
# intentionally separate from phantom-harness.sh and phantom-proposal.sh:
# launch leaves the selected app running and emits no evidence/proposal output.
SCRIPT_DIR="${BASH_SOURCE[0]%/*}"
if [[ "$SCRIPT_DIR" == "${BASH_SOURCE[0]}" ]]; then SCRIPT_DIR="."; fi
if [[ "$SCRIPT_DIR" != /* ]]; then SCRIPT_DIR="$PWD/$SCRIPT_DIR"; fi
SCRIPT_DIR="$(cd "$SCRIPT_DIR" && pwd -P)"
export PHOENIX_PREVIEW_LAUNCH_SCRIPT_DIR="$SCRIPT_DIR"
exec /usr/bin/python3 - "$@" <<'PY'
import hashlib
import json
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

SCRIPT_DIR = Path(os.environ.get("PHOENIX_PREVIEW_LAUNCH_SCRIPT_DIR", "")).resolve()
REPO_ROOT = (SCRIPT_DIR / "../..").resolve()
FIXTURE_RELATIVE = Path("shared/src/iosSimulatorArm64Main/kotlin/com/devil/phoenixproject/fixture/SimulatorLaunchFixture.kt")
PROJECT_RELATIVE = Path("iosApp/VitruvianPhoenix/VitruvianPhoenix.xcodeproj/project.pbxproj")
CONFIG_RELATIVE = Path("iosApp/VitruvianPhoenix/Config/Supabase.xcconfig")
GRADLEW_RELATIVE = Path("gradlew")
EXPECTED_FIXTURE_SHA256 = "e180679548a2d96dbc59c51449edb3b99c19d3e3be82eca98c0707a21a64e78e"
EXPECTED_BUNDLE_ID = "com.devil.phoenixproject.projectphoenix"
EXPECTED_FIXTURE = "just-lift-connected"
SIMULATOR_NAME = "Phantom Harness iPhone 17 Pro"
SIMULATOR_UDID = "678A4E3B-6A1F-469C-8068-9A2608A85783"
SIMULATOR_RUNTIME_SUFFIX = "iOS-26-5"
SYSTEM_PATH = "/usr/bin:/bin:/usr/sbin:/sbin"
COMMAND_TIMEOUT_SECONDS = 1800
PROCESS_TERM_GRACE_SECONDS = 0.5
PROCESS_REAP_TIMEOUT_SECONDS = 5
MAX_DESCRIPTOR_BYTES = 64 * 1024
UDID_RE = re.compile(r"^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
SHA1_RE = re.compile(r"^[0-9A-Fa-f]{40}$")
LAUNCH_COMMAND = "phantom-preview-launch.sh launch [WORKTREE] just-lift-connected"
CLEANUP_COMMAND = "phantom-preview-launch.sh stop SESSION_DESCRIPTOR"
PLACEHOLDER_CONFIG = (
    b"// Temporary non-secret simulator fixture configuration.\n"
    b"SUPABASE_URL = https://placeholder.invalid\n"
    b"SUPABASE_ANON_KEY = placeholder-anon-key\n"
)
PROTECTED_ORIGINAL = REPO_ROOT.parent / "project-phoenix-mp"


class LaunchFailure(Exception):
    pass


class UsageFailure(Exception):
    pass


def fail(message="launch failed"):
    raise LaunchFailure(message)


def usage():
    raise UsageFailure(
        "usage: phantom-preview-launch.sh launch [WORKTREE] just-lift-connected; "
        "phantom-preview-launch.sh stop SESSION_DESCRIPTOR"
    )


def safe_git_env():
    return {
        "PATH": SYSTEM_PATH,
        "HOME": str(REPO_ROOT),
        "LANG": "C",
        "LC_ALL": "C",
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_GLOBAL": "/dev/null",
    }


def git_command(root, *args, check=True):
    try:
        result = subprocess.run(
            ["git", "-C", str(root), *args],
            env=safe_git_env(),
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            check=False,
        )
    except OSError:
        fail()
    if check and result.returncode != 0:
        fail()
    return result


def reject_secret_environment():
    name_pattern = re.compile(
        r"(?:TOKEN|SECRET|PASSWORD|PASSWD|PRIVATE[_-]?KEY|CREDENTIAL|API[_-]?KEY|ANON[_-]?KEY|AUTHORIZATION)",
        re.IGNORECASE,
    )
    value_patterns = (
        re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
        re.compile(r"\b(?:gh[pousr]|github_pat|glpat|xox[baprs]|sk|rk)[_-][A-Za-z0-9_./=-]{20,}\b", re.IGNORECASE),
        re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
        re.compile(r"\bBearer\s+[A-Za-z0-9._~+/=-]{20,}", re.IGNORECASE),
    )
    for key, value in os.environ.items():
        if key not in {"PATH", "PWD", "OLDPWD", "SHLVL"} and name_pattern.search(key) and value:
            fail()
        if key not in {"PATH", "PWD", "OLDPWD", "SHLVL", "JAVA_HOME", "TMPDIR", "HOME", "DEVELOPER_DIR"}:
            if any(pattern.search(value) for pattern in value_patterns):
                fail()


def reject_secret_arguments(arguments):
    name_pattern = re.compile(
        r"(?:TOKEN|SECRET|PASSWORD|PASSWD|PRIVATE[_-]?KEY|CREDENTIAL|API[_-]?KEY|ANON[_-]?KEY|AUTHORIZATION|BEARER)",
        re.IGNORECASE,
    )
    value_patterns = (
        re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----", re.IGNORECASE),
        re.compile(r"\b(?:gh[pousr]|github_pat|glpat|xox[baprs]|sk|rk)[_-][A-Za-z0-9_./=-]{20,}\b", re.IGNORECASE),
        re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"),
        re.compile(r"\bBearer\s+[A-Za-z0-9._~+/=-]{16,}", re.IGNORECASE),
        re.compile(r"(?i)\b(?:https?|ssh|file)://[^\s]+"),
    )
    for argument in arguments:
        if not isinstance(argument, str) or "\x00" in argument:
            fail()
        if name_pattern.search(argument) or any(pattern.search(argument) for pattern in value_patterns):
            fail()


def canonical_input_path(raw):
    if not isinstance(raw, str) or not raw or "\x00" in raw or "\n" in raw or "\r" in raw or "\\" in raw:
        fail()
    if not os.path.isabs(raw) or os.path.normpath(raw) != raw:
        fail()
    path = Path(raw)
    try:
        info = os.lstat(path)
    except OSError:
        fail()
    if stat.S_ISLNK(info.st_mode):
        fail()
    try:
        resolved = path.resolve(strict=True)
    except (OSError, RuntimeError):
        fail()
    # /tmp and /var are trusted macOS aliases; every caller-created alias is
    # still rejected by the component walk below.
    resolved_string = str(resolved)
    trusted_alias = (
        (raw == "/tmp" or raw.startswith("/tmp/")) and resolved_string == "/private" + raw
    ) or (
        (raw == "/var" or raw.startswith("/var/")) and resolved_string == "/private" + raw
    )
    if resolved_string != raw and not trusted_alias:
        fail()
    current = Path(path.anchor)
    for component in path.parts[1:]:
        current /= component
        try:
            component_info = os.lstat(current)
        except OSError:
            fail()
        if stat.S_ISLNK(component_info.st_mode) and str(current) not in {"/tmp", "/var"}:
            fail()
    return resolved if trusted_alias else path


def owned_directory(path):
    try:
        info = os.lstat(path)
    except OSError:
        fail()
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid():
        fail()


def owned_regular(path, *, executable=False):
    try:
        info = os.lstat(path)
    except OSError:
        fail()
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
        fail()
    if executable and not os.access(path, os.X_OK):
        fail()


def registered_worktrees():
    output = git_command(REPO_ROOT, "worktree", "list", "--porcelain").stdout
    paths = set()
    for line in output.splitlines():
        if line.startswith("worktree "):
            raw = line[len("worktree "):]
            try:
                candidate = canonical_input_path(raw)
            except LaunchFailure:
                continue
            paths.add(candidate)
    return paths


def validate_target(requested):
    if requested is None:
        target = REPO_ROOT
    else:
        target = canonical_input_path(requested)
    # The canonical preview host must be on its current branch/ref. A detached
    # caller-owned worktree is allowed only through the explicit registered
    # worktree argument below.
    if target == REPO_ROOT and not git_command(target, "symbolic-ref", "--quiet", "--short", "HEAD", check=False).stdout.strip():
        fail()
    owned_directory(target)
    if target == PROTECTED_ORIGINAL:
        fail()
    top_level = git_command(target, "rev-parse", "--show-toplevel", check=False).stdout.strip()
    if top_level != str(target):
        fail()
    if requested is not None and target != REPO_ROOT and target not in registered_worktrees():
        fail()
    if target != REPO_ROOT and target == PROTECTED_ORIGINAL:
        fail()
    for relative in (FIXTURE_RELATIVE, PROJECT_RELATIVE, GRADLEW_RELATIVE):
        path = target / relative
        try:
            if relative == GRADLEW_RELATIVE:
                owned_regular(path, executable=True)
            else:
                owned_regular(path)
        except LaunchFailure:
            raise
    fixture = target / FIXTURE_RELATIVE
    digest = hashlib.sha256(fixture.read_bytes()).hexdigest()
    if digest != EXPECTED_FIXTURE_SHA256:
        fail()
    status = git_command(target, "status", "--porcelain=v2", "--untracked-files=all", "--ignored=no").stdout
    if status:
        fail()
    head = git_command(target, "rev-parse", "--verify", "HEAD").stdout.strip()
    if not SHA1_RE.fullmatch(head):
        fail()
    return target, head


def make_private_root():
    try:
        root = Path(tempfile.mkdtemp(prefix="phantom-preview-launch-"))
        os.chmod(root, 0o700)
        for name in ("home", "tmp", "gradle-user-home"):
            path = root / name
            path.mkdir(mode=0o700)
            os.chmod(path, 0o700)
        return root
    except OSError:
        fail()


def java_version_ok(java):
    if not java.is_file() or not os.access(java, os.X_OK):
        return False
    try:
        result = subprocess.run(
            [str(java), "-version"],
            env={"PATH": SYSTEM_PATH, "LC_ALL": "C", "LANG": "C"},
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            timeout=15,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return False
    return result.returncode == 0 and bool(re.search(rb'version\s+["\']', result.stderr))


def trusted_directory(path, allowed_owners):
    try:
        info = os.lstat(path)
    except OSError:
        return False
    return (
        stat.S_ISDIR(info.st_mode)
        and info.st_uid in allowed_owners
        and not (stat.S_IMODE(info.st_mode) & 0o022)
    )


def trusted_java_file(path, allowed_owners):
    try:
        info = os.lstat(path)
    except OSError:
        return False
    mode = stat.S_IMODE(info.st_mode)
    return (
        stat.S_ISREG(info.st_mode)
        and info.st_uid in allowed_owners
        and not (mode & 0o022)
        and bool(mode & 0o111)
        and os.access(path, os.X_OK)
    )


def valid_java_home(raw):
    if not isinstance(raw, str) or not raw or "\x00" in raw or "\n" in raw or "\r" in raw or "\\" in raw:
        return None
    if not os.path.isabs(raw) or os.path.normpath(raw) != raw:
        return None
    allowed_owners = {0, os.getuid()}
    try:
        canonical = Path(raw).resolve(strict=True)
    except (OSError, RuntimeError):
        return None
    java = canonical / "bin" / "java"
    if not trusted_directory(canonical, allowed_owners):
        return None
    if not trusted_directory(java.parent, allowed_owners):
        return None
    if not trusted_java_file(java, allowed_owners):
        return None
    if not java_version_ok(java):
        return None
    return str(canonical)


def bootstrap_java():
    # JAVA_HOME is an explicit trust assertion.  An unset value is not
    # substituted with a system/PATH runtime, and an empty/invalid assertion
    # fails before any simulator or build tool is started.
    if "JAVA_HOME" not in os.environ:
        fail()
    configured_home = valid_java_home(os.environ["JAVA_HOME"])
    if configured_home is None:
        fail()
    return configured_home


def child_environment(private_root, java_home):
    environment = {
        "PATH": SYSTEM_PATH,
        "JAVA_HOME": java_home,
        "HOME": str(private_root / "home"),
        "TMPDIR": str(private_root / "tmp"),
        "GRADLE_USER_HOME": str(private_root / "gradle-user-home"),
        "LANG": "C",
        "LC_ALL": "C",
        "PHOENIX_SIMULATOR_FIXTURE": EXPECTED_FIXTURE,
        "PHOENIX_PREVIEW_LAUNCH": "1",
    }
    # Test-only stand-ins are accepted only through a caller-owned directory
    # that is validated before it can affect child lookup. Normal launches
    # retain the fixed system PATH above.
    test_tool_dir = os.environ.get("PHOENIX_PREVIEW_LAUNCH_TEST_BIN", "")
    if test_tool_dir:
        test_tool_path = canonical_input_path(test_tool_dir)
        owned_directory(test_tool_path)
        environment["PATH"] = f"{test_tool_path}:{SYSTEM_PATH}"
    developer_dir = os.environ.get("DEVELOPER_DIR", "")
    if developer_dir:
        canonical_input_path(developer_dir)
        environment["DEVELOPER_DIR"] = developer_dir
    return environment


def process_group_exists(process):
    try:
        os.killpg(process.pid, 0)
    except OSError:
        return False
    return True


def bounded_reap(process, timeout=PROCESS_REAP_TIMEOUT_SECONDS):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            return process.wait(timeout=min(0.05, max(0.0, deadline - time.monotonic())))
        except subprocess.TimeoutExpired:
            continue
    raise subprocess.TimeoutExpired(process.args, timeout)


def terminate_process_group(process):
    # A direct child can exit during TERM grace while a stubborn descendant
    # keeps the session alive. KILL is therefore unconditional after grace.
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except OSError:
        pass
    grace_deadline = time.monotonic() + PROCESS_TERM_GRACE_SECONDS
    while time.monotonic() < grace_deadline:
        try:
            process.wait(timeout=0.02)
        except subprocess.TimeoutExpired:
            pass
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except OSError:
        pass
    try:
        bounded_reap(process)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except OSError:
            pass
        bounded_reap(process)


def run_tool(private_root, stage, argv, cwd, environment, timeout=COMMAND_TIMEOUT_SECONDS, allowed=(0,)):
    stdout_path = private_root / (stage + ".stdout")
    stderr_path = private_root / (stage + ".stderr")
    try:
        with open(stdout_path, "wb", opener=lambda path, flags: os.open(path, flags, 0o600)) as stdout, open(stderr_path, "wb", opener=lambda path, flags: os.open(path, flags, 0o600)) as stderr:
            process = subprocess.Popen(
                list(argv),
                cwd=str(cwd),
                env=environment,
                stdin=subprocess.DEVNULL,
                stdout=stdout,
                stderr=stderr,
                start_new_session=True,
            )
            try:
                return_code = process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                terminate_process_group(process)
                fail()
            if process_group_exists(process):
                # A successful leader is not sufficient: descendants must
                # not outlive a tool invocation or retain the session.
                terminate_process_group(process)
                fail()
    except (OSError, ValueError):
        fail()
    if return_code not in allowed:
        fail()
    try:
        return stdout_path.read_bytes()
    except OSError:
        fail()


def safe_config(target):
    config = target / CONFIG_RELATIVE
    parent = config.parent
    owned_directory(parent)
    try:
        info = os.lstat(config)
    except FileNotFoundError:
        try:
            fd = os.open(config, os.O_CREAT | os.O_EXCL | os.O_WRONLY | getattr(os, "O_NOFOLLOW", 0), 0o600)
            with os.fdopen(fd, "wb") as stream:
                stream.write(PLACEHOLDER_CONFIG)
            os.chmod(config, 0o600)
            return True
        except OSError:
            fail()
    except OSError:
        fail()
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
        fail()
    return False


def config_identity(target):
    config = target / CONFIG_RELATIVE
    owned_regular(config)
    info = os.lstat(config)
    return info.st_dev, info.st_ino


def remove_config(target, expected_identity):
    config = target / CONFIG_RELATIVE
    try:
        info = os.lstat(config)
    except FileNotFoundError:
        if expected_identity is not None:
            fail()
        return
    except OSError:
        fail()
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
        fail()
    if expected_identity is not None and (info.st_dev, info.st_ino) != expected_identity:
        fail()
    if expected_identity is not None:
        try:
            if config.read_bytes() != PLACEHOLDER_CONFIG:
                fail()
        except OSError:
            fail()
    try:
        config.unlink()
    except OSError:
        fail()


def parse_devices(payload):
    try:
        data = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError):
        fail()
    if not isinstance(data, dict):
        fail()
    devices = data.get("devices")
    if not isinstance(devices, dict):
        fail()
    candidates = []
    for runtime, entries in devices.items():
        if not isinstance(runtime, str):
            fail()
        if not runtime.endswith(SIMULATOR_RUNTIME_SUFFIX):
            continue
        if not isinstance(entries, list):
            fail()
        for device in entries:
            if not isinstance(device, dict):
                fail()
            if device.get("isAvailable") is False:
                continue
            if device.get("isAvailable") is not True:
                fail()
            name = device.get("name")
            udid = device.get("udid")
            state = device.get("state")
            if not isinstance(name, str) or not isinstance(udid, str) or not isinstance(state, str):
                fail()
            if name != SIMULATOR_NAME or udid != SIMULATOR_UDID:
                continue
            if state not in {"Booted", "Shutdown"}:
                continue
            candidates.append((state == "Booted", udid, runtime, state))
    if not candidates:
        fail()
    candidates.sort(key=lambda item: (not item[0], item[1]))
    _, udid, runtime, state = candidates[0]
    return {"udid": udid, "runtime": runtime, "state": state}


def validate_app(path):
    try:
        info = os.lstat(path)
    except OSError:
        fail()
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid():
        fail()


def source_unchanged(target, expected_head, expected_status):
    current_head = git_command(target, "rev-parse", "--verify", "HEAD").stdout.strip()
    current_status = git_command(target, "status", "--porcelain=v2", "--untracked-files=all", "--ignored=no").stdout
    if current_head != expected_head or current_status != expected_status:
        fail()


def cleanup_failed_app(private_root, environment, udid, installed):
    if not installed:
        return
    try:
        run_tool(private_root, "failure-terminate", ["xcrun", "simctl", "terminate", udid, EXPECTED_BUNDLE_ID], REPO_ROOT, environment, allowed=(0, 149))
        run_tool(private_root, "failure-uninstall", ["xcrun", "simctl", "uninstall", udid, EXPECTED_BUNDLE_ID], REPO_ROOT, environment, allowed=(0, 149))
    except LaunchFailure:
        pass


def launch(requested_worktree):
    target, base_sha = validate_target(requested_worktree)
    private_root = make_private_root()
    created_config = False
    config_file_identity = None
    installed = False
    success = False
    expected_status = git_command(target, "status", "--porcelain=v2", "--untracked-files=all", "--ignored=no").stdout
    environment = None
    try:
        java_home = bootstrap_java()
        environment = child_environment(private_root, java_home)
        devices = parse_devices(run_tool(private_root, "simulator-list", ["xcrun", "simctl", "list", "devices", "-j"], target, environment))
        udid = devices["udid"]
        if devices["state"] != "Booted":
            run_tool(private_root, "simulator-boot", ["xcrun", "simctl", "boot", udid], target, environment)
        run_tool(private_root, "simulator-bootstatus", ["xcrun", "simctl", "bootstatus", udid, "-b"], target, environment)
        created_config = safe_config(target)
        if created_config:
            config_file_identity = config_identity(target)
        derived_data = private_root / "derived-data"
        derived_data.mkdir(mode=0o700)
        project = target / "iosApp/VitruvianPhoenix/VitruvianPhoenix.xcodeproj"
        run_tool(
            private_root,
            "xcodebuild-build",
            [
                "xcodebuild",
                "-project",
                str(project),
                "-scheme",
                "VitruvianPhoenix",
                "-configuration",
                "Debug",
                "-sdk",
                "iphonesimulator",
                "-destination",
                f"platform=iOS Simulator,id={udid}",
                "-derivedDataPath",
                str(derived_data),
                "CODE_SIGNING_ALLOWED=NO",
                "CODE_SIGNING_REQUIRED=NO",
                "-hideShellScriptEnvironment",
                "build",
            ],
            target,
            environment,
        )
        app = derived_data / "Build/Products/Debug-iphonesimulator/VitruvianPhoenix.app"
        validate_app(app)
        run_tool(private_root, "simulator-install", ["xcrun", "simctl", "install", udid, str(app)], target, environment)
        installed = True
        launch_environment = dict(environment)
        # simctl only forwards SIMCTL_CHILD_* variables into the launched
        # application.  Keeping this on the launch boundary is deliberate:
        # PHOENIX_SIMULATOR_FIXTURE in xcrun's own environment is insufficient.
        launch_environment["SIMCTL_CHILD_PHOENIX_SIMULATOR_FIXTURE"] = EXPECTED_FIXTURE
        run_tool(private_root, "simulator-launch", ["xcrun", "simctl", "launch", udid, EXPECTED_BUNDLE_ID], target, launch_environment)
        source_unchanged(target, base_sha, expected_status)
        if created_config:
            remove_config(target, config_file_identity)
            created_config = False
        descriptor = {
            "schemaVersion": 1,
            "status": "running",
            "simulatorUdid": udid,
            "bundleId": EXPECTED_BUNDLE_ID,
            "fixture": EXPECTED_FIXTURE,
            "baseSha": base_sha,
            "launchCommand": LAUNCH_COMMAND,
            "cleanupCommand": CLEANUP_COMMAND,
        }
        success = True
        print(json.dumps(descriptor, sort_keys=True, separators=(",", ":")))
    finally:
        if created_config:
            try:
                remove_config(target, config_file_identity)
            except LaunchFailure:
                success = False
        if not success:
            if environment is not None:
                cleanup_failed_app(private_root, environment, locals().get("udid", ""), installed)
        shutil.rmtree(private_root, ignore_errors=True)


def descriptor_from_path(raw):
    path = canonical_input_path(raw)
    owned_regular(path)
    try:
        info = os.lstat(path)
        if stat.S_IMODE(info.st_mode) & 0o077:
            fail()
        data = path.read_bytes()
    except OSError:
        fail()
    if len(data) > MAX_DESCRIPTOR_BYTES:
        fail()

    def reject_duplicate_keys(entries):
        result = {}
        for key, item in entries:
            if key in result:
                raise ValueError
            result[key] = item
        return result

    try:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=reject_duplicate_keys)
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError):
        fail()
    if not isinstance(value, dict):
        fail()
    expected_keys = {
        "schemaVersion",
        "status",
        "simulatorUdid",
        "bundleId",
        "fixture",
        "baseSha",
        "launchCommand",
        "cleanupCommand",
    }
    if set(value) != expected_keys:
        fail()
    if type(value.get("schemaVersion")) is not int or value["schemaVersion"] != 1:
        fail()
    string_fields = (
        "status",
        "simulatorUdid",
        "bundleId",
        "fixture",
        "baseSha",
        "launchCommand",
        "cleanupCommand",
    )
    if any(type(value.get(field)) is not str for field in string_fields):
        fail()
    if value["status"] != "running":
        fail()
    if value["simulatorUdid"] != SIMULATOR_UDID:
        fail()
    if value["bundleId"] != EXPECTED_BUNDLE_ID or value["fixture"] != EXPECTED_FIXTURE:
        fail()
    if not SHA1_RE.fullmatch(value["baseSha"]):
        fail()
    if value["launchCommand"] != LAUNCH_COMMAND or value["cleanupCommand"] != CLEANUP_COMMAND:
        fail()
    return value


def stop(raw_descriptor):
    descriptor = descriptor_from_path(raw_descriptor)
    private_root = make_private_root()
    environment = {
        "PATH": SYSTEM_PATH,
        "LANG": "C",
        "LC_ALL": "C",
        "PHOENIX_SIMULATOR_FIXTURE": EXPECTED_FIXTURE,
        "PHOENIX_PREVIEW_LAUNCH": "1",
    }
    try:
        test_tool_dir = os.environ.get("PHOENIX_PREVIEW_LAUNCH_TEST_BIN", "")
        if test_tool_dir:
            test_tool_path = canonical_input_path(test_tool_dir)
            owned_directory(test_tool_path)
            environment["PATH"] = f"{test_tool_path}:{SYSTEM_PATH}"
        devices = parse_devices(run_tool(private_root, "stop-list", ["xcrun", "simctl", "list", "devices", "-j"], REPO_ROOT, environment))
        if devices["udid"] != descriptor["simulatorUdid"]:
            fail()
        if devices["state"] != "Booted":
            run_tool(private_root, "stop-boot", ["xcrun", "simctl", "boot", descriptor["simulatorUdid"]], REPO_ROOT, environment)
        run_tool(private_root, "stop-bootstatus", ["xcrun", "simctl", "bootstatus", descriptor["simulatorUdid"], "-b"], REPO_ROOT, environment)
        run_tool(private_root, "stop-terminate", ["xcrun", "simctl", "terminate", descriptor["simulatorUdid"], EXPECTED_BUNDLE_ID], REPO_ROOT, environment, allowed=(0, 149))
        run_tool(private_root, "stop-uninstall", ["xcrun", "simctl", "uninstall", descriptor["simulatorUdid"], EXPECTED_BUNDLE_ID], REPO_ROOT, environment, allowed=(0, 149))
        stopped = dict(descriptor)
        stopped["status"] = "stopped"
        print(json.dumps(stopped, sort_keys=True, separators=(",", ":")))
    finally:
        shutil.rmtree(private_root, ignore_errors=True)


def main(argv):
    reject_secret_environment()
    reject_secret_arguments(argv)
    if not argv:
        usage()
    if argv[0] == "launch":
        if len(argv) == 2 and argv[1] == EXPECTED_FIXTURE:
            launch(None)
            return 0
        if len(argv) == 3 and argv[2] == EXPECTED_FIXTURE:
            launch(argv[1])
            return 0
        usage()
    if argv[0] == "stop" and len(argv) == 2:
        stop(argv[1])
        return 0
    usage()


try:
    sys.exit(main(sys.argv[1:]))
except UsageFailure as error:
    print(f"phantom-preview-launch: {error}", file=sys.stderr)
    sys.exit(2)
except LaunchFailure:
    print("phantom-preview-launch: request refused or launch failed", file=sys.stderr)
    sys.exit(1)
except Exception:
    # Never expose raw subprocess, filesystem, or parser diagnostics to the
    # caller; all unexpected failures use the same bounded safe error.
    print("phantom-preview-launch: request refused or launch failed", file=sys.stderr)
    sys.exit(1)
except KeyboardInterrupt:
    print("phantom-preview-launch: launch interrupted", file=sys.stderr)
    sys.exit(130)
PY
