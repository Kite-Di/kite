#!/bin/sh
# The dependency graph is a development-only artifact. NO APK — debug or release —
# may contain the graph, the inspector server, Ktor, or the web board.
# Usage: scripts/check-apk-safety.sh [apk...]   (default: app debug + release APKs)
set -e
APKS="${*:-app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/release/app-release-unsigned.apk}"
overall=0
for APK in $APKS; do
  [ -f "$APK" ] || { echo "SKIP (not built): $APK"; continue; }
  fail=0
  TMP=$(mktemp -d)
  unzip -qo "$APK" -d "$TMP"
  grep -rqs "io/ktor" "$TMP"/*.dex && { echo "FAIL [$APK]: Ktor classes present"; fail=1; }
  grep -rqs "com/kite/di/inspector/InspectorServer" "$TMP"/*.dex \
    && { echo "FAIL [$APK]: InspectorServer present"; fail=1; }
  [ -e "$TMP/kite/graph.json" ] && { echo "FAIL [$APK]: graph.json packaged"; fail=1; }
  [ -d "$TMP/assets/webboard" ] && { echo "FAIL [$APK]: web board assets packaged"; fail=1; }
  # The demo app needs no permissions at all; INTERNET appearing would mean a server snuck in.
  python3 - "$TMP/AndroidManifest.xml" <<'PY' || { echo "FAIL [$APK]: INTERNET permission requested"; fail=1; }
import sys
data = open(sys.argv[1], 'rb').read()
sys.exit(1 if 'android.permission.INTERNET'.encode('utf-16-le') in data else 0)
PY
  rm -rf "$TMP"
  [ "$fail" -eq 0 ] && echo "OK  [$APK]: clean (no graph, no server, no board, no INTERNET)"
  [ "$fail" -ne 0 ] && overall=1
done
exit $overall
