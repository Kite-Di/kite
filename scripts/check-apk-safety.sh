#!/bin/sh
# Everything in debug, nothing in release.
#
# A debug APK is a development build: it may carry the inspector, Ktor, the web
# board and the graph — that is what makes the board work on a device. A RELEASE
# APK must carry none of it: no graph, no decisions, no rules vocabulary, no
# server, no board assets, no runtime tracing, no INTERNET permission. This script
# enforces the release half; the debug half needs no enforcing.
#
# Usage: scripts/check-apk-safety.sh [apk...]   (default: the release APK)
set -e
APKS="${*:-demo/tutorial_app/app/build/outputs/apk/release/app-release-unsigned.apk}"
overall=0
for APK in $APKS; do
  [ -f "$APK" ] || { echo "SKIP (not built): $APK"; continue; }
  case "$APK" in
    *release*) ;;
    *) echo "SKIP (development build, everything is allowed): $APK"; continue ;;
  esac

  fail=0
  TMP=$(mktemp -d)
  unzip -qo "$APK" -d "$TMP"

  # Packaged files: plain names, unaffected by R8.
  [ -e "$TMP/kite/graph.json" ] && { echo "FAIL [$APK]: graph.json packaged"; fail=1; }
  [ -e "$TMP/kite/decisions.json" ] && { echo "FAIL [$APK]: decisions.json packaged"; fail=1; }
  [ -d "$TMP/assets/webboard" ] && { echo "FAIL [$APK]: web board assets packaged"; fail=1; }

  # The app needs no permissions at all; INTERNET appearing would mean a server snuck in.
  python3 - "$TMP/AndroidManifest.xml" <<'PY' || { echo "FAIL [$APK]: INTERNET permission requested"; fail=1; }
import sys
data = open(sys.argv[1], 'rb').read()
sys.exit(1 if 'android.permission.INTERNET'.encode('utf-16-le') in data else 0)
PY
  rm -rf "$TMP"

  # Code: ask mapping.txt, not the dex. R8 renames whatever it keeps, so a dex grep
  # for a class name proves nothing — while a class R8 removed has no mapping entry
  # at all. This also makes the check fail loudly if the release stops being minified.
  MAP="$(dirname "$(dirname "$(dirname "$APK")")")/mapping/release/mapping.txt"
  if [ -f "$MAP" ]; then
    absent() { # <pattern> <what> — note the explicit `return 0`: without it the
      # function would exit non-zero on the *good* path (grep found nothing) and
      # `set -e` would abort the script silently.
      grep -q "$1" "$MAP" && { echo "FAIL [$APK]: $2 present in mapping.txt"; fail=1; }
      return 0
    }
    absent '^io\.ktor\.' 'Ktor'
    absent '^com\.kite\.di\.inspector\.' 'inspector classes'
    # The @Root/@Bind/@Scoped vocabulary is SOURCE-retained + compileOnly.
    absent '^com\.kite\.di\.rules\.' 'rules annotations'
    # Runtime tracing: the guarded paths and their payloads. `GraphEvents`
    # itself may remain as an empty shell — R8 keeps the class, strips every member.
    absent 'com\.kite\.di\.runtime\.observe\.GraphEvent\$' 'GraphEvent payload classes'
    absent 'com\.kite\.di\.runtime\.ScopeNode\$InstanceMeta' 'per-instance tracing ledger'
  else
    echo "FAIL [$APK]: no mapping.txt — the release is not minified, so none of this is verifiable"
    fail=1
  fi

  [ "$fail" -eq 0 ] && echo "OK  [$APK]: clean (no graph, no decisions, no rules, no inspector, no Ktor, no board, no tracing, no INTERNET)"
  [ "$fail" -ne 0 ] && overall=1
done
exit $overall
