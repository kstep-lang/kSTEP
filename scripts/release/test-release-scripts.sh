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

  r="$(mkdist ai)"; : > "$r/lib/prompt-executor-anthropic-client-jvm-1.3.0.jar"; : > "$r/lib/ktor-client-core-jvm-3.4.3.jar"
  (cd "$WORK/d-ai" && zip -qr ../ai.zip kstep-cli-1.0.0)
  expect_rc "verify-dist rejects AI/LLM client jars" 1 bash "$D" "$WORK/ai.zip" 1.0.0
  contains "$WORK/err" "AI/LLM client jars"; check "reports AI/LLM client jars" $?

  # the Ktor SERVER jars of kstep-mcp are legitimate in the CLI zip: they must not trip the AI guard
  r="$(mkdist ktorserver)"; : > "$r/lib/ktor-server-core-jvm-3.4.3.jar"
  (cd "$WORK/d-ktorserver" && zip -qr ../ktorserver.zip kstep-cli-1.0.0)
  bash "$D" "$WORK/ktorserver.zip" 1.0.0 >/dev/null 2>"$WORK/err2"
  ! grep -q "AI/LLM client jars" "$WORK/err2"; check "ktor-server jars do not trip the AI guard" $?

  # Full smoke test against a stub launcher (a shell script, no Java needed): export writes a STEP header, import answers
  # as given by $STUB_IMPORT ("unavailable" = the expected geometry_unavailable error, anything else = success), convert copies.
  mkstub() { # name
    local r="$WORK/d-$1/kstep-cli-1.0.0"
    mkdir -p "$r/bin" "$r/lib"
    : > "$r/bin/kstep-cli.bat"; : > "$r/lib/kstep-cli-1.0.0.jar"
    cat > "$r/bin/kstep-cli" <<'STUB'
#!/usr/bin/env bash
case "$1" in
  --help) echo "Usage: kstep-cli"; exit 0 ;;
  export)
    # kstep-cli export <script> [--out f] | [--target t ... | --all-targets] [--out-dir d] [--force] [--output json]:
    # without a target choice it writes --out; with one it writes export-block-smoke.step into --out-dir (exit 1 over
    # an existing file without --force). STUB_EXPORT: nojson = text instead of JSON, notargets = fails, overwrite = never
    # refuses an existing file.
    out=""; outdir=""; targets=0; force=0; json=0
    shift
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --out) out="$2"; shift 2 ;;
        --out-dir) outdir="$2"; shift 2 ;;
        --all-targets) targets=1; shift ;;
        --target) targets=1; shift 2 ;;
        --force) force=1; shift ;;
        --output) json=1; shift 2 ;;
        *) shift ;;
      esac
    done
    if [ "$targets" = "0" ]; then
      printf 'ISO-10303-21;\nEND-ISO-10303-21;\n' > "$out"; exit 0
    fi
    [ "${STUB_EXPORT:-}" = "notargets" ] && exit 1
    t="$outdir/export-block-smoke.step"
    if [ -e "$t" ] && [ "$force" = "0" ] && [ "${STUB_EXPORT:-}" != "overwrite" ]; then exit 1; fi
    printf 'ISO-10303-21;\nEND-ISO-10303-21;\n' > "$t"
    if [ "$json" = "1" ]; then
      if [ "${STUB_EXPORT:-}" = "nojson" ]; then echo "Exported 1 root(s)"; else
        echo '{"status":"success","command":"export","targets":[]}'
      fi
    fi
    exit 0 ;;
  import)
    if [ "${STUB_IMPORT:-}" = "unavailable" ]; then
      echo '{"status":"error","command":"import","errorKind":"geometry_unavailable","code":"KSTEP-S-007"}'; exit 1
    fi
    echo '{"status":"success","command":"import"}'; exit 0 ;;
  convert)
    # kstep-cli convert <file|dir> --to okf|kts [--out <dir>] [--output json] [--tag t] [--title t]: copies each file
    # under the name the real command would use. STUB_CONVERT=nojson prints text instead of JSON, nodir fails for a folder.
    in="$2"; shift 2
    to=""; out=""; json=0; rec=0
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --recursive) rec=1; shift ;;
        --to) to="$2"; shift 2 ;;
        --out) out="$2"; shift 2 ;;
        --tag|--title) shift 2 ;;
        --output) json=1; shift 2 ;;
        *) shift ;;
      esac
    done
    conv() {
      name="$(basename "$1")"
      if [ "$to" = "okf" ]; then cp "$1" "$out/${name%.kstep.kts}.md"; else cp "$1" "$out/${name%.md}.kstep.kts"; fi
    }
    written=""
    if [ -d "$in" ] && [ "$rec" = "1" ]; then
      # recursive: mirror the sub-folders below --out; STUB_CONVERT=norecursive writes only the top level
      [ "${STUB_CONVERT:-}" = "norecursive" ] && { for f in "$in"/*; do [ -f "$f" ] && conv "$f"; done; rec=0; }
      if [ "$rec" = "1" ]; then
        for f in $(cd "$in" && find . -type f | sed 's|^\./||'); do
          mkdir -p "$out/$(dirname "$f")"
          name="$(basename "$f")"
          rel="$(dirname "$f")"; [ "$rel" = "." ] && rel="" || rel="$rel/"
          if [ "$to" = "okf" ]; then tn="${name%.kstep.kts}.md"; else tn="${name%.md}.kstep.kts"; fi
          cp "$in/$f" "$out/$rel$tn"
          written="$written{\"name\":\"$tn\",\"source\":\"$f\",\"target\":\"$rel$tn\"},"
        done
        written="${written%,}"
      fi
    elif [ -d "$in" ]; then
      [ "${STUB_CONVERT:-}" = "nodir" ] && exit 1
      for f in "$in"/*; do [ -f "$f" ] && conv "$f"; done
    else
      conv "$in"
    fi
    if [ "$json" = "1" ]; then
      if [ "${STUB_CONVERT:-}" = "nojson" ]; then echo "Wrote converted file"; else
        if [ "$rec" = "1" ]; then
          echo '{"status":"success","command":"convert","target":"'"$to"'","recursive":true,"written":['"$written"'],"skipped":[]}'
        else
          echo '{"status":"success","command":"convert","target":"'"$to"'","written":[],"skipped":[]}'
        fi
      fi
    fi
    exit 0 ;;
esac
exit 3
STUB
  }
  : > "$WORK/fixture.kts"; : > "$WORK/export-block.kstep.kts"
  mkstub ok; (cd "$WORK/d-ok" && zip -qr ../ok.zip kstep-cli-1.0.0)
  STUB_IMPORT=unavailable expect_rc "verify-dist accepts geometry_unavailable import" 0 \
    bash "$D" "$WORK/ok.zip" 1.0.0 "$WORK/fixture.kts"
  contains "$WORK/out" "OK:"; check "verify-dist prints OK" $?
  STUB_IMPORT=success expect_rc "verify-dist rejects an import that does not report geometry_unavailable" 1 \
    bash "$D" "$WORK/ok.zip" 1.0.0 "$WORK/fixture.kts"
  contains "$WORK/err" "exited 0, expected 1"; check "reports unexpected import exit" $?
  STUB_IMPORT=unavailable STUB_CONVERT=nojson expect_rc "verify-dist rejects convert --output json without JSON" 1 \
    bash "$D" "$WORK/ok.zip" 1.0.0 "$WORK/fixture.kts"
  contains "$WORK/err" "did not report success"; check "reports convert json without success" $?
  STUB_IMPORT=unavailable STUB_CONVERT=nodir expect_rc "verify-dist rejects a failing folder convert" 1 \
    bash "$D" "$WORK/ok.zip" 1.0.0 "$WORK/fixture.kts"
  contains "$WORK/err" "convert of a folder failed"; check "reports failing folder convert" $?
  STUB_IMPORT=unavailable STUB_CONVERT=norecursive expect_rc "verify-dist rejects a convert --recursive that does not mirror" 1 \
    bash "$D" "$WORK/ok.zip" 1.0.0 "$WORK/fixture.kts"
  contains "$WORK/err" "recursive"; check "reports convert --recursive that does not mirror" $?
  STUB_IMPORT=unavailable STUB_EXPORT=nojson expect_rc "verify-dist rejects export --all-targets without JSON" 1 \
    bash "$D" "$WORK/ok.zip" 1.0.0 "$WORK/fixture.kts"
  contains "$WORK/err" "did not report success"; check "reports export targets json without success" $?
  STUB_IMPORT=unavailable STUB_EXPORT=notargets expect_rc "verify-dist rejects a failing export --all-targets" 1 \
    bash "$D" "$WORK/ok.zip" 1.0.0 "$WORK/fixture.kts"
  contains "$WORK/err" "--all-targets failed"; check "reports failing export --all-targets" $?
  STUB_IMPORT=unavailable STUB_EXPORT=overwrite expect_rc "verify-dist rejects an export that overwrites without --force" 1 \
    bash "$D" "$WORK/ok.zip" 1.0.0 "$WORK/fixture.kts"
  contains "$WORK/err" "expected 1 (no --force)"; check "reports silent overwrite" $?
  STUB_IMPORT=unavailable expect_rc "verify-dist rejects a missing targets fixture" 1 \
    bash "$D" "$WORK/ok.zip" 1.0.0 "$WORK/fixture.kts" "$WORK/missing-targets.kts"
  contains "$WORK/err" "targets fixture"; check "reports missing targets fixture" $?
else
  echo "note: zip/unzip missing, skipping verify-dist negative tests" >&2
fi

# ---- verify-native-dist (negative cases + stub happy path; no Java or real bridge needed) -----
if command -v zip >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1; then
  ND="$HERE/verify-native-dist.sh"
  case "$(uname -s)/$(uname -m)" in
    Linux/x86_64) NP=linux-x86-64; NE=so ;;
    Darwin/arm64) NP=macos-aarch64; NE=dylib ;;
    *) NP=""; NE="" ;;
  esac
  if [ -n "$NP" ]; then
    NR="kstep-1.0.0-$NP-native"
    mknative() { # name -> creates a complete stub archive tree and prints its root
      local r="$WORK/n-$1/$NR"
      mkdir -p "$r/bin" "$r/lib/native/$NP"
      : > "$r/lib/kstep-cli-1.0.0.jar"; : > "$r/NATIVE-README.txt"; : > "$r/LICENSE"; : > "$r/NOTICE"
      echo x > "$r/lib/native/$NP/libkstep_occt_bridge.$NE"; echo x > "$r/lib/native/$NP/libkstep_planegcs_bridge.$NE"
      cat > "$r/bin/kstep" <<'STUB'
#!/bin/sh
case "${KSTEP_OCCT_LIB_DIR:-}" in ""|/*) ;; *) echo "kstep: KSTEP_OCCT_LIB_DIR must be absolute" >&2; exit 2 ;; esac
case "$1" in
  --help) echo "Usage: kstep"; exit 0 ;;
  --version) echo "kstep 1.0.0"; echo "occt: available 7.9.3"; echo "planegcs: available abc"; exit 0 ;;
  render)
    while [ "$#" -gt 0 ]; do [ "$1" = "--out" ] && out="$2"; shift; done
    printf 'solid x\n' > "$out"; exit 0 ;;
esac
exit 3
STUB
      echo "$r"
    }
    zipnative() { mkdir -p "$WORK/nz-$1"; (cd "$WORK/n-$1" && zip -qr "$WORK/nz-$1/$NR.zip" "$NR"); echo "$WORK/nz-$1/$NR.zip"; }
    : > "$WORK/nfixture.kts"
    mknative ok >/dev/null; Z="$(zipnative ok)"
    expect_rc "verify-native accepts a complete stub archive" 0 bash "$ND" "$Z" 1.0.0 "$WORK/nfixture.kts"
    contains "$WORK/out" "OK:"; check "verify-native prints OK" $?

    r="$(mknative nolaunch)"; rm "$r/bin/kstep"; Z="$(zipnative nolaunch)"
    expect_rc "verify-native rejects missing launcher" 1 bash "$ND" "$Z" 1.0.0 "$WORK/nfixture.kts"
    r="$(mknative occt)"; : > "$r/lib/native/$NP/libTKernel.$NE"; Z="$(zipnative occt)"
    expect_rc "verify-native rejects bundled OCCT libs" 1 bash "$ND" "$Z" 1.0.0 "$WORK/nfixture.kts"
    r="$(mknative nogcs)"; rm "$r/lib/native/$NP/libkstep_planegcs_bridge.$NE"; Z="$(zipnative nogcs)"
    expect_rc "verify-native rejects a missing PlaneGCS bridge" 1 bash "$ND" "$Z" 1.0.0 "$WORK/nfixture.kts"
    r="$(mknative jar)"; mkdir -p "$WORK/njarsrc/dev/kstep/geometry/native/$NP"; : > "$WORK/njarsrc/dev/kstep/geometry/native/$NP/x"
    (cd "$WORK/njarsrc" && zip -qr "$r/lib/kstep-geometry-1.0.0.jar" dev); Z="$(zipnative jar)"
    expect_rc "verify-native rejects natives embedded in a jar" 1 bash "$ND" "$Z" 1.0.0 "$WORK/nfixture.kts"
    contains "$WORK/err" "embeds native bridge"; check "verify-native reports embedded bridge" $?
    r="$(mknative java1)"; mkdir -p "$r/jre/bin"; echo x > "$r/jre/bin/java"; Z="$(zipnative java1)"
    expect_rc "verify-native rejects a bundled jre/bin/java" 1 bash "$ND" "$Z" 1.0.0 "$WORK/nfixture.kts"
    contains "$WORK/err" "Java runtime"; check "verify-native reports the bundled Java" $?
    r="$(mknative java2)"; mkdir -p "$r/lib/server"; echo x > "$r/lib/server/libjvm.$NE"; Z="$(zipnative java2)"
    expect_rc "verify-native rejects a bundled libjvm" 1 bash "$ND" "$Z" 1.0.0 "$WORK/nfixture.kts"
    r="$(mknative dup)"; Z="$(zipnative dup)"
    python3 - "$Z" "$NR/NOTICE" <<'PY' 2>/dev/null
import sys, zipfile, warnings
warnings.simplefilter("ignore")
with zipfile.ZipFile(sys.argv[1], "a") as z:
    z.writestr(sys.argv[2], b"dup")
PY
    expect_rc "verify-native rejects duplicate entries" 1 bash "$ND" "$Z" 1.0.0 "$WORK/nfixture.kts"
  fi
  # Wrong host: an archive for the other platform must be refused with exit 2.
  case "${NP:-}" in macos-aarch64) OTHER=linux-x86-64 ;; *) OTHER=macos-aarch64 ;; esac
  mkdir -p "$WORK/nwrong"; : > "$WORK/nwrong/kstep-1.0.0-$OTHER-native.zip"
  expect_rc "verify-native refuses an archive of another platform" 2 bash "$ND" "$WORK/nwrong/kstep-1.0.0-$OTHER-native.zip" 1.0.0
  contains "$WORK/err" "wrong host"; check "verify-native reports wrong host" $?
else
  echo "note: zip/unzip missing, skipping verify-native-dist tests" >&2
fi

# ---- check-native-linkage (otool/readelf/objdump replaced by stubs) -----------------------------
if command -v zip >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1; then
  CL="$HERE/check-native-linkage.sh"
  FB="$WORK/fakebin"; mkdir -p "$FB"
  for t in otool readelf objdump; do
    cat > "$FB/$t" <<'STUB'
#!/bin/sh
# Prints $FAKE_DIR/<tool>.<flag>.<occt|pgcs>; the last argument is the library path.
tool=$(basename "$0"); flag="$1"; shift
case "$flag" in -L) flag=-deps ;; -l) flag=-cmds ;; esac
for a in "$@"; do last="$a"; done
case "$last" in *planegcs*) kind=pgcs ;; *) kind=occt ;; esac
f="$FAKE_DIR/$tool$flag.$kind"
[ -f "$f" ] || f="$FAKE_DIR/$tool$flag"
cat "$f"
STUB
    chmod +x "$FB/$t"
  done
  mklink() { # platform name -> zip path with two stub bridge files
    local plat="$1" nm="$2" ext=so
    [ "$plat" = macos-aarch64 ] && ext=dylib
    local r="$WORK/cl-$nm/kstep-1.0.0-$plat-native"
    mkdir -p "$r/lib/native/$plat" "$WORK/clz-$nm"
    echo x > "$r/lib/native/$plat/libkstep_occt_bridge.$ext"; echo x > "$r/lib/native/$plat/libkstep_planegcs_bridge.$ext"
    (cd "$WORK/cl-$nm" && zip -qr "$WORK/clz-$nm/kstep-1.0.0-$plat-native.zip" "kstep-1.0.0-$plat-native")
    mkdir -p "$WORK/fake-$nm"; echo "$WORK/clz-$nm/kstep-1.0.0-$plat-native.zip"
  }
  runcl() { # name zip -> runs with stubs
    FAKE_DIR="$WORK/fake-$1" PATH="$FB:$PATH" bash "$CL" "$2" 1.0.0
  }
  clcase() { # name want-rc zip
    local name="$1" want="$2" z="$3"
    FAKE_DIR="$WORK/fake-$name" PATH="$FB:$PATH" bash "$CL" "$z" 1.0.0 >"$WORK/out" 2>"$WORK/err"
    local rc=$?
    [ "$rc" -eq "$want" ]; check "check-native-linkage $name (rc=$rc, want $want)" $?
  }
  # macOS fixtures
  OPT=/opt/homebrew/opt/opencascade/lib
  mac_L() { # file, extra-dep
    { echo "x.dylib:"; echo "	@rpath/libkstep_occt_bridge.dylib (compatibility version 0.0.0, current version 0.0.0)"
      echo "	$OPT/libTKernel.7.9.dylib (compatibility version 7.9.0, current version 7.9.0)"
      echo "	/usr/lib/libc++.1.dylib (compatibility version 1.0.0, current version 1900.0.0)"
      [ -n "${2:-}" ] && echo "	$2 (compatibility version 1.0.0, current version 1.0.0)"; } > "$1"
  }
  mac_l() { printf 'Load command 1\n          cmd LC_RPATH\n      cmdsize 48\n         path %s (offset 12)\nLoad command 2\n      cmd LC_BUILD_VERSION\n     platform 1\n        minos 14.0\n' "$1" > "$2"; }
  pg_L() { { echo "p.dylib:"; echo "	@rpath/libkstep_planegcs_bridge.dylib (compatibility version 0.0.0, current version 0.0.0)"; echo "	/usr/lib/libc++.1.dylib (compatibility version 1.0.0, current version 1900.0.0)"; } > "$1"; }
  mkmac() { # name extra-dep rpath
    local z; z="$(mklink macos-aarch64 "$1")"; local d="$WORK/fake-$1"
    mac_L "$d/otool-deps.occt" "$2"; pg_L "$d/otool-deps.pgcs"; mac_l "$3" "$d/otool-cmds.occt"; : > "$d/otool-cmds.pgcs"
    echo "$z"
  }
  Z="$(mkmac macok "" $OPT)"; clcase macok 0 "$Z"
  contains "$WORK/out" "minos=14.0"; check "linkage prints minos" $?
  Z="$(mkmac maccellar "/opt/homebrew/Cellar/opencascade/7.9.3_1/lib/libTKernel.7.9.dylib" $OPT)"; clcase maccellar 1 "$Z"
  contains "$WORK/err" "versioned path"; check "linkage names the Cellar path" $?
  Z="$(mkmac macrunner "/Users/runner/work/lib/libfoo.dylib" $OPT)"; clcase macrunner 1 "$Z"
  Z="$(mkmac macrpath "" /Users/runner/occt/lib)"; clcase macrpath 1 "$Z"
  contains "$WORK/err" "LC_RPATH"; check "linkage names the foreign LC_RPATH" $?
  Z="$(mkmac macforeign "/usr/local/lib/libfoo.dylib" $OPT)"; clcase macforeign 1 "$Z"
  Z="$(mkmac macotherocct "/opt/homebrew/opt/opencascade/lib/libTKernel.8.0.dylib" $OPT)"; clcase macotherocct 1 "$Z"
  # Linux fixtures
  lin_d() { # file, rpathline, extra needed
    { echo "Dynamic section at offset 0x1 contains 9 entries:"
      echo " 0x0000000000000001 (NEEDED)             Shared library: [libTKernel.so.7.9]"
      echo " 0x0000000000000001 (NEEDED)             Shared library: [libstdc++.so.6]"
      echo " 0x0000000000000001 (NEEDED)             Shared library: [libc.so.6]"
      [ -n "${3:-}" ] && echo " 0x0000000000000001 (NEEDED)             Shared library: [$3]"
      echo "$2"; } > "$1"
  }
  mklin() { # name rpathline needed
    local z; z="$(mklink linux-x86-64 "$1")"; local d="$WORK/fake-$1"
    lin_d "$d/readelf-d.occt" "$2" "${3:-}"
    printf 'Dynamic section:\n 0x1 (NEEDED)             Shared library: [libstdc++.so.6]\n 0x1 (NEEDED)             Shared library: [libm.so.6]\n' > "$d/readelf-d.pgcs"
    printf 'x  DF *UND*  0000  GLIBC_2.34 memcpy\nx  DF *UND*  0000  GLIBC_2.38 strlcpy\nx  DF *UND*  0000  GLIBCXX_3.4.32 foo\nx  DF *UND*  0000  GLIBCXX_3.4.9 bar\n' > "$d/objdump-T.occt"
    : > "$d/objdump-T.pgcs"
    echo "$z"
  }
  Z="$(mklin linok ' 0x000000000000000f (RPATH)              Library rpath: [/usr/lib/x86_64-linux-gnu]')"; clcase linok 0 "$Z"
  contains "$WORK/out" "glibc_floor=2.38"; check "linkage prints glibc_floor" $?
  contains "$WORK/out" "glibcxx_floor=3.4.32"; check "linkage prints glibcxx_floor" $?
  Z="$(mklin linrunpath ' 0x000000000000001d (RUNPATH)            Library runpath: [/usr/lib/x86_64-linux-gnu]')"; clcase linrunpath 1 "$Z"
  contains "$WORK/err" "RUNPATH"; check "linkage names the RUNPATH" $?
  Z="$(mklin linhome ' 0x000000000000000f (RPATH)              Library rpath: [/home/runner/occt/lib]')"; clcase linhome 1 "$Z"
  Z="$(mklin linforeign ' 0x000000000000000f (RPATH)              Library rpath: [/usr/lib/x86_64-linux-gnu]' libfoo.so.1)"; clcase linforeign 1 "$Z"
  contains "$WORK/err" "libfoo.so.1"; check "linkage names the foreign NEEDED" $?
  Z="$(mklin linnorpath '')"; clcase linnorpath 1 "$Z"
  # usage errors
  expect_rc "check-native-linkage wrong archive name" 2 bash "$CL" "$WORK/nope.zip" 1.0.0
  : > "$WORK/weird.zip"; expect_rc "check-native-linkage unknown platform name" 2 bash "$CL" "$WORK/weird.zip" 1.0.0
else
  echo "note: zip/unzip missing, skipping check-native-linkage tests" >&2
fi

# ---- tap-payload ---------------------------------------------------------------------------------
TP="$HERE/tap-payload.sh"
if command -v jq >/dev/null 2>&1 && command -v python3 >/dev/null 2>&1; then
  if command -v sha256sum >/dev/null 2>&1; then TS=(sha256sum); else TS=(shasum -a 256); fi
  mkassets() { # name -> dir with both zips + valid sums
    local d="$WORK/tp-$1"; mkdir -p "$d"
    for p in macos-aarch64 linux-x86-64; do
      printf 'zip-%s' "$p" > "$d/kstep-1.2.3-$p-native.zip"
      (cd "$d" && "${TS[@]}" "kstep-1.2.3-$p-native.zip" > "kstep-1.2.3-$p-native.zip.sha256")
    done
    echo "$d"
  }
  d="$(mkassets ok)"
  expect_rc "tap-payload good" 0 bash "$TP" "$d" 1.2.3 kstep-lang/kSTEP
  cp "$WORK/out" "$WORK/payload.json"
  python3 - "$WORK/payload.json" <<'PY'
import json, sys
p = json.load(open(sys.argv[1]))
assert p["version"] == "1.2.3"
m = p["platforms"]["macos-aarch64"]; l = p["platforms"]["linux-x86-64"]
assert m["url"] == "https://github.com/kstep-lang/kSTEP/releases/download/v1.2.3/kstep-1.2.3-macos-aarch64-native.zip"
assert l["url"].endswith("/kstep-1.2.3-linux-x86-64-native.zip")
assert len(m["sha256"]) == 64 and len(l["sha256"]) == 64 and m["sha256"] != l["sha256"]
PY
  check "tap-payload JSON structure" $?
  d="$(mkassets noplat)"; rm "$d/kstep-1.2.3-linux-x86-64-native.zip" "$d/kstep-1.2.3-linux-x86-64-native.zip.sha256"
  expect_rc "tap-payload missing platform" 1 bash "$TP" "$d" 1.2.3 kstep-lang/kSTEP
  d="$(mkassets badline)"; echo "nothex  kstep-1.2.3-macos-aarch64-native.zip" > "$d/kstep-1.2.3-macos-aarch64-native.zip.sha256"
  expect_rc "tap-payload malformed sha line" 1 bash "$TP" "$d" 1.2.3 kstep-lang/kSTEP
  d="$(mkassets tamper)"; printf 'evil' > "$d/kstep-1.2.3-linux-x86-64-native.zip"
  expect_rc "tap-payload tampered zip" 1 bash "$TP" "$d" 1.2.3 kstep-lang/kSTEP
  contains "$WORK/err" "does not match"; check "tap-payload reports the mismatch" $?
  d="$(mkassets ok2)"
  expect_rc "tap-payload injection in version" 2 bash "$TP" "$d" '1.2.3;rm -rf /' kstep-lang/kSTEP
  expect_rc "tap-payload quote in version" 2 bash "$TP" "$d" '1.2.3"' kstep-lang/kSTEP
  expect_rc "tap-payload command substitution in version" 2 bash "$TP" "$d" '$(id)' kstep-lang/kSTEP
  expect_rc "tap-payload repo with space" 2 bash "$TP" "$d" 1.2.3 'kstep-lang/kSTEP x'
  expect_rc "tap-payload repo with quote" 2 bash "$TP" "$d" 1.2.3 'a/b"c'
  expect_rc "tap-payload usage" 2 bash "$TP" "$d"
else
  echo "note: jq/python3 missing, skipping tap-payload tests" >&2
fi

# ---- check-maven-central (curl stubbed, no network) ----------------------------------
M="$HERE/check-maven-central.sh"
MC_LIST="$WORK/mc-artifacts.txt"
printf '# comment\nkstep-core\nkstep-asciidoc\n\nkstep-mcp\n' > "$MC_LIST"
mkdir -p "$WORK/mcbin"
cat > "$WORK/mcbin/curl" <<'STUB'
#!/usr/bin/env bash
# Stub: the last argument is the URL; the code comes from $MC_CODES (artifact=code lines, default 404).
url="${*: -1}"
echo "$url" >> "$MC_LOG"
art="$(basename "$(dirname "$(dirname "$url")")")"
code="$(grep "^$art=" "$MC_CODES" 2>/dev/null | head -1 | cut -d= -f2)"
printf '%s' "${code:-404}"
STUB
chmod +x "$WORK/mcbin/curl"
export MC_LOG="$WORK/mc.log" MC_CODES="$WORK/mc.codes"
mc() { PATH="$WORK/mcbin:$PATH" bash "$M" "$@"; }
: > "$MC_LOG"
printf '' > "$MC_CODES"
expect_rc "central all 404 -> absent" 0 mc 0.4.0 https://example.invalid/maven2 "$MC_LIST"
contains "$WORK/out" "state=absent"; check "central state=absent" $?
contains "$MC_LOG" "https://example.invalid/maven2/dev/kstep/kstep-asciidoc/0.4.0/kstep-asciidoc-0.4.0.pom"
check "central URL form" $?
printf 'kstep-core=200\nkstep-asciidoc=200\nkstep-mcp=200\n' > "$MC_CODES"
expect_rc "central all 200 -> published" 0 mc 0.4.0 https://example.invalid/maven2 "$MC_LIST"
contains "$WORK/out" "state=published"; check "central state=published" $?
printf 'kstep-core=200\n' > "$MC_CODES"
expect_rc "central mixed -> error" 1 mc 0.4.0 https://example.invalid/maven2 "$MC_LIST"
contains "$WORK/err" "::error::"; check "central mixed emits ::error::" $?
printf 'kstep-core=500\n' > "$MC_CODES"
expect_rc "central 500 -> error" 1 mc 0.4.0 https://example.invalid/maven2 "$MC_LIST"
printf 'kstep-mcp=000\n' > "$MC_CODES"
expect_rc "central 000 -> error" 1 mc 0.4.0 https://example.invalid/maven2 "$MC_LIST"
printf '' > "$MC_CODES"
for bad in "0.4" "v0.4.0" "0.4.0;x" ""; do
  expect_rc "central rejects version [$bad]" 2 mc "$bad" https://example.invalid/maven2 "$MC_LIST"
done
expect_rc "central rejects http base" 2 mc 0.4.0 http://example.invalid/maven2 "$MC_LIST"

# ---- verify-maven-artifacts (fixture repository built with python3) -------------------
if command -v python3 >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1; then
  VM="$HERE/verify-maven-artifacts.sh"
  VM_LIST="$WORK/vm-artifacts.txt"
  printf 'kstep-core\nkstep-express\n' > "$VM_LIST"
  mkrepo() { # name; builds $WORK/repo-<name> with a good fixture
    python3 -I - "$WORK/repo-$1" <<'PY'
import hashlib, os, sys, zipfile
root = sys.argv[1]
arts = ["kstep-core", "kstep-express"]
POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>dev.kstep</groupId><artifactId>%s</artifactId><version>1.0.0</version>
<name>%s</name><description>d</description><url>https://kstep.dev</url>
<licenses><license><name>Apache-2.0</name></license></licenses>
<developers><developer><id>ibetchvaia</id></developer></developers>
<scm><connection>c</connection><developerConnection>d</developerConnection>
<url>https://github.com/kstep-lang/kSTEP</url></scm>
<dependencies>%s</dependencies></project>"""
DEP = "<dependency><groupId>dev.kstep</groupId><artifactId>kstep-express</artifactId><version>1.0.0</version></dependency>"
def jar(path, names):
    with zipfile.ZipFile(path, "w") as z:
        for n in names:
            z.writestr(n, "x")
for a in arts:
    d = os.path.join(root, "dev/kstep", a, "1.0.0")
    os.makedirs(d)
    base = os.path.join(d, a + "-1.0.0")
    legal = ["META-INF/LICENSE", "META-INF/NOTICE"]
    main = legal + ["dev/kstep/x/A.class"]
    if a == "kstep-core":
        main.append("dev/kstep/generated/ap242v1/Product.class")
    jar(base + ".jar", main)
    jar(base + "-sources.jar", legal + ["dev/kstep/x/A.kt"])
    jar(base + "-javadoc.jar", legal)
    open(base + ".pom", "w").write(POM % (a, a, DEP if a == "kstep-core" else ""))
    open(base + ".module", "w").write('{"variants":[{"name":"apiElements"}]}')
    for f in os.listdir(d):
        p = os.path.join(d, f)
        data = open(p, "rb").read()
        for algo in ("md5", "sha1", "sha256", "sha512"):
            open(p + "." + algo, "w").write(hashlib.new(algo, data).hexdigest())
PY
  }
  rehash() { # file: rewrites checksums of a modified file
    python3 -I - "$1" <<'PY'
import hashlib, sys
p = sys.argv[1]
data = open(p, "rb").read()
for algo in ("md5", "sha1", "sha256", "sha512"):
    open(p + "." + algo, "w").write(hashlib.new(algo, data).hexdigest())
PY
  }
  CORE="dev/kstep/kstep-core/1.0.0/kstep-core-1.0.0"
  mkrepo good
  expect_rc "verify-maven good fixture" 0 bash "$VM" "$WORK/repo-good" 1.0.0 "$VM_LIST"
  mkrepo nosrc; rm "$WORK/repo-nosrc/$CORE-sources.jar"
  expect_rc "verify-maven missing sources jar" 1 bash "$VM" "$WORK/repo-nosrc" 1.0.0 "$VM_LIST"
  mkrepo extra; mkdir -p "$WORK/repo-extra/dev/kstep/kstep-cli"
  expect_rc "verify-maven extra module directory" 1 bash "$VM" "$WORK/repo-extra" 1.0.0 "$VM_LIST"
  mkrepo so
  python3 -I - "$WORK/repo-so/$CORE.jar" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "a") as z:
    z.writestr("dev/kstep/geometry/native/linux-x86-64/libkstep_occt_bridge.so", "x")
PY
  rehash "$WORK/repo-so/$CORE.jar"
  expect_rc "verify-maven native library in jar" 1 bash "$VM" "$WORK/repo-so" 1.0.0 "$VM_LIST"
  mkrepo sha; printf 'deadbeef' > "$WORK/repo-sha/$CORE.jar.sha1"
  expect_rc "verify-maven wrong checksum" 1 bash "$VM" "$WORK/repo-sha" 1.0.0 "$VM_LIST"
  mkrepo nolic
  sed -i.bak 's#<licenses>.*</licenses>##' "$WORK/repo-nolic/$CORE.pom"; rm "$WORK/repo-nolic/$CORE.pom.bak"
  rehash "$WORK/repo-nolic/$CORE.pom"
  expect_rc "verify-maven POM without licence" 1 bash "$VM" "$WORK/repo-nolic" 1.0.0 "$VM_LIST"
  mkrepo cli
  sed -i.bak 's#<artifactId>kstep-express</artifactId><version>1.0.0</version></dependency>#<artifactId>kstep-cli</artifactId><version>1.0.0</version></dependency>#' \
    "$WORK/repo-cli/$CORE.pom"; rm "$WORK/repo-cli/$CORE.pom.bak"
  rehash "$WORK/repo-cli/$CORE.pom"
  expect_rc "verify-maven POM depending on kstep-cli" 1 bash "$VM" "$WORK/repo-cli" 1.0.0 "$VM_LIST"
  mkrepo nogen
  python3 -I - "$WORK/repo-nogen/$CORE.jar" <<'PY'
import sys, zipfile
p = sys.argv[1]
with zipfile.ZipFile(p) as z:
    items = [(n, z.read(n)) for n in z.namelist() if "ap242v1" not in n]
with zipfile.ZipFile(p, "w") as z:
    for n, d in items:
        z.writestr(n, d)
PY
  rehash "$WORK/repo-nogen/$CORE.jar"
  expect_rc "verify-maven core jar without generated classes" 1 bash "$VM" "$WORK/repo-nogen" 1.0.0 "$VM_LIST"
  mkrepo asc; : > "$WORK/repo-asc/$CORE.jar.asc"
  expect_rc "verify-maven .asc without --signed" 1 bash "$VM" "$WORK/repo-asc" 1.0.0 "$VM_LIST"
else
  echo "note: python3/unzip missing, skipping verify-maven-artifacts tests" >&2
fi

echo "PASS $PASSED/$TOTAL"
[ "$PASSED" -eq "$TOTAL" ]
