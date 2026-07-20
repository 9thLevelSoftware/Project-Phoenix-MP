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
REAL_XML_PATCH = (
    "diff --git a/shared/src/commonMain/composeResources/values/strings.xml "
    "b/shared/src/commonMain/composeResources/values/strings.xml\n"
    "--- a/shared/src/commonMain/composeResources/values/strings.xml\n"
    "+++ b/shared/src/commonMain/composeResources/values/strings.xml\n"
    "@@ -1 +1,4 @@\n"
    "-<string name=\"autostart_ready\">AUTO-START READY</string>\n"
    "+<resources>\n"
    "+<string name=\"autostart_ready\">SIMULATOR READY</string>\n"
    "+<bool name=\"api_token_enabled\">false</bool>\n"
    "+</resources>\n"
)

# This fixture is deliberately shaped from the current producer contract in
# phantom-harness.sh/write_manifest and phantom-proposal.sh/write_success_outputs:
# the exact command journal, run-manifest provenance/capture topology, private
# log set, real PNG chunk forms, diff JSON fields, and public Markdown claims.
# It is self-contained and sanitized, but not a minimal success-shaped fake.
PRODUCER_COMMAND_SPECS = (
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
PRODUCER_MARKERS = ("xctest.passed", "phantom.connected", "simulator.screenshot")
PRODUCER_TEXTUAL_ARTIFACTS = ("toolchain.log", "build.log", "test.log", "app-state.log", "simulator.log", "screenshot.log", ".commands.jsonl")
PRODUCER_FIXTURE_SHA256 = "e180679548a2d96dbc59c51449edb3b99c19d3e3be82eca98c0707a21a64e78e"
PRODUCER_PUBLIC_VERIFICATION = (
    "- Baseline canonical harness case: verified",
    "- Candidate canonical harness case: verified",
    "- Kotlin/resource compile gate when required: verified",
    "- Bound comparison metadata: verified",
    "- Temporary worktree: cleaned after rendering",
)
LARGE_BINARY_RESOURCE = "shared/src/commonMain/composeResources/values/large.png"
LARGE_BINARY_SIZE = 1024 * 1024


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


def valid_png(
    metadata=b"safe metadata",
    pixels=b"\x00" + b"\x00" * 8 + b"\x00" + b"\x00" * 8,
    keyword=b"XML:com.adobe.xmp",
    compression_flag=0,
    compression_method=0,
):
    import struct
    import zlib

    def chunk(kind, payload):
        return len(payload).to_bytes(4, "big") + kind + payload + zlib.crc32(kind + payload).to_bytes(4, "big")

    ihdr = struct.pack(">IIBBBBB", 2, 2, 8, 6, 0, 0, 0)
    encoded_metadata = zlib.compress(metadata) if compression_flag == 1 else metadata
    itxt = keyword + b"\x00" + bytes((compression_flag, compression_method)) + b"\x00\x00" + encoded_metadata
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"iTXt", itxt) + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b"")


def large_valid_png(seed):
    import struct
    import zlib

    def chunk(kind, payload):
        return len(payload).to_bytes(4, "big") + kind + payload + zlib.crc32(kind + payload).to_bytes(4, "big")

    width = height = 512
    raw = deterministic_binary_payload(seed)
    pixels = (raw * ((height * (1 + width * 4) + len(raw) - 1) // len(raw)))[:height * (1 + width * 4)]
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(pixels, 0)) + chunk(b"IEND", b"")


def make_patch(path, marker="ok"):
    new_lines = ["candidate"] + (marker.splitlines() or [""])
    new_body = "".join(f"+{line}\n" for line in new_lines)
    write_private(
        path,
        "diff --git a/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Candidate.kt "
        "b/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Candidate.kt\n"
        "--- a/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Candidate.kt\n"
        "+++ b/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Candidate.kt\n"
        f"@@ -1 +1,{len(new_lines)} @@\n-ok\n{new_body}",
    )


def make_nul_swift_patch(path):
    name = "iosApp/VitruvianPhoenix/VitruvianPhoenix/Opaque.swift"
    write_private(path, (
        f"diff --git a/{name} b/{name}\n"
        "new file mode 100644\n"
        "--- /dev/null\n"
        f"+++ b/{name}\n"
        "@@ -0,0 +1 @@\n"
        "opaque\x00swift\n"
    ).encode("utf-8"))


def make_duplicate_json_patch(path):
    resource = "shared/src/commonMain/composeResources/values/config.json"
    write_private(path, (
        f"diff --git a/{resource} b/{resource}\n"
        f"--- a/{resource}\n+++ b/{resource}\n"
        "@@ -1 +1 @@\n"
        '-{\"title\":\"safe\"}\n'
        '+{\"title\":\"safe\",\"title\":\"also-safe\"}\n'
    ))


def make_nested_xml_credential_patch(path):
    resource = "shared/src/commonMain/composeResources/values/strings.xml"
    write_private(path, (
        f"diff --git a/{resource} b/{resource}\n"
        f"--- a/{resource}\n+++ b/{resource}\n"
        "@@ -1 +1 @@\n"
        '-<string name=\"autostart_ready\">AUTO-START READY</string>\n'
        '+<api_token><value>secret</value></api_token>\n'
    ))


def make_xml_patch(path):
    write_private(path, REAL_XML_PATCH)


def make_structured_json_credential_patch(path):
    resource = "shared/src/commonMain/composeResources/values/config.json"
    write_private(
        path,
        f"diff --git a/{resource} b/{resource}\n"
        f"--- a/{resource}\n"
        f"+++ b/{resource}\n"
        "@@ -1 +1 @@\n"
        '-{\"title\":\"safe\"}\n'
        '+{\"api_token\":\"' + "a" * 32 + '\"}\n',
    )


def make_generic_json_credential_patch(path):
    resource = "shared/src/commonMain/composeResources/values/config.json"
    write_private(
        path,
        f"diff --git a/{resource} b/{resource}\n"
        f"--- a/{resource}\n"
        f"+++ b/{resource}\n"
        "@@ -1 +1 @@\n"
        '-{\"title\":\"safe\"}\n'
        '+{\"title\":\"TOKEN=' + "a" * 32 + '\"}\n',
    )


def make_structured_xml_credential_patch(path):
    resource = "shared/src/commonMain/composeResources/values/strings.xml"
    secret_values = {
        "api_token": "a" * 16,
        "apiToken": "b" * 17,
        "api-token": "c" * 18,
    }
    xml = "\n".join(
        [
            "<resources>",
            *[
                f'<string name="{name}">{value}</string>'
                for name, value in secret_values.items()
            ],
            "</resources>",
        ]
    ) + "\n"
    added_lines = "".join(f"+{line}\n" for line in xml.splitlines())
    write_private(
        path,
        f"diff --git a/{resource} b/{resource}\n"
        f"--- a/{resource}\n"
        f"+++ b/{resource}\n"
        f"@@ -1 +1,{len(xml.splitlines())} @@\n"
        '-<string name=\"autostart_ready\">AUTO-START READY</string>\n'
        + added_lines,
    )


def make_generic_xml_credential_patch(path):
    resource = "shared/src/commonMain/composeResources/values/strings.xml"
    xml = "<resources><string name=\"label\">TOKEN=" + "a" * 32 + "</string></resources>\n"
    added_lines = "".join(f"+{line}\n" for line in xml.splitlines())
    write_private(
        path,
        f"diff --git a/{resource} b/{resource}\n"
        f"--- a/{resource}\n"
        f"+++ b/{resource}\n"
        "@@ -1 +1 @@\n"
        '-<string name=\"autostart_ready\">AUTO-START READY</string>\n'
        + added_lines,
    )


def make_binary_payload_credential_patch(path):
    resource = "shared/src/commonMain/composeResources/values/audio.mp3"
    with tempfile.TemporaryDirectory(prefix="binary-credential-source-") as temp_name:
        repo = Path(temp_name) / "repo"
        target = repo / resource
        target.parent.mkdir(mode=0o700, parents=True)
        target.write_bytes(b"ID3\x04\x00\x00\x00\x00\x00\x00")
        subprocess.run(["git", "-C", str(repo), "init", "-q"], check=True)
        subprocess.run(["git", "-C", str(repo), "config", "user.email", "test@example.invalid"], check=True)
        subprocess.run(["git", "-C", str(repo), "config", "user.name", "Binary Credential Fixture"], check=True)
        subprocess.run(["git", "-C", str(repo), "add", resource], check=True)
        subprocess.run(["git", "-C", str(repo), "commit", "-qm", "base"], check=True)
        target.write_bytes(b"ID3\x04\x00\x00\x00\x00\x00\x00API_TOKEN=" + b"c" * 32)
        diff = subprocess.check_output(["git", "-C", str(repo), "diff", "--binary", "--full-index", "HEAD", "--", resource])
    write_private(path, diff)


def make_binary_patch(path):
    """Create the same deterministic tracked-resource binary diff producer emits."""
    resource = "shared/src/commonMain/composeResources/values/icon.png"
    with tempfile.TemporaryDirectory(prefix="binary-patch-source-") as temp_name:
        repo = Path(temp_name) / "repo"
        (repo / Path(resource).parent).mkdir(mode=0o700, parents=True)
        (repo / resource).write_bytes(valid_png(b"base"))
        subprocess.run(["git", "-C", str(repo), "init", "-q"], check=True)
        subprocess.run(["git", "-C", str(repo), "config", "user.email", "test@example.invalid"], check=True)
        subprocess.run(["git", "-C", str(repo), "config", "user.name", "Binary Fixture"], check=True)
        subprocess.run(["git", "-C", str(repo), "add", resource], check=True)
        subprocess.run(["git", "-C", str(repo), "commit", "-qm", "base"], check=True)
        (repo / resource).write_bytes(valid_png(b"candidate", b"\x00" + b"\xff\x00\x00\xff" * 2 + b"\x00" + b"\xff\x00\x00\xff" * 2))
        diff = subprocess.check_output(["git", "-C", str(repo), "diff", "--binary", "--full-index", "HEAD", "--", resource])
    write_private(path, diff)


def deterministic_binary_payload(seed):
    """Return incompressible, deterministic bytes for a large Git binary diff."""
    blocks = []
    counter = 0
    total = 0
    while total < LARGE_BINARY_SIZE:
        block = hashlib.sha256(seed + counter.to_bytes(8, "big")).digest()
        blocks.append(block)
        total += len(block)
        counter += 1
    return b"".join(blocks)[:LARGE_BINARY_SIZE]


def make_large_binary_patch(path):
    """Create a deterministic 1 MiB tracked-resource binary diff."""
    with tempfile.TemporaryDirectory(prefix="large-binary-patch-source-") as temp_name:
        repo = Path(temp_name) / "repo"
        resource = repo / LARGE_BINARY_RESOURCE
        resource.parent.mkdir(mode=0o700, parents=True)
        resource.write_bytes(large_valid_png(b"phoenix-preview-base"))
        subprocess.run(["git", "-C", str(repo), "init", "-q"], check=True)
        subprocess.run(["git", "-C", str(repo), "config", "user.email", "test@example.invalid"], check=True)
        subprocess.run(["git", "-C", str(repo), "config", "user.name", "Large Binary Fixture"], check=True)
        subprocess.run(["git", "-C", str(repo), "add", LARGE_BINARY_RESOURCE], check=True)
        subprocess.run(["git", "-C", str(repo), "commit", "-qm", "base"], check=True)
        resource.write_bytes(large_valid_png(b"phoenix-preview-candidate"))
        diff = subprocess.check_output(["git", "-C", str(repo), "diff", "--binary", "--full-index", "HEAD", "--", LARGE_BINARY_RESOURCE])
    write_private(path, diff)


def make_binary_add_delete_patch(path, changed_path, operation):
    """Create a canonical binary resource addition or deletion from a temporary git repo."""
    payload = valid_png(b"base" if operation == "delete" else b"normal")
    with tempfile.TemporaryDirectory(prefix="binary-add-delete-source-") as temp_name:
        repo = Path(temp_name) / "repo"
        resource = repo / changed_path
        resource.parent.mkdir(mode=0o700, parents=True)
        subprocess.run(["git", "-C", str(repo), "init", "-q"], check=True)
        subprocess.run(["git", "-C", str(repo), "config", "user.email", "test@example.invalid"], check=True)
        subprocess.run(["git", "-C", str(repo), "config", "user.name", "Binary Fixture"], check=True)
        if operation == "delete":
            resource.write_bytes(payload)
            subprocess.run(["git", "-C", str(repo), "add", changed_path], check=True)
        elif operation == "add":
            subprocess.run(["git", "-C", str(repo), "commit", "--allow-empty", "-qm", "base"], check=True)
            resource.write_bytes(payload)
            subprocess.run(["git", "-C", str(repo), "add", changed_path], check=True)
        else:
            raise ValueError(operation)
        if operation == "delete":
            subprocess.run(["git", "-C", str(repo), "commit", "-qm", "base"], check=True)
            resource.unlink()
            subprocess.run(["git", "-C", str(repo), "add", "-u", changed_path], check=True)
        diff = subprocess.check_output(["git", "-C", str(repo), "diff", "--cached", "--binary", "--full-index"])
    write_private(path, diff)


def make_itxt_binary_patch(path, repo, operation, name, keyword, metadata, compression_flag=0, compression_method=0):
    resource = "shared/src/commonMain/composeResources/values/" + name
    target = repo / resource
    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    payload = valid_png(
        metadata.encode("utf-8"),
        keyword=keyword.encode("utf-8"),
        compression_flag=compression_flag,
        compression_method=compression_method,
    )
    if operation == "add":
        if target.exists():
            raise AssertionError(resource)
        target.write_bytes(payload)
        subprocess.run(["git", "-C", str(repo), "add", resource], check=True)
    elif operation == "delete":
        target.write_bytes(payload)
        subprocess.run(["git", "-C", str(repo), "add", resource], check=True)
        subprocess.run(["git", "-C", str(repo), "commit", "-qm", "iTXt PNG baseline"], check=True)
        target.unlink()
        subprocess.run(["git", "-C", str(repo), "add", "-u", resource], check=True)
    else:
        raise ValueError(operation)
    diff = subprocess.check_output(["git", "-C", str(repo), "diff", "--cached", "--binary", "--full-index"])
    if operation == "add":
        target.unlink()
        subprocess.run(["git", "-C", str(repo), "reset", "--quiet", "--", resource], check=True)
    else:
        subprocess.run(["git", "-C", str(repo), "restore", "--staged", "--worktree", "--", resource], check=True)
    write_private(path, diff)


def make_deleted_binary_credential_patch(path, repo):
    resource = "shared/src/commonMain/composeResources/values/deleted-secret.png"
    target = repo / resource
    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    target.write_bytes(valid_png(b"API_TOKEN=" + b"a" * 24))
    subprocess.run(["git", "-C", str(repo), "add", resource], check=True)
    subprocess.run(["git", "-C", str(repo), "commit", "-qm", "credential PNG baseline"], check=True)
    target.unlink()
    diff = subprocess.check_output(["git", "-C", str(repo), "diff", "--binary", "--full-index"])
    subprocess.run(["git", "-C", str(repo), "restore", "--", resource], check=True)
    write_private(path, diff)


def mutate_binary_sections(data, mutation):
    lines = data.splitlines(keepends=True)
    section_indices = [index for index, line in enumerate(lines) if line.startswith((b"literal ", b"delta "))]
    if len(section_indices) != 2:
        raise AssertionError(f"expected canonical add/delete sections, got {section_indices}")
    second = section_indices[1]
    if mutation == "missing":
        mutated = lines[:second] + lines[second + 2:]
    elif mutation == "extra":
        mutated = lines + lines[second:second + 2]
    else:
        raise ValueError(mutation)
    return b"".join(mutated)


def make_unscannable_binary_patch(path):
    resource = "shared/src/commonMain/composeResources/values/audio.mp3"
    with tempfile.TemporaryDirectory(prefix="unscannable-binary-source-") as temp_name:
        repo = Path(temp_name) / "repo"
        target = repo / resource
        target.parent.mkdir(mode=0o700, parents=True)
        target.write_bytes(b"ID3\x04\x00\x00\x00\x00\x00\x00")
        subprocess.run(["git", "-C", str(repo), "init", "-q"], check=True)
        subprocess.run(["git", "-C", str(repo), "config", "user.email", "test@example.invalid"], check=True)
        subprocess.run(["git", "-C", str(repo), "config", "user.name", "Unscannable Binary Fixture"], check=True)
        subprocess.run(["git", "-C", str(repo), "add", resource], check=True)
        subprocess.run(["git", "-C", str(repo), "commit", "-qm", "base"], check=True)
        target.write_bytes(b"ID3\x04\x00\x00\x00\x00\x00\x00normal-audio-payload")
        diff = subprocess.check_output(["git", "-C", str(repo), "diff", "--binary", "--full-index", "HEAD", "--", resource])
    write_private(path, diff)


def make_rename_copy_patch(path, operation):
    old = "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Candidate.kt"
    new = "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/" + ("Renamed.kt" if operation == "rename" else "Copied.kt")
    with tempfile.TemporaryDirectory(prefix="rename-copy-source-") as temp_name:
        repo = Path(temp_name) / "repo"
        source = repo / old
        source.parent.mkdir(mode=0o700, parents=True)
        source.write_text("candidate\n", encoding="utf-8")
        subprocess.run(["git", "-C", str(repo), "init", "-q"], check=True)
        subprocess.run(["git", "-C", str(repo), "config", "user.email", "test@example.invalid"], check=True)
        subprocess.run(["git", "-C", str(repo), "config", "user.name", "Rename Copy Fixture"], check=True)
        subprocess.run(["git", "-C", str(repo), "add", old], check=True)
        subprocess.run(["git", "-C", str(repo), "commit", "-qm", "base"], check=True)
        if operation == "rename":
            subprocess.run(["git", "-C", str(repo), "mv", old, new], check=True)
            diff = subprocess.check_output(["git", "-C", str(repo), "diff", "--find-renames", "--full-index", "HEAD", "--"])
        elif operation == "copy":
            subprocess.run(["cp", str(source), str(repo / new)], check=True)
            subprocess.run(["git", "-C", str(repo), "add", new], check=True)
            diff = subprocess.check_output(["git", "-C", str(repo), "diff", "--cached", "--find-copies=100%", "--find-copies-harder", "--full-index"])
        else:
            raise ValueError(operation)
    write_private(path, diff)


def make_trailing_whitespace_patch(path):
    write_private(
        path,
        "diff --git a/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Candidate.kt "
        "b/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Candidate.kt\n"
        "--- a/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Candidate.kt\n"
        "+++ b/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Candidate.kt\n"
        "@@ -1 +1 @@\n"
        "-ok\n"
        "+candidate   \n",
    )


def make_add_delete_patch(path, changed_path, operation):
    if operation == "add":
        old_header = "/dev/null"
        new_header = f"b/{changed_path}"
        mode = "new file mode 100644\n"
        hunk = "@@ -0,0 +1 @@\n+<resources><string name=\"candidate\">SAFE</string></resources>\n" if changed_path.endswith(".xml") else "@@ -0,0 +1 @@\n+candidate\n"
    elif operation == "delete":
        old_header = f"a/{changed_path}"
        new_header = "/dev/null"
        mode = "deleted file mode 100644\n"
        hunk = "@@ -1 +0,0 @@\n-candidate\n"
    else:
        raise ValueError(operation)
    write_private(
        path,
        f"diff --git a/{changed_path} b/{changed_path}\n"
        + mode
        + f"--- {old_header}\n"
        + f"+++ {new_header}\n"
        + hunk,
    )


def make_path_patch(path, changed_path):
    write_private(
        path,
        f"diff --git a/{changed_path} b/{changed_path}\n"
        f"--- a/{changed_path}\n"
        f"+++ b/{changed_path}\n"
        "@@ -0,0 +1 @@\n"
        "+candidate\n",
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
    compile_log = root / "compile-environment.jsonl"

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
        "def png(with_credential=False):\n"
        "    import struct, zlib\n"
        "    def chunk(kind, payload):\n"
        "        return len(payload).to_bytes(4, 'big') + kind + payload + zlib.crc32(kind + payload).to_bytes(4, 'big')\n"
        "    ihdr = struct.pack('>IIBBBBB', 2, 2, 8, 6, 0, 0, 0)\n"
        "    pixels = b'\\x00' + b'\\x00' * 8 + b'\\x00' + b'\\x00' * 8\n"
        "    metadata = ('API_TOKEN=' + 'a' * 24) if with_credential else ('{\\\"title\\\":\\\"TOKEN=' + 'a' * 24 + '\\\"}' if mode == 'png-structured-credential' else '<x:xmpmeta xmlns:x=\\\"adobe:ns:meta/\\\" xmlns:rdf=\\\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\\\"><rdf:RDF/></x:xmpmeta>')\n"
        "    itxt = b'XML:com.adobe.xmp\\x00\\x00\\x00\\x00\\x00' + metadata.encode('utf-8')\n"
        "    return b'\\x89PNG\\r\\n\\x1a\\n' + chunk(b'IHDR', ihdr) + chunk(b'iTXt', itxt) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')\n"
        "def make_diff_png():\n"
        "    import struct, zlib\n"
        "    def chunk(kind, payload):\n"
        "        return len(payload).to_bytes(4, 'big') + kind + payload + zlib.crc32(kind + payload).to_bytes(4, 'big')\n"
        "    ihdr = struct.pack('>IIBBBBB', 2, 2, 8, 6, 0, 0, 0)\n"
        "    pixels = b'\\x00' + b'\\xff\\x00\\x00\\xff' * 2 + b'\\x00' + b'\\xff\\x00\\x00\\xff' * 2\n"
        "    return b'\\x89PNG\\r\\n\\x1a\\n' + chunk(b'IHDR', ihdr) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')\n"
        "if len(sys.argv) != 5 or sys.argv[1] != 'render' or sys.argv[3] != 'just-lift-connected':\n"
        "    raise SystemExit(2)\n"
        "artifact, patch = Path(sys.argv[2]), Path(sys.argv[4])\n"
        "if os.environ.get('PHOENIX_PROPOSAL_TRUSTED_INPUT') != '1': raise SystemExit(3)\n"
        "patch_text = patch.read_bytes().decode('utf-8', 'replace')\n"
        "def marker(prefix):\n"
        "    for line in patch_text.splitlines():\n"
        "        candidate = line[1:] if line.startswith(('+', '-', ' ')) else line\n"
        "        if candidate.startswith(prefix): return candidate[len(prefix):]\n"
        "    return ''\n"
        "mode = os.environ.get('PREVIEW_TEST_RENDERER_MODE', '') or marker('TEST_MODE:') or 'ok'\n"
        "if mode == 'replace-patch':\n"
        "    original, replacement, observed = marker('REPLACE_PATCH_HEX:').split('|', 2)\n"
        "    original, replacement, observed = [bytes.fromhex(value).decode('utf-8') for value in (original, replacement, observed)]\n"
        "    os.replace(replacement, original)\n"
        "    Path(observed).write_bytes(patch.read_bytes())\n"
        "if mode == 'replace-result':\n"
        "    result_root, original, outside = marker('REPLACE_RESULT_HEX:').split('|', 2)\n"
        "    result_root, original, outside = [bytes.fromhex(value).decode('utf-8') for value in (result_root, original, outside)]\n"
        "    os.rename(result_root, original)\n"
        "    os.symlink(outside, result_root)\n"
        "if mode == 'mutate-host':\n"
        "    Path(Path(__file__).resolve().parents[2] / 'README.md').write_text('host mutated\\n', encoding='utf-8')\n"
        "if mode in ('stubborn-child', 'leader-exits-descendant', 'nested-child'):\n"
        "    sleep_child = bytes.fromhex(marker('SLEEP_CHILD_HEX:')).decode('utf-8')\n"
        "    grandchild_code = \"import signal, time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(60)\"\n"
        "    child_code = \"import os, signal, subprocess, sys, time; signal.signal(signal.SIGTERM, signal.SIG_IGN); open(%r, 'w').write(str(os.getpid()) + '\\\\n');\" % sleep_child\n"
        "    child_code += \"subprocess.Popen([sys.executable, '-c', %r]) ;\" % grandchild_code if mode == 'nested-child' else ''\n"
        "    child_code += \"time.sleep(60)\"\n"
        "    child_env = dict(os.environ, PYTHONPATH='')\n"
        "    subprocess.Popen([sys.executable, '-c', child_code], env=child_env)\n"
        "    if mode == 'leader-exits-descendant': time.sleep(0.1); raise SystemExit(0)\n"
        "    signal.signal(signal.SIGTERM, lambda _signum, _frame: os._exit(0))\n"
        "    time.sleep(60)\n"
        "if mode == 'sleep':\n"
        "    sleep_child = bytes.fromhex(marker('SLEEP_CHILD_HEX:')).decode('utf-8')\n"
        "    Path(sleep_child).write_text(str(os.getpid()) + '\\n', encoding='ascii')\n"
        "    time.sleep(60)\n"
        "if b'FAIL_RENDERER' in patch.read_bytes(): raise SystemExit(17)\n"
        "artifact.mkdir(mode=0o700, parents=True, exist_ok=True)\n"
        "base = subprocess.check_output(['git', '-C', str(Path(__file__).resolve().parents[2]), 'rev-parse', 'HEAD'], text=True).strip()\n"
        "patch_bytes = patch.read_bytes()\n"
        "patch_sha = hashlib.sha256(patch_bytes).hexdigest()\n"
        "def patch_facts(raw):\n"
        "    paths = sorted({parts[index][2:] for line in raw.decode('utf-8').splitlines() if line.startswith('diff --git ') for parts in [line.split()] if len(parts) == 4 for index in (2, 3)})\n"
        "    kinds = sorted({'kotlin' if path.startswith('shared/src/commonMain/kotlin/') else 'swift' if path.endswith('.swift') else 'resource' for path in paths})\n"
        "    return paths, kinds, b'GIT binary patch' in raw\n"
        "def applied_facts(repo, base_sha, patch_path):\n"
        "    import shutil, tempfile\n"
        "    holder = Path(tempfile.mkdtemp(prefix='fake-apply-'))\n"
        "    tree = holder / 'tree'\n"
        "    try:\n"
        "        subprocess.run(['git', '-C', str(repo), 'worktree', 'add', '--detach', str(tree), base_sha], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)\n"
        "        subprocess.run(['git', '-C', str(tree), 'apply', '--check', '--binary', '--whitespace=nowarn', str(patch_path)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)\n"
        "        subprocess.run(['git', '-C', str(tree), 'apply', '--binary', '--whitespace=nowarn', str(patch_path)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)\n"
        "        diff = subprocess.check_output(['git', '-C', str(tree), 'diff', '--binary', '--full-index', 'HEAD', '--'])\n"
        "        return hashlib.sha256(diff).hexdigest()\n"
        "    finally:\n"
        "        subprocess.run(['git', '-C', str(repo), 'worktree', 'remove', '--force', str(tree)], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)\n"
        "        shutil.rmtree(holder, ignore_errors=True)\n"
        "changed_files, candidate_kinds, binary_patch = patch_facts(patch_bytes)\n"
        "applied_diff_sha = applied_facts(Path(__file__).resolve().parents[2], base, patch)\n"
        "fixture_sha = 'e180679548a2d96dbc59c51449edb3b99c19d3e3be82eca98c0707a21a64e78e'\n"
        "simulator = {'udid': '11111111-2222-3333-4444-555555555555', 'name': 'iPhone 16', 'runtime': 'iOS-18-0', 'state': 'Booted'}\n"
        "command_specs = [('xcodebuild.version', 'toolchain.log'), ('simulator.boot', 'boot.log'), ('simulator.bootstatus', 'bootstatus.log'), ('simulator.terminate', 'terminate.log'), ('simulator.uninstall', 'uninstall.log'), ('build', 'build.log'), ('run-tests', 'test.log'), ('simulator.app-state', 'app-state.log'), ('simulator.logs', 'simulator.log'), ('simulator.screenshot', 'screenshot.log')]\n"
        "commands = [{'name': name, 'exitCode': 0, 'output': output, **({'resultBundle': {'basename': 'test.xcresult', 'status': 'private-not-retained'}} if name == 'run-tests' else {})} for name, output in command_specs]\n"
        "markers = ['xctest.passed', 'phantom.connected', 'simulator.screenshot']\n"
        "capture_png = png(mode == 'png-text-credential')\n"
        "capture_sha = hashlib.sha256(capture_png).hexdigest()\n"
        "captures = [{'slug': 'simulator-after', 'path': 'after.png', 'sha256': capture_sha, 'dimensions': {'width': 2, 'height': 2}, 'phase': 'after', 'pair': 'simulator-after', 'checkpoint': 'phantom-connected', 'fixtureId': 'just-lift-connected', 'fixtureSha256': fixture_sha, 'simulator': simulator}, {'slug': 'xctest-after', 'path': 'xctest-attachment.png', 'sha256': capture_sha, 'dimensions': {'width': 2, 'height': 2}, 'phase': 'after', 'pair': 'xctest-after', 'checkpoint': 'phantom-connected', 'fixtureId': 'just-lift-connected', 'fixtureSha256': fixture_sha, 'simulator': simulator}]\n"
        "run = {'schemaVersion': 1, 'runId': 'run-fake-1', 'provenance': {'baseSha': base, 'fixture': {'id': 'just-lift-connected', 'sha256': fixture_sha}, 'xcode': 'Xcode 16.0', 'sdk': '18.0', 'simulator': simulator, 'bundleId': 'com.devil.phoenixproject.projectphoenix'}, 'commands': commands, 'semanticMarkers': {'required': markers, 'observed': markers}, 'captures': captures, 'textualArtifacts': [{'path': name} for name in ('toolchain.log', 'build.log', 'test.log', 'app-state.log', 'simulator.log', 'screenshot.log', '.commands.jsonl')]}\n"
        "run_bytes = (json.dumps(run, sort_keys=True, indent=2) + '\\n').encode()\n"
        "run_sha = hashlib.sha256(run_bytes).hexdigest()\n"
        "diff_png = make_diff_png() if mode not in ('fake-capture-as-diff', 'forged-diff-image') else capture_png\n"
        "diff_sha = hashlib.sha256(diff_png).hexdigest()\n"
        "diff = {'passed': True, 'thresholdPassed': True, 'dimensions': {'width': 2, 'height': 2}, 'width': 2, 'height': 2, 'changedPixels': 0, 'changedPixelRatio': 0.0, 'changedRatio': 0.0, 'meanChannelDelta': 0.0, 'maxChannelDelta': 0, 'maskTopPixels': 0, 'threshold': 0.0, 'inputs': {'before': 'xctest-attachment.png', 'after': 'xctest-attachment.png'}}\n"
        "diff_bytes = (json.dumps(diff, sort_keys=True) + '\\n').encode()\n"
        "diff_sha_json = hashlib.sha256(diff_bytes).hexdigest()\n"
        "identity = {'baseSha': base, 'fixtureId': 'just-lift-connected', 'fixtureSha256': fixture_sha, 'bundleId': 'com.devil.phoenixproject.projectphoenix', 'simulator': simulator, 'commands': [name for name, _ in command_specs], 'markers': sorted(markers)}\n"
        "compact_capture = {'path': 'after.png', 'sha256': capture_sha, 'dimensions': {'width': 2, 'height': 2}}\n"
        "comparison = {'before': compact_capture, 'after': compact_capture, 'diffJson': {'path': 'comparison/diff.json', 'sha256': diff_sha_json}, 'diffImage': {'path': 'comparison/diff.png', 'sha256': diff_sha, 'dimensions': {'width': 2, 'height': 2}}, 'summary': diff}\n"
        "focused_checks = [{'name': 'git.diff.check', 'passed': True}] + ([{'name': 'shared.compileKotlinIosSimulatorArm64', 'passed': True}] if {'kotlin', 'resource'} & set(candidate_kinds) else [])\n"
        "manifest = {'schemaVersion': 1, 'status': 'passed', 'trustedInput': True, 'fixture': 'just-lift-connected', 'baseSha': base, 'patch': {'path': 'proposal.patch', 'sha256': patch_sha, 'size': len(patch_bytes), 'binary': binary_patch, 'format': 'exact-input'}, 'candidateKinds': candidate_kinds, 'allowedChangedFiles': changed_files, 'actualChangedFiles': changed_files, 'worktree': {'baseSha': base, 'headSha': base, 'detached': True, 'uncommitted': True, 'statusEntryCount': len(changed_files), 'appliedDiffSha256': applied_diff_sha}, 'focusedChecks': focused_checks, 'before': {'artifact': 'before', 'manifestSha256': run_sha, 'identity': identity}, 'after': {'artifact': 'after', 'manifestSha256': run_sha, 'identity': identity}, 'comparison': comparison, 'evidence': {'proposalMarkdown': 'proposal.md', 'summaryJson': 'evidence-summary.json'}}\n"
        "summary = {'schemaVersion': 1, 'status': 'passed', 'trustedInput': True, 'fixture': 'just-lift-connected', 'baseSha': base, 'patchSha256': patch_sha, 'changedFiles': changed_files, 'beforeAfterIdentity': identity, 'comparison': comparison, 'artifacts': ['before', 'after', 'proposal.patch', 'proposal-manifest.json', 'proposal.md', 'comparison/diff.json', 'comparison/diff.png']}\n"
        "proposal = '# Phantom proposal evidence\\n\\nStatus: **passed**\\n\\nThis proposal was rendered from the real Phoenix app in a disposable detached worktree using trusted candidate input.\\n\\n- Fixture: `just-lift-connected`\\n- Verified base SHA: `' + base + '`\\n- Proposal patch SHA-256: `' + patch_sha + '`\\n\\n## Allowed changed files\\n\\n' + ''.join('- `' + path + '`\\n' for path in changed_files) + '\\n## Verification\\n\\n- Baseline canonical harness case: verified\\n- Candidate canonical harness case: verified\\n- Kotlin/resource compile gate when required: verified\\n- Bound comparison metadata: verified\\n- Temporary worktree: cleaned after rendering\\n'\n"
        "if mode in ('sleep-verify-before', 'sleep-verify-after'):\n"
        "    private(artifact / ('.sleep-verify-' + ('before' if mode.endswith('before') else 'after')), b'1\\n', binary=True)\n"
        "for name in ('before/run.json', 'after/run.json'):\n"
        "    private(artifact / name, run_bytes, binary=True)\n"
        "private(artifact / 'proposal.patch', patch_bytes, binary=True)\n"
        "private(artifact / '.phantom-proposal', 'phantom-proposal-artifact-v1\\n')\n"
        "for phase in ('before', 'after'):\n"
        "    private(artifact / (phase + '/.phantom-harness'), 'phantom-harness-artifact-v1\\n')\n"
        "    private(artifact / (phase + '/.commands.jsonl'), ''.join(json.dumps(item, sort_keys=True) + '\\n' for item in commands))\n"
        "    for _, output in command_specs:\n"
        "        private(artifact / (phase + '/' + output), 'Xcode path: /Applications/Xcode.app\\nDerivedData: /Users/preview/Library/Developer/Xcode/DerivedData\\nTemporary directory: /tmp/phantom-preview\\nCoreSimulator: /Library/Developer/CoreSimulator\\n')\n"
        "    private(artifact / (phase + '/after.png'), capture_png, binary=True)\n"
        "    private(artifact / (phase + '/xctest-attachment.png'), capture_png, binary=True)\n"
        "private(artifact / 'proposal.md', proposal)\n"
        "private(artifact / 'evidence-summary.json', json.dumps(summary, sort_keys=True, indent=2) + '\\n')\n"
        "private(artifact / 'proposal-manifest.json', json.dumps(manifest, sort_keys=True, indent=2) + '\\n')\n"
        "private(artifact / 'comparison/diff.json', diff_bytes, binary=True)\n"
        "private(artifact / 'comparison/diff.png', diff_png, binary=True)\n"
        "if mode == 'minimal-evidence': private(artifact / 'evidence-summary.json', json.dumps({'schemaVersion': 1, 'status': 'passed'}) + '\\n')\n"
        "if mode == 'unknown-run': run['unknown'] = True; private(artifact / 'before/run.json', json.dumps(run) + '\\n')\n"
        "if mode == 'bad-run-types': run['schemaVersion'] = True; private(artifact / 'after/run.json', json.dumps(run) + '\\n')\n"
        "if mode == 'unknown-diff': diff['unknown'] = True; private(artifact / 'comparison/diff.json', json.dumps(diff) + '\\n')\n"
        "if mode == 'bad-diff-input': diff['inputs'] = {'before': 'run.json', 'after': 'run.json'}; private(artifact / 'comparison/diff.json', json.dumps(diff) + '\\n')\n"
        "if mode.startswith('forged-diff-'):\n"
        "    if mode == 'forged-diff-ratio': diff['changedPixelRatio'] = 0.25\n"
        "    if mode == 'forged-diff-changed-ratio': diff['changedRatio'] = 0.25\n"
        "    if mode == 'forged-diff-pixels': diff['changedPixels'] = 5\n"
        "    if mode == 'forged-diff-threshold': diff['maxChannelDelta'] = 1\n"
        "    if mode == 'forged-diff-pass': diff['passed'] = False; diff['thresholdPassed'] = False\n"
        "    if mode == 'forged-diff-mask': diff['maskTopPixels'] = 1\n"
        "    private(artifact / 'comparison/diff.json', (json.dumps(diff, sort_keys=True) + '\\n').encode(), binary=True)\n"
        "if mode == 'malformed-png': private(artifact / 'comparison/diff.png', b'not-a-png', binary=True)\n"
        "if mode == 'bad-markdown': private(artifact / 'proposal.md', '# Phantom proposal evidence\\nStatus: **passed**\\n')\n"
        "if mode == 'bad-markdown-ref': private(artifact / 'proposal.md', proposal + '\\n- `comparison/unknown.json`\\n')\n"
        "if mode == 'bad-markdown-fixture': private(artifact / 'proposal.md', proposal.replace('- Fixture: `just-lift-connected`', '- Fixture: `forged-fixture`'))\n"
        "if mode == 'bad-markdown-base': private(artifact / 'proposal.md', proposal.replace('- Verified base SHA: `' + base + '`', '- Verified base SHA: `' + ('0' * 40) + '`'))\n"
        "if mode == 'bad-markdown-patch': private(artifact / 'proposal.md', proposal.replace('- Proposal patch SHA-256: `' + patch_sha + '`', '- Proposal patch SHA-256: `' + ('0' * 64) + '`'))\n"
        "if mode == 'bad-markdown-check': private(artifact / 'proposal.md', proposal.replace('- Bound comparison metadata: verified', '- Bound comparison metadata: forged'))\n"
        "if mode.startswith('patch-mismatch-'):\n"
        "    mismatch = dict(manifest)\n"
        "    mismatch['patch'] = dict(manifest['patch'])\n"
        "    if mode == 'patch-mismatch-sha': mismatch['patch']['sha256'] = '0' * 64\n"
        "    if mode == 'patch-mismatch-size': mismatch['patch']['size'] += 1\n"
        "    if mode == 'patch-mismatch-path': mismatch['patch']['path'] = 'safe.patch'\n"
        "    if mode == 'patch-mismatch-format': mismatch['patch']['format'] = 'unified-diff'\n"
        "    private(artifact / 'proposal-manifest.json', json.dumps(mismatch) + '\\n')\n"
        "if mode == 'claim-fake-path':\n"
        "    mismatch = dict(manifest); mismatch['candidateKinds'] = ['kotlin']; mismatch['allowedChangedFiles'] = ['shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Other.kt']; mismatch['actualChangedFiles'] = mismatch['allowedChangedFiles']; private(artifact / 'proposal-manifest.json', json.dumps(mismatch) + '\\n')\n"
        "if mode == 'claim-fake-kind':\n"
        "    mismatch = dict(manifest); mismatch['candidateKinds'] = ['resource']; private(artifact / 'proposal-manifest.json', json.dumps(mismatch) + '\\n')\n"
        "if mode == 'claim-fake-checks':\n"
        "    mismatch = dict(manifest); mismatch['focusedChecks'] = [{'name': 'git.diff.check', 'passed': True}]; private(artifact / 'proposal-manifest.json', json.dumps(mismatch) + '\\n')\n"
        "if mode == 'claim-fake-head':\n"
        "    mismatch = dict(manifest); mismatch['worktree'] = dict(manifest['worktree']); mismatch['worktree']['headSha'] = '0' * 40; private(artifact / 'proposal-manifest.json', json.dumps(mismatch) + '\\n')\n"
        "if mode == 'claim-fake-status':\n"
        "    mismatch = dict(manifest); mismatch['worktree'] = dict(manifest['worktree']); mismatch['worktree']['statusEntryCount'] = 99; private(artifact / 'proposal-manifest.json', json.dumps(mismatch) + '\\n')\n"
        "if mode == 'claim-fake-applied-diff':\n"
        "    mismatch = dict(manifest); mismatch['worktree'] = dict(manifest['worktree']); mismatch['worktree']['appliedDiffSha256'] = patch_sha; private(artifact / 'proposal-manifest.json', json.dumps(mismatch) + '\\n')\n"
        "leak = os.environ.get('PREVIEW_TEST_LEAK_KIND', '') or marker('LEAK_KIND:')\n"
        "if leak == 'proposal.md': private(artifact / leak, 'absolute=/Users/host/private/candidate.patch\\nAPI_TOKEN=REDACTED_REDACTED\\n')\n"
        "elif leak == 'evidence-summary.json': private(artifact / leak, json.dumps({'schemaVersion': 1, 'status': 'passed', 'leak': '/private/host/API_TOKEN=REDACTED_REDACTED'}) + '\\n')\n"
        "elif leak == 'proposal-manifest.json': private(artifact / leak, json.dumps({'schemaVersion': 1, 'status': 'passed', 'fixture': 'just-lift-connected', 'baseSha': base, 'patch': {'path': '/Users/host/private/candidate.patch', 'sha256': '0' * 64, 'size': 1, 'binary': False, 'format': 'exact-input'}}) + '\\n')\n"
        "elif leak in ('before/run.json', 'after/run.json', 'comparison/diff.json'): private(artifact / leak, json.dumps({'schemaVersion': 1, 'leak': 'Bearer REDACTED_REDACTED /Users/host/private'}) + '\\n')\n"
        "elif leak == 'comparison/diff.png': private(artifact / leak, png() + b' /Users/host/private API_TOKEN=REDACTED_REDACTED', binary=True)\n"
        "if mode == 'unexpected-internal-root': private(artifact / 'internal-unexpected.log', 'unexpected internal output\\n')\n"
        "if mode == 'unexpected-internal-nested': private(artifact / 'before/internal-unexpected.log', 'unexpected internal output\\n')\n"
        "if mode == 'internal-benign-log':\n"
        "    benign_log = ('2026-07-19 17:47:40.087 Df VitruvianPhoenix[75993:9e0b96] [com.apple.UIKit:EventDeferring] [0x106699420] Begin local event deferring requested for token:0x106699420; environments: 1; reason: UIWindowScene: 0x10662e000: Begin event deferring in keyboardFocus for window: 0x10640c800\\n'\n"
        "        '2026-07-19 17:47:40.088 Df VitruvianPhoenix[75993:9e0ba8] [com.apple.BackBoard:EventDelivery] policyStatus:<BKSHIDEventDeliveryPolicyObserver: 0x1044a1140; token:0x1044a1140:sceneID%3Acom.example.project-default; status: ancestor> was:target\\n')\n"
        "    private(artifact / 'before/simulator.log', benign_log)\n"
        "    private(artifact / 'after/simulator.log', benign_log)\n"
        "if mode == 'benign-ui-label':\n"
        "    benign_label = 'Text(\\\"API token\\\")\\nUILabel(text: \\\"Client secret\\\")\\n'\n"
        "    private(artifact / 'before/simulator.log', benign_label)\n"
        "    private(artifact / 'after/simulator.log', benign_label)\n"
        "typed_credential_lengths = {'typed-credential-16': 16, 'typed-credential-17': 17, 'typed-credential-18': 18, 'typed-credential-19': 19, 'typed-credential-long': 64}\n"
        "if mode in typed_credential_lengths:\n"
        "    value = 'a' * typed_credential_lengths[mode]\n"
        "    typed_log = 'val apiToken: String = \\\"' + value + '\\\"\\n'\n"
        "    private(artifact / 'before/simulator.log', typed_log)\n"
        "    private(artifact / 'after/simulator.log', typed_log)\n"
        "internal_log_credentials = {\n"
        "    'internal-secret': 'API_TOKEN=' + 'a' * 24 + '\\n',\n"
        "    'internal-private-key': '-----BEGIN ' + 'PRIVATE KEY-----\\n' + 'MIIEvQIBADANBgkqhkiG9w0BAQEFAASC\\n' + '-----END PRIVATE KEY-----\\n',\n"
        "    'internal-bearer': 'Authorization: Bearer ' + 'a' * 24 + '\\n',\n"
        "    'internal-api-key': 'OPENAI_API_KEY=sk-proj-' + 'a' * 24 + '\\n',\n"
        "    'internal-aws-key': 'AWS_ACCESS_KEY_ID=AKIA' + 'IOSFODNN7EXAMPLE' + '\\n',\n"
        "    'internal-known-token': 'GITHUB_TOKEN=ghp_' + 'a' * 24 + '\\n',\n"
        "}\n"
        "if mode in internal_log_credentials: private(artifact / 'before/simulator.log', internal_log_credentials[mode])\n"
        "if mode == 'internal-absolute-path': private(artifact / 'after/simulator.log', '/Users/host/private/output\\n')\n"
        "if mode == 'internal-symlink': os.symlink('run.json', artifact / 'before/internal-link')\n"
        "if mode == 'missing-proposal-marker': (artifact / '.phantom-proposal').unlink()\n"
        "if mode == 'bad-proposal-marker': private(artifact / '.phantom-proposal', 'altered-proposal-marker\\n')\n"
        "if mode == 'symlink-proposal-marker': (artifact / '.phantom-proposal').unlink(); os.symlink('before/run.json', artifact / '.phantom-proposal')\n"
        "if mode == 'unknown-public': private(artifact / 'unknown-public.txt', 'must not publish\\n')\n"
        "if mode == 'publish-internal-log': private(artifact / 'simulator.log', 'must remain private\\n')\n"
        "if mode == 'missing-proposal-patch': (artifact / 'proposal.patch').unlink()\n"
        "if mode == 'bad-proposal-patch': private(artifact / 'proposal.patch', b'not-the-request-patch\\n', binary=True)\n"
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
        "if sys.argv[1] == 'verify':\n"
        "    if len(sys.argv) != 3 or not sys.argv[2].endswith(('/before', '/after')): raise SystemExit(4)\n"
        "    with open(LOG, 'a', encoding='utf-8') as stream:\n"
        "        stream.write(json.dumps({'args': sys.argv[1:], 'env': dict(sorted(os.environ.items()))}) + '\\n')\n"
        "    phase = Path(sys.argv[2]).name\n"
        "    sleep_marker = Path(sys.argv[2]).parent / ('.sleep-verify-' + phase)\n"
        "    if sleep_marker.exists():\n"
        "        pid_file = Path(str(sleep_marker) + '.pid')\n"
        "        pid_file.write_text(str(os.getpid()) + '\\n', encoding='ascii')\n"
        "        time.sleep(60)\n"
        "    if os.environ.get('PREVIEW_TEST_VERIFY_FAIL') == '1' or (Path(sys.argv[2]).parent / '.verify-fail').exists(): raise SystemExit(17)\n"
        "    print('{\\\"passed\\\":true}')\n"
        "    raise SystemExit(0)\n"
        "if len(sys.argv) != 5 or sys.argv[1] != 'compare': raise SystemExit(2)\n"
        "output = Path(sys.argv[4])\n"
        "output.mkdir(mode=0o700, parents=True, exist_ok=True)\n"
        "def chunk(kind, payload):\n"
        "    import zlib\n"
        "    return len(payload).to_bytes(4, 'big') + kind + payload + zlib.crc32(kind + payload).to_bytes(4, 'big')\n"
        "ihdr = (2).to_bytes(4, 'big') + (2).to_bytes(4, 'big') + bytes([8, 6, 0, 0, 0])\n"
        "pixels = b'\\x00' + b'\\xff\\x00\\x00\\xff' * 2 + b'\\x00' + b'\\xff\\x00\\x00\\xff' * 2\n"
        "import zlib\n"
        "diff_png = b'\\x89PNG\\r\\n\\x1a\\n' + chunk(b'IHDR', ihdr) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')\n"
        "(output / 'diff.png').write_bytes(diff_png)\n"
        "(output / 'diff.png').chmod(0o600)\n"
        "diff = {'passed': True, 'thresholdPassed': True, 'dimensions': {'width': 2, 'height': 2}, 'width': 2, 'height': 2, 'changedPixels': 0, 'changedPixelRatio': 0.0, 'changedRatio': 0.0, 'meanChannelDelta': 0.0, 'maxChannelDelta': 0, 'maskTopPixels': 0, 'threshold': 0.0, 'inputs': {'before': 'xctest-attachment.png', 'after': 'xctest-attachment.png'}}\n"
        "(output / 'diff.json').write_text(json.dumps(diff, sort_keys=True) + '\\n', encoding='utf-8')\n"
        "(output / 'diff.json').chmod(0o600)\n",
        encoding="utf-8",
    )
    chmod(harness, 0o700)

    gradlew = repo / "gradlew"
    gradlew.write_text(
        "#!/usr/bin/env bash\n"
        "set -euo pipefail\n"
        "case \" $* \" in *\" :shared:compileKotlinIosSimulatorArm64 \"*);; *) exit 2;; esac\n"
        "case \" $* \" in *\" -Pskip.supabase.check=true \"*);; *) exit 2;; esac\n"
        f"/usr/bin/python3 - {str(compile_log)!r} <<'PY'\n"
        "import json, os, sys\n"
        "from pathlib import Path\n"
        "Path(sys.argv[1]).open('a', encoding='utf-8').write(json.dumps({'cwd': os.getcwd(), 'env': dict(sorted(os.environ.items()))}) + '\\n')\n"
        "PY\n"
        "if grep -F COMPILE_FAIL shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Candidate.kt >/dev/null 2>&1; then exit 19; fi\n"
        "exit 0\n",
        encoding="utf-8",
    )
    chmod(gradlew, 0o700)

    subprocess.run(["git", "-C", str(repo), "init", "-q"], check=True)
    subprocess.run(["git", "-C", str(repo), "config", "user.email", "test@example.invalid"], check=True)
    subprocess.run(["git", "-C", str(repo), "config", "user.name", "Preview Test"], check=True)
    write_private(repo / "README.md", "fake source\n")
    write_private(repo / "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Candidate.kt", "ok\n")
    write_private(repo / "shared/src/commonMain/composeResources/values/strings.xml", "<string name=\"autostart_ready\">AUTO-START READY</string>\n")
    write_private(repo / "shared/src/commonMain/composeResources/values/config.json", '{"title":"safe"}\n')
    write_private(repo / "shared/src/commonMain/composeResources/values/deleted.xml", "candidate\n")
    write_private(repo / "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Deleted.kt", "candidate\n")
    write_private(repo / "shared/src/commonMain/composeResources/values/icon.png", valid_png(b"base"))
    write_private(repo / LARGE_BINARY_RESOURCE, large_valid_png(b"phoenix-preview-base"))
    subprocess.run(["git", "-C", str(repo), "add", "README.md", "gradlew", ".github", "shared"], check=True)
    subprocess.run(["git", "-C", str(repo), "commit", "-qm", "fixture"], check=True)
    return repo, renderer_log, verifier_log, compile_log


def run_wrapper(repo, request_path, result_root, extra_env=None, timeout_seconds=None):
    env = os.environ.copy()
    env.update({
        "PHOENIX_HARNESS_UDID": "11111111-2222-3333-4444-555555555555",
        "PREVIEW_TEST_SECRET_TOKEN": "must-not-cross-env-i",
    })
    if extra_env:
        env.update(extra_env)
    command = [str(repo / ".github/scripts/phantom-kanban-preview.sh"), str(request_path), str(result_root)]
    if timeout_seconds is None:
        return subprocess.run(command, cwd=repo, env=env, text=True, capture_output=True)
    process = subprocess.Popen(command, cwd=repo, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
    try:
        stdout, stderr = process.communicate(timeout=timeout_seconds)
    except subprocess.TimeoutExpired:
        process.send_signal(signal.SIGTERM)
        try:
            stdout, stderr = process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            stdout, stderr = process.communicate(timeout=5)
        fail(f"wrapper timed out after {timeout_seconds}s: stdout={stdout!r}, stderr={stderr!r}")
    return subprocess.CompletedProcess(command, process.returncode, stdout, stderr)


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


def run_git_phase_signal(repo, request_path, result_root, hook, signal_name):
    env = {**os.environ, "PHOENIX_HARNESS_UDID": "11111111-2222-3333-4444-555555555555", "PHOENIX_PREVIEW_TEST_HOOK": hook}
    process = subprocess.Popen(
        [str(repo / ".github/scripts/phantom-kanban-preview.sh"), str(request_path), str(result_root)],
        cwd=repo,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    marker = None
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        candidates = sorted(Path("/tmp").glob("phantom-kanban-preview-*/.test-hook-" + hook))
        if candidates:
            marker = candidates[-1]
            break
        time.sleep(0.02)
    if marker is None:
        process.kill()
        process.communicate(timeout=5)
        fail(f"{hook} git hook did not start")
    pid_file = Path(str(marker) + ".pid")
    child_pid = None
    while time.monotonic() < deadline:
        if pid_file.exists():
            pid_text = pid_file.read_text(encoding="ascii").strip()
            if pid_text.isdigit():
                child_pid = int(pid_text)
                break
        time.sleep(0.02)
    if child_pid is None:
        process.kill()
        process.communicate(timeout=5)
        fail(f"{hook} git hook child did not become active")
    process.send_signal(getattr(signal, signal_name))
    stdout, stderr = process.communicate(timeout=10)
    completed = type("Completed", (), {"stdout": stdout, "stderr": stderr})()
    assert_no_traceback(completed, f"{hook} {signal_name}")
    if process.returncode == 0:
        fail(f"{hook} {signal_name} unexpectedly returned success")
    assert_result_only(result_root, "interrupted", "preview interrupted")
    try:
        os.kill(child_pid, 0)
    except ProcessLookupError:
        pass
    else:
        fail(f"{hook} {signal_name} left its active git/apply child alive")
    if marker.exists() or pid_file.exists():
        fail(f"{hook} {signal_name} left a private hook marker/orphan")


def main():
    if not wrapper_source.exists():
        # This is intentional RED evidence before the production wrapper exists.
        fail("phantom-kanban-preview.sh is missing")
    with tempfile.TemporaryDirectory(prefix="phantom-kanban-preview-test-") as temp_name:
        temp = Path(temp_name)
        repo, renderer_log, verifier_log, compile_log = make_fake_repo(temp)
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
        manifest = json.loads((valid_result / "proposal-manifest.json").read_text(encoding="utf-8"))
        summary = json.loads((valid_result / "evidence-summary.json").read_text(encoding="utf-8"))
        proposal_text = (valid_result / "proposal.md").read_text(encoding="utf-8")
        expected_proposal = "\n".join([
            "# Phantom proposal evidence", "", "Status: **passed**", "",
            "This proposal was rendered from the real Phoenix app in a disposable detached worktree using trusted candidate input.", "",
            f"- Fixture: `{manifest['fixture']}`",
            f"- Verified base SHA: `{manifest['baseSha']}`",
            f"- Proposal patch SHA-256: `{manifest['patch']['sha256']}`", "",
            "## Allowed changed files", "",
            *[f"- `{path}`" for path in manifest["actualChangedFiles"]], "",
            "## Verification", "", *PRODUCER_PUBLIC_VERIFICATION, "",
        ])
        if proposal_text != expected_proposal:
            fail("producer public proposal claims are not exact")
        if manifest["baseSha"] != payload["base_sha"] or manifest["patch"]["sha256"] != payload["patch_sha256"] or summary["baseSha"] != manifest["baseSha"] or summary["patchSha256"] != manifest["patch"]["sha256"]:
            fail("public claims are not bound to manifest and summary")
        run = json.loads((valid_result / "before/run.json").read_text(encoding="utf-8"))
        if run["provenance"]["fixture"]["id"] != "just-lift-connected" or run["provenance"]["fixture"]["sha256"] != PRODUCER_FIXTURE_SHA256:
            fail("producer fixture provenance drifted")
        if [capture["path"] for capture in run["captures"]] != ["after.png", "xctest-attachment.png"] or [capture["checkpoint"] for capture in run["captures"]] != ["phantom-connected", "phantom-connected"]:
            fail("producer capture topology drifted")
        if [item["name"] for item in run["commands"]] != [name for name, _ in PRODUCER_COMMAND_SPECS] or [item["path"] for item in run["textualArtifacts"]] != list(PRODUCER_TEXTUAL_ARTIFACTS):
            fail("producer command/log topology drifted")
        if tuple(run["semanticMarkers"]["required"]) != PRODUCER_MARKERS or tuple(run["semanticMarkers"]["observed"]) != PRODUCER_MARKERS:
            fail("producer semantic marker contract drifted")
        diff = json.loads((valid_result / "comparison/diff.json").read_text(encoding="utf-8"))
        total_pixels = diff["width"] * diff["height"]
        if total_pixels != 4 or diff["changedPixelRatio"] != diff["changedPixels"] / total_pixels or diff["changedRatio"] != diff["changedPixels"] / total_pixels:
            fail("producer diff arithmetic is not dimension-bound")
        for phase in ("before", "after"):
            if not (valid_result / phase / "run.json").is_file():
                fail(f"producer public run manifest missing {phase}/run.json")
        png = (valid_result / "comparison/diff.png").read_bytes()
        if not (png.startswith(b"\x89PNG\r\n\x1a\n") and b"IHDR" in png and b"IDAT" in png and b"IEND" in png):
            fail("producer diff is not a PNG-shaped binary artifact")
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

        xml_patch = temp / "private" / "xml-resource.patch"
        make_xml_patch(xml_patch)
        xml_request = temp / "xml-resource.json"
        write_json(xml_request, request("KANBAN-XML", xml_patch))
        xml_result = fresh_result(temp, "xml-resource-result")
        completed = run_wrapper(repo, xml_request, xml_result)
        if completed.returncode != 0:
            fail(f"valid XML resource patch was rejected: stdout={completed.stdout!r}, stderr={completed.stderr!r}")
        xml_payload = json.loads((xml_result / "preview-result.json").read_text(encoding="utf-8"))
        if xml_payload.get("status") != "passed":
            fail(f"valid XML resource patch did not produce a passed result: {xml_payload}")

        compile_fail_patch = temp / "compile-failure.patch"
        make_patch(compile_fail_patch, marker="COMPILE_FAIL")
        compile_fail_request = temp / "compile-failure.json"
        write_json(compile_fail_request, request("KANBAN-COMPILE-FAILURE", compile_fail_patch))
        compile_fail_result = fresh_result(temp, "compile-failure-result")
        assert_failure(run_wrapper(repo, compile_fail_request, compile_fail_result), compile_fail_result, "validate-request")

        png_structured_patch = temp / "png-structured-credential.patch"
        make_patch(png_structured_patch, marker="ok\nTEST_MODE:png-structured-credential")
        png_structured_request = temp / "png-structured-credential.json"
        write_json(png_structured_request, request("KANBAN-PNG-STRUCTURED-CREDENTIAL", png_structured_patch))
        png_structured_result = fresh_result(temp, "png-structured-credential-result")
        assert_failure(run_wrapper(repo, png_structured_request, png_structured_result, extra_env={"PREVIEW_TEST_RENDERER_MODE": "png-structured-credential"}), png_structured_result, "validate-artifacts")

        host_repo, _host_renderer_log, _host_verifier_log, _host_compile_log = make_fake_repo(temp / "host-mutation-fixture")
        host_patch = temp / "host-mutation.patch"
        make_patch(host_patch, marker="ok\nTEST_MODE:mutate-host")
        host_request = temp / "host-mutation.json"
        write_json(host_request, request("KANBAN-HOST-MUTATION", host_patch))
        host_result = fresh_result(temp, "host-mutation-result")
        assert_failure(run_wrapper(host_repo, host_request, host_result), host_result, "renderer")

        for mode in ("stubborn-child", "leader-exits-descendant", "nested-child"):
            bounded_root = temp / ("bounded-" + mode)
            bounded_repo, _bounded_renderer_log, _bounded_verifier_log, _bounded_compile_log = make_fake_repo(bounded_root)
            bounded_wrapper = bounded_repo / ".github/scripts/phantom-kanban-preview.sh"
            bounded_wrapper.write_text(bounded_wrapper.read_text(encoding="utf-8").replace("CHILD_TIMEOUT_SECONDS = 1800", "CHILD_TIMEOUT_SECONDS = 1"), encoding="utf-8")
            bounded_wrapper.chmod(0o700)
            subprocess.run(["git", "-C", str(bounded_repo), "add", ".github/scripts/phantom-kanban-preview.sh"], check=True)
            subprocess.run(["git", "-C", str(bounded_repo), "commit", "-qm", "bounded-test"], check=True)
            child_pid_file = temp / (mode + ".pid")
            bounded_patch = temp / (mode + ".patch")
            child_hex = str(child_pid_file).encode("utf-8").hex()
            make_patch(bounded_patch, marker="ok\nTEST_MODE:" + mode + "\nSLEEP_CHILD_HEX:" + child_hex)
            bounded_request = temp / (mode + ".json")
            write_json(bounded_request, request("KANBAN-BOUNDED-" + mode, bounded_patch))
            bounded_result = fresh_result(temp, mode + "-result")
            completed = run_wrapper(bounded_repo, bounded_request, bounded_result, timeout_seconds=10)
            if completed.returncode == 0:
                fail("bounded child unexpectedly succeeded: " + mode)
            bounded_payload = json.loads((bounded_result / "preview-result.json").read_text(encoding="utf-8"))
            if bounded_payload.get("stage") != "renderer":
                fail(f"bounded child failed at wrong stage: mode={mode}, payload={bounded_payload}")
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline and not child_pid_file.exists():
                time.sleep(0.02)
            if not child_pid_file.exists():
                fail("bounded child fixture did not start: " + mode)
            child_pid = int(child_pid_file.read_text(encoding="ascii").strip())
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                try:
                    os.kill(child_pid, 0)
                except ProcessLookupError:
                    break
                time.sleep(0.02)
            else:
                fail("bounded process group left a descendant: " + mode)

        closed_root = temp / "bounded-closed-output"
        closed_repo, _closed_renderer_log, _closed_verifier_log, _closed_compile_log = make_fake_repo(closed_root)
        closed_wrapper = closed_repo / ".github/scripts/phantom-kanban-preview.sh"
        closed_wrapper.write_text(closed_wrapper.read_text(encoding="utf-8").replace("TRACKED_TIMEOUT_SECONDS = 30", "TRACKED_TIMEOUT_SECONDS = 1"), encoding="utf-8")
        closed_wrapper.chmod(0o700)
        subprocess.run(["git", "-C", str(closed_repo), "add", ".github/scripts/phantom-kanban-preview.sh"], check=True)
        subprocess.run(["git", "-C", str(closed_repo), "commit", "-qm", "closed-output-timeout"], check=True)
        closed_child_pid = temp / "closed-output-child.pid"
        closed_request = temp / "closed-output.json"
        write_json(closed_request, request("KANBAN-CLOSED-OUTPUT", patch))
        closed_result = fresh_result(temp, "closed-output-result")
        closed_env = {**os.environ, "PHOENIX_HARNESS_UDID": "11111111-2222-3333-4444-555555555555", "PHOENIX_PREVIEW_TEST_HOOK": "closed-output", "PHOENIX_PREVIEW_TEST_CHILD_PID": str(closed_child_pid)}
        closed_process = subprocess.Popen([str(closed_wrapper), str(closed_request), str(closed_result)], cwd=closed_repo, env=closed_env, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            closed_stdout, closed_stderr = closed_process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            closed_process.kill()
            closed_process.communicate(timeout=5)
            fail("wrapper closed-output leader exceeded its tracked deadline")
        if closed_process.returncode == 0:
            fail("wrapper closed-output leader unexpectedly succeeded")
        assert_failure(type("Completed", (), {"stdout": closed_stdout, "stderr": closed_stderr, "returncode": closed_process.returncode})(), closed_result, "validate-request")
        deadline = time.monotonic() + 2
        while not closed_child_pid.exists() and time.monotonic() < deadline:
            time.sleep(0.02)
        if not closed_child_pid.exists():
            fail("wrapper closed-output descendant did not start")
        child_pid = int(closed_child_pid.read_text(encoding="ascii").strip())
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            try:
                os.kill(child_pid, 0)
            except ProcessLookupError:
                break
            time.sleep(0.05)
        else:
            fail("wrapper closed-output descendant survived cleanup")

        structured_json_patch = temp / "structured-json-credential.patch"
        make_structured_json_credential_patch(structured_json_patch)
        structured_json_request = temp / "structured-json-credential.json"
        write_json(structured_json_request, request("KANBAN-STRUCTURED-JSON-CREDENTIAL", structured_json_patch))
        structured_json_result = fresh_result(temp, "structured-json-credential-result")
        assert_failure(run_wrapper(repo, structured_json_request, structured_json_result), structured_json_result, "validate-request")

        structured_xml_patch = temp / "structured-xml-credential.patch"
        make_structured_xml_credential_patch(structured_xml_patch)
        structured_xml_request = temp / "structured-xml-credential.json"
        write_json(structured_xml_request, request("KANBAN-STRUCTURED-XML-CREDENTIAL", structured_xml_patch))
        structured_xml_result = fresh_result(temp, "structured-xml-credential-result")
        assert_failure(run_wrapper(repo, structured_xml_request, structured_xml_result), structured_xml_result, "validate-request")

        duplicate_json_patch = temp / "duplicate-json-resource.patch"
        make_duplicate_json_patch(duplicate_json_patch)
        duplicate_json_request = temp / "duplicate-json-resource.json"
        write_json(duplicate_json_request, request("KANBAN-DUPLICATE-JSON", duplicate_json_patch))
        duplicate_json_result = fresh_result(temp, "duplicate-json-resource-result")
        assert_failure(run_wrapper(repo, duplicate_json_request, duplicate_json_result), duplicate_json_result, "validate-request")

        nested_xml_patch = temp / "nested-xml-credential.patch"
        make_nested_xml_credential_patch(nested_xml_patch)
        nested_xml_request = temp / "nested-xml-credential.json"
        write_json(nested_xml_request, request("KANBAN-NESTED-XML", nested_xml_patch))
        nested_xml_result = fresh_result(temp, "nested-xml-credential-result")
        assert_failure(run_wrapper(repo, nested_xml_request, nested_xml_result), nested_xml_result, "validate-request")

        nul_swift_patch = temp / "nul-swift.patch"
        make_nul_swift_patch(nul_swift_patch)
        nul_swift_request = temp / "nul-swift.json"
        write_json(nul_swift_request, request("KANBAN-NUL-SWIFT", nul_swift_patch))
        nul_swift_result = fresh_result(temp, "nul-swift-result")
        assert_failure(run_wrapper(repo, nul_swift_request, nul_swift_result), nul_swift_result, "validate-request")

        generic_json_patch = temp / "generic-json-credential.patch"
        make_generic_json_credential_patch(generic_json_patch)
        generic_json_request = temp / "generic-json-credential.json"
        write_json(generic_json_request, request("KANBAN-GENERIC-JSON-CREDENTIAL", generic_json_patch))
        generic_json_result = fresh_result(temp, "generic-json-credential-result")
        assert_failure(run_wrapper(repo, generic_json_request, generic_json_result), generic_json_result, "validate-request")

        generic_xml_patch = temp / "generic-xml-credential.patch"
        make_generic_xml_credential_patch(generic_xml_patch)
        generic_xml_request = temp / "generic-xml-credential.json"
        write_json(generic_xml_request, request("KANBAN-GENERIC-XML-CREDENTIAL", generic_xml_patch))
        generic_xml_result = fresh_result(temp, "generic-xml-credential-result")
        assert_failure(run_wrapper(repo, generic_xml_request, generic_xml_result), generic_xml_result, "validate-request")

        unscannable_binary_patch = temp / "unscannable-binary.patch"
        make_unscannable_binary_patch(unscannable_binary_patch)
        unscannable_binary_request = temp / "unscannable-binary.json"
        write_json(unscannable_binary_request, request("KANBAN-UNSCANNABLE-BINARY", unscannable_binary_patch))
        unscannable_binary_result = fresh_result(temp, "unscannable-binary-result")
        assert_failure(run_wrapper(repo, unscannable_binary_request, unscannable_binary_result), unscannable_binary_result, "validate-request")

        for operation in ("rename", "copy"):
            rename_copy_patch = temp / (operation + ".patch")
            make_rename_copy_patch(rename_copy_patch, operation)
            rename_copy_request = temp / (operation + ".json")
            write_json(rename_copy_request, request("KANBAN-RESTRICTED-RENAME-COPY", rename_copy_patch))
            rename_copy_result = fresh_result(temp, operation + "-result")
            assert_failure(run_wrapper(repo, rename_copy_request, rename_copy_result), rename_copy_result, "validate-request")

        trailing_patch = temp / "trailing-whitespace.patch"
        make_trailing_whitespace_patch(trailing_patch)
        trailing_request = temp / "trailing-whitespace.json"
        write_json(trailing_request, request("KANBAN-TRAILING-WHITESPACE", trailing_patch))
        trailing_result = fresh_result(temp, "trailing-whitespace-result")
        assert_failure(run_wrapper(repo, trailing_request, trailing_result), trailing_result, "validate-request")

        decoded_binary_credential_patch = temp / "decoded-binary-credential.patch"
        make_binary_payload_credential_patch(decoded_binary_credential_patch)
        decoded_binary_credential_request = temp / "decoded-binary-credential.json"
        write_json(decoded_binary_credential_request, request("KANBAN-DECODED-BINARY-CREDENTIAL", decoded_binary_credential_patch))
        decoded_binary_credential_result = fresh_result(temp, "decoded-binary-credential-result")
        assert_failure(run_wrapper(repo, decoded_binary_credential_request, decoded_binary_credential_result), decoded_binary_credential_result, "validate-request")

        binary_patch = temp / "private" / "tracked-resource-binary.patch"
        make_binary_patch(binary_patch)
        binary_request = temp / "binary-resource.json"
        write_json(binary_request, request("KANBAN-BINARY", binary_patch))
        binary_result = fresh_result(temp, "binary-resource-result")
        completed = run_wrapper(repo, binary_request, binary_result)
        if completed.returncode != 0:
            fail(f"valid tracked binary resource patch was rejected: stdout={completed.stdout!r}, stderr={completed.stderr!r}")
        binary_manifest = json.loads((binary_result / "proposal-manifest.json").read_text(encoding="utf-8"))
        if binary_manifest["patch"]["binary"] is not True or binary_manifest["candidateKinds"] != ["resource"]:
            fail(f"binary resource claims were not bound: {binary_manifest}")
        if binary_manifest["allowedChangedFiles"] != ["shared/src/commonMain/composeResources/values/icon.png"] or binary_manifest["actualChangedFiles"] != binary_manifest["allowedChangedFiles"]:
            fail(f"binary resource paths were not bound: {binary_manifest}")
        if [item["name"] for item in binary_manifest["focusedChecks"]] != ["git.diff.check", "shared.compileKotlinIosSimulatorArm64"]:
            fail(f"binary resource focused checks were not conditional: {binary_manifest}")

        large_binary_patch = temp / "large-tracked-resource-binary.patch"
        make_large_binary_patch(large_binary_patch)
        large_binary_request = temp / "large-binary-resource.json"
        write_json(large_binary_request, request("KANBAN-LARGE-BINARY", large_binary_patch))
        large_binary_result = fresh_result(temp, "large-binary-resource-result")
        completed = run_wrapper(repo, large_binary_request, large_binary_result, timeout_seconds=20)
        if completed.returncode != 0:
            fail(f"valid large tracked binary resource patch was rejected: stdout={completed.stdout!r}, stderr={completed.stderr!r}")
        large_binary_manifest = json.loads((large_binary_result / "proposal-manifest.json").read_text(encoding="utf-8"))
        if large_binary_manifest["patch"]["binary"] is not True or large_binary_manifest["candidateKinds"] != ["resource"]:
            fail(f"large binary resource claims were not bound: {large_binary_manifest}")
        if large_binary_manifest["allowedChangedFiles"] != [LARGE_BINARY_RESOURCE] or large_binary_manifest["actualChangedFiles"] != [LARGE_BINARY_RESOURCE]:
            fail(f"large binary resource paths were not bound: {large_binary_manifest}")

        binary_add_delete_patches = {}
        for operation, changed_path in (
            ("add", "shared/src/commonMain/composeResources/values/added-binary.png"),
            ("delete", "shared/src/commonMain/composeResources/values/icon.png"),
        ):
            binary_add_delete_patch = temp / f"binary-{operation}.patch"
            make_binary_add_delete_patch(binary_add_delete_patch, changed_path, operation)
            binary_add_delete_patches[operation] = (binary_add_delete_patch, changed_path)
            binary_add_delete_request = temp / f"binary-{operation}.json"
            write_json(binary_add_delete_request, request(f"KANBAN-BINARY-{operation.upper()}", binary_add_delete_patch))
            binary_add_delete_result = fresh_result(temp, f"binary-{operation}-result")
            completed = run_wrapper(repo, binary_add_delete_request, binary_add_delete_result)
            if completed.returncode != 0:
                fail(
                    f"valid canonical binary {operation} resource patch was rejected: "
                    f"stdout={completed.stdout!r}, stderr={completed.stderr!r}"
                )
            binary_add_delete_manifest = json.loads((binary_add_delete_result / "proposal-manifest.json").read_text(encoding="utf-8"))
            if binary_add_delete_manifest["patch"]["binary"] is not True or binary_add_delete_manifest["candidateKinds"] != ["resource"]:
                fail(f"canonical binary {operation} resource claims were not bound: {binary_add_delete_manifest}")
            if binary_add_delete_manifest["allowedChangedFiles"] != [changed_path] or binary_add_delete_manifest["actualChangedFiles"] != [changed_path]:
                fail(f"canonical binary {operation} resource paths were not bound: {binary_add_delete_manifest}")

        deleted_binary_root = temp / "deleted-binary-credential-fixture"
        deleted_binary_repo, _deleted_renderer_log, _deleted_verifier_log, _deleted_compile_log = make_fake_repo(deleted_binary_root)
        deleted_binary_patch = temp / "deleted-binary-credential.patch"
        make_deleted_binary_credential_patch(deleted_binary_patch, deleted_binary_repo)
        deleted_binary_request = temp / "deleted-binary-credential.json"
        write_json(deleted_binary_request, request("KANBAN-DELETED-BINARY-CREDENTIAL", deleted_binary_patch))
        deleted_binary_result = fresh_result(temp, "deleted-binary-credential-result")
        assert_failure(run_wrapper(deleted_binary_repo, deleted_binary_request, deleted_binary_result), deleted_binary_result, "validate-request")

        for fixture, metadata, compression_flag, compression_method in (
            ("keyword-credential", "SAFE", 0, 0),
            ("decoded-bearer", "Bearer aaaaaaaaaaaaaaaa", 1, 0),
            ("host-path", "/Users/fixture/private/image.xmp", 1, 0),
            ("invalid-compression-flag", "SAFE", 2, 0),
            ("invalid-compression-method", "SAFE", 0, 1),
        ):
            if fixture == "keyword-credential":
                keyword = "API_TOKEN"
            else:
                keyword = "Description"
            for operation in ("add", "delete"):
                itxt_root = temp / f"itxt-{fixture}-{operation}"
                itxt_repo, _itxt_renderer_log, _itxt_verifier_log, _itxt_compile_log = make_fake_repo(itxt_root)
                itxt_patch = temp / f"itxt-{fixture}-{operation}.patch"
                make_itxt_binary_patch(
                    itxt_patch,
                    itxt_repo,
                    operation,
                    f"{fixture}-{operation}.png",
                    keyword,
                    metadata,
                    compression_flag,
                    compression_method,
                )
                itxt_request = temp / f"itxt-{fixture}-{operation}.json"
                write_json(itxt_request, request(f"KANBAN-ITXT-{fixture.upper()}-{operation.upper()}", itxt_patch))
                itxt_result = fresh_result(temp, f"itxt-{fixture}-{operation}-result")
                assert_failure(run_wrapper(itxt_repo, itxt_request, itxt_result), itxt_result, "validate-request")

        for operation, (source_patch, _changed_path) in binary_add_delete_patches.items():
            for mutation in ("missing", "extra"):
                malformed_binary = temp / f"binary-{operation}-{mutation}.patch"
                write_private(malformed_binary, mutate_binary_sections(source_patch.read_bytes(), mutation))
                malformed_binary_request = temp / f"binary-{operation}-{mutation}.json"
                write_json(malformed_binary_request, request(f"KANBAN-BINARY-{operation.upper()}-{mutation.upper()}", malformed_binary))
                malformed_binary_result = fresh_result(temp, f"binary-{operation}-{mutation}-result")
                assert_failure(run_wrapper(repo, malformed_binary_request, malformed_binary_result), malformed_binary_result, "validate-request")

        malformed_binary = temp / "malformed-binary.patch"
        malformed_binary_bytes = binary_patch.read_bytes().replace(b"GIT binary patch\n", b"GIT binary patch\nliteral 4\n", 1)
        write_private(malformed_binary, malformed_binary_bytes)
        malformed_binary_request = temp / "malformed-binary.json"
        write_json(malformed_binary_request, request("KANBAN-BINARY-BAD", malformed_binary))
        malformed_binary_result = fresh_result(temp, "malformed-binary-result")
        assert_failure(run_wrapper(repo, malformed_binary_request, malformed_binary_result), malformed_binary_result, "validate-request")

        unsafe_binary = temp / "unsafe-binary.patch"
        unsafe_binary_bytes = binary_patch.read_bytes().replace(
            b"a/shared/src/commonMain/composeResources/values/icon.png b/shared/src/commonMain/composeResources/values/icon.png",
            b"a/shared/src/commonMain/composeResources/values/../Unsafe.png b/shared/src/commonMain/composeResources/values/../Unsafe.png",
            1,
        )
        write_private(unsafe_binary, unsafe_binary_bytes)
        unsafe_binary_request = temp / "unsafe-binary.json"
        write_json(unsafe_binary_request, request("KANBAN-BINARY-UNSAFE", unsafe_binary))
        unsafe_binary_result = fresh_result(temp, "unsafe-binary-result")
        assert_failure(run_wrapper(repo, unsafe_binary_request, unsafe_binary_result), unsafe_binary_result, "validate-request")

        credential_binary = temp / "credential-binary.patch"
        credential_binary_bytes = binary_patch.read_bytes().replace(b"GIT binary patch\n", b"GIT binary patch\nAPI_TOKEN=REDACTED_REDACTED\n", 1)
        write_private(credential_binary, credential_binary_bytes)
        credential_binary_request = temp / "credential-binary.json"
        write_json(credential_binary_request, request("KANBAN-BINARY-CREDENTIAL", credential_binary))
        credential_binary_result = fresh_result(temp, "credential-binary-result")
        assert_failure(run_wrapper(repo, credential_binary_request, credential_binary_result), credential_binary_result, "validate-request")

        for operation, changed_path in (
            ("add", "shared/src/commonMain/composeResources/values/added.xml"),
            ("delete", "shared/src/commonMain/composeResources/values/deleted.xml"),
            ("add", "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Added.kt"),
            ("delete", "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/Deleted.kt"),
        ):
            add_delete_patch = temp / f"{operation}-{Path(changed_path).name}.patch"
            make_add_delete_patch(add_delete_patch, changed_path, operation)
            add_delete_request = temp / (add_delete_patch.stem + ".json")
            write_json(add_delete_request, request("KANBAN-ADD-DELETE", add_delete_patch))
            add_delete_result = fresh_result(temp, add_delete_patch.stem + "-result")
            completed = run_wrapper(repo, add_delete_request, add_delete_result)
            if completed.returncode != 0:
                fail(
                    f"valid {operation} diff was rejected for {changed_path}: "
                    f"stdout={completed.stdout!r}, stderr={completed.stderr!r}"
                )
            add_delete_payload = json.loads((add_delete_result / "preview-result.json").read_text(encoding="utf-8"))
            if add_delete_payload.get("status") != "passed":
                fail(f"valid {operation} diff did not produce a passed result: {add_delete_payload}")

        bad_add_delete_cases = (
            (
                "add-absolute-endpoint",
                "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/BadAbsolute.kt",
                "add",
                lambda text, changed_path: text.replace(f"+++ b/{changed_path}", "+++ /absolute/path.kt"),
            ),
            (
                "add-traversal-endpoint",
                "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/BadTraversal.kt",
                "add",
                lambda text, changed_path: text.replace(
                    f"+++ b/{changed_path}",
                    "+++ b/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/../BadTraversal.kt",
                ),
            ),
            (
                "delete-unsupported-endpoint",
                "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/BadUnsupported.kt",
                "delete",
                lambda text, changed_path: text.replace(
                    f"--- a/{changed_path}",
                    "--- a/androidApp/src/main/res/values/strings.xml",
                ),
            ),
            (
                "both-null-endpoints",
                "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/BadBothNull.kt",
                "add",
                lambda text, changed_path: text.replace(f"+++ b/{changed_path}", "+++ /dev/null"),
            ),
        )
        for label, changed_path, operation, mutate in bad_add_delete_cases:
            bad_patch = temp / (label + ".patch")
            make_add_delete_patch(bad_patch, changed_path, operation)
            write_private(bad_patch, mutate(bad_patch.read_text(encoding="utf-8"), changed_path))
            bad_request = temp / (label + ".json")
            write_json(bad_request, request("KANBAN-BAD-ADD-DELETE", bad_patch))
            bad_result = fresh_result(temp, label + "-result")
            assert_failure(run_wrapper(repo, bad_request, bad_result), bad_result, "validate-request")

        for root in (
            "/Users", "/private", "/tmp", "/Applications", "/Library",
            "/var", "/home", "/Volumes", "/System", "/opt", "/etc", "/usr",
            "/bin", "/sbin", "/dev", "/root", "/run", "/proc", "/sys", "/mnt", "/media", "/srv", "/boot", "/efi", "/cores",
        ):
            host_patch = temp / ("host-path-" + root[1:] + ".patch")
            make_patch(host_patch, marker=f"ok\nhost={root}/candidate")
            host_request = temp / (host_patch.stem + ".json")
            write_json(host_request, request("KANBAN-HOST", host_patch))
            host_result = fresh_result(temp, host_patch.stem + "-result")
            assert_failure(run_wrapper(repo, host_request, host_result), host_result, "validate-request")

        credential_patch = temp / "credential.patch"
        make_patch(credential_patch, marker="ok\nAPI_TOKEN=REDACTED_REDACTED")
        credential_request = temp / "credential.json"
        write_json(credential_request, request("KANBAN-CREDENTIAL", credential_patch))
        credential_result = fresh_result(temp, "credential-result")
        assert_failure(run_wrapper(repo, credential_request, credential_result), credential_result, "validate-request")

        for label, assignment in (
            ("typed-api-token", 'val apiToken: String = "' + "a" * 24 + '"'),
            ("typed-client-secret", 'let clientSecret: String = "' + "b" * 24 + '"'),
        ):
            typed_patch = temp / (label + ".patch")
            make_patch(typed_patch, marker="ok\n" + assignment)
            typed_request = temp / (label + ".json")
            write_json(typed_request, request("KANBAN-TYPED-CREDENTIAL", typed_patch))
            typed_result = fresh_result(temp, label + "-result")
            assert_failure(run_wrapper(repo, typed_request, typed_result), typed_result, "validate-request")

        traversal_patch = temp / "traversal.patch"
        make_path_patch(traversal_patch, "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/../Unsafe.kt")
        traversal_request = temp / "traversal.json"
        write_json(traversal_request, request("KANBAN-TRAVERSAL", traversal_patch))
        traversal_result = fresh_result(temp, "traversal-result")
        assert_failure(run_wrapper(repo, traversal_request, traversal_result), traversal_result, "validate-request")

        unsafe_path_patch = temp / "unsafe-path.patch"
        make_path_patch(unsafe_path_patch, "androidApp/src/main/res/values/strings.xml")
        unsafe_path_request = temp / "unsafe-path.json"
        write_json(unsafe_path_request, request("KANBAN-UNSAFE-PATH", unsafe_path_patch))
        unsafe_path_result = fresh_result(temp, "unsafe-path-result")
        assert_failure(run_wrapper(repo, unsafe_path_request, unsafe_path_result), unsafe_path_result, "validate-request")

        malformed_patch = temp / "malformed.patch"
        make_patch(malformed_patch)
        write_private(malformed_patch, malformed_patch.read_text(encoding="utf-8").replace("@@ -1 +1,2 @@", "@@ malformed @@"))
        malformed_patch_request = temp / "malformed-patch.json"
        write_json(malformed_patch_request, request("KANBAN-MALFORMED-PATCH", malformed_patch))
        malformed_patch_result = fresh_result(temp, "malformed-patch-result")
        assert_failure(run_wrapper(repo, malformed_patch_request, malformed_patch_result), malformed_patch_result, "validate-request")

        unknown_patch = temp / "unknown.patch"
        make_patch(unknown_patch)
        write_private(unknown_patch, unknown_patch.read_text(encoding="utf-8") + "unknown diff syntax\n")
        unknown_patch_request = temp / "unknown-patch.json"
        write_json(unknown_patch_request, request("KANBAN-UNKNOWN-PATCH", unknown_patch))
        unknown_patch_result = fresh_result(temp, "unknown-patch-result")
        assert_failure(run_wrapper(repo, unknown_patch_request, unknown_patch_result), unknown_patch_result, "validate-request")

        benign_patch = temp / "benign-internal-log.patch"
        make_patch(benign_patch, marker="ok\nTEST_MODE:internal-benign-log")
        benign_request = temp / "benign-internal-log.json"
        write_json(benign_request, request("KANBAN-70", benign_patch))
        benign_result = fresh_result(temp, "benign-internal-log-result")
        completed = run_wrapper(repo, benign_request, benign_result)
        if completed.returncode != 0:
            fail(f"benign CoreSimulator log was rejected: stdout={completed.stdout!r}, stderr={completed.stderr!r}")
        benign_payload = json.loads((benign_result / "preview-result.json").read_text(encoding="utf-8"))
        if benign_payload.get("status") != "passed":
            fail(f"benign CoreSimulator log did not produce a passed result: {benign_payload}")

        benign_label_patch = temp / "benign-ui-label.patch"
        make_patch(benign_label_patch, marker="ok\nTEST_MODE:benign-ui-label")
        benign_label_request = temp / "benign-ui-label.json"
        write_json(benign_label_request, request("KANBAN-71", benign_label_patch))
        benign_label_result = fresh_result(temp, "benign-ui-label-result")
        completed = run_wrapper(repo, benign_label_request, benign_label_result)
        if completed.returncode != 0:
            fail(f"benign UI labels were rejected as credentials: stdout={completed.stdout!r}, stderr={completed.stderr!r}")

        for typed_mode in ("typed-credential-16", "typed-credential-17", "typed-credential-18", "typed-credential-19", "typed-credential-long"):
            typed_log_patch = temp / (typed_mode + ".patch")
            make_patch(typed_log_patch, marker="ok\nTEST_MODE:" + typed_mode)
            typed_log_request = temp / (typed_mode + ".json")
            write_json(typed_log_request, request("KANBAN-TYPED-LOG", typed_log_patch))
            typed_log_result = fresh_result(temp, typed_mode + "-result")
            assert_failure(run_wrapper(repo, typed_log_request, typed_log_result), typed_log_result, "validate-artifacts")

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
        nested_patch.unlink()

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

        for mode in (
            "minimal-evidence", "unknown-run", "bad-run-types", "unknown-diff", "bad-diff-input",
            "forged-diff-ratio", "forged-diff-changed-ratio", "forged-diff-pixels",
            "forged-diff-threshold", "forged-diff-pass", "forged-diff-mask", "fake-capture-as-diff", "forged-diff-image",
            "malformed-png", "png-text-credential", "bad-markdown", "bad-markdown-ref",
            "bad-markdown-fixture", "bad-markdown-base", "bad-markdown-patch", "bad-markdown-check",
        ):
            mode_patch = temp / (mode + ".patch")
            make_patch(mode_patch, marker="ok\nTEST_MODE:" + mode)
            mode_request = temp / (mode + ".json")
            write_json(mode_request, request("KANBAN-63", mode_patch))
            mode_result = fresh_result(temp, mode + "-result")
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

        for mode in (
            "claim-fake-path", "claim-fake-kind", "claim-fake-checks",
            "claim-fake-head", "claim-fake-status", "claim-fake-applied-diff",
        ):
            mode_patch = temp / (mode + ".patch")
            make_patch(mode_patch, marker="ok\nTEST_MODE:" + mode)
            mode_request = temp / (mode + ".json")
            write_json(mode_request, request("KANBAN-CLAIMS", mode_patch))
            mode_result = fresh_result(temp, mode + "-result")
            assert_failure(run_wrapper(repo, mode_request, mode_result), mode_result, "validate-artifacts")

        for mode in (
            "unexpected-internal-root",
            "unexpected-internal-nested",
            "internal-secret",
            "internal-private-key",
            "internal-bearer",
            "internal-api-key",
            "internal-aws-key",
            "internal-known-token",
            "internal-symlink",
            "missing-proposal-marker",
            "bad-proposal-marker",
            "symlink-proposal-marker",
            "unknown-public",
            "publish-internal-log",
            "missing-proposal-patch",
            "bad-proposal-patch",
        ):
            mode_patch = temp / (mode + ".patch")
            make_patch(mode_patch, marker="ok\nTEST_MODE:" + mode)
            mode_request = temp / (mode + ".json")
            write_json(mode_request, request("KANBAN-69", mode_patch))
            mode_result = fresh_result(temp, mode + "-result")
            assert_failure(run_wrapper(repo, mode_request, mode_result), mode_result, "validate-artifacts")

        request_in_worktree = repo / "request-in-registered-worktree.json"
        write_json(request_in_worktree, request("KANBAN-65", patch))
        request_in_worktree_result = fresh_result(temp, "request-in-registered-worktree-result")
        assert_failure(run_wrapper(repo, request_in_worktree, request_in_worktree_result), request_in_worktree_result, "validate-request")
        request_in_worktree.unlink()

        task_worktree = temp / "registered-task-worktree"
        subprocess.run(["git", "-C", str(repo), "worktree", "add", "-q", "-b", "task-worktree", str(task_worktree)], check=True)
        request_in_task_worktree = task_worktree / "task-worktree-request.json"
        write_json(request_in_task_worktree, request("KANBAN-66", patch))
        request_in_task_worktree_result = fresh_result(temp, "task-worktree-request-result")
        assert_failure(run_wrapper(repo, request_in_task_worktree, request_in_task_worktree_result), request_in_task_worktree_result, "validate-request")
        subprocess.run(["git", "-C", str(repo), "worktree", "remove", "--force", str(task_worktree)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        for hook in ("request", "patch-verify", "gitrepo"):
            for signal_name in ("SIGHUP", "SIGINT", "SIGTERM"):
                hook_patch = temp / (hook + "-signal.patch")
                make_patch(hook_patch, marker="ok")
                hook_request = temp / (hook + "-signal.json")
                write_json(hook_request, request("KANBAN-GIT-SIGNAL", hook_patch))
                hook_result = fresh_result(temp, hook + "-" + signal_name + "-result")
                run_git_phase_signal(repo, hook_request, hook_result, hook, signal_name)

        for phase_name in ("before", "after"):
            for signal_name in ("SIGHUP", "SIGINT", "SIGTERM"):
                phase = "sleep-verify-" + phase_name
                case = phase + "-" + signal_name
                phase_patch = temp / (case + ".patch")
                make_patch(phase_patch, marker="ok\nTEST_MODE:" + phase)
                phase_request = temp / (case + ".json")
                write_json(phase_request, request("KANBAN-67", phase_patch))
                phase_result = fresh_result(temp, case + "-result")
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
                expected_records = 1 if phase_name == "before" else 2
                while time.monotonic() < deadline:
                    if verifier_log.exists() and len(verifier_log.read_text(encoding="utf-8").splitlines()) >= expected_records:
                        break
                    time.sleep(0.02)
                else:
                    process.kill()
                    process.communicate(timeout=5)
                    fail("verification child did not start for " + case)
                verify_records = [json.loads(line) for line in verifier_log.read_text(encoding="utf-8").splitlines()]
                active_record = verify_records[expected_records - 1]
                pid_file = Path(active_record["env"]["TMPDIR"]).parent / "proposal" / (".sleep-verify-" + phase_name + ".pid")
                verifier_pid = None
                while time.monotonic() < deadline:
                    if pid_file.exists():
                        pid_text = pid_file.read_text(encoding="ascii").strip()
                        if pid_text.isdigit():
                            verifier_pid = int(pid_text)
                            break
                    time.sleep(0.02)
                if verifier_pid is None:
                    process.kill()
                    process.communicate(timeout=5)
                    fail("verification child did not enter deterministic sleep for " + case)
                try:
                    os.kill(verifier_pid, 0)
                except ProcessLookupError:
                    process.kill()
                    process.communicate(timeout=5)
                    fail("verification child exited before signal for " + case)
                process.send_signal(getattr(signal, signal_name))
                stdout, stderr = process.communicate(timeout=10)
                completed = type("Completed", (), {"stdout": stdout, "stderr": stderr})()
                assert_no_traceback(completed, "verifier " + case)
                if not list(phase_result.rglob("*")):
                    fail(f"phase signal left empty result: case={case} rc={process.returncode} stdout={stdout!r} stderr={stderr!r}")
                assert_result_only(phase_result, "interrupted", "preview interrupted")
                if process.returncode == 0:
                    fail("signal unexpectedly returned success for " + case)
                try:
                    os.kill(verifier_pid, 0)
                except ProcessLookupError:
                    pass
                else:
                    fail("verifier child survived signal for " + case)

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
        make_patch(replacement_patch, marker=f"ok\nTEST_MODE:replace-patch\nREPLACE_PATCH_HEX:{str(replacement_patch).encode().hex()}|{str(replacement).encode().hex()}|{str(observed_patch).encode().hex()}")
        replacement_request = temp / "replacement-race.json"
        write_json(replacement_request, request("KANBAN-54", replacement_patch))
        replacement_snapshot = replacement_patch.read_bytes()
        replacement_result = fresh_result(temp, "replacement-race-result")
        completed = run_wrapper(repo, replacement_request, replacement_result)
        if completed.returncode != 0:
            fail(f"immutable patch snapshot was rejected: stdout={completed.stdout!r}, stderr={completed.stderr!r}")
        if json.loads((replacement_result / "preview-result.json").read_text(encoding="utf-8")).get("status") != "passed":
            fail("immutable patch snapshot did not produce a passed result")
        if observed_patch.read_bytes() != replacement_snapshot:
            fail("renderer observed replaced patch instead of immutable snapshot")

        outside_result = temp / "replacement-outside"
        outside_result.mkdir(mode=0o700)
        raced_result = fresh_result(temp, "replacement-result")
        original_result = temp / "replacement-result-original"
        race_patch = temp / "result-race.patch"
        make_patch(race_patch, marker=f"ok\nTEST_MODE:replace-result\nREPLACE_RESULT_HEX:{str(raced_result).encode().hex()}|{str(original_result).encode().hex()}|{str(outside_result).encode().hex()}")
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
            make_patch(signal_patch, marker=f"ok\nTEST_MODE:sleep\nSLEEP_CHILD_HEX:{str(child_pid_file).encode().hex()}")
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
            while (not child_pid_file.exists() or not child_pid_file.read_text(encoding="ascii").strip()) and time.monotonic() < deadline:
                time.sleep(0.02)
            if not child_pid_file.exists() or not child_pid_file.read_text(encoding="ascii").strip():
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
