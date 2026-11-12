#!/usr/bin/env bash
# Usage: verify-maven-artifacts.sh <repo-dir> <version> [--signed] [artifacts-file]
# Verifies a local Maven repository layout (build/maven-staging) before anything is uploaded to
# Maven Central: file set, checksums, signatures, POM content, jar content. Needs unzip + python3.
# See ADR-0043.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="${1-}"
VERSION="${2-}"
SIGNED=0
LIST="$HERE/published-artifacts.txt"
shift 2 || true
for arg in "$@"; do
  case "$arg" in
    --signed) SIGNED=1 ;;
    *) LIST="$arg" ;;
  esac
done

fail() { echo "::error::verify-maven-artifacts: $*" >&2; exit 1; }

RE='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
[[ "$VERSION" =~ $RE ]] || fail "invalid version"
[ -d "$REPO/dev/kstep" ] || fail "no dev/kstep directory in repository"
[ -f "$LIST" ] || fail "artifact list not found"
command -v unzip >/dev/null 2>&1 || fail "unzip is required"
command -v python3 >/dev/null 2>&1 || fail "python3 is required"

ARTIFACTS=()
while IFS= read -r line || [ -n "$line" ]; do
  a="${line//[[:space:]]/}"
  case "$a" in ''|'#'*) continue ;; esac
  ARTIFACTS+=("$a")
done < "$LIST"
[ "${#ARTIFACTS[@]}" -gt 0 ] || fail "empty artifact list"

hash_of() { # algo file
  local algo="$1" f="$2" n="${1#sha}"
  case "$algo" in
    md5)
      if command -v md5sum >/dev/null 2>&1; then md5sum "$f" | cut -d' ' -f1
      elif command -v md5 >/dev/null 2>&1; then md5 -q "$f"
      else openssl dgst -md5 "$f" | awk '{print $NF}'; fi ;;
    sha1|sha256|sha512)
      if command -v "sha${n}sum" >/dev/null 2>&1; then "sha${n}sum" "$f" | cut -d' ' -f1
      elif command -v shasum >/dev/null 2>&1; then shasum -a "$n" "$f" | cut -d' ' -f1
      else openssl dgst "-$algo" "$f" | awk '{print $NF}'; fi ;;
  esac
}

# Structure: dev/ holds only kstep/, kstep/ holds exactly the listed artifacts.
[ "$(find "$REPO/dev" -mindepth 1 -maxdepth 1 | wc -l | tr -d ' ')" = "1" ] || fail "unexpected entries under dev/"
actual="$(find "$REPO/dev/kstep" -mindepth 1 -maxdepth 1 -exec basename {} \; | LC_ALL=C sort | tr '\n' ' ')"
expected="$(printf '%s\n' "${ARTIFACTS[@]}" | LC_ALL=C sort | tr '\n' ' ')"
[ "$actual" = "$expected" ] || fail "artifact directories differ. found: $actual expected: $expected"

for a in "${ARTIFACTS[@]}"; do
  d="$REPO/dev/kstep/$a/$VERSION"
  [ -d "$d" ] || fail "$a: missing version directory"
  base="$d/$a-$VERSION"
  for ext in pom module jar -sources.jar -javadoc.jar; do
    case "$ext" in -*) f="$base$ext" ;; *) f="$base.$ext" ;; esac
    [ -f "$f" ] || fail "$a: missing $(basename "$f")"
    for algo in md5 sha1; do
      [ -f "$f.$algo" ] || fail "$a: missing $(basename "$f").$algo"
    done
    for algo in md5 sha1 sha256 sha512; do
      if [ -f "$f.$algo" ]; then
        want="$(tr -d '[:space:]' < "$f.$algo")"
        got="$(hash_of "$algo" "$f")"
        [ "$want" = "$got" ] || fail "$a: $(basename "$f").$algo does not match the file"
      fi
    done
    if [ "$SIGNED" = 1 ]; then
      [ -f "$f.asc" ] || fail "$a: missing $(basename "$f").asc"
      if command -v gpg >/dev/null 2>&1; then
        gpg --batch --verify "$f.asc" "$f" >/dev/null 2>&1 || fail "$a: bad signature on $(basename "$f")"
      fi
    else
      [ ! -f "$f.asc" ] || fail "$a: unexpected signature $(basename "$f").asc in an unsigned run"
    fi
  done

  # Jars: no native library, legal files present, sources contain Kotlin.
  for j in "$base.jar" "$base-sources.jar" "$base-javadoc.jar"; do
    entries="$(unzip -Z1 "$j")" || fail "$a: cannot read $(basename "$j")"
    # Here-strings, not pipes: `grep -q` closing early would SIGPIPE the writer and trip pipefail.
    if grep -Eiq '\.(so|dylib|dll|jnilib)$|^dev/kstep/[^/]+/native/.' <<<"$entries"; then
      fail "$a: $(basename "$j") contains a native library"
    fi
    grep -qx 'META-INF/LICENSE' <<<"$entries" || fail "$a: $(basename "$j") lacks META-INF/LICENSE"
    grep -qx 'META-INF/NOTICE' <<<"$entries" || fail "$a: $(basename "$j") lacks META-INF/NOTICE"
  done
  src_entries="$(unzip -Z1 "$base-sources.jar")"
  grep -Eq '\.(kt|java)$' <<<"$src_entries" || fail "$a: sources jar contains no .kt or .java file"
  if [ "$a" = "kstep-core" ]; then
    core_entries="$(unzip -Z1 "$base.jar")"
    grep -q '^dev/kstep/generated/ap242v1/' <<<"$core_entries" \
      || fail "kstep-core: jar lacks the generated AP242 classes (dev/kstep/generated/ap242v1/)"
  fi

  # POM and Gradle module metadata.
  ARTS="$(printf '%s ' "${ARTIFACTS[@]}")" python3 -I - "$base.pom" "$base.module" "$a" "$VERSION" <<'PY' || fail "$a: POM/module check failed"
import json, os, sys
import xml.etree.ElementTree as ET

pom, module, art, ver = sys.argv[1:5]
known = set(os.environ["ARTS"].split())
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
r = ET.parse(pom).getroot()

def t(path, root=r):
    e = root.find(path, ns)
    return (e.text or "").strip() if e is not None else ""

errs = []
if t("m:groupId") != "dev.kstep": errs.append("groupId")
if t("m:artifactId") != art: errs.append("artifactId")
if t("m:version") != ver: errs.append("version")
if not t("m:name"): errs.append("name")
if not t("m:description"): errs.append("description")
if t("m:url") != "https://kstep.dev": errs.append("url")
if t("m:licenses/m:license/m:name") != "Apache-2.0": errs.append("license")
if "github.com/kstep-lang/kSTEP" not in t("m:scm/m:url"): errs.append("scm url")
if not t("m:scm/m:connection"): errs.append("scm connection")
if not t("m:scm/m:developerConnection"): errs.append("scm developerConnection")
if t("m:developers/m:developer/m:id") != "ibetchvaia": errs.append("developer id")
banned = {"kstep-cli", "kstep-viewer", "kstep-tests", "kstep-docs"}
for d in r.findall("m:dependencies/m:dependency", ns):
    if t("m:groupId", d) == "dev.kstep":
        dep = t("m:artifactId", d)
        if dep in banned: errs.append("dependency on unpublished module " + dep)
        elif dep not in known: errs.append("dependency on unlisted module " + dep)
        if t("m:version", d) != ver: errs.append("dependency version of " + dep)
mod = json.load(open(module))
for v in mod.get("variants", []):
    if "ap242" in v.get("name", "").lower():
        errs.append("module variant " + v["name"])
if errs:
    print("; ".join(errs), file=sys.stderr)
    sys.exit(1)
PY
done

echo "OK: ${#ARTIFACTS[@]} artifacts verified in $REPO (signed=$SIGNED)"
