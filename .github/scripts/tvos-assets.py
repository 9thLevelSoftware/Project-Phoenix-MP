#!/usr/bin/env python3
"""Generate/check Phoenix TV branding from the exact official phone icon.

Requires Pillow (generated with 12.3.0). No network, signing or native build.
The whole source image is scaled uniformly, never cropped, recolored or traced.
A 10% vertical safe margin keeps the artwork away from parallax clipping.

Apple format references:
https://developer.apple.com/library/archive/documentation/Xcode/Reference/xcode_ref-Asset_Catalog_Format/BrandAssetsType.html
https://developer.apple.com/library/archive/documentation/Xcode/Reference/xcode_ref-Asset_Catalog_Format/ImageStackType.html
https://developer.apple.com/library/archive/documentation/Xcode/Reference/xcode_ref-Asset_Catalog_Format/ImageStackLayerType.html
https://developer.apple.com/design/human-interface-guidelines/app-icons
https://developer.apple.com/design/human-interface-guidelines/top-shelf
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "iosApp/PhoenixApp/PhoenixApp/Assets.xcassets/AppIcon.appiconset/AppIcon1024.png"
SOURCE_SHA256 = "ed9a69d0e5fdc3458e246aa7668e9d130d7973cb0dd18afa63b6819009353778"
CATALOG = ROOT / "tvosApp/PhoenixTV/Assets.xcassets"
BRAND = Path("AppIcon.brandassets")
INFO = {"author": "com.devil.phoenixproject.projectphoenix", "version": 1}
# The large App Store stack is 1x; the Home icon and shelf sets also supply 2x.
ASSETS = (
    ("App Icon - Small.imagestack", "primary-app-icon", (400, 240), (1, 2)),
    ("App Icon - Large.imagestack", "primary-app-icon", (1280, 768), (1,)),
    ("Top Shelf Image.imageset", "top-shelf-image", (1920, 720), (1, 2)),
    ("Top Shelf Image Wide.imageset", "top-shelf-image-wide", (2320, 720), (1, 2)),
)


def centered_art(source, size, *, transparent):
    """Preserve the entire square official icon, including its original background."""
    width, height = size
    side = (height * 4 // 5) // 2 * 2  # Even dimensions give exact centering.
    art = source.resize((side, side), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA" if transparent else "RGB", size,
                       (0, 0, 0, 0) if transparent else (0, 0, 0))
    canvas.paste(art, ((width - side) // 2, (height - side) // 2))
    return canvas


def expected_catalog():
    if hashlib.sha256(SOURCE.read_bytes()).hexdigest() != SOURCE_SHA256:
        raise ValueError("Official source icon changed; review it before updating SOURCE_SHA256")
    with Image.open(SOURCE) as original:
        if original.size != (1024, 1024) or original.mode != "RGB":
            raise ValueError("Expected the official opaque RGB 1024x1024 icon")
        source = original.copy()
    documents: dict[Path, dict] = {Path("Contents.json"): {"info": INFO}}
    images = {}
    documents[BRAND / "Contents.json"] = {
        "assets": [{"filename": name, "idiom": "tv", "role": role,
                    "size": f"{size[0]}x{size[1]}"} for name, role, size, _ in ASSETS],
        "info": INFO,
    }
    for name, role, size, scales in ASSETS:
        asset = BRAND / name
        if role == "primary-app-icon":
            # Front to back ordering is Apple's image-stack format. Foreground
            # transparency is padding only; no pixels of the source are removed.
            layers = ("Front.imagestacklayer", "Back.imagestacklayer")
            documents[asset / "Contents.json"] = {
                "layers": [{"filename": layer} for layer in layers], "info": INFO,
            }
            for layer in layers:
                documents[asset / layer / "Contents.json"] = {"info": INFO}
                image_set = asset / layer / "Content.imageset"
                documents[image_set / "Contents.json"] = image_set_document(scales)
                for scale in scales:
                    pixels = (size[0] * scale, size[1] * scale)
                    images[image_set / f"image@{scale}x.png"] = (
                        Image.new("RGB", pixels, (0, 0, 0)) if layer.startswith("Back")
                        else centered_art(source, pixels, transparent=True)
                    )
        else:
            documents[asset / "Contents.json"] = image_set_document(scales)
            for scale in scales:
                pixels = (size[0] * scale, size[1] * scale)
                images[asset / f"image@{scale}x.png"] = centered_art(source, pixels, transparent=False)
    return documents, images


def image_set_document(scales):
    return {"images": [{"filename": f"image@{scale}x.png", "idiom": "tv", "scale": f"{scale}x"}
                       for scale in scales], "info": INFO}


def process(output, *, check):
    documents, images = expected_catalog()
    expected_paths = set(documents) | set(images)
    existing_paths = {path.relative_to(output) for path in output.rglob("*") if path.is_file()}
    unexpected = existing_paths - expected_paths
    if unexpected:
        raise ValueError("Unexpected catalog files (not removed): " + ", ".join(map(str, sorted(unexpected))))
    if check:
        missing = expected_paths - existing_paths
        if missing:
            raise ValueError("Missing catalog files: " + ", ".join(map(str, sorted(missing))))
    for relative, document in documents.items():
        path = output / relative
        if check:
            if json.loads(path.read_text()) != document:
                raise ValueError(f"Catalog metadata differs: {relative}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(document, indent=2) + "\n")
    for relative, expected in images.items():
        path = output / relative
        if check:
            with Image.open(path) as actual:
                # Compare decoded pixels rather than compression bytes: zlib may
                # differ across hosts without changing the artwork or opacity.
                if (actual.format != "PNG" or actual.mode != expected.mode or
                        actual.size != expected.size or actual.tobytes() != expected.tobytes()):
                    raise ValueError(f"Catalog image differs: {relative}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            expected.save(path, format="PNG", optimize=False, compress_level=9)
    print(f"{'Verified' if check else 'Generated'} {len(documents)} JSON files and {len(images)} PNGs: {output}")
    print(f"Official source SHA-256: {SOURCE_SHA256}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify without writing; fails on drift or missing files")
    parser.add_argument("--output", type=Path, default=CATALOG, help="Catalog output (default: PhoenixTV/Assets.xcassets)")
    args = parser.parse_args()
    try:
        process(args.output.resolve(), check=args.check)
        return 0
    except (OSError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
