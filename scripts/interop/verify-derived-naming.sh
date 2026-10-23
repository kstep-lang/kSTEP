#!/usr/bin/env bash
# Verifies derived-edge naming (ADR-0034, Welle N2). Writes only below build/interop/derived-naming/.
#   --spike   build and run the standalone OCCT history spike (scripts/interop/spike_derived_history.cpp)
#   (default) export the three positive example scripts, read each file with OCCT's typed reader and FreeCAD
#             and compare volume / faces with the hand-computed values.
# Prerequisites: installDist of kstep-cli, OpenCascade (Homebrew), FreeCAD.app (optional).
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/interop/derived-naming"; mkdir -p "$OUT"
P="${OCCT_PREFIX:-$(brew --prefix opencascade)}"
if [ "${1:-}" = "--spike" ]; then
  c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" "$ROOT/scripts/interop/spike_derived_history.cpp" \
    -o "$OUT/spike_derived_history" -lTKernel -lTKMath -lTKBRep -lTKTopAlgo -lTKPrim -lTKBO -lTKFillet -lTKGeomBase -lTKG3d -lTKGeomAlgo -lTKTopAlgo
  "$OUT/spike_derived_history" | tee "$OUT/spike.txt"
  exit 0
fi
c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" "$ROOT/scripts/interop/read_step_structure.cpp" \
  -o "$OUT/read_step_structure" -lTKDESTEP -lTKXSBase -lTKernel -lTKMath
KSTEP="$ROOT/kstep-cli/build/install/kstep-cli/bin/kstep-cli"
FC="${FREECADCMD:-/Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd}"
# name  expected volume / faces / solids
#   hole rim fillet r=1 (plate 40x40x10, hole R=4)     15491.650, 8 faces, 1 solid
#   the same after the plate becomes 60x40x10          23491.650, 8 faces, 1 solid
#   union of two offset boxes, section edges filleted  7017.168, 12 faces, 1 solid
for entry in "export-hole-rim-fillet 15491.650 8 1" "export-hole-rim-fillet-rebuild 23491.650 8 1" \
             "export-union-section-fillet 7017.168 12 1"; do
  set -- $entry
  "$KSTEP" export "$ROOT/kstep-tests/src/test/resources/$1.kstep.kts" --out "$OUT/$1.step" --validate
  echo "== $1 (expect volume $2, $3 faces, $4 solid(s))"
  "$OUT/read_step_structure" "$OUT/$1.step" | grep -E "status|undefined|model check"
  if [ -x "$FC" ]; then
    "$FC" -c "import Part; s=Part.read('$OUT/$1.step'); print('FreeCAD', s.ShapeType, s.isValid(), round(s.Volume,3), len(s.Faces), len(s.Solids))" 2>&1 | grep FreeCAD
  fi
done
