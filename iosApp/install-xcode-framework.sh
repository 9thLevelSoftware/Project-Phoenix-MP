#!/bin/sh
# Copy the iosArm64 shared.framework to the path the Xcode project links.
#
# Gradle writes a configuration-specific bundle:
#   shared/build/bin/iosArm64/debugFramework/shared.framework
#   shared/build/bin/iosArm64/releaseFramework/shared.framework
# Debug and Release in PhoenixApp.xcodeproj both link:
#   shared/build/bin/iosArm64/xcodeFramework/shared.framework
#
# Local debug, from the repo root, after linkDebugFrameworkIosArm64:
#   iosApp/install-xcode-framework.sh debug
# Release workflows, after linkReleaseFrameworkIosArm64:
#   iosApp/install-xcode-framework.sh release
#
# The destination is a real copy. Do not symlink release into debugFramework.
# PHOENIX_ROOT overrides the repo root (tests). Default is the parent of iosApp/.
set -eu

usage() {
  echo "usage: iosApp/install-xcode-framework.sh debug|release" >&2
  exit 1
}

CONFIG="${1:-}"
case "$CONFIG" in
  debug|release) ;;
  *) usage ;;
esac

if [ -n "${PHOENIX_ROOT:-}" ]; then
  ROOT="$PHOENIX_ROOT"
else
  ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
fi

SRC="$ROOT/shared/build/bin/iosArm64/${CONFIG}Framework/shared.framework"
DEST_DIR="$ROOT/shared/build/bin/iosArm64/xcodeFramework"
DEST="$DEST_DIR/shared.framework"

if [ ! -d "$SRC" ] || [ ! -f "$SRC/shared" ] || [ ! -f "$SRC/Modules/module.modulemap" ] || [ ! -f "$SRC/Info.plist" ]; then
  echo "error: linkable shared.framework not found at $SRC" >&2
  echo "Build :shared:linkDebugFrameworkIosArm64 or :shared:linkReleaseFrameworkIosArm64 first." >&2
  exit 1
fi

rm -rf "$DEST"
mkdir -p "$DEST"
# "$SRC/." copies the bundle contents into a real directory, including when
# the source path itself is a symlink.
cp -R "$SRC"/. "$DEST"/

if [ -L "$DEST" ] || [ -L "$DEST/shared" ] || [ ! -f "$DEST/shared" ] || [ ! -s "$DEST/shared" ]; then
  echo "error: $DEST/shared is not a real framework binary" >&2
  exit 1
fi
if [ ! -f "$DEST/Modules/module.modulemap" ] || [ ! -f "$DEST/Info.plist" ]; then
  echo "error: $DEST is missing the module map or Info.plist" >&2
  exit 1
fi

echo "Installed $CONFIG shared.framework to $DEST"
