#!/usr/bin/env bash
# Native CoreGraphics/ImageIO contract test for phantom-image-diff.swift.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

select_tmp_root() {
    if [[ -n "${TMPDIR:-}" && -d "$TMPDIR" && -w "$TMPDIR" ]]; then
        printf '%s\n' "$TMPDIR"
    else
        printf '%s\n' /tmp
    fi
}

test_tmp_root_selection() {
    local test_tmpdir selected
    test_tmpdir="$(mktemp -d /tmp/phantom-image-diff-root-test.XXXXXX)"

    selected="$(TMPDIR="$test_tmpdir" select_tmp_root)"
    [[ "$selected" == "$test_tmpdir" ]] || {
        printf 'expected writable TMPDIR to be selected, got %s\n' "$selected" >&2
        return 1
    }

    rm -rf "$test_tmpdir"
    selected="$(TMPDIR="$test_tmpdir" select_tmp_root)"
    [[ "$selected" == "/tmp" ]] || {
        printf 'expected missing TMPDIR to fall back to /tmp, got %s\n' "$selected" >&2
        return 1
    }

    unset TMPDIR
    selected="$(select_tmp_root)"
    [[ "$selected" == "/tmp" ]] || {
        printf 'expected unset TMPDIR to fall back to /tmp, got %s\n' "$selected" >&2
        return 1
    }
}

if [[ "${1:-}" == "--test-tmp-root" ]]; then
    test_tmp_root_selection
    echo "phantom image diff temp-root selection tests passed"
    exit 0
fi

TMP_ROOT="$(select_tmp_root)"
export TMPDIR="$TMP_ROOT"
TMP_DIR="$(mktemp -d "$TMP_ROOT/phantom-image-diff.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

GENERATOR="$TMP_DIR/generate.swift"
cat > "$GENERATOR" <<'SWIFT'
import Foundation
import CoreGraphics
import ImageIO

let outputDirectory = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)

func writePNG(_ pixels: [UInt8], width: Int, height: Int, to url: URL) {
    let data = Data(pixels) as CFData
    guard let provider = CGDataProvider(data: data),
          let image = CGImage(
              width: width,
              height: height,
              bitsPerComponent: 8,
              bitsPerPixel: 32,
              bytesPerRow: width * 4,
              space: CGColorSpaceCreateDeviceRGB(),
              bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
              provider: provider,
              decode: nil,
              shouldInterpolate: false,
              intent: .defaultIntent
          ),
          let destination = CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil)
    else {
        fatalError("could not construct PNG")
    }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { fatalError("could not write PNG") }
}

func pixels(width: Int, height: Int, topDifference: Bool, bodyDifference: Bool) -> [UInt8] {
    var result = [UInt8](repeating: 255, count: width * height * 4)
    for y in 0..<height {
        let topRow = y == 0
        let different = (topRow && topDifference) || (!topRow && bodyDifference)
        for x in 0..<width {
            let offset = (y * width + x) * 4
            if different {
                result[offset] = 0
                result[offset + 1] = topRow ? 0 : 255
                result[offset + 2] = topRow ? 255 : 0
                result[offset + 3] = 255
            }
        }
    }
    return result
}

writePNG(pixels(width: 4, height: 4, topDifference: false, bodyDifference: false), width: 4, height: 4, to: outputDirectory.appendingPathComponent("before.png"))
writePNG(pixels(width: 4, height: 4, topDifference: true, bodyDifference: false), width: 4, height: 4, to: outputDirectory.appendingPathComponent("top-after.png"))
writePNG(pixels(width: 4, height: 4, topDifference: false, bodyDifference: true), width: 4, height: 4, to: outputDirectory.appendingPathComponent("body-after.png"))
writePNG(pixels(width: 5, height: 4, topDifference: false, bodyDifference: false), width: 5, height: 4, to: outputDirectory.appendingPathComponent("mismatch.png"))
SWIFT

swiftc -framework ImageIO -framework CoreGraphics "$GENERATOR" -o "$TMP_DIR/generate"
"$TMP_DIR/generate" "$TMP_DIR"

swiftc -framework ImageIO -framework CoreGraphics "$SCRIPT_DIR/phantom-image-diff.swift" -o "$TMP_DIR/phantom-image-diff"

run_diff() {
    "$TMP_DIR/phantom-image-diff" "$@"
}

run_diff \
    --before "$TMP_DIR/before.png" \
    --after "$TMP_DIR/top-after.png" \
    --diff "$TMP_DIR/masked.diff.png" \
    --json "$TMP_DIR/masked.json" \
    --mask-top-pixels 1 \
    --threshold 0

run_diff \
    --before "$TMP_DIR/before.png" \
    --after "$TMP_DIR/body-after.png" \
    --diff "$TMP_DIR/body.diff.png" \
    --json "$TMP_DIR/body.json" \
    --mask-top-pixels 1 \
    --threshold 0

python3 - "$TMP_DIR/masked.json" "$TMP_DIR/body.json" "$TMP_DIR/masked.diff.png" "$TMP_DIR/body.diff.png" <<'PY'
import json
import pathlib
import sys

masked, body = (json.loads(pathlib.Path(path).read_text()) for path in sys.argv[1:3])
assert masked["dimensions"] == {"width": 4, "height": 4}, masked
assert masked["changedPixels"] == 0, masked
assert masked["changedPixelRatio"] == 0, masked
assert masked["passed"] is True, masked
assert body["dimensions"] == {"width": 4, "height": 4}, body
assert body["changedPixels"] > 0, body
assert body["changedPixelRatio"] > 0, body
assert body["passed"] is False, body
for path in sys.argv[3:]:
    data = pathlib.Path(path).read_bytes()
    assert data.startswith(b"\x89PNG\r\n\x1a\n"), path
PY

if run_diff \
    --before "$TMP_DIR/before.png" \
    --after "$TMP_DIR/mismatch.png" \
    --diff "$TMP_DIR/mismatch.diff.png" \
    --json "$TMP_DIR/mismatch.json" \
    --mask-top-pixels 1 \
    --threshold 0; then
    echo "expected mismatched dimensions to be rejected" >&2
    exit 1
fi

echo "phantom image diff tests passed"
