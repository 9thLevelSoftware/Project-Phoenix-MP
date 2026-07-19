#!/usr/bin/env python3
"""Contract tests for the secure Phantom evidence manifest verifier."""

import hashlib
import importlib.util
import json
import os
import stat
import subprocess
import sys
import tempfile
import unittest
import zlib
from pathlib import Path


SCRIPT = Path(__file__).with_name("phantom-harness-verify.py")


def load_verifier():
    spec = importlib.util.spec_from_file_location("phantom_harness_verify", SCRIPT)
    if spec is None or spec.loader is None:
        raise AssertionError("could not load verifier module")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def png_bytes(width=2, height=2):
    def chunk(kind, payload):
        return (
            len(payload).to_bytes(4, "big")
            + kind
            + payload
            + zlib.crc32(kind + payload).to_bytes(4, "big")
        )

    ihdr = (
        width.to_bytes(4, "big")
        + height.to_bytes(4, "big")
        + bytes([8, 6, 0, 0, 0])
    )
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", b"") + chunk(b"IEND", b"")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def write_bytes(path, data, mode=0o600):
    path.write_bytes(data)
    os.chmod(path, mode)


class PhantomHarnessVerifyTests(unittest.TestCase):
    def setUp(self):
        self.verifier = load_verifier()
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "evidence"
        self.root.mkdir(mode=0o700)
        os.chmod(self.root, 0o700)
        self.before = png_bytes()
        self.after = png_bytes()
        write_bytes(self.root / "before.png", self.before)
        write_bytes(self.root / "after.png", self.after)
        write_bytes(self.root / "commands.log", b"build: exit=0\ntest: exit=0\n")
        self.manifest = self._manifest()
        self._write_manifest()

    def tearDown(self):
        self.temp.cleanup()

    def _manifest(self):
        fixture_hash = "b" * 64
        simulator = {
            "udid": "11111111-2222-3333-4444-555555555555",
            "name": "iPhone 17 Pro",
            "runtime": "iOS 26.0",
        }
        return {
            "schemaVersion": 1,
            "runId": "run-20260718-001",
            "provenance": {
                "baseSha": "a" * 40,
                "fixture": {"id": "fixture-phantom-v1", "sha256": fixture_hash},
                "xcode": "Xcode 26.0",
                "sdk": "iPhoneSimulator26.0.sdk",
                "simulator": simulator,
                "bundleId": "com.example.phoenix.phantom",
            },
            "commands": [
                {"name": "build", "exitCode": 0},
                {"name": "run-tests", "exitCode": 0},
            ],
            "semanticMarkers": {
                "required": ["app.ready", "phantom.connected"],
                "observed": ["app.ready", "phantom.connected", "capture.after"],
            },
            "captures": [
                {
                    "slug": "before",
                    "path": "before.png",
                    "sha256": sha256(self.before),
                    "phase": "before",
                    "checkpoint": "phantom-connected",
                    "fixtureId": "fixture-phantom-v1",
                    "fixtureSha256": fixture_hash,
                    "simulator": simulator,
                },
                {
                    "slug": "after",
                    "path": "after.png",
                    "sha256": sha256(self.after),
                    "phase": "after",
                    "checkpoint": "phantom-connected",
                    "fixtureId": "fixture-phantom-v1",
                    "fixtureSha256": fixture_hash,
                    "simulator": simulator,
                },
            ],
            "textualArtifacts": [{"path": "commands.log"}],
        }

    def _write_manifest(self):
        write_bytes(
            self.root / "run.json",
            json.dumps(self.manifest, sort_keys=True, indent=2).encode("utf-8"),
        )

    def _run(self):
        completed = subprocess.run(
            [sys.executable, str(SCRIPT), str(self.root)],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertTrue(completed.stdout.strip(), completed.stderr)
        result = json.loads(completed.stdout)
        return completed, result

    def test_valid_packet_passes(self):
        completed, result = self._run()
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(
            result,
            {
                "passed": True,
                "schemaVersion": 1,
                "runId": "run-20260718-001",
                "captureCount": 2,
                "failures": [],
            },
        )

    def test_valid_nested_before_after_packet_passes(self):
        before_dir = self.root / "before"
        after_dir = self.root / "after"
        before_dir.mkdir(mode=0o700)
        after_dir.mkdir(mode=0o700)
        os.chmod(before_dir, 0o700)
        os.chmod(after_dir, 0o700)
        (self.root / "before.png").rename(before_dir / "capture.png")
        (self.root / "after.png").rename(after_dir / "capture.png")
        self.manifest["captures"][0]["path"] = "before/capture.png"
        self.manifest["captures"][1]["path"] = "after/capture.png"
        self._write_manifest()

        completed, result = self._run()

        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertTrue(result["passed"], result)

    def test_nested_symlink_is_rejected(self):
        nested = self.root / "nested"
        nested.mkdir(mode=0o700)
        os.chmod(nested, 0o700)
        outside = Path(self.temp.name) / "outside.txt"
        write_bytes(outside, b"not an artifact")
        (nested / "escaped.txt").symlink_to(outside)
        self._write_manifest()

        completed, result = self._run()

        self.assertEqual(completed.returncode, 1)
        self.assertTrue(any("symlink" in failure.lower() for failure in result["failures"]))

    def test_nested_secret_like_text_is_rejected_without_echoing_secret(self):
        secret = "Bearer abcdefghijklmnopqrstuvwx"
        nested = self.root / "nested"
        nested.mkdir(mode=0o700)
        os.chmod(nested, 0o700)
        write_bytes(nested / "commands.log", ("Authorization: " + secret).encode("utf-8"))
        self.manifest["textualArtifacts"].append({"path": "nested/commands.log"})
        self._write_manifest()

        completed, result = self._run()

        self.assertEqual(completed.returncode, 1)
        self.assertFalse(result["passed"])
        self.assertNotIn(secret, completed.stdout)
        self.assertTrue(any("secret" in failure.lower() for failure in result["failures"]))

    def test_nested_non_regular_file_is_rejected(self):
        nested = self.root / "nested"
        nested.mkdir(mode=0o700)
        os.chmod(nested, 0o700)
        os.mkfifo(nested / "unexpected.fifo", 0o600)
        self._write_manifest()

        completed, result = self._run()

        self.assertEqual(completed.returncode, 1)
        self.assertFalse(result["passed"])
        self.assertTrue(any("regular" in failure.lower() for failure in result["failures"]))

    def test_missing_capture_hash_fails(self):
        del self.manifest["captures"][0]["sha256"]
        self._write_manifest()
        completed, result = self._run()
        self.assertEqual(completed.returncode, 1)
        self.assertFalse(result["passed"])
        self.assertTrue(any("hash" in failure.lower() for failure in result["failures"]))

    def test_hash_mismatch_fails(self):
        self.manifest["captures"][0]["sha256"] = "0" * 64
        self._write_manifest()
        completed, result = self._run()
        self.assertEqual(completed.returncode, 1)
        self.assertTrue(any("sha" in failure.lower() for failure in result["failures"]))

    def test_symlink_and_path_traversal_fail(self):
        outside = Path(self.temp.name) / "outside.png"
        write_bytes(outside, self.before)
        (self.root / "before.png").unlink()
        (self.root / "before.png").symlink_to(outside)
        self.manifest["captures"][0]["path"] = "before.png"
        self._write_manifest()
        completed, result = self._run()
        self.assertEqual(completed.returncode, 1)
        self.assertTrue(any("symlink" in failure.lower() for failure in result["failures"]))

        self.manifest["captures"][0]["path"] = "../outside.png"
        self._write_manifest()
        completed, result = self._run()
        self.assertEqual(completed.returncode, 1)
        self.assertTrue(any("root" in failure.lower() or "travers" in failure.lower() for failure in result["failures"]))

    def test_invalid_png_fails(self):
        write_bytes(self.root / "before.png", b"not a png")
        self.manifest["captures"][0]["sha256"] = sha256(b"not a png")
        self._write_manifest()
        completed, result = self._run()
        self.assertEqual(completed.returncode, 1)
        self.assertTrue(any("png" in failure.lower() for failure in result["failures"]))

    def test_wrong_permissions_fail(self):
        os.chmod(self.root / "before.png", 0o644)
        completed, result = self._run()
        self.assertEqual(completed.returncode, 1)
        self.assertTrue(any("permission" in failure.lower() for failure in result["failures"]))

        os.chmod(self.root, 0o755)
        completed, result = self._run()
        self.assertEqual(completed.returncode, 1)
        self.assertTrue(any("artifact root" in failure.lower() for failure in result["failures"]))

    def test_secret_like_text_is_rejected_without_echoing_secret(self):
        secret = "ghp_1234567890abcdefghijklmnopqrstuvwxyzABCD"
        write_bytes(self.root / "commands.log", ("Authorization: Bearer " + secret).encode("utf-8"))
        self._write_manifest()
        completed, result = self._run()
        self.assertEqual(completed.returncode, 1)
        self.assertFalse(result["passed"])
        self.assertNotIn(secret, completed.stdout)
        self.assertTrue(any("secret" in failure.lower() for failure in result["failures"]))

    def test_duplicate_capture_slug_fails(self):
        duplicate = dict(self.manifest["captures"][1])
        duplicate["path"] = "before.png"
        self.manifest["captures"].append(duplicate)
        self._write_manifest()
        completed, result = self._run()
        self.assertEqual(completed.returncode, 1)
        self.assertTrue(any("duplicate" in failure.lower() for failure in result["failures"]))

    def test_before_after_identity_mismatch_fails(self):
        self.manifest["captures"][1]["fixtureId"] = "fixture-other"
        self._write_manifest()
        completed, result = self._run()
        self.assertEqual(completed.returncode, 1)
        self.assertTrue(any("identity" in failure.lower() or "fixture" in failure.lower() for failure in result["failures"]))

    def test_command_and_semantic_contracts_are_enforced(self):
        self.manifest["commands"][1]["exitCode"] = 2
        self.manifest["semanticMarkers"]["observed"] = ["app.ready"]
        self._write_manifest()
        completed, result = self._run()
        self.assertEqual(completed.returncode, 1)
        self.assertTrue(any("command" in failure.lower() for failure in result["failures"]))
        self.assertTrue(any("marker" in failure.lower() for failure in result["failures"]))


if __name__ == "__main__":
    unittest.main(verbosity=2)
