import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"


def workflow(name: str) -> str:
    return (WORKFLOWS / name).read_text(encoding="utf-8")


class ReleaseWorkflowContracts(unittest.TestCase):
    def test_ios_release_konan_cache_follows_gradle_version_inputs(self) -> None:
        ci_keys = re.findall(
            r"(?m)^[ ]+key: \$\{\{ runner\.os \}\}-konan-\$\{\{ hashFiles\([^)]+\) \}\}$",
            workflow("ci-tests.yml"),
        )
        self.assertGreaterEqual(len(ci_keys), 1)
        expected_key = ci_keys[0].strip()
        self.assertIn("gradle/libs.versions.toml", expected_key)
        self.assertIn("**/*.gradle*", expected_key)
        self.assertIn("**/gradle-wrapper.properties", expected_key)
        self.assertNotRegex(expected_key, r"\d+\.\d+\.\d+")
        for name in (
            "ios-release-ipa.yml",
            "ios-testflight.yml",
        ):
            with self.subTest(workflow=name):
                text = workflow(name)
                self.assertIn("path: ~/.konan", text)
                self.assertRegex(text, r"uses: actions/cache@[0-9a-f]{40}")
                self.assertIn(expected_key, text)
                self.assertIn("restore-keys: |\n            ${{ runner.os }}-konan-\n", text)
                self.assertNotRegex(text, r"konan-\d+\.\d+\.\d+")

    def test_ios_archives_use_xcode_26_sdk(self) -> None:
        for name in (
            "ios-release-ipa.yml",
            "ios-testflight.yml",
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
        for name in ("ios-testflight.yml",):
            with self.subTest(workflow=name):
                text = workflow(name)
                # Contract is SHA-pinned uses + runner/tooling split. Do not assert
                # Dependabot version comments (`# v4`, `# v7.0.1`, …); those move
                # independently of the pin. SHA pinning is also covered by
                # test_release_workflow_actions_are_sha_pinned.
                self.assertRegex(
                    text,
                    r"(?ms)^  build:.*?runs-on: macos-26.*?uses: actions/upload-artifact@[0-9a-f]{40}",
                )
                self.assertRegex(
                    text,
                    r"(?ms)^  upload(?:-and-distribute)?:.*?needs: build.*?runs-on: macos-15"
                    r".*?uses: actions/download-artifact@[0-9a-f]{40}"
                    r".*?xcode-select -s /Applications/Xcode_16\.4\.app/Contents/Developer"
                    r".*?xcrun altool",
                )
                self.assertNotIn("github.run_attempt", text)
                self.assertGreaterEqual(text.count("testflight-ipa-${{ github.run_id }}"), 2)
                self.assertRegex(
                    text,
                    r"(?ms)uses: actions/upload-artifact@[0-9a-f]{40}.*?overwrite: true",
                )

    def test_store_jobs_are_not_blocked_by_the_other_platform(self) -> None:
        for name, release_job in (("release-all.yml", "create-release"),):
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

        existing = workflow("release-all-existing.yml")
        self.assertIn("needs: [prepare-release, tests]\n    if: ${{ !inputs.skip_android_playstore }}", existing)
        self.assertIn("needs: [prepare-release, tests]\n    if: ${{ !inputs.skip_ios_testflight }}", existing)
        self.assertNotIn("needs: [prepare-release, tests, android-apk]", existing)
        self.assertNotIn("needs: [prepare-release, tests, ios-ipa]", existing)

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
            ("android-playstore.yml", "build-and-upload"),
            ("android-release-apk.yml", "build-and-attach"),
            ("ios-release-ipa.yml", "build-and-attach"),
            ("ios-testflight.yml", "build"),
        ):
            with self.subTest(workflow=name):
                text = workflow(name)
                self.assertRegex(
                    text,
                    r"\n  tests:\n(?:    if: github\.ref == 'refs/heads/main'\n)?"
                    r"    uses: \./\.github/workflows/release-tests\.yml\n",
                )
                self.assertIn(f"\n  {first_job}:\n    needs: tests\n", text)
        gate = workflow("release-tests.yml")
        self.assertNotIn("continue-on-error", gate)
        self.assertNotIn("|| true", gate)
        # Orchestrators test once up front, build exactly the tested commit, and tell the
        # platform workflows they call to skip the re-run.
        for name, first_job in (("release-all.yml", "create-release"),):
            with self.subTest(workflow=name):
                text = workflow(name)
                self.assertEqual(text.count("skip_tests: true"), 4)
                self.assertIn(
                    f"\n  {first_job}:\n    needs: tests\n"
                    "    if: ${{ !cancelled() && needs.tests.result != 'failure' }}\n",
                    text,
                )
        self.assertIn("ref: ${{ needs.tests.outputs.sha }}", workflow("release-all.yml"))
        self.assertEqual(
            workflow("release-all-existing.yml").count(
                "source_ref: ${{ needs.prepare-release.outputs.sha }}"
            ),
            4,
        )
        existing = workflow("release-all-existing.yml")
        self.assertIn("ref: ${{ needs.prepare-release.outputs.sha }}", existing)
        self.assertIn('gh api "repos/${{ github.repository }}/git/ref/tags/$TAG" --jq .object', existing)
        self.assertIn('gh api "repos/${{ github.repository }}/git/tags/$sha" --jq .object', existing)
        self.assertIn('test "$object_type" = "commit"', existing)
        self.assertNotIn("gh release delete-asset", existing)
        self.assertIn("needs: [prepare-release, tests]", existing)
        # Direct dispatch of a platform workflow must run the tests: skip only via an input
        # that defaults to false, and test the ref that is built.
        for name in (
            "android-playstore.yml",
            "android-release-apk.yml",
            "ios-release-ipa.yml",
            "ios-testflight.yml",
        ):
            with self.subTest(workflow=name):
                text = workflow(name)
                self.assertIn(
                    "\n  tests:\n    uses: ./.github/workflows/release-tests.yml\n    with:\n"
                    "      ref: ${{ inputs.source_ref }}\n"
                    "      skip: ${{ inputs.skip_tests || false }}\n",
                    text,
                )
                self.assertRegex(
                    text,
                    r"(?m)^      skip_tests:\n(?:        (?!default:).*\n)*        default: false\n",
                )
                self.assertEqual(text.count("skip_tests"), 2)

    def test_testflight_skip_distribution_keeps_validation_and_unit_tests(self) -> None:
        self.assertFalse((WORKFLOWS / "ios-testflight-internal.yml").exists())
        for path in WORKFLOWS.glob("*.yml"):
            self.assertNotIn(
                "ios-testflight-internal",
                path.read_text(encoding="utf-8"),
                path.name,
            )

        text = workflow("ios-testflight.yml")
        declarations = re.findall(
            r"(?m)^      skip_distribution:\n"
            r"(?:        .+\n)*?"
            r"        type: boolean\n"
            r"        default: false\n",
            text,
        )
        self.assertEqual(len(declarations), 2)
        self.assertEqual(text.count("if: ${{ !inputs.skip_distribution }}"), 2)
        self.assertRegex(
            text,
            r"(?m)^      - name: Add build to TestFlight test group\n"
            r"        if: \$\{\{ !inputs\.skip_distribution \}\}$",
        )
        self.assertRegex(
            text,
            r"(?m)^      - name: Tag build for release notes tracking\n"
            r"        if: \$\{\{ !inputs\.skip_distribution \}\}$",
        )
        self.assertRegex(
            text,
            r"(?m)^      - name: Upload to TestFlight\n        env:\n",
        )

        icon = re.search(
            r"(?ms)^      - name: Validate release app icons\n(?P<body>.*?)(?=^      - name: )",
            text,
        )
        self.assertIsNotNone(icon)
        icon_body = icon.group("body")
        self.assertNotIn("if:", icon_body)
        self.assertNotIn("skip_distribution", icon_body)
        self.assertIn("python3 scripts/validate_ios_app_icons.py", icon_body)
        self.assertIn("python3 scripts/test_ios_app_icons.py", icon_body)

        tests = re.search(r"(?ms)^  tests:\n(?P<body>.*?)(?=^  build:\n)", text)
        self.assertIsNotNone(tests)
        self.assertNotIn("skip_distribution", tests.group("body"))
        self.assertIn("uses: ./.github/workflows/release-tests.yml", tests.group("body"))

    def test_release_workflow_actions_are_sha_pinned(self) -> None:
        for name in (
            "release-all.yml",
            "release-all-existing.yml",
            "release-tests.yml",
            "android-playstore.yml",
            "android-release-apk.yml",
            "ios-release-ipa.yml",
            "ios-testflight.yml",
        ):
            with self.subTest(workflow=name):
                refs = re.findall(r"(?m)^\s*(?:- )?uses: (\S+)", workflow(name))
                self.assertTrue(refs)
                for ref in refs:
                    if not ref.startswith("./"):
                        self.assertRegex(ref, r"@[0-9a-f]{40}$")

    def test_release_asset_replacement_is_staged_and_uses_workflow_sha_helper(self) -> None:
        for name in ("android-release-apk.yml", "ios-release-ipa.yml"):
            with self.subTest(workflow=name):
                text = workflow(name)
                self.assertIn("replace_release_asset.py", text)
                self.assertIn("${{ github.workflow_sha }}", text)
                self.assertIn("Stage and promote", text)
                self.assertNotIn("gh release upload", text)
                self.assertNotIn("--clobber", text)

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
        self.assertIn("-Xmx4g", body)
        self.assertIn("uses: mikepenz/action-junit-report@", body)
        self.assertIn("fail_on_failure: false", body)
        self.assertNotIn("fail_on_failure: true", body)
        self.assertIn(
            "if: github.event_name == 'workflow_dispatch' || "
            "(github.event_name == 'push' && github.ref == 'refs/heads/main')",
            body,
        )


if __name__ == "__main__":
    unittest.main()
