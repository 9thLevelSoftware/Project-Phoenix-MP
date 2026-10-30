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
import sys
import tempfile
import unittest
import uuid
import zipfile
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
        self.catalog_fixture = b'[{"id":"offline-tooling-fixture"}]\n'

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
                       {"SUPABASE_ANON_KEY": "sb_secret_" + uuid.uuid4().hex}, {"SUPABASE_ANON_KEY": "placeholder"}]:
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

    def test_source_revision_accepts_exact_checkout_without_review_or_workflow_sha_match(self):
        sha = "a" * 40
        with patch.dict(os.environ, {"TVOS_SOURCE_SHA": sha, "GITHUB_SHA": "b" * 40}, clear=True), \
                patch.object(release, "run", return_value=sha + "\n") as command:
            release.source_revision_guard()
        command.assert_called_once_with(["git", "rev-parse", "HEAD"], "Check source SHA", sensitive=True)

    def test_source_revision_rejects_missing_and_nonimmutable_inputs_before_git(self):
        for sha in (None, "", "main", "v1.0.9", "a" * 39, "A" * 40, "a" * 40 + "; echo unsafe"):
            with self.subTest(sha=sha), \
                    patch.dict(os.environ, {} if sha is None else {"TVOS_SOURCE_SHA": sha}, clear=True), \
                    patch.object(release, "run") as command:
                with self.assertRaises(ValueError):
                    release.source_revision_guard()
                command.assert_not_called()

    def test_source_revision_rejects_a_different_checkout(self):
        with patch.dict(os.environ, {"TVOS_SOURCE_SHA": "a" * 40}, clear=True), \
                patch.object(release, "run", return_value="b" * 40 + "\n"):
            with self.assertRaisesRegex(ValueError, "Checkout differs from selected source SHA"):
                release.source_revision_guard()

    def test_ci_release_checks_source_before_parity_or_production_composition(self):
        with patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}, clear=True), \
                patch.object(release, "source_revision_guard", side_effect=ValueError("source mismatch")) as source, \
                patch.object(release, "read_versions") as versions, \
                patch.object(release, "source_guard") as production:
            with self.assertRaisesRegex(ValueError, "source mismatch"):
                release.ci_release_guard()
            source.assert_called_once_with()
            versions.assert_not_called()
            production.assert_not_called()

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

    def export_ipa_fixture(self, path, *, changes=None, extra_app=False):
        info = {"CFBundleIdentifier": release.BUNDLE, "CFBundleShortVersionString": "2.3.4",
                "CFBundleVersion": "456", "DTPlatformName": "appletvos", "UIDeviceFamily": [3]}
        info.update(changes or {})
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as ipa:
            ipa.writestr("Payload/Phoenix TV.app/Info.plist", plistlib.dumps(info))
            if extra_app:
                ipa.writestr("Payload/Another.app/Info.plist", plistlib.dumps(info))

    def test_export_normalizes_display_name_ipa_after_metadata_validation(self):
        export = self.root / "export"
        export.mkdir()
        source = export / "Phoenix TV.ipa"
        self.export_ipa_fixture(source)
        result = release.normalize_exported_ipa(export, {"marketing": "2.3.4"}, "456")

        self.assertEqual(result, export / "PhoenixTV.ipa")
        self.assertTrue(result.is_file())
        self.assertFalse(source.exists())
        self.assertTrue(zipfile.is_zipfile(result))

    def test_export_preserves_already_stable_ipa_name(self):
        export = self.root / "export"
        export.mkdir()
        stable = export / "PhoenixTV.ipa"
        self.export_ipa_fixture(stable)

        result = release.normalize_exported_ipa(export, {"marketing": "2.3.4"}, "456")

        self.assertEqual(result, stable)
        self.assertTrue(stable.is_file())

    def test_export_normalizes_case_variant_name_on_filesystem(self):
        export = self.root / "export"
        export.mkdir()
        self.export_ipa_fixture(export / "phoenixTV.ipa")

        result = release.normalize_exported_ipa(export, {"marketing": "2.3.4"}, "456")

        self.assertEqual(result, export / "PhoenixTV.ipa")
        self.assertTrue(result.is_file())
        self.assertTrue(zipfile.is_zipfile(result))
        self.assertEqual([path.name for path in export.glob("*.ipa")], ["PhoenixTV.ipa"])

    def test_export_rejects_symlink_ipa_candidate(self):
        export = self.root / "export"
        export.mkdir()
        target = export / "actual.zip"
        self.export_ipa_fixture(target)
        (export / "linked.ipa").symlink_to(target)

        with self.assertRaisesRegex(ValueError, "regular file"):
            release.normalize_exported_ipa(export, {"marketing": "2.3.4"}, "456")

    def test_export_requires_exactly_one_ipa_candidate(self):
        for names in ((), ("one.ipa", "two.ipa")):
            with self.subTest(names=names):
                export = self.root / ("export-" + str(len(names)))
                export.mkdir()
                for name in names:
                    self.export_ipa_fixture(export / name)
                with self.assertRaisesRegex(ValueError, "exactly one"):
                    release.normalize_exported_ipa(export, {"marketing": "2.3.4"}, "456")

    def test_export_rejects_malformed_zip_and_multiple_payload_apps(self):
        export = self.root / "export"
        export.mkdir()
        malformed = export / "broken.ipa"
        malformed.write_bytes(b"not a zip archive")
        with self.assertRaisesRegex(ValueError, "ZIP"):
            release.normalize_exported_ipa(export, {"marketing": "2.3.4"}, "456")

        malformed.unlink()
        with zipfile.ZipFile(malformed, "w", compression=zipfile.ZIP_DEFLATED) as ipa:
            ipa.writestr("Payload/Phoenix TV.app/Info.plist", b'<?xml version="1.0"?><plist><dict>')
        with self.assertRaisesRegex(ValueError, "Info.plist"):
            release.normalize_exported_ipa(export, {"marketing": "2.3.4"}, "456")

        malformed.unlink()
        ambiguous = export / "ambiguous.ipa"
        self.export_ipa_fixture(ambiguous, extra_app=True)
        with self.assertRaisesRegex(ValueError, "exactly one.*app"):
            release.normalize_exported_ipa(export, {"marketing": "2.3.4"}, "456")

    def test_export_rejects_mismatched_payload_identity(self):
        cases = (({"CFBundleIdentifier": "com.example.other"}, "bundle"),
                 ({"DTPlatformName": "iphoneos", "UIDeviceFamily": [1]}, "platform"),
                 ({"CFBundleShortVersionString": "9.9.9"}, "version"),
                 ({"CFBundleVersion": "999"}, "build"))
        for changes, identity in cases:
            with self.subTest(identity=identity):
                export = self.root / ("export-" + identity)
                export.mkdir()
                source = export / "Phoenix TV.ipa"
                self.export_ipa_fixture(source, changes=changes)
                with self.assertRaisesRegex(ValueError, identity):
                    release.normalize_exported_ipa(export, {"marketing": "2.3.4"}, "456")
                self.assertTrue(source.is_file())
                self.assertFalse((export / "PhoenixTV.ipa").exists())

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
        catalog = self.root / release.EXERCISES
        catalog.parent.mkdir(parents=True, exist_ok=True)
        catalog.write_bytes(self.catalog_fixture)
        home = self.root / "home"
        home.mkdir(exist_ok=True)
        buffer = io.StringIO()
        # Controlled env and repo fixtures: no live checkout or real user config.
        with patch.object(release, "ROOT", self.root), patch.object(release, "source_guard"), \
                patch.object(release, "read_versions", return_value={}), \
                patch.dict(os.environ, {"PATH": os.defpath, "HOME": str(home)} | (env or {}), clear=True), \
                patch.object(sys, "argv", argv), patch.object(release, "xcode_command", side_effect=child), \
                contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
            result = release.main()
        return result, buffer.getvalue()

    def test_offline_build_failure_exposes_real_stdout_and_stderr_diagnostics(self):
        command_echo = uuid.uuid4().hex
        result, output = self.offline_cli(
            b"/src/ContentView.swift:42:9: error: cannot find 'bridge' in scope\n"
            b"e: file:///src/Bridge.kt:58:12 Unresolved reference 'snapshot'.\n"
            b"ld: library not found for -lFixture\n"
            b"Execution failed for task ':shared-tvos:linkReleaseFrameworkTvosArm64'.\n"
            b"Command PhaseScriptExecution failed with a nonzero exit code\n"
            b"** BUILD FAILED **\n" +
            f"COMMAND_ECHO_MUST_NOT_APPEAR {command_echo}\n".encode())
        self.assertEqual(result, 65)
        for diagnostic in ("ContentView.swift:42:9: error:", "Unresolved reference 'snapshot'",
                           "ld: library not found", "Execution failed for task",
                           "Command PhaseScriptExecution failed", "fixture stderr diagnostic"):
            self.assertIn(diagnostic, output)
        self.assertNotIn("COMMAND_ECHO_MUST_NOT_APPEAR", output)
        self.assertNotIn(command_echo, output)
        self.assertNotIn("ARGUMENT_CANARY_NEVER_LOG", output)
        self.assertNotIn("Unsigned app:", output)
        self.assertIn("ERROR: Unsigned tvOS build exited 65", output)

    def test_offline_build_diagnostics_redact_inherited_values_before_truncation(self):
        # Generate a synthetic canary; no credential-shaped literal is committed.
        generic = uuid.uuid4().hex
        pem_line = uuid.uuid4().hex
        # A bare random hex pair can occur in boilerplate (e.g. ff in Offline).
        # Keep two-character coverage without making the absence assertion flaky.
        short_secret = "q" + uuid.uuid4().hex[0]
        file_credential = uuid.uuid4().hex
        file_password = uuid.uuid4().hex
        url_password = uuid.uuid4().hex
        ansi_canary = uuid.uuid4().hex
        boundary_canary = "boundary_" + uuid.uuid4().hex * 24
        env = {"P12_PASSWORD": uuid.uuid4().hex, "GH_TOKEN": uuid.uuid4().hex,
               "SUPABASE_ANON_KEY": uuid.uuid4().hex,
               "UNCLASSIFIED_SETTING": generic,
               "APPSTORE_API_KEY": f"BEGIN PRIVATE KEY {pem_line}\n{pem_line}\n{pem_line}",
               "SHORT_SECRET": short_secret, "BOUNDARY_TOKEN": boundary_canary,
               "ANSI_TOKEN": ansi_canary}
        lines = ["error: value=" + value for value in env.values()]
        lines += ["error: " + line for line in env["APPSTORE_API_KEY"].splitlines()]
        boundary_filler = "a" * 480
        lines += ["error: " + boundary_filler + boundary_canary,
                  "error: Authorization: Bearer " + file_credential,
                  "error: password=" + file_password,
                  "error: https://user:" + url_password + "@example.invalid/path",
                  "error: " + ansi_canary[:8] + "\x1b[31m" + ansi_canary[8:] + "\x1b[0m"]
        result, output = self.offline_cli("\n".join(lines).encode(), env=env)
        self.assertEqual(result, 65)
        for value in env.values():
            for fragment in value.splitlines():
                self.assertNotIn(fragment, output)
        exposed_boundary_prefix = boundary_canary[:13]
        for fragment in (file_credential, file_password, url_password, exposed_boundary_prefix,
                         "ARGUMENT_CANARY_NEVER_LOG"):
            self.assertNotIn(fragment, output)
        self.assertIn("[redacted]", output)
        self.assertIn("fixture stderr diagnostic", output)

    def test_offline_build_diagnostics_are_bounded_and_not_workflow_commands(self):
        annotation = uuid.uuid4().hex
        workflow_annotation = uuid.uuid4().hex
        lines = ["::error::" + annotation, "export KEY=RAW_DUMP_CANARY",
                 f"::error::/src/Workflow.swift:12:9: error: {workflow_annotation}",
                 "error: first diagnostic\x1b[31m\x1b[0m\x00"]
        lines += [f"error: diagnostic {index} " + "z" * 2000 for index in range(100)]
        result, output = self.offline_cli("\n".join(lines).encode())
        self.assertEqual(result, 65)
        self.assertIn("first diagnostic", output)
        self.assertNotIn(annotation, output)
        self.assertNotIn("RAW_DUMP_CANARY", output)
        self.assertIn(f"native: ::error::/src/Workflow.swift:12:9: error: {workflow_annotation}", output)
        self.assertNotIn("\x1b", output)
        self.assertNotIn("\x00", output)
        self.assertLess(len(output), 23000)
        self.assertIn("truncated", output)
        self.assertTrue(all(not line.startswith("::") for line in output.splitlines()))

    def test_short_ambient_values_do_not_destroy_diagnostic_anchors(self):
        short_secret = "q" + uuid.uuid4().hex[0]
        result, output = self.offline_cli(
            f"/src/Error1.swift:17:27: error: failure {short_secret}".encode(),
            env={"CI": "1", "SHLVL": "1", "P12_PASSWORD": short_secret})
        self.assertEqual(result, 65)
        diagnostic = next(line for line in output.splitlines() if line.startswith("native: /src/Error1.swift"))
        self.assertIn("native: /src/Error1.swift:17:27: error: failure [redacted]", diagnostic)
        self.assertNotIn(short_secret, diagnostic)

    def test_github_runner_values_preserve_four_digit_diagnostic_anchors(self):
        runner_booleans = {"CI": "true", "GITHUB_ACTIONS": "true",
                           "GITHUB_REF_PROTECTED": "true"}
        boolean_diagnostic = "/src/Error.swift:1234:5678: error: runner values true true true"
        result, output = self.offline_cli(boolean_diagnostic.encode(), env=runner_booleans)
        self.assertEqual(result, 65)
        self.assertIn("native: " + boolean_diagnostic, output)

        result, output = self.offline_cli(
            boolean_diagnostic.encode(), env=runner_booleans | {"SECRET": "true"})
        self.assertEqual(result, 65)
        self.assertIn(
            "native: /src/Error.swift:1234:5678: error: runner values [redacted] [redacted] [redacted]",
            output)
        self.assertNotIn("runner values true true true", output)

        result, output = self.offline_cli(
            b"/src/Error.swift:1234:5678: error: runner value 1234", env={"GITHUB_RUN_NUMBER": "1234"})
        self.assertEqual(result, 65)
        self.assertIn("native: /src/Error.swift:1234:5678: error: runner value 1234", output)

        long_number = "20261026"
        result, output = self.offline_cli(
            f"/src/Error.swift:{long_number}:5678: error: runner value {long_number}".encode(),
            env={"GITHUB_RUN_NUMBER": long_number})
        self.assertEqual(result, 65)
        self.assertIn(
            f"native: /src/Error.swift:{long_number}:5678: error: runner value {long_number}", output)

        # An exact GitHub key does not exempt arbitrary values from canary protection.
        canary = "z" + uuid.uuid4().hex
        result, output = self.offline_cli(
            f"/src/Error.swift:1234:5678: error: invalid runner value {canary}".encode(),
            env={"GITHUB_RUN_NUMBER": canary})
        self.assertEqual(result, 65)
        self.assertNotIn(canary, output)
        self.assertIn("[redacted]", output)

    def test_runner_values_remain_redacted_when_reused_by_secret_named_variables(self):
        cases = (("CI", "true", "SECRET"),
                 ("GITHUB_RUN_NUMBER", "1234", "PASSWORD"))
        for runner_name, value, secret_name in cases:
            with self.subTest(secret_name=secret_name):
                result, output = self.offline_cli(
                    f"/src/Error.swift:6789:5678: error: reused value {value}".encode(),
                    env={runner_name: value, secret_name: value})
                self.assertEqual(result, 65)
                self.assertIn(
                    "native: /src/Error.swift:6789:5678: error: reused value [redacted]", output)
                self.assertNotIn(f"reused value {value}", output)

    def test_credentials_in_supported_local_config_paths_withhold_diagnostics(self):
        cases = (("local.properties", "supabase.anon.key"),
                 ("gradle.properties", "signingPassword"),
                 ("iosApp/PhoenixApp/Config/SupabaseBase.xcconfig", "SUPABASE_ANON_KEY"),
                 ("iosApp/PhoenixApp/Config/Supabase.xcconfig", "SUPABASE_ANON_KEY"),
                 ("home/.gradle/gradle.properties", "signingPassword"),
                 ("home/.xcconfig", "SUPABASE_ANON_KEY"))
        candidates = [self.root / path for path, _ in cases]
        for relative, key in cases:
            with self.subTest(path=relative):
                # Each candidate must independently trigger withholding; a previous
                # iteration must not leave a credential-bearing shadow config behind.
                for candidate in candidates:
                    candidate.unlink(missing_ok=True)
                config = self.root / relative
                config.parent.mkdir(parents=True, exist_ok=True)
                config_value = uuid.uuid4().hex
                config.write_text(f"{key} = {config_value}\n")
                try:
                    result, output = self.offline_cli(
                        f"/src/Config.swift:21:3: error: {config_value}\n".encode())
                    self.assertEqual(result, 65)
                    self.assertNotIn(config_value, output)
                    self.assertIn("credential-bearing local build config detected", output)
                finally:
                    config.unlink(missing_ok=True)

    def test_benign_repo_gradle_properties_do_not_suppress_diagnostics(self):
        (self.root / "gradle.properties").write_text("org.gradle.jvmargs=-Xmx1g\n")
        xconfig = self.root / "iosApp/PhoenixApp/Config/SupabaseBase.xcconfig"
        xconfig.parent.mkdir(parents=True, exist_ok=True)
        xconfig.write_text("SUPABASE_URL =\nSUPABASE_ANON_KEY =\n")
        result, output = self.offline_cli(b"/src/Build.swift:6:2: error: benign-config-check\n")
        self.assertEqual(result, 65)
        self.assertIn("native: /src/Build.swift:6:2: error: benign-config-check", output)
        self.assertNotIn("credential-bearing local build config detected", output)

    def test_offline_build_success_stays_quiet_and_checks_real_resources(self):
        app = self.root / "Build/Products/Release-appletvos/PhoenixTV.app"
        app.mkdir(parents=True)
        (app / "exercises.json").write_bytes(self.catalog_fixture)
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
        output_canary = uuid.uuid4().hex
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer), self.assertRaises(subprocess.CalledProcessError) as caught:
            release.run(["/bin/sh", "-c", f"printf 'error: {output_canary}\\n'; exit 37"], "private fixture", sensitive=True)
        self.assertEqual(caught.exception.returncode, 37)
        self.assertEqual(buffer.getvalue(), "private fixture: exit 37\n")
        self.assertNotIn(output_canary, str(caught.exception))
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
        password = uuid.uuid4().hex
        with self.assertRaises(ValueError) as error:
            release.required_env({"P12_PASSWORD": password}, release.SIGNING)
        self.assertIn("TVOS_PROVISION_PROFILE_BASE64", str(error.exception))
        self.assertNotIn(password, str(error.exception))

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
        matches = re.findall(r"(?ms)^      - (?:name: [^\n]*\n        )?uses: actions/" + re.escape(action) +
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

    def test_archive_build_number_uses_serialized_utc_time_and_keeps_source_validation(self):
        text = self.workflow("tvos-testflight.yml")
        archive = self.job(text, "archive")
        self.assertIn('build_number="$(date -u +%Y%m%d%H%M%S)"', archive)
        self.assertNotIn("GITHUB_RUN_NUMBER", archive)
        self.assertNotIn("GITHUB_RUN_ATTEMPT", archive)
        self.assertRegex(text, r"(?m)^concurrency:\n  group: tvos-testflight\n  cancel-in-progress: false$")
        for job, dependency in (("simulator-tests", "acceptance"), ("archive", "simulator-tests"), ("upload", "archive")):
            body = self.job(text, job)
            self.assertRegex(body, r"(?m)^    needs: " + dependency + r"$")
            self.assertIn("ref: ${{ inputs.source_sha || github.sha }}", body)
        acceptance = self.job(text, "acceptance")
        self.assertIn("ref: ${{ inputs.source_sha || github.sha }}", acceptance)
        self.assertIn("run: python3 .github/scripts/tvos-release.py source", acceptance)
        self.assertIn("python3 .github/scripts/tvos-release.py preflight", acceptance)
        self.assertIn("TVOS_SOURCE_SHA: ${{ inputs.source_sha || github.sha }}", text)
        self.assertEqual(text.count("      source_sha:\n        required: false"), 1)
        self.assertRegex(text, r"source_sha:\n        description: [^\n]+\n        required: false")
        self.assertNotRegex(text, r"TVOS_REVIEW|review_pr:|review_accepted:|pull-requests:|statuses:")

    def test_archive_build_numbers_are_ordered_across_callers_and_archive_retries(self):
        archive = self.job(self.workflow("tvos-testflight.yml"), "archive")
        generator = re.search(r'(?m)^          (build_number=.*)$', archive).group(1)
        # Execute the actual assignment with a controlled date command. All callers
        # deliberately share a run number: GitHub's per-workflow counters can collide.
        program = (
            'set -e\n'
            'date() {\n'
            '  test "$#" -eq 2\n'
            '  test "$1" = "-u"\n'
            '  test "$2" = "+%Y%m%d%H%M%S"\n'
            '  printf "%s\\n" "$FIXTURE_UTC_TIMESTAMP"\n'
            '}\n' + generator + '\nprintf "%s" "$build_number"\n'
        )
        cases = (
            ("tvOS TestFlight beta", "20261029120000", "1"),
            ("Release All Platforms", "20261029120100", "1"),
            ("Release All (Existing)", "20261231235959", "1"),
            ("Release All (Existing)", "20270101000000", "2"),
        )
        numbers = []
        for caller, timestamp, attempt in cases:
            with self.subTest(caller=caller, attempt=attempt):
                env = os.environ | {"GITHUB_WORKFLOW": caller, "GITHUB_RUN_NUMBER": "7",
                                    "GITHUB_RUN_ATTEMPT": attempt, "FIXTURE_UTC_TIMESTAMP": timestamp}
                result = subprocess.run(["bash", "-c", program], env=env, capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, timestamp)
                self.assertRegex(result.stdout, r"^[0-9]{14}$")
                self.assertLessEqual(len(result.stdout), 18)
                numbers.append(int(result.stdout))
        self.assertEqual(numbers, sorted(set(numbers)))

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


    def assert_serial_release_heap(self, job):
        # KGP 2.4.0 runs Kotlin/Native link in the Gradle JVM by default;
        # kotlin.native.jvmArgs only sizes a separate exec-mode JVM.
        for setting in ("-Dorg.gradle.jvmargs=-Xmx10g", "-Dorg.gradle.workers.max=1",
                        "-Dorg.gradle.parallel=false",
                        "-Dorg.gradle.project.kotlin.native.disableCompilerDaemon=false",
                        "-Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process"):
            with self.subTest(setting=setting):
                self.assertIn(setting, job)
        self.assertNotIn("-Dorg.gradle.project.kotlin.native.jvmArgs=-Xmx12g", job)

    def test_unsigned_release_device_job_is_separate_standard_runner_cross_compile(self):
        text = self.workflow("tvos-build.yml")
        device = self.job(text, "native-tv-device")
        self.assertRegex(device, r"(?m)^    runs-on: macos-26-intel$")
        self.assertNotRegex(device, r"(?m)^    needs:")
        self.assertIn("DEVELOPER_DIR: /Applications/Xcode_26.2.app/Contents/Developer", device)
        self.assertIn('test -d "$DEVELOPER_DIR"', device)
        self.assertIn("xcrun --sdk appletvos --show-sdk-version", device)
        self.assertNotIn('test "$(uname -m)" = arm64', device)
        self.assert_serial_release_heap(device)
        self.assertIn("tvos-release.py preflight", device)
        self.assertIn("tvos-tooling-tests.py", device)
        self.assertIn("test_update_version.py", device)
        self.assertIn("tvos-release.py local build --sdk appletvos --configuration Release", device)
        self.assertIn("tvos-device-derived", device)
        self.assertIn("device-build.log", device)
        self.assertIn("CODE_SIGNING_ALLOWED: 'NO'", device)
        self.assertIn("CODE_SIGNING_REQUIRED: 'NO'", device)
        self.assertIn("persist-credentials: false", device)
        self.assertIn('scratch="$RUNNER_TEMP/phoenix-tv-device-tmp"', device)
        self.assertIn("TMPDIR=%s", device)
        self.assertIn("JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=%s", device)
        self.assertIn("pull_request:", text)
        self.assertIn("permissions:", text)
        self.assertRegex(text, r"(?m)^  contents: read$")
        self.assertNotIn("${{ secrets.", text)
        self.assertNotIn("tvosSimulatorArm64Test", device)
        self.assertNotIn("--sdk appletvsimulator", device)
        artifact = self.artifact_step(device, "upload-artifact")
        self.assertIn("if: always()", artifact)
        self.assertIn("${{ runner.temp }}/tvos-evidence/", artifact)
        self.assertIn("${{ runner.temp }}/tvos-device-derived/Logs/", artifact)
        self.assertIn("if-no-files-found: warn", artifact)

    def test_native_tests_and_ui_remain_on_arm64_runner_without_device_release(self):
        text = self.workflow("tvos-build.yml")
        native = self.job(text, "native-tv")
        self.assertRegex(native, r"(?m)^    runs-on: macos-26$")
        self.assertIn('test "$(uname -m)" = arm64', native)
        self.assertIn("-Dorg.gradle.project.kotlin.native.jvmArgs=-Xmx4g", native)
        self.assertIn('scratch="$RUNNER_TEMP/phoenix-tv-tmp"', native)
        self.assertIn("TMPDIR=%s", native)
        self.assertIn("JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=%s", native)
        self.assertIn(":shared-tvos:tvosSimulatorArm64Test", native)
        self.assertIn("tvos-release.py local test --sdk appletvsimulator --configuration Debug", native)
        self.assertNotIn("tvos-release.py local build --sdk appletvos --configuration Release", native)
        self.assertIn("tvos-simulator-derived", native)
        artifact = self.artifact_step(native, "upload-artifact")
        self.assertIn("if: always()", artifact)
        self.assertIn("${{ runner.temp }}/tvos-evidence/", artifact)

    def test_signed_archive_uses_matching_heap_and_keeps_source_signing_and_upload_checks(self):
        text = self.workflow("tvos-testflight.yml")
        archive = self.job(text, "archive")
        self.assertRegex(archive, r"(?m)^    runs-on: macos-26-intel$")
        self.assert_serial_release_heap(archive)
        self.assertIn("sudo xcode-select -s /Applications/Xcode_26.2.app/Contents/Developer", archive)
        self.assertIn("tvos-release.py archive", archive)
        self.assertIn("BUILD_CERTIFICATE_BASE64: ${{ secrets.BUILD_CERTIFICATE_BASE64 }}", archive)
        self.assertIn("TVOS_PROVISION_PROFILE_BASE64: ${{ secrets.TVOS_PROVISION_PROFILE_BASE64 }}", archive)

        acceptance = self.job(text, "acceptance")
        self.assertIn("run: python3 .github/scripts/tvos-release.py source", acceptance)
        self.assertIn("python3 .github/scripts/tvos-release.py preflight", acceptance)
        self.assertIn("ref: ${{ inputs.source_sha || github.sha }}", acceptance)
        self.assertIn("TVOS_SOURCE_SHA: ${{ inputs.source_sha || github.sha }}", text)

        simulator = self.job(text, "simulator-tests")
        self.assertRegex(simulator, r"(?m)^    runs-on: macos-26$")
        self.assertIn('test "$(uname -m)" = arm64', simulator)
        self.assertIn("-Dorg.gradle.project.kotlin.native.jvmArgs=-Xmx4g", simulator)
        upload = self.job(text, "upload")
        self.assertIn("if: ${{ inputs.upload }}", upload)
        self.assertRegex(upload, r"(?m)^    runs-on: macos-15$")
        self.assertIn("needs: archive", upload)
        self.assertIn("tvos-release.py upload", upload)


if __name__ == "__main__":
    unittest.main()
