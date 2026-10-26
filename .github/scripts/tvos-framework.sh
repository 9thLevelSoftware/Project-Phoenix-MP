#!/bin/bash
# Xcode build phase: one native compiler, correct SDK AND configuration, no stale fallback.
set -euo pipefail
: "${SRCROOT:?Xcode SRCROOT is required}"
case "${PLATFORM_NAME:-}" in
  appletvos) target=tvosArm64; suffix=TvosArm64 ;;
  appletvsimulator) target=tvosSimulatorArm64; suffix=TvosSimulatorArm64 ;;
  *) printf 'Unsupported TV SDK\n' >&2; exit 2 ;;
esac
case "${CONFIGURATION:-}" in
  Debug) variant=debug ;;
  Release) variant=release ;;
  *) printf 'Unsupported TV configuration\n' >&2; exit 2 ;;
esac
: "${BUILT_PRODUCTS_DIR:?Xcode BUILT_PRODUCTS_DIR is required}"
root="$(cd "$SRCROOT/.." && pwd)"
cd "$root"
# Test/local builds need no cloud credentials in Gradle; the app receives cloud config
# through a private Xcode override. No parallel targets or compiler processes here.
./gradlew --no-daemon --console=plain --no-parallel --max-workers=1 \
  -Pskip.supabase.check=true ":shared-tvos:link${CONFIGURATION}Framework${suffix}"
source="$root/shared-tvos/build/bin/$target/${variant}Framework/PhoenixTvCore.framework"
test -s "$source/PhoenixTvCore"
destination="$BUILT_PRODUCTS_DIR/KotlinFrameworks/PhoenixTvCore.framework"
mkdir -p "$BUILT_PRODUCTS_DIR/KotlinFrameworks"
rm -rf "$destination"
ditto "$source" "$destination"
