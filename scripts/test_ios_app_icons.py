#!/usr/bin/env python3
"""Deterministic offline tests for validate_ios_app_icons."""

from __future__ import annotations

import json
import struct
import tempfile
import unittest
from pathlib import Path

from validate_ios_app_icons import IconValidationError, validate_app_icon_set, validate_png


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def fake_png(path: Path, width: int, height: int, color_type: int) -> None:
    ihdr = struct.pack(">IIBBBBB", width, height, 8, color_type, 0, 0, 0)
    path.write_bytes(PNG_SIGNATURE + struct.pack(">I", len(ihdr)) + b"IHDR" + ihdr)


class IOSAppIconValidationTests(unittest.TestCase):
    def test_accepts_opaque_marketing_icon(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            asset_dir = Path(directory)
            fake_png(asset_dir / "AppIcon.png", 1024, 1024, 2)
            (asset_dir / "Contents.json").write_text(
                json.dumps(
                    {"images": [{"filename": "AppIcon.png", "idiom": "ios-marketing"}]}
                ),
                encoding="utf-8",
            )
            self.assertEqual(validate_app_icon_set(asset_dir), 1)

    def test_rejects_alpha_channel(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            icon = Path(directory) / "alpha.png"
            fake_png(icon, 1024, 1024, 6)
            with self.assertRaisesRegex(IconValidationError, "alpha channel"):
                validate_png(icon, (1024, 1024))

    def test_rejects_wrong_marketing_dimensions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            asset_dir = Path(directory)
            fake_png(asset_dir / "AppIcon.png", 512, 512, 2)
            (asset_dir / "Contents.json").write_text(
                json.dumps(
                    {"images": [{"filename": "AppIcon.png", "idiom": "ios-marketing"}]}
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(IconValidationError, "expected 1024x1024"):
                validate_app_icon_set(asset_dir)


if __name__ == "__main__":
    unittest.main()
