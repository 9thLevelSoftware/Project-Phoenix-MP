import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / ".github" / "scripts" / "replace_release_asset.py"

FAKE_GH = r'''
import json, os, sys
state_path = os.environ["FAKE_RELEASE_STATE"]
state = json.loads(open(state_path).read())
args = sys.argv[1:]
if args[:2] == ["release", "view"]:
    print(json.dumps({"assets": [asset | {
        "id": "RA_node_" + str(asset["id"]),
        "apiUrl": "https://api.github.com/repos/owner/repo/releases/assets/" + str(asset["id"]),
    } for asset in state["assets"]]}))
elif args[:2] == ["release", "upload"]:
    if os.environ.get("FAKE_MODE") == "upload-fail": sys.exit(1)
    upload_spec = args[3]
    path, label = upload_spec.split("#", 1) if "#" in upload_spec else (upload_spec, None)
    name = os.path.basename(path)
    if any(asset["name"] == name for asset in state["assets"]): sys.exit(1)
    state["assets"].append({"id": state["next_id"], "name": name, "size": os.path.getsize(path)})
    state["uploads"].append({"name": name, "label": label})
    state["next_id"] += 1
elif args[:3] == ["api", "--method", "PATCH"]:
    asset_id = int(args[3].rsplit("/", 1)[1])
    name = args[5].split("=", 1)[1]
    asset = next(item for item in state["assets"] if item["id"] == asset_id)
    if os.environ.get("FAKE_MODE") == "promote-fail" and ".candidate-" in asset["name"] and "." not in name.removeprefix("ProjectPhoenix-v1.apk"):
        sys.exit(1)
    asset["name"] = name
elif args[:3] == ["api", "--method", "DELETE"]:
    asset_id = int(args[3].rsplit("/", 1)[1])
    state["assets"] = [item for item in state["assets"] if item["id"] != asset_id]
else:
    raise SystemExit("unexpected gh call: " + repr(args))
open(state_path, "w").write(json.dumps(state))
'''


class ReplaceReleaseAssetTest(unittest.TestCase):
    def run_helper(self, assets, mode=""):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            state_path = directory / "state.json"
            state_path.write_text(json.dumps({"assets": assets, "uploads": [], "next_id": 10}), encoding="utf-8")
            fake_gh = directory / "fake_gh.py"
            fake_gh.write_text(FAKE_GH, encoding="utf-8")
            source = directory / "replacement.apk"
            source.write_bytes(b"new-content")
            env = os.environ | {
                "FAKE_RELEASE_STATE": str(state_path),
                "FAKE_MODE": mode,
                "GITHUB_REPOSITORY": "owner/repo",
                "GITHUB_RUN_ID": "42",
                "GITHUB_RUN_ATTEMPT": "1",
                "GH_BIN": f'"{sys.executable}" "{fake_gh}"',
            }
            result = subprocess.run(
                [sys.executable, str(HELPER), "v1", str(source), "ProjectPhoenix-v1.apk"],
                env=env,
                text=True,
                capture_output=True,
                check=False,
            )
            return result, json.loads(state_path.read_text(encoding="utf-8"))

    def test_success_stages_validates_promotes_then_removes_old_backup(self):
        result, state = self.run_helper([{"id": 1, "name": "ProjectPhoenix-v1.apk", "size": 3}])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            [{"id": 10, "name": "ProjectPhoenix-v1.apk", "size": 11}],
            state["assets"],
        )
        self.assertEqual(
            [{"name": "ProjectPhoenix-v1.apk.candidate-42-1", "label": None}],
            state["uploads"],
        )

    def test_upload_failure_keeps_existing_canonical_asset(self):
        old = {"id": 1, "name": "ProjectPhoenix-v1.apk", "size": 3}
        result, state = self.run_helper([old], "upload-fail")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([old], state["assets"])

    def test_promotion_failure_restores_existing_canonical_asset(self):
        old = {"id": 1, "name": "ProjectPhoenix-v1.apk", "size": 3}
        result, state = self.run_helper([old], "promote-fail")
        self.assertNotEqual(0, result.returncode)
        self.assertIn(old, state["assets"])
        self.assertTrue(any(".failed-" in item["name"] for item in state["assets"]))
        self.assertEqual("ProjectPhoenix-v1.apk.candidate-42-1", state["uploads"][0]["name"])

    def test_missing_existing_asset_skips_backup_and_promotes_staged_asset(self):
        result, state = self.run_helper([])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("ProjectPhoenix-v1.apk", state["assets"][0]["name"])


if __name__ == "__main__":
    unittest.main()
