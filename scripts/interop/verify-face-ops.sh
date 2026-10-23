#!/usr/bin/env bash
# Verifies the Welle-Q shell / nearest exports end to end (ADR-0037): export the example scripts, read them with
# OCCT's typed reader and, if available, FreeCAD, and compare volume, face count, solid count and bounding box
# with the hand-computed values below. Exits 1 on any deviation. Writes only below build/interop/face-ops/.
# Prerequisites: installDist of kstep-cli, OpenCascade (Homebrew), FreeCAD.app (optional).
# `--spike` additionally builds and runs scripts/interop/spike_shell_history.cpp.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/interop/face-ops"; mkdir -p "$OUT"
P="${OCCT_PREFIX:-$(brew --prefix opencascade)}"
c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" "$ROOT/scripts/interop/read_step_structure.cpp" \
  -o "$OUT/read_step_structure" -lTKDESTEP -lTKXSBase -lTKernel -lTKMath
if [ "${1:-}" = "--spike" ]; then
  (cd "$OUT" && c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" \
    "$ROOT/scripts/interop/spike_shell_history.cpp" -o spike_shell_history \
    -lTKDESTEP -lTKXSBase -lTKernel -lTKMath -lTKBRep -lTKTopAlgo -lTKPrim -lTKBO -lTKGeomBase -lTKG3d -lTKGeomAlgo \
    -lTKShHealing -lTKOffset -lTKFillet && ./spike_shell_history)
fi
KSTEP="$ROOT/kstep-cli/build/install/kstep-cli/bin/kstep-cli"
FC="${FREECADCMD:-/Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd}"
FAIL=0
# name | volume | faces | solids | xmin xmax ymin ymax zmin zmax
TABLE='export-shell-box|7152|11|1|0 40 0 30 0 20
export-shell-box-outward|8912|11|1|-2 42 -2 32 -2 20
export-shell-cylinder|1448.2742|5|1|-10 10 -10 10 0 20
export-shell-feature-rebuild|9792|11|1|0 40 0 30 0 30
export-nearest-fillet|5948.4956|8|1|0 10 0 20 0 30'
while IFS='|' read -r name vol faces solids box; do
  "$KSTEP" export "$ROOT/kstep-tests/src/test/resources/$name.kstep.kts" --out "$OUT/$name.step" --validate >/dev/null
  echo "== $name (expect volume $vol, $faces faces, $solids solid(s), box $box)"
  "$OUT/read_step_structure" "$OUT/$name.step" | grep -E "status|undefined|model check" || true
  grep -c MANIFOLD_SOLID_BREP "$OUT/$name.step" | sed 's/^/   MANIFOLD_SOLID_BREP entities: /'
  if [ -x "$FC" ]; then
    res=$("$FC" -c "import Part; s=Part.read('$OUT/$name.step'); b=s.BoundBox; print('FreeCAD', s.ShapeType, s.isValid(), round(s.Volume,4), len(s.Faces), len(s.Solids), round(b.XMin,4), round(b.XMax,4), round(b.YMin,4), round(b.YMax,4), round(b.ZMin,4), round(b.ZMax,4))" 2>&1 | grep '^FreeCAD' || true)
    echo "   $res"
    python3 - "$res" "$vol" "$faces" "$solids" "$box" <<'PY' || FAIL=1
import sys
res, vol, faces, solids, box = sys.argv[1:]
t = res.split()
if len(t) != 12:
    sys.exit("   FAIL: no FreeCAD result")
ok = t[1] in ("Solid", "Compound") and t[2] == "True" and abs(float(t[3]) - float(vol)) <= 1e-3
ok = ok and int(t[4]) == int(faces) and int(t[5]) == int(solids)
ok = ok and all(abs(float(a) - float(b)) <= 1e-4 for a, b in zip(t[6:], box.split()))
if not ok:
    sys.exit("   FAIL: deviation from the hand-computed values")
print("   OK")
PY
  fi
done <<< "$TABLE"
exit $FAIL
