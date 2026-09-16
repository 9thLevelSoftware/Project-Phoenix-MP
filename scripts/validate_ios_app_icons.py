#!/usr/bin/env python3
"""Validate iOS app-icon PNGs before a release archive is created.

This intentionally uses only the Python standard library so CI can run it
before signing and without App Store Connect credentials.
"""

from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
ALPHA_COLOR_TYPES = {4, 6}


class IconValidationError(ValueError):
    """Raised when an app-icon asset violates Apple's release constraints."""


def png_properties(path: Path) -> tuple[int, int, int]:
    """Return width, height, and PNG color type from the IHDR chunk."""
    with path.open("rb") as image:
        if image.read(8) != PNG_SIGNATURE:
            raise IconValidationError(f"{path}: not a PNG file")
        length = struct.unpack(">I", image.read(4))[0]
        chunk_type = image.read(4)
        if length != 13 or chunk_type != b"IHDR":
            raise IconValidationError(f"{path}: missing PNG IHDR chunk")
        width, height, bit_depth, color_type, _, _, _ = struct.unpack(
            ">IIBBBBB", image.read(13)
        )
    if width <= 0 or height <= 0 or bit_depth not in {1, 2, 4, 8, 16}:
        raise IconValidationError(f"{path}: invalid PNG dimensions or bit depth")
    return width, height, color_type


def validate_png(path: Path, expected_size: tuple[int, int] | None = None) -> None:
    if not path.is_file():
        raise IconValidationError(f"{path}: referenced icon does not exist")
    width, height, color_type = png_properties(path)
    if color_type in ALPHA_COLOR_TYPES:
        raise IconValidationError(f"{path}: PNG has an alpha channel")
    if expected_size and (width, height) != expected_size:
        expected = "x".join(map(str, expected_size))
        raise IconValidationError(
            f"{path}: expected {expected}, got {width}x{height}"
        )


def validate_app_icon_set(asset_dir: Path) -> int:
    manifest_path = asset_dir / "Contents.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise IconValidationError(f"{manifest_path}: missing Contents.json") from exc
    except json.JSONDecodeError as exc:
        raise IconValidationError(f"{manifest_path}: invalid JSON: {exc}") from exc

    images = manifest.get("images")
    if not isinstance(images, list) or not images:
        raise IconValidationError(f"{manifest_path}: no icon images declared")

    validated = 0
    for image in images:
        filename = image.get("filename") if isinstance(image, dict) else None
        if not filename or Path(filename).name != filename:
            raise IconValidationError(f"{manifest_path}: invalid icon filename")
        expected_size = None
        if image.get("idiom") == "ios-marketing":
            expected_size = (1024, 1024)
        validate_png(asset_dir / filename, expected_size)
        validated += 1
    return validated


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--asset-dir",
        type=Path,
        default=Path("iosApp/PhoenixApp/PhoenixApp/Assets.xcassets/AppIcon.appiconset"),
        help="AppIcon.appiconset directory",
    )
    parser.add_argument(
        "--source",
        type=Path,
        action="append",
        default=[],
        help="Additional source PNG to validate (repeatable)",
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        validated = validate_app_icon_set(args.asset_dir)
        for source in args.source:
            validate_png(source)
        print(f"Validated {validated} iOS app icon(s); all are alpha-free PNGs.")
    except IconValidationError as exc:
        print(f"ERROR: {exc}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
