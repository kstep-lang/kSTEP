#!/usr/bin/env bash
# Verifies the Welle-M exports end to end (ADR-0032): export the sketch-extension example scripts, read each file
# with OCCT's typed reader (read_step_structure) and, if available, FreeCAD, and compare volume, face and solid
# count with the hand-computed values. Writes only below build/interop/ (never next to the scripts).
# Prerequisites: installDist of kstep-cli, OpenCascade (Homebrew), FreeCAD.app (optional).
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/interop/sketch-extensions"; mkdir -p "$OUT"
P="${OCCT_PREFIX:-$(brew --prefix opencascade)}"
c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" "$ROOT/scripts/interop/read_step_structure.cpp" \
  -o "$OUT/read_step_structure" -lTKDESTEP -lTKXSBase -lTKernel -lTKMath
KSTEP="$ROOT/kstep-cli/build/install/kstep-cli/bin/kstep-cli"
FC="${FREECADCMD:-/Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd}"
# name  expected volume / faces / solids
#   equal radius holes   8000 - 2*90*pi    = 7434.513, 8 faces, 1 solid
#   radius unknown       same plate        = 7434.513, 8 faces, 1 solid
#   tangent circle       8000 - 360*pi     = 6869.027, 7 faces, 1 solid (r = 6 from the tangent)
#   two islands          2 * 1000          = 2000,     12 faces, 2 solids
#   sketch feature       60*20*10 - 90*pi  = 11717.257, 7 faces, 1 solid
for entry in "export-sketch-equal-radius-holes 7434.513 8 1" "export-sketch-radius-unknown 7434.513 8 1" \
             "export-sketch-tangent-circle 6869.027 7 1" "export-sketch-two-islands 2000.0 12 2" \
             "export-sketch-feature-rebuild 11717.257 7 1"; do
  set -- $entry
  "$KSTEP" export "$ROOT/kstep-tests/src/test/resources/$1.kstep.kts" --out "$OUT/$1.step" --validate
  echo "== $1 (expect volume $2, $3 faces, $4 solid(s))"
  "$OUT/read_step_structure" "$OUT/$1.step" | grep -E "status|undefined|model check"
  if [ -x "$FC" ]; then
    "$FC" -c "import Part; s=Part.read('$OUT/$1.step'); print('FreeCAD', s.ShapeType, s.isValid(), round(s.Volume,3), len(s.Faces), len(s.Solids))" 2>&1 | grep FreeCAD
  fi
done
