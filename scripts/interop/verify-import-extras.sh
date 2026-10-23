#!/usr/bin/env bash
# Verifies the Welle R import extras end to end (ADR-0038): colours, layers, length units, freeform surfaces.
# Regenerates the fixtures, compares them byte for byte with the committed copies, and cross-checks `kstep import`
# against OCCT's own XCAF reader (dump_xcaf_tree modes `styles` and `units`). Writes only below build/interop/import-extras/.
# Prerequisites: installDist of kstep-cli, OpenCascade (Homebrew), python3, jq (optional), FreeCAD.app (optional).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/interop/import-extras"; mkdir -p "$OUT"
RES="$ROOT/kstep-tests/src/test/resources"
KSTEP="$ROOT/kstep-cli/build/install/kstep-cli/bin/kstep-cli"
P="${OCCT_PREFIX:-$(brew --prefix opencascade)}"
BEFORE="$(git -C "$ROOT" status --porcelain --ignored scripts)"

c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" "$ROOT/scripts/interop/dump_xcaf_tree.cpp" \
  -o "$OUT/dump_xcaf_tree" -lTKDESTEP -lTKXCAF -lTKLCAF -lTKCAF -lTKCDF -lTKXSBase -lTKBRep -lTKTopAlgo -lTKPrim \
  -lTKG3d -lTKGeomBase -lTKMath -lTKernel -lTKService 2>/dev/null

echo "== fixtures are reproducible"
"$KSTEP" export "$RES/export-two-bodies-different.kstep.kts" --out "$OUT/two-body-base.step" >/dev/null 2>&1
python3 -B "$ROOT/scripts/interop/make_import_extras_fixtures.py" "$OUT" >/dev/null
for f in styled-box two-body-styled inch-box cm-box m-box no-unit-box unresolved-unit-box structure-only; do
  cmp "$OUT/$f.step" "$RES/import/$f.step" && echo "  $f.step identical"
done

has_jq() { command -v jq >/dev/null; }

echo "== colours and layers: kstep import vs OCCT"
"$OUT/dump_xcaf_tree" styles "$RES/import/styled-box.step"
if has_jq; then
  "$KSTEP" import "$RES/import/styled-box.step" --output json 2>/dev/null \
    | jq -c '.parts[0] | {colour, layers, faceStyles}'
  # the file value is sRGB (1, 0.5, 0): the linear value OCCT stores internally would be 0.214 for the green channel
  "$KSTEP" import "$RES/import/styled-box.step" --output json 2>/dev/null \
    | jq -e '.parts[0].colour.g > 0.4999 and .parts[0].colour.g < 0.5001' >/dev/null && echo "  green channel is the file value 0.5"
fi

echo "== units: kstep import vs OCCT"
for f in inch-box cm-box m-box no-unit-box; do
  echo "-- $f"
  "$OUT/dump_xcaf_tree" units "$RES/import/$f.step" | grep -E "file length unit|bbox"
  if has_jq; then
    "$KSTEP" import "$RES/import/$f.step" --output json 2>/dev/null \
      | jq -c '{sourceLengthUnit, bbox: .parts[0].boundingBox.max}'
  fi
done
echo "-- unresolved-unit-box (expected: KSTEP-I-013)"
"$KSTEP" import "$RES/import/unresolved-unit-box.step" --output json 2>/dev/null || true
echo "-- structure-only (expected: KSTEP-I-011)"
"$KSTEP" import "$RES/import/structure-only.step" --output json 2>/dev/null || true

echo "== freeform: FreeCAD writes a B-spline loft, kstep imports it"
FC="${FREECADCMD:-/Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd}"
if [ -x "$FC" ]; then
  PYTHONDONTWRITEBYTECODE=1 STEP_FILE="$OUT/freecad-loft.step" "$FC" "$ROOT/scripts/interop/freecad_make_loft.py" 2>&1 | grep -E "^FreeCAD"
  "$KSTEP" import "$OUT/freecad-loft.step" --output json 2>/dev/null | jq -c '.parts[0] | {volume, surfaceTypes}' 2>/dev/null \
    || "$KSTEP" import "$OUT/freecad-loft.step" 2>/dev/null | grep -E "volume"
else
  echo "FreeCAD not found, skipped"
fi

AFTER="$(git -C "$ROOT" status --porcelain --ignored scripts)"
if [ "$BEFORE" != "$AFTER" ]; then
  echo "ERROR: the run left files below scripts/:"; diff <(echo "$BEFORE") <(echo "$AFTER") || true; exit 1
fi
echo "OK: nothing written below scripts/"
