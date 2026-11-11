#!/bin/bash
# Zoukan clean-install check for macOS on Apple Silicon (Phase 7 gate, run locally; see
# desktop/RELEASING.md).
#
#   desktop/packaging/check/install-check-macos.sh [--skip-build] [--keep-installed]
#
# Builds the DMG (and notarizes it when ZOUKAN_NOTARY_* is set), mounts it, copies Zoukan.app to a
# fresh folder (ZOUKAN_INSTALL_DIR, default ~/Applications/ZoukanCheck), checks the signature and
# what Gatekeeper says about a downloaded copy, runs the installed app's self-test (window and
# network included, in a throwaway profile), removes the copy, and writes
# desktop/build/reports/install/install-check-macos.md. Exits 1 when a check fails.
set -u
cd "$(dirname "$0")/../../.."
root=$(pwd)
out="$root/desktop/build/reports/install"
mkdir -p "$out"
skip_build=0; keep=0
for arg in "$@"; do
  case "$arg" in
    --skip-build) skip_build=1 ;;
    --keep-installed) keep=1 ;;
  esac
done
rows=""; failed=0
result() { # name ok detail
  local mark=pass; [ "$2" = 1 ] || { mark=FAIL; failed=1; }
  echo "[$mark] $1: $3"
  rows+="| $1 | $mark | ${3//|/\\|} |"$'\n'
}

signed=0; [ -n "${ZOUKAN_MAC_SIGN_IDENTITY:-}" ] && signed=1
notarize=0; { [ -n "${ZOUKAN_NOTARY_PROFILE:-}" ] || [ -n "${ZOUKAN_NOTARY_APPLE_ID:-}" ]; } && [ $signed = 1 ] && notarize=1

if [ $skip_build = 0 ]; then
  task=:desktop:packageDmg; [ $notarize = 1 ] && task=:desktop:notarizeDmg
  ./gradlew "$task" --console=plain || { echo "$task failed"; exit 1; }
fi
dmg=$(ls -t desktop/build/packaging/installers/*.dmg 2>/dev/null | head -1)
[ -n "$dmg" ] || { echo "No DMG in desktop/build/packaging/installers"; exit 1; }
result "DMG" 1 "$(basename "$dmg"), $(( $(stat -f %z "$dmg") / 1048576 )) MB"

if [ $signed = 1 ]; then
  out_dmg=$(codesign --verify --verbose=2 "$dmg" 2>&1); result "DMG signature" $([ $? = 0 ] && echo 1 || echo 0) "$(echo "$out_dmg" | tail -1)"
  if [ $notarize = 1 ]; then
    out_staple=$(xcrun stapler validate "$dmg" 2>&1); result "Notarization ticket stapled" $([ $? = 0 ] && echo 1 || echo 0) "$(echo "$out_staple" | tail -1)"
  fi
fi

mnt=$(mktemp -d /tmp/zoukan-dmg.XXXXXX)
hdiutil attach -nobrowse -readonly -mountpoint "$mnt" "$dmg" >/dev/null
result "Mount" $([ -d "$mnt/Zoukan.app" ] && echo 1 || echo 0) "$mnt"
dest="${ZOUKAN_INSTALL_DIR:-$HOME/Applications/ZoukanCheck}"
rm -rf "$dest/Zoukan.app"; mkdir -p "$dest"
ditto "$mnt/Zoukan.app" "$dest/Zoukan.app"
hdiutil detach "$mnt" >/dev/null
app="$dest/Zoukan.app"
result "Install (copy to $dest)" $([ -x "$app/Contents/MacOS/Zoukan" ] && echo 1 || echo 0) "$app"
version=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$app/Contents/Info.plist" 2>/dev/null)
result "Bundle" $([ -n "$version" ] && echo 1 || echo 0) "$(/usr/libexec/PlistBuddy -c "Print :CFBundleIdentifier" "$app/Contents/Info.plist") $version, $(lipo -archs "$app/Contents/MacOS/Zoukan")"

if [ $signed = 1 ]; then
  v=$(codesign --verify --deep --strict --verbose=2 "$app" 2>&1); result "App signature" $([ $? = 0 ] && echo 1 || echo 0) "$(echo "$v" | tail -1)"
  rt=$(codesign -d --verbose=2 "$app" 2>&1 | grep -o 'flags=[^ ]*'); result "Hardened runtime" $(echo "$rt" | grep -q runtime && echo 1 || echo 0) "$rt"
else
  v=$(codesign --verify --verbose=2 "$app" 2>&1); result "App signature (ad hoc, no Developer ID set)" $([ $? = 0 ] && echo 1 || echo 0) "$(echo "$v" | tail -1)"
fi
# What Gatekeeper says about a downloaded copy: accepted only when signed and notarized
xattr -w com.apple.quarantine "0081;$(printf %x "$(date +%s)");Safari;" "$app"
gk=$(spctl --assess --type execute --verbose=2 "$app" 2>&1)
if [ $notarize = 1 ]; then
  result "Gatekeeper (downloaded copy)" $(echo "$gk" | grep -q accepted && echo 1 || echo 0) "$(echo "$gk" | tr '\n' ' ')"
else
  result "Gatekeeper (downloaded copy, not notarized: rejection expected)" 1 "$(echo "$gk" | tr '\n' ' ')"
fi
xattr -dr com.apple.quarantine "$app"

report="$out/selftest-macos.md"; rm -f "$report"
"$app/Contents/MacOS/Zoukan" selftest --ui --network --report "$report" >"$out/selftest-macos.log" 2>&1
code=$?
result "Self-test of the installed app" $([ $code = 0 ] && [ -f "$report" ] && echo 1 || echo 0) "exit $code, report $report"

if [ $keep = 0 ]; then
  rm -rf "$app"; rmdir "$dest" 2>/dev/null
  result "Remove" $([ ! -e "$app" ] && echo 1 || echo 0) "$app"
fi

{
  echo "# Zoukan install check: macOS $(sw_vers -productVersion) $(uname -m), $([ $failed = 1 ] && echo FAILED || echo passed)"
  echo; date; echo
  echo "| Check | Result | Detail |"; echo "|---|---|---|"
  printf "%s" "$rows"
  [ -f "$report" ] && { echo; cat "$report"; }
} > "$out/install-check-macos.md"
echo "Report: $out/install-check-macos.md"
exit $failed
