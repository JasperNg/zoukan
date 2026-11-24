#!/usr/bin/env bash
set -euo pipefail

if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

log_dir=$(mktemp -d)
cleanup() {
  ./gradlew --stop >/dev/null 2>&1 || true
  rm -rf "$log_dir"
}
trap cleanup EXIT

run_gradle_collect_checksums() {
  local name="$1"
  shift
  local log="$log_dir/$name.log"
  local status=0
  local tee_status=0

  local pipe_status=()
  set +e
  "$@" 2>&1 | tee "$log"
  pipe_status=("${PIPESTATUS[@]}")
  set -e

  status=${pipe_status[0]}
  tee_status=${pipe_status[1]}

  if [ "$status" -eq 0 ] && [ "$tee_status" -ne 0 ]; then
    status=$tee_status
  fi

  if [ "$status" -ne 0 ]; then
    if grep -E -q 'Could not resolve all (files|artifacts|dependencies) for configuration|> Could not (resolve|download|GET|HEAD|find) |Dependency verification failed|Connection reset|Read timed out|UnknownHostException' "$log"; then
      echo "::error::$name failed due to dependency or network resolution (exit $status); aborting metadata refresh." >&2
      return "$status"
    fi
    echo "::warning::$name exited with status $status (not a dependency/network failure); keeping collected checksums." >&2
  fi

  return 0
}

for platform in linux-x64 windows-x64 macos-arm64; do
  echo "Resolving desktop classpaths for $platform..."
  ./gradlew -I gradle/resolve-desktop-classpaths.init.gradle.kts \
    --write-verification-metadata sha256 \
    "-Pzoukan.targetPlatform=$platform" \
    :desktop:resolveDesktopClasspaths
done

echo "Refreshing Spotless and desktop/KMP verification checksums..."
run_gradle_collect_checksums desktop-and-lint \
  ./gradlew --write-verification-metadata sha256 --continue \
  spotlessCheck \
  :desktop:assemble :desktop:testClasses :desktop:toolsClasses \
  :extension-jvm:testClasses :android-compat:testClasses \
  :backup:desktopTestClasses :data:desktopTestClasses :domain:desktopTestClasses \
  :core:common:desktopTestClasses :source-api:desktopTestClasses \
  -x distTar -x distZip

if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/platforms" ]; then
  echo "Refreshing Android verification checksums..."
  run_gradle_collect_checksums android \
    ./gradlew --write-verification-metadata sha256 --continue \
    :app:compileDebugKotlin :app:compileDebugUnitTestKotlin \
    :data:compileAndroidMain :domain:compileAndroidMain :domain:compileAndroidHostTest \
    :core:common:compileAndroidMain :source-api:compileAndroidMain \
    :app:testDebugUnitTest :domain:testAndroidHostTest
else
  echo "::warning::ANDROID_HOME is not set or lacks a platforms directory; skipping Android verification checksum refresh." >&2
fi

python3 - <<'PY'
import sys
import xml.etree.ElementTree as ET

path = "gradle/verification-metadata.xml"
ns = {"v": "https://schema.gradle.org/dependency-verification"}

try:
    tree = ET.parse(path)
except ET.ParseError as exc:
    print(f"::error::{path} is not valid XML: {exc}", file=sys.stderr)
    sys.exit(1)

root = tree.getroot()
verify_metadata = root.find("./v:configuration/v:verify-metadata", ns)
if verify_metadata is None or (verify_metadata.text or "").strip().lower() != "true":
    print(f"::error::{path} must keep <verify-metadata>true</verify-metadata>", file=sys.stderr)
    sys.exit(1)

dups = []
for comp in root.findall(".//v:component", ns):
    group = comp.attrib.get("group", "")
    name = comp.attrib.get("name", "")
    version = comp.attrib.get("version", "")
    for art in comp.findall("v:artifact", ns):
        hashes = art.findall("v:sha256", ns)
        if len(hashes) > 1:
            art_name = art.attrib.get("name", "")
            dups.append(f"{group}:{name}:{version} ({art_name}: {len(hashes)} sha256 elements)")

if dups:
    print(
        f"::error::{path} contains artifacts with multiple sha256 hashes (possible mutated artifact): "
        + "; ".join(dups),
        file=sys.stderr,
    )
    sys.exit(1)

print(f"Verified {path}: XML valid, verify-metadata=true, no duplicate artifact hashes.")
PY
