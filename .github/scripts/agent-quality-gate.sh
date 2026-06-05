#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
  if [[ ! -f "$repo_root/local.properties" ]] || ! grep -q '^sdk\.dir=' "$repo_root/local.properties"; then
    printf 'Android SDK is not configured. Set ANDROID_HOME, ANDROID_SDK_ROOT, or local.properties sdk.dir before running the agent gate.\n' >&2
    exit 1
  fi
fi

bash "$repo_root/.github/scripts/forbid-tracked-secrets.sh"

gradle_args=(
  -Pskip.supabase.check=true
  spotlessCheck
  validateSchemaManifest
  :shared:verifyCommonMainVitruvianDatabaseMigration
  :shared:testAndroidHostTest
  --console=plain
  --no-daemon
)

uses_windows_tools=false
if command -v cmd.exe >/dev/null 2>&1 && [[ -f "$repo_root/gradlew.bat" ]]; then
  uses_windows_tools=true
  (cd "$repo_root" && cmd.exe /C "gradlew.bat ${gradle_args[*]}")
else
  (cd "$repo_root" && ./gradlew "${gradle_args[@]}")
fi

if [[ "$uses_windows_tools" == true ]]; then
  (cd "$repo_root" && cmd.exe /C "git diff --check")
else
  git -C "$repo_root" diff --check
fi
