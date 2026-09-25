#!/bin/bash
# iOS Beta Preparation Script
# Run this on macOS to prepare the iOS app for beta testing
#
# Usage: ./prepare_ios_beta.sh
#
# This script will:
# 1. Build the shared XCFramework
# 2. Convert sound files from OGG to CAF
# 3. Generate app icons
# 4. Create launch screen assets
#
# Prerequisites:
# - macOS with Xcode Command Line Tools
# - ffmpeg (brew install ffmpeg) for sound conversion
# - Java 17+ for Gradle

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "=========================================="
echo "iOS Beta Preparation Script"
echo "=========================================="
echo ""

# Check prerequisites
check_prerequisites() {
    echo "Checking prerequisites..."

    if ! command -v xcodebuild &> /dev/null; then
        echo "Error: Xcode Command Line Tools not found"
        echo "Install with: xcode-select --install"
        exit 1
    fi
    echo "  ✓ Xcode Command Line Tools"

    if ! command -v ffmpeg &> /dev/null; then
        echo "Warning: ffmpeg not found - sound conversion will be skipped"
        echo "Install with: brew install ffmpeg"
        FFMPEG_AVAILABLE=false
    else
        echo "  ✓ ffmpeg"
        FFMPEG_AVAILABLE=true
    fi

    if ! command -v java &> /dev/null; then
        echo "Error: Java not found"
        echo "Install Java 17+: brew install openjdk@17"
        exit 1
    fi
    echo "  ✓ Java"

    echo ""
}

# Step 1: Build shared XCFramework
build_framework() {
    echo "Step 1: Building shared XCFramework..."
    cd "$PROJECT_ROOT"

    ./gradlew :shared:assembleXCFramework

    FRAMEWORK_PATH="$PROJECT_ROOT/shared/build/XCFrameworks/release/shared.xcframework"
    if [ -d "$FRAMEWORK_PATH" ]; then
        echo "  ✓ Framework built: $FRAMEWORK_PATH"
    else
        echo "  ✗ Framework build failed"
        exit 1
    fi
    echo ""
}

# Step 2: Convert sound files
convert_sounds() {
    echo "Step 2: Converting sound files..."

    if [ "$FFMPEG_AVAILABLE" = true ]; then
        cd "$SCRIPT_DIR"
        chmod +x convert_sounds.sh
        ./convert_sounds.sh
    else
        echo "  Skipped (ffmpeg not available)"
    fi
    echo ""
}

# Step 3: Generate app icons
generate_icons() {
    echo "Step 3: Generating app icons..."
    cd "$SCRIPT_DIR"

    if [ -f "AppIcon1024.png" ]; then
        chmod +x generate_icons.sh
        ./generate_icons.sh
    else
        echo "  Skipped (AppIcon1024.png not found)"
        echo "  Please provide a 1024x1024 PNG image"
    fi
    echo ""
}

# Step 4: Create launch screen assets
setup_launch_assets() {
    echo "Step 4: Creating launch screen assets..."
    cd "$SCRIPT_DIR"

    chmod +x setup_launch_assets.sh
    ./setup_launch_assets.sh
    echo ""
}

# Main execution
check_prerequisites
build_framework
convert_sounds
generate_icons
setup_launch_assets

echo "=========================================="
echo "iOS Beta Preparation Complete!"
echo "=========================================="
echo ""
echo "Next steps:"
echo "1. Open iosApp/PhoenixApp/PhoenixApp.xcodeproj in Xcode"
echo "2. Verify the shared.xcframework is linked in:"
echo "   Target → General → Frameworks, Libraries, and Embedded Content"
echo "3. If sound files were converted, add the Sounds folder to the project"
echo "4. Select a physical iOS device (BLE won't work in simulator)"
echo "5. Build and run (Cmd+R)"
echo "6. For TestFlight: Product → Archive"
echo ""
