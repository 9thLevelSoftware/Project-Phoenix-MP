#!/usr/bin/env bash
#
# run-simulator-tests.sh - run the Run No Evil protocol/input checks for one
# allowlisted product in the Connect IQ simulator (issue #1391 B7).
#
# Usage: run-simulator-tests.sh <tests.prg> <device_id>
#   e.g. run-simulator-tests.sh build/phoenix-tests.prg fr255
#
# Requires: CONNECT_IQ_HOME set (DuckSoft/setup-connectiq-actions does this),
# plus timeout, xvfb-run, dbus-run-session, tee and grep on the PATH.
#
# Simulator handling adapted from DuckSoft/setup-connectiq-actions
# (MIT License, Copyright (c) 2025 Vincent Hagen), which is itself based on
# blackshadev/garmin-connectiq-tools and matco/connectiq-tester.
#
set -euo pipefail
: "${CONNECT_IQ_HOME:?CONNECT_IQ_HOME must be set}"
if (( $# != 2 )); then
    echo "Usage: $0 tests.prg device_id" >&2
    exit 2
fi
if [[ ! -f "$1" ]]; then
    echo "::error::Compiled test binary not found at $1." >&2
    exit 1
fi
for command_name in timeout xvfb-run dbus-run-session tee grep; do
    command_path="$(command -v "$command_name" || true)"
    if [[ -z "$command_path" ]]; then
        echo "::error::Required command not found: $command_name." >&2
        exit 1
    fi
done
for sdk_executable in simulator monkeydo; do
    executable_path="${CONNECT_IQ_HOME}/bin/${sdk_executable}"
    if [[ ! -x "$executable_path" ]]; then
        echo "::error::ConnectIQ executable is missing or not executable: $executable_path." >&2
        exit 1
    fi
done
echo "::debug::Simulator check: SDK=$CONNECT_IQ_HOME tests=$1 device=$2"

# Keep simulator, D-Bus and monkeydo inside one virtual display session.
exec timeout --signal=TERM --kill-after=10s 240s \
    xvfb-run -a -s "-screen 0 1280x1024x24" dbus-run-session -- \
    bash -euo pipefail -c '
        log_dir="$(mktemp -d)"
        simulator_pid=""
        cleanup() {
            if [[ -n "$simulator_pid" ]]; then
                kill "$simulator_pid" 2>/dev/null || true
                wait "$simulator_pid" 2>/dev/null || true
            fi
            if [[ -s "$log_dir/simulator.log" ]]; then
                echo "::group::ConnectIQ simulator log" >&2
                cat "$log_dir/simulator.log" >&2
                echo "::endgroup::" >&2
            fi
            rm -rf "$log_dir"
        }
        trap cleanup EXIT
        trap "exit 143" TERM
        trap "exit 130" INT

        "$CONNECT_IQ_HOME/bin/simulator" >"$log_dir/simulator.log" 2>&1 &
        simulator_pid=$!

        # The native simulator serves MonkeyDo on TCP 1234.
        ready=false
        for (( attempt=0; attempt<60; attempt++ )); do
            if ! kill -0 "$simulator_pid" 2>/dev/null; then
                echo "::error::Simulator exited before becoming ready." >&2
                exit 1
            fi
            if (: > /dev/tcp/127.0.0.1/1234) 2>/dev/null; then
                ready=true
                break
            fi
            sleep 0.5
        done
        if [[ "$ready" != true ]]; then
            echo "::error::Simulator did not become ready on 127.0.0.1:1234 in 30s." >&2
            exit 1
        fi

        # SDK 9.x can return nonzero after reporting a valid passing result,
        # so the final MonkeyDo summary line is the authoritative verdict.
        set +e
        "$CONNECT_IQ_HOME/bin/monkeydo" "$1" "$2" -t 2>&1 | tee "$log_dir/tests.log"
        pipeline_status=("${PIPESTATUS[@]}")
        set -e
        if (( pipeline_status[1] != 0 )); then
            echo "::error::Failed to capture MonkeyDo output (tee exited ${pipeline_status[1]})." >&2
            exit "${pipeline_status[1]}"
        fi
        if grep -Eq "^PASSED \(passed=[1-9][0-9]*, failed=0, errors=0\)[[:space:]]*$" "$log_dir/tests.log"; then
            echo "::debug::Verified passing MonkeyDo result summary for $2"
        else
            echo "::error::MonkeyDo did not report a passing test summary for $2." >&2
            exit 1
        fi
    ' bash "$1" "$2"
