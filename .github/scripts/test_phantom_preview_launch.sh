#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export SCRIPT_DIR
exec /usr/bin/python3 - <<'PY'
import hashlib
import json
import os
import signal
import shutil
import stat
import subprocess
import sys
import tempfile
import time
from pathlib import Path

script_dir = Path(os.environ["SCRIPT_DIR"])
launcher = script_dir / "phantom-preview-launch.sh"

# TDD RED checkpoint: this test suite must fail for the feature-missing state.
if not launcher.is_file():
    print("RED: phantom-preview-launch.sh is missing (expected before implementation)", file=sys.stderr)
    raise SystemExit(1)

SOURCE_ROOT = script_dir.parent.parent
FIXTURE_REL = Path("shared/src/iosSimulatorArm64Main/kotlin/com/devil/phoenixproject/fixture/SimulatorLaunchFixture.kt")
PROJECT_REL = Path("iosApp/VitruvianPhoenix/VitruvianPhoenix.xcodeproj/project.pbxproj")
CONFIG_REL = Path("iosApp/VitruvianPhoenix/Config/Supabase.xcconfig")
EXPECTED_FIXTURE_SHA256 = "e180679548a2d96dbc59c51449edb3b99c19d3e3be82eca98c0707a21a64e78e"
EXPECTED_UDID = "678A4E3B-6A1F-469C-8068-9A2608A85783"
EXPECTED_SIMULATOR_NAME = "Phantom Harness iPhone 17 Pro"
EXPECTED_BUNDLE = "com.devil.phoenixproject.projectphoenix"
EXPECTED_LAUNCH_COMMAND = "phantom-preview-launch.sh launch [WORKTREE] just-lift-connected"
EXPECTED_CLEANUP_COMMAND = "phantom-preview-launch.sh stop SESSION_DESCRIPTOR"
ALLOWED_CHILD_ENV = {
    "PATH",
    "JAVA_HOME",
    "HOME",
    "TMPDIR",
    "GRADLE_USER_HOME",
    "LANG",
    "LC_ALL",
    "PHOENIX_SIMULATOR_FIXTURE",
    "SIMCTL_CHILD_PHOENIX_SIMULATOR_FIXTURE",
    "PHOENIX_PREVIEW_LAUNCH",
    "SDKROOT",
    "CPATH",
    "MANPATH",
    "LIBRARY_PATH",
    "__CF_USER_TEXT_ENCODING",
}


def fail(message):
    raise AssertionError(message)


def run(command, *, cwd=None, env=None):
    return subprocess.run(
        command,
        cwd=cwd,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def git(root, *args, check=True):
    result = run(["git", "-C", str(root), *args])
    if check and result.returncode != 0:
        fail(f"git failed: {args}: {result.stderr}")
    return result


def write_executable(path, content):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    path.chmod(0o700)


def make_fake_tools(root, state):
    fake_bin = root / "fake-bin"
    fake_bin.mkdir(mode=0o700)
    env_log = state / "child-env.jsonl"
    command_log = state / "commands.log"
    target_literal = str(root / "host")
    state_literal = str(state)

    write_executable(
        fake_bin / "xcrun",
        f'''#!/usr/bin/env python3
import json
import os
import signal
import sys
import subprocess
import time
from pathlib import Path

state = Path({state_literal!r})
log = state / "commands.log"
args = sys.argv[1:]
with log.open("a", encoding="utf-8") as stream:
    stream.write("xcrun " + " ".join(args) + "\\n")
with (state / "child-env.jsonl").open("a", encoding="utf-8") as stream:
    stream.write(json.dumps(dict(sorted(os.environ.items())), sort_keys=True) + "\\n")
if args[:3] == ["simctl", "list", "devices"]:
    if (state / "fail-device-output").exists():
        print("{{malformed", end="")
        raise SystemExit(0)
    if (state / "fail-process-group").exists():
        child_code = "import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(60)"
        child = subprocess.Popen([sys.executable, "-c", child_code])
        (state / "descendant-pid").write_text(str(child.pid), encoding="ascii")
        signal.signal(signal.SIGTERM, lambda _signum, _frame: os._exit(0))
        time.sleep(60)
    print(json.dumps({{"devices": {{"com.apple.CoreSimulator.SimRuntime.iOS-26-5": [{{
        "state": "Booted" if (state / "booted").exists() else "Shutdown",
        "isAvailable": True,
        "name": "{EXPECTED_SIMULATOR_NAME}",
        "udid": "{EXPECTED_UDID}",
    }}]}}}}))
    raise SystemExit(0)
if len(args) >= 3 and args[:2] == ["simctl", "boot"]:
    if (state / "fail-boot").exists():
        raise SystemExit(31)
    (state / "booted").write_text("booted\\n", encoding="ascii")
    raise SystemExit(0)
if len(args) >= 3 and args[:2] == ["simctl", "bootstatus"]:
    raise SystemExit(0 if (state / "booted").exists() else 1)
if len(args) >= 4 and args[:2] == ["simctl", "install"]:
    if (state / "fail-install").exists():
        raise SystemExit(23)
    (state / "installed").write_text(args[3], encoding="utf-8")
    raise SystemExit(0)
if len(args) >= 4 and args[:2] == ["simctl", "launch"]:
    with (state / "simctl-launch-env.jsonl").open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(dict(sorted(os.environ.items())), sort_keys=True) + "\\n")
    if os.environ.get("SIMCTL_CHILD_PHOENIX_SIMULATOR_FIXTURE") != "just-lift-connected":
        raise SystemExit(25)
    if (state / "fail-launch").exists():
        raise SystemExit(24)
    (state / "running").write_text(args[3], encoding="utf-8")
    raise SystemExit(0)
if len(args) >= 4 and args[:2] == ["simctl", "terminate"]:
    (state / "terminate-called").write_text("yes\\n", encoding="ascii")
    (state / "running").unlink(missing_ok=True)
    raise SystemExit(0)
if len(args) >= 4 and args[:2] == ["simctl", "uninstall"]:
    (state / "uninstall-called").write_text("yes\\n", encoding="ascii")
    (state / "installed").unlink(missing_ok=True)
    raise SystemExit(0)
print("unexpected xcrun command", file=sys.stderr)
raise SystemExit(2)
''',
    )

    write_executable(
        fake_bin / "xcodebuild",
        f'''#!/usr/bin/env python3
import json
import os
import subprocess
import sys
from pathlib import Path

state = Path({state_literal!r})
target = Path({target_literal!r})
args = sys.argv[1:]
with (state / "commands.log").open("a", encoding="utf-8") as stream:
    stream.write("xcodebuild " + " ".join(args) + "\\n")
with (state / "child-env.jsonl").open("a", encoding="utf-8") as stream:
    stream.write(json.dumps(dict(sorted(os.environ.items())), sort_keys=True) + "\\n")
if args == ["-version"]:
    print("Xcode 26.5")
    print("Build version fake")
    raise SystemExit(0)
if "build" not in args or "test" in args:
    print("unexpected xcodebuild command", file=sys.stderr)
    raise SystemExit(2)
if (state / "fail-build").exists():
    print("fake build failure", file=sys.stderr)
    raise SystemExit(17)
subprocess.run(
    [str(target / "gradlew"), ":shared:iosSimulatorArm64ProcessResources", ":shared:embedAndSignAppleFrameworkForXcode", "-Pskip.supabase.check=true"],
    cwd=target,
    env=os.environ.copy(),
    check=True,
)
derived = Path(args[args.index("-derivedDataPath") + 1])
app = derived / "Build" / "Products" / "Debug-iphonesimulator" / "VitruvianPhoenix.app"
app.mkdir(mode=0o700, parents=True)
(app / "placeholder").write_text("real app placeholder\\n", encoding="utf-8")
print("Build Succeeded")
raise SystemExit(0)
''',
    )

    write_executable(
        fake_bin / "gradlew",
        f'''#!/usr/bin/env python3
import json
import os
import sys
from pathlib import Path

state = Path({state_literal!r})
with (state / "commands.log").open("a", encoding="utf-8") as stream:
    stream.write("gradle " + " ".join(sys.argv[1:]) + "\\n")
with (state / "child-env.jsonl").open("a", encoding="utf-8") as stream:
    stream.write(json.dumps(dict(sorted(os.environ.items())), sort_keys=True) + "\\n")
if (state / "fail-gradle").exists():
    raise SystemExit(19)
Path.cwd().joinpath(".gradle").mkdir(mode=0o700, exist_ok=True)
Path.cwd().joinpath(".gradle", "fake-cache").write_text("ignored build cache\\n", encoding="utf-8")
Path.cwd().joinpath("shared", "build").mkdir(mode=0o700, parents=True, exist_ok=True)
Path.cwd().joinpath("shared", "build", "fake-output").write_text("ignored generated output\\n", encoding="utf-8")
raise SystemExit(0)
''',
    )
    return fake_bin, env_log, command_log


def make_repo(root, state):
    host = root / "host"
    (host / ".github/scripts").mkdir(mode=0o700, parents=True)
    (host / FIXTURE_REL.parent).mkdir(mode=0o700, parents=True)
    (host / PROJECT_REL.parent).mkdir(mode=0o700, parents=True)
    (host / CONFIG_REL.parent).mkdir(mode=0o700, parents=True)
    shutil.copy2(launcher, host / ".github/scripts/phantom-preview-launch.sh")
    shutil.copy2(SOURCE_ROOT / FIXTURE_REL, host / FIXTURE_REL)
    shutil.copy2(SOURCE_ROOT / PROJECT_REL, host / PROJECT_REL)
    (host / CONFIG_REL.parent / "SupabaseBase.xcconfig").write_text("// tracked config directory marker\\n", encoding="utf-8")
    (host / ".gitignore").write_text(
        "iosApp/VitruvianPhoenix/Config/Supabase.xcconfig\n"
        ".gradle/\nshared/build/\nbuild/\n",
        encoding="utf-8",
    )
    gradlew = host / "gradlew"
    state_literal = str(state)
    write_executable(
        gradlew,
        f'''#!/usr/bin/env python3
import os
import sys
from pathlib import Path
state = Path({state_literal!r})
with (state / "commands.log").open("a", encoding="utf-8") as stream:
    stream.write("gradle-wrapper " + " ".join(sys.argv[1:]) + "\\n")
if (state / "fail-gradle").exists():
    raise SystemExit(19)
raise SystemExit(0)
''',
    )
    git(host, "init", "-q")
    git(host, "config", "user.email", "test@example.invalid")
    git(host, "config", "user.name", "Phantom launcher test")
    git(host, "add", ".")
    git(host, "commit", "-qm", "launcher fixture")
    return host


def clean_status(root):
    return git(root, "status", "--porcelain=v2", "--untracked-files=all", "--ignored=no").stdout


def base_sha(root):
    return git(root, "rev-parse", "--verify", "HEAD").stdout.strip()


def invocation_env(fake_bin, root, java_home, inherited_value="not-a-secret"):
    return {
        "PATH": f"{fake_bin}:/usr/bin:/bin:/usr/sbin:/sbin",
        "HOME": str(root / "test-home"),
        "TMPDIR": str(root / "test-tmp"),
        "JAVA_HOME": str(java_home),
        "PHOENIX_PREVIEW_LAUNCH_TEST_BIN": str(fake_bin),
        "UNSAFE_INHERITED_VALUE": inherited_value,
        "INHERITED_MARKER": "must-not-reach-child",
    }


def run_launch(host, fake_bin, root, java_home, *args, inherited_value="not-a-secret"):
    env = invocation_env(fake_bin, root, java_home, inherited_value)
    return run([str(host / ".github/scripts/phantom-preview-launch.sh"), *args], cwd=host, env=env)


def parse_descriptor(result):
    if result.returncode != 0:
        fail(f"launcher failed: stdout={result.stdout!r} stderr={result.stderr!r}")
    lines = result.stdout.strip().splitlines()
    if len(lines) != 1:
        fail(f"expected exactly one JSON descriptor line, got {lines!r}; stderr={result.stderr!r}")
    try:
        descriptor = json.loads(lines[0])
    except json.JSONDecodeError as error:
        fail(f"descriptor was not JSON: {error}: {lines!r}")
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
    if set(descriptor) != expected_keys:
        fail(f"descriptor schema mismatch: {descriptor!r}")
    if descriptor["schemaVersion"] != 1 or descriptor["status"] != "running":
        fail(f"descriptor status/schema mismatch: {descriptor!r}")
    if descriptor["simulatorUdid"] != EXPECTED_UDID or descriptor["bundleId"] != EXPECTED_BUNDLE:
        fail(f"descriptor simulator identity mismatch: {descriptor!r}")
    if descriptor["fixture"] != "just-lift-connected" or not isinstance(descriptor["baseSha"], str) or len(descriptor["baseSha"]) != 40:
        fail(f"descriptor provenance mismatch: {descriptor!r}")
    if descriptor["launchCommand"] != EXPECTED_LAUNCH_COMMAND or descriptor["cleanupCommand"] != EXPECTED_CLEANUP_COMMAND:
        fail(f"descriptor command contract mismatch: {descriptor!r}")
    encoded = json.dumps(descriptor, sort_keys=True)
    if "/" in encoded or "\\" in encoded or "UNSAFE_INHERITED_VALUE" in encoded or "INHERITED_MARKER" in encoded:
        fail(f"descriptor leaked an unsafe path/value: {encoded}")
    if result.stderr:
        fail(f"successful launch wrote unexpected public stderr: {result.stderr!r}")
    return descriptor


def assert_rejected(result, message, command_log):
    if result.returncode == 0:
        fail(message + ": accepted")
    if result.stdout.strip():
        fail(message + f": emitted public stdout {result.stdout!r}")
    if "/" in result.stderr or "UNSAFE_INHERITED_VALUE" in result.stderr:
        fail(message + f": leaked unsafe stderr {result.stderr!r}")
    if command_log.read_text(encoding="utf-8"):
        fail(message + ": reached fake simulator/build tools")


def assert_safe_failed(result, message):
    if result.returncode == 0:
        fail(message + ": accepted")
    if result.stdout.strip():
        fail(message + f": emitted public stdout {result.stdout!r}")
    if "Traceback" in result.stderr or "/" in result.stderr or "UNSAFE_INHERITED_VALUE" in result.stderr:
        fail(message + f": leaked unsafe diagnostics {result.stderr!r}")


with tempfile.TemporaryDirectory(prefix="phantom-preview-launch-test-") as temp_name:
    root = Path(temp_name)
    state = root / "state"
    state.mkdir(mode=0o700)
    (root / "test-home").mkdir(mode=0o700)
    (root / "test-tmp").mkdir(mode=0o700)
    canonical_java_home = root / "cellar" / "openjdk@21" / "21.0.8" / "libexec" / "openjdk.jdk" / "Contents" / "Home"
    (canonical_java_home / "bin").mkdir(mode=0o700, parents=True)
    write_executable(
        canonical_java_home / "bin/java",
        '#!/usr/bin/env bash\nif [[ "${1-}" == "-version" ]]; then printf \'openjdk version "21.0.8" fake\\n\' >&2; exit 0; fi\nexit 2\n',
    )
    java_home = root / "package-manager" / "opt" / "openjdk@21"
    java_home.parent.mkdir(mode=0o700, parents=True)
    java_home.symlink_to(canonical_java_home, target_is_directory=True)
    host = make_repo(root, state)
    fake_bin, env_log, command_log = make_fake_tools(root, state)
    command_log.write_text("", encoding="utf-8")

    # Clean canonical host/current-ref eligibility and persistent app state.
    clean_before = clean_status(host)
    if clean_before:
        fail(f"fixture host unexpectedly dirty before launch: {clean_before!r}")
    head_before = base_sha(host)
    descriptor = parse_descriptor(run_launch(host, fake_bin, root, java_home, "launch", "just-lift-connected"))
    if not (state / "booted").exists() or not (state / "installed").exists() or not (state / "running").exists():
        fail("successful launch did not retain the booted simulator/app")
    if (host / CONFIG_REL).exists():
        fail("temporary Supabase config was not removed after successful launch")
    if clean_status(host) != clean_before or base_sha(host) != head_before:
        fail("successful launch mutated the clean host HEAD/status")
    commands = command_log.read_text(encoding="utf-8").splitlines()
    if not any(line.startswith("xcrun simctl install ") for line in commands) or not any(line.startswith("xcrun simctl launch ") for line in commands):
        fail(f"install/launch were not issued: {commands!r}")
    if any(line.startswith("xcrun simctl terminate ") or line.startswith("xcrun simctl uninstall ") for line in commands):
        fail("successful launch terminated or uninstalled the app")
    if sum(line.startswith("xcrun simctl boot ") for line in commands) != 1:
        fail(f"first launch did not boot exactly once: {commands!r}")

    for line in env_log.read_text(encoding="utf-8").splitlines():
        child_env = json.loads(line)
        unexpected = set(child_env) - ALLOWED_CHILD_ENV
        if unexpected:
            fail(f"child environment was not minimal: {unexpected!r}")
        if "UNSAFE_INHERITED_VALUE" in child_env or "INHERITED_MARKER" in child_env:
            fail("inherited values leaked to fake Apple/Gradle tools")
        if child_env.get("PHOENIX_SIMULATOR_FIXTURE") != "just-lift-connected":
            fail("fixture was not present in child environment")
        if child_env.get("PHOENIX_PREVIEW_LAUNCH") != "1":
            fail("persistent-launch marker was not present in child environment")
        if child_env.get("JAVA_HOME") != str(canonical_java_home.resolve()):
            fail(f"child did not receive canonical package-manager JAVA_HOME: {child_env.get('JAVA_HOME')!r}")

    launch_envs = [
        json.loads(line)
        for line in (state / "simctl-launch-env.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    if len(launch_envs) != 1 or launch_envs[0].get("SIMCTL_CHILD_PHOENIX_SIMULATOR_FIXTURE") != "just-lift-connected":
        fail(f"simctl launch did not receive the exact child fixture environment: {launch_envs!r}")

    # Reuse the booted simulator through a caller-owned registered worktree.
    worktree = root / "clean-worktree"
    git(host, "worktree", "add", "--detach", str(worktree), "HEAD")
    try:
        command_log.write_text("", encoding="utf-8")
        second = parse_descriptor(run_launch(host, fake_bin, root, java_home, "launch", str(worktree), "just-lift-connected"))
        if second["baseSha"] != head_before:
            fail("registered worktree descriptor was not bound to its HEAD")
        commands = command_log.read_text(encoding="utf-8").splitlines()
        if any(line.startswith("xcrun simctl boot ") for line in commands):
            fail("booted simulator was not reused")
        if not (state / "running").exists():
            fail("worktree launch did not leave the app running")
    finally:
        git(host, "worktree", "remove", "--force", str(worktree), check=False)

    # Stop is explicitly descriptor-bound and performs only fixed app cleanup.
    descriptor_path = root / "session.json"
    descriptor_path.write_text(json.dumps(descriptor, sort_keys=True) + "\n", encoding="utf-8")
    descriptor_path.chmod(0o600)
    # Cleanup must make the exact descriptor-bound simulator usable before
    # terminate/uninstall, even when the simulator is currently shut down.
    (state / "booted").unlink()
    command_log.write_text("", encoding="utf-8")
    stopped = run([str(host / ".github/scripts/phantom-preview-launch.sh"), "stop", str(descriptor_path)], cwd=root, env=invocation_env(fake_bin, root, java_home))
    if stopped.returncode != 0 or stopped.stderr or len(stopped.stdout.strip().splitlines()) != 1:
        fail(f"stop did not emit one safe descriptor: {stopped.stdout!r} {stopped.stderr!r}")
    stopped_descriptor = json.loads(stopped.stdout)
    if set(stopped_descriptor) != set(descriptor) or stopped_descriptor["status"] != "stopped":
        fail(f"stop descriptor mismatch: {stopped_descriptor!r}")
    if (state / "running").exists() or (state / "installed").exists():
        fail("stop did not terminate/uninstall the described app")
    stop_commands = command_log.read_text(encoding="utf-8").splitlines()
    if stop_commands != [
        "xcrun simctl list devices -j",
        f"xcrun simctl boot {EXPECTED_UDID}",
        f"xcrun simctl bootstatus {EXPECTED_UDID} -b",
        f"xcrun simctl terminate {EXPECTED_UDID} {EXPECTED_BUNDLE}",
        f"xcrun simctl uninstall {EXPECTED_UDID} {EXPECTED_BUNDLE}",
    ]:
        fail(f"stop issued an unsafe/unexpected command set: {stop_commands!r}")
    if not (state / "booted").exists():
        fail("stopped simulator cleanup did not leave the descriptor-bound simulator booted")

    # A simulator boot failure is reported through the same generic safe
    # boundary as malformed device output; no raw subprocess diagnostics leak.
    (state / "booted").unlink()
    (state / "fail-boot").write_text("fail\n", encoding="ascii")
    command_log.write_text("", encoding="utf-8")
    boot_failure = run(
        [str(host / ".github/scripts/phantom-preview-launch.sh"), "stop", str(descriptor_path)],
        cwd=root,
        env=invocation_env(fake_bin, root, java_home),
    )
    assert_safe_failed(boot_failure, "simulator stop boot failure")
    boot_failure_commands = command_log.read_text(encoding="utf-8").splitlines()
    if any(line.startswith("xcrun simctl terminate ") or line.startswith("xcrun simctl uninstall ") for line in boot_failure_commands):
        fail("stop boot failure attempted terminate/uninstall")
    (state / "fail-boot").unlink()

    # Unknown fixtures, unsafe argument shapes, and secret-bearing environment values fail before tools.
    for args, label in [
        (("launch", "unknown-fixture"), "unknown fixture"),
        (("launch", "just-lift-connected", "extra"), "extra argument"),
        (("launch", str(host / ".." / host.name), "just-lift-connected"), "path traversal"),
    ]:
        command_log.write_text("", encoding="utf-8")
        assert_rejected(run_launch(host, fake_bin, root, java_home, *args), label, command_log)
    command_log.write_text("", encoding="utf-8")
    secret_env = invocation_env(fake_bin, root, java_home)
    secret_env["GITHUB_TOKEN"] = "not-a-real-token-but-refuse-it"
    assert_rejected(run([str(host / ".github/scripts/phantom-preview-launch.sh"), "launch", "just-lift-connected"], cwd=host, env=secret_env), "credential-like environment", command_log)

    # Explicitly empty/invalid JAVA_HOME fails closed; the launcher must not
    # silently use /usr/bin/java or another PATH fallback.
    for java_value, label in [("", "empty JAVA_HOME"), (str(root / "missing-jdk"), "missing JAVA_HOME")]:
        command_log.write_text("", encoding="utf-8")
        bad_java_env = invocation_env(fake_bin, root, java_home)
        bad_java_env["JAVA_HOME"] = java_value
        assert_rejected(
            run([str(host / ".github/scripts/phantom-preview-launch.sh"), "launch", "just-lift-connected"], cwd=host, env=bad_java_env),
            label,
            command_log,
        )
    unsafe_home = root / "unsafe-home-jdk"
    shutil.copytree(canonical_java_home, unsafe_home)
    unsafe_home.chmod(0o777)
    unsafe_java_env = invocation_env(fake_bin, root, unsafe_home)
    command_log.write_text("", encoding="utf-8")
    assert_rejected(
        run([str(host / ".github/scripts/phantom-preview-launch.sh"), "launch", "just-lift-connected"], cwd=host, env=unsafe_java_env),
        "group/world-writable JDK home",
        command_log,
    )
    unsafe_executable_home = root / "unsafe-executable-jdk"
    shutil.copytree(canonical_java_home, unsafe_executable_home)
    (unsafe_executable_home / "bin/java").chmod(0o777)
    unsafe_executable_env = invocation_env(fake_bin, root, unsafe_executable_home)
    command_log.write_text("", encoding="utf-8")
    assert_rejected(
        run([str(host / ".github/scripts/phantom-preview-launch.sh"), "launch", "just-lift-connected"], cwd=host, env=unsafe_executable_env),
        "group/world-writable Java executable",
        command_log,
    )
    symlink_executable_home = root / "symlink-executable-jdk"
    (symlink_executable_home / "bin").mkdir(mode=0o700, parents=True)
    (symlink_executable_home / "bin/java").symlink_to(canonical_java_home / "bin/java")
    symlink_executable_env = invocation_env(fake_bin, root, symlink_executable_home)
    command_log.write_text("", encoding="utf-8")
    assert_rejected(
        run([str(host / ".github/scripts/phantom-preview-launch.sh"), "launch", "just-lift-connected"], cwd=host, env=symlink_executable_env),
        "symlink Java executable",
        command_log,
    )

    # Dirty tracked and untracked source is rejected. Build output remains ignored and harmless.
    tracked = host / "shared" / "tracked-source.txt"
    tracked.write_text("tracked source\\n", encoding="utf-8")
    git(host, "add", tracked)
    git(host, "commit", "-qm", "tracked dirty fixture")
    tracked.write_text("dirty tracked source\\n", encoding="utf-8")
    command_log.write_text("", encoding="utf-8")
    assert_rejected(run_launch(host, fake_bin, root, java_home, "launch", "just-lift-connected"), "dirty tracked source", command_log)
    tracked.write_text("tracked source\\n", encoding="utf-8")
    untracked = host / "Unsafe.swift"
    untracked.write_text("untracked source\\n", encoding="utf-8")
    command_log.write_text("", encoding="utf-8")
    assert_rejected(run_launch(host, fake_bin, root, java_home, "launch", "just-lift-connected"), "dirty untracked source", command_log)
    untracked.unlink()
    git(host, "reset", "--hard", "HEAD~1")
    if clean_status(host):
        fail("dirty-source test cleanup did not restore a clean host")

    # Registered but protected original checkout, symlink, nested, and foreign repositories are refused.
    protected = root / "project-phoenix-mp"
    git(host, "worktree", "add", "--detach", str(protected), "HEAD")
    try:
        command_log.write_text("", encoding="utf-8")
        assert_rejected(run_launch(host, fake_bin, root, java_home, "launch", str(protected), "just-lift-connected"), "protected original checkout", command_log)
    finally:
        git(host, "worktree", "remove", "--force", str(protected), check=False)
    symlink = root / "symlink-worktree"
    symlink.symlink_to(host, target_is_directory=True)
    command_log.write_text("", encoding="utf-8")
    assert_rejected(run_launch(host, fake_bin, root, java_home, "launch", str(symlink), "just-lift-connected"), "symlink worktree", command_log)
    nested = host / "nested"
    nested.mkdir()
    command_log.write_text("", encoding="utf-8")
    assert_rejected(run_launch(host, fake_bin, root, java_home, "launch", str(nested), "just-lift-connected"), "nested non-worktree", command_log)
    nested.rmdir()
    foreign = root / "foreign"
    foreign.mkdir(mode=0o700)
    git(foreign, "init", "-q")
    git(foreign, "config", "user.email", "test@example.invalid")
    git(foreign, "config", "user.name", "Foreign")
    (foreign / "README").write_text("foreign\\n", encoding="utf-8")
    git(foreign, "add", ".")
    git(foreign, "commit", "-qm", "foreign")
    command_log.write_text("", encoding="utf-8")
    assert_rejected(run_launch(host, fake_bin, root, java_home, "launch", str(foreign), "just-lift-connected"), "foreign repository", command_log)

    # Descriptor validation rejects tampering before any fixed cleanup action.
    tampered = json.loads(descriptor_path.read_text(encoding="utf-8"))
    tampered["simulatorUdid"] = "11111111-2222-3333-4444-555555555555"
    descriptor_path.write_text(json.dumps(tampered) + "\n", encoding="utf-8")
    descriptor_path.chmod(0o600)
    command_log.write_text("", encoding="utf-8")
    assert_rejected(run([str(host / ".github/scripts/phantom-preview-launch.sh"), "stop", str(descriptor_path)], cwd=root, env=invocation_env(fake_bin, root, java_home)), "tampered descriptor", command_log)
    descriptor_path.write_text(json.dumps(descriptor) + "\n", encoding="utf-8")
    descriptor_path.chmod(0o600)

    malformed_descriptor_cases = [
        ('{"schemaVersion":', "malformed descriptor JSON"),
        (json.dumps({**descriptor, "schemaVersion": True}) + "\n", "boolean schemaVersion"),
        (
            "{" + ",".join(
                [json.dumps(key) + ":" + json.dumps(value) for key, value in descriptor.items()]
                + [json.dumps("schemaVersion") + ":1"]
            ) + "}\n",
            "duplicate descriptor key",
        ),
        (json.dumps({**descriptor, "launchCommand": "/Users/host/unsafe-command"}) + "\n", "host path descriptor command"),
    ]
    for payload, label in malformed_descriptor_cases:
        descriptor_path.write_text(payload, encoding="utf-8")
        descriptor_path.chmod(0o600)
        command_log.write_text("", encoding="utf-8")
        assert_rejected(
            run([str(host / ".github/scripts/phantom-preview-launch.sh"), "stop", str(descriptor_path)], cwd=root, env=invocation_env(fake_bin, root, java_home)),
            label,
            command_log,
        )
    secret_descriptor_path = root / "secret-session.json"
    secret_descriptor_path.write_text(json.dumps(descriptor) + "\n", encoding="utf-8")
    secret_descriptor_path.chmod(0o600)
    command_log.write_text("", encoding="utf-8")
    assert_rejected(
        run([str(host / ".github/scripts/phantom-preview-launch.sh"), "stop", str(secret_descriptor_path)], cwd=root, env=invocation_env(fake_bin, root, java_home)),
        "credential-like descriptor path",
        command_log,
    )
    descriptor_path.write_text(json.dumps(descriptor) + "\n", encoding="utf-8")
    descriptor_path.chmod(0o600)

    # Malformed simulator JSON is a generic safe failure, not a traceback or
    # a diagnostic containing the private fixture path.
    (state / "fail-device-output").write_text("malformed\\n", encoding="ascii")
    command_log.write_text("", encoding="utf-8")
    malformed_device = run_launch(host, fake_bin, root, java_home, "launch", "just-lift-connected")
    assert_safe_failed(malformed_device, "malformed simulator device output")
    if (host / CONFIG_REL).exists() or (state / "running").exists() or (state / "installed").exists():
        fail("malformed simulator output left app/config state behind")
    command_log.write_text("", encoding="utf-8")
    malformed_stop_device = run(
        [str(host / ".github/scripts/phantom-preview-launch.sh"), "stop", str(descriptor_path)],
        cwd=root,
        env=invocation_env(fake_bin, root, java_home),
    )
    assert_safe_failed(malformed_stop_device, "malformed simulator stop device output")
    (state / "fail-device-output").unlink()

    # Build/Gradle failure cleans the temporary config and leaves no app state or public logs.
    (state / "fail-gradle").write_text("fail\\n", encoding="ascii")
    command_log.write_text("", encoding="utf-8")
    failure = run_launch(host, fake_bin, root, java_home, "launch", "just-lift-connected")
    if failure.returncode == 0 or failure.stdout.strip():
        fail(f"failed launch did not fail quietly: {failure.returncode} {failure.stdout!r}")
    if "/" in failure.stderr or "fake build failure" in failure.stderr:
        fail(f"failed launch leaked raw path/build output: {failure.stderr!r}")
    if (host / CONFIG_REL).exists() or (state / "running").exists() or (state / "installed").exists():
        fail("failed launch did not clean temporary config/app state")
    if any(line.startswith("xcrun simctl terminate ") or line.startswith("xcrun simctl uninstall ") for line in command_log.read_text(encoding="utf-8").splitlines()):
        fail("pre-install Gradle failure attempted app cleanup")
    (state / "fail-gradle").unlink()
    if clean_status(host):
        fail("failure cleanup mutated source status")

    # Install failure occurs before the app is installed, so no terminate or
    # uninstall cleanup is appropriate.
    (state / "fail-install").write_text("fail\\n", encoding="ascii")
    command_log.write_text("", encoding="utf-8")
    install_failure = run_launch(host, fake_bin, root, java_home, "launch", "just-lift-connected")
    assert_safe_failed(install_failure, "simulator install failure")
    install_commands = command_log.read_text(encoding="utf-8").splitlines()
    if any(line.startswith("xcrun simctl terminate ") or line.startswith("xcrun simctl uninstall ") for line in install_commands):
        fail("failed install attempted terminate/uninstall before installation")
    if (host / CONFIG_REL).exists() or (state / "running").exists() or (state / "installed").exists():
        fail("failed install did not clean temporary config/app state")
    (state / "fail-install").unlink()
    if clean_status(host):
        fail("failed install mutated source status")

    # Launch failure occurs after installation, so cleanup must terminate and
    # uninstall exactly once and still emit no descriptor.
    (state / "fail-launch").write_text("fail\\n", encoding="ascii")
    (state / "terminate-called").unlink(missing_ok=True)
    (state / "uninstall-called").unlink(missing_ok=True)
    command_log.write_text("", encoding="utf-8")
    launch_failure = run_launch(host, fake_bin, root, java_home, "launch", "just-lift-connected")
    assert_safe_failed(launch_failure, "simulator launch failure")
    launch_failure_commands = command_log.read_text(encoding="utf-8").splitlines()
    if sum(line.startswith("xcrun simctl terminate ") for line in launch_failure_commands) != 1:
        fail(f"failed launch did not terminate exactly once: {launch_failure_commands!r}")
    if sum(line.startswith("xcrun simctl uninstall ") for line in launch_failure_commands) != 1:
        fail(f"failed launch did not uninstall exactly once: {launch_failure_commands!r}")
    if (state / "running").exists() or (state / "installed").exists() or (host / CONFIG_REL).exists():
        fail("failed launch did not clean installed app/config state")
    (state / "fail-launch").unlink()
    if clean_status(host):
        fail("failed launch mutated source status")

    # A stubborn descendant must not survive timeout cleanup after its leader
    # exits during TERM grace. Bound only this disposable fixture's launcher;
    # production keeps the full command timeout.
    bounded_launcher = host / ".github/scripts/phantom-preview-launch.sh"
    bounded_launcher.write_text(
        bounded_launcher.read_text(encoding="utf-8").replace(
            "COMMAND_TIMEOUT_SECONDS = 1800", "COMMAND_TIMEOUT_SECONDS = 1"
        ),
        encoding="utf-8",
    )
    git(host, "add", bounded_launcher)
    git(host, "commit", "-qm", "bounded process-group fixture")
    (state / "fail-process-group").write_text("fail\n", encoding="ascii")
    (state / "descendant-pid").unlink(missing_ok=True)
    command_log.write_text("", encoding="utf-8")
    process_group_failure = run_launch(host, fake_bin, root, java_home, "launch", "just-lift-connected")
    assert_safe_failed(process_group_failure, "stubborn process-group descendant")
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline and not (state / "descendant-pid").exists():
        time.sleep(0.02)
    if not (state / "descendant-pid").exists():
        fail("stubborn process-group fixture did not start its descendant")
    descendant_pid = int((state / "descendant-pid").read_text(encoding="ascii"))
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        try:
            os.kill(descendant_pid, 0)
        except ProcessLookupError:
            break
        time.sleep(0.02)
    else:
        os.kill(descendant_pid, signal.SIGKILL)
        fail("stubborn process-group descendant survived cleanup")
    (state / "fail-process-group").unlink()

print("GREEN: phantom persistent launch contract, safety gates, descriptor validation, cleanup, and minimal env passed")
PY
