# Writes a STEP file with FreeCAD (ADR-0038, Welle R): a smooth (not ruled) loft solid through three circles, radius 10 at
# z=0, radius 8 at z=10 and radius 5 at z=20, labelled "FreeCADLoft". (Two circles alone give a plain cone.) The side surface is a B-spline: kSTEP has no operation that
# produces NURBS, so this is the only way to feed a freeform surface into the import path.
#
#   PYTHONDONTWRITEBYTECODE=1 STEP_FILE=out.step freecadcmd scripts/interop/freecad_make_loft.py
#
# Exactly one mode per process (see freecad_make_box.py). Writes only the path given in STEP_FILE.
import os

import FreeCAD  # noqa: E402
import Import  # noqa: E402
import Part  # noqa: E402

path = os.environ["STEP_FILE"]
doc = FreeCAD.newDocument("loft")
bottom = Part.Wire(Part.makeCircle(10, FreeCAD.Vector(0, 0, 0)))
middle = Part.Wire(Part.makeCircle(8, FreeCAD.Vector(0, 0, 10)))
top = Part.Wire(Part.makeCircle(5, FreeCAD.Vector(0, 0, 20)))
solid = Part.makeLoft([bottom, middle, top], True, False)
obj = doc.addObject("Part::Feature", "FreeCADLoft")
obj.Shape = solid
obj.Label = "FreeCADLoft"
doc.recompute()
Import.export([obj], path)
print("FreeCAD", ".".join(FreeCAD.Version()[:3]), "wrote", path, "volume", solid.Volume)
