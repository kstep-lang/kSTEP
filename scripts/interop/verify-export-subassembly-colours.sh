#!/usr/bin/env bash
# Gate for the export of instance colours on occurrences of sub-assemblies (ADR-0046, Welle X).
# OCCT's own XCAF reader and OCCT's presentation logic (XCAFPrs_DocumentExplorer, spike_subassembly_colour_read.cpp) read the
# hand-written pattern (make_instance_colour_fixtures.py --leaf-items, scenarios H1-H8) and tell what each leaf looks like.
# Scenario H0 (informative) shows what STEPCAFControl_Writer itself writes for the same pattern.
# Writes only below build/interop/export-subassembly-colours/. Prerequisites: OpenCascade (Homebrew), python3, and
# ./gradlew :kstep-cli:installDist (the nested base fixtures are exported by kstep-cli).
#   verify-export-subassembly-colours.sh [--refresh]   --refresh rewrites the committed JVM-gate fixtures
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/interop/export-subassembly-colours"; mkdir -p "$OUT"
FX="$ROOT/scripts/interop/fixtures/instance-colour"
KSTEP="$ROOT/kstep-cli/build/install/kstep-cli/bin/kstep-cli"
P="${OCCT_PREFIX:-$(brew --prefix opencascade)}"
LIBS=(-lTKDESTEP -lTKXCAF -lTKLCAF -lTKCAF -lTKCDF -lTKXSBase -lTKBRep -lTKTopAlgo -lTKPrim -lTKG3d -lTKGeomBase -lTKMath -lTKernel -lTKService -lTKGeomAlgo -lTKG2d -lTKBO -lTKShHealing -lTKMesh -lTKDE)
BEFORE="$(git -C "$ROOT" status --porcelain --ignored scripts)"
[ -x "$KSTEP" ] || { echo "kstep-cli is not installed (./gradlew :kstep-cli:installDist)"; exit 2; }
export PYTHONDONTWRITEBYTECODE=1
GEN() { python3 -I "$ROOT/scripts/interop/make_instance_colour_fixtures.py" "$@" --pds reuse; }
CC() { c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" "$@" 2>/dev/null; }

CC "$ROOT/scripts/interop/spike_subassembly_colour_read.cpp" -o "$OUT/spike_subassembly_colour_read" "${LIBS[@]}"
CC "$ROOT/scripts/interop/spike_export_styles.cpp" -o "$OUT/spike_export_styles" "${LIBS[@]}"
RD="$OUT/spike_subassembly_colour_read"
for b in nested-boxes nested-boxes-part-orange nested-two-parts nested-three-levels nested-own-geometry; do
  "$KSTEP" export "$FX/$b.kstep.kts" --out "$OUT/$b.step" >/dev/null 2>&1
done

fail=0
gate() { # name, expectation arguments..., file is $OUT/<name>.step
  local n="$1"; shift
  if "$RD" "$OUT/$n.step" "$@" >"$OUT/$n.log" 2>&1; then echo "  $n: ok"; else echo "  $n: FAILED (see $OUT/$n.log)"; fail=1; fi
}
B=blue; BLUE=0,0,1; RED=1,0,0; ORANGE=1,0.5,0
echo "== H0 (informative): what OCCT's own writer produces for a colour on a sub-assembly component"
"$OUT/spike_export_styles" subassembly-instance 2 "$OUT/H0.step" >/dev/null
"$RD" "$OUT/H0.step" --free 1 --components 6 --volume 24000 --comp 2=$BLUE --comp 1=none --leaf 2/1=$BLUE --leaf 2/2=$BLUE --leaf 1/1=none --leaf 1/2=none >"$OUT/H0.log" 2>&1 && echo "  H0: ok" || { echo "  H0: FAILED"; fail=1; }
echo "     OCCT writes $(grep -c "^#[0-9]* = STYLED_ITEM" "$OUT/H0.step") STYLED_ITEMs on the leaf solids, one PRESENTATION_STYLE_BY_CONTEXT per leaf path"

echo "== GATE (OCCT 7.9.3 reads the hand-written sub-assembly pattern as an instance colour of the component)"
GEN "$OUT/nested-boxes.step" "$OUT/H1.step" --colour 2=$BLUE --leaf-items all
gate H1 --free 1 --components 6 --volume 24000 --comp 2=$BLUE --comp 1=none --comp 1/1=none --comp 1/2=none --comp 2/1=none --comp 2/2=none \
  --part Box=none --part Unterbaugruppe=none --leaf 2/1=$BLUE --leaf 2/2=$BLUE --leaf 1/1=none --leaf 1/2=none
GEN "$OUT/nested-two-parts.step" "$OUT/H2.step" --colour 2=$BLUE --leaf-items first
gate H2 --free 1 --components 6 --volume 18400 --comp 2=$BLUE --comp 1=none --part Box=none --part Platte=none \
  --leaf 2/1=$BLUE --leaf 2/2=$BLUE --leaf 1/1=none --leaf 1/2=none
GEN "$OUT/nested-boxes-part-orange.step" "$OUT/H3.step" --colour 2=$BLUE --leaf-items all
# precedence: the colour of the part beats the colour of an OUTER sub-assembly occurrence
gate H3 --free 1 --components 6 --volume 24000 --comp 2=$BLUE --part Box=$ORANGE \
  --leaf 1/1=$ORANGE --leaf 1/2=$ORANGE --leaf 2/1=$ORANGE --leaf 2/2=$ORANGE
GEN "$OUT/nested-boxes.step" "$OUT/H4.step" --colour 2=$BLUE --colour 3=$RED --leaf-items all
# precedence: the inner occurrence colour beats the outer one (usage 3 is the component 1 of SUB for both SUB occurrences)
gate H4 --free 1 --components 6 --volume 24000 --comp 2=$BLUE --comp 1/1=$RED --comp 2/1=$RED --comp 1/2=none --comp 2/2=none \
  --leaf 1/1=$RED --leaf 1/2=none --leaf 2/1=$RED --leaf 2/2=$BLUE
GEN "$OUT/nested-boxes-part-orange.step" "$OUT/H4b.step" --colour 2=$BLUE --colour 3=$RED --leaf-items all
# precedence in full: own occurrence colour > part colour > outer occurrence colours
gate H4b --free 1 --components 6 --volume 24000 --leaf 1/1=$RED --leaf 1/2=$ORANGE --leaf 2/1=$RED --leaf 2/2=$ORANGE
GEN "$OUT/nested-three-levels.step" "$OUT/H5.step" --colour 2=$BLUE --leaf-items all
gate H5 --free 1 --components 5 --volume 12000 --comp 1/1=$BLUE --comp 1/2=none --comp 1/1/1=none --leaf 1/1/1=$BLUE --leaf 1/2/1=none
python3 -I "$ROOT/scripts/interop/make_instance_colour_fixtures.py" "$OUT/nested-boxes.step" "$OUT/H6.step" --colour 2=$BLUE --pds reuse --leaf-items all --no-sdr
# negative: without the SHAPE_DEFINITION_REPRESENTATION OCCT paints the shared BOX product, i.e. all four occurrences
gate H6 --free 1 --components 6 --comp 2=none --part Box=$BLUE --leaf 1/1=$BLUE --leaf 1/2=$BLUE --leaf 2/1=$BLUE --leaf 2/2=$BLUE
GEN "$OUT/nested-boxes.step" "$OUT/H7.step" --colour 2=$BLUE --leaf-items axis
gate H7 --free 1 --components 6 --volume 24000 --comp 2=$BLUE --part Box=none --leaf 2/1=$BLUE --leaf 2/2=$BLUE --leaf 1/1=none --leaf 1/2=none
GEN "$OUT/nested-own-geometry.step" "$OUT/H8.step" --colour 1=$BLUE
# a sub-assembly with a shape of its own (Welle V pattern): the colour is inherited by the children as well
gate H8 --free 1 --components 6 --volume 18400 --comp 1=$BLUE --comp 2=none --leaf 1/1=$BLUE --leaf 1/2=$BLUE --leaf 2/1=none --leaf 2/2=none
if [ "$fail" = 0 ]; then echo "GATE PASSED"; else echo "GATE FAILED"; exit 1; fi

# The committed fixtures of the JVM gate test are H1, H3 and H4.
RES="$ROOT/kstep-tests/src/test/resources/instance-colour"
for g in H1 H3 H4; do
  if [ "${1:-}" = "--refresh" ]; then mkdir -p "$RES"; cp "$OUT/$g.step" "$RES/gate-$g.step"
  elif [ -f "$RES/gate-$g.step" ]; then cmp -s "$OUT/$g.step" "$RES/gate-$g.step" || echo "NOTE: $RES/gate-$g.step differs from the regenerated $g.step (rerun with --refresh)"; fi
done

echo "== FreeCAD reads H1 as geometry (volume 24000); colours are not readable headless"
FC="${FREECADCMD:-/Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd}"
if [ -x "$FC" ]; then
  PYTHONDONTWRITEBYTECODE=1 STEP_FILE="$OUT/H1.step" "$FC" "$ROOT/scripts/interop/freecad_read_styles.py" 2>&1 | grep -E "^(objects|  )" || true
else
  echo "FreeCAD not found, skipped"
fi

AFTER="$(git -C "$ROOT" status --porcelain --ignored scripts)"
if [ "$BEFORE" != "$AFTER" ]; then echo "ERROR: the run left files below scripts/:"; diff <(echo "$BEFORE") <(echo "$AFTER") || true; exit 1; fi
echo "OK: nothing written below scripts/"
