#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REAL_GIT="$(command -v git)"
SOURCE_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RENDERER="$SCRIPT_DIR/phantom-proposal.sh"
VERIFY_SOURCE="$SCRIPT_DIR/phantom-harness-verify.py"
FIXTURE_SOURCE="$SOURCE_ROOT/shared/src/iosSimulatorArm64Main/kotlin/com/devil/phoenixproject/fixture/SimulatorLaunchFixture.kt"
TMP_DIR="$(python3 - <<'PY'
import tempfile
print(tempfile.mkdtemp(prefix="phantom-proposal-test-"))
PY
)"
FAKE_BIN="$TMP_DIR/bin"
mkdir -p "$FAKE_BIN" "$TMP_DIR/home" "$TMP_DIR/os-tmp"
chmod 700 "$TMP_DIR/home" "$TMP_DIR/os-tmp"
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
PARENT_AUTH_HINT='PARENT_REDACTED_VALUE'
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

make_fake_repo() {
    local repo="$1"
    local baseline="${2-// baseline}"
    mkdir -p "$repo/.github/scripts" "$repo/shared/src/iosSimulatorArm64Main/kotlin/com/devil/phoenixproject/fixture" "$repo/iosApp/VitruvianPhoenix/VitruvianPhoenix"
    cp "$VERIFY_SOURCE" "$repo/.github/scripts/phantom-harness-verify.py"
    cp "$FIXTURE_SOURCE" "$repo/shared/src/iosSimulatorArm64Main/kotlin/com/devil/phoenixproject/fixture/SimulatorLaunchFixture.kt"
    cp "$RENDERER" "$repo/.github/scripts/phantom-proposal.sh"
    chmod 600 "$repo/.github/scripts/phantom-harness-verify.py"
    chmod 700 "$repo/.github/scripts/phantom-proposal.sh"
    python3 - "$repo/iosApp/VitruvianPhoenix/VitruvianPhoenix/Proposal.swift" "$baseline" <<'PY'
import os
import sys
from pathlib import Path
path = Path(sys.argv[1])
path.write_text(sys.argv[2] + "\n", encoding="utf-8")
os.chmod(path, 0o600)
PY
    python3 - "$repo/iosApp/VitruvianPhoenix/VitruvianPhoenix/Binary.swift" <<'PY'
import os
import sys
from pathlib import Path
path = Path(sys.argv[1])
path.write_bytes(bytes([0]) + b"opaque-base")
os.chmod(path, 0o600)
PY
    python3 - "$repo/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Proposal.kt" <<'PY'
import os
import sys
from pathlib import Path
path = Path(sys.argv[1])
path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
path.write_text('// baseline kotlin\n', encoding='utf-8')
os.chmod(path, 0o600)
PY
    python3 - "$repo/iosApp/VitruvianPhoenix/VitruvianPhoenix/Unchanged.swift" "$repo/.gitignore" <<'PY'
import os
import sys
from pathlib import Path
tracked = Path(sys.argv[1])
tracked.write_text('// tracked candidate fixture\n', encoding='utf-8')
os.chmod(tracked, 0o600)
ignore = Path(sys.argv[2])
ignore.write_text(
    '.gradle/\n'
    'build/\n'
    'shared/build/\n'
    'androidApp/build/\n'
    'iosApp/VitruvianPhoenix/build/\n'
    'iosApp/VitruvianPhoenix/DerivedData/\n'
    'iosApp/VitruvianPhoenix/VitruvianPhoenix.xcodeproj/project.xcworkspace/\n'
    'unknown-generated/\n',
    encoding='utf-8',
)
os.chmod(ignore, 0o600)
PY
    python3 - "$repo/.github/scripts/phantom-harness.sh" "$TMP_DIR/proposal-child-env.jsonl" <<'PY'
import os
import sys
from pathlib import Path
runner = Path(sys.argv[1])
environment_log = sys.argv[2]
runner.write_text(r'''#!/usr/bin/env bash
set -euo pipefail

SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_REPO_ROOT="$(cd "$SCRIPT_ROOT/../.." && pwd)"
REPO_ROOT="$SCRIPT_REPO_ROOT"
if [[ -n "${PHOENIX_HARNESS_REPO_ROOT-}" ]]; then
    REPO_ROOT="$PHOENIX_HARNESS_REPO_ROOT"
fi
FIXTURE="$REPO_ROOT/shared/src/iosSimulatorArm64Main/kotlin/com/devil/phoenixproject/fixture/SimulatorLaunchFixture.kt"
VERIFY="$SCRIPT_ROOT/phantom-harness-verify.py"
PROPOSAL_ENV_LOG="__PROPOSAL_ENV_LOG__"

record_child_environment() {
    local action="${1-}"
    python3 - "$PROPOSAL_ENV_LOG" "$action" "$PWD" <<'PY_ENV'
import json
import os
import resource
import sys
from pathlib import Path
path = Path(sys.argv[1])
record = {
    "action": sys.argv[2],
    "cwd": sys.argv[3],
    "env": dict(sorted(os.environ.items())),
    "rlimitFsize": resource.getrlimit(resource.RLIMIT_FSIZE)[0],
    "rlimitNoFile": resource.getrlimit(resource.RLIMIT_NOFILE)[0],
}
with path.open("a", encoding="utf-8") as stream:
    stream.write(json.dumps(record, sort_keys=True) + "\n")
PY_ENV
}

write_png() {
    python3 - "$1" <<'PY2'
import struct
import sys
import zlib
from pathlib import Path
path = Path(sys.argv[1])
def chunk(kind, payload):
    return len(payload).to_bytes(4, "big") + kind + payload + zlib.crc32(kind + payload).to_bytes(4, "big")
ihdr = struct.pack(">IIBBBBB", 2, 2, 8, 6, 0, 0, 0)
pixels = b"\x00" + b"\x00" * 8 + b"\x00" + b"\x00" * 8
path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b""))
path.chmod(0o600)
PY2
}

write_diff_png() {
    python3 - "$1" <<'PY2'
import struct
import sys
import zlib
from pathlib import Path
path = Path(sys.argv[1])
def chunk(kind, payload):
    return len(payload).to_bytes(4, "big") + kind + payload + zlib.crc32(kind + payload).to_bytes(4, "big")
ihdr = struct.pack(">IIBBBBB", 2, 2, 8, 6, 0, 0, 0)
pixels = b"\x00" + b"\xff\x00\x00\xff" * 2 + b"\x00" + b"\xff\x00\x00\xff" * 2
path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b""))
path.chmod(0o600)
PY2
}

case "${1-}" in
case)
    artifact="$2"
    fixture="$3"
    [[ "$fixture" == just-lift-connected ]] || exit 1
    record_child_environment case
    if [[ -z "${JAVA_HOME-}" || ! -x "${JAVA_HOME-}/bin/java" ]] || ! "${JAVA_HOME-}/bin/java" -version >/dev/null 2>&1; then
        printf 'fake harness: child environment has no usable JAVA_HOME\n' >&2
        exit 1
    fi
    source_text="$(cat "$REPO_ROOT/iosApp/VitruvianPhoenix/VitruvianPhoenix/Proposal.swift")"
    if [[ "$(pwd -P)" == "$(cd "$REPO_ROOT" && pwd -P)" && "$source_text" == *FORGE_BEFORE* ]]; then
        source_text="$source_text FORGE_BASE"
    fi
    if [[ "$(pwd -P)" == "$(cd "$REPO_ROOT" && pwd -P)" && "$source_text" == *MUTATE_BASELINE* ]]; then
        printf 'mutated by baseline\n' > "$REPO_ROOT/iosApp/VitruvianPhoenix/VitruvianPhoenix/Original.swift"
        chmod 600 "$REPO_ROOT/iosApp/VitruvianPhoenix/VitruvianPhoenix/Original.swift"
    fi
    mkdir -p "$artifact"
    chmod 700 "$artifact"
    if [[ "$REPO_ROOT" != "$SCRIPT_REPO_ROOT" && "$source_text" == *SIGNAL* ]]; then
        : > "$artifact/.signal-ready"
        chmod 600 "$artifact/.signal-ready"
        while :; do sleep 1; done
    fi
    if [[ "$REPO_ROOT" != "$SCRIPT_REPO_ROOT" && "$source_text" == *TIMEOUT* ]]; then
        while :; do sleep 1; done
    fi
    if [[ "$REPO_ROOT" != "$SCRIPT_REPO_ROOT" && "$source_text" == *BLOAT* ]]; then
        python3 - "$TMPDIR/proposal-bloat" <<'PY2'
import os
import sys
from pathlib import Path
path = Path(sys.argv[1])
path.write_bytes(b"x" * (160 * 1024 * 1024))
path.chmod(0o600)
PY2
    fi
    if [[ "$REPO_ROOT" != "$SCRIPT_REPO_ROOT" && "$source_text" == *GRADLE_CACHE* ]]; then
        python3 - "$GRADLE_USER_HOME/legitimate-cache.bin" <<'PY2'
import os
import sys
from pathlib import Path
path = Path(sys.argv[1])
path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
with path.open("wb") as stream:
    stream.truncate(259 * 1024 * 1024)
path.chmod(0o600)
PY2
    fi
    if [[ "$REPO_ROOT" != "$SCRIPT_REPO_ROOT" && "$source_text" == *GENERATED_RUN* ]]; then
        mkdir -p "$PWD/iosApp/VitruvianPhoenix/build"
        printf 'generated by fake Xcode run\n' > "$PWD/iosApp/VitruvianPhoenix/build/run-output.bin"
        chmod 600 "$PWD/iosApp/VitruvianPhoenix/build/run-output.bin"
        mkdir -p "$PWD/iosApp/VitruvianPhoenix/VitruvianPhoenix.xcodeproj/project.xcworkspace"
        printf 'generated by fake Xcode run\n' > "$PWD/iosApp/VitruvianPhoenix/VitruvianPhoenix.xcodeproj/project.xcworkspace/contents.xcworkspacedata"
        chmod 600 "$PWD/iosApp/VitruvianPhoenix/VitruvianPhoenix.xcodeproj/project.xcworkspace/contents.xcworkspacedata"
    fi
    if [[ "$REPO_ROOT" != "$SCRIPT_REPO_ROOT" && "$source_text" == *GENERATED_UNKNOWN_XCODEPROJ* ]]; then
        mkdir -p "$PWD/iosApp/VitruvianPhoenix/Unknown.xcodeproj/project.xcworkspace"
        printf 'unexpected sibling Xcode workspace\n' > "$PWD/iosApp/VitruvianPhoenix/Unknown.xcodeproj/project.xcworkspace/contents.xcworkspacedata"
        chmod 600 "$PWD/iosApp/VitruvianPhoenix/Unknown.xcodeproj/project.xcworkspace/contents.xcworkspacedata"
    fi
    if [[ "$REPO_ROOT" != "$SCRIPT_REPO_ROOT" && "$source_text" == *GENERATED_WORKSPACE_SYMLINK* ]]; then
        workspace="$PWD/iosApp/VitruvianPhoenix/VitruvianPhoenix.xcodeproj/project.xcworkspace"
        mkdir -p "$workspace"
        ln -s "$PWD/iosApp/VitruvianPhoenix/VitruvianPhoenix/Proposal.swift" "$workspace/unsafe-link"
    fi
    if [[ "$REPO_ROOT" != "$SCRIPT_REPO_ROOT" && "$source_text" == *MUTATE_FINAL* ]]; then
        original=""
        while IFS= read -r line; do
            case "$line" in
                worktree\ * ) candidate="${line#worktree }"; [[ "$candidate" != "$PWD" ]] && original="$candidate"; ;;
            esac
        done < <(git -C "$REPO_ROOT" worktree list --porcelain)
        [[ -n "$original" ]] && printf 'mutated after baseline\n' > "$original/iosApp/VitruvianPhoenix/VitruvianPhoenix/Original.swift"
    fi
    base="$(git -C "$PWD" rev-parse HEAD)"
    fixture_sha="$(python3 - "$FIXTURE" <<'PY2'
import hashlib
import sys
from pathlib import Path
print(hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest())
PY2
)"
    udid="${PHOENIX_HARNESS_UDID:?}"
    python3 - "$artifact" "$base" "$fixture_sha" "$udid" "$source_text" <<'PY2'
import hashlib
import json
import os
import struct
import sys
import zlib
from pathlib import Path
root = Path(sys.argv[1])
base = sys.argv[2]
fixture_sha = sys.argv[3]
udid = sys.argv[4]
source = sys.argv[5]
simulator = {"udid": udid, "name": "Fake iPhone", "runtime": "iOS-26-5", "state": "Booted"}
commands = [
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
for name, output in commands:
    text = "ok\n"
    if output == "build.log": text = "Build Succeeded\n"
    if output == "test.log": text = "Test Case -[PhantomJustLiftFlowUITests testHomeToJustLiftToPhantomConnected] passed\n"
    if output == "simulator.log": text = "phantom connected semantic checkpoint\n"
    (root / output).write_text(text, encoding="utf-8")
    (root / output).chmod(0o600)
records = []
for name, output in commands:
    record = {"name": name, "exitCode": 0, "output": output}
    if name == "run-tests": record["resultBundle"] = {"basename": "test.xcresult", "status": "private-not-retained"}
    records.append(record)
(root / ".commands.jsonl").write_text("".join(json.dumps(record, separators=(",", ":")) + "\n" for record in records), encoding="utf-8")
(root / ".commands.jsonl").chmod(0o600)
(root / ".phantom-harness").write_text("phantom-harness-artifact-v1\n", encoding="utf-8")
(root / ".phantom-harness").chmod(0o600)
def chunk(kind, payload):
    return len(payload).to_bytes(4, "big") + kind + payload + zlib.crc32(kind + payload).to_bytes(4, "big")
ihdr = struct.pack(">IIBBBBB", 2, 2, 8, 6, 0, 0, 0)
source_marker = source.encode("utf-8")[:4096]
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"tEXt", b"source\0" + source_marker) + chunk(b"IDAT", b"") + chunk(b"IEND", b"")
for name in ("after.png", "xctest-attachment.png"):
    (root / name).write_bytes(png)
    (root / name).chmod(0o600)
manifest = {
    "schemaVersion": 1,
    "runId": "fake-run",
    "sourceMarker": source,
    "provenance": {
        "baseSha": base,
        "fixture": {"id": "just-lift-connected", "sha256": fixture_sha},
        "xcode": "Fake Xcode",
        "sdk": "Fake SDK",
        "simulator": simulator,
        "bundleId": "com.devil.phoenixproject.projectphoenix",
    },
    "commands": records,
    "semanticMarkers": {"required": ["xctest.passed", "phantom.connected", "simulator.screenshot"], "observed": ["xctest.passed", "phantom.connected", "simulator.screenshot"]},
    "captures": [
        {"slug": "simulator-after", "path": "after.png", "sha256": hashlib.sha256(png).hexdigest(), "phase": "after", "pair": "simulator", "checkpoint": "phantom-connected", "fixtureId": "just-lift-connected", "fixtureSha256": fixture_sha, "simulator": simulator},
        {"slug": "xctest-after", "path": "xctest-attachment.png", "sha256": hashlib.sha256(png).hexdigest(), "phase": "after", "pair": "xctest", "checkpoint": "phantom-connected", "fixtureId": "just-lift-connected", "fixtureSha256": fixture_sha, "simulator": simulator},
    ],
}
if "FORGE_BASE" in source: manifest["provenance"]["baseSha"] = "f" * 40
if "FORGE_FIXTURE" in source: manifest["provenance"]["fixture"] = {"id": "forged-fixture", "sha256": "0" * 64}
if "FORGE_BUNDLE" in source: manifest["provenance"]["bundleId"] = "com.example.forged"
if "FORGE_COMMANDS" in source: manifest["commands"] = [{"name": "fake-success", "exitCode": 0, "output": "fake.log"}]
if "FORGE_MARKERS" in source: manifest["semanticMarkers"] = {"required": ["fake.marker"], "observed": ["fake.marker"]}
(root / "run.json").write_text(json.dumps(manifest, sort_keys=True) + "\n", encoding="utf-8")
(root / "run.json").chmod(0o600)
PY2
    ;;
verify)
    exec python3 "$VERIFY" "$2"
    ;;
compare)
    output="$4"
    mkdir -p "$output"
    chmod 700 "$output"
    write_diff_png "$output/diff.png"
    python3 - "$output/diff.json" <<'PY2'
import json
import sys
from pathlib import Path
path = Path(sys.argv[1])
path.write_text(json.dumps({"passed": True, "thresholdPassed": True, "dimensions": {"width": 2, "height": 2}, "width": 2, "height": 2, "changedPixels": 0, "changedPixelRatio": 0.0, "changedRatio": 0.0, "meanChannelDelta": 0.0, "maxChannelDelta": 0, "maskTopPixels": 0, "threshold": 0.0, "inputs": {"before": "xctest-attachment.png", "after": "xctest-attachment.png"}}) + "\n", encoding="utf-8")
path.chmod(0o600)
PY2
    ;;
*) exit 2 ;;
esac
'''.replace("__PROPOSAL_ENV_LOG__", environment_log), encoding='utf-8')
os.chmod(runner, 0o700)
PY
    python3 - "$repo/gradlew" "$TMP_DIR/proposal-child-env.jsonl" <<'PY'
import os
import sys
from pathlib import Path
path = Path(sys.argv[1])
environment_log = sys.argv[2]
path.write_text(r'''#!/usr/bin/env bash
set -euo pipefail
case " $* " in
    *" :shared:compileKotlinIosSimulatorArm64 "*) ;;
    *) exit 2 ;;
esac
case " $* " in
    *" -Pskip.supabase.check=true "*) ;;
    *) exit 2 ;;
esac
python3 - "__PROPOSAL_ENV_LOG__" <<'PY_ENV'
import json
import os
import resource
import sys
from pathlib import Path
record = {
    "action": "gradle-compile",
    "cwd": os.getcwd(),
    "env": dict(sorted(os.environ.items())),
    "rlimitFsize": resource.getrlimit(resource.RLIMIT_FSIZE)[0],
    "rlimitNoFile": resource.getrlimit(resource.RLIMIT_NOFILE)[0],
}
with Path(sys.argv[1]).open("a", encoding="utf-8") as stream:
    stream.write(json.dumps(record, sort_keys=True) + "\n")
PY_ENV
: > "$TMPDIR/compile-ran"
source_text="$(cat "$PWD/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Proposal.kt")"
mkdir -p "$PWD/.gradle" "$PWD/shared/build"
printf 'generated by fake Gradle\n' > "$PWD/.gradle/proposal-cache.bin"
printf 'generated by fake Kotlin compile\n' > "$PWD/shared/build/compile-output.bin"
chmod 600 "$PWD/.gradle/proposal-cache.bin" "$PWD/shared/build/compile-output.bin"
if [[ "$source_text" == *GENERATED_UNKNOWN* ]]; then
    mkdir -p "$PWD/unknown-generated/nested"
    printf 'unexpected ignored output\n' > "$PWD/unknown-generated/nested/output.bin"
    chmod 600 "$PWD/unknown-generated/nested/output.bin"
fi
if [[ "$source_text" == *GENERATED_SYMLINK* ]]; then
    ln -s "$PWD/iosApp/VitruvianPhoenix/VitruvianPhoenix/Proposal.swift" "$PWD/shared/build/generated-link"
fi
if [[ "$source_text" == *GENERATED_TRACKED* ]]; then
    printf '// unexpected tracked candidate mutation\n' > "$PWD/iosApp/VitruvianPhoenix/VitruvianPhoenix/Unchanged.swift"
    chmod 600 "$PWD/iosApp/VitruvianPhoenix/VitruvianPhoenix/Unchanged.swift"
fi
'''.replace('__PROPOSAL_ENV_LOG__', environment_log), encoding='utf-8')
os.chmod(path, 0o700)
PY
    "$REAL_GIT" -C "$repo" init -q
    "$REAL_GIT" -C "$repo" config user.email test@example.invalid
    "$REAL_GIT" -C "$repo" config user.name "Phantom Test"
    "$REAL_GIT" -C "$repo" add .
    "$REAL_GIT" -C "$repo" commit -q -m base
}

make_patch() {
    local destination="$1"
    local old="$2"
    local new="$3"
    python3 - "$destination" "$old" "$new" <<'PY'
import os
import sys
from pathlib import Path
path = Path(sys.argv[1])
old = sys.argv[2]
new = sys.argv[3]
path.write_text(f"diff --git a/iosApp/VitruvianPhoenix/VitruvianPhoenix/Proposal.swift b/iosApp/VitruvianPhoenix/VitruvianPhoenix/Proposal.swift\n--- a/iosApp/VitruvianPhoenix/VitruvianPhoenix/Proposal.swift\n+++ b/iosApp/VitruvianPhoenix/VitruvianPhoenix/Proposal.swift\n@@ -1 +1 @@\n-{old}\n+{new}\n", encoding="utf-8")
os.chmod(path, 0o600)
PY
}

add_harmless_ignored_artifacts() {
    local repo="$1"
    python3 - "$repo" <<'PY'
import os
import subprocess
import sys
from pathlib import Path

repo = Path(sys.argv[1])
(repo / ".gitignore").write_text(
    ".gradle/\n"
    ".hermes/\n"
    "build/\n"
    "**/build/\n"
    "**/*.xcworkspace/\n"
    "**/xcuserdata/\n"
    "**/*.xcuserdatad/\n"
    "**/DerivedData/\n"
    "iosApp/VitruvianPhoenix/Config/Supabase.xcconfig\n",
    encoding="utf-8",
)
os.chmod(repo / ".gitignore", 0o600)
subprocess.run(["git", "-C", str(repo), "add", ".gitignore"], check=True)
subprocess.run(["git", "-C", str(repo), "commit", "-q", "-m", "ignore local artifacts"], check=True)

paths = (
    ".gradle/cache.bin",
    ".hermes/state.json",
    "build/output.bin",
    "shared/build/output.bin",
    "iosApp/VitruvianPhoenix/build/output.bin",
    "iosApp/VitruvianPhoenix/VitruvianPhoenix.xcworkspace/contents.xcworkspacedata",
    "iosApp/VitruvianPhoenix/VitruvianPhoenix/VitruvianPhoenix.xcuserdatad/UserInterfaceState.xcuserstate",
    "iosApp/VitruvianPhoenix/DerivedData/Build/Products/app",
    "iosApp/VitruvianPhoenix/Config/Supabase.xcconfig",
)
for relative in paths:
    path = repo / relative
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    path.write_text("ignored local artifact\n", encoding="utf-8")
    os.chmod(path, 0o600)
PY
}

make_kotlin_patch() {
    local destination="$1"
    local old="$2"
    local new="$3"
    python3 - "$destination" "$old" "$new" <<'PY'
import os
import sys
from pathlib import Path
path = Path(sys.argv[1])
old = sys.argv[2]
new = sys.argv[3]
name = 'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Proposal.kt'
path.write_text(f"diff --git a/{name} b/{name}\n--- a/{name}\n+++ b/{name}\n@@ -1 +1 @@\n-{old}\n+{new}\n", encoding='utf-8')
os.chmod(path, 0o600)
PY
}

make_path_patch() {
    local destination="$1"
    local path="$2"
    python3 - "$destination" "$path" <<'PY'
import os
import sys
from pathlib import Path
out = Path(sys.argv[1])
path = sys.argv[2]
out.write_text(f"diff --git a/{path} b/{path}\n--- a/{path}\n+++ b/{path}\n@@ -0,0 +1 @@\n+candidate\n", encoding="utf-8")
os.chmod(out, 0o600)
PY
}

make_resource_patch() {
    local destination="$1"
    local resource="$2"
    local payload="$3"
    python3 - "$destination" "$resource" "$payload" <<'PY'
import os
import sys
from pathlib import Path
out = Path(sys.argv[1])
resource = sys.argv[2]
payload = sys.argv[3]
out.write_text(
    f"diff --git a/{resource} b/{resource}\n"
    "new file mode 100644\n"
    "index 0000000..0000000\n"
    "--- /dev/null\n"
    f"+++ b/{resource}\n"
    "@@ -0,0 +1 @@\n"
    f"+{payload}\n",
    encoding="utf-8",
)
os.chmod(out, 0o600)
PY
}

make_rename_or_copy_patch() {
    local destination="$1"
    local operation="$2"
    python3 - "$destination" "$operation" <<'PY'
import os
import sys
from pathlib import Path
out = Path(sys.argv[1])
operation = sys.argv[2]
source = "iosApp/VitruvianPhoenix/VitruvianPhoenix/Proposal.swift"
target = "iosApp/VitruvianPhoenix/VitruvianPhoenix/Canonical.swift"
if operation == "rename":
    body = f"diff --git a/{source} b/{target}\n" "similarity index 100%\n" f"rename from {source}\nrename to {target}\n"
elif operation == "copy":
    body = f"diff --git a/{source} b/{target}\n" "similarity index 100%\n" f"copy from {source}\ncopy to {target}\n"
else:
    raise SystemExit(2)
out.write_text(body, encoding="utf-8")
os.chmod(out, 0o600)
PY
}

make_binary_swift_patch() {
    local destination="$1"
    python3 - "$destination" <<'PY'
import os
import subprocess
import sys
import tempfile
from pathlib import Path
with tempfile.TemporaryDirectory(prefix="proposal-binary-swift-") as name:
    repo = Path(name) / "repo"
    path = repo / "iosApp/VitruvianPhoenix/VitruvianPhoenix/Binary.swift"
    path.parent.mkdir(mode=0o700, parents=True)
    path.write_bytes(bytes([0]) + b"opaque-base")
    subprocess.run(["git", "-C", str(repo), "init", "-q"], check=True)
    subprocess.run(["git", "-C", str(repo), "config", "user.email", "test@example.invalid"], check=True)
    subprocess.run(["git", "-C", str(repo), "config", "user.name", "Proposal Fixture"], check=True)
    subprocess.run(["git", "-C", str(repo), "add", str(path.relative_to(repo))], check=True)
    subprocess.run(["git", "-C", str(repo), "commit", "-qm", "base"], check=True)
    path.write_bytes(bytes([0]) + b"opaque-candidate")
    diff = subprocess.check_output(["git", "-C", str(repo), "diff", "--binary", "--full-index", "HEAD", "--"])
Path(sys.argv[1]).write_bytes(diff)
os.chmod(sys.argv[1], 0o600)
PY
}

run_renderer() {
    local repo="$1"
    local artifact="$2"
    local patch="$3"
    local timeout="${4-}"
    env -i \
        PATH="$FAKE_BIN:/usr/bin:/bin" \
        HOME="$TMP_DIR/home" \
        TMPDIR="$TMP_DIR/os-tmp" \
        PARENT_AUTH_HINT="$PARENT_AUTH_HINT" \
        PHOENIX_HARNESS_UDID=11111111-2222-3333-4444-555555555555 \
        PHOENIX_HARNESS_ALLOW_DESTRUCTIVE=1 \
        PHOENIX_PROPOSAL_TRUSTED_INPUT=1 \
        ${timeout:+PHOENIX_PROPOSAL_TIMEOUT_SECONDS="$timeout"} \
        "$repo/.github/scripts/phantom-proposal.sh" render "$artifact" just-lift-connected "$patch"
}

run_renderer_with_java_home() {
    local java_home="$1"
    local repo="$2"
    local artifact="$3"
    local patch="$4"
    env -i \
        PATH="$FAKE_BIN:/usr/bin:/bin" \
        HOME="$TMP_DIR/home" \
        TMPDIR="$TMP_DIR/os-tmp" \
        JAVA_HOME="$java_home" \
        PARENT_AUTH_HINT="$PARENT_AUTH_HINT" \
        PHOENIX_HARNESS_UDID=11111111-2222-3333-4444-555555555555 \
        PHOENIX_HARNESS_ALLOW_DESTRUCTIVE=1 \
        PHOENIX_PROPOSAL_TRUSTED_INPUT=1 \
        "$repo/.github/scripts/phantom-proposal.sh" render "$artifact" just-lift-connected "$patch"
}

NO_JAVA_BIN="$TMP_DIR/no-java-bin"
mkdir "$NO_JAVA_BIN"
for tool in bash chmod git mkdir python3; do
    ln -s "$(command -v "$tool")" "$NO_JAVA_BIN/$tool"
done

run_renderer_without_java() {
    local repo="$1"
    local artifact="$2"
    local patch="$3"
    env -i \
        PATH="$NO_JAVA_BIN" \
        HOME="$TMP_DIR/home" \
        TMPDIR="$TMP_DIR/os-tmp" \
        JAVA_HOME="$TMP_DIR/invalid-jdk" \
        PARENT_AUTH_HINT="$PARENT_AUTH_HINT" \
        PHOENIX_HARNESS_UDID=11111111-2222-3333-4444-555555555555 \
        PHOENIX_HARNESS_ALLOW_DESTRUCTIVE=1 \
        PHOENIX_PROPOSAL_TRUSTED_INPUT=1 \
        "$repo/.github/scripts/phantom-proposal.sh" render "$artifact" just-lift-connected "$patch"
}

REPO="$TMP_DIR/repo"
make_fake_repo "$REPO"
PATCH="$TMP_DIR/candidate.patch"
make_patch "$PATCH" '// baseline' '// candidate'

# The fake harness records the exact child environment and requires a usable
# Java runtime.  This is the proposal baseline integration check: the renderer
# must resolve the parent's fake java shim and wire only its JAVA_HOME into the
# restricted child.
CHILD_ENV_ARTIFACT="$TMP_DIR/child-env-baseline"
run_renderer "$REPO" "$CHILD_ENV_ARTIFACT" "$PATCH" >"$TMP_DIR/child-env-baseline.out"
python3 - "$TMP_DIR/proposal-child-env.jsonl" "$JAVA_HOME_VALID" <<'PY'
import json
import resource
import sys
from pathlib import Path
records = [json.loads(line) for line in Path(sys.argv[1]).read_text().splitlines() if line.strip()]
cases = [record for record in records if record["action"] == "case"]
assert len(cases) >= 2, cases
for record in cases[-2:]:
    env = record["env"]
    assert env.get("JAVA_HOME") == sys.argv[2], env
    assert env["PATH"] == "/usr/bin:/bin:/usr/sbin:/sbin", env
    assert env.get("PHOENIX_HARNESS_REPO_ROOT") == str(Path(record["cwd"]).resolve()), record
    assert "PARENT_AUTH_HINT" not in env, env
    assert all("PARENT_REDACTED_VALUE" not in value for value in env.values()), env
    assert record["rlimitFsize"] == resource.RLIM_INFINITY, record
    assert record["rlimitNoFile"] == resource.getrlimit(resource.RLIMIT_NOFILE)[0], record
before_manifest = json.loads((Path(sys.argv[1]).parent / "child-env-baseline" / "before" / "run.json").read_text())
after_manifest = json.loads((Path(sys.argv[1]).parent / "child-env-baseline" / "after" / "run.json").read_text())
assert before_manifest["sourceMarker"] == "// baseline", before_manifest
assert after_manifest["sourceMarker"] == "// candidate", after_manifest
assert before_manifest["sourceMarker"] != after_manifest["sourceMarker"]
assert (Path(sys.argv[1]).parent / "child-env-baseline" / "before" / "after.png").read_bytes() != (Path(sys.argv[1]).parent / "child-env-baseline" / "after" / "after.png").read_bytes()
PY

# An explicitly configured usable JAVA_HOME is propagated exactly, while an
# unusable configured home falls back to the validated java executable on the
# proposal process PATH.
EXPLICIT_ARTIFACT="$TMP_DIR/explicit-java-home"
run_renderer_with_java_home "$JAVA_HOME_VALID" "$REPO" "$EXPLICIT_ARTIFACT" "$PATCH" >"$TMP_DIR/explicit-java-home.out"
python3 - "$TMP_DIR/proposal-child-env.jsonl" "$JAVA_HOME_VALID" <<'PY'
import json
import sys
from pathlib import Path
records = [json.loads(line) for line in Path(sys.argv[1]).read_text().splitlines() if line.strip()]
case = [record for record in records if record["action"] == "case"][-1]
assert case["env"].get("JAVA_HOME") == sys.argv[2], case
PY

INVALID_JAVA_HOME="$TMP_DIR/invalid-jdk"
mkdir -p "$INVALID_JAVA_HOME/bin"
cat > "$INVALID_JAVA_HOME/bin/java" <<'SH'
#!/usr/bin/env bash
exit 97
SH
chmod 700 "$INVALID_JAVA_HOME/bin/java"
FALLBACK_ARTIFACT="$TMP_DIR/fallback-java-home"
run_renderer_with_java_home "$INVALID_JAVA_HOME" "$REPO" "$FALLBACK_ARTIFACT" "$PATCH" >"$TMP_DIR/fallback-java-home.out"
python3 - "$TMP_DIR/proposal-child-env.jsonl" "$JAVA_HOME_VALID" <<'PY'
import json
import sys
from pathlib import Path
records = [json.loads(line) for line in Path(sys.argv[1]).read_text().splitlines() if line.strip()]
case = [record for record in records if record["action"] == "case"][-1]
assert case["env"].get("JAVA_HOME") == sys.argv[2], case
PY

# With both the configured home and the proposal PATH unusable, resolution
# fails before the fake harness baseline is invoked and emits no secret/path
# diagnostics.
NO_RUNTIME_ARTIFACT="$TMP_DIR/no-runtime"
case_count_before="$(python3 - "$TMP_DIR/proposal-child-env.jsonl" <<'PY'
import sys
from pathlib import Path
print(sum(1 for line in Path(sys.argv[1]).read_text().splitlines() if '"action": "case"' in line))
PY
)"
if run_renderer_without_java "$REPO" "$NO_RUNTIME_ARTIFACT" "$PATCH" >"$TMP_DIR/no-runtime.out" 2>&1; then
    fail 'proposal accepted a missing Java runtime'
fi
grep -Fx 'phantom-proposal: unable to locate a usable Java runtime; set JAVA_HOME to a JDK home or ensure java is on PATH' "$TMP_DIR/no-runtime.out" >/dev/null \
    || fail 'missing proposal Java runtime failure was not actionable'
case_count_after="$(python3 - "$TMP_DIR/proposal-child-env.jsonl" <<'PY'
import sys
from pathlib import Path
print(sum(1 for line in Path(sys.argv[1]).read_text().splitlines() if '"action": "case"' in line))
PY
)"
[[ "$case_count_before" == "$case_count_after" ]] || fail 'proposal baseline ran without a usable Java runtime'

IGNORED_REPO="$TMP_DIR/ignored-repo"
make_fake_repo "$IGNORED_REPO"
add_harmless_ignored_artifacts "$IGNORED_REPO"
IGNORED_ARTIFACT="$TMP_DIR/ignored-artifact"
run_renderer "$IGNORED_REPO" "$IGNORED_ARTIFACT" "$PATCH" >"$TMP_DIR/ignored.out"
python3 - "$IGNORED_ARTIFACT/proposal-manifest.json" <<'PY'
import json
import sys
from pathlib import Path
assert json.loads(Path(sys.argv[1]).read_text())["status"] == "passed"
PY

IGNORED_DIRTY_REPO="$TMP_DIR/ignored-dirty-repo"
make_fake_repo "$IGNORED_DIRTY_REPO"
add_harmless_ignored_artifacts "$IGNORED_DIRTY_REPO"
printf '// dirty source\n' > "$IGNORED_DIRTY_REPO/iosApp/VitruvianPhoenix/VitruvianPhoenix/Proposal.swift"
if run_renderer "$IGNORED_DIRTY_REPO" "$TMP_DIR/ignored-dirty" "$PATCH" >"$TMP_DIR/ignored-dirty.out" 2>&1; then
    fail 'tracked source edit alongside ignored artifacts was accepted'
fi
grep -F 'original harness worktree' "$TMP_DIR/ignored-dirty.out" >/dev/null || fail 'tracked source edit was not rejected safely'

# The renderer is trusted-input only and must fail before touching the artifact
# root when the explicit operator gate is absent.
NO_TRUST="$TMP_DIR/no-trust"
if env -i PATH="$FAKE_BIN:/usr/bin:/bin" HOME="$TMP_DIR/home" TMPDIR="$TMP_DIR/os-tmp" PHOENIX_HARNESS_UDID=11111111-2222-3333-4444-555555555555 PHOENIX_HARNESS_ALLOW_DESTRUCTIVE=1 "$REPO/.github/scripts/phantom-proposal.sh" render "$NO_TRUST" just-lift-connected "$PATCH" >"$TMP_DIR/no-trust.out" 2>&1; then
    fail 'missing trusted-input gate was accepted'
fi
grep -F 'PHOENIX_PROPOSAL_TRUSTED_INPUT=1' "$TMP_DIR/no-trust.out" >/dev/null || fail 'trusted-input error was not actionable'
[[ ! -e "$NO_TRUST" ]] || fail 'missing trusted-input gate touched artifact root'

# Artifact roots below the repository are never valid evidence destinations.
NESTED="$REPO/nested-artifact"
if run_renderer "$REPO" "$NESTED" "$PATCH" >"$TMP_DIR/nested.out" 2>&1; then
    fail 'artifact root nested under repository was accepted'
fi
[[ ! -e "$NESTED" ]] || fail 'nested artifact root was created'

# Only the three render-relevant source/resource prefixes are accepted.
for forbidden in \
    '.github/scripts/other.sh' \
    'docs/testing/notes.md' \
    'androidApp/src/main/kotlin/Unsafe.kt' \
    'shared/build.gradle.kts' \
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/Config.kt' \
    '.github/workflows/ci.yml' \
    'iosApp/VitruvianPhoenix/VitruvianPhoenix.xcodeproj/project.pbxproj' \
    'iosApp/VitruvianPhoenix/Config/Supabase.xcconfig' \
    'iosApp/VitruvianPhoenix/VitruvianPhoenix/Generated.html'; do
    candidate="$TMP_DIR/forbidden-${forbidden//\//_}.patch"
    make_path_patch "$candidate" "$forbidden"
    artifact="$TMP_DIR/artifact-${forbidden//\//_}"
    if run_renderer "$REPO" "$artifact" "$candidate" >"$TMP_DIR/forbidden.out" 2>&1; then
        fail "forbidden path accepted: $forbidden"
    fi
done

UNSUPPORTED="$TMP_DIR/unsupported.patch"
make_path_patch "$UNSUPPORTED" 'iosApp/VitruvianPhoenix/VitruvianPhoenix/Proposal.exe'
if run_renderer "$REPO" "$TMP_DIR/unsupported" "$UNSUPPORTED" >"$TMP_DIR/unsupported.out" 2>&1; then
    fail 'unsupported candidate extension was accepted'
fi

# Secret assignments are rejected without placing their values in diagnostics.
SECRET_PATCH="$TMP_DIR/secret.patch"
python3 - "$SECRET_PATCH" <<'PY'
import os
import sys
from pathlib import Path
path = Path(sys.argv[1])
path.write_text('diff --git a/iosApp/VitruvianPhoenix/VitruvianPhoenix/Proposal.swift b/iosApp/VitruvianPhoenix/VitruvianPhoenix/Proposal.swift\n--- a/iosApp/VitruvianPhoenix/VitruvianPhoenix/Proposal.swift\n+++ b/iosApp/VitruvianPhoenix/VitruvianPhoenix/Proposal.swift\n@@ -1 +1 @@\n-// baseline\n+SUPABASE_ANON_KEY=super-secret-anon-value API_TOKEN=super-secret-api-value\n', encoding='utf-8')
os.chmod(path, 0o600)
PY
if run_renderer "$REPO" "$TMP_DIR/secret" "$SECRET_PATCH" >"$TMP_DIR/secret.out" 2>&1; then
    fail 'credential assignment patch was accepted'
fi
if grep -E 'super-secret-anon-value|super-secret-api-value' "$TMP_DIR/secret.out" >/dev/null 2>&1; then
    fail 'credential assignment value leaked in diagnostics'
fi

# A changed original HEAD/status is rejected before rendering.
DIRTY_REPO="$TMP_DIR/dirty-repo"
make_fake_repo "$DIRTY_REPO"
printf '// dirty\n' > "$DIRTY_REPO/iosApp/VitruvianPhoenix/VitruvianPhoenix/Proposal.swift"
if run_renderer "$DIRTY_REPO" "$TMP_DIR/dirty" "$PATCH" >"$TMP_DIR/dirty.out" 2>&1; then
    fail 'dirty original worktree was accepted'
fi
grep -F 'original harness worktree' "$TMP_DIR/dirty.out" >/dev/null || fail 'dirty worktree failure was not reported safely'

# Baseline/finalization worktree mutations must be detected and leave a failure manifest.
MUTATE_BASE="$TMP_DIR/mutate-base-repo"
make_fake_repo "$MUTATE_BASE" '// MUTATE_BASELINE'
MUTATE_PATCH="$TMP_DIR/mutate.patch"
make_patch "$MUTATE_PATCH" '// MUTATE_BASELINE' '// candidate'
if run_renderer "$MUTATE_BASE" "$TMP_DIR/mutate-base" "$MUTATE_PATCH" >"$TMP_DIR/mutate-base.out" 2>&1; then
    fail 'original mutation after baseline was accepted'
fi
python3 - "$TMP_DIR/mutate-base/proposal-manifest.json" <<'PY'
import json
import sys
from pathlib import Path
manifest = json.loads(Path(sys.argv[1]).read_text())
assert manifest["status"] == "failed"
PY

MUTATE_FINAL="$TMP_DIR/mutate-final-repo"
make_fake_repo "$MUTATE_FINAL"
FINAL_PATCH="$TMP_DIR/final.patch"
make_patch "$FINAL_PATCH" '// baseline' '// MUTATE_FINAL'
if run_renderer "$MUTATE_FINAL" "$TMP_DIR/mutate-final" "$FINAL_PATCH" >"$TMP_DIR/mutate-final.out" 2>&1; then
    fail 'original mutation before finalization was accepted'
fi
python3 - "$TMP_DIR/mutate-final/proposal-manifest.json" <<'PY'
import json
import sys
from pathlib import Path
manifest = json.loads(Path(sys.argv[1]).read_text())
assert manifest["status"] == "failed"
PY

# Forged provenance, bundle, fixture, canonical command, and marker contracts
# are rejected for the candidate after the real verifier is invoked.
for token in FORGE_BASE FORGE_FIXTURE FORGE_BUNDLE FORGE_COMMANDS FORGE_MARKERS; do
    forged="$TMP_DIR/forged-${token}.patch"
    make_patch "$forged" '// baseline' "// $token"
    artifact="$TMP_DIR/forged-${token}"
    if run_renderer "$REPO" "$artifact" "$forged" >"$TMP_DIR/forged-${token}.out" 2>&1; then
        fail "forged candidate contract accepted: $token"
    fi
    python3 - "$artifact/proposal-manifest.json" <<'PY'
import json
import sys
from pathlib import Path
manifest = json.loads(Path(sys.argv[1]).read_text())
assert manifest["status"] == "failed"
PY
done

FORGED_BEFORE_REPO="$TMP_DIR/forged-before-repo"
make_fake_repo "$FORGED_BEFORE_REPO" '// FORGE_BEFORE'
FORGED_BEFORE_PATCH="$TMP_DIR/forged-before.patch"
make_patch "$FORGED_BEFORE_PATCH" '// FORGE_BEFORE' '// candidate'
if run_renderer "$FORGED_BEFORE_REPO" "$TMP_DIR/forged-before" "$FORGED_BEFORE_PATCH" >"$TMP_DIR/forged-before.out" 2>&1; then
    fail 'forged baseline SHA contract was accepted'
fi
python3 - "$TMP_DIR/forged-before/proposal-manifest.json" <<'PY'
import json
import sys
from pathlib import Path
manifest = json.loads(Path(sys.argv[1]).read_text())
assert manifest["status"] == "failed"
PY

# A partially successful worktree add must still be removed and pruned.
PARTIAL_REPO="$TMP_DIR/partial-repo"
make_fake_repo "$PARTIAL_REPO"
PARTIAL_TRIGGER="$TMP_DIR/partial.trigger"
: > "$PARTIAL_TRIGGER"
cat > "$FAKE_BIN/git" <<SH
#!/usr/bin/env bash
set -euo pipefail
if [[ "\$*" == *" worktree add "* ]] && [[ -e "$PARTIAL_TRIGGER" ]]; then
    "$REAL_GIT" "\$@" || exit \$?
    exit 91
fi
exec "$REAL_GIT" "\$@"
SH
chmod 700 "$FAKE_BIN/git"
if run_renderer "$PARTIAL_REPO" "$TMP_DIR/partial" "$PATCH" >"$TMP_DIR/partial.out" 2>&1; then
    fail 'partial worktree add was accepted'
fi
[[ -z "$("$REAL_GIT" -C "$PARTIAL_REPO" worktree list --porcelain | grep -F 'worktree ' | tail -n +2)" ]] || fail 'partial worktree metadata leaked'
rm -f "$FAKE_BIN/git" "$PARTIAL_TRIGGER"

# HUP, INT, and TERM are required to produce safe failure manifests and clean
# the worktree.
for signal_name in TERM HUP INT; do
    SIGNAL_REPO="$TMP_DIR/signal-$signal_name-repo"
    make_fake_repo "$SIGNAL_REPO"
    SIGNAL_PATCH="$TMP_DIR/signal-$signal_name.patch"
    make_patch "$SIGNAL_PATCH" '// baseline' '// SIGNAL'
    SIGNAL_ARTIFACT="$TMP_DIR/signal-$signal_name-artifact"
    python3 - "$SIGNAL_REPO" "$SIGNAL_ARTIFACT" "$SIGNAL_PATCH" "$signal_name" "$FAKE_BIN" "$TMP_DIR/home" "$TMP_DIR/os-tmp" <<'PY'
import os
import signal
import subprocess
import sys
import time
from pathlib import Path
repo, artifact, patch, signal_name, fake_bin, home, tmp = sys.argv[1:]
env = {
    "PATH": f"{fake_bin}:/usr/bin:/bin",
    "HOME": home,
    "TMPDIR": tmp,
    "PHOENIX_HARNESS_UDID": "11111111-2222-3333-4444-555555555555",
    "PHOENIX_HARNESS_ALLOW_DESTRUCTIVE": "1",
    "PHOENIX_PROPOSAL_TRUSTED_INPUT": "1",
}
command = [str(Path(repo) / ".github/scripts/phantom-proposal.sh"), "render", artifact, "just-lift-connected", patch]
with open(Path(artifact).parent / f"signal-{signal_name}.out", "w", encoding="utf-8") as output:
    process = subprocess.Popen(command, env=env, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
    marker = Path(artifact) / "after/.signal-ready"
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline and not marker.exists():
        time.sleep(0.05)
    if not marker.exists():
        process.kill()
        process.wait()
        raise SystemExit(f"{signal_name} fixture did not reach candidate execution")
    os.kill(process.pid, getattr(signal, "SIG" + signal_name))
    try:
        result = process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait()
        raise SystemExit(f"{signal_name} renderer did not exit after signal")
    if result == 0:
        raise SystemExit(f"{signal_name} was accepted")
PY
    python3 - "$SIGNAL_ARTIFACT/proposal-manifest.json" <<'PY'
import json
import sys
from pathlib import Path
manifest = json.loads(Path(sys.argv[1]).read_text())
assert manifest["status"] == "failed"
PY
done

# Timeout and bounded private-output failures must clean their worktrees and
# write only a safe failure manifest.
for token in TIMEOUT BLOAT; do
    lower_token="$(printf '%s' "$token" | tr '[:upper:]' '[:lower:]')"
    bounded="$TMP_DIR/$lower_token.patch"
    make_patch "$bounded" '// baseline' "// $token"
    artifact="$TMP_DIR/$lower_token-artifact"
    if [[ "$token" == TIMEOUT ]]; then
        if run_renderer "$REPO" "$artifact" "$bounded" 2 >"$TMP_DIR/$lower_token.out" 2>&1; then
            fail "bounded child failure was accepted: $token"
        fi
    elif run_renderer "$REPO" "$artifact" "$bounded" >"$TMP_DIR/$lower_token.out" 2>&1; then
        fail "bounded child failure was accepted: $token"
    fi
    python3 - "$artifact/proposal-manifest.json" <<'PY'
import json
import sys
from pathlib import Path
manifest = json.loads(Path(sys.argv[1]).read_text())
assert manifest["status"] == "failed"
assert list(Path(sys.argv[1]).parent.iterdir()) == [Path(sys.argv[1]).parent / ".phantom-proposal", Path(sys.argv[1])]
PY
done

# A restricted child must be able to create a measured-size legitimate cache
# file under the named Gradle directory; the post-run private-tree check still
# rejects the same size outside that directory.
GRADLE_CACHE_PATCH="$TMP_DIR/gradle-cache.patch"
make_patch "$GRADLE_CACHE_PATCH" '// baseline' '// GRADLE_CACHE'
GRADLE_CACHE_ARTIFACT="$TMP_DIR/gradle-cache-artifact"
run_renderer "$REPO" "$GRADLE_CACHE_ARTIFACT" "$GRADLE_CACHE_PATCH" >"$TMP_DIR/gradle-cache.out"
python3 - "$GRADLE_CACHE_ARTIFACT/proposal-manifest.json" <<'PY'
import json
import sys
from pathlib import Path
manifest = json.loads(Path(sys.argv[1]).read_text())
assert manifest["status"] == "passed"
PY

# Exercise the context-aware private-tree bounds directly with sparse files so
# the regression does not consume real disk space. The named Gradle cache gets
# its measured 1.4 GiB cold-cache allowance, while candidate-controlled files
# and artifacts retain the strict 128 MiB individual-file ceiling.
BOUNDS_RENDERER="$TMP_DIR/check-tree-bounds.sh"
python3 - "$RENDERER" "$BOUNDS_RENDERER" <<'PY'
import os
import sys
from pathlib import Path
source = Path(sys.argv[1]).read_text(encoding="utf-8")
marker = '\nmain "$@"\n'
assert source.endswith(marker)
source = source[:-len(marker)] + "\n"
for line in (
    "trap on_exit EXIT",
    "trap 'on_signal HUP 129' HUP",
    "trap 'on_signal INT 130' INT",
    "trap 'on_signal TERM 143' TERM",
):
    source = source.replace(f"{line}\n", "")
destination = Path(sys.argv[2])
destination.write_text(source, encoding="utf-8")
os.chmod(destination, 0o700)
PY

# RED regression: a timed-out producer may exit on SIGTERM while its stubborn
# descendant ignores SIGTERM and keeps the inherited output pipe open.  The
# bounded command must still SIGKILL the whole process group and reap its leader.
STUBBORN_SCRIPT="$TMP_DIR/stubborn-producer.py"
STUBBORN_CHILD_PID="$TMP_DIR/stubborn-producer-child.pid"
python3 - "$STUBBORN_SCRIPT" "$STUBBORN_CHILD_PID" <<'PY'
import os
import sys
from pathlib import Path
script = Path(sys.argv[1])
child_pid = sys.argv[2]
child_code = (
    "import os, signal, time; "
    "signal.signal(signal.SIGTERM, signal.SIG_IGN); "
    f"open({child_pid!r}, 'w').write(str(os.getpid())); time.sleep(60)"
)
script.write_text(
    "import signal, subprocess, sys, time\n"
    f"subprocess.Popen([sys.executable, '-c', {child_code!r}])\n"
    "def finish(_signum, _frame): raise SystemExit(0)\n"
    "signal.signal(signal.SIGTERM, finish)\n"
    "time.sleep(60)\n",
    encoding="utf-8",
)
os.chmod(script, 0o700)
PY
STUBBORN_LOG="$TMP_DIR/stubborn-producer.log"
python3 - "$BOUNDS_RENDERER" "$STUBBORN_SCRIPT" "$STUBBORN_LOG" "$STUBBORN_CHILD_PID" <<'PY'
import os
import signal
import subprocess
import sys
import time
from pathlib import Path
renderer, script, log, child_pid_file = sys.argv[1:]
child_pid_path = Path(child_pid_file)
command = ["bash", "-c", 'source "$1"; bounded_command "$2" 2 "$PWD" python3 "$3"', "_", renderer, log, script]
started = time.monotonic()
process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
try:
    stdout, stderr = process.communicate(timeout=5)
except subprocess.TimeoutExpired:
    os.killpg(process.pid, signal.SIGKILL)
    process.wait()
    raise SystemExit(f"stubborn producer exceeded bounded deadline: stdout={stdout!r} stderr={stderr!r}")
elapsed = time.monotonic() - started
if elapsed >= 4:
    raise SystemExit(f"stubborn producer timeout grace was not bounded: {elapsed:.3f}s")
startup_deadline = time.monotonic() + 1
while not child_pid_path.exists() and time.monotonic() < startup_deadline:
    time.sleep(0.02)
if not child_pid_path.exists():
    raise SystemExit("stubborn producer descendant did not start")
child_pid = int(child_pid_path.read_text(encoding="ascii").strip())
deadline = time.monotonic() + 2
while time.monotonic() < deadline:
    try:
        os.kill(child_pid, 0)
    except ProcessLookupError:
        break
    time.sleep(0.05)
else:
    raise SystemExit("stubborn producer descendant survived timeout cleanup")
if process.returncode == 0:
    raise SystemExit("stubborn producer unexpectedly succeeded")
PY

# The default profile remains the strict profile for non-build children.
DEFAULT_PROFILE_LOG="$TMP_DIR/default-profile.log"
bash -c 'source "$1"; bounded_command "$2" 5 "$PWD" python3 -c "import resource; print(resource.getrlimit(resource.RLIMIT_FSIZE)[0], resource.getrlimit(resource.RLIMIT_NOFILE)[0])"' _ "$BOUNDS_RENDERER" "$DEFAULT_PROFILE_LOG"
python3 - "$DEFAULT_PROFILE_LOG" <<'PY'
import resource
import sys
from pathlib import Path
values = Path(sys.argv[1]).read_text(encoding="utf-8").split()
assert values == [str(512 * 1024 * 1024), "256"], values
assert values[0] != str(resource.RLIM_INFINITY), values
PY

# The build helper has no generic command escape hatch: only its two explicit
# build roles can select the no-FSIZE profile.
if bash -c 'source "$1"; build_tool_command compare "$2" 5 "$PWD"' _ "$BOUNDS_RENDERER" "$TMP_DIR/rejected-build.log"; then
    fail 'build helper accepted a non-build command role'
fi

run_bounds_check() {
    local root="$1"
    local excluded="${2-}"
    bash -c 'source "$1"; check_tree_bounds "$2" "$3"' _ "$BOUNDS_RENDERER" "$root" "$excluded"
}
create_sparse_tree() {
    local root="$1"
    shift
    python3 - "$root" "$@" <<'PY'
import os
import sys
from pathlib import Path
root = Path(sys.argv[1])
sizes = [int(value) for value in sys.argv[2:]]
root.mkdir(mode=0o700, parents=False)
for index, size in enumerate(sizes):
    path = root / f"cache-{index:02d}.bin"
    with path.open("wb") as stream:
        stream.truncate(size)
    path.chmod(0o600)
if sum(path.stat().st_blocks for path in root.iterdir()) * 512 >= sum(sizes):
    raise SystemExit("resource fixture is not sparse")
PY
}
create_private_tree() {
    local root="$1"
    shift
    mkdir -m 700 "$root"
    create_sparse_tree "$root/gradle-user-home" "$@"
}
BOUNDS_ROOT="$TMP_DIR/bounds"
mkdir -m 700 "$BOUNDS_ROOT"
MEGABYTE=$((1024 * 1024))
create_private_tree "$BOUNDS_ROOT/within-cache" $((259 * MEGABYTE)) $((190 * MEGABYTE)) $((200 * MEGABYTE)) $((200 * MEGABYTE)) $((200 * MEGABYTE)) $((200 * MEGABYTE)) $((184 * MEGABYTE))
create_private_tree "$BOUNDS_ROOT/over-cache-file" $((513 * MEGABYTE))
create_private_tree "$BOUNDS_ROOT/over-cache-total" $((512 * MEGABYTE)) $((512 * MEGABYTE)) $((512 * MEGABYTE)) $((512 * MEGABYTE)) 1
mkdir -m 700 "$BOUNDS_ROOT/over-candidate-file"
create_sparse_tree "$BOUNDS_ROOT/over-candidate-file/home" $((129 * MEGABYTE))
mkdir -m 700 "$BOUNDS_ROOT/symlink"
printf 'target\n' > "$BOUNDS_ROOT/symlink/target"
chmod 600 "$BOUNDS_ROOT/symlink/target"
ln -s target "$BOUNDS_ROOT/symlink/link"
mkdir -m 700 "$BOUNDS_ROOT/nonregular"
mkfifo "$BOUNDS_ROOT/nonregular/fifo"
mkdir -m 700 "$BOUNDS_ROOT/excluded-worktree"
create_sparse_tree "$BOUNDS_ROOT/excluded-worktree/candidate" $((129 * MEGABYTE))
printf 'outside\n' > "$BOUNDS_ROOT/excluded-worktree/outside"
chmod 600 "$BOUNDS_ROOT/excluded-worktree/outside"

run_bounds_check "$BOUNDS_ROOT/within-cache" || fail '1.4 GiB sparse Gradle cache was rejected'
if run_bounds_check "$BOUNDS_ROOT/over-cache-file"; then
    fail '513 MiB Gradle cache file was accepted'
fi
if run_bounds_check "$BOUNDS_ROOT/over-cache-total"; then
    fail 'Gradle cache above the 2 GiB total ceiling was accepted'
fi
if run_bounds_check "$BOUNDS_ROOT/over-candidate-file"; then
    fail '129 MiB candidate-controlled private file was accepted'
fi
if run_bounds_check "$BOUNDS_ROOT/symlink"; then
    fail 'private symlink was accepted'
fi
if run_bounds_check "$BOUNDS_ROOT/nonregular"; then
    fail 'private nonregular file was accepted'
fi
run_bounds_check "$BOUNDS_ROOT/excluded-worktree" "$BOUNDS_ROOT/excluded-worktree/candidate" || fail 'excluded candidate worktree was not ignored'

# The strict policy still rejects ignored output before apply, while the
# post-build policy accepts only a bounded explicit generated root.  Sparse
# files keep the over-limit fixture from consuming real disk space.
STATUS_EXPECTED="$TMP_DIR/status-expected.json"
STATUS_FILE="$TMP_DIR/generated-status"
STATUS_ACTUAL="$TMP_DIR/generated-actual.json"
python3 - "$STATUS_EXPECTED" <<'PY'
import json
import os
import sys
from pathlib import Path
path = Path(sys.argv[1])
path.write_text(json.dumps({"paths": []}) + "\n", encoding="utf-8")
os.chmod(path, 0o600)
PY
mkdir -m 700 "$REPO/.gradle"
printf 'expected generated output\n' > "$REPO/.gradle/expected.bin"
chmod 600 "$REPO/.gradle/expected.bin"
bash -c 'source "$1"; status_paths_and_validate "$2" "$3" "$4" "$5" post-build' _ "$BOUNDS_RENDERER" "$REPO" "$STATUS_EXPECTED" "$STATUS_FILE" "$STATUS_ACTUAL"
python3 - "$STATUS_ACTUAL" <<'PY'
import json
import sys
from pathlib import Path
assert json.loads(Path(sys.argv[1]).read_text()) == []
PY
if bash -c 'source "$1"; status_paths_and_validate "$2" "$3" "$4" "$5" strict' _ "$BOUNDS_RENDERER" "$REPO" "$STATUS_EXPECTED" "$STATUS_FILE" "$STATUS_ACTUAL"; then
    fail 'strict pre-apply status accepted ignored generated output'
fi
python3 - "$REPO/.gradle/over-limit.bin" <<'PY'
import os
import sys
from pathlib import Path
path = Path(sys.argv[1])
with path.open('wb') as stream:
    stream.truncate(513 * 1024 * 1024)
path.chmod(0o600)
PY
if bash -c 'source "$1"; status_paths_and_validate "$2" "$3" "$4" "$5" post-build' _ "$BOUNDS_RENDERER" "$REPO" "$STATUS_EXPECTED" "$STATUS_FILE" "$STATUS_ACTUAL"; then
    fail 'oversized generated output was accepted'
fi
python3 - "$REPO/.gradle" <<'PY'
import shutil
import sys
shutil.rmtree(sys.argv[1])
PY

# Producer-shaped resource fixtures exercise the same decoded-input boundary as
# the preview consumer.  Safe structured resources pass; generic assignments,
# opaque binary Swift, and canonical rename/copy metadata fail before rendering.
for fixture in json-safe xml-safe json-credential xml-credential opaque-binary-swift rename copy; do
    fixture_repo="$TMP_DIR/producer-$fixture-repo"
    make_fake_repo "$fixture_repo"
    fixture_patch="$TMP_DIR/producer-$fixture.patch"
    case "$fixture" in
        json-safe) make_resource_patch "$fixture_patch" "shared/src/commonMain/composeResources/values/labels.json" '{"title":"SAFE"}' ;;
        xml-safe) make_resource_patch "$fixture_patch" "shared/src/commonMain/composeResources/values/strings.xml" '<resources><string name="title">SAFE</string></resources>' ;;
        json-credential) make_resource_patch "$fixture_patch" "shared/src/commonMain/composeResources/values/labels.json" '{"title":"TOKEN=REDACTED_REDACTED"}' ;;
        xml-credential) make_resource_patch "$fixture_patch" "shared/src/commonMain/composeResources/values/strings.xml" '<resources><string name="title">TOKEN=REDACTED_REDACTED</string></resources>' ;;
        opaque-binary-swift) make_binary_swift_patch "$fixture_patch" ;;
        rename|copy) make_rename_or_copy_patch "$fixture_patch" "$fixture" ;;
    esac
    fixture_artifact="$TMP_DIR/producer-$fixture-artifact"
    if [[ "$fixture" == json-safe || "$fixture" == xml-safe ]]; then
        run_renderer "$fixture_repo" "$fixture_artifact" "$fixture_patch" >"$TMP_DIR/producer-$fixture.out"
        python3 - "$fixture_artifact/proposal-manifest.json" <<'PY'
import json
import sys
from pathlib import Path
assert json.loads(Path(sys.argv[1]).read_text())["status"] == "passed"
PY
    else
        if run_renderer "$fixture_repo" "$fixture_artifact" "$fixture_patch" >"$TMP_DIR/producer-$fixture.out" 2>&1; then
            fail "producer accepted unsafe fixture: $fixture"
        fi
        python3 - "$fixture_artifact/proposal-manifest.json" <<'PY'
import json
import sys
from pathlib import Path
result = json.loads(Path(sys.argv[1]).read_text())
assert result["status"] == "failed"
PY
    fi
done

# A normal Swift candidate is accepted only after both canonical harness cases,
# compile-free real-app execution, comparison validation, and cleanup.
SUCCESS_ARTIFACT="$TMP_DIR/success"
run_renderer "$REPO" "$SUCCESS_ARTIFACT" "$PATCH" >"$TMP_DIR/success.out"
python3 - "$SUCCESS_ARTIFACT" "$REPO" <<'PY'
import json
import os
import stat
import sys
from pathlib import Path
root = Path(sys.argv[1])
repo = Path(sys.argv[2])
manifest = json.loads((root / "proposal-manifest.json").read_text())
assert manifest["status"] == "passed"
assert manifest["baseSha"] == manifest["worktree"]["baseSha"] == manifest["worktree"]["headSha"]
assert manifest["comparison"]["before"]["dimensions"] == {"width": 2, "height": 2}
assert manifest["comparison"]["after"]["dimensions"] == {"width": 2, "height": 2}
assert json.loads((root / "comparison/diff.json").read_text())["dimensions"] == {"width": 2, "height": 2}
assert (repo / "iosApp/VitruvianPhoenix/VitruvianPhoenix/Proposal.swift").read_text() == "// baseline\n"
for path in (root / "proposal.patch", root / "proposal-manifest.json", root / "proposal.md", root / "evidence-summary.json", root / "comparison/diff.json", root / "comparison/diff.png"):
    assert stat.S_IMODE(os.lstat(path).st_mode) == 0o600
assert stat.S_IMODE(os.lstat(root).st_mode) == 0o700
PY
[[ -z "$("$REAL_GIT" -C "$REPO" status --porcelain --untracked-files=all)" ]] || fail 'successful render changed original worktree'
[[ -z "$("$REAL_GIT" -C "$REPO" worktree list --porcelain | grep -F 'worktree ' | tail -n +2)" ]] || fail 'successful render leaked worktree'

KOTLIN_REPO="$TMP_DIR/kotlin-repo"
make_fake_repo "$KOTLIN_REPO"
KOTLIN_PATCH="$TMP_DIR/kotlin.patch"
make_kotlin_patch "$KOTLIN_PATCH" '// baseline kotlin' '// candidate kotlin'
KOTLIN_ARTIFACT="$TMP_DIR/kotlin-artifact"
run_renderer "$KOTLIN_REPO" "$KOTLIN_ARTIFACT" "$KOTLIN_PATCH" >"$TMP_DIR/kotlin.out"
python3 - "$KOTLIN_ARTIFACT" <<'PY'
import json
import sys
from pathlib import Path
manifest = json.loads((Path(sys.argv[1]) / "proposal-manifest.json").read_text())
assert manifest["candidateKinds"] == ["kotlin"]
assert any(item["name"] == "shared.compileKotlinIosSimulatorArm64" for item in manifest["focusedChecks"])
assert not (Path(sys.argv[1]) / ".gradle").exists()
assert not (Path(sys.argv[1]) / "shared").exists()
PY
python3 - "$TMP_DIR/proposal-child-env.jsonl" <<'PY'
import json
import resource
import sys
from pathlib import Path
records = [json.loads(line) for line in Path(sys.argv[1]).read_text().splitlines() if line.strip()]
compile_record = [record for record in records if record["action"] == "gradle-compile"][-1]
assert compile_record["env"].get("PHOENIX_HARNESS_REPO_ROOT") == str(Path(compile_record["cwd"]).resolve()), compile_record
assert compile_record["rlimitFsize"] == resource.RLIM_INFINITY, compile_record
assert compile_record["rlimitNoFile"] == resource.getrlimit(resource.RLIMIT_NOFILE)[0], compile_record
PY

RUN_OUTPUT_PATCH="$TMP_DIR/run-output.patch"
make_patch "$RUN_OUTPUT_PATCH" '// baseline' '// GENERATED_RUN'
RUN_OUTPUT_ARTIFACT="$TMP_DIR/run-output-artifact"
run_renderer "$REPO" "$RUN_OUTPUT_ARTIFACT" "$RUN_OUTPUT_PATCH" >"$TMP_DIR/run-output.out"
python3 - "$RUN_OUTPUT_ARTIFACT/proposal-manifest.json" <<'PY'
import json
import sys
from pathlib import Path
assert json.loads(Path(sys.argv[1]).read_text())["status"] == "passed"
PY

# Only the exact generated workspace root is accepted after the candidate run;
# an unallowlisted sibling Xcode project and a symlink inside the known root
# remain unsafe worktree artifacts.
for token in GENERATED_UNKNOWN_XCODEPROJ GENERATED_WORKSPACE_SYMLINK; do
    unsafe_patch="$TMP_DIR/${token}.patch"
    make_patch "$unsafe_patch" '// baseline' "// $token"
    unsafe_artifact="$TMP_DIR/${token}-artifact"
    if run_renderer "$REPO" "$unsafe_artifact" "$unsafe_patch" >"$TMP_DIR/${token}.out" 2>&1; then
        fail "unsafe Xcode workspace output was accepted: $token"
    fi
    python3 - "$unsafe_artifact/proposal-manifest.json" <<'PY'
import json
import sys
from pathlib import Path
manifest = json.loads(Path(sys.argv[1]).read_text())
assert manifest["status"] == "failed"
PY
done

# Post-compile ignored output is accepted only for the explicit generated
# roots, after recursive type/path/size validation.  Unknown ignored output,
# symlinks in an allowed tree, and tracked changes outside patch metadata must
# remain failures.
for token in GENERATED_UNKNOWN GENERATED_SYMLINK GENERATED_TRACKED; do
    unsafe_patch="$TMP_DIR/${token}.patch"
    make_kotlin_patch "$unsafe_patch" '// baseline kotlin' "// $token"
    unsafe_artifact="$TMP_DIR/${token}-artifact"
    if run_renderer "$REPO" "$unsafe_artifact" "$unsafe_patch" >"$TMP_DIR/${token}.out" 2>&1; then
        fail "unsafe candidate output was accepted: $token"
    fi
    python3 - "$unsafe_artifact/proposal-manifest.json" <<'PY'
import json
import sys
from pathlib import Path
manifest = json.loads(Path(sys.argv[1]).read_text())
assert manifest["status"] == "failed"
PY
done

printf 'phantom proposal shell tests passed\n'
