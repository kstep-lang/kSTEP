#!/usr/bin/env bash
# Check what the bridge libraries of a native runtime archive (ADR-0050, ADR-0100) link against.
# Usage: check-native-linkage.sh <zip> <version>
# The platform comes from the archive name. Needs otool (macOS) or readelf + objdump (Linux) on PATH;
# the host is not checked, so the self-test can feed it stub tools.
# macOS: OCCT only via /opt/homebrew/opt/opencascade/lib/libTK*.7.9.dylib (opt symlink, survives 7.9.x
#   upgrades), never a versioned Cellar path, never a build-machine path. Linux: the distro OCCT
#   (libTK*.so.7.9) with the single DT_RPATH /usr/lib/x86_64-linux-gnu, plus a glibc floor report.
# Exit: 0 ok, 1 violation, 2 usage error. Prints KEY=VALUE lines (glibc_floor=, glibcxx_floor=, minos=).
set -euo pipefail

fail() { echo "::error::check-native-linkage: $*" >&2; exit 1; }
if [ "$#" -ne 2 ]; then
  echo "::error::usage: check-native-linkage.sh <zip> <version>" >&2
  exit 2
fi
ZIP="$1"
VERSION="$2"
[ -f "$ZIP" ] || { echo "::error::check-native-linkage: $ZIP not found" >&2; exit 2; }
case "$VERSION" in
  *[!0-9.]* | "") echo "::error::check-native-linkage: invalid version" >&2; exit 2 ;;
esac
case "$(basename "$ZIP")" in
  *-linux-x86-64-native.zip) PLATFORM=linux-x86-64; EXT=so ;;
  *-macos-aarch64-native.zip) PLATFORM=macos-aarch64; EXT=dylib ;;
  *) echo "::error::check-native-linkage: cannot infer the platform from the archive name $(basename "$ZIP")" >&2; exit 2 ;;
esac
command -v unzip >/dev/null 2>&1 || { echo "::error::unzip not available" >&2; exit 2; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -q "$ZIP" -d "$TMP/x"
ROOT="$TMP/x/kstep-$VERSION-$PLATFORM-native"
OCCT="$ROOT/lib/native/$PLATFORM/libkstep_occt_bridge.$EXT"
PGCS="$ROOT/lib/native/$PLATFORM/libkstep_planegcs_bridge.$EXT"
[ -f "$OCCT" ] || fail "missing OCCT bridge in the archive"
[ -f "$PGCS" ] || fail "missing PlaneGCS bridge in the archive"

FORBIDDEN='(/Cellar/|/Users/|/private/|/home/|/runner/|/tmp/|/var/)'

if [ "$PLATFORM" = "macos-aarch64" ]; then
  command -v otool >/dev/null 2>&1 || { echo "::error::otool not available" >&2; exit 2; }
  OPT_RE='^/opt/homebrew/opt/opencascade/lib/libTK[A-Za-z0-9]+\.7\.9\.dylib$'
  check_deps() { # lib, allow_occt(0/1)
    local lib="$1" allow="$2" dep
    # First line of "otool -L" is the file itself; the own id line follows as an @rpath entry.
    while IFS= read -r dep; do
      [ -n "$dep" ] || continue
      case "$dep" in
        @rpath/libkstep_*_bridge.dylib) continue ;;
        /usr/lib/* | /System/*) continue ;;
      esac
      if printf '%s' "$dep" | grep -Eq "$FORBIDDEN"; then
        fail "$(basename "$lib") depends on a build-machine or versioned path: $dep"
      fi
      if [ "$allow" = "1" ] && printf '%s' "$dep" | grep -Eq "$OPT_RE"; then continue; fi
      fail "$(basename "$lib") has a disallowed dependency: $dep"
    done < <(otool -L "$lib" | tail -n +2 | sed -E 's/^[[:space:]]+//; s/ \(compatibility version.*$//')
  }
  check_deps "$OCCT" 1
  check_deps "$PGCS" 0
  for lib in "$OCCT" "$PGCS"; do
    while IFS= read -r rp; do
      [ -n "$rp" ] || continue
      if [ "$lib" = "$OCCT" ] && [ "$rp" = "/opt/homebrew/opt/opencascade/lib" ]; then continue; fi
      fail "$(basename "$lib") has an unexpected LC_RPATH: $rp"
    done < <(otool -l "$lib" | awk '/cmd LC_RPATH/ {f=1} f && $1=="path" {print $2; f=0}')
  done
  MINOS="$(otool -l "$OCCT" | awk '/LC_BUILD_VERSION/ {f=1} f && $1=="minos" {print $2; exit} /cmd LC_VERSION_MIN_MACOSX/ {g=1} g && $1=="version" {print $2; exit}')"
  echo "minos=${MINOS:-unknown}"
else
  command -v readelf >/dev/null 2>&1 || { echo "::error::readelf not available" >&2; exit 2; }
  command -v objdump >/dev/null 2>&1 || { echo "::error::objdump not available" >&2; exit 2; }
  NEEDED_RE='^(libTK[A-Za-z0-9]+\.so\.7\.9|libstdc\+\+\.so\.6|libm\.so\.6|libgcc_s\.so\.1|libc\.so\.6|ld-linux-x86-64\.so\.2)$'
  for lib in "$OCCT" "$PGCS"; do
    DYN="$(readelf -d "$lib")"
    while IFS= read -r n; do
      [ -n "$n" ] || continue
      if [ "$lib" = "$PGCS" ] && printf '%s' "$n" | grep -q '^libTK'; then
        fail "$(basename "$lib") must not depend on OCCT: $n"
      fi
      printf '%s' "$n" | grep -Eq "$NEEDED_RE" || fail "$(basename "$lib") has a disallowed NEEDED entry: $n"
    done < <(printf '%s\n' "$DYN" | sed -nE 's/.*\(NEEDED\).*\[(.*)\].*/\1/p')
    if printf '%s\n' "$DYN" | grep -q '(RUNPATH)'; then
      fail "$(basename "$lib") has a RUNPATH; the OCCT bridge must carry a plain DT_RPATH"
    fi
    RPATHS="$(printf '%s\n' "$DYN" | sed -nE 's/.*\(RPATH\).*\[(.*)\].*/\1/p')"
    if [ "$lib" = "$OCCT" ]; then
      [ "$RPATHS" = "/usr/lib/x86_64-linux-gnu" ] \
        || fail "$(basename "$lib") RPATH must be exactly /usr/lib/x86_64-linux-gnu (got: ${RPATHS:-none})"
    else
      [ -z "$RPATHS" ] || fail "$(basename "$lib") must not carry an RPATH (got: $RPATHS)"
    fi
    if printf '%s\n%s\n' "$DYN" "$RPATHS" | grep -Eq '/home/|/runner/|/Users/'; then
      fail "$(basename "$lib") references a build-machine path"
    fi
  done
  SYMS="$(for lib in "$OCCT" "$PGCS"; do objdump -T "$lib"; done)"
  floor() { # prefix -> highest version
    printf '%s\n' "$SYMS" | grep -oE "$1_[0-9]+(\.[0-9]+)*" | sed "s/^$1_//" | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1
  }
  echo "glibc_floor=$(floor GLIBC)"
  echo "glibcxx_floor=$(floor GLIBCXX)"
fi
echo "OK: $ZIP linkage verified ($PLATFORM)"
