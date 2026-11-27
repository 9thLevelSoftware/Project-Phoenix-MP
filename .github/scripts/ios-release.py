#!/usr/bin/env python3
"""Shared iOS CI release helpers for ios-release-ipa, ios-testflight, and ios-testflight-internal."""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ASC_API_BASE = "https://api.appstoreconnect.apple.com/v1"


def bump_build_number(run_number: str, pbxproj_path: Path) -> int:
    build_num = f"{dt.datetime.now().strftime('%Y%m%d')}{run_number}"
    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a", encoding="utf-8", newline="\n") as handle:
            handle.write(f"build_number={build_num}\n")
    print(f"Build number: {build_num}")

    content = pbxproj_path.read_text(encoding="utf-8")
    updated = re.sub(
        r"CURRENT_PROJECT_VERSION = [0-9]+",
        f"CURRENT_PROJECT_VERSION = {build_num}",
        content,
    )
    pbxproj_path.write_text(updated, encoding="utf-8", newline="\n")

    matches = [line for line in updated.splitlines() if "CURRENT_PROJECT_VERSION" in line]
    for line in matches[:2]:
        print(line)
    return 0


def install_legacy_framework() -> int:
    print("Checkout predates xcodeFramework; installing the release bundle at debugFramework")
    src = Path("shared/build/bin/iosArm64/releaseFramework/shared.framework")
    dest = Path("shared/build/bin/iosArm64/debugFramework/shared.framework")
    if dest.exists():
        shutil.rmtree(dest)
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(src, dest, symlinks=False)
    binary = dest / "shared"
    if not binary.is_file() or binary.stat().st_size == 0:
        print(f"ERROR: Missing or empty framework binary at {binary}", file=sys.stderr)
        return 1
    return 0


def install_signing() -> int:
    runner_temp = Path(os.environ["RUNNER_TEMP"])
    cert_b64 = os.environ["BUILD_CERTIFICATE_BASE64"]
    p12_password = os.environ["P12_PASSWORD"]
    profile_b64 = os.environ["PROVISION_PROFILE_BASE64"]
    keychain_password = os.environ["KEYCHAIN_PASSWORD"]

    cert_path = runner_temp / "build_certificate.p12"
    profile_path = runner_temp / "build_pp.mobileprovision"
    keychain_path = runner_temp / "app-signing.keychain-db"

    cert_path.write_bytes(base64.b64decode(cert_b64))
    profile_path.write_bytes(base64.b64decode(profile_b64))

    subprocess.run(["security", "create-keychain", "-p", keychain_password, str(keychain_path)], check=True)
    subprocess.run(["security", "set-keychain-settings", "-lut", "21600", str(keychain_path)], check=True)
    subprocess.run(["security", "unlock-keychain", "-p", keychain_password, str(keychain_path)], check=True)
    subprocess.run(
        [
            "security",
            "import",
            str(cert_path),
            "-P",
            p12_password,
            "-A",
            "-t",
            "cert",
            "-f",
            "pkcs12",
            "-k",
            str(keychain_path),
        ],
        check=True,
    )
    subprocess.run(
        [
            "security",
            "set-key-partition-list",
            "-S",
            "apple-tool:,apple:",
            "-k",
            keychain_password,
            str(keychain_path),
        ],
        check=True,
    )
    subprocess.run(["security", "list-keychain", "-d", "user", "-s", str(keychain_path)], check=True)

    profiles_dir = Path.home() / "Library" / "MobileDevice" / "Provisioning Profiles"
    profiles_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(profile_path, profiles_dir / profile_path.name)
    return 0


def _b64url(data: bytes | str) -> str:
    if isinstance(data, str):
        data = data.encode("utf-8")
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def build_asc_jwt() -> str:
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
    from cryptography.hazmat.primitives.serialization import load_pem_private_key

    kid = os.environ["APPSTORE_API_KEY_ID"]
    iss = os.environ["APPSTORE_ISSUER_ID"]
    key_path = Path.home() / ".appstoreconnect" / "private_keys" / f"AuthKey_{kid}.p8"
    private_key = load_pem_private_key(key_path.read_bytes(), password=None)
    now = int(time.time())
    hdr = _b64url(json.dumps({"alg": "ES256", "kid": kid, "typ": "JWT"}, separators=(",", ":")))
    pay = _b64url(
        json.dumps({"iss": iss, "iat": now, "exp": now + 1200, "aud": "appstoreconnect-v1"}, separators=(",", ":"))
    )
    msg = f"{hdr}.{pay}"
    r, s = decode_dss_signature(private_key.sign(msg.encode("ascii"), ec.ECDSA(hashes.SHA256())))
    return f"{msg}.{_b64url(r.to_bytes(32, 'big') + s.to_bytes(32, 'big'))}"


def _asc_request(
    method: str,
    url: str,
    jwt_token: str,
    payload: dict | None = None,
) -> tuple[int, dict | None, str]:
    headers = {"Authorization": f"Bearer {jwt_token}"}
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            parsed = json.loads(raw) if raw.strip() else None
            return resp.status, parsed, raw
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(raw) if raw.strip() else None
        except json.JSONDecodeError:
            parsed = None
        return exc.code, parsed, raw


def verify_asc_upload(build_version: str, jwt_token: str) -> int:
    url = f"{ASC_API_BASE}/builds?filter[version]={urllib.parse.quote(build_version)}&limit=1"
    status_code, data, _ = _asc_request("GET", url, jwt_token)
    if status_code == 200 and data and data.get("data"):
        verify_status = data["data"][0]["attributes"].get("processingState", "UNKNOWN")
    else:
        verify_status = "NOT_FOUND"
    print(f"Build status 30s after upload: {verify_status}")
    return 0 if verify_status != "NOT_FOUND" else 2


def _generate_whats_new() -> str:
    last_tag = subprocess.run(
        ["git", "tag", "-l", "testflight/*", "--sort=-version:refname"],
        capture_output=True,
        text=True,
        check=False,
    ).stdout.splitlines()
    last_tf_tag = last_tag[0].strip() if last_tag else ""
    if last_tf_tag:
        print(f"Last TestFlight tag: {last_tf_tag}")
        log_cmd = ["git", "log", "--pretty=format:\u2022 %s", f"{last_tf_tag}..HEAD", "--no-merges"]
    else:
        print("No previous TestFlight tag found, using last 15 commits")
        log_cmd = ["git", "log", "--pretty=format:\u2022 %s", "-15", "--no-merges"]

    commits = subprocess.run(log_cmd, capture_output=True, text=True, check=False).stdout.splitlines()[:20]
    commit_log = "\n".join(commits).strip()
    if commit_log:
        return (
            f"Changes in this build:\n\n{commit_log}\n\n"
            "Please test these changes and report any issues via TestFlight feedback."
        )
    return "Maintenance build. Please test general app stability and report any issues via TestFlight feedback."


def distribute_testflight() -> int:
    bundle_id = os.environ["BUNDLE_ID"]
    build_version = os.environ["BUILD_VERSION"]
    group_name = os.environ["TESTFLIGHT_GROUP_NAME"]
    fail_on_dist_err = os.environ.get("FAIL_ON_DISTRIBUTION_ERROR", "false").lower() == "true"

    print("=== Generating JWT for App Store Connect API ===")
    jwt_token = build_asc_jwt()
    if not jwt_token:
        print("ERROR: Failed to generate JWT")
        return 1
    print(f"JWT generated successfully (length: {len(jwt_token)})")

    print(f"=== Fetching App ID for bundle: {bundle_id} ===")
    app_url = f"{ASC_API_BASE}/apps?filter[bundleId]={urllib.parse.quote(bundle_id)}"
    http_code, app_data, raw_app = _asc_request("GET", app_url, jwt_token)
    if http_code != 200 or not app_data or not app_data.get("data"):
        print(f"ERROR: API call failed with HTTP {http_code}")
        print(f"Response: {raw_app}")
        return 1
    app_id = app_data["data"][0]["id"]
    print(f"App ID: {app_id}")

    print("=== Fetching beta groups ===")
    http_code, groups_data, raw_groups = _asc_request("GET", f"{ASC_API_BASE}/apps/{app_id}/betaGroups", jwt_token)
    if http_code != 200 or not groups_data:
        print(f"ERROR: Beta groups API call failed with HTTP {http_code}")
        print(f"Response: {raw_groups}")
        return 1

    group_id = next(
        (g["id"] for g in groups_data.get("data", []) if g["attributes"].get("name") == group_name),
        "",
    )
    print(f"Beta Group ID: {group_id}")
    if not group_id:
        print(f"ERROR: Could not find beta group '{group_name}'")
        print("Available groups:")
        for g in groups_data.get("data", []):
            print(f"  - {g['attributes'].get('name')}")
        return 1

    print("=== Waiting for build to process (this may take several minutes) ===")
    print(f"Looking for build version: {build_version}")
    build_id = ""
    build_query = f"{ASC_API_BASE}/builds?filter[app]={app_id}&filter[version]={urllib.parse.quote(build_version)}&limit=1"
    for attempt in range(1, 41):
        if attempt % 20 == 0:
            jwt_token = build_asc_jwt()
        _, builds_data, _ = _asc_request("GET", build_query, jwt_token)
        if builds_data and builds_data.get("data"):
            build_entry = builds_data["data"][0]
            build_status = build_entry["attributes"].get("processingState", "UNKNOWN")
        else:
            build_entry = None
            build_status = "NOT_FOUND"
        print(f"Build status: {build_status} (attempt {attempt}/40)")
        if build_status == "VALID" and build_entry is not None:
            build_id = build_entry["id"]
            break
        if build_status == "INVALID":
            print("ERROR: Build is invalid and cannot be distributed")
            return 1
        time.sleep(30)

    if not build_id:
        print("ERROR: Build did not finish processing in time")
        return 1
    print(f"Build ID: {build_id}")

    print("=== Setting 'What to Test' notes for external review ===")
    _, loc_data, _ = _asc_request("GET", f"{ASC_API_BASE}/builds/{build_id}/betaBuildLocalizations", jwt_token)
    existing_loc_id = next(
        (loc["id"] for loc in (loc_data or {}).get("data", []) if loc["attributes"].get("locale") == "en-US"),
        "",
    )
    whats_new = _generate_whats_new()
    print("Generated release notes:")
    print(whats_new)

    if existing_loc_id:
        print(f"Updating existing localization: {existing_loc_id}")
        loc_code, _, loc_raw = _asc_request(
            "PATCH",
            f"{ASC_API_BASE}/betaBuildLocalizations/{existing_loc_id}",
            jwt_token,
            {"data": {"type": "betaBuildLocalizations", "id": existing_loc_id, "attributes": {"whatsNew": whats_new}}},
        )
    else:
        print("Creating new localization for en-US")
        loc_code, _, loc_raw = _asc_request(
            "POST",
            f"{ASC_API_BASE}/betaBuildLocalizations",
            jwt_token,
            {
                "data": {
                    "type": "betaBuildLocalizations",
                    "attributes": {"locale": "en-US", "whatsNew": whats_new},
                    "relationships": {"build": {"data": {"type": "builds", "id": build_id}}},
                }
            },
        )
    if loc_code in (200, 201):
        print("SUCCESS: 'What to Test' notes set successfully")
    else:
        print(f"WARNING: Failed to set 'What to Test' notes (HTTP {loc_code})")
        print(loc_raw)

    print("=== Setting export compliance information ===")
    comp_code, _, comp_raw = _asc_request(
        "PATCH",
        f"{ASC_API_BASE}/builds/{build_id}",
        jwt_token,
        {"data": {"type": "builds", "id": build_id, "attributes": {"usesNonExemptEncryption": False}}},
    )
    if comp_code == 200:
        print("SUCCESS: Export compliance set (usesNonExemptEncryption: false)")
    else:
        print(f"WARNING: Failed to set export compliance (HTTP {comp_code})")
        print(comp_raw)

    print(f"=== Adding build to test group '{group_name}' ===")
    add_code, _, add_raw = _asc_request(
        "POST",
        f"{ASC_API_BASE}/betaGroups/{group_id}/relationships/builds",
        jwt_token,
        {"data": [{"type": "builds", "id": build_id}]},
    )
    if add_code in (200, 204):
        print(f"SUCCESS: Build added to test group '{group_name}'")
    else:
        print(f"WARNING: Unexpected response code {add_code}")
        print(add_raw)
        if fail_on_dist_err:
            print(f"ERROR: failing because fail_on_distribution_error=true and the build was not added to '{group_name}'")
            return 1

    print("=== Submitting build for Beta App Review ===")
    rev_code, _, rev_raw = _asc_request(
        "POST",
        f"{ASC_API_BASE}/betaAppReviewSubmissions",
        jwt_token,
        {"data": {"type": "betaAppReviewSubmissions", "relationships": {"build": {"data": {"type": "builds", "id": build_id}}}}},
    )
    if rev_code in (200, 201):
        print("SUCCESS: Build submitted for Beta App Review")
    elif rev_code == 409:
        print("INFO: Build already submitted for review or review not required (internal group)")
    else:
        print(f"WARNING: Failed to submit for Beta App Review (HTTP {rev_code})")
        print(rev_raw)
        if fail_on_dist_err:
            print("ERROR: failing because fail_on_distribution_error=true and Beta App Review submission failed")
            return 1

    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    bump = sub.add_parser("bump-build-number")
    bump.add_argument("--run-number", required=True)
    bump.add_argument("--pbxproj", required=True, type=Path)

    sub.add_parser("install-legacy-framework")
    sub.add_parser("install-signing")
    sub.add_parser("generate_jwt")

    verify = sub.add_parser("verify-asc-upload")
    verify.add_argument("--build-version", required=True)
    verify.add_argument("--jwt", required=True)

    sub.add_parser("distribute-testflight")

    args = parser.parse_args(argv)
    if args.command == "bump-build-number":
        return bump_build_number(args.run_number, args.pbxproj)
    if args.command == "install-legacy-framework":
        return install_legacy_framework()
    if args.command == "install-signing":
        return install_signing()
    if args.command == "generate_jwt":
        print(build_asc_jwt())
        return 0
    if args.command == "verify-asc-upload":
        return verify_asc_upload(args.build_version, args.jwt)
    if args.command == "distribute-testflight":
        return distribute_testflight()
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
