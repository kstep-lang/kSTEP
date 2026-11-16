#!/usr/bin/env bash
# Gate for transparent instance colours (ADR-0044 amendment, Welle Y).
# OCCT's own XCAF reader and presentation logic (spike_subassembly_colour_read.cpp, which also reads flat assemblies) read the
# hand-written pattern (make_instance_colour_fixtures.py, 4th colour component = alpha, scenarios Y1-Y7) and tell what each
# component, part and leaf looks like. Scenario Y0 (informative) shows what STEPCAFControl_Writer itself writes.
# Writes only below build/interop/export-instance-transparency/. Prerequisites: OpenCascade (Homebrew), python3, and
# ./gradlew :kstep-cli:installDist (the base fixtures are exported by kstep-cli).
#   verify-export-instance-transparency.sh [--refresh]   --refresh rewrites the committed JVM-gate fixtures
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/interop/export-instance-transparency"; mkdir -p "$OUT"
FX="$ROOT/scripts/interop/fixtures/instance-colour"
KSTEP="$ROOT/kstep-cli/build/install/kstep-cli/bin/kstep-cli"
P="${OCCT_PREFIX:-$(brew --prefix opencascade)}"
LIBS=(-lTKDESTEP -lTKXCAF -lTKLCAF -lTKCAF -lTKCDF -lTKXSBase -lTKBRep -lTKTopAlgo -lTKPrim -lTKG3d -lTKGeomBase -lTKMath -lTKernel -lTKService -lTKGeomAlgo -lTKG2d -lTKBO -lTKShHealing -lTKMesh -lTKDE)
BEFORE="$(git -C "$ROOT" status --porcelain --ignored scripts)"
[ -x "$KSTEP" ] || { echo "kstep-cli is not installed (./gradlew :kstep-cli:installDist)"; exit 2; }
export PYTHONDONTWRITEBYTECODE=1
GEN() { python3 -I "$ROOT/scripts/interop/make_instance_colour_fixtures.py" "$@" --pds reuse; }
CC() { c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" "$@" 2>/dev/null; }

CC "$ROOT/scripts/interop/spike_subassembly_colour_read.cpp" -o "$OUT/spike_subassembly_colour_read" "${LIBS[@]}"
CC "$ROOT/scripts/interop/spike_export_styles.cpp" -o "$OUT/spike_export_styles" "${LIBS[@]}"
RD="$OUT/spike_subassembly_colour_read"
for b in two-boxes two-boxes-part-orange two-boxes-part-orange-alpha nested-boxes; do
  "$KSTEP" export "$FX/$b.kstep.kts" --out "$OUT/$b.step" >/dev/null 2>&1
done

fail=0
gate() { # name, expectation arguments..., file is $OUT/<name>.step
  local n="$1"; shift
  if "$RD" "$OUT/$n.step" "$@" >"$OUT/$n.log" 2>&1; then echo "  $n: ok"; else echo "  $n: FAILED (see $OUT/$n.log)"; fail=1; fi
}
BLUE=0,0,1; RED=1,0,0; ORANGE=1,0.5,0
echo "== Y0 (informative): what OCCT's own writer produces for a translucent component colour"
"$OUT/spike_export_styles" instance-alpha 2 "$OUT/Y0.step" >/dev/null
"$RD" "$OUT/Y0.step" --free 1 --components 2 --volume 12000 --comp 2=0,0,1,0.5 --comp 1=none >"$OUT/Y0.log" 2>&1 && echo "  Y0: ok" || { echo "  Y0: differs (informative, see $OUT/Y0.log)"; }
grep -E "STYLED_ITEM|SURFACE_STYLE_TRANSPARENT|SURFACE_STYLE_RENDERING|PRESENTATION_STYLE_BY_CONTEXT" "$OUT/Y0.step" | cut -c1-120 | sed 's/^/     /'

echo "== GATE (OCCT 7.9.3 reads the hand-written translucent pattern as an instance colour of the component)"
GEN "$OUT/two-boxes.step" "$OUT/Y1.step" --colour 2=0,0,1,0.5
gate Y1 --free 1 --components 2 --volume 12000 --comp 2=0,0,1,0.5 --comp 1=none --part Box=none --leaf 2=0,0,1,0.5 --leaf 1=none
GEN "$OUT/two-boxes.step" "$OUT/Y2.step" --colour 1=1,0,0 --colour 2=0,0,1,0.25
gate Y2 --free 1 --components 2 --volume 12000 --comp 1=1,0,0,1 --comp 2=0,0,1,0.25 --leaf 1=1,0,0,1 --leaf 2=0,0,1,0.25
GEN "$OUT/two-boxes-part-orange.step" "$OUT/Y3.step" --colour 2=0,0,1,0.5
# the part colour stays opaque (no alpha leak), the instance colour wins on occurrence 2
gate Y3 --free 1 --components 2 --volume 12000 --comp 2=0,0,1,0.5 --part Box=1,0.5,0,1 --leaf 1=1,0.5,0,1 --leaf 2=0,0,1,0.5
GEN "$OUT/two-boxes-part-orange-alpha.step" "$OUT/Y3b.step" --colour 2=0,0,1
# precedence incl. alpha: the winning source (opaque instance colour) supplies its whole colour, the part alpha does not leak
gate Y3b --free 1 --components 2 --volume 12000 --comp 2=0,0,1,1 --part Box=1,0.5,0,0.5 --leaf 1=1,0.5,0,0.5 --leaf 2=0,0,1,1
GEN "$OUT/nested-boxes.step" "$OUT/Y4.step" --colour 2=0,0,1,0.5 --leaf-items all
gate Y4 --free 1 --components 6 --volume 24000 --comp 2=0,0,1,0.5 --comp 1=none --part Box=none --leaf 2/1=0,0,1,0.5 --leaf 2/2=0,0,1,0.5 --leaf 1/1=none --leaf 1/2=none
GEN "$OUT/nested-boxes.step" "$OUT/Y5.step" --colour 2=0,0,1,0.25 --colour 3=1,0,0 --leaf-items all
# precedence: inner occurrence colour (usage 3 = component 1 of SUB) beats the outer one, alpha included
gate Y5 --free 1 --components 6 --volume 24000 --leaf 1/1=1,0,0,1 --leaf 1/2=none --leaf 2/1=1,0,0,1 --leaf 2/2=0,0,1,0.25
for a in 0.0 0.7529411764705882 0.3 0.999999; do
  n=Y6-$a
  GEN "$OUT/two-boxes.step" "$OUT/$n.step" --colour 2=0,0,1,$a
  gate "$n" --free 1 --components 2 --comp 2=0,0,1,$a --leaf 2=0,0,1,$a --leaf 1=none
done
cp "$OUT/Y6-0.7529411764705882.step" "$OUT/Y6.step"
python3 -I "$ROOT/scripts/interop/make_instance_colour_fixtures.py" "$OUT/two-boxes.step" "$OUT/Y7.step" --colour 2=0,0,1,0.5 --pds reuse --no-sdr
# negative: without the SHAPE_DEFINITION_REPRESENTATION OCCT paints the shared product (and its alpha leaks onto both usages)
echo "  Y7 (negative, informative):"; "$RD" "$OUT/Y7.step" --comp 2=none --leaf 1=0,0,1,0.5 --leaf 2=0,0,1,0.5 --part Box=0,0,1,0.5 >"$OUT/Y7.log" 2>&1 && echo "    ok: OCCT repaints the shared part, the SDR stays mandatory" || { echo "    differs (see $OUT/Y7.log)"; fail=1; }
if [ "$fail" = 0 ]; then echo "GATE PASSED"; else echo "GATE FAILED"; exit 1; fi

# Tamper fixtures for the hostile-input tests (not part of the gate)
for t in neg:-0.5 gt1:1.5 huge:1.E400; do
  GEN "$OUT/two-boxes.step" "$OUT/tamper-alpha-${t%%:*}.step" --colour 2=0,0,1,0.5 --raw-t "${t#*:}"
done

RES="$ROOT/kstep-tests/src/test/resources/instance-colour"
for g in Y1 Y3 Y3b Y4 Y5 Y6; do
  if [ "${1:-}" = "--refresh" ]; then mkdir -p "$RES"; cp "$OUT/$g.step" "$RES/gate-$g.step"
  elif [ -f "$RES/gate-$g.step" ]; then cmp -s "$OUT/$g.step" "$RES/gate-$g.step" || echo "NOTE: $RES/gate-$g.step differs from the regenerated $g.step (rerun with --refresh)"; fi
done
if [ "${1:-}" = "--refresh" ]; then for t in neg gt1 huge; do cp "$OUT/tamper-alpha-$t.step" "$RES/tamper-alpha-$t.step"; done; fi

echo "== FreeCAD reads Y1 and Y4 as geometry"
FC="${FREECADCMD:-/Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd}"
if [ -x "$FC" ]; then
  for y in Y1 Y4; do PYTHONDONTWRITEBYTECODE=1 STEP_FILE="$OUT/$y.step" "$FC" "$ROOT/scripts/interop/freecad_read_styles.py" 2>&1 | grep -E "^(objects|  )" | head -4 || true; done
else echo "  FreeCAD not installed, skipped"; fi

AFTER="$(git -C "$ROOT" status --porcelain --ignored scripts)"
[ "$BEFORE" = "$AFTER" ] || { echo "WARNING: git status of scripts/ changed"; echo "$AFTER"; }
echo "done"
