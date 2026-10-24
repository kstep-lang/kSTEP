# FreeCAD headless check of a STEP file with colours and layers (ADR-0039). Run with freecadcmd:
#
#   STEP_FILE=out.step freecadcmd scripts/interop/freecad_read_styles.py
#
# Prints plain text only, writes nothing. Volume, solid count and bounding box are always reported. Colours are only
# readable through the GUI importer (ImportGui) or through view objects; both are absent in a headless freecadcmd, so
# the colour line says "not available headless" instead of guessing. The ADR records this as a limit of the
# FreeCAD check, not of the export.
import os

import FreeCAD  # noqa: E402
import Import  # noqa: E402

path = os.environ["STEP_FILE"]
print("FreeCAD", ".".join(FreeCAD.Version()[:3]))
doc = FreeCAD.newDocument("styles")
Import.insert(path, doc.Name)
shapes = [o for o in doc.Objects if hasattr(o, "Shape") and not o.Shape.isNull() and o.Shape.Solids]
total = sum(s.Shape.Volume for s in shapes)
solids = sum(len(s.Shape.Solids) for s in shapes)
print("objects with solids:", len(shapes), "solids:", solids, "volume: %.6f" % total)
for o in shapes:
    b = o.Shape.BoundBox
    print("  %s volume %.6f bbox [%.3f,%.3f]x[%.3f,%.3f]x[%.3f,%.3f]" % (
        o.Label, o.Shape.Volume, b.XMin, b.XMax, b.YMin, b.YMax, b.ZMin, b.ZMax))
colour_lines = 0
for o in doc.Objects:
    view = getattr(o, "ViewObject", None)
    if view is not None and hasattr(view, "ShapeColor"):
        print("  colour %s %s" % (o.Label, tuple(round(c, 6) for c in view.ShapeColor[:3])))
        colour_lines += 1
if colour_lines == 0:
    print("colour: not available headless (no view objects)")
