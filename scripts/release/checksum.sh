#!/usr/bin/env bash
# Write <file>.sha256 ("<hex>  <basename>", sha256sum -c compatible) and verify it.
# Usage: checksum.sh <file>
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "::error::usage: checksum.sh <file>" >&2
  exit 2
fi
FILE="$1"
[ -f "$FILE" ] || { echo "::error::$FILE not found" >&2; exit 2; }
DIR="$(cd "$(dirname "$FILE")" && pwd)"
BASE="$(basename "$FILE")"

if command -v sha256sum >/dev/null 2>&1; then
  SUM=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then
  SUM=(shasum -a 256)
else
  echo "::error::neither sha256sum nor shasum found" >&2
  exit 2
fi
cd "$DIR"
"${SUM[@]}" "$BASE" > "$BASE.sha256"
"${SUM[@]}" -c "$BASE.sha256"
