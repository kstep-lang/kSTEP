#!/usr/bin/env bash
# Verify the macOS app image of the kSTEP desktop and smoke-test its worker (ADR-0082).
# Usage: verify-desktop-app.sh <kSTEP.app> [fixture]
# Checks the bundle layout, that the app carries no native bridge and no Compose in the worker, the code signature, and runs
# one worker with the BUNDLED java and the packaged class path.
set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo "::error::usage: verify-desktop-app.sh <kSTEP.app> [fixture]" >&2
  exit 2
fi
APP="$1"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE="${2:-$HERE/../../kstep-tests/src/test/resources/hello-assembly.kstep.kts}"

fail() { echo "::error::verify-desktop-app: $*" >&2; exit 1; }
[ -d "$APP" ] || fail "$APP not found"
APP="$(cd "$APP" && pwd)"
JAVA="$APP/Contents/runtime/Contents/Home/bin/java"
CFG="$APP/Contents/app/kSTEP.cfg"
WORKER="$APP/Contents/kstep-worker"
MANIFEST="$WORKER/worker-classpath.txt"

# 1. The bundled runtime has a java launcher (jlink must not run with --strip-native-commands).
[ -x "$JAVA" ] || fail "the bundled runtime has no executable bin/java"
"$JAVA" -version 2>&1 | head -1 | grep -q '"21' || fail "the bundled java is not 21: $("$JAVA" -version 2>&1 | head -1)"
echo "bundled java: $("$JAVA" -version 2>&1 | head -1)"
# The built-in MCP server (ADR-0095) uses com.sun.net.httpserver: the runtime must carry jdk.httpserver.
"$JAVA" --list-modules 2>/dev/null | grep -q '^jdk.httpserver' || fail "the bundled runtime has no jdk.httpserver module (ADR-0095)"

# 2. The launcher passes the worker folder.
[ -f "$CFG" ] || fail "missing $CFG"
grep -qF -- '-Dkstep.desktop.workerLibDir=$APPDIR/../kstep-worker' "$CFG" || fail "kSTEP.cfg does not carry workerLibDir"

# 3. The worker folder: manifest entries exist as regular files; no UI jars in the worker.
[ -f "$MANIFEST" ] || fail "missing $MANIFEST"
COUNT=0
while IFS= read -r name; do
  [ -n "$name" ] || continue
  case "$name" in */*|*..*) fail "invalid manifest entry: $name" ;; esac
  [ -f "$WORKER/$name" ] && [ ! -L "$WORKER/$name" ] || fail "manifest entry missing or not a regular file: $name"
  COUNT=$((COUNT + 1))
done < "$MANIFEST"
[ "$COUNT" -gt 0 ] || fail "empty worker manifest"
UI_JARS="$(ls "$WORKER" | grep -E '^(skiko|org\.jetbrains\.compose|desktop-jvm|kstep-desktop-|kstep-viewer-)' || true)"
[ -z "$UI_JARS" ] || fail "UI jars in the worker folder: $UI_JARS"
ls "$WORKER" | grep -q '^kstep-cli-' || fail "the worker folder has no kstep-cli jar"
echo "worker jars: $COUNT"

# 4. No native bridge anywhere in the bundle: not as a file, not inside a jar (the JVM-only line, -Pkstep.native.exclude=true).
FILES="$(find "$APP" -name 'libkstep_*bridge*' -o -name 'libkstep_*.dylib' | head -5)"
[ -z "$FILES" ] || fail "native bridge files in the bundle: $FILES"
while IFS= read -r jar; do
  if unzip -Z1 "$jar" 2>/dev/null | grep -qE '^dev/kstep/[^/]+/native/'; then
    fail "native bridge inside $(basename "$jar") (build with -Pkstep.native.exclude=true)"
  fi
done < <(find "$APP/Contents/app" "$WORKER" -name '*.jar')

# 5. The code signature (jpackage signs ad hoc without --mac-sign); recorded, and it must verify.
codesign -dv "$APP" 2>&1 | sed 's/^/codesign: /' || true
codesign --verify --deep --strict "$APP" 2>&1 || fail "codesign --verify --deep --strict failed"

# 6. Smoke: one worker with the bundled java and the packaged class path. The JVM-only line must answer honestly
# (occtAvailable=false), not crash with a missing class or a compiler error.
CP="$(sed "s#^#$WORKER/#" "$MANIFEST" | paste -sd: -)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
set +e
OUT="$("$JAVA" -Djava.awt.headless=true -cp "$CP" dev.kstep.cli.DesktopRenderWorker "$FIXTURE" --format glb --out "$TMP/out.glb" --output json 2>"$TMP/err")"
CODE=$?
set -e
echo "worker exit: $CODE"
echo "$OUT" | tail -1
[ "$CODE" -eq 0 ] || [ "$CODE" -eq 1 ] || fail "worker exited with $CODE: $(head -3 "$TMP/err")"
echo "$OUT" | tail -1 | grep -q '"status":"success"' || fail "the worker did not report success"
grep -qE 'ClassNotFound|NoClassDefFound|Could not find or load' "$TMP/err" && fail "worker error: $(head -3 "$TMP/err")" || true
echo "verify-desktop-app: OK"
