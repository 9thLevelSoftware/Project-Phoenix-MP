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
import stat
import subprocess
import sys
import tempfile
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
        "import hashlib, json, os, stat, sys\n"
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
        "if b'FAIL_RENDERER' in patch.read_bytes(): raise SystemExit(17)\n"
        "artifact.mkdir(mode=0o700, parents=True, exist_ok=True)\n"
        "base = __import__('subprocess').check_output(['git', '-C', str(Path(__file__).resolve().parents[2]), 'rev-parse', 'HEAD'], text=True).strip()\n"
        "for name in ('before/run.json', 'after/run.json'):\n"
        "    private(artifact / name, '{\\\"schemaVersion\\\":1}\\n')\n"
        "private(artifact / 'proposal.md', '# Phantom proposal evidence\\nStatus: **passed**\\n')\n"
        "private(artifact / 'evidence-summary.json', json.dumps({'schemaVersion': 1, 'status': 'passed'}) + '\\n')\n"
        "private(artifact / 'proposal-manifest.json', json.dumps({'schemaVersion': 1, 'status': 'passed', 'fixture': 'just-lift-connected', 'baseSha': base}) + '\\n')\n"
        "private(artifact / 'comparison/diff.json', json.dumps({'passed': True}) + '\\n')\n"
        "private(artifact / 'comparison/diff.png', png(), binary=True)\n"
        "private(artifact / 'host-leak.txt', '/Users/host/private/candidate.patch\\n')\n"
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
        "import json, os, sys\n"
        f"LOG = {str(verifier_log)!r}\n"
        "if len(sys.argv) != 3 or sys.argv[1] != 'verify': raise SystemExit(2)\n"
        "with open(LOG, 'a', encoding='utf-8') as stream:\n"
        "    stream.write(json.dumps({'args': sys.argv[1:], 'env': dict(sorted(os.environ.items()))}) + '\\n')\n"
        "if not sys.argv[2].endswith('/before') and not sys.argv[2].endswith('/after'): raise SystemExit(4)\n"
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
    if payload != {
        "schema_version": 1,
        "status": "failed",
        "stage": stage,
        "reason": {
            "validate-request": "request validation failed",
            "renderer": "renderer failed",
        }.get(stage, "canonical verification failed" if stage.startswith("verify-") else "artifact validation failed"),
    }:
        fail(f"unexpected failure payload at {stage}: {payload!r}")
    if "/" in json.dumps(payload):
        fail(f"failure payload contains unsafe path at {stage}")


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

    print("PASS: constrained Phoenix Kanban preview wrapper contract tests")


if __name__ == "__main__":
    main()
PY
