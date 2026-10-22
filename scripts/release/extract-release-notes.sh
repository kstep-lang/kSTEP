#!/usr/bin/env bash
# Extract one version's section from CHANGELOG.adoc and write it as light Markdown.
# Usage: extract-release-notes.sh <version> <CHANGELOG.adoc> <out.md>
# Missing/empty section -> ::warning:: and a generic fallback note (exit 0).
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "::error::usage: extract-release-notes.sh <version> <CHANGELOG.adoc> <out.md>" >&2
  exit 2
fi
VERSION="$1"
CHANGELOG="$2"
OUT="$3"
[ -f "$CHANGELOG" ] || { echo "::error::$CHANGELOG not found" >&2; exit 2; }

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "::error::version must be MAJOR.MINOR.PATCH (so '== Unreleased' can never be selected)" >&2
  exit 2
fi
ESC="${VERSION//./\\.}"
BODY="$(awk -v re="^== ${ESC}( |\$)" '
  $0 ~ re && !seen { seen = 1; insec = 1; next }
  insec && /^== / { exit }
  insec { print }
' "$CHANGELOG" | sed -e 's/^==== /#### /' -e 's/^=== /### /')"

# Empty = nothing but blank lines.
if [ -z "$(printf '%s' "$BODY" | tr -d '[:space:]')" ]; then
  echo "::warning::No CHANGELOG section for $VERSION; using generic release notes." >&2
  {
    echo "## kSTEP $VERSION"
    echo
    echo "kSTEP v$VERSION. See CHANGELOG.adoc in the repository for details."
    echo
    echo "Verify the download with the attached .sha256 file (\`sha256sum -c\`)."
  } > "$OUT"
  exit 0
fi

HEADING="$(awk -v re="^== ${ESC}( |\$)" '$0 ~ re { print; exit }' "$CHANGELOG" | sed -e 's/^== //')"
{
  echo "## kSTEP $HEADING"
  printf '%s\n' "$BODY"
  echo
  echo "Verify the download with the attached .sha256 file (\`sha256sum -c\`)."
} > "$OUT"
