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
# 480s: upstream samples pass at 180s, but the Phoenix Run No Evil suite is
# larger and cold simulator device boots on shared runners are slow.
exec timeout --signal=TERM --kill-after=10s 480s \
    xvfb-run -a -s "-screen 0 1280x1024x24" dbus-run-session -- \
    bash -euo pipefail -c '
        log_dir="$(mktemp -d)"
        simulator_pid=""
        observer_pid=""
        device=$2
        cleanup() {
            printf "[%s] cleanup device=%s\n" "$(date -u +%T)" "$device" >&2
            if [[ -n "$observer_pid" ]]; then
                kill "$observer_pid" 2>/dev/null || true
                wait "$observer_pid" 2>/dev/null || true
            fi
            if [[ -n "$simulator_pid" ]]; then
                kill "$simulator_pid" 2>/dev/null || true
                wait "$simulator_pid" 2>/dev/null || true
            fi
            if [[ -s "$log_dir/simulator.log" ]]; then
                echo "::group::ConnectIQ simulator log" >&2
                cat "$log_dir/simulator.log" >&2
                echo "::endgroup::" >&2
            fi
            if [[ -s "$log_dir/tests.log" ]]; then
                echo "::group::MonkeyDo output (incl. partial on timeout)" >&2
                cat "$log_dir/tests.log" >&2
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
                printf "[%s] simulator pid=%s port=1234 accepting connections\n" "$(date -u +%T)" "$simulator_pid"
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
        printf "[%s] launching monkeydo prg=%s device=%s\n" "$(date -u +%T)" "$1" "$2"
        # Read-only snapshot from this isolated virtual display/session. Never
        # treat an open port, visible window or thread state as a test verdict.
        (
            sleep 45
            printf "[%s] progress device=%s captured_bytes=%s\n" "$(date -u +%T)" "$2" "$(wc -c < "$log_dir/tests.log")"
            ss -tnp "( sport = :1234 or dport = :1234 )"
            xwininfo -root -tree
            # Passive observation only: do not dismiss or interact with a dialog.
            if [[ "$device" == fenix7 && -n "${RUNNER_TEMP:-}" ]] && command -v xwd >/dev/null && command -v convert >/dev/null; then
                if xwininfo -name Error >/dev/null 2>&1; then
                    timeout 5s xwd -silent -name Error | convert xwd:- "$RUNNER_TEMP/phoenix-simulator-error.png"
                    timeout 10s /usr/bin/python3 garmin/tests/read-simulator-dialog.py
                fi
            fi
            tree=$(pstree -Tp "$$")
            printf "%s\n" "$tree"
            for pid in $(printf "%s\n" "$tree" | grep -oE "java\([0-9]+\)" | tr -cd "0-9\n"); do
                timeout 10s jcmd "$pid" Thread.print
            done
        ) >&2 &
        observer_pid=$!
        "$CONNECT_IQ_HOME/bin/monkeydo" "$1" "$2" -t 2>&1 | tee "$log_dir/tests.log"
        pipeline_status=("${PIPESTATUS[@]}")
        set -e
        printf "[%s] monkeydo exit=%s tee exit=%s\n" "$(date -u +%T)" "${pipeline_status[0]}" "${pipeline_status[1]}"
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
