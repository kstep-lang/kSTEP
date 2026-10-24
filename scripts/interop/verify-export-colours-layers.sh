#!/usr/bin/env bash
# Verifies the Welle S export of colours and layers end to end (ADR-0039): exports the example scripts, reads the files
# back with kSTEP (kstep import) AND with OCCT's own XCAF reader (dump_xcaf_tree styles), checks the unstyled goldens
# byte for byte, and lets FreeCAD read the files. Writes only below build/interop/export-colours-layers/.
#   verify-export-colours-layers.sh --spike   additionally builds and runs scripts/interop/spike_export_styles.cpp
# Prerequisites: installDist of kstep-cli, OpenCascade (Homebrew), python3, jq (optional), FreeCAD.app (optional).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/interop/export-colours-layers"; mkdir -p "$OUT"
RES="$ROOT/kstep-tests/src/test/resources"
KSTEP="$ROOT/kstep-cli/build/install/kstep-cli/bin/kstep-cli"
P="${OCCT_PREFIX:-$(brew --prefix opencascade)}"
FC="${FREECADCMD:-/Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd}"
BEFORE="$(git -C "$ROOT" status --porcelain --ignored scripts)"
has_jq() { command -v jq >/dev/null; }
LIBS="-lTKDESTEP -lTKXCAF -lTKLCAF -lTKCAF -lTKCDF -lTKXSBase -lTKBRep -lTKTopAlgo -lTKPrim -lTKG3d -lTKGeomBase -lTKMath -lTKernel -lTKService"

c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" "$ROOT/scripts/interop/dump_xcaf_tree.cpp" \
  -o "$OUT/dump_xcaf_tree" $LIBS 2>/dev/null

if [ "${1:-}" = "--spike" ]; then
  c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" "$ROOT/scripts/interop/spike_export_styles.cpp" \
    -o "$OUT/spike_export_styles" -lTKGeomAlgo -lTKTopAlgo -lTKG2d $LIBS 2>/dev/null
  for sc in plain xcaf-plain part face layer alpha multi unicode; do
    for a in 0 2; do "$OUT/spike_export_styles" "$sc" "$a" "$OUT/spike-$sc-$a.step" >/dev/null 2>&1 && echo "spike $sc assembly=$a ok"; done
  done
fi

echo "== a) part colour and layer"
"$KSTEP" export "$RES/export-colour-layer-box.kstep.kts" --out "$OUT/colour-layer-box.step" 2>/dev/null
"$OUT/dump_xcaf_tree" styles "$OUT/colour-layer-box.step"
has_jq && "$KSTEP" import "$OUT/colour-layer-box.step" --output json 2>/dev/null | jq -c '.parts[0] | {volume, colour, layers}'

echo "== b) face colour and layer on Faces.atMax(Z), height 30 and 40"
for h in 30.0 40.0; do
  sed "s/val height = 30.0/val height = $h/" "$RES/export-colour-face-top.kstep.kts" > "$OUT/face-top-$h.kstep.kts"
  "$KSTEP" export "$OUT/face-top-$h.kstep.kts" --out "$OUT/face-top-$h.step" 2>/dev/null
  echo "-- height $h"; "$OUT/dump_xcaf_tree" styles "$OUT/face-top-$h.step"
  has_jq && "$KSTEP" import "$OUT/face-top-$h.step" --output json 2>/dev/null | jq -c '.parts[0] | {volume, faceStyles}'
done

echo "== c) placed assembly with two coloured parts"
"$KSTEP" export "$RES/export-colour-assembly-two-parts.kstep.kts" --out "$OUT/assembly.step" 2>/dev/null
has_jq && "$KSTEP" import "$OUT/assembly.step" --output json 2>/dev/null \
  | jq -c '{parts: [.parts[] | {productId, colour, layers}], totals, placements: [.assemblies[0].components[] | {name, t: .placement.translation}]}'

echo "== d) unstyled exports are byte-identical to the goldens generated before ADR-0039"
for n in box multi-solid placed-assembly; do
  "$KSTEP" export "$RES/export-golden-$n.kstep.kts" --out "$OUT/golden-$n.step" >/dev/null 2>&1
  cmp "$OUT/golden-$n.step" "$RES/export-golden-$n.step" && echo "  $n identical"
done

echo "== FreeCAD reads the styled files (geometry only; colours are not readable headless)"
if [ -x "$FC" ]; then
  for f in colour-layer-box assembly; do
    echo "-- $f"
    PYTHONDONTWRITEBYTECODE=1 STEP_FILE="$OUT/$f.step" "$FC" "$ROOT/scripts/interop/freecad_read_styles.py" 2>&1 \
      | grep -E "^(objects|  )" || true
  done
else
  echo "FreeCAD not found, skipped"
fi

AFTER="$(git -C "$ROOT" status --porcelain --ignored scripts)"
if [ "$BEFORE" != "$AFTER" ]; then
  echo "ERROR: the run left files below scripts/:"; diff <(echo "$BEFORE") <(echo "$AFTER") || true; exit 1
fi
echo "OK: nothing written below scripts/"
