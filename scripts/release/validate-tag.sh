#!/usr/bin/env bash
# Validate a release tag and print KEY=VALUE lines (tag, version, prerelease) on stdout.
# Usage: validate-tag.sh <tag>
# Only plain semver tags vX.Y.Z are accepted -- no suffixes (see ADR-0025).
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "::error::usage: validate-tag.sh <tag>" >&2
  exit 2
fi
TAG="$1"
# Bash [[ =~ ]] with $ matches before a trailing newline in some implementations; reject
# any control character / whitespace explicitly first.
case "$TAG" in
  *[[:space:]]* | *[[:cntrl:]]*)
    echo "::error::Invalid release tag (whitespace or control characters): refusing." >&2
    exit 2
    ;;
esac
RE='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
if ! [[ "$TAG" =~ $RE ]]; then
  echo "::error::Invalid release tag. Expected vMAJOR.MINOR.PATCH (e.g. v0.2.0), no suffix." >&2
  exit 2
fi
MAJOR="${BASH_REMATCH[1]}"
PRERELEASE=false
if [ "$MAJOR" = "0" ]; then PRERELEASE=true; fi
echo "tag=$TAG"
echo "version=${TAG#v}"
echo "prerelease=$PRERELEASE"
