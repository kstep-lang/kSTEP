#!/usr/bin/env bash
# Verifies placement-aware GLB rendering end to end (ADR-0035): renders the placed-assembly fixture with the
# installed CLI and compares the GLB position bounds with the hand-calculated bounding box.
# Prerequisite: installDist of kstep-cli. Writes only below build/interop/.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/interop"; mkdir -p "$OUT"
KSTEP="$ROOT/kstep-cli/build/install/kstep-cli/bin/kstep-cli"
"$KSTEP" render "$ROOT/kstep-tests/src/test/resources/export-placed-assembly.kstep.kts" -f glb --out "$OUT/placed.glb"
python3 -I "$ROOT/scripts/interop/glb_bounds.py" "$OUT/placed.glb" \
  --expect-min 0 0 0 --expect-max 40 40 22
