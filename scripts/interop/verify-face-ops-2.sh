#!/usr/bin/env bash
# Verifies the Welle-Q2 pocket / hole / draft / thicken exports end to end (ADR-0040): export the example scripts, read them
# with OCCT's typed reader and, if available, FreeCAD, and compare volume, face count, solid count and bounding box with the
# hand-computed values below. Exits 1 on any deviation. Writes only below build/interop/face-ops-2/.
# Prerequisites: installDist of kstep-cli, OpenCascade (Homebrew), FreeCAD.app (optional).
#   --spike     additionally builds and runs scripts/interop/spike_face_ops_history.cpp
#   --baseline  additionally exports the pre-Q2 example scripts with the CLI of a master worktree (git worktree, built with
#               installDist under build/interop/face-ops-2/baseline) and with this tree, and compares the STEP files after
#               normalising the time stamp in FILE_NAME: files that use none of the new features must be byte-identical.
#               BASE_REF (default c615c23) selects the baseline commit.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/interop/face-ops-2"; mkdir -p "$OUT"
P="${OCCT_PREFIX:-$(brew --prefix opencascade)}"
c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" "$ROOT/scripts/interop/read_step_structure.cpp" \
  -o "$OUT/read_step_structure" -lTKDESTEP -lTKXSBase -lTKernel -lTKMath
if [ "${1:-}" = "--spike" ]; then
  (cd "$OUT" && c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" \
    "$ROOT/scripts/interop/spike_face_ops_history.cpp" -o spike_face_ops_history \
    -lTKDESTEP -lTKXSBase -lTKernel -lTKMath -lTKBRep -lTKTopAlgo -lTKPrim -lTKBO -lTKGeomBase -lTKG3d -lTKGeomAlgo \
    -lTKShHealing -lTKOffset -lTKFillet && ./spike_face_ops_history)
fi
KSTEP="$ROOT/kstep-cli/build/install/kstep-cli/bin/kstep-cli"
FC="${FREECADCMD:-/Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd}"
FAIL=0
# name | volume | faces | solids | xmin xmax ymin ymax zmin zmax
TABLE='export-pocket-rect|23500|11|1|0 40 0 30 0 20
export-pocket-hole-blind|23698.4071|8|1|0 40 0 30 0 20
export-pocket-hole-through|22994.6904|7|1|0 40 0 30 0 20
export-pocket-feature-rebuild|35500|11|1|0 40 0 30 0 30
export-draft-box|21631.9629|6|1|0 40 0 30 0 20
export-thicken-face|26400|6|1|0 40 0 30 0 22'
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

if [ "${1:-}" = "--baseline" ] || [ "${2:-}" = "--baseline" ]; then
  BASE_REF="${BASE_REF:-c615c23}"
  WT="$OUT/baseline"
  if [ ! -x "$WT/kstep-cli/build/install/kstep-cli/bin/kstep-cli" ]; then
    [ -d "$WT" ] || git -C "$ROOT" worktree add --detach "$WT" "$BASE_REF"
    (cd "$WT" && ./gradlew -Dorg.gradle.daemon.registry.base="$OUT/daemon-reg" :kstep-cli:installDist --no-build-cache >/dev/null)
  fi
  BASEKSTEP="$WT/kstep-cli/build/install/kstep-cli/bin/kstep-cli"
  mkdir -p "$OUT/regress/base" "$OUT/regress/new"
  # Scripts of the baseline commit that use none of the Q2 features.
  for script in "$WT"/kstep-tests/src/test/resources/export-*.kstep.kts; do
    name="$(basename "$script" .kstep.kts)"
    "$BASEKSTEP" export "$script" --out "$OUT/regress/base/$name.step" >/dev/null 2>&1 || { echo "   (skip $name: baseline export failed)"; continue; }
    "$KSTEP" export "$script" --out "$OUT/regress/new/$name.step" >/dev/null 2>&1 || { echo "FAIL regression: $name exports with the baseline but not with this tree"; FAIL=1; continue; }
    # FILE_NAME carries a time stamp; normalise it before comparing.
    sed -E "s/'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.+Z-]*'/'TS'/" "$OUT/regress/base/$name.step" > "$OUT/regress/base/$name.norm"
    sed -E "s/'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.+Z-]*'/'TS'/" "$OUT/regress/new/$name.step" > "$OUT/regress/new/$name.norm"
    if cmp -s "$OUT/regress/base/$name.norm" "$OUT/regress/new/$name.norm"; then echo "   identical: $name"; else echo "FAIL regression: $name differs from the baseline"; FAIL=1; fi
  done
fi
exit $FAIL
