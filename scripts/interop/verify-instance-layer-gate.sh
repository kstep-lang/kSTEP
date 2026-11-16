#!/usr/bin/env bash
# Gate for presentation layers on assembly usages (Welle Z, ADR-0047).
# OCCT's own XCAF reader (spike_instance_layer_read.cpp) reads hand-written PRESENTATION_LAYER_ASSIGNMENT patterns
# (make_instance_layer_fixtures.py, patterns p1..p5, scenarios L0-L9) and tells where each layer lands: on the component
# (wanted), on the shared part or its sub-shapes (silent wrong result), or nowhere (lost).
# Writes only below build/interop/instance-layer-gate/. Prerequisites: OpenCascade (Homebrew), python3,
# ./gradlew :kstep-cli:installDist.   verify-instance-layer-gate.sh [--refresh]   --refresh rewrites the committed JVM-gate fixtures
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/interop/instance-layer-gate"; mkdir -p "$OUT"
FX="$ROOT/scripts/interop/fixtures/instance-colour"
KSTEP="$ROOT/kstep-cli/build/install/kstep-cli/bin/kstep-cli"
P="${OCCT_PREFIX:-$(brew --prefix opencascade)}"
LIBS=(-lTKDESTEP -lTKXCAF -lTKLCAF -lTKCAF -lTKCDF -lTKXSBase -lTKBRep -lTKTopAlgo -lTKPrim -lTKG3d -lTKGeomBase -lTKMath -lTKernel -lTKService -lTKGeomAlgo -lTKG2d -lTKBO -lTKShHealing -lTKMesh -lTKDE)
[ -x "$KSTEP" ] || { echo "kstep-cli is not installed (./gradlew :kstep-cli:installDist)"; exit 2; }
export PYTHONDONTWRITEBYTECODE=1
COL() { python3 -I "$ROOT/scripts/interop/make_instance_colour_fixtures.py" "$@" --pds reuse; }
LAY() { python3 -I "$ROOT/scripts/interop/make_instance_layer_fixtures.py" "$@"; }
CC() { c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" "$@" 2>/dev/null; }
CC "$ROOT/scripts/interop/spike_instance_layer_read.cpp" -o "$OUT/spike_instance_layer_read" "${LIBS[@]}" || { echo "spike build failed"; exit 2; }
CC "$ROOT/scripts/interop/spike_export_styles.cpp" -o "$OUT/spike_export_styles" "${LIBS[@]}" || true
RD="$OUT/spike_instance_layer_read"
for b in two-boxes nested-boxes; do "$KSTEP" export "$FX/$b.kstep.kts" --out "$OUT/$b.step" >/dev/null 2>&1; done
# the coloured bases for p1/p4/L6
COL "$OUT/two-boxes.step" "$OUT/two-boxes-blue.step" --colour 2=0,0,1
COL "$OUT/nested-boxes.step" "$OUT/nested-boxes-blue.step" --colour 2=0,0,1 --leaf-items all

pass=0; failn=0
gate() { # name, expectations; informative: prints ok/MISMATCH, never aborts
  local n="$1"; shift
  if "$RD" "$OUT/$n.step" "$@" >"$OUT/$n.log" 2>&1; then echo "  $n: PASS"; else echo "  $n: FAIL (see $OUT/$n.log)"; grep -E "MISMATCH|^component|^part|assembly layers" "$OUT/$n.log" | sed 's/^/       /'; fi
}
V="--free 1 --components 2 --volume 12000"
echo "== L0 (informative): OCCT's own writer, SetLayer on component 2"
"$OUT/spike_export_styles" instance-layer 2 "$OUT/L0.step" >/dev/null 2>&1; grep -c PRESENTATION_LAYER_ASSIGNMENT "$OUT/L0.step" | sed 's/^/     PLA entities written: /'
"$RD" "$OUT/L0.step" --comp 2=none >"$OUT/L0.log" 2>&1; sed 's/^/     /' <"$OUT/L0.log" | grep -E "^     (component|part)"

echo "== L1 (u2 on Layer-I), patterns p1-p5; PASS = comp 2 carries it, comp 1 / part / sub-shapes / assembly do not"
for p in p1 p4; do LAY "$OUT/two-boxes-blue.step" "$OUT/L1-$p.step" --layer 2=Layer-I --pattern $p; done
for p in p2 p2b p3 p5; do LAY "$OUT/two-boxes.step" "$OUT/L1-$p.step" --layer 2=Layer-I --pattern $p; done
for p in p1 p2 p2b p4 p5; do echo " pattern $p"; gate "L1-$p" $V --comp 2=Layer-I --comp 1=none --part Box=none --partsub Box=none --assembly none; done
echo " pattern p3 (control, expected: lands on the shared part)"; gate L1-p3 $V --comp 2=Layer-I --comp 1=none --part Box=none --partsub Box=none --assembly none
echo "== L2 (u1 Layer-A, u2 Layer-B), p2"
LAY "$OUT/two-boxes.step" "$OUT/L2.step" --layer 1=Layer-A --layer 2=Layer-B --pattern p2
gate L2 $V --comp 1=Layer-A --comp 2=Layer-B --part Box=none --partsub Box=none
echo "== L3 (part on Layer-P via p3 on u1 plus u2 on Layer-I via p2): the shared part keeps Layer-P, comp 2 gains Layer-I"
LAY "$OUT/two-boxes.step" "$OUT/L3a.step" --layer 1=Layer-P --pattern p3; LAY "$OUT/L3a.step" "$OUT/L3.step" --layer 2=Layer-I --pattern p2
gate L3 $V --comp 2=Layer-I --comp 1=none
echo "== L4 (negative): p2 without the SHAPE_DEFINITION_REPRESENTATION"
LAY "$OUT/two-boxes.step" "$OUT/L4.step" --layer 2=Layer-I --pattern p2 --no-sdr
gate L4 $V --comp 2=Layer-I --comp 1=none --part Box=none
echo "== L5 (sub-assembly: SUB occurrence 2 on Layer-S), p2 and p3"
LAY "$OUT/nested-boxes.step" "$OUT/L5.step" --layer 2=Layer-S --pattern p2
gate L5 --free 1 --components 6 --volume 24000 --comp 2=Layer-S --comp 1=none --comp 2/1=none --comp 2/2=none --comp 1/1=none --part Box=none --partsub Box=none
echo "== L6 (layer plus colour on the same usage; colour must stay blue)"
LAY "$OUT/two-boxes-blue.step" "$OUT/L6.step" --layer 2=Layer-I --pattern p2
gate L6 $V --comp 2=Layer-I --comp 1=none --part Box=none --compcol 2=0,0,1 --compcol 1=none
echo "== L7 (one layer, two usages)"
LAY "$OUT/two-boxes.step" "$OUT/L7.step" --layer 1,2=Layer-X --pattern p2
gate L7 $V --comp 1=Layer-X --comp 2=Layer-X --part Box=none
echo "== L8 (apostrophe name, 128 characters)"
LAY "$OUT/two-boxes.step" "$OUT/L8a.step" --layer 2="O'Brien" --pattern p2; gate L8a $V --comp 2="O'Brien" --comp 1=none
N128="$(python3 -c "print('L'*128)")"
LAY "$OUT/two-boxes.step" "$OUT/L8b.step" --layer 2="$N128" --pattern p2; gate L8b $V --comp 2="$N128" --comp 1=none
echo "== L9 (informative): FreeCAD loads L1-p2 and L5 as geometry"
FC="${FREECADCMD:-/Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd}"
if [ -x "$FC" ]; then
  for y in L1-p2 L5; do STEP_FILE="$OUT/$y.step" STEP_MODE=open "$FC" "$ROOT/scripts/interop/freecad_assembly_check.py" 2>&1 | tail -3 | sed 's/^/     /'; done
else echo "  FreeCAD not installed, skipped"; fi

RES="$ROOT/kstep-tests/src/test/resources/instance-layer"
if [ "${1:-}" = "--refresh" ]; then mkdir -p "$RES"; for g in L1-p1 L1-p2 L1-p3 L1-p4 L3 L5 L6; do cp "$OUT/$g.step" "$RES/gate-$g.step"; done; fi
echo done
