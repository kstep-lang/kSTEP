#!/usr/bin/env bash
# Verifies the Welle-N exports end to end (ADR-0033): export the persistent-naming and undo/redo example scripts,
# read each file with OCCT's typed reader (read_step_structure) and, if available, FreeCAD, and compare volume,
# face and solid count with the hand-computed values. Writes only below build/interop/ (never next to the scripts).
# Prerequisites: installDist of kstep-cli, OpenCascade (Homebrew), FreeCAD.app (optional).
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/interop/persistent-naming"; mkdir -p "$OUT"
P="${OCCT_PREFIX:-$(brew --prefix opencascade)}"
c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" "$ROOT/scripts/interop/read_step_structure.cpp" \
  -o "$OUT/read_step_structure" -lTKDESTEP -lTKXSBase -lTKernel -lTKMath
KSTEP="$ROOT/kstep-cli/build/install/kstep-cli/bin/kstep-cli"
FC="${FREECADCMD:-/Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd}"
# name  expected volume / faces / solids
#   sketch plate, generated-edge fillet    8000  - 4*(1 - pi/4)*2^2*10 = 7965.664,  10 faces, 1 solid
#   the same after width 40 -> 60          12000 - 34.336              = 11965.664, 10 faces, 1 solid
#   feature history after undo/redo        7965.664 - pi*5^2*10        = 7180.266,  11 faces, 1 solid
for entry in "export-sketch-generated-fillet 7965.664 10 1" "export-sketch-generated-fillet-rebuild 11965.664 10 1" \
             "export-feature-history-undo 7180.266 11 1"; do
  set -- $entry
  "$KSTEP" export "$ROOT/kstep-tests/src/test/resources/$1.kstep.kts" --out "$OUT/$1.step" --validate
  echo "== $1 (expect volume $2, $3 faces, $4 solid(s))"
  "$OUT/read_step_structure" "$OUT/$1.step" | grep -E "status|undefined|model check"
  if [ -x "$FC" ]; then
    "$FC" -c "import Part; s=Part.read('$OUT/$1.step'); print('FreeCAD', s.ShapeType, s.isValid(), round(s.Volume,3), len(s.Faces), len(s.Solids))" 2>&1 | grep FreeCAD
  fi
done
