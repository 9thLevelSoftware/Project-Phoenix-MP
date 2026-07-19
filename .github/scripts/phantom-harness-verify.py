#!/usr/bin/env python3
"""Verify a self-contained, immutable Phantom iOS evidence packet.

The verifier intentionally accepts one small schema (version 1) and emits only a
machine-readable verdict.  It does not echo artifact contents: in particular,
secret findings are represented by a generic failure only.

Version 1 manifest shape (the provenance and marker objects may also be flattened
for callers that cannot nest them)::

    {
      "schemaVersion": 1,
      "runId": "...",
      "provenance": {
        "baseSha": "40 hex chars",
        "fixture": {"id": "...", "sha256": "64 hex chars"},
        "xcode": "...", "sdk": "...",
        "simulator": {"udid": "...", "name": "...", "runtime": "..."},
        "bundleId": "..."
      },
      "commands": [{"name": "build", "exitCode": 0}],
      "semanticMarkers": {"required": ["..."], "observed": ["..."]},
      "captures": [{
        "slug": "before", "path": "before.png", "sha256": "...",
        "phase": "before", "checkpoint": "...",
        "fixtureId": "...", "fixtureSha256": "...", "simulator": {...}
      }]
    }

Capture paths are relative files inside the artifact root.  Their parent tree
must be made only of caller-owned 0700 directories, and only manifest-parent
directories or documented generated subtrees may be nested.  This prevents an
otherwise valid hash from turning the manifest into a file-read primitive.  All
files in the packet are required to be regular 0600 files and the root is
required to be an exact 0700 directory.
"""

import hashlib
import json
import os
import re
import stat
import sys
import zlib
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Set, Tuple

SCHEMA_VERSION = 1
ROOT_MODE = 0o700
FILE_MODE = 0o600
MAX_FILE_BYTES = 8 * 1024 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024
SECRET_SCAN_CHUNK = 64 * 1024
SECRET_SCAN_OVERLAP = 8192
MAX_PNG_DIMENSION = 100000
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
HEX_SHA1 = re.compile(r"^[0-9a-fA-F]{40}$")
HEX_SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")
BUNDLE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9.-]*(?:\.[A-Za-z0-9][A-Za-z0-9.-]*)+$")
SLUG = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
SIMULATOR_UDID = re.compile(r"^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")

REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURE_ID = "just-lift-connected"
FIXTURE_SOURCE = REPO_ROOT / "shared/src/iosSimulatorArm64Main/kotlin/com/devil/phoenixproject/fixture/SimulatorLaunchFixture.kt"
EXPECTED_BUNDLE_ID = "com.devil.phoenixproject.projectphoenix"
EXPECTED_FIXTURE_SHA256 = "e180679548a2d96dbc59c51449edb3b99c19d3e3be82eca98c0707a21a64e78e"
CANONICAL_COMMANDS = (
    "xcodebuild.version",
    "simulator.boot",
    "simulator.bootstatus",
    "simulator.terminate",
    "simulator.uninstall",
    "build",
    "run-tests",
    "simulator.app-state",
    "simulator.logs",
    "simulator.screenshot",
)
CANONICAL_MARKERS = ("xctest.passed", "phantom.connected", "simulator.screenshot")
CANONICAL_OUTPUTS = {
    "xcodebuild.version": "toolchain.log",
    "simulator.boot": "boot.log",
    "simulator.bootstatus": "bootstatus.log",
    "simulator.terminate": "terminate.log",
    "simulator.uninstall": "uninstall.log",
    "build": "build.log",
    "run-tests": "test.log",
    "simulator.app-state": "app-state.log",
    "simulator.logs": "simulator.log",
    "simulator.screenshot": "screenshot.log",
}

# These patterns are intentionally conservative and bounded.  They identify
# common credential *shapes*, not arbitrary words such as "token" in logs.
SECRET_PATTERNS = (
    re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    re.compile(rb"\b(?:gh[pousr]|github_pat|glpat|xox[baprs]|sk|rk)[_-][A-Za-z0-9_./=-]{20,4096}\b", re.I),
    re.compile(rb"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(rb"\bBearer\s+[A-Za-z0-9._~+/=-]{20,4096}", re.I),
    re.compile(rb"\b(?:api[_-]?key|access[_-]?key|secret|password|passwd|auth[_-]?token|anon[_-]?key)\s*[:=]\s*['\"]?[A-Za-z0-9._~+/=-]{16,4096}", re.I),
)

Failure = str


def fixture_sha256() -> str:
    digest = hashlib.sha256()
    with FIXTURE_SOURCE.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _nonempty_string(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _first(mapping: Dict[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in mapping:
            return mapping[key]
    return None


def _provenance(manifest: Dict[str, Any]) -> Dict[str, Any]:
    value = manifest.get("provenance")
    return value if isinstance(value, dict) else {}


def _field(manifest: Dict[str, Any], name: str, *aliases: str) -> Any:
    provenance = _provenance(manifest)
    value = _first(provenance, name, *aliases)
    if value is not None:
        return value
    return _first(manifest, name, *aliases)


def _version_text(value: Any) -> Any:
    if isinstance(value, dict):
        return _first(value, "version", "build", "id", "name")
    return value


def _fixture_fields(manifest: Dict[str, Any]) -> Tuple[Any, Any]:
    fixture = _field(manifest, "fixture")
    fixture_id = _field(manifest, "fixtureId", "fixture_id")
    fixture_hash = _field(manifest, "fixtureSha256", "fixtureHash", "fixture_sha256")
    if isinstance(fixture, dict):
        fixture_id = _first(fixture, "id", "fixtureId", "fixture_id") or fixture_id
        fixture_hash = _first(fixture, "sha256", "hash", "fixtureSha256") or fixture_hash
    elif _nonempty_string(fixture) and fixture_id is None:
        fixture_id = fixture
    return fixture_id, fixture_hash


def _simulator_value(manifest: Dict[str, Any]) -> Any:
    return _field(manifest, "simulator", "simulatorIdentity", "simulator_identity")


def _simulator_key(value: Any) -> Optional[Tuple[Any, ...]]:
    if isinstance(value, str) and value.strip():
        return (value.strip(),)
    if not isinstance(value, dict):
        return None
    # An identity is more than a display name.  Include all supplied stable
    # fields so a before/after runtime or UDID drift cannot be hidden.
    parts = []
    for key in ("udid", "id", "name", "runtime", "runtimeVersion", "osVersion", "deviceType"):
        if key in value:
            item = value[key]
            if not _nonempty_string(item):
                return None
            parts.append((key, item.strip()))
    if not parts:
        return None
    return tuple(parts)


def _capture_simulator(capture: Dict[str, Any]) -> Any:
    value = _first(capture, "simulator", "simulatorIdentity", "simulator_identity")
    if value is not None:
        return value
    value = _first(capture, "simulatorId", "simulator_id", "simulatorUdid", "simulatorUDID", "udid")
    return value


def _capture_fixture_fields(capture: Dict[str, Any], fallback_id: Any, fallback_hash: Any) -> Tuple[Any, Any]:
    fixture = capture.get("fixture")
    fixture_id = _first(capture, "fixtureId", "fixture_id")
    fixture_hash = _first(capture, "fixtureSha256", "fixtureHash", "fixture_sha256")
    if isinstance(fixture, dict):
        fixture_id = _first(fixture, "id", "fixtureId", "fixture_id") or fixture_id
        fixture_hash = _first(fixture, "sha256", "hash", "fixtureSha256") or fixture_hash
    elif _nonempty_string(fixture) and fixture_id is None:
        fixture_id = fixture
    return (
        fallback_id if fixture_id is None else fixture_id,
        fallback_hash if fixture_hash is None else fixture_hash,
    )


def _relative_path_parts(raw_path: Any) -> Optional[List[str]]:
    if not _nonempty_string(raw_path):
        return None
    value = raw_path.strip()
    if "\x00" in value or "\\" in value or value.startswith("/"):
        return None
    # Reject Windows drive and UNC forms even when the verifier runs on POSIX.
    if re.match(r"^[A-Za-z]:", value) or value.startswith("//"):
        return None
    parts = value.split("/")
    if not parts or any(not part or part in (".", "..") for part in parts):
        return None
    return parts


def _safe_child(root: Path, raw_path: Any, label: str, failures: List[Failure]) -> Optional[Path]:
    if not _nonempty_string(raw_path):
        failures.append("%s path is missing" % label)
        return None
    parts = _relative_path_parts(raw_path)
    if parts is None:
        failures.append("%s path must be a relative file inside artifact root" % label)
        return None

    path = root.joinpath(*parts)
    current = root
    for part in parts[:-1]:
        current = current / part
        try:
            info = os.lstat(current)
        except OSError:
            failures.append("%s parent directory is missing" % label)
            return None
        if stat.S_ISLNK(info.st_mode):
            failures.append("%s path contains a symlink" % label)
            return None
        if not stat.S_ISDIR(info.st_mode):
            failures.append("%s path parent is not a directory" % label)
            return None
        if info.st_uid != os.getuid():
            failures.append("%s path parent has incorrect owner" % label)
            return None
        if stat.S_IMODE(info.st_mode) != ROOT_MODE:
            failures.append("%s path parent has incorrect permissions" % label)
            return None

    try:
        info = os.lstat(path)
    except OSError:
        failures.append("%s file is missing" % label)
        return None
    if stat.S_ISLNK(info.st_mode):
        failures.append("%s file is a symlink" % label)
        return None
    if not stat.S_ISREG(info.st_mode):
        failures.append("%s file is not a regular file" % label)
        return None
    if info.st_uid != os.getuid():
        failures.append("%s file has incorrect owner" % label)
        return None
    if stat.S_IMODE(info.st_mode) != FILE_MODE:
        failures.append("%s file has incorrect permissions" % label)
        return None
    return path


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            block = stream.read(1024 * 1024)
            if not block:
                return digest.hexdigest()
            digest.update(block)


def _validate_png(path: Path, label: str, failures: List[Failure]) -> None:
    try:
        with path.open("rb") as stream:
            data = stream.read(64)
    except OSError:
        failures.append("%s PNG could not be read" % label)
        return
    if len(data) < 33 or data[:8] != PNG_SIGNATURE:
        failures.append("%s is not a valid PNG header" % label)
        return
    length = int.from_bytes(data[8:12], "big")
    kind = data[12:16]
    if length != 13 or kind != b"IHDR" or len(data) < 16 + length + 4:
        failures.append("%s has a malformed PNG IHDR" % label)
        return
    payload = data[16:29]
    expected_crc = int.from_bytes(data[29:33], "big")
    if zlib.crc32(kind + payload) & 0xFFFFFFFF != expected_crc:
        failures.append("%s has a corrupt PNG IHDR" % label)
        return
    width = int.from_bytes(payload[0:4], "big")
    height = int.from_bytes(payload[4:8], "big")
    if width < 1 or height < 1 or width > MAX_PNG_DIMENSION or height > MAX_PNG_DIMENSION:
        failures.append("%s has invalid PNG dimensions" % label)


def _marker_names(value: Any) -> Set[str]:
    if isinstance(value, dict):
        return {str(key) for key, present in value.items() if present is True or present == "observed"}
    if isinstance(value, (list, tuple, set)):
        names = set()
        for item in value:
            if isinstance(item, str) and item.strip():
                names.add(item.strip())
            elif isinstance(item, dict):
                marker = _first(item, "id", "name", "marker")
                if _nonempty_string(marker):
                    names.add(marker.strip())
        return names
    return set()


def _command_list(value: Any) -> Optional[List[Any]]:
    if isinstance(value, dict):
        value = [
            (dict(result, name=name) if isinstance(result, dict) else {"name": name, "exitCode": result})
            for name, result in value.items()
        ]
    if not isinstance(value, list):
        return None
    return value


def _command_signature(result: Dict[str, Any]) -> Optional[Tuple[Any, ...]]:
    name = _first(result, "name", "id", "command")
    exit_code = _first(result, "exitCode", "exit_code", "statusCode", "status_code")
    output = result.get("output")
    if not _nonempty_string(name) or not isinstance(exit_code, int) or isinstance(exit_code, bool):
        return None
    if output is not None and not _nonempty_string(output):
        return None
    bundle = result.get("resultBundle")
    if bundle is not None and not isinstance(bundle, dict):
        return None
    bundle_signature = tuple(sorted(bundle.items())) if isinstance(bundle, dict) else None
    return (name.strip(), exit_code, output.strip() if isinstance(output, str) else None, bundle_signature)


def _read_command_journal(root: Path, failures: List[Failure]) -> List[Dict[str, Any]]:
    journal = _safe_child(root, ".commands.jsonl", "command journal", failures)
    if journal is None:
        return []
    try:
        if os.lstat(journal).st_size > MAX_FILE_BYTES:
            failures.append("command journal exceeds the bounded scan limit")
            return []
    except OSError:
        failures.append("command journal could not be inspected")
        return []
    records: List[Dict[str, Any]] = []
    try:
        lines = journal.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError):
        failures.append("command journal is not valid UTF-8")
        return []
    for index, line in enumerate(lines):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError:
            failures.append("command journal record is malformed")
            continue
        if not isinstance(record, dict) or _command_signature(record) is None:
            failures.append("command journal record is malformed")
            continue
        records.append(record)
    if not records:
        failures.append("command journal is empty")
    return records


def _validate_commands(manifest: Dict[str, Any], root: Path, failures: List[Failure]) -> Dict[str, Dict[str, Any]]:
    commands_value = _first(manifest, "commandResults", "commands", "command_results")
    commands = _command_list(commands_value)
    if not commands:
        failures.append("command results are missing")
        return {}
    manifest_by_name: Dict[str, Dict[str, Any]] = {}
    for index, result in enumerate(commands):
        if not isinstance(result, dict):
            failures.append("command result %d is malformed" % (index + 1))
            continue
        signature = _command_signature(result)
        if signature is None:
            failures.append("command result %d is malformed" % (index + 1))
            continue
        name = signature[0]
        if name in manifest_by_name:
            failures.append("duplicate command result name")
        manifest_by_name[name] = result
        if signature[1] != 0:
            failures.append("command result failed")
        output = result.get("output")
        if output is not None:
            _safe_child(root, output, "command output", failures)

    journal = _read_command_journal(root, failures)
    journal_by_name: Dict[str, Dict[str, Any]] = {}
    for record in journal:
        signature = _command_signature(record)
        if signature is None:
            continue
        name = signature[0]
        if name in journal_by_name:
            failures.append("duplicate command journal name")
        journal_by_name[name] = record

    for name, result in manifest_by_name.items():
        journal_record = journal_by_name.get(name)
        if journal_record is None or _command_signature(result) != _command_signature(journal_record):
            failures.append("manifest command does not match command journal")

    for name in CANONICAL_COMMANDS:
        manifest_record = manifest_by_name.get(name)
        journal_record = journal_by_name.get(name)
        if manifest_record is None or journal_record is None:
            failures.append("canonical command is missing")
            continue
        if _command_signature(manifest_record)[1] != 0 or _command_signature(journal_record)[1] != 0:
            failures.append("canonical command failed")
        expected_output = CANONICAL_OUTPUTS[name]
        if manifest_record.get("output") != expected_output or journal_record.get("output") != expected_output:
            failures.append("canonical command output topology is invalid")

    run_tests = manifest_by_name.get("run-tests")
    if run_tests is not None:
        bundle = run_tests.get("resultBundle")
        if not isinstance(bundle, dict) or bundle.get("basename") != "test.xcresult" or not _nonempty_string(bundle.get("status")):
            failures.append("XCTest result bundle topology is missing")
    return manifest_by_name


def _validate_command_evidence(root: Path, failures: List[Failure]) -> None:
    required_outputs = set(CANONICAL_OUTPUTS.values())
    for output in sorted(required_outputs):
        path = _safe_child(root, output, "command output", failures)
        if path is None:
            continue
        try:
            data = path.read_bytes()
        except OSError:
            failures.append("command output could not be read")
            continue
        if len(data) > MAX_FILE_BYTES:
            failures.append("command output exceeds the bounded scan limit")
            continue
        if output == "build.log" and not re.search(rb"build\s+succeeded", data, re.I):
            failures.append("build evidence is missing")
        if output == "test.log":
            if not re.search(rb"PhantomJustLiftFlowUITests\s+testHomeToJustLiftToPhantomConnected.*passed", data, re.I):
                failures.append("XCTest pass evidence is missing")
        if output == "simulator.log" and not re.search(rb"phantom.*connected|connected.*phantom", data, re.I | re.S):
            failures.append("Phantom connected evidence is missing")


def _validate_markers(manifest: Dict[str, Any], failures: List[Failure]) -> None:
    markers = _first(manifest, "semanticMarkers", "markers", "semantic_markers")
    if isinstance(markers, dict):
        required_value = _first(markers, "required", "requiredMarkers", "required_markers")
        observed_value = _first(markers, "observed", "observedMarkers", "observed_markers")
    else:
        required_value = _first(manifest, "requiredSemanticMarkers", "required_markers")
        observed_value = _first(manifest, "observedSemanticMarkers", "observed_markers")
    required = _marker_names(required_value)
    observed = _marker_names(observed_value)
    canonical = set(CANONICAL_MARKERS)
    if required != canonical:
        failures.append("required semantic markers do not match canonical contract")
    if observed != canonical:
        failures.append("observed semantic markers do not match canonical contract")


def _capture_phase(capture: Dict[str, Any]) -> Optional[str]:
    phase = _first(capture, "phase", "side", "kind")
    if isinstance(phase, str) and phase.strip().lower() in ("before", "after"):
        return phase.strip().lower()
    slug = _first(capture, "slug", "captureSlug", "capture_slug")
    if isinstance(slug, str):
        match = re.search(r"(?:^|[._-])(before|after)$", slug, re.I)
        if match:
            return match.group(1).lower()
        if slug.lower() in ("before", "after"):
            return slug.lower()
    return None


def _capture_pair_key(capture: Dict[str, Any]) -> str:
    explicit = _first(capture, "pair", "pairId", "pair_id")
    if _nonempty_string(explicit):
        return explicit.strip()
    slug = _first(capture, "slug", "captureSlug", "capture_slug")
    if isinstance(slug, str):
        return re.sub(r"(?:[._-](?:before|after)|^(?:before|after))$", "", slug, flags=re.I)
    return ""


def _validate_capture_identity(
    capture: Dict[str, Any],
    top_fixture_id: Any,
    top_fixture_hash: Any,
    top_simulator: Any,
    index: int,
    failures: List[Failure],
) -> Tuple[Optional[Dict[str, Any]], Optional[Path]]:
    label = "capture %d" % (index + 1)
    slug = _first(capture, "slug", "captureSlug", "capture_slug")
    if not isinstance(slug, str) or not _nonempty_string(slug) or not SLUG.fullmatch(slug.strip()):
        failures.append("%s has an invalid slug" % label)
    path = _safe_child(
        Path(capture["_root"]),
        _first(capture, "path", "relativePath", "relative_path", "file", "filename"),
        label,
        failures,
    )
    expected_hash = _first(capture, "sha256", "hash", "sha_256")
    if not isinstance(expected_hash, str) or not HEX_SHA256.fullmatch(expected_hash):
        failures.append("%s is missing a valid SHA-256 hash" % label)
    if path is not None:
        _validate_png(path, label, failures)
        if isinstance(expected_hash, str) and HEX_SHA256.fullmatch(expected_hash):
            try:
                if os.lstat(path).st_size > MAX_FILE_BYTES:
                    actual = None
                    failures.append("%s exceeds the bounded scan limit" % label)
                else:
                    actual = _sha256(path)
            except OSError:
                actual = None
            if actual is not None and actual.lower() != expected_hash.lower():
                failures.append("%s SHA-256 does not match" % label)

    fixture_id, fixture_hash = _capture_fixture_fields(capture, top_fixture_id, top_fixture_hash)
    if fixture_id != top_fixture_id:
        failures.append("%s fixture identity does not match run provenance" % label)
    if fixture_hash != top_fixture_hash:
        failures.append("%s fixture hash does not match run provenance" % label)
    checkpoint = _first(capture, "checkpoint", "checkpointId", "checkpoint_id")
    if not _nonempty_string(checkpoint):
        failures.append("%s checkpoint identity is missing" % label)
    capture_simulator = _capture_simulator(capture)
    if capture_simulator is None:
        capture_simulator = top_simulator
    if _simulator_key(capture_simulator) != _simulator_key(top_simulator):
        failures.append("%s simulator identity does not match run provenance" % label)
    return capture, path


def _validate_capture_pairs(captures: Sequence[Dict[str, Any]], failures: List[Failure]) -> None:
    pairs: Dict[str, Dict[str, Dict[str, Any]]] = {}
    for capture in captures:
        phase = _capture_phase(capture)
        if phase is None:
            continue
        pair = pairs.setdefault(_capture_pair_key(capture), {})
        if phase in pair:
            failures.append("duplicate %s capture in before/after pair" % phase)
        else:
            pair[phase] = capture
    for pair in pairs.values():
        before = pair.get("before")
        after = pair.get("after")
        if before is None or after is None:
            continue
        before_id, before_hash = _capture_fixture_fields(before, None, None)
        after_id, after_hash = _capture_fixture_fields(after, None, None)
        if before_id != after_id or before_hash != after_hash:
            failures.append("before/after fixture identity mismatch")
        before_checkpoint = _first(before, "checkpoint", "checkpointId", "checkpoint_id")
        after_checkpoint = _first(after, "checkpoint", "checkpointId", "checkpoint_id")
        if before_checkpoint != after_checkpoint:
            failures.append("before/after checkpoint identity mismatch")
        if _simulator_key(_capture_simulator(before)) != _simulator_key(_capture_simulator(after)):
            failures.append("before/after simulator identity mismatch")


def _manifest_reference_paths(manifest: Dict[str, Any]) -> List[Any]:
    references: List[Any] = []
    captures = manifest.get("captures")
    if isinstance(captures, list):
        for capture in captures:
            if isinstance(capture, dict):
                references.append(_first(capture, "path", "relativePath", "relative_path", "file", "filename"))
    listed = _first(manifest, "textualArtifacts", "textArtifacts", "textual_artifacts")
    if isinstance(listed, list):
        for item in listed:
            references.append(item.get("path") if isinstance(item, dict) else item)
    return references


def _allowed_directory_paths(root: Path, manifest: Dict[str, Any]) -> Set[Path]:
    # These are the generated subtrees documented by the harness contract.  A
    # manifest reference may also intentionally introduce a nested capture or
    # textual-artifact directory (for example, before/capture.png).
    generated_subtrees = {root / "derived-data", root / "test.xcresult"}
    allowed = set(generated_subtrees)
    for raw_path in _manifest_reference_paths(manifest):
        parts = _relative_path_parts(raw_path)
        if not parts:
            continue
        current = root
        for part in parts[:-1]:
            current = current / part
            allowed.add(current)
    return allowed


def _walk_artifact_tree(root: Path, failures: List[Failure]):
    pending = [root]
    while pending:
        directory = pending.pop()
        try:
            children = sorted(directory.iterdir(), key=lambda path: path.name)
        except OSError:
            failures.append("artifact directory could not be inspected")
            continue
        for path in children:
            try:
                info = os.lstat(path)
            except OSError:
                failures.append("artifact entry could not be inspected")
                continue
            yield path, info
            if stat.S_ISDIR(info.st_mode):
                pending.append(path)


def _validate_artifact_tree(root: Path, manifest: Dict[str, Any], failures: List[Failure]) -> None:
    allowed_directories = _allowed_directory_paths(root, manifest)
    generated_subtrees = (root / "derived-data", root / "test.xcresult")
    uid = os.getuid()
    for path, info in _walk_artifact_tree(root, failures):
        if stat.S_ISLNK(info.st_mode):
            failures.append("artifact tree contains a symlink")
            continue
        if stat.S_ISDIR(info.st_mode):
            if info.st_uid != uid:
                failures.append("artifact directory has incorrect owner")
            if stat.S_IMODE(info.st_mode) != ROOT_MODE:
                failures.append("artifact directory has incorrect permissions")
            if path not in allowed_directories and not any(
                base == path or base in path.parents for base in generated_subtrees
            ):
                failures.append("unexpected nested artifact path topology")
            continue
        if not stat.S_ISREG(info.st_mode):
            failures.append("artifact entry is not a regular file or directory")
            continue
        if info.st_uid != uid:
            failures.append("artifact file has incorrect owner")
        if stat.S_IMODE(info.st_mode) != FILE_MODE:
            failures.append("artifact file has incorrect permissions")


def _is_env_artifact(path: Path) -> bool:
    return path.name == ".env" or path.name.startswith(".env.")


def _scan_file_for_secret(path: Path) -> Optional[bool]:
    overlap = b""
    try:
        with path.open("rb") as stream:
            while True:
                block = stream.read(SECRET_SCAN_CHUNK)
                if not block:
                    return False
                window = overlap + block
                if any(pattern.search(window) for pattern in SECRET_PATTERNS):
                    return True
                overlap = window[-SECRET_SCAN_OVERLAP:]
    except OSError:
        return None


def _scan_secrets(root: Path, failures: List[Failure], manifest: Optional[Dict[str, Any]] = None) -> None:
    del manifest  # Every regular artifact is scanned; manifest listings are not an allowlist.
    total_size = 0
    for path, info in _walk_artifact_tree(root, failures):
        if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
            continue
        if _is_env_artifact(path):
            failures.append(".env artifacts are not accepted")
            continue
        if info.st_size > MAX_FILE_BYTES:
            failures.append("artifact file exceeds the bounded scan limit")
            continue
        total_size += info.st_size
        if total_size > MAX_TOTAL_BYTES:
            failures.append("artifact packet exceeds the bounded scan limit")
            return
        found = _scan_file_for_secret(path)
        if found is None:
            failures.append("artifact file could not be scanned")
            continue
        if found:
            failures.append("secret-like content detected in artifact")
            return


def _check_root(root: Path, failures: List[Failure]) -> bool:
    try:
        info = os.lstat(root)
    except OSError:
        failures.append("artifact root does not exist")
        return False
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
        failures.append("artifact root is not a directory")
        return False
    if info.st_uid != os.getuid():
        failures.append("artifact root has incorrect owner")
    if stat.S_IMODE(info.st_mode) != ROOT_MODE:
        failures.append("artifact root has incorrect permissions")
    return True


def _validate_recursive_tree(root: Path, failures: List[Failure]) -> None:
    if not _check_root(root, failures):
        return
    uid = os.getuid()
    for path, info in _walk_artifact_tree(root, failures):
        if stat.S_ISLNK(info.st_mode):
            failures.append("artifact tree contains a symlink")
            continue
        if stat.S_ISDIR(info.st_mode):
            if info.st_uid != uid or stat.S_IMODE(info.st_mode) != ROOT_MODE:
                failures.append("artifact directory ownership or permissions are invalid")
            continue
        if not stat.S_ISREG(info.st_mode):
            failures.append("artifact entry is not a regular file or directory")
            continue
        if info.st_uid != uid or stat.S_IMODE(info.st_mode) != FILE_MODE:
            failures.append("artifact file ownership or permissions are invalid")
    _scan_secrets(root, failures)


def verify_recursive_tree(artifact_dir: str) -> Dict[str, Any]:
    failures: List[Failure] = []
    _validate_recursive_tree(Path(artifact_dir), failures)
    unique_failures: List[Failure] = []
    seen: Set[str] = set()
    for failure in failures:
        if failure not in seen:
            unique_failures.append(failure)
            seen.add(failure)
    return {
        "passed": not unique_failures,
        "schemaVersion": None,
        "runId": None,
        "captureCount": 0,
        "failures": unique_failures,
    }


def verify(artifact_dir: str) -> Dict[str, Any]:
    root = Path(artifact_dir)
    failures: List[Failure] = []
    schema_version: Any = None
    run_id: Any = None
    capture_count = 0
    root_ok = _check_root(root, failures)
    manifest: Dict[str, Any] = {}
    manifest_path: Optional[Path] = None
    if root_ok:
        manifest_path = _safe_child(root, "run.json", "manifest", failures)
    if manifest_path is not None:
        try:
            if os.lstat(manifest_path).st_size > MAX_FILE_BYTES:
                raise ValueError("manifest exceeds the bounded scan limit")
            raw_manifest = manifest_path.read_bytes()
            manifest = json.loads(raw_manifest.decode("utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError):
            failures.append("run.json is not valid UTF-8 JSON")
    if not isinstance(manifest, dict):
        failures.append("run.json root must be an object")
        manifest = {}
    if root_ok:
        _validate_artifact_tree(root, manifest, failures)
    _scan_secrets(root, failures, manifest)

    sentinel = _safe_child(root, ".phantom-harness", "harness sentinel", failures) if root_ok else None
    if sentinel is not None:
        try:
            if sentinel.read_bytes() != b"phantom-harness-artifact-v1\n":
                failures.append("harness sentinel is invalid")
        except OSError:
            failures.append("harness sentinel could not be read")
    elif root_ok:
        failures.append("harness sentinel is missing")

    schema_version = _first(manifest, "schemaVersion", "schema_version")
    if schema_version != SCHEMA_VERSION:
        failures.append("unsupported or missing schema version")
    run_id = manifest.get("runId", manifest.get("run_id"))
    if not _nonempty_string(run_id):
        failures.append("run ID is missing")
        run_id = None
    else:
        run_id = run_id.strip()

    base_sha = _field(manifest, "baseSha", "baseSHA", "base_sha", "commitSha")
    if not isinstance(base_sha, str) or not HEX_SHA1.fullmatch(base_sha) or base_sha.lower() == "0" * 40:
        failures.append("base SHA is missing or malformed")
    fixture_id, fixture_hash = _fixture_fields(manifest)
    if fixture_id != FIXTURE_ID:
        failures.append("fixture ID does not match canonical contract")
    expected_fixture_hash: Optional[str]
    try:
        expected_fixture_hash = fixture_sha256()
    except OSError:
        expected_fixture_hash = None
    if not isinstance(fixture_hash, str) or not HEX_SHA256.fullmatch(fixture_hash):
        failures.append("fixture SHA-256 is missing or malformed")
    elif expected_fixture_hash != EXPECTED_FIXTURE_SHA256 or fixture_hash.lower() != EXPECTED_FIXTURE_SHA256:
        failures.append("fixture SHA-256 does not match canonical source")
    xcode = _version_text(_field(manifest, "xcode", "xcodeVersion", "xcode_version"))
    sdk = _version_text(_field(manifest, "sdk", "sdkVersion", "sdk_version"))
    if not _nonempty_string(xcode):
        failures.append("Xcode provenance is missing")
    if not _nonempty_string(sdk):
        failures.append("SDK provenance is missing")
    simulator = _simulator_value(manifest)
    simulator_key = _simulator_key(simulator)
    if (
        not isinstance(simulator, dict)
        or not SIMULATOR_UDID.fullmatch(str(simulator.get("udid", "")))
        or not _nonempty_string(simulator.get("name"))
        or not _nonempty_string(simulator.get("runtime"))
        or simulator.get("state") not in ("Shutdown", "Booted")
        or simulator_key is None
    ):
        failures.append("simulator identity is missing or malformed")
    bundle_id = _field(manifest, "bundleId", "bundleID", "bundle_id")
    if bundle_id != EXPECTED_BUNDLE_ID or not isinstance(bundle_id, str) or not BUNDLE_ID.fullmatch(bundle_id):
        failures.append("bundle ID does not match canonical app")

    command_results = _validate_commands(manifest, root, failures) if root_ok else {}
    if root_ok and command_results:
        _validate_command_evidence(root, failures)
    _validate_markers(manifest, failures)

    captures = manifest.get("captures")
    if not isinstance(captures, list) or not captures:
        failures.append("captures are missing")
        captures = []
    capture_count = len(captures)
    slugs: Set[str] = set()
    valid_capture_objects: List[Dict[str, Any]] = []
    for index, capture in enumerate(captures):
        if not isinstance(capture, dict):
            failures.append("capture %d is malformed" % (index + 1))
            continue
        capture["_root"] = str(root)
        slug = _first(capture, "slug", "captureSlug", "capture_slug")
        if isinstance(slug, str):
            normalized_slug = slug.strip()
            if normalized_slug in slugs:
                failures.append("duplicate capture slug")
            slugs.add(normalized_slug)
        valid_capture_objects.append(capture)
        _validate_capture_identity(capture, fixture_id, fixture_hash, simulator, index, failures)
    _validate_capture_pairs(valid_capture_objects, failures)

    capture_by_slug = {
        _first(capture, "slug", "captureSlug", "capture_slug"): capture
        for capture in valid_capture_objects
        if isinstance(_first(capture, "slug", "captureSlug", "capture_slug"), str)
    }
    for slug, expected_path in (("simulator-after", None), ("xctest-after", "xctest-attachment.png")):
        capture = capture_by_slug.get(slug)
        if capture is None:
            failures.append("required screenshot or XCTest attachment capture is missing")
            continue
        if expected_path is not None and _first(capture, "path", "relativePath", "relative_path", "file", "filename") != expected_path:
            failures.append("required screenshot or XCTest attachment topology is invalid")
        if _capture_phase(capture) != "after" or _first(capture, "checkpoint", "checkpointId", "checkpoint_id") != "phantom-connected":
            failures.append("required screenshot or XCTest attachment semantics are invalid")

    # Keep output deterministic and bounded.  Never append raw file contents or
    # secret matches to a failure; marker labels are the only manifest values
    # intentionally echoed for diagnosis.
    unique_failures = []
    seen = set()
    for failure in failures:
        if failure not in seen:
            unique_failures.append(failure)
            seen.add(failure)
    return {
        "passed": not unique_failures,
        "schemaVersion": schema_version,
        "runId": run_id,
        "captureCount": capture_count,
        "failures": unique_failures,
    }


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if len(args) == 2 and args[0] == "--recursive-tree":
        result = verify_recursive_tree(args[1])
    elif len(args) != 1:
        result = {
            "passed": False,
            "schemaVersion": None,
            "runId": None,
            "captureCount": 0,
            "failures": ["usage: phantom-harness-verify.py ARTIFACT_DIR"],
        }
    else:
        result = verify(args[0])
    sys.stdout.write(json.dumps(result, separators=(",", ":"), sort_keys=False) + "\n")
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
