#!/usr/bin/env bash
# Usage: check-maven-central.sh <version> [base-url] [artifacts-file]
# Probes Maven Central (read-only HEAD requests) for the .pom of every published artifact and prints
# state=published | state=absent. Mixed or unknown results are errors (fail closed): Maven Central
# is immutable, so "assume absent" must never be the fallback. See ADR-0043.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION="${1-}"
BASE="${2:-https://repo1.maven.org/maven2}"
LIST="${3:-$HERE/published-artifacts.txt}"

RE='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
if ! [[ "$VERSION" =~ $RE ]]; then
  echo "::error::check-maven-central: invalid version (expected MAJOR.MINOR.PATCH)." >&2
  exit 2
fi
case "$BASE" in
  https://*) ;;
  *) echo "::error::check-maven-central: base URL must be https." >&2; exit 2 ;;
esac
if [ ! -f "$LIST" ]; then
  echo "::error::check-maven-central: artifact list not found." >&2
  exit 2
fi

present=()
absent=()
unknown=0
while IFS= read -r line || [ -n "$line" ]; do
  a="${line//[[:space:]]/}"
  case "$a" in ''|'#'*) continue ;; esac
  code="$(curl -sS -o /dev/null -I --max-time 30 --retry 3 -w '%{http_code}' \
    "$BASE/dev/kstep/$a/$VERSION/$a-$VERSION.pom" 2>/dev/null || true)"
  echo "probe $a: HTTP ${code:-000}"
  case "$code" in
    200) present+=("$a") ;;
    404) absent+=("$a") ;;
    *) unknown=$((unknown + 1)) ;;
  esac
done < "$LIST"

if [ "$unknown" -gt 0 ]; then
  echo "::error::check-maven-central: $unknown probe(s) returned neither 200 nor 404; state unknown, refusing to continue." >&2
  exit 1
fi
if [ "${#present[@]}" -gt 0 ] && [ "${#absent[@]}" -gt 0 ]; then
  echo "::error::check-maven-central: partial state for $VERSION; present: ${present[*]}. Manual intervention required (Central Portal deployments)." >&2
  exit 1
fi
if [ "${#present[@]}" -gt 0 ]; then
  echo "state=published"
else
  echo "state=absent"
fi
