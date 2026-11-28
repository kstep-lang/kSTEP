#!/usr/bin/env bash
# Verify the opt-in native runtime archive (kstep-<version>-<platform>-native.zip, ADR-0050) and smoke-test it.
# Usage: verify-native-dist.sh <zip> <version> [fixture]
# Must run on the archive's own platform (linux-x86-64 / macos-aarch64) with a JDK 21 `java` on PATH / JAVA_HOME.
# If OCCT cannot be loaded the script exits 3 -- unless KSTEP_VERIFY_ALLOW_NO_OCCT=1, which turns that into a loud SKIP.
set -euo pipefail

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  echo "::error::usage: verify-native-dist.sh <zip> <version> [fixture]" >&2
  exit 2
fi
ZIP="$1"
VERSION="$2"
FIXTURE="${3:-kstep-tests/src/test/resources/export-box-20x30x40.kstep.kts}"

fail() { echo "::error::verify-native-dist: $*" >&2; exit 1; }
[ -f "$ZIP" ] || fail "$ZIP not found"
command -v unzip >/dev/null 2>&1 || fail "unzip not available"

case "$(basename "$ZIP")" in
  *-linux-x86-64-native.zip) PLATFORM=linux-x86-64; EXT=so ;;
  *-macos-aarch64-native.zip) PLATFORM=macos-aarch64; EXT=dylib ;;
  *) fail "cannot infer the platform from the archive name $(basename "$ZIP")" ;;
esac
case "$(uname -s)/$(uname -m)" in
  Linux/x86_64) HOST=linux-x86-64 ;;
  Darwin/arm64) HOST=macos-aarch64 ;;
  *) HOST="other" ;;
esac
if [ "$HOST" != "$PLATFORM" ]; then
  echo "::error::verify-native-dist: wrong host: archive is for $PLATFORM, this host is $HOST" >&2
  exit 2
fi

ROOT="kstep-$VERSION-$PLATFORM-native"
LIST="$(unzip -Z1 "$ZIP")"
has() { printf '%s\n' "$LIST" | grep -qxF -- "$1"; }

for entry in "bin/kstep" "lib/kstep-cli-$VERSION.jar" "lib/native/$PLATFORM/libkstep_occt_bridge.$EXT" \
  "lib/native/$PLATFORM/libkstep_planegcs_bridge.$EXT" "NATIVE-README.txt" "LICENSE" "NOTICE"; do
  has "$ROOT/$entry" || fail "missing $ROOT/$entry"
done

DUPES="$(printf '%s\n' "$LIST" | sort | uniq -d)"
[ -z "$DUPES" ] || fail "duplicate zip entries (Gradle 9 duplicate strategy): $DUPES"

NATIVE="$(printf '%s\n' "$LIST" | grep -E '\.(so|dylib|dll)$' || true)"
[ "$(printf '%s\n' "$NATIVE" | grep -c .)" = "2" ] || fail "expected exactly two native libraries, found: $NATIVE"
BAD="$(printf '%s\n' "$NATIVE" | grep -vE "^$ROOT/lib/native/$PLATFORM/libkstep_(occt|planegcs)_bridge\.$EXT\$" || true)"
[ -z "$BAD" ] || fail "unexpected native library location: $BAD"
OCCT_BUNDLED="$(printf '%s\n' "$LIST" | grep -E '(^|/)(libTK|libOCC|libocct)' || true)"
[ -z "$OCCT_BUNDLED" ] || fail "OCCT libraries must not be bundled: $OCCT_BUNDLED"

# No Java runtime may ship in the archive (ADR-0100): the launcher uses the host's JDK 21.
JAVA_BUNDLED="$(printf '%s\n' "$LIST" | grep -E "^$ROOT/(runtime|jre|jdk)/|(^|/)bin/java(\.exe)?$|(^|/)libjvm\.(so|dylib)$|\.jmod$" || true)"
[ -z "$JAVA_BUNDLED" ] || fail "a Java runtime must not be bundled: $JAVA_BUNDLED"

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

# Launcher checks.
BIN="$TMP/x/$ROOT/bin/kstep"
chmod +x "$BIN"
HELP="$("$BIN" --help 2>/dev/null)" || fail "bin/kstep --help exited non-zero"
printf '%s' "$HELP" | grep -q 'Usage:' || fail "--help output lacks 'Usage:'"
VER_OUT="$("$BIN" --version 2>/dev/null)" || fail "bin/kstep --version exited non-zero"
[ "$(printf '%s\n' "$VER_OUT" | head -n 1)" = "kstep $VERSION" ] \
  || fail "--version first line is not 'kstep $VERSION' (got: $(printf '%s\n' "$VER_OUT" | head -n 1))"

# The optional OCCT directory override must not break the launcher (Linux prepends it to LD_LIBRARY_PATH,
# macOS ignores it). A nonexistent directory is skipped silently; a relative one is refused with exit 2.
OVR_OUT="$(KSTEP_OCCT_LIB_DIR=/nonexistent-kstep-dir "$BIN" --version 2>/dev/null | head -n 1)" \
  || fail "launcher failed with KSTEP_OCCT_LIB_DIR=/nonexistent-kstep-dir"
[ "$OVR_OUT" = "kstep $VERSION" ] || fail "launcher printed '$OVR_OUT' with KSTEP_OCCT_LIB_DIR set"
# An existing absolute directory takes the real LD_LIBRARY_PATH prepend branch on Linux.
OVR_OUT="$(KSTEP_OCCT_LIB_DIR="$TMP" "$BIN" --version 2>/dev/null | head -n 1)" \
  || fail "launcher failed with KSTEP_OCCT_LIB_DIR=$TMP"
[ "$OVR_OUT" = "kstep $VERSION" ] || fail "launcher printed '$OVR_OUT' with an existing KSTEP_OCCT_LIB_DIR"
if [ "$PLATFORM" = "linux-x86-64" ]; then
  if KSTEP_OCCT_LIB_DIR=relative/dir "$BIN" --version >/dev/null 2>&1; then
    fail "launcher accepted a relative KSTEP_OCCT_LIB_DIR"
  fi
fi

# APP_HOME resolution through a symlink and from another working directory.
ln -s "$BIN" "$TMP/kstep-link"
mkdir "$TMP/elsewhere"
LINK_OUT="$(cd "$TMP/elsewhere" && "$TMP/kstep-link" --version 2>/dev/null | head -n 1)" \
  || fail "launcher failed when invoked through a symlink from another cwd"
[ "$LINK_OUT" = "kstep $VERSION" ] || fail "symlinked launcher printed '$LINK_OUT'"

# Native smoke test.
if printf '%s\n' "$VER_OUT" | grep -q '^occt: available'; then
  printf '%s\n' "$VER_OUT" | grep -q '^planegcs: available' || fail "OCCT loads but the PlaneGCS bridge does not"
  [ -f "$FIXTURE" ] || fail "fixture $FIXTURE not found"
  STL="$TMP/box.stl"
  "$BIN" render "$FIXTURE" --format stl --out "$STL" 2>/dev/null || fail "kstep render --format stl failed"
  [ -s "$STL" ] || fail "render produced no/empty STL"
else
  echo "OCCT could not be loaded: $(printf '%s\n' "$VER_OUT" | grep '^occt:' || echo '(no occt line)')" >&2
  HINT="$("$BIN" render "$FIXTURE" --format stl --out "$TMP/x.stl" 2>&1 >/dev/null || true)"
  printf '%s' "$HINT" | grep -q 'hint: the OCCT geometry bridge could not be loaded' \
    || fail "OCCT unavailable and the install hint was not printed"
  if [ "${KSTEP_VERIFY_ALLOW_NO_OCCT:-0}" = "1" ]; then
    echo "SKIP: native geometry smoke test NOT run (OCCT missing; KSTEP_VERIFY_ALLOW_NO_OCCT=1) -- hint verified only"
    echo "OK: $ZIP verified (layout, launcher, hint; native render NOT exercised)"
    exit 0
  fi
  echo "::error::verify-native-dist: OCCT missing on this host (install OCCT 7.9.x, see NATIVE-README.txt)" >&2
  exit 3
fi

echo "OK: $ZIP verified (layout, no bundled OCCT, launcher via symlink, --version, native STL render)"
