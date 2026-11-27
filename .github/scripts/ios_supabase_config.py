#!/usr/bin/env python3
"""Validate Supabase credentials for iOS release workflows and archived Info.plist."""
from __future__ import annotations

import argparse
import importlib.util
import os
from pathlib import Path
import plistlib
import sys
from xml.parsers.expat import ExpatError


def _load_tvos_release():
    script_path = Path(__file__).with_name("tvos-release.py")
    spec = importlib.util.spec_from_file_location("tvos_release", script_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load {script_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_supabase_xcconfig(output_path: Path, env: dict[str, str] | None = None) -> Path:
    source_env = dict(os.environ if env is None else env)
    tvos_release = _load_tvos_release()
    config_text = tvos_release.cloud_config(source_env, required=True)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(config_text, encoding="utf-8", newline="\n")
    try:
        output_path.chmod(0o600)
    except OSError:
        pass
    return output_path


def verify_archived_info_plist(plist_path: Path, env: dict[str, str] | None = None) -> None:
    source_env = dict(os.environ if env is None else env)
    tvos_release = _load_tvos_release()
    tvos_release.cloud_config(source_env, required=True)
    if not plist_path.is_file():
        raise ValueError(f"Archived Info.plist not found: {plist_path}")
    try:
        info = plistlib.loads(plist_path.read_bytes())
    except (OSError, ValueError, plistlib.InvalidFileException, ExpatError):
        raise ValueError("Archived Info.plist is unreadable or invalid") from None
    if not isinstance(info, dict):
        raise ValueError("Archived Info.plist must contain a dictionary")
    if (
        info.get("SUPABASE_URL") != source_env["SUPABASE_URL"]
        or info.get("SUPABASE_ANON_KEY") != source_env["SUPABASE_ANON_KEY"]
    ):
        raise ValueError(
            "Archived Info.plist SUPABASE_URL or SUPABASE_ANON_KEY is missing, truncated, or mismatched"
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    write_parser = sub.add_parser("write-xcconfig", help="Validate secrets and write Supabase.xcconfig")
    write_parser.add_argument("--output", required=True, type=Path)

    verify_parser = sub.add_parser("verify-plist", help="Verify archived app Info.plist Supabase config")
    verify_parser.add_argument("--plist", required=True, type=Path)

    args = parser.parse_args(argv)
    try:
        if args.command == "write-xcconfig":
            write_supabase_xcconfig(args.output)
        elif args.command == "verify-plist":
            verify_archived_info_plist(args.plist)
        return 0
    except (ValueError, OSError, plistlib.InvalidFileException, ExpatError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
