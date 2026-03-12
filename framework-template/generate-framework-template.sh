#!/usr/bin/env bash
set -euo pipefail

# Generates a distributable framework template from the current repository.
# Intended to run on the maintained `framework-template` branch.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-${ROOT_DIR}/build/framework-template-dist}"

INCLUDE_FILES=(
  "README.md"
  "build.gradle.kts"
  "settings.gradle.kts"
  "gradle.properties"
)

INCLUDE_DIRS=(
  "gradle"
  "shared"
)

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

for file in "${INCLUDE_FILES[@]}"; do
  if [[ -f "${ROOT_DIR}/${file}" ]]; then
    mkdir -p "${OUT_DIR}/$(dirname "${file}")"
    cp "${ROOT_DIR}/${file}" "${OUT_DIR}/${file}"
  fi
done

for dir in "${INCLUDE_DIRS[@]}"; do
  if [[ -d "${ROOT_DIR}/${dir}" ]]; then
    rsync -a --delete \
      --exclude 'build/' \
      --exclude '.gradle/' \
      --exclude '*.iml' \
      --exclude '.DS_Store' \
      "${ROOT_DIR}/${dir}/" "${OUT_DIR}/${dir}/"
  fi
done

cat <<MSG
Framework template generated:
  ${OUT_DIR}

Next steps:
  1. Validate with ./gradlew :shared:check
  2. Tag the framework-template branch (vX.Y.Z-template)
MSG
