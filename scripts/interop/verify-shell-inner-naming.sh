#!/usr/bin/env bash
# Verifies persistent naming of the inner wall of an INWARD shell (ADR-0041, Welle T). Writes only below
# build/interop/shell-inner-naming/.
#   --spike   build and run the OCCT history spike (scripts/interop/spike_shell_history.cpp, `--inner-naming`)
#   (default) export the two positive example scripts, read each file with OCCT's typed reader and FreeCAD and compare
#             volume / faces with the hand-computed values; the failure script must exit non-zero with KSTEP-S-011;
#             the unnamed shell exports must be byte-identical to an export from master (header timestamps ignored).
# Prerequisites: installDist of kstep-cli, OpenCascade (Homebrew), FreeCAD.app (optional).
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/interop/shell-inner-naming"; mkdir -p "$OUT"
P="${OCCT_PREFIX:-$(brew --prefix opencascade)}"
if [ "${1:-}" = "--spike" ]; then
  c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" "$ROOT/scripts/interop/spike_shell_history.cpp" \
    -o "$OUT/spike_shell_history" \
    -lTKDESTEP -lTKXSBase -lTKernel -lTKMath -lTKBRep -lTKTopAlgo -lTKPrim -lTKBO -lTKGeomBase -lTKG3d -lTKGeomAlgo \
    -lTKShHealing -lTKOffset -lTKFillet
  "$OUT/spike_shell_history" --inner-naming | tee "$OUT/spike.txt"
  exit 0
fi
c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" "$ROOT/scripts/interop/read_step_structure.cpp" \
  -o "$OUT/read_step_structure" -lTKDESTEP -lTKXSBase -lTKernel -lTKMath
KSTEP="$ROOT/kstep-cli/build/install/kstep-cli/bin/kstep-cli"
FC="${FREECADCMD:-/Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd}"
FAIL=0
# name volume faces
#   box 40x30x20, top open, wall 2, inner vertical edges rounded r=1   7167.451, 15 faces
#   the same after the box becomes 40x30x30                           9816.035, 15 faces
for entry in "export-shell-inner-fillet 7167.451 15" "export-shell-inner-fillet-rebuild 9816.035 15"; do
  set -- $entry
  "$KSTEP" export "$ROOT/kstep-tests/src/test/resources/$1.kstep.kts" --out "$OUT/$1.step" --validate
  echo "== $1 (expect volume $2, $3 faces)"
  "$OUT/read_step_structure" "$OUT/$1.step" | grep -E "status|undefined|model check"
  if [ -x "$FC" ]; then
    LINE="$("$FC" -c "import Part; s=Part.read('$OUT/$1.step'); print('FreeCAD', s.ShapeType, s.isValid(), round(s.Volume,3), len(s.Faces), s.BoundBox)" 2>&1 | grep FreeCAD)"
    echo "$LINE"
    case "$LINE" in *"True $2 $3"*) ;; *) echo "DEVIATION: $1"; FAIL=1;; esac
  fi
done
echo "== hello-shell-inner-unresolved (expect exit != 0 and KSTEP-S-011)"
if "$KSTEP" export "$ROOT/kstep-tests/src/test/resources/hello-shell-inner-unresolved.kstep.kts" --out "$OUT/unresolved.step" >"$OUT/unresolved.log" 2>&1; then
  echo "DEVIATION: the failure script exported successfully"; FAIL=1
elif grep -q "KSTEP-S-011" "$OUT/unresolved.log"; then
  grep -E "no inner image|KSTEP-S-011" "$OUT/unresolved.log" | head -3
else
  echo "DEVIATION: no KSTEP-S-011 in the output"; cat "$OUT/unresolved.log"; FAIL=1
fi
# Byte identity of the unnamed shell exports against master (built in a worktree below build/).
if [ -n "${MASTER_KSTEP:-}" ] && [ -x "$MASTER_KSTEP" ]; then
  for name in export-shell-box export-shell-feature-rebuild export-shell-box-outward; do
    "$KSTEP" export "$ROOT/kstep-tests/src/test/resources/$name.kstep.kts" --out "$OUT/$name.new.step" >/dev/null
    "$MASTER_KSTEP" export "$ROOT/kstep-tests/src/test/resources/$name.kstep.kts" --out "$OUT/$name.master.step" >/dev/null
    if diff <(grep -v "^FILE_NAME\|^/\* time_stamp" "$OUT/$name.new.step") <(grep -v "^FILE_NAME\|^/\* time_stamp" "$OUT/$name.master.step") >/dev/null; then
      echo "== $name identical to master"
    else
      echo "DEVIATION: $name differs from master"; FAIL=1
    fi
  done
else
  echo "(byte-identity check skipped: set MASTER_KSTEP to a kstep-cli built from master)"
fi
exit $FAIL
