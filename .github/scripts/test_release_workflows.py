import itertools
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"


def workflow(name: str) -> str:
    return (WORKFLOWS / name).read_text(encoding="utf-8")


class ReleaseWorkflowContracts(unittest.TestCase):
    def job(self, text: str, name: str) -> str:
        match = re.search(
            r"(?ms)^  " + re.escape(name) + r":\n(.*?)(?=^  [a-z][a-z0-9-]*:\n|\Z)", text
        )
        self.assertIsNotNone(match, f"Missing workflow job: {name}")
        return match.group(1)

    def test_orchestrators_upload_tv_by_default_without_extra_review_inputs(self) -> None:
        for name, gate, source in (
            ("release-all.yml", "create-release", "github.sha"),
            ("release-all-existing.yml", "prepare-release", "needs.prepare-release.outputs.sha"),
        ):
            with self.subTest(workflow=name):
                text = workflow(name)
                self.assertRegex(text, r"(?m)^      skip_tvos_testflight:\n(?:        .*\n)*?        default: false$")
                for removed in ("tvos_review_pr", "tvos_review_accepted", "TVOS_REVIEW", "pull-requests:", "statuses:"):
                    self.assertNotIn(removed, text)
                tv = self.job(text, "tvos-testflight")
                self.assertIn(f"needs: {gate}" if gate == "create-release" else "needs: [prepare-release, tests]", tv)
                self.assertIn("if: ${{ !inputs.skip_tvos_testflight }}", tv)
                self.assertIn("uses: ./.github/workflows/tvos-testflight.yml", tv)
                self.assertIn("source_sha: ${{ " + source + " }}", tv)
                self.assertIn("upload: true", tv)
                self.assertIn("secrets: inherit", tv)
                # TV runs its own native tests and cannot bypass them via phone skip_tests.
                self.assertNotIn("skip_tests", tv)
                self.assertNotIn("tvos-release.py review", self.job(text, gate))
                summary = self.job(text, "summary")
                self.assertIn("tvos-testflight]", summary)
                self.assertIn("needs.tvos-testflight.result", summary)
        main = self.job(workflow("release-all.yml"), "create-release")
        for path in (
            "tvosApp/PhoenixTV.xcodeproj/project.pbxproj",
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Constants.kt",
        ):
            self.assertIn(path, main)
        self.assertIn('[ "$TVOS_VERSIONS" != "$ANDROID_VERSION" ]', main)
        self.assertIn('[ "$SHARED_VERSION" != "$ANDROID_VERSION" ]', main)

    def test_existing_release_pins_dispatched_main_before_testing_and_building(self) -> None:
        prepare = self.job(workflow("release-all-existing.yml"), "prepare-release")
        self.assertIn('"${{ github.ref }}" != "refs/heads/main"', prepare)
        self.assertIn("ref: ${{ github.sha }}", prepare)
        self.assertIn("SOURCE_SHA: ${{ github.sha }}", prepare)
        self.assertIn('test "$(git rev-parse HEAD)" = "$SOURCE_SHA"', prepare)
        self.assertIn('--version "${TAG#v}" --check', prepare)
        self.assertNotIn("SKIP_TVOS", prepare)
        tv = self.job(workflow("release-all-existing.yml"), "tvos-testflight")
        self.assertIn("source_sha: ${{ needs.prepare-release.outputs.sha }}", tv)
        self.assertIn("Source SHA: ${{ needs.prepare-release.outputs.sha }}", self.job(workflow("release-all-existing.yml"), "summary"))

    def test_existing_release_prepare_accepts_current_main_and_rejects_version_drift(self) -> None:
        prepare = self.job(workflow("release-all-existing.yml"), "prepare-release")
        step = prepare.split("      - name: Verify existing release and source version\n", 1)[1]
        script = textwrap.dedent(step.split("        run: |\n", 1)[1]).replace(
            "${{ github.repository }}", "example/phoenix"
        )
        bash = shutil.which("bash")
        if os.name == "nt":
            # Local Windows runs need Git Bash rather than the WSL launcher on PATH.
            bash = str(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe")
        if not bash or not Path(bash).is_file():
            self.skipTest("Bash is required for prepare-step regression coverage")
        current_sha = "a" * 40
        cases = [("v1.0.9", drift) for drift in (None, "android", "shared", "ios", "tvos")]
        cases += [("1.0.9", None), ("v1.0.9-rc1", None)]
        for tag, drift in cases:
            with self.subTest(tag=tag, drift=drift), tempfile.TemporaryDirectory() as raw:
                fixture = Path(raw)
                files = {
                    "settings.gradle.kts": "",
                    "androidApp/build.gradle.kts": 'versionName = "1.0.9"\n',
                    "shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Constants.kt": 'const val APP_VERSION = "1.0.9"\n',
                    "iosApp/PhoenixApp/PhoenixApp.xcodeproj/project.pbxproj": "MARKETING_VERSION = 1.0.9;\n" * 2,
                    "tvosApp/PhoenixTV.xcodeproj/project.pbxproj": "MARKETING_VERSION = 1.0.9;\n" * 2,
                }
                if drift:
                    prefix = {"android": "androidApp/", "shared": "shared/", "ios": "iosApp/", "tvos": "tvosApp/"}[drift]
                    files = {path: content.replace("1.0.9", "1.0.10") if path.startswith(prefix) else content
                             for path, content in files.items()}
                for path, content in files.items():
                    target = fixture / path
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_text(content, encoding="utf-8")
                checker = ".agents/skills/update-phoenix-version/scripts/update_version.py"
                (fixture / checker).parent.mkdir(parents=True)
                shutil.copyfile(ROOT / checker, fixture / checker)
                (fixture / "bin").mkdir()
                for name, body in {
                    "gh": 'echo "$*" >> gh-calls\n[ "$1 $2" = "release view" ]\n',
                    "git": 'echo "$*" >> git-calls\n[ "$*" = "rev-parse HEAD" ] || exit 99\necho "$SOURCE_SHA"\n',
                }.items():
                    fake = fixture / "bin" / name
                    fake.write_text("#!/bin/sh\n" + body, encoding="utf-8", newline="\n")
                    fake.chmod(0o755)
                prefix = 'export PATH="$PWD/bin:$PATH"\npython3() { ' + shlex.quote(Path(sys.executable).as_posix()) + ' "$@"; }\n'
                result = subprocess.run(
                    [bash, "-c", prefix + script], cwd=fixture,
                    env=os.environ | {"TAG": tag, "SOURCE_SHA": current_sha, "GITHUB_OUTPUT": "output"},
                    capture_output=True, text=True, check=False,
                )
                if tag != "v1.0.9":
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(f"Invalid release tag '{tag}' (expected vX.Y.Z).", result.stderr)
                    self.assertFalse((fixture / "gh-calls").exists())
                    self.assertFalse((fixture / "git-calls").exists())
                    self.assertFalse((fixture / "output").exists(), "Invalid tags must not unlock downstream jobs")
                else:
                    self.assertEqual((fixture / "gh-calls").read_text().strip(), "release view v1.0.9 --repo example/phoenix")
                    self.assertEqual((fixture / "git-calls").read_text().strip(), "rev-parse HEAD")
                if drift:
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("expected 1.0.9, found 1.0.10", result.stderr)
                    self.assertFalse((fixture / "output").exists(), "Failed validation must not unlock downstream jobs")
                elif tag == "v1.0.9":
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual((fixture / "output").read_text(), f"sha={current_sha}\n")
                for path, content in files.items():
                    self.assertEqual((fixture / path).read_text(), content, "Version validation must never rewrite source")

    def test_mobile_store_condition_truth_tables(self) -> None:
        text = workflow("release-all.yml")
        states = ("success", "failure", "cancelled", "skipped")
        for store, asset, store_skip, asset_skip in (
            ("android-playstore", "android-apk", "skip_android_playstore", "skip_android_apk"),
            ("ios-testflight", "ios-ipa", "skip_ios_testflight", "skip_ios_ipa"),
        ):
            body = self.job(text, store)
            condition = re.search(r"(?m)^    if: \$\{\{ (.*?) \}\}$", body).group(1)
            # Evaluate the actual checked-in expression for every relevant status/input
            # combination, including skipped assets and failure/cancellation blocking.
            for cancelled, skip_store, skip_asset, release, artifact in itertools.product(
                (False, True), (False, True), (False, True), states, states
            ):
                values = {
                    "cancelled()": cancelled,
                    f"inputs.{store_skip}": skip_store,
                    f"inputs.{asset_skip}": skip_asset,
                    "needs.create-release.result": release,
                    f"needs.{asset}.result": artifact,
                }
                expression = condition
                for token, value in values.items():
                    expression = expression.replace(token, repr(value))
                expression = expression.replace("&&", " and ").replace("||", " or ").replace("!", " not ")
                actual = eval(expression.strip(), {"__builtins__": {}}, {})
                expected = (not cancelled and release == "success" and not skip_store and
                            (artifact == "success" or (skip_asset and artifact == "skipped")))
                with self.subTest(store=store, **values):
                    self.assertEqual(actual, expected)

    def test_ios_release_konan_cache_follows_gradle_version_inputs(self) -> None:
        ci_text = workflow("ci-tests.yml")
        ci_keys = re.findall(
            r"(?m)^[ ]+key: \$\{\{ runner\.os \}\}-\$\{\{ runner\.arch \}\}-konan-\$\{\{ hashFiles\([^)]+\) \}\}$",
            ci_text,
        )
        self.assertGreaterEqual(len(ci_keys), 1)
        expected_key = ci_keys[0].strip()
        restore_prefix = "restore-keys: |\n            ${{ runner.os }}-${{ runner.arch }}-konan-\n"
        self.assertEqual(ci_text.count(restore_prefix), len(ci_keys))
        self.assertIn("gradle/libs.versions.toml", expected_key)
        self.assertIn("**/*.gradle*", expected_key)
        self.assertIn("**/gradle-wrapper.properties", expected_key)
        self.assertNotRegex(expected_key, r"\d+\.\d+\.\d+")
        for name in (
            "ios-release-ipa.yml",
            "ios-testflight.yml",
            "ios-testflight-internal.yml",
        ):
            with self.subTest(workflow=name):
                text = workflow(name)
                self.assertIn("path: ~/.konan", text)
                self.assertRegex(text, r"uses: actions/cache@[0-9a-f]{40}")
                self.assertIn(expected_key, text)
                self.assertIn(restore_prefix, text)
                self.assertNotRegex(text, r"konan-\d+\.\d+\.\d+")

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

    def test_internal_testflight_upload_does_not_probe_distribution(self) -> None:
        # The internal workflow uploads with altool and stops. It has no
        # distribution step, so the copied JWT poll (and its "will retry"
        # warning) must not come back. The public workflow still uses that JWT.
        internal = workflow("ios-testflight-internal.yml")
        upload = re.search(
            r"(?ms)^  upload:\n(?P<body>.*?)(?=^  [a-z][a-z0-9-]*:\n|\Z)",
            internal,
        )
        self.assertIsNotNone(upload)
        body = upload.group("body")
        self.assertIn("xcrun altool --upload-package", body)
        self.assertIn("AuthKey_${APPSTORE_API_KEY_ID}.p8", body)
        for absent in (
            "setup-python",
            "pip install cryptography",
            "generate_jwt",
            "sleep 30",
            "distribution step will retry",
            "Verifying upload reached App Store Connect",
        ):
            self.assertNotIn(absent, internal)

        public = workflow("ios-testflight.yml")
        self.assertIn("pip install cryptography", public)
        self.assertIn("generate_jwt", public)
        self.assertIn("distribution step will retry", public)
        self.assertIn("- name: Add build to TestFlight test group", public)

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
        release_all = workflow("release-all.yml")
        self.assertIn("ref: ${{ needs.tests.outputs.sha }}", release_all)
        # The test gate must check out the dispatched SHA, not the moving main branch.
        self.assertIn(
            "    uses: ./.github/workflows/release-tests.yml\n"
            "    with:\n"
            "      ref: ${{ github.sha }}\n",
            release_all,
        )
        self.assertNotIn("ref: main\n", release_all)
        self.assertEqual(
            workflow("release-all-existing.yml").count(
                "source_ref: ${{ needs.prepare-release.outputs.sha }}"
            ),
            4,
        )
        existing = workflow("release-all-existing.yml")
        self.assertIn("ref: ${{ needs.prepare-release.outputs.sha }}", existing)
        self.assertNotIn("gh api", self.job(existing, "prepare-release"))
        self.assertIn('echo "sha=$SOURCE_SHA" >> "$GITHUB_OUTPUT"', existing)
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

    def test_play_version_preflight_is_one_shared_script(self) -> None:
        script = ROOT / ".github" / "scripts" / "resolve-play-version-code.sh"
        self.assertTrue(script.is_file())
        steps = []
        for name in ("android-release-apk.yml", "android-playstore.yml"):
            text = workflow(name)
            start = text.index("      # This workflow may build a historical tag, whose checkout predates the helper.\n")
            end = text.index("      - name: Write Supabase credentials to local.properties")
            step = text[start:end]
            steps.append(step)
            self.assertIn(".github/scripts/resolve-play-version-code.sh", step)
            self.assertIn("${{ github.workflow_sha }}", step)
            self.assertIn("id: version", step)
            self.assertIn("PACKAGE_NAME: com.devil.phoenixproject", step)
            self.assertIn("VERSION_CODE_OVERRIDE: ${{ inputs.versionCodeOverride }}", step)
            self.assertNotIn("androidpublisher.googleapis.com", step)
            self.assertNotIn("PLAY_MAX_VERSION_CODE", step)
            self.assertIn(
                "-Pversion.code=${{ steps.version.outputs.selected_version_code }}",
                text,
            )
        self.assertEqual(steps[0], steps[1])

    def test_release_asset_replacement_is_staged_and_uses_workflow_sha_helper(self) -> None:
        for name in ("android-release-apk.yml", "ios-release-ipa.yml"):
            with self.subTest(workflow=name):
                text = workflow(name)
                self.assertIn("replace_release_asset.py", text)
                self.assertIn("${{ github.workflow_sha }}", text)
                self.assertIn("Stage and promote", text)
                self.assertNotIn("gh release upload", text)
                self.assertNotIn("--clobber", text)

    def test_ci_host_unit_tests_share_one_gradle_invocation(self) -> None:
        text = workflow("ci-tests.yml")
        job = re.search(
            r"(?ms)^  unit-tests:\n(?P<body>.*?)(?=^  [a-z][a-z0-9-]*:\n)", text
        )
        self.assertIsNotNone(job)
        body = job.group("body")
        combined = (
            "run: ./gradlew -Pskip.supabase.check=true "
            ":shared:testAndroidHostTest :androidApp:testDebugUnitTest --continue\n"
        )
        self.assertEqual(body.count(combined), 1)
        self.assertNotIn("Run shared module unit tests", body)
        self.assertNotIn("Run androidApp unit tests", body)
        self.assertNotIn("continue-on-error", body)
        self.assertIn("name: Upload test results\n        if: always()", body)
        self.assertIn("shared/build/test-results/", body)
        self.assertIn("androidApp/build/test-results/", body)
        self.assertIn("check_name: 'Shared Module Test Results'", body)
        self.assertIn("check_name: 'Android App Unit Test Results'", body)

    def test_ci_path_filter_still_runs_for_sealed_backend_handoff_and_qa_docs(self) -> None:
        text = workflow("ci-tests.yml")
        on_block = text.split("permissions:", 1)[0]
        self.assertNotIn("paths-ignore:", on_block)
        sealed_paths = (
            "    paths:\n"
            "      - '**'\n"
            "      - '!**.md'\n"
            "      - '!docs/**'\n"
            "      - 'docs/backend-handoff/**'\n"
            "      - 'docs/qa/**'\n"
        )
        self.assertEqual(on_block.count(sealed_paths), 2)

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
