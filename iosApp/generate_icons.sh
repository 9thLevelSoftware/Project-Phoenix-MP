#!/bin/bash
# App icon generation script for iOS
# Run this on macOS to generate all required icon sizes
#
# Usage: ./generate_icons.sh
#
# Prerequisites:
# - macOS with sips (included with macOS)
# - Source image: AppIcon1024.png (1024x1024) in this directory

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_IMAGE="$SCRIPT_DIR/AppIcon1024.png"
OUTPUT_DIR="$SCRIPT_DIR/PhoenixApp/PhoenixApp/Assets.xcassets/AppIcon.appiconset"

# Check source image exists
if [ ! -f "$SOURCE_IMAGE" ]; then
    echo "Error: Source image not found: $SOURCE_IMAGE"
    echo "Please provide a 1024x1024 PNG image named AppIcon1024.png"
    exit 1
fi

# Create output directory
mkdir -p "$OUTPUT_DIR"

echo "Generating iOS app icons..."
echo "Source: $SOURCE_IMAGE"
echo "Destination: $OUTPUT_DIR"
echo ""

# iPhone icons
SIZES=(
    "20:2x:40"
    "20:3x:60"
    "29:2x:58"
    "29:3x:87"
    "40:2x:80"
    "40:3x:120"
    "60:2x:120"
    "60:3x:180"
)

# iPad icons
IPAD_SIZES=(
    "20:1x:20"
    "20:2x:40"
    "29:1x:29"
    "29:2x:58"
    "40:1x:40"
    "40:2x:80"
    "76:1x:76"
    "76:2x:152"
    "83.5:2x:167"
)

# App Store icon
APP_STORE_SIZE="1024:1x:1024"

generate_icon() {
    local size=$1
    local scale=$2
    local pixels=$3
    local suffix=$4
    local output_name="icon_${size}pt@${scale}${suffix}.png"

    echo "  Generating: $output_name (${pixels}x${pixels})"
    sips -z $pixels $pixels "$SOURCE_IMAGE" --out "$OUTPUT_DIR/$output_name" > /dev/null 2>&1
}

echo "iPhone icons:"
for entry in "${SIZES[@]}"; do
    IFS=':' read -r size scale pixels <<< "$entry"
    generate_icon "$size" "$scale" "$pixels" ""
done

echo ""
echo "iPad icons:"
for entry in "${IPAD_SIZES[@]}"; do
    IFS=':' read -r size scale pixels <<< "$entry"
    generate_icon "$size" "$scale" "$pixels" "_ipad"
done

echo ""
echo "App Store icon:"
IFS=':' read -r size scale pixels <<< "$APP_STORE_SIZE"
echo "  Generating: AppStoreIcon.png (${pixels}x${pixels})"
sips -z $pixels $pixels "$SOURCE_IMAGE" --out "$OUTPUT_DIR/AppStoreIcon.png" > /dev/null 2>&1

# Generate Contents.json
cat > "$OUTPUT_DIR/Contents.json" << 'EOF'
{
  "images" : [
    {
      "filename" : "icon_20pt@2x.png",
      "idiom" : "iphone",
      "scale" : "2x",
      "size" : "20x20"
    },
    {
      "filename" : "icon_20pt@3x.png",
      "idiom" : "iphone",
      "scale" : "3x",
      "size" : "20x20"
    },
    {
      "filename" : "icon_29pt@2x.png",
      "idiom" : "iphone",
      "scale" : "2x",
      "size" : "29x29"
    },
    {
      "filename" : "icon_29pt@3x.png",
      "idiom" : "iphone",
      "scale" : "3x",
      "size" : "29x29"
    },
    {
      "filename" : "icon_40pt@2x.png",
      "idiom" : "iphone",
      "scale" : "2x",
      "size" : "40x40"
    },
    {
      "filename" : "icon_40pt@3x.png",
      "idiom" : "iphone",
      "scale" : "3x",
      "size" : "40x40"
    },
    {
      "filename" : "icon_60pt@2x.png",
      "idiom" : "iphone",
      "scale" : "2x",
      "size" : "60x60"
    },
    {
      "filename" : "icon_60pt@3x.png",
      "idiom" : "iphone",
      "scale" : "3x",
      "size" : "60x60"
    },
    {
      "filename" : "icon_20pt@1x_ipad.png",
      "idiom" : "ipad",
      "scale" : "1x",
      "size" : "20x20"
    },
    {
      "filename" : "icon_20pt@2x_ipad.png",
      "idiom" : "ipad",
      "scale" : "2x",
      "size" : "20x20"
    },
    {
      "filename" : "icon_29pt@1x_ipad.png",
      "idiom" : "ipad",
      "scale" : "1x",
      "size" : "29x29"
    },
    {
      "filename" : "icon_29pt@2x_ipad.png",
      "idiom" : "ipad",
      "scale" : "2x",
      "size" : "29x29"
    },
    {
      "filename" : "icon_40pt@1x_ipad.png",
      "idiom" : "ipad",
      "scale" : "1x",
      "size" : "40x40"
    },
    {
      "filename" : "icon_40pt@2x_ipad.png",
      "idiom" : "ipad",
      "scale" : "2x",
      "size" : "40x40"
    },
    {
      "filename" : "icon_76pt@1x_ipad.png",
      "idiom" : "ipad",
      "scale" : "1x",
      "size" : "76x76"
    },
    {
      "filename" : "icon_76pt@2x_ipad.png",
      "idiom" : "ipad",
      "scale" : "2x",
      "size" : "76x76"
    },
    {
      "filename" : "icon_83.5pt@2x_ipad.png",
      "idiom" : "ipad",
      "scale" : "2x",
      "size" : "83.5x83.5"
    },
    {
      "filename" : "AppStoreIcon.png",
      "idiom" : "ios-marketing",
      "scale" : "1x",
      "size" : "1024x1024"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
EOF

echo ""
echo "Done! App icons generated in: $OUTPUT_DIR"
echo ""
echo "Contents.json has been created with proper references."
echo "Open Xcode to verify the icons appear correctly in Assets.xcassets."
