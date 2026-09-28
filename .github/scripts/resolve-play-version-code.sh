#!/usr/bin/env bash
# Resolve a monotonic Android version code from current Google Play track state.
# Shared by android-release-apk.yml and android-playstore.yml.
#
# Required env: ACCESS_TOKEN, PACKAGE_NAME, GITHUB_OUTPUT
# Optional env: VERSION_CODE_OVERRIDE (empty selects Play max + 1)
#
# Writes play_max_version_code, selected_version_code, and version_code_source
# to GITHUB_OUTPUT. Deletes the temporary Play edit on exit.

set -euo pipefail

cleanup() {
  if [ -n "${EDIT_ID:-}" ]; then
    curl --silent --show-error --fail \
      -X DELETE \
      -H "Authorization: Bearer $ACCESS_TOKEN" \
      "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$PACKAGE_NAME/edits/$EDIT_ID" \
      >/dev/null || echo "Warning: failed to delete temporary Play edit $EDIT_ID" >&2
  fi
}

trap cleanup EXIT

EDIT_RESPONSE="$(curl --silent --show-error --fail \
  -X POST \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{}' \
  "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$PACKAGE_NAME/edits")"
EDIT_ID="$(printf '%s' "$EDIT_RESPONSE" | jq -r '.id // empty')"

if [ -z "$EDIT_ID" ]; then
  echo "Failed to create a temporary Google Play edit." >&2
  printf '%s\n' "$EDIT_RESPONSE" >&2
  exit 1
fi

# Google Play intermittently returns 5xx while reading track state.
# curl retries transient HTTP failures with bounded exponential backoff.
TRACKS_RESPONSE="$(curl --silent --show-error --fail \
  --connect-timeout 10 \
  --max-time 20 \
  --retry 4 \
  --retry-max-time 45 \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$PACKAGE_NAME/edits/$EDIT_ID/tracks")"
PLAY_MAX_VERSION_CODE="$(printf '%s' "$TRACKS_RESPONSE" | jq -r '[.tracks[]?.releases[]?.versionCodes[]? | tonumber] | max // empty')"

if [ -n "$PLAY_MAX_VERSION_CODE" ]; then
  PLAY_MAX_OUTPUT="$PLAY_MAX_VERSION_CODE"
else
  PLAY_MAX_OUTPUT="none"
  PLAY_MAX_VERSION_CODE=0
fi

OVERRIDE="$(printf '%s' "${VERSION_CODE_OVERRIDE:-}" | xargs)"
if [ -n "$OVERRIDE" ]; then
  if ! [[ "$OVERRIDE" =~ ^[0-9]+$ ]]; then
    echo "Invalid workflow input versionCodeOverride='$OVERRIDE'. Expected a positive integer <= 2100000000." >&2
    exit 1
  fi

  SELECTED_VERSION_CODE="$OVERRIDE"
  VERSION_CODE_SOURCE="manual_override"
else
  if [ "$PLAY_MAX_OUTPUT" = "none" ]; then
    echo "No existing Play version codes were found. Provide workflow_dispatch input versionCodeOverride to seed the next release." >&2
    exit 1
  fi

  SELECTED_VERSION_CODE=$((PLAY_MAX_VERSION_CODE + 1))
  VERSION_CODE_SOURCE="play_max_plus_one"
fi

if [ "$SELECTED_VERSION_CODE" -lt 1 ] || [ "$SELECTED_VERSION_CODE" -gt 2100000000 ]; then
  echo "Selected version code $SELECTED_VERSION_CODE is outside the allowed range 1..2100000000." >&2
  exit 1
fi

if [ "$PLAY_MAX_OUTPUT" != "none" ] && [ "$SELECTED_VERSION_CODE" -le "$PLAY_MAX_VERSION_CODE" ]; then
  echo "Selected version code $SELECTED_VERSION_CODE must be greater than current Play max $PLAY_MAX_VERSION_CODE." >&2
  exit 1
fi

echo "play_max_version_code=$PLAY_MAX_OUTPUT" >> "$GITHUB_OUTPUT"
echo "selected_version_code=$SELECTED_VERSION_CODE" >> "$GITHUB_OUTPUT"
echo "version_code_source=$VERSION_CODE_SOURCE" >> "$GITHUB_OUTPUT"

echo "::notice title=Google Play version preflight::play_max_version_code=$PLAY_MAX_OUTPUT selected_version_code=$SELECTED_VERSION_CODE source=$VERSION_CODE_SOURCE"
echo "Play max version code: $PLAY_MAX_OUTPUT"
echo "Selected version code: $SELECTED_VERSION_CODE"
echo "Version code source: $VERSION_CODE_SOURCE"
