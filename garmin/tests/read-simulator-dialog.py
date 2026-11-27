"""Read only accessible names under the simulator Error dialog on this Xvfb."""
import sys

import pyatspi


def labels(node, depth=0):
    if depth > 8:
        return
    print(node.getRoleName(), repr(node.name), flush=True)
    for child in node:
        if child is not None:
            labels(child, depth + 1)


try:
    for app in pyatspi.Registry.getDesktop(0):
        for window in app:
            if window.name == "Error":
                labels(window)
except Exception as exc:
    print(f"Dialog observer unavailable: {exc}", file=sys.stderr)
    sys.exit(1)