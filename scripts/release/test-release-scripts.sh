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

  # Full smoke test against a stub launcher (a shell script, no Java needed): export writes a STEP header, import answers
  # as given by $STUB_IMPORT ("unavailable" = the expected geometry_unavailable error, anything else = success).
  mkstub() { # name
    local r="$WORK/d-$1/kstep-cli-1.0.0"
    mkdir -p "$r/bin" "$r/lib"
    : > "$r/bin/kstep-cli.bat"; : > "$r/lib/kstep-cli-1.0.0.jar"
    cat > "$r/bin/kstep-cli" <<'STUB'
#!/usr/bin/env bash
case "$1" in
  --help) echo "Usage: kstep-cli"; exit 0 ;;
  export)
    while [ "$#" -gt 0 ]; do [ "$1" = "--out" ] && out="$2"; shift; done
    printf 'ISO-10303-21;\nEND-ISO-10303-21;\n' > "$out"; exit 0 ;;
  import)
    if [ "${STUB_IMPORT:-}" = "unavailable" ]; then
      echo '{"status":"error","command":"import","errorKind":"geometry_unavailable","code":"KSTEP-S-007"}'; exit 1
    fi
    echo '{"status":"success","command":"import"}'; exit 0 ;;
esac
exit 3
STUB
  }
  : > "$WORK/fixture.kts"
  mkstub ok; (cd "$WORK/d-ok" && zip -qr ../ok.zip kstep-cli-1.0.0)
  STUB_IMPORT=unavailable expect_rc "verify-dist accepts geometry_unavailable import" 0 \
    bash "$D" "$WORK/ok.zip" 1.0.0 "$WORK/fixture.kts"
  contains "$WORK/out" "OK:"; check "verify-dist prints OK" $?
  STUB_IMPORT=success expect_rc "verify-dist rejects an import that does not report geometry_unavailable" 1 \
    bash "$D" "$WORK/ok.zip" 1.0.0 "$WORK/fixture.kts"
  contains "$WORK/err" "exited 0, expected 1"; check "reports unexpected import exit" $?
else
  echo "note: zip/unzip missing, skipping verify-dist negative tests" >&2
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
