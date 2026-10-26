#!/usr/bin/env python3
"""Standalone tvOS build/release tooling. Python 3.10+; no credentials needed for tests.

Native commands run only via local/archive/upload subcommands, never preflight/tests.
Release commands are CI-only and require an independently approved exact source SHA.
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import json
import os
from pathlib import Path
import plistlib
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import zipfile
import zlib
from xml.parsers.expat import ExpatError

ROOT = Path(__file__).resolve().parents[2]
BUNDLE = "com.devil.phoenixproject.projectphoenix"
TV_PROJECT = Path("tvosApp/PhoenixTV.xcodeproj/project.pbxproj")
IOS_PROJECT = Path("iosApp/PhoenixApp/PhoenixApp.xcodeproj/project.pbxproj")
EXERCISES = Path("shared/src/commonMain/composeResources/files/exercises.json")
# TV-only required-reason policy; usage/rationale and Apple references: tvosApp/README.md.
PRIVACY_REASONS = {
    "NSPrivacyAccessedAPICategoryUserDefaults": {"CA92.1"},
    "NSPrivacyAccessedAPICategoryFileTimestamp": {"C617.1"},
    "NSPrivacyAccessedAPICategorySystemBootTime": {"35F9.1"},
}
SIGNING = ("BUILD_CERTIFICATE_BASE64", "P12_PASSWORD", "KEYCHAIN_PASSWORD", "TEAM_ID",
           "TVOS_PROVISION_PROFILE_BASE64", "TVOS_PROVISIONING_PROFILE_NAME")
ASC = ("APPSTORE_API_KEY", "APPSTORE_API_KEY_ID", "APPSTORE_ISSUER_ID", "APP_APPLE_ID")
SENSITIVE_SETTING_NAME = re.compile(
    r"pass(?:word|wd|phrase)|secret|token|authorization|credential|private[._ -]?key|api[._ -]?key|supabase", re.I)
NON_SENSITIVE_RUNNER_VALUES = {
    "CI": re.compile(r"(?:true|false)"),
    "GITHUB_ACTIONS": re.compile(r"(?:true|false)"),
    "GITHUB_REF_PROTECTED": re.compile(r"(?:true|false)"),
    "GITHUB_RUN_NUMBER": re.compile(r"[0-9]+"),
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def required_env(env, names):
    missing = [name for name in names if not env.get(name, "").strip()]
    require(not missing, "Missing configuration: " + ", ".join(missing))


def print_offline_build_diagnostics(output, env):
    """Lossy public excerpt for unsigned/offline builds only; never a raw log.

    Redact all values from credential-named variables (even short or multiline values)
    and generic inherited values of at least four characters. Only exact boolean CI /
    GITHUB_ACTIONS / GITHUB_REF_PROTECTED settings and numeric GITHUB_RUN_NUMBER values
    are excluded from that generic rule to preserve ordinary diagnostic text and anchors. A
    value also present under a credential-named variable remains redacted. Bare credential
    keywords still suppress their full line as a deliberate privacy tradeoff; no general secret
    parser is claimed.
    Known credential-bearing local config files suppress this optional excerpt in run().
    """
    def clean(text):
        text = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", text)
        return re.sub(r"[\x00-\x08\x0b-\x1f\x7f]", "", text)

    values = set()
    for name, value in env.items():
        sensitive_name = SENSITIVE_SETTING_NAME.search(name) is not None
        if not value or (len(value) < 4 and not sensitive_name):
            continue
        runner_pattern = NON_SENSITIVE_RUNNER_VALUES.get(name)
        if not sensitive_name and runner_pattern and runner_pattern.fullmatch(value):
            continue
        values.update(clean(part) for part in (value, *value.splitlines()))
    values.discard("")
    redactions = re.compile("|".join(re.escape(value) for value in sorted(values, key=len, reverse=True))) if values else None
    diagnostic = re.compile(
        r"^(?:.*\.(?:swift|kt|m|mm|c|h|cpp):\d+(?::\d+)?: (?:fatal )?error:|"
        r"e: |(?:xcodebuild|clang): error:|ld: |error: |Undefined symbols for architecture |"
        r"Execution failed for task |FAILURE: Build failed|Caused by: |"
        r"Command \w+ failed with a nonzero exit code|\*\* BUILD FAILED \*\*)")
    credential = re.compile(
        r"password|passwd|secret|token|authorization|private[ _-]?key|https?://\S+@|"
        r"eyJ[\w-]+\.[\w-]+\.[\w-]+|sb_(?:secret|publishable)_", re.I)
    lines = []
    truncated = False
    for line in clean(output).splitlines():
        line = line.strip()
        if not diagnostic.match(line):
            continue
        if len(lines) == 40:
            truncated = True
            break
        if credential.search(line):
            line = "[redacted credential-like diagnostic]"
        elif redactions:
            line = redactions.sub("[redacted]", line)
        if len(line) > 500:
            line = line[:500] + " [truncated]"
            truncated = True
        lines.append(line)
    print("Offline native failure diagnostics (sanitized; other output withheld):", flush=True)
    for line in lines:
        print("native: " + line, flush=True)  # Never emit GitHub workflow commands.
    if not lines:
        print("native: No publishable diagnostic lines; raw output withheld.", flush=True)
    if truncated:
        print("native: Diagnostic excerpt truncated (40 lines / 500 characters per line).", flush=True)


def credential_bearing_local_config(root):
    """Check explicit known build-config files, including repo Supabase.xcconfig.

    Never return or print config values; do not recursively scan the repository.
    """
    root = Path(root)
    candidates = (
        root / "local.properties",
        root / "gradle.properties",
        root / "iosApp/PhoenixApp/Config/SupabaseBase.xcconfig",
        root / "iosApp/PhoenixApp/Config/Supabase.xcconfig",
        Path.home() / ".gradle/gradle.properties",
        Path.home() / ".xcconfig",
    )
    for path in candidates:
        try:
            lines = path.read_text(errors="replace").splitlines()
        except FileNotFoundError:
            continue
        except OSError:
            # If a known local config exists but cannot be inspected, withhold excerpts.
            if path.exists():
                return True
            continue
        for line in lines:
            setting = line.strip()
            if not setting or setting.startswith(("#", ";", "//")):
                continue
            match = re.match(r"([^:=]+?)\s*[:=]\s*(.*)$", setting)
            if match and match.group(2).strip() and SENSITIVE_SETTING_NAME.search(match.group(1)):
                return True
    return False


def run(command, label, *, sensitive=False, env=None, cwd=ROOT, offline_build_diagnostics=False):
    """Never print command arguments: signing passwords and paths can be sensitive."""
    result = subprocess.run([str(part) for part in command], cwd=cwd, env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if not sensitive and result.stdout:
        print(result.stdout, end="", flush=True)
    elif result.returncode and offline_build_diagnostics:
        if credential_bearing_local_config(cwd):
            print("Offline native diagnostics withheld (credential-bearing local build config detected).", flush=True)
        else:
            print_offline_build_diagnostics(result.stdout, os.environ if env is None else env)
    print(f"{label}: exit {result.returncode}", flush=True)
    if result.returncode:
        raise subprocess.CalledProcessError(result.returncode, label)
    return result.stdout


def fields(root, path, pattern, count):
    values = re.findall(pattern, (root / path).read_text(), flags=re.MULTILINE)
    require(len(values) == count, f"Expected {count} version fields in {path}; found {len(values)}")
    return values


def read_versions(root):
    android = fields(root, "androidApp/build.gradle.kts", r'^\s*versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"', 1)
    shared = fields(root, "shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Constants.kt",
                    r'^\s*const val APP_VERSION\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"', 1)
    marketing = r'^\s*MARKETING_VERSION\s*=\s*([0-9]+\.[0-9]+\.[0-9]+);'
    ios = fields(root, IOS_PROJECT, marketing, 2)
    tv = fields(root, TV_PROJECT, marketing, 2)
    require(len(set(android + shared + ios + tv)) == 1,
            f"Version parity failed: Android={android}, shared={shared}, iOS={ios}, TV={tv}. Integrate the version PR; do not bypass release parity.")
    build_pattern = r'^\s*CURRENT_PROJECT_VERSION\s*=\s*([1-9][0-9]*);'
    builds = fields(root, IOS_PROJECT, build_pattern, 2) + fields(root, TV_PROJECT, build_pattern, 2)
    require(len(set(builds)) == 1, "Checked-in Apple build defaults differ between iOS and tvOS")
    code = fields(root, "androidApp/build.gradle.kts", r'^\s*versionCode\s*=\s*injectedVersionCode\s*\?:\s*([1-9][0-9]*)\s*$', 1)
    # Android codes and Apple builds are distinct numbering systems, not equal integers.
    return {"marketing": android[0], "android_code": code[0], "apple_build": builds[0]}


def cloud_config(env, *, required):
    if not required:
        # Deterministic offline local mode, even if the phone has a local secret config.
        return "SUPABASE_URL =\nSUPABASE_ANON_KEY =\n"
    required_env(env, ("SUPABASE_URL", "SUPABASE_ANON_KEY"))
    url, key = env["SUPABASE_URL"], env["SUPABASE_ANON_KEY"]
    require(re.fullmatch(r"https://[A-Za-z0-9.-]+(?::443)?/?", url) is not None,
            "SUPABASE_URL must be an HTTPS origin without substitutions or credentials")
    require(re.fullmatch(r"[A-Za-z0-9_.-]+", key) is not None, "SUPABASE_ANON_KEY has unsafe characters")
    if key.startswith("sb_publishable_"):
        require(len(key) > len("sb_publishable_"), "Empty publishable key")
    else:
        try:
            pieces = key.split(".")
            require(len(pieces) == 3, "Expected public anon JWT or publishable key")
            payload = json.loads(base64.urlsafe_b64decode(pieces[1] + "=" * (-len(pieces[1]) % 4)))
            require(payload.get("role") == "anon", "Only public anon keys may be embedded in an app")
        except (ValueError, KeyError, TypeError):
            raise ValueError("SUPABASE_ANON_KEY must be a public anon JWT or publishable key") from None
    return f"SUPABASE_URL = {url.replace('//', '/$()/')}\nSUPABASE_ANON_KEY = {key}\n"


def source_guard(root):
    """Fail closed on production/fixture composition drift; not a BLE behavior proof."""
    device = (root / "shared-tvos/src/tvosArm64Main/kotlin/com/devil/phoenixproject/di/TvosBleRepositoryFactory.tvosArm64.kt").read_text()
    require("createTvosBleRepository(): BleRepository = KableBleRepository()" in device,
            "Device BLE factory must use the real Kable repository")
    require("isTvosSimulatorFixtureEnabled(): Boolean = false" in device and "Phantom" not in device,
            "Device BLE factory must disable simulator fixtures")
    simulator = (root / "shared-tvos/src/tvosSimulatorArm64Main/kotlin/com/devil/phoenixproject/di/TvosBleRepositoryFactory.tvosSimulatorArm64.kt").read_text()
    require('"--phoenix-tv-phantom"' in simulator and
            "Platform.isDebugBinary && NSProcessInfo.processInfo.arguments.contains(TV_PHANTOM_FIXTURE_ARGUMENT)" in simulator,
            "Phantom must require both a debug binary and explicit simulator launch argument")
    gradle = (root / "shared-tvos/build.gradle.kts").read_text()
    require('implementation(project(":third_party:kable-core-tvos"))' in gradle,
            "Production TV module must link the real Kable/CoreBluetooth implementation")
    project = (root / TV_PROJECT).read_text()
    require('tvos-framework.sh' in project, "Xcode must use the configuration-aware TV framework phase")
    require('exercises.json in Resources' in project, "Xcode must copy the exercise catalog into the app")
    require((root / EXERCISES).is_file(), "Missing exercise catalog")
    require(set(re.findall(r'TVOS_DEPLOYMENT_TARGET = ([^;]+);', project)) == {"17.0"},
            "All TV configurations must retain the tvOS 17 minimum")


def validate_profile(profile, env):
    ent = profile.get("Entitlements", {})
    require(profile.get("Platform") == ["tvOS"], "Provisioning profile is not tvOS")
    require(profile.get("Name") == env["TVOS_PROVISIONING_PROFILE_NAME"], "TV provisioning profile name mismatch")
    require(profile.get("TeamIdentifier") == [env["TEAM_ID"]], "Provisioning team mismatch")
    require(ent.get("application-identifier") == env["TEAM_ID"] + "." + BUNDLE, "Provisioning bundle mismatch")
    require(ent.get("com.apple.developer.team-identifier") == env["TEAM_ID"], "Entitlement team mismatch")
    require(ent.get("get-task-allow") is False and not profile.get("ProvisionedDevices") and
            not profile.get("ProvisionsAllDevices"), "An App Store distribution profile is required, not development/ad-hoc/enterprise")
    expiry = profile.get("ExpirationDate")
    require(isinstance(expiry, dt.datetime) and expiry.replace(tzinfo=dt.timezone.utc) > dt.datetime.now(dt.timezone.utc), "TV profile expired or expiry missing")
    require(bool(profile.get("DeveloperCertificates")), "Profile contains no signing certificates")
    uuid = profile.get("UUID", "")
    require(re.fullmatch(r"[0-9A-Fa-f-]{36}", uuid) is not None, "Invalid provisioning profile UUID")
    return uuid


def accept_review(pr, reviews, sha, repo, status_receipt=None):
    require(re.fullmatch(r"[0-9a-f]{40}", sha) is not None, "A full lowercase source SHA is required")
    require(pr["base"]["repo"]["full_name"] == repo and pr["head"]["repo"]["full_name"] == repo,
            "Release review must be for this repository, not a fork")
    head = pr["head"]["sha"]
    require(not pr.get("draft") and (sha == head or (pr.get("merged") and pr.get("merge_commit_sha") == sha)),
            "Review does not cover this source SHA")
    latest = {}
    for review in sorted(reviews, key=lambda r: r["id"]):
        if review["state"] in {"APPROVED", "CHANGES_REQUESTED", "DISMISSED"}:
            latest[review["user"]["login"]] = review
    require(not any(r["state"] == "CHANGES_REQUESTED" for r in latest.values()), "Unresolved changes-requested review")
    approved = any(r["state"] == "APPROVED" and r["commit_id"] == head and
                   r["user"]["login"].casefold() != pr["user"]["login"].casefold() and
                   r.get("author_association") in {"OWNER", "MEMBER", "COLLABORATOR"}
                   for r in latest.values())
    # Separate automated reviewers can use the owner's GitHub identity, which
    # cannot approve its own PR. This is an audited reviewer attestation, not
    # permission for the implementation worker to approve itself.
    receipt = status_receipt or {}
    statuses = sorted((s for s in receipt.get("statuses", [])
                       if s.get("context") == "tvOS independent review"),
                      key=lambda s: s["id"], reverse=True)
    status = statuses[0] if statuses else {}
    attested = (receipt.get("sha") == sha and status.get("state") == "success" and
                status.get("creator", {}).get("login", "").casefold() == repo.split("/")[0].casefold() and
                status.get("description") == "Independent tvOS review accepted" and
                re.fullmatch(r"https://github\.com/" + re.escape(repo) +
                             r"/pull/" + str(pr.get("number")) + r"#issuecomment-[0-9]+",
                             status.get("target_url", "")) is not None)
    require(approved or attested, "Missing trusted current-revision independent review or automated reviewer attestation")


def review_gate():
    env = os.environ
    required_env(env, ("GITHUB_REPOSITORY", "GITHUB_SHA", "TVOS_SOURCE_SHA", "TVOS_REVIEW_PR", "GH_TOKEN"))
    require(env.get("TVOS_REVIEW_ACCEPTED") == "true", "Independent review acceptance must be explicitly enabled")
    sha, repo = env["TVOS_SOURCE_SHA"], env["GITHUB_REPOSITORY"]
    require(sha == env["GITHUB_SHA"], "Workflow and reviewed source must be the same immutable SHA")
    require(re.fullmatch(r"[1-9][0-9]*", env["TVOS_REVIEW_PR"]) is not None, "Invalid review PR number")
    prefix = f"repos/{repo}/pulls/{env['TVOS_REVIEW_PR']}"
    pr = json.loads(run(["gh", "api", prefix], "Read review PR", sensitive=True))
    pages = json.loads(run(["gh", "api", "--paginate", "--slurp", prefix + "/reviews?per_page=100"], "Read all reviews", sensitive=True))
    status_pages = json.loads(run(["gh", "api", "--paginate", "--slurp",
                                   f"repos/{repo}/commits/{sha}/statuses?per_page=100"],
                                  "Read exact-revision review attestations", sensitive=True))
    receipt = {"sha": sha, "statuses": [status for page in status_pages for status in page]}
    accept_review(pr, [review for page in pages for review in page], sha, repo, receipt)
    require(run(["git", "rev-parse", "HEAD"], "Check source SHA", sensitive=True).strip() == sha, "Checkout differs from reviewed SHA")
    print("Independent review gate accepted the exact source revision.")


def ci_release_guard():
    require(os.environ.get("GITHUB_ACTIONS") == "true", "Signing and upload are CI-only")
    review_gate()  # Recheck before either signing or uploading, not just at dispatch time.
    read_versions(ROOT)
    source_guard(ROOT)


def xcode_command(sdk, configuration, derived, config, action, destination):
    return ["xcodebuild", "-quiet", "-project", "tvosApp/PhoenixTV.xcodeproj", "-scheme", "PhoenixTV",
            "-configuration", configuration, "-sdk", sdk, "-destination", destination,
            "-derivedDataPath", str(derived), "-xcconfig", str(config), "-jobs", "1", action]


def verify_privacy_manifest(app):
    """Validate the app-root resource, not a source file or unembedded static library."""
    name = "PrivacyInfo.xcprivacy"
    try:
        manifest = plistlib.loads((app / name).read_bytes())
    except (OSError, ValueError, plistlib.InvalidFileException, ExpatError):
        raise ValueError(f"Built app {name} is missing, unreadable or invalid") from None
    require(isinstance(manifest, dict), f"{name} must contain a dictionary")
    entries = manifest.get("NSPrivacyAccessedAPITypes")
    require(isinstance(entries, list), f"{name} NSPrivacyAccessedAPITypes must be an array")
    declared = {}
    for entry in entries:
        require(isinstance(entry, dict), f"{name} API entries must be dictionaries")
        category = entry.get("NSPrivacyAccessedAPIType")
        reasons = entry.get("NSPrivacyAccessedAPITypeReasons")
        require(isinstance(category, str) and category in PRIVACY_REASONS,
                f"{name} contains an unreviewed or invalid TV API category")
        require(category not in declared, f"{name} contains a duplicate API category")
        require(isinstance(reasons, list) and bool(reasons) and
                all(isinstance(reason, str) for reason in reasons),
                f"{name} reasons must be a nonempty array of strings")
        require(len(reasons) == len(set(reasons)), f"{name} contains duplicate reasons")
        declared[category] = set(reasons)
    require(declared == PRIVACY_REASONS, f"{name} does not match the reviewed TV required-reason policy")


def verify_export_compliance(info):
    require(info.get("ITSAppUsesNonExemptEncryption") is False,
            "Built app ITSAppUsesNonExemptEncryption must be boolean false for the reviewed exempt-encryption policy")


def verify_app(app, versions, build, *, production):
    verify_privacy_manifest(app)
    info = plistlib.loads((app / "Info.plist").read_bytes())
    verify_export_compliance(info)
    require(info.get("CFBundleIdentifier") == BUNDLE, "Built app bundle ID mismatch")
    require(info.get("CFBundleShortVersionString") == versions["marketing"] and
            info.get("CFBundleVersion") == build, "Built app version mismatch")
    require(info.get("MinimumOSVersion") == "17.0" and info.get("UIDeviceFamily") == [3], "Built app TV platform/minimum mismatch")
    require((app / "exercises.json").read_bytes() == (ROOT / EXERCISES).read_bytes(), "Built exercise catalog missing or differs from source")
    if production:
        require(info.get("DTPlatformName") == "appletvos", "Archive is not a device TV app")
        require(info.get("SUPABASE_URL") == os.environ["SUPABASE_URL"] and
                info.get("SUPABASE_ANON_KEY") == os.environ["SUPABASE_ANON_KEY"], "Built cloud config is missing, truncated or incorrect")
        binary = (app / "PhoenixTV").read_bytes()
        require(not any(token in binary for token in (b"PhantomBleRepository", b"PH:AN:TO:MS:BX:01", b"--phoenix-tv-phantom")),
                "Simulator-only telemetry fixture leaked into device binary")


def normalize_exported_ipa(export_root, versions, build):
    """Validate the sole exported TV IPA and give downstream upload a stable name."""
    export_root = Path(export_root)
    require(export_root.is_dir(), "TV IPA export directory is missing")
    candidates = sorted(export_root.glob("*.ipa"))
    require(len(candidates) == 1, f"Expected exactly one TV IPA export; found {len(candidates)}")
    source = candidates[0]
    require(source.is_file() and not source.is_symlink(), "TV IPA export is not a regular file")

    try:
        with zipfile.ZipFile(source) as ipa:
            app_infos = [name for name in ipa.namelist()
                         if re.fullmatch(r"Payload/[^/]+\.app/Info\.plist", name)]
            require(len(app_infos) == 1, "TV IPA must contain exactly one Payload app Info.plist")
            info_data = ipa.read(app_infos[0])
    except (zipfile.BadZipFile, OSError, KeyError, RuntimeError, EOFError, zlib.error):
        raise ValueError("Exported TV IPA is not a readable ZIP archive") from None

    try:
        info = plistlib.loads(info_data)
    except (ValueError, TypeError, plistlib.InvalidFileException, ExpatError):
        raise ValueError("Exported TV IPA app Info.plist is invalid") from None
    require(isinstance(info, dict), "Exported TV IPA app Info.plist must contain a dictionary")
    require(info.get("CFBundleIdentifier") == BUNDLE, "Exported IPA bundle ID mismatch")
    require(info.get("DTPlatformName") == "appletvos" and info.get("UIDeviceFamily") == [3],
            "Exported IPA platform mismatch")
    require(info.get("CFBundleShortVersionString") == versions["marketing"] and
            info.get("CFBundleVersion") == build, "Exported IPA version/build mismatch")

    stable = export_root / "PhoenixTV.ipa"
    if source != stable:
        require(not stable.exists(), "Stable TV IPA destination already exists")
        source.replace(stable)
    return stable


def local(args):
    require(args.sdk == "appletvsimulator" or args.action == "build", "Tests require an Apple TV simulator")
    require(args.configuration == "Debug" or args.action == "build", "Phantom UI tests require Debug")
    require(args.action != "test" or bool(args.simulator), "Pass the exact bootable TV simulator UDID for tests")
    source_guard(ROOT)
    # Local compile can precede parent integration; release parity is NEVER waived.
    try:
        versions = read_versions(ROOT)
        print("Version defaults: " + json.dumps(versions))
    except ValueError as error:
        print(f"LOCAL ONLY parity warning: {error}")
    derived = Path(args.derived_data).expanduser().resolve()
    results = Path(args.result_bundle).expanduser().resolve()
    with tempfile.TemporaryDirectory(prefix="phoenix-tv-local-") as folder:
        config = Path(folder) / "Offline.xcconfig"
        config.write_text(cloud_config({}, required=False))
        config.chmod(0o600)
        destination = f"platform=tvOS Simulator,id={args.simulator}" if args.simulator else (
            "generic/platform=tvOS Simulator" if args.sdk == "appletvsimulator" else "generic/platform=tvOS")
        command = xcode_command(args.sdk, args.configuration, derived, config, args.action, destination)
        command += ["CODE_SIGNING_ALLOWED=NO", "CODE_SIGNING_REQUIRED=NO"]
        if args.action == "test":
            results = Path(args.result_bundle).expanduser().resolve()
            require(not results.exists(), "Result bundle already exists; choose a fresh path to preserve prior evidence")
            # Keep normal xcresult logs/screenshots without a ten-minute host sysdiagnose.
            command += ["-resultBundlePath", str(results), "-parallel-testing-enabled", "NO",
                        "-maximum-concurrent-test-simulator-destinations", "1", "-collect-test-diagnostics", "never"]
        # Only this unsigned build path uses a deterministic, empty cloud config.
        # Tests and all signing/API/security calls retain full output suppression.
        run(command, "Unsigned tvOS " + args.action, sensitive=True,
            cwd=ROOT, offline_build_diagnostics=args.action == "build")
        app = derived / f"Build/Products/{args.configuration}-{args.sdk}/PhoenixTV.app"
        require((app / "exercises.json").read_bytes() == (ROOT / EXERCISES).read_bytes(), "Exercise resource not copied into local app")
        verify_privacy_manifest(app)
        verify_export_compliance(plistlib.loads((app / "Info.plist").read_bytes()))
        print(f"Unsigned app: {app}")
        if args.action == "test":
            print(f"Native test evidence: {results}")


def archive(args):
    ci_release_guard()
    env = os.environ
    required_env(env, SIGNING + ASC + ("SUPABASE_URL", "SUPABASE_ANON_KEY"))
    config_text = cloud_config(env, required=True)
    versions = read_versions(ROOT)
    require(re.fullmatch(r"[1-9][0-9]*", args.build_number) is not None, "Build number must be a positive integer")
    output = Path(args.output).resolve()
    require(not output.exists(), "Archive output directory already exists; use a fresh path")
    output.mkdir(parents=True)
    derived = Path(args.derived_data).expanduser().resolve()
    keychains = shlex.split(run(["security", "list-keychains", "-d", "user"], "Read keychain search list", sensitive=True))
    installed_profile = None
    with tempfile.TemporaryDirectory(prefix="phoenix-tv-signing-") as folder:
        private = Path(folder)
        keychain = private / "tv-signing.keychain-db"
        try:
            config = private / "Cloud.xcconfig"
            config.write_text(config_text)
            config.chmod(0o600)
            cert = private / "certificate.p12"
            profile_file = private / "tv.mobileprovision"
            cert.write_bytes(base64.b64decode(env["BUILD_CERTIFICATE_BASE64"], validate=True))
            profile_file.write_bytes(base64.b64decode(env["TVOS_PROVISION_PROFILE_BASE64"], validate=True))
            cert.chmod(0o600)
            profile_file.chmod(0o600)
            decoded = run(["security", "cms", "-D", "-i", profile_file], "Decode TV provisioning profile", sensitive=True)
            uuid = validate_profile(plistlib.loads(decoded.encode()), env)
            profiles_dir = Path.home() / "Library/MobileDevice/Provisioning Profiles"
            profiles_dir.mkdir(parents=True, exist_ok=True)
            destination = profiles_dir / (uuid + ".mobileprovision")
            require(not destination.exists(), "Refusing to replace an existing provisioning profile")
            shutil.copyfile(profile_file, destination)
            installed_profile = destination
            run(["security", "create-keychain", "-p", env["KEYCHAIN_PASSWORD"], keychain], "Create ephemeral keychain", sensitive=True)
            run(["security", "set-keychain-settings", "-lut", "21600", keychain], "Set keychain lifetime", sensitive=True)
            run(["security", "unlock-keychain", "-p", env["KEYCHAIN_PASSWORD"], keychain], "Unlock keychain", sensitive=True)
            run(["security", "import", cert, "-P", env["P12_PASSWORD"], "-t", "cert", "-f", "pkcs12", "-k", keychain,
                 "-T", "/usr/bin/codesign", "-T", "/usr/bin/security"], "Import distribution certificate", sensitive=True)
            run(["security", "set-key-partition-list", "-S", "apple-tool:,apple:", "-k", env["KEYCHAIN_PASSWORD"], keychain], "Authorize signing tools", sensitive=True)
            run(["security", "list-keychains", "-d", "user", "-s", keychain, *keychains], "Select ephemeral keychain", sensitive=True)
            archive_path = output / "PhoenixTV.xcarchive"
            command = xcode_command("appletvos", "Release", derived, config, "archive", "generic/platform=tvOS")
            command += ["-archivePath", str(archive_path), "CODE_SIGN_STYLE=Manual", "CODE_SIGN_IDENTITY=Apple Distribution",
                        "DEVELOPMENT_TEAM=" + env["TEAM_ID"], "PROVISIONING_PROFILE_SPECIFIER=" + env["TVOS_PROVISIONING_PROFILE_NAME"],
                        "CURRENT_PROJECT_VERSION=" + args.build_number]
            run(command, "Signed TV archive", sensitive=True)
            app = archive_path / "Products/Applications/PhoenixTV.app"
            verify_app(app, versions, args.build_number, production=True)
            run(["codesign", "--verify", "--deep", "--strict", app], "Verify archive signature", sensitive=True)
            embedded = run(["security", "cms", "-D", "-i", app / "embedded.mobileprovision"], "Read embedded TV profile", sensitive=True)
            require(validate_profile(plistlib.loads(embedded.encode()), env) == uuid, "Archive embedded another profile")
            options = private / "ExportOptions.plist"
            options.write_bytes(plistlib.dumps({"method": "app-store-connect", "destination": "export", "teamID": env["TEAM_ID"],
                "signingStyle": "manual", "signingCertificate": "Apple Distribution", "manageAppVersionAndBuildNumber": False,
                "uploadSymbols": True, "provisioningProfiles": {BUNDLE: env["TVOS_PROVISIONING_PROFILE_NAME"]}}))
            run(["xcodebuild", "-quiet", "-exportArchive", "-archivePath", archive_path,
                 "-exportPath", output / "export", "-exportOptionsPlist", options], "Export TV IPA", sensitive=True)
            normalize_exported_ipa(output / "export", versions, args.build_number)
            (output / "export/release.json").write_text(json.dumps({"source_sha": env["TVOS_SOURCE_SHA"], "bundle": BUNDLE,
                "platform": "TV_OS", "marketing": versions["marketing"], "build": args.build_number}, indent=2) + "\n")
        finally:
            # Do not mask a native command's real failure code with a cleanup failure.
            subprocess.run(["security", "list-keychains", "-d", "user", "-s", *keychains], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(["security", "delete-keychain", str(keychain)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if installed_profile:
                installed_profile.unlink(missing_ok=True)
    print("Signed archive and exported IPA verified locally; not uploaded.")


def find_build(data, app_id, marketing, build):
    included = {item["id"]: item for item in data.get("included", []) if item["type"] == "preReleaseVersions"}
    for item in data["data"]:
        relationships = item.get("relationships", {})
        pre = included.get(relationships.get("preReleaseVersion", {}).get("data", {}).get("id"), {}).get("attributes", {})
        if (relationships.get("app", {}).get("data", {}).get("id") == app_id and
                item["attributes"]["version"] == build and pre.get("platform") == "TV_OS" and pre.get("version") == marketing):
            return item["id"], item["attributes"]["processingState"]
    return None


def asc_token():
    # Loaded only by the authorized upload path; offline tests use the stdlib alone.
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
    def encode(value):
        return base64.urlsafe_b64encode(value).rstrip(b"=").decode()
    now = int(time.time())
    header = encode(json.dumps({"alg": "ES256", "kid": os.environ["APPSTORE_API_KEY_ID"], "typ": "JWT"}).encode())
    body = encode(json.dumps({"iss": os.environ["APPSTORE_ISSUER_ID"], "iat": now, "exp": now + 600, "aud": "appstoreconnect-v1"}).encode())
    key = serialization.load_pem_private_key(os.environ["APPSTORE_API_KEY"].encode(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError("ASC API key must be an ES256 private key")
    message = header + "." + body
    r, s = decode_dss_signature(key.sign(message.encode(), ec.ECDSA(hashes.SHA256())))
    return message + "." + encode(r.to_bytes(32, "big") + s.to_bytes(32, "big"))


def asc_get(path, params=None):
    url = "https://api.appstoreconnect.apple.com/v1/" + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + asc_token()})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def upload(args):
    ci_release_guard()
    required_env(os.environ, ASC)
    folder = Path(args.input).resolve()
    metadata = json.loads((folder / "release.json").read_text())
    require(metadata["source_sha"] == os.environ["TVOS_SOURCE_SHA"] and metadata["bundle"] == BUNDLE and
            metadata["platform"] == "TV_OS" and metadata["marketing"] == read_versions(ROOT)["marketing"], "IPA metadata does not match reviewed TV source")
    app_id = os.environ["APP_APPLE_ID"]
    app = asc_get("apps/" + app_id)
    require(app["data"]["attributes"]["bundleId"] == BUNDLE, "ASC app ID belongs to another bundle")
    key_id = os.environ["APPSTORE_API_KEY_ID"]
    require(re.fullmatch(r"[A-Za-z0-9]+", key_id) is not None, "Invalid ASC key ID")
    # API_PRIVATE_KEYS_DIR is supported by altool; never touch the user's key directory.
    with tempfile.TemporaryDirectory(prefix="phoenix-tv-upload-") as directory:
        key = Path(directory) / f"AuthKey_{key_id}.p8"
        key.write_text(os.environ["APPSTORE_API_KEY"])
        key.chmod(0o600)
        upload_env = os.environ | {"API_PRIVATE_KEYS_DIR": directory}
        output = run(["xcrun", "altool", "--upload-package", folder / "PhoenixTV.ipa", "--type", "appletvos",
                      "--bundle-id", BUNDLE, "--bundle-version", metadata["build"],
                      "--bundle-short-version-string", metadata["marketing"], "--apple-id", app_id,
                      "--apiKey", key_id, "--apiIssuer", os.environ["APPSTORE_ISSUER_ID"]], "Upload TV IPA", sensitive=True, env=upload_env)
        require(not any(re.search(r"error|failed|invalid", line, re.I) for line in output.splitlines() if "no errors" not in line.lower()), "Uploader reported an error despite exit zero")
    # Never mistake a same-number iOS build for successful TV delivery. Refresh JWT
    # on every GET, fail on HTTP errors, and bound the wait to 20 minutes.
    for _ in range(40):
        data = asc_get("builds", {"filter[app]": app_id, "filter[version]": metadata["build"],
                                 "filter[preReleaseVersion.platform]": "TV_OS", "include": "app,preReleaseVersion", "limit": "200"})
        require(not data.get("links", {}).get("next"), "Ambiguous paginated ASC build response; refusing partial verification")
        found = find_build(data, app_id, metadata["marketing"], metadata["build"])
        if found and found[1] == "VALID":
            print(f"Verified processed TV_OS build {found[0]}: {metadata['marketing']} ({metadata['build']}).")
            print("No tester groups, beta review submissions or app metadata were changed.")
            return
        require(not found or found[1] not in {"INVALID", "FAILED"}, "ASC rejected the uploaded TV build")
        time.sleep(30)
    raise ValueError("Uploaded TV build was not verified VALID before timeout; delivery remains unverified")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("preflight", help="Offline strict parity and production composition gate")
    sub.add_parser("review", help="Read-only GitHub independent review acceptance gate")
    local_parser = sub.add_parser("local", help="Unsigned build or deterministic debug simulator UI tests")
    local_parser.add_argument("action", choices=("build", "test"))
    local_parser.add_argument("--sdk", choices=("appletvsimulator", "appletvos"), default="appletvsimulator")
    local_parser.add_argument("--configuration", choices=("Debug", "Release"), default="Debug")
    local_parser.add_argument("--simulator", help="Exact TV simulator UDID (required for tests)")
    local_parser.add_argument("--derived-data", default=os.environ.get("TVOS_DERIVED_DATA", str(ROOT / ".scratch/tvos-derived-data")))
    local_parser.add_argument("--result-bundle", default=str(ROOT / ".scratch/tvos-tests.xcresult"))
    archive_parser = sub.add_parser("archive", help="CI-only reviewed signed device archive/export; no upload")
    archive_parser.add_argument("--build-number", required=True)
    archive_parser.add_argument("--output", required=True)
    archive_parser.add_argument("--derived-data", required=True)
    upload_parser = sub.add_parser("upload", help="CI-only upload followed by exact-platform ASC verification")
    upload_parser.add_argument("--input", required=True)
    args = parser.parse_args()
    try:
        if args.command == "preflight":
            source_guard(ROOT)
            print(json.dumps(read_versions(ROOT), indent=2))
        elif args.command == "review":
            review_gate()
        elif args.command == "local":
            local(args)
        elif args.command == "archive":
            archive(args)
        elif args.command == "upload":
            upload(args)
        return 0
    except subprocess.CalledProcessError as error:
        print(f"ERROR: {error.cmd} exited {error.returncode}", file=sys.stderr)
        return error.returncode if error.returncode > 0 else 128 - error.returncode
    except (ValueError, OSError, KeyError) as error:
        # Errors deliberately name fields, never their secret values or response bodies.
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
