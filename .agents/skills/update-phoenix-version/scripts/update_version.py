#!/usr/bin/env python3
"""Update Project Phoenix app version metadata."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path


VERSION_PATTERN = re.compile(r"^\d+\.\d+\.\d+$")
ANDROID_CODE_MIN = 1
ANDROID_CODE_MAX = 2_100_000_000


@dataclass(frozen=True)
class Target:
    label: str
    relative_path: Path
    pattern: re.Pattern[str]
    expected_matches: int
    replacement_value: str


@dataclass
class TargetResult:
    label: str
    relative_path: Path
    old_values: list[str]
    new_value: str

    @property
    def changed(self) -> bool:
        return any(value != self.new_value for value in self.old_values)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Update Project Phoenix Android, iOS, and Settings app version metadata.",
    )
    parser.add_argument("--version", required=True, help="Marketing/app version, for example 0.10.0.")
    parser.add_argument(
        "--android-code",
        type=int,
        help="Optional Android default versionCode for androidApp/build.gradle.kts.",
    )
    parser.add_argument(
        "--ios-build",
        help="Optional iOS CURRENT_PROJECT_VERSION build number for the Xcode project.",
    )
    parser.add_argument(
        "--repo-root",
        type=Path,
        help="Repository root. Defaults to the current directory or an ancestor of this script.",
    )
    parser.add_argument("--dry-run", action="store_true", help="Report changes without writing files.")
    parser.add_argument("--check", action="store_true", help="Verify values without writing files.")
    return parser.parse_args()


def validate_args(args: argparse.Namespace) -> None:
    if not VERSION_PATTERN.fullmatch(args.version):
        raise ValueError("--version must use numeric x.y.z format, for example 0.10.0.")

    if args.android_code is not None and not (ANDROID_CODE_MIN <= args.android_code <= ANDROID_CODE_MAX):
        raise ValueError(f"--android-code must be between {ANDROID_CODE_MIN} and {ANDROID_CODE_MAX}.")

    if args.ios_build is not None and not re.fullmatch(r"[1-9]\d*", args.ios_build):
        raise ValueError("--ios-build must be a positive integer string.")

    if args.dry_run and args.check:
        raise ValueError("Use either --dry-run or --check, not both.")


def has_repo_markers(path: Path) -> bool:
    return (
        (path / "settings.gradle.kts").is_file()
        and (path / "androidApp").is_dir()
        and (path / "iosApp").is_dir()
        and (path / "shared").is_dir()
    )


def find_repo_root(explicit: Path | None) -> Path:
    starts: list[Path]
    if explicit is not None:
        starts = [explicit]
    else:
        starts = [Path.cwd(), Path(__file__).resolve().parent]

    checked: set[Path] = set()
    for start in starts:
        resolved_start = start.resolve()
        for candidate in (resolved_start, *resolved_start.parents):
            if candidate in checked:
                continue
            checked.add(candidate)
            if has_repo_markers(candidate):
                return candidate

    raise FileNotFoundError("Could not find Project Phoenix repo root.")


def make_targets(args: argparse.Namespace) -> list[Target]:
    version = args.version
    targets = [
        Target(
            label="Android versionName",
            relative_path=Path("androidApp/build.gradle.kts"),
            pattern=re.compile(r'^(\s*versionName\s*=\s*")([^"]+)(".*)$', re.MULTILINE),
            expected_matches=1,
            replacement_value=version,
        ),
        Target(
            label="Settings/shared APP_VERSION",
            relative_path=Path("shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Constants.kt"),
            pattern=re.compile(r'^(\s*const\s+val\s+APP_VERSION\s*=\s*")([^"]+)(".*)$', re.MULTILINE),
            expected_matches=1,
            replacement_value=version,
        ),
        Target(
            label="iOS MARKETING_VERSION",
            relative_path=Path("iosApp/VitruvianPhoenix/VitruvianPhoenix.xcodeproj/project.pbxproj"),
            pattern=re.compile(r"^(\s*MARKETING_VERSION\s*=\s*)([^;]+)(;.*)$", re.MULTILINE),
            expected_matches=2,
            replacement_value=version,
        ),
    ]

    if args.android_code is not None:
        targets.append(
            Target(
                label="Android default versionCode",
                relative_path=Path("androidApp/build.gradle.kts"),
                pattern=re.compile(
                    r"^(\s*versionCode\s*=\s*injectedVersionCode\s*\?:\s*)(\d+)(\s*)$",
                    re.MULTILINE,
                ),
                expected_matches=1,
                replacement_value=str(args.android_code),
            ),
        )

    if args.ios_build is not None:
        targets.append(
            Target(
                label="iOS CURRENT_PROJECT_VERSION",
                relative_path=Path("iosApp/VitruvianPhoenix/VitruvianPhoenix.xcodeproj/project.pbxproj"),
                pattern=re.compile(r"^(\s*CURRENT_PROJECT_VERSION\s*=\s*)([0-9]+)(;.*)$", re.MULTILINE),
                expected_matches=2,
                replacement_value=args.ios_build,
            ),
        )

    return targets


def apply_target(text: str, target: Target) -> tuple[str, TargetResult]:
    matches = list(target.pattern.finditer(text))
    if len(matches) != target.expected_matches:
        raise ValueError(
            f"{target.label}: expected {target.expected_matches} matches in "
            f"{target.relative_path}, found {len(matches)}.",
        )

    old_values = [match.group(2).strip() for match in matches]

    def replace(match: re.Match[str]) -> str:
        return f"{match.group(1)}{target.replacement_value}{match.group(3)}"

    updated_text = target.pattern.sub(replace, text)
    result = TargetResult(
        label=target.label,
        relative_path=target.relative_path,
        old_values=old_values,
        new_value=target.replacement_value,
    )
    return updated_text, result


def format_values(values: list[str]) -> str:
    unique = []
    for value in values:
        if value not in unique:
            unique.append(value)
    return ", ".join(unique)


def main() -> int:
    try:
        args = parse_args()
        validate_args(args)
        repo_root = find_repo_root(args.repo_root)
        targets = make_targets(args)

        texts: dict[Path, str] = {}
        updated_texts: dict[Path, str] = {}
        results: list[TargetResult] = []

        for target in targets:
            path = repo_root / target.relative_path
            if not path.is_file():
                raise FileNotFoundError(f"Missing expected file: {target.relative_path}")

            current_text = updated_texts.get(path)
            if current_text is None:
                current_text = texts[path] = path.read_text(encoding="utf-8")

            new_text, result = apply_target(current_text, target)
            updated_texts[path] = new_text
            results.append(result)

        if args.check:
            mismatches = [result for result in results if result.changed]
            if mismatches:
                for result in mismatches:
                    print(
                        f"{result.label}: expected {result.new_value}, "
                        f"found {format_values(result.old_values)} in {result.relative_path}",
                        file=sys.stderr,
                    )
                return 1
            print(f"Version metadata is aligned in {repo_root}.")
            return 0

        for result in results:
            action = "update" if result.changed else "already"
            print(
                f"{action}: {result.label} in {result.relative_path}: "
                f"{format_values(result.old_values)} -> {result.new_value}"
            )

        if args.dry_run:
            print("Dry run only. No files written.")
            return 0

        for path, updated_text in updated_texts.items():
            if texts[path] != updated_text:
                path.write_text(updated_text, encoding="utf-8", newline="\n")

        print(f"Updated version metadata in {repo_root}.")
        return 0

    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
