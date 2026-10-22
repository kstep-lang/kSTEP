#!/usr/bin/env bash
# Self-test for scripts/release/*.sh. No network, no Gradle. Prints "PASS n/n" or exits 1.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

TOTAL=0
PASSED=0
check() { # name, ok(0/1)
  TOTAL=$((TOTAL + 1))
  if [ "$2" -eq 0 ]; then PASSED=$((PASSED + 1)); else echo "FAIL: $1" >&2; fi
}
# expect_rc <name> <expected-rc> <cmd...>
expect_rc() {
  local name="$1" want="$2"; shift 2
  "$@" >"$WORK/out" 2>"$WORK/err"
  local rc=$?
  [ "$rc" -eq "$want" ]; check "$name (rc=$rc, want $want)" $?
}
contains() { grep -qF -- "$2" "$1"; }

# ---- validate-tag ------------------------------------------------------------------
V="$HERE/validate-tag.sh"
expect_rc "validate v0.1.0" 0 bash "$V" v0.1.0
contains "$WORK/out" "prerelease=true"; check "v0.1.0 prerelease=true" $?
contains "$WORK/out" "version=0.1.0"; check "v0.1.0 version" $?
expect_rc "validate v1.2.3" 0 bash "$V" v1.2.3
contains "$WORK/out" "prerelease=false"; check "v1.2.3 prerelease=false" $?
for bad in "v0.1" "0.1.0" "v01.0.0" "v0.1.0-rc1" 'v0.1.0;rm -rf /' $'v0.1.0\n' "" "v0.1.0 "; do
  expect_rc "validate rejects [$bad]" 2 bash "$V" "$bad"
done

# ---- check-version -----------------------------------------------------------------
C="$HERE/check-version.sh"
mk() { printf '%s\n' "$2" > "$WORK/$1"; }
mk ok.kts $'plugins {\n}\n\nallprojects {\n    group = "x"\n    version = "0.1.0"\n}'
expect_rc "check-version match" 0 bash "$C" 0.1.0 "$WORK/ok.kts"
expect_rc "check-version mismatch" 1 bash "$C" 0.2.0 "$WORK/ok.kts"
contains "$WORK/err" "::error::"; check "mismatch emits ::error::" $?
mk none.kts $'allprojects {\n    group = "x"\n}'
expect_rc "check-version none" 1 bash "$C" 0.1.0 "$WORK/none.kts"
mk two.kts $'allprojects {\n    version = "0.1.0"\n    version = "0.1.0"\n}'
expect_rc "check-version two" 1 bash "$C" 0.1.0 "$WORK/two.kts"
mk outside.kts $'version = "9.9.9"\nallprojects {\n    version = "0.1.0"\n}\nsubprojects {\n    version = "8.8.8"\n}'
expect_rc "check-version ignores outside allprojects" 0 bash "$C" 0.1.0 "$WORK/outside.kts"

# ---- extract-release-notes ---------------------------------------------------------
E="$HERE/extract-release-notes.sh"
cat > "$WORK/CL.adoc" <<'CL'
= Changelog

== Unreleased

=== Added

* unreleased-marker

== 0.1.10 — 2026-11-01

=== Added

* ten-marker

== 0.1.1 — 2026-10-30

=== Fixed

* one-marker

== 0.1.0 — 2026-10-19 (pre-release)

=== Added

* zero-marker
CL
expect_rc "notes 0.1.0 (last section)" 0 bash "$E" 0.1.0 "$WORK/CL.adoc" "$WORK/n010.md"
contains "$WORK/n010.md" "zero-marker"; check "0.1.0 has its content" $?
contains "$WORK/n010.md" "### Added"; check "0.1.0 headings converted" $?
! contains "$WORK/n010.md" "one-marker"; check "0.1.0 no foreign content" $?
expect_rc "notes 0.1.1" 0 bash "$E" 0.1.1 "$WORK/CL.adoc" "$WORK/n011.md"
contains "$WORK/n011.md" "one-marker"; check "0.1.1 content" $?
! contains "$WORK/n011.md" "ten-marker"; check "0.1.1 does not match 0.1.10" $?
! contains "$WORK/n011.md" "zero-marker"; check "0.1.1 stops at next section" $?
expect_rc "notes Unreleased never selected" 2 bash "$E" Unreleased "$WORK/CL.adoc" "$WORK/nu.md"
expect_rc "notes missing -> fallback" 0 bash "$E" 9.9.9 "$WORK/CL.adoc" "$WORK/n999.md"
contains "$WORK/err" "::warning::"; check "fallback warns" $?
contains "$WORK/n999.md" "See CHANGELOG.adoc"; check "fallback text" $?

# ---- checksum ----------------------------------------------------------------------
K="$HERE/checksum.sh"
printf 'abc' > "$WORK/abc.bin"
expect_rc "checksum" 0 bash "$K" "$WORK/abc.bin"
contains "$WORK/abc.bin.sha256" "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  abc.bin"
check "checksum known hash + basename-only format" $?
if command -v shasum >/dev/null 2>&1; then
  STUB="$WORK/stub"; mkdir "$STUB"
  for t in bash dirname basename shasum perl; do
    p="$(command -v "$t" 2>/dev/null || true)"; [ -n "$p" ] && ln -s "$p" "$STUB/$t"
  done
  rm -f "$WORK/abc.bin.sha256"
  PATH="$STUB" "$(command -v bash)" "$K" "$WORK/abc.bin" >"$WORK/out" 2>"$WORK/err"; rc=$?
  [ "$rc" -eq 0 ]; check "checksum shasum fallback (rc=$rc)" $?
  contains "$WORK/abc.bin.sha256" "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
  check "shasum fallback hash" $?
fi

# ---- verify-dist (negative cases; the java smoke test is exercised by the real run) ---
if command -v zip >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1; then
  D="$HERE/verify-dist.sh"
  mkdist() { # name, [extra-file-relative-to-root]
    local r="$WORK/d-$1/kstep-cli-1.0.0"
    mkdir -p "$r/bin" "$r/lib"
    : > "$r/bin/kstep-cli"; : > "$r/bin/kstep-cli.bat"; : > "$r/lib/kstep-cli-1.0.0.jar"
    echo "$r"
  }
  r="$(mkdist so)"; : > "$r/lib/libfoo.so"
  (cd "$WORK/d-so" && zip -qr ../so.zip kstep-cli-1.0.0)
  expect_rc "verify-dist rejects .so" 1 bash "$D" "$WORK/so.zip" 1.0.0
  contains "$WORK/err" "native libraries"; check "reports native libraries" $?

  r="$(mkdist jar)"; mkdir -p "$WORK/jarsrc/dev/kstep/geometry/native/linux-x86-64"
  : > "$WORK/jarsrc/dev/kstep/geometry/native/linux-x86-64/x"
  (cd "$WORK/jarsrc" && zip -qr "$r/lib/kstep-geometry-1.0.0.jar" dev)
  (cd "$WORK/d-jar" && zip -qr ../jar.zip kstep-cli-1.0.0)
  expect_rc "verify-dist rejects native resources in jar" 1 bash "$D" "$WORK/jar.zip" 1.0.0
  contains "$WORK/err" "embeds native bridge"; check "reports embedded bridge" $?

  r="$(mkdist nolaunch)"; rm "$r/bin/kstep-cli"
  (cd "$WORK/d-nolaunch" && zip -qr ../nolaunch.zip kstep-cli-1.0.0)
  expect_rc "verify-dist rejects missing launcher" 1 bash "$D" "$WORK/nolaunch.zip" 1.0.0
else
  echo "note: zip/unzip missing, skipping verify-dist negative tests" >&2
fi

echo "PASS $PASSED/$TOTAL"
[ "$PASSED" -eq "$TOTAL" ]
