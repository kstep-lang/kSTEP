#!/usr/bin/env bash
# Fail unless the version in build.gradle.kts (allprojects block) equals the tag's version.
# Usage: check-version.sh <expected-version> <path/to/build.gradle.kts>
# Lesson from kUML v0.53.0: a tag was pushed while the hard-coded version still said the
# previous release. Exactly one match is required so the check can never pass vacuously.
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "::error::usage: check-version.sh <expected-version> <build.gradle.kts>" >&2
  exit 2
fi
EXPECTED="$1"
FILE="$2"
[ -f "$FILE" ] || { echo "::error::$FILE not found" >&2; exit 2; }

FOUND="$(awk '
  /^allprojects[[:space:]]*\{/ { inblock = 1; next }
  inblock && /^\}/ { inblock = 0 }
  inblock && /^[[:space:]]*version[[:space:]]*=[[:space:]]*"[^"]*"/ {
    line = $0
    sub(/^[^"]*"/, "", line)
    sub(/".*$/, "", line)
    print line
  }
' "$FILE")"

COUNT=0
if [ -n "$FOUND" ]; then COUNT="$(printf '%s\n' "$FOUND" | wc -l | tr -d ' ')"; fi
if [ "$COUNT" -ne 1 ]; then
  echo "::error::Expected exactly one version = \"...\" in the allprojects block of $FILE, found $COUNT." >&2
  exit 1
fi
if [ "$FOUND" != "$EXPECTED" ]; then
  echo "::error::Tag version $EXPECTED does not match build.gradle.kts version $FOUND." \
       "Bump version in allprojects BEFORE tagging (kUML v0.53.0 lesson: the artifacts would carry the wrong version)." >&2
  exit 1
fi
echo "OK: tag version $EXPECTED == build.gradle.kts version $FOUND"
