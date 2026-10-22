#!/usr/bin/env bash
# Verifies `kstep import` end to end (ADR-0030): export the Welle I/J scripts, import them again, and
# compare with OCCT's own XCAF tree and (optionally) FreeCAD. Writes only below build/interop/import/.
# Prerequisites: installDist of kstep-cli, OpenCascade (Homebrew), jq (optional), FreeCAD.app (optional).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/interop/import"; mkdir -p "$OUT"
P="${OCCT_PREFIX:-$(brew --prefix opencascade)}"
c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" "$ROOT/scripts/interop/dump_xcaf_tree.cpp" \
  -o "$OUT/dump_xcaf_tree" -lTKDESTEP -lTKXCAF -lTKLCAF -lTKCAF -lTKCDF -lTKXSBase -lTKBRep -lTKTopAlgo -lTKPrim \
  -lTKG3d -lTKGeomBase -lTKMath -lTKernel -lTKService 2>/dev/null
KSTEP="$ROOT/kstep-cli/build/install/kstep-cli/bin/kstep-cli"
RES="$ROOT/kstep-tests/src/test/resources"
for name in export-box-20x30x40 export-plate-with-hole export-revolved-sleeve export-boolean-union \
            export-chamfered-prism export-placed-assembly export-placed-assembly-own-geometry; do
  echo "== $name"
  "$KSTEP" export "$RES/$name.kstep.kts" --out "$OUT/$name.step" >/dev/null 2>&1
  if command -v jq >/dev/null; then
    "$KSTEP" import "$OUT/$name.step" --output json 2>/dev/null \
      | jq -c '{parts: [.parts[] | {name, volume, faces: .topology.faces}], totals: {occurrences: .totals.occurrenceCount, volume: .totals.volume, bbox: .totals.boundingBox}}'
  else
    "$KSTEP" import "$OUT/$name.step" 2>/dev/null | grep -E "^(Parts|  [A-Za-z]|Total)"
  fi
  echo "-- XCAF dump (OCCT)"; "$OUT/dump_xcaf_tree" dump "$OUT/$name.step" 2>/dev/null | grep -E "volume=|SUMMARY" | sed 's/^ *//'
done
echo "== OCCT-authored nested reference"
"$OUT/dump_xcaf_tree" nested "$OUT/nested.step" >/dev/null 2>&1
"$KSTEP" import "$OUT/nested.step" 2>/dev/null | grep -E "Occurrences|NAUO|Total"
"$OUT/dump_xcaf_tree" dump "$OUT/nested.step" 2>/dev/null | grep -E "global bbox"
FC="${FREECADCMD:-/Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd}"
if [ -x "$FC" ]; then
  echo "== FreeCAD writes a box, kSTEP imports it"
  STEP_FILE="$OUT/freecad-box.step" "$FC" "$ROOT/scripts/interop/freecad_make_box.py" >/dev/null 2>&1
  "$KSTEP" import "$OUT/freecad-box.step" 2>/dev/null | grep -E "Parts|FreeCADBox|Total"
  echo "== FreeCAD reads the placed assembly (part mode), for comparison"
  STEP_FILE="$OUT/export-placed-assembly.step" STEP_MODE=part "$FC" "$ROOT/scripts/interop/freecad_assembly_check.py" 2>&1 \
    | grep -E "Part.read|solid volume"
else
  echo "FreeCAD not found, skipped"
fi
