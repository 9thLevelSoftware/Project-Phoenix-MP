#!/usr/bin/env python3
"""Version helper tests: all writes are confined to temporary fixture repositories."""
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("update_version.py")
IOS = "iosApp/PhoenixApp/PhoenixApp.xcodeproj/project.pbxproj"
TV = "tvosApp/PhoenixTV.xcodeproj/project.pbxproj"
CONSTANTS = "shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Constants.kt"


class VersionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="tvos-version-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        for name, text in {
            "settings.gradle.kts": "// fixture\n",
            "androidApp/build.gradle.kts": 'versionName = "2.3.4"\nversionCode = injectedVersionCode ?: 19\n',
            CONSTANTS: 'const val APP_VERSION = "2.3.4"\n',
            IOS: "\tMARKETING_VERSION = 2.3.4;\n\tCURRENT_PROJECT_VERSION = 123;\n" * 2,
            TV: "\tMARKETING_VERSION = 2.3.4;\n\tCURRENT_PROJECT_VERSION = 123;\n" * 2 + "// preserve worker changes\n",
        }.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)

    def run_helper(self, *args):
        return subprocess.run([sys.executable, str(SCRIPT), "--repo-root", str(self.root),
                               "--version", "2.3.5", *args], capture_output=True, text=True)

    def snapshot(self):
        return {str(p.relative_to(self.root)): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}

    def test_updates_tv_marketing_but_not_build_without_request(self):
        result = self.run_helper()
        self.assertEqual(result.returncode, 0, result.stderr)
        text = (self.root / TV).read_text()
        self.assertEqual(text.count("MARKETING_VERSION = 2.3.5;"), 2)
        self.assertEqual(text.count("CURRENT_PROJECT_VERSION = 123;"), 2)
        self.assertIn("// preserve worker changes", text)

    def test_tv_build_is_independent_of_ios_build(self):
        result = self.run_helper("--tvos-build", "456")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / TV).read_text().count("CURRENT_PROJECT_VERSION = 456;"), 2)
        self.assertEqual((self.root / IOS).read_text().count("CURRENT_PROJECT_VERSION = 123;"), 2)

    def test_ios_build_keeps_existing_semantics(self):
        result = self.run_helper("--ios-build", "789", "--android-code", "20")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / IOS).read_text().count("CURRENT_PROJECT_VERSION = 789;"), 2)
        self.assertEqual((self.root / TV).read_text().count("CURRENT_PROJECT_VERSION = 123;"), 2)

    def test_dry_run_never_writes(self):
        before = self.snapshot()
        result = self.run_helper("--tvos-build", "456", "--dry-run")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(before, self.snapshot())

    def test_check_detects_tv_drift(self):
        self.assertEqual(self.run_helper().returncode, 0)
        path = self.root / TV
        path.write_text(path.read_text().replace("2.3.5", "2.3.4"))
        before = self.snapshot()
        result = self.run_helper("--check")
        self.assertEqual(result.returncode, 1)
        self.assertIn("tvOS MARKETING_VERSION", result.stderr)
        self.assertEqual(before, self.snapshot())

    def test_missing_tv_does_not_partially_write(self):
        (self.root / TV).unlink()
        before = self.snapshot()
        self.assertEqual(self.run_helper().returncode, 1)
        self.assertEqual(before, self.snapshot())

    def test_malformed_tv_does_not_partially_write(self):
        (self.root / TV).write_text("MARKETING_VERSION = 2.3.4;\n")
        before = self.snapshot()
        self.assertEqual(self.run_helper().returncode, 1)
        self.assertEqual(before, self.snapshot())

    def test_invalid_tv_build_rejected_before_writes(self):
        for value in ["0", "-1", "1.2", "abc"]:
            with self.subTest(value=value):
                before = self.snapshot()
                self.assertNotEqual(self.run_helper("--tvos-build", value).returncode, 0)
                self.assertEqual(before, self.snapshot())


if __name__ == "__main__":
    unittest.main()
