#!/usr/bin/env python3
"""Offline release-tooling tests. No native compiler, signing or network calls."""
import base64
import contextlib
import datetime as dt
import io
import importlib.util
import json
import os
from pathlib import Path
import plistlib
import re
import subprocess
import uuid
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".github/scripts/tvos-release.py"
SPEC = importlib.util.spec_from_file_location("tvos_release", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class ToolingTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(release, "TV release implementation is missing")
        self.temp = tempfile.TemporaryDirectory(prefix="tvos-tooling-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def versions_fixture(self):
        for path, text in {
            "androidApp/build.gradle.kts": 'versionName = "2.3.4"\nversionCode = injectedVersionCode ?: 19\n',
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Constants.kt": 'const val APP_VERSION = "2.3.4"\n',
            "iosApp/PhoenixApp/PhoenixApp.xcodeproj/project.pbxproj": 'MARKETING_VERSION = 2.3.4;\nCURRENT_PROJECT_VERSION = 123;\n' * 2,
            "tvosApp/PhoenixTV.xcodeproj/project.pbxproj": 'MARKETING_VERSION = 2.3.4;\nCURRENT_PROJECT_VERSION = 123;\n' * 2,
        }.items():
            file = self.root / path
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(text)

    def test_versions_are_derived_not_hardcoded(self):
        self.versions_fixture()
        self.assertEqual(release.read_versions(self.root), {"marketing": "2.3.4", "android_code": "19", "apple_build": "123"})

    def test_version_or_build_drift_fails_closed(self):
        for old, new in [("2.3.4", "2.3.5"), ("123", "124")]:
            self.versions_fixture()
            path = self.root / "tvosApp/PhoenixTV.xcodeproj/project.pbxproj"
            path.write_text(path.read_text().replace(old, new))
            with self.assertRaises(ValueError):
                release.read_versions(self.root)

    def test_missing_or_extra_version_fields_fail(self):
        self.versions_fixture()
        path = self.root / "tvosApp/PhoenixTV.xcodeproj/project.pbxproj"
        path.write_text(path.read_text() + "MARKETING_VERSION = 2.3.4;\n")
        with self.assertRaises(ValueError):
            release.read_versions(self.root)

    def test_cloud_configuration_escapes_url_and_never_prints_values(self):
        env = {"SUPABASE_URL": "https://fixture.supabase.co", "SUPABASE_ANON_KEY": "sb_publishable_fixture"}
        text = release.cloud_config(env, required=True)
        self.assertIn("https:/$()/fixture.supabase.co", text)
        self.assertEqual(release.cloud_config({}, required=False), "SUPABASE_URL =\nSUPABASE_ANON_KEY =\n")

    def test_cloud_rejects_missing_placeholder_injection_and_privileged_key(self):
        env = {"SUPABASE_URL": "https://fixture.supabase.co", "SUPABASE_ANON_KEY": "sb_publishable_fixture"}
        for change in [{"SUPABASE_URL": ""}, {"SUPABASE_ANON_KEY": ""},
                       {"SUPABASE_URL": "http://localhost"}, {"SUPABASE_URL": "$(URL)"},
                       {"SUPABASE_ANON_KEY": "x\nOTHER=bad"}, {"SUPABASE_ANON_KEY": "$(KEY)"},
                       {"SUPABASE_ANON_KEY": "sb_secret_bad"}, {"SUPABASE_ANON_KEY": "placeholder"}]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                release.cloud_config(env | change, required=True)

    def profile_fixture(self):
        return {"UUID": "12345678-1234-1234-1234-123456789abc", "Name": "TV Distribution",
                "Platform": ["tvOS"], "TeamIdentifier": ["TEAM123456"],
                "ExpirationDate": dt.datetime(2099, 1, 1), "DeveloperCertificates": [b"fixture"],
                "Entitlements": {"application-identifier": "TEAM123456." + release.BUNDLE,
                                 "com.apple.developer.team-identifier": "TEAM123456", "get-task-allow": False}}

    def test_profile_requires_exact_tv_app_store_identity(self):
        env = {"TEAM_ID": "TEAM123456", "TVOS_PROVISIONING_PROFILE_NAME": "TV Distribution"}
        self.assertEqual(release.validate_profile(self.profile_fixture(), env), "12345678-1234-1234-1234-123456789abc")
        for change in [{"Platform": ["iOS"]}, {"ProvisionedDevices": ["device"]},
                       {"ProvisionsAllDevices": True}, {"TeamIdentifier": ["OTHER"]},
                       {"Name": "Phone Profile"}, {"ExpirationDate": dt.datetime(2000, 1, 1)}]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                release.validate_profile(self.profile_fixture() | change, env)
        profile = self.profile_fixture()
        profile["Entitlements"]["application-identifier"] = "TEAM123456.*"
        with self.assertRaises(ValueError):
            release.validate_profile(profile, env)

    def review_fixture(self):
        sha = "a" * 40
        pr = {"number": 123, "head": {"sha": sha, "repo": {"full_name": "owner/repo"}},
              "base": {"repo": {"full_name": "owner/repo"}}, "user": {"login": "author"}, "draft": False}
        review = {"id": 1, "state": "APPROVED", "commit_id": sha, "user": {"login": "reviewer"}, "author_association": "MEMBER"}
        return sha, pr, review

    def test_automated_review_attestation_is_exact_trusted_and_revocable(self):
        sha, pr, _ = self.review_fixture()
        status = {"id": 1, "context": "tvOS independent review", "state": "success",
                  "creator": {"login": "owner"},
                  "description": "Independent tvOS review accepted",
                  "target_url": "https://github.com/owner/repo/pull/123#issuecomment-123"}
        receipt = {"sha": sha, "statuses": [status]}
        release.accept_review(pr, [], sha, "owner/repo", receipt)
        bad = [receipt | {"sha": "b" * 40},
               receipt | {"statuses": [status | {"creator": {"login": "stranger"}}]},
               receipt | {"statuses": [status | {"target_url": "https://example.com/review"}]},
               receipt | {"statuses": [status, status | {"id": 2, "state": "failure"}]}]
        for rejected in bad:
            with self.assertRaises(ValueError):
                release.accept_review(pr, [], sha, "owner/repo", rejected)
        _, _, review = self.review_fixture()
        with self.assertRaises(ValueError):
            release.accept_review(pr, [review | {"state": "CHANGES_REQUESTED"}], sha, "owner/repo", receipt)

    def test_review_requires_independent_current_approval(self):
        sha, pr, review = self.review_fixture()
        release.accept_review(pr, [review], sha, "owner/repo")
        for reviews in [[], [review | {"commit_id": "b" * 40}], [review | {"user": {"login": "author"}}],
                        [review | {"author_association": "NONE"}], [review | {"state": "DISMISSED"}],
                        [review, review | {"id": 2, "state": "CHANGES_REQUESTED"}]]:
            with self.subTest(reviews=reviews), self.assertRaises(ValueError):
                release.accept_review(pr, reviews, sha, "owner/repo")

    def test_merge_review_is_bound_to_merged_head(self):
        sha, pr, review = self.review_fixture()
        merge = "b" * 40
        pr |= {"merged": True, "merge_commit_sha": merge}
        release.accept_review(pr, [review], merge, "owner/repo")
        with self.assertRaises(ValueError):
            release.accept_review(pr, [review], "c" * 40, "owner/repo")

    def test_upload_verification_matches_platform_marketing_build_and_app(self):
        data = {"data": [{"id": "tv-build", "attributes": {"version": "456", "processingState": "VALID"},
                          "relationships": {"preReleaseVersion": {"data": {"id": "pre"}}, "app": {"data": {"id": "app"}}}}],
                "included": [{"type": "preReleaseVersions", "id": "pre", "attributes": {"platform": "TV_OS", "version": "2.3.4"}}]}
        self.assertEqual(release.find_build(data, "app", "2.3.4", "456"), ("tv-build", "VALID"))
        data["included"][0]["attributes"]["platform"] = "IOS"
        self.assertIsNone(release.find_build(data, "app", "2.3.4", "456"))

    def test_command_failure_preserves_real_exit(self):
        with self.assertRaises(subprocess.CalledProcessError) as caught:
            release.run(["/bin/sh", "-c", "exit 37"], "fixture failure")
        self.assertEqual(caught.exception.returncode, 37)

    def offline_cli(self, output, *, code=65, action="build", env=None):
        """Replace only the forbidden native executable; run a real child and CLI."""
        def child(sdk, configuration, derived, config, native_action, destination):
            program = (
                "import os, pathlib, stat, sys\n"
                f"config = pathlib.Path({str(config)!r})\n"
                "assert config.read_text() == 'SUPABASE_URL =\\nSUPABASE_ANON_KEY =\\n'\n"
                "assert stat.S_IMODE(config.stat().st_mode) == 0o600\n"
                "assert 'CODE_SIGNING_ALLOWED=NO' in sys.argv\n"
                "assert 'CODE_SIGNING_REQUIRED=NO' in sys.argv\n"
                f"sys.stdout.buffer.write({output!r}); sys.stdout.flush()\n"
                "sys.stderr.write('xcodebuild: error: fixture stderr diagnostic\\n')\n"
                f"sys.exit({code})\n"
            )
            return [sys.executable, "-c", program, "ARGUMENT_CANARY_NEVER_LOG"]

        argv = [str(SCRIPT), "local", action, "--sdk",
                "appletvos" if action == "build" else "appletvsimulator",
                "--configuration", "Release" if action == "build" else "Debug",
                "--derived-data", str(self.root), "--result-bundle", str(self.root / "tests.xcresult")]
        if action == "test":
            argv += ["--simulator", "fixture-udid"]
        buffer = io.StringIO()
        # Controlled env: real user secrets never appear in fixture output.
        with patch.dict(os.environ, {"PATH": os.defpath} | (env or {}), clear=True), \
                patch.object(sys, "argv", argv), patch.object(release, "xcode_command", side_effect=child), \
                contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
            result = release.main()
        return result, buffer.getvalue()

    def test_offline_build_failure_exposes_real_stdout_and_stderr_diagnostics(self):
        result, output = self.offline_cli(
            b"/src/ContentView.swift:42:9: error: cannot find 'bridge' in scope\n"
            b"e: file:///src/Bridge.kt:58:12 Unresolved reference 'snapshot'.\n"
            b"ld: library not found for -lFixture\n"
            b"Execution failed for task ':shared-tvos:linkReleaseFrameworkTvosArm64'.\n"
            b"Command PhaseScriptExecution failed with a nonzero exit code\n"
            b"** BUILD FAILED **\n"
            b"COMMAND_ECHO_MUST_NOT_APPEAR fixture-argument\n")
        self.assertEqual(result, 65)
        for diagnostic in ("ContentView.swift:42:9: error:", "Unresolved reference 'snapshot'",
                           "ld: library not found", "Execution failed for task",
                           "Command PhaseScriptExecution failed", "fixture stderr diagnostic"):
            self.assertIn(diagnostic, output)
        self.assertNotIn("COMMAND_ECHO_MUST_NOT_APPEAR", output)
        self.assertNotIn("ARGUMENT_CANARY_NEVER_LOG", output)
        self.assertNotIn("Unsigned app:", output)
        self.assertIn("ERROR: Unsigned tvOS build exited 65", output)

    def test_offline_build_diagnostics_redact_inherited_values_before_truncation(self):
        env = {"P12_PASSWORD": uuid.uuid4().hex, "GH_TOKEN": "GH_CANARY_NOT_PUBLIC",
               "SUPABASE_ANON_KEY": "CLOUD_CANARY_NOT_PUBLIC",
               "UNCLASSIFIED_SETTING": "ARBITRARY_CANARY_NOT_PUBLIC",
               "APPSTORE_API_KEY": "-----BEGIN PRIVATE KEY-----\nPEM_CANARY_LINE_ONE\nPEM_CANARY_LINE_TWO\n-----END PRIVATE KEY-----",
               "SHORT_SECRET": "xy"}
        lines = ["error: value=" + value for value in env.values()]
        lines += ["error: " + line for line in env["APPSTORE_API_KEY"].splitlines()]
        lines += ["error: " + "a" * 490 + env["GH_TOKEN"],
                  "error: Authorization: Bearer FILE_ONLY_CREDENTIAL_CANARY",
                  "error: password=FILE_ONLY_PASSWORD_CANARY",
                  "error: https://user:FILE_ONLY_URL_CANARY@example.invalid/path",
                  "error: GH_\x1b[31mCANARY_NOT_PUBLIC\x1b[0m"]
        result, output = self.offline_cli("\n".join(lines).encode(), env=env)
        self.assertEqual(result, 65)
        for value in env.values():
            for fragment in value.splitlines():
                self.assertNotIn(fragment, output)
        for fragment in ("FILE_ONLY_CREDENTIAL_CANARY", "FILE_ONLY_PASSWORD_CANARY", "FILE_ONLY_URL_CANARY",
                         "GH_CANARY", "ARGUMENT_CANARY_NEVER_LOG"):
            self.assertNotIn(fragment, output)
        self.assertIn("[redacted]", output)
        self.assertIn("fixture stderr diagnostic", output)

    def test_offline_build_diagnostics_are_bounded_and_not_workflow_commands(self):
        lines = ["::error::ANNOTATION_CANARY", "export KEY=RAW_DUMP_CANARY",
                 "error: first diagnostic\x1b[31m\x1b[0m\x00"]
        lines += [f"error: diagnostic {index} " + "z" * 2000 for index in range(100)]
        result, output = self.offline_cli("\n".join(lines).encode())
        self.assertEqual(result, 65)
        self.assertIn("first diagnostic", output)
        self.assertNotIn("ANNOTATION_CANARY", output)
        self.assertNotIn("RAW_DUMP_CANARY", output)
        self.assertNotIn("\x1b", output)
        self.assertNotIn("\x00", output)
        self.assertLess(len(output), 23000)
        self.assertIn("truncated", output)
        self.assertTrue(all(not line.startswith("::") for line in output.splitlines()))

    def test_offline_build_success_stays_quiet_and_checks_real_resources(self):
        app = self.root / "Build/Products/Release-appletvos/PhoenixTV.app"
        app.mkdir(parents=True)
        (app / "exercises.json").write_bytes((ROOT / release.EXERCISES).read_bytes())
        (app / "PrivacyInfo.xcprivacy").write_bytes(plistlib.dumps(self.privacy_fixture()))
        (app / "Info.plist").write_bytes(plistlib.dumps({"ITSAppUsesNonExemptEncryption": False}))
        result, output = self.offline_cli(b"error: SUCCESS_OUTPUT_CANARY\n", code=0)
        self.assertEqual(result, 0)
        self.assertNotIn("SUCCESS_OUTPUT_CANARY", output)
        self.assertNotIn("fixture stderr diagnostic", output)
        self.assertIn("Unsigned app:", output)
        (app / "exercises.json").unlink()
        result, output = self.offline_cli(b"", code=0)
        self.assertEqual(result, 1)
        self.assertNotIn("Unsigned app:", output)

    def test_offline_failure_without_publishable_lines_keeps_exit_and_withholds_raw_output(self):
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer), self.assertRaises(subprocess.CalledProcessError) as caught:
            release.run([sys.executable, "-c", "print('RAW_OUTPUT_CANARY'); raise SystemExit(71)"],
                        "offline fixture", sensitive=True, offline_build_diagnostics=True, env={"PATH": os.defpath})
        self.assertEqual(caught.exception.returncode, 71)
        self.assertIsNone(caught.exception.output)
        self.assertIn("No publishable diagnostic lines", buffer.getvalue())
        self.assertNotIn("RAW_OUTPUT_CANARY", buffer.getvalue())

    def test_local_test_action_does_not_opt_into_offline_build_diagnostics(self):
        result, output = self.offline_cli(b"error: TEST_ACTION_CANARY\n", action="test")
        self.assertEqual(result, 65)
        self.assertNotIn("TEST_ACTION_CANARY", output)
        self.assertNotIn("fixture stderr diagnostic", output)

    def test_sensitive_command_success_returns_output_without_logging_it(self):
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            result = release.run([sys.executable, "-c", "print('PRIVATE_SUCCESS_CANARY')"],
                                 "private success fixture", sensitive=True)
        self.assertEqual(result, "PRIVATE_SUCCESS_CANARY\n")
        self.assertNotIn("PRIVATE_SUCCESS_CANARY", buffer.getvalue())
        self.assertIn("private success fixture: exit 0", buffer.getvalue())

    def test_framework_phase_selects_release_device_and_serial_gradle(self):
        self.framework_case("appletvos", "Release", "TvosArm64", "release")

    def test_framework_phase_selects_debug_simulator(self):
        self.framework_case("appletvsimulator", "Debug", "TvosSimulatorArm64", "debug")

    def framework_case(self, platform, configuration, task_suffix, folder):
        (self.root / "tvosApp").mkdir()
        target = task_suffix[0].lower() + task_suffix[1:]
        framework = self.root / f"shared-tvos/build/bin/{target}/{folder}Framework/PhoenixTvCore.framework"
        framework.mkdir(parents=True)
        (framework / "PhoenixTvCore").write_text("fixture, not a native binary")
        capture = self.root / "args.json"
        gradle = self.root / "gradlew"
        gradle.write_text('#!/usr/bin/env python3\nimport json,sys\nfrom pathlib import Path\nPath("args.json").write_text(json.dumps(sys.argv[1:]))\n')
        gradle.chmod(0o755)
        env = os.environ | {"SRCROOT": str(self.root / "tvosApp"), "PLATFORM_NAME": platform,
                            "CONFIGURATION": configuration, "BUILT_PRODUCTS_DIR": str(self.root / "Products")}
        result = subprocess.run(["bash", str(ROOT / ".github/scripts/tvos-framework.sh")], env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads(capture.read_text())
        self.assertIn(f":shared-tvos:link{configuration}Framework{task_suffix}", args)
        self.assertIn("--max-workers=1", args)
        self.assertIn("--no-parallel", args)
        self.assertTrue((self.root / "Products/KotlinFrameworks/PhoenixTvCore.framework/PhoenixTvCore").is_file())

    def test_native_failure_exit_survives_cli_and_no_resources_are_claimed(self):
        args = type("Args", (), {"sdk": "appletvsimulator", "action": "build", "configuration": "Debug",
                                "simulator": None, "derived_data": str(self.root), "result_bundle": str(self.root / "tests.xcresult")})()
        with patch.object(release, "source_guard"), patch.object(release, "read_versions", return_value={}), \
                patch.object(release, "run", side_effect=subprocess.CalledProcessError(65, "Unsigned tvOS build")):
            with self.assertRaises(subprocess.CalledProcessError) as caught:
                release.local(args)
        self.assertEqual(caught.exception.returncode, 65)

    def test_local_xcode_commands_are_unsigned_and_resource_check_is_real(self):
        args = type("Args", (), {"sdk": "appletvsimulator", "action": "build", "configuration": "Debug",
                                "simulator": None, "derived_data": str(self.root), "result_bundle": str(self.root / "tests.xcresult")})()
        with patch.object(release, "source_guard"), patch.object(release, "read_versions", return_value={}), \
                patch.object(release, "run") as command:
            with self.assertRaises(FileNotFoundError):
                release.local(args)
        self.assertIn("CODE_SIGNING_ALLOWED=NO", command.call_args.args[0])
        self.assertIn("-xcconfig", command.call_args.args[0])
        self.assertEqual(command.call_args.args[0][command.call_args.args[0].index("-jobs") + 1], "1")

    def privacy_fixture(self):
        # Independent expected TV policy, not copied from the verifier's constants.
        return {"NSPrivacyAccessedAPITypes": [
            {"NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategory" + category,
             "NSPrivacyAccessedAPITypeReasons": [reason]}
            for category, reason in [("UserDefaults", "CA92.1"), ("FileTimestamp", "C617.1"),
                                     ("SystemBootTime", "35F9.1")]
        ]}

    def privacy_app_fixture(self):
        app = self.root / "PhoenixTV.app"
        app.mkdir()
        catalog = self.root / release.EXERCISES
        catalog.parent.mkdir(parents=True)
        catalog.write_bytes(b'[{"id":"fixture"}]')
        (app / "exercises.json").write_bytes(catalog.read_bytes())
        info = {"CFBundleIdentifier": release.BUNDLE, "CFBundleShortVersionString": "2.3.4",
                "CFBundleVersion": "456", "MinimumOSVersion": "17.0", "UIDeviceFamily": [3],
                "DTPlatformName": "appletvos", "ITSAppUsesNonExemptEncryption": False,
                "SUPABASE_URL": "https://fixture.supabase.co",
                "SUPABASE_ANON_KEY": "sb_publishable_fixture"}
        (app / "Info.plist").write_bytes(plistlib.dumps(info))
        (app / "PhoenixTV").write_bytes(b"offline fixture, not a compiled executable")
        for context in (patch.object(release, "ROOT", self.root),
                        patch.dict(os.environ, {key: info[key] for key in ("SUPABASE_URL", "SUPABASE_ANON_KEY")})):
            context.start()
            self.addCleanup(context.stop)
        return app

    def test_tv_source_declares_only_exempt_encryption(self):
        info = plistlib.loads((ROOT / "tvosApp/PhoenixTV/Info.plist").read_bytes())
        self.assertIs(info.get("ITSAppUsesNonExemptEncryption"), False)

    def test_verify_app_requires_boolean_false_export_compliance_local_and_archive(self):
        app = self.privacy_app_fixture()
        (app / "PrivacyInfo.xcprivacy").write_bytes(plistlib.dumps(self.privacy_fixture()))
        info = plistlib.loads((app / "Info.plist").read_bytes())
        for fmt in (plistlib.FMT_XML, plistlib.FMT_BINARY):
            for value in (None, True, "false", 0, False):
                candidate = dict(info)
                if value is None:
                    candidate.pop("ITSAppUsesNonExemptEncryption")
                else:
                    candidate["ITSAppUsesNonExemptEncryption"] = value
                (app / "Info.plist").write_bytes(plistlib.dumps(candidate, fmt=fmt))
                for production in (False, True):
                    with self.subTest(fmt=fmt, value=value, production=production):
                        if value is False:
                            release.verify_app(app, {"marketing": "2.3.4"}, "456", production=production)
                        else:
                            with self.assertRaisesRegex(ValueError, "ITSAppUsesNonExemptEncryption"):
                                release.verify_app(app, {"marketing": "2.3.4"}, "456", production=production)

    def test_local_runner_checks_built_export_compliance_after_native_command(self):
        app = self.privacy_app_fixture()
        (app / "PrivacyInfo.xcprivacy").write_bytes(plistlib.dumps(self.privacy_fixture()))
        for sdk, configuration in (("appletvsimulator", "Debug"), ("appletvos", "Release")):
            destination = self.root / f"Build/Products/{configuration}-{sdk}/PhoenixTV.app"
            destination.parent.mkdir(parents=True)
            app = app.rename(destination)
            args = type("Args", (), {"sdk": sdk, "action": "build", "configuration": configuration,
                                    "simulator": None, "derived_data": str(self.root),
                                    "result_bundle": str(self.root / "tests.xcresult")})()
            with patch.object(release, "source_guard"), patch.object(release, "read_versions", return_value={}), \
                    patch.object(release, "run"), contextlib.redirect_stdout(io.StringIO()):
                for value in (None, True, "false", 0, False):
                    info = {} if value is None else {"ITSAppUsesNonExemptEncryption": value}
                    (app / "Info.plist").write_bytes(plistlib.dumps(info))
                    with self.subTest(sdk=sdk, value=value):
                        if value is False:
                            release.local(args)
                        else:
                            with self.assertRaisesRegex(ValueError, "ITSAppUsesNonExemptEncryption"):
                                release.local(args)

    def test_verify_app_rejects_missing_privacy_manifest_local_and_archive(self):
        app = self.privacy_app_fixture()
        for production in (False, True):
            with self.subTest(production=production), self.assertRaisesRegex(ValueError, "PrivacyInfo.xcprivacy"):
                release.verify_app(app, {"marketing": "2.3.4"}, "456", production=production)

    def test_verify_app_rejects_privacy_manifest_only_in_static_framework(self):
        app = self.privacy_app_fixture()
        nested = app / "Frameworks/PhoenixTvCore.framework/PrivacyInfo.xcprivacy"
        nested.parent.mkdir(parents=True)
        nested.write_bytes(plistlib.dumps(self.privacy_fixture()))
        with self.assertRaisesRegex(ValueError, "PrivacyInfo.xcprivacy"):
            release.verify_app(app, {"marketing": "2.3.4"}, "456", production=False)

    def test_verify_app_rejects_unparseable_privacy_manifest(self):
        app = self.privacy_app_fixture()
        for data in (b"not a plist", b'<?xml version="1.0"?><plist><dict>'):
            (app / "PrivacyInfo.xcprivacy").write_bytes(data)
            for production in (False, True):
                with self.subTest(data=data, production=production), self.assertRaisesRegex(ValueError, "PrivacyInfo.xcprivacy"):
                    release.verify_app(app, {"marketing": "2.3.4"}, "456", production=production)

    def test_verify_app_rejects_invalid_privacy_shape_categories_and_reasons(self):
        app = self.privacy_app_fixture()
        valid = self.privacy_fixture()["NSPrivacyAccessedAPITypes"]
        invalid = [[], {}, {"NSPrivacyAccessedAPITypes": {}}, {"NSPrivacyAccessedAPITypes": []},
                   {"NSPrivacyAccessedAPITypes": ["not a dictionary"]}]
        for index in range(len(valid)):
            invalid.append({"NSPrivacyAccessedAPITypes": valid[:index] + valid[index + 1:]})
        for change in ({"NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryDiskSpace"},
                       {"NSPrivacyAccessedAPIType": []}, {"NSPrivacyAccessedAPITypeReasons": []},
                       {"NSPrivacyAccessedAPITypeReasons": "CA92.1"},
                       {"NSPrivacyAccessedAPITypeReasons": [1]},
                       {"NSPrivacyAccessedAPITypeReasons": ["C617.1"]},
                       {"NSPrivacyAccessedAPITypeReasons": ["CA92.1", "ZZZZ.1"]},
                       {"NSPrivacyAccessedAPITypeReasons": ["CA92.1", "CA92.1"]}):
            invalid.append({"NSPrivacyAccessedAPITypes": [valid[0] | change, *valid[1:]]})
        invalid.append({"NSPrivacyAccessedAPITypes": valid + [valid[0]]})
        for manifest in invalid:
            (app / "PrivacyInfo.xcprivacy").write_bytes(plistlib.dumps(manifest))
            for production in (False, True):
                with self.subTest(manifest=manifest, production=production), self.assertRaisesRegex(ValueError, "PrivacyInfo.xcprivacy"):
                    release.verify_app(app, {"marketing": "2.3.4"}, "456", production=production)

    def test_verify_app_accepts_valid_xml_and_binary_privacy_local_and_archive(self):
        app = self.privacy_app_fixture()
        for fmt in (plistlib.FMT_XML, plistlib.FMT_BINARY):
            (app / "PrivacyInfo.xcprivacy").write_bytes(plistlib.dumps(self.privacy_fixture(), fmt=fmt))
            for production in (False, True):
                with self.subTest(fmt=fmt, production=production):
                    release.verify_app(app, {"marketing": "2.3.4"}, "456", production=production)

    def test_local_runner_validates_privacy_after_native_command(self):
        app = self.root / "Build/Products/Debug-appletvsimulator/PhoenixTV.app"
        app.mkdir(parents=True)
        (app / "exercises.json").write_bytes((ROOT / release.EXERCISES).read_bytes())
        (app / "Info.plist").write_bytes(plistlib.dumps({"ITSAppUsesNonExemptEncryption": False}))
        args = type("Args", (), {"sdk": "appletvsimulator", "action": "build", "configuration": "Debug",
                                "simulator": None, "derived_data": str(self.root), "result_bundle": str(self.root / "tests.xcresult")})()
        with patch.object(release, "source_guard"), patch.object(release, "read_versions", return_value={}), \
                patch.object(release, "run"), contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(ValueError, "PrivacyInfo.xcprivacy"):
                release.local(args)
            (app / "PrivacyInfo.xcprivacy").write_bytes(plistlib.dumps(self.privacy_fixture()))
            release.local(args)

    @unittest.skipUnless(Path("/usr/bin/plutil").is_file(), "Apple plutil is required to parse the PBX project")
    def test_tv_privacy_source_is_registered_in_application_resources(self):
        source = ROOT / "tvosApp/PhoenixTV/PrivacyInfo.xcprivacy"
        self.assertTrue(source.is_file(), "TV privacy source is missing")
        self.assertEqual(plistlib.loads(source.read_bytes()), self.privacy_fixture())
        project = json.loads(subprocess.check_output([
            "/usr/bin/plutil", "-convert", "json", "-o", "-", str(ROOT / release.TV_PROJECT)]))
        objects = project["objects"]
        app = next(obj for obj in objects.values() if obj.get("isa") == "PBXNativeTarget"
                   and obj.get("name") == "PhoenixTV")
        self.assertEqual(app["productType"], "com.apple.product-type.application")
        resources = [objects[phase] for phase in app["buildPhases"]
                     if objects[phase]["isa"] == "PBXResourcesBuildPhase"]
        refs = [objects[build]["fileRef"] for phase in resources for build in phase["files"]]
        privacy_refs = [ref for ref in refs if objects[ref].get("path") == "PrivacyInfo.xcprivacy"]
        self.assertEqual(len(privacy_refs), 1, "Manifest must be in the actual app Resources phase exactly once")
        group = next(obj for obj in objects.values() if obj.get("isa") == "PBXGroup"
                     and obj.get("path") == "PhoenixTV")
        self.assertIn(privacy_refs[0], group["children"])
        self.assertEqual(objects[privacy_refs[0]]["sourceTree"], "<group>")

    def test_production_bundle_rejects_fake_telemetry_and_bad_resources(self):
        self.versions_fixture()
        app = self.root / "PhoenixTV.app"
        app.mkdir()
        catalog = self.root / release.EXERCISES
        catalog.parent.mkdir(parents=True)
        catalog.write_bytes(b'[{"id":"fixture"}]')
        (app / "exercises.json").write_bytes(catalog.read_bytes())
        info = {"CFBundleIdentifier": release.BUNDLE, "CFBundleShortVersionString": "2.3.4", "CFBundleVersion": "456",
                "MinimumOSVersion": "17.0", "UIDeviceFamily": [3], "DTPlatformName": "appletvos",
                "ITSAppUsesNonExemptEncryption": False,
                "SUPABASE_URL": "https://fixture.supabase.co", "SUPABASE_ANON_KEY": "sb_publishable_fixture"}
        (app / "Info.plist").write_bytes(plistlib.dumps(info))
        (app / "PhoenixTV").write_bytes(b"real transport fixture")
        (app / "PrivacyInfo.xcprivacy").write_bytes(plistlib.dumps(self.privacy_fixture()))
        with patch.object(release, "ROOT", self.root), patch.dict(os.environ, {k: info[k] for k in ("SUPABASE_URL", "SUPABASE_ANON_KEY")}):
            release.verify_app(app, {"marketing": "2.3.4"}, "456", production=True)
            (app / "PhoenixTV").write_bytes(b"PhantomBleRepository")
            with self.assertRaises(ValueError):
                release.verify_app(app, {"marketing": "2.3.4"}, "456", production=True)
            (app / "PhoenixTV").write_bytes(b"real transport fixture")
            (app / "exercises.json").write_bytes(b"[]")
            with self.assertRaises(ValueError):
                release.verify_app(app, {"marketing": "2.3.4"}, "456", production=True)

    def test_framework_preserves_gradle_failure_without_copying_stale_framework(self):
        (self.root / "tvosApp").mkdir()
        gradle = self.root / "gradlew"
        gradle.write_text("#!/bin/bash\nexit 42\n")
        gradle.chmod(0o755)
        env = os.environ | {"SRCROOT": str(self.root / "tvosApp"), "PLATFORM_NAME": "appletvos",
                            "CONFIGURATION": "Release", "BUILT_PRODUCTS_DIR": str(self.root / "Products")}
        result = subprocess.run(["bash", str(ROOT / ".github/scripts/tvos-framework.sh")], env=env, capture_output=True)
        self.assertEqual(result.returncode, 42)
        self.assertFalse((self.root / "Products").exists())

    def test_xcode_phase_is_configuration_aware_and_has_no_inline_debug_fallback(self):
        project = (ROOT / release.TV_PROJECT).read_text()
        self.assertIn("tvos-framework.sh", project)
        self.assertNotIn("TASK=:shared-tvos:linkDebugFramework", project)

    def test_ci_commands_refuse_local_credentials(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(ValueError):
                release.ci_release_guard()

    def test_sensitive_command_output_is_not_logged_even_on_failure(self):
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer), self.assertRaises(subprocess.CalledProcessError) as caught:
            release.run(["/bin/sh", "-c", "printf 'error: TEST_SECRET_SENTINEL\\n'; exit 37"], "private fixture", sensitive=True)
        self.assertEqual(caught.exception.returncode, 37)
        self.assertEqual(buffer.getvalue(), "private fixture: exit 37\n")
        self.assertNotIn("TEST_SECRET_SENTINEL", str(caught.exception))
        self.assertIsNone(caught.exception.output)
        self.assertIsNone(caught.exception.stderr)

    def test_anon_jwt_allowed_but_service_role_rejected(self):
        for role in ["anon", "service_role"]:
            payload = base64.urlsafe_b64encode(json.dumps({"role": role}).encode()).decode().rstrip("=")
            env = {"SUPABASE_URL": "https://fixture.supabase.co", "SUPABASE_ANON_KEY": "eyJ.test.sig".replace("test", payload)}
            if role == "anon":
                self.assertIn(env["SUPABASE_ANON_KEY"], release.cloud_config(env, required=True))
            else:
                with self.assertRaises(ValueError):
                    release.cloud_config(env, required=True)

    def test_missing_signing_config_names_fields_not_values(self):
        with self.assertRaises(ValueError) as error:
            release.required_env({"P12_PASSWORD": "TEST_SECRET_SENTINEL"}, release.SIGNING)
        self.assertIn("TVOS_PROVISION_PROFILE_BASE64", str(error.exception))
        self.assertNotIn("TEST_SECRET_SENTINEL", str(error.exception))

    def test_production_source_guard_rejects_fake_factory(self):
        paths = [release.TV_PROJECT, release.EXERCISES, Path("shared-tvos/build.gradle.kts"),
                 Path("shared-tvos/src/tvosArm64Main/kotlin/com/devil/phoenixproject/di/TvosBleRepositoryFactory.tvosArm64.kt"),
                 Path("shared-tvos/src/tvosSimulatorArm64Main/kotlin/com/devil/phoenixproject/di/TvosBleRepositoryFactory.tvosSimulatorArm64.kt")]
        for relative in paths:
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes((ROOT / relative).read_bytes())
        release.source_guard(self.root)
        factory = self.root / paths[3]
        factory.write_text(factory.read_text().replace("= KableBleRepository()", "= PhantomBleRepository()"))
        with self.assertRaises(ValueError):
            release.source_guard(self.root)

    def test_simulator_selection_rejects_missing_tv_runtime(self):
        path = ROOT / ".github/scripts/tvos-simulator.py"
        self.assertTrue(path.exists(), "CI simulator lifecycle helper is missing")
        spec = importlib.util.spec_from_file_location("tvos_simulator", path)
        assert spec is not None and spec.loader is not None
        sim = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(sim)
        with self.assertRaises(ValueError):
            sim.select_runtime({"runtimes": [{"identifier": "iOS", "isAvailable": True, "version": "26.2"}]})
        data = {"runtimes": [{"identifier": "com.apple.CoreSimulator.SimRuntime.tvOS-26-2", "isAvailable": True,
                               "version": "26.2", "supportedDeviceTypes": [{"identifier": "Apple-TV-4K", "name": "Apple TV 4K"}]}]}
        self.assertEqual(sim.select_runtime(data), ("com.apple.CoreSimulator.SimRuntime.tvOS-26-2", "Apple-TV-4K"))

    def test_framework_rejects_unknown_platform_before_gradle(self):
        env = os.environ | {"SRCROOT": str(self.root), "PLATFORM_NAME": "iphoneos", "CONFIGURATION": "Release"}
        result = subprocess.run(["bash", str(ROOT / ".github/scripts/tvos-framework.sh")], env=env, capture_output=True)
        self.assertNotEqual(result.returncode, 0)


class WorkflowContractTests(unittest.TestCase):
    def workflow(self, name):
        return (ROOT / ".github/workflows" / name).read_text()

    def job(self, text, name):
        match = re.search(r"(?ms)^  " + re.escape(name) + r":\n(.*?)(?=^  [a-z][a-z0-9-]*:\n|\Z)", text)
        assert match is not None, f"Missing workflow job: {name}"
        return match.group(1)

    def artifact_step(self, job, action):
        matches = re.findall(r"(?ms)^      - uses: actions/" + re.escape(action) +
                             r"@[0-9a-f]{40}[^\n]*\n(.*?)(?=^      - |\Z)", job)
        self.assertEqual(len(matches), 1, f"Expected exactly one {action} step in job")
        return matches[0]

    def artifact_names(self):
        text = self.workflow("tvos-testflight.yml")
        names = []
        for job, action in (("archive", "upload-artifact"), ("upload", "download-artifact")):
            step = self.artifact_step(self.job(text, job), action)
            matches = re.findall(r"(?m)^          name: (.+)$", step)
            self.assertEqual(len(matches), 1)
            names.append(matches[0])
        return names

    def test_archive_and_download_use_exact_run_stable_ipa_name(self):
        for job, name in zip(("archive", "upload"), self.artifact_names()):
            with self.subTest(job=job):
                self.assertEqual(name, "tvos-testflight-ipa-${{ github.run_id }}")

    def test_upload_only_rerun_finds_original_archive_artifact(self):
        archive, download = self.artifact_names()
        # Render the actual workflow expressions for a retained attempt-1 archive
        # and an attempt-2 uploader; equality in a single attempt misses this bug.
        def render(name, attempt):
            return name.replace("${{ github.run_id }}", "123456").replace("${{ github.run_attempt }}", str(attempt))
        self.assertEqual(render(archive, 1), render(download, 2))
        self.assertEqual(render(archive, 2), render(download, 2))

    def test_rerun_archive_overwrites_only_the_ipa_artifact(self):
        archive = self.job(self.workflow("tvos-testflight.yml"), "archive")
        step = self.artifact_step(archive, "upload-artifact")
        self.assertRegex(step, r"(?m)^          overwrite: true$")
        self.assertIn("${{ runner.temp }}/tvos-release/export/PhoenixTV.ipa", step)
        self.assertIn("${{ runner.temp }}/tvos-release/export/release.json", step)
        self.assertRegex(step, r"(?m)^          if-no-files-found: error$")

    def test_archive_build_number_keeps_attempt_uniqueness_and_review_gates(self):
        text = self.workflow("tvos-testflight.yml")
        archive = self.job(text, "archive")
        self.assertIn('test "$GITHUB_RUN_ATTEMPT" -lt 1000', archive)
        self.assertIn('${GITHUB_RUN_NUMBER}$(printf \'%03d\' "$GITHUB_RUN_ATTEMPT")', archive)
        for job, dependency in (("simulator-tests", "acceptance"), ("archive", "simulator-tests"), ("upload", "archive")):
            body = self.job(text, job)
            self.assertRegex(body, r"(?m)^    needs: " + dependency + r"$")
            self.assertIn("ref: ${{ github.sha }}", body)
        acceptance = self.job(text, "acceptance")
        self.assertIn("ref: ${{ github.sha }}", acceptance)
        self.assertIn("run: python3 .github/scripts/tvos-release.py review", acceptance)
        self.assertIn("python3 .github/scripts/tvos-release.py preflight", acceptance)
        self.assertIn("TVOS_SOURCE_SHA: ${{ inputs.source_sha }}", text)
        self.assertIn("TVOS_REVIEW_ACCEPTED: ${{ inputs.review_accepted }}", text)

    def test_unsigned_pr_paths_include_version_helper_changes(self):
        text = self.workflow("tvos-build.yml")
        trigger = re.search(r"(?ms)^  pull_request:\n(.*?)(?=^  [a-z_]+:|\Z)", text)
        assert trigger is not None, "Missing pull_request trigger"
        paths = re.findall(r"(?m)^      - '([^']+)'$", trigger.group(1))
        self.assertIn(".agents/skills/update-phoenix-version/**", paths)

    def test_unsigned_offline_step_runs_both_python_suites_fail_closed(self):
        native = self.job(self.workflow("tvos-build.yml"), "native-tv")
        step = re.search(r"(?ms)^      - name: Offline tooling checks[^\n]*\n(.*?)(?=^      - |\Z)", native)
        assert step is not None, "Missing unsigned offline-check step"
        body = step.group(1)
        self.assertIn("set -euo pipefail", body)
        self.assertNotIn("continue-on-error", body)
        for script, log in ((".github/scripts/tvos-tooling-tests.py", "tooling-tests.log"),
                            (".agents/skills/update-phoenix-version/scripts/test_update_version.py", "version-tests.log")):
            with self.subTest(script=script):
                self.assertRegex(body, r"(?m)^          python3 " + re.escape(script) +
                                 r' 2>&1 \| tee "\$RUNNER_TEMP/tvos-evidence/' + re.escape(log) + r'"$')


if __name__ == "__main__":
    unittest.main()
