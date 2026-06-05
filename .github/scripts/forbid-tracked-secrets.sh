#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
violations=()

while IFS= read -r -d '' raw_path; do
  path="${raw_path//\\//}"
  lower="$(printf '%s' "$path" | tr '[:upper:]' '[:lower:]')"
  is_template=false
  case "$lower" in
    *.example|*.sample|*.template|*/templates/*) is_template=true ;;
  esac

  if [[ "$path" =~ (^|/)Supabase\.xcconfig$ ]]; then
    violations+=("$path (real iOS Supabase config must stay local or be generated from CI secrets)")
    continue
  fi

  if [[ "$path" =~ (^|/).*\.local\.properties$ || "$path" =~ (^|/)local\.properties$ ]]; then
    violations+=("$path (local Gradle/SDK or secret properties must not be tracked)")
    continue
  fi

  if [[ "$is_template" == false && ( "$path" =~ (^|/)google-services\.json$ || "$path" =~ (^|/)GoogleService-Info\.plist$ ) ]]; then
    violations+=("$path (service config must be supplied outside git unless it is a template)")
    continue
  fi

  if [[ "$is_template" == false && "$path" =~ \.(jks|keystore|p12|mobileprovision)$ ]]; then
    violations+=("$path (signing material must be supplied from a local machine or CI secret)")
    continue
  fi

  if [[ "$is_template" == false && "$path" =~ (^|/)\.env(\..*)?$ ]]; then
    violations+=("$path (environment files must not be tracked)")
    continue
  fi
done < <(git -C "$repo_root" ls-files -z)

if (( ${#violations[@]} > 0 )); then
  printf 'Forbidden tracked secret-bearing files found:\n' >&2
  printf ' - %s\n' "${violations[@]}" >&2
  exit 1
fi

printf 'No forbidden tracked secret-bearing files found.\n'
