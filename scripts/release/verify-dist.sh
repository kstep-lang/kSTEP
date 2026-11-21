#!/usr/bin/env bash
# Verify the kstep-cli distribution zip and smoke-test it.
# Usage: verify-dist.sh <zip> <version> [fixture] [targets-fixture]
# Needs a JDK 21 `java` on PATH / JAVA_HOME. Fixture default: hello-assembly.kstep.kts; the targets fixture (a script with
# an export { } block, ADR-0065) defaults to export-block.kstep.kts next to the fixture.
set -euo pipefail

if [ "$#" -lt 2 ] || [ "$#" -gt 4 ]; then
  echo "::error::usage: verify-dist.sh <zip> <version> [fixture] [targets-fixture]" >&2
  exit 2
fi
ZIP="$1"
VERSION="$2"
FIXTURE="${3:-kstep-tests/src/test/resources/hello-assembly.kstep.kts}"
TARGETS_FIXTURE="${4:-$(dirname "$FIXTURE")/export-block.kstep.kts}"
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

# The CLI zip must stay free of the Compose desktop/viewer modules (ADR-0051).
UI_JARS="$(printf '%s\n' "$LIST" | grep -E "/lib/(kstep-desktop-|kstep-viewer-|skiko|desktop-jvm|org\.jetbrains\.compose)" || true)"
[ -z "$UI_JARS" ] || fail "desktop/compose jars found in CLI zip: $UI_JARS"

# ... and free of the AI/LLM client (Koog, HTTP client, the AI module; ADR-0056). The CLI zip legitimately carries the
# Ktor SERVER jars of kstep-mcp, so only the client and Koog artifacts are rejected, not every ktor-* jar.
AI_JARS="$(printf '%s\n' "$LIST" | grep -E "/lib/(kstep-desktop-ai-|ktor-client-|prompt-executor-|prompt-model-|prompt-llm-|prompt-markdown-|prompt-cache-|prompt-structure-|agents-|http-client-|embeddings-|koog-)" || true)"
[ -z "$AI_JARS" ] || fail "AI/LLM client jars found in CLI zip: $AI_JARS"

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

# Convert smoke test (ADR-0060): script -> note -> script, text only (no native bridge needed), and the round trip must
# return the fixture unchanged when it ends with a line break.
FIXTURE_NAME="$(basename "$FIXTURE")"
STEM="${FIXTURE_NAME%.kstep.kts}"
mkdir -p "$TMP/okf" "$TMP/kts"
"$BIN" convert "$FIXTURE" --to okf --out "$TMP/okf" >/dev/null || fail "kstep-cli convert --to okf failed"
[ -f "$TMP/okf/$STEM.md" ] || fail "convert --to okf produced no $STEM.md"
"$BIN" convert "$TMP/okf/$STEM.md" --to kts --out "$TMP/kts" >/dev/null || fail "kstep-cli convert --to kts failed"
[ -f "$TMP/kts/$STEM.kstep.kts" ] || fail "convert --to kts produced no $STEM.kstep.kts"
if [ -z "$(tail -c 1 "$FIXTURE")" ]; then
  cmp -s "$FIXTURE" "$TMP/kts/$STEM.kstep.kts" || fail "convert round trip changed the script"
fi

# Convert JSON + folder smoke test (ADR-0061): --output json prints exactly one JSON line, and a folder converts too.
# --out is never created by the command, so the folders exist beforehand.
mkdir -p "$TMP/okf-json" "$TMP/kts-dir"
J="$("$BIN" convert "$FIXTURE" --to okf --out "$TMP/okf-json" --output json --tag smoke)" \
  || fail "kstep-cli convert --output json failed"
[ "$(printf '%s\n' "$J" | wc -l | tr -d ' ')" = "1" ] || fail "convert json is not one line"
{ printf '%s' "$J" | grep -q '"status":"success"' && printf '%s' "$J" | grep -q '"command":"convert"'; } \
  || fail "convert json did not report success (got: $J)"
"$BIN" convert "$TMP/okf" --to kts --out "$TMP/kts-dir" >/dev/null || fail "kstep-cli convert of a folder failed"
[ -f "$TMP/kts-dir/$STEM.kstep.kts" ] || fail "folder convert produced no $STEM.kstep.kts"

# Recursive folder convert (ADR-0071): sub-folders are mirrored below --out, the JSON says so and names the relative target.
mkdir -p "$TMP/tree/a/b" "$TMP/tree-out"
cp "$TMP/okf/$STEM.md" "$TMP/tree/a/b/x.md"
J="$("$BIN" convert "$TMP/tree" --to kts --out "$TMP/tree-out" --recursive --output json)" \
  || fail "kstep-cli convert --recursive failed"
[ "$(printf '%s\n' "$J" | wc -l | tr -d ' ')" = "1" ] || fail "convert --recursive json is not one line"
printf '%s' "$J" | grep -q '"recursive":true' || fail "convert --recursive json does not say recursive (got: $J)"
printf '%s' "$J" | grep -q '"target":"a/b/x.kstep.kts"' \
  || fail "convert --recursive json names no relative target (got: $J)"
[ -f "$TMP/tree-out/a/b/x.kstep.kts" ] || fail "convert --recursive did not mirror a/b/x.kstep.kts"

# Declared export targets (ADR-0065): STEP only (the zip ships no native bridge). One JSON line, the declared file is
# written, and a second run over the existing file must fail (exit 1) without --force.
[ -f "$TARGETS_FIXTURE" ] || fail "targets fixture $TARGETS_FIXTURE not found"
mkdir -p "$TMP/targets"
J="$("$BIN" export "$TARGETS_FIXTURE" --all-targets --out-dir "$TMP/targets" --output json)" \
  || fail "kstep-cli export --all-targets failed"
[ "$(printf '%s\n' "$J" | wc -l | tr -d ' ')" = "1" ] || fail "export --all-targets json is not one line"
{ printf '%s' "$J" | grep -q '"status":"success"' && printf '%s' "$J" | grep -q '"command":"export"'; } \
  || fail "export --all-targets json did not report success (got: $J)"
[ -f "$TMP/targets/export-block-smoke.step" ] || fail "declared target not written"
[ "$(head -n 1 "$TMP/targets/export-block-smoke.step" | tr -d '\r')" = "ISO-10303-21;" ] \
  || fail "declared target does not start with ISO-10303-21;"
rc=0
"$BIN" export "$TARGETS_FIXTURE" --all-targets --out-dir "$TMP/targets" >/dev/null 2>&1 || rc=$?
[ "$rc" -eq 1 ] || fail "export --all-targets over an existing file exited $rc, expected 1 (no --force)"

echo "OK: $ZIP verified (layout, no natives, --help, export, export targets, import, convert incl. json, folder and recursive smoke tests)"
