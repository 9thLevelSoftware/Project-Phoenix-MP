#!/usr/bin/env bash
#
# iOS Schema Validator
# ====================
# Validates that DriverFactory.ios.kt table definitions match VitruvianDatabase.sq
#
# Background: iOS uses manual table creation (Layer 4 defense against migration crashes).
# The table schemas in DriverFactory.ios.kt MUST exactly match VitruvianDatabase.sq,
# otherwise fresh iOS installs will crash with SQLiteException when SQLDelight queries
# try to access columns that don't exist.
#
# This script validates:
# 1. All SQLDelight tables exist in iOS DriverFactory
# 2. iOS doesn't define extra tables
# 3. Every column in every table matches, including order
#
# Run in CI to catch schema drift before it causes crashes.
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Go up two levels from .github/scripts to project root
PROJECT_ROOT="$(dirname "$(dirname "$SCRIPT_DIR")")"

SQ_FILE="$PROJECT_ROOT/shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq"
IOS_FILE="$PROJECT_ROOT/shared/src/iosMain/kotlin/com/devil/phoenixproject/data/local/DriverFactory.ios.kt"

echo "iOS Schema Validator"
echo "===================="
echo ""

# Check files exist
if [ ! -f "$SQ_FILE" ]; then
    echo "ERROR: SQLDelight schema not found: $SQ_FILE"
    exit 1
fi

if [ ! -f "$IOS_FILE" ]; then
    echo "ERROR: iOS DriverFactory not found: $IOS_FILE"
    exit 1
fi

echo "Comparing:"
echo "  SQLDelight: $(basename $SQ_FILE)"
echo "  iOS:        $(basename $IOS_FILE)"
echo ""

PYTHON_BIN=""
if command -v python3 >/dev/null 2>&1; then
    PYTHON_BIN="python3"
elif command -v python >/dev/null 2>&1; then
    PYTHON_BIN="python"
else
    echo "ERROR: python3 or python is required to validate schema parity"
    exit 1
fi

"$PYTHON_BIN" - "$SQ_FILE" "$IOS_FILE" <<'PY'
import re
import sys
from pathlib import Path

sq_file = Path(sys.argv[1])
ios_file = Path(sys.argv[2])


def strip_sql_comments(text: str) -> str:
    cleaned_lines = []
    for line in text.splitlines():
        cleaned_lines.append(line.split("--", 1)[0])
    return "\n".join(cleaned_lines)


def split_top_level(body: str) -> list[str]:
    parts = []
    current = []
    depth = 0
    quote = None
    i = 0

    while i < len(body):
        char = body[i]
        if quote:
            current.append(char)
            if char == quote:
                if quote == "'" and i + 1 < len(body) and body[i + 1] == quote:
                    current.append(body[i + 1])
                    i += 1
                else:
                    quote = None
        else:
            if char in ("'", '"'):
                quote = char
                current.append(char)
            elif char == "(":
                depth += 1
                current.append(char)
            elif char == ")":
                depth -= 1
                current.append(char)
            elif char == "," and depth == 0:
                part = "".join(current).strip()
                if part:
                    parts.append(part)
                current = []
            else:
                current.append(char)
        i += 1

    tail = "".join(current).strip()
    if tail:
        parts.append(tail)
    return parts


def extract_columns(body: str) -> list[str]:
    columns = []
    for part in split_top_level(body):
        stripped = part.strip()
        if not stripped:
            continue
        upper = stripped.upper()
        if upper.startswith(("FOREIGN KEY", "PRIMARY KEY", "UNIQUE", "CHECK", "CONSTRAINT")):
            continue
        columns.append(stripped.split()[0].strip('`"'))
    return columns


def extract_sqldelight_tables(text: str) -> dict[str, list[str]]:
    clean_text = strip_sql_comments(text)
    pattern = re.compile(r"CREATE TABLE\s+(?:IF NOT EXISTS\s+)?(\w+)\s*\((.*?)\);", re.S)
    return {
        match.group(1): extract_columns(match.group(2))
        for match in pattern.finditer(clean_text)
    }


def extract_ios_tables(text: str) -> dict[str, list[str]]:
    pattern = re.compile(r"CREATE TABLE IF NOT EXISTS\s+(\w+)\s*\((.*?)\)\s*\"\"\"", re.S)
    return {
        match.group(1): extract_columns(strip_sql_comments(match.group(2)))
        for match in pattern.finditer(text)
    }


sql_tables = extract_sqldelight_tables(sq_file.read_text(encoding="utf-8"))
ios_tables = extract_ios_tables(ios_file.read_text(encoding="utf-8"))

errors: list[str] = []
column_count = 0

print("Step 1: Checking table parity...")
missing_tables = sorted(set(sql_tables) - set(ios_tables))
extra_tables = sorted(set(ios_tables) - set(sql_tables))
if missing_tables:
    for table in missing_tables:
        errors.append(f"Table '{table}' exists in SQLDelight but is missing from iOS DriverFactory")
if extra_tables:
    for table in extra_tables:
        errors.append(f"Table '{table}' exists in iOS DriverFactory but not in SQLDelight")
if not missing_tables and not extra_tables:
    print(f"  OK: All {len(sql_tables)} tables present in both files")

print("")
print("Step 2: Checking full column parity...")
for table in sorted(sql_tables):
    if table not in ios_tables:
        continue

    sql_columns = sql_tables[table]
    ios_columns = ios_tables[table]
    column_count += len(sql_columns)

    missing_columns = [column for column in sql_columns if column not in ios_columns]
    extra_columns = [column for column in ios_columns if column not in sql_columns]

    if missing_columns:
        errors.append(
            f"Table '{table}' is missing iOS columns: {', '.join(missing_columns)}"
        )
    if extra_columns:
        errors.append(
            f"Table '{table}' has extra iOS columns: {', '.join(extra_columns)}"
        )
    if not missing_columns and not extra_columns and sql_columns != ios_columns:
        mismatches = []
        for index, (sql_column, ios_column) in enumerate(zip(sql_columns, ios_columns), start=1):
            if sql_column != ios_column:
                mismatches.append(f"{index}: expected {sql_column}, found {ios_column}")
        errors.append(
            f"Table '{table}' column order differs between SQLDelight and iOS: "
            + "; ".join(mismatches)
        )

if not errors:
    print(f"  OK: All {column_count} SQLDelight columns match iOS definitions")

print("")
print("====================")
if errors:
    print(f"FAILED: Found {len(errors)} schema mismatch error(s)")
    print("")
    for error in errors:
        print(f"  ERROR: {error}")
    print("")
    print("To fix: Update shared/src/iosMain/kotlin/.../DriverFactory.ios.kt")
    print("        to match shared/src/commonMain/sqldelight/.../VitruvianDatabase.sq")
    print("")
    print("See: .planning/debug/resolved/issue-223-ios-fresh-install-sqlite-crash.md")
    sys.exit(1)

print("SUCCESS: iOS schema validation passed")
print("")
print(f"Tables checked: {len(sql_tables)}")
print(f"Columns checked: {column_count}")
PY
