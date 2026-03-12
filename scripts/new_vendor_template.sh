#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATE_DIR="$ROOT_DIR/template/starter"

usage() {
  cat <<USAGE
Usage: scripts/new_vendor_template.sh --vendor <VendorName> --package <package.id> [--out <dir>] [--registry <file>]
USAGE
}

VENDOR=""
PACKAGE_ID=""
OUT_DIR=""
REGISTRY_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --vendor) VENDOR="$2"; shift 2 ;;
    --package) PACKAGE_ID="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    --registry) REGISTRY_FILE="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown arg: $1"; usage; exit 1 ;;
  esac
done

if [[ -z "$VENDOR" || -z "$PACKAGE_ID" ]]; then
  usage
  exit 1
fi

VENDOR_LOWER="$(echo "$VENDOR" | tr '[:upper:]' '[:lower:]')"
TARGET_DIR="${OUT_DIR:-$ROOT_DIR/template/generated/$VENDOR_LOWER}"

mkdir -p "$TARGET_DIR"
cp -R "$TEMPLATE_DIR"/. "$TARGET_DIR/"

for file in "$TARGET_DIR"/*.kt; do
  sed -i "s/package com\.phoenix\.vendor\.template/package ${PACKAGE_ID//./\.}/g" "$file"
  sed -i "s/interface VendorPlugin/interface ${VENDOR}Plugin/g" "$file"
  sed -i "s/object PluginRegistry/object ${VENDOR}PluginRegistry/g" "$file"
done

[[ -f "$TARGET_DIR/VendorPlugin.kt" ]] && mv "$TARGET_DIR/VendorPlugin.kt" "$TARGET_DIR/${VENDOR}Plugin.kt"
[[ -f "$TARGET_DIR/PluginRegistry.kt" ]] && mv "$TARGET_DIR/PluginRegistry.kt" "$TARGET_DIR/${VENDOR}PluginRegistry.kt"

if [[ -n "$REGISTRY_FILE" && -f "$REGISTRY_FILE" ]]; then
  ENTRY="register(${VENDOR}Plugin())"
  if ! grep -q "$ENTRY" "$REGISTRY_FILE"; then
    printf '\n    // Added by new_vendor_template.sh\n    %s\n' "$ENTRY" >> "$REGISTRY_FILE"
  fi
fi

echo "Created vendor scaffold for $VENDOR at $TARGET_DIR"
