#!/usr/bin/env bash
# Gate and end-to-end check for the export of instance colours (ADR-0044, Welle V).
#  1. GATE: OCCT's own XCAF reader (spike_instance_colour_read.cpp) reads the hand-written PRESENTATION_STYLE_BY_CONTEXT
#     pattern (scripts/interop/make_instance_colour_fixtures.py, scenarios G1-G7) as a colour of the component label.
#  2. (when kstep-cli is installed) the export of a script with styleInstance(...) is read back by OCCT and by kstep import,
#     is a pure suffix of the file without instance colours, and FreeCAD loads it as geometry.
# Writes only below build/interop/export-instance-colours/.
# Prerequisites: OpenCascade (Homebrew), python3; for part 2 installDist of kstep-cli, jq (optional), FreeCAD.app (optional).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/interop/export-instance-colours"; mkdir -p "$OUT"
FX="$ROOT/scripts/interop/fixtures/instance-colour"
KSTEP="$ROOT/kstep-cli/build/install/kstep-cli/bin/kstep-cli"
P="${OCCT_PREFIX:-$(brew --prefix opencascade)}"
FC="${FREECADCMD:-/Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd}"
LIBS=(-lTKDESTEP -lTKXCAF -lTKLCAF -lTKCAF -lTKCDF -lTKXSBase -lTKBRep -lTKTopAlgo -lTKPrim -lTKG3d -lTKGeomBase -lTKMath -lTKernel -lTKService -lTKGeomAlgo -lTKG2d -lTKBO -lTKShHealing -lTKMesh -lTKDE)
BEFORE="$(git -C "$ROOT" status --porcelain --ignored scripts)"
# the pattern kSTEP writes: the SHAPE_DEFINITION_REPRESENTATION hangs on the existing PRODUCT_DEFINITION_SHAPE('Placement') (--pds reuse)
GEN() { python3 -I "$ROOT/scripts/interop/make_instance_colour_fixtures.py" "$@" --pds reuse; }

c++ -std=c++17 -I"$P/include/opencascade" -L"$P/lib" -Wl,-rpath,"$P/lib" "$ROOT/scripts/interop/spike_instance_colour_read.cpp" \
  -o "$OUT/spike_instance_colour_read" "${LIBS[@]}" 2>/dev/null
RD="$OUT/spike_instance_colour_read"

for b in two-boxes two-boxes-part-orange two-boxes-multi-solid; do
  "$KSTEP" export "$FX/$b.kstep.kts" --out "$OUT/$b.step" >/dev/null 2>&1
done

fail=0
gate() { # name, expectation arguments..., file is $OUT/<name>.step
  local n="$1"; shift
  if "$RD" "$OUT/$n.step" "$@" >"$OUT/$n.log" 2>&1; then echo "  $n: ok"; else echo "  $n: FAILED (see $OUT/$n.log)"; fail=1; fi
}
echo "== GATE (OCCT 7.9.3 reads the hand-written pattern as an instance colour)"
GEN "$OUT/two-boxes.step" "$OUT/G1.step" --colour 2=0,0,1
gate G1 --free 1 --components 2 --volume 12000 --c1 none --c2 0,0,1 --part none --assembly none
GEN "$OUT/two-boxes.step" "$OUT/G2.step" --colour 1=1,0,0 --colour 2=0,0,1
gate G2 --free 1 --components 2 --volume 12000 --c1 1,0,0 --c2 0,0,1 --part none --assembly none
GEN "$OUT/two-boxes-part-orange.step" "$OUT/G3.step" --colour 2=0,0,1
gate G3 --free 1 --components 2 --volume 12000 --c1 none --c2 0,0,1 --part 1,0.5,0 --assembly none
GEN "$OUT/two-boxes.step" "$OUT/G4.step" --colour 2=0,0,1 --axis own
gate G4 --free 1 --components 2 --volume 12000 --c1 none --c2 0,0,1 --part none
GEN "$OUT/two-boxes-multi-solid.step" "$OUT/G5.step" --colour 2=0,0,1 --per-msb
gate G5 --free 1 --components 2 --c1 none --c2 0,0,1 --part none
GEN "$OUT/two-boxes.step" "$OUT/G6.step" --colour 1=0.7529411764705882,0.3,0.25 --colour 2=0.25,0.3,0.7529411764705882
gate G6 --free 1 --components 2 --c1 0.7529411764705882,0.3,0.25 --c2 0.25,0.3,0.7529411764705882
GEN "$OUT/two-boxes.step" "$OUT/G7.step" --colour 2=0,0,1 --no-sdr
# without the SHAPE_DEFINITION_REPRESENTATION OCCT does not see an instance colour but paints the shared product (both usages)
gate G7 --free 1 --components 2 --c1 none --c2 none --part 0,0,1
# G8: a second PRODUCT_DEFINITION_SHAPE on the usage instead of reusing the existing one (read the same by OCCT)
python3 -I "$ROOT/scripts/interop/make_instance_colour_fixtures.py" "$OUT/two-boxes.step" "$OUT/G8.step" --colour 2=0,0,1 --pds new
gate G8 --free 1 --components 2 --volume 12000 --c1 none --c2 0,0,1 --part none
if [ "$fail" = 0 ]; then echo "GATE PASSED"; else echo "GATE FAILED"; exit 1; fi

# The committed fixtures of the JVM gate test are G1, G2, G3 and G6.
for g in G1 G2 G3 G6; do cmp -s "$OUT/$g.step" "$ROOT/kstep-tests/src/test/resources/instance-colour/gate-$g.step" \
  || echo "NOTE: kstep-tests/src/test/resources/instance-colour/gate-$g.step differs from the regenerated $g.step (rerun with --refresh to update)"; done
if [ "${1:-}" = "--refresh" ]; then for g in G1 G2 G3 G6; do cp "$OUT/$g.step" "$ROOT/kstep-tests/src/test/resources/instance-colour/gate-$g.step"; done; fi

if [ ! -x "$KSTEP" ]; then echo "kstep-cli not installed (./gradlew :kstep-cli:installDist), end-to-end part skipped"; exit 0; fi
RES="$ROOT/kstep-tests/src/test/resources"
has_jq() { command -v jq >/dev/null; }

echo "== end-to-end: styleInstance(...) in a script, export, read back by OCCT and by kstep import"
"$KSTEP" export "$RES/export-instance-colour-boxes.kstep.kts" --out "$OUT/E1.step" >/dev/null 2>&1
"$RD" "$OUT/E1.step" --free 1 --components 2 --volume 12000 --c1 1,0,0 --c2 0,0,1 --part none --assembly none | tail -1
grep -c "PRESENTATION_STYLE_BY_CONTEXT" "$OUT/E1.step" | sed 's/^/  PRESENTATION_STYLE_BY_CONTEXT entities: /'
has_jq && "$KSTEP" import "$OUT/E1.step" --output json 2>/dev/null \
  | jq -c '{parts: (.parts|length), volume: .totals.volume, components: [.assemblies[0].components[]|.colour], presentation}'
"$KSTEP" import "$OUT/E1.step" 2>/dev/null | grep -E "colour"

echo "== the file without instance colours is a byte prefix of the file with them (golden placed assembly)"
"$KSTEP" export "$RES/export-golden-placed-assembly.kstep.kts" --out "$OUT/E2-plain.step" >/dev/null 2>&1
"$KSTEP" export "$RES/export-instance-colour-two.kstep.kts" --out "$OUT/E2-coloured.step" >/dev/null 2>&1
cmp "$OUT/E2-plain.step" "$RES/export-golden-placed-assembly.step" && echo "  plain export identical to the golden"
python3 -I - "$OUT/E2-plain.step" "$OUT/E2-coloured.step" <<'PY'
import sys
plain, col = (open(p, encoding="utf-8").read() for p in sys.argv[1:3])
cut = plain.rindex("ENDSEC;")
assert col.startswith(plain[:cut]), "the plain file is not a prefix"
print("  prefix identity holds; appended lines:", col[cut:col.rindex("ENDSEC;")].count("\n"))
PY
"$RD" "$OUT/E2-coloured.step" --free 1 --components 4 --c3 1,0,0 --c4 0,0,1 --c1 none --c2 none | tail -1

echo "== error cases (code in text and JSON, no file written)"
sed 's/    styleInstance(u2, StepColour.rgb(0.0, 0.0, 1.0))/    styleInstance(u2, StepColour.rgba(0.0, 0.0, 1.0, 0.5))/' "$RES/export-instance-colour-boxes.kstep.kts" > "$OUT/bad-alpha.kstep.kts"
sed 's/    styleInstance(u2, StepColour.rgb(0.0, 0.0, 1.0))/    styleInstance(u1, StepColour.rgb(0.0, 0.0, 1.0))/' "$RES/export-instance-colour-boxes.kstep.kts" > "$OUT/twice.kstep.kts"
sed 's/    roots(u1, u2)/    roots(u1)/' "$RES/export-instance-colour-boxes.kstep.kts" > "$OUT/nonroot.kstep.kts"
for f in bad-alpha twice nonroot; do
  set +e; "$KSTEP" export "$OUT/$f.kstep.kts" --out "$OUT/$f.step" --output json 2>/dev/null | { has_jq && jq -r '"  '"$f"': \(.code)"' || cat; }; set -e
  test ! -e "$OUT/$f.step" || { echo "ERROR: $f wrote a file"; exit 1; }
done

echo "== FreeCAD reads the file as geometry (2 objects, volume 12000); colours are not readable headless"
if [ -x "$FC" ]; then
  PYTHONDONTWRITEBYTECODE=1 STEP_FILE="$OUT/E1.step" "$FC" "$ROOT/scripts/interop/freecad_read_styles.py" 2>&1 | grep -E "^(objects|  )" || true
else
  echo "FreeCAD not found, skipped"
fi

AFTER="$(git -C "$ROOT" status --porcelain --ignored scripts)"
if [ "$BEFORE" != "$AFTER" ]; then echo "ERROR: the run left files below scripts/:"; diff <(echo "$BEFORE") <(echo "$AFTER") || true; exit 1; fi
echo "OK: nothing written below scripts/"
