#!/usr/bin/env bash
#
# run-static-checks.sh - offline sanity checks for the Phoenix Remote watch
# app (issue #1391 B7). These run on any machine with bash + xmllint and are
# the first CI gate before the simulator tests run per product.
#
# Checks:
#   1  XML resources are well-formed (manifest, strings, drawables)
#   2  manifest product allowlist matches the documented shortlist
#   3  manifest entry point exists
#   4  README documents every allowlisted product
#   5  CI workflow builds exactly the allowlisted products
#   6  CI workflow pins the Connect IQ SDK version (never "latest")
#   7  no fake Gradle Garmin task anywhere (watch app is NOT in the mobile build)
#   8  wire-contract enum strings are intact (actions, stages, reasons, readiness)
#   9  every Rez.Strings id referenced in Monkey C exists in strings.xml
#  10  no hard-coded English literals in drawText calls (strings.xml instead)
#  11  no Phase-2 dashboard copy shipped in string resources
#  12  rough Monkey C brace balance per source file
#  13  shell scripts pass bash -n
#
set -euo pipefail

cd "$(dirname "$0")/.."   # garmin/
REPO_ROOT="$(cd .. && pwd)"
FAILURES=0

fail() {
    echo "FAIL: $*" >&2
    FAILURES=$((FAILURES + 1))
}

pass() {
    echo "ok:   $*"
}

# ---------------------------------------------------------------- 1
for f in manifest.xml resources/strings/strings.xml resources/drawables/drawables.xml; do
    if xmllint --noout "$f" 2>/dev/null; then
        pass "well-formed XML: $f"
    else
        fail "malformed XML: $f"
    fi
done

# ---------------------------------------------------------------- 2
EXPECTED_PRODUCTS="fenix7 fr255 fr265 fr965"
MANIFEST_PRODUCTS="$(grep -o '<iq:product id="[^"]*"' manifest.xml | sed 's/.*id="//;s/"//' | sort | tr '\n' ' ' | sed 's/ $//')"
if [[ "$MANIFEST_PRODUCTS" == "$(echo $EXPECTED_PRODUCTS)" ]]; then
    pass "manifest allowlist == expected ($EXPECTED_PRODUCTS)"
else
    fail "manifest allowlist mismatch: got [$MANIFEST_PRODUCTS] want [$(echo $EXPECTED_PRODUCTS)]"
fi

# ---------------------------------------------------------------- 3
ENTRY_POINT="$(grep -o 'entry="[^"]*"' manifest.xml | sed 's/entry="//;s/"//')"
if [[ -f "source/${ENTRY_POINT}.mc" ]]; then
    pass "manifest entry point exists: source/${ENTRY_POINT}.mc"
else
    fail "manifest entry point missing: source/${ENTRY_POINT}.mc"
fi

# ---------------------------------------------------------------- 4
for p in fr255 fr265 fr965 fenix7; do
    if grep -q "$p" README.md; then
        pass "README documents product $p"
    else
        fail "README does not document product $p"
    fi
done

# ---------------------------------------------------------------- 5
CI_WORKFLOW="$REPO_ROOT/.github/workflows/garmin-ci.yml"
if [[ ! -f "$CI_WORKFLOW" ]]; then
    fail "missing $CI_WORKFLOW"
else
    for p in $EXPECTED_PRODUCTS; do
        if grep -q -- "- $p" "$CI_WORKFLOW"; then
            pass "CI matrix builds product $p"
        else
            fail "CI matrix missing product $p"
        fi
    done

    # ---------------------------------------------------------------- 6
    if grep -q 'sdk-version: *"latest"' "$CI_WORKFLOW"; then
        fail "CI must pin the Connect IQ SDK version (found 'latest')"
    elif grep -q 'sdk-version:' "$CI_WORKFLOW"; then
        pass "CI pins the Connect IQ SDK version"
    else
        fail "CI workflow does not set sdk-version"
    fi

    # ---------------------------------------------------------------- 7
    # Flag actual Gradle invocations only (explanatory comments may mention
    # that no Gradle wiring exists - that is not a fake task).
    if grep -Eq 'gradlew|(^|[^A-Za-z])gradle(\s|$)' "$CI_WORKFLOW"; then
        fail "garmin-ci.yml must not run Gradle (no fake Garmin Gradle task)"
    else
        pass "no Gradle invocations in garmin-ci.yml"
    fi
fi

# ---------------------------------------------------------------- 8
CONTRACT_STRINGS=(
    START_NEXT STOP_SET CANCEL_EXERCISE
    ACCEPTED APPLIED REJECTED
    STALE_STATE NOT_READY DISCONNECTED UNSUPPORTED_VERSION DISABLED BUSY
    RECOVERY_REQUIRED SAVE_FAILED UNKNOWN_RESULT INVALID_MESSAGE
    PAYLOAD_TOO_LARGE UNAUTHORIZED_DEVICE COMMAND_ID_CONFLICT
    CONFIRMATION_EXPIRED EPOCH_CAP_REACHED
    DISABLED UNAVAILABLE SETUP CONNECTED ARMED_READY ACTIVE RESTING SUMMARY
    PHONE_INPUT_REQUIRED BUSY ERROR RECOVERY_REQUIRED
    HELLO STATE COMMAND RESULT
)
for s in "${CONTRACT_STRINGS[@]}"; do
    if grep -q "\"$s\"" source/PhoenixProtocol.mc; then
        :
    else
        fail "wire-contract string missing in PhoenixProtocol.mc: $s"
    fi
done
pass "wire-contract enum strings present in PhoenixProtocol.mc"

# ---------------------------------------------------------------- 9
MISSING=0
for id in $(grep -o 'Rez\.Strings\.[A-Za-z0-9_]*' source/*.mc | sed 's/.*Rez\.Strings\.//' | sort -u); do
    if ! grep -q "id=\"$id\"" resources/strings/strings.xml; then
        fail "Rez.Strings.$id referenced in code but missing from strings.xml"
        MISSING=1
    fi
done
if [[ "$MISSING" -eq 0 ]]; then
    pass "every Rez.Strings id used in code exists in strings.xml"
fi

# ---------------------------------------------------------------- 10
if grep -n 'drawText.*"[A-Za-z][A-Za-z]' source/*.mc | grep -v '^\s*//' >/dev/null 2>&1; then
    fail "hard-coded English literal in a drawText call (use strings.xml)"
    grep -n 'drawText.*"[A-Za-z][A-Za-z]' source/*.mc >&2 || true
else
    pass "no hard-coded English literals in drawText calls"
fi

# ---------------------------------------------------------------- 11
# Scan only <string> values (comments may legitimately name Phase 2).
if grep '<string' resources/strings/strings.xml | grep -Eiq 'dashboard|1RM|volume PR|rest timer'; then
    fail "Phase-2 dashboard copy found in strings.xml values"
else
    pass "no Phase-2 dashboard copy in string resource values"
fi

# ---------------------------------------------------------------- 12
for f in source/*.mc tests/*.mc; do
    OPEN=$(grep -o '{' "$f" | wc -l | tr -d ' ')
    CLOSE=$(grep -o '}' "$f" | wc -l | tr -d ' ')
    if [[ "$OPEN" == "$CLOSE" ]]; then
        pass "brace balance: $f ($OPEN pairs)"
    else
        fail "unbalanced braces in $f: $OPEN '{' vs $CLOSE '}'"
    fi
done

# ---------------------------------------------------------------- 13
for f in tests/*.sh; do
    if bash -n "$f"; then
        pass "bash -n $f"
    else
        fail "syntax error in $f"
    fi
done

echo
if [[ "$FAILURES" -eq 0 ]]; then
    echo "PASSED: all static checks clean"
    exit 0
fi
echo "FAILED: $FAILURES static check(s) failed" >&2
exit 1
