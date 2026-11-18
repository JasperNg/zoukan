#!/usr/bin/env python3
"""Coverage of the lines a pull request changes, from a Kover (JaCoCo-format) XML report.

Reads `git diff --unified=0 <base>...HEAD` (or a saved diff with --diff-file) and the report that
`./gradlew :koverXmlReportUnit` writes, and prints a Markdown summary: for each changed Kotlin or
Java file, how many of its changed executable lines the unit tier ran, and which it did not.

Only the project's own lines count. In android-compat, which is vendored, a line is the
project's only inside its ZOUKAN markers (see VendoredFilesMarkedTest); elsewhere every line is,
so upstream files show up as they are. Changed files the report does not know (Android-only code,
modules without coverage, build scripts) are listed apart. Test sources are left out.

The summary is information only: the exit status is 0 whatever the coverage. It is not 0 when the
script itself fails (no report, git failing, a malformed diff), so a broken script is seen.

    python3 .github/scripts/changed_line_coverage.py --base origin/master \\
        --report build/reports/kover/reportUnit.xml >> "$GITHUB_STEP_SUMMARY"
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field

SOURCE_EXTENSIONS = (".kt", ".java")
# Test source sets: src/test, src/desktopTest, src/androidUnitTest, src/commonTest...
TEST_SOURCE = re.compile(r"(^|/)src/(test|[A-Za-z]*Test)/")
HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")

OPENER = re.compile(r"^\s*// ZOUKAN -->\s*$")
CLOSER = re.compile(r"^\s*// ZOUKAN <--\s*$")
WHOLE_OPENER = re.compile(r"^\s*// ZOUKAN: whole file -->\s*$")
WHOLE_CLOSER = re.compile(r"^\s*// ZOUKAN: whole file <--\s*$")
INLINE_PAIR = re.compile(r"/\* ZOUKAN --> \*/.*?/\* ZOUKAN <-- \*/")
VENDORED_PREFIX = "android-compat/"


def parse_diff(diff: str) -> dict[str, set[int]]:
    """The added or changed line numbers (new side) of each file in a --unified=0 diff."""
    changed: dict[str, set[int]] = {}
    path: str | None = None
    for line in diff.splitlines():
        if line.startswith("+++ "):
            target = line[4:].strip()
            if target.startswith('"') and target.endswith('"'):
                target = target[1:-1]
            if target == "/dev/null":
                path = None
            elif target.startswith("b/"):
                path = target[2:]
            else:
                raise ValueError(f"Unexpected diff target line: {line!r}")
            continue
        match = HUNK.match(line)
        if match and path is not None:
            start = int(match.group(1))
            count = int(match.group(2)) if match.group(2) is not None else 1
            changed.setdefault(path, set()).update(range(start, start + count))
    return {p: lines for p, lines in changed.items() if lines}


@dataclass
class FileLines:
    """Per line number: whether the report has the line as covered (any instruction ran)."""

    covered: dict[int, bool] = field(default_factory=dict)


def parse_report(report_path: str) -> dict[str, FileLines]:
    """Kover's XML report as {"package/dir/File.kt": FileLines}. Merges duplicates of a file."""
    files: dict[str, FileLines] = {}
    package = ""
    for event, element in ET.iterparse(report_path, events=("start", "end")):
        if event == "start" and element.tag == "package":
            package = element.get("name", "")
        elif event == "end" and element.tag == "sourcefile":
            name = element.get("name", "")
            key = f"{package}/{name}" if package else name
            entry = files.setdefault(key, FileLines())
            for line in element.findall("line"):
                number = int(line.get("nr", "0"))
                covered = int(line.get("ci", "0")) > 0
                entry.covered[number] = entry.covered.get(number, False) or covered
            element.clear()
        elif event == "end" and element.tag in ("class", "package"):
            element.clear()
    if not files:
        raise ValueError(f"No source files in the coverage report {report_path}")
    return files


def find_in_report(path: str, report: dict[str, FileLines]) -> FileLines | None:
    """The report's entry for a repository path: by package folder, or by a file name used once."""
    by_package = [key for key in report if path == key or path.endswith("/" + key)]
    if by_package:
        return report[max(by_package, key=len)]
    name = path.rsplit("/", 1)[-1]
    by_name = [key for key in report if key.rsplit("/", 1)[-1] == name]
    return report[by_name[0]] if len(by_name) == 1 else None


def own_lines(path: str, text: str | None) -> set[int] | None:
    """The 1-based lines that are the project's own, or None when every line is."""
    if not path.startswith(VENDORED_PREFIX):
        return None
    if text is None:
        return set()
    own: set[int] = set()
    inside = False
    for number, raw in enumerate(text.splitlines(), start=1):
        line = INLINE_PAIR.sub("", raw)
        if OPENER.match(line) or WHOLE_OPENER.match(line):
            inside = True
            own.add(number)
        elif CLOSER.match(line) or WHOLE_CLOSER.match(line):
            inside = False
            own.add(number)
        elif inside or INLINE_PAIR.search(raw):
            own.add(number)
    return own


def ranges(numbers: list[int]) -> str:
    """1, 2, 3, 7 as "1-3, 7"."""
    parts: list[str] = []
    start = previous = None
    for n in sorted(numbers):
        if start is None:
            start = previous = n
        elif n == previous + 1:
            previous = n
        else:
            parts.append(f"{start}" if start == previous else f"{start}-{previous}")
            start = previous = n
    if start is not None:
        parts.append(f"{start}" if start == previous else f"{start}-{previous}")
    return ", ".join(parts)


def summarize(changed: dict[str, set[int]], report: dict[str, FileLines], read_file) -> str:
    """The Markdown summary. read_file(path) returns the file's text at HEAD, or None."""
    rows: list[tuple[str, int, int, list[int]]] = []
    not_measured: list[str] = []
    vendored_only: list[str] = []
    for path in sorted(changed):
        if not path.endswith(SOURCE_EXTENSIONS) or TEST_SOURCE.search(path):
            continue
        lines = changed[path]
        own = own_lines(path, read_file(path))
        if own is not None:
            lines = lines & own
            if not lines:
                vendored_only.append(path)
                continue
        entry = find_in_report(path, report)
        if entry is None:
            not_measured.append(path)
            continue
        executable = sorted(n for n in lines if n in entry.covered)
        if not executable:
            continue
        missed = [n for n in executable if not entry.covered[n]]
        rows.append((path, len(executable), len(executable) - len(missed), missed))

    out = ["## Coverage of changed lines", ""]
    total = sum(r[1] for r in rows)
    hit = sum(r[2] for r in rows)
    if total:
        out.append(f"The unit tier runs **{hit} of {total}** changed executable lines ({100 * hit / total:.0f}%).")
    else:
        out.append("No changed executable line is in the coverage report.")
    out += ["", "Information only: nothing fails on low coverage. Upstream files count like the project's own,",
            "except in android-compat, where only the lines inside ZOUKAN markers count.", ""]
    if rows:
        out += ["| File | Changed lines run | Not run |", "|---|---|---|"]
        for path, count, covered, missed in rows:
            out.append(f"| `{path}` | {covered}/{count} | {ranges(missed) or '-'} |")
        out.append("")
    if not_measured:
        out.append("Not in the report (no unit-tier coverage for this code, such as Android-only code):")
        out += [f"- `{p}`" for p in not_measured]
        out.append("")
    if vendored_only:
        out.append("Changed only outside the ZOUKAN markers (vendored code, not counted):")
        out += [f"- `{p}`" for p in vendored_only]
        out.append("")
    return "\n".join(out)


def git(*args: str, cwd: str) -> str:
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--report", required=True, help="Kover XML report (koverXmlReportUnit)")
    parser.add_argument("--base", help="the base to compare with, as in git diff <base>...HEAD")
    parser.add_argument("--diff-file", help="a saved `git diff --unified=0` to read instead of running git")
    parser.add_argument("--repo", default=".", help="the repository root (default: the current folder)")
    args = parser.parse_args(argv)
    if (args.base is None) == (args.diff_file is None):
        parser.error("give exactly one of --base and --diff-file")

    if args.diff_file:
        with open(args.diff_file, encoding="utf-8") as f:
            diff = f.read()
    else:
        diff = git("diff", "--unified=0", "--no-color", "--no-ext-diff", "--no-renames",
                   "--src-prefix=a/", "--dst-prefix=b/",
                   f"{args.base}...HEAD", "--", *[f"*{e}" for e in SOURCE_EXTENSIONS], cwd=args.repo)

    def read_file(path: str) -> str | None:
        full = os.path.join(args.repo, path)
        if not os.path.isfile(full):
            return None
        with open(full, encoding="utf-8", errors="replace") as f:
            return f.read()

    print(summarize(parse_diff(diff), parse_report(args.report), read_file))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
