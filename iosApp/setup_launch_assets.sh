#!/bin/bash
# Launch screen assets setup script for iOS
# Run this on macOS to create launch screen assets
#
# Usage: ./setup_launch_assets.sh
#
# Prerequisites:
# - macOS with sips (included with macOS)
# - Source image: AppIcon1024.png in this directory

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_IMAGE="$SCRIPT_DIR/AppIcon1024.png"
ASSETS_DIR="$SCRIPT_DIR/PhoenixApp/PhoenixApp/Assets.xcassets"

echo "Setting up launch screen assets..."
echo ""

# Create LaunchIcon.imageset
LAUNCH_ICON_DIR="$ASSETS_DIR/LaunchIcon.imageset"
mkdir -p "$LAUNCH_ICON_DIR"

echo "Creating LaunchIcon.imageset..."

# Generate launch icon sizes (centered logo, typically smaller than full screen)
if [ -f "$SOURCE_IMAGE" ]; then
    echo "  Generating: LaunchIcon.png (200x200)"
    sips -z 200 200 "$SOURCE_IMAGE" --out "$LAUNCH_ICON_DIR/LaunchIcon.png" > /dev/null 2>&1

    echo "  Generating: LaunchIcon@2x.png (400x400)"
    sips -z 400 400 "$SOURCE_IMAGE" --out "$LAUNCH_ICON_DIR/LaunchIcon@2x.png" > /dev/null 2>&1

    echo "  Generating: LaunchIcon@3x.png (600x600)"
    sips -z 600 600 "$SOURCE_IMAGE" --out "$LAUNCH_ICON_DIR/LaunchIcon@3x.png" > /dev/null 2>&1
else
    echo "  Warning: Source image not found, creating placeholder structure"
fi

cat > "$LAUNCH_ICON_DIR/Contents.json" << 'EOF'
{
  "images" : [
    {
      "filename" : "LaunchIcon.png",
      "idiom" : "universal",
      "scale" : "1x"
    },
    {
      "filename" : "LaunchIcon@2x.png",
      "idiom" : "universal",
      "scale" : "2x"
    },
    {
      "filename" : "LaunchIcon@3x.png",
      "idiom" : "universal",
      "scale" : "3x"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
EOF

# Create LaunchScreenBackground.colorset
LAUNCH_BG_DIR="$ASSETS_DIR/LaunchScreenBackground.colorset"
mkdir -p "$LAUNCH_BG_DIR"

echo ""
echo "Creating LaunchScreenBackground.colorset..."

# Colors from the app theme:
# Dark mode: #0F172A (RGB: 15, 23, 42)
# Light mode: #F8FAFC (RGB: 248, 250, 252)
cat > "$LAUNCH_BG_DIR/Contents.json" << 'EOF'
{
  "colors" : [
    {
      "color" : {
        "color-space" : "srgb",
        "components" : {
          "alpha" : "1.000",
          "blue" : "0.988",
          "green" : "0.980",
          "red" : "0.973"
        }
      },
      "idiom" : "universal"
    },
    {
      "appearances" : [
        {
          "appearance" : "luminosity",
          "value" : "dark"
        }
      ],
      "color" : {
        "color-space" : "srgb",
        "components" : {
          "alpha" : "1.000",
          "blue" : "0.165",
          "green" : "0.090",
          "red" : "0.059"
        }
      },
      "idiom" : "universal"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
EOF

echo ""
echo "Done! Launch screen assets created:"
echo "  - $LAUNCH_ICON_DIR"
echo "  - $LAUNCH_BG_DIR"
echo ""
echo "The Info.plist already references these assets."
echo "Open Xcode to verify the assets appear correctly."
