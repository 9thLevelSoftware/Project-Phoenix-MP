import json
import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / ".github" / "scripts" / "resolve-play-version-code.sh"

FAKE_CURL = textwrap.dedent(
    r"""
    #!/usr/bin/env python3
    import json, os, sys
    state_path = os.environ["FAKE_CURL_STATE"]
    state = json.loads(open(state_path).read())
    args = sys.argv[1:]
    method = "GET"
    url = None
    i = 0
    while i < len(args):
        arg = args[i]
        if arg == "-X" and i + 1 < len(args):
            method = args[i + 1]
            i += 2
            continue
        if arg in ("-H", "-d", "--connect-timeout", "--max-time", "--retry", "--retry-max-time", "--output") and i + 1 < len(args):
            i += 2
            continue
        if arg.startswith("-"):
            i += 1
            continue
        url = arg
        i += 1
    state["calls"].append({"method": method, "url": url})
    open(state_path, "w").write(json.dumps(state))
    mode = os.environ.get("FAKE_CURL_MODE", "")
    if mode == "edit-http-fail" and method == "POST":
        sys.stderr.write("curl: (22) The requested URL returned error: 500\n")
        sys.exit(22)
    if method == "DELETE":
        if mode == "delete-fail":
            sys.exit(1)
        sys.exit(0)
    if method == "POST":
        print(json.dumps(state.get("edit_response", {"id": "edit-123"})))
        sys.exit(0)
    if mode == "tracks-http-fail" and url and url.endswith("/tracks"):
        sys.stderr.write("curl: (22) The requested URL returned error: 503\n")
        sys.exit(22)
    if url and url.endswith("/tracks"):
        print(json.dumps(state.get("tracks", {"tracks": []})))
        sys.exit(0)
    sys.stderr.write("unexpected curl: %r\n" % (args,))
    sys.exit(99)
    """
)


class ResolvePlayVersionCodeTest(unittest.TestCase):
    def run_helper(self, tracks=None, override="", mode="", edit_response=None):
        with tempfile.TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            state_path = directory / "state.json"
            state = {
                "calls": [],
                "tracks": tracks if tracks is not None else {"tracks": []},
            }
            if edit_response is not None:
                state["edit_response"] = edit_response
            state_path.write_text(json.dumps(state), encoding="utf-8")
            curl = directory / "curl"
            curl.write_text(FAKE_CURL.lstrip("\n"), encoding="utf-8")
            curl.chmod(0o755)
            output = directory / "github_output"
            env = os.environ | {
                "PATH": f"{directory}{os.pathsep}{os.environ.get('PATH', '')}",
                "FAKE_CURL_STATE": str(state_path),
                "FAKE_CURL_MODE": mode,
                "ACCESS_TOKEN": "token",
                "PACKAGE_NAME": "com.devil.phoenixproject",
                "VERSION_CODE_OVERRIDE": override,
                "GITHUB_OUTPUT": str(output),
            }
            result = subprocess.run(
                ["bash", str(HELPER)],
                env=env,
                text=True,
                capture_output=True,
                check=False,
            )
            recorded = json.loads(state_path.read_text(encoding="utf-8"))
            outputs = {}
            if output.exists():
                for line in output.read_text(encoding="utf-8").splitlines():
                    key, value = line.split("=", 1)
                    outputs[key] = value
            return result, outputs, recorded["calls"]

    def test_selects_one_past_the_highest_code_across_tracks(self) -> None:
        tracks = {
            "tracks": [
                {"releases": [{"versionCodes": ["3", 10]}]},
                {"releases": [{"versionCodes": [7]}, {}]},
            ]
        }
        result, outputs, calls = self.run_helper(tracks=tracks)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            {
                "play_max_version_code": "10",
                "selected_version_code": "11",
                "version_code_source": "play_max_plus_one",
            },
            outputs,
        )
        self.assertIn(
            "::notice title=Google Play version preflight::play_max_version_code=10 selected_version_code=11 source=play_max_plus_one",
            result.stdout,
        )
        self.assertIn("Play max version code: 10\n", result.stdout)
        self.assertIn("Selected version code: 11\n", result.stdout)
        self.assertIn("Version code source: play_max_plus_one\n", result.stdout)
        self.assertEqual("POST", calls[0]["method"])
        self.assertTrue(calls[0]["url"].endswith("/applications/com.devil.phoenixproject/edits"))
        self.assertTrue(calls[1]["url"].endswith("/edits/edit-123/tracks"))
        self.assertEqual("DELETE", calls[2]["method"])
        self.assertTrue(calls[2]["url"].endswith("/edits/edit-123"))

    def test_manual_override_must_exceed_play_max_and_stay_in_range(self) -> None:
        tracks = {"tracks": [{"releases": [{"versionCodes": [41]}]}]}
        result, outputs, calls = self.run_helper(tracks=tracks, override="  50  ")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("50", outputs["selected_version_code"])
        self.assertEqual("manual_override", outputs["version_code_source"])
        self.assertEqual("41", outputs["play_max_version_code"])
        self.assertEqual("DELETE", calls[-1]["method"])

        low, _, _ = self.run_helper(tracks=tracks, override="41")
        self.assertNotEqual(0, low.returncode)
        self.assertIn(
            "Selected version code 41 must be greater than current Play max 41.",
            low.stderr,
        )

        high, _, _ = self.run_helper(tracks=tracks, override="2100000001")
        self.assertNotEqual(0, high.returncode)
        self.assertIn(
            "Selected version code 2100000001 is outside the allowed range 1..2100000000.",
            high.stderr,
        )

        zero, _, _ = self.run_helper(tracks=tracks, override="0")
        self.assertNotEqual(0, zero.returncode)
        self.assertIn(
            "Selected version code 0 is outside the allowed range 1..2100000000.",
            zero.stderr,
        )

        invalid, _, _ = self.run_helper(tracks=tracks, override="12abc")
        self.assertNotEqual(0, invalid.returncode)
        self.assertIn(
            "Invalid workflow input versionCodeOverride='12abc'. Expected a positive integer <= 2100000000.",
            invalid.stderr,
        )

    def test_missing_play_codes_require_an_override_seed(self) -> None:
        missing, _, calls = self.run_helper(tracks={"tracks": []})
        self.assertNotEqual(0, missing.returncode)
        self.assertIn(
            "No existing Play version codes were found. Provide workflow_dispatch input versionCodeOverride to seed the next release.",
            missing.stderr,
        )
        self.assertEqual("DELETE", calls[-1]["method"])

        seeded, outputs, _ = self.run_helper(tracks={"tracks": []}, override="7")
        self.assertEqual(0, seeded.returncode, seeded.stderr)
        self.assertEqual(
            {
                "play_max_version_code": "none",
                "selected_version_code": "7",
                "version_code_source": "manual_override",
            },
            outputs,
        )
        self.assertIn(
            "::notice title=Google Play version preflight::play_max_version_code=none selected_version_code=7 source=manual_override",
            seeded.stdout,
        )

    def test_edit_without_id_fails_and_skips_delete(self) -> None:
        result, outputs, calls = self.run_helper(edit_response={"error": "nope"})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Failed to create a temporary Google Play edit.", result.stderr)
        self.assertIn('{"error": "nope"}', result.stderr)
        self.assertEqual({}, outputs)
        self.assertEqual(["POST"], [call["method"] for call in calls])

    def test_cleanup_still_deletes_the_edit_when_later_calls_fail(self) -> None:
        result, _, calls = self.run_helper(mode="tracks-http-fail")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(["POST", "GET", "DELETE"], [call["method"] for call in calls])

        warned, _, calls = self.run_helper(
            tracks={"tracks": [{"releases": [{"versionCodes": [1]}]}]},
            mode="delete-fail",
        )
        self.assertEqual(0, warned.returncode, warned.stderr)
        self.assertIn("Warning: failed to delete temporary Play edit edit-123", warned.stderr)
        self.assertEqual("DELETE", calls[-1]["method"])


if __name__ == "__main__":
    unittest.main()
