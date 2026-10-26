#!/usr/bin/env python3
"""Create/delete only a fresh CI-owned TV simulator. No local simulator resets."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def select_runtime(data):
    runtimes = [runtime for runtime in data["runtimes"]
                if ".tvOS-" in runtime["identifier"] and runtime.get("isAvailable")
                and int(runtime["version"].split(".")[0]) >= 17]
    if not runtimes:
        raise ValueError("The selected Xcode has no available tvOS 17+ runtime")
    runtime = max(runtimes, key=lambda value: tuple(map(int, value["version"].split("."))))
    devices = [device for device in runtime.get("supportedDeviceTypes", []) if "Apple TV 4K" in device["name"]]
    if not devices:
        raise ValueError("No Apple TV 4K device type is supported by the installed TV runtime")
    return runtime["identifier"], sorted(devices, key=lambda device: device["identifier"])[-1]["identifier"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("create", "delete"))
    args = parser.parse_args()
    try:
        if os.environ.get("GITHUB_ACTIONS") != "true":
            raise ValueError("Simulator creation/deletion helper is CI-only; use an explicit local UDID with tvos-release.py local")
        marker = Path(os.environ["RUNNER_TEMP"]) / "phoenix-tv-owned-simulator"
        if args.action == "delete":
            if marker.exists():
                udid = marker.read_text().strip()
                if not re.fullmatch(r"[0-9A-Fa-f-]{36}", udid):
                    raise ValueError("Invalid owned simulator marker")
                subprocess.run(["xcrun", "simctl", "delete", udid], check=True)
                marker.unlink()
            return 0
        if marker.exists():
            raise ValueError("Owned simulator marker already exists; refusing to orphan it")
        data = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "runtimes", "--json"], text=True))
        runtime, device = select_runtime(data)
        udid = subprocess.check_output(["xcrun", "simctl", "create", "Phoenix TV CI", device, runtime], text=True).strip()
        if not re.fullmatch(r"[0-9A-Fa-f-]{36}", udid):
            raise ValueError("simctl did not return a simulator UDID")
        marker.write_text(udid + "\n")
        with open(os.environ["GITHUB_ENV"], "a") as output:
            output.write("TVOS_SIMULATOR_UDID=" + udid + "\n")
        subprocess.run(["xcrun", "simctl", "boot", udid], check=True)
        subprocess.run(["xcrun", "simctl", "bootstatus", udid, "-b"], check=True)
        return 0
    except subprocess.CalledProcessError as error:
        return error.returncode
    except (ValueError, KeyError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
