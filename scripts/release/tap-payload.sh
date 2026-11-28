#!/usr/bin/env bash
# Print the repository_dispatch client_payload for the Homebrew tap (ADR-0100) as JSON on stdout.
# Usage: tap-payload.sh <assets-dir> <version> <owner/repo>
# <assets-dir> must hold kstep-<version>-{macos-aarch64,linux-x86-64}-native.zip and their .sha256 files.
# The checksums are recomputed here, never trusted from the .sha256 file alone. All values reach jq
# through --arg, never through string concatenation.
set -euo pipefail

fail() { echo "::error::tap-payload: $*" >&2; exit 1; }
if [ "$#" -ne 3 ]; then
  echo "::error::usage: tap-payload.sh <assets-dir> <version> <owner/repo>" >&2
  exit 2
fi
DIR="$1"
VERSION="$2"
REPO="$3"

VRE='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
[[ "$VERSION" =~ $VRE ]] || { echo "::error::tap-payload: invalid version" >&2; exit 2; }
RRE='^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$'
[[ "$REPO" =~ $RRE ]] || { echo "::error::tap-payload: invalid repository" >&2; exit 2; }
[ -d "$DIR" ] || { echo "::error::tap-payload: $DIR is not a directory" >&2; exit 2; }
command -v jq >/dev/null 2>&1 || { echo "::error::tap-payload: jq not available" >&2; exit 2; }

if command -v sha256sum >/dev/null 2>&1; then SUM=(sha256sum); elif command -v shasum >/dev/null 2>&1; then SUM=(shasum -a 256); else
  echo "::error::neither sha256sum nor shasum found" >&2; exit 2
fi

for platform in macos-aarch64 linux-x86-64; do
  name="kstep-$VERSION-$platform-native.zip"
  [ -f "$DIR/$name" ] || fail "missing $name"
  [ -f "$DIR/$name.sha256" ] || fail "missing $name.sha256"
  line="$(cat "$DIR/$name.sha256")"
  [[ "$line" =~ ^([0-9a-f]{64})\ \ (.+)$ ]] || fail "$name.sha256 is not '<64 hex>  <file name>'"
  [ "${BASH_REMATCH[2]}" = "$name" ] || fail "$name.sha256 names another file"
  declared="${BASH_REMATCH[1]}"
  actual="$("${SUM[@]}" "$DIR/$name" | cut -d' ' -f1)"
  [ "$declared" = "$actual" ] || fail "$name does not match its .sha256 (declared $declared, actual $actual)"
  url="https://github.com/$REPO/releases/download/v$VERSION/$name"
  case "$platform" in
    macos-aarch64) MAC_SHA="$actual"; MAC_URL="$url" ;;
    linux-x86-64) LIN_SHA="$actual"; LIN_URL="$url" ;;
  esac
done

jq -n --arg v "$VERSION" \
  --arg mu "$MAC_URL" --arg ms "$MAC_SHA" --arg lu "$LIN_URL" --arg ls "$LIN_SHA" \
  '{version:$v, platforms:{"macos-aarch64":{url:$mu,sha256:$ms},"linux-x86-64":{url:$lu,sha256:$ls}}}'
