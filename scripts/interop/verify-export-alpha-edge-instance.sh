#!/usr/bin/env bash
# Verifies the Welle U export of transparency and edge colours end to end (ADR-0042): exports the example scripts, reads
# the files back with kSTEP (kstep import) AND with OCCT's own XCAF reader (dump_xcaf_tree styles, now with alpha and
# edges), checks the unstyled and single-colour goldens byte for byte, checks the error codes, and lets FreeCAD read the
# files as geometry. Writes only below build/interop/export-alpha-edge-instance/.
#   verify-export-alpha-edge-instance.sh --spike   additionally builds and runs the ADR-0042 scenarios of
#                                                  scripts/interop/spike_export_styles.cpp (alpha, edge, instance)
# Prerequisites: installDist of kstep-cli, OpenCascade (Homebrew), jq (optional), FreeCAD.app (optional).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/interop/export-alpha-edge-instance"; mkdir -p "$OUT"
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
  for sc in alpha alpha-face part-alpha-face-alpha edge part-edge face-edge edge-alpha instance instance-vs-product; do
    for a in 0 2; do
      "$OUT/spike_export_styles" "$sc" "$a" "$OUT/spike-$sc-$a.step" >/dev/null 2>&1 && echo "spike $sc assembly=$a ok"
      echo "-- $sc assembly=$a (OCCT XCAF read-back)"; "$OUT/dump_xcaf_tree" styles "$OUT/spike-$sc-$a.step" || true
    done
  done
fi

echo "== a) translucent part and top face (alpha 0.5 and 0.25)"
"$KSTEP" export "$RES/export-alpha-box.kstep.kts" --out "$OUT/alpha-box.step" 2>/dev/null
grep -E "SURFACE_STYLE_TRANSPARENT" "$OUT/alpha-box.step"
"$OUT/dump_xcaf_tree" styles "$OUT/alpha-box.step"
has_jq && "$KSTEP" import "$OUT/alpha-box.step" --output json 2>/dev/null | jq -c '.parts[0] | {volume, colour, faceStyles, edgeStyles}'
"$KSTEP" import "$OUT/alpha-box.step" 2>/dev/null | grep -E "alpha"

echo "== b) the four top edges red"
"$KSTEP" export "$RES/export-edge-colour-top.kstep.kts" --out "$OUT/edge-colour-top.step" 2>/dev/null
grep -E "CURVE_STYLE|DRAUGHTING_PRE_DEFINED_(CURVE_FONT|COLOUR)" "$OUT/edge-colour-top.step" | head -3
"$OUT/dump_xcaf_tree" styles "$OUT/edge-colour-top.step"
has_jq && "$KSTEP" import "$OUT/edge-colour-top.step" --output json 2>/dev/null \
  | jq -c '{edgeStyles: .parts[0].edgeStyles, presentation}'

echo "== b2) the docs example (translucent part, translucent face, four red edges)"
"$KSTEP" export "$RES/export-docs-alpha-edges.kstep.kts" --out "$OUT/docs-alpha-edges.step" 2>/dev/null
"$OUT/dump_xcaf_tree" styles "$OUT/docs-alpha-edges.step"
has_jq && "$KSTEP" import "$OUT/docs-alpha-edges.step" --output json 2>/dev/null | jq -c '.parts[0] | {volume, colour, faceStyles, edgeStyles}'

echo "== c) instance colours: not part of Welle U (ADR-0042, follow-up). Run with --spike for the measured OCCT pattern."

echo "== d) alpha 1.0 and the unstyled goldens are byte-identical to the files generated before ADR-0042"
for n in box multi-solid placed-assembly part-colour face-colour; do
  "$KSTEP" export "$RES/export-golden-$n.kstep.kts" --out "$OUT/golden-$n.step" >/dev/null 2>&1
  cmp "$OUT/golden-$n.step" "$RES/export-golden-$n.step" && echo "  $n identical"
done
"$KSTEP" export "$RES/export-golden-part-colour-rgba1.kstep.kts" --out "$OUT/golden-part-colour-rgba1.step" >/dev/null 2>&1
cmp "$OUT/golden-part-colour-rgba1.step" "$RES/export-golden-part-colour.step" && echo "  part-colour via rgba(.., 1.0) identical"

echo "== e) error cases (code in text and JSON)"
sed 's/StepColour.rgba(1.0, 0.5, 0.0, 0.5)/StepColour.rgba(1.0, 0.5, 0.0, 1.5)/' "$RES/export-alpha-box.kstep.kts" > "$OUT/bad-alpha.kstep.kts"
sed 's/StepColours.RED, expected = 4/StepColour.rgba(1.0, 0.0, 0.0, 0.5), expected = 4/' "$RES/export-edge-colour-top.kstep.kts" > "$OUT/bad-edge-alpha.kstep.kts"
sed 's/Edges.atMax(Direction3.Z), StepColours.RED, expected = 4/Edges.seams(), StepColours.RED/' "$RES/export-edge-colour-top.kstep.kts" > "$OUT/no-edge.kstep.kts"
sed 's/^    styleEdges(\(.*\))$/    styleEdges(\1)\n    styleEdges(\1)/' "$RES/export-edge-colour-top.kstep.kts" > "$OUT/twice-edge.kstep.kts"
for f in bad-alpha bad-edge-alpha no-edge twice-edge; do
  set +e; "$KSTEP" export "$OUT/$f.kstep.kts" --out "$OUT/$f.step" --output json 2>/dev/null | { has_jq && jq -r '"  '"$f"': \(.code)"' || cat; }; set -e
  test ! -e "$OUT/$f.step" || { echo "ERROR: $f wrote a file"; exit 1; }
done

echo "== FreeCAD reads the files as geometry (volume 6000, 6 faces; colours are not readable headless)"
if [ -x "$FC" ]; then
  for f in alpha-box edge-colour-top docs-alpha-edges; do
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
