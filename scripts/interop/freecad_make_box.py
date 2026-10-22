# Writes a STEP file with FreeCAD (ADR-0030, Welle K): one Part::Box 10 x 20 x 30 labelled "FreeCADBox".
# Used to prove that kSTEP can read a file written by a foreign CAD tool.
#
#   STEP_FILE=out.step freecadcmd scripts/interop/freecad_make_box.py
#
# Exactly one mode per process (never mix Part.read and Import.* in one FreeCAD process, see
# freecad_assembly_check.py). Writes only the path given in STEP_FILE.
import os

import FreeCAD  # noqa: E402
import Import  # noqa: E402

path = os.environ["STEP_FILE"]
doc = FreeCAD.newDocument("box")
box = doc.addObject("Part::Box", "FreeCADBox")
box.Length = 10
box.Width = 20
box.Height = 30
box.Label = "FreeCADBox"
doc.recompute()
Import.export([box], path)
print("FreeCAD", ".".join(FreeCAD.Version()[:3]), "wrote", path)
