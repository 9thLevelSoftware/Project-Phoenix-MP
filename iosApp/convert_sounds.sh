#!/bin/bash
# Sound file conversion script for iOS
# Run this on macOS to convert Android OGG files to iOS CAF format
#
# Usage: ./convert_sounds.sh
#
# Prerequisites:
# - macOS with afconvert (included with Xcode Command Line Tools)
# - Or: brew install ffmpeg (alternative method)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
ANDROID_RAW="$PROJECT_ROOT/shared/src/androidMain/res/raw"
IOS_SOUNDS="$SCRIPT_DIR/VitruvianPhoenix/VitruvianPhoenix"

# Create output directory
mkdir -p "$IOS_SOUNDS"

echo "Converting sound files from OGG to CAF..."
echo "Source: $ANDROID_RAW"
echo "Destination: $IOS_SOUNDS"
echo ""

# Find all sound files dynamically
if [ ! -d "$ANDROID_RAW" ]; then
    echo "Error: Source directory not found: $ANDROID_RAW"
    exit 1
fi

echo "Scanning $ANDROID_RAW for OGG files..."
SOUND_FILES=()
while IFS= read -r -d '' file; do
    SOUND_FILES+=("$file")
done < <(find "$ANDROID_RAW" -maxdepth 1 -name "*.ogg" -print0)

echo "Found ${#SOUND_FILES[@]} sound files to convert."
echo ""

for input_path in "${SOUND_FILES[@]}"; do
    filename=$(basename "$input_path")
    sound="${filename%.*}"
    INPUT="$input_path"
    OUTPUT="$IOS_SOUNDS/$sound.caf"

    if [ -f "$INPUT" ]; then
        echo "Converting: $sound.ogg -> $sound.caf"

        # Method 1: Using afconvert (macOS native)
        if command -v afconvert &> /dev/null; then
            # First convert OGG to WAV using ffmpeg, then to CAF
            if command -v ffmpeg &> /dev/null; then
                TEMP_WAV="/tmp/$sound.wav"
                ffmpeg -y -i "$INPUT" -acodec pcm_s16le "$TEMP_WAV" 2>/dev/null
                afconvert "$TEMP_WAV" "$OUTPUT" -d ima4 -f caff
                rm -f "$TEMP_WAV"
            else
                echo "  Warning: ffmpeg not found, trying direct conversion..."
                # afconvert may not support OGG directly
                afconvert "$INPUT" "$OUTPUT" -d ima4 -f caff 2>/dev/null || {
                    echo "  Error: Direct OGG conversion failed. Install ffmpeg: brew install ffmpeg"
                    continue
                }
            fi
        # Method 2: Using ffmpeg only
        elif command -v ffmpeg &> /dev/null; then
            ffmpeg -y -i "$INPUT" -c:a pcm_s16le "$OUTPUT" 2>/dev/null
        else
            echo "  Error: Neither afconvert nor ffmpeg found!"
            echo "  Install ffmpeg: brew install ffmpeg"
            exit 1
        fi

        if [ -f "$OUTPUT" ]; then
            echo "  ✓ Created: $OUTPUT"
        fi
    else
        echo "Warning: Source file not found: $INPUT"
    fi
done

echo ""
echo "Done! Sound files are ready in: $IOS_SOUNDS"
echo ""
echo "Next steps:"
echo "1. Open VitruvianPhoenix.xcodeproj in Xcode"
echo "2. Drag the Sounds folder into your project"
echo "3. Ensure 'Copy items if needed' is checked"
echo "4. Verify target membership is set for VitruvianPhoenix"
