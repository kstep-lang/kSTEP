#!/usr/bin/env bash
# Verifies a placed-assembly export end to end (ADR-0028): export, OCCT typed reader, OCCT XCAF
# tree, FreeCAD (two separate sessions). Writes only below build/interop/.
# Prerequisites: installDist of kstep-cli, OpenCascade (Homebrew), FreeCAD.app (optional).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/interop"; mkdir -p "$OUT"
P="${OCCT_PREFIX:-$(brew --prefix opencascade)}"
LIBS="-lTKDESTEP -lTKXCAF -lTKLCAF -lTKCAF -lTKCDF -lTKXSBase -lTKBRep -lTKTopAlgo -lTKPrim -lTKG3d -lTKGeomBase -lTKMath -lTKernel -lTKService"
c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" "$ROOT/scripts/interop/dump_xcaf_tree.cpp" -o "$OUT/dump_xcaf_tree" $LIBS
c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" "$ROOT/scripts/interop/read_step_structure.cpp" -o "$OUT/read_step_structure" -lTKDESTEP -lTKXSBase -lTKernel -lTKMath
KSTEP="$ROOT/kstep-cli/build/install/kstep-cli/bin/kstep-cli"
"$KSTEP" export "$ROOT/kstep-tests/src/test/resources/export-placed-assembly.kstep.kts" --out "$OUT/placed.step" --validate
echo "== OCCT typed reader"; "$OUT/read_step_structure" "$OUT/placed.step" | grep -E "status|CDSR|item_|undefined|model check"
echo "== XCAF tree (expect 4 components, volume 17384, bbox [0,40]x[0,40]x[0,22])"
"$OUT/dump_xcaf_tree" dump "$OUT/placed.step" | grep -E "name=|global bbox|SUMMARY"
FC="${FREECADCMD:-/Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd}"
if [ -x "$FC" ]; then
  for mode in open part; do
    echo "== FreeCAD $mode"
    STEP_FILE="$OUT/placed.step" STEP_MODE=$mode "$FC" "$ROOT/scripts/interop/freecad_assembly_check.py" 2>&1 | grep -vE "^$|App::(Line|Plane|Origin|Point)"
  done
else
  echo "FreeCAD not found, skipped"
fi
