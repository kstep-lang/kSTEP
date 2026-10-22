#!/usr/bin/env bash
# Verifies the Welle-J geometry-operation exports end to end (ADR-0029): export the example scripts,
# read them with OCCT's typed reader and, if available, FreeCAD. Writes only below build/interop/.
# Prerequisites: installDist of kstep-cli, OpenCascade (Homebrew), FreeCAD.app (optional).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/interop/geometry-operations"; mkdir -p "$OUT"
P="${OCCT_PREFIX:-$(brew --prefix opencascade)}"
c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" "$ROOT/scripts/interop/read_step_structure.cpp" \
  -o "$OUT/read_step_structure" -lTKDESTEP -lTKXSBase -lTKernel -lTKMath
KSTEP="$ROOT/kstep-cli/build/install/kstep-cli/bin/kstep-cli"
FC="${FREECADCMD:-/Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd}"
# name  expected volume / faces
for entry in "export-plate-with-hole 15497.345 7" "export-boolean-union 7000 10" "export-boolean-common 1000 6" \
             "export-boolean-cut 3000 8" "export-revolved-sleeve 4712.389 4" "export-chamfered-prism 23920 7"; do
  set -- $entry
  "$KSTEP" export "$ROOT/kstep-tests/src/test/resources/$1.kstep.kts" --out "$OUT/$1.step" --validate
  echo "== $1 (expect volume $2, $3 faces)"
  "$OUT/read_step_structure" "$OUT/$1.step" | grep -E "status|undefined|model check"
  if [ -x "$FC" ]; then
    "$FC" -c "import Part; s=Part.read('$OUT/$1.step'); print('FreeCAD', s.ShapeType, s.isValid(), round(s.Volume,3), len(s.Faces))" 2>&1 | grep FreeCAD
  fi
done
