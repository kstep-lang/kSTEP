#!/usr/bin/env bash
# Verifies the Welle-L exports end to end (ADR-0031): export the sketch/selection example scripts, read each file
# with OCCT's typed reader (read_step_structure) and, if available, FreeCAD, and compare volume and face count with
# the hand-computed values. Writes only below build/interop/ (never next to the scripts).
# Prerequisites: installDist of kstep-cli, OpenCascade (Homebrew), FreeCAD.app (optional).
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/interop/sketch-selection"; mkdir -p "$OUT"
P="${OCCT_PREFIX:-$(brew --prefix opencascade)}"
c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" "$ROOT/scripts/interop/read_step_structure.cpp" \
  -o "$OUT/read_step_structure" -lTKDESTEP -lTKXSBase -lTKernel -lTKMath
KSTEP="$ROOT/kstep-cli/build/install/kstep-cli/bin/kstep-cli"
FC="${FREECADCMD:-/Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd}"
# name  expected volume / faces
#   sketch plate        8000 - 90*pi                          = 7717.257, 7 faces
#   box fillet          16000 - 4*(1 - pi/4)*2^2*10           = 15965.664, 10 faces
#   box chamfer         16000 - (1600 - (1600+1444+1520)/3)   = 15921.333, 10 faces
#   pocket tracked      16000 - 1000 - 4*(1 - pi/4)*2^2*10    = 14965.664, 14 faces
for entry in "export-sketch-plate-with-hole 7717.257 7" "export-box-fillet-z-edges 15965.664 10" \
             "export-box-chamfer-top-edges 15921.333 10" "export-pocket-tracked-fillet 14965.664 14"; do
  set -- $entry
  "$KSTEP" export "$ROOT/kstep-tests/src/test/resources/$1.kstep.kts" --out "$OUT/$1.step" --validate
  echo "== $1 (expect volume $2, $3 faces)"
  "$OUT/read_step_structure" "$OUT/$1.step" | grep -E "status|undefined|model check"
  if [ -x "$FC" ]; then
    "$FC" -c "import Part; s=Part.read('$OUT/$1.step'); print('FreeCAD', s.ShapeType, s.isValid(), round(s.Volume,3), len(s.Faces))" 2>&1 | grep FreeCAD
  fi
done
