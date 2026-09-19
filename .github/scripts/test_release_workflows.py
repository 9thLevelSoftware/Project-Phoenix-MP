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
                    r"(?ms)^  build:.*?runs-on: macos-26.*?uses: actions/upload-artifact@[0-9a-f]{40} # v4",
                )
                self.assertRegex(
                    text,
                    r"(?ms)^  upload(?:-and-distribute)?:.*?needs: build.*?runs-on: macos-15"
                    r".*?uses: actions/download-artifact@[0-9a-f]{40} # v4"
                    r".*?xcode-select -s /Applications/Xcode_16\.4\.app/Contents/Developer"
                    r".*?xcrun altool",
                )
                self.assertNotIn("github.run_attempt", text)
                self.assertGreaterEqual(text.count("testflight-ipa-${{ github.run_id }}"), 2)
                self.assertRegex(
                    text,
                    r"(?ms)uses: actions/upload-artifact@[0-9a-f]{40} # v4.*?overwrite: true",
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

    def test_releases_are_gated_on_unit_tests(self) -> None:
        self.assertIn(
            "./gradlew -Pskip.supabase.check=true :shared:testAndroidHostTest :androidApp:testDebugUnitTest",
            workflow("release-tests.yml"),
        )
        for name, first_job in (
            ("release-all.yml", "create-release"),
            ("release-all-existing.yml", "prepare-release"),
            ("android-playstore.yml", "build-and-upload"),
            ("android-release-apk.yml", "build-and-attach"),
            ("ios-release-ipa.yml", "build-and-attach"),
            ("ios-testflight.yml", "build"),
            ("ios-testflight-internal.yml", "build"),
        ):
            with self.subTest(workflow=name):
                text = workflow(name)
                self.assertIn("\n  tests:\n    uses: ./.github/workflows/release-tests.yml\n", text)
                self.assertIn(f"\n  {first_job}:\n    needs: tests\n", text)
        # Orchestrators test once up front; the platform workflows they call skip the re-run.
        for name in ("release-all.yml", "release-all-existing.yml"):
            with self.subTest(workflow=name):
                self.assertEqual(workflow(name).count("skip_tests: true"), 4)

    def test_release_workflow_actions_are_sha_pinned(self) -> None:
        for name in (
            "release-all.yml",
            "release-all-existing.yml",
            "release-tests.yml",
            "android-playstore.yml",
            "android-release-apk.yml",
            "ios-release-ipa.yml",
            "ios-testflight.yml",
            "ios-testflight-internal.yml",
        ):
            with self.subTest(workflow=name):
                refs = re.findall(r"(?m)^\s*(?:- )?uses: (\S+)", workflow(name))
                self.assertTrue(refs)
                for ref in refs:
                    if not ref.startswith("./"):
                        self.assertRegex(ref, r"@[0-9a-f]{40}$")

    def test_ci_ios_simulator_tests_are_non_blocking_and_skip_prs(self) -> None:
        text = workflow("ci-tests.yml")
        self.assertNotIn("verifyCommonMainPhoenixDatabaseMigration", text)
        job = re.search(
            r"(?ms)^  ios-simulator-tests:\n(?P<body>.*?)(?=^  [a-z][a-z0-9-]*:\n)", text
        )
        self.assertIsNotNone(job)
        body = job.group("body")
        self.assertIn("continue-on-error: true", body)
        self.assertIn(":shared:iosSimulatorArm64Test", body)
        self.assertIn("runs-on: macos-latest", body)
        self.assertIn(
            "if: github.event_name == 'workflow_dispatch' || "
            "(github.event_name == 'push' && github.ref == 'refs/heads/main')",
            body,
        )


if __name__ == "__main__":
    unittest.main()
