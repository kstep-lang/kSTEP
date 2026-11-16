#!/usr/bin/env bash
# Gate for path-exact colours via SPECIFIED_HIGHER_USAGE_OCCURRENCE (ADR-0048, Welle AA).
# Two OCCT oracles read the hand-written pattern (make_path_colour_fixtures.py) with SetSHUOMode(true):
#   (reader)   XCAFDoc_ColorTool::GetInstanceColor on every located leaf shape (spike_path_colour_read.cpp --inst)
#   (explorer) XCAFPrs_DocumentExplorer, OCCT's own presentation logic (--leaf)
# Gate rule (ADR-0048, Q1): it PASSES only when BOTH oracles give the hand-calculated colours; one oracle ignoring the
# pattern means an OCCT viewer shows something else, so the gate fails closed.
# Scene X: ASM > SUB x2 (u1 at x=0, u2 at x=100) > BOX x2 (u3 at x=0, u4 at x=20). Leaves by bounding-box minX: x0 (u1,u3),
# x20 (u1,u4), x100 (u2,u3), x120 (u2,u4); explorer paths 1/1, 1/2, 2/1, 2/2 in the same order.
# GetInstanceColor reports only SHUO colours (an outer component colour such as u2 in P4 is checked as --comp instead).
# Scenario S0 (informative) shows what OCCT's own writer produces. Writes only below build/interop/path-colour/.
# Prerequisites: OpenCascade (Homebrew), python3 and ./gradlew :kstep-cli:installDist.
#   verify-path-colour-gate.sh [--refresh]   --refresh rewrites the committed JVM-gate fixtures
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/interop/path-colour"; mkdir -p "$OUT"
FX="$ROOT/scripts/interop/fixtures/instance-colour"
KSTEP="$ROOT/kstep-cli/build/install/kstep-cli/bin/kstep-cli"
P="${OCCT_PREFIX:-$(brew --prefix opencascade)}"
LIBS=(-lTKDESTEP -lTKXCAF -lTKLCAF -lTKCAF -lTKCDF -lTKXSBase -lTKBRep -lTKTopAlgo -lTKPrim -lTKG3d -lTKGeomBase -lTKMath -lTKernel -lTKService -lTKGeomAlgo -lTKG2d -lTKBO -lTKShHealing -lTKMesh -lTKDE)
BEFORE="$(git -C "$ROOT" status --porcelain --ignored scripts)"
[ -x "$KSTEP" ] || { echo "kstep-cli is not installed (./gradlew :kstep-cli:installDist)"; exit 2; }
export PYTHONDONTWRITEBYTECODE=1
c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" "$ROOT/scripts/interop/spike_path_colour_read.cpp" -o "$OUT/spike" "${LIBS[@]}" 2>/dev/null
RD="$OUT/spike"
for b in nested-boxes nested-boxes-part-orange; do "$KSTEP" export "$FX/$b.kstep.kts" --out "$OUT/$b.step" >/dev/null 2>&1; done
USAGE() { python3 -I "$ROOT/scripts/interop/make_instance_colour_fixtures.py" "$@" --pds reuse --leaf-items all; }
PATHC() { python3 -I "$ROOT/scripts/interop/make_path_colour_fixtures.py" "$@"; }
G=0,1,0; Y=1,1,0; BLUE=0,0,1; RED=1,0,0; ORANGE=1,0.5,0; fail=0
# gate NAME "x0=none x20=none x100=0,1,0 x120=none" "1/1=none 1/2=none 2/1=0,1,0 2/2=none" [shuo count]
gate() {
  local n="$1" inst=() leaf=() e
  for e in $2; do case "$e" in comp:*) inst+=(--comp "${e#comp:}");; *) inst+=(--inst "$e");; esac; done
  for e in $3; do leaf+=(--leaf "$e"); done
  local r=ok x=ok
  "$RD" "$OUT/$n.step" --free 1 --components 6 --volume 24000 "${inst[@]}" >"$OUT/$n.reader.log" 2>&1 || r=FAILED
  "$RD" "$OUT/$n.step" --free 1 --components 6 --volume 24000 "${leaf[@]}" >"$OUT/$n.explorer.log" 2>&1 || x=FAILED
  echo "  $n: reader $r, explorer $x"
  [ "$r" = ok ] && [ "$x" = ok ] || fail=1
}
N="x0=none x20=none x120=none"; NL="1/1=none 1/2=none 2/2=none"
echo "== S0 (informative): OCCT's own writer, SetSHUO [u2,u3] + green on the SHUO label, assembly mode 2"
"$RD" --s0 "$OUT/S0.step" 2 >/dev/null 2>&1
grep -E "SPECIFIED_HIGHER|PRODUCT_DEFINITION_SHAPE\('SHUO'|^#[0-9]+ = STYLED_ITEM|^#[0-9]+ = SHAPE_DEFINITION_REPRESENTATION\(#[0-9]+,#4[0-9][0-9]\)" "$OUT/S0.step" | sed 's/^/     /' || true
"$RD" "$OUT/S0.step" --free 1 --components 6 --volume 24000 --inst x100=$G --inst x0=none --inst x20=none --inst x120=none 2>&1 | grep -q "RESULT ok" && echo "  S0: reader ok" || { echo "  S0: reader FAILED"; fail=1; }
echo "== GATE"
PATHC "$OUT/nested-boxes.step" "$OUT/P1.step" --path u2,u3:$G
gate P1 "$N x100=$G" "$NL 2/1=$G"
USAGE "$OUT/nested-boxes.step" "$OUT/P2base.step" --colour 3=$RED
PATHC "$OUT/P2base.step" "$OUT/P2.step" --path u2,u3:$G
gate P2 "x0=$RED x20=none x100=$G x120=none" "1/1=$RED 1/2=none 2/1=$G 2/2=none"
PATHC "$OUT/nested-boxes-part-orange.step" "$OUT/P3.step" --path u2,u3:$G
gate P3 "x0=$ORANGE x20=$ORANGE x100=$G x120=$ORANGE" "1/1=$ORANGE 1/2=$ORANGE 2/1=$G 2/2=$ORANGE"
USAGE "$OUT/nested-boxes.step" "$OUT/P4base.step" --colour 2=$BLUE
PATHC "$OUT/P4base.step" "$OUT/P4.step" --path u2,u3:$G
gate P4 "x0=none x20=none x100=$G comp:2=$BLUE" "1/1=none 1/2=none 2/1=$G 2/2=$BLUE"
USAGE "$OUT/nested-boxes-part-orange.step" "$OUT/P4bbase.step" --colour 2=$BLUE --colour 3=$RED
PATHC "$OUT/P4bbase.step" "$OUT/P4b.step" --path u2,u3:$G
gate P4b "x0=$RED x20=$ORANGE x100=$G x120=$ORANGE" "1/1=$RED 1/2=$ORANGE 2/1=$G 2/2=$ORANGE"
PATHC "$OUT/nested-boxes.step" "$OUT/P5.step" --path u2,u3:0,1,0,0.5
gate P5 "$N x100=0,1,0,0.5" "$NL 2/1=0,1,0,0.5"
PATHC "$OUT/nested-boxes.step" "$OUT/P5b.step" --path u2,u3:0,1,0,0.25
gate P5b "$N x100=0,1,0,0.25" "$NL 2/1=0,1,0,0.25"
PATHC "$OUT/nested-boxes.step" "$OUT/P7.step" --path u2,u3:$G --path u2,u4:$Y
gate P7 "x0=none x20=none x100=$G x120=$Y" "1/1=none 1/2=none 2/1=$G 2/2=$Y"
echo "== P6 (negative, informative): without the SHAPE_DEFINITION_REPRESENTATION"
PATHC "$OUT/nested-boxes.step" "$OUT/P6.step" --path u2,u3:$G --no-sdr
"$RD" "$OUT/P6.step" --free 1 --components 6 2>&1 | grep -E "^instance leaf" | sed 's/^/     /'
echo "== S0deep (informative, P8 at reader level): OCCT's writer, TOP > ASM x2 > SUB x2 > BOX, SetSHUO on [asm2, u2, u3]"
"$RD" --s0-deep "$OUT/S0deep.step" >/dev/null 2>&1
grep -E "SPECIFIED_HIGHER" "$OUT/S0deep.step" | sed 's/^/     /' || true
"$RD" "$OUT/S0deep.step" 2>&1 | grep -E "^instance leaf x1100" | sed 's/^/     /' || true
echo "     nested pattern: upper_usage = the SHUO of the prefix chain, next_usage = the last usage; generator writes length 2 only"
echo "== P9-P11 are not measured (the verdict does not depend on them)"
echo "== TAMPER: no crash, no hang (timeout 60 s), exit code and wall time per fixture"
T() { # name tamper-args...
  local n="$1"; shift
  PATHC "$OUT/nested-boxes.step" "$OUT/$n.step" --path u2,u3:$G "$@"
  local t0=$SECONDS rc=0
  timeout 60 "$RD" "$OUT/$n.step" >"$OUT/$n.log" 2>&1 || rc=$?
  echo "  $n: exit $rc, $((SECONDS - t0)) s"
  if [ "$rc" -ge 124 ]; then echo "     CRASH OR HANG (>=124)"; fail=1; fi
}
T tamper-cycle --tamper cycle
T tamper-nonchain --tamper nonchain
T tamper-selfref --tamper selfref
T tamper-deep200 --tamper deep:200
T tamper-count2000 --tamper count:2000
T tamper-alpha-negative --raw-t -0.5
T tamper-alpha-over --raw-t 1.5
T tamper-alpha-huge --raw-t 1.E400
for t in tamper-alpha-negative tamper-alpha-over tamper-alpha-huge; do
  grep -E "^instance leaf x100" "$OUT/$t.log" | sed "s/^/     $t: /"
done
if [ "$fail" = 0 ]; then echo "GATE PASSED"; else echo "GATE FAILED"; fi
RES="$ROOT/kstep-tests/src/test/resources/path-colour"
for g in P1 P2 P3 P4b P5 P6 P7 tamper-cycle tamper-nonchain tamper-selfref tamper-deep200 tamper-alpha-negative tamper-alpha-over tamper-alpha-huge; do
  f=gate-$g.step; case "$g" in tamper-*) f=$g.step;; esac
  if [ "${1:-}" = "--refresh" ]; then mkdir -p "$RES"; cp "$OUT/$g.step" "$RES/$f"
  elif [ -f "$RES/$f" ]; then cmp -s "$OUT/$g.step" "$RES/$f" || echo "NOTE: $RES/$f differs from the regenerated $g.step (rerun with --refresh)"; fi
done
echo "== FreeCAD reads P1 as geometry (volume 24000); colours are not readable headless"
FC="${FREECADCMD:-/Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd}"
if [ -x "$FC" ]; then
  PYTHONDONTWRITEBYTECODE=1 STEP_FILE="$OUT/P1.step" "$FC" "$ROOT/scripts/interop/freecad_read_styles.py" 2>&1 | grep -E "^(objects|  )" || true
else
  echo "FreeCAD not found, skipped"
fi
AFTER="$(git -C "$ROOT" status --porcelain --ignored scripts)"
if [ "$BEFORE" != "$AFTER" ]; then echo "ERROR: the run left files below scripts/:"; diff <(echo "$BEFORE") <(echo "$AFTER") || true; exit 1; fi
echo "OK: nothing written below scripts/"
exit 0
