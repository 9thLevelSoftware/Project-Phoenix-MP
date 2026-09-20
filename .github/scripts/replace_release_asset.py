#!/usr/bin/env python3
"""Stage and validate a replacement while retaining the previous release asset."""

import json
import os
import shlex
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def run_gh(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    command = [*shlex.split(os.environ.get("GH_BIN", "gh")), *args]
    result = subprocess.run(command, text=True, capture_output=True, check=False)
    if check and result.returncode:
        sys.stderr.write(result.stderr)
        raise RuntimeError("gh command failed: " + " ".join(args))
    return result


def assets(tag: str, repo: str) -> list[dict[str, object]]:
    result = run_gh("release", "view", tag, "--repo", repo, "--json", "assets")
    return json.loads(result.stdout)["assets"]


def asset_named(tag: str, repo: str, name: str) -> dict[str, object] | None:
    return next((asset for asset in assets(tag, repo) if asset["name"] == name), None)


def rename(asset: dict[str, object], name: str) -> None:
    # `release view` exposes a GraphQL node id; REST mutations require apiUrl.
    run_gh("api", "--method", "PATCH", str(asset["apiUrl"]), "-f", f"name={name}")


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: replace_release_asset.py RELEASE_TAG FILE CANONICAL_NAME", file=sys.stderr)
        return 2

    tag, source_name, canonical = sys.argv[1:]
    source = Path(source_name)
    if not source.is_file():
        print(f"Release asset does not exist: {source}", file=sys.stderr)
        return 2

    repo = os.environ["GITHUB_REPOSITORY"]
    suffix = f"candidate-{os.environ.get('GITHUB_RUN_ID', 'local')}-{os.environ.get('GITHUB_RUN_ATTEMPT', '1')}"
    staged_name = f"{canonical}.{suffix}"
    backup_name = f"{canonical}.backup-{suffix}"
    failed_name = f"{canonical}.failed-{suffix}"
    expected_size = source.stat().st_size
    staging_dir = Path(tempfile.mkdtemp(prefix="phoenix-release-asset-"))
    staged_path = staging_dir / staged_name

    try:
        old = asset_named(tag, repo, canonical)
        shutil.copyfile(source, staged_path)
        run_gh("release", "upload", tag, str(staged_path), "--repo", repo)
        staged = asset_named(tag, repo, staged_name)
        if staged is None or staged.get("size") != expected_size:
            raise RuntimeError("staged release asset is missing or has the wrong size")

        old_was_renamed = False
        if old is not None:
            rename(old, backup_name)
            old_was_renamed = True

        try:
            rename(staged, canonical)
            promoted = asset_named(tag, repo, canonical)
            if promoted is None or promoted.get("id") != staged["id"] or promoted.get("size") != expected_size:
                raise RuntimeError("promoted release asset failed validation")
        except Exception:
            if old_was_renamed:
                try:
                    rename(staged, failed_name)
                    rename(old, canonical)
                except Exception as rollback_error:
                    print(f"CRITICAL: could not restore prior release asset: {rollback_error}", file=sys.stderr)
            raise

        if old is not None:
            result = run_gh("api", "--method", "DELETE", str(old["apiUrl"]), check=False)
            if result.returncode:
                print("Warning: promoted asset is valid but old backup was retained for recovery.", file=sys.stderr)
        print(f"Successfully promoted {canonical} on release {tag}")
        return 0
    except Exception as error:
        print(f"Release asset replacement failed: {error}", file=sys.stderr)
        return 1
    finally:
        shutil.rmtree(staging_dir, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
