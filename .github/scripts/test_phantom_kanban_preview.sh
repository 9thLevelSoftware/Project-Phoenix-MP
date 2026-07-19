#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export SCRIPT_DIR
exec python3 - <<'PY'
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

script_dir = Path(os.environ["SCRIPT_DIR"])
wrapper_source = script_dir / "phantom-kanban-preview.sh"


def fail(message):
    raise AssertionError(message)


def chmod(path, mode):
    os.chmod(path, mode)


def write_private(path, data):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if isinstance(data, bytes):
        path.write_bytes(data)
    else:
        path.write_text(data, encoding="utf-8")
    chmod(path, 0o600)


def write_json(path, value):
    write_private(path, json.dumps(value, sort_keys=True) + "\n")


def make_patch(path, marker="ok"):
    write_private(
        path,
        "diff --git a/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Candidate.kt "
        "b/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Candidate.kt\n"
        "--- a/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Candidate.kt\n"
        "+++ b/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Candidate.kt\n"
        f"@@ -1 +1 @@\n-{marker}\n+candidate\n",
    )


def request(ticket, patch, **overrides):
    value = {
        "schema_version": 1,
        "ticket_id": ticket,
        "fixture": "just-lift-connected",
        "patch_file": str(patch),
        "trusted_input": True,
        "expected": {
            "screen": "just-lift",
            "markers": ["xctest.passed", "phantom.connected"],
        },
    }
    value.update(overrides)
    return value


def make_fake_repo(root):
    repo = root / "fake-phoenix-source"
    scripts = repo / ".github" / "scripts"
    scripts.mkdir(mode=0o700, parents=True)
    shutil.copy2(wrapper_source, scripts / wrapper_source.name)
    chmod(scripts / wrapper_source.name, 0o700)
    renderer_log = root / "renderer-environment.json"
    verifier_log = root / "verifier-environment.jsonl"

    renderer_impl = scripts / "fake-renderer.py"
    renderer_impl.write_text(
        "#!/usr/bin/env python3\n"
        "import hashlib, json, os, signal, stat, subprocess, sys, time\n"
        "from pathlib import Path\n"
        "def private(path, data, binary=False):\n"
        "    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)\n"
        "    if binary: path.write_bytes(data)\n"
        "    else: path.write_text(data, encoding='utf-8')\n"
        "    os.chmod(path, 0o600)\n"
        "def png():\n"
        "    import struct, zlib\n"
        "    def chunk(kind, payload):\n"
        "        return len(payload).to_bytes(4, 'big') + kind + payload + zlib.crc32(kind + payload).to_bytes(4, 'big')\n"
        "    ihdr = struct.pack('>IIBBBBB', 2, 2, 8, 6, 0, 0, 0)\n"
        "    return b'\\x89PNG\\r\\n\\x1a\\n' + chunk(b'IHDR', ihdr) + chunk(b'IDAT', b'') + chunk(b'IEND', b'')\n"
        "if len(sys.argv) != 5 or sys.argv[1] != 'render' or sys.argv[3] != 'just-lift-connected':\n"
        "    raise SystemExit(2)\n"
        "artifact, patch = Path(sys.argv[2]), Path(sys.argv[4])\n"
        "if os.environ.get('PHOENIX_PROPOSAL_TRUSTED_INPUT') != '1': raise SystemExit(3)\n"
        "patch_text = patch.read_bytes().decode('utf-8', 'replace')\n"
        "def marker(prefix):\n"
        "    for line in patch_text.splitlines():\n"
        "        if line.startswith(prefix): return line[len(prefix):]\n"
        "    return ''\n"
        "mode = os.environ.get('PREVIEW_TEST_RENDERER_MODE', '') or marker('TEST_MODE:') or 'ok'\n"
        "if mode == 'replace-patch':\n"
        "    original, replacement, observed = marker('REPLACE_PATCH:').split('|', 2)\n"
        "    os.replace(replacement, original)\n"
        "    Path(observed).write_bytes(patch.read_bytes())\n"
        "if mode == 'replace-result':\n"
        "    result_root, original, outside = marker('REPLACE_RESULT:').split('|', 2)\n"
        "    os.rename(result_root, original)\n"
        "    os.symlink(outside, result_root)\n"
        "if mode == 'sleep':\n"
        "    Path(marker('SLEEP_CHILD:')).write_text(str(os.getpid()) + '\\n', encoding='ascii')\n"
        "    time.sleep(60)\n"
        "if b'FAIL_RENDERER' in patch.read_bytes(): raise SystemExit(17)\n"
        "artifact.mkdir(mode=0o700, parents=True, exist_ok=True)\n"
        "base = subprocess.check_output(['git', '-C', str(Path(__file__).resolve().parents[2]), 'rev-parse', 'HEAD'], text=True).strip()\n"
        "patch_bytes = patch.read_bytes()\n"
        "patch_sha = hashlib.sha256(patch_bytes).hexdigest()\n"
        "changed_file = 'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Candidate.kt'\n"
        "fixture_sha = 'e180679548a2d96dbc59c51449edb3b99c19d3e3be82eca98c0707a21a64e78e'\n"
        "simulator = {'udid': '11111111-2222-3333-4444-555555555555', 'name': 'iPhone 16', 'runtime': 'iOS-18-0', 'state': 'Booted'}\n"
        "command_specs = [('xcodebuild.version', 'toolchain.log'), ('simulator.boot', 'boot.log'), ('simulator.bootstatus', 'bootstatus.log'), ('simulator.terminate', 'terminate.log'), ('simulator.uninstall', 'uninstall.log'), ('build', 'build.log'), ('run-tests', 'test.log'), ('simulator.app-state', 'app-state.log'), ('simulator.logs', 'simulator.log'), ('simulator.screenshot', 'screenshot.log')]\n"
        "commands = [{'name': name, 'exitCode': 0, 'output': output, **({'resultBundle': {'basename': 'test.xcresult', 'status': 'private-not-retained'}} if name == 'run-tests' else {})} for name, output in command_specs]\n"
        "markers = ['xctest.passed', 'phantom.connected', 'simulator.screenshot']\n"
        "captures = [{'slug': 'simulator-after', 'path': 'after.png', 'sha256': 'a' * 64, 'dimensions': {'width': 2, 'height': 2}, 'phase': 'after', 'pair': 'simulator-after', 'checkpoint': 'phantom-connected', 'fixtureId': 'just-lift-connected', 'fixtureSha256': fixture_sha, 'simulator': simulator}, {'slug': 'xctest-after', 'path': 'xctest-attachment.png', 'sha256': 'b' * 64, 'dimensions': {'width': 2, 'height': 2}, 'phase': 'after', 'pair': 'xctest-after', 'checkpoint': 'phantom-connected', 'fixtureId': 'just-lift-connected', 'fixtureSha256': fixture_sha, 'simulator': simulator}]\n"
        "run = {'schemaVersion': 1, 'runId': 'run-fake-1', 'provenance': {'baseSha': base, 'fixture': {'id': 'just-lift-connected', 'sha256': fixture_sha}, 'xcode': 'Xcode 16.0', 'sdk': '18.0', 'simulator': simulator, 'bundleId': 'com.devil.phoenixproject.projectphoenix'}, 'commands': commands, 'semanticMarkers': {'required': markers, 'observed': markers}, 'captures': captures, 'textualArtifacts': [{'path': name} for name in ('toolchain.log', 'build.log', 'test.log', 'app-state.log', 'simulator.log', 'screenshot.log', '.commands.jsonl')]}\n"
        "run_bytes = (json.dumps(run, sort_keys=True, indent=2) + '\\n').encode()\n"
        "run_sha = hashlib.sha256(run_bytes).hexdigest()\n"
        "diff_png = png()\n"
        "diff_sha = hashlib.sha256(diff_png).hexdigest()\n"
        "diff = {'passed': True, 'thresholdPassed': True, 'dimensions': {'width': 2, 'height': 2}, 'width': 2, 'height': 2, 'changedPixels': 0, 'changedPixelRatio': 0.0, 'changedRatio': 0.0, 'meanChannelDelta': 0.0, 'maxChannelDelta': 0, 'maskTopPixels': 0, 'threshold': 0.0, 'inputs': {'before': 'run.json', 'after': 'run.json'}}\n"
        "diff_bytes = (json.dumps(diff, sort_keys=True) + '\\n').encode()\n"
        "diff_sha_json = hashlib.sha256(diff_bytes).hexdigest()\n"
        "identity = {'baseSha': base, 'fixtureId': 'just-lift-connected', 'fixtureSha256': fixture_sha, 'bundleId': 'com.devil.phoenixproject.projectphoenix', 'simulator': simulator, 'commands': [name for name, _ in command_specs], 'markers': sorted(markers)}\n"
        "comparison = {'identity': identity, 'beforeManifestSha256': run_sha, 'afterManifestSha256': run_sha, 'beforeCapture': captures[0], 'afterCapture': captures[0], 'diffJson': {'path': 'comparison/diff.json', 'sha256': diff_sha_json}, 'diffImage': {'path': 'comparison/diff.png', 'sha256': diff_sha, 'dimensions': {'width': 2, 'height': 2}}, 'summary': diff}\n"
        "manifest = {'schemaVersion': 1, 'status': 'passed', 'trustedInput': True, 'fixture': 'just-lift-connected', 'baseSha': base, 'patch': {'path': 'proposal.patch', 'sha256': patch_sha, 'size': len(patch_bytes), 'binary': False, 'format': 'exact-input'}, 'candidateKinds': ['kotlin'], 'allowedChangedFiles': [changed_file], 'actualChangedFiles': [changed_file], 'worktree': {'baseSha': base, 'headSha': base, 'detached': True, 'uncommitted': True, 'statusEntryCount': 1, 'appliedDiffSha256': patch_sha}, 'focusedChecks': [{'name': 'git.diff.check', 'passed': True}], 'before': {'artifact': 'before', 'manifestSha256': run_sha, 'identity': identity}, 'after': {'artifact': 'after', 'manifestSha256': run_sha, 'identity': identity}, 'comparison': comparison, 'evidence': {'proposalMarkdown': 'proposal.md', 'summaryJson': 'evidence-summary.json'}}\n"
        "summary = {'schemaVersion': 1, 'status': 'passed', 'trustedInput': True, 'fixture': 'just-lift-connected', 'baseSha': base, 'patchSha256': patch_sha, 'changedFiles': [changed_file], 'beforeAfterIdentity': identity, 'comparison': comparison, 'artifacts': ['before', 'after', 'proposal.patch', 'proposal-manifest.json', 'proposal.md', 'comparison/diff.json', 'comparison/diff.png']}\n"
        "proposal = '# Phantom proposal evidence\\n\\nStatus: **passed**\\n\\nThis proposal was rendered from the real Phoenix app in a disposable detached worktree using trusted candidate input.\\n\\n- Fixture: `just-lift-connected`\\n- Verified base SHA: `' + base + '`\\n- Proposal patch SHA-256: `' + patch_sha + '`\\n\\n## Allowed changed files\\n\\n- `' + changed_file + '`\\n\\n## Verification\\n\\n- Baseline canonical harness case: verified\\n- Candidate canonical harness case: verified\\n- Kotlin/resource compile gate when required: verified\\n- Bound comparison metadata: verified\\n- Temporary worktree: cleaned after rendering\\n'\n"
        "if mode in ('sleep-verify-before', 'sleep-verify-after'):\n"
        "    private(artifact / ('.sleep-verify-' + ('before' if mode.endswith('before') else 'after')), b'1\\n', binary=True)\n"
        "for name in ('before/run.json', 'after/run.json'):\n"
        "    private(artifact / name, run_bytes, binary=True)\n"
        "private(artifact / 'proposal.md', proposal)\n"
        "private(artifact / 'evidence-summary.json', json.dumps(summary, sort_keys=True, indent=2) + '\\n')\n"
        "private(artifact / 'proposal-manifest.json', json.dumps(manifest, sort_keys=True, indent=2) + '\\n')\n"
        "private(artifact / 'comparison/diff.json', diff_bytes, binary=True)\n"
        "private(artifact / 'comparison/diff.png', diff_png, binary=True)\n"
        "if mode == 'minimal-evidence': private(artifact / 'evidence-summary.json', json.dumps({'schemaVersion': 1, 'status': 'passed'}) + '\\n')\n"
        "if mode == 'unknown-run': run['unknown'] = True; private(artifact / 'before/run.json', json.dumps(run) + '\\n')\n"
        "if mode == 'bad-run-types': run['schemaVersion'] = True; private(artifact / 'after/run.json', json.dumps(run) + '\\n')\n"
        "if mode == 'unknown-diff': diff['unknown'] = True; private(artifact / 'comparison/diff.json', json.dumps(diff) + '\\n')\n"
        "if mode == 'bad-markdown': private(artifact / 'proposal.md', '# Phantom proposal evidence\\nStatus: **passed**\\n')\n"
        "if mode == 'bad-markdown-ref': private(artifact / 'proposal.md', proposal + '\\n- `comparison/unknown.json`\\n')\n"
        "if mode.startswith('patch-mismatch-'):\n"
        "    mismatch = dict(manifest)\n"
        "    mismatch['patch'] = dict(manifest['patch'])\n"
        "    if mode == 'patch-mismatch-sha': mismatch['patch']['sha256'] = '0' * 64\n"
        "    if mode == 'patch-mismatch-size': mismatch['patch']['size'] += 1\n"
        "    if mode == 'patch-mismatch-path': mismatch['patch']['path'] = 'safe.patch'\n"
        "    if mode == 'patch-mismatch-format': mismatch['patch']['format'] = 'unified-diff'\n"
        "    private(artifact / 'proposal-manifest.json', json.dumps(mismatch) + '\\n')\n"
        "leak = os.environ.get('PREVIEW_TEST_LEAK_KIND', '') or marker('LEAK_KIND:')\n"
        "if leak == 'proposal.md': private(artifact / leak, 'absolute=/Users/host/private/candidate.patch\\nAPI_TOKEN=aaaaaaaaaaaaaaaaaaaaaaaa\\n')\n"
        "elif leak == 'evidence-summary.json': private(artifact / leak, json.dumps({'schemaVersion': 1, 'status': 'passed', 'leak': '/private/host/API_TOKEN=aaaaaaaaaaaaaaaaaaaaaaaa'}) + '\\n')\n"
        "elif leak == 'proposal-manifest.json': private(artifact / leak, json.dumps({'schemaVersion': 1, 'status': 'passed', 'fixture': 'just-lift-connected', 'baseSha': base, 'patch': {'path': '/Users/host/private/candidate.patch', 'sha256': '0' * 64, 'size': 1, 'binary': False, 'format': 'exact-input'}}) + '\\n')\n"
        "elif leak in ('before/run.json', 'after/run.json', 'comparison/diff.json'): private(artifact / leak, json.dumps({'schemaVersion': 1, 'leak': 'Bearer aaaaaaaaaaaaaaaaaaaaaaaa /Users/host/private'}) + '\\n')\n"
        "elif leak == 'comparison/diff.png': private(artifact / leak, png() + b' /Users/host/private API_TOKEN=aaaaaaaaaaaaaaaaaaaaaaaa', binary=True)\n"
        "if mode == 'missing': (artifact / 'after/run.json').unlink()\n"
        "if mode == 'malformed': private(artifact / 'comparison/diff.json', '{not-json\\n')\n"
        "if mode == 'duplicate-manifest': private(artifact / 'proposal-manifest.json', '{\\\"schemaVersion\\\":1,\\\"schemaVersion\\\":1,\\\"status\\\":\\\"passed\\\",\\\"fixture\\\":\\\"just-lift-connected\\\",\\\"baseSha\\\":' + json.dumps(base) + '}\\n')\n"
        "if mode == 'bad-manifest-types': private(artifact / 'proposal-manifest.json', json.dumps({'schemaVersion': True, 'status': 'passed', 'fixture': 'just-lift-connected', 'baseSha': base}) + '\\n')\n"
        "if marker('VERIFY_FAIL:'): private(artifact / '.verify-fail', marker('VERIFY_FAIL:'))\n"
        "raise SystemExit(0)\n",
        encoding="utf-8",
    )
    chmod(renderer_impl, 0o600)
    renderer = scripts / "phantom-proposal.sh"
    renderer.write_text(
        "#!/bin/bash\n"
        "set -euo pipefail\n"
        f"/usr/bin/env > {str(renderer_log)!r}\n"
        f"exec /usr/bin/python3 {str(renderer_impl)!r} \"$@\"\n",
        encoding="utf-8",
    )
    chmod(renderer, 0o700)

    harness = scripts / "phantom-harness.sh"
    harness.write_text(
        "#!/usr/bin/env python3\n"
        "import json, os, sys, time\n"
        "from pathlib import Path\n"
        f"LOG = {str(verifier_log)!r}\n"
        "if len(sys.argv) != 3 or sys.argv[1] != 'verify': raise SystemExit(2)\n"
        "with open(LOG, 'a', encoding='utf-8') as stream:\n"
        "    stream.write(json.dumps({'args': sys.argv[1:], 'env': dict(sorted(os.environ.items()))}) + '\\n')\n"
        "if not sys.argv[2].endswith('/before') and not sys.argv[2].endswith('/after'): raise SystemExit(4)\n"
        "phase = Path(sys.argv[2]).name\n"
        "sleep_marker = Path(sys.argv[2]).parent / ('.sleep-verify-' + phase)\n"
        "if sleep_marker.exists():\n"
        "    pid_file = Path(str(sleep_marker) + '.pid')\n"
        "    pid_file.write_text(str(os.getpid()) + '\\n', encoding='ascii')\n"
        "    time.sleep(60)\n"
        "if os.environ.get('PREVIEW_TEST_VERIFY_FAIL') == '1' or (Path(sys.argv[2]).parent / '.verify-fail').exists(): raise SystemExit(17)\n"
        "print('{\"passed\":true}')\n",
        encoding="utf-8",
    )
    chmod(harness, 0o700)

    subprocess.run(["git", "init", "-q", str(repo)], check=True)
    subprocess.run(["git", "-C", str(repo), "config", "user.email", "test@example.invalid"], check=True)
    subprocess.run(["git", "-C", str(repo), "config", "user.name", "Preview Test"], check=True)
    write_private(repo / "README.md", "fake source\n")
    subprocess.run(["git", "-C", str(repo), "add", "README.md"], check=True)
    subprocess.run(["git", "-C", str(repo), "commit", "-qm", "fixture"], check=True)
    return repo, renderer_log, verifier_log


def run_wrapper(repo, request_path, result_root, extra_env=None):
    env = os.environ.copy()
    env.update({
        "PHOENIX_HARNESS_UDID": "11111111-2222-3333-4444-555555555555",
        "PREVIEW_TEST_SECRET_TOKEN": "must-not-cross-env-i",
    })
    if extra_env:
        env.update(extra_env)
    return subprocess.run(
        [str(repo / ".github/scripts/phantom-kanban-preview.sh"), str(request_path), str(result_root)],
        cwd=repo,
        env=env,
        text=True,
        capture_output=True,
    )


def fresh_result(root, name="result"):
    path = root / name
    path.mkdir(mode=0o700)
    chmod(path, 0o700)
    return path


def assert_failure(completed, result, stage):
    if completed.returncode == 0:
        fail(f"expected failure at {stage}, stdout={completed.stdout!r}")
    if not result.exists():
        fail(f"missing safe failure result for {stage}")
    files = sorted(path.relative_to(result).as_posix() for path in result.rglob("*"))
    if files != ["preview-result.json"]:
        fail(f"failure leaked artifacts at {stage}: {files}")
    payload = json.loads((result / "preview-result.json").read_text(encoding="utf-8"))
    expected_reason = {
        "validate-request": "request validation failed",
        "renderer": "renderer failed",
        "interrupted": "preview interrupted",
        "publish": "result publication failed",
    }.get(stage, "canonical verification failed" if stage.startswith("verify-") else "artifact validation failed")
    if payload != {
        "schema_version": 1,
        "status": "failed",
        "stage": stage,
        "reason": expected_reason,
    }:
        fail(f"unexpected failure payload at {stage}: {payload!r}")
    if "/" in json.dumps(payload):
        fail(f"failure payload contains unsafe path at {stage}")
    if "Traceback" in completed.stderr or str(result) in completed.stderr or str(result) in completed.stdout:
        fail(f"failure output was not sanitized at {stage}: stdout={completed.stdout!r} stderr={completed.stderr!r}")


def assert_no_traceback(completed, label):
    if "Traceback" in completed.stdout or "Traceback" in completed.stderr:
        fail(f"traceback leaked for {label}: stdout={completed.stdout!r} stderr={completed.stderr!r}")
    if "/Users/" in completed.stdout or "/Users/" in completed.stderr or "/private/" in completed.stdout or "/private/" in completed.stderr:
        fail(f"host path leaked for {label}: stdout={completed.stdout!r} stderr={completed.stderr!r}")


def assert_result_only(result, stage, reason):
    files = sorted(path.relative_to(result).as_posix() for path in result.rglob("*"))
    if files != ["preview-result.json"]:
        fail(f"expected result-only publication for {stage}: {files}")
    payload = json.loads((result / "preview-result.json").read_text(encoding="utf-8"))
    if payload != {"schema_version": 1, "status": "failed", "stage": stage, "reason": reason}:
        fail(f"unexpected result-only payload for {stage}: {payload!r}")


def main():
    if not wrapper_source.exists():
        # This is intentional RED evidence before the production wrapper exists.
        fail("phantom-kanban-preview.sh is missing")
    with tempfile.TemporaryDirectory(prefix="phantom-kanban-preview-test-") as temp_name:
        temp = Path(temp_name)
        repo, renderer_log, verifier_log = make_fake_repo(temp)
        patch = temp / "private" / "candidate.patch"
        make_patch(patch)
        request_path = temp / "private" / "request.json"
        valid_result = fresh_result(temp, "valid-result")
        write_json(request_path, request("KANBAN-42", patch))

        completed = run_wrapper(repo, request_path, valid_result)
        if completed.returncode != 0:
            fail(f"valid request failed: rc={completed.returncode}, stdout={completed.stdout!r}, stderr={completed.stderr!r}, result={list(valid_result.rglob('*'))!r}, result_text={(valid_result / 'preview-result.json').read_text(encoding='utf-8') if (valid_result / 'preview-result.json').exists() else None!r}")
        expected_paths = {
            "preview-result.json",
            "proposal.md",
            "evidence-summary.json",
            "proposal-manifest.json",
            "comparison/diff.png",
            "comparison/diff.json",
            "before/run.json",
            "after/run.json",
        }
        actual_paths = {path.relative_to(valid_result).as_posix() for path in valid_result.rglob("*") if path.is_file()}
        if actual_paths != expected_paths:
            fail(f"success allowlist mismatch: {sorted(actual_paths)}")
        payload = json.loads((valid_result / "preview-result.json").read_text(encoding="utf-8"))
        if set(payload) != {"schema_version", "status", "ticket_id", "fixture", "base_sha", "patch_sha256", "artifacts"}:
            fail(f"unsafe success result keys: {payload}")
        if payload["status"] != "passed" or payload["schema_version"] != 1 or payload["ticket_id"] != "KANBAN-42" or payload["fixture"] != "just-lift-connected":
            fail(f"incorrect success metadata: {payload}")
        if not re.fullmatch(r"[0-9a-f]{40}", payload["base_sha"]):
            fail("base SHA is not a bounded hash")
        if payload["patch_sha256"] != hashlib.sha256(patch.read_bytes()).hexdigest():
            fail("patch SHA mismatch")
        artifact_entries = payload["artifacts"]
        if [entry["path"] for entry in artifact_entries] != [
            "proposal.md", "evidence-summary.json", "proposal-manifest.json", "comparison/diff.png",
            "comparison/diff.json", "before/run.json", "after/run.json",
        ]:
            fail(f"unexpected artifact paths: {artifact_entries}")
        for entry in artifact_entries:
            if set(entry) != {"path", "sha256"} or "/" not in entry["path"] and entry["path"].startswith("/"):
                fail(f"unsafe artifact entry: {entry}")
            path = valid_result / entry["path"]
            if entry["sha256"] != hashlib.sha256(path.read_bytes()).hexdigest():
                fail(f"artifact hash mismatch: {entry}")
        if "/Users/" in json.dumps(payload) or "/private/" in json.dumps(payload):
            fail("success result leaked an absolute host path")
        if (valid_result / "host-leak.txt").exists():
            fail("unapproved renderer artifact leaked into result")
        for path in valid_result.rglob("*"):
            if path.is_file() and stat.S_IMODE(path.stat().st_mode) != 0o600:
                fail(f"result file permissions are not private: {path}")
        renderer_env = {}
        for line in renderer_log.read_text(encoding="utf-8").splitlines():
            key, separator, value = line.partition("=")
            if separator:
                renderer_env[key] = value
        allowed_env = {
            "PATH", "HOME", "TMPDIR", "LC_ALL", "PWD", "SHLVL", "_", "PHOENIX_PROPOSAL_TRUSTED_INPUT",
            "PHOENIX_HARNESS_UDID", "PHOENIX_HARNESS_ALLOW_DESTRUCTIVE", "JAVA_HOME", "DEVELOPER_DIR",
        }
        if set(renderer_env) - allowed_env:
            fail(f"renderer received non-minimal environment: {set(renderer_env) - allowed_env}")
        if renderer_env.get("PHOENIX_PROPOSAL_TRUSTED_INPUT") != "1" or "PREVIEW_TEST_SECRET_TOKEN" in renderer_env:
            fail(f"renderer credential/trust boundary failed: {renderer_env}")
        verify_records = [json.loads(line) for line in verifier_log.read_text(encoding="utf-8").splitlines()]
        if [record["args"][1].rsplit("/", 1)[-1] for record in verify_records] != ["before", "after"]:
            fail(f"canonical verifier was not called independently: {verify_records}")

        malformed = temp / "malformed.json"
        write_private(malformed, "{not-json")
        result = fresh_result(temp, "malformed-result")
        assert_failure(run_wrapper(repo, malformed, result), result, "validate-request")

        foreign = temp / "foreign.json"
        write_json(foreign, request("KANBAN-43", patch, fixture="foreign-fixture"))
        result = fresh_result(temp, "foreign-result")
        assert_failure(run_wrapper(repo, foreign, result), result, "validate-request")

        nested_patch = repo / "nested.patch"
        make_patch(nested_patch)
        nested = temp / "nested.json"
        write_json(nested, request("KANBAN-44", nested_patch))
        result = fresh_result(temp, "nested-result")
        assert_failure(run_wrapper(repo, nested, result), result, "validate-request")

        untrusted = temp / "untrusted.json"
        write_json(untrusted, request("KANBAN-45", patch, trusted_input=False))
        result = fresh_result(temp, "untrusted-result")
        assert_failure(run_wrapper(repo, untrusted, result), result, "validate-request")

        nonempty = fresh_result(temp, "nonempty-result")
        write_private(nonempty / "caller-owned.txt", "keep me\n")
        nonempty_request = temp / "nonempty.json"
        write_json(nonempty_request, request("KANBAN-46", patch))
        completed = run_wrapper(repo, nonempty_request, nonempty)
        if completed.returncode == 0:
            fail("nonempty result root unexpectedly accepted")
        if (nonempty / "preview-result.json").exists():
            fail("nonempty result root received a result")
        if (nonempty / "caller-owned.txt").read_text(encoding="utf-8") != "keep me\n":
            fail("nonempty result root was modified")

        bad_mode = fresh_result(temp, "bad-mode-result")
        chmod(bad_mode, 0o755)
        bad_mode_request = temp / "bad-mode.json"
        write_json(bad_mode_request, request("KANBAN-47", patch))
        completed = run_wrapper(repo, bad_mode_request, bad_mode)
        if completed.returncode == 0 or (bad_mode / "preview-result.json").exists():
            fail("bad-mode result root unexpectedly accepted or modified")

        nested_result = repo / "nested-result"
        nested_result.mkdir(mode=0o700)
        chmod(nested_result, 0o700)
        nested_result_request = temp / "nested-result-request.json"
        write_json(nested_result_request, request("KANBAN-48", patch))
        completed = run_wrapper(repo, nested_result_request, nested_result)
        if completed.returncode == 0 or (nested_result / "preview-result.json").exists():
            fail("result root nested under source checkout was accepted")

        failure_patch = temp / "private" / "renderer-failure.patch"
        make_patch(failure_patch, marker="FAIL_RENDERER")
        failure_request = temp / "renderer-failure.json"
        write_json(failure_request, request("KANBAN-49", failure_patch))
        result = fresh_result(temp, "renderer-failure-result")
        assert_failure(run_wrapper(repo, failure_request, result), result, "renderer")

        duplicate = temp / "duplicate.json"
        duplicate_raw = json.dumps(request("KANBAN-50", patch))
        write_private(duplicate, duplicate_raw[:-1] + ',"schema_version":1}\n')
        result = fresh_result(temp, "duplicate-result")
        assert_failure(run_wrapper(repo, duplicate, result), result, "validate-request")

        bool_schema = temp / "bool-schema.json"
        write_json(bool_schema, request("KANBAN-51", patch, schema_version=True))
        result = fresh_result(temp, "bool-schema-result")
        assert_failure(run_wrapper(repo, bool_schema, result), result, "validate-request")

        duplicate_manifest_patch = temp / "duplicate-manifest.patch"
        make_patch(duplicate_manifest_patch, marker="ok\nTEST_MODE:duplicate-manifest")
        duplicate_manifest_request = temp / "duplicate-manifest.json"
        write_json(duplicate_manifest_request, request("KANBAN-61", duplicate_manifest_patch))
        duplicate_manifest_result = fresh_result(temp, "duplicate-manifest-result")
        assert_failure(run_wrapper(repo, duplicate_manifest_request, duplicate_manifest_result), duplicate_manifest_result, "validate-artifacts")

        bad_manifest_types_patch = temp / "bad-manifest-types.patch"
        make_patch(bad_manifest_types_patch, marker="ok\nTEST_MODE:bad-manifest-types")
        bad_manifest_types_request = temp / "bad-manifest-types.json"
        write_json(bad_manifest_types_request, request("KANBAN-62", bad_manifest_types_patch))
        bad_manifest_types_result = fresh_result(temp, "bad-manifest-types-result")
        assert_failure(run_wrapper(repo, bad_manifest_types_request, bad_manifest_types_result), bad_manifest_types_result, "validate-artifacts")

        for mode, label in (
            ("minimal-evidence", "minimal-evidence"),
            ("unknown-run", "unknown-run"),
            ("bad-run-types", "bad-run-types"),
            ("unknown-diff", "unknown-diff"),
            ("bad-markdown", "bad-markdown"),
            ("bad-markdown-ref", "bad-markdown-ref"),
        ):
            mode_patch = temp / (label + ".patch")
            make_patch(mode_patch, marker="ok\nTEST_MODE:" + mode)
            mode_request = temp / (label + ".json")
            write_json(mode_request, request("KANBAN-63", mode_patch))
            mode_result = fresh_result(temp, label + "-result")
            completed = run_wrapper(repo, mode_request, mode_result)
            if completed.returncode == 0:
                fail(f"strict mode unexpectedly accepted: {mode}")
            assert_failure(completed, mode_result, "validate-artifacts")

        for mode in ("patch-mismatch-sha", "patch-mismatch-size", "patch-mismatch-path", "patch-mismatch-format"):
            mode_patch = temp / (mode + ".patch")
            make_patch(mode_patch, marker="ok\nTEST_MODE:" + mode)
            mode_request = temp / (mode + ".json")
            write_json(mode_request, request("KANBAN-64", mode_patch))
            mode_result = fresh_result(temp, mode + "-result")
            assert_failure(run_wrapper(repo, mode_request, mode_result), mode_result, "validate-artifacts")

        request_in_worktree = repo / "request-in-registered-worktree.json"
        write_json(request_in_worktree, request("KANBAN-65", patch))
        request_in_worktree_result = fresh_result(temp, "request-in-registered-worktree-result")
        assert_failure(run_wrapper(repo, request_in_worktree, request_in_worktree_result), request_in_worktree_result, "validate-request")

        task_worktree = temp / "registered-task-worktree"
        subprocess.run(["git", "-C", str(repo), "worktree", "add", "-q", "-b", "task-worktree", str(task_worktree)], check=True)
        request_in_task_worktree = task_worktree / "task-worktree-request.json"
        write_json(request_in_task_worktree, request("KANBAN-66", patch))
        request_in_task_worktree_result = fresh_result(temp, "task-worktree-request-result")
        assert_failure(run_wrapper(repo, request_in_task_worktree, request_in_task_worktree_result), request_in_task_worktree_result, "validate-request")
        subprocess.run(["git", "-C", str(repo), "worktree", "remove", "--force", str(task_worktree)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        for phase, signal_name in (("sleep-verify-before", "SIGINT"), ("sleep-verify-after", "SIGHUP")):
            phase_patch = temp / (phase + ".patch")
            make_patch(phase_patch, marker="ok\nTEST_MODE:" + phase)
            phase_request = temp / (phase + ".json")
            write_json(phase_request, request("KANBAN-67", phase_patch))
            phase_result = fresh_result(temp, phase + "-result")
            verifier_log.write_text("", encoding="utf-8")
            process = subprocess.Popen(
                [str(repo / ".github/scripts/phantom-kanban-preview.sh"), str(phase_request), str(phase_result)],
                cwd=repo,
                env={**os.environ, "PHOENIX_HARNESS_UDID": "11111111-2222-3333-4444-555555555555"},
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            deadline = time.monotonic() + 10
            expected_records = 1 if phase.endswith("before") else 2
            while time.monotonic() < deadline:
                if verifier_log.exists() and len(verifier_log.read_text(encoding="utf-8").splitlines()) >= expected_records:
                    break
                time.sleep(0.02)
            else:
                process.kill()
                process.communicate(timeout=5)
                fail("verification child did not start for " + phase)
            process.send_signal(getattr(signal, signal_name))
            stdout, stderr = process.communicate(timeout=10)
            if not list(phase_result.rglob("*")):
                fail(f"phase signal left empty result: phase={phase} rc={process.returncode} stdout={stdout!r} stderr={stderr!r}")
            assert_result_only(phase_result, "interrupted", "preview interrupted")
            if process.returncode == 0:
                fail("signal unexpectedly returned success for " + phase)

        request_link = temp / "request-link.json"
        request_link.symlink_to(request_path)
        result = fresh_result(temp, "request-link-result")
        assert_failure(run_wrapper(repo, request_link, result), result, "validate-request")

        patch_link = temp / "patch-link.patch"
        patch_link.symlink_to(patch)
        patch_link_request = temp / "patch-link.json"
        write_json(patch_link_request, request("KANBAN-52", patch_link))
        result = fresh_result(temp, "patch-link-result")
        assert_failure(run_wrapper(repo, patch_link_request, result), result, "validate-request")

        outside_result = temp / "outside-result"
        outside_result.mkdir(mode=0o700)
        result_link = temp / "result-link"
        result_link.symlink_to(outside_result, target_is_directory=True)
        link_request = temp / "result-link-request.json"
        write_json(link_request, request("KANBAN-53", patch))
        completed = run_wrapper(repo, link_request, result_link)
        assert_no_traceback(completed, "result symlink")
        if completed.returncode == 0 or list(outside_result.iterdir()):
            fail("result symlink was accepted or modified")

        replacement = temp / "replacement.patch"
        make_patch(replacement, marker="replacement")
        observed_patch = temp / "observed-patch.bin"
        replacement_patch = temp / "replacement-race.patch"
        make_patch(replacement_patch, marker=f"ok\nTEST_MODE:replace-patch\nREPLACE_PATCH:{replacement_patch}|{replacement}|{observed_patch}")
        replacement_request = temp / "replacement-race.json"
        write_json(replacement_request, request("KANBAN-54", replacement_patch))
        replacement_snapshot = replacement_patch.read_bytes()
        replacement_result = fresh_result(temp, "replacement-race-result")
        completed = run_wrapper(repo, replacement_request, replacement_result)
        if completed.returncode != 0:
            fail(f"patch replacement race failed unexpectedly: rc={completed.returncode} stdout={completed.stdout!r} stderr={completed.stderr!r}")
        if observed_patch.read_bytes() != replacement_snapshot:
            fail("renderer observed replaced patch instead of immutable snapshot")
        published = json.loads((replacement_result / "preview-result.json").read_text(encoding="utf-8"))
        if published["patch_sha256"] != hashlib.sha256(replacement_snapshot).hexdigest():
            fail("published patch hash was not derived from the snapshot")

        outside_result = temp / "replacement-outside"
        outside_result.mkdir(mode=0o700)
        raced_result = fresh_result(temp, "replacement-result")
        original_result = temp / "replacement-result-original"
        race_patch = temp / "result-race.patch"
        make_patch(race_patch, marker=f"ok\nTEST_MODE:replace-result\nREPLACE_RESULT:{raced_result}|{original_result}|{outside_result}")
        race_request = temp / "result-race.json"
        write_json(race_request, request("KANBAN-55", race_patch))
        completed = run_wrapper(repo, race_request, raced_result)
        assert_no_traceback(completed, "result replacement")
        if completed.returncode == 0:
            fail("result replacement race unexpectedly succeeded")
        if list(outside_result.iterdir()):
            fail("result replacement race published outside the held result directory")
        assert_result_only(original_result, "publish", "result publication failed")
        raced_result.unlink()

        for signal_name in ("SIGINT", "SIGHUP", "SIGTERM"):
            publication_patch = temp / ("publication-" + signal_name + ".patch")
            make_patch(publication_patch)
            publication_request = temp / ("publication-" + signal_name + ".json")
            write_json(publication_request, request("KANBAN-68", publication_patch))
            publication_result = fresh_result(temp, "publication-" + signal_name + "-result")
            process = subprocess.Popen(
                [str(repo / ".github/scripts/phantom-kanban-preview.sh"), str(publication_request), str(publication_result)],
                cwd=repo,
                env={**os.environ, "PHOENIX_HARNESS_UDID": "11111111-2222-3333-4444-555555555555"},
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            marker = publication_result / ".publication-staging" / ".publication-precheck"
            deadline = time.monotonic() + 10
            while not marker.exists() and time.monotonic() < deadline:
                time.sleep(0.01)
            if not marker.exists():
                process.kill()
                process.communicate(timeout=5)
                fail("publication precheck marker did not appear for " + signal_name)
            process.send_signal(getattr(signal, signal_name))
            stdout, stderr = process.communicate(timeout=10)
            if process.returncode == 0:
                fail("publication race unexpectedly returned success for " + signal_name)
            assert_no_traceback(type("Completed", (), {"stdout": stdout, "stderr": stderr})(), "publication " + signal_name)
            assert_result_only(publication_result, "interrupted", "preview interrupted")

        for leak_kind in (
            "proposal.md",
            "evidence-summary.json",
            "proposal-manifest.json",
            "before/run.json",
            "after/run.json",
            "comparison/diff.json",
            "comparison/diff.png",
        ):
            leak_patch = temp / ("leak-" + leak_kind.replace("/", "-") + ".patch")
            make_patch(leak_patch, marker=f"ok\nLEAK_KIND:{leak_kind}")
            leak_request = temp / (leak_patch.stem + ".json")
            write_json(leak_request, request("KANBAN-56", leak_patch))
            leak_result = fresh_result(temp, leak_patch.stem + "-result")
            assert_failure(run_wrapper(repo, leak_request, leak_result), leak_result, "validate-artifacts")

        missing_patch = temp / "missing-artifact.patch"
        make_patch(missing_patch, marker="ok\nTEST_MODE:missing")
        missing_request = temp / "missing-artifact.json"
        write_json(missing_request, request("KANBAN-57", missing_patch))
        missing_result = fresh_result(temp, "missing-artifact-result")
        assert_failure(run_wrapper(repo, missing_request, missing_result), missing_result, "validate-artifacts")

        malformed_patch = temp / "malformed-artifact.patch"
        make_patch(malformed_patch, marker="ok\nTEST_MODE:malformed")
        malformed_artifact_request = temp / "malformed-artifact.json"
        write_json(malformed_artifact_request, request("KANBAN-58", malformed_patch))
        malformed_artifact_result = fresh_result(temp, "malformed-artifact-result")
        assert_failure(run_wrapper(repo, malformed_artifact_request, malformed_artifact_result), malformed_artifact_result, "validate-artifacts")

        verify_fail_patch = temp / "verify-failure.patch"
        make_patch(verify_fail_patch, marker="ok\nVERIFY_FAIL:failure")
        verify_fail_request = temp / "verify-failure.json"
        write_json(verify_fail_request, request("KANBAN-59", verify_fail_patch))
        verify_fail_result = fresh_result(temp, "verify-failure-result")
        assert_failure(run_wrapper(repo, verify_fail_request, verify_fail_result), verify_fail_result, "verify-before")

        for signal_name in ("SIGINT", "SIGHUP", "SIGTERM"):
            child_pid_file = temp / ("renderer-child-" + signal_name + ".pid")
            signal_patch = temp / ("signal-" + signal_name + ".patch")
            make_patch(signal_patch, marker=f"ok\nTEST_MODE:sleep\nSLEEP_CHILD:{child_pid_file}")
            signal_request = temp / ("signal-" + signal_name + ".json")
            write_json(signal_request, request("KANBAN-60", signal_patch))
            signal_result = fresh_result(temp, "signal-" + signal_name + "-result")
            process = subprocess.Popen(
                [str(repo / ".github/scripts/phantom-kanban-preview.sh"), str(signal_request), str(signal_result)],
                cwd=repo,
                env={**os.environ, "PHOENIX_HARNESS_UDID": "11111111-2222-3333-4444-555555555555"},
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            deadline = time.monotonic() + 10
            while not child_pid_file.exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            if not child_pid_file.exists():
                process.kill()
                process.communicate(timeout=5)
                fail("renderer child did not start for " + signal_name)
            child_pid = int(child_pid_file.read_text(encoding="ascii").strip())
            process.send_signal(getattr(signal, signal_name))
            stdout, stderr = process.communicate(timeout=10)
            if process.returncode == 0:
                fail(signal_name + " unexpectedly returned success")
            if "Traceback" in stdout or "Traceback" in stderr or "/private/" in stdout or "/private/" in stderr:
                fail(f"{signal_name} output was not sanitized: stdout={stdout!r} stderr={stderr!r}")
            assert_result_only(signal_result, "interrupted", "preview interrupted")
            try:
                os.kill(child_pid, 0)
            except ProcessLookupError:
                pass
            else:
                fail(signal_name + " left renderer child alive")

    print("PASS: constrained Phoenix Kanban preview wrapper contract tests")


if __name__ == "__main__":
    main()
PY
