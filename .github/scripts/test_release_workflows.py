import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"


def workflow(name: str) -> str:
    return (WORKFLOWS / name).read_text(encoding="utf-8")


class ReleaseWorkflowContracts(unittest.TestCase):
    def test_ios_archives_use_xcode_26_sdk(self) -> None:
        for name in (
            "ios-release-ipa.yml",
            "ios-testflight.yml",
            "ios-testflight-internal.yml",
        ):
            with self.subTest(workflow=name):
                text = workflow(name)
                build_section = text.split("- name: Build shared framework", 1)[0]
                self.assertIn("runs-on: macos-26", build_section)
                self.assertIn(
                    "xcode-select -s /Applications/Xcode_26.2.app/Contents/Developer",
                    build_section,
                )

    def test_testflight_build_and_upload_use_compatible_runners(self) -> None:
        for name in ("ios-testflight.yml", "ios-testflight-internal.yml"):
            with self.subTest(workflow=name):
                text = workflow(name)
                self.assertRegex(
                    text,
                    r"(?ms)^  build:.*?runs-on: macos-26.*?uses: actions/upload-artifact@v4",
                )
                self.assertRegex(
                    text,
                    r"(?ms)^  upload(?:-and-distribute)?:.*?needs: build.*?runs-on: macos-15"
                    r".*?uses: actions/download-artifact@v4"
                    r".*?xcode-select -s /Applications/Xcode_16\.4\.app/Contents/Developer"
                    r".*?xcrun altool",
                )
                self.assertNotIn("github.run_attempt", text)
                self.assertGreaterEqual(text.count("testflight-ipa-${{ github.run_id }}"), 2)
                self.assertRegex(
                    text,
                    r"(?ms)uses: actions/upload-artifact@v4.*?overwrite: true",
                )

    def test_store_jobs_are_not_blocked_by_the_other_platform(self) -> None:
        for name, release_job in (
            ("release-all.yml", "create-release"),
            ("release-all-existing.yml", "prepare-release"),
        ):
            with self.subTest(workflow=name):
                text = workflow(name)
                android = re.search(
                    r"(?ms)^  android-playstore:\n(?P<body>.*?)(?=^  [a-z][a-z0-9-]*:\n)",
                    text,
                )
                ios = re.search(
                    r"(?ms)^  ios-testflight:\n(?P<body>.*?)(?=^  [a-z][a-z0-9-]*:\n)",
                    text,
                )
                self.assertIsNotNone(android)
                self.assertIsNotNone(ios)
                android_body = android.group("body")
                ios_body = ios.group("body")
                self.assertIn(f"needs: [{release_job}, android-apk]", android_body)
                self.assertNotIn("ios-ipa", android_body)
                self.assertIn(f"needs: [{release_job}, ios-ipa]", ios_body)
                self.assertNotIn("android-apk", ios_body)

        self.assertNotIn(
            "APK and IPA builds complete before store publication begins.",
            workflow("release-all.yml"),
        )


if __name__ == "__main__":
    unittest.main()
