#!/usr/bin/env bash
# Verify the kstep-cli distribution zip and smoke-test it.
# Usage: verify-dist.sh <zip> <version> [fixture]
# Needs a JDK 21 `java` on PATH / JAVA_HOME. Fixture default: hello-assembly.kstep.kts.
set -euo pipefail

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  echo "::error::usage: verify-dist.sh <zip> <version> [fixture]" >&2
  exit 2
fi
ZIP="$1"
VERSION="$2"
FIXTURE="${3:-kstep-tests/src/test/resources/hello-assembly.kstep.kts}"
ROOT="kstep-cli-$VERSION"

fail() { echo "::error::verify-dist: $*" >&2; exit 1; }
[ -f "$ZIP" ] || fail "$ZIP not found"
command -v unzip >/dev/null 2>&1 || fail "unzip not available"

LIST="$(unzip -Z1 "$ZIP")"
has() { printf '%s\n' "$LIST" | grep -qxF -- "$1"; }

has "$ROOT/bin/kstep-cli" || fail "missing $ROOT/bin/kstep-cli"
has "$ROOT/bin/kstep-cli.bat" || fail "missing $ROOT/bin/kstep-cli.bat"
has "$ROOT/lib/kstep-cli-$VERSION.jar" || fail "missing $ROOT/lib/kstep-cli-$VERSION.jar"

DUPES="$(printf '%s\n' "$LIST" | sort | uniq -d)"
[ -z "$DUPES" ] || fail "duplicate zip entries (Gradle 9 duplicate strategy): $DUPES"

NATIVE="$(printf '%s\n' "$LIST" | grep -E '\.(so|dylib|dll)$' || true)"
[ -z "$NATIVE" ] || fail "native libraries found in zip: $NATIVE"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -q "$ZIP" -d "$TMP/x"
for pat in kstep-geometry kstep-constraints kstep-sketch; do
  for jar in "$TMP/x/$ROOT/lib/$pat"-*.jar; do
    [ -f "$jar" ] || continue
    if unzip -Z1 "$jar" | grep -qE '^dev/kstep/[^/]+/native/'; then
      fail "$(basename "$jar") embeds native bridge resources (dev/kstep/*/native/)"
    fi
  done
done

# Smoke tests.
BIN="$TMP/x/$ROOT/bin/kstep-cli"
chmod +x "$BIN"
HELP="$("$BIN" --help)" || fail "kstep-cli --help exited non-zero"
printf '%s' "$HELP" | grep -q 'Usage:' || fail "--help output lacks 'Usage:'"

[ -f "$FIXTURE" ] || fail "fixture $FIXTURE not found"
OUTFILE="$TMP/smoke.step"
"$BIN" export "$FIXTURE" --out "$OUTFILE" || fail "kstep-cli export failed"
[ -f "$OUTFILE" ] || fail "export produced no file"
FIRST="$(head -n 1 "$OUTFILE" | tr -d '\r')"
[ "$FIRST" = "ISO-10303-21;" ] || fail "exported file does not start with ISO-10303-21; (got: $FIRST)"

# Import smoke test: the zip ships no native bridge, so importing the file just exported must end with the one-line
# JSON error geometry_unavailable (exit 1), never with a crash or a hang (ADR-0038).
rc=0
IMP="$("$BIN" import "$OUTFILE" --output json 2>"$TMP/import.err")" || rc=$?
[ "$rc" -eq 1 ] || fail "kstep-cli import (no native bridge) exited $rc, expected 1"
[ "$(printf '%s\n' "$IMP" | wc -l | tr -d ' ')" = "1" ] || fail "import stdout is not one JSON line"
printf '%s' "$IMP" | grep -q '"errorKind":"geometry_unavailable"' \
  || fail "import did not report geometry_unavailable (got: $IMP)"

echo "OK: $ZIP verified (layout, no natives, --help, export and import smoke tests)"
