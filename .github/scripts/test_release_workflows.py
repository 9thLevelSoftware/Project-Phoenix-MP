import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"


def workflow(name: str) -> str:
    return (WORKFLOWS / name).read_text(encoding="utf-8")


def archive_action() -> str:
    return (
        ROOT / ".github" / "actions" / "ios-signed-archive" / "action.yml"
    ).read_text(encoding="utf-8")


IOS_ARCHIVE_WORKFLOWS = (
    "ios-release-ipa.yml",
    "ios-testflight.yml",
    "ios-testflight-internal.yml",
)


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
        action = archive_action()
        self.assertIn("path: ~/.konan", action)
        self.assertRegex(action, r"uses: actions/cache@[0-9a-f]{40}")
        self.assertIn(expected_key, action)
        self.assertRegex(
            action,
            r"restore-keys: \|\n[ ]+\$\{\{ runner\.os \}\}-konan-\n",
        )
        self.assertNotRegex(action, r"konan-\d+\.\d+\.\d+")
        for name in IOS_ARCHIVE_WORKFLOWS:
            with self.subTest(workflow=name):
                text = workflow(name)
                self.assertIn("uses: ./.github/actions/ios-signed-archive", text)
                self.assertNotIn("path: ~/.konan", text)
                self.assertNotRegex(text, r"konan-\d+\.\d+\.\d+")

    def test_ios_archives_use_xcode_26_sdk(self) -> None:
        action = archive_action()
        self.assertIn(
            "xcode-select -s /Applications/Xcode_26.2.app/Contents/Developer",
            action,
        )
        for name in IOS_ARCHIVE_WORKFLOWS:
            with self.subTest(workflow=name):
                text = workflow(name)
                build_job = text.split("\n  upload", 1)[0]
                self.assertIn("runs-on: macos-26", build_job)
                self.assertIn("uses: ./.github/actions/ios-signed-archive", build_job)
                self.assertNotIn(
                    "xcode-select -s /Applications/Xcode_26.2.app/Contents/Developer",
                    text,
                )

    def test_ios_signed_archive_sequence_is_shared(self) -> None:
        action = archive_action()
        self.assertIn("using: composite", action)
        self.assertIn("iosApp/install-xcode-framework.sh release", action)
        self.assertIn(
            'PROCESSED="shared/build/processedResources/iosArm64/main/composeResources"',
            action,
        )
        self.assertIn(
            "ERROR: Processed Compose resources not found at $PROCESSED",
            action,
        )
        self.assertIn("if: inputs.validate_icons == 'true'", action)
        self.assertIn("python3 scripts/validate_ios_app_icons.py", action)
        self.assertIn("python3 scripts/test_ios_app_icons.py", action)
        self.assertIn("id: build_number", action)
        self.assertIn("security delete-keychain $RUNNER_TEMP/app-signing.keychain-db", action)
        for absent in (
            "create-xcframework",
            "ln -sfn",
            "find shared/build",
            "fallback",
            "debugFramework",
        ):
            self.assertNotIn(absent, action)
        pins = re.findall(r"(?m)^\s*(?:- )?uses: (\S+)", action)
        self.assertGreaterEqual(len(pins), 3)
        for ref in pins:
            self.assertRegex(ref, r"@[0-9a-f]{40}$")

        secret_inputs = (
            "supabase_url: ${{ secrets.SUPABASE_URL }}",
            "supabase_anon_key: ${{ secrets.SUPABASE_ANON_KEY }}",
            "build_certificate_base64: ${{ secrets.BUILD_CERTIFICATE_BASE64 }}",
            "p12_password: ${{ secrets.P12_PASSWORD }}",
            "provision_profile_base64: ${{ secrets.PROVISION_PROFILE_BASE64 }}",
            "keychain_password: ${{ secrets.KEYCHAIN_PASSWORD }}",
            "team_id: ${{ secrets.TEAM_ID }}",
            "provisioning_profile_name: ${{ secrets.PROVISIONING_PROFILE_NAME }}",
        )
        overlays = []
        calls = []
        for name, icons in (
            ("ios-release-ipa.yml", "false"),
            ("ios-testflight.yml", "true"),
            ("ios-testflight-internal.yml", "true"),
        ):
            with self.subTest(workflow=name):
                text = workflow(name)
                self.assertEqual(
                    text.count("uses: ./.github/actions/ios-signed-archive"),
                    1,
                )
                self.assertNotIn("validate_ios_app_icons.py", text)
                self.assertNotIn("linkReleaseFrameworkIosArm64", text)
                self.assertNotIn("create-xcframework", text)
                self.assertNotIn("ln -sfn", text)
                self.assertNotIn("find shared/build", text)
                self.assertNotIn("processedResources/iosArm64", text)
                overlay = re.search(
                    r"(?ms)^      # The build checkout may predate this action\..*?"
                    r"(?=^      - name: Build signed iOS archive\n)",
                    text,
                )
                self.assertIsNotNone(overlay)
                overlays.append(overlay.group(0))
                call = re.search(
                    r"(?ms)^      - name: Build signed iOS archive\n"
                    r"(?P<body>.*?)(?=^      - name: )",
                    text,
                )
                self.assertIsNotNone(call)
                body = call.group("body")
                self.assertIn(f"validate_icons: {icons}\n", body)
                for secret in secret_inputs:
                    self.assertIn(secret, body)
                calls.append(re.sub(r"validate_icons: (?:true|false)\n", "", body))
                if name != "ios-release-ipa.yml":
                    self.assertIn(
                        "build_number: ${{ steps.archive.outputs.build_number }}",
                        text,
                    )
        self.assertEqual(overlays[0], overlays[1])
        self.assertEqual(overlays[1], overlays[2])
        self.assertEqual(calls[0], calls[1])
        self.assertEqual(calls[1], calls[2])

        internal = workflow("ios-testflight-internal.yml")
        self.assertIn("\n  upload:\n    needs: build\n", internal)
        self.assertNotIn("upload-and-distribute", internal)
        self.assertNotIn("skip_distribution", internal)
        self.assertIn("permissions:\n  contents: read\n", internal)
        self.assertIn("group: ios-testflight-internal", internal)

    def test_testflight_build_and_upload_use_compatible_runners(self) -> None:
        for name in ("ios-testflight.yml", "ios-testflight-internal.yml"):
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
            ("ios-testflight-internal.yml", "build"),
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
        internal = workflow("ios-testflight-internal.yml")
        self.assertIn(
            "\n  tests:\n    uses: ./.github/workflows/release-tests.yml\n\n", internal
        )  # no `with:`, so it can never be skipped

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
